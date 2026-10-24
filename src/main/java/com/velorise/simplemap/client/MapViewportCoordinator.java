package com.velorise.simplemap.client;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.client.MapManager.MapConfig;
import com.velorise.simplemap.client.MapDebugRecorder.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapWorkScheduler.MapProcessor;
import com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy.MapGpuBudgetController;
import com.velorise.simplemap.client.cave.CaveTextureManager;
import com.velorise.simplemap.client.cave.*;

import com.velorise.simplemap.client.gui.MapScreen;
import com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand.CaveView;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stores viewport intent during rendering and performs scanning/cache requests
 * from client tick. Rendering remains cache-only, including static dimension
 * views and heavily zoomed layered-cave views.
 */
public final class MapViewportCoordinator {
    private static final MapViewportCoordinator INSTANCE = new MapViewportCoordinator();

    private volatile Request fullscreenRequest;
    private volatile Request minimapRequest;
    private volatile long fullscreenGeneration = 1L;
    private long lastFullscreenRun;
    /** PASS160: the Cave source cursor is a persistent writer pulse, independent
     * from render/publication interaction gating. */
    private long lastFullscreenCaveSourceRun;
    private long lastMinimapRun;
    private long lastLayerUploadRun;
    private long lastAdjacentWarmupRun;
    /**
     * Surface publication must not depend on opening MapScreen or on the current
     * minimap zoom. The scanner writes CPU metadata continuously; this bounded
     * hot-set publisher converts those mutations into exact 64x64 leaves and LOD
     * parents while normal gameplay continues.
     */
    private long lastBackgroundSurfaceRun;
    /** Player render-distance source maintenance is independent from MapScreen. */
    private long lastLoadedSurfaceHaloRun;
    private long lastHiddenCaveHaloRun;
    private long lastSuspendedTeleportEpoch = Long.MIN_VALUE;
    // Background publication repairs/widens the retained surface cache. It must
    // not compete one-for-one with the visible minimap request every client tick.
    private static final long BACKGROUND_SURFACE_INTERVAL_NANOS = 250_000_000L;
    /** Cave is the visible projection, so Surface receives a slower maintenance
     * cadence rather than being paused entirely. */
    private static final long CAVE_BACKGROUND_SURFACE_INTERVAL_NANOS = 500_000_000L;
    private static final int BACKGROUND_SURFACE_MIN_RADIUS = 96;
    private static final int BACKGROUND_SURFACE_MAX_RADIUS = 256;
    private static final int CAVE_BACKGROUND_SURFACE_MAX_RADIUS = 128;
    private static final long LOADED_SURFACE_HALO_INTERVAL_NANOS = 50_000_000L;
    private static final long HIDDEN_CAVE_HALO_INTERVAL_NANOS = 150_000_000L;
    private static final long CAVE_ADJACENT_WARMUP_INTERVAL_NANOS = 2_000_000_000L;
    private static final long CAVE_FULLSCREEN_SOURCE_IDLE_INTERVAL_NANOS = 40_000_000L;
    private static final long CAVE_FULLSCREEN_SOURCE_INTERACT_INTERVAL_NANOS = 75_000_000L;
    private static final long LOADED_SURFACE_HALO_GAME_BUDGET_NANOS = 6_000_000L;
    private static final long LOADED_SURFACE_HALO_FULLSCREEN_BUDGET_NANOS = 5_000_000L;
    private LayerStreamState layerStream = new LayerStreamState();

    private MapViewportCoordinator() {
    }

    private long submittedSurfaceRequests;

    public synchronized long getSubmittedSurfaceRequests() {
        return submittedSurfaceRequests;
    }

    public void drainPublicationFrame(long frameId) {
        MapPublicationCoordinator.getInstance().drainFrame(frameId);
    }

    private void submitSurfaceDemand(double minX, double maxX, double minZ, double maxZ,
            double focusX, double focusZ, float logicalScale,
            MapRequestLane lane, boolean trimFarFullscreen) {
        if (lane == null) return;
        if (trimFarFullscreen && lane == MapRequestLane.FULLSCREEN) {
            MapSurfaceDemandPolicy.Bounds trimmed = MapSurfaceDemandPolicy.trim(
                    minX, maxX, minZ, maxZ, logicalScale);
            minX = trimmed.minX();
            maxX = trimmed.maxX();
            minZ = trimmed.minZ();
            maxZ = trimmed.maxZ();
        }
        MapViewportDemandPolicy.Bounds admitted =
                MapViewportDemandPolicy.trimEdgeSlivers(
                        minX, maxX, minZ, maxZ, lane);
        minX = admitted.minX();
        maxX = admitted.maxX();
        minZ = admitted.minZ();
        maxZ = admitted.maxZ();
        MapOverviewTextureManager.getInstance().setPreferredSurfaceScale(logicalScale);
        MapTextureManager.getInstance().requestVisiblePages(
                minX, maxX, minZ, maxZ,
                focusX, focusZ,
                Math.max(0.01f, logicalScale), lane);
        synchronized (this) { submittedSurfaceRequests++; }
    }

    public static MapViewportCoordinator getInstance() {
        return INSTANCE;
    }

    public void submitFullscreen(double minX, double maxX, double minZ, double maxZ,
            float scale, boolean interacting) {
        submitFullscreen(minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, interacting);
    }

    /**
     * Fullscreen traversal is stable for both Surface and Cave. The mouse is only
     * an attention hint inside the current update slice and never resets the scan.
     */
    public void submitFullscreen(double minX, double maxX, double minZ, double maxZ,
            float scale, double focusX, double focusZ, boolean interacting) {
        long now = System.nanoTime();
        double clampedFocusX = clamp(focusX, minX, maxX);
        double clampedFocusZ = clamp(focusZ, minZ, maxZ);
        Request previousFullscreen = fullscreenRequest;
        boolean openingFullscreen = previousFullscreen == null;
        if (interacting && (previousFullscreen == null || !previousFullscreen.interacting)) {
            beginFullscreenInteraction();
            previousFullscreen = fullscreenRequest;
            openingFullscreen = previousFullscreen == null;
        }
        long planningKey = planningKey(minX, maxX, minZ, maxZ, scale);
        boolean planningChanged = previousFullscreen == null
                || previousFullscreen.planningKey != planningKey;
        if (previousFullscreen == null
                || (previousFullscreen.planningKey != planningKey
                        && !pageRectanglesOverlap(previousFullscreen,
                                minX, maxX, minZ, maxZ))) {
            // A continuous pan keeps most of the old viewport useful. Purging the
            // shared fullscreen executor epoch on every page-boundary crossing
            // cancelled overlapping Surface/Cave work before it could publish.
            // Subsystems perform ownership handoff for the overlap; only a disjoint
            // jump needs the coarse global purge.
            MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        }
        if (previousFullscreen == null) {
            fullscreenRequest = new Request(minX, maxX, minZ, maxZ, scale,
                    clampedFocusX, clampedFocusZ, interacting,
                    MapRequestLane.FULLSCREEN, now, planningKey);
        } else {
            previousFullscreen.update(minX, maxX, minZ, maxZ, scale,
                    clampedFocusX, clampedFocusZ, interacting, now, planningKey);
        }
        // Rendering can call this once per visual frame while the scheduler consumes
        // at 20-25 Hz. Count only a new planning generation instead of allocating
        // and recording identical demand 40-200 times per second.
        if (planningChanged) {
            MapPipelineTelemetry.getInstance().recordViewportRequest(
                    MapRequestLane.FULLSCREEN);
        }
        Request active = fullscreenRequest;
        if (active != null) active.generation = fullscreenGeneration;
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        governor.setFullscreenState(true, interacting);
        governor.setFocus(clampedFocusX, clampedFocusZ);
        if (openingFullscreen) {
            /*
             * Do not revoke the player-local writer when MapScreen opens. The engine
             * keeps its minimap loading set alive behind the fullscreen map; only
             * the visible renderer changes. Revoking MINIMAP ownership here caused
             * loaded chunks and completed cave pages to accumulate until the screen
             * closed, at which point they appeared in one sudden burst.
             */
            lastLoadedSurfaceHaloRun = 0L;
            lastHiddenCaveHaloRun = 0L;
        }
    }

