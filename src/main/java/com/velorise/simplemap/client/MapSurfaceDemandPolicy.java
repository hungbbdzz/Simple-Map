package com.velorise.simplemap.client;

/**
 * Separates the area already eligible for rendering from the smaller area that
 * may create new exact-surface work at far zoom.
 *
 * <p>Resident exact/branch textures are still rendered across the full viewport.
 * Only source capture, exact-leaf admission and saved-cache demand are trimmed.
 * The right edge receives a slightly larger fringe because fullscreen controls
 * and normal player attention are biased toward the centre/left of the map.</p>
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
        double width = Math.max(1.0, safeMaxX - safeMinX);
        double height = Math.max(1.0, safeMaxZ - safeMinZ);

        Fractions fractions = fractions(scale);
        if (fractions.identity()) {
            latest = new Snapshot(false, 1.0, 0.0, 0.0, 0.0,
                    exactActiveWindow(scale, false));
            return new Bounds(safeMinX, safeMaxX, safeMinZ, safeMaxZ);
        }

        double trimmedMinX = safeMinX + width * fractions.left();
        double trimmedMaxX = safeMaxX - width * fractions.right();
        double trimmedMinZ = safeMinZ + height * fractions.vertical();
        double trimmedMaxZ = safeMaxZ - height * fractions.vertical();

        // Never collapse a cold viewport below two exact leaves in either axis.
        double minimumSpan = MapPageLayout.PAGE_SIZE * 2.0;
        if (trimmedMaxX - trimmedMinX < minimumSpan) {
            double center = (safeMinX + safeMaxX) * 0.5;
            trimmedMinX = Math.max(safeMinX, center - minimumSpan * 0.5);
            trimmedMaxX = Math.min(safeMaxX, center + minimumSpan * 0.5);
        }
        if (trimmedMaxZ - trimmedMinZ < minimumSpan) {
            double center = (safeMinZ + safeMaxZ) * 0.5;
            trimmedMinZ = Math.max(safeMinZ, center - minimumSpan * 0.5);
            trimmedMaxZ = Math.min(safeMaxZ, center + minimumSpan * 0.5);
        }

        double originalArea = width * height;
        double trimmedArea = Math.max(1.0, trimmedMaxX - trimmedMinX)
                * Math.max(1.0, trimmedMaxZ - trimmedMinZ);
        latest = new Snapshot(true, Math.min(1.0, trimmedArea / originalArea),
                fractions.left(), fractions.right(), fractions.vertical(),
                exactActiveWindow(scale, false));
        return new Bounds(trimmedMinX, trimmedMaxX, trimmedMinZ, trimmedMaxZ);
    }

    /**
     * Maximum number of concurrent fullscreen exact leaves. Branch publication
     * remains independent and therefore continues to fill the complete viewport.
     */
    public static int exactActiveWindow(float scale, boolean pressure) {
        if (scale < 0.125f) return 1;
        if (scale < 0.25f) return pressure ? 1 : 2;
        if (scale < 0.50f) return pressure ? 2 : 4;
        return pressure ? 4 : 12;
    }

    public static Snapshot snapshot() {
        return latest;
    }

    private static Fractions fractions(float scale) {
        if (scale >= 0.50f) return Fractions.NONE;
        if (scale >= 0.25f) return new Fractions(0.04, 0.12, 0.05);
        if (scale >= 0.125f) return new Fractions(0.07, 0.18, 0.08);
        return new Fractions(0.10, 0.22, 0.12);
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

    private record Fractions(double left, double right, double vertical) {
        private static final Fractions NONE = new Fractions(0.0, 0.0, 0.0);

        private boolean identity() {
            return left == 0.0 && right == 0.0 && vertical == 0.0;
        }
    }
}
