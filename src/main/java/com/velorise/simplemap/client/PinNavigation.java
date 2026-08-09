package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;

/**
 * Stable world-space navigation-pin geometry.
 *
 * <p>The route always connects the player's current world position to the fixed
 * destination. Stability comes from defining the complete world-space segment
 * first and clipping only after its dot lattice has been chosen. Dots are phased
 * from the destination backwards, so walking straight toward the pin removes dots
 * behind the player instead of sliding every dot along the terrain. Viewport edges
 * never become navigation endpoints.</p>
 */
public final class PinNavigation {
    public static final double ROUTE_MARGIN_BLOCKS = 4.0;
    public static final double ROUTE_DOT_SPACING_BLOCKS = 8.0;
    public static final int MAX_VISIBLE_ROUTE_DOTS = 256;

    private PinNavigation() {
    }

    /** Activates a new destination. Legacy persisted route-origin fields are kept only
     * for save compatibility; rendering no longer treats them as a fixed path origin. */
    public static void activate(double targetX, double targetZ) {
        if (!validCoordinate(targetX) || !validCoordinate(targetZ)) return;
        MapConfig.pinWorldX = targetX;
        MapConfig.pinWorldZ = targetZ;
        MapConfig.pinActive = true;
        MapConfig.pinRouteStartValid = false;
        MapConfig.pinRouteStartWorldX = 0.0;
        MapConfig.pinRouteStartWorldZ = 0.0;
    }

    /** Moves an already tracked destination. */
    public static void updateDestination(double targetX, double targetZ) {
        if (!validCoordinate(targetX) || !validCoordinate(targetZ)) return;
        MapConfig.pinWorldX = targetX;
        MapConfig.pinWorldZ = targetZ;
    }

    public static void clear() {
        MapConfig.pinActive = false;
        MapConfig.pinRouteStartValid = false;
        MapConfig.pinRouteStartWorldX = 0.0;
        MapConfig.pinRouteStartWorldZ = 0.0;
    }

    /**
     * Compatibility hook for old pin save data. Route rendering is now live and does
     * not require a persisted origin, so no new route origin needs to be captured.
     */
    public static boolean captureRouteStartIfMissing() {
        return false;
    }