    public void submitMinimap(double minX, double maxX, double minZ, double maxZ,
            float scale) {
        long now = System.nanoTime();
        double focusX = (minX + maxX) * 0.5;
        double focusZ = (minZ + maxZ) * 0.5;
        Request previousMinimap = minimapRequest;
        long planningKey = planningKey(minX, maxX, minZ, maxZ, scale);
        boolean planningChanged = previousMinimap == null
                || previousMinimap.planningKey != planningKey;
        if (previousMinimap == null
                || (planningChanged && !pageRectanglesOverlap(previousMinimap,
                        minX, maxX, minZ, maxZ))) {
            // Normal player movement crosses one 64x64 boundary at a time. Cancelling
            // the entire minimap epoch at every crossing discarded overlapping exact
            // builds faster than the 50 ms tick-side consumer could publish them.
            // Only a disjoint teleport/dimension jump needs the coarse purge.
            MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        }
        if (previousMinimap == null) {
            minimapRequest = new Request(minX, maxX, minZ, maxZ, scale,
                    focusX, focusZ, false, MapRequestLane.MINIMAP,
                    now, planningKey);
        } else {
            previousMinimap.update(minX, maxX, minZ, maxZ, scale,
                    focusX, focusZ, false, now, planningKey);
        }
        if (planningChanged) {
            MapPipelineTelemetry.getInstance().recordViewportRequest(
                    MapRequestLane.MINIMAP);
        }
        if (!MapPerformanceGovernor.getInstance().isFullscreenOpen()) {
            MapPerformanceGovernor.getInstance().setFocus(
                    (minX + maxX) * 0.5, (minZ + maxZ) * 0.5);
        }
    }

    /** Immediately revokes old fullscreen ownership at drag/zoom start. */
    public void beginFullscreenInteraction() {
        fullscreenGeneration++;
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        Request request = fullscreenRequest;
        if (request != null) request.interacting = true;
        MapPerformanceGovernor.getInstance().setFullscreenState(true, true);
    }

    /**
     * Cancels stale/background ownership at travel start while retaining the current
     * minimap mailbox. The next tick may therefore capture and publish one bounded
     * foreground slice instead of freezing the map for the full settle window.
     */
    public void prepareMovementStreaming() {
        MapWorkScheduler.bumpViewport(MapRequestLane.BACKGROUND);
        MapWorkScheduler.bumpViewport(MapRequestLane.PREFETCH);
        MapPublicationCoordinator.getInstance().beginTick();
        lastMinimapRun = 0L;
        lastFullscreenRun = 0L;
        lastFullscreenCaveSourceRun = 0L;
        lastLayerUploadRun = 0L;
        lastAdjacentWarmupRun = 0L;
        lastBackgroundSurfaceRun = 0L;
        lastLoadedSurfaceHaloRun = 0L;
        lastHiddenCaveHaloRun = 0L;
    }

    /**
     * Quarantines the discontinuous player-local lane once after teleport. The
     * fullscreen world-map viewport is independent from the player's position and
     * must retain its valid work/mailbox when the player teleports while MapScreen
     * remains open. Revoking FULLSCREEN here was the source of persistent black
     * rectangles: the unchanged viewer lost ownership while the new MINIMAP halo
     * simultaneously reused atlas slots.
     */
    public void suspendForMovement() {
        MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        MapPublicationCoordinator.getInstance().beginTick();
        MapPublicationCoordinator.getInstance().setPublicationAllowed(false);
        lastMinimapRun = 0L;
        lastLoadedSurfaceHaloRun = 0L;
        lastHiddenCaveHaloRun = 0L;
    }

    public void closeFullscreen() {
        fullscreenRequest = null;
        fullscreenGeneration++;
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        lastFullscreenCaveSourceRun = 0L;
        layerStream = new LayerStreamState();
        MapPerformanceGovernor.getInstance().setFullscreenState(false, false);
    }

