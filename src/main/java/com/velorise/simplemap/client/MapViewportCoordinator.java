package com.velorise.simplemap.client;

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
    private LayerStreamState layerStream = new LayerStreamState();
    private LayerStreamState surfaceStream = new LayerStreamState();

    private MapViewportCoordinator() {
    }

    public static MapViewportCoordinator getInstance() {
        return INSTANCE;
    }

    public void submitFullscreen(double minX, double maxX, double minZ, double maxZ,
            float scale, boolean interacting) {
        fullscreenRequest = new Request(minX, maxX, minZ, maxZ, scale,
                interacting, System.nanoTime());
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        governor.setFullscreenState(true, interacting);
        governor.setFocus((minX + maxX) * 0.5, (minZ + maxZ) * 0.5);
    }

    public void submitMinimap(double minX, double maxX, double minZ, double maxZ,
            float scale) {
        minimapRequest = new Request(minX, maxX, minZ, maxZ, scale,
                false, System.nanoTime());
        if (!MapPerformanceGovernor.getInstance().isFullscreenOpen()) {
            MapPerformanceGovernor.getInstance().setFocus(
                    (minX + maxX) * 0.5, (minZ + maxZ) * 0.5);
        }
    }

    public void closeFullscreen() {
        fullscreenRequest = null;
        layerStream = new LayerStreamState();
        surfaceStream = new LayerStreamState();
        MapPerformanceGovernor.getInstance().setFullscreenState(false, false);
    }

    /** Drops only selected-Y traversal state while retaining warm GPU textures. */
    public void onLayerChanged() {
        layerStream = new LayerStreamState();
        lastLayerUploadRun = 0L;
    }

    public void reset() {
        fullscreenRequest = null;
        minimapRequest = null;
        lastFullscreenRun = 0L;
        lastMinimapRun = 0L;
        lastLayerUploadRun = 0L;
        layerStream = new LayerStreamState();
        surfaceStream = new LayerStreamState();
        MapPerformanceGovernor.getInstance().setFullscreenState(false, false);
    }

    public void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return;
        long now = System.nanoTime();
        Request fullscreen = fullscreenRequest;
        if (fullscreen != null && now - fullscreen.submittedNanos < 500_000_000L
                && !fullscreen.interacting && now - lastFullscreenRun >= 20_000_000L) {
            lastFullscreenRun = now;

            // A static dimension has no live ClientLevel to scan. Only saved cache
            // files are streamed; this prevents Overworld blocks being written into
            // a Nether/End/modded-dimension map while browsing it remotely.
            if (MapManager.getInstance().isViewingLiveDimension()) {
                ChunkScanner.getInstance().scanVisibleArea(minecraft,
                        fullscreen.minX, fullscreen.maxX,
                        fullscreen.minZ, fullscreen.maxZ, fullscreen.scale);
            }

            requestTextures(minecraft, fullscreen);
            boolean cave = CaveMode.isActive(minecraft);
            boolean full = cave && CaveMode.isFullView(minecraft);
            boolean focus = MapConfig.fastFullscreenLoading && fullscreen.scale >= 0.55f;
            if (full) {
                FullCaveTextureManager.getInstance().uploadDirtyTextures(focus);
                MapOverviewTextureManager.getInstance().uploadDirtyTextures(focus);
            } else if (cave) {
                // Exact 512x512 layer images stay sharp, but publication is paced by
                // zoom. Never use the forced upload path here: it can publish several
                // full-size images in one frame and recreate the far-zoom freeze.
                long uploadInterval = layerUploadIntervalNanos(fullscreen.scale);
                if (now - lastLayerUploadRun >= uploadInterval) {
                    lastLayerUploadRun = now;
                    CaveTextureManager.getInstance().uploadDirtyTextures(false);
                }
            } else {
                MapTextureManager.getInstance().uploadDirtyTextures(focus);
                MapOverviewTextureManager.getInstance().uploadDirtyTextures(focus);
            }
        }

        Request minimap = minimapRequest;
        if (minimap != null && now - minimap.submittedNanos < 500_000_000L
                && now - lastMinimapRun >= 100_000_000L) {
            lastMinimapRun = now;
            // Minimap always represents the player's live level.
            if (MapManager.getInstance().isViewingLiveDimension()) {
                ChunkScanner.getInstance().scanVisibleArea(minecraft,
                        minimap.minX, minimap.maxX,
                        minimap.minZ, minimap.maxZ, minimap.scale);
                requestTextures(minecraft, minimap);
                boolean cave = CaveMode.isActive(minecraft);
                boolean full = cave && CaveMode.isFullView(minecraft);
                if (full) {
                    FullCaveTextureManager.getInstance().uploadDirtyTextures(false);
                    MapOverviewTextureManager.getInstance().uploadDirtyTextures(false);
                } else if (cave) {
                    CaveTextureManager.getInstance().uploadDirtyTextures(false);
                } else {
                    MapTextureManager.getInstance().uploadDirtyTextures(false);
                    MapOverviewTextureManager.getInstance().uploadDirtyTextures(false);
                }
            }
        }
    }

    private void requestTextures(Minecraft minecraft, Request request) {
        boolean cave = CaveMode.isActive(minecraft);
        boolean full = cave && CaveMode.isFullView(minecraft);
        int layerY = cave ? CaveMode.getLayerY(minecraft) : Integer.MIN_VALUE;

        if (cave && !full) {
            CaveMapManager.getInstance().setActiveLayer(layerY);
            requestLayerRegions(request, layerY);
            return;
        }

        if (!full && !MapManager.getInstance().isViewingLiveDimension()) {
            // Static dimensions deliberately hide the old grey surface overview.
            // Stream exact saved regions center-out so a stationary far-zoom view
            // still resolves without requiring the user to zoom in and out.
            requestSurfaceRegions(request);
            return;
        }

        int mode = full ? MapOverviewTextureManager.MODE_FULL
                : MapOverviewTextureManager.MODE_SURFACE;
        int overviewStride = request.scale < 0.18f ? 8 : 4;
        MapOverviewTextureManager.getInstance().requestVisible(mode, 0,
                request.minX, request.maxX, request.minZ, request.maxZ,
                overviewStride);
        if (request.scale < 0.42f) return;

        if (full) {
            FullCaveTextureManager.getInstance().requestVisiblePages(
                    request.minX, request.maxX, request.minZ, request.maxZ);
        } else {
            MapTextureManager.getInstance().requestVisiblePages(
                    request.minX, request.maxX, request.minZ, request.maxZ);
        }
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

        if (!layerStream.matches(dimension, layerY, minRx, maxRx, minRz, maxRz)) {
            layerStream = LayerStreamState.create(dimension, layerY,
                    minRx, maxRx, minRz, maxRz);
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

    private void requestSurfaceRegions(Request request) {
        int minRx = (int) Math.floor(request.minX - 1.0) >> 9;
        int maxRx = (int) Math.floor(request.maxX + 1.0) >> 9;
        int minRz = (int) Math.floor(request.minZ - 1.0) >> 9;
        int maxRz = (int) Math.floor(request.maxZ + 1.0) >> 9;
        String dimension = MapManager.getInstance().getDimensionCacheKey();
        int sentinel = Integer.MIN_VALUE;

        if (!surfaceStream.matches(dimension, sentinel, minRx, maxRx, minRz, maxRz)) {
            surfaceStream = LayerStreamState.create(dimension, sentinel,
                    minRx, maxRx, minRz, maxRz);
        } else if (surfaceStream.cursor >= surfaceStream.regions.size()
                && System.nanoTime() - surfaceStream.completedAtNanos >= 750_000_000L) {
            surfaceStream.cursor = 0;
        }

        int budget = surfaceRegionBudget(request.scale);
        MapManager manager = MapManager.getInstance();
        MapTextureManager textures = MapTextureManager.getInstance();
        int processed = 0;
        while (processed < budget && surfaceStream.cursor < surfaceStream.regions.size()) {
            int[] region = surfaceStream.regions.get(surfaceStream.cursor++);
            int rx = region[0];
            int rz = region[1];
            processed++;
            textures.requestVisibleRegion(rx, rz);
            if (manager.hasRegionFile(rx, rz) || manager.isRegionLoadedInCache(rx, rz)) {
                int dx = rx - surfaceStream.centerRx;
                int dz = rz - surfaceStream.centerRz;
                int priority = 250_000 - (dx * dx + dz * dz) * 100;
                MapProcessor.getInstance().enqueueSurfaceLoad(rx, rz, Math.max(1, priority));
            }
        }
        if (surfaceStream.cursor >= surfaceStream.regions.size()) {
            surfaceStream.completedAtNanos = System.nanoTime();
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

    private static int surfaceRegionBudget(float scale) {
        // Surface disk loads are cheaper and can run faster than cave projection.
        if (scale < 0.12f) return 2;
        if (scale < 0.20f) return 4;
        if (scale < 0.35f) return 8;
        if (scale < 0.55f) return 16;
        return 32;
    }

    private static long layerUploadIntervalNanos(float scale) {
        if (scale < 0.12f) return 140_000_000L;
        if (scale < 0.20f) return 100_000_000L;
        if (scale < 0.35f) return 70_000_000L;
        return 50_000_000L;
    }

    private record Request(double minX, double maxX, double minZ, double maxZ,
            float scale, boolean interacting, long submittedNanos) {
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
                int minRx, int maxRx, int minRz, int maxRz) {
            LayerStreamState state = new LayerStreamState();
            state.dimension = dimension;
            state.layerY = layerY;
            state.minRx = minRx;
            state.maxRx = maxRx;
            state.minRz = minRz;
            state.maxRz = maxRz;
            state.centerRx = (minRx + maxRx) >> 1;
            state.centerRz = (minRz + maxRz) >> 1;
            List<int[]> regions = new ArrayList<>();
            for (int rz = minRz; rz <= maxRz; rz++) {
                for (int rx = minRx; rx <= maxRx; rx++) {
                    regions.add(new int[] { rx, rz });
                }
            }
            regions.sort(Comparator
                    .comparingInt((int[] pair) -> Math.max(
                            Math.abs(pair[0] - state.centerRx),
                            Math.abs(pair[1] - state.centerRz)))
                    .thenComparingInt(pair -> Math.abs(pair[0] - state.centerRx)
                            + Math.abs(pair[1] - state.centerRz))
                    .thenComparingInt(pair -> pair[1])
                    .thenComparingInt(pair -> pair[0]));
            state.regions = regions;
            return state;
        }

        private boolean matches(String dimension, int layerY,
                int minRx, int maxRx, int minRz, int maxRz) {
            return this.dimension.equals(dimension) && this.layerY == layerY
                    && this.minRx == minRx && this.maxRx == maxRx
                    && this.minRz == minRz && this.maxRz == maxRz;
        }
    }
}
