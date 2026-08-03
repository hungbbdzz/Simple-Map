package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

/** Bounded coalescing policy for incremental Layered-Cave tile publication. */
final class CaveTilePublicationPolicy {
    static final int MIN_BATCH_TILES = 2;
    static final long MAX_HOLD_MS = 50L;
    static final long RETRY_MS = 8L;

    private CaveTilePublicationPolicy() { }

    static boolean shouldPublish(MapRequestLane lane, boolean initialized,
            boolean replacingProjection, int readyTiles, long firstReadyMs,
            long nowMs) {
        if (readyTiles <= 0) return false;
        if (!initialized || replacingProjection || lane == MapRequestLane.MINIMAP) return true;
        if (readyTiles >= MIN_BATCH_TILES) return true;
        return firstReadyMs > 0L && nowMs - firstReadyMs >= MAX_HOLD_MS;
    }
}