    /** Drops only selected-Y traversal state while retaining warm GPU textures. */
    public void onLayerChanged() {
        fullscreenGeneration++;
        layerStream = new LayerStreamState();
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        lastFullscreenCaveSourceRun = 0L;
        lastLayerUploadRun = 0L;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && CaveMode.isActive(mc)) {
            int targetLayer = CaveMode.isFullView(mc) ? Integer.MIN_VALUE : CaveMode.getLayerY(mc);
            com.velorise.simplemap.client.cave.CaveTextureManager.getInstance()
                    .onLayerActivated(targetLayer);
        }
    }

    public void reset() {
        fullscreenRequest = null;
        minimapRequest = null;
        fullscreenGeneration++;
        lastFullscreenRun = 0L;
        lastFullscreenCaveSourceRun = 0L;
        lastMinimapRun = 0L;
        lastLayerUploadRun = 0L;
        lastAdjacentWarmupRun = 0L;
        lastBackgroundSurfaceRun = 0L;
        lastLoadedSurfaceHaloRun = 0L;
        lastHiddenCaveHaloRun = 0L;
        lastSuspendedTeleportEpoch = Long.MIN_VALUE;
        layerStream = new LayerStreamState();
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        MapPerformanceGovernor.getInstance().setFullscreenState(false, false);
    }

    public void tick(Minecraft minecraft) {
        tick(minecraft, MapPerformanceGovernor.getInstance().observationProfile(minecraft));
    }

    public void tick(Minecraft minecraft,
            MapPerformanceGovernor.ObservationProfile profile) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return;
        MapActivityGate activityGate = MapActivityGate.getInstance();
        if (activityGate.blocksForegroundStreaming()) {
            long teleportEpoch = activityGate.teleportEpoch();
            if (lastSuspendedTeleportEpoch != teleportEpoch) {
                lastSuspendedTeleportEpoch = teleportEpoch;
                suspendForMovement();
            } else {
                // Do not bump MINIMAP/FULLSCREEN generations on every quarantine
                // tick. That repeatedly invalidated the destination work we had just
                // submitted and is the main teleport-only consistency failure.
                MapPublicationCoordinator publication =
                        MapPublicationCoordinator.getInstance();
                publication.beginTick();
                publication.setPublicationAllowed(false);
            }
            return;
        }
        if (profile == null) profile = MapPerformanceGovernor.getInstance()
                .observationProfile(minecraft);
        MapPublicationCoordinator publication = MapPublicationCoordinator.getInstance();
        publication.beginTick();
        long now = System.nanoTime();
        Request fullscreen = fullscreenRequest;
        boolean fullscreenVisible = fullscreen != null
                && fullscreen.generation == fullscreenGeneration
                && now - fullscreen.submittedNanos < 10_000_000_000L;
        // The minimap renderer is hidden while MapScreen owns the screen, but its
        // bounded Cave presentation subscriber is retained below. The coordinator consumes the
        // World Map MapProcessor product directly for minimap cave chunks; Simple Map
        // mirrors that with one UnifiedCaveTextureManager product rather than letting
        // the two views diverge until MapScreen closes.
        Request minimap = minimapRequest;
        tickLoadedPlayerHalo(minecraft, now, fullscreenVisible, publication);
        if (!fullscreenVisible && minimap != null
                && now - minimap.submittedNanos < 500_000_000L
                && now - lastMinimapRun >= profile.minimapIntervalNanos()) {
            lastMinimapRun = now;
            boolean viewingLive = MapManager.getInstance().isViewingLiveDimension();
            if (viewingLive) {
                boolean caveVisibleNow = CaveMode.isActive(minecraft);
                if (caveVisibleNow) {
                    if (profile.allowVisibleScan()) {
                        ChunkScanner.getInstance().scanVisibleCaveArea(minecraft,
                                minimap.minX, minimap.maxX,
                                minimap.minZ, minimap.maxZ, minimap.scale,
                                minimap.focusX, minimap.focusZ, minimap.lane);
                    }
                } else if (profile.allowVisibleScan()) {
                    ChunkScanner.getInstance().scanVisibleArea(minecraft,
                            minimap.minX, minimap.maxX,
                            minimap.minZ, minimap.maxZ, minimap.scale,
                            minimap.focusX, minimap.focusZ, minimap.lane);
                }
            }
            if (profile.allowSavedVisible() || !viewingLive) {
                long savedStarted = System.nanoTime();
                requestTextures(minecraft, minimap);
                MapObservationTelemetry.getInstance().record(
                        MapObservationTelemetry.Lane.SAVED_VISIBLE,
                        System.nanoTime() - savedStarted, 1);
            } else if (profile.allowPublication()) {
                requestLiveTextures(minecraft, minimap);
            }
            boolean cave = CaveMode.isActive(minecraft);
            boolean full = cave && CaveMode.isFullView(minecraft);
            if (full) {
                publication.requestFullCave(false);
            } else if (cave) {
                publication.requestLayeredCave();
            } else {
                publication.requestSurface(MapRequestLane.MINIMAP, false);
            }
        }


        /*
         * PASS160: source acquisition is a persistent writer, not a one-shot side
         * effect of a renderer/publication pass. PASS159 could request the first
         * 32-page Cave window, fill the first rows, then stop permanently because
         * the next source window was only selected when this coordinator entered
         * the non-interacting fullscreen publication branch again. the 
         * MinimapWriter/MapWriter advances its cursor independently under a hard
         * time/cadence budget. Do the same here: the retained fullscreen request is
         * enough to advance the Cave source cursor even while composition is being
         * reused or the viewport is briefly interacting.
         */
        boolean fullscreenCaveVisible = fullscreenVisible
                && CaveMode.isActive(minecraft);

        /*
         * PASS168 parity: while the world-map Cave view is visible there is
         * exactly one foreground presentation owner. PASS164 kept a hidden MINIMAP
         * subscriber alive here, which started a second presentation generation and
         * repeatedly reclassified the same retained region products between MINIMAP
         * and FULLSCREEN. The 12:55 trace showed that handoff churn while fullscreen
         * exact residency stayed near zero. The atlas/repository remain shared, so
         * the minimap reuses finished products when the screen closes without owning
         * a competing foreground generation while it is hidden.
         */
        if (fullscreenCaveVisible && minimap != null
                && MapManager.getInstance().isViewingLiveDimension()
                && now - lastHiddenCaveHaloRun
                        >= HIDDEN_CAVE_HALO_INTERVAL_NANOS) {
            lastHiddenCaveHaloRun = now;
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_FULLSCREEN_HIDDEN_MINIMAP_SUPPRESSED", 1000L)) {
                recorder.event("CAVE_FULLSCREEN_HIDDEN_MINIMAP_SUPPRESSED",
                        "top_y=" + CaveMode.getLayerY(minecraft)
                                + " policy=single_visible_foreground_owner_shared_cache"
                                + " pass=PASS168");
            }
        }

        /*
         * PASS167: the visible fullscreen Cave writer is a liveness owner, not saved
         * background work. Do not let ObservationProfile.allowSavedVisible() park it.
         * The 12:06 trace changed Layered Top-Y with 228 visible pages, advanced only
         * 26-48 source pages, then emitted no writer pulses while CPU/IO were idle.
         * The persistent writer keeps its cursor alive every update and
         * merely shrinks work to the elapsed-time budget. Our source window is already
         * pressure-bounded (32/16/8 and halved under pressure), so keep pulsing until
         * the visible cursor closes. Teleport/world handoff quarantine remains the
         * only foreground hard stop and scanVisibleCaveArea() enforces it again.
         */
        if (fullscreenCaveVisible
                && !fullscreen.interacting
                && MapManager.getInstance().isViewingLiveDimension()
                && !MapActivityGate.getInstance().blocksForegroundStreaming()) {
            long caveSourceInterval = fullscreen.interacting
                    ? CAVE_FULLSCREEN_SOURCE_INTERACT_INTERVAL_NANOS
                    : CAVE_FULLSCREEN_SOURCE_IDLE_INTERVAL_NANOS;
            if (now - lastFullscreenCaveSourceRun >= caveSourceInterval) {
                lastFullscreenCaveSourceRun = now;
                double sourceFocusX = (fullscreen.minX + fullscreen.maxX) * 0.5;
                double sourceFocusZ = (fullscreen.minZ + fullscreen.maxZ) * 0.5;
                ChunkScanner.getInstance().scanVisibleCaveArea(minecraft,
                        fullscreen.minX, fullscreen.maxX,
                        fullscreen.minZ, fullscreen.maxZ, fullscreen.scale,
                        sourceFocusX, sourceFocusZ, fullscreen.lane);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent(
                        "CAVE_FULLSCREEN_PERSISTENT_WRITER_PULSE", 500L)) {
                    recorder.event("CAVE_FULLSCREEN_PERSISTENT_WRITER_PULSE",
                            "interacting=" + fullscreen.interacting
                                    + " interval_ms=" + caveSourceInterval / 1_000_000L
                                    + " profile_saved_visible=" + profile.allowSavedVisible()
                                    + " foreground_liveness=true"
                                    + " predecessor_policy=retained_cursor_not_render_request"
                                    + " policy=persistent_visible_writer"
                                    + " pass=PASS167");
                }
            }
        }

        if (fullscreenVisible && !fullscreen.interacting
                && now - lastFullscreenRun >= profile.fullscreenIntervalNanos()) {
            lastFullscreenRun = now;

            // A static dimension has no live ClientLevel to scan. Only saved cache
            // files are streamed; this prevents Overworld blocks being written into
            // a Nether/End/modded-dimension map while browsing it remotely.
            boolean caveVisibleNow = CaveMode.isActive(minecraft);
            if (MapManager.getInstance().isViewingLiveDimension()
                    && !caveVisibleNow && profile.allowVisibleScan()) {
                double schedulingFocusX = (fullscreen.minX + fullscreen.maxX) * 0.5;
                double schedulingFocusZ = (fullscreen.minZ + fullscreen.maxZ) * 0.5;
                ChunkScanner.getInstance().scanVisibleArea(minecraft,
                        fullscreen.minX, fullscreen.maxX,
                        fullscreen.minZ, fullscreen.maxZ, fullscreen.scale,
                        schedulingFocusX, schedulingFocusZ, fullscreen.lane);
            }

            if (profile.allowSavedVisible()) {
                long savedStarted = System.nanoTime();
                requestTextures(minecraft, fullscreen);
                MapObservationTelemetry.getInstance().record(
                        MapObservationTelemetry.Lane.SAVED_VISIBLE,
                        System.nanoTime() - savedStarted, 1);
            } else if (profile.allowPublication()) {
                // Same loaded-source-only path as the minimap. This keeps an open
                // fullscreen map following the player without admitting saved IO.
                requestLiveTextures(minecraft, fullscreen);
            }
            boolean cave = CaveMode.isActive(minecraft);
            boolean full = cave && CaveMode.isFullView(minecraft);
            boolean focus = MapConfig.fastFullscreenLoading
                    && MapPerformanceGovernor.getInstance()
                            .hasForegroundUploadHeadroom();
            if (full) {
                publication.requestFullCave(focus);
            } else if (cave) {
                // Layer publication remains zoom-paced, but it is drained only once.
                long uploadInterval = layerUploadIntervalNanos(fullscreen.scale);
                if (now - lastLayerUploadRun >= uploadInterval) {
                    lastLayerUploadRun = now;
                    publication.requestLayeredCave(focus);
                }
            } else {
                publication.requestSurface(MapRequestLane.FULLSCREEN, focus);
            }
        }

        if (profile.allowArchiveBackground()) {
            tickLiveSurfaceBackground(minecraft, now, fullscreen);
        }
        tickAdjacentLayerWarmup(minecraft, now, fullscreen, minimap, profile);
        publication.setPublicationAllowed(profile.allowPublication());
    }


    /**
     * Keeps every currently loaded Minecraft chunk flowing into the Surface
     * repository even while a fullscreen map owns the visible viewport. Loaded
     * render-distance coverage is a minimap invariant, not optional background
     * prefetch: BACKGROUND requests are intentionally centre-limited to 3x3 pages
     * by MapTextureManager and therefore stranded the outer halo whenever MapScreen
     * was open. Keep this demand on the MINIMAP lane; fullscreen still owns its own
     * visible lane and GPU publication budget.
     */
    private void tickLoadedPlayerHalo(Minecraft minecraft, long now,
            boolean fullscreenVisible, MapPublicationCoordinator publication) {
        if (!MapManager.getInstance().isViewingLiveDimension()) return;
        boolean caveActive = CaveMode.isActive(minecraft);
        boolean caveFullscreenVisible = fullscreenVisible && caveActive;
        int renderDistance = Math.max(2,
                minecraft.options.getEffectiveRenderDistance());
        int radius = Math.max(64, (renderDistance + 1) * 16);
        /*
         * PASS158: the active projection owns the gameplay foreground budget.
         * PASS157 still refreshed and published the hidden Surface halo every
         * 50 ms while the Cave minimap was visible. The latest capture shows
         * thousands of Surface batches/exact refreshes and 0.7-1.2 GiB/s
         * allocation during GAME/Cave even when Cave source wait is zero. The engine
         * keeps one bounded map writer for the selected projection instead of
         * rebuilding an invisible alternative map in parallel. Surface catches
         * up from loaded chunks after Cave is disabled.
         */
        if (!caveActive
                && now - lastLoadedSurfaceHaloRun
                        >= LOADED_SURFACE_HALO_INTERVAL_NANOS) {
            lastLoadedSurfaceHaloRun = now;
            long budget = fullscreenVisible
                    ? LOADED_SURFACE_HALO_FULLSCREEN_BUDGET_NANOS
                    : LOADED_SURFACE_HALO_GAME_BUDGET_NANOS;
            ChunkScanner.getInstance().maintainLoadedSurfaceHalo(
                    minecraft, radius, budget);
            double centerX = minecraft.player.getX();
            double centerZ = minecraft.player.getZ();
            submitSurfaceDemand(
                    centerX - radius, centerX + radius,
                    centerZ - radius, centerZ + radius,
                    centerX, centerZ, 1.0f,
                    MapRequestLane.MINIMAP, false);
            publication.requestSurface(MapRequestLane.MINIMAP, false);
        }

        if (caveActive) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "PASS158_CAVE_HIDDEN_SURFACE_SUPPRESSED", 2000L)) {
                recorder.event("CAVE_HIDDEN_SURFACE_WORK_SUPPRESSED",
                        "surface_halo=false surface_background=false "
                                + "policy=active_projection_exclusive_foreground "
                                + "pass=PASS158");
            }
        }

        /*
         * PASS145: when fullscreen Cave is the visible task, the hidden minimap
         * surface halo and the extra player-centred cave halo add CPU churn but do
         * not improve the map the user is waiting on. Let the fullscreen viewport
         * own the source budget exclusively until its visible Cave work closes.
         */
        if (!fullscreenVisible || !CaveMode.isActive(minecraft)
                || caveFullscreenVisible
                || now - lastHiddenCaveHaloRun < HIDDEN_CAVE_HALO_INTERVAL_NANOS) {
            return;
        }
        lastHiddenCaveHaloRun = now;
        CaveView view = CaveMode.isFullView(minecraft)
                ? CaveView.FULL : CaveView.LAYERED;
        int topY = view == CaveView.FULL
                ? Integer.MIN_VALUE : CaveMode.getLayerY(minecraft);
        double centerX = minecraft.player.getX();
        double centerZ = minecraft.player.getZ();
        CaveTextureManager.getInstance().requestVisiblePages(
                topY,
                centerX - radius, centerX + radius,
                centerZ - radius, centerZ + radius,
                1.0f, centerX, centerZ, MapRequestLane.BACKGROUND);
    }

    /**
     * Keeps the player-centred surface hot set current even when the minimap is
     * hidden or its scale selects only a high LOD. This is deliberately small: it
     * publishes only already-scanned dirty regions and never expands the gameplay
     * chunk load radius.
     */
    private void tickLiveSurfaceBackground(Minecraft minecraft, long now, Request fullscreen) {
        if (!MapManager.getInstance().isViewingLiveDimension()) return;
        boolean caveActive = CaveMode.isActive(minecraft);
        boolean fullscreenVisible = fullscreen != null
                && now - fullscreen.submittedNanos < 500_000_000L;
        // PASS158: Surface fullscreen owns visible Surface demand, while active Cave
        // owns gameplay foreground exclusively. Hidden Surface maintenance is not
        // latency-sensitive and resumes when Cave is disabled.
        if (fullscreenVisible || caveActive) return;
        long interval = BACKGROUND_SURFACE_INTERVAL_NANOS;
        if (now - lastBackgroundSurfaceRun < interval) return;
        lastBackgroundSurfaceRun = now;

        int renderDistance = minecraft.options.renderDistance().get();
        int maximumRadius = BACKGROUND_SURFACE_MAX_RADIUS;
        int radius = Math.max(BACKGROUND_SURFACE_MIN_RADIUS,
                Math.min(maximumRadius, (renderDistance + 1) * 16));
        double centerX = minecraft.player.getX();
        double centerZ = minecraft.player.getZ();
        double minX = centerX - radius;
        double maxX = centerX + radius;
        double minZ = centerZ - radius;
        double maxZ = centerZ + radius;

        submitSurfaceDemand(minX, maxX, minZ, maxZ,
                centerX, centerZ, 1.0f,
                MapRequestLane.BACKGROUND, false);
        MapPublicationCoordinator.getInstance().requestSurface(MapRequestLane.BACKGROUND, false);

        // Do not decode cave Anvil sources while Surface is the active projection.
        // The old warmup could fan one newly explored surface viewport into many
        // 16-chunk cave source decodes and was a major CPU-spike source.
    }

    /**
     * Warm only already-cached adjacent cave bands. This lane never changes the
     * active layer and never starts Anvil reads; it prepares retained pages that can
     * become an immediate fallback after a vertical band transition.
     */
    private void tickAdjacentLayerWarmup(Minecraft minecraft, long now,
            Request fullscreen, Request minimap,
            MapPerformanceGovernor.ObservationProfile profile) {
        if (!profile.allowLayerWarmup() || !CaveMode.isActive(minecraft)
                || CaveMode.isFullView(minecraft)
                || MapPerformanceGovernor.getInstance().underPressure()
                || (fullscreen != null
                        && now - fullscreen.submittedNanos < 500_000_000L)
                || !MapManager.getInstance().isViewingLiveDimension()) return;
        if (now - lastAdjacentWarmupRun < CAVE_ADJACENT_WARMUP_INTERVAL_NANOS) return;
        lastAdjacentWarmupRun = now;

        Request source = fullscreen != null && now - fullscreen.submittedNanos < 500_000_000L
                ? fullscreen : minimap;
        double centerX = source == null ? minecraft.player.getX()
                : (source.minX + source.maxX) * 0.5;
        double centerZ = source == null ? minecraft.player.getZ()
                : (source.minZ + source.maxZ) * 0.5;
        double radius = source == null ? 128.0
                : Math.min(256.0, Math.max(96.0,
                        Math.max(source.maxX - source.minX,
                                source.maxZ - source.minZ) * 0.25));
    }

    private void requestTextures(Minecraft minecraft, Request request) {
        requestLiveTextures(minecraft, request);
    }

    /**
     * Converts only repositories already populated from loaded chunks into visible
     * exact demand. No Anvil read, broad decoder, warmup or persistence work is
     * admitted here, so it is safe for the bounded movement lane.
     *
     * @return {@code true} when the active projection is Cave (which has no
     *         additional Surface saved-source work to perform).
     */
    private boolean requestLiveTextures(Minecraft minecraft, Request request) {
        boolean cave = CaveMode.isActive(minecraft);
        boolean full = cave && CaveMode.isFullView(minecraft);
        int layerY = cave ? CaveMode.getLayerY(minecraft) : Integer.MIN_VALUE;

        if (cave) {
            // Cave page projection is much heavier than a cached leaf load.
            // Far zoom remains branch-rendered, but the screen-space policy admits
            // one slow coherent leaf seed so a cold viewport can build LOD instead
            // of staying black forever.
            // Minimap is camera/player-centred. Fullscreen uses a stable
            // viewport-owned traversal; cursor movement never changes admission.
            double schedulingFocusX = (request.minX + request.maxX) * 0.5;
            double schedulingFocusZ = (request.minZ + request.maxZ) * 0.5;
            int targetLayer = full ? Integer.MIN_VALUE : layerY;
            double guiScale = minecraft == null || minecraft.getWindow() == null
                    ? 1.0D : Math.max(1.0D, minecraft.getWindow().getGuiScale());
            float physicalScale = MapLodPolicy.MapRenderScalePolicy.physicalPixelsPerBlock(
                    request.scale, guiScale);
            CaveMapManager.getInstance().setActiveLayer(targetLayer);
            requestLayerRegions(request, targetLayer);
            CaveTextureManager.getInstance().requestVisiblePages(
                    targetLayer,
                    request.minX, request.maxX, request.minZ, request.maxZ,
                    physicalScale, schedulingFocusX, schedulingFocusZ,
                    request.lane);
            return true;
        }

        // Exact leaves are the sole surface authority. At far zoom the manager
        // admits only a bounded attention hot set; recursive branches cover the
        // remainder without constructing legacy 512x512 overview textures.
        submitSurfaceDemand(
                request.minX, request.maxX,
                request.minZ, request.maxZ,
                (request.minX + request.maxX) * 0.5,
                (request.minZ + request.maxZ) * 0.5,
                request.scale, request.lane,
                request.lane == MapRequestLane.FULLSCREEN);
        return false;
    }

    /**
     * Streams exact selected-Y cave regions center-out. It deliberately avoids
     * low-detail cave parents: thin tunnels retain the old sharp appearance while
     * the region request rate falls with zoom level.
     */
    private void requestLayerRegions(Request request, int layerY) {
        int minRx = (int) Math.floor(request.minX - 1.0) >> 9;
        int maxRx = (int) Math.floor(request.maxX + 1.0) >> 9;
        int minRz = (int) Math.floor(request.minZ - 1.0) >> 9;
        int maxRz = (int) Math.floor(request.maxZ + 1.0) >> 9;
        String dimension = MapManager.getInstance().getDimensionCacheKey();
        int focusRx = Math.max(minRx, Math.min(maxRx,
                (int) Math.floor(request.focusX) >> 9));
        int focusRz = Math.max(minRz, Math.min(maxRz,
                (int) Math.floor(request.focusZ) >> 9));

        if (!layerStream.matches(dimension, layerY, minRx, maxRx, minRz, maxRz,
                focusRx, focusRz)) {
            layerStream = LayerStreamState.create(dimension, layerY,
                    minRx, maxRx, minRz, maxRz, focusRx, focusRz);
        } else if (layerStream.cursor >= layerStream.regions.size()
                && System.nanoTime() - layerStream.completedAtNanos >= 15_000_000_000L) {
            // A slow maintenance pass catches previously saturated IO or newly
            // written archive data without producing a visible centre-out wave
            // or allocating empty regions every few seconds.
            layerStream.cursor = 0;
        }

        int budget = layerRegionBudget(request.scale);
        CaveMapManager layers = CaveMapManager.getInstance();
        CaveTextureManager textures = CaveTextureManager.getInstance();
        int processed = 0;
        while (processed < budget && layerStream.cursor < layerStream.regions.size()) {
            int[] region = layerStream.regions.get(layerStream.cursor++);
            int rx = region[0];
            int rz = region[1];
            if (!layers.hasRegionFile(layerY, rx, rz)
                    && !layers.isRegionLoaded(layerY, rx, rz)) {
                continue;
            }
            processed++;

            boolean alreadySliced = layers.isRegionAnvilSlicedAtLayer(layerY, rx, rz);
            boolean warmExactTexture = alreadySliced
                    || (layers.isRegionLoaded(layerY, rx, rz)
                            && (textures.peekRegionTexture(layerY, rx, rz) != null
                                    || textures.hasAnyPageTexture(layerY, rx, rz)));
            if (warmExactTexture) {
                continue;
            }
            layers.requestVisibleRegion(layerY, rx, rz);
            int dx = rx - layerStream.centerRx;
            int dz = rz - layerStream.centerRz;
            int priority = 250_000 - (dx * dx + dz * dz) * 100;
            MapProcessor.getInstance().enqueueCaveLoad(
                    layerY, rx, rz, Math.max(1, priority));
        }
        if (layerStream.cursor >= layerStream.regions.size()) {
            layerStream.completedAtNanos = System.nanoTime();
        }
    }

    private static int layerRegionBudget(float scale) {
        return 8;
    }

    private static long layerUploadIntervalNanos(float scale) {
        if (scale < 0.12f) return 60_000_000L;
        if (scale < 0.20f) return 45_000_000L;
        if (scale < 0.35f) return 30_000_000L;
        return 20_000_000L;
    }

    /** Mutable render-to-tick mailbox. Reusing it removes one short-lived object
     * per rendered minimap/fullscreen frame while still publishing the newest
     * bounds and focus to the client-tick consumer. */
    private static final class Request {
        private double minX;
        private double maxX;
        private double minZ;
        private double maxZ;
        private float scale;
        private double focusX;
        private double focusZ;
        private boolean interacting;
        private final MapRequestLane lane;
        private volatile long submittedNanos;
        private long planningKey;
        private volatile long generation;

        private Request(double minX, double maxX, double minZ, double maxZ,
                float scale, double focusX, double focusZ,
                boolean interacting, MapRequestLane lane, long submittedNanos,
                long planningKey) {
            this.lane = lane;
            update(minX, maxX, minZ, maxZ, scale, focusX, focusZ,
                    interacting, submittedNanos, planningKey);
        }

        private void update(double minX, double maxX, double minZ, double maxZ,
                float scale, double focusX, double focusZ,
                boolean interacting, long submittedNanos, long planningKey) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.scale = scale;
            this.focusX = focusX;
            this.focusZ = focusZ;
            this.interacting = interacting;
            this.planningKey = planningKey;
            // Publish timestamp last so a tick that observes a fresh request also
            // observes all preceding viewport fields.
            this.submittedNanos = submittedNanos;
        }
    }


    private static boolean pageRectanglesOverlap(Request previous,
            double minX, double maxX, double minZ, double maxZ) {
        int previousMinPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(previous.minX));
        int previousMaxPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(previous.maxX));
        int previousMinPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(previous.minZ));
        int previousMaxPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(previous.maxZ));
        int currentMinPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(minX));
        int currentMaxPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(maxX));
        int currentMinPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(minZ));
        int currentMaxPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(maxZ));
        return previousMinPageX <= currentMaxPageX
                && previousMaxPageX >= currentMinPageX
                && previousMinPageZ <= currentMaxPageZ
                && previousMaxPageZ >= currentMinPageZ;
    }

    private static long planningKey(double minX, double maxX,
            double minZ, double maxZ, float scale) {
        int minPageX = MapPageLayout.globalPageFromBlock((int) Math.floor(minX));
        int maxPageX = MapPageLayout.globalPageFromBlock((int) Math.floor(maxX));
        int minPageZ = MapPageLayout.globalPageFromBlock((int) Math.floor(minZ));
        int maxPageZ = MapPageLayout.globalPageFromBlock((int) Math.floor(maxZ));
        int scaleBucket = Math.max(0, Math.min(15,
                (int) Math.floor(Math.log(Math.max(0.0001f, scale)) / Math.log(2.0) + 8.0)));
        long key = 0x9E3779B97F4A7C15L;
        key = Long.rotateLeft(key ^ minPageX, 13) * 0xC2B2AE3D27D4EB4FL;
        key = Long.rotateLeft(key ^ maxPageX, 17) * 0x165667B19E3779F9L;
        key = Long.rotateLeft(key ^ minPageZ, 21) * 0x85EBCA77C2B2AE63L;
        key = Long.rotateLeft(key ^ maxPageZ, 29) * 0x27D4EB2F165667C5L;
        return key ^ scaleBucket;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(Math.min(minimum, maximum),
                Math.min(Math.max(minimum, maximum), value));
    }

    private static final class LayerStreamState {
        private String dimension = "";
        private int layerY = Integer.MIN_VALUE;
        private int minRx;
        private int maxRx;
        private int minRz;
        private int maxRz;
        private int centerRx;
        private int centerRz;
        private List<int[]> regions = List.of();
        private int cursor;
        private long completedAtNanos;

        private static LayerStreamState create(String dimension, int layerY,
                int minRx, int maxRx, int minRz, int maxRz,
                int focusRx, int focusRz) {
            LayerStreamState state = new LayerStreamState();
            state.dimension = dimension;
            state.layerY = layerY;
            state.minRx = minRx;
            state.maxRx = maxRx;
            state.minRz = minRz;
            state.maxRz = maxRz;
            state.centerRx = Math.max(minRx, Math.min(maxRx, focusRx));
            state.centerRz = Math.max(minRz, Math.min(maxRz, focusRz));
            CaveMapManager layers = CaveMapManager.getInstance();
            int clampedMinRx = Math.max(minRx, state.centerRx - 12);
            int clampedMaxRx = Math.min(maxRx, state.centerRx + 12);
            int clampedMinRz = Math.max(minRz, state.centerRz - 12);
            int clampedMaxRz = Math.min(maxRz, state.centerRz + 12);
            List<int[]> regions = new ArrayList<>();
            for (int rx = clampedMinRx; rx <= clampedMaxRx; rx++) {
                for (int rz = clampedMinRz; rz <= clampedMaxRz; rz++) {
                    if (layers.hasRegionFile(layerY, rx, rz)
                            || layers.isRegionLoaded(layerY, rx, rz)) {
                        regions.add(new int[] { rx, rz });
                    }
                }
            }
            regions.sort(Comparator
                    .comparingInt((int[] region) -> {
                        int dx = region[0] - state.centerRx;
                        int dz = region[1] - state.centerRz;
                        return dx * dx + dz * dz;
                    })
                    .thenComparingInt(region -> Math.max(
                            Math.abs(region[0] - state.centerRx),
                            Math.abs(region[1] - state.centerRz)))
                    .thenComparingInt(region -> region[1])
                    .thenComparingInt(region -> region[0]));
            state.regions = regions;
            return state;
        }

        private boolean matches(String dimension, int layerY,
                int minRx, int maxRx, int minRz, int maxRz,
                int focusRx, int focusRz) {
            return this.dimension.equals(dimension) && this.layerY == layerY
                    && this.minRx == minRx && this.maxRx == maxRx
                    && this.minRz == minRz && this.maxRz == maxRz;
        }
    }


