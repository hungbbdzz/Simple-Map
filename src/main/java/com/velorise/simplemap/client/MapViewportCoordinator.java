package com.velorise.simplemap.client;

import com.velorise.simplemap.client.surface.SurfaceDemandController;
import com.velorise.simplemap.client.cave.v2.CaveProjectionController;
import com.velorise.simplemap.client.cave.CaveWorldSaveReader;
import com.velorise.simplemap.client.cave.CaveScreenSpacePolicy;
import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;
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
    private long lastFullscreenRun;
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
    // Background publication repairs/widens the retained surface cache. It must
    // not compete one-for-one with the visible minimap request every client tick.
    private static final long BACKGROUND_SURFACE_INTERVAL_NANOS = 250_000_000L;
    private static final int BACKGROUND_SURFACE_MIN_RADIUS = 96;
    private static final int BACKGROUND_SURFACE_MAX_RADIUS = 256;
    private LayerStreamState layerStream = new LayerStreamState();

    private MapViewportCoordinator() {
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
        double clampedFocusX = clamp(focusX, minX, maxX);
        double clampedFocusZ = clamp(focusZ, minZ, maxZ);
        Request previousFullscreen = fullscreenRequest;
        boolean openingFullscreen = previousFullscreen == null;
        long planningKey = planningKey(minX, maxX, minZ, maxZ, scale);
        if (previousFullscreen == null || previousFullscreen.planningKey != planningKey) {
            MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        }
        fullscreenRequest = new Request(minX, maxX, minZ, maxZ, scale,
                clampedFocusX, clampedFocusZ, interacting,
                MapRequestLane.FULLSCREEN, System.nanoTime(), planningKey);
        MapPipelineTelemetry.getInstance().recordViewportRequest(MapRequestLane.FULLSCREEN);
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        governor.setFullscreenState(true, interacting);
        governor.setFocus(clampedFocusX, clampedFocusZ);
        if (openingFullscreen) {
            UnifiedCaveTextureManager.getInstance().suspendLane(MapRequestLane.MINIMAP);
            CaveWorldSaveReader.getInstance().suspendLane(MapRequestLane.MINIMAP);
        }
    }

    public void submitMinimap(double minX, double maxX, double minZ, double maxZ,
            float scale) {
        double focusX = (minX + maxX) * 0.5;
        double focusZ = (minZ + maxZ) * 0.5;
        Request previousMinimap = minimapRequest;
        long planningKey = planningKey(minX, maxX, minZ, maxZ, scale);
        if (previousMinimap == null || previousMinimap.planningKey != planningKey) {
            MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        }
        minimapRequest = new Request(minX, maxX, minZ, maxZ, scale,
                focusX, focusZ, false, MapRequestLane.MINIMAP,
                System.nanoTime(), planningKey);
        MapPipelineTelemetry.getInstance().recordViewportRequest(MapRequestLane.MINIMAP);
        if (!MapPerformanceGovernor.getInstance().isFullscreenOpen()) {
            MapPerformanceGovernor.getInstance().setFocus(
                    (minX + maxX) * 0.5, (minZ + maxZ) * 0.5);
        }
    }

    public void closeFullscreen() {
        fullscreenRequest = null;
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        layerStream = new LayerStreamState();
        MapPerformanceGovernor.getInstance().setFullscreenState(false, false);
    }

    /** Drops only selected-Y traversal state while retaining warm GPU textures. */
    public void onLayerChanged() {
        layerStream = new LayerStreamState();
        MapWorkScheduler.bumpViewport(MapRequestLane.FULLSCREEN);
        MapWorkScheduler.bumpViewport(MapRequestLane.MINIMAP);
        lastLayerUploadRun = 0L;
    }

    public void reset() {
        fullscreenRequest = null;
        minimapRequest = null;
        lastFullscreenRun = 0L;
        lastMinimapRun = 0L;
        lastLayerUploadRun = 0L;
        lastAdjacentWarmupRun = 0L;
        lastBackgroundSurfaceRun = 0L;
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
        if (profile == null) profile = MapPerformanceGovernor.getInstance()
                .observationProfile(minecraft);
        MapPublicationCoordinator publication = MapPublicationCoordinator.getInstance();
        publication.beginTick();
        long now = System.nanoTime();
        Request fullscreen = fullscreenRequest;
        boolean fullscreenVisible = fullscreen != null
                && now - fullscreen.submittedNanos < 500_000_000L;
        // The minimap is not visible while MapScreen owns the screen. Xaero keeps
        // its writer alive near the player but does not spend fullscreen leaf/cache
        // admission on a hidden minimap render lane. CPU live observation continues
        // elsewhere; exact cave IO and GPU publication belong to the visible map.
        Request minimap = minimapRequest;
        if (!fullscreenVisible && minimap != null
                && now - minimap.submittedNanos < 500_000_000L
                && now - lastMinimapRun >= profile.minimapIntervalNanos()) {
            lastMinimapRun = now;
            // Minimap always represents the player's live level.
            if (MapManager.getInstance().isViewingLiveDimension()) {
                if (profile.allowVisibleScan()) {
                    ChunkScanner.getInstance().scanVisibleArea(minecraft,
                            minimap.minX, minimap.maxX,
                            minimap.minZ, minimap.maxZ, minimap.scale,
                            minimap.focusX, minimap.focusZ, minimap.lane);
                }
                if (profile.allowSavedVisible()) {
                    long savedStarted = System.nanoTime();
                    requestTextures(minecraft, minimap);
                    MapObservationTelemetry.getInstance().record(
                            MapObservationTelemetry.Lane.SAVED_VISIBLE,
                            System.nanoTime() - savedStarted, 1);
                }
                boolean cave = CaveMode.isActive(minecraft);
                boolean full = cave && CaveMode.isFullView(minecraft);
                if (full) {
                    publication.requestFullCave(false);
                } else if (cave) {
                    publication.requestLayeredCave();
                } else {
                    // requestTextures() already admitted the minimap exact hot set.
                    // Only publish intent here; a second request pass used to repeat
                    // the same center-out traversal every minimap tick.
                    publication.requestSurface(MapRequestLane.MINIMAP, false);
                }
            }
        }


        if (fullscreenVisible
                && now - lastFullscreenRun >= profile.fullscreenIntervalNanos()) {
            lastFullscreenRun = now;

            // A static dimension has no live ClientLevel to scan. Only saved cache
            // files are streamed; this prevents Overworld blocks being written into
            // a Nether/End/modded-dimension map while browsing it remotely.
            if (profile.allowVisibleScan()
                    && MapManager.getInstance().isViewingLiveDimension()) {
                // Full Map load order is viewport-owned, never cursor-owned.
                // The mouse remains available to UI inspection only.
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
            }
            boolean cave = CaveMode.isActive(minecraft);
            boolean full = cave && CaveMode.isFullView(minecraft);
            boolean focus = MapConfig.fastFullscreenLoading && fullscreen.scale >= 0.55f;
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
     * Keeps the player-centred surface hot set current even when the minimap is
     * hidden or its scale selects only a high LOD. This is deliberately small: it
     * publishes only already-scanned dirty regions and never expands the gameplay
     * chunk load radius.
     */
    private void tickLiveSurfaceBackground(Minecraft minecraft, long now, Request fullscreen) {
        if (!MapManager.getInstance().isViewingLiveDimension()) return;
        if (CaveMode.isActive(minecraft)) return;
        if (fullscreen != null && now - fullscreen.submittedNanos < 500_000_000L) return;
        if (now - lastBackgroundSurfaceRun < BACKGROUND_SURFACE_INTERVAL_NANOS) return;
        lastBackgroundSurfaceRun = now;

        int renderDistance = minecraft.options.renderDistance().get();
        int radius = Math.max(BACKGROUND_SURFACE_MIN_RADIUS,
                Math.min(BACKGROUND_SURFACE_MAX_RADIUS, (renderDistance + 1) * 16));
        double centerX = minecraft.player.getX();
        double centerZ = minecraft.player.getZ();
        double minX = centerX - radius;
        double maxX = centerX + radius;
        double minZ = centerZ - radius;
        double maxZ = centerZ + radius;

        SurfaceDemandController.getInstance().submit(
                new SurfaceDemandController.Request(minX, maxX, minZ, maxZ,
                        centerX, centerZ, 1.0f,
                        MapRequestLane.BACKGROUND, false));
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
                || (fullscreen != null
                        && now - fullscreen.submittedNanos < 500_000_000L)
                || !MapManager.getInstance().isViewingLiveDimension()) return;
        if (now - lastAdjacentWarmupRun < 500_000_000L) return;
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
        int activeTopY = CaveMode.getLayerY(minecraft);
        int bandY = com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                com.velorise.simplemap.client.cave.CaveView.LAYERED, activeTopY);
        com.velorise.simplemap.client.cave.CavePipeline pipeline =
                com.velorise.simplemap.client.cave.CavePipeline.getInstance();
        long warmupStarted = System.nanoTime();
        pipeline.warmLayerBand(bandY - 8,
                centerX - radius, centerX + radius,
                centerZ - radius, centerZ + radius, 0.25f);
        pipeline.warmLayerBand(bandY + 24,
                centerX - radius, centerX + radius,
                centerZ - radius, centerZ + radius, 0.25f);
        MapObservationTelemetry.getInstance().record(
                MapObservationTelemetry.Lane.LAYER_WARMUP,
                System.nanoTime() - warmupStarted, 2);
    }

    private void requestTextures(Minecraft minecraft, Request request) {
        boolean cave = CaveMode.isActive(minecraft);
        boolean full = cave && CaveMode.isFullView(minecraft);
        int layerY = cave ? CaveMode.getLayerY(minecraft) : Integer.MIN_VALUE;

        if (cave) {
            // Cave page projection is much heavier than Xaero's cached leaf load.
            // Far zoom remains branch-rendered, but the screen-space policy admits
            // one slow coherent leaf seed so a cold viewport can build LOD instead
            // of staying black forever.
            // Minimap is camera/player-centred. Fullscreen uses a stable
            // viewport-owned traversal; cursor movement never changes admission.
            double schedulingFocusX = (request.minX + request.maxX) * 0.5;
            double schedulingFocusZ = (request.minZ + request.maxZ) * 0.5;
            if (!full) CaveMapManager.getInstance().setActiveLayer(layerY);
            CaveProjectionController.getInstance().request(
                    full ? com.velorise.simplemap.client.cave.CaveView.FULL
                            : com.velorise.simplemap.client.cave.CaveView.LAYERED,
                    full ? Integer.MIN_VALUE : layerY,
                    request.minX, request.maxX, request.minZ, request.maxZ,
                    request.scale, schedulingFocusX, schedulingFocusZ,
                    request.lane);
            return;
        }

        if (!MapManager.getInstance().isViewingLiveDimension()) {
            // Exact-page demand is the sole read authority. When the selected page
            // reaches publication, MapTextureManager requests its containing saved
            // region; a separate region streamer would create a second, conflicting
            // load order and reproduce the scattered behaviour V17 removes.
            SurfaceDemandController.getInstance().submit(
                    new SurfaceDemandController.Request(
                            request.minX, request.maxX,
                            request.minZ, request.maxZ,
                            (request.minX + request.maxX) * 0.5,
                            (request.minZ + request.maxZ) * 0.5,
                            request.scale, request.lane,
                            request.lane == MapRequestLane.FULLSCREEN));
            return;
        }

        // Exact leaves are the sole surface authority. At far zoom the manager
        // admits only a bounded attention hot set; recursive branches cover the
        // remainder without constructing legacy 512x512 overview textures.
        SurfaceDemandController.getInstance().submit(
                new SurfaceDemandController.Request(
                        request.minX, request.maxX,
                        request.minZ, request.maxZ,
                        (request.minX + request.maxX) * 0.5,
                        (request.minZ + request.maxZ) * 0.5,
                        request.scale, request.lane,
                        request.lane == MapRequestLane.FULLSCREEN));
        // Surface demand never starts cave-world reconstruction. Cave source data is
        // captured by the bounded archive writer and requested only when a cave
        // projection is actually visible.
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
                && System.nanoTime() - layerStream.completedAtNanos >= 4_000_000_000L) {
            // A slow maintenance pass catches previously saturated IO or newly
            // written archive data without producing a visible centre-out wave
            // every 750 ms over an already cached selected-Y layer.
            layerStream.cursor = 0;
        }

        int budget = layerRegionBudget(request.scale);
        CaveMapManager layers = CaveMapManager.getInstance();
        VerticalCaveArchiveManager archive = VerticalCaveArchiveManager.getInstance();
        CaveTextureManager textures = CaveTextureManager.getInstance();
        int processed = 0;
        while (processed < budget && layerStream.cursor < layerStream.regions.size()) {
            int[] region = layerStream.regions.get(layerStream.cursor++);
            int rx = region[0];
            int rz = region[1];
            processed++;

            textures.requestVisibleRegion(layerY, rx, rz);
            boolean warmExactTexture = layers.hasRegionFile(rx, rz)
                    && textures.peekRegionTexture(layerY, rx, rz) != null;
            // A warm exact-layer GPU texture is already the requested result. Keep it
            // visible without reloading/reprojecting the same file after every Top-Y
            // switch. Missing regions still stream in square region rings.
            if (!warmExactTexture && (layers.hasRegionFile(rx, rz)
                    || layers.isRegionLoaded(rx, rz) || archive.hasRegionData(rx, rz))) {
                int dx = rx - layerStream.centerRx;
                int dz = rz - layerStream.centerRz;
                int priority = 250_000 - (dx * dx + dz * dz) * 100;
                MapProcessor.getInstance().enqueueCaveLoad(
                        layerY, rx, rz, Math.max(1, priority));
            }
        }
        if (layerStream.cursor >= layerStream.regions.size()) {
            layerStream.completedAtNanos = System.nanoTime();
        }
    }

    private static int layerRegionBudget(float scale) {
        // Each exact layer region may project 262,144 columns/pixels and upload a
        // 512x512 texture. Keep far zoom intentionally conservative.
        if (scale < 0.12f) return 1;
        if (scale < 0.20f) return 1;
        if (scale < 0.35f) return 2;
        if (scale < 0.55f) return 4;
        return 8;
    }

    private static long layerUploadIntervalNanos(float scale) {
        if (scale < 0.12f) return 140_000_000L;
        if (scale < 0.20f) return 100_000_000L;
        if (scale < 0.35f) return 70_000_000L;
        return 50_000_000L;
    }

    private record Request(double minX, double maxX, double minZ, double maxZ,
            float scale, double focusX, double focusZ,
            boolean interacting, MapRequestLane lane, long submittedNanos,
            long planningKey) {
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
            List<int[]> regions = new ArrayList<>();
            for (int rx = minRx; rx <= maxRx; rx++) {
                for (int rz = minRz; rz <= maxRz; rz++) {
                    regions.add(new int[] { rx, rz });
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
                    && this.minRz == minRz && this.maxRz == maxRz
                    && this.centerRx == focusRx && this.centerRz == focusRz;
        }
    }
}
