package com.velorise.simplemap.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.SurfaceLeafAtlas;
import com.velorise.simplemap.client.cave.SurfaceLodTree;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
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
    /** Bounded ordered fullscreen window. Admission adapts to measured CPU pressure. */
    private static final int FULLSCREEN_ACTIVE_WINDOW_MIN = 4;
    private static final int FULLSCREEN_ACTIVE_WINDOW_MAX = 12;

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
    private final Map<String, Long> pageRevisions = new HashMap<>();
    /** Per-lane demand keeps the player-centred minimap ahead of panned fullscreen work. */
    private final Map<String, SurfacePageDemand> pageDemands = new HashMap<>();
    private final MapViewLoadPlanner.State[] visiblePagePlanners =
            new MapViewLoadPlanner.State[MapRequestLane.values().length];
    private final MapViewLoadPlanner.Page[] fullscreenSliceBuffer =
            new MapViewLoadPlanner.Page[MapViewLoadPlanner.FULLSCREEN_SLICE_SIZE];
    private final MapViewLoadPlanner.Page[] minimapHaloBuffer =
            new MapViewLoadPlanner.Page[(MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES * 2 + 1)
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
    private final Map<String, Integer> blockColorsCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> vanillaBlockColorsCache = new ConcurrentHashMap<>();
    private final Map<String, BlockTintPolicy> tintPolicyCache = new ConcurrentHashMap<>();
    private long lastUploadTime;

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
        long now = System.nanoTime();
        MapManager manager = MapManager.getInstance();
        synchronized (dirtyTextures) {
            for (int pageX = 0; pageX < MapPageLayout.PAGES_PER_REGION; pageX++) {
                for (int pageZ = 0; pageZ < MapPageLayout.PAGES_PER_REGION; pageZ++) {
                    addDirtyPageLocked(regionX, regionZ, pageX, pageZ, now);
                }
            }
            markNeighbourBoundaryLocked(manager, regionX - 1, regionZ,
                    MapPageLayout.PAGES_PER_REGION - 1, -1, now);
            markNeighbourBoundaryLocked(manager, regionX + 1, regionZ, 0, -1, now);
            markNeighbourBoundaryLocked(manager, regionX, regionZ - 1,
                    -1, MapPageLayout.PAGES_PER_REGION - 1, now);
            markNeighbourBoundaryLocked(manager, regionX, regionZ + 1,
                    -1, 0, now);
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

    private void addDirtyPageLocked(int regionX, int regionZ,
            int pageX, int pageZ, long now) {
        String regionKey = key(regionX, regionZ);
        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        revisions.merge(regionKey, 1L, Long::sum);
        pageRevisions.merge(leafKey, 1L, Long::sum);
        firstDirtyPageNanos.putIfAbsent(leafKey, now);
        lastDirtyPageNanos.put(leafKey, now);
        dirtyPages.add(leafKey);
        ExactPageStateTracker.getInstance().transition(
                "surface:" + leafKey, ExactPageState.REQUESTED,
                effectivePageLane(leafKey, System.currentTimeMillis()),
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
        int primaryGlobalPageX = Math.floorDiv(blockX, MapPageLayout.PAGE_SIZE);
        int primaryGlobalPageZ = Math.floorDiv(blockZ, MapPageLayout.PAGE_SIZE);
        MapManager manager = MapManager.getInstance();
        synchronized (dirtyTextures) {
            for (int globalPageX = minGlobalPageX; globalPageX <= maxGlobalPageX; globalPageX++) {
                int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
                int pageX = MapPageLayout.localPage(globalPageX);
                for (int globalPageZ = minGlobalPageZ; globalPageZ <= maxGlobalPageZ; globalPageZ++) {
                    int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
                    int pageZ = MapPageLayout.localPage(globalPageZ);
                    boolean primary = globalPageX == primaryGlobalPageX
                            && globalPageZ == primaryGlobalPageZ;
                    if (!primary && !manager.isRegionLoadedInCache(regionX, regionZ)
                            && !manager.hasRegionFile(regionX, regionZ)) {
                        continue;
                    }
                    String regionKey = key(regionX, regionZ);
                    String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
                    // Advance the region source revision so an older in-flight
                    // 512x512 fallback cannot overwrite a newer exact leaf.
                    revisions.merge(regionKey, 1L, Long::sum);
                    pageRevisions.merge(leafKey, 1L, Long::sum);
                    firstDirtyPageNanos.putIfAbsent(leafKey, now);
                    lastDirtyPageNanos.put(leafKey, now);
                    dirtyPages.add(leafKey);
                    pendingLeafPublications.removeIf(pending -> pending.regionKey.equals(regionKey));
                }
            }
        }
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
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    /**
     * Requests visible exact leaves around the user's attention point. Fullscreen
     * focus is the block under the mouse; minimap focus is the player. This mirrors
     * Xaero's distance comparator while keeping the branch hierarchy as fallback.
     */
    public void requestVisiblePages(double minX, double maxX, double minZ, double maxZ,
            double focusX, double focusZ, MapRequestLane lane) {
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int minRx = (int) Math.floor(minX - 1.0) >> 9;
        int maxRx = (int) Math.floor(maxX + 1.0) >> 9;
        int minRz = (int) Math.floor(minZ - 1.0) >> 9;
        int maxRz = (int) Math.floor(maxZ + 1.0) >> 9;
        int focusRx = clamp((int) Math.floor(focusX) >> 9, minRx, maxRx);
        int focusRz = clamp((int) Math.floor(focusZ) >> 9, minRz, maxRz);
        if (effectiveLane == MapRequestLane.MINIMAP) {
            markVisibleRegionsCenterOut(minRx, maxRx, minRz, maxRz,
                    focusRx, focusRz);
        } else {
            markVisibleRegionsStable(minRx, maxRx, minRz, maxRz);
        }

        int minGlobalPageX = Math.floorDiv((int) Math.floor(minX - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxGlobalPageX = Math.floorDiv((int) Math.floor(maxX + 1.0),
                MapPageLayout.PAGE_SIZE);
        int minGlobalPageZ = Math.floorDiv((int) Math.floor(minZ - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxGlobalPageZ = Math.floorDiv((int) Math.floor(maxZ + 1.0),
                MapPageLayout.PAGE_SIZE);
        int focusPageX = clamp(Math.floorDiv((int) Math.floor(focusX),
                MapPageLayout.PAGE_SIZE), minGlobalPageX, maxGlobalPageX);
        int focusPageZ = clamp(Math.floorDiv((int) Math.floor(focusZ),
                MapPageLayout.PAGE_SIZE), minGlobalPageZ, maxGlobalPageZ);

        if (effectiveLane == MapRequestLane.MINIMAP) {
            // Xaero's minimap is a separate demand producer: complete a compact
            // player/camera-centred exact halo before spending work elsewhere.
            int count = MapViewLoadPlanner.fillMinimapHalo(
                    minGlobalPageX, maxGlobalPageX,
                    minGlobalPageZ, maxGlobalPageZ,
                    focusPageX, focusPageZ, minimapHaloBuffer);
            int active = activeDemandCount(effectiveLane, System.currentTimeMillis());
            int admission = Math.max(0, Math.min(4, 8 - active));
            int admitted = 0;
            for (int i = 0; i < count && admitted < admission; i++) {
                MapViewLoadPlanner.Page page = minimapHaloBuffer[i];
                if (!isLeafRequestCandidate(page.x(), page.z(), effectiveLane)) continue;
                int priority = effectiveLane.priorityBase() + 180_000
                        - Math.min(160_000, page.ordinal() * 4_000);
                requestLeafResident(page.x(), page.z(), effectiveLane, priority);
                admitted++;
            }
            return;
        }

        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            /*
             * Fullscreen surface loading is intentionally cursor-independent.
             * A small ordered window is admitted from the current stable screen-row
             * slice. Later builds may finish early, but the GPU publication gate
             * holds them until every earlier ordinal has settled.
             */
            MapViewLoadPlanner.State planner =
                    visiblePagePlanners[effectiveLane.ordinal()];
            boolean viewportChanged = planner.configure(
                    MapManager.getInstance().getDimensionCacheKey(),
                    minGlobalPageX, maxGlobalPageX,
                    minGlobalPageZ, maxGlobalPageZ);
            if (viewportChanged) {
                clearAllPageDemandLane(MapRequestLane.FULLSCREEN);
                // Drop stale region reads from the previous viewport. The serial
                // MapProcessor can then follow the same ordered leaf frontier.
                MapProcessor.getInstance().clearSurfaceLoads();
            }

            long nowMs = System.currentTimeMillis();
            int active = activeDemandCount(effectiveLane, nowMs);
            int available = Math.max(0, fullscreenActiveWindow() - active);
            int slicesChecked = 0;
            while (slicesChecked++ < 2) {
                int sliceCount = planner.fillCurrentFullscreenSlice(
                        fullscreenSliceBuffer);
                boolean unsettled = false;
                int admitted = 0;
                for (int i = 0; i < sliceCount; i++) {
                    MapViewLoadPlanner.Page candidate = fullscreenSliceBuffer[i];
                    FullscreenLeafState state = fullscreenLeafState(
                            candidate.x(), candidate.z(), nowMs);
                    if (state == FullscreenLeafState.SATISFIED) {
                        continue;
                    }
                    if (state == FullscreenLeafState.UNAVAILABLE) {
                        clearFullscreenDemand(candidate.x(), candidate.z());
                        continue;
                    }
                    unsettled = true;
                    if (state == FullscreenLeafState.WAITING) {
                        refreshFullscreenDemand(candidate.x(), candidate.z(),
                                candidate.ordinal(), nowMs);
                        continue;
                    }
                    if (admitted >= available) continue;
                    int priority = effectiveLane.priorityBase() + 100_000
                            - Math.min(90_000, i * 900);
                    requestLeafResident(candidate.x(), candidate.z(),
                            effectiveLane, priority, candidate.ordinal());
                    admitted++;
                }
                if (unsettled || admitted > 0) return;
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
        MapManager manager = MapManager.getInstance();
        if (!manager.isRegionLoadedInCache(regionX, regionZ)
                && !manager.hasRegionFile(regionX, regionZ)
                && !(lane == MapRequestLane.MINIMAP
                        && manager.isViewingLiveDimension())) return false;
        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(leafKey);
            if (page == null) return true;
            if (page.initialized) return false;
            if (page.pending != null && !page.pending.isDone()) return false;
            return System.nanoTime() >= page.retryAfterNanos;
        }
    }

    private FullscreenLeafState fullscreenLeafState(
            int globalPageX, int globalPageZ, long nowMs) {
        int regionX = MapPageLayout.regionFromGlobalPage(globalPageX);
        int regionZ = MapPageLayout.regionFromGlobalPage(globalPageZ);
        MapManager manager = MapManager.getInstance();
        if (!manager.isRegionLoadedInCache(regionX, regionZ)
                && !manager.hasRegionFile(regionX, regionZ)) {
            return FullscreenLeafState.UNAVAILABLE;
        }

        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        MapManager.Region sourceRegion = manager.getRegion(
                regionX, regionZ, false);
        if (sourceRegion != null && sourceRegion.isLoaded()
                && !sourceRegion.hasAnyDataInPage(pageX, pageZ)) {
            // Unknown/unexplored leaves are holes by definition, not failed loads.
            // Skip them so one empty page cannot stall the entire visible frontier.
            return FullscreenLeafState.UNAVAILABLE;
        }
        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(leafKey);
            if (page != null && page.initialized) {
                return FullscreenLeafState.SATISFIED;
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
        synchronized (dirtyTextures) {
            pageDemands.computeIfAbsent(leafKey, ignored -> new SurfacePageDemand())
                    .observe(MapRequestLane.FULLSCREEN, priority, nowMs, ordinal);
            dirtyPages.add(leafKey);
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
        MapManager manager = MapManager.getInstance();
        if (!manager.isRegionLoadedInCache(regionX, regionZ)
                && !manager.hasRegionFile(regionX, regionZ)) {
            // The live minimap must be able to bootstrap the region under the
            // player before it has ever been saved. Fullscreen/static views remain
            // cache-only and do not create empty remote regions.
            if (effectiveLane == MapRequestLane.MINIMAP
                    && manager.isViewingLiveDimension()) {
                if (manager.getRegion(regionX, regionZ, true) == null) return;
            } else {
                return;
            }
        }
        int pageX = MapPageLayout.localPage(globalPageX);
        int pageZ = MapPageLayout.localPage(globalPageZ);
        String leafKey = pageKey(regionX, regionZ, pageX, pageZ);
        boolean refreshOnly = false;
        synchronized (pageCache) {
            PageTextureInfo page = pageCache.get(leafKey);
            // Xaero's viewing queue keeps the current leaf owned until it settles.
            // Refresh the demand timestamp while the immutable build is still in
            // flight instead of letting its lane TTL expire and admitting a later
            // page from another row.
            if (page != null && page.initialized) return;
            if (page != null && page.pending == null
                    && System.nanoTime() < page.retryAfterNanos) {
                refreshOnly = true;
            } else if (page != null && page.pending != null) {
                if (effectiveLane.strongerThan(page.pendingLane)
                        && page.pending.cancel(false)) {
                    page.pending = null;
                    page.pendingLane = null;
                    MapPipelineTelemetry.getInstance().recordTaskCancelledBeforeRun();
                } else {
                    refreshOnly = true;
                }
            }
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
            if (markDirty) dirtyPages.add(leafKey);
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
        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
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
        String key = pageKey(regionX, regionZ, pageX, pageZ);
        synchronized (pageCache) {
            PageTextureInfo info = pageCache.get(key);
            if (info == null || !info.initialized || info.atlasSlot < 0
                    || info.knownColumns <= 0) return null;
            markRegionVisible(key(regionX, regionZ));
            MapResidencyManager.getInstance().touch("surface:" + key);
            return leafAtlas.region(info.atlasSlot, false);
        }
    }

    public CaveAtlasRegion peekGlowPageRegion(int regionX, int regionZ, int pageX, int pageZ) {
        String key = pageKey(regionX, regionZ, pageX, pageZ);
        synchronized (pageCache) {
            PageTextureInfo info = pageCache.get(key);
            if (info == null || !info.initialized || info.atlasSlot < 0
                    || info.knownColumns <= 0) return null;
            MapResidencyManager.getInstance().touch("surface:" + key);
            return leafAtlas.region(info.atlasSlot, true);
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


    private int fullscreenActiveWindow() {
        if (MapPerformanceGovernor.getInstance().underPressure()) {
            return FULLSCREEN_ACTIVE_WINDOW_MIN;
        }
        MapWorkScheduler.Snapshot work = MapWorkScheduler.snapshot();
        if (work.cpuTotalCost() < 240 && work.cpuActive() < 2) {
            return FULLSCREEN_ACTIVE_WINDOW_MAX;
        }
        return 8;
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
        uploadDirtyTextures(false);
    }

    /**
     * Foreground publication path used by the central map runner. It only
     * schedules and publishes exact 64x64 leaves. Compatibility 512x512 region
     * textures are excluded so they cannot compete with visible exact/LOD work.
     */
    public void uploadExactTextures(boolean force) {
        uploadExactTextures(null, force);
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

        int pageBudget;
        long pageBudgetNanos;
        if (force) {
            pageBudget = 16;
            pageBudgetNanos = 6_000_000L;
        } else if (effectiveLane == MapRequestLane.MINIMAP) {
            pageBudget = 4;
            pageBudgetNanos = 4_000_000L;
        } else if (effectiveLane == MapRequestLane.FULLSCREEN) {
            // More immutable builds may be in flight, but per-frame publication
            // remains small and is governed by the shared GPU/frame ledger.
            pageBudget = MapPerformanceGovernor.getInstance().underPressure() ? 2 : 4;
            pageBudgetNanos = Math.min(3_000_000L,
                    MapPerformanceGovernor.getInstance().textureUploadBudgetNanos(true));
        } else {
            pageBudget = 1;
            pageBudgetNanos = 1_500_000L;
        }
        long pageDeadline = System.nanoTime() + pageBudgetNanos;
        processDirtyPages(pageBudget, force, pageDeadline, effectiveLane);

        // Compatibility-derived 512-region leaves are maintenance work. Never let
        // them leak into a visible minimap/fullscreen frame as unordered islands.
        if (force || effectiveLane == null
                || effectiveLane == MapRequestLane.BACKGROUND
                || effectiveLane == MapRequestLane.PREFETCH) {
            long leafDeadline = System.nanoTime()
                    + (force ? 2_000_000L : 500_000L);
            publishPendingLeafPages(force ? 4 : 1, leafDeadline);
        }
    }


    /**
     * Compatibility entry point retained for manual refresh/debug paths. Normal
     * gameplay calls {@link #uploadExactTextures(boolean)} through
     * {@link MapPublicationCoordinator} and therefore never builds legacy region
     * textures in the foreground.
     */
    public void uploadDirtyTextures(boolean force) {
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
            if (!force && System.nanoTime() > deadlineNanos) {
                requeuePage(leafKey);
                continue;
            }
            PageAddress address = parsePageKey(leafKey);
            if (address == null) continue;
            MapManager.Region region = MapManager.getInstance().getRegion(
                    address.regionX(), address.regionZ(), false);
            if (region == null || !region.isLoaded()) {
                MapProcessor.getInstance().enqueueSurfaceLoad(
                        address.regionX(), address.regionZ(),
                        surfaceLoadPriority(leafKey,
                                address.regionX(), address.regionZ()));
                requeuePage(leafKey);
                continue;
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
            if (page.initialized && page.colorPixels != null
                    && page.glowPixels != null
                    && page.uploadedRevision >= requestedRevision) {
                // GPU-only eviction was repaired from the retained CPU snapshot.
                // Do not restyle the same 64x64 page from world data again.
                clearPageDemandLane(leafKey, requestedLane == null
                        ? MapRequestLane.BACKGROUND : requestedLane);
                synchronized (dirtyTextures) {
                    dirtyPages.remove(leafKey);
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
        List<String> candidates = new ArrayList<>();
        long nowNanos = System.nanoTime();
        long nowMs = System.currentTimeMillis();
        Set<String> visibleRegions;
        synchronized (visibleTextures) {
            visibleRegions = new LinkedHashSet<>(visibleTextures.keySet());
        }
        synchronized (dirtyTextures) {
            if (dirtyPages.isEmpty()) return candidates;
            for (String leafKey : dirtyPages) {
                if (!isCurrentDimensionKey(leafKey)
                        || !isPageReadyForPublication(leafKey, nowNanos, force)) continue;
                if (!force && requestedLane != null) {
                    SurfacePageDemand demand = pageDemands.get(leafKey);
                    MapRequestLane lane = demand == null
                            ? null : demand.effectiveLane(nowMs);
                    if (lane != requestedLane) continue;
                }
                candidates.add(leafKey);
            }
            candidates.sort((left, right) -> compareSurfacePageDemand(
                    left, right, nowMs, visibleRegions));
            if (candidates.size() > budget) {
                candidates = new ArrayList<>(candidates.subList(0, budget));
            }
            dirtyPages.removeAll(candidates);
        }
        return candidates;
    }

    private int compareSurfacePageDemand(String left, String right, long nowMs,
            Set<String> visibleRegions) {
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
        boolean leftVisible = visibleRegions.contains(regionKeyFromPageKey(left));
        boolean rightVisible = visibleRegions.contains(regionKeyFromPageKey(right));
        if (leftVisible != rightVisible) return leftVisible ? -1 : 1;
        long leftFirst = firstDirtyPageNanos.getOrDefault(left, Long.MAX_VALUE);
        long rightFirst = firstDirtyPageNanos.getOrDefault(right, Long.MAX_VALUE);
        return Long.compare(leftFirst, rightFirst);
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
            dirtyPages.add(leafKey);
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
                    MapRequestLane uploadLane = page.pendingLane == null
                            ? MapRequestLane.BACKGROUND : page.pendingLane;
                    if (uploadLane == MapRequestLane.FULLSCREEN
                            && !isFullscreenPublicationFrontier(
                                    leafKey, System.currentTimeMillis())) {
                        // Later immutable builds may finish first. Keep them CPU-ready
                        // until the earliest visible ordinal reaches the atlas.
                        retainCompleted = true;
                        requeuePage(leafKey);
                        return;
                    }
                    if (!MapGpuBudgetController.getInstance().tryReserve(
                            MapGpuBudgetController.UploadKind.SURFACE_EXACT,
                            uploadLane, uploadLane == MapRequestLane.MINIMAP)) {
                        // Keep the completed immutable build instead of discarding
                        // it and restyling the same page when the next GPU window
                        // opens.
                        retainCompleted = true;
                        requeuePage(leafKey);
                        return;
                    }
                    // A page snapshot is internally coherent even if more columns
                    // arrived while it was being styled. Publish it progressively,
                    // then queue the newer revision instead of leaving the page black
                    // until scanning becomes completely quiet.
                    published = applyPreparedPage(page, prepared, address);
                    if (!published && page.atlasSlot < 0
                            && prepared.styled() != null && prepared.glow() != null) {
                        // No safely replaceable atlas slot exists yet. Keep the
                        // completed immutable payload and retry after branch
                        // publication creates an eviction fence.
                        retainCompleted = true;
                    }
                    if (published && uploadLane == MapRequestLane.FULLSCREEN) {
                        clearPageDemandLane(leafKey, MapRequestLane.FULLSCREEN);
                    }
                }
                if (!published || !page.initialized
                        || currentRevision > prepared.revision()) {
                    requeuePage(leafKey);
                } else {
                    synchronized (dirtyTextures) {
                        dirtyPages.remove(leafKey);
                        firstDirtyPageNanos.remove(leafKey);
                        lastDirtyPageNanos.remove(leafKey);
                    }
                }
            } catch (RuntimeException exception) {
                MapPipelineTelemetry.getInstance().recordExactBuildDiscarded();
                LOGGER.debug("Discarded failed/stale surface page job {}", leafKey, exception);
                requeuePage(leafKey);
            } finally {
                if (!retainCompleted) {
                    page.pending = null;
                    page.pendingLane = null;
                    page.pendingCompletionRecorded = false;
                }
            }
            return;
        }

        long revision;
        synchronized (dirtyTextures) {
            revision = pageRevisions.getOrDefault(leafKey, 0L);
        }
        MapRequestLane buildLane = effectivePageLane(leafKey, System.currentTimeMillis());
        // Avoid allocating/copying a 68x68 snapshot when the shared CPU ledger
        // already knows that the worker pool is saturated.
        if (!MapWorkScheduler.canAdmitCpu(buildLane, 8)) {
            requeuePage(leafKey);
            return;
        }
        SurfacePageBuildInputs inputs = captureSurfacePageBuildInputs(address);
        synchronized (dirtyTextures) {
            long afterCapture = pageRevisions.getOrDefault(leafKey, 0L);
            if (afterCapture != revision) {
                // A source or halo dependency changed while the compact snapshot
                // was being copied. Never label that mixed snapshot with the newer
                // revision; retry from one coherent revision instead.
                inputs.release();
                requeuePage(leafKey);
                return;
            }
        }
        ExactPageStateTracker.getInstance().transition(
                "surface:" + leafKey, ExactPageState.CPU_READY,
                buildLane, revision);
        ExactPageStateTracker.getInstance().transition(
                "surface:" + leafKey, ExactPageState.BUILDING,
                buildLane, revision);
        MapPipelineTelemetry.getInstance().recordExactBuildQueued();
        long exactBuildQueuedNanos = System.nanoTime();
        long viewportEpoch = MapWorkScheduler.viewportEpoch(buildLane);
        CompletableFuture<MapTextureBuildWorker.PreparedPair> future =
                MapTextureBuildWorker.tryBuildSurfacePage(
                        inputs.pixels(), inputs.tints(), inputs.stride(), inputs.halo(),
                        inputs.worldPageStartX(), inputs.worldPageStartZ(),
                        inputs.biomePalette(), inputs.blockPalette(),
                        inputs.biomeLookup(), inputs.blockColors(), inputs.tintPolicies(),
                        inputs.tintDisabledBlocks(), MapConfig.blockColourMode,
                        MapConfig.displayFlowers, MapConfig.terrainSlopes, inputs.light(),
                        MapConfig.mapColorProfile, revision,
                        () -> MapManager.getInstance().isGenerationCurrent(page.generation)
                                && MapWorkScheduler.isViewportCurrent(
                                        buildLane, viewportEpoch),
                        buildLane.executorPriority());
        if (future == null) {
            inputs.release();
            ExactPageStateTracker.getInstance().transition(
                    "surface:" + leafKey, ExactPageState.FAILED_RETRYABLE,
                    buildLane, revision);
            MapPipelineTelemetry.getInstance().recordExactBuildDiscarded();
            requeuePage(leafKey);
            return;
        }
        page.pending = future;
        page.pendingLane = buildLane;
        page.pendingCompletionRecorded = false;
        future.whenComplete((ignored, throwable) -> {
            inputs.release();
            MapPipelineTelemetry.getInstance().recordStageNanos(
                    MapPipelineStage.EXACT_BUILD,
                    System.nanoTime() - exactBuildQueuedNanos);
            requeuePage(leafKey);
        });
    }

    private boolean applyPreparedPage(PageTextureInfo page,
            MapTextureBuildWorker.PreparedPair prepared, PageAddress address) {
        int expected = MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE;
        if (prepared.styled() == null || prepared.styled().length < expected
                || prepared.glow() == null || prepared.glow().length < expected) {
            return false;
        }
        long[] knownRows = prepared.pageKnownRows() != null
                && prepared.pageKnownRows().length > 0
                && prepared.pageKnownRows()[0] != null
                ? prepared.pageKnownRows()[0]
                : inferKnownRows(prepared.styled());
        int knownColumns = countKnownColumns(knownRows);
        if (knownColumns <= 0) {
            // An all-unknown page is not renderable content. Uploading it marked the
            // minimap as successful while only an opaque black atlas slot existed.
            page.retryAfterNanos = System.nanoTime() + 150_000_000L;
            ExactPageStateTracker.getInstance().transition(
                    "surface:" + page.key, ExactPageState.REQUESTED,
                    page.pendingLane, prepared.revision());
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
        leafAtlas.upload(page.atlasSlot, prepared.styled(), prepared.glow());
        long exactUploadNanos = System.nanoTime() - exactUploadStart;
        MapPipelineTelemetry.getInstance().recordStageNanos(
                MapPipelineStage.EXACT_UPLOAD, exactUploadNanos);
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.SURFACE_EXACT,
                exactUploadNanos);
        page.colorPixels = prepared.styled();
        page.glowPixels = prepared.glow();
        page.uploadedRevision = prepared.revision();
        page.initialized = true;
        page.knownColumns = knownColumns;
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
                page.pendingLane, prepared.revision());
        if (firstGpuPublication) MapPipelineTelemetry.getInstance().recordExactGpuReady();

        MapOverviewTextureManager.getInstance().updateSurfaceLeafPage(
                address.regionX() * MapPageLayout.PAGES_PER_REGION + address.pageX(),
                address.regionZ() * MapPageLayout.PAGES_PER_REGION + address.pageZ(),
                prepared.styled(), knownRows, rowsComplete(knownRows),
                prepared.revision(), page.pendingLane);
        trimPageCache();
        return true;
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
                        pixels, tints, biomePalette, biomeIndices,
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
        return new SurfacePageBuildInputs(pixels, tints, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, selectedColorCache,
                tintPolicyCache,
                Set.copyOf(MapConfig.blockColorOverrides.keySet()), light,
                pooledBuffer);
    }

    private static void remapWindow(MapManager.RegionWindow source,
            int destinationX, int destinationZ, int destinationStride,
            long[] destinationPixels, int[] destinationTints,
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
        future.whenComplete((ignored, throwable) -> {
            synchronized (textureCache) {
                if (textureCache.get(key) != info) return;
            }
            requeue(key);
        });
    }

    private RegionTextureInfo createTextureInfo(int regionX, int regionZ, long generation) {
        DynamicTexture texture = new DynamicTexture(512, 512, false);
        texture.setFilter(false, false);
        String dimension = texturePathDimension();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("simplemap",
                "regions/" + dimension + "/r_" + regionX + '_' + regionZ);
        Minecraft.getInstance().getTextureManager().register(location, texture);

        DynamicTexture glowTexture = new DynamicTexture(512, 512, false);
        glowTexture.setFilter(false, false);
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
        info.texture.setFilter(false, false);
        info.glowTexture.setFilter(false, false);
        info.uploadedRevision = prepared.revision();
        info.initialized = true;
        String legacyResidencyKey = "legacy:" + key(regionX, regionZ);
        MapResidencyManager.getInstance().register(
                legacyResidencyKey,
                MapResidencyManager.Kind.LEGACY,
                2L * 512L * 512L * Integer.BYTES,
                () -> evictLegacyRegionForBudget(regionX, regionZ));
        MapResidencyManager.getInstance().enforceBudget(
                legacyResidencyKey, MapRequestLane.BACKGROUND);

        // V14 leaf architecture: the 512x512 region remains a compatibility
        // fallback, while exact retained GPU leaves are published at 64x64 blocks.
        enqueueLeafPublication(regionX, regionZ, info.generation, prepared);
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
        int knownColumns = countKnownColumns(knownRows);
        if (knownColumns <= 0) return true;
        if (page.atlasSlot < 0) {
            page.atlasSlot = acquireSurfaceAtlasSlot(page.key);
            if (page.atlasSlot < 0) return false;
        }

        leafAtlas.upload(page.atlasSlot, leafPixels, leafGlow);
        page.colorPixels = leafPixels;
        page.glowPixels = leafGlow;
        page.uploadedRevision = pending.revision;
        page.initialized = true;
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

        boolean complete = rowsComplete(knownRows);
        MapOverviewTextureManager.getInstance().updateSurfaceLeafPage(
                pending.regionX * MapPageLayout.PAGES_PER_REGION + pageX,
                pending.regionZ * MapPageLayout.PAGES_PER_REGION + pageZ,
                leafPixels, knownRows, complete,
                pending.revision, MapRequestLane.BACKGROUND);
        trimPageCache();
        return true;
    }

    private PageTextureInfo ensurePageInfo(String key, long generation) {
        synchronized (pageCache) {
            PageTextureInfo existing = pageCache.get(key);
            if (existing != null && existing.generation == generation) {
                // Atlas slots are acquired only for a GPU-ready payload. Cold or
                // queued pages no longer reserve scarce residency while their CPU
                // build is still pending.
                if (existing.atlasSlot < 0 && existing.colorPixels != null
                        && existing.glowPixels != null && existing.knownColumns > 0) {
                    existing.atlasSlot = acquireSurfaceAtlasSlot(key);
                    if (existing.atlasSlot < 0) return null;
                    restoreSurfacePageResidency(existing);
                }
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
        for (var entry : pageCache.entrySet()) {
            PageTextureInfo candidate = entry.getValue();
            if (candidate.pending != null || candidate.atlasSlot < 0
                    || entry.getKey().equals(protectedKey)
                    || !hasReplacementCoverage(candidate)) continue;
            String residencyKey = "surface:" + entry.getKey();
            candidates.add(residencyKey);
            byKey.put(residencyKey, candidate);
        }
        String victimKey = MapResidencyManager.getInstance().chooseVictim(
                candidates, "surface:" + protectedKey);
        PageTextureInfo victim = byKey.get(victimKey);
        if (victim == null) return -1;
        releaseSurfaceAtlasResidency(victim);
        return leafAtlas.acquireSlot();
    }

    private PageTextureInfo oldestColdSurfacePage(String protectedKey) {
        for (var entry : pageCache.entrySet()) {
            PageTextureInfo candidate = entry.getValue();
            if (entry.getKey().equals(protectedKey) || candidate.pending != null) continue;
            if (candidate.atlasSlot < 0 || hasReplacementCoverage(candidate)) return candidate;
        }
        return null;
    }

    private void restoreSurfacePageResidency(PageTextureInfo page) {
        if (page == null || page.atlasSlot < 0 || page.colorPixels == null
                || page.glowPixels == null || page.knownColumns <= 0) return;
        leafAtlas.upload(page.atlasSlot, page.colorPixels, page.glowPixels);
        page.initialized = true;
        setPageResidentIndexed(page, true);
        String residentKey = "surface:" + page.key;
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.SURFACE_EXACT,
                2L * MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE * Integer.BYTES,
                () -> evictSurfacePageForBudget(page.key));
        MapResidencyManager.getInstance().enforceBudget(
                residentKey, MapRequestLane.FULLSCREEN);
        ExactPageStateTracker.getInstance().transition(
                residentKey, ExactPageState.GPU_READY,
                page.pendingLane, page.uploadedRevision);
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
        BlockTextureColorSampler.clearCache();
    }

    public void invalidateStyle() {
        SurfaceLodTree.invalidatePersistentCache();
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
                        firstDirtyPageNanos.putIfAbsent(leafKey, now);
                        lastDirtyPageNanos.put(leafKey, now);
                        dirtyPages.add(leafKey);
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
        pendingLeafPublications.clear();
        leafAtlas.resetSlots();
        ExactPageStateTracker.getInstance().clearPrefix("surface:");
        synchronized (dirtyTextures) {
            dirtyTextures.clear();
            dirtyPages.clear();
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
            pageDemands.entrySet().removeIf(entry -> {
                entry.getValue().expire(nowMs);
                return entry.getValue().effectiveLane(nowMs) == null
                        && !dirtyPages.contains(entry.getKey());
            });
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

    private void clearAllPageDemandLane(MapRequestLane lane) {
        synchronized (dirtyTextures) {
            for (SurfacePageDemand demand : pageDemands.values()) {
                demand.clearLane(lane);
            }
        }
    }

    private MapRequestLane effectivePageLane(String leafKey, long nowMs) {
        synchronized (dirtyTextures) {
            SurfacePageDemand demand = pageDemands.get(leafKey);
            MapRequestLane lane = demand == null ? null : demand.effectiveLane(nowMs);
            return lane == null ? MapRequestLane.BACKGROUND : lane;
        }
    }

    private static String regionKeyFromPageKey(String leafKey) {
        if (leafKey == null) return "";
        int separator = leafKey.lastIndexOf(':');
        return separator < 0 ? leafKey : leafKey.substring(0, separator);
    }


    private boolean evictSurfacePageForBudget(String pageKey) {
        if (pageKey == null || renderBatchDepth > 0) return false;
        synchronized (pageCache) {
            PageTextureInfo retired = pageCache.get(pageKey);
            if (retired == null || retired.pending != null
                    || retired.atlasSlot < 0 || !retired.initialized
                    || !hasReplacementCoverage(retired)) return false;
            releaseSurfaceAtlasResidency(retired);
            return true;
        }
    }

    private void releaseSurfaceAtlasResidency(PageTextureInfo info) {
        if (info == null || info.atlasSlot < 0) return;
        setPageResidentIndexed(info, false);
        leafAtlas.releaseSlot(info.atlasSlot);
        MapResidencyManager.getInstance().remove("surface:" + info.key);
        info.atlasSlot = -1;
        info.initialized = false;
        ExactPageStateTracker.getInstance().transition(
                "surface:" + info.key, ExactPageState.GPU_EVICTED,
                info.pendingLane, info.uploadedRevision);
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
        if (info.pending != null && info.pending.cancel(false)) {
            MapPipelineTelemetry.getInstance().recordTaskCancelledBeforeRun();
        }
        MapRequestLane retiredLane = info.pendingLane;
        info.pending = null;
        info.pendingLane = null;
        info.pendingCompletionRecorded = false;
        setPageResidentIndexed(info, false);
        if (info.atlasSlot >= 0) leafAtlas.releaseSlot(info.atlasSlot);
        MapResidencyManager.getInstance().remove("surface:" + info.key);
        info.atlasSlot = -1;
        info.initialized = false;
        ExactPageStateTracker.getInstance().transition(
                "surface:" + info.key, ExactPageState.GPU_EVICTED,
                retiredLane, info.uploadedRevision);
        info.colorPixels = null;
        info.glowPixels = null;
        synchronized (dirtyTextures) {
            dirtyPages.remove(info.key);
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
                fullscreenOrdinal = Math.min(fullscreenOrdinal, traversalOrdinal);
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
        private PageAuthority authority = PageAuthority.NONE;
        private int[] colorPixels;
        private int[] glowPixels;

        private PageTextureInfo(String key, int atlasSlot, long generation) {
            this.key = key;
            this.atlasSlot = atlasSlot;
            this.generation = generation;
        }
    }
}