    /** Uses the current player position when an explicit interpolated origin is not
     * available. Renderers should prefer {@link #currentRoute(double, double)}. */
    public static Route currentRoute() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) return null;
        return currentRoute(minecraft.player.getX(), minecraft.player.getZ());
    }

    /**
     * Builds the complete navigation segment from the live player origin to the pin.
     * The viewport is intentionally not part of this calculation.
     */
    public static Route currentRoute(double startX, double startZ) {
        if (!MapConfig.pinActive
                || !validCoordinate(startX) || !validCoordinate(startZ)
                || !validCoordinate(MapConfig.pinWorldX)
                || !validCoordinate(MapConfig.pinWorldZ)) {
            return null;
        }
        /*
         * Navigation geometry is block-owned, not sub-pixel player-owned. Holding
         * the route origin at the centre of the player's current block means normal
         * movement inside one block cannot make the destination-phased guide lattice
         * wobble between neighbouring blocks. Crossing into a new block legitimately
         * advances the route origin and recomputes the path to the fixed target.
         */
        double routeStartX = blockCenter(startX);
        double routeStartZ = blockCenter(startZ);
        double dx = MapConfig.pinWorldX - routeStartX;
        double dz = MapConfig.pinWorldZ - routeStartZ;
        double length = Math.hypot(dx, dz);
        if (!(length > ROUTE_MARGIN_BLOCKS * 2.0)) return null;
        return new Route(routeStartX, routeStartZ,
                MapConfig.pinWorldX, MapConfig.pinWorldZ,
                dx, dz, length, dx / length, dz / length);
    }

    /**
     * Returns destination-phased dot indices whose full player-to-pin segment
     * intersects the supplied world rectangle. Clipping never redistributes dots.
     * {@code minWorldSpacing} may skip indices at far zoom, but the retained indices
     * remain aligned to the same destination-based lattice.
     */
    public static DotRange visibleDots(Route route,
            double minX, double maxX, double minZ, double maxZ,
            double minWorldSpacing, int maxDots) {
        if (route == null || maxX < minX || maxZ < minZ) return DotRange.EMPTY;
        double[] interval = {0.0, 1.0};
        if (!clipAxis(route.startX(), route.dx(), minX, maxX, interval)
                || !clipAxis(route.startZ(), route.dz(), minZ, maxZ, interval)) {
            return DotRange.EMPTY;
        }

        // t runs start -> target. Our dot phase runs target -> start, therefore the
        // visible distance-from-target interval is reversed relative to t.
        double minDistanceFromTarget = Math.max(ROUTE_MARGIN_BLOCKS,
                route.length() * (1.0 - interval[1]));
        double maxDistanceFromTarget = Math.min(
                route.length() - ROUTE_MARGIN_BLOCKS,
                route.length() * (1.0 - interval[0]));
        if (maxDistanceFromTarget < minDistanceFromTarget) return DotRange.EMPTY;

        int first = (int) Math.ceil((minDistanceFromTarget - ROUTE_MARGIN_BLOCKS)
                / ROUTE_DOT_SPACING_BLOCKS - 1.0e-9);
        int last = (int) Math.floor((maxDistanceFromTarget - ROUTE_MARGIN_BLOCKS)
                / ROUTE_DOT_SPACING_BLOCKS + 1.0e-9);
        first = Math.max(0, first);
        last = Math.min(route.lastDotIndex(), last);
        if (last < first) return DotRange.EMPTY;

        int stride = Math.max(1, (int) Math.ceil(
                Math.max(ROUTE_DOT_SPACING_BLOCKS, minWorldSpacing)
                        / ROUTE_DOT_SPACING_BLOCKS));
        first = alignUp(first, stride);
        if (first > last) return DotRange.EMPTY;

        int boundedMax = Math.max(1, maxDots);
        int count = 1 + (last - first) / stride;
        if (count > boundedMax) {
            int multiplier = (int) Math.ceil(count / (double) boundedMax);
            stride *= Math.max(1, multiplier);
            first = alignUp(first, stride);
            if (first > last) return DotRange.EMPTY;
        }
        return new DotRange(first, last, stride);
    }

    /** Dots are anchored from the fixed destination backwards toward the player. */
    public static double dotWorldX(Route route, int index) {
        double distance = ROUTE_MARGIN_BLOCKS
                + Math.max(0, index) * ROUTE_DOT_SPACING_BLOCKS;
        return blockCenter(route.targetX() - route.nx() * distance);
    }

    /** Dots are anchored from the fixed destination backwards toward the player. */
    public static double dotWorldZ(Route route, int index) {
        double distance = ROUTE_MARGIN_BLOCKS
                + Math.max(0, index) * ROUTE_DOT_SPACING_BLOCKS;
        return blockCenter(route.targetZ() - route.nz() * distance);
    }

    private static boolean clipAxis(double origin, double delta,
            double min, double max, double[] interval) {
        if (Math.abs(delta) < 1.0e-12) {
            return origin >= min && origin <= max;
        }
        double a = (min - origin) / delta;
        double b = (max - origin) / delta;
        if (a > b) {
            double swap = a;
            a = b;
            b = swap;
        }
        interval[0] = Math.max(interval[0], a);
        interval[1] = Math.min(interval[1], b);
        return interval[1] >= interval[0];
    }

    private static int alignUp(int value, int stride) {
        if (stride <= 1) return value;
        int remainder = Math.floorMod(value, stride);
        return remainder == 0 ? value : value + (stride - remainder);
    }

    private static boolean validCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000.0;
    }

    private static double blockCenter(double coordinate) {
        return Math.floor(coordinate) + 0.5;
    }

    public record Route(double startX, double startZ,
            double targetX, double targetZ,
            double dx, double dz, double length, double nx, double nz) {
        public int lastDotIndex() {
            double usable = length - ROUTE_MARGIN_BLOCKS * 2.0;
            return usable < 0.0 ? -1
                    : (int) Math.floor(usable / ROUTE_DOT_SPACING_BLOCKS);
        }
    }

    public record DotRange(int firstIndex, int lastIndex, int stride) {
        private static final DotRange EMPTY = new DotRange(1, 0, 1);

        public boolean isEmpty() {
            return lastIndex < firstIndex;
        }
    }
}