public static final class MapViewportDemandPolicy {
    private MapViewportDemandPolicy() {
    }

    public static Bounds trimEdgeSlivers(double minX, double maxX,
            double minZ, double maxZ, MapRequestLane lane) {
        double safeMinX = Math.min(minX, maxX);
        double safeMaxX = Math.max(minX, maxX);
        double safeMinZ = Math.min(minZ, maxZ);
        double safeMaxZ = Math.max(minZ, maxZ);
        return new Bounds(safeMinX, safeMaxX, safeMinZ, safeMaxZ);
    }

    public record Bounds(double minX, double maxX, double minZ, double maxZ) {
    }
}

/**
 * Defines the rolling exact-surface working set for a zoom-selected viewport.
 *
 * <p>The whole viewport remains eligible. Work volume is controlled by the
 * bounded centre-out frontier and the selected LOD, not by cutting arbitrary
 * strips from the screen.</p>
 */
public static final class MapSurfaceDemandPolicy {
    private static volatile Snapshot latest = Snapshot.identity();

    private MapSurfaceDemandPolicy() {
    }

    public static Bounds trim(double minX, double maxX,
            double minZ, double maxZ, float scale) {
        double safeMinX = Math.min(minX, maxX);
        double safeMaxX = Math.max(minX, maxX);
        double safeMinZ = Math.min(minZ, maxZ);
        double safeMaxZ = Math.max(minZ, maxZ);
        latest = new Snapshot(false, 1.0, 0.0, 0.0, 0.0,
                exactActiveWindow(scale, false));
        return new Bounds(safeMinX, safeMaxX, safeMinZ, safeMaxZ);
    }

