package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.DenseCaveTile;
import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;
import com.velorise.simplemap.client.gpu.TileKey;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.atomic.AtomicLong;

/** Compatibility facade for the unified page-only cave texture pipeline. */
public final class CaveTextureManager {
    private static final CaveTextureManager INSTANCE = new CaveTextureManager();
    private final UnifiedCaveTextureManager unified = UnifiedCaveTextureManager.getInstance();
    private final AtomicLong handoffRevision = new AtomicLong();
    private volatile int activeLayerY = Integer.MIN_VALUE;
    private volatile int previousLayerY = Integer.MIN_VALUE;
    private volatile boolean layerHandoffPending;
    private volatile long layerHandoffStartedMs;
    private volatile int layerHandoffReadyPasses;

    private static final int HANDOFF_RADIUS_PAGES = 1;
    private static final int HANDOFF_MIN_READY_PAGES = 5;
    private static final int HANDOFF_STABLE_PASSES = 2;
    private static final long HANDOFF_MAX_WAIT_MS = 2_000L;

    private CaveTextureManager() {
    }

    public static CaveTextureManager getInstance() {
        return INSTANCE;
    }

    public void markRegionTextureDirty(int layerY, int rx, int rz) {
        unified.markRegionDirty(CaveView.LAYERED, layerY, rx, rz);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, 1.0f);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale, MapRequestLane lane) {
        requestVisiblePages(layerY, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void requestVisiblePages(int layerY, double minX, double maxX,
            double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        if (!sameBand(activeLayerY, layerY)) onLayerActivated(layerY);
        else activeLayerY = layerY;
        unified.requestVisiblePages(CaveView.LAYERED, layerY,
                minX, maxX, minZ, maxZ, scale, focusX, focusZ, lane);
        observeLayerHandoff(layerY, minX, maxX, minZ, maxZ,
                focusX, focusZ, lane);
    }

    public void requestVisibleRegion(int layerY, int rx, int rz) {
        if (!sameBand(activeLayerY, layerY)) onLayerActivated(layerY);
        else activeLayerY = layerY;
        unified.requestRegion(CaveView.LAYERED, layerY, rx, rz);
    }

    public synchronized void onLayerActivated(int layerY) {
        if (sameBand(activeLayerY, layerY)) {
            activeLayerY = layerY;
            return;
        }

        // If B is still loading while A remains displayed and the user selects A
        // again, cancel the transition immediately. Otherwise the actually visible
        // layer (A), not the half-loaded B, becomes the fallback for C.
        int visibleLayer = layerHandoffPending
                && previousLayerY != Integer.MIN_VALUE
                        ? previousLayerY : activeLayerY;
        if (visibleLayer != Integer.MIN_VALUE && sameBand(visibleLayer, layerY)) {
            activeLayerY = layerY;
            previousLayerY = Integer.MIN_VALUE;
            layerHandoffPending = false;
            layerHandoffStartedMs = 0L;
            layerHandoffReadyPasses = 0;
            handoffRevision.incrementAndGet();
            return;
        }

        previousLayerY = visibleLayer;
        activeLayerY = layerY;
        layerHandoffPending = previousLayerY != Integer.MIN_VALUE;
        layerHandoffStartedMs = layerHandoffPending
                ? System.currentTimeMillis() : 0L;
        layerHandoffReadyPasses = 0;
        handoffRevision.incrementAndGet();
    }

    public long contentRevision() {
        return unified.contentRevision() + handoffRevision.get();
    }

    public long branchContentRevision() {
        return unified.branchContentRevision() + handoffRevision.get();
    }

    public void beginRenderBatch() {
        unified.beginRenderBatch();
    }

    public void endRenderBatch() {
        unified.endRenderBatch();
    }

    public CaveAtlasRegion peekPageRegion(int layerY, int rx, int rz,
            int pageX, int pageZ, float scale) {
        if (!sameBand(activeLayerY, layerY)) return null;
        int visibleLayer = visibleLayerY(layerY);
        return unified.peekPageRegion(CaveView.LAYERED, visibleLayer,
                rx, rz, pageX, pageZ, scale);
    }

    public TileKey pageTileKey(int layerY, int globalPageX,
            int globalPageZ, float scale) {
        if (!sameBand(activeLayerY, layerY)) return null;
        return unified.pageTileKey(CaveView.LAYERED, visibleLayerY(layerY),
                globalPageX, globalPageZ, scale);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int rx, int rz) {
        return peekBranchRegion(layerY, 1, rx, rz);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int level,
            int nodeX, int nodeZ) {
        if (!sameBand(activeLayerY, layerY)) return null;
        return unified.peekBranchRegion(CaveView.LAYERED, visibleLayerY(layerY),
                level, nodeX, nodeZ);
    }

    public boolean hasBranchData(int layerY, int level, int nodeX, int nodeZ) {
        if (!sameBand(activeLayerY, layerY)) return false;
        return unified.hasBranchData(CaveView.LAYERED, visibleLayerY(layerY),
                level, nodeX, nodeZ);
    }

    public boolean hasResidentPageInNode(int layerY, int level,
            int nodeX, int nodeZ) {
        if (!sameBand(activeLayerY, layerY)) return false;
        return unified.hasResidentPageInNode(CaveView.LAYERED,
                visibleLayerY(layerY), level, nodeX, nodeZ);
    }

    public boolean allowFullscreenExact(int layerY,
            int globalPageX, int globalPageZ) {
        if (!sameBand(activeLayerY, layerY)) return false;
        return unified.allowFullscreenExact(CaveView.LAYERED,
                visibleLayerY(layerY),
                globalPageX, globalPageZ);
    }

    public ResourceLocation peekPageTexture(int layerY, int rx, int rz,
            int pageX, int pageZ) {
        CaveAtlasRegion region = peekPageRegion(layerY, rx, rz, pageX, pageZ, 1.0f);
        return region == null ? null : region.texture();
    }

    public boolean hasAnyPageTexture(int layerY, int rx, int rz) {
        if (!sameBand(activeLayerY, layerY)) return false;
        return unified.hasAnyPage(CaveView.LAYERED,
                visibleLayerY(layerY), rx, rz);
    }

    /** Region textures were removed; exact 64x64 pages are the foreground unit. */
    public ResourceLocation getRegionTexture(int layerY, int rx, int rz) {
        requestVisibleRegion(layerY, rx, rz);
        return null;
    }

    public ResourceLocation peekRegionTexture(int layerY, int rx, int rz) {
        return null;
    }

    public void uploadDirtyTextures() {
        uploadDirtyTextures(false);
    }

    public void uploadDirtyTextures(boolean force) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        unified.upload(force);
    }

    public void invalidateStyle() {
        unified.invalidateStyle();
    }

    public void clearCache() {
        unified.clear();
        resetViewState();
    }

    /** Resets only the selected-layer handoff state. Dimension-qualified atlas
     * pages remain resident inside the unified cache. */
    public void suspendForDimensionSwitch() {
        resetViewState();
    }

    private void resetViewState() {
        activeLayerY = Integer.MIN_VALUE;
        previousLayerY = Integer.MIN_VALUE;
        layerHandoffPending = false;
        layerHandoffStartedMs = 0L;
        layerHandoffReadyPasses = 0;
        handoffRevision.incrementAndGet();
    }

    private int visibleLayerY(int requestedLayerY) {
        return layerHandoffPending && previousLayerY != Integer.MIN_VALUE
                ? previousLayerY : requestedLayerY;
    }

    private synchronized void observeLayerHandoff(int layerY,
            double minX, double maxX, double minZ, double maxZ,
            double focusX, double focusZ, MapRequestLane lane) {
        if (!layerHandoffPending || !sameBand(activeLayerY, layerY)) return;
        int minimumPageX = Math.floorDiv((int) Math.floor(Math.min(minX, maxX)),
                MapPageLayout.PAGE_SIZE);
        int maximumPageX = Math.floorDiv((int) Math.floor(Math.nextDown(
                Math.max(minX, maxX))), MapPageLayout.PAGE_SIZE);
        int minimumPageZ = Math.floorDiv((int) Math.floor(Math.min(minZ, maxZ)),
                MapPageLayout.PAGE_SIZE);
        int maximumPageZ = Math.floorDiv((int) Math.floor(Math.nextDown(
                Math.max(minZ, maxZ))), MapPageLayout.PAGE_SIZE);
        int centerPageX = Math.max(minimumPageX, Math.min(maximumPageX,
                Math.floorDiv((int) Math.floor(focusX), MapPageLayout.PAGE_SIZE)));
        int centerPageZ = Math.max(minimumPageZ, Math.min(maximumPageZ,
                Math.floorDiv((int) Math.floor(focusZ), MapPageLayout.PAGE_SIZE)));

        int resolved = 0;
        int target = 0;
        boolean centerResolved = false;
        int radius = lane == MapRequestLane.MINIMAP ? 0 : HANDOFF_RADIUS_PAGES;
        for (int dz = -radius; dz <= radius; dz++) {
            int pageZ = centerPageZ + dz;
            if (pageZ < minimumPageZ || pageZ > maximumPageZ) continue;
            for (int dx = -radius; dx <= radius; dx++) {
                int pageX = centerPageX + dx;
                if (pageX < minimumPageX || pageX > maximumPageX) continue;
                target++;
                boolean ready = unified.isPageProjectionResolved(
                        CaveView.LAYERED, layerY, pageX, pageZ);
                if (ready) resolved++;
                if (dx == 0 && dz == 0) centerResolved = ready;
            }
        }
        int required = lane == MapRequestLane.MINIMAP ? 1
                : Math.min(HANDOFF_MIN_READY_PAGES, target);
        if (centerResolved && resolved >= required) layerHandoffReadyPasses++;
        else layerHandoffReadyPasses = 0;

        long elapsed = System.currentTimeMillis() - layerHandoffStartedMs;
        if (layerHandoffReadyPasses >= HANDOFF_STABLE_PASSES
                || (centerResolved && elapsed >= HANDOFF_MAX_WAIT_MS)) {
            int loadedLayer = activeLayerY;
            int unloadedLayer = previousLayerY;
            previousLayerY = Integer.MIN_VALUE;
            layerHandoffPending = false;
            layerHandoffStartedMs = 0L;
            layerHandoffReadyPasses = 0;
            handoffRevision.incrementAndGet();
            MapDebugRecorder.getInstance().event("CAVE_LAYER_HANDOFF",
                    "previous_top_y=" + unloadedLayer
                            + " current_top_y=" + loadedLayer
                            + " center_page=" + centerPageX + ',' + centerPageZ
                            + " ready_pages=" + resolved + '/' + target);
        }
    }

    private static boolean sameBand(int firstTopY, int secondTopY) {
        if (firstTopY == Integer.MIN_VALUE || secondTopY == Integer.MIN_VALUE) {
            return firstTopY == secondTopY;
        }
        return DenseCaveTile.normalizeLayer(CaveView.LAYERED, firstTopY)
                == DenseCaveTile.normalizeLayer(CaveView.LAYERED, secondTopY);
    }
}
