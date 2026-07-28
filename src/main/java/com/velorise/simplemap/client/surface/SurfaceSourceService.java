package com.velorise.simplemap.client.surface;

import com.velorise.simplemap.client.SurfaceRegionSourceDatabase;

/** Source-database facade extracted from the old all-purpose texture manager. */
public final class SurfaceSourceService {
    private static final SurfaceSourceService INSTANCE = new SurfaceSourceService();
    private SurfaceSourceService() { }
    public static SurfaceSourceService getInstance() { return INSTANCE; }

    public SurfaceRegionSourceDatabase.Snapshot snapshot() {
        return SurfaceRegionSourceDatabase.getInstance().snapshot();
    }

    public void markChunkDirty(int regionX, int regionZ, int localChunkIndex) {
        SurfaceRegionSourceDatabase.getInstance().markChunkDirty(
                regionX, regionZ, localChunkIndex);
    }

    public void clear() { SurfaceRegionSourceDatabase.getInstance().clear(); }
}