    /**
     * Maximum number of concurrent fullscreen exact leaves. Branch publication
     * remains independent and therefore continues to fill the complete viewport.
     */
    public static int exactActiveWindow(float scale, boolean pressure) {
        return exactActiveWindow(scale, pressure, Integer.MAX_VALUE);
    }

    /**
     * Capacity-aware exact working set. At density-correct L1 far zoom, the
     * 512x512 Region LOD is authoritative full-viewport coverage and exact leaves
     * are refinement only. Keep that hot set small so exact work cannot starve the
     * coarse disk/Region-LOD frontier. Close zoom may use a larger exact window.
     */
    public static int exactActiveWindow(float scale, boolean pressure,
            int visiblePageCount) {
        int visible = Math.max(1, visiblePageCount);
        float safeScale = Float.isFinite(scale) && scale > 0.0f ? scale : 1.0f;
        float projectedChunkPixels = safeScale * MapPageLayout.SUBTILE_SIZE;
        int cap = projectedChunkPixels >= 16.0f ? 48
                : projectedChunkPixels >= 8.0f ? 32
                : projectedChunkPixels >= 4.0f ? 16
                : projectedChunkPixels >= 2.0f ? 8 : 4;
        if (pressure) cap = Math.max(2, cap / 2);
        return Math.min(visible, cap);
    }

