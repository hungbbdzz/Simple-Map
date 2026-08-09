package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

/** Bounded coalescing policy for incremental Layered-Cave tile publication. */
final class CaveTilePublicationPolicy {
    static final int MIN_BATCH_TILES = 4;
    static final long MAX_HOLD_MS = 75L;
    /**
     * A cold fullscreen leaf is revealed as one 64x64 transaction. Source capture
     * may still resolve its sixteen Minecraft chunks independently, but exposing a
     * four-chunk subset is what made first-time cave coverage look like scattered
     * islands. This mirrors Xaero's MapTileChunk boundary: all 4x4 child chunks are
     * traversed before the leaf buffer is rebuilt and uploaded.
     */
    static final int FULLSCREEN_FIRST_BATCH_TILES = 16;
    /** Minimap keeps a smaller first batch because player-local latency dominates. */
    static final int MINIMAP_FIRST_BATCH_TILES = 6;
    static final long FIRST_MAX_HOLD_MS = 400L;
    static final long RETRY_MS = 8L;

    private CaveTilePublicationPolicy() { }

    static boolean shouldPublish(MapRequestLane lane, boolean initialized,
            boolean replacingProjection, int readyTiles, long firstReadyMs,
            long nowMs) {
        return shouldPublish(lane, initialized, replacingProjection, false,
                readyTiles, firstReadyMs, nowMs);
    }

    static boolean shouldPublish(MapRequestLane lane, boolean initialized,
            boolean replacingProjection, boolean leadingPage,
            int readyTiles, long firstReadyMs, long nowMs) {
        if (readyTiles <= 0) return false;
        if (replacingProjection) return true;
        if (!initialized) {
            int firstBatchTiles = lane == MapRequestLane.FULLSCREEN
                    ? FULLSCREEN_FIRST_BATCH_TILES : MINIMAP_FIRST_BATCH_TILES;
            if (readyTiles >= firstBatchTiles) return true;
            // A fullscreen timeout must not tear open the leading 64x64 leaf. The
            // source reader keeps progressing behind it and the branch texture stays
            // visible until the whole page is ready. Only the minimap may trade
            // coherence for bounded player-local latency.
            if (lane == MapRequestLane.FULLSCREEN) return false;
            return leadingPage && firstReadyMs > 0L
                    && nowMs - firstReadyMs >= FIRST_MAX_HOLD_MS;
        }
        /*
         * Do not upload a complete 64x64 page + every retained mip for each single
         * 16x16 child that arrives. That was the main PASS121 Cave GPU churn: an
         * initialized minimap page could be swapped up to sixteen times while one
         * source wave completed. Four-child coalescing caps the common case near
         * four uploads per page, while the 75 ms timeout keeps local edits/live
         * travel responsive. Fullscreen uses the same bounded batch after its
         * coherent cold-page transaction.
         */
        if (readyTiles >= MIN_BATCH_TILES) return true;
        return firstReadyMs > 0L && nowMs - firstReadyMs >= MAX_HOLD_MS;
    }

    /**
     * Returns the largest four-neighbour component in one 4x4 page mask. A cold
     * page publishes this component first, never an arbitrary set of isolated
     * worker completions. Existing pages may still patch every ready tile.
     */
    static int largestConnectedMask(int readyMask) {
        int remaining = readyMask & 0xFFFF;
        int bestMask = 0;
        while (remaining != 0) {
            int seed = Integer.numberOfTrailingZeros(remaining);
            int component = 0;
            int frontier = 1 << seed;
            while (frontier != 0) {
                int bit = frontier & -frontier;
                frontier &= ~bit;
                if ((component & bit) != 0) continue;
                component |= bit;
                int tile = Integer.numberOfTrailingZeros(bit);
                int x = tile & 3;
                int z = tile >>> 2;
                int neighbours = 0;
                if (x > 0) neighbours |= bit >>> 1;
                if (x < 3) neighbours |= bit << 1;
                if (z > 0) neighbours |= bit >>> 4;
                if (z < 3) neighbours |= bit << 4;
                frontier |= neighbours & readyMask & ~component;
            }
            remaining &= ~component;
            int componentSize = Integer.bitCount(component);
            int bestSize = Integer.bitCount(bestMask);
            if (componentSize > bestSize
                    || (componentSize == bestSize
                            && Integer.numberOfTrailingZeros(component)
                                    < Integer.numberOfTrailingZeros(bestMask))) {
                bestMask = component;
            }
        }
        return bestMask;
    }
}
