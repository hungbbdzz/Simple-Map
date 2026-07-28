package com.velorise.simplemap.client;

/**
 * Dependency-light admission policy for region-centric surface transactions.
 *
 * <p>Cold foreground requests intentionally start as a single-leaf transaction.
 * Larger supertiles are admitted only after the focused leaf has source coverage
 * and nearby leaf demand proves that the extra capture/assembly work will be used.</p>
 */
public final class SurfaceBatchPolicy {
    private SurfaceBatchPolicy() {
    }

    public static int chooseBatchSize(MapRequestLane lane, boolean initialized,
            boolean sourceReady, int demandedInFourByFour) {
        MapRequestLane effective = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        if (effective == MapRequestLane.BACKGROUND
                || effective == MapRequestLane.PREFETCH) {
            // Archive reconstruction used to capture the complete 8x8 page
            // region on every retry. A cold region therefore walked 34x34
            // chunks (including the capture halo) even when none of them were
            // resident. Keep reconstruction cooperative on the live path and
            // widen it only after persisted source coverage is available.
            if (!sourceReady) return 1;
            int demand = Math.max(0, demandedInFourByFour);
            if (demand >= 6) return 4;
            return demand >= 2 ? 2 : 1;
        }
        if (!initialized && !sourceReady) return 1;
        int demand = Math.max(0, demandedInFourByFour);
        if (effective == MapRequestLane.MINIMAP) return demand >= 2 ? 2 : 1;
        if (demand >= 6) return 4;
        return demand >= 2 ? 2 : 1;
    }

    public static boolean shouldBuildPage(boolean requestedPage,
            boolean sourceReady, boolean hasDemand, MapRequestLane lane) {
        if (requestedPage) return true;
        MapRequestLane effective = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        boolean reconstructionLane = effective == MapRequestLane.BACKGROUND
                || effective == MapRequestLane.PREFETCH;
        return sourceReady && (hasDemand || reconstructionLane);
    }
}