    public static Snapshot snapshot() {
        return latest;
    }

    public record Bounds(double minX, double maxX, double minZ, double maxZ) {
    }

    public record Snapshot(boolean trimmed, double areaRatio,
            double leftFraction, double rightFraction, double verticalFraction,
            int exactActiveWindow) {
        private static Snapshot identity() {
            return new Snapshot(false, 1.0, 0.0, 0.0, 0.0, 12);
        }
    }

}

/**
 * Single render-thread publication runner for all visible map projections.
 *
 * <p>Viewport/tick code publishes intent and performs no GPU work. Render events
 * drain the same intent against one shared frame ledger. This mirrors the
 * central render-process pattern: completed CPU work can advance every rendered
 * frame, while a slow frame automatically receives a smaller upload slice.
 * The visible projection exclusively owns that frame's GPU publication slice;
 * inactive projection payloads stay retained until they become primary.</p>
 */
public static final class MapPublicationCoordinator {
    private static final MapPublicationCoordinator INSTANCE =
            new MapPublicationCoordinator();

    private boolean surfaceRequested;
    private boolean surfaceFocus;
    private MapRequestLane surfaceLane;
    private boolean layeredCaveRequested;
    private boolean layeredCaveFocus;
    private boolean fullCaveRequested;
    private boolean fullCaveFocus;
    private boolean publicationAllowed;
    private long lastFrameId = Long.MIN_VALUE;
    private long drainCount;
    private long coalescedRequests;

    private MapPublicationCoordinator() {
    }

    public static MapPublicationCoordinator getInstance() {
        return INSTANCE;
    }

    /** Starts a fresh viewport-intent epoch. GPU publication remains frame-owned. */
    public void beginTick() {
        surfaceRequested = false;
        surfaceFocus = false;
        surfaceLane = null;
        layeredCaveRequested = false;
        layeredCaveFocus = false;
        fullCaveRequested = false;
        fullCaveFocus = false;
        coalescedRequests = 0L;
    }

    public void setPublicationAllowed(boolean allowed) {
        publicationAllowed = allowed;
    }

    public void requestSurface(boolean focus) {
        requestSurface(MapRequestLane.BACKGROUND, focus);
    }

    public void requestSurface(MapRequestLane lane, boolean focus) {
        if (surfaceRequested) coalescedRequests++;
        surfaceRequested = true;
        surfaceFocus |= focus;
        MapRequestLane effective = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        if (effective.strongerThan(surfaceLane)) surfaceLane = effective;
    }

    public void requestLayeredCave() {
        requestLayeredCave(false);
    }

    public void requestLayeredCave(boolean focus) {
        if (layeredCaveRequested) coalescedRequests++;
        layeredCaveRequested = true;
        layeredCaveFocus |= focus;
    }

    public void requestFullCave(boolean focus) {
        if (fullCaveRequested) coalescedRequests++;
        fullCaveRequested = true;
        fullCaveFocus |= focus;
    }

    /**
     * Drains one primary projection family for one actual rendered frame. The
     * viewport intent deliberately remains live until the next client tick, so a
     * 120/144 Hz client can consume completed work in small slices instead of one
     * 20 TPS burst.
     */
    public void drainFrame(long frameId) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        if (!publicationAllowed) return;
        if (frameId == lastFrameId) return;
        if (!surfaceRequested && !layeredCaveRequested && !fullCaveRequested) return;
        lastFrameId = frameId;

