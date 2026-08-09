package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.SurfaceLodTree;
import com.velorise.simplemap.client.lod.RegionLodGraph;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

/**
 * Legacy region overviews plus the page-rooted recursive surface LOD tree.
 *
 * Keys include the viewed dimension so switching dimensions can retain a small
 * warm GPU cache without ever drawing a tile from another dimension. Layered
 * cave rendering intentionally does not use these parents because downsampling
 * destroys thin cave passages; it streams exact textures instead.
 */
public final class MapOverviewTextureManager {
    public static final int MODE_SURFACE = 0;
    public static final int MODE_LAYER = 1;
    public static final int MODE_FULL = 2;

    private static final MapOverviewTextureManager INSTANCE = new MapOverviewTextureManager();
    private static final int MAX_TEXTURES =
            MapMemoryBudgetPolicy.overviewTextureLimit();
    /** Short visible-first recolour sweep after a style generation change. */
    private static final long STYLE_REFRESH_WINDOW_MS = 2_500L;

    private final Map<Key, TextureInfo> textures = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<Key> dirty = new LinkedHashSet<>();
    private final Map<Key, Long> revisions = new HashMap<>();
    private final List<TextureInfo> deferredCloses = new ArrayList<>();
    private final SurfaceLodTree surfaceLodTree = new SurfaceLodTree();
    /** M4 durable authority. The factor-2 tree below is now a refinement adapter. */
    private final RegionSurfaceLodService regionLodService =
            RegionSurfaceLodService.getInstance();
    /** Overview is a compatibility fallback and runs only on the shared background lane. */
    private int renderBatchDepth;
    private long lastUploadMs;
    private volatile long styleRefreshUntilMs;
    private String streamDimension = "";
    private int streamMode = Integer.MIN_VALUE;
    private int streamLayerY = Integer.MIN_VALUE;
    private int streamStride = Integer.MIN_VALUE;
    private int streamMinRx = Integer.MIN_VALUE;
    private int streamMaxRx = Integer.MIN_VALUE;
    private int streamMinRz = Integer.MIN_VALUE;
    private int streamMaxRz = Integer.MIN_VALUE;
    private long[] streamPlan = new long[0];
    private int streamCursor;
    private long streamRestartMs;
    /** Density-correct branch level requested by the latest visible surface view. */
    private volatile int preferredSurfaceBranchLevel = 1;

    private MapOverviewTextureManager() {
    }

    public static MapOverviewTextureManager getInstance() {
        return INSTANCE;
    }

    /** Called after the GPU page-table swap once per rendered frame. */
    public void onPageTableFrameBoundary() {
        surfaceLodTree.onPageTableFrameBoundary();
        regionLodService.onPageTableFrameBoundary();
    }

    public ResourceLocation getSurface(int rx, int rz, int stride, boolean cachedOnly) {
        return get(new Key(currentDimension(), MODE_SURFACE, 0, rx, rz,
                normalizeStride(stride)), cachedOnly);
    }

    public ResourceLocation getLayer(int layerY, int rx, int rz, int stride, boolean cachedOnly) {
        return get(new Key(currentDimension(), MODE_LAYER, layerY, rx, rz,
                normalizeStride(stride)), cachedOnly);
    }

    public ResourceLocation getFull(int rx, int rz, int stride, boolean cachedOnly) {
        return get(new Key(currentDimension(), MODE_FULL, 0, rx, rz,
                normalizeStride(stride)), cachedOnly);
    }

