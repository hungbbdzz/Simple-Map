package com.velorise.simplemap.client.cave.v2;

import com.velorise.simplemap.client.MapRequestLane;
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
        UnifiedCaveTextureManager.getInstance().requestVisiblePages(view, layerY,
                minX, maxX, minZ, maxZ, scale, focusX, focusZ, lane);
        int chunkX = ((int) Math.floor(focusX)) >> 4;
        int chunkZ = ((int) Math.floor(focusZ)) >> 4;
        if (view == CaveView.FULL) {
            CaveProjectionServiceV2.getInstance().fullSummary(chunkX, chunkZ);
        } else {
            CaveProjectionServiceV2.getInstance().layered(chunkX, chunkZ,
                    layerY, 0L);
        }
    }
}
