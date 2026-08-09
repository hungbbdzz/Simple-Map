package com.velorise.simplemap.client.cave.v2;

import com.velorise.simplemap.client.cave.CaveChunkTile;
import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;
import com.velorise.simplemap.client.cave.projection.CaveProjectionServiceV2;

/** One compact source authority shared by Layered and Full Cave projections. */
public final class CaveCacheService {
    private static final CaveCacheService INSTANCE = new CaveCacheService();
    private CaveCacheService() { }
    public static CaveCacheService getInstance() { return INSTANCE; }

    public boolean ingest(CaveChunkTile.Snapshot snapshot) {
        return CaveArchiveV2Service.getInstance().ingest(snapshot);
    }

    public boolean ingest(CompactCaveTile compact) {
        return CaveArchiveV2Service.getInstance().ingest(compact);
    }

    public CompactCaveTile get(int chunkX, int chunkZ) {
        return CaveArchiveV2Service.getInstance().get(chunkX, chunkZ);
    }

    public void clear() {
        CaveProjectionServiceV2.getInstance().clear();
        CaveArchiveV2Service.getInstance().clear();
    }
}
