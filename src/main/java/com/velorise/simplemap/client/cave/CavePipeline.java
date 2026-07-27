package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.CaveMode;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapVisualClassifier;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapRequestLane;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.io.File;

/** Entry point for the rewritten cave subsystem. */
public final class CavePipeline {
    private static final CavePipeline INSTANCE = new CavePipeline();
    /* Periodic fallback only. Known block/fluid edits already use explicit column
     * rechecks, so continuously rescanning 80 full-height columns per second steals
     * frame time without improving a static world. */
    private static final int REVALIDATION_BATCH_COLUMNS = 2;
    private static final int REVALIDATION_INTERVAL_TICKS = 20;
    private static final int[][] REVALIDATION_CHUNK_OFFSETS = {
            { 0, 0 }, { 1, 0 }, { 0, 1 }, { -1, 0 }, { 0, -1 },
            { 1, 1 }, { -1, 1 }, { -1, -1 }, { 1, -1 }
    };

    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final CaveTileScanner scanner = new CaveTileScanner();
    private final CaveChunkReadinessTracker readiness = new CaveChunkReadinessTracker();
    /** Multi-run archive is retained for compatibility/background analysis only. */
    private final CaveTileScheduler scheduler = new CaveTileScheduler(repository, scanner);
    /** Foreground renderer source: one dense transactionally published tile/mode. */
    private final CaveDisplayScheduler displayScheduler =
            new CaveDisplayScheduler(repository, readiness);
    private final CaveWorldSaveReader worldSaveReader = CaveWorldSaveReader.getInstance();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();

    private static final int MAX_VISIBLE_SOFT_REFRESH_TILES = 4096;
    private static final int MAX_TRANSITION_STALE_TILES = 4096;
    private static final long VIEWPORT_REFRESH_MEMORY_NANOS = 2_000_000_000L;

    private long lastRevalidationTick = Long.MIN_VALUE;
    private int revalidationStep;
    private int lastViewportMinChunkX = Integer.MIN_VALUE;
    private int lastViewportMaxChunkX = Integer.MIN_VALUE;
    private int lastViewportMinChunkZ = Integer.MIN_VALUE;
    private int lastViewportMaxChunkZ = Integer.MIN_VALUE;
    private CaveView lastViewportView;
    private int lastViewportLayerY = Integer.MIN_VALUE;
    private long lastViewportNanos;
    private static final int LAYER_WARMUP_INITIAL_RADIUS = 2;
    private static final int LAYER_WARMUP_RADIUS_STEP = 2;
    private static final int LAYER_WARMUP_MAX_RADIUS = 128;
    private LayerWarmupState layerWarmup;

    private CavePipeline() {
    }

    public static CavePipeline getInstance() {
        return INSTANCE;
    }

    public CaveTileRepository repository() {
        return repository;
    }

    public CaveTelemetry.Snapshot telemetry() {
        return telemetry.snapshot();
    }

    public long generation() {
        return repository.generation();
    }

    public boolean isGenerationCurrent(long generation) {
        return repository.isGenerationCurrent(generation);
    }

    public void setCacheDirectory(File directory) {
        repository.setDirectory(directory);
        scheduler.reset();
        displayScheduler.reset();
        readiness.reset();
        worldSaveReader.reset();
        worldSaveReader.clearSourceCache();
        CaveStateClassifier.getInstance().clear();
        MapVisualClassifier.getInstance().clear();
        resetRevalidation();
        layerWarmup = null;
        UnifiedCaveTextureManager.getInstance().clear();
    }

    public void resetScanState() {
        scheduler.reset();
        displayScheduler.reset();
        // Mode/Top-Y changes use this path too. Preserve world/player readiness so a
        // normal layer switch is not misdetected as another teleport on the next tick.
        worldSaveReader.reset();
        resetRevalidation();
        layerWarmup = null;
    }

