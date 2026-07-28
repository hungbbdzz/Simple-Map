package com.velorise.simplemap.client;

/**
 * Converts the physical fullscreen viewport into a work-admission viewport made
 * only from complete 64-block exact leaves.
 *
 * <p>Resident textures are still rendered against the real viewport, including
 * partially visible edge leaves. Only new CPU, disk, projection and exact-page
 * work excludes the incomplete outer ring. Minimap and background lanes keep
 * their original bounds.</p>
 */
public final class MapViewportDemandPolicy {
    private static final double LEAF_SIZE = MapPageLayout.PAGE_SIZE;

    private MapViewportDemandPolicy() {
    }

    public static Bounds trimEdgeSlivers(double minX, double maxX,
            double minZ, double maxZ, MapRequestLane lane) {
        double safeMinX = Math.min(minX, maxX);
        double safeMaxX = Math.max(minX, maxX);
        double safeMinZ = Math.min(minZ, maxZ);
        double safeMaxZ = Math.max(minZ, maxZ);
        if (lane != MapRequestLane.FULLSCREEN) {
            return new Bounds(safeMinX, safeMaxX, safeMinZ, safeMaxZ);
        }

        Axis x = completeLeaves(safeMinX, safeMaxX);
        Axis z = completeLeaves(safeMinZ, safeMaxZ);
        return new Bounds(x.minimum(), x.maximum(), z.minimum(), z.maximum());
    }

    /**
     * Returns exclusive aligned bounds containing only leaves fully enclosed by
     * the physical viewport. Very narrow views fall back to their original axis
     * so high zoom can never suppress all demand.
     */
    private static Axis completeLeaves(double minimum, double maximum) {
        double alignedMinimum = Math.ceil(minimum / LEAF_SIZE) * LEAF_SIZE;
        double alignedMaximum = Math.floor(maximum / LEAF_SIZE) * LEAF_SIZE;
        if (alignedMaximum <= alignedMinimum) {
            return new Axis(minimum, maximum);
        }
        return new Axis(alignedMinimum, alignedMaximum);
    }

    private record Axis(double minimum, double maximum) {
    }

    public record Bounds(double minX, double maxX, double minZ, double maxZ) {
    }
}
