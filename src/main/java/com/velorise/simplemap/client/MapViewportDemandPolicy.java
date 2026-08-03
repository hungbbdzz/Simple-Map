package com.velorise.simplemap.client;

/**
 * Preserves the physical viewport as the work-admission viewport.
 *
 * <p>A 64x64 page is only a GPU atlas allocation. Surface publication is owned by
 * complete 16x16 chunk subtiles, so clipping demand to complete 64-block pages
 * incorrectly hid edge chunks and made zoom look locked to the page grid.</p>
 */
public final class MapViewportDemandPolicy {
    private MapViewportDemandPolicy() {
    }

    public static Bounds trimEdgeSlivers(double minX, double maxX,
            double minZ, double maxZ, MapRequestLane lane) {
        double safeMinX = Math.min(minX, maxX);
        double safeMaxX = Math.max(minX, maxX);
        double safeMinZ = Math.min(minZ, maxZ);
        double safeMaxZ = Math.max(minZ, maxZ);
        return new Bounds(safeMinX, safeMaxX, safeMinZ, safeMaxZ);
    }

    public record Bounds(double minX, double maxX, double minZ, double maxZ) {
    }
}