        boolean focused = fullCaveRequested ? fullCaveFocus
                : layeredCaveRequested ? layeredCaveFocus : surfaceFocus;
        // Source capture and GPU upload must share the same physical-frame
        // boundary. A time-window budget can otherwise refill repeatedly inside
        // one already-slow frame and deepen the movement hitch.
        SurfaceRegionSourceDatabase.getInstance().beginPublicationFrame(frameId);
        MapGpuBudgetController.getInstance().beginFrame(focused);
        long started = System.nanoTime();
        if (fullCaveRequested || layeredCaveRequested) {
            CaveTextureManager.getInstance().uploadDirtyTextures(focused);
        } else if (surfaceRequested) {
            /*
             * Publish prepared coarse branches before exact leaves consume the
             * shared frame ledger. This gives far-zoom L1 coverage a deterministic
             * path to the screen instead of letting exact uploads starve it forever.
             * Exact pages published below feed the branch source for the next frame;
             * avoiding a second same-frame drain keeps denial counters and render
             * thread planning work bounded.
             */
            MapOverviewTextureManager.getInstance().publishBranches(surfaceFocus);
            MapTextureManager.getInstance().uploadExactTextures(
                    surfaceLane, surfaceFocus);
        }
        /*
         * The visible projection receives exclusive ownership of the current
         * upload slice. There is intentionally no secondary Surface GPU drain while
         * Cave/Full Cave is visible. CPU-complete Surface payloads remain retained
         * and resume when Surface becomes primary again.
         */
        drainCount++;
        MapObservationTelemetry.getInstance().record(
                MapObservationTelemetry.Lane.PUBLICATION,
                System.nanoTime() - started, 1);
    }

    /** Compatibility entry point for manual/debug paths. */
    public void drainFrame() {
        // Non-render callers cannot identify a physical frame. They receive a
        // unique ledger only for this compatibility path; normal rendering must
        // call the overload with the real client-owned frame id.
        drainFrame(lastFrameId == Long.MAX_VALUE ? Long.MIN_VALUE : lastFrameId + 1L);
    }

    /** Compatibility entry point for manual/debug paths. */
    public void drain(boolean allowed) {
        setPublicationAllowed(allowed);
        drainFrame();
    }

    public Snapshot snapshot() {
        return new Snapshot(drainCount, coalescedRequests,
                surfaceRequested, surfaceLane,
                layeredCaveRequested, fullCaveRequested,
                publicationAllowed);
    }

    public record Snapshot(long drainCount, long coalescedRequests,
            boolean surfaceRequested, MapRequestLane surfaceLane,
            boolean layeredCaveRequested, boolean fullCaveRequested,
            boolean publicationAllowed) {
    }
}



    public static final class MapLodPolicy {
    public static final int LEAF_WORLD_SIZE = 64;
    public static final int MAX_BRANCH_LEVEL = 7;

    private MapLodPolicy() {
    }

    public static int branchLevel(float pixelsPerBlock) {
        return branchLevel(pixelsPerBlock, MAX_BRANCH_LEVEL);
    }

    public static int branchLevel(float pixelsPerBlock, int maximumLevel) {
        double footprint = blocksPerScreenPixel(pixelsPerBlock);
        if (footprint < 2.0) return 0;
        int level = (int) Math.floor(Math.log(footprint) / Math.log(2.0));
        return Math.max(0, Math.min(maximumLevel, level));
    }


    /**
     * Selects the finest density-correct level that also fits the retained-node
     * budget for the current viewport. This prevents atlas thrashing on extremely
     * wide fullscreen views while keeping minimap views on a finer level.
     */
    public static int branchLevel(float pixelsPerBlock, int viewportWidth,
            int viewportHeight, double searchFactor, int targetVisibleNodes) {
        int level = branchLevel(pixelsPerBlock);
        double worldWidth = Math.max(1.0, viewportWidth * searchFactor
                * blocksPerScreenPixel(pixelsPerBlock));
        double worldHeight = Math.max(1.0, viewportHeight * searchFactor
                * blocksPerScreenPixel(pixelsPerBlock));
        // Exact leaves share one atlas binding and can therefore tolerate a much
        // larger quad count than independent branch nodes. The threshold follows
        // the selected memory profile instead of assuming the old 1024-slot atlas.
        if (level == 0) {
            long exactX = Math.max(1L, (long) Math.ceil(worldWidth / LEAF_WORLD_SIZE) + 1L);
            long exactZ = Math.max(1L, (long) Math.ceil(worldHeight / LEAF_WORLD_SIZE) + 1L);
            long exactSlots = (long) MapMemoryBudgetPolicy.surfaceLeafColumns()
                    * MapMemoryBudgetPolicy.surfaceLeafColumns();
            long safeExactSlots = Math.max(64L, exactSlots * 7L / 8L);
            if (exactX * exactZ <= safeExactSlots) return 0;
            level = 1;
        }
        while (level < MAX_BRANCH_LEVEL) {
            int columns = level <= 2
                    ? MapMemoryBudgetPolicy.branchLowColumns()
                    : MapMemoryBudgetPolicy.branchHighColumns();
            int atlasCapacity = columns * columns;
            int safeCapacity = Math.max(16, atlasCapacity * 3 / 4);
            int budget = Math.max(16, Math.min(targetVisibleNodes, safeCapacity));
            int nodeWorldSize = worldSizeForBranch(level);
            long nodesX = Math.max(1L, (long) Math.ceil(worldWidth / nodeWorldSize) + 1L);
            long nodesZ = Math.max(1L, (long) Math.ceil(worldHeight / nodeWorldSize) + 1L);
            if (nodesX * nodesZ <= budget) break;
            level++;
        }
        return level;
    }


    /**
     * Adds a small density hysteresis around adjacent LOD boundaries.
     *
     * <p>Without this guard a tiny wheel/viewport rounding change can alternate
     * between two branch levels every frame, making the renderer swap between
     * independently resident snapshots. Budget-forced coarser levels are never
     * delayed, because protecting frame time is more important than hysteresis.</p>
     */
    public static int stabilizeBranchLevel(int candidateLevel, int previousLevel,
            float pixelsPerBlock) {
        if (previousLevel < 0 || candidateLevel == previousLevel) return candidateLevel;
        if (Math.abs(candidateLevel - previousLevel) > 1) return candidateLevel;

        int densityLevel = branchLevel(pixelsPerBlock);
        // The viewport-node budget may require a coarser level than density alone.
        if (candidateLevel > previousLevel && candidateLevel > densityLevel) {
            return candidateLevel;
        }
        // When a previous budget-forced level is no longer necessary, return to the
        // density-correct level immediately rather than retaining excessive blur.
        if (candidateLevel < previousLevel && previousLevel > densityLevel) {
            return candidateLevel;
        }

        double footprint = blocksPerScreenPixel(pixelsPerBlock);
        if (candidateLevel > previousLevel) {
            double enterCoarser = (1 << candidateLevel) * 1.12;
            return footprint >= enterCoarser ? candidateLevel : previousLevel;
        }
        double leaveCoarser = (1 << previousLevel) * 0.88;
        return footprint <= leaveCoarser ? candidateLevel : previousLevel;
    }

    /**
     * Cave-specific LOD stabilization around user-scale boundaries.
     *
     * <p>Cave hierarchy selection is driven by logical map zoom, not GUI-scaled
     * framebuffer density. A narrow two-percent guard removes wheel jitter without
     * retaining exact L0 deep into the range where an L1 root should already be the
     * visible authority.</p>
     */
    public static int stabilizeCaveBranchLevel(int candidateLevel, int previousLevel,
            float logicalPixelsPerBlock) {
        if (previousLevel < 0 || candidateLevel == previousLevel) return candidateLevel;
        if (Math.abs(candidateLevel - previousLevel) > 1) return candidateLevel;

        double footprint = blocksPerScreenPixel(logicalPixelsPerBlock);
        if (candidateLevel > previousLevel) {
            double enterCoarser = (1 << candidateLevel) * 1.02;
            return footprint >= enterCoarser ? candidateLevel : previousLevel;
        }
        double leaveCoarser = (1 << previousLevel) * 0.98;
        return footprint <= leaveCoarser ? candidateLevel : previousLevel;
    }

    public static int leafMipLevel(float pixelsPerBlock, int maximumMip) {
        double footprint = blocksPerScreenPixel(pixelsPerBlock);
        int level = footprint < 2.0 ? 0
                : (int) Math.floor(Math.log(footprint) / Math.log(2.0));
        return Math.max(0, Math.min(maximumMip, level));
    }

    public static double blocksPerScreenPixel(float pixelsPerBlock) {
        return 1.0 / Math.max(0.0001, pixelsPerBlock);
    }

    public static int worldSizeForBranch(int level) {
        if (level <= 0) return LEAF_WORLD_SIZE;
        return LEAF_WORLD_SIZE << level;
    }

    public static int pageSpanForBranch(int level) {
        return level <= 0 ? 1 : 1 << level;
    }


public static final class MapRenderScalePolicy {
    private MapRenderScalePolicy() {
    }

    public static float physicalPixelsPerBlock(float logicalPixelsPerBlock,
            double guiScale) {
        float logical = Math.max(0.0001f, logicalPixelsPerBlock);
        return (float) (logical * Math.max(1.0, guiScale));
    }

    public static int physicalPixels(int logicalPixels, double guiScale) {
        return Math.max(1, (int) Math.ceil(Math.max(1, logicalPixels)
                * Math.max(1.0, guiScale)));
    }

    public static Scales fullscreenFbo(float logicalPixelsPerBlock,
            double guiScale) {
        float physical = physicalPixelsPerBlock(logicalPixelsPerBlock, guiScale);
        // The off-screen target and the final framebuffer have the same physical
        // texel density. LOD policy therefore uses physical density too.
        return new Scales(physical, physical);
    }

    public record Scales(float renderPixelsPerBlock, float policyPixelsPerBlock) {
    }
}

}


    public static final class MapViewLoadPlanner {
    public static final int FULLSCREEN_SLICE_SIZE = 100;
    public static final int FULLSCREEN_SHORTLIST_SIZE = 10;
    public static final int MINIMAP_HALO_PAGES = 1;
    /**
     * Maximum exact working radius needed by Minecraft's supported client render
     * distances. A standalone 9x9 chunk writer is not the policy used when
     * its world-map integration is active: that path renders the actual minimap
     * bounds from the shared region cache. The old radius of two pages confused
     * those two paths and permanently limited Simple Map to a 320x320-block island.
     */
    public static final int MINIMAP_MAX_RADIUS_PAGES = 10;

    /** Cave gameplay writer: centre +/-2 64x64 map pages. */
    public static final int CAVE_MINIMAP_MAX_RADIUS_PAGES = 2;
    private MapViewLoadPlanner() {
    }

    /** Persistent traversal state. Focus is deliberately not part of the key. */
    public static final class State {
        private String dimension = "";
        private int minX;
        private int maxX;
        private int minZ;
        private int maxZ;
        private int sliceIndex;
        private int sliceCount;
        private boolean configured;
        private boolean retainedOverlap;
        private long completedCycles;
        private long[] pagePlan = new long[0];

        public boolean configure(String dimension, int minX, int maxX,
                int minZ, int maxZ) {
            String safeDimension = dimension == null ? "" : dimension;
            int safeMinX = Math.min(minX, maxX);
            int safeMaxX = Math.max(minX, maxX);
            int safeMinZ = Math.min(minZ, maxZ);
            int safeMaxZ = Math.max(minZ, maxZ);
            if (configured && this.dimension.equals(safeDimension)
                    && this.minX == safeMinX && this.maxX == safeMaxX
                    && this.minZ == safeMinZ && this.maxZ == safeMaxZ) return false;

            int previousMinX = this.minX;
            int previousMaxX = this.maxX;
            int previousMinZ = this.minZ;
            int previousMaxZ = this.maxZ;
            boolean sameDimension = configured && this.dimension.equals(safeDimension);
            retainedOverlap = sameDimension && rectanglesOverlap(
                    previousMinX, previousMaxX, previousMinZ, previousMaxZ,
                    safeMinX, safeMaxX, safeMinZ, safeMaxZ);

            this.dimension = safeDimension;
            this.minX = safeMinX;
            this.maxX = safeMaxX;
            this.minZ = safeMinZ;
            this.maxZ = safeMaxZ;
            this.pagePlan = buildPlan(safeMinX, safeMaxX, safeMinZ, safeMaxZ);
            this.sliceCount = Math.max(1, (pagePlan.length
                    + FULLSCREEN_SLICE_SIZE - 1) / FULLSCREEN_SLICE_SIZE);
            this.sliceIndex = 0;
            this.completedCycles = 0L;
            this.configured = true;
            return true;
        }

        /**
         * Fills the current fullscreen update slice without advancing it.
         * The immutable plan is centre-out, so zooming never restarts at a remote
         * top-left edge and strands the inspected page behind hundreds of leaves.
         */
        public int fillCurrentFullscreenSlice(long[] output) {
            if (!configured || output == null || output.length == 0) return 0;
            int start = sliceIndex * FULLSCREEN_SLICE_SIZE;
            int count = Math.min(output.length,
                    Math.max(0, Math.min(pagePlan.length,
                            start + FULLSCREEN_SLICE_SIZE) - start));
            if (count > 0) System.arraycopy(pagePlan, start, output, 0, count);
            return count;
        }

        /** Compatibility view for dependency-light tools; production uses longs. */
        public int fillCurrentFullscreenSlice(Page[] output) {
            if (!configured || output == null || output.length == 0) return 0;
            int start = sliceIndex * FULLSCREEN_SLICE_SIZE;
            int end = Math.min(pagePlan.length, start + FULLSCREEN_SLICE_SIZE);
            int count = 0;
            for (int index = start; index < end && count < output.length; index++) {
                long packed = pagePlan[index];
                output[count++] = new Page(packedX(packed), packedZ(packed), index);
            }
            return count;
        }

        /** Advances only after the current slice has no missing or in-flight page. */
        public void advanceFullscreenSlice() {
            if (!configured) return;
            sliceIndex++;
            if (sliceIndex >= sliceCount) {
                sliceIndex = 0;
                completedCycles++;
            }
        }

        /** Compatibility helper for tests/tools that intentionally consume a slice. */
        public int fillNextFullscreenSlice(long[] output) {
            int count = fillCurrentFullscreenSlice(output);
            advanceFullscreenSlice();
            return count;
        }

        public int fillNextFullscreenSlice(Page[] output) {
            int count = fillCurrentFullscreenSlice(output);
            advanceFullscreenSlice();
            return count;
        }

        public int currentSliceStartOrdinal() {
            return sliceIndex * FULLSCREEN_SLICE_SIZE;
        }

        public boolean retainedOverlap() {
            return retainedOverlap;
        }

        public long completedCycles() {
            return completedCycles;
        }

        public int currentSliceIndex() {
            return sliceIndex;
        }

        public int sliceCount() {
            return sliceCount;
        }

        public void clear() {
            dimension = "";
            minX = maxX = minZ = maxZ = 0;
            sliceIndex = 0;
            sliceCount = 0;
            completedCycles = 0L;
            configured = false;
            retainedOverlap = false;
            pagePlan = new long[0];
        }

        private static long[] buildPlan(int minX, int maxX,
                int minZ, int maxZ) {
            int width = Math.max(0, maxX - minX + 1);
            int height = Math.max(0, maxZ - minZ + 1);
            int total = width * height;
            if (total == 0) return new long[0];

            /*
             * The coordinator shortlists the closest viewed regions rather than walking a
             * screen edge. Generate the same deterministic centre-out authority
             * without sorting or temporary distance arrays. Retained overlap is
             * still preserved by the caller for source ownership; satisfied centre
             * pages are skipped cheaply and newly exposed nearby pages are then
             * admitted before distant edges.
             */
            long[] result = new long[total];
            int centerX = minX + (maxX - minX) / 2;
            int centerZ = minZ + (maxZ - minZ) / 2;
            int maximumRadius = Math.max(
                    Math.max(centerX - minX, maxX - centerX),
                    Math.max(centerZ - minZ, maxZ - centerZ));
            int ordinal = 0;
            for (int radius = 0; radius <= maximumRadius; radius++) {
                int left = centerX - radius;
                int right = centerX + radius;
                int top = centerZ - radius;
                int bottom = centerZ + radius;
                if (top >= minZ && top <= maxZ) {
                    for (int x = Math.max(minX, left);
                            x <= Math.min(maxX, right); x++) {
                        result[ordinal] = pack(x, top);
                        ordinal++;
                    }
                }
                if (radius == 0) continue;
                for (int z = Math.max(minZ, top + 1);
                        z <= Math.min(maxZ, bottom - 1); z++) {
                    if (left >= minX && left <= maxX) {
                        result[ordinal] = pack(left, z);
                        ordinal++;
                    }
                    if (right != left && right >= minX && right <= maxX) {
                        result[ordinal] = pack(right, z);
                        ordinal++;
                    }
                }
                if (bottom >= minZ && bottom <= maxZ) {
                    for (int x = Math.max(minX, left);
                            x <= Math.min(maxX, right); x++) {
                        result[ordinal] = pack(x, bottom);
                        ordinal++;
                    }
                }
            }
            return result;
        }

        private static boolean rectanglesOverlap(int firstMinX, int firstMaxX,
                int firstMinZ, int firstMaxZ, int secondMinX, int secondMaxX,
                int secondMinZ, int secondMaxZ) {
            return firstMinX <= secondMaxX && firstMaxX >= secondMinX
                    && firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ;
        }
    }

    /** Covers the loaded chunk radius, page-alignment slack and one repair halo. */
    public static int minimapWorkingRadiusPages(int renderDistanceChunks) {
        int loadedRadiusChunks = Math.max(2, renderDistanceChunks + 3);
        int alignedPages = (loadedRadiusChunks + 3) / 4;
        return Math.max(2, Math.min(MINIMAP_MAX_RADIUS_PAGES,
                alignedPages + MINIMAP_HALO_PAGES));
    }

    /**
     * Fills a bounded minimap exact-leaf halo in deterministic centre-out order.
     * The visible rectangle is expanded by one page, then capped to the caller's
     * loaded-world working radius so extreme zoom cannot flood the exact pipeline.
     */
    public static int fillMinimapHalo(int visibleMinX, int visibleMaxX,
            int visibleMinZ, int visibleMaxZ, int centerX, int centerZ,
            long[] output) {
        return fillMinimapHalo(visibleMinX, visibleMaxX,
                visibleMinZ, visibleMaxZ, centerX, centerZ,
                MINIMAP_MAX_RADIUS_PAGES, output);
    }

    public static int fillMinimapHalo(int visibleMinX, int visibleMaxX,
            int visibleMinZ, int visibleMaxZ, int centerX, int centerZ,
            int maximumRadiusPages, long[] output) {
        if (output == null || output.length == 0) return 0;
        int safeRadius = Math.max(1,
                Math.min(MINIMAP_MAX_RADIUS_PAGES, maximumRadiusPages));
        int minX = Math.max(Math.min(visibleMinX, visibleMaxX) - MINIMAP_HALO_PAGES,
                centerX - safeRadius);
        int maxX = Math.min(Math.max(visibleMinX, visibleMaxX) + MINIMAP_HALO_PAGES,
                centerX + safeRadius);
        int minZ = Math.max(Math.min(visibleMinZ, visibleMaxZ) - MINIMAP_HALO_PAGES,
                centerZ - safeRadius);
        int maxZ = Math.min(Math.max(visibleMinZ, visibleMaxZ) + MINIMAP_HALO_PAGES,
                centerZ + safeRadius);
        int maximumRadius = Math.max(
                Math.max(Math.abs(centerX - minX), Math.abs(maxX - centerX)),
                Math.max(Math.abs(centerZ - minZ), Math.abs(maxZ - centerZ)));
        int count = 0;
        for (int radius = 0; radius <= maximumRadius && count < output.length;
                radius++) {
            int left = centerX - radius;
            int right = centerX + radius;
            int top = centerZ - radius;
            int bottom = centerZ + radius;
            if (top >= minZ && top <= maxZ) {
                for (int x = Math.max(minX, left);
                        x <= Math.min(maxX, right) && count < output.length; x++) {
                    output[count++] = pack(x, top);
                }
            }
            if (radius == 0) continue;
            for (int z = Math.max(minZ, top + 1);
                    z <= Math.min(maxZ, bottom - 1) && count < output.length; z++) {
                if (left >= minX && left <= maxX) output[count++] = pack(left, z);
                if (count >= output.length) break;
                if (right != left && right >= minX && right <= maxX) {
                    output[count++] = pack(right, z);
                }
            }
            if (bottom >= minZ && bottom <= maxZ) {
                for (int x = Math.max(minX, left);
                        x <= Math.min(maxX, right) && count < output.length; x++) {
                    output[count++] = pack(x, bottom);
                }
            }
        }
        return count;
    }

    /** Compatibility overload; production uses the allocation-free long buffer. */
    public static int fillMinimapHalo(int visibleMinX, int visibleMaxX,
            int visibleMinZ, int visibleMaxZ, int centerX, int centerZ,
            Page[] output) {
        if (output == null || output.length == 0) return 0;
        long[] packed = new long[output.length];
        int count = fillMinimapHalo(visibleMinX, visibleMaxX,
                visibleMinZ, visibleMaxZ, centerX, centerZ, packed);
        for (int index = 0; index < count; index++) {
            output[index] = new Page(packedX(packed[index]), packedZ(packed[index]), index);
        }
        return count;
    }

    static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    static int packedX(long packed) {
        return (int) (packed >> 32);
    }

    static int packedZ(long packed) {
        return (int) packed;
    }

    public record Page(int x, int z, int ordinal) {
        public int distanceSquaredTo(int focusX, int focusZ) {
            int dx = x - focusX;
            int dz = z - focusZ;
            return dx * dx + dz * dz;
        }
    }
}

}