    /** Queues compact parent textures from client tick, never from rendering. */
    public void requestVisible(int mode, int layerY, double minX, double maxX,
            double minZ, double maxZ, int stride) {
        // Exact layered cave tiles are intentionally preserved at every zoom.
        if (mode == MODE_LAYER) return;
        int safeStride = normalizeStride(stride);
        String dimension = currentDimension();
        int minRx = (int) Math.floor(minX - 1.0) >> 9;
        int maxRx = (int) Math.floor(maxX + 1.0) >> 9;
        int minRz = (int) Math.floor(minZ - 1.0) >> 9;
        int maxRz = (int) Math.floor(maxZ + 1.0) >> 9;
        long now = System.currentTimeMillis();

        boolean changed = !dimension.equals(streamDimension)
                || mode != streamMode || layerY != streamLayerY
                || safeStride != streamStride
                || minRx != streamMinRx || maxRx != streamMaxRx
                || minRz != streamMinRz || maxRz != streamMaxRz;
        if (changed) {
            if (mode == MODE_SURFACE
                    && MapManager.getInstance().isViewingLiveDimension()
                    && MapPerformanceGovernor.getInstance().isFullscreenOpen()) {
                MapProcessor.getInstance().clearSurfaceLoads();
            }
            streamDimension = dimension;
            streamMode = mode;
            streamLayerY = layerY;
            streamStride = safeStride;
            streamMinRx = minRx;
            streamMaxRx = maxRx;
            streamMinRz = minRz;
            streamMaxRz = maxRz;
            streamPlan = buildSweepPlan(minRx, maxRx, minRz, maxRz);
            streamCursor = 0;
            streamRestartMs = 0L;
        } else {
            if (now < streamRestartMs) return;
            if (streamCursor >= streamPlan.length) streamCursor = 0;
        }

        // Spread one complete viewport sweep across roughly one second instead of
        // admitting a short burst and then waking again on an exact 1 Hz boundary.
        // The old pulse was visible as a small periodic hitch even when average FPS
        // remained high.
        int admission = Math.min(2, overviewAdmissionBudget(safeStride));
        int admitted = 0;
        while (streamCursor < streamPlan.length && admitted < admission) {
            long packed = streamPlan[streamCursor++];
            int rx = (int) (packed >> 32);
            int rz = (int) packed;
            Key key = new Key(dimension, mode, layerY, rx, rz, safeStride);
            int orderedPriority = 500_000 - Math.min(400_000, streamCursor);
            if (ensureSource(key, orderedPriority)) requestDirty(key);
            admitted++;
        }
        int groups = Math.max(1, (streamPlan.length + admission - 1) / admission);
        long cadenceMs = Math.max(20L, Math.min(200L, 1_000L / groups));
        streamRestartMs = now + cadenceMs;
    }

    private static int overviewAdmissionBudget(int stride) {
        if (stride >= 32) return 2;
        if (stride >= 16) return 3;
        if (stride >= 8) return 4;
        return 6;
    }

