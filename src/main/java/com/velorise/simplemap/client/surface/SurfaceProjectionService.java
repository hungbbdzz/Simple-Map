package com.velorise.simplemap.client.surface;

import com.velorise.simplemap.client.RegionSurfaceLodService;
import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.lod.RegionLodGraph;
import com.velorise.simplemap.client.pipeline.RevisionStamp;

/** Region-centric coarse projection facade for M4/M11. */
public final class SurfaceProjectionService {
    private static final SurfaceProjectionService INSTANCE =
            new SurfaceProjectionService();
    private SurfaceProjectionService() { }
    public static SurfaceProjectionService getInstance() { return INSTANCE; }

    public void setVisibleView(RevisionStamp stamp, float logicalScale,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int focusPageX, int focusPageZ) {
        RegionSurfaceLodService.getInstance().setVisibleView(stamp, logicalScale,
                minPageX, maxPageX, minPageZ, maxPageZ,
                focusPageX, focusPageZ);
    }

    public void publish(boolean focused, long deadlineNanos) {
        RegionSurfaceLodService.getInstance().publish(focused, deadlineNanos);
    }

    public CaveAtlasRegion peek(int level, int nodeX, int nodeZ) {
        return RegionSurfaceLodService.getInstance().peek(level, nodeX, nodeZ);
    }

    public RegionLodGraph.Summary summary() {
        return RegionSurfaceLodService.getInstance().summary();
    }

    public void clear() { RegionSurfaceLodService.getInstance().clear(); }
}