    /**
     * Runs before viewport/scanner work on the client tick. Teleports and world
     * replacements cancel only unpublished transactions; cached cave pages remain
     * visible while the new 3x3 neighbourhood reaches a stable FULL/light state.
     */
    public void tickClientState(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            readiness.reset();
            return;
        }
        if (!readiness.observePlayer(minecraft)) return;
        scheduler.reset();
        displayScheduler.cancelInFlight();
        layerWarmup = null;

        // Freeze the old automatic Top-Y before any scanner/render caller can
        // evaluate the teleported player Y and fast-switch the active projection.
        CaveMode.holdAutomaticLayer(minecraft, 5);
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int radius = Math.max(2, minecraft.options.renderDistance().get() + 2);
        // A tile incorrectly committed by an older alpha must not remain permanently
        // "fresh" after teleport. Keep its pixels as fallback, but force every known
        // cave projection in the new neighbourhood through the stable live path when
        // that projection is next requested. This also covers the new Layered Top-Y
        // selected after the settle window ends.
        repository.markDisplayRangeStaleAllLayers(
                centerChunkX - radius, centerChunkX + radius,
                centerChunkZ - radius, centerChunkZ + radius,
                MAX_TRANSITION_STALE_TILES);
    }

    /** Foreground gameplay scan: complete nearby chunk tiles centre-first. */
    public void scanAroundPlayer(Minecraft minecraft, int blockRadius) {
        if (!usable(minecraft)) return;
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2, minecraft.options.renderDistance().get() + 2);
        int requestedRadius = Math.max(1, (blockRadius + 15) >> 4);
        int chunkRadius = Math.min(liveRadius, requestedRadius);
        CaveView view = CaveMode.isFullView(minecraft) ? CaveView.FULL : CaveView.LAYERED;
        int layerY = CaveMode.getLayerY(minecraft);
        displayScheduler.enqueueAround(minecraft.level, view, layerY,
                centerChunkX, centerChunkZ, chunkRadius, 1_000_000);

        long budget = MapPerformanceGovernor.getInstance().gameplayScanBudgetNanos(true);
        if (budget > 0L) {
            displayScheduler.process(minecraft.level, System.nanoTime() + budget);
        }
        updateTelemetry();
    }

    /** Quiet archive capture while the surface map is active. */
    public void scanBackgroundAroundPlayer(Minecraft minecraft, int blockRadius) {
        if (!usable(minecraft)) return;
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        if (!governor.allowBackgroundWork(minecraft)) return;
        long budget = governor.verticalArchiveBudgetNanos();
        if (budget <= 0L) return;
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2, minecraft.options.renderDistance().get() + 1);
        int requestedRadius = Math.max(1, (blockRadius + 15) >> 4);
        scheduler.enqueueAround(minecraft.level, centerChunkX, centerChunkZ,
                Math.min(liveRadius, requestedRadius), 250_000,
                CaveTileScheduler.Lane.BACKGROUND);
        scheduler.process(minecraft.level, System.nanoTime() + budget);
        updateTelemetry();
    }

    /**
     * Full-screen scan uses live LevelChunks near the player and the integrated
     * server's read-only .mca storage for generated chunks outside render distance.
     */
    public void scanVisibleArea(Minecraft minecraft, double minX, double maxX,
            double minZ, double maxZ, float scale) {
        scanVisibleArea(minecraft, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5,
                MapRequestLane.FULLSCREEN);
    }

    public void scanVisibleArea(Minecraft minecraft, double minX, double maxX,
            double minZ, double maxZ, float scale, MapRequestLane lane) {
        scanVisibleArea(minecraft, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void scanVisibleArea(Minecraft minecraft, double minX, double maxX,
            double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        if (!usable(minecraft)) return;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int viewportMinChunkX = ((int) Math.floor(Math.min(minX, maxX))) >> 4;
        int viewportMaxChunkX = ((int) Math.floor(Math.max(minX, maxX))) >> 4;
        int viewportMinChunkZ = ((int) Math.floor(Math.min(minZ, maxZ))) >> 4;
        int viewportMaxChunkZ = ((int) Math.floor(Math.max(minZ, maxZ))) >> 4;
        double viewportCenterX = Math.max(viewportMinChunkX,
                Math.min(viewportMaxChunkX, focusX / 16.0));
        double viewportCenterZ = Math.max(viewportMinChunkZ,
                Math.min(viewportMaxChunkZ, focusZ / 16.0));

        CaveView view = CaveMode.isFullView(minecraft) ? CaveView.FULL : CaveView.LAYERED;
        int layerY = CaveMode.getLayerY(minecraft);
        lastViewportMinChunkX = viewportMinChunkX;
        lastViewportMaxChunkX = viewportMaxChunkX;
        lastViewportMinChunkZ = viewportMinChunkZ;
        lastViewportMaxChunkZ = viewportMaxChunkZ;
        lastViewportView = view;
        lastViewportLayerY = DenseCaveTile.normalizeLayer(view, layerY);
        lastViewportNanos = System.nanoTime();

        int admittedMinChunkX = viewportMinChunkX;
        int admittedMaxChunkX = viewportMaxChunkX;
        int admittedMinChunkZ = viewportMinChunkZ;
        int admittedMaxChunkZ = viewportMaxChunkZ;
        LayerWarmupState warmup = layerWarmup;
        if (warmup != null && warmup.matches(view, layerY)) {
            admittedMinChunkX = Math.max(admittedMinChunkX,
                    warmup.centerChunkX - warmup.currentRadius);
            admittedMaxChunkX = Math.min(admittedMaxChunkX,
                    warmup.centerChunkX + warmup.currentRadius);
            admittedMinChunkZ = Math.max(admittedMinChunkZ,
                    warmup.centerChunkZ - warmup.currentRadius);
            admittedMaxChunkZ = Math.min(admittedMaxChunkZ,
                    warmup.centerChunkZ + warmup.currentRadius);
        }

        boolean branchOnly = CaveScreenSpacePolicy.branchOnly(scale, effectiveLane);
        // Far zoom is rendered from branch/root textures, but Xaero still admits
        // an occasional leaf when no cached branch exists. The policy limits this to
        // one coherent page per slow pass, allowing cold areas to build LOD without
        // reopening the previous exact-page flood.
        if (admittedMinChunkX <= admittedMaxChunkX
                && admittedMinChunkZ <= admittedMaxChunkZ) {
            worldSaveReader.requestVisible(minecraft, view, layerY,
                    admittedMinChunkX, admittedMaxChunkX,
                    admittedMinChunkZ, admittedMaxChunkZ,
                    viewportCenterX, viewportCenterZ, scale, effectiveLane);
        }

        int playerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int playerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2, minecraft.options.renderDistance().get() + 3);
        int minChunkX = Math.max(admittedMinChunkX - 1, playerChunkX - liveRadius);
        int maxChunkX = Math.min(admittedMaxChunkX + 1, playerChunkX + liveRadius);
        int minChunkZ = Math.max(admittedMinChunkZ - 1, playerChunkZ - liveRadius);
        int maxChunkZ = Math.min(admittedMaxChunkZ + 1, playerChunkZ + liveRadius);
        if (CaveScreenSpacePolicy.restrictLiveProjectionToFocusPage(scale, effectiveLane)) {
            // At the normal cave zoom-out floor one exact page is only 4x4 screen
            // pixels. Keep live projection to the single focus page; saved-data
            // admission advances separately and branch/root textures cover the view.
            int focusChunkX = ((int) Math.floor(focusX)) >> 4;
            int focusChunkZ = ((int) Math.floor(focusZ)) >> 4;
            int firstFocusChunkX = Math.floorDiv(focusChunkX, 4) * 4;
            int firstFocusChunkZ = Math.floorDiv(focusChunkZ, 4) * 4;
            minChunkX = Math.max(minChunkX, firstFocusChunkX);
            maxChunkX = Math.min(maxChunkX, firstFocusChunkX + 3);
            minChunkZ = Math.max(minChunkZ, firstFocusChunkZ);
            maxChunkZ = Math.min(maxChunkZ, firstFocusChunkZ + 3);
        }
        if (minChunkX <= maxChunkX && minChunkZ <= maxChunkZ) {
            displayScheduler.enqueueViewport(minecraft.level, view, layerY,
                    minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                    (minChunkX + maxChunkX) * 0.5, (minChunkZ + maxChunkZ) * 0.5,
                    effectiveLane.priorityBase(), effectiveLane);
            MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
            long budget = effectiveLane == MapRequestLane.MINIMAP
                    ? governor.gameplayScanBudgetNanos(true)
                    : governor.fullscreenCaveBudgetNanos(
                            scale, MapConfig.fastFullscreenLoading);
            if (budget > 0L) {
                displayScheduler.process(minecraft.level, System.nanoTime() + budget);
            }
        }
        updateTelemetry();
    }

    public void scanColumnNow(Level level, int blockX, int blockZ) {
        if (level == null || !level.hasChunk(blockX >> 4, blockZ >> 4)) return;
        CaveChunkTile tile = repository.getOrCreateLiveTile(blockX >> 4, blockZ >> 4);
        int index = CaveChunkTile.index(blockX & 15, blockZ & 15);
        CaveTileScanContext context = CaveTileScanContext.create(
                level, blockX >> 4, blockZ >> 4);
        long started = System.nanoTime();
        CaveColumnData data = scanner.scanColumn(level, blockX, blockZ, context);
        if (data != null) {
            boolean revalidation = tile.isColumnScanned(blockX & 15, blockZ & 15);
            boolean changed = repository.commitColumn(tile, index, data);
            if (changed) {
                markCurrentDisplayStale(blockX >> 4, blockZ >> 4);
                enqueueCurrentDisplayPatch(blockX >> 4, blockZ >> 4,
                        blockX & 15, blockZ & 15, 1_600_000);
            }
            telemetry.recordColumnScan(System.nanoTime() - started, revalidation, changed);
        }
        updateTelemetry();
    }

    /** Compatibility entry point for direct callers outside MapMutationBus. */
    public void invalidateColumn(int blockX, int blockZ) {
        onColumnMutation(blockX, blockZ, 0);
    }

    /**
     * Event-driven mutation of one X/Z column. Archive data is invalidated at column
     * granularity; every known display layer stays visible but stale. The active
     * projection receives a dense one-column patch when a LIVE seed exists.
     */
    public void onColumnMutation(int blockX, int blockZ, int reasons) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        readiness.markChunkChanged(chunkX, chunkZ);
        repository.invalidateColumn(blockX, blockZ);
        repository.markDisplayRangeStaleAllLayers(
                chunkX, chunkX, chunkZ, chunkZ, 4096);
        scheduler.enqueue(chunkX, chunkZ, 1_500_000);
        enqueueCurrentDisplayPatch(chunkX, chunkZ,
                blockX & 15, blockZ & 15, 1_700_000);
    }

    /**
     * Chunk/light replacement invalidates the resident archive tile and schedules a
     * complete dense transaction. MapMutationBus already expands the 3x3 dependency
     * neighbourhood, so this method acts on exactly one chunk.
     */
    public void onChunkMutation(int chunkX, int chunkZ, int reasons) {
        readiness.markChunkChanged(chunkX, chunkZ);
        repository.invalidateLoadedTile(chunkX, chunkZ);
        repository.markDisplayRangeStaleAllLayers(
                chunkX, chunkX, chunkZ, chunkZ, 4096);
        scheduler.enqueue(chunkX, chunkZ, 1_650_000);
        enqueueCurrentDisplayReplacement(chunkX, chunkZ, 1_800_000);
    }

    /** Chunk unload revokes authority but deliberately preserves cached pixels. */
    public void onChunkUnavailable(int chunkX, int chunkZ, int reasons) {
        readiness.markChunkChanged(chunkX, chunkZ);
        repository.markDisplayRangeStaleAllLayers(
                chunkX, chunkX, chunkZ, chunkZ, 4096);
        displayScheduler.cancelChunk(chunkX, chunkZ);
    }

    /**
     * Non-destructive recheck for block/fluid updates. The previous column remains
     * renderable until the live replacement scan completes.
     */
    public void requestColumnRecheck(int blockX, int blockZ) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        markCurrentDisplayStale(chunkX, chunkZ);
        enqueueCurrentDisplayPatch(chunkX, chunkZ,
                blockX & 15, blockZ & 15, 1_400_000);
        if (repository.requestColumnRecheck(blockX, blockZ)) {
            scheduler.enqueueRevalidation(chunkX, chunkZ, 700_000);
        }
    }

    /**
     * Retargets Layered Cave inside the currently retained 16-block band.
     * Existing dense tiles, pages and LOD nodes stay visible. Only known/loaded
     * chunks are marked stale and replaced transactionally for the new exact Top-Y.
     */
    public void retargetLayer(Minecraft minecraft, int topY) {
        if (!usable(minecraft) || CaveMode.isFullView(minecraft)) return;
        int bandY = DenseCaveTile.normalizeLayer(CaveView.LAYERED, topY);
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int liveRadius = Math.max(2, minecraft.options.renderDistance().get() + 2);
        int maximumRadius = liveRadius;
        if (lastViewportView == CaveView.LAYERED
                && System.nanoTime() - lastViewportNanos <= VIEWPORT_REFRESH_MEMORY_NANOS) {
            maximumRadius = Math.max(maximumRadius, Math.max(
                    Math.max(Math.abs(lastViewportMinChunkX - centerChunkX),
                            Math.abs(lastViewportMaxChunkX - centerChunkX)),
                    Math.max(Math.abs(lastViewportMinChunkZ - centerChunkZ),
                            Math.abs(lastViewportMaxChunkZ - centerChunkZ))));
        }
        maximumRadius = Math.min(LAYER_WARMUP_MAX_RADIUS, maximumRadius);
        int initialRadius = Math.min(LAYER_WARMUP_INITIAL_RADIUS, maximumRadius);
        layerWarmup = new LayerWarmupState(CaveView.LAYERED, bandY, topY,
                centerChunkX, centerChunkZ, initialRadius, maximumRadius,
                minecraft.level.getGameTime() + 1L);

        markAndEnqueueWarmupRing(minecraft, layerWarmup, 0, initialRadius);
        updateTelemetry();
    }

    /** Advances retained Top-Y replacement from the player outward. */
    public void tickObservation(Minecraft minecraft, boolean allowWarmup) {
        if (!usable(minecraft)) return;
        LayerWarmupState state = layerWarmup;
        if (state == null) return;
        if (!state.matches(CaveView.LAYERED, CaveMode.getLayerY(minecraft))) {
            layerWarmup = null;
            return;
        }
        if (!allowWarmup || minecraft.level.getGameTime() < state.nextTick) return;
        if (state.currentRadius >= state.maximumRadius) {
            layerWarmup = null;
            return;
        }
        int previous = state.currentRadius;
        int next = Math.min(state.maximumRadius,
                previous + LAYER_WARMUP_RADIUS_STEP);
        state.currentRadius = next;
        state.nextTick = minecraft.level.getGameTime() + 2L;
        markAndEnqueueWarmupRing(minecraft, state, previous + 1, next);
        if (next >= state.maximumRadius) layerWarmup = null;
    }

    /**
     * Warms only retained disk/cache data for an adjacent band. It deliberately does
     * not change CaveMapManager's active layer and does not start world-save reads.
     */
    public void warmLayerBand(int topY, double minX, double maxX,
            double minZ, double maxZ, float scale) {
        int minRegionX = ((int) Math.floor(Math.min(minX, maxX))) >> 9;
        int maxRegionX = ((int) Math.floor(Math.max(minX, maxX))) >> 9;
        int minRegionZ = ((int) Math.floor(Math.min(minZ, maxZ))) >> 9;
        int maxRegionZ = ((int) Math.floor(Math.max(minZ, maxZ))) >> 9;
        int centerRegionX = (minRegionX + maxRegionX) >> 1;
        int centerRegionZ = (minRegionZ + maxRegionZ) >> 1;
        int admitted = 0;
        int maximumRing = Math.max(
                Math.max(Math.abs(centerRegionX - minRegionX),
                        Math.abs(maxRegionX - centerRegionX)),
                Math.max(Math.abs(centerRegionZ - minRegionZ),
                        Math.abs(maxRegionZ - centerRegionZ)));
        for (int ring = 0; ring <= maximumRing && admitted < 4; ring++) {
            for (int dz = -ring; dz <= ring && admitted < 4; dz++) {
                for (int dx = -ring; dx <= ring && admitted < 4; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int regionX = centerRegionX + dx;
                    int regionZ = centerRegionZ + dz;
                    if (regionX < minRegionX || regionX > maxRegionX
                            || regionZ < minRegionZ || regionZ > maxRegionZ) continue;
                    repository.requestRegionLoad(regionX, regionZ);
                    admitted++;
                }
            }
        }
        UnifiedCaveTextureManager.getInstance().requestVisiblePages(
                CaveView.LAYERED, topY, minX, maxX, minZ, maxZ, scale,
                MapRequestLane.BACKGROUND);
    }

    private void markAndEnqueueWarmupRing(Minecraft minecraft,
            LayerWarmupState state, int minimumRing, int maximumRing) {
        for (int ring = Math.max(0, minimumRing); ring <= maximumRing; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = state.centerChunkX + dx;
                    int chunkZ = state.centerChunkZ + dz;
                    repository.markDisplayTileStale(CaveView.LAYERED,
                            state.projectionTopY, chunkX, chunkZ);
                    if (!minecraft.level.hasChunk(chunkX, chunkZ)) continue;
                    int priority = 1_550_000 - (dx * dx + dz * dz) * 100;
                    displayScheduler.enqueueReplacement(chunkX, chunkZ,
                            CaveView.LAYERED, state.projectionTopY, priority);
                }
            }
        }
    }

    public void requestRefresh(Minecraft minecraft, int blockRadius) {
        if (!usable(minecraft)) return;
        int centerChunkX = ((int) Math.floor(minecraft.player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(minecraft.player.getZ())) >> 4;
        int radius = Math.min(Math.max(1, (blockRadius + 15) >> 4),
                minecraft.options.renderDistance().get() + 3);
        CaveView view = CaveMode.isFullView(minecraft)
                ? CaveView.FULL : CaveView.LAYERED;
        int layerY = CaveMode.getLayerY(minecraft);
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        if (lastViewportView == view && lastViewportLayerY == normalizedLayer
                && System.nanoTime() - lastViewportNanos <= VIEWPORT_REFRESH_MEMORY_NANOS) {
            repository.markDisplayRangeStale(view, layerY,
                    lastViewportMinChunkX, lastViewportMaxChunkX,
                    lastViewportMinChunkZ, lastViewportMaxChunkZ,
                    MAX_VISIBLE_SOFT_REFRESH_TILES);
        }
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                if (!minecraft.level.hasChunk(chunkX, chunkZ)) continue;
                repository.invalidateTile(chunkX, chunkZ);
                repository.markDisplayTileStale(view, layerY, chunkX, chunkZ);
                scheduler.enqueue(chunkX, chunkZ,
                        1_500_000 - (dx * dx + dz * dz) * 100);
                enqueueCurrentDisplayReplacement(chunkX, chunkZ,
                        1_600_000 - (dx * dx + dz * dz) * 100);
            }
        }
        long deadline = System.nanoTime() + 20_000_000L;
        displayScheduler.process(minecraft.level, deadline);
        if (System.nanoTime() < deadline) scheduler.process(minecraft.level, deadline);
        updateTelemetry();
    }

    public void requestRegionLoad(int regionX, int regionZ) {
        repository.requestRegionLoad(regionX, regionZ);
    }

    public int getColor(CaveView view, int layerY, int blockX, int blockZ) {
        return repository.getColor(view, layerY, Minecraft.getInstance().level, blockX, blockZ);
    }

    public int getHeight(CaveView view, int layerY, int blockX, int blockZ) {
        return repository.getHeight(view, layerY, Minecraft.getInstance().level, blockX, blockZ);
    }

    public CaveColumnData.Candidate getCandidate(int blockX, int blockZ,
            int maximumY, int minimumY) {
        return repository.getCandidate(blockX, blockZ, maximumY, minimumY);
    }

    public CaveColumnData.Candidate getFullCandidate(int blockX, int blockZ) {
        return repository.getFullCandidate(blockX, blockZ);
    }

    public CaveTileRepository.ResolvedRegion resolveRegion(CaveView view,
            int layerY, int regionX, int regionZ) {
        return repository.resolveRegion(view, layerY, Minecraft.getInstance().level,
                regionX, regionZ);
    }

    public boolean hasRegionData(int regionX, int regionZ) {
        return repository.hasRegionData(regionX, regionZ);
    }

    public boolean isRegionLoaded(int regionX, int regionZ) {
        return repository.isRegionLoaded(regionX, regionZ);
    }

    public long getRegionRevision(int regionX, int regionZ) {
        return repository.getRegionRevision(regionX, regionZ);
    }

    public void tickSave() {
        repository.tickSave();
        updateTelemetry();
    }

    public void clearRuntime(boolean preserveDisk) {
        scheduler.reset();
        displayScheduler.reset();
        readiness.reset();
        worldSaveReader.reset();
        worldSaveReader.clearSourceCache();
        layerWarmup = null;
        repository.clearRuntime(preserveDisk);
        CaveStateClassifier.getInstance().clear();
        MapVisualClassifier.getInstance().clear();
        resetRevalidation();
        UnifiedCaveTextureManager.getInstance().clear();
    }

    public void flushAndClear() {
        scheduler.reset();
        displayScheduler.reset();
        readiness.reset();
        worldSaveReader.reset();
        layerWarmup = null;
        repository.flushAndClear();
        CaveStateClassifier.getInstance().clear();
        MapVisualClassifier.getInstance().clear();
        resetRevalidation();
        UnifiedCaveTextureManager.getInstance().clear();
    }

    public int activeLayer() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0 : CaveMode.getLayerY(minecraft);
    }

    private void scheduleNearPlayerRevalidation(Minecraft minecraft,
            int centerChunkX, int centerChunkZ, int liveRadius) {
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        if (!governor.allowBackgroundWork(minecraft)) return;
        long gameTime = minecraft.level.getGameTime();
        if (lastRevalidationTick != Long.MIN_VALUE
                && gameTime - lastRevalidationTick < REVALIDATION_INTERVAL_TICKS) return;
        lastRevalidationTick = gameTime;

        int batchesPerTile = CaveChunkTile.COLUMN_COUNT / REVALIDATION_BATCH_COLUMNS;
        int tileStep = Math.floorDiv(revalidationStep, batchesPerTile)
                % REVALIDATION_CHUNK_OFFSETS.length;
        int batch = Math.floorMod(revalidationStep, batchesPerTile);
        revalidationStep = (revalidationStep + 1)
                % (REVALIDATION_CHUNK_OFFSETS.length * batchesPerTile);

        int[] offset = REVALIDATION_CHUNK_OFFSETS[tileStep];
        if (Math.abs(offset[0]) > liveRadius || Math.abs(offset[1]) > liveRadius) return;
        int chunkX = centerChunkX + offset[0];
        int chunkZ = centerChunkZ + offset[1];
        if (!minecraft.level.hasChunk(chunkX, chunkZ)) return;
        CaveChunkTile tile = repository.getLoadedTile(chunkX, chunkZ);
        if (tile == null || !tile.isComplete() || tile.recheckColumnCount() >= 32) return;

        boolean queued = false;
        int start = batch * REVALIDATION_BATCH_COLUMNS;
        for (int i = 0; i < REVALIDATION_BATCH_COLUMNS; i++) {
            // A coprime permutation spreads each batch across the tile rather than
            // repeatedly scanning one visible row from left to right.
            int column = ((start + i) * 73) & 255;
            int blockX = (chunkX << 4) + (column & 15);
            int blockZ = (chunkZ << 4) + (column >>> 4);
            queued |= repository.requestColumnRecheck(blockX, blockZ);
        }
        if (queued) scheduler.enqueueRevalidation(chunkX, chunkZ, 650_000);
    }

    private void markCurrentDisplayStale(int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!usable(minecraft)) return;
        CaveView view = CaveMode.isFullView(minecraft)
                ? CaveView.FULL : CaveView.LAYERED;
        repository.markDisplayTileStale(view, CaveMode.getLayerY(minecraft),
                chunkX, chunkZ);
    }

    private void enqueueCurrentDisplayReplacement(int chunkX, int chunkZ,
            int priority) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!usable(minecraft) || !minecraft.level.hasChunk(chunkX, chunkZ)) return;
        CaveView view = CaveMode.isFullView(minecraft) ? CaveView.FULL : CaveView.LAYERED;
        displayScheduler.enqueueReplacement(chunkX, chunkZ, view,
                CaveMode.getLayerY(minecraft), priority);
    }

    private void enqueueCurrentDisplayPatch(int chunkX, int chunkZ,
            int localX, int localZ, int priority) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!usable(minecraft) || !minecraft.level.hasChunk(chunkX, chunkZ)) return;
        CaveView view = CaveMode.isFullView(minecraft) ? CaveView.FULL : CaveView.LAYERED;
        displayScheduler.enqueuePatch(chunkX, chunkZ, view,
                CaveMode.getLayerY(minecraft), localX, localZ, priority);
    }

    private void updateTelemetry() {
        UnifiedCaveTextureManager textureManager = UnifiedCaveTextureManager.getInstance();
        telemetry.updateQueues(scheduler.queuedTaskCount()
                        + displayScheduler.queuedTaskCount()
                        + worldSaveReader.queuedCount()
                        + worldSaveReader.inFlightCount(),
                textureManager.requestCount(), textureManager.pendingBuildCount(),
                repository.loadedTileCount());
        telemetry.logIfEnabled();
    }

    private void resetRevalidation() {
        lastRevalidationTick = Long.MIN_VALUE;
        revalidationStep = 0;
        lastViewportMinChunkX = lastViewportMaxChunkX = Integer.MIN_VALUE;
        lastViewportMinChunkZ = lastViewportMaxChunkZ = Integer.MIN_VALUE;
        lastViewportView = null;
        lastViewportLayerY = Integer.MIN_VALUE;
        lastViewportNanos = 0L;
    }

    private static final class LayerWarmupState {
        private final CaveView view;
        private final int bandY;
        private final int projectionTopY;
        private final int centerChunkX;
        private final int centerChunkZ;
        private int currentRadius;
        private final int maximumRadius;
        private long nextTick;

        private LayerWarmupState(CaveView view, int bandY, int projectionTopY,
                int centerChunkX, int centerChunkZ, int currentRadius,
                int maximumRadius, long nextTick) {
            this.view = view;
            this.bandY = bandY;
            this.projectionTopY = projectionTopY;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
            this.currentRadius = currentRadius;
            this.maximumRadius = maximumRadius;
            this.nextTick = nextTick;
        }

        private boolean matches(CaveView requestedView, int requestedTopY) {
            return view == requestedView
                    && bandY == DenseCaveTile.normalizeLayer(requestedView, requestedTopY)
                    && projectionTopY == requestedTopY;
        }
    }

    private static boolean usable(Minecraft minecraft) {
        return minecraft != null && minecraft.level != null && minecraft.player != null
                && MapManager.getInstance().isViewingLiveDimension();
    }
}