    private static long[] buildSweepPlan(int minRx, int maxRx,
            int minRz, int maxRz) {
        int width = Math.max(0, maxRx - minRx + 1);
        int height = Math.max(0, maxRz - minRz + 1);
        long[] result = new long[width * height];
        int cursor = 0;
        // Compatibility overview requests follow the same stable visual frontier
        // as region authority: top-to-bottom, then left-to-right. The previous
        // centre-distance insertion sort recreated isolated coarse islands and
        // also spent O(n^2) work whenever the viewport changed.
        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                result[cursor++] = pack(rx, rz);
            }
        }
        return result;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static int strideForScale(float scale) {
        if (scale >= 0.50f) return 2;
        if (scale >= 0.25f) return 4;
        if (scale >= 0.125f) return 8;
        if (scale >= 0.0625f) return 16;
        if (scale >= 0.03125f) return 32;
        return 64;
    }

    /** A fixed 64x64 region source feeds the recursive surface LOD tree. */
    public static int sourceStrideForScale(float scale) {
        return scale < 0.25f ? 8 : strideForScale(scale);
    }

    public void setPreferredSurfaceScale(float scale) {
        int densityLevel = MapLodPolicy.branchLevel(scale, 3);
        preferredSurfaceBranchLevel = Math.max(1, densityLevel);
    }

    /**
     * Publishes the current fullscreen Surface attention window to the LOD tree.
     * This is priority metadata only; durable dirty nodes outside the viewport are
     * preserved and resume through the normal background path.
     */
    public void setPreferredSurfaceView(float scale, int hierarchyLevel,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int focusPageX, int focusPageZ) {
        setPreferredSurfaceView(scale, hierarchyLevel,
                minPageX, maxPageX, minPageZ, maxPageZ,
                focusPageX, focusPageZ, MapRequestLane.FULLSCREEN);
    }

    public void setPreferredSurfaceView(float scale, int hierarchyLevel,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int focusPageX, int focusPageZ, MapRequestLane lane) {
        setPreferredSurfaceScale(scale);
        int preferred = Math.max(1, hierarchyLevel);
        surfaceLodTree.setVisibleWindow(currentDimension(), preferred,
                minPageX, maxPageX, minPageZ, maxPageZ, focusPageX, focusPageZ);
        regionLodService.setVisibleView(
                MapSessionManager.getInstance().activeStamp(), scale,
                minPageX, maxPageX, minPageZ, maxPageZ,
                focusPageX, focusPageZ, lane);
    }

    public CaveAtlasRegion peekSurfaceBranch(int level, int nodeX, int nodeZ) {
        return surfaceLodTree.peek(currentDimension(), level, nodeX, nodeZ);
    }

    /** Region-centric M4 coarse node. Level 0 spans one 512x512 region. */
    public CaveAtlasRegion peekRegionSurfaceBranch(int level, int nodeX,
            int nodeZ) {
        return regionLodService.peek(level, nodeX, nodeZ);
    }

    public boolean hasRegionSurfaceBranchData(int level, int nodeX,
            int nodeZ) {
        return regionLodService.hasData(level, nodeX, nodeZ);
    }

    public boolean hasSurfaceBranchData(int level, int nodeX, int nodeZ) {
        return surfaceLodTree.hasData(currentDimension(), level, nodeX, nodeZ);
    }

    /** Receives one exact 64x64 surface leaf from the shared texture store. */
    public void updateSurfaceLeafPage(int globalPageX, int globalPageZ,
            int[] pixels64, long[] knownRows, boolean complete) {
        updateSurfaceLeafPage(globalPageX, globalPageZ, pixels64, knownRows,
                complete, System.nanoTime(), MapRequestLane.BACKGROUND);
    }

    public void updateSurfaceLeafPage(int globalPageX, int globalPageZ,
            int[] pixels64, long[] knownRows, boolean complete,
            long sourceRevision, MapRequestLane lane) {
        surfaceLodTree.updatePage(currentDimension(), globalPageX, globalPageZ,
                pixels64, knownRows, complete, sourceRevision, lane);
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null || !stamp.isCurrent()) return;
        int regionX = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int regionZ = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int localPageX = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int localPageZ = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int leafIndex = MapPageLayout.pageIndex(localPageX, localPageZ);
        boolean known = false;
        if (knownRows != null) {
            for (long row : knownRows) {
                if (row != 0L) {
                    known = true;
                    break;
                }
            }
        }
        regionLodService.updateExactLeaf(stamp, regionX, regionZ,
                leafIndex, sourceRevision, known, complete, true,
                pixels64, knownRows);
    }

    public void markSurfaceLeafEvicted(RevisionStamp stamp, int regionX,
            int regionZ, int localPageX, int localPageZ) {
        if (stamp == null) return;
        regionLodService.markExactLeafEvicted(stamp, regionX, regionZ,
                MapPageLayout.pageIndex(localPageX, localPageZ));
    }

    public RegionLodGraph.Summary lodGraphSummary() {
        return regionLodService.summary();
    }

    /** Coverage fence used before an exact atlas slot may be retired. */
    public boolean hasPublishedSurfaceCoverage(int globalPageX, int globalPageZ,
            long sourceRevision) {
        return regionLodService.coversExactPage(globalPageX, globalPageZ,
                sourceRevision)
                || surfaceLodTree.coversPage(currentDimension(), globalPageX,
                        globalPageZ, sourceRevision);
    }

    private static int normalizeStride(int stride) {
        int value = Math.max(2, Math.min(64, stride));
        int power = Integer.highestOneBit(value);
        return power < value ? Math.min(64, power << 1) : power;
    }

    private ResourceLocation get(Key key, boolean cachedOnly) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return null;
        synchronized (textures) {
            TextureInfo existing = textures.get(key);
            if (existing != null) {
                refreshGeneration(existing);
                if (existing.initialized) {
                    MapResidencyManager.getInstance().touch(
                            overviewResidencyKey(existing.key));
                    return existing.location;
                }
                return null;
            }
            if (cachedOnly) return null;
        }
        if (!sourceLoaded(key)) return null;
        requestDirty(key);
        return null;
    }

    private TextureInfo createTextureInfo(Key key) {
        Minecraft minecraft = Minecraft.getInstance();
        int size = 512 / key.stride;
        DynamicTexture texture = new DynamicTexture(size, size, false);
        texture.setFilter(false, false);
        configureOverviewSampling(texture);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("simplemap",
                "overview/" + texturePath(key.dimension) + "/m" + key.mode
                        + "_y" + key.layerY + "_s" + key.stride
                        + "_" + key.rx + '_' + key.rz);
        minecraft.getTextureManager().register(location, texture);
        return new TextureInfo(texture, location, key, generationFor(key));
    }

    private boolean sourceLoaded(Key key) {
        if (!isCurrentDimension(key)) return false;
        return switch (key.mode) {
            case MODE_SURFACE -> {
                MapManager.Region region = MapManager.getInstance().getRegion(key.rx, key.rz, false);
                yield region != null && region.isLoaded();
            }
            case MODE_LAYER -> {
                CaveMapManager manager = CaveMapManager.getInstance();
                CaveRegion region = manager.getActiveLayerY() == key.layerY
                        ? manager.getRegion(key.rx, key.rz, false) : null;
                yield region != null && region.isLoaded();
            }
            case MODE_FULL -> {
                FullCaveMapManager.FullRegion region = FullCaveMapManager.getInstance()
                        .getRegion(key.rx, key.rz, false);
                yield region != null && region.isLoaded();
            }
            default -> false;
        };
    }

    private boolean ensureSource(Key key) {
        return ensureSource(key, priority(key.rx, key.rz));
    }

    private boolean ensureSource(Key key, int requestPriority) {
        if (!isCurrentDimension(key)) return false;
        return switch (key.mode) {
            case MODE_SURFACE -> {
                MapManager manager = MapManager.getInstance();
                MapManager.Region region = manager.getRegion(key.rx, key.rz, false);
                if (region == null && manager.hasRegionFile(key.rx, key.rz)) {
                    MapProcessor.getInstance().enqueueSurfaceLoad(key.rx, key.rz,
                            requestPriority);
                }
                yield region != null && region.isLoaded();
            }
            case MODE_LAYER -> {
                CaveMapManager manager = CaveMapManager.getInstance();
                if (manager.getActiveLayerY() != key.layerY) yield false;
                CaveRegion region = manager.getRegion(key.rx, key.rz, false);
                if (region == null && (manager.hasRegionFile(key.rx, key.rz)
                        || VerticalCaveArchiveManager.getInstance().hasRegionData(key.rx, key.rz))) {
                    MapProcessor.getInstance().enqueueCaveLoad(key.layerY, key.rx, key.rz,
                            requestPriority);
                }
                yield region != null && region.isLoaded();
            }
            case MODE_FULL -> {
                FullCaveMapManager manager = FullCaveMapManager.getInstance();
                FullCaveMapManager.FullRegion region = manager.getRegion(key.rx, key.rz, false);
                if (region == null && manager.hasRegionFile(key.rx, key.rz)) {
                    MapProcessor.getInstance().enqueueFullCaveLoad(key.rx, key.rz,
                            requestPriority);
                }
                yield region != null && region.isLoaded();
            }
            default -> false;
        };
    }

    private long generationFor(Key key) {
        return switch (key.mode) {
            case MODE_SURFACE -> MapManager.getInstance().getGeneration();
            case MODE_LAYER -> CaveMapManager.getInstance().getLayerGeneration();
            case MODE_FULL -> FullCaveMapManager.getInstance().getGeneration();
            default -> 0L;
        };
    }

    private boolean generationCurrent(TextureInfo info) {
        if (!isCurrentDimension(info.key)) return false;
        return switch (info.key.mode) {
            case MODE_SURFACE -> MapManager.getInstance().isGenerationCurrent(info.generation);
            case MODE_LAYER -> CaveMapManager.getInstance().isLayerGenerationCurrent(
                    info.generation, info.key.layerY);
            case MODE_FULL -> FullCaveMapManager.getInstance().isGenerationCurrent(info.generation);
            default -> false;
        };
    }

    private void refreshGeneration(TextureInfo info) {
        if (!isCurrentDimension(info.key)) return;
        long generation = generationFor(info.key);
        if (info.generation == generation) return;
        if (info.pending != null) info.pending.cancel(false);
        info.pending = null;
        info.ready = null;
        info.generation = generation;
        info.uploadedRevision = Long.MIN_VALUE;
        markDirty(info.key);
    }

    public void markSurfaceRegionDirty(int rx, int rz) {
        markModeRegionDirty(MODE_SURFACE, 0, rx, rz);
    }

    public void markLayerRegionDirty(int layerY, int rx, int rz) {
        markModeRegionDirty(MODE_LAYER, layerY, rx, rz);
    }

    public void markFullRegionDirty(int rx, int rz) {
        markModeRegionDirty(MODE_FULL, 0, rx, rz);
    }

    private void markModeRegionDirty(int mode, int layerY, int rx, int rz) {
        String dimension = currentDimension();
        synchronized (textures) {
            for (Key key : textures.keySet()) {
                if (key.dimension.equals(dimension) && key.mode == mode
                        && key.layerY == layerY && key.rx == rx && key.rz == rz) {
                    markDirty(key);
                }
            }
        }
    }

    private void requestDirty(Key key) {
        TextureInfo info;
        synchronized (textures) {
            info = textures.get(key);
        }
        synchronized (dirty) {
            long currentRevision = revisions.getOrDefault(key, 0L);
            if (info != null && info.pending != null) return;
            if (info != null && info.initialized
                    && info.uploadedRevision >= currentRevision) return;
            if (dirty.contains(key)) return;
            if (currentRevision == 0L) revisions.put(key, 1L);
            dirty.add(key);
        }
    }

    /** Re-admits unfinished work without fabricating a new source/style revision. */
    private void requeue(Key key) {
        synchronized (dirty) {
            dirty.add(key);
        }
    }

    private void markDirty(Key key) {
        synchronized (dirty) {
            revisions.merge(key, 1L, Long::sum);
            dirty.add(key);
        }
    }

    /**
     * Publishes only recursive surface LOD branches. This is the normal
     * foreground path; compatibility overview textures are left for explicit
     * refresh/debug use and cannot consume visible-map publication time.
     */
    public void publishBranches(boolean focus) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> publishBranches(focus));
            return;
        }
        surfaceLodTree.synchronizeStorage();
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        long deadline = System.nanoTime() + Math.max(500_000L,
                governor.textureUploadBudgetNanos(focus) / 2);
        // M4 region authority always receives the first publication slice.
        regionLodService.publish(focus, deadline);
        int legacyBudget = regionLodService.legacyPublishBudget(focus);
        if (legacyBudget > 0 && System.nanoTime() < deadline) {
            surfaceLodTree.publish(legacyBudget, deadline,
                    preferredSurfaceBranchLevel);
        }
    }

    public void uploadDirtyTextures(boolean focus) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> uploadDirtyTextures(focus));
            return;
        }
        surfaceLodTree.synchronizeStorage();
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        long now = System.currentTimeMillis();
        if (!focus && !governor.isInteracting() && now - lastUploadMs < 60L) return;
        lastUploadMs = now;
        boolean styleRefresh = focus && now < styleRefreshUntilMs
                && !governor.underPressure();
        int budget = styleRefresh ? 6
                : (focus || governor.isInteracting()) ? 2 : 1;
        long deadline = System.nanoTime() + Math.max(500_000L,
                styleRefresh ? governor.textureUploadBudgetNanos(true)
                        : governor.textureUploadBudgetNanos(focus) / 2);

        // Manual compatibility path: M4 region coverage publishes first,
        // followed by the old factor-2 refinement adapter.
        regionLodService.publish(focus, deadline);
        int legacyBudget = regionLodService.legacyPublishBudget(focus);
        if (legacyBudget > 0 && System.nanoTime() < deadline) {
            surfaceLodTree.publish(legacyBudget, deadline,
                    preferredSurfaceBranchLevel);
        }
        if (System.nanoTime() >= deadline) return;

        List<Key> selected = selectDirty(budget);
        for (Key key : selected) {
            if (System.nanoTime() >= deadline) {
                markDirty(key);
                continue;
            }
            if (!ensureSource(key)) {
                markDirty(key);
                continue;
            }
            TextureInfo info;
            synchronized (textures) {
                info = textures.get(key);
                if (info == null) {
                    info = createTextureInfo(key);
                    textures.put(key, info);
                    trim();
                } else {
                    refreshGeneration(info);
                }
            }
            schedule(info, focus);
        }
    }

    private List<Key> selectDirty(int budget) {
        String dimension = currentDimension();
        List<Key> selected = new ArrayList<>(budget);
        synchronized (dirty) {
            for (Key key : dirty) {
                if (key.dimension.equals(dimension)) selected.add(key);
                if (selected.size() >= budget) break;
            }
            dirty.removeAll(selected);
        }
        // Do not reorder completed work by distance here. Request order is the
        // visible reveal contract; asynchronous builders may finish out of order,
        // but the dirty queue remains a deterministic commit gate.
        return selected;
    }

    private void schedule(TextureInfo info, boolean focus) {
        Key key = info.key;
        if (!generationCurrent(info)) return;
        if (info.ready != null) {
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.LEGACY,
                    MapRequestLane.BACKGROUND, focus)) {
                requeue(key);
                return;
            }
            MapTextureBuildWorker.PreparedSingle prepared = info.ready;
            info.ready = null;
            if (generationCurrent(info)) {
                apply(info, prepared);
                synchronized (dirty) {
                    if (revisions.getOrDefault(key, 0L) > prepared.revision()) dirty.add(key);
                }
            }
            return;
        }
        if (info.pending != null) {
            if (!info.pending.isDone()) return;
            try {
                info.ready = info.pending.join();
            } catch (RuntimeException ignored) {
                requeue(key);
            } finally {
                info.pending = null;
            }
            if (info.ready != null) {
                requeue(key);
            }
            return;
        }

        long revision;
        synchronized (dirty) {
            revision = revisions.getOrDefault(key, 0L);
        }
        CompletableFuture<MapTextureBuildWorker.PreparedSingle> future = switch (key.mode) {
            case MODE_SURFACE -> buildSurface(info, revision);
            case MODE_LAYER -> buildLayer(info, revision);
            case MODE_FULL -> buildFull(info, revision);
            default -> null;
        };
        if (future == null) {
            requeue(key);
            return;
        }
        info.pending = future;
        future.whenComplete((ignored, throwable) -> requeue(key));
    }

    private CompletableFuture<MapTextureBuildWorker.PreparedSingle> buildSurface(
            TextureInfo info, long revision) {
        Key key = info.key;
        MapManager.Region region = MapManager.getInstance().getRegion(key.rx, key.rz, false);
        if (region == null || !region.isLoaded()) return null;

        long[] pixels = region.snapshotPackedPixels();
        int[] tints = region.snapshotTints();
        List<String> biomePalette = region.snapshotBiomePalette();
        List<String> blockPalette = region.snapshotBlockPalette();

        var level = Minecraft.getInstance().level;
        Registry<Biome> biomeRegistry = level == null ? null
                : level.registryAccess().registryOrThrow(Registries.BIOME);
        Biome[] resolvedBiomes = new Biome[biomePalette.size()];
        for (int index = 0; index < biomePalette.size(); index++) {
            try {
                if (biomeRegistry != null) {
                    resolvedBiomes[index] = biomeRegistry.get(
                            ResourceLocation.parse(biomePalette.get(index)));
                }
            } catch (RuntimeException ignored) {
            }
        }
        IntFunction<Biome> biomeLookup = index ->
                index >= 0 && index < resolvedBiomes.length ? resolvedBiomes[index] : null;

        MapTextureManager colorManager = MapTextureManager.getInstance();
        Map<String, Integer> blockColors = new HashMap<>();
        Map<String, BlockTintPolicy> tintPolicies = new HashMap<>();
        for (String blockId : blockPalette) {
            blockColors.put(blockId,
                    colorManager.resolveBlockColor(blockId, MapConfig.blockColourMode));
            if (MapConfig.blockColourMode == 0) {
                tintPolicies.put(blockId, colorManager.resolveTintPolicy(blockId));
            }
        }
        Set<String> tintDisabledBlocks = Set.copyOf(MapConfig.blockColorOverrides.keySet());
        int stride = key.stride;
        int colourMode = MapConfig.blockColourMode;
        boolean showFlowers = MapConfig.displayFlowers;
        int terrainSlopes = MapConfig.terrainSlopes;
        int profile = MapConfig.mapColorProfile;

        return CompletableFuture.supplyAsync(() -> {
            int[] styled = SurfaceColorizer.colorize(
                    pixels, tints, biomePalette, blockPalette, biomeLookup,
                    blockColors, tintPolicies, tintDisabledBlocks,
                    colourMode, showFlowers, terrainSlopes, profile);
            return new MapTextureBuildWorker.PreparedSingle(
                    downsamplePixels(styled, stride, false), revision);
        }, MapWorkScheduler.cpuExecutor(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 48, () -> true));
    }

    private CompletableFuture<MapTextureBuildWorker.PreparedSingle> buildLayer(
            TextureInfo info, long revision) {
        Key key = info.key;
        CaveRegion region = CaveMapManager.getInstance().getRegion(key.rx, key.rz, false);
        if (region == null || !region.isLoaded()) return null;
        int[] source = region.snapshotPixels();
        return CompletableFuture.supplyAsync(() ->
                new MapTextureBuildWorker.PreparedSingle(
                        downsamplePixels(source, key.stride, true), revision), MapWorkScheduler.cpuExecutor(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 48, () -> true));
    }

    private CompletableFuture<MapTextureBuildWorker.PreparedSingle> buildFull(
            TextureInfo info, long revision) {
        Key key = info.key;
        FullCaveMapManager.FullRegion region = FullCaveMapManager.getInstance()
                .getRegion(key.rx, key.rz, false);
        if (region == null || !region.isLoaded()) return null;
        int[] source = region.snapshotPixels();
        return CompletableFuture.supplyAsync(() ->
                new MapTextureBuildWorker.PreparedSingle(
                        downsamplePixels(source, key.stride, true), revision), MapWorkScheduler.cpuExecutor(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 48, () -> true));
    }

    private static int[] downsamplePixels(int[] source, int stride,
            boolean preserveThinFeatures) {
        int outputSize = 512 / stride;
        int[] colors = new int[outputSize * outputSize];
        for (int z = 0; z < outputSize; z++) {
            int sourceZ = z * stride;
            for (int x = 0; x < outputSize; x++) {
                int sourceX = x * stride;
                colors[z * outputSize + x] = representativeVisibleBlock(
                        source, sourceX, sourceZ, stride, preserveThinFeatures);
            }
        }
        return colors;
    }

    /**
     * Chooses an existing high-salience texel instead of manufacturing a muddy
     * average color. This keeps rivers, cave lines and biome edges readable when
     * a large saved region is represented by a small overview texture.
     */
    private static int representativeVisibleBlock(int[] source, int startX,
            int startZ, int stride, boolean preserveThinFeatures) {
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int count = 0;
        int maxAlpha = 0;
        int salient = 0;
        int salientScore = Integer.MIN_VALUE;
        for (int dz = 0; dz < stride; dz++) {
            int row = (startZ + dz) * 512 + startX;
            for (int dx = 0; dx < stride; dx++) {
                int value = source[row + dx];
                int alpha = (value >>> 24) & 0xFF;
                if (alpha == 0 || value == 0) continue;
                int r = value & 0xFF;
                int g = (value >>> 8) & 0xFF;
                int b = (value >>> 16) & 0xFF;
                red += r;
                green += g;
                blue += b;
                maxAlpha = Math.max(maxAlpha, alpha);
                count++;
                if (preserveThinFeatures) {
                    int chroma = Math.max(r, Math.max(g, b))
                            - Math.min(r, Math.min(g, b));
                    int score = chroma * 8 + r * 3 + g * 6 + b;
                    if (score > salientScore) {
                        salientScore = score;
                        salient = value;
                    }
                }
            }
        }
        if (count == 0) return 0;
        int r = (int) (red / count);
        int g = (int) (green / count);
        int b = (int) (blue / count);
        if (preserveThinFeatures && salient != 0) {
            // Blend a quarter of the strongest cave/water/emissive texel into the
            // area mean. All samples still contribute, while one-pixel structures
            // remain visible at multi-region zoom levels.
            r = (r * 3 + (salient & 0xFF)) / 4;
            g = (g * 3 + ((salient >>> 8) & 0xFF)) / 4;
            b = (b * 3 + ((salient >>> 16) & 0xFF)) / 4;
        }
        return (Math.max(224, maxAlpha) << 24) | (b << 16) | (g << 8) | r;
    }

    private void apply(TextureInfo info, MapTextureBuildWorker.PreparedSingle prepared) {
        NativeImage image = info.texture.getPixels();
        if (image == null) return;
        int size = 512 / info.key.stride;
        for (int z = 0; z < size; z++) {
            int row = z * size;
            for (int x = 0; x < size; x++) {
                image.setPixelRGBA(x, z, prepared.styled()[row + x]);
            }
        }
        long uploadStart = System.nanoTime();
        info.texture.upload();
        long uploadNanos = System.nanoTime() - uploadStart;
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.LEGACY, uploadNanos);
        configureOverviewSampling(info.texture);
        info.initialized = true;
        info.uploadedRevision = prepared.revision();
        int bytes = size * size * Integer.BYTES;
        String residentKey = overviewResidencyKey(info.key);
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.SURFACE_LEGACY, bytes,
                () -> evictOverviewForBudget(info.key));
        MapResidencyManager.getInstance().enforceBudget(
                residentKey, MapRequestLane.BACKGROUND);
    }

    /** Parent pixels are already density-reduced representative samples. */
    private static void configureOverviewSampling(DynamicTexture texture) {
        if (!RenderSystem.isOnRenderThreadOrInit()) {
            RenderSystem.recordRenderCall(() -> configureOverviewSampling(texture));
            return;
        }
        GlStateManager._bindTexture(texture.getId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
    }

    public void beginRenderBatch() {
        synchronized (textures) {
            renderBatchDepth++;
        }
    }

    public void endRenderBatch() {
        List<TextureInfo> close = null;
        synchronized (textures) {
            if (renderBatchDepth > 0) renderBatchDepth--;
            if (renderBatchDepth == 0 && !deferredCloses.isEmpty()) {
                close = new ArrayList<>(deferredCloses);
                deferredCloses.clear();
            }
        }
        if (close != null) for (TextureInfo info : close) info.close();
    }

    public void invalidateStyle() {
        SurfaceLodTree.invalidatePersistentCache();
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        regionLodService.invalidate(stamp);
        styleRefreshUntilMs = System.currentTimeMillis() + STYLE_REFRESH_WINDOW_MS;
        lastUploadMs = 0L;
        streamCursor = 0;
        streamRestartMs = 0L;
        String dimension = currentDimension();
        synchronized (textures) {
            synchronized (dirty) {
                LinkedHashSet<Key> ordered = new LinkedHashSet<>();
                // Put the current viewport first. Existing textures stay visible
                // while these replacement revisions are built and atomically uploaded.
                if (dimension.equals(streamDimension) && streamPlan.length > 0) {
                    for (long packed : streamPlan) {
                        int rx = (int) (packed >> 32);
                        int rz = (int) packed;
                        Key key = new Key(dimension, streamMode, streamLayerY,
                                rx, rz, streamStride);
                        if (textures.containsKey(key)) ordered.add(key);
                    }
                }
                ordered.addAll(dirty);
                for (Key key : textures.keySet()) {
                    if (!key.dimension.equals(dimension)) continue;
                    revisions.merge(key, 1L, Long::sum);
                    ordered.add(key);
                }
                dirty.clear();
                dirty.addAll(ordered);
            }
        }
    }

    public void clearCache() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::clearCache);
            return;
        }
        List<TextureInfo> close = new ArrayList<>();
        synchronized (textures) {
            close.addAll(textures.values());
            close.addAll(deferredCloses);
            textures.clear();
            deferredCloses.clear();
            renderBatchDepth = 0;
        }
        for (TextureInfo info : close) info.close();
        synchronized (dirty) {
            dirty.clear();
            revisions.clear();
        }
        surfaceLodTree.clear();
        regionLodService.clear();
        streamDimension = "";
        streamMode = Integer.MIN_VALUE;
        streamLayerY = Integer.MIN_VALUE;
        streamStride = Integer.MIN_VALUE;
        streamPlan = new long[0];
        streamCursor = 0;
        streamRestartMs = 0L;
    }

    private boolean evictOverviewForBudget(Key key) {
        if (key == null || renderBatchDepth > 0) return false;
        TextureInfo retired;
        synchronized (textures) {
            retired = textures.get(key);
            if (retired == null || retired.pending != null
                    || retired.ready != null || !retired.initialized) return false;
            textures.remove(key);
        }
        retired.close();
        return true;
    }

    private static String overviewResidencyKey(Key key) {
        return "overview:" + key;
    }

    private void trim() {
        List<TextureInfo> retired = new ArrayList<>();
        while (textures.size() > MAX_TEXTURES) {
            var iterator = textures.entrySet().iterator();
            if (!iterator.hasNext()) break;
            retired.add(iterator.next().getValue());
            iterator.remove();
        }
        for (TextureInfo info : retired) {
            if (renderBatchDepth > 0) deferredCloses.add(info);
            else info.close();
        }
    }

    private int priority(int rx, int rz) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 1;
        int prx = player.blockPosition().getX() >> 9;
        int prz = player.blockPosition().getZ() >> 9;
        return Math.max(1, 20_000 - (Math.abs(rx - prx) + Math.abs(rz - prz)) * 100);
    }

    private static String currentDimension() {
        return MapManager.getInstance().getDimensionCacheKey();
    }

    private static boolean isCurrentDimension(Key key) {
        return key.dimension.equals(currentDimension());
    }

    private static String texturePath(String dimension) {
        return dimension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    private record Key(String dimension, int mode, int layerY,
            int rx, int rz, int stride) {
    }

    private static final class TextureInfo {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final Key key;
        private long generation;
        private CompletableFuture<MapTextureBuildWorker.PreparedSingle> pending;
        private MapTextureBuildWorker.PreparedSingle ready;
        private long uploadedRevision = Long.MIN_VALUE;
        private boolean initialized;

        private TextureInfo(DynamicTexture texture, ResourceLocation location,
                Key key, long generation) {
            this.texture = texture;
            this.location = location;
            this.key = key;
            this.generation = generation;
        }

        private void close() {
            if (pending != null) pending.cancel(false);
            ready = null;
            MapResidencyManager.getInstance().remove(overviewResidencyKey(key));
            Minecraft.getInstance().getTextureManager().release(location);
        }
    }
}
