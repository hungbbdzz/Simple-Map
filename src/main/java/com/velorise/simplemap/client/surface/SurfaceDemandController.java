package com.velorise.simplemap.client.surface;

import com.velorise.simplemap.client.MapOverviewTextureManager;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapSurfaceDemandPolicy;
import com.velorise.simplemap.client.MapTextureManager;
import com.velorise.simplemap.client.MapViewportDemandPolicy;

/**
 * M11 demand boundary. The viewport owns intent; this controller is the only
 * new-architecture entry point that converts it into exact surface demand.
 */
public final class SurfaceDemandController {
    public record Request(double minX, double maxX, double minZ, double maxZ,
            double focusX, double focusZ, float logicalScale,
            MapRequestLane lane, boolean trimFarFullscreen) { }

    private static final SurfaceDemandController INSTANCE =
            new SurfaceDemandController();
    private long submitted;

    private SurfaceDemandController() { }
    public static SurfaceDemandController getInstance() { return INSTANCE; }

    public void submit(Request request) {
        if (request == null || request.lane() == null) return;
        double minX = request.minX();
        double maxX = request.maxX();
        double minZ = request.minZ();
        double maxZ = request.maxZ();
        if (request.trimFarFullscreen()
                && request.lane() == MapRequestLane.FULLSCREEN) {
            MapSurfaceDemandPolicy.Bounds trimmed = MapSurfaceDemandPolicy.trim(
                    minX, maxX, minZ, maxZ, request.logicalScale());
            minX = trimmed.minX();
            maxX = trimmed.maxX();
            minZ = trimmed.minZ();
            maxZ = trimmed.maxZ();
        }
        MapViewportDemandPolicy.Bounds admitted =
                MapViewportDemandPolicy.trimEdgeSlivers(
                        minX, maxX, minZ, maxZ, request.lane());
        minX = admitted.minX();
        maxX = admitted.maxX();
        minZ = admitted.minZ();
        maxZ = admitted.maxZ();
        MapOverviewTextureManager.getInstance().setPreferredSurfaceScale(
                request.logicalScale());
        MapTextureManager.getInstance().requestVisiblePages(
                minX, maxX, minZ, maxZ,
                request.focusX(), request.focusZ(),
                Math.max(0.01f, request.logicalScale()), request.lane());
        synchronized (this) { submitted++; }
    }

    public synchronized long submitted() { return submitted; }
}
