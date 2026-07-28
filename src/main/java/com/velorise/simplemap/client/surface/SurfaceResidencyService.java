package com.velorise.simplemap.client.surface;

import com.velorise.simplemap.client.MapResidencyManager;
import com.velorise.simplemap.client.gpu.MapGpuPageTableService;
import com.velorise.simplemap.client.gpu.TileKey;

/** GPU residency/page-table lookup boundary for surface projections. */
public final class SurfaceResidencyService {
    private static final SurfaceResidencyService INSTANCE =
            new SurfaceResidencyService();
    private SurfaceResidencyService() { }
    public static SurfaceResidencyService getInstance() { return INSTANCE; }

    public MapGpuPageTableService.Resolved resolve(TileKey key) {
        return MapGpuPageTableService.getInstance().resolve(key);
    }

    public void remove(TileKey key) {
        MapGpuPageTableService.getInstance().remove(key);
    }

    public MapResidencyManager.Snapshot summary() {
        return MapResidencyManager.getInstance().snapshot();
    }
}
