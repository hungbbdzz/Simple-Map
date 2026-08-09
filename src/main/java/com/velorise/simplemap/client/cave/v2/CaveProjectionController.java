package com.velorise.simplemap.client.cave.v2;

import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.CaveTextureManager;
import com.velorise.simplemap.client.cave.projection.CaveProjectionServiceV2;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;

/** M11 request boundary shared by Layered and Full Cave projections. */
public final class CaveProjectionController {
    private static final CaveProjectionController INSTANCE =
            new CaveProjectionController();
    private CaveProjectionController() { }
    public static CaveProjectionController getInstance() { return INSTANCE; }

    public void request(CaveView view, int layerY,
            double minX, double maxX, double minZ, double maxZ,
            float scale, double focusX, double focusZ, MapRequestLane lane) {
        int projectionLayerY = layerY;
        if (view == CaveView.LAYERED) {
            // The facade owns Xaero-style target/writer/display separation. During
            // vertical movement the minimap keeps its stable writer layer instead
            // of starting one focus projection for every transient player Y.
            CaveTextureManager caveTextures = CaveTextureManager.getInstance();
            caveTextures.requestVisiblePages(layerY,
                    minX, maxX, minZ, maxZ, scale, focusX, focusZ, lane);
            projectionLayerY = caveTextures.projectionLayerForLane(layerY, lane);
        } else {
            UnifiedCaveTextureManager.getInstance().requestVisiblePages(view, layerY,
                    minX, maxX, minZ, maxZ, scale, focusX, focusZ, lane);
        }
        int chunkX = ((int) Math.floor(focusX)) >> 4;
        int chunkZ = ((int) Math.floor(focusZ)) >> 4;
        if (view == CaveView.FULL) {
            CaveProjectionServiceV2.getInstance().fullSummary(chunkX, chunkZ);
        } else {
            CaveProjectionServiceV2.getInstance().layered(chunkX, chunkZ,
                    projectionLayerY, 0L);
        }
    }
}
