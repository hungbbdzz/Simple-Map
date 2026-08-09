package com.velorise.simplemap.client;

import com.velorise.simplemap.client.surface.SurfacePublicationService;
import com.velorise.simplemap.client.surface.SurfaceResidencyService;
import com.velorise.simplemap.client.gpu.MapGpuPageTableService;
import com.velorise.simplemap.client.gpu.PageTableEntry;
import com.velorise.simplemap.client.gpu.TileKey;
import com.velorise.simplemap.client.gpu.UploadCommand;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.SurfaceLeafAtlas;
import com.velorise.simplemap.client.cave.SurfaceLodTree;
import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import com.velorise.simplemap.client.pipeline.MapWorkKey;
import com.velorise.simplemap.client.pipeline.MapWorkStage;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.MapColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/** Render-thread-owned GPU cache for surface region textures. */
public class MapTextureManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapTextureManager INSTANCE = new MapTextureManager();
    private static final int MAX_TEXTURE_REGIONS =
            MapMemoryBudgetPolicy.legacyRegionLimit();
    private static final int MAX_TEXTURE_PAGES = Math.max(
            SurfaceLeafAtlas.SLOT_COUNT,
            Math.min(3072, SurfaceLeafAtlas.SLOT_COUNT * 2));
    private static final int MAX_VISIBLE_HISTORY = MAX_TEXTURE_REGIONS * 4;
    private static final long VISIBLE_TTL_MS = 2_000L;
    /** Wait for a brief quiet period before rebuilding a 512x512 region. */
    private static final long DIRTY_QUIET_NANOS = 60_000_000L;
    /** Publish progressive snapshots frequently while a region is still scanning. */
    private static final long DIRTY_MAX_WAIT_NANOS = 350_000_000L;
    private static final long PAGE_DIRTY_QUIET_NANOS = 30_000_000L;
    private static final long PAGE_DIRTY_MAX_WAIT_NANOS = 160_000_000L;
    /** Visible terrain edits should appear within roughly one rendered frame. */
    /** Offscreen dirty is a reconstructable revision marker, not retained work. */
    private static final long HISTORICAL_DIRTY_RETENTION_NANOS = 2_000_000_000L;
    /** Brief adaptive sweep after palette/profile changes. */
    private static final long STYLE_REFRESH_WINDOW_MS = 2_500L;
    /** Bounded ordered fullscreen window. Admission adapts to measured CPU pressure. */
    private static final int FULLSCREEN_ACTIVE_WINDOW_MIN = 6;
    private static final int FULLSCREEN_ACTIVE_WINDOW_MAX = 48;
    private static final int[] CAPTURE_WAKE_BATCH_SIZES = { 1, 2, 4 };

    private final Map<String, RegionTextureInfo> textureCache = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, PageTextureInfo> pageCache = new LinkedHashMap<>(128, 0.75f, true);
    private final SurfaceLeafAtlas leafAtlas = new SurfaceLeafAtlas();
    private final SurfacePageBufferPool pageBufferPool = SurfacePageBufferPool.getInstance();
    /** O(1) exact-leaf subtree residency counts, indexed by branch level. */
    private final List<Map<Long, Integer>> residentPageCountsByLevel =
            new ArrayList<>(SurfaceLodTree.MAX_LEVEL + 1);
    private long observedLeafStorageGeneration = Long.MIN_VALUE;
    private final Set<String> dirtyTextures = new LinkedHashSet<>();
    private final Set<String> dirtyPages = new LinkedHashSet<>();
    /**
     * Fair bounded traversal order for dirty exact leaves. The old selector walked
     * the complete dirtyPages set on every render callback; during exploration that
     * set regularly contains thousands of entries although only 1-4 can be
     * published. Keep one queue occurrence per key and rotate only a bounded slice.
     */
    private final ArrayDeque<String> dirtyPageOrder = new ArrayDeque<>();
    private final Set<String> dirtyPageQueued = new HashSet<>();
    private final Map<String, Long> pageRevisions = new HashMap<>();
    /** 16x16 exact-leaf parts which actually need restyling/publication. */
    private final Map<String, Integer> dirtyPageSubtileMasks = new HashMap<>();
    private final Map<String, CompletableFuture<PreparedSurfaceRegionBatch>>
            pendingSurfaceBatches = new HashMap<>();
    /**
     * CPU-ready exact leaves waiting only for render-thread publication. Cave can
     * own the visible projection for minutes; keeping these futures discoverable
     * only through dirty-page scans retained dozens of prepared 64x64 payloads and
     * prevented spare GPU ledger capacity from advancing Surface in the background.
     */
    private final ArrayDeque<String> completedPagePublications = new ArrayDeque<>();
    private final Set<String> completedPagePublicationKeys = new HashSet<>();
    private static final long COMPLETED_MAINTENANCE_INTERVAL_NANOS = 16_000_000L;
    private static final int COMPLETED_MAINTENANCE_SCAN_LIMIT = 16;
    /**
     * CPU-ready exact leaves own pooled pixel buffers until render publication.
     * Once this queue grows, submitting more fullscreen/background styling only
     * converts spare worker capacity into retained heap and later atlas churn.
     * Keep the minimap exempt, contract fullscreen work at the soft watermark and
     * stop all non-minimap captures at the hard watermark until publication drains.
     */
    private static final int COMPLETED_PUBLICATION_SOFT_LIMIT = 48;
    private static final int COMPLETED_PUBLICATION_HARD_LIMIT = 96;
    private static final int FOCUSED_PAGE_WORK_BUDGET = 6;
    private final String[] completedPublicationScanBuffer =
            new String[COMPLETED_MAINTENANCE_SCAN_LIMIT];
    private static final int MAX_RESIDENCY_RESTORE_QUEUE = MAX_TEXTURE_PAGES;
    private static final int RESIDENCY_RESTORE_SCAN_LIMIT = 24;
    private static final long RESIDENCY_RESTORE_RETRY_NANOS = 80_000_000L;
    /** Newly uploaded exact leaves may not immediately become atlas victims. */
    private static final long SURFACE_RESIDENCY_MIN_HOLD_NANOS = 750_000_000L;
    private long lastCompletedMaintenanceNanos;
    /**
     * GPU-evicted exact pages retain their coherent CPU pixels. Re-uploading them
     * synchronously from ensurePageInfo() caused an atlas ping-pong: every dirty
     * page lookup could evict a page that the renderer had used one frame earlier,
     * then the next lookup restored that victim and evicted the first page again.
     * Keep restore demand explicit, deduplicated and render-budgeted instead.
     */
    private final ArrayDeque<String> residencyRestoreQueue = new ArrayDeque<>();
    private final Set<String> residencyRestoreKeys = new HashSet<>();
    private final Map<String, Long> batchCaptureAttemptNanos = new HashMap<>();
    /** Consecutive source misses per batch, used to avoid render-thread retry storms. */
    private final Map<String, Integer> batchCaptureDeferrals = new HashMap<>();
    /**
     * A cold 512x512 region can expose dozens of distinct dirty leaves at once.
     * Per-batch backoff still allowed every leaf key to probe independently, which
     * produced hundreds of render-thread capture attempts per second while walking
     * into newly generated chunks. Share a coarse 4x4-page cooldown inside each
     * lane; a source-availability wake-up clears it immediately.
     */
    private final Map<String, Long> captureClusterAttemptNanos = new HashMap<>();
    private final Map<String, Integer> captureClusterDeferrals = new HashMap<>();
    /** Per-lane demand keeps the player-centred minimap ahead of panned fullscreen work. */
    private final Map<String, SurfacePageDemand> pageDemands = new HashMap<>();
    /** Current compact minimap-demand owner; stale 10 s leases must not fill its cap. */
    private String minimapDemandDimension = "";
    private int minimapDemandFocusPageX = Integer.MIN_VALUE;
    private int minimapDemandFocusPageZ = Integer.MIN_VALUE;
    private final MapViewLoadPlanner.State[] visiblePagePlanners =
            new MapViewLoadPlanner.State[MapRequestLane.values().length];
    private final long[] fullscreenSliceBuffer =
            new long[MapViewLoadPlanner.FULLSCREEN_SLICE_SIZE];
    private final long[] minimapHaloBuffer =
            new long[(MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES * 2 + 1)
                    * (MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES * 2 + 1)];
    private final Map<String, Long> firstDirtyPageNanos = new HashMap<>();
    private final Map<String, Long> lastDirtyPageNanos = new HashMap<>();
    private final Map<String, Long> visibleTextures = new LinkedHashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final Map<String, Long> firstDirtyNanos = new HashMap<>();
    private final Map<String, Long> lastDirtyNanos = new HashMap<>();
    private final List<RegionTextureInfo> deferredCloses = new ArrayList<>();
    private final List<PageTextureInfo> deferredPageCloses = new ArrayList<>();
    /** Staged exact-leaf publication; one 512 region is revealed as 64 ordered pages. */
    private final ArrayDeque<PendingLeafPublication> pendingLeafPublications = new ArrayDeque<>();
    private static final int MAX_PENDING_LEAF_REGIONS = 8;
    private int renderBatchDepth;
    /** Monotonic render batch epoch used to protect the current/previous frame. */
    private long renderEpoch;
    private final Map<String, Integer> blockColorsCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> vanillaBlockColorsCache = new ConcurrentHashMap<>();
    private final Map<String, BlockTintPolicy> tintPolicyCache = new ConcurrentHashMap<>();
    /** Biome registry lookup cache scoped to the active style generation. */
    private final Map<String, Biome> styleBiomeCache = new HashMap<>();
    private long styleBiomeCacheSessionId = Long.MIN_VALUE;
    private long styleBiomeCacheGeneration = Long.MIN_VALUE;
    private long lastUploadTime;
    private volatile long styleRefreshUntilMs;
    /**
     * Invalidates worker callbacks that outlive a world/dimension cache lifetime.
     * The render thread increments this before detaching futures; callbacks may still
     * release owned buffers, but must not recreate demand/state after the clear.
     */
    private volatile long cacheEpoch = 1L;
    /** True while fullscreen exact demand is intentionally disabled by the
     * region-only far-zoom policy. The transition clears old demand once. */
    private boolean fullscreenExactSuppressed;

    private MapTextureManager() {
        for (int i = 0; i < visiblePagePlanners.length; i++) {
            visiblePagePlanners[i] = new MapViewLoadPlanner.State();
        }
        for (int level = 0; level <= SurfaceLodTree.MAX_LEVEL; level++) {
            residentPageCountsByLevel.add(new HashMap<>());
        }
    }

    public static MapTextureManager getInstance() {
        return INSTANCE;
    }

    /** Completes the surface-atlas slot retirement fence after a page-table swap. */
    public void onPageTableFrameBoundary() {
        leafAtlas.onPageTableFrameBoundary();
    }

    /** Allocation-light counts for the diagnostic recorder. */
    public DebugSnapshot debugSnapshot() {
        int regions;
        int pages;
        int initializedPages = 0;
        int pendingPages = 0;
        int completedPendingPages = 0;
        synchronized (textureCache) {
            regions = textureCache.size();
        }
        synchronized (pageCache) {
            pages = pageCache.size();
            for (PageTextureInfo page : pageCache.values()) {
                if (page.initialized) initializedPages++;
                if (page.pending != null) {
                    if (page.pending.isDone()) completedPendingPages++;
                    else pendingPages++;
                }
            }
        }
        int dirtyRegionCount;
        int dirtyPageCount;
        int demandCount;
        synchronized (dirtyTextures) {
            dirtyRegionCount = dirtyTextures.size();
            dirtyPageCount = dirtyPages.size();
            demandCount = pageDemands.size();
        }
        int pendingBatchCount;
        synchronized (pendingSurfaceBatches) {
            pendingBatchCount = pendingSurfaceBatches.size();
        }
        int pendingLeafCount;
        synchronized (pendingLeafPublications) {
            pendingLeafCount = pendingLeafPublications.size();
        }
        return new DebugSnapshot(regions, pages, initializedPages, pendingPages,
                completedPendingPages, dirtyRegionCount, dirtyPageCount,
                pendingBatchCount, demandCount, pendingLeafCount);
    }

    public record DebugSnapshot(int regions, int pages, int initializedPages,
            int pendingPages, int completedPendingPages, int dirtyRegions,
            int dirtyPages, int pendingBatches, int pageDemands,
            int pendingLeafPublications) {
        public static DebugSnapshot empty() {
            return new DebugSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public void registerBlockColor(String blockId, int argb) {
        blockColorsCache.put(blockId, argb);
        vanillaBlockColorsCache.put(blockId, argb);
    }

    public Integer getBlockColor(String blockId) {
        return (MapConfig.blockColourMode == 1 ? vanillaBlockColorsCache : blockColorsCache).get(blockId);
    }

    /** Must resolve uncached texture resources on the client thread. */
    public int resolveBlockColor(String blockId) {
        return resolveBlockColor(blockId, MapConfig.blockColourMode);
    }

    public BlockTintPolicy resolveTintPolicy(String blockIdText) {
        BlockTintPolicy cached = tintPolicyCache.get(blockIdText);
        if (cached != null) return cached;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return BlockTintPolicy.NONE;
        BlockTintPolicy resolved = BlockTintPolicy.NONE;
        try {
            ResourceLocation blockId = ResourceLocation.parse(blockIdText);
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockId);
            var state = block.defaultBlockState();
            String path = blockId.getPath();
            // Cherry leaves use a fixed pink texture. Treating them as generic
            // foliage bakes a green biome multiplier into both surface and cave maps.
            if (state.is(net.minecraft.world.level.block.Blocks.CHERRY_LEAVES)) {
                tintPolicyCache.put(blockIdText, BlockTintPolicy.NONE);
                return BlockTintPolicy.NONE;
            }
            if (BlockTextureColorSampler.modelUsesTint(blockId)) {
                boolean leaves = state.is(net.minecraft.tags.BlockTags.LEAVES)
                        || path.endsWith("_leaves") || path.contains("foliage");
                if (leaves || path.contains("vine")) resolved = BlockTintPolicy.FOLIAGE;
                else if (path.contains("grass") || path.contains("fern")) resolved = BlockTintPolicy.GRASS;
            }
        } catch (Throwable ignored) {
        }
        tintPolicyCache.put(blockIdText, resolved);
        return resolved;
    }

    public int resolveBlockColor(String blockId, int colourMode) {
        Map<String, Integer> cache = colourMode == 1 ? vanillaBlockColorsCache : blockColorsCache;
        Integer override = MapConfig.blockColorOverrides.get(blockId);
        if (override != null) {
            int opaque = 0xFF000000 | (override & 0x00FFFFFF);
            cache.put(blockId, opaque);
            return opaque;
        }
        Integer cached = cache.get(blockId);
        if (cached != null) return cached;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return 0xFFFFFFFF;
        int resolved = resolveDefaultBlockColor(blockId, colourMode);
        cache.put(blockId, resolved);
        return resolved;
    }

    /** Resolves the automatic color while deliberately ignoring a saved override. */
    public int resolveAutomaticBlockColor(String blockId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return 0xFFFFFFFF;
        return resolveDefaultBlockColor(blockId, MapConfig.blockColourMode);
    }

    /** Invalidates one block's derived color, then rebuilds every cached surface tile. */
    public void invalidateBlockColor(String blockId) {
        if (blockId != null) {
            blockColorsCache.remove(blockId);
            vanillaBlockColorsCache.remove(blockId);
            tintPolicyCache.remove(blockId);
            try {
                BlockTextureColorSampler.invalidate(ResourceLocation.parse(blockId));
            } catch (RuntimeException ignored) {
                BlockTextureColorSampler.clearCache();
            }
        }
        invalidateStyle();
    }

    public void markRegionDirty(int regionX, int regionZ) {
        String key = key(regionX, regionZ);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            // Full-region invalidations are reserved for disk loads, style changes
            // and explicit refreshes. Live column updates use markPageDirtyForBlock().
            revisions.merge(key, 1L, Long::sum);
            firstDirtyNanos.putIfAbsent(key, now);
            lastDirtyNanos.put(key, now);
            dirtyTextures.add(key);
        }
    }

    /**
     * Called when a complete surface or light region becomes available from disk.
     * All local leaves are admitted, and boundary leaves in neighbouring regions
     * are restyled because their two-pixel halo may now contain better data.
     */
    public void markRegionSourceAvailable(int regionX, int regionZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> markRegionSourceAvailable(regionX, regionZ));
            return;
        }
        markRegionDirty(regionX, regionZ);
        String batchPrefix = key(regionX, regionZ) + ":batch:";
        String clusterPrefix = key(regionX, regionZ) + ":cluster:";
        synchronized (pendingSurfaceBatches) {
            // A completed disk read is an explicit wake-up. Do not leave its
            // visible batches sleeping behind a previous exponential backoff.
            batchCaptureAttemptNanos.keySet().removeIf(
                    batchKey -> batchKey.startsWith(batchPrefix));
            batchCaptureDeferrals.keySet().removeIf(
                    batchKey -> batchKey.startsWith(batchPrefix));
            captureClusterAttemptNanos.keySet().removeIf(
                    clusterKey -> clusterKey.startsWith(clusterPrefix));
            captureClusterDeferrals.keySet().removeIf(
                    clusterKey -> clusterKey.startsWith(clusterPrefix));
        }
        RegionSurfaceLodService.getInstance().onRegionSourceAvailable(
                regionX, regionZ);
        long now = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        long cachedPageMask = 0L;
        synchronized (pageCache) {
            for (int pageZ = 0; pageZ < MapPageLayout.PAGES_PER_REGION; pageZ++) {
                for (int pageX = 0; pageX < MapPageLayout.PAGES_PER_REGION; pageX++) {
                    String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
                    if (pageCache.containsKey(leafKey)) {
                        cachedPageMask |= 1L << MapPageLayout.pageIndex(pageX, pageZ);
                    }
                }
            }
        }
        synchronized (dirtyTextures) {
            for (int pageZ = 0; pageZ < MapPageLayout.PAGES_PER_REGION; pageZ++) {
                for (int pageX = 0; pageX < MapPageLayout.PAGES_PER_REGION; pageX++) {
                    int pageIndex = MapPageLayout.pageIndex(pageX, pageZ);
                    String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
                    SurfacePageDemand demand = pageDemands.get(leafKey);
                    boolean demanded = demand != null
                            && demand.effectiveLane(nowMs) != null;
                    if (demanded || (cachedPageMask & (1L << pageIndex)) != 0L) {
                        addDirtyPageLocked(regionX, regionZ, pageX, pageZ, now);
                    }
                }
            }
        }
    }

    /**
     * A live chunk packet is an authoritative source-progress signal. Clear only
     * capture backoff for its 512x512 map region; unlike a completed disk-region
     * load this must not dirty every leaf in the region.
     */
    public void wakeRegionCaptureForChunk(int chunkX, int chunkZ) {
        int chunksPerRegion = Math.max(1, MapPageLayout.REGION_SIZE / 16);
        int regionX = Math.floorDiv(chunkX, chunksPerRegion);
        int regionZ = Math.floorDiv(chunkZ, chunksPerRegion);
        int localChunkX = Math.floorMod(chunkX, chunksPerRegion);
        int localChunkZ = Math.floorMod(chunkZ, chunksPerRegion);
        int chunksPerPage = Math.max(1, MapPageLayout.PAGE_SIZE / 16);
        int localPageX = localChunkX / chunksPerPage;
        int localPageZ = localChunkZ / chunksPerPage;
        String regionKey = key(regionX, regionZ);
        synchronized (pendingSurfaceBatches) {
            for (int batchSize : CAPTURE_WAKE_BATCH_SIZES) {
                int batchPageX = (localPageX / batchSize) * batchSize;
                int batchPageZ = (localPageZ / batchSize) * batchSize;
                String batchKey = regionKey + ":batch:" + batchSize + ':'
                        + batchPageX + ',' + batchPageZ;
                batchCaptureAttemptNanos.remove(batchKey);
                batchCaptureDeferrals.remove(batchKey);
            }
            int clusterX = localPageX >> 2;
            int clusterZ = localPageZ >> 2;
            for (MapRequestLane lane : MapRequestLane.values()) {
                String clusterKey = regionKey + ":cluster:" + lane.name() + ':'
                        + clusterX + ',' + clusterZ;
                captureClusterAttemptNanos.remove(clusterKey);
                captureClusterDeferrals.remove(clusterKey);
            }
        }
    }

    private void markNeighbourBoundaryLocked(MapManager manager,
            int regionX, int regionZ, int fixedPageX, int fixedPageZ, long now) {
        if (!manager.isRegionLoadedInCache(regionX, regionZ)
                && !manager.hasRegionFile(regionX, regionZ)) return;
        if (fixedPageX >= 0) {
            for (int pageZ = 0; pageZ < MapPageLayout.PAGES_PER_REGION; pageZ++) {
                addDirtyPageLocked(regionX, regionZ, fixedPageX, pageZ, now);
            }
        } else {
            for (int pageX = 0; pageX < MapPageLayout.PAGES_PER_REGION; pageX++) {
                addDirtyPageLocked(regionX, regionZ, pageX, fixedPageZ, now);
            }
        }
    }

    private void enqueueDirtyPageLocked(String leafKey) {
        dirtyPages.add(leafKey);
        if (dirtyPageQueued.add(leafKey)) {
            dirtyPageOrder.addLast(leafKey);
        }
    }

    private void removeDirtyPageLocked(String leafKey) {
        dirtyPages.remove(leafKey);
        // The deque occurrence is removed lazily when it reaches the head. Keeping
        // dirtyPageQueued set prevents duplicate queue nodes if the page is dirtied
        // again before then.
    }

    private void addDirtyPageLocked(int regionX, int regionZ,
            int pageX, int pageZ, long now) {
        addDirtyPageLocked(regionX, regionZ, pageX, pageZ, now,
                MapPageLayout.FULL_SUBTILE_MASK);
    }

    private void addDirtyPageLocked(int regionX, int regionZ,
            int pageX, int pageZ, long now, int subtileMask) {
        String regionKey = key(regionX, regionZ);
        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        int updateMask = subtileMask & MapPageLayout.FULL_SUBTILE_MASK;
        if (updateMask == 0) return;
        revisions.merge(regionKey, 1L, Long::sum);
        pageRevisions.merge(leafKey, 1L, Long::sum);
        dirtyPageSubtileMasks.merge(leafKey, updateMask,
                (previous, added) -> previous | added);
        firstDirtyPageNanos.putIfAbsent(leafKey, now);
        lastDirtyPageNanos.put(leafKey, now);
        enqueueDirtyPageLocked(leafKey);
        MapRequestLane activeLane = effectivePageLane(
                leafKey, System.currentTimeMillis());
        ExactPageStateTracker.getInstance().transition(
                "surface:" + leafKey,
                activeLane == null ? ExactPageState.STALE_GENERATION
                        : ExactPageState.REQUESTED,
                activeLane == null ? MapRequestLane.BACKGROUND : activeLane,
                pageRevisions.getOrDefault(leafKey, 0L));
        pendingLeafPublications.removeIf(pending -> pending.regionKey.equals(regionKey));
    }

    /**
     * Marks the exact 64x64 leaf affected by a world-column update. A two-block
     * dependency halo also invalidates adjacent pages when relief or biome tint
     * samples cross a page edge.
     */
    public void markPageDirtyForBlock(int blockX, int blockZ) {
        int dependencyRadius = MapPageLayout.PAGE_HALO;
        int minGlobalPageX = Math.floorDiv(blockX - dependencyRadius, MapPageLayout.PAGE_SIZE);
        int maxGlobalPageX = Math.floorDiv(blockX + dependencyRadius, MapPageLayout.PAGE_SIZE);
        int minGlobalPageZ = Math.floorDiv(blockZ - dependencyRadius, MapPageLayout.PAGE_SIZE);
        int maxGlobalPageZ = Math.floorDiv(blockZ + dependencyRadius, MapPageLayout.PAGE_SIZE);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            for (int globalPageX = minGlobalPageX; globalPageX <= maxGlobalPageX; globalPageX++) {
                int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
                int pageX = MapPageLayout.localPage(globalPageX);
                for (int globalPageZ = minGlobalPageZ; globalPageZ <= maxGlobalPageZ; globalPageZ++) {
                    int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
                    int pageZ = MapPageLayout.localPage(globalPageZ);
                    /*
                     * PASS110: edge repair follows the same retained Surface authority
                     * consumed by every renderer lane. PASS109 still consulted the
                     * legacy MapManager.Region/cache here, so a teleported/warmed
                     * neighbour could already exist in SurfaceRegionSourceDatabase and
                     * on the GPU yet be skipped merely because its mutable Region was
                     * not loaded. That left permanent tint/slope seams at the overlap
                     * between old and newly visited areas. Dirty the tiny halo
                     * intersection unconditionally; the immutable source probe decides
                     * later whether a replacement can be built, while the last-good GPU
                     * page remains visible.
                     */
                    int updateMask = subtileMaskForWorldRect(globalPageX,
                            globalPageZ, blockX - dependencyRadius,
                            blockZ - dependencyRadius,
                            blockX + dependencyRadius,
                            blockZ + dependencyRadius);
                    addDirtyPageLocked(regionX, regionZ, pageX, pageZ, now,
                            updateMask);
                }
            }
        }
    }

    /**
     * Invalidates the exact leaf touched by a complete 16x16 chunk transaction,
     * plus only the pages intersecting the two-block relief/tint halo. This is the
     * chunk-granular counterpart of {@link #markPageDirtyForBlock(int, int)} and
     * avoids repeating the same queue/revision work for every one of 256 columns.
     */
    public void markPageDirtyForChunk(int chunkX, int chunkZ) {
        int dependencyRadius = MapPageLayout.PAGE_HALO;
        int minBlockX = (chunkX << 4) - dependencyRadius;
        int maxBlockX = (chunkX << 4) + 15 + dependencyRadius;
        int minBlockZ = (chunkZ << 4) - dependencyRadius;
        int maxBlockZ = (chunkZ << 4) + 15 + dependencyRadius;
        int minGlobalPageX = Math.floorDiv(minBlockX, MapPageLayout.PAGE_SIZE);
        int maxGlobalPageX = Math.floorDiv(maxBlockX, MapPageLayout.PAGE_SIZE);
        int minGlobalPageZ = Math.floorDiv(minBlockZ, MapPageLayout.PAGE_SIZE);
        int maxGlobalPageZ = Math.floorDiv(maxBlockZ, MapPageLayout.PAGE_SIZE);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            for (int globalPageZ = minGlobalPageZ;
                    globalPageZ <= maxGlobalPageZ; globalPageZ++) {
                int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
                int pageZ = MapPageLayout.localPage(globalPageZ);
                for (int globalPageX = minGlobalPageX;
                        globalPageX <= maxGlobalPageX; globalPageX++) {
                    int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
                    int pageX = MapPageLayout.localPage(globalPageX);
                    int updateMask = subtileMaskForWorldRect(globalPageX,
                            globalPageZ, minBlockX, minBlockZ,
                            maxBlockX, maxBlockZ);
                    /*
                     * Do not intersect the repair mask with MapManager completion
                     * bits. SurfaceRegionSourceDatabase is the publication authority;
                     * an adjacent retained page may be complete even when no legacy
                     * Region object is resident (especially after teleport/cache
                     * replay). Scheduling a harmless halo repair is cheaper than
                     * leaving an old edge permanently stitched to a new transaction.
                     */
                    addDirtyPageLocked(regionX, regionZ, pageX, pageZ, now,
                            updateMask);
                }
            }
        }
    }

    private static int subtileMaskForWorldRect(int globalPageX,
            int globalPageZ, int minBlockX, int minBlockZ,
            int maxBlockX, int maxBlockZ) {
        int pageStartX = globalPageX * MapPageLayout.PAGE_SIZE;
        int pageStartZ = globalPageZ * MapPageLayout.PAGE_SIZE;
        int localMinX = Math.max(0, minBlockX - pageStartX);
        int localMinZ = Math.max(0, minBlockZ - pageStartZ);
        int localMaxX = Math.min(MapPageLayout.PAGE_SIZE - 1,
                maxBlockX - pageStartX);
        int localMaxZ = Math.min(MapPageLayout.PAGE_SIZE - 1,
                maxBlockZ - pageStartZ);
        if (localMinX > localMaxX || localMinZ > localMaxZ) return 0;
        int firstSubtileX = localMinX / MapPageLayout.SUBTILE_SIZE;
        int lastSubtileX = localMaxX / MapPageLayout.SUBTILE_SIZE;
        int firstSubtileZ = localMinZ / MapPageLayout.SUBTILE_SIZE;
        int lastSubtileZ = localMaxZ / MapPageLayout.SUBTILE_SIZE;
        int result = 0;
        for (int subtileZ = firstSubtileZ; subtileZ <= lastSubtileZ; subtileZ++) {
            for (int subtileX = firstSubtileX; subtileX <= lastSubtileX; subtileX++) {
                result |= 1 << MapPageLayout.subtileIndex(subtileX, subtileZ);
            }
        }
        return result;
    }


    /**
     * Marks the regions covering the given world-coordinate viewport as visible
     * so the upload prioritiser includes them in the next upload cycle.
     * Called from the client tick via MapViewportCoordinator.
     * Never triggers disk loads or CPU builds; those happen in uploadDirtyTextures().
     */
    public void requestVisiblePages(double minX, double maxX, double minZ, double maxZ) {
        requestVisiblePages(minX, maxX, minZ, maxZ, MapRequestLane.FULLSCREEN);
    }

    public void requestVisiblePages(double minX, double maxX, double minZ, double maxZ,
            MapRequestLane lane) {
        requestVisiblePages(minX, maxX, minZ, maxZ,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, 1.0f, lane);
    }

    /**
     * Requests visible exact leaves around the user's attention point. Fullscreen
     * focus is the block under the mouse; minimap focus is the player. This mirrors
     * Xaero's distance comparator while keeping the branch hierarchy as fallback.
     */
    public void requestVisiblePages(double minX, double maxX, double minZ, double maxZ,
            double focusX, double focusZ, MapRequestLane lane) {
        requestVisiblePages(minX, maxX, minZ, maxZ,
                focusX, focusZ, 1.0f, lane);
    }

    public void requestVisiblePages(double minX, double maxX, double minZ, double maxZ,
            double focusX, double focusZ, float scale, MapRequestLane lane) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        double pageMinimumX = effectiveLane == MapRequestLane.FULLSCREEN
                ? minX : minX - 1.0;
        double pageMaximumX = effectiveLane == MapRequestLane.FULLSCREEN
                ? Math.nextDown(maxX) : maxX + 1.0;
        double pageMinimumZ = effectiveLane == MapRequestLane.FULLSCREEN
                ? minZ : minZ - 1.0;
        double pageMaximumZ = effectiveLane == MapRequestLane.FULLSCREEN
                ? Math.nextDown(maxZ) : maxZ + 1.0;
        int minGlobalPageX = Math.floorDiv((int) Math.floor(pageMinimumX),
                MapPageLayout.PAGE_SIZE);
        int maxGlobalPageX = Math.floorDiv((int) Math.floor(pageMaximumX),
                MapPageLayout.PAGE_SIZE);
        int minGlobalPageZ = Math.floorDiv((int) Math.floor(pageMinimumZ),
                MapPageLayout.PAGE_SIZE);
        int maxGlobalPageZ = Math.floorDiv((int) Math.floor(pageMaximumZ),
                MapPageLayout.PAGE_SIZE);
        int minRx = MapPageLayout.regionFromGlobalPage(minGlobalPageX);
        int maxRx = MapPageLayout.regionFromGlobalPage(maxGlobalPageX);
        int minRz = MapPageLayout.regionFromGlobalPage(minGlobalPageZ);
        int maxRz = MapPageLayout.regionFromGlobalPage(maxGlobalPageZ);
        int focusRx = clamp((int) Math.floor(focusX) >> 9, minRx, maxRx);
        int focusRz = clamp((int) Math.floor(focusZ) >> 9, minRz, maxRz);
        if (effectiveLane == MapRequestLane.MINIMAP) {
            markVisibleRegionsCenterOut(minRx, maxRx, minRz, maxRz,
                    focusRx, focusRz);
        } else {
            markVisibleRegionsStable(minRx, maxRx, minRz, maxRz);
        }

        int focusPageX = clamp(Math.floorDiv((int) Math.floor(focusX),
                MapPageLayout.PAGE_SIZE), minGlobalPageX, maxGlobalPageX);
        int focusPageZ = clamp(Math.floorDiv((int) Math.floor(focusZ),
                MapPageLayout.PAGE_SIZE), minGlobalPageZ, maxGlobalPageZ);

        if (effectiveLane == MapRequestLane.MINIMAP) {
            replaceMinimapDemandWindowIfMoved(
                    MapManager.getInstance().getDimensionCacheKey(),
                    focusPageX, focusPageZ);
            // Xaero's minimap is a separate demand producer: complete a compact
            // player/camera-centred exact halo before spending work elsewhere.
            // Size it from Minecraft's loaded radius instead of a fixed 5x5-page
            // island, so rotated corners and newly loaded edge chunks are included.
            Minecraft minecraft = Minecraft.getInstance();
            int renderDistance = minecraft == null ? 12
                    : minecraft.options.renderDistance().get();
            int workingRadius = MapViewLoadPlanner
                    .minimapWorkingRadiusPages(renderDistance);
            int count = MapViewLoadPlanner.fillMinimapHalo(
                    minGlobalPageX, maxGlobalPageX,
                    minGlobalPageZ, maxGlobalPageZ,
                    focusPageX, focusPageZ, workingRadius, minimapHaloBuffer);
            // Completed and pending pages already reject themselves in
            // isLeafRequestCandidate(). Counting every still-live demand against an
            // eight-page quota stranded the rest of the loaded render-distance halo
            // until the complete MINIMAP request TTL expired.
            int admission = MapPerformanceGovernor.getInstance().underPressure()
                    ? 8 : 24;
            int admitted = 0;
            for (int i = 0; i < count && admitted < admission; i++) {
                long page = minimapHaloBuffer[i];
                int pageX = MapViewLoadPlanner.packedX(page);
                int pageZ = MapViewLoadPlanner.packedZ(page);
                if (!isLeafRequestCandidate(pageX, pageZ, effectiveLane)) continue;
                int priority = effectiveLane.priorityBase() + 180_000
                        - Math.min(160_000, i * 4_000);
                requestLeafResident(pageX, pageZ, effectiveLane, priority);
                admitted++;
            }
            return;
        }

        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            /*
             * Fullscreen admission is a cyclic fixed-coordinate traversal, not a
             * completion frontier. One page waiting for a region file or for its
             * first complete 16x16 source tile must never pin every later row. The
             * planner still gives stable ordinals for priority, but advances after
             * inspecting each slice and revisits unresolved leaves on the next lap.
             */
            MapViewLoadPlanner.State planner =
                    visiblePagePlanners[effectiveLane.ordinal()];
            boolean viewportChanged = planner.configure(
                    MapManager.getInstance().getDimensionCacheKey(),
                    minGlobalPageX, maxGlobalPageX,
                    minGlobalPageZ, maxGlobalPageZ);
            if (viewportChanged) {
                clearAllPageDemandLane(MapRequestLane.FULLSCREEN);
                /*
                 * PASS100 / Xaero retained-residency rule:
                 * a viewport rebase changes ownership, not cache residency.
                 * Eagerly releasing every exact page outside the new rectangle
                 * caused hundreds of avoidable GPU evictions/restores and made
                 * revisiting a location black until exact leaves were uploaded
                 * again. Keep old exact slots resident and let the atlas/residency
                 * LRU choose victims only when a new allocation actually needs
                 * space. This mirrors Xaero's loaded/cache separation.
                 */
                if (planner.retainedOverlap()) {
                    MapProcessor.getInstance().retainSurfaceLoadsInRegions(
                            minRx, maxRx, minRz, maxRz);
                } else {
                    MapProcessor.getInstance().clearSurfaceLoads();
                }
            }

            long nowMs = System.currentTimeMillis();
            int active = activeDemandCount(effectiveLane, nowMs);
            int visiblePageCount = Math.max(1,
                    (maxGlobalPageX - minGlobalPageX + 1)
                            * (maxGlobalPageZ - minGlobalPageZ + 1));
            int exactWindow = fullscreenActiveWindow(scale, visiblePageCount);
            if (exactWindow <= 0) {
                if (!fullscreenExactSuppressed) {
                    clearAllPageDemandLane(MapRequestLane.FULLSCREEN);
                    fullscreenExactSuppressed = true;
                }
                return;
            }
            fullscreenExactSuppressed = false;
            int available = Math.max(0, exactWindow - active);
            boolean coarseCoverageAccepted =
                    MapRegionLodPolicy.directProjectionEnabled(scale,
                            minGlobalPageX, maxGlobalPageX,
                            minGlobalPageZ, maxGlobalPageZ);
            int slicesChecked = Math.min(8,
                    Math.max(2, maxGlobalPageZ - minGlobalPageZ + 1));
            for (int checked = 0; checked < slicesChecked; checked++) {
                int sliceCount = planner.fillCurrentFullscreenSlice(
                        fullscreenSliceBuffer);
                int sliceStartOrdinal = planner.currentSliceStartOrdinal();
                for (int i = 0; i < sliceCount; i++) {
                    long candidate = fullscreenSliceBuffer[i];
                    int pageX = MapViewLoadPlanner.packedX(candidate);
                    int pageZ = MapViewLoadPlanner.packedZ(candidate);
                    int ordinal = sliceStartOrdinal + i;
                    FullscreenLeafState state = fullscreenLeafState(
                            pageX, pageZ, nowMs, coarseCoverageAccepted);
                    if (state == FullscreenLeafState.SATISFIED) continue;
                    if (state == FullscreenLeafState.UNAVAILABLE) {
                        clearFullscreenDemand(pageX, pageZ);
                        continue;
                    }
                    if (state == FullscreenLeafState.WAITING) {
                        refreshFullscreenDemand(pageX, pageZ, ordinal, nowMs);
                        continue;
                    }
                    if (available <= 0) continue;
                    int priority = effectiveLane.priorityBase() + 100_000
                            - Math.min(90_000, i * 900);
                    requestLeafResident(pageX, pageZ,
                            effectiveLane, priority, ordinal);
                    available--;
                }
                planner.advanceFullscreenSlice();
            }
            return;
        }

        // Writer/background work remains player-centred and tightly bounded. It is
        // not allowed to inherit the fullscreen traversal or exact-page volume.
        int radius = effectiveLane == MapRequestLane.BACKGROUND ? 1 : 0;
        requestLeavesCenterOut(Math.max(minGlobalPageX, focusPageX - radius),
                Math.min(maxGlobalPageX, focusPageX + radius),
                Math.max(minGlobalPageZ, focusPageZ - radius),
                Math.min(maxGlobalPageZ, focusPageZ + radius),
                focusPageX, focusPageZ, effectiveLane);
    }

    private boolean isLeafRequestCandidate(int globalPageX, int globalPageZ,
            MapRequestLane lane) {
        int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
        int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        if (retainedLeafSourceMask(regionX, regionZ, pageX, pageZ) == 0) {
            return false;
        }

        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        long currentRevision;
        synchronized (dirtyTextures) {
            currentRevision = pageRevisions.getOrDefault(leafKey, 0L);
        }
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(leafKey);
            if (page == null) return true;
            boolean revisionCurrent = page.uploadedRevision >= currentRevision;
            if (page.initialized && revisionCurrent
                    && pageCoversSourceForLane(page, regionX, regionZ,
                            pageX, pageZ, lane)) return false;
            if (page.pending != null && !page.pending.isDone()) return false;
            return System.nanoTime() >= page.retryAfterNanos;
        }
    }

    /** All renderer lanes consume only the immutable retained writer database. */
    private int retainedLeafSourceMask(int regionX, int regionZ,
            int pageX, int pageZ) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        return SurfaceRegionSourceDatabase.getInstance().leafPresentSubtileMask(
                stamp, regionX, regionZ, pageX, pageZ);
    }

    /**
     * Renderer demand never snapshots MapManager.Region directly. It only asks the
     * writer side to populate the immutable retained source and waits for the
     * publication wake-up. This is the Surface equivalent of Xaero's writer-owned
     * loadingBlocks/MapTileChunk buffers.
     */
    private void ensureRetainedSurfaceSource(int regionX, int regionZ,
            int pageX, int pageZ, MapRequestLane lane, int priority) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null || !stamp.isCurrent()) return;
        MapManager manager = MapManager.getInstance();
        int retainedMask = retainedLeafSourceMask(regionX, regionZ, pageX, pageZ);
        int missingMask = MapPageLayout.FULL_SUBTILE_MASK & ~retainedMask;
        if (missingMask != 0 && (lane == MapRequestLane.MINIMAP
                || lane == MapRequestLane.FULLSCREEN)) {
            ChunkScanner.getInstance().nudgeRetainedSurfacePage(
                    regionX, regionZ, pageX, pageZ, missingMask);
        }
        MapManager.Region region = manager.getRegion(regionX, regionZ, false);
        if (region != null && region.isLoaded()) {
            /*
             * PASS109: exact renderer demand is one 64x64 page. Warm only its
             * 4x4 source chunks instead of rescanning all 1,024 chunks in the
             * 512x512 region. Coarse/LOD services may still request a region-wide
             * warm independently.
             */
            SurfaceRegionSourceDatabase.getInstance().warmLoadedPage(
                    stamp, regionX, regionZ, pageX, pageZ,
                    lane == null ? MapRequestLane.BACKGROUND : lane);
            return;
        }
        if (manager.hasRegionFile(regionX, regionZ)) {
            MapProcessor.getInstance().enqueueSurfaceLoad(
                    regionX, regionZ, Math.max(1, priority));
        }
    }

    private boolean pageSnapshotCoversRetainedSource(PageTextureInfo page,
            int regionX, int regionZ, int pageX, int pageZ) {
        if (page == null || page.colorPixels == null || page.glowPixels == null
                || page.completeSubtileMask == 0 || page.knownColumns <= 0) {
            return false;
        }
        int required = retainedLeafSourceMask(regionX, regionZ, pageX, pageZ);
        /* PASS110: a 64x64 exact texture is the atomic GPU transaction, but its
         * coverage mask does not need to pretend that source outside the currently
         * available 4x4 chunk sweep exists. Xaero updates one MapTileChunk buffer
         * after visiting all sixteen child positions; skipped edge chunks remain
         * unloaded while the available MapTiles are coherent. Treat the current
         * retained-source mask as the authority and repair/expand this page when a
         * later chunk publication grows that mask. */
        return required != 0
                && (page.completeSubtileMask & required) == required;
    }

    private boolean pageCoversSourceForLane(PageTextureInfo page,
            int regionX, int regionZ, int pageX, int pageZ,
            MapRequestLane lane) {
        return page != null && page.initialized && page.atlasSlot >= 0
                && pageSnapshotCoversRetainedSource(page, regionX, regionZ,
                        pageX, pageZ);
    }

    private boolean pageSnapshotCoversSourceForLane(PageTextureInfo page,
            int regionX, int regionZ, int pageX, int pageZ,
            MapRequestLane lane) {
        return pageSnapshotCoversRetainedSource(page, regionX, regionZ,
                pageX, pageZ);
    }

    private FullscreenLeafState fullscreenLeafState(
            int globalPageX, int globalPageZ, long nowMs,
            boolean coarseCoverageAccepted) {
        int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
        int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        int retainedMask = retainedLeafSourceMask(regionX, regionZ, pageX, pageZ);
        MapManager manager = MapManager.getInstance();
        if (retainedMask == 0
                && !manager.isRegionLoadedInCache(regionX, regionZ)
                && !manager.hasRegionFile(regionX, regionZ)) {
            return FullscreenLeafState.UNAVAILABLE;
        }

        if (retainedMask != MapPageLayout.FULL_SUBTILE_MASK) {
            MapManager.Region sourceRegion = manager.getRegion(
                    regionX, regionZ, false);
            if (sourceRegion != null && sourceRegion.isLoaded()
                    && !sourceRegion.hasAnyDataInPage(pageX, pageZ)
                    && retainedMask == 0) {
                return FullscreenLeafState.UNAVAILABLE;
            }
            ensureRetainedSurfaceSource(regionX, regionZ, pageX, pageZ,
                    MapRequestLane.FULLSCREEN,
                    MapRequestLane.FULLSCREEN.priorityBase());
            // PASS111: one missing child must not gate the other retained children.
            // A non-zero retained mask is already coherent publication authority;
            // the exact page advertises only those subtiles in its page-table mask.
            if (retainedMask == 0) return FullscreenLeafState.WAITING;
        }
        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        long currentRevision;
        synchronized (dirtyTextures) {
            currentRevision = pageRevisions.getOrDefault(leafKey, 0L);
        }
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(leafKey);
            boolean revisionCurrent = page != null
                    && page.uploadedRevision >= currentRevision;
            if (revisionCurrent && pageCoversSourceForLane(page,
                    regionX, regionZ, pageX, pageZ,
                    MapRequestLane.FULLSCREEN)) {
                return FullscreenLeafState.SATISFIED;
            }
            if (revisionCurrent && pageSnapshotCoversSourceForLane(page,
                    regionX, regionZ, pageX, pageZ,
                    MapRequestLane.FULLSCREEN)) {
                // At a branch/region-LOD viewport, a published replacement is a
                // settled visual result. Do not cycle every retained exact leaf
                // through the finite 576-slot atlas merely to advance the frontier.
                if (coarseCoverageAccepted && hasReplacementCoverage(page)) {
                    return FullscreenLeafState.SATISFIED;
                }
                if (page.pending != null
                        || System.nanoTime() < page.residencyRetryAfterNanos) {
                    return FullscreenLeafState.WAITING;
                }
                return FullscreenLeafState.REQUESTABLE;
            }
            if (page != null && (page.pending != null
                    || System.nanoTime() < page.retryAfterNanos)) {
                return FullscreenLeafState.WAITING;
            }
        }
        synchronized (dirtyTextures) {
            SurfacePageDemand demand = pageDemands.get(leafKey);
            if (demand != null
                    && demand.isLaneActive(MapRequestLane.FULLSCREEN, nowMs)) {
                return FullscreenLeafState.WAITING;
            }
        }
        return FullscreenLeafState.REQUESTABLE;
    }

    private void clearFullscreenDemand(int globalPageX, int globalPageZ) {
        int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
        int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        clearPageDemandLane(pageKey(regionX, regionZ, pageX, pageZ),
                MapRequestLane.FULLSCREEN);
    }

    private void refreshFullscreenDemand(int globalPageX, int globalPageZ,
            long ordinal, long nowMs) {
        int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
        int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        int priority = MapRequestLane.FULLSCREEN.priorityBase() + 100_000
                - (int) Math.min(90_000L, ordinal % 100L * 900L);

        MapManager manager = MapManager.getInstance();
        int retainedMask = retainedLeafSourceMask(regionX, regionZ, pageX, pageZ);
        if (retainedMask != MapPageLayout.FULL_SUBTILE_MASK) {
            boolean sourceMayExist = manager.isRegionLoadedInCache(regionX, regionZ)
                    || manager.hasRegionFile(regionX, regionZ);
            if (sourceMayExist) {
                ensureRetainedSurfaceSource(regionX, regionZ, pageX, pageZ,
                        MapRequestLane.FULLSCREEN, priority);
            }
            if (retainedMask == 0) {
                clearPageDemandLane(leafKey, MapRequestLane.FULLSCREEN);
                return;
            }
            // Partial retained coverage is requestable; missing children remain masked.
        }
        synchronized (dirtyTextures) {
            pageDemands.computeIfAbsent(leafKey, ignored -> new SurfacePageDemand())
                    .observe(MapRequestLane.FULLSCREEN, priority, nowMs, ordinal);
            enqueueDirtyPageLocked(leafKey);
        }
    }

    private int activeDemandCount(MapRequestLane lane, long nowMs) {
        int count = 0;
        synchronized (dirtyTextures) {
            for (SurfacePageDemand demand : pageDemands.values()) {
                if (demand.isLaneActive(lane, nowMs)) count++;
            }
        }
        return count;
    }

    /**
     * A minimap lease lasts long enough for an immutable build to survive normal
     * frame/tick skew. It must not, however, let pages from earlier player
     * positions consume the compact eight-page hot-set quota. On each 64-block
     * focus-page transition, retire only the old MINIMAP ownership. Overlapping
     * pending builds are immediately re-observed below; their CPU work is retained.
     */
    private void replaceMinimapDemandWindowIfMoved(String dimension,
            int focusPageX, int focusPageZ) {
        String safeDimension = dimension == null ? "" : dimension;
        if (minimapDemandFocusPageX == focusPageX
                && minimapDemandFocusPageZ == focusPageZ
                && minimapDemandDimension.equals(safeDimension)) return;
        clearAllPageDemandLane(MapRequestLane.MINIMAP);
        minimapDemandDimension = safeDimension;
        minimapDemandFocusPageX = focusPageX;
        minimapDemandFocusPageZ = focusPageZ;
    }

    private void requestLeavesCenterOut(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
            MapRequestLane lane) {
        int maximumRadius = Math.max(
                Math.max(Math.abs(centerPageX - minPageX), Math.abs(maxPageX - centerPageX)),
                Math.max(Math.abs(centerPageZ - minPageZ), Math.abs(maxPageZ - centerPageZ)));
        for (int radius = 0; radius <= maximumRadius; radius++) {
            for (int pageZ = centerPageZ - radius; pageZ <= centerPageZ + radius; pageZ++) {
                for (int pageX = centerPageX - radius; pageX <= centerPageX + radius; pageX++) {
                    if (Math.max(Math.abs(pageX - centerPageX),
                            Math.abs(pageZ - centerPageZ)) != radius) continue;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    int dx = pageX - centerPageX;
                    int dz = pageZ - centerPageZ;
                    int distancePenalty = Math.min(800_000, (dx * dx + dz * dz) * 2_000);
                    requestLeafResident(pageX, pageZ, lane,
                            lane.priorityBase() - distancePenalty);
                }
            }
        }
    }

    private void requestLeafResident(int globalPageX, int globalPageZ,
            MapRequestLane lane, int priority) {
        requestLeafResident(globalPageX, globalPageZ, lane, priority, Long.MAX_VALUE);
    }

    private void requestLeafResident(int globalPageX, int globalPageZ,
            MapRequestLane lane, int priority, long fullscreenOrdinal) {
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
        int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        MapManager manager = MapManager.getInstance();
        int retainedMask = retainedLeafSourceMask(regionX, regionZ, pageX, pageZ);
        if (retainedMask != MapPageLayout.FULL_SUBTILE_MASK) {
            boolean sourceMayExist = manager.isRegionLoadedInCache(regionX, regionZ)
                    || manager.hasRegionFile(regionX, regionZ);
            if (!sourceMayExist && retainedMask == 0) return;
            ensureRetainedSurfaceSource(regionX, regionZ, pageX, pageZ,
                    effectiveLane, priority);
            if (retainedMask == 0) {
                observePageDemand(leafKey, effectiveLane, priority,
                        fullscreenOrdinal, false);
                return;
            }
            // PASS111: retained partial pages participate in the exact work set.
        }

        long currentRevision;
        synchronized (dirtyTextures) {
            currentRevision = pageRevisions.getOrDefault(leafKey, 0L);
        }
        boolean refreshOnly = false;
        boolean restoreOnly = false;
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(leafKey);
            boolean revisionCurrent = page != null
                    && page.uploadedRevision >= currentRevision;
            // Coverage and freshness are separate invariants. PASS103 treated an
            // already-covered but newly-dirtied page as satisfied, allowing visible
            // stale pages to sit behind hundreds of historical dirty keys.
            if (revisionCurrent && pageCoversSourceForLane(page, regionX, regionZ,
                    pageX, pageZ, effectiveLane)) return;
            if (revisionCurrent && pageSnapshotCoversSourceForLane(page,
                    regionX, regionZ, pageX, pageZ, effectiveLane)) {
                restoreOnly = true;
            } else if (page != null && page.pending == null
                    && System.nanoTime() < page.retryAfterNanos) {
                refreshOnly = true;
            } else if (page != null && page.pending != null) {
                if (effectiveLane.strongerThan(page.pendingLane)
                        && page.pending.cancel(false)) {
                    ExactPageStateTracker.getInstance().transition(
                            "surface:" + leafKey, ExactPageState.STALE_GENERATION,
                            page.pendingLane, page.uploadedRevision);
                    page.pending = null;
                    page.pendingLane = null;
                    page.pendingCompletionRecorded = false;
                    MapPipelineTelemetry.getInstance().recordTaskCancelledBeforeRun();
                } else {
                    refreshOnly = true;
                }
            }
        }
        if (restoreOnly) {
            observePageDemand(leafKey, effectiveLane, priority,
                    fullscreenOrdinal, false);
            enqueueResidencyRestore(leafKey);
            return;
        }
        if (refreshOnly) {
            observePageDemand(leafKey, effectiveLane, priority,
                    fullscreenOrdinal, false);
            return;
        }
        MapPipelineTelemetry.getInstance().recordPageAdmission(effectiveLane);
        observePageDemand(leafKey, effectiveLane, priority,
                fullscreenOrdinal, true);
    }


    private void observePageDemand(String leafKey, MapRequestLane lane,
            int priority, long fullscreenOrdinal, boolean markDirty) {
        synchronized (dirtyTextures) {
            long nowNanos = System.nanoTime();
            long nowMillis = System.currentTimeMillis();
            pageDemands.computeIfAbsent(leafKey, ignored -> new SurfacePageDemand())
                    .observe(lane, priority, nowMillis, fullscreenOrdinal);
            pageRevisions.putIfAbsent(leafKey, 0L);
            firstDirtyPageNanos.putIfAbsent(leafKey, nowNanos);
            lastDirtyPageNanos.putIfAbsent(leafKey, nowNanos);
            if (markDirty) enqueueDirtyPageLocked(leafKey);
        }
    }


    private void markVisibleRegionsCenterOut(int minRx, int maxRx,
            int minRz, int maxRz, int focusRx, int focusRz) {
        int maximumRadius = Math.max(
                Math.max(Math.abs(focusRx - minRx), Math.abs(maxRx - focusRx)),
                Math.max(Math.abs(focusRz - minRz), Math.abs(maxRz - focusRz)));
        for (int radius = 0; radius <= maximumRadius; radius++) {
            for (int rz = focusRz - radius; rz <= focusRz + radius; rz++) {
                for (int rx = focusRx - radius; rx <= focusRx + radius; rx++) {
                    if (Math.max(Math.abs(rx - focusRx), Math.abs(rz - focusRz)) != radius) continue;
                    if (rx < minRx || rx > maxRx || rz < minRz || rz > maxRz) continue;
                    markRegionVisible(key(rx, rz));
                }
            }
        }
    }

    private void markVisibleRegionsStable(int minRx, int maxRx,
            int minRz, int maxRz) {
        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                markRegionVisible(key(rx, rz));
            }
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(Math.min(minimum, maximum),
                Math.min(Math.max(minimum, maximum), value));
    }

    public void requestVisibleRegion(int regionX, int regionZ) {
        markRegionVisible(key(regionX, regionZ));
    }

    public ResourceLocation getRegionTexture(int regionX, int regionZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            MapProcessor.getInstance().enqueueSurfaceLoad(regionX, regionZ, 1);
            return null;
        }

        String key = key(regionX, regionZ);
        markRegionVisible(key);
        MapManager manager = MapManager.getInstance();
        MapManager.Region region = manager.getRegion(regionX, regionZ, false);
        if (region == null) {
            if (manager.hasRegionFile(regionX, regionZ)) {
                int priority = distancePriority(regionX, regionZ);
                MapProcessor.getInstance().enqueueSurfaceLoad(regionX, regionZ, priority);
            }
            return null;
        }
        if (!region.isLoaded()) return null;

        RegionTextureInfo info;
        synchronized (textureCache) {
            info = textureCache.get(key);
            if (info == null) {
                info = createTextureInfo(regionX, regionZ, manager.getGeneration());
                textureCache.put(key, info);
                trimTextureCache();
                schedulePreparation(key, info, region, regionX, regionZ);
            } else if (info.generation != manager.getGeneration()) {
                if (info.pending != null) info.pending.cancel(false);
                info.pending = null;
                info.generation = manager.getGeneration();
            }
        }
        return info.initialized ? info.location : null;
    }

    public ResourceLocation getGlowRegionTexture(int regionX, int regionZ) {
        getRegionTexture(regionX, regionZ);
        synchronized (textureCache) {
            RegionTextureInfo info = textureCache.get(key(regionX, regionZ));
            return info != null && info.initialized ? info.glowLocation : null;
        }
    }


    /** Returns only an already uploaded GPU tile; never loads, rebuilds or allocates. */
    public ResourceLocation peekRegionTexture(int regionX, int regionZ) {
        String key = key(regionX, regionZ);
        synchronized (textureCache) {
            RegionTextureInfo info = textureCache.get(key);
            if (info == null || !info.initialized) return null;
            markRegionVisible(key);
            MapResidencyManager.getInstance().touch("legacy:" + key);
            return info.location;
        }
    }

    /** Cached-only counterpart for the emissive overlay. */
    public ResourceLocation peekGlowRegionTexture(int regionX, int regionZ) {
        String key = key(regionX, regionZ);
        synchronized (textureCache) {
            RegionTextureInfo info = textureCache.get(key);
            if (info == null || !info.initialized) return null;
            markRegionVisible(key);
            MapResidencyManager.getInstance().touch("legacy:" + key);
            return info.glowLocation;
        }
    }

    /** Cached-only exact 64x64 leaf handle inside the shared surface atlas. */
    public CaveAtlasRegion peekPageRegion(int regionX, int regionZ, int pageX, int pageZ) {
        return peekPageRegion(regionX, regionZ, pageX, pageZ, false);
    }

    public CaveAtlasRegion peekGlowPageRegion(int regionX, int regionZ, int pageX, int pageZ) {
        return peekPageRegion(regionX, regionZ, pageX, pageZ, true);
    }

    private CaveAtlasRegion peekPageRegion(int regionX, int regionZ,
            int pageX, int pageZ, boolean glow) {
        String key = pageKey(regionX, regionZ, pageX, pageZ);
        if (!glow) markRegionVisible(key(regionX, regionZ));
        synchronized (pageCache) {
            PageTextureInfo info = pageCache.get(key);
            if (info == null || info.knownColumns <= 0) return null;
            info.lastVisibleRenderEpoch = renderEpoch;
            if (!info.initialized || info.atlasSlot < 0) {
                // Rendering is cache-only. Explicit viewport demand owns exact
                // restoration so a coarse fullscreen traversal cannot enqueue
                // hundreds of evicted leaves and make them fight for the finite
                // atlas every frame. Minimap/fullscreen hot windows already call
                // requestLeafResident(), which queues the bounded restore.
                return null;
            }
            MapResidencyManager.getInstance().touch("surface:" + key);
            if (info.publishedStamp != null) {
                int globalPageX = regionX * MapPageLayout.PAGES_PER_REGION + pageX;
                int globalPageZ = regionZ * MapPageLayout.PAGES_PER_REGION + pageZ;
                int variant = glow ? TileKey.VARIANT_SURFACE_GLOW
                        : TileKey.VARIANT_SURFACE_EXACT;
                MapGpuPageTableService.Resolved resolved =
                        SurfaceResidencyService.getInstance().resolve(
                                new TileKey(info.publishedStamp.sessionId(), 0, 0,
                                        globalPageX, globalPageZ, variant));
                if (resolved != null) {
                    PageTableEntry entry = resolved.entry();
                    long completeMask = info.completeSubtileMask & 0xFFFFL;
                    return new CaveAtlasRegion(resolved.texture(),
                            entry.sourceX(), entry.sourceY(), entry.sourceSize(),
                            entry.atlasSize(), 0, MapPageLayout.PAGE_SIZE,
                            completeMask, completeMask);
                }
            }
            CaveAtlasRegion region = leafAtlas.region(info.atlasSlot, glow);
            if (region == null) return null;
            long completeMask = info.completeSubtileMask & 0xFFFFL;
            return new CaveAtlasRegion(region.texture(), region.sourceX(),
                    region.sourceY(), region.sourceSize(), region.atlasSize(),
                    0, MapPageLayout.PAGE_SIZE, completeMask, completeMask);
        }
    }

    /** Complete 16x16 source units retained for a logical exact Surface page. */
    public int completeSubtileMask(int globalPageX, int globalPageZ) {
        int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
        int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        synchronized (pageCache) {
            PageTextureInfo info = pageCache.get(pageKey(
                    regionX, regionZ, pageX, pageZ));
            return info == null ? 0 : info.completeSubtileMask;
        }
    }

    public boolean hasAnyPageTexture(int regionX, int regionZ) {
        String prefix = key(regionX, regionZ) + ":";
        synchronized (pageCache) {
            for (var entry : pageCache.entrySet()) {
                if (entry.getKey().startsWith(prefix)
                        && entry.getValue().initialized
                        && entry.getValue().atlasSlot >= 0
                        && entry.getValue().knownColumns > 0) return true;
            }
        }
        return false;
    }

    /**
     * Exact-leaf residency test for recursive surface rendering. LOD parents are
     * derived caches and may cover cold descendants, but they must not suppress a
     * resident exact page that can provide newer or sharper data.
     */
    public boolean hasResidentPageInNode(int level, int nodeX, int nodeZ) {
        if (level < 1 || level > SurfaceLodTree.MAX_LEVEL) return false;
        synchronized (residentPageCountsByLevel) {
            return residentPageCountsByLevel.get(level)
                    .getOrDefault(packNode(nodeX, nodeZ), 0) > 0;
        }
    }


    private int fullscreenActiveWindow(float scale, int visiblePageCount) {
        boolean pressure = MapPerformanceGovernor.getInstance().underPressure();
        int zoomCap = MapSurfaceDemandPolicy.exactActiveWindow(
                scale, pressure, visiblePageCount);
        if (zoomCap <= 0) return 0;
        if (pressure) return Math.min(FULLSCREEN_ACTIVE_WINDOW_MIN, zoomCap);
        int adaptive = MapWorkScheduler.cpuTotalCost() < 240
                && MapWorkScheduler.cpuActiveCount() < 2
                ? FULLSCREEN_ACTIVE_WINDOW_MAX : 16;
        return Math.min(adaptive, zoomCap);
    }

    private static long packNode(int nodeX, int nodeZ) {
        return ((long) nodeX << 32) ^ (nodeZ & 0xFFFFFFFFL);
    }

    /** Maintains exact-leaf coverage counts for every ancestor in O(log LOD). */
    private void setPageResidentIndexed(PageTextureInfo info, boolean resident) {
        if (info == null || info.residentIndexed == resident) return;
        PageAddress address = parsePageKey(info.key);
        if (address == null) return;
        int globalPageX = address.regionX() * MapPageLayout.PAGES_PER_REGION
                + address.pageX();
        int globalPageZ = address.regionZ() * MapPageLayout.PAGES_PER_REGION
                + address.pageZ();
        int delta = resident ? 1 : -1;
        synchronized (residentPageCountsByLevel) {
            for (int level = 1; level <= SurfaceLodTree.MAX_LEVEL; level++) {
                int span = MapLodPolicy.pageSpanForBranch(level);
                long key = packNode(Math.floorDiv(globalPageX, span),
                        Math.floorDiv(globalPageZ, span));
                Map<Long, Integer> counts = residentPageCountsByLevel.get(level);
                int next = counts.getOrDefault(key, 0) + delta;
                if (next <= 0) counts.remove(key);
                else counts.put(key, next);
            }
        }
        info.residentIndexed = resident;
    }

    /** Publish-before-retire fence: an exact page remains until a branch covers it. */
    private boolean hasReplacementCoverage(PageTextureInfo info) {
        if (info == null || info.uploadedRevision == Long.MIN_VALUE) return false;
        PageAddress address = parsePageKey(info.key);
        if (address == null) return false;
        int globalPageX = address.regionX() * MapPageLayout.PAGES_PER_REGION
                + address.pageX();
        int globalPageZ = address.regionZ() * MapPageLayout.PAGES_PER_REGION
                + address.pageZ();
        return MapOverviewTextureManager.getInstance().hasPublishedSurfaceCoverage(
                globalPageX, globalPageZ, info.uploadedRevision);
    }


    /** True when a compatibility 512x512 texture can cover part of this branch. */
    public boolean hasResidentRegionInNode(int level, int nodeX, int nodeZ) {
        int worldSize = MapLodPolicy.worldSizeForBranch(level);
        int minWorldX = nodeX * worldSize;
        int minWorldZ = nodeZ * worldSize;
        int maxWorldX = minWorldX + worldSize - 1;
        int maxWorldZ = minWorldZ + worldSize - 1;
        int minRx = Math.floorDiv(minWorldX, 512);
        int maxRx = Math.floorDiv(maxWorldX, 512);
        int minRz = Math.floorDiv(minWorldZ, 512);
        int maxRz = Math.floorDiv(maxWorldZ, 512);
        synchronized (textureCache) {
            for (RegionTextureInfo info : textureCache.values()) {
                if (info.initialized && info.regionX >= minRx && info.regionX <= maxRx
                        && info.regionZ >= minRz && info.regionZ <= maxRz) return true;
            }
        }
        return false;
    }

    public void beginRenderBatch() {
        synchronized (textureCache) {
            if (renderBatchDepth == 0) renderEpoch++;
            renderBatchDepth++;
        }
    }

    public void endRenderBatch() {
        List<RegionTextureInfo> closeNow = null;
        List<PageTextureInfo> closePagesNow = null;
        synchronized (textureCache) {
            if (renderBatchDepth > 0) renderBatchDepth--;
            if (renderBatchDepth == 0) {
                if (!deferredCloses.isEmpty()) {
                    closeNow = new ArrayList<>(deferredCloses);
                    deferredCloses.clear();
                }
                if (!deferredPageCloses.isEmpty()) {
                    closePagesNow = new ArrayList<>(deferredPageCloses);
                    deferredPageCloses.clear();
                }
            }
        }
        if (closeNow != null) for (RegionTextureInfo info : closeNow) info.close();
        if (closePagesNow != null) for (PageTextureInfo info : closePagesNow) closePage(info);
    }

    public void uploadDirtyTextures() {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        uploadDirtyTextures(false);
    }

    /**
     * Foreground publication path used by the central map runner. It only
     * schedules and publishes exact 64x64 leaves. Compatibility 512x512 region
     * textures are excluded so they cannot compete with visible exact/LOD work.
     */
    public void uploadExactTextures(boolean force) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        uploadExactTextures(null, force);
    }

    /**
     * Uses only already-completed immutable Surface builds. This maintenance lane
     * never captures source data, submits CPU work or expands viewport demand, so a
     * visible Cave frame can safely spend leftover shared GPU budget without
     * delaying the primary projection. At most {@code budget} exact leaves are
     * attempted and denied uploads remain queued for a later frame.
     */
    public int publishCompletedExactTextures(int budget, long budgetNanos) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return 0;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return 0;
        int safeBudget = Math.max(0, budget);
        if (safeBudget == 0 || budgetNanos <= 0L) return 0;
        long started = System.nanoTime();
        if (started - lastCompletedMaintenanceNanos
                < COMPLETED_MAINTENANCE_INTERVAL_NANOS) return 0;
        lastCompletedMaintenanceNanos = started;
        long deadline = started + budgetNanos;
        if (deadline < started) deadline = Long.MAX_VALUE;
        synchronizeLeafAtlasStorage();
        return completedDrainPublished(drainCompletedExactTextures(
                safeBudget, deadline, null));
    }

    /**
     * Drains immutable CPU-ready leaves without admitting source capture or new
     * worker work. The high half of the return value is the number of queue entries
     * considered; the low half is the number that reached GPU residency. Surface
     * foreground publication uses the considered count as backpressure even when a
     * full atlas temporarily denies an upload.
     */
    private long drainCompletedExactTextures(int budget, long deadline,
            MapRequestLane preferredLane) {
        int published = 0;
        int considered = 0;
        while (published < budget
                && considered < COMPLETED_MAINTENANCE_SCAN_LIMIT
                && System.nanoTime() < deadline) {
            String leafKey = pollCompletedPagePublication(preferredLane);
            if (leafKey == null) break;
            considered++;
            PageTextureInfo page;
            synchronized (pageCache) {
                page = pageCache.get(leafKey);
            }
            if (page == null || page.pending == null || !page.pending.isDone()
                    || !MapManager.getInstance().isGenerationCurrent(page.generation)) {
                continue;
            }
            PageAddress address = parsePageKey(leafKey);
            if (address == null) continue;
            boolean wasInitialized = page.initialized;
            long previousRevision = page.uploadedRevision;
            long previousGpuRetryAfter = page.gpuRetryAfterNanos;
            // The completed-pending branch returns before the Region argument is
            // used. Passing null is deliberate: this lane must never fall through
            // into a new source capture/build.
            schedulePagePreparation(leafKey, page, null, address);
            if (page.initialized
                    && (!wasInitialized || page.uploadedRevision > previousRevision)) {
                published++;
            } else if (page.gpuRetryAfterNanos > previousGpuRetryAfter
                    && page.gpuRetryAfterNanos > System.nanoTime()) {
                /*
                 * The shared ledger is exhausted for this upload kind in the
                 * current physical frame. Xaero stops the upload slice here rather
                 * than probing dozens of other retained textures against the same
                 * limit. The completed payload remains queued by
                 * schedulePagePreparation(), so the next frame resumes without a
                 * rebuild or source read.
                 */
                break;
            }
        }
        return ((long) considered << 32) | (published & 0xFFFFFFFFL);
    }

    private static int completedDrainConsidered(long result) {
        return (int) (result >>> 32);
    }

    private static int completedDrainPublished(long result) {
        return (int) result;
    }

    public boolean hasCompletedExactTextures() {
        synchronized (completedPagePublications) {
            return !completedPagePublications.isEmpty();
        }
    }

    private int completedPagePublicationCount() {
        synchronized (completedPagePublications) {
            return completedPagePublications.size();
        }
    }

    /**
     * Publishes only the lane that owns the visible surface this tick. This keeps
     * background writer/style work from appearing as random islands while the
     * fullscreen frontier is progressing.
     */
    public void uploadExactTextures(MapRequestLane requestedLane, boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> uploadExactTextures(requestedLane, force));
            return;
        }
        synchronizeLeafAtlasStorage();
        long now = System.currentTimeMillis();
        pruneVisibility(now);
        prunePageDemands(now);
        MapRequestLane effectiveLane = requestedLane;
        boolean minimapHot = effectiveLane == MapRequestLane.MINIMAP
                || (effectiveLane == null && hasFreshMinimapDemand(now));
        // Publication is render-frame driven. Cadence only suppresses duplicate
        // GUI/screen callbacks in the same frame; the shared GPU ledger controls bursts.
        long cadenceMs = minimapHot ? 6L
                : effectiveLane == MapRequestLane.FULLSCREEN ? 0L : 32L;
        if (!force && now - lastUploadTime < cadenceMs) return;
        lastUploadTime = now;

        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        boolean pressured = governor.underPressure();
        boolean styleRefresh = effectiveLane == MapRequestLane.FULLSCREEN
                && now < styleRefreshUntilMs && !pressured;
        int pageBudget;
        long pageBudgetNanos;
        if (styleRefresh) {
            // Style refresh is resident-source work, but it still shares an 8 ms
            // target frame. Reveal a few centre-out pages per callback rather than
            // reserving five milliseconds and starving Minecraft rendering.
            pageBudget = Math.min(4, governor.texturePageBudget(true));
            pageBudgetNanos = Math.min(1_500_000L,
                    governor.textureUploadBudgetNanos(true));
        } else if (force) {
            // Focus/force bypasses cadence only. It must never bypass the current
            // frame deadline; doing so made one map subsystem consume 4-8 ms before
            // the world, entities, UI and the other map pipelines rendered.
            pageBudget = pressured ? 2 : governor.texturePageBudget(true);
            pageBudgetNanos = governor.textureUploadBudgetNanos(true);
        } else if (effectiveLane == MapRequestLane.MINIMAP) {
            boolean idleHeadroom = governor.hasStreamingHeadroom();
            pageBudget = pressured ? 1 : Math.min(idleHeadroom ? 6 : 4,
                    governor.texturePageBudget(true) + (idleHeadroom ? 2 : 0));
            pageBudgetNanos = governor.textureUploadBudgetNanos(true);
        } else if (effectiveLane == MapRequestLane.FULLSCREEN) {
            boolean idleHeadroom = governor.hasStreamingHeadroom();
            pageBudget = pressured ? 1 : Math.min(idleHeadroom ? 8 : 5,
                    governor.texturePageBudget(true) + (idleHeadroom ? 3 : 0));
            pageBudgetNanos = governor.textureUploadBudgetNanos(true);
        } else {
            pageBudget = 1;
            pageBudgetNanos = governor.textureUploadBudgetNanos(false);
        }
        long pageDeadline = System.nanoTime() + pageBudgetNanos;
        int completedBudget = styleRefresh ? Math.min(6, pageBudget)
                : force ? Math.min(6, pageBudget)
                : isForegroundLane(effectiveLane)
                        ? Math.min(governor.hasStreamingHeadroom() ? 4 : 2,
                                pageBudget)
                        : Math.min(1, pageBudget);
        long completedResult = drainCompletedExactTextures(
                completedBudget, pageDeadline, effectiveLane);
        int completedWork = Math.min(pageBudget,
                completedDrainConsidered(completedResult));
        int remainingBudget = Math.max(0, pageBudget - completedWork);

        int restoreBudget = force ? Math.min(2, remainingBudget)
                : effectiveLane == MapRequestLane.MINIMAP
                ? Math.min(2, remainingBudget)
                : effectiveLane == MapRequestLane.FULLSCREEN
                ? Math.min(1, remainingBudget) : 0;
        int restored = processResidencyRestores(
                restoreBudget, pageDeadline, effectiveLane);
        int dirtyBudget = Math.max(0, remainingBudget - restored);
        int completedBacklog = completedPagePublicationCount();
        if (effectiveLane != MapRequestLane.MINIMAP) {
            if (completedBacklog >= COMPLETED_PUBLICATION_HARD_LIMIT) {
                dirtyBudget = 0;
            } else if (completedBacklog >= COMPLETED_PUBLICATION_SOFT_LIMIT) {
                dirtyBudget = effectiveLane == MapRequestLane.FULLSCREEN
                        ? Math.min(1, dirtyBudget) : 0;
            }
        }
        processDirtyPages(dirtyBudget,
                force, pageDeadline, effectiveLane);

        // Compatibility-derived 512-region leaves are maintenance work. Never let
        // them leak into a visible minimap/fullscreen frame as unordered islands.
        if (force || effectiveLane == null
                || effectiveLane == MapRequestLane.BACKGROUND
                || effectiveLane == MapRequestLane.PREFETCH) {
            long leafDeadline = Math.min(pageDeadline,
                    System.nanoTime() + (force ? 400_000L : 250_000L));
            publishPendingLeafPages(force ? 2 : 1, leafDeadline);
        }
    }


    /**
     * Compatibility entry point retained for manual refresh/debug paths. Normal
     * gameplay calls {@link #uploadExactTextures(boolean)} through
     * {@link MapPublicationCoordinator} and therefore never builds legacy region
     * textures in the foreground.
     */
    public void uploadDirtyTextures(boolean force) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        uploadExactTextures(force);
        uploadLegacyTextures(force);
    }

    private void uploadLegacyTextures(boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> uploadLegacyTextures(force));
            return;
        }
        int countBudget = force ? 4 : 1;
        long deadline = System.nanoTime() + (force ? 8_000_000L : 1_000_000L);
        List<String> work = selectDirty(countBudget, force);
        for (String key : work) {
            if (System.nanoTime() > deadline && !force) {
                requeue(key);
                continue;
            }
            int separator = key.lastIndexOf('|');
            int comma = key.indexOf(',', separator + 1);
            int regionX = Integer.parseInt(key.substring(separator + 1, comma));
            int regionZ = Integer.parseInt(key.substring(comma + 1));
            if (hasCompleteExactRegion(regionX, regionZ)) continue;
            MapManager.Region region = MapManager.getInstance().getRegion(regionX, regionZ, false);
            if (region == null || !region.isLoaded()) {
                MapProcessor.getInstance().enqueueSurfaceLoad(regionX, regionZ,
                        distancePriority(regionX, regionZ));
                requeue(key);
                continue;
            }

            RegionTextureInfo info;
            synchronized (textureCache) {
                info = textureCache.get(key);
                if (info == null) {
                    info = createTextureInfo(regionX, regionZ,
                            MapManager.getInstance().getGeneration());
                    textureCache.put(key, info);
                    trimTextureCache();
                } else if (info.generation != MapManager.getInstance().getGeneration()) {
                    if (info.pending != null) info.pending.cancel(false);
                    info.pending = null;
                    info.generation = MapManager.getInstance().getGeneration();
                }
            }
            schedulePreparation(key, info, region, regionX, regionZ);
        }
    }

    private void processDirtyPages(int budget, boolean force, long deadlineNanos,
            MapRequestLane requestedLane) {
        List<String> work = selectDirtyPages(budget, force, requestedLane);
        for (String leafKey : work) {
            if (System.nanoTime() > deadlineNanos) {
                requeuePage(leafKey);
                continue;
            }
            PageAddress address = parsePageKey(leafKey);
            if (address == null) continue;
            int retainedMask = retainedLeafSourceMask(
                    address.regionX(), address.regionZ(),
                    address.pageX(), address.pageZ());
            MapManager.Region region = null;
            if (retainedMask != MapPageLayout.FULL_SUBTILE_MASK) {
                ensureRetainedSurfaceSource(address.regionX(), address.regionZ(),
                        address.pageX(), address.pageZ(), requestedLane,
                        surfaceLoadPriority(leafKey,
                                address.regionX(), address.regionZ()));
                if (retainedMask == 0) {
                    requeuePage(leafKey);
                    continue;
                }
            }
            PageTextureInfo page = ensurePageInfo(
                    leafKey, MapManager.getInstance().getGeneration());
            if (page == null) {
                requeuePage(leafKey);
                continue;
            }
            long requestedRevision;
            synchronized (dirtyTextures) {
                requestedRevision = pageRevisions.getOrDefault(leafKey, 0L);
            }
            if (page.uploadedRevision >= requestedRevision
                    && pageSnapshotCoversSourceForLane(page,
                            address.regionX(), address.regionZ(),
                            address.pageX(), address.pageZ(), requestedLane)) {
                if (page.atlasSlot < 0 && isForegroundLane(requestedLane)) {
                    enqueueResidencyRestore(leafKey);
                } else if (page.atlasSlot >= 0) {
                    clearPageDemandLane(leafKey, requestedLane == null
                            ? MapRequestLane.BACKGROUND : requestedLane);
                }
                synchronized (dirtyTextures) {
                    removeDirtyPageLocked(leafKey);
                    dirtyPageSubtileMasks.remove(leafKey);
                    firstDirtyPageNanos.remove(leafKey);
                    lastDirtyPageNanos.remove(leafKey);
                }
                continue;
            }
            schedulePagePreparation(leafKey, page, region, address);
        }
    }

    private List<String> selectDirtyPages(int budget, boolean force,
            MapRequestLane requestedLane) {
        int safeBudget = Math.max(0, budget);
        List<String> selected = new ArrayList<>(safeBudget);
        if (safeBudget == 0) return selected;
        long nowNanos = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        synchronized (dirtyTextures) {
            if (dirtyPages.isEmpty()) return selected;

            /*
             * PASS104: current viewport ownership is the first queue, historical
             * dirty order is only the fairness fallback. PASS103 could have ~443
             * dirty leaves but only ~25 current demands; a changed visible leaf
             * buried hundreds of entries deep then looked permanently stuck.
             * pageDemands is small and bounded by viewport leases, so ranking it
             * first is cheap and mirrors Xaero's viewer-centric writer priority.
             */
            List<String> demanded = new ArrayList<>();
            for (Map.Entry<String, SurfacePageDemand> entry : pageDemands.entrySet()) {
                String leafKey = entry.getKey();
                if (!dirtyPages.contains(leafKey) || !isCurrentDimensionKey(leafKey)) {
                    continue;
                }
                MapRequestLane lane = entry.getValue() == null ? null
                        : entry.getValue().effectiveLane(nowMs);
                if (!force && requestedLane != null && lane != requestedLane) continue;
                boolean styleFastPath = lane == MapRequestLane.FULLSCREEN
                        && nowMs < styleRefreshUntilMs;
                if (isPageReadyForPublication(leafKey, nowNanos, force)
                        || styleFastPath) {
                    demanded.add(leafKey);
                }
            }
            demanded.sort((left, right) -> compareSurfacePageDemand(
                    left, right, nowMs));
            for (String leafKey : demanded) {
                if (selected.size() >= safeBudget) break;
                selected.add(leafKey);
            }

            /*
             * Examine a bounded rotating slice instead of every historical dirty page. A key
             * removed from the deque is either selected, discarded as stale, or
             * appended to the tail. This gives eventual fairness without O(N)
             * render-thread work when exploration creates a multi-thousand-page
             * backlog.
             */
            int scanLimit = MapPerformanceGovernor.getInstance().underPressure()
                    ? 96 : 384;
            int toScan = Math.min(scanLimit, dirtyPageOrder.size());
            for (int scanned = 0; scanned < toScan; scanned++) {
                String leafKey = dirtyPageOrder.pollFirst();
                if (leafKey == null) break;
                dirtyPageQueued.remove(leafKey);
                if (!dirtyPages.contains(leafKey)) continue;
                if (selected.contains(leafKey)) continue;

                SurfacePageDemand demand = pageDemands.get(leafKey);
                MapRequestLane lane = demand == null
                        ? null : demand.effectiveLane(nowMs);
                if (lane == null && nowNanos - lastDirtyPageNanos.getOrDefault(
                        leafKey, nowNanos) >= HISTORICAL_DIRTY_RETENTION_NANOS) {
                    /* Exact Surface source remains durable in retained/db/pageRevision.
                     * Drop only the reconstructable scheduler marker. A later viewport
                     * demand compares uploadedRevision with pageRevision and requeues a
                     * full leaf if this revision is still stale. */
                    dirtyPages.remove(leafKey);
                    dirtyPageSubtileMasks.remove(leafKey);
                    firstDirtyPageNanos.remove(leafKey);
                    lastDirtyPageNanos.remove(leafKey);
                    ExactPageStateTracker.getInstance().removeIfState(
                            "surface:" + leafKey, ExactPageState.REQUESTED);
                    continue;
                }
                boolean eligible = isCurrentDimensionKey(leafKey)
                        && (force || requestedLane == null || lane == requestedLane);
                if (eligible) {
                    boolean styleFastPath = lane == MapRequestLane.FULLSCREEN
                            && nowMs < styleRefreshUntilMs;
                    eligible = isPageReadyForPublication(leafKey, nowNanos, force)
                            || styleFastPath;
                }
                if (!eligible) {
                    enqueueDirtyPageLocked(leafKey);
                    continue;
                }

                if (selected.size() < safeBudget) {
                    selected.add(leafKey);
                    continue;
                }
                int worstIndex = 0;
                for (int index = 1; index < selected.size(); index++) {
                    if (compareSurfacePageDemand(selected.get(worstIndex),
                            selected.get(index), nowMs) < 0) {
                        worstIndex = index;
                    }
                }
                if (compareSurfacePageDemand(leafKey, selected.get(worstIndex),
                        nowMs) < 0) {
                    String displaced = selected.set(worstIndex, leafKey);
                    enqueueDirtyPageLocked(displaced);
                } else {
                    enqueueDirtyPageLocked(leafKey);
                }
            }
            selected.sort((left, right) -> compareSurfacePageDemand(
                    left, right, nowMs));
            for (String leafKey : selected) {
                removeDirtyPageLocked(leafKey);
            }
        }
        return selected;
    }

    private int compareSurfacePageDemand(String left, String right, long nowMs) {
        SurfacePageDemand leftDemand = pageDemands.get(left);
        SurfacePageDemand rightDemand = pageDemands.get(right);
        MapRequestLane leftLane = leftDemand == null ? null : leftDemand.effectiveLane(nowMs);
        MapRequestLane rightLane = rightDemand == null ? null : rightDemand.effectiveLane(nowMs);
        int leftRank = leftLane == null ? 0 : leftLane.rank();
        int rightRank = rightLane == null ? 0 : rightLane.rank();
        int byLane = Integer.compare(rightRank, leftRank);
        if (byLane != 0) return byLane;
        if (leftLane == MapRequestLane.FULLSCREEN
                && rightLane == MapRequestLane.FULLSCREEN) {
            long leftOrdinal = leftDemand == null
                    ? Long.MAX_VALUE : leftDemand.fullscreenOrdinal;
            long rightOrdinal = rightDemand == null
                    ? Long.MAX_VALUE : rightDemand.fullscreenOrdinal;
            int byOrdinal = Long.compare(leftOrdinal, rightOrdinal);
            if (byOrdinal != 0) return byOrdinal;
        }
        int leftPriority = leftDemand == null ? Integer.MIN_VALUE
                : leftDemand.effectivePriority(nowMs);
        int rightPriority = rightDemand == null ? Integer.MIN_VALUE
                : rightDemand.effectivePriority(nowMs);
        int byPriority = Integer.compare(rightPriority, leftPriority);
        if (byPriority != 0) return byPriority;
        boolean leftVisible = isRegionRecentlyVisible(regionKeyFromPageKey(left));
        boolean rightVisible = isRegionRecentlyVisible(regionKeyFromPageKey(right));
        if (leftVisible != rightVisible) return leftVisible ? -1 : 1;
        long leftFirst = firstDirtyPageNanos.getOrDefault(left, Long.MAX_VALUE);
        long rightFirst = firstDirtyPageNanos.getOrDefault(right, Long.MAX_VALUE);
        return Long.compare(leftFirst, rightFirst);
    }


    private void enqueueCompletedPagePublication(String leafKey) {
        if (leafKey == null) return;
        /*
         * A completed immutable payload has left the capture/build stage. Keeping
         * the same key in dirtyPages lets one CPU_READY page consume both the
         * completed-publication budget and the dirty-build budget every frame. If
         * its original lane was BACKGROUND, the protected minimap reserve can deny
         * both attempts forever and strand the whole fullscreen slice. Ownership is
         * exclusive: completedPagePublications owns the key until it is uploaded or
         * discarded; a newer source revision re-adds it to dirtyPages afterwards.
         */
        synchronized (dirtyTextures) {
            removeDirtyPageLocked(leafKey);
            firstDirtyPageNanos.remove(leafKey);
            lastDirtyPageNanos.remove(leafKey);
        }
        synchronized (completedPagePublications) {
            if (completedPagePublicationKeys.add(leafKey)) {
                completedPagePublications.addLast(leafKey);
            }
        }
    }

    private void enqueueResidencyRestore(String leafKey) {
        if (leafKey == null) return;
        synchronized (residencyRestoreQueue) {
            if (!residencyRestoreKeys.add(leafKey)) return;
            while (residencyRestoreQueue.size() >= MAX_RESIDENCY_RESTORE_QUEUE) {
                String retired = residencyRestoreQueue.pollFirst();
                if (retired != null) residencyRestoreKeys.remove(retired);
            }
            residencyRestoreQueue.addLast(leafKey);
        }
    }

    private String pollResidencyRestore() {
        synchronized (residencyRestoreQueue) {
            String leafKey = residencyRestoreQueue.pollFirst();
            if (leafKey != null) residencyRestoreKeys.remove(leafKey);
            return leafKey;
        }
    }

    private void forgetResidencyRestore(String leafKey) {
        if (leafKey == null) return;
        synchronized (residencyRestoreQueue) {
            if (!residencyRestoreKeys.remove(leafKey)) return;
            residencyRestoreQueue.remove(leafKey);
        }
    }

    private int processResidencyRestores(int budget, long deadlineNanos,
            MapRequestLane requestedLane) {
        int restored = 0;
        int considered = 0;
        long now = System.nanoTime();
        while (restored < budget
                && considered < RESIDENCY_RESTORE_SCAN_LIMIT
                && System.nanoTime() < deadlineNanos) {
            String leafKey = pollResidencyRestore();
            if (leafKey == null) break;
            considered++;
            PageTextureInfo page;
            synchronized (pageCache) {
                page = pageCache.get(leafKey);
                if (page == null || page.atlasSlot >= 0 || page.initialized
                        || page.colorPixels == null || page.glowPixels == null
                        || page.knownColumns <= 0
                        || !MapManager.getInstance().isGenerationCurrent(
                                page.generation)) {
                    continue;
                }
                if (!isRecentlyRenderVisible(page)
                        && !hasActiveForegroundDemand(leafKey,
                                System.currentTimeMillis())) continue;
                if (page.residencyRetryAfterNanos > now
                        || page.gpuRetryAfterNanos > now) {
                    enqueueResidencyRestore(leafKey);
                    continue;
                }
            }
            MapRequestLane lane = requestedLane == null
                    ? effectivePageLane(leafKey, System.currentTimeMillis())
                    : requestedLane;
            if (!surfaceGpuLaneEligible(lane, System.nanoTime())) {
                enqueueResidencyRestore(leafKey);
                if (requestedLane != null) break;
                continue;
            }
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.SURFACE_EXACT,
                    lane, lane == MapRequestLane.MINIMAP)) {
                deferSurfaceGpuRetry(page, lane);
                enqueueResidencyRestore(leafKey);
                break;
            }
            resetSurfaceGpuRetry(page, lane);
            if (!restoreSurfacePageResidency(page, lane)) {
                synchronized (pageCache) {
                    page.residencyRetryAfterNanos = System.nanoTime()
                            + RESIDENCY_RESTORE_RETRY_NANOS;
                }
                enqueueResidencyRestore(leafKey);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent(
                        "SURFACE_RESIDENCY_RESTORE_DEFERRED", 500L)) {
                    recorder.event("SURFACE_RESIDENCY_RESTORE_DEFERRED",
                            "page=" + leafKey + " lane=" + lane
                                    + " reason=no_cold_atlas_victim");
                }
                break;
            }
            restored++;
        }
        return restored;
    }

    private static boolean isForegroundLane(MapRequestLane lane) {
        return lane == MapRequestLane.MINIMAP
                || lane == MapRequestLane.FULLSCREEN;
    }

    private boolean isRecentlyRenderVisible(PageTextureInfo page) {
        return page != null && page.lastVisibleRenderEpoch > 0L
                && renderEpoch - page.lastVisibleRenderEpoch <= 1L;
    }

    private boolean hasActiveForegroundDemand(String leafKey, long nowMs) {
        synchronized (dirtyTextures) {
            SurfacePageDemand demand = pageDemands.get(leafKey);
            return demand != null
                    && (demand.isLaneActive(MapRequestLane.MINIMAP, nowMs)
                    || demand.isLaneActive(MapRequestLane.FULLSCREEN, nowMs));
        }
    }

    private boolean isSurfaceResidencyProtected(PageTextureInfo page,
            long nowNanos, long nowMs) {
        return page != null && (isRecentlyRenderVisible(page)
                || hasActiveForegroundDemand(page.key, nowMs)
                || (page.lastGpuPublicationNanos > 0L
                && nowNanos - page.lastGpuPublicationNanos
                        < SURFACE_RESIDENCY_MIN_HOLD_NANOS));
    }

    private String pollCompletedPagePublication(MapRequestLane preferredLane) {
        synchronized (completedPagePublications) {
            int scanned = 0;
            while (scanned < completedPublicationScanBuffer.length
                    && !completedPagePublications.isEmpty()) {
                String leafKey = completedPagePublications.removeFirst();
                completedPagePublicationKeys.remove(leafKey);
                if (isCurrentDimensionKey(leafKey)) {
                    completedPublicationScanBuffer[scanned++] = leafKey;
                }
            }
            if (scanned == 0) return null;

            long nowMs = System.currentTimeMillis();
            long nowNanos = System.nanoTime();
            int best = -1;
            for (int index = 0; index < scanned; index++) {
                String candidate = completedPublicationScanBuffer[index];
                if (!completedPublicationGpuEligible(candidate, nowNanos)
                        || !completedPublicationMatchesLane(candidate, preferredLane, nowMs)) {
                    continue;
                }
                if (best < 0 || compareCompletedPublication(candidate,
                        completedPublicationScanBuffer[best], preferredLane, nowMs) < 0) {
                    best = index;
                }
            }
            String selected = best < 0 ? null : completedPublicationScanBuffer[best];
            for (int index = 0; index < scanned; index++) {
                String leafKey = completedPublicationScanBuffer[index];
                completedPublicationScanBuffer[index] = null;
                if (index == best) continue;
                if (completedPagePublicationKeys.add(leafKey)) {
                    completedPagePublications.addLast(leafKey);
                }
            }
            return selected;
        }
    }

    /**
     * Foreground drains must never spend their bounded page budget repeatedly on a
     * stale BACKGROUND/PREFETCH payload. A completed page becomes eligible as soon
     * as the current viewport observes it in the requested lane (or a stronger one),
     * at which point publication is safely promoted without rebuilding its immutable
     * pixels.
     */
    private boolean completedPublicationGpuEligible(String leafKey, long nowNanos) {
        long pageRetryAfter;
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(leafKey);
            if (page == null) return true;
            pageRetryAfter = page.gpuRetryAfterNanos;
        }
        /*
         * Request lanes are viewport ownership, not a permanent property of a
         * completed payload. PASS99 kept the lane captured when the CPU job was
         * scheduled, so an offscreen result from an old fullscreen viewport could
         * continue reserving foreground GPU budget for seconds. Xaero re-prioritizes
         * retained textures from the current loaded/loading view. Do the same:
         * current demand owns the lane; no current demand means background.
         */
        MapRequestLane demandLane = activePageLane(
                leafKey, System.currentTimeMillis());
        MapRequestLane effectiveLane = demandLane == null
                ? MapRequestLane.BACKGROUND : demandLane;
        return pageRetryAfter <= nowNanos
                && surfaceGpuLaneEligible(effectiveLane, nowNanos);
    }

    private static long surfaceGpuRetryDelayNanos(MapRequestLane lane) {
        MapRequestLane effective = normalizedGpuLane(lane);
        boolean pressure = MapPerformanceGovernor.getInstance().underPressure();
        return switch (effective) {
            case MINIMAP -> pressure ? 8_000_000L : 4_000_000L;
            case FULLSCREEN -> pressure ? 16_000_000L : 8_000_000L;
            default -> pressure ? 40_000_000L : 24_000_000L;
        };
    }

    private static MapRequestLane normalizedGpuLane(MapRequestLane lane) {
        return lane == null ? MapRequestLane.BACKGROUND : lane;
    }

    private boolean surfaceGpuLaneEligible(MapRequestLane lane, long nowNanos) {
        return true;
    }

    private void deferSurfaceGpuRetry(PageTextureInfo page,
            MapRequestLane lane) {
        if (page == null) return;
        page.gpuReservationFailures = Math.min(8, page.gpuReservationFailures + 1);
        long baseDelay = surfaceGpuRetryDelayNanos(lane);
        int shift = Math.min(3, Math.max(0, page.gpuReservationFailures - 1));
        long cap = switch (normalizedGpuLane(lane)) {
            case MINIMAP -> 24_000_000L;
            case FULLSCREEN -> 64_000_000L;
            default -> 192_000_000L;
        };
        long delay = Math.min(cap, baseDelay << shift);
        page.gpuRetryAfterNanos = System.nanoTime() + delay;
    }

    private void resetSurfaceGpuRetry(PageTextureInfo page,
            MapRequestLane lane) {
        if (page != null) {
            page.gpuReservationFailures = 0;
            page.gpuRetryAfterNanos = 0L;
        }
    }

    private boolean completedPublicationMatchesLane(String leafKey,
            MapRequestLane preferredLane, long nowMs) {
        if (preferredLane == null) return true;
        MapRequestLane active = activePageLane(leafKey, nowMs);
        return active != null
                && (active == preferredLane || active.strongerThan(preferredLane));
    }

    private int compareCompletedPublication(String left, String right,
            MapRequestLane preferredLane, long nowMs) {
        synchronized (dirtyTextures) {
            SurfacePageDemand leftDemand = pageDemands.get(left);
            SurfacePageDemand rightDemand = pageDemands.get(right);
            MapRequestLane leftLane = leftDemand == null
                    ? null : leftDemand.effectiveLane(nowMs);
            MapRequestLane rightLane = rightDemand == null
                    ? null : rightDemand.effectiveLane(nowMs);
            boolean leftPreferred = preferredLane != null
                    && leftLane == preferredLane;
            boolean rightPreferred = preferredLane != null
                    && rightLane == preferredLane;
            if (leftPreferred != rightPreferred) return leftPreferred ? -1 : 1;
            return compareSurfacePageDemand(left, right, nowMs);
        }
    }

    private void forgetCompletedPagePublication(String leafKey) {
        if (leafKey == null) return;
        synchronized (completedPagePublications) {
            if (!completedPagePublicationKeys.remove(leafKey)) return;
            completedPagePublications.remove(leafKey);
        }
    }

    private boolean isRegionRecentlyVisible(String regionKey) {
        synchronized (visibleTextures) {
            return visibleTextures.containsKey(regionKey);
        }
    }

    private boolean isPageReadyForPublication(String leafKey, long now, boolean force) {
        if (force) return true;
        long first = firstDirtyPageNanos.getOrDefault(leafKey, now);
        long last = lastDirtyPageNanos.getOrDefault(leafKey, first);
        return now - last >= PAGE_DIRTY_QUIET_NANOS
                || now - first >= PAGE_DIRTY_MAX_WAIT_NANOS;
    }

    private void requeuePage(String leafKey) {
        synchronized (dirtyTextures) {
            long now = System.nanoTime();
            firstDirtyPageNanos.putIfAbsent(leafKey, now);
            lastDirtyPageNanos.putIfAbsent(leafKey, now);
            enqueueDirtyPageLocked(leafKey);
        }
    }

    private void schedulePagePreparation(String leafKey, PageTextureInfo page,
            MapManager.Region region, PageAddress address) {
        if (!MapManager.getInstance().isGenerationCurrent(page.generation)) return;
        if (page.pending != null) {
            if (!page.pending.isDone()) {
                requeuePage(leafKey);
                return;
            }
            boolean retainCompleted = false;
            try {
                MapTextureBuildWorker.PreparedPair prepared = page.pending.join();
                ExactPageStateTracker.getInstance().transition(
                        "surface:" + leafKey, ExactPageState.CPU_READY,
                        page.pendingLane, prepared.revision());
                long currentRevision;
                synchronized (dirtyTextures) {
                    currentRevision = pageRevisions.getOrDefault(leafKey, 0L);
                }
                if (!page.pendingCompletionRecorded) {
                    MapPipelineTelemetry.getInstance().recordExactBuildCompleted();
                    page.pendingCompletionRecorded = true;
                }
                boolean published = false;
                if (MapManager.getInstance().isGenerationCurrent(page.generation)
                        && prepared.revision() >= page.uploadedRevision) {
                    MapRequestLane originalLane = page.pendingLane == null
                            ? MapRequestLane.BACKGROUND : page.pendingLane;
                    MapRequestLane demandLane = activePageLane(
                            leafKey, System.currentTimeMillis());
                    /*
                     * Do not let the lane captured at schedule time become sticky.
                     * If the viewport moved away while this immutable build was in
                     * flight, publish it as background cache work instead of
                     * stealing the new viewport's foreground GPU slice.
                     */
                    MapRequestLane uploadLane = demandLane == null
                            ? MapRequestLane.BACKGROUND : demandLane;
                    if (uploadLane != originalLane) {
                        page.pendingLane = uploadLane;
                        resetSurfaceGpuRetry(page, uploadLane);
                        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                        if (recorder.shouldEmitEvent(
                                "SURFACE_COMPLETED_LANE_PROMOTED:" + leafKey, 1000L)) {
                            recorder.event("SURFACE_COMPLETED_LANE_PROMOTED",
                                    "page=" + leafKey + " from=" + originalLane
                                            + " to=" + uploadLane);
                        }
                    }
                    // The dirty-page selector already processes fullscreen leaves
                    // by traversal ordinal. A second global publication frontier can
                    // deadlock the viewport when the earliest leaf is waiting on an
                    // absent source/halo region, leaving later completed pages black.
                    // Publish any selected coherent page and let the ordinal sorter
                    // preserve coarse ordering without a hard dependency.
                    long nowNanos = System.nanoTime();
                    if (page.gpuRetryAfterNanos > nowNanos) {
                        retainCompleted = true;
                        return;
                    }
                    if (!MapGpuBudgetController.getInstance().tryReserve(
                            MapGpuBudgetController.UploadKind.SURFACE_EXACT,
                            uploadLane, uploadLane == MapRequestLane.MINIMAP)) {
                        deferSurfaceGpuRetry(page, uploadLane);
                        // Keep the completed immutable build instead of discarding
                        // it and restyling the same page when the next GPU window
                        // opens.
                        retainCompleted = true;
                        return;
                    }
                    resetSurfaceGpuRetry(page, uploadLane);
                    // Apply into the CPU loading bank first. A cold exact page is
                    // exposed to the atlas only after the full 4x4 body transaction
                    // is coherent; an already published page remains last-good until
                    // its full replacement is ready.
                    published = applyPreparedPage(page, prepared, address);
                    if (!published && page.atlasSlot < 0
                            && page.completeSubtileMask != 0
                            && pageSnapshotCoversRetainedSource(page,
                                    address.regionX(), address.regionZ(),
                                    address.pageX(), address.pageZ())
                            && prepared.styled() != null && prepared.glow() != null) {
                        // A coherent page that only lacks a safely replaceable atlas
                        // slot should keep its immutable completion. A partial loading
                        // bank must NOT keep the old completion pinned: later writer
                        // publications need to schedule the missing chunks and merge
                        // them into that bank.
                        retainCompleted = true;
                    }
                    boolean settled = published && pageCoversSourceForLane(page,
                            address.regionX(), address.regionZ(),
                            address.pageX(), address.pageZ(), uploadLane);
                    if (settled && uploadLane != null) {
                        // Every lane owns a lease, not just fullscreen. Keeping a
                        // settled MINIMAP lease alive filled its eight-page admission
                        // window until TTL expiry, so restart replay advanced in
                        // disconnected bursts despite the archive already being warm.
                        clearPageDemandLane(leafKey, uploadLane);
                    }
                }
                MapRequestLane completionLane = page.pendingLane == null
                        ? MapRequestLane.BACKGROUND : page.pendingLane;
                boolean coversCurrentSource = published && pageCoversSourceForLane(
                        page, address.regionX(), address.regionZ(),
                        address.pageX(), address.pageZ(), completionLane);
                if (coversCurrentSource && currentRevision <= prepared.revision()) {
                    /* A page-edge sweep may legitimately cover fewer than sixteen
                     * chunks. It is still a coherent GPU transaction for every source
                     * child that currently exists. Future retained publication wakes
                     * the same page and atomically expands/repairs the coverage. */
                    synchronized (dirtyTextures) {
                        removeDirtyPageLocked(leafKey);
                        dirtyPageSubtileMasks.remove(leafKey);
                        firstDirtyPageNanos.remove(leafKey);
                        lastDirtyPageNanos.remove(leafKey);
                    }
                } else if (!surfacePageHasRetainedSource(address)) {
                    // The source changed while this immutable batch was running and
                    // the prepared payload no longer covers the current retained
                    // sweep. Park until the next chunk-complete wake-up instead of
                    // looping zero-output builds.
                    settleSurfacePageWithoutCompleteSource(address, completionLane);
                } else {
                    page.retryAfterNanos = Math.max(page.retryAfterNanos,
                            System.nanoTime() + 16_000_000L);
                    requeuePage(leafKey);
                }
            } catch (RuntimeException exception) {
                Throwable terminalFailure = unwrapCompletionFailure(exception);
                boolean cancelled = terminalFailure
                        instanceof java.util.concurrent.CancellationException;
                ExactPageStateTracker.getInstance().transition(
                        "surface:" + leafKey,
                        cancelled ? ExactPageState.STALE_GENERATION
                                : ExactPageState.FAILED_RETRYABLE,
                        page.pendingLane, pageRevisions.getOrDefault(leafKey, 0L));
                if (!cancelled) {
                    MapPipelineTelemetry.getInstance().recordExactBuildDiscarded();
                    LOGGER.debug("Discarded failed surface page job {}",
                            leafKey, terminalFailure);
                }
                requeuePage(leafKey);
            } finally {
                if (retainCompleted) {
                    enqueueCompletedPagePublication(leafKey);
                } else {
                    forgetCompletedPagePublication(leafKey);
                    page.pending = null;
                    page.pendingLane = null;
                    page.pendingCompletionRecorded = false;
                }
            }
            return;
        }

        MapRequestLane buildLane = effectivePageLane(
                leafKey, System.currentTimeMillis());
        if (scheduleSurfaceBatch(address, buildLane)) {
            return;
        }

        /*
         * PASS109: there is no renderer-side Region snapshot fallback anymore.
         * Every lane consumes the same immutable SurfaceRegionSource authority.
         * A missing batch plan means the writer/warming path has not published a
         * coherent page yet; retain the demand and wait for its wake-up.
         */
        ensureRetainedSurfaceSource(address.regionX(), address.regionZ(),
                address.pageX(), address.pageZ(), buildLane, surfaceLoadPriority(
                        leafKey, address.regionX(), address.regionZ()));
        requeuePage(leafKey);
    }

    private boolean applyPreparedPage(PageTextureInfo page,
            MapTextureBuildWorker.PreparedPair prepared, PageAddress address) {
        if (prepared == null) return false;
        RevisionStamp preparedStamp = prepared.stamp();
        if (preparedStamp != null
                && !MapSessionManager.getInstance().isCurrent(preparedStamp)) {
            MapPipelineTelemetry.getInstance().recordExactBuildDiscarded();
            return false;
        }
        int expected = MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE;
        if (prepared.styled() == null || prepared.styled().length < expected
                || prepared.glow() == null || prepared.glow().length < expected) {
            return false;
        }
        long[] incomingRows = prepared.pageKnownRows() != null
                && prepared.pageKnownRows().length > 0
                && prepared.pageKnownRows()[0] != null
                ? prepared.pageKnownRows()[0]
                : inferKnownRows(prepared.styled());
        int incomingSubtiles = MapPageLayout.completeSubtileMask(incomingRows)
                & prepared.updateSubtileMask();
        if (incomingSubtiles == 0) {
            // Sparse columns from an in-progress scan are not a coherent map tile.
            // Publishing them produced the isolated tree tops visible in the report.
            page.retryAfterNanos = System.nanoTime() + 32_000_000L;
            ExactPageStateTracker.getInstance().transition(
                    "surface:" + page.key, ExactPageState.REQUESTED,
                    page.pendingLane, prepared.revision());
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "SURFACE_SUBTILE_INCOMPLETE_REJECTED:" + page.key, 1000L)) {
                recorder.event("SURFACE_SUBTILE_INCOMPLETE_REJECTED",
                        "page=" + page.key + " revision=" + prepared.revision()
                                + " lane=" + page.pendingLane + " known_columns="
                                + countKnownColumns(incomingRows));
            }
            return false;
        }

        // A branch worker may still read the last color snapshot shared with it.
        // Detach exactly once after such a publication; between branch milestones
        // the render thread owns the retained arrays and merges subtiles in place.
        int[] mergedColor = page.colorPixels == null
                ? new int[expected]
                : page.pixelSnapshotShared
                        ? java.util.Arrays.copyOf(page.colorPixels, expected)
                        : page.colorPixels;
        int[] mergedGlow = page.glowPixels == null
                ? new int[expected] : page.glowPixels;
        long[] mergedRows = page.knownRows == null
                ? new long[MapPageLayout.PAGE_SIZE] : page.knownRows;
        mergeCompleteSubtiles(mergedColor, prepared.styled(), incomingSubtiles);
        mergeCompleteSubtiles(mergedGlow, prepared.glow(), incomingSubtiles);
        mergeSubtileKnownRows(mergedRows, incomingSubtiles);
        int previousCompleteSubtiles = page.completeSubtileMask;
        int mergedSubtiles = previousCompleteSubtiles | incomingSubtiles;
        int knownColumns = countKnownColumns(mergedRows);

        /*
         * PASS109 / Xaero loaded-loading parity:
         *
         * Xaero Minimap writes into loadingBlocks and swaps the completed grid into
         * loadedBlocks. Xaero World Map visits all 4x4 MapTiles of a MapTileChunk
         * before updateBuffers(). SimpleMap previously made each complete 16x16
         * child visible immediately. In the PASS108 log, 674/679 first Surface GPU
         * publications were incomplete pages (279 had only one 16x16 chunk), which
         * is exactly the disconnected checkerboard/island effect seen on screen.
         *
         * Keep partial children only in this CPU loading bank. A page that has never
         * been published cannot enter the exact page table until all sixteen body
         * chunks are coherent. Existing full pages remain last-good while their
         * replacement is prepared.
         */
        int retainedSubtilesNow = retainedLeafSourceMask(
                address.regionX(), address.regionZ(), address.pageX(), address.pageZ());
        boolean capturesCurrentSweep = retainedSubtilesNow != 0
                && (mergedSubtiles & retainedSubtilesNow) == retainedSubtilesNow;
        if (!page.initialized
                && mergedSubtiles != MapPageLayout.FULL_SUBTILE_MASK
                && !capturesCurrentSweep) {
            page.colorPixels = mergedColor;
            page.glowPixels = mergedGlow;
            page.pixelSnapshotShared = false;
            page.knownRows = mergedRows;
            page.completeSubtileMask = mergedSubtiles;
            page.knownColumns = knownColumns;
            page.retryAfterNanos = System.nanoTime() + 32_000_000L;
            ExactPageStateTracker.getInstance().transition(
                    "surface:" + page.key, ExactPageState.CPU_PARTIAL,
                    page.pendingLane, prepared.revision());
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "SURFACE_PAGE_LOADING_BANK:" + page.key, 500L)) {
                recorder.event("SURFACE_PAGE_LOADING_BANK",
                        "page=" + page.key + " revision=" + prepared.revision()
                                + " lane=" + page.pendingLane + " known_columns="
                                + knownColumns + " subtile_mask=0x"
                                + Integer.toHexString(mergedSubtiles)
                                + " retained_mask=0x"
                                + Integer.toHexString(retainedSubtilesNow));
            }
            return false;
        }

        if (page.atlasSlot < 0) {
            page.atlasSlot = acquireSurfaceAtlasSlot(page.key);
            if (page.atlasSlot < 0) {
                page.retryAfterNanos = System.nanoTime() + 16_000_000L;
                return false;
            }
        }
        boolean firstGpuPublication = !page.initialized;
        String stateKey = "surface:" + page.key;
        ExactPageStateTracker.getInstance().transition(
                stateKey, ExactPageState.UPLOAD_QUEUED,
                page.pendingLane, prepared.revision());
        long exactUploadStart = System.nanoTime();
        TileKey colorTileKey = exactTileKey(preparedStamp, address,
                TileKey.VARIANT_SURFACE_EXACT);
        int coverageSubtiles = mergedSubtiles;
        /* The atlas API uploads one 64x64 page physically; coverageSubtiles is the
         * semantic page-table mask. Reserve the real physical byte cost while never
         * advertising absent edge children as valid. */
        int uploadBytes = Math.toIntExact(2L * Integer.BYTES
                * MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE);
        UploadCommand upload = new UploadCommand(colorTileKey,
                preparedStamp, page.pendingLane, uploadBytes,
                prepared.revision(), null,
                () -> leafAtlas.upload(page.atlasSlot, mergedColor, mergedGlow),
                null, null);
        SurfacePublicationService.getInstance().executeInline(upload);
        long exactUploadNanos = System.nanoTime() - exactUploadStart;
        MapPipelineTelemetry.getInstance().recordStageNanos(
                MapPipelineStage.EXACT_UPLOAD, exactUploadNanos);
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.SURFACE_EXACT,
                exactUploadNanos);
        page.colorPixels = mergedColor;
        page.glowPixels = mergedGlow;
        page.pixelSnapshotShared = false;
        page.knownRows = mergedRows;
        page.completeSubtileMask = mergedSubtiles;
        if (mergedSubtiles != previousCompleteSubtiles) {
            MapResidencyManager.getInstance().markCoverageChanged(
                    MapResidencyManager.Kind.SURFACE_EXACT);
        }
        page.uploadedRevision = Math.max(page.uploadedRevision, prepared.revision());
        page.publishedStamp = preparedStamp != null ? preparedStamp
                : MapSessionManager.getInstance().activeStamp();
        recordGpuPublication(page, address, page.uploadedRevision);
        MapResidencyManager.getInstance().markPixelsChanged(
                MapResidencyManager.Kind.SURFACE_EXACT);
        page.initialized = true;
        page.lastGpuPublicationNanos = System.nanoTime();
        page.knownColumns = knownColumns;
        stageExactPageTable(page, address, page.uploadedRevision);
        page.retryAfterNanos = 0L;
        page.authority = PageAuthority.EXACT;
        setPageResidentIndexed(page, true);
        String residentKey = "surface:" + page.key;
        MapResidencyManager.getInstance().register(
                residentKey,
                MapResidencyManager.Kind.SURFACE_EXACT,
                2L * MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE * Integer.BYTES,
                () -> evictSurfacePageForBudget(page.key));
        MapResidencyManager.getInstance().enforceBudget(
                residentKey, page.pendingLane);
        ExactPageStateTracker.getInstance().transition(
                stateKey, ExactPageState.GPU_READY,
                page.pendingLane, page.uploadedRevision);
        if (firstGpuPublication) {
            MapPipelineTelemetry.getInstance().recordExactGpuReady();
        }
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (firstGpuPublication || recorder.shouldEmitEvent(
                "SURFACE_PAGE_ATOMIC_REFRESH:" + page.key, 250L)) {
            recorder.event(firstGpuPublication
                            ? "SURFACE_PAGE_GPU_READY" : "SURFACE_PAGE_ATOMIC_REFRESH",
                    "page=" + page.key + " region=" + address.regionX() + ','
                            + address.regionZ() + " local=" + address.pageX() + ','
                            + address.pageZ() + " revision=" + page.uploadedRevision
                            + " lane=" + page.pendingLane + " known_columns="
                            + knownColumns + " subtile_mask=0x"
                            + Integer.toHexString(mergedSubtiles)
                            + " uploaded_mask=0x"
                            + Integer.toHexString(coverageSubtiles) + " upload_ms="
                            + String.format(java.util.Locale.ROOT, "%.4f",
                            exactUploadNanos / 1_000_000.0));
        }

        int completeSubtileCount = Integer.bitCount(mergedSubtiles);
        // Coarse branch derivation walks/reduces the full 64x64 leaf twice. Exact
        // display is already updated per 16x16 chunk, so refresh the far-zoom
        // hierarchy only at quarter-page milestones (and on every update once a
        // page is complete). This removes synchronous render-thread amplification
        // without hiding progressive exploration.
        if (firstGpuPublication
                || mergedSubtiles == MapPageLayout.FULL_SUBTILE_MASK
                || (completeSubtileCount & 3) == 0) {
            MapOverviewTextureManager.getInstance().updateSurfaceLeafPage(
                    address.regionX() * MapPageLayout.PAGES_PER_REGION
                            + address.pageX(),
                    address.regionZ() * MapPageLayout.PAGES_PER_REGION
                            + address.pageZ(),
                    mergedColor, mergedRows,
                    mergedSubtiles == MapPageLayout.FULL_SUBTILE_MASK,
                    page.uploadedRevision, page.pendingLane);
            page.pixelSnapshotShared = true;
        }
        trimPageCache();
        return true;
    }

    private int chooseSurfaceBatchSize(RevisionStamp stamp,
            PageAddress requested, MapRequestLane lane) {
        /*
         * The live minimap is latency-sensitive and its source arrives one complete
         * 16x16 chunk transaction at a time. PASS100 still grouped a single dirty
         * minimap leaf into 2x2 batches (100 halo chunks) even when only 1/4 pages
         * were attached; the log shows those bursts beside the 27.6 ms frame. Xaero
         * advances individual MinimapChunk/tile state and never widens one fresh
         * chunk into a multi-page capture transaction. Fullscreen retains batching
         * because throughput, not per-frame latency, is its primary concern.
         */
        if (lane == MapRequestLane.MINIMAP) return 1;
        // Large 2x2/4x4 source batches are throughput optimisations only when the
        // frame has headroom. Under pressure they amplify capture, materialisation
        // and completed-publication backlog, so fall back to one resumable leaf.
        if (MapPerformanceGovernor.getInstance().underPressure()) return 1;
        boolean initialized = false;
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(pageKey(requested.regionX(),
                    requested.regionZ(), requested.pageX(), requested.pageZ()));
            initialized = page != null && page.initialized && page.atlasSlot >= 0;
        }
        boolean sourceReady = SurfaceRegionSourceDatabase.getInstance()
                .isLeafSourceReady(stamp, requested.regionX(),
                        requested.regionZ(), requested.pageX(), requested.pageZ());
        long nowMs = System.currentTimeMillis();
        int groupStartX = (requested.pageX()
                / SurfaceRegionSourceDatabase.DEFAULT_BATCH_PAGES)
                * SurfaceRegionSourceDatabase.DEFAULT_BATCH_PAGES;
        int groupStartZ = (requested.pageZ()
                / SurfaceRegionSourceDatabase.DEFAULT_BATCH_PAGES)
                * SurfaceRegionSourceDatabase.DEFAULT_BATCH_PAGES;
        int demanded = 0;
        synchronized (dirtyTextures) {
            for (int z = groupStartZ; z < groupStartZ + 4; z++) {
                for (int x = groupStartX; x < groupStartX + 4; x++) {
                    SurfacePageDemand demand = pageDemands.get(pageKey(
                            requested.regionX(), requested.regionZ(), x, z));
                    if (demand != null
                            && demand.effectiveLane(nowMs) != null) {
                        demanded++;
                    }
                }
            }
        }
        return SurfaceBatchPolicy.chooseBatchSize(lane, initialized,
                sourceReady, demanded);
    }

    private boolean scheduleSurfaceBatch(PageAddress requested,
            MapRequestLane requestedLane) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null || !stamp.isComplete() || !stamp.isCurrent()) return false;
        MapRequestLane initialLane = requestedLane == null
                ? MapRequestLane.BACKGROUND : requestedLane;
        /*
         * Resolve the requested leaf's live ownership before deciding transaction
         * geometry. PASS101 chose 2x2 using a historical FULLSCREEN/BACKGROUND lane,
         * then discovered a live MINIMAP demand while attaching leaves and promoted
         * the already-sized batch. That is how two lane=MINIMAP pages=2/4 batches
         * still escaped the nominal MINIMAP=1x1 rule. Xaero does not merge its
         * latency-sensitive MinimapChunk writer into a wider world-map transaction.
         */
        long demandNowMs = System.currentTimeMillis();
        synchronized (dirtyTextures) {
            SurfacePageDemand requestedDemand = pageDemands.get(pageKey(
                    requested.regionX(), requested.regionZ(),
                    requested.pageX(), requested.pageZ()));
            MapRequestLane liveLane = requestedDemand == null ? null
                    : requestedDemand.effectiveLane(demandNowMs);
            if (liveLane != null && liveLane.strongerThan(initialLane)) {
                initialLane = liveLane;
            }
        }
        int requestedRetainedMask = retainedLeafSourceMask(
                requested.regionX(), requested.regionZ(),
                requested.pageX(), requested.pageZ());
        if (requestedRetainedMask != MapPageLayout.FULL_SUBTILE_MASK) {
            ensureRetainedSurfaceSource(requested.regionX(), requested.regionZ(),
                    requested.pageX(), requested.pageZ(), initialLane,
                    initialLane.priorityBase());
            if (requestedRetainedMask == 0) {
                settleSurfacePageWithoutCompleteSource(requested, initialLane);
                return true;
            }
        }
        int completedBacklog = completedPagePublicationCount();
        boolean backpressured = initialLane != MapRequestLane.MINIMAP
                && (completedBacklog >= COMPLETED_PUBLICATION_HARD_LIMIT
                || (completedBacklog >= COMPLETED_PUBLICATION_SOFT_LIMIT
                && (initialLane == MapRequestLane.BACKGROUND
                || initialLane == MapRequestLane.PREFETCH)));
        if (backpressured) {
            String requestedKey = pageKey(requested.regionX(), requested.regionZ(),
                    requested.pageX(), requested.pageZ());
            requeuePage(requestedKey);
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "SURFACE_PUBLICATION_BACKPRESSURE:" + initialLane, 500L)) {
                recorder.event("SURFACE_PUBLICATION_BACKPRESSURE",
                        "lane=" + initialLane + " completed=" + completedBacklog
                                + " page=" + requestedKey);
            }
            return true;
        }
        int batchSize = chooseSurfaceBatchSize(stamp, requested, initialLane);
        int batchPageX = (requested.pageX() / batchSize) * batchSize;
        int batchPageZ = (requested.pageZ() / batchSize) * batchSize;
        String batchKey = key(requested.regionX(), requested.regionZ())
                + ":batch:" + batchSize + ':' + batchPageX + "," + batchPageZ;
        String clusterKey = key(requested.regionX(), requested.regionZ())
                + ":cluster:" + initialLane.name() + ':'
                + (requested.pageX() >> 2) + ',' + (requested.pageZ() >> 2);
        synchronized (pendingSurfaceBatches) {
            if (pendingSurfaceBatches.containsKey(batchKey)) {
                requeuePage(pageKey(requested.regionX(), requested.regionZ(),
                        requested.pageX(), requested.pageZ()));
                return true;
            }
            long now = System.nanoTime();
            long previousCluster = captureClusterAttemptNanos.getOrDefault(
                    clusterKey, 0L);
            int clusterDeferrals = captureClusterDeferrals.getOrDefault(clusterKey, 0);
            long clusterRetryDelay = surfaceClusterRetryDelayNanos(
                    initialLane, clusterDeferrals);
            if (now - previousCluster < clusterRetryDelay) {
                requeuePage(pageKey(requested.regionX(), requested.regionZ(),
                        requested.pageX(), requested.pageZ()));
                return true;
            }
            long previous = batchCaptureAttemptNanos.getOrDefault(batchKey, 0L);
            int deferrals = batchCaptureDeferrals.getOrDefault(batchKey, 0);
            long retryDelay = surfaceCaptureRetryDelayNanos(initialLane, deferrals);
            if (now - previous < retryDelay) {
                requeuePage(pageKey(requested.regionX(), requested.regionZ(),
                        requested.pageX(), requested.pageZ()));
                return true;
            }
            captureClusterAttemptNanos.put(clusterKey, now);
            batchCaptureAttemptNanos.put(batchKey, now);
        }

        long[] pageRevisionSnapshot = new long[batchSize * batchSize];
        int[] requestedSubtileMasks = new int[batchSize * batchSize];
        boolean[] attachPage = new boolean[batchSize * batchSize];
        boolean[] residentPage = new boolean[batchSize * batchSize];
        synchronized (pageCache) {
            for (int localZ = 0; localZ < batchSize; localZ++) {
                for (int localX = 0; localX < batchSize; localX++) {
                    int index = localZ * batchSize + localX;
                    PageTextureInfo page = pageCache.get(pageKey(
                            requested.regionX(), requested.regionZ(),
                            batchPageX + localX, batchPageZ + localZ));
                    residentPage[index] = page != null
                            && page.initialized && page.atlasSlot >= 0;
                }
            }
        }
        MapRequestLane lane = initialLane;
        long maxRevision = 1L;
        long leafMask = 0L;
        int activePageCount = 0;
        synchronized (dirtyTextures) {
            for (int localZ = 0; localZ < batchSize; localZ++) {
                for (int localX = 0; localX < batchSize; localX++) {
                    int pageX = batchPageX + localX;
                    int pageZ = batchPageZ + localZ;
                    String pageKey = pageKey(requested.regionX(), requested.regionZ(),
                            pageX, pageZ);
                    int index = localZ * batchSize + localX;
                    long revision = pageRevisions.getOrDefault(pageKey, 0L);
                    pageRevisionSnapshot[index] = revision;
                    requestedSubtileMasks[index] = dirtyPageSubtileMasks
                            .getOrDefault(pageKey,
                                    MapPageLayout.FULL_SUBTILE_MASK);
                    maxRevision = Math.max(maxRevision, revision);
                    SurfacePageDemand demand = pageDemands.get(pageKey);
                    boolean requestedPage = pageX == requested.pageX()
                            && pageZ == requested.pageZ();
                    boolean pageActuallyDirty = dirtyPages.contains(pageKey);
                    boolean sourceReady = SurfaceRegionSourceDatabase.getInstance()
                            .isLeafSourceReady(stamp, requested.regionX(),
                                    requested.regionZ(), pageX, pageZ);
                    // Demand controls priority/residency, not invalidation. Keep
                    // stronger latency lanes out of a weaker neighbour batch. A
                    // fullscreen/background transaction may reuse its own nearby
                    // leaves, but it must never absorb a MINIMAP leaf and then turn
                    // the whole 2x2/4x4 transaction into minimap work after sizing.
                    MapRequestLane candidate = demand == null ? null
                            : demand.effectiveLane(demandNowMs);
                    boolean liveDemand = candidate != null;
                    boolean strongerNeighbour = !requestedPage && candidate != null
                            && candidate.strongerThan(lane);
                    attachPage[index] = requestedPage
                            || (!strongerNeighbour && pageActuallyDirty
                                    && SurfaceBatchPolicy.shouldBuildPage(false,
                                            sourceReady, liveDemand, lane))
                            || (!strongerNeighbour && !residentPage[index]
                                    && SurfaceBatchPolicy.shouldBuildPage(false,
                                            sourceReady, liveDemand, lane));
                    if (attachPage[index]) {
                        activePageCount++;
                        leafMask |= 1L << (pageZ
                                * MapPageLayout.PAGES_PER_REGION + pageX);
                    }
                }
            }
        }
        synchronized (pageCache) {
            for (int localZ = 0; localZ < batchSize; localZ++) {
                for (int localX = 0; localX < batchSize; localX++) {
                    int index = localZ * batchSize + localX;
                    if (!attachPage[index]) continue;
                    int pageX = batchPageX + localX;
                    int pageZ = batchPageZ + localZ;
                    int required = retainedLeafSourceMask(
                            requested.regionX(), requested.regionZ(), pageX, pageZ);
                    if (required == 0) {
                        attachPage[index] = false;
                        requestedSubtileMasks[index] = 0;
                        continue;
                    }
                    PageTextureInfo resident = pageCache.get(pageKey(
                            requested.regionX(), requested.regionZ(),
                            pageX, pageZ));
                    int published = resident == null ? 0
                            : resident.completeSubtileMask;
                    requestedSubtileMasks[index] =
                            (requestedSubtileMasks[index] & required)
                                    | (required & ~published);
                }
            }
        }
        /*
         * PASS108 ready-mask authority. Dirty/requested bits describe desired work,
         * but a worker may only be submitted for body subtiles that the immutable
         * source currently contains. PASS107 could attach a page for an unavailable
         * neighbour bit merely because another chunk in the 64x64 page was known;
         * the worker then produced updateSubtileMask=0 and the same page was probed
         * again. Clamp first, detach zero-work neighbours, and let source publication
         * wake the requested leaf when its body arrives.
         */
        activePageCount = 0;
        leafMask = 0L;
        boolean requestedPageHasReadyWork = false;
        for (int localZ = 0; localZ < batchSize; localZ++) {
            for (int localX = 0; localX < batchSize; localX++) {
                int index = localZ * batchSize + localX;
                if (!attachPage[index]) continue;
                if (requestedSubtileMasks[index] == 0) {
                    attachPage[index] = false;
                    continue;
                }
                int pageX = batchPageX + localX;
                int pageZ = batchPageZ + localZ;
                activePageCount++;
                leafMask |= 1L << (pageZ
                        * MapPageLayout.PAGES_PER_REGION + pageX);
                if (pageX == requested.pageX() && pageZ == requested.pageZ()) {
                    requestedPageHasReadyWork = true;
                }
            }
        }
        if (!requestedPageHasReadyWork) {
            settleSurfacePageWithoutCompleteSource(requested, lane);
            return true;
        }

        if (lane == MapRequestLane.MINIMAP && batchSize != 1) {
            // Defensive guard against a future caller/demand race violating the
            // latency contract. Requeue rather than materialising a wide minimap
            // capture plan on the render thread.
            requeuePage(pageKey(requested.regionX(), requested.regionZ(),
                    requested.pageX(), requested.pageZ()));
            return true;
        }
        if (!MapWorkScheduler.canAdmitCpu(lane,
                Math.max(8, activePageCount * 6))) {
            requeuePage(pageKey(requested.regionX(), requested.regionZ(),
                    requested.pageX(), requested.pageZ()));
            return true;
        }

        SurfaceRegionSourceDatabase.BatchSourcePlan source =
                SurfaceRegionSourceDatabase.getInstance().captureBatchPlan(stamp,
                        requested.regionX(), requested.regionZ(), batchPageX,
                        batchPageZ, requested.pageX(), requested.pageZ(),
                        batchSize, batchSize, MapConfig.minimapNightMode != 0, lane);
        if (source == null) {
            synchronized (pendingSurfaceBatches) {
                batchCaptureDeferrals.merge(batchKey, 1,
                        (previous, one) -> Math.min(8, previous + one));
                captureClusterDeferrals.merge(clusterKey, 1,
                        (previous, one) -> Math.min(6, previous + one));
            }
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("SURFACE_BATCH_WAITING_SOURCE:" + batchKey,
                    1000L)) {
                recorder.event("SURFACE_BATCH_WAITING_SOURCE",
                        "batch=" + batchKey + " lane=" + lane
                                + " requested_page=" + requested.pageX() + ','
                                + requested.pageZ());
            }
            requeuePage(pageKey(requested.regionX(), requested.regionZ(),
                    requested.pageX(), requested.pageZ()));
            return true;
        }
        synchronized (pendingSurfaceBatches) {
            batchCaptureAttemptNanos.remove(batchKey);
            batchCaptureDeferrals.remove(batchKey);
            captureClusterAttemptNanos.remove(clusterKey);
            captureClusterDeferrals.remove(clusterKey);
        }
        MapStyleSnapshot style = captureStyleSnapshot(source, stamp);
        final MapRequestLane admittedLane = lane;
        final long batchRevision = maxRevision;
        final long batchMask = leafMask;
        final long generation = MapManager.getInstance().getGeneration();
        final int submittedPageCount = activePageCount;
        final long submittedCacheEpoch = cacheEpoch;
        MapWorkKey graphKey = new MapWorkKey(stamp, requested.regionX(),
                requested.regionZ(), MapWorkStage.GPU_PREPARE, Integer.MIN_VALUE);
        MapWorkGraph graph = MapWorkGraph.getInstance();
        graph.request(graphKey, batchRevision, batchMask);
        CompletableFuture<PreparedSurfaceRegionBatch> batchFuture =
                MapTextureBuildWorker.tryBuildSurfaceBatch(source, style,
                        pageRevisionSnapshot, attachPage,
                        requestedSubtileMasks,
                        () -> MapManager.getInstance().isGenerationCurrent(generation)
                                && stamp.isCurrent(),
                        admittedLane.executorPriority());
        if (batchFuture == null) {
            source.close();
            graph.defer(graphKey);
            requeuePage(pageKey(requested.regionX(), requested.regionZ(),
                    requested.pageX(), requested.pageZ()));
            return true;
        }
        synchronized (pendingSurfaceBatches) {
            pendingSurfaceBatches.put(batchKey, batchFuture);
        }
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent("SURFACE_BATCH_SUBMITTED:" + batchKey, 1000L)) {
            recorder.event("SURFACE_BATCH_SUBMITTED",
                    "batch=" + batchKey + " lane=" + admittedLane
                            + " pages=" + submittedPageCount + '/'
                            + (batchSize * batchSize)
                            + " revision=" + batchRevision);
        }
        MapRequestLane batchLane = admittedLane;
        Object[] submittedPageFutures = new Object[batchSize * batchSize];
        for (int localZ = 0; localZ < batchSize; localZ++) {
            for (int localX = 0; localX < batchSize; localX++) {
                int pageX = batchPageX + localX;
                int pageZ = batchPageZ + localZ;
                String leafKey = pageKey(requested.regionX(), requested.regionZ(),
                        pageX, pageZ);
                int pageIndex = localZ * batchSize + localX;
                if (!attachPage[pageIndex]) continue;
                PageTextureInfo info = ensurePageInfo(leafKey, generation);
                if (info == null || info.pending != null) continue;
                int capturedX = localX;
                int capturedZ = localZ;
                CompletableFuture<MapTextureBuildWorker.PreparedPair> pageFuture =
                        batchFuture.thenApply(batch -> {
                            MapTextureBuildWorker.PreparedPair pair =
                                    batch.page(capturedX, capturedZ);
                            if (pair == null) {
                                throw new java.util.concurrent.CompletionException(
                                        new IllegalStateException(
                                                "Missing prepared batch leaf"));
                            }
                            return pair;
                        });
                info.pending = pageFuture;
                submittedPageFutures[pageIndex] = pageFuture;
                pageFuture.whenComplete((pair, failure) -> {
                    if (failure == null && pair != null
                            && submittedCacheEpoch == cacheEpoch
                            && stamp.isCurrent()) {
                        enqueueCompletedPagePublication(leafKey);
                    }
                });
                info.pendingLane = batchLane;
                info.pendingCompletionRecorded = false;
                MapPipelineTelemetry.getInstance().recordExactBuildQueued();
                ExactPageStateTracker.getInstance().transition(
                        "surface:" + leafKey, ExactPageState.BUILDING,
                        batchLane, pageRevisionSnapshot[pageIndex]);
            }
        }
        batchFuture.whenComplete((batch, failure) -> {
            source.close();
            synchronized (pendingSurfaceBatches) {
                pendingSurfaceBatches.remove(batchKey, batchFuture);
            }
            boolean callbackCurrent = submittedCacheEpoch == cacheEpoch
                    && stamp.isCurrent();
            if (!callbackCurrent) {
                for (int localZ = 0; localZ < batchSize; localZ++) {
                    for (int localX = 0; localX < batchSize; localX++) {
                        int pageIndex = localZ * batchSize + localX;
                        if (!attachPage[pageIndex]) continue;
                        String staleKey = pageKey(requested.regionX(),
                                requested.regionZ(), batchPageX + localX,
                                batchPageZ + localZ);
                        synchronized (pageCache) {
                            PageTextureInfo stale = pageCache.get(staleKey);
                            if (stale != null
                                    && stale.pending == submittedPageFutures[pageIndex]) {
                                stale.pending = null;
                                stale.pendingLane = null;
                                stale.pendingCompletionRecorded = false;
                            }
                        }
                        forgetCompletedPagePublication(staleKey);
                        ExactPageStateTracker.getInstance().transition(
                                "surface:" + staleKey,
                                ExactPageState.STALE_GENERATION,
                                admittedLane, pageRevisionSnapshot[pageIndex]);
                    }
                }
                return;
            }
            Throwable terminalFailure = unwrapCompletionFailure(failure);
            boolean cancelled = terminalFailure
                    instanceof java.util.concurrent.CancellationException;
            for (int localZ = 0; localZ < batchSize; localZ++) {
                for (int localX = 0; localX < batchSize; localX++) {
                    int pageIndex = localZ * batchSize + localX;
                    if (!attachPage[pageIndex]) continue;
                    String completedLeafKey = pageKey(requested.regionX(),
                            requested.regionZ(), batchPageX + localX,
                            batchPageZ + localZ);
                    ExactPageStateTracker.getInstance().transition(
                            "surface:" + completedLeafKey,
                            failure == null && batch != null
                                    ? ExactPageState.CPU_READY
                                    : cancelled ? ExactPageState.STALE_GENERATION
                                    : ExactPageState.FAILED_RETRYABLE,
                            admittedLane, pageRevisionSnapshot[pageIndex]);
                }
            }
            if (failure == null) {
                graph.markPrepared(graphKey, batchRevision, batchMask);
                MapDebugRecorder completionRecorder = MapDebugRecorder.getInstance();
                if (completionRecorder.shouldEmitEvent(
                        "SURFACE_BATCH_PREPARED:" + batchKey, 1000L)) {
                    completionRecorder.event("SURFACE_BATCH_PREPARED",
                            "batch=" + batchKey + " pages="
                                    + submittedPageCount + '/'
                                    + (batchSize * batchSize)
                                    + " revision=" + batchRevision);
                }
            } else if (cancelled) {
                // Viewport-scoped work is routinely purged when panning, changing
                // zoom or switching map modes. Cancellation is a normal terminal
                // state, not a failed Surface build and should not pollute failure
                // telemetry or trigger error-oriented retry behaviour.
                graph.defer(graphKey);
            } else {
                graph.defer(graphKey);
                MapDebugRecorder completionRecorder = MapDebugRecorder.getInstance();
                if (completionRecorder.shouldEmitEvent(
                        "SURFACE_BATCH_FAILED:" + batchKey, 500L)) {
                    completionRecorder.event("SURFACE_BATCH_FAILED",
                            "batch=" + batchKey + " failure="
                                    + terminalFailure.getClass().getSimpleName() + ':'
                                    + String.valueOf(terminalFailure.getMessage()));
                }
            }
            for (int localZ = 0; localZ < batchSize; localZ++) {
                for (int localX = 0; localX < batchSize; localX++) {
                    int pageIndex = localZ * batchSize + localX;
                    if (attachPage[pageIndex]) {
                        requeuePage(pageKey(requested.regionX(), requested.regionZ(),
                                batchPageX + localX, batchPageZ + localZ));
                    }
                }
            }
        });
        return true;
    }

    /**
     * Source capture happens on the render thread because the retained Region is
     * mutable. Missing disk data used to retry every 5 ms, repeatedly rebuilding
     * capture plans while zooming over an unexplored or still-loading area. Keep
     * the minimap responsive, but progressively cool fullscreen/background misses
     * until a region-load wake-up clears the delay.
     */
    private static long surfaceCaptureRetryDelayNanos(MapRequestLane lane,
            int consecutiveDeferrals) {
        MapRequestLane effective = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        long base = switch (effective) {
            // Completed 16x16 chunks call wakeRegionCaptureForChunk(), so polling
            // an unchanged missing page at frame cadence only burns render-thread
            // CPU. These remain short fallbacks for disk/source completions whose
            // explicit wake-up was lost.
            case MINIMAP -> 50_000_000L;
            case FULLSCREEN -> 100_000_000L;
            case PREFETCH -> 180_000_000L;
            case BACKGROUND -> 250_000_000L;
        };
        int shift = Math.min(4, Math.max(0, consecutiveDeferrals));
        return Math.min(750_000_000L, base << shift);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /** Aggregate cold-leaf misses so one unexplored viewport cannot probe every
     * local page independently on the render thread. The lane remains part of the
     * key, therefore a fullscreen miss never delays the player-centred minimap. */
    private static long surfaceClusterRetryDelayNanos(MapRequestLane lane,
            int consecutiveDeferrals) {
        MapRequestLane effective = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        long base = switch (effective) {
            case MINIMAP -> 80_000_000L;
            case FULLSCREEN -> 150_000_000L;
            case PREFETCH -> 250_000_000L;
            case BACKGROUND -> 350_000_000L;
        };
        int shift = Math.min(3, Math.max(0, consecutiveDeferrals));
        return Math.min(500_000_000L, base << shift);
    }

    MapStyleSnapshot captureStyleSnapshot(
            SurfaceRegionSourceDatabase.BatchSourcePlan source,
            RevisionStamp stamp) {
        var level = Minecraft.getInstance().level;
        Registry<Biome> biomeRegistry = level == null ? null
                : level.registryAccess().registryOrThrow(Registries.BIOME);

        // Resource/model colour resolution is client-thread-affine. Resolve only
        // unique raw IDs here; the expensive final palette union/remap stays in
        // BatchSourcePlan.assemble() on a worker.
        Map<String, Biome> resolvedBiomes = new HashMap<>();
        Set<String> seenBiomes = new HashSet<>();
        source.forEachBiomePaletteEntry(rawId -> {
            String id = rawId == null || rawId.isBlank()
                    ? "minecraft:plains" : rawId;
            if (!seenBiomes.add(id)) return;
            Biome biome = resolveStyleBiome(id, biomeRegistry,
                    stamp.sessionId(), stamp.styleGeneration());
            if (biome != null) resolvedBiomes.put(id, biome);
        });

        Map<String, Integer> selectedColorCache = MapConfig.blockColourMode == 1
                ? vanillaBlockColorsCache : blockColorsCache;
        Map<String, Integer> paletteColors = new HashMap<>();
        Map<String, BlockTintPolicy> paletteTintPolicies = new HashMap<>();
        Set<String> paletteTintDisabled = new HashSet<>();
        Set<String> seenBlocks = new HashSet<>();
        source.forEachBlockPaletteEntry(rawId -> {
            String blockId = rawId == null || rawId.isBlank()
                    ? "minecraft:air" : rawId;
            if (!seenBlocks.add(blockId)) return;
            resolveBlockColor(blockId, MapConfig.blockColourMode);
            if (MapConfig.blockColourMode == 0) resolveTintPolicy(blockId);
            Integer color = selectedColorCache.get(blockId);
            if (color != null) paletteColors.put(blockId, color);
            BlockTintPolicy tintPolicy = tintPolicyCache.get(blockId);
            if (tintPolicy != null) paletteTintPolicies.put(blockId, tintPolicy);
            if (MapConfig.blockColorOverrides.containsKey(blockId)) {
                paletteTintDisabled.add(blockId);
            }
        });

        return MapStyleSnapshot.takeOwnershipById(stamp, resolvedBiomes,
                paletteColors.isEmpty() ? Map.of() : Map.copyOf(paletteColors),
                paletteTintPolicies.isEmpty() ? Map.of()
                        : Map.copyOf(paletteTintPolicies),
                paletteTintDisabled.isEmpty() ? Set.of()
                        : Set.copyOf(paletteTintDisabled),
                MapConfig.blockColourMode, MapConfig.displayFlowers,
                MapConfig.terrainSlopes, MapConfig.mapColorProfile);
    }

    MapStyleSnapshot captureStyleSnapshot(
            MapManager.RegionLodSnapshot source,
            RevisionStamp stamp) {
        return captureStyleSnapshot(
                java.util.Arrays.asList(source.biomePaletteUnsafe()),
                java.util.Arrays.asList(source.blockPaletteUnsafe()), stamp);
    }

    private MapStyleSnapshot captureStyleSnapshot(
            List<String> biomePalette, List<String> blockPalette,
            RevisionStamp stamp) {
        var level = Minecraft.getInstance().level;
        Registry<Biome> biomeRegistry = level == null ? null
                : level.registryAccess().registryOrThrow(Registries.BIOME);
        Biome[] resolvedBiomes = new Biome[biomePalette.size()];
        for (int index = 0; index < resolvedBiomes.length; index++) {
            resolvedBiomes[index] = resolveStyleBiome(
                    biomePalette.get(index), biomeRegistry,
                    stamp.sessionId(), stamp.styleGeneration());
        }
        Map<String, Integer> selectedColorCache = MapConfig.blockColourMode == 1
                ? vanillaBlockColorsCache : blockColorsCache;
        Map<String, Integer> paletteColors = new HashMap<>(
                Math.max(4, blockPalette.size() * 2));
        Map<String, BlockTintPolicy> paletteTintPolicies = new HashMap<>(
                Math.max(4, blockPalette.size() * 2));
        Set<String> paletteTintDisabled = new HashSet<>();
        for (String blockId : blockPalette) {
            resolveBlockColor(blockId, MapConfig.blockColourMode);
            if (MapConfig.blockColourMode == 0) resolveTintPolicy(blockId);
            Integer color = selectedColorCache.get(blockId);
            if (color != null) paletteColors.put(blockId, color);
            BlockTintPolicy tintPolicy = tintPolicyCache.get(blockId);
            if (tintPolicy != null) paletteTintPolicies.put(blockId, tintPolicy);
            if (MapConfig.blockColorOverrides.containsKey(blockId)) {
                paletteTintDisabled.add(blockId);
            }
        }
        Map<String, Integer> immutableColors = paletteColors.isEmpty()
                ? Map.of() : Map.copyOf(paletteColors);
        Map<String, BlockTintPolicy> immutableTintPolicies =
                paletteTintPolicies.isEmpty() ? Map.of()
                : Map.copyOf(paletteTintPolicies);
        Set<String> immutableTintDisabled = paletteTintDisabled.isEmpty()
                ? Set.of() : Set.copyOf(paletteTintDisabled);
        return MapStyleSnapshot.takeOwnership(stamp, resolvedBiomes,
                immutableColors, immutableTintPolicies, immutableTintDisabled,
                MapConfig.blockColourMode, MapConfig.displayFlowers,
                MapConfig.terrainSlopes, MapConfig.mapColorProfile);
    }

    private Biome resolveStyleBiome(String biomeId, Registry<Biome> registry,
            long sessionId, long styleGeneration) {
        String safeId = biomeId == null || biomeId.isBlank()
                ? "minecraft:plains" : biomeId;
        synchronized (styleBiomeCache) {
            if (styleBiomeCacheSessionId != sessionId
                    || styleBiomeCacheGeneration != styleGeneration) {
                styleBiomeCache.clear();
                styleBiomeCacheSessionId = sessionId;
                styleBiomeCacheGeneration = styleGeneration;
            }
            if (styleBiomeCache.containsKey(safeId)) {
                return styleBiomeCache.get(safeId);
            }
            Biome resolved = null;
            try {
                if (registry != null) {
                    resolved = registry.get(ResourceLocation.parse(safeId));
                }
            } catch (RuntimeException ignored) { }
            styleBiomeCache.put(safeId, resolved);
            return resolved;
        }
    }

    private void recordGpuPublication(PageTextureInfo page, PageAddress address,
            long revision) {
        RevisionStamp stamp = page.publishedStamp;
        if (stamp == null || !stamp.isCurrent()) return;
        long mask = 1L << (address.pageZ() * MapPageLayout.PAGES_PER_REGION
                + address.pageX());
        MapWorkKey key = new MapWorkKey(stamp, address.regionX(),
                address.regionZ(), MapWorkStage.GPU_UPLOAD, Integer.MIN_VALUE);
        MapWorkGraph graph = MapWorkGraph.getInstance();
        graph.request(key, Math.max(1L, revision), mask);
        graph.markGpuPublished(key, Math.max(1L, revision), mask);
    }

    private void recordGpuEviction(PageTextureInfo page) {
        RevisionStamp stamp = page == null ? null : page.publishedStamp;
        PageAddress address = page == null ? null : parsePageKey(page.key);
        if (stamp == null || address == null) return;
        long mask = 1L << (address.pageZ() * MapPageLayout.PAGES_PER_REGION
                + address.pageX());
        MapWorkKey key = new MapWorkKey(stamp, address.regionX(),
                address.regionZ(), MapWorkStage.GPU_UPLOAD, Integer.MIN_VALUE);
        MapWorkGraph.getInstance().markGpuEvicted(key,
                Math.max(1L, page.uploadedRevision), mask);
        MapOverviewTextureManager.getInstance().markSurfaceLeafEvicted(stamp,
                address.regionX(), address.regionZ(), address.pageX(),
                address.pageZ());
    }

    /**
     * Builds a compact 68x68 source window for one 64x64 leaf. Packed palette
     * indices are remapped into page-local palettes because the halo can cross
     * up to four independently-paletted 512x512 regions.
     */
    private SurfacePageBuildInputs captureSurfacePageBuildInputs(PageAddress address) {
        int pageSize = MapPageLayout.PAGE_SIZE;
        int halo = MapPageLayout.PAGE_HALO;
        int stride = MapPageLayout.PAGE_SNAPSHOT_SIZE;
        SurfacePageBufferPool.Buffer pooledBuffer = pageBufferPool.acquire(stride * stride);
        long[] pixels = pooledBuffer.pixels();
        int[] tints = pooledBuffer.tints();
        byte[] light = pooledBuffer.light();
        byte[] known = new byte[stride * stride];
        java.util.Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
        java.util.Arrays.fill(tints, SurfaceTintData.UNKNOWN);

        int globalPageX = address.regionX() * MapPageLayout.PAGES_PER_REGION
                + address.pageX();
        int globalPageZ = address.regionZ() * MapPageLayout.PAGES_PER_REGION
                + address.pageZ();
        int worldPageStartX = globalPageX * pageSize;
        int worldPageStartZ = globalPageZ * pageSize;
        int windowStartX = worldPageStartX - halo;
        int windowStartZ = worldPageStartZ - halo;
        int windowEndX = windowStartX + stride - 1;
        int windowEndZ = windowStartZ + stride - 1;

        List<String> biomePalette = new ArrayList<>();
        List<String> blockPalette = new ArrayList<>();
        Map<String, Integer> biomeIndices = new HashMap<>();
        Map<String, Integer> blockIndices = new HashMap<>();
        MapManager manager = MapManager.getInstance();

        int minRegionX = Math.floorDiv(windowStartX, MapPageLayout.REGION_SIZE);
        int maxRegionX = Math.floorDiv(windowEndX, MapPageLayout.REGION_SIZE);
        int minRegionZ = Math.floorDiv(windowStartZ, MapPageLayout.REGION_SIZE);
        int maxRegionZ = Math.floorDiv(windowEndZ, MapPageLayout.REGION_SIZE);
        for (int sourceRegionX = minRegionX; sourceRegionX <= maxRegionX; sourceRegionX++) {
            int sourceWorldMinX = sourceRegionX * MapPageLayout.REGION_SIZE;
            int intersectionMinX = Math.max(windowStartX, sourceWorldMinX);
            int intersectionMaxX = Math.min(windowEndX,
                    sourceWorldMinX + MapPageLayout.REGION_SIZE - 1);
            for (int sourceRegionZ = minRegionZ; sourceRegionZ <= maxRegionZ; sourceRegionZ++) {
                int sourceWorldMinZ = sourceRegionZ * MapPageLayout.REGION_SIZE;
                int intersectionMinZ = Math.max(windowStartZ, sourceWorldMinZ);
                int intersectionMaxZ = Math.min(windowEndZ,
                        sourceWorldMinZ + MapPageLayout.REGION_SIZE - 1);
                int width = intersectionMaxX - intersectionMinX + 1;
                int height = intersectionMaxZ - intersectionMinZ + 1;
                if (width <= 0 || height <= 0) continue;

                MapManager.Region source = manager.getRegion(sourceRegionX, sourceRegionZ, false);
                if (source == null || !source.isLoaded()) {
                    if (manager.hasRegionFile(sourceRegionX, sourceRegionZ)) {
                        MapProcessor.getInstance().enqueueSurfaceLoad(sourceRegionX, sourceRegionZ,
                                distancePriority(sourceRegionX, sourceRegionZ));
                    }
                    continue;
                }

                int localMinX = intersectionMinX - sourceWorldMinX;
                int localMinZ = intersectionMinZ - sourceWorldMinZ;
                MapManager.RegionWindow window = source.snapshotWindow(
                        localMinX, localMinZ, width, height);
                int destinationX = intersectionMinX - windowStartX;
                int destinationZ = intersectionMinZ - windowStartZ;
                remapWindow(window, destinationX, destinationZ, stride,
                        pixels, tints, known, biomePalette, biomeIndices,
                        blockPalette, blockIndices);

                MapLightManager.LightRegion lightRegion = MapLightManager.getInstance().getRegion(
                        sourceRegionX, sourceRegionZ, MapConfig.minimapNightMode != 0);
                if (lightRegion != null && lightRegion.isLoaded()) {
                    byte[] sourceLight = lightRegion.snapshotWindow(
                            localMinX, localMinZ, width, height);
                    for (int z = 0; z < height; z++) {
                        System.arraycopy(sourceLight, z * width, light,
                                (destinationZ + z) * stride + destinationX, width);
                    }
                }
            }
        }

        var level = Minecraft.getInstance().level;
        Registry<Biome> biomeRegistry = level == null ? null
                : level.registryAccess().registryOrThrow(Registries.BIOME);
        Biome[] resolvedBiomes = new Biome[biomePalette.size()];
        for (int i = 0; i < biomePalette.size(); i++) {
            try {
                if (biomeRegistry != null) {
                    resolvedBiomes[i] = biomeRegistry.get(
                            ResourceLocation.parse(biomePalette.get(i)));
                }
            } catch (RuntimeException ignored) {
            }
        }
        IntFunction<Biome> biomeLookup = index ->
                index >= 0 && index < resolvedBiomes.length ? resolvedBiomes[index] : null;

        Map<String, Integer> selectedColorCache = MapConfig.blockColourMode == 1
                ? vanillaBlockColorsCache : blockColorsCache;
        for (String blockId : blockPalette) {
            resolveBlockColor(blockId, MapConfig.blockColourMode);
            if (MapConfig.blockColourMode == 0) resolveTintPolicy(blockId);
        }
        return new SurfacePageBuildInputs(pixels, tints, known, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, selectedColorCache,
                tintPolicyCache,
                Set.copyOf(MapConfig.blockColorOverrides.keySet()), light,
                pooledBuffer);
    }

    private static void remapWindow(MapManager.RegionWindow source,
            int destinationX, int destinationZ, int destinationStride,
            long[] destinationPixels, int[] destinationTints,
            byte[] destinationKnown,
            List<String> biomePalette, Map<String, Integer> biomeIndices,
            List<String> blockPalette, Map<String, Integer> blockIndices) {
        String[] sourceBiomes = source.biomePalette();
        String[] sourceBlocks = source.blockPalette();
        for (int z = 0; z < source.height(); z++) {
            for (int x = 0; x < source.width(); x++) {
                int sourceIndex = z * source.width() + x;
                long packed = source.pixels()[sourceIndex];
                int destinationIndex = (destinationZ + z) * destinationStride
                        + destinationX + x;
                destinationKnown[destinationIndex] = source.known()[sourceIndex];
                destinationTints[destinationIndex] = source.tints()[sourceIndex];
                if (MapBlockData.isEmpty(packed)) continue;

                short sourceBlock = MapBlockData.blockId(packed);
                short remappedBlock = MapBlockData.NO_BLOCK;
                int blockIndex = sourceBlock & 0xFFFF;
                if (sourceBlock != MapBlockData.NO_BLOCK
                        && blockIndex < sourceBlocks.length) {
                    String blockId = sourceBlocks[blockIndex];
                    if (blockId == null || blockId.isBlank()) blockId = "minecraft:air";
                    int mapped = blockIndices.computeIfAbsent(blockId, id -> {
                        int next = blockPalette.size();
                        blockPalette.add(id);
                        return next;
                    });
                    remappedBlock = (short) Math.min(65_534, mapped);
                }

                byte sourceBiome = MapBlockData.biomeId(packed);
                byte remappedBiome = MapBlockData.NO_BIOME;
                int biomeIndex = sourceBiome & 0xFF;
                if (sourceBiome != MapBlockData.NO_BIOME
                        && biomeIndex < sourceBiomes.length) {
                    String biomeId = sourceBiomes[biomeIndex];
                    if (biomeId == null || biomeId.isBlank()) biomeId = "minecraft:plains";
                    int mapped = biomeIndices.computeIfAbsent(biomeId, id -> {
                        if (biomePalette.size() >= 255) return 254;
                        int next = biomePalette.size();
                        biomePalette.add(id);
                        return next;
                    });
                    remappedBiome = (byte) Math.min(254, mapped);
                }

                destinationPixels[destinationIndex] = MapBlockData.packRaw(
                        MapBlockData.topY(packed), remappedBlock, remappedBiome,
                        MapBlockData.flags(packed), MapBlockData.floorY(packed));
            }
        }
    }

    private SurfaceBuildInputs captureSurfaceBuildInputs(MapManager.Region region,
            int regionX, int regionZ) {
        long[] pixels = region.snapshotPackedPixels();
        int[] tints = region.snapshotTints();
        List<String> biomePalette = region.snapshotBiomePalette();
        List<String> blockPalette = region.snapshotBlockPalette();

        var level = Minecraft.getInstance().level;
        Registry<Biome> biomeRegistry = level == null ? null
                : level.registryAccess().registryOrThrow(Registries.BIOME);
        Biome[] resolvedBiomes = new Biome[biomePalette.size()];
        for (int i = 0; i < biomePalette.size(); i++) {
            try {
                if (biomeRegistry != null) {
                    resolvedBiomes[i] = biomeRegistry.get(
                            ResourceLocation.parse(biomePalette.get(i)));
                }
            } catch (RuntimeException ignored) {
            }
        }
        IntFunction<Biome> biomeLookup = index ->
                index >= 0 && index < resolvedBiomes.length ? resolvedBiomes[index] : null;

        Map<String, Integer> selectedColorCache = MapConfig.blockColourMode == 1
                ? vanillaBlockColorsCache : blockColorsCache;
        for (String blockId : blockPalette) {
            resolveBlockColor(blockId, MapConfig.blockColourMode);
            if (MapConfig.blockColourMode == 0) resolveTintPolicy(blockId);
        }
        Map<String, Integer> blockColors = new HashMap<>(selectedColorCache);
        Map<String, BlockTintPolicy> tintPolicies = new HashMap<>(tintPolicyCache);
        Set<String> tintDisabledBlocks = Set.copyOf(
                MapConfig.blockColorOverrides.keySet());

        byte[] light = null;
        MapLightManager.LightRegion lightRegion = MapLightManager.getInstance().getRegion(
                regionX, regionZ, MapConfig.minimapNightMode != 0);
        if (lightRegion != null) {
            light = new byte[MapPageLayout.REGION_SIZE * MapPageLayout.REGION_SIZE];
            lightRegion.lock();
            try {
                System.arraycopy(lightRegion.getLevelsDirect(), 0, light, 0, light.length);
            } finally {
                lightRegion.unlock();
            }
        }
        return new SurfaceBuildInputs(pixels, tints, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks, light);
    }

    private List<String> selectDirty(int budget, boolean force) {
        List<String> selected = new ArrayList<>(budget);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            if (dirtyTextures.isEmpty()) return selected;
            synchronized (visibleTextures) {
                for (String key : visibleTextures.keySet()) {
                    if (isCurrentDimensionKey(key) && dirtyTextures.contains(key) && isReadyForPublication(key, now, force)) {
                        selected.add(key);
                        if (selected.size() >= budget) break;
                    }
                }
            }
            if (selected.size() < budget) {
                for (String key : dirtyTextures) {
                    if (isCurrentDimensionKey(key) && !selected.contains(key) && isReadyForPublication(key, now, force)) {
                        selected.add(key);
                    }
                    if (selected.size() >= budget) break;
                }
            }
            dirtyTextures.removeAll(selected);
        }
        return selected;
    }

    private boolean isReadyForPublication(String key, long now, boolean force) {
        if (force) return true;
        long first = firstDirtyNanos.getOrDefault(key, now);
        long last = lastDirtyNanos.getOrDefault(key, first);
        return now - last >= DIRTY_QUIET_NANOS || now - first >= DIRTY_MAX_WAIT_NANOS;
    }

    private void schedulePreparation(String key, RegionTextureInfo info,
            MapManager.Region region, int regionX, int regionZ) {
        if (!MapManager.getInstance().isGenerationCurrent(info.generation)) return;
        if (info.pending != null) {
            if (!info.pending.isDone()) return;
            try {
                MapTextureBuildWorker.PreparedPair prepared = info.pending.join();
                if (MapManager.getInstance().isGenerationCurrent(info.generation)) {
                    boolean current;
                    synchronized (dirtyTextures) {
                        long currentRevision = revisions.getOrDefault(key, 0L);
                        current = currentRevision == prepared.revision();
                        if (current) {
                            dirtyTextures.remove(key);
                            firstDirtyNanos.remove(key);
                            lastDirtyNanos.remove(key);
                        } else {
                            // Exact page updates may have advanced the source while
                            // this compatibility region was building. Never publish
                            // the stale 512 snapshot over newer 64x64 leaves.
                            dirtyTextures.add(key);
                        }
                    }
                    if (current) applyPrepared(info, prepared, regionX, regionZ);
                }
            } catch (RuntimeException exception) {
                LOGGER.debug("Discarded failed/stale surface texture job {}", key, exception);
                requeue(key);
            } finally {
                info.pending = null;
            }
            return;
        }

        SurfaceBuildInputs inputs = captureSurfaceBuildInputs(region, regionX, regionZ);
        long revision;
        synchronized (dirtyTextures) {
            revision = revisions.getOrDefault(key, 0L);
        }

        CompletableFuture<MapTextureBuildWorker.PreparedPair> future =
                MapTextureBuildWorker.tryBuildSurface(inputs.pixels(), inputs.tints(),
                        inputs.biomePalette(), inputs.blockPalette(),
                        inputs.biomeLookup(), inputs.blockColors(), inputs.tintPolicies(),
                        inputs.tintDisabledBlocks(), MapConfig.blockColourMode,
                        MapConfig.displayFlowers, MapConfig.terrainSlopes, inputs.light(),
                        MapConfig.mapColorProfile, revision,
                        () -> MapManager.getInstance().isGenerationCurrent(info.generation));
        if (future == null) {
            requeue(key);
            return;
        }
        info.pending = future;
        long submittedCacheEpoch = cacheEpoch;
        future.whenComplete((ignored, throwable) -> {
            if (submittedCacheEpoch != cacheEpoch) return;
            synchronized (textureCache) {
                if (textureCache.get(key) != info) return;
            }
            requeue(key);
        });
    }

    private RegionTextureInfo createTextureInfo(int regionX, int regionZ, long generation) {
        DynamicTexture texture = new DynamicTexture(512, 512, false);
        configureExactSurfaceSampling(texture);
        String dimension = texturePathDimension();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("simplemap",
                "regions/" + dimension + "/r_" + regionX + '_' + regionZ);
        Minecraft.getInstance().getTextureManager().register(location, texture);

        DynamicTexture glowTexture = new DynamicTexture(512, 512, false);
        configureExactSurfaceSampling(glowTexture);
        ResourceLocation glowLocation = ResourceLocation.fromNamespaceAndPath("simplemap",
                "regions/" + dimension + "/glow_" + regionX + '_' + regionZ);
        Minecraft.getInstance().getTextureManager().register(glowLocation, glowTexture);
        return new RegionTextureInfo(texture, location, glowTexture, glowLocation, generation, regionX, regionZ);
    }

    private void applyPrepared(RegionTextureInfo info, MapTextureBuildWorker.PreparedPair prepared,
            int regionX, int regionZ) {
        NativeImage image = info.texture.getPixels();
        NativeImage glowImage = info.glowTexture.getPixels();
        if (image == null || glowImage == null) return;
        int[] styled = prepared.styled();
        int[] glow = prepared.glow();
        for (int z = 0; z < 512; z++) {
            int row = z * 512;
            for (int x = 0; x < 512; x++) {
                image.setPixelRGBA(x, z, styled[row + x]);
                glowImage.setPixelRGBA(x, z, glow[row + x]);
            }
        }
        long legacyUploadStart = System.nanoTime();
        info.texture.upload();
        info.glowTexture.upload();
        long legacyUploadNanos = System.nanoTime() - legacyUploadStart;
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.LEGACY, legacyUploadNanos);
        configureExactSurfaceSampling(info.texture);
        configureExactSurfaceSampling(info.glowTexture);
        info.uploadedRevision = prepared.revision();
        info.initialized = true;
        String legacyResidencyKey = "legacy:" + key(regionX, regionZ);
        MapResidencyManager.getInstance().register(
                legacyResidencyKey,
                MapResidencyManager.Kind.SURFACE_LEGACY,
                2L * 512L * 512L * Integer.BYTES,
                () -> evictLegacyRegionForBudget(regionX, regionZ));
        MapResidencyManager.getInstance().enforceBudget(
                legacyResidencyKey, MapRequestLane.BACKGROUND);

        // V14 leaf architecture: the 512x512 region remains a compatibility
        // fallback, while exact retained GPU leaves are published at 64x64 blocks.
        enqueueLeafPublication(regionX, regionZ, info.generation, prepared);
    }

    /** Xaero RegionTexture semantics: LINEAR minification, NEAREST magnification. */
    private static void configureExactSurfaceSampling(DynamicTexture texture) {
        if (texture == null) return;
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(() -> configureExactSurfaceSampling(texture));
            return;
        }
        GlStateManager._bindTexture(texture.getId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
    }

    private void enqueueLeafPublication(int regionX, int regionZ, long generation,
            MapTextureBuildWorker.PreparedPair prepared) {
        String regionKey = key(regionX, regionZ);
        pendingLeafPublications.removeIf(pending -> pending.regionKey.equals(regionKey));
        while (pendingLeafPublications.size() >= MAX_PENDING_LEAF_REGIONS) {
            pendingLeafPublications.pollFirst();
        }
        pendingLeafPublications.addLast(new PendingLeafPublication(
                regionKey, regionX, regionZ, generation, prepared.revision(),
                prepared.styled(), prepared.glow(), prepared.pageKnownRows(),
                centerOutPageOrder()));
    }

    /**
     * Publishes compatibility-derived leaves center-out. Exact-authority pages are
     * never overwritten, and the already uploaded 512 region remains visible while
     * missing leaves are staged under a small background budget.
     */
    private int publishPendingLeafPages(int budget, long deadlineNanos) {
        int published = 0;
        while (published < budget && System.nanoTime() < deadlineNanos) {
            PendingLeafPublication pending = pendingLeafPublications.peekFirst();
            if (pending == null) break;
            RegionTextureInfo current;
            synchronized (textureCache) {
                current = textureCache.get(pending.regionKey);
            }
            if (current == null || current.generation != pending.generation
                    || current.uploadedRevision != pending.revision
                    || pending.styled == null || pending.styled.length < 512 * 512
                    || pending.glow == null || pending.glow.length < 512 * 512) {
                pendingLeafPublications.pollFirst();
                continue;
            }
            if (pending.cursor >= MapPageLayout.PAGES_PER_REGION
                    * MapPageLayout.PAGES_PER_REGION) {
                pendingLeafPublications.pollFirst();
                continue;
            }

            int ordered = pending.pageOrder[pending.cursor];
            int pageX = ordered & (MapPageLayout.PAGES_PER_REGION - 1);
            int pageZ = ordered / MapPageLayout.PAGES_PER_REGION;
            if (!publishLeafPage(pending, pageX, pageZ)) break;
            pending.cursor++;
            published++;
            if (pending.cursor >= MapPageLayout.PAGES_PER_REGION
                    * MapPageLayout.PAGES_PER_REGION) {
                pendingLeafPublications.pollFirst();
            }
        }
        return published;
    }

    private boolean publishLeafPage(PendingLeafPublication pending, int pageX, int pageZ) {
        String pageKey = pageKey(pending.regionX, pending.regionZ, pageX, pageZ);
        PageTextureInfo page = ensurePageInfo(pageKey, pending.generation);
        if (page == null) return false;
        if (page.authority == PageAuthority.EXACT) return true;

        int[] leafPixels = new int[MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE];
        int[] leafGlow = new int[MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE];
        int startX = pageX * MapPageLayout.PAGE_SIZE;
        int startZ = pageZ * MapPageLayout.PAGE_SIZE;
        for (int z = 0; z < MapPageLayout.PAGE_SIZE; z++) {
            int sourceRow = (startZ + z) * 512 + startX;
            int leafRow = z * MapPageLayout.PAGE_SIZE;
            System.arraycopy(pending.styled, sourceRow, leafPixels, leafRow,
                    MapPageLayout.PAGE_SIZE);
            System.arraycopy(pending.glow, sourceRow, leafGlow, leafRow,
                    MapPageLayout.PAGE_SIZE);
        }
        int pageIndex = pageZ * MapPageLayout.PAGES_PER_REGION + pageX;
        long[] knownRows = pending.knownPages != null
                && pageIndex < pending.knownPages.length
                && pending.knownPages[pageIndex] != null
                ? pending.knownPages[pageIndex] : inferKnownRows(leafPixels);
        int completeSubtiles = MapPageLayout.completeSubtileMask(knownRows);
        if (completeSubtiles != MapPageLayout.FULL_SUBTILE_MASK) {
            // Xaero World Map updates a MapTileChunk buffer only after its complete
            // 4x4 chunk transaction has been visited. Keep sparse/edge data in the
            // 512x512 retained underlay instead of promoting disconnected 16x16
            // children into the exact atlas. The exact page is admitted once all
            // sixteen body chunks are coherent.
            return true;
        }

        // The exact 64x64 leaf is now one coherent 4x4 chunk transaction.
        int[] coherentPixels = new int[leafPixels.length];
        int[] coherentGlow = new int[leafGlow.length];
        long[] coherentRows = new long[MapPageLayout.PAGE_SIZE];
        mergeCompleteSubtiles(coherentPixels, leafPixels, completeSubtiles);
        mergeCompleteSubtiles(coherentGlow, leafGlow, completeSubtiles);
        mergeSubtileKnownRows(coherentRows, completeSubtiles);
        int knownColumns = countKnownColumns(coherentRows);

        if (page.atlasSlot < 0) {
            page.atlasSlot = acquireSurfaceAtlasSlot(page.key);
            if (page.atlasSlot < 0) return false;
        }

        leafAtlas.upload(page.atlasSlot, coherentPixels, coherentGlow);
        MapResidencyManager.getInstance().markPixelsChanged(
                MapResidencyManager.Kind.SURFACE_EXACT);
        page.colorPixels = coherentPixels;
        page.glowPixels = coherentGlow;
        page.knownRows = coherentRows;
        int previousCompleteSubtiles = page.completeSubtileMask;
        page.completeSubtileMask = completeSubtiles;
        if (completeSubtiles != previousCompleteSubtiles) {
            MapResidencyManager.getInstance().markCoverageChanged(
                    MapResidencyManager.Kind.SURFACE_EXACT);
        }
        page.uploadedRevision = pending.revision;
        page.initialized = true;
        page.lastGpuPublicationNanos = System.nanoTime();
        page.knownColumns = knownColumns;
        page.retryAfterNanos = 0L;
        page.authority = PageAuthority.LEGACY_DERIVED;
        setPageResidentIndexed(page, true);
        String residentKey = "surface:" + page.key;
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.SURFACE_EXACT,
                2L * MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE * Integer.BYTES,
                () -> evictSurfacePageForBudget(page.key));
        MapResidencyManager.getInstance().enforceBudget(
                residentKey, MapRequestLane.BACKGROUND);
        ExactPageStateTracker.getInstance().transition(
                "surface:" + pageKey, ExactPageState.GPU_READY,
                MapRequestLane.BACKGROUND, pending.revision);

        boolean complete = completeSubtiles == MapPageLayout.FULL_SUBTILE_MASK;
        MapOverviewTextureManager.getInstance().updateSurfaceLeafPage(
                pending.regionX * MapPageLayout.PAGES_PER_REGION + pageX,
                pending.regionZ * MapPageLayout.PAGES_PER_REGION + pageZ,
                coherentPixels, coherentRows, complete,
                pending.revision, MapRequestLane.BACKGROUND);
        trimPageCache();
        return true;
    }

    private PageTextureInfo ensurePageInfo(String key, long generation) {
        synchronized (pageCache) {
            PageTextureInfo existing = pageCache.get(key);
            if (existing != null && existing.generation == generation) {
                // Never restore GPU residency as a side effect of a metadata lookup.
                // Dirty/background scans may touch thousands of retained CPU pages;
                // synchronous restoration here caused visible pages to evict one
                // another every 40-100 ms. The explicit restore queue above owns all
                // re-uploads and observes the same frame/GPU budget as publication.
                return existing;
            }
            if (existing != null) {
                pageCache.remove(key);
                closePage(existing);
            }
            while (pageCache.size() >= MAX_TEXTURE_PAGES) {
                PageTextureInfo retired = oldestColdSurfacePage(key);
                if (retired == null) break;
                pageCache.remove(retired.key);
                closePage(retired);
            }
            PageTextureInfo created = new PageTextureInfo(key, -1, generation);
            pageCache.put(key, created);
            return created;
        }
    }

    private int acquireSurfaceAtlasSlot(String protectedKey) {
        int slot = leafAtlas.acquireSlot();
        if (slot >= 0) return slot;
        java.util.List<String> candidates = new java.util.ArrayList<>();
        java.util.Map<String, PageTextureInfo> byKey = new java.util.HashMap<>();
        long nowNanos = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        for (var entry : pageCache.entrySet()) {
            PageTextureInfo candidate = entry.getValue();
            if (candidate.pending != null || candidate.atlasSlot < 0
                    || entry.getKey().equals(protectedKey)
                    || isSurfaceResidencyProtected(candidate, nowNanos, nowMs)) continue;
            String residencyKey = "surface:" + entry.getKey();
            candidates.add(residencyKey);
            byKey.put(residencyKey, candidate);
        }
        String victimKey = MapResidencyManager.getInstance().chooseVictim(
                candidates, "surface:" + protectedKey);
        PageTextureInfo victim = byKey.get(victimKey);
        if (victim == null) return -1;
        releaseSurfaceAtlasResidency(victim, "atlas_reuse_offscreen");
        return leafAtlas.acquireSlot();
    }

    private PageTextureInfo oldestColdSurfacePage(String protectedKey) {
        long nowNanos = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        for (var entry : pageCache.entrySet()) {
            PageTextureInfo candidate = entry.getValue();
            if (entry.getKey().equals(protectedKey) || candidate.pending != null
                    || isSurfaceResidencyProtected(candidate, nowNanos, nowMs)) continue;
            if (candidate.atlasSlot < 0 || hasReplacementCoverage(candidate)) return candidate;
        }
        return null;
    }

    private boolean restoreSurfacePageResidency(PageTextureInfo page,
            MapRequestLane lane) {
        if (page == null || page.colorPixels == null
                || page.glowPixels == null
                || page.completeSubtileMask == 0
                || page.knownColumns <= 0) {
            return false;
        }
        PageAddress restoreAddress = parsePageKey(page.key);
        if (restoreAddress == null
                || !pageSnapshotCoversRetainedSource(page,
                        restoreAddress.regionX(), restoreAddress.regionZ(),
                        restoreAddress.pageX(), restoreAddress.pageZ())) {
            return false;
        }
        if (page.atlasSlot < 0) {
            page.atlasSlot = acquireSurfaceAtlasSlot(page.key);
            if (page.atlasSlot < 0) return false;
        }
        long uploadStarted = System.nanoTime();
        leafAtlas.upload(page.atlasSlot, page.colorPixels, page.glowPixels);
        long uploadNanos = System.nanoTime() - uploadStarted;
        MapPipelineTelemetry.getInstance().recordStageNanos(
                MapPipelineStage.EXACT_UPLOAD, uploadNanos);
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.SURFACE_EXACT, uploadNanos);
        page.initialized = true;
        page.lastGpuPublicationNanos = System.nanoTime();
        page.residencyRetryAfterNanos = 0L;
        setPageResidentIndexed(page, true);
        String residentKey = "surface:" + page.key;
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.SURFACE_EXACT,
                2L * MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE * Integer.BYTES,
                () -> evictSurfacePageForBudget(page.key));
        MapResidencyManager.getInstance().enforceBudget(
                residentKey, lane);
        PageAddress address = parsePageKey(page.key);
        if (address != null) {
            recordGpuPublication(page, address, page.uploadedRevision);
            MapResidencyManager.getInstance().markPixelsChanged(
                    MapResidencyManager.Kind.SURFACE_EXACT);
            stageExactPageTable(page, address, page.uploadedRevision);
        }
        ExactPageStateTracker.getInstance().transition(
                residentKey, ExactPageState.GPU_READY,
                lane, page.uploadedRevision);
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent(
                "SURFACE_PAGE_GPU_RESTORED:" + page.key, 250L)) {
            recorder.event("SURFACE_PAGE_GPU_RESTORED",
                    "page=" + page.key + " revision=" + page.uploadedRevision
                            + " lane=" + lane + " upload_ms="
                            + String.format(java.util.Locale.ROOT, "%.4f",
                            uploadNanos / 1_000_000.0D));
        }
        return true;
    }

    private void synchronizeLeafAtlasStorage() {
        leafAtlas.ensureInitialized();
        long generation = leafAtlas.storageGeneration();
        if (observedLeafStorageGeneration == Long.MIN_VALUE) {
            observedLeafStorageGeneration = generation;
            return;
        }
        if (observedLeafStorageGeneration == generation) return;
        observedLeafStorageGeneration = generation;
        MapResidencyManager.getInstance().markTopologyChanged();
        synchronized (pageCache) {
            for (PageTextureInfo page : pageCache.values()) {
                if (page.atlasSlot >= 0 && page.colorPixels != null && page.glowPixels != null) {
                    leafAtlas.upload(page.atlasSlot, page.colorPixels, page.glowPixels);
                    page.initialized = true;
                }
            }
        }
    }

    private static int[] centerOutPageOrder() {
        int side = MapPageLayout.PAGES_PER_REGION;
        int count = side * side;
        Integer[] boxed = new Integer[count];
        double center = (side - 1) * 0.5;
        for (int index = 0; index < count; index++) boxed[index] = index;
        java.util.Arrays.sort(boxed, (left, right) -> {
            int lx = left % side;
            int lz = left / side;
            int rx = right % side;
            int rz = right / side;
            double ld = (lx - center) * (lx - center) + (lz - center) * (lz - center);
            double rd = (rx - center) * (rx - center) + (rz - center) * (rz - center);
            int byDistance = Double.compare(ld, rd);
            if (byDistance != 0) return byDistance;
            int byRing = Integer.compare(
                    Math.max(Math.abs(lx * 2 - (side - 1)), Math.abs(lz * 2 - (side - 1))),
                    Math.max(Math.abs(rx * 2 - (side - 1)), Math.abs(rz * 2 - (side - 1))));
            return byRing != 0 ? byRing : Integer.compare(left, right);
        });
        int[] order = new int[count];
        for (int index = 0; index < count; index++) order[index] = boxed[index];
        return order;
    }

    private static void mergeCompleteSubtiles(int[] target, int[] source,
            int subtileMask) {
        if (target == null || source == null || subtileMask == 0) return;
        for (int subtileZ = 0; subtileZ < MapPageLayout.SUBTILES_PER_PAGE;
                subtileZ++) {
            for (int subtileX = 0; subtileX < MapPageLayout.SUBTILES_PER_PAGE;
                    subtileX++) {
                int bit = 1 << MapPageLayout.subtileIndex(subtileX, subtileZ);
                if ((subtileMask & bit) == 0) continue;
                int startX = subtileX * MapPageLayout.SUBTILE_SIZE;
                int startZ = subtileZ * MapPageLayout.SUBTILE_SIZE;
                for (int localZ = 0; localZ < MapPageLayout.SUBTILE_SIZE;
                        localZ++) {
                    int row = (startZ + localZ) * MapPageLayout.PAGE_SIZE
                            + startX;
                    System.arraycopy(source, row, target, row,
                            MapPageLayout.SUBTILE_SIZE);
                }
            }
        }
    }

    private static void mergeSubtileKnownRows(long[] target, int subtileMask) {
        if (target == null || target.length < MapPageLayout.PAGE_SIZE
                || subtileMask == 0) return;
        long chunkBits = (1L << MapPageLayout.SUBTILE_SIZE) - 1L;
        for (int subtileZ = 0; subtileZ < MapPageLayout.SUBTILES_PER_PAGE;
                subtileZ++) {
            for (int subtileX = 0; subtileX < MapPageLayout.SUBTILES_PER_PAGE;
                    subtileX++) {
                int bit = 1 << MapPageLayout.subtileIndex(subtileX, subtileZ);
                if ((subtileMask & bit) == 0) continue;
                long rowBits = chunkBits
                        << (subtileX * MapPageLayout.SUBTILE_SIZE);
                int startZ = subtileZ * MapPageLayout.SUBTILE_SIZE;
                for (int localZ = 0; localZ < MapPageLayout.SUBTILE_SIZE;
                        localZ++) {
                    target[startZ + localZ] |= rowBits;
                }
            }
        }
    }

    private static long[] inferKnownRows(int[] pixels) {
        long[] rows = new long[MapPageLayout.PAGE_SIZE];
        for (int z = 0; z < MapPageLayout.PAGE_SIZE; z++) {
            long mask = 0L;
            int row = z * MapPageLayout.PAGE_SIZE;
            for (int x = 0; x < MapPageLayout.PAGE_SIZE; x++) {
                if (pixels[row + x] != 0) mask |= 1L << x;
            }
            rows[z] = mask;
        }
        return rows;
    }

    private static int countKnownColumns(long[] rows) {
        if (rows == null) return 0;
        int count = 0;
        int limit = Math.min(rows.length, MapPageLayout.PAGE_SIZE);
        for (int z = 0; z < limit; z++) count += Long.bitCount(rows[z]);
        return count;
    }

    private static boolean rowsComplete(long[] rows) {
        if (rows == null || rows.length < MapPageLayout.PAGE_SIZE) return false;
        for (int z = 0; z < MapPageLayout.PAGE_SIZE; z++) {
            if (rows[z] != -1L) return false;
        }
        return true;
    }

    private int resolveDefaultBlockColor(String blockIdText, int colourMode) {
        try {
            ResourceLocation blockId = ResourceLocation.parse(blockIdText);
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockId);
            if (block == null) return 0xFFFFFFFF;
            var state = block.defaultBlockState();
            var minecraft = Minecraft.getInstance();
            var level = minecraft.level;
            BlockPos position = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();

            // VANILLA intentionally uses the same small MapColor palette as a
            // vanilla filled map. ACCURATE resolves the actual block model texture.
            if (colourMode == 1) {
                try {
                    MapColor mapColor = state.getMapColor(level, position);
                    return 0xFF000000 | (mapColor.col & 0x00FFFFFF);
                } catch (Throwable ignored) {
                    return 0xFFFFFFFF;
                }
            }

            Integer sampled = BlockTextureColorSampler.sampleArgb(blockId);
            if (sampled != null) return sampled;

            int rgb = -1;
            if (level != null && !BrokenBlockTintCache.getInstance().isBroken(blockIdText)) {
                try {
                    rgb = minecraft.getBlockColors().getColor(state, level, position, 0);
                } catch (Throwable throwable) {
                    BrokenBlockTintCache.getInstance().markBroken(blockIdText);
                }
            }
            if (rgb == -1) {
                try {
                    MapColor mapColor = state.getMapColor(level, position);
                    rgb = mapColor.col;
                } catch (Throwable ignored) {
                    rgb = 0xFFFFFF;
                }
            }
            return 0xFF000000 | (rgb & 0x00FFFFFF);
        } catch (RuntimeException exception) {
            return 0xFFFFFFFF;
        }
    }

    public void clearDerivedColorCaches() {
        blockColorsCache.clear();
        vanillaBlockColorsCache.clear();
        tintPolicyCache.clear();
        synchronized (styleBiomeCache) {
            styleBiomeCache.clear();
            styleBiomeCacheSessionId = Long.MIN_VALUE;
            styleBiomeCacheGeneration = Long.MIN_VALUE;
        }
        BlockTextureColorSampler.clearCache();
    }

    public void invalidateStyle() {
        SurfaceLodTree.invalidatePersistentCache();
        styleRefreshUntilMs = System.currentTimeMillis() + STYLE_REFRESH_WINDOW_MS;
        lastUploadTime = 0L;
        synchronized (textureCache) {
            synchronized (pageCache) {
                synchronized (dirtyTextures) {
                    long now = System.nanoTime();
                    for (String key : textureCache.keySet()) {
                        revisions.merge(key, 1L, Long::sum);
                        firstDirtyNanos.putIfAbsent(key, now);
                        lastDirtyNanos.put(key, now);
                        dirtyTextures.add(key);
                    }
                    for (String leafKey : pageCache.keySet()) {
                        pageRevisions.merge(leafKey, 1L, Long::sum);
                        dirtyPageSubtileMasks.put(leafKey,
                                MapPageLayout.FULL_SUBTILE_MASK);
                        firstDirtyPageNanos.putIfAbsent(leafKey, now);
                        lastDirtyPageNanos.put(leafKey, now);
                        enqueueDirtyPageLocked(leafKey);
                    }
                }
            }
        }
    }

    public void clearCache() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::clearCache);
            return;
        }
        // Invalidate callbacks before any future is detached or cancelled.
        cacheEpoch++;
        fullscreenExactSuppressed = false;
        List<RegionTextureInfo> closeNow = new ArrayList<>();
        synchronized (textureCache) {
            closeNow.addAll(textureCache.values());
            closeNow.addAll(deferredCloses);
            textureCache.clear();
            deferredCloses.clear();
            renderBatchDepth = 0;
        }
        for (RegionTextureInfo info : closeNow) info.close();

        List<PageTextureInfo> closePagesNow = new ArrayList<>();
        synchronized (pageCache) {
            closePagesNow.addAll(pageCache.values());
            closePagesNow.addAll(deferredPageCloses);
            pageCache.clear();
            deferredPageCloses.clear();
        }
        for (PageTextureInfo info : closePagesNow) closePage(info);
        synchronized (residentPageCountsByLevel) {
            for (Map<Long, Integer> levelCounts : residentPageCountsByLevel) {
                levelCounts.clear();
            }
        }
        synchronized (pendingLeafPublications) {
            pendingLeafPublications.clear();
        }
        synchronized (completedPagePublications) {
            completedPagePublications.clear();
            completedPagePublicationKeys.clear();
            lastCompletedMaintenanceNanos = 0L;
        }
        synchronized (residencyRestoreQueue) {
            residencyRestoreQueue.clear();
            residencyRestoreKeys.clear();
        }
        /*
         * CompletableFuture.cancel() can execute dependent callbacks synchronously.
         * Those callbacks remove their batch from pendingSurfaceBatches. Cancelling
         * while iterating the live HashMap therefore mutates the iterator re-entrantly
         * and caused the logout ConcurrentModificationException. Detach first, then
         * cancel the stable snapshot outside the monitor.
         */
        List<CompletableFuture<PreparedSurfaceRegionBatch>> cancelBatches;
        synchronized (pendingSurfaceBatches) {
            cancelBatches = new ArrayList<>(pendingSurfaceBatches.values());
            pendingSurfaceBatches.clear();
            batchCaptureAttemptNanos.clear();
            batchCaptureDeferrals.clear();
            captureClusterAttemptNanos.clear();
            captureClusterDeferrals.clear();
        }
        for (CompletableFuture<PreparedSurfaceRegionBatch> future : cancelBatches) {
            future.cancel(false);
        }
        SurfaceRegionSourceDatabase.getInstance().clear();
        leafAtlas.resetSlots();
        ExactPageStateTracker.getInstance().clearPrefix("surface:");
        synchronized (dirtyTextures) {
            dirtyTextures.clear();
            dirtyPages.clear();
            dirtyPageOrder.clear();
            dirtyPageQueued.clear();
            dirtyPageSubtileMasks.clear();
            revisions.clear();
            pageRevisions.clear();
            pageDemands.clear();
            firstDirtyNanos.clear();
            lastDirtyNanos.clear();
            firstDirtyPageNanos.clear();
            lastDirtyPageNanos.clear();
        }
        synchronized (visibleTextures) {
            visibleTextures.clear();
        }
        blockColorsCache.clear();
        vanillaBlockColorsCache.clear();
        tintPolicyCache.clear();
        BlockTextureColorSampler.clearCache();
    }

    private boolean hasCompleteExactRegion(int regionX, int regionZ) {
        long generation = MapManager.getInstance().getGeneration();
        synchronized (pageCache) {
            for (int pageX = 0; pageX < MapPageLayout.PAGES_PER_REGION; pageX++) {
                for (int pageZ = 0; pageZ < MapPageLayout.PAGES_PER_REGION; pageZ++) {
                    PageTextureInfo page = pageCache.get(pageKey(regionX, regionZ, pageX, pageZ));
                    if (page == null || page.generation != generation
                            || !page.initialized || page.atlasSlot < 0
                            || page.authority != PageAuthority.EXACT) return false;
                }
            }
            return true;
        }
    }

    private boolean hasFreshMinimapDemand(long nowMs) {
        synchronized (dirtyTextures) {
            for (SurfacePageDemand demand : pageDemands.values()) {
                if (demand.effectiveLane(nowMs) == MapRequestLane.MINIMAP) return true;
            }
            return false;
        }
    }

    private void prunePageDemands(long nowMs) {
        synchronized (dirtyTextures) {
            var iterator = pageDemands.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, SurfacePageDemand> entry = iterator.next();
                entry.getValue().expire(nowMs);
                if (entry.getValue().effectiveLane(nowMs) != null) continue;
                /* Dirty source revision may survive offscreen, but viewport demand
                 * ownership must not. PASS104 kept every expired demand alive while
                 * its leaf was dirty, so historical work accumulated indefinitely. */
                String retiredKey = entry.getKey();
                iterator.remove();
                ExactPageStateTracker.getInstance().removeIfState(
                        "surface:" + retiredKey, ExactPageState.REQUESTED);
            }
        }
    }

    private boolean isFullscreenPublicationFrontier(String leafKey, long nowMs) {
        synchronized (dirtyTextures) {
            SurfacePageDemand target = pageDemands.get(leafKey);
            if (target == null
                    || !target.isLaneActive(MapRequestLane.FULLSCREEN, nowMs)) {
                return true;
            }
            long minimum = Long.MAX_VALUE;
            for (SurfacePageDemand demand : pageDemands.values()) {
                if (!demand.isLaneActive(MapRequestLane.FULLSCREEN, nowMs)) continue;
                minimum = Math.min(minimum, demand.fullscreenOrdinal);
            }
            return target.fullscreenOrdinal <= minimum;
        }
    }

    private void clearPageDemandLane(String leafKey, MapRequestLane lane) {
        synchronized (dirtyTextures) {
            SurfacePageDemand demand = pageDemands.get(leafKey);
            if (demand != null) demand.clearLane(lane);
        }
    }

    private boolean surfacePageHasRetainedSource(PageAddress address) {
        if (address == null) return true;
        return retainedLeafSourceMask(address.regionX(), address.regionZ(),
                address.pageX(), address.pageZ()) != 0;
    }

    private void settleSurfacePageWithoutCompleteSource(
            PageAddress address, MapRequestLane lane) {
        if (address == null) return;
        String leafKey = pageKey(address.regionX(), address.regionZ(),
                address.pageX(), address.pageZ());
        boolean anyData = retainedLeafSourceMask(
                address.regionX(), address.regionZ(),
                address.pageX(), address.pageZ()) != 0;
        boolean teleportForegroundRecovery =
                MapActivityGate.getInstance().isTeleportRecoveryActive()
                        && (lane == MapRequestLane.MINIMAP
                                || lane == MapRequestLane.FULLSCREEN);
        long revision;
        synchronized (dirtyTextures) {
            revision = pageRevisions.getOrDefault(leafKey, 0L);
            if (!teleportForegroundRecovery) {
                removeDirtyPageLocked(leafKey);
                dirtyPageSubtileMasks.remove(leafKey);
                firstDirtyPageNanos.remove(leafKey);
                lastDirtyPageNanos.remove(leafKey);
                SurfacePageDemand demand = pageDemands.get(leafKey);
                if (demand != null) demand.clearLane(lane);
            }
        }
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(leafKey);
            if (page != null) {
                page.retryAfterNanos = Math.max(page.retryAfterNanos,
                        System.nanoTime() + (teleportForegroundRecovery
                                ? 100_000_000L : 250_000_000L));
            }
        }
        ExactPageStateTracker.getInstance().transition(
                "surface:" + leafKey,
                (anyData || teleportForegroundRecovery)
                        ? ExactPageState.CPU_PARTIAL : ExactPageState.KNOWN_EMPTY,
                lane, revision);
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (recorder.shouldEmitEvent(
                "SURFACE_PAGE_WAITING_COMPLETE_SOURCE:" + leafKey, 1000L)) {
            recorder.event("SURFACE_PAGE_WAITING_COMPLETE_SOURCE",
                    "page=" + leafKey + " lane=" + lane
                            + " partial=" + anyData
                            + " teleport_recovery=" + teleportForegroundRecovery);
        }
    }

    private void clearAllPageDemandLane(MapRequestLane lane) {
        synchronized (dirtyTextures) {
            for (SurfacePageDemand demand : pageDemands.values()) {
                demand.clearLane(lane);
            }
        }
    }

    private MapRequestLane activePageLane(String leafKey, long nowMs) {
        synchronized (dirtyTextures) {
            SurfacePageDemand demand = pageDemands.get(leafKey);
            return demand == null ? null : demand.effectiveLane(nowMs);
        }
    }

    private MapRequestLane effectivePageLane(String leafKey, long nowMs) {
        MapRequestLane lane = activePageLane(leafKey, nowMs);
        return lane == null ? MapRequestLane.BACKGROUND : lane;
    }

    private static String regionKeyFromPageKey(String leafKey) {
        if (leafKey == null) return "";
        int separator = leafKey.lastIndexOf(':');
        return separator < 0 ? leafKey : leafKey.substring(0, separator);
    }


    /**
     * A non-overlapping fullscreen jump (teleport/dimension-scale jump) must not
     * leave the finite exact atlas occupied by the previous location. CPU pixels,
     * source snapshots and disk cache remain retained; only stale GPU residency is
     * released. Active minimap demand is preserved.
     */
    private void rebaseFullscreenSurfaceAtlas(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ) {
        int released = 0;
        long nowMs = System.currentTimeMillis();
        synchronized (pageCache) {
            for (PageTextureInfo page : pageCache.values()) {
                if (page == null || page.atlasSlot < 0) continue;
                PageAddress address = parsePageKey(page.key);
                if (address == null) continue;
                int globalPageX = address.regionX()
                        * MapPageLayout.PAGES_PER_REGION + address.pageX();
                int globalPageZ = address.regionZ()
                        * MapPageLayout.PAGES_PER_REGION + address.pageZ();
                boolean inside = globalPageX >= minPageX && globalPageX <= maxPageX
                        && globalPageZ >= minPageZ && globalPageZ <= maxPageZ;
                if (inside || hasActiveMinimapDemand(page.key, nowMs)) continue;
                releaseSurfaceAtlasResidency(page, "fullscreen_viewport_rebase");
                released++;
            }
        }
        if (released > 0) {
            MapResidencyManager.getInstance().markCoverageChanged(
                    MapResidencyManager.Kind.SURFACE_EXACT);
            MapDebugRecorder.getInstance().event("SURFACE_VIEWPORT_ATLAS_REBASE",
                    "released=" + released + " min_page=" + minPageX + ","
                            + minPageZ + " max_page=" + maxPageX + "," + maxPageZ);
        }
    }

    private boolean hasActiveMinimapDemand(String leafKey, long nowMs) {
        synchronized (dirtyTextures) {
            SurfacePageDemand demand = pageDemands.get(leafKey);
            return demand != null
                    && demand.isLaneActive(MapRequestLane.MINIMAP, nowMs);
        }
    }

    private boolean evictSurfacePageForBudget(String pageKey) {
        if (pageKey == null || renderBatchDepth > 0) return false;
        synchronized (pageCache) {
            PageTextureInfo retired = pageCache.get(pageKey);
            if (retired == null || retired.pending != null
                    || retired.atlasSlot < 0 || !retired.initialized
                    || isSurfaceResidencyProtected(retired,
                            System.nanoTime(), System.currentTimeMillis())) return false;
            releaseSurfaceAtlasResidency(retired, "budget_offscreen");
            return true;
        }
    }

    private void releaseSurfaceAtlasResidency(PageTextureInfo info) {
        releaseSurfaceAtlasResidency(info, "replacement_coverage");
    }

    private void releaseSurfaceAtlasResidency(PageTextureInfo info, String reason) {
        if (info == null || info.atlasSlot < 0) return;
        recordGpuEviction(info);
        setPageResidentIndexed(info, false);
        removeExactPageTable(info);
        leafAtlas.releaseSlot(info.atlasSlot);
        MapResidencyManager.getInstance().remove("surface:" + info.key);
        info.atlasSlot = -1;
        info.initialized = false;
        ExactPageStateTracker.getInstance().transition(
                "surface:" + info.key, ExactPageState.GPU_EVICTED,
                info.pendingLane, info.uploadedRevision);
        MapDebugRecorder.getInstance().event("SURFACE_PAGE_GPU_EVICTED",
                "page=" + info.key + " revision=" + info.uploadedRevision
                        + " reason=" + reason);
    }

    private boolean evictLegacyRegionForBudget(int regionX, int regionZ) {
        if (renderBatchDepth > 0) return false;
        String regionKey = key(regionX, regionZ);
        RegionTextureInfo retired;
        synchronized (textureCache) {
            retired = textureCache.get(regionKey);
            if (retired == null || retired.pending != null || !retired.initialized) {
                return false;
            }
            textureCache.remove(regionKey);
        }
        retired.close();
        return true;
    }

    private void trimPageCache() {
        List<PageTextureInfo> retired = new ArrayList<>();
        synchronized (pageCache) {
            while (pageCache.size() > MAX_TEXTURE_PAGES) {
                PageTextureInfo candidate = oldestColdSurfacePage(null);
                if (candidate == null) break;
                pageCache.remove(candidate.key);
                retired.add(candidate);
            }
        }
        for (PageTextureInfo info : retired) retire(info);
    }

    private void retire(PageTextureInfo info) {
        if (renderBatchDepth > 0) deferredPageCloses.add(info);
        else closePage(info);
    }

    private void closePage(PageTextureInfo info) {
        if (info == null) return;
        forgetResidencyRestore(info.key);
        if (info.pending != null && info.pending.cancel(false)) {
            MapPipelineTelemetry.getInstance().recordTaskCancelledBeforeRun();
        }
        MapRequestLane retiredLane = info.pendingLane;
        forgetCompletedPagePublication(info.key);
        info.pending = null;
        info.pendingLane = null;
        info.pendingCompletionRecorded = false;
        if (info.atlasSlot >= 0) recordGpuEviction(info);
        setPageResidentIndexed(info, false);
        if (info.atlasSlot >= 0) {
            removeExactPageTable(info);
            leafAtlas.releaseSlot(info.atlasSlot);
        }
        MapResidencyManager.getInstance().remove("surface:" + info.key);
        info.atlasSlot = -1;
        info.initialized = false;
        ExactPageStateTracker.getInstance().transition(
                "surface:" + info.key, ExactPageState.GPU_EVICTED,
                retiredLane, info.uploadedRevision);
        info.colorPixels = null;
        info.glowPixels = null;
        synchronized (dirtyTextures) {
            removeDirtyPageLocked(info.key);
            pageRevisions.remove(info.key);
            firstDirtyPageNanos.remove(info.key);
            lastDirtyPageNanos.remove(info.key);
            pageDemands.remove(info.key);
        }
    }

    private void trimTextureCache() {
        List<RegionTextureInfo> retired = new ArrayList<>();
        while (textureCache.size() > MAX_TEXTURE_REGIONS) {
            var iterator = textureCache.entrySet().iterator();
            if (!iterator.hasNext()) break;
            RegionTextureInfo eldest = iterator.next().getValue();
            iterator.remove();
            retired.add(eldest);
        }
        for (RegionTextureInfo info : retired) retire(info);
    }

    private void retire(RegionTextureInfo info) {
        if (renderBatchDepth > 0) deferredCloses.add(info);
        else info.close();
    }

    private void markRegionVisible(String key) {
        synchronized (visibleTextures) {
            visibleTextures.remove(key);
            visibleTextures.put(key, System.currentTimeMillis());
            while (visibleTextures.size() > MAX_VISIBLE_HISTORY) {
                var iterator = visibleTextures.entrySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    private void pruneVisibility(long now) {
        synchronized (visibleTextures) {
            visibleTextures.entrySet().removeIf(entry -> now - entry.getValue() > VISIBLE_TTL_MS);
        }
    }

    private void requeue(String key) {
        synchronized (dirtyTextures) {
            long now = System.nanoTime();
            firstDirtyNanos.putIfAbsent(key, now);
            lastDirtyNanos.putIfAbsent(key, now);
            dirtyTextures.add(key);
        }
    }

    private int surfaceLoadPriority(String leafKey, int regionX, int regionZ) {
        long nowMs = System.currentTimeMillis();
        synchronized (dirtyTextures) {
            SurfacePageDemand demand = pageDemands.get(leafKey);
            MapRequestLane lane = demand == null ? null : demand.effectiveLane(nowMs);
            if (lane == MapRequestLane.FULLSCREEN) {
                long ordinal = demand.fullscreenOrdinal;
                return lane.priorityBase()
                        - (int) Math.min(900_000L,
                                ordinal == Long.MAX_VALUE ? 0L : ordinal);
            }
            if (lane != null) return lane.priorityBase();
        }
        return distancePriority(regionX, regionZ);
    }

    private int distancePriority(int regionX, int regionZ) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 1;
        int playerRegionX = player.blockPosition().getX() >> 9;
        int playerRegionZ = player.blockPosition().getZ() >> 9;
        int distance = Math.abs(regionX - playerRegionX) + Math.abs(regionZ - playerRegionZ);
        return Math.max(1, 10_000 - distance * 100);
    }

    private void stageExactPageTable(PageTextureInfo page,
            PageAddress address, long revision) {
        if (page == null || address == null || page.atlasSlot < 0
                || page.publishedStamp == null) return;
        CaveAtlasRegion color = leafAtlas.region(page.atlasSlot, false);
        CaveAtlasRegion glow = leafAtlas.region(page.atlasSlot, true);
        int globalPageX = address.regionX() * MapPageLayout.PAGES_PER_REGION
                + address.pageX();
        int globalPageZ = address.regionZ() * MapPageLayout.PAGES_PER_REGION
                + address.pageZ();
        int flags = PageTableEntry.withCoverageMask(
                PageTableEntry.FLAG_PROTECTED, page.completeSubtileMask);
        if (page.completeSubtileMask == MapPageLayout.FULL_SUBTILE_MASK) {
            flags |= PageTableEntry.FLAG_COMPLETE;
        }
        if (color != null) {
            SurfacePublicationService.getInstance().stage(
                    new TileKey(page.publishedStamp.sessionId(), 0, 0,
                            globalPageX, globalPageZ,
                            TileKey.VARIANT_SURFACE_EXACT),
                    color.texture(), page.atlasSlot,
                    leafAtlas.storageGeneration(), revision, flags,
                    color.sourceX(), color.sourceY(), color.sourceSize(),
                    color.atlasSize());
        }
        if (glow != null && page.glowPixels != null) {
            SurfacePublicationService.getInstance().stage(
                    new TileKey(page.publishedStamp.sessionId(), 0, 0,
                            globalPageX, globalPageZ,
                            TileKey.VARIANT_SURFACE_GLOW),
                    glow.texture(), page.atlasSlot,
                    leafAtlas.storageGeneration(), revision, flags,
                    glow.sourceX(), glow.sourceY(), glow.sourceSize(),
                    glow.atlasSize());
        }
    }

    private void removeExactPageTable(PageTextureInfo page) {
        if (page == null || page.publishedStamp == null) return;
        PageAddress address = parsePageKey(page.key);
        if (address == null) return;
        int globalPageX = address.regionX() * MapPageLayout.PAGES_PER_REGION
                + address.pageX();
        int globalPageZ = address.regionZ() * MapPageLayout.PAGES_PER_REGION
                + address.pageZ();
        SurfacePublicationService table = SurfacePublicationService.getInstance();
        table.remove(new TileKey(page.publishedStamp.sessionId(), 0, 0,
                globalPageX, globalPageZ, TileKey.VARIANT_SURFACE_EXACT));
        table.remove(new TileKey(page.publishedStamp.sessionId(), 0, 0,
                globalPageX, globalPageZ, TileKey.VARIANT_SURFACE_GLOW));
    }

    private static TileKey exactTileKey(RevisionStamp stamp,
            PageAddress address, int variant) {
        RevisionStamp current = stamp != null ? stamp
                : MapSessionManager.getInstance().activeStamp();
        if (current == null || address == null) return null;
        return new TileKey(current.sessionId(), 0, 0,
                address.regionX() * MapPageLayout.PAGES_PER_REGION
                        + address.pageX(),
                address.regionZ() * MapPageLayout.PAGES_PER_REGION
                        + address.pageZ(), variant);
    }

    private static String key(int regionX, int regionZ) {
        return MapManager.getInstance().getDimensionCacheKey() + "|" + regionX + "," + regionZ;
    }

    private static String pageKey(int regionX, int regionZ, int pageX, int pageZ) {
        return key(regionX, regionZ) + ":" + pageX + "," + pageZ;
    }

    private static PageAddress parsePageKey(String value) {
        if (value == null) return null;
        try {
            int separator = value.lastIndexOf('|');
            int pageSeparator = value.lastIndexOf(':');
            if (separator < 0 || pageSeparator <= separator) return null;
            int regionComma = value.indexOf(',', separator + 1);
            int pageComma = value.indexOf(',', pageSeparator + 1);
            if (regionComma < 0 || pageComma < 0) return null;
            int regionX = Integer.parseInt(value.substring(separator + 1, regionComma));
            int regionZ = Integer.parseInt(value.substring(regionComma + 1, pageSeparator));
            int pageX = Integer.parseInt(value.substring(pageSeparator + 1, pageComma));
            int pageZ = Integer.parseInt(value.substring(pageComma + 1));
            return new PageAddress(regionX, regionZ, pageX, pageZ);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isCurrentDimensionKey(String key) {
        return key != null && key.startsWith(MapManager.getInstance().getDimensionCacheKey() + "|");
    }

    private static String texturePathDimension() {
        return MapManager.getInstance().getDimensionCacheKey().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9/._-]", "_");
    }

    private record PageAddress(int regionX, int regionZ, int pageX, int pageZ) {
    }

    private record SurfacePageBuildInputs(
            long[] pixels,
            int[] tints,
            byte[] known,
            int stride,
            int halo,
            int worldPageStartX,
            int worldPageStartZ,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks,
            byte[] light,
            SurfacePageBufferPool.Buffer pooledBuffer) {
        private void release() {
            SurfacePageBufferPool.getInstance().release(pooledBuffer);
        }
    }

    private record SurfaceBuildInputs(
            long[] pixels,
            int[] tints,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks,
            byte[] light) {
    }

    private enum FullscreenLeafState {
        SATISFIED,
        REQUESTABLE,
        WAITING,
        UNAVAILABLE
    }

    private static final class PendingLeafPublication {
        private final String regionKey;
        private final int regionX;
        private final int regionZ;
        private final long generation;
        private final long revision;
        private final int[] styled;
        private final int[] glow;
        private final long[][] knownPages;
        private final int[] pageOrder;
        private int cursor;

        private PendingLeafPublication(String regionKey, int regionX, int regionZ,
                long generation, long revision, int[] styled, int[] glow,
                long[][] knownPages, int[] pageOrder) {
            this.regionKey = regionKey;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.generation = generation;
            this.revision = revision;
            this.styled = styled;
            this.glow = glow;
            this.knownPages = knownPages;
            this.pageOrder = pageOrder;
        }
    }

    private static final class RegionTextureInfo {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final DynamicTexture glowTexture;
        private final ResourceLocation glowLocation;
        private long generation;
        private final int regionX;
        private final int regionZ;
        private CompletableFuture<MapTextureBuildWorker.PreparedPair> pending;
        private long uploadedRevision = Long.MIN_VALUE;
        private boolean initialized;

        private RegionTextureInfo(DynamicTexture texture, ResourceLocation location,
                DynamicTexture glowTexture, ResourceLocation glowLocation, long generation,
                int regionX, int regionZ) {
            this.texture = texture;
            this.location = location;
            this.glowTexture = glowTexture;
            this.glowLocation = glowLocation;
            this.generation = generation;
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        private void close() {
            if (pending != null) pending.cancel(false);
            MapResidencyManager.getInstance().remove(
                    "legacy:" + MapTextureManager.key(regionX, regionZ));
            Minecraft.getInstance().getTextureManager().release(location);
            Minecraft.getInstance().getTextureManager().release(glowLocation);
        }
    }

    private static final class SurfacePageDemand {
        private final long[] lastSeenByLane = new long[MapRequestLane.values().length];
        private final int[] priorityByLane = new int[MapRequestLane.values().length];
        private long fullscreenOrdinal = Long.MAX_VALUE;

        private SurfacePageDemand() {
            java.util.Arrays.fill(priorityByLane, Integer.MIN_VALUE);
        }

        private void observe(MapRequestLane lane, int priority, long nowMs,
                long traversalOrdinal) {
            int index = lane.ordinal();
            lastSeenByLane[index] = nowMs;
            priorityByLane[index] = Math.max(priorityByLane[index], priority);
            if (lane == MapRequestLane.FULLSCREEN
                    && traversalOrdinal != Long.MAX_VALUE) {
                // The ordinal belongs to the current viewport plan. Replacing it
                // prevents an overlapping pan from retaining a stale frontier rank.
                fullscreenOrdinal = traversalOrdinal;
            }
        }

        private void clearLane(MapRequestLane lane) {
            int index = lane.ordinal();
            lastSeenByLane[index] = 0L;
            priorityByLane[index] = Integer.MIN_VALUE;
            if (lane == MapRequestLane.FULLSCREEN) {
                fullscreenOrdinal = Long.MAX_VALUE;
            }
        }

        private boolean isLaneActive(MapRequestLane lane, long nowMs) {
            long seen = lastSeenByLane[lane.ordinal()];
            return seen != 0L && nowMs - seen <= lane.requestTtlMs();
        }

        private void expire(long nowMs) {
            for (MapRequestLane lane : MapRequestLane.values()) {
                int index = lane.ordinal();
                long seen = lastSeenByLane[index];
                if (seen == 0L || nowMs - seen <= lane.requestTtlMs()) continue;
                lastSeenByLane[index] = 0L;
                priorityByLane[index] = Integer.MIN_VALUE;
                if (lane == MapRequestLane.FULLSCREEN) {
                    fullscreenOrdinal = Long.MAX_VALUE;
                }
            }
        }

        private MapRequestLane effectiveLane(long nowMs) {
            MapRequestLane best = null;
            for (MapRequestLane lane : MapRequestLane.values()) {
                long seen = lastSeenByLane[lane.ordinal()];
                if (seen == 0L || nowMs - seen > lane.requestTtlMs()) continue;
                if (lane.strongerThan(best)) best = lane;
            }
            return best;
        }

        private int effectivePriority(long nowMs) {
            int best = Integer.MIN_VALUE;
            for (MapRequestLane lane : MapRequestLane.values()) {
                int index = lane.ordinal();
                long seen = lastSeenByLane[index];
                if (seen == 0L || nowMs - seen > lane.requestTtlMs()) continue;
                best = Math.max(best, priorityByLane[index]);
            }
            return best;
        }
    }

    private enum PageAuthority {
        NONE,
        LEGACY_DERIVED,
        EXACT
    }

    private static final class PageTextureInfo {
        private final String key;
        private int atlasSlot;
        private final long generation;
        private long uploadedRevision = Long.MIN_VALUE;
        private CompletableFuture<MapTextureBuildWorker.PreparedPair> pending;
        private MapRequestLane pendingLane;
        private boolean pendingCompletionRecorded;
        private boolean initialized;
        private boolean residentIndexed;
        private int knownColumns;
        private long retryAfterNanos;
        private long residencyRetryAfterNanos;
        private long gpuRetryAfterNanos;
        private int gpuReservationFailures;
        private long lastVisibleRenderEpoch;
        private long lastGpuPublicationNanos;
        private PageAuthority authority = PageAuthority.NONE;
        private int[] colorPixels;
        private int[] glowPixels;
        /** Color pixels are currently referenced by an async LOD leaf job. */
        private boolean pixelSnapshotShared;
        /** Exact authoritative rows already merged into this retained GPU page. */
        private long[] knownRows;
        /** Complete Minecraft-chunk subtiles resident inside the 64x64 page. */
        private int completeSubtileMask;
        private RevisionStamp publishedStamp;

        private PageTextureInfo(String key, int atlasSlot, long generation) {
            this.key = key;
            this.atlasSlot = atlasSlot;
            this.generation = generation;
        }
    }
}
