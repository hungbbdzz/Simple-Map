package com.velorise.simplemap.client;

/**
 * Defines the rolling exact-surface working set for a zoom-selected viewport.
 *
 * <p>The whole viewport remains eligible. Work volume is controlled by the
 * bounded centre-out frontier and the selected LOD, not by cutting arbitrary
 * strips from the screen.</p>
 */
public final class MapSurfaceDemandPolicy {
    private static volatile Snapshot latest = Snapshot.identity();

    private MapSurfaceDemandPolicy() {
    }

    public static Bounds trim(double minX, double maxX,
            double minZ, double maxZ, float scale) {
        double safeMinX = Math.min(minX, maxX);
        double safeMaxX = Math.max(minX, maxX);
        double safeMinZ = Math.min(minZ, maxZ);
        double safeMaxZ = Math.max(minZ, maxZ);
        latest = new Snapshot(false, 1.0, 0.0, 0.0, 0.0,
                exactActiveWindow(scale, false));
        return new Bounds(safeMinX, safeMaxX, safeMinZ, safeMaxZ);
    }

    /**
     * Maximum number of concurrent fullscreen exact leaves. Branch publication
     * remains independent and therefore continues to fill the complete viewport.
     */
    public static int exactActiveWindow(float scale, boolean pressure) {
        return exactActiveWindow(scale, pressure, Integer.MAX_VALUE);
    }

    /**
     * Capacity-aware exact working set. At density-correct L1 far zoom, the
     * 512x512 Region LOD is authoritative full-viewport coverage and exact leaves
     * are refinement only. Keep that hot set small so exact work cannot starve the
     * coarse disk/Region-LOD frontier. Close zoom may use a larger exact window.
     */
    public static int exactActiveWindow(float scale, boolean pressure,
            int visiblePageCount) {
        int visible = Math.max(1, visiblePageCount);
        float safeScale = Float.isFinite(scale) && scale > 0.0f ? scale : 1.0f;
        float projectedChunkPixels = safeScale * MapPageLayout.SUBTILE_SIZE;
        int cap = projectedChunkPixels >= 16.0f ? 48
                : projectedChunkPixels >= 8.0f ? 32
                : projectedChunkPixels >= 4.0f ? 16
                : projectedChunkPixels >= 2.0f ? 8 : 4;
        if (pressure) cap = Math.max(2, cap / 2);
        return Math.min(visible, cap);
    }

    public static Snapshot snapshot() {
        return latest;
    }

    public record Bounds(double minX, double maxX, double minZ, double maxZ) {
    }

    public record Snapshot(boolean trimmed, double areaRatio,
            double leftFraction, double rightFraction, double verticalFraction,
            int exactActiveWindow) {
        private static Snapshot identity() {
            return new Snapshot(false, 1.0, 0.0, 0.0, 0.0, 12);
        }
    }

}
