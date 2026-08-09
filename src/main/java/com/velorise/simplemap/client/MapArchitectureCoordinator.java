package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.projection.CaveProjectionServiceV2;
import com.velorise.simplemap.client.cave.v2.CaveCacheService;
import com.velorise.simplemap.client.gpu.MapGpuPageTableService;
import com.velorise.simplemap.client.gpu.MapUploadEngine;
import com.velorise.simplemap.client.minimap.MinimapService;
import com.velorise.simplemap.client.persistence.v2.MapPersistenceV2Service;
import com.velorise.simplemap.client.surface.SurfaceDemandController;
import com.velorise.simplemap.client.surface.SurfaceProjectionService;
import com.velorise.simplemap.client.surface.SurfaceSourceService;

/**
 * Cross-milestone lifecycle boundary for the V17.9–V18.5 architecture stack.
 * Subsystems remain independently testable and never infer session ownership
 * from a late callback.
 */
public final class MapArchitectureCoordinator {
    public record Summary(MapGpuPageTableService.Summary pageTable,
            MapUploadEngine.Summary uploads,
            MinimapService.Summary minimap,
            SurfaceRegionSourceDatabase.Snapshot surfaceSource,
            com.velorise.simplemap.client.lod.RegionLodGraph.Summary surfaceProjection,
            long surfaceRequests,
            CaveArchiveV2Service.Summary caveArchive,
            CaveProjectionServiceV2.Summary caveProjection,
            MapPersistenceV2Service.Summary persistence) { }

    private static final MapArchitectureCoordinator INSTANCE =
            new MapArchitectureCoordinator();
    private long lastFrameId = Long.MIN_VALUE;

    private MapArchitectureCoordinator() { }
    public static MapArchitectureCoordinator getInstance() { return INSTANCE; }

    public void onWorldJoin() {
        lastFrameId = Long.MIN_VALUE;
    }

    public void onFrameBoundary(long frameId) {
        if (frameId == lastFrameId) return;
        lastFrameId = frameId;
        // Publication order: upload payload, stage back table, then swap once at
        // a frame boundary. Compatibility owners still execute inline uploads.
        MapUploadEngine.getInstance().drain(
                System.nanoTime() + 1_000_000L, 2 * 1024 * 1024);
        MapGpuPageTableService.getInstance().swapAtFrameBoundary();
        MapTextureManager.getInstance().onPageTableFrameBoundary();
        MapOverviewTextureManager.getInstance().onPageTableFrameBoundary();
        com.velorise.simplemap.client.cave.UnifiedCaveTextureManager.getInstance()
                .onPageTableFrameBoundary();
    }

    public void onWorldLeave() {
        MinimapService.getInstance().clear();
        SurfaceProjectionService.getInstance().clear();
        SurfaceSourceService.getInstance().clear();
        CaveCacheService.getInstance().clear();
        MapUploadEngine.getInstance().clear();
        MapGpuPageTableService.getInstance().clear();
        lastFrameId = Long.MIN_VALUE;
    }

    public Summary summary() {
        return new Summary(
                MapGpuPageTableService.getInstance().summary(),
                MapUploadEngine.getInstance().summary(),
                MinimapService.getInstance().summary(),
                SurfaceSourceService.getInstance().snapshot(),
                SurfaceProjectionService.getInstance().summary(),
                SurfaceDemandController.getInstance().submitted(),
                CaveArchiveV2Service.getInstance().summary(),
                CaveProjectionServiceV2.getInstance().summary(),
                MapPersistenceV2Service.getInstance().summary());
    }
}
