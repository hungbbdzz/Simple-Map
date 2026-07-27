package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.DenseCaveTile;
import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;
import net.minecraft.resources.ResourceLocation;

/** Compatibility facade for the unified page-only cave texture pipeline. */
public final class CaveTextureManager {
    private static final CaveTextureManager INSTANCE = new CaveTextureManager();
    private final UnifiedCaveTextureManager unified = UnifiedCaveTextureManager.getInstance();
    private volatile int activeLayerY = Integer.MIN_VALUE;
    private volatile int previousLayerY = Integer.MIN_VALUE;

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
    }

    public void requestVisibleRegion(int layerY, int rx, int rz) {
        if (!sameBand(activeLayerY, layerY)) onLayerActivated(layerY);
        else activeLayerY = layerY;
        unified.requestRegion(CaveView.LAYERED, layerY, rx, rz);
    }

    public void onLayerActivated(int layerY) {
        if (activeLayerY != Integer.MIN_VALUE && !sameBand(activeLayerY, layerY)) {
            // Keep the previous band until the requested band has replacement pages.
            // Per-page lookup prefers the current band and falls back only where it is
            // still missing, so a slow world-save read never turns the map black.
            previousLayerY = activeLayerY;
        }
        activeLayerY = layerY;
    }

    public long contentRevision() {
        return unified.contentRevision();
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
        CaveAtlasRegion current = unified.peekPageRegion(CaveView.LAYERED, layerY,
                rx, rz, pageX, pageZ, scale);
        if (current != null || !previousFallbackActive()) return current;
        return unified.peekPageRegion(CaveView.LAYERED, previousLayerY,
                rx, rz, pageX, pageZ, scale);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int rx, int rz) {
        return peekBranchRegion(layerY, 1, rx, rz);
    }

    public CaveAtlasRegion peekBranchRegion(int layerY, int level,
            int nodeX, int nodeZ) {
        if (!sameBand(activeLayerY, layerY)) return null;
        CaveAtlasRegion current = unified.peekBranchRegion(CaveView.LAYERED, layerY,
                level, nodeX, nodeZ);
        if (current != null || !previousFallbackActive()) return current;
        return unified.peekBranchRegion(CaveView.LAYERED, previousLayerY,
                level, nodeX, nodeZ);
    }

    public boolean hasBranchData(int layerY, int level, int nodeX, int nodeZ) {
        if (!sameBand(activeLayerY, layerY)) return false;
        return unified.hasBranchData(CaveView.LAYERED, layerY, level, nodeX, nodeZ)
                || (previousFallbackActive() && unified.hasBranchData(
                        CaveView.LAYERED, previousLayerY, level, nodeX, nodeZ));
    }

    public boolean hasResidentPageInNode(int layerY, int level,
            int nodeX, int nodeZ) {
        if (!sameBand(activeLayerY, layerY)) return false;
        return unified.hasResidentPageInNode(CaveView.LAYERED, layerY,
                level, nodeX, nodeZ)
                || (previousFallbackActive() && unified.hasResidentPageInNode(
                        CaveView.LAYERED, previousLayerY, level, nodeX, nodeZ));
    }

    public boolean allowFullscreenExact(int layerY,
            int globalPageX, int globalPageZ) {
        if (!sameBand(activeLayerY, layerY)) return false;
        return unified.allowFullscreenExact(CaveView.LAYERED, layerY,
                globalPageX, globalPageZ);
    }

    public ResourceLocation peekPageTexture(int layerY, int rx, int rz,
            int pageX, int pageZ) {
        CaveAtlasRegion region = peekPageRegion(layerY, rx, rz, pageX, pageZ, 1.0f);
        return region == null ? null : region.texture();
    }

    public boolean hasAnyPageTexture(int layerY, int rx, int rz) {
        if (!sameBand(activeLayerY, layerY)) return false;
        return unified.hasAnyPage(CaveView.LAYERED, layerY, rx, rz)
                || (previousFallbackActive() && unified.hasAnyPage(
                        CaveView.LAYERED, previousLayerY, rx, rz));
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
        unified.upload(force);
    }

    public void invalidateStyle() {
        unified.invalidateStyle();
    }

    public void clearCache() {
        unified.clear();
        activeLayerY = Integer.MIN_VALUE;
        previousLayerY = Integer.MIN_VALUE;
    }

    private boolean previousFallbackActive() {
        return previousLayerY != Integer.MIN_VALUE
                && !sameBand(previousLayerY, activeLayerY);
    }

    private static boolean sameBand(int firstTopY, int secondTopY) {
        if (firstTopY == Integer.MIN_VALUE || secondTopY == Integer.MIN_VALUE) {
            return firstTopY == secondTopY;
        }
        return DenseCaveTile.normalizeLayer(CaveView.LAYERED, firstTopY)
                == DenseCaveTile.normalizeLayer(CaveView.LAYERED, secondTopY);
    }
}
