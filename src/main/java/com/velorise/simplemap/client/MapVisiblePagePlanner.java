package com.velorise.simplemap.client;

/**
 * Allocation-free centre-out page frontier used for large surface viewports.
 *
 * <p>The attention hot set is requested every pass. This planner contributes a
 * very small rotating frontier so cold pages outside that hot set eventually
 * seed the LOD hierarchy instead of being permanently omitted.</p>
 */
final class MapVisiblePagePlanner {
    private static final long REPAIR_INTERVAL_NANOS = 1_500_000_000L;

    private String dimension = "";
    private int minX;
    private int maxX;
    private int minZ;
    private int maxZ;
    private int centerX;
    private int centerZ;
    private int radius;
    private int ringIndex;
    private int maximumRadius;
    private long completedAtNanos;
    private boolean configured;

    void configure(String dimension, int minX, int maxX, int minZ, int maxZ,
            int centerX, int centerZ) {
        String safeDimension = dimension == null ? "" : dimension;
        if (configured && this.dimension.equals(safeDimension)
                && this.minX == minX && this.maxX == maxX
                && this.minZ == minZ && this.maxZ == maxZ
                && this.centerX == centerX && this.centerZ == centerZ) return;
        this.dimension = safeDimension;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.maximumRadius = Math.max(
                Math.max(Math.abs(centerX - minX), Math.abs(maxX - centerX)),
                Math.max(Math.abs(centerZ - minZ), Math.abs(maxZ - centerZ)));
        this.radius = 0;
        this.ringIndex = 0;
        this.completedAtNanos = 0L;
        this.configured = true;
    }

    Page next(long nowNanos) {
        if (!configured) return null;
        if (radius > maximumRadius) {
            if (completedAtNanos == 0L) completedAtNanos = nowNanos;
            if (nowNanos - completedAtNanos < REPAIR_INTERVAL_NANOS) return null;
            radius = 0;
            ringIndex = 0;
            completedAtNanos = 0L;
        }
        while (radius <= maximumRadius) {
            int side = radius * 2 + 1;
            int cellCount = side * side;
            while (ringIndex < cellCount) {
                int index = ringIndex++;
                int localX = index % side - radius;
                int localZ = index / side - radius;
                if (Math.max(Math.abs(localX), Math.abs(localZ)) != radius) continue;
                int pageX = centerX + localX;
                int pageZ = centerZ + localZ;
                if (pageX < minX || pageX > maxX || pageZ < minZ || pageZ > maxZ) continue;
                return new Page(pageX, pageZ);
            }
            radius++;
            ringIndex = 0;
        }
        completedAtNanos = nowNanos;
        return null;
    }

    record Page(int x, int z) {
    }
}
