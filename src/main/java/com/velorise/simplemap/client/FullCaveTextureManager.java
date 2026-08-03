package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;
import com.velorise.simplemap.client.gpu.TileKey;
import net.minecraft.resources.ResourceLocation;

/** Compatibility facade for Full Cave pages produced from the shared tile archive. */
public final class FullCaveTextureManager {
    private static final FullCaveTextureManager INSTANCE = new FullCaveTextureManager();
    private final UnifiedCaveTextureManager unified = UnifiedCaveTextureManager.getInstance();

    private FullCaveTextureManager() {
    }

    public static FullCaveTextureManager getInstance() {
        return INSTANCE;
    }

    public void markRegionTextureDirty(int rx, int rz) {
        unified.markRegionDirty(CaveView.FULL, Integer.MIN_VALUE, rx, rz);
    }

    public void requestVisiblePages(double minX, double maxX,
            double minZ, double maxZ) {
        requestVisiblePages(minX, maxX, minZ, maxZ, 1.0f);
    }

    public void requestVisiblePages(double minX, double maxX,
            double minZ, double maxZ, float scale) {
        requestVisiblePages(minX, maxX, minZ, maxZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public void requestVisiblePages(double minX, double maxX,
            double minZ, double maxZ, float scale, MapRequestLane lane) {
        requestVisiblePages(minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void requestVisiblePages(double minX, double maxX,
            double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        unified.requestVisiblePages(CaveView.FULL, Integer.MIN_VALUE,
                minX, maxX, minZ, maxZ, scale, focusX, focusZ, lane);
    }

    public void requestVisibleRegion(int rx, int rz) {
        unified.requestRegion(CaveView.FULL, Integer.MIN_VALUE, rx, rz);
    }

    public ResourceLocation getRegionTexture(int rx, int rz) {
        requestVisibleRegion(rx, rz);
        return null;
    }

    public long contentRevision() {
        return unified.contentRevision();
    }

    public long branchContentRevision() {
        return unified.branchContentRevision();
    }

    public void beginRenderBatch() {
        unified.beginRenderBatch();
    }

    public void endRenderBatch() {
        unified.endRenderBatch();
    }

    public CaveAtlasRegion peekPageRegion(int rx, int rz, int pageX,
            int pageZ, float scale) {
        return unified.peekPageRegion(CaveView.FULL, Integer.MIN_VALUE,
                rx, rz, pageX, pageZ, scale);
    }

    public TileKey pageTileKey(int globalPageX, int globalPageZ, float scale) {
        return unified.pageTileKey(CaveView.FULL, Integer.MIN_VALUE,
                globalPageX, globalPageZ, scale);
    }

    public CaveAtlasRegion peekBranchRegion(int rx, int rz) {
        return peekBranchRegion(1, rx, rz);
    }

    public CaveAtlasRegion peekBranchRegion(int level, int nodeX, int nodeZ) {
        return unified.peekBranchRegion(CaveView.FULL, Integer.MIN_VALUE,
                level, nodeX, nodeZ);
    }

    public boolean hasBranchData(int level, int nodeX, int nodeZ) {
        return unified.hasBranchData(CaveView.FULL, Integer.MIN_VALUE,
                level, nodeX, nodeZ);
    }

    public boolean hasResidentPageInNode(int level, int nodeX, int nodeZ) {
        return unified.hasResidentPageInNode(CaveView.FULL, Integer.MIN_VALUE,
                level, nodeX, nodeZ);
    }

    public boolean allowFullscreenExact(int globalPageX, int globalPageZ) {
        return unified.allowFullscreenExact(CaveView.FULL, Integer.MIN_VALUE,
                globalPageX, globalPageZ);
    }

    public ResourceLocation peekPageTexture(int rx, int rz, int pageX, int pageZ) {
        CaveAtlasRegion region = peekPageRegion(rx, rz, pageX, pageZ, 1.0f);
        return region == null ? null : region.texture();
    }

    public boolean hasAnyPageTexture(int rx, int rz) {
        return unified.hasAnyPage(CaveView.FULL, Integer.MIN_VALUE, rx, rz);
    }

    public ResourceLocation peekRegionTexture(int rx, int rz) {
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
    }
}
