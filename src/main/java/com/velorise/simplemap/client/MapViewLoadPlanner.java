package com.velorise.simplemap.client;

/**
 * Separate visible-demand policies for fullscreen maps and the minimap.
 *
 * <p>Fullscreen follows a stable viewport traversal split into small update
 * slices. Simple Map exposes its smaller 64-block leaves as screen rows so the
 * visible result advances left-to-right and top-to-bottom. Cursor movement is not
 * part of the load key or candidate priority.</p>
 *
 * <p>The minimap is intentionally different. It requests a compact exact-leaf
 * halo around the camera/player in centre-out order, allowing the small visible
 * area to become complete before unrelated fullscreen work.</p>
 */
public final class MapViewLoadPlanner {
    public static final int FULLSCREEN_SLICE_SIZE = 100;
    public static final int FULLSCREEN_SHORTLIST_SIZE = 10;
    public static final int MINIMAP_HALO_PAGES = 1;
    public static final int MINIMAP_MAX_RADIUS_PAGES = 3;

    private MapViewLoadPlanner() {
    }

    /** Persistent traversal state. Focus is deliberately not part of the key. */
    public static final class State {
        private String dimension = "";
        private int minX;
        private int maxX;
        private int minZ;
        private int maxZ;
        private int sliceIndex;
        private int sliceCount;
        private boolean configured;
        private boolean retainedOverlap;
        private long completedCycles;
        private Page[] pagePlan = new Page[0];

        public boolean configure(String dimension, int minX, int maxX,
                int minZ, int maxZ) {
            String safeDimension = dimension == null ? "" : dimension;
            int safeMinX = Math.min(minX, maxX);
            int safeMaxX = Math.max(minX, maxX);
            int safeMinZ = Math.min(minZ, maxZ);
            int safeMaxZ = Math.max(minZ, maxZ);
            if (configured && this.dimension.equals(safeDimension)
                    && this.minX == safeMinX && this.maxX == safeMaxX
                    && this.minZ == safeMinZ && this.maxZ == safeMaxZ) return false;

            int previousMinX = this.minX;
            int previousMaxX = this.maxX;
            int previousMinZ = this.minZ;
            int previousMaxZ = this.maxZ;
            boolean sameDimension = configured && this.dimension.equals(safeDimension);
            retainedOverlap = sameDimension && rectanglesOverlap(
                    previousMinX, previousMaxX, previousMinZ, previousMaxZ,
                    safeMinX, safeMaxX, safeMinZ, safeMaxZ);

            this.dimension = safeDimension;
            this.minX = safeMinX;
            this.maxX = safeMaxX;
            this.minZ = safeMinZ;
            this.maxZ = safeMaxZ;
            this.pagePlan = buildPlan(safeMinX, safeMaxX, safeMinZ, safeMaxZ,
                    retainedOverlap, previousMinX, previousMaxX,
                    previousMinZ, previousMaxZ);
            this.sliceCount = Math.max(1, (pagePlan.length
                    + FULLSCREEN_SLICE_SIZE - 1) / FULLSCREEN_SLICE_SIZE);
            this.sliceIndex = 0;
            this.completedCycles = 0L;
            this.configured = true;
            return true;
        }

        /**
         * Fills the current fullscreen update slice without advancing it.
         * A continuous pan places newly exposed pages before retained overlap;
         * cold opens and teleports retain the stable row-major reveal.
         */
        public int fillCurrentFullscreenSlice(Page[] output) {
            if (!configured || output == null || output.length == 0) return 0;
            int start = sliceIndex * FULLSCREEN_SLICE_SIZE;
            int end = Math.min(pagePlan.length, start + FULLSCREEN_SLICE_SIZE);
            int count = 0;
            for (int index = start; index < end && count < output.length; index++) {
                output[count++] = pagePlan[index];
            }
            return count;
        }

        /** Advances only after the current slice has no missing or in-flight page. */
        public void advanceFullscreenSlice() {
            if (!configured) return;
            sliceIndex++;
            if (sliceIndex >= sliceCount) {
                sliceIndex = 0;
                completedCycles++;
            }
        }

        /** Compatibility helper for tests/tools that intentionally consume a slice. */
        public int fillNextFullscreenSlice(Page[] output) {
            int count = fillCurrentFullscreenSlice(output);
            advanceFullscreenSlice();
            return count;
        }

        public boolean retainedOverlap() {
            return retainedOverlap;
        }

        public long completedCycles() {
            return completedCycles;
        }

        public int currentSliceIndex() {
            return sliceIndex;
        }

        public int sliceCount() {
            return sliceCount;
        }

        public void clear() {
            dimension = "";
            minX = maxX = minZ = maxZ = 0;
            sliceIndex = 0;
            sliceCount = 0;
            completedCycles = 0L;
            configured = false;
            retainedOverlap = false;
            pagePlan = new Page[0];
        }

        private static Page[] buildPlan(int minX, int maxX, int minZ, int maxZ,
                boolean deltaFirst, int oldMinX, int oldMaxX,
                int oldMinZ, int oldMaxZ) {
            int width = Math.max(0, maxX - minX + 1);
            int height = Math.max(0, maxZ - minZ + 1);
            int total = width * height;
            if (total == 0) return new Page[0];

            long[] newlyExposed = new long[total];
            int newlyExposedCount = 0;
            if (deltaFirst) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        if (x < oldMinX || x > oldMaxX
                                || z < oldMinZ || z > oldMaxZ) {
                            newlyExposed[newlyExposedCount++] = pack(x, z);
                        }
                    }
                }
                sortByDistance(newlyExposed, newlyExposedCount,
                        (minX + maxX) * 0.5, (minZ + maxZ) * 0.5);
            }

            Page[] result = new Page[total];
            int ordinal = 0;
            for (int index = 0; index < newlyExposedCount; index++) {
                long packed = newlyExposed[index];
                result[ordinal] = new Page(unpackX(packed), unpackZ(packed), ordinal);
                ordinal++;
            }
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    boolean retained = deltaFirst && x >= oldMinX && x <= oldMaxX
                            && z >= oldMinZ && z <= oldMaxZ;
                    if (deltaFirst && !retained) continue;
                    result[ordinal] = new Page(x, z, ordinal);
                    ordinal++;
                }
            }
            return result;
        }

        private static void sortByDistance(long[] pages, int length,
                double centerX, double centerZ) {
            for (int index = 1; index < length; index++) {
                long value = pages[index];
                int cursor = index - 1;
                while (cursor >= 0 && compare(value, pages[cursor],
                        centerX, centerZ) < 0) {
                    pages[cursor + 1] = pages[cursor];
                    cursor--;
                }
                pages[cursor + 1] = value;
            }
        }

        private static int compare(long first, long second,
                double centerX, double centerZ) {
            int firstX = unpackX(first);
            int firstZ = unpackZ(first);
            int secondX = unpackX(second);
            int secondZ = unpackZ(second);
            int distance = Double.compare(
                    distanceSquared(firstX, firstZ, centerX, centerZ),
                    distanceSquared(secondX, secondZ, centerX, centerZ));
            if (distance != 0) return distance;
            int byZ = Integer.compare(firstZ, secondZ);
            return byZ != 0 ? byZ : Integer.compare(firstX, secondX);
        }

        private static double distanceSquared(int x, int z,
                double centerX, double centerZ) {
            double dx = x - centerX;
            double dz = z - centerZ;
            return dx * dx + dz * dz;
        }

        private static long pack(int x, int z) {
            return ((long) x << 32) ^ (z & 0xffffffffL);
        }

        private static int unpackX(long packed) {
            return (int) (packed >> 32);
        }

        private static int unpackZ(long packed) {
            return (int) packed;
        }

        private static boolean rectanglesOverlap(int firstMinX, int firstMaxX,
                int firstMinZ, int firstMaxZ, int secondMinX, int secondMaxX,
                int secondMinZ, int secondMaxZ) {
            return firstMinX <= secondMaxX && firstMaxX >= secondMinX
                    && firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ;
        }
    }

    /**
     * Fills a bounded minimap exact-leaf halo in deterministic centre-out order.
     * The visible rectangle is expanded by one page, then capped to a small radius
     * so extreme minimap zoom cannot flood the exact pipeline.
     */
    public static int fillMinimapHalo(int visibleMinX, int visibleMaxX,
            int visibleMinZ, int visibleMaxZ, int centerX, int centerZ,
            Page[] output) {
        if (output == null || output.length == 0) return 0;
        int minX = Math.max(Math.min(visibleMinX, visibleMaxX) - MINIMAP_HALO_PAGES,
                centerX - MINIMAP_MAX_RADIUS_PAGES);
        int maxX = Math.min(Math.max(visibleMinX, visibleMaxX) + MINIMAP_HALO_PAGES,
                centerX + MINIMAP_MAX_RADIUS_PAGES);
        int minZ = Math.max(Math.min(visibleMinZ, visibleMaxZ) - MINIMAP_HALO_PAGES,
                centerZ - MINIMAP_MAX_RADIUS_PAGES);
        int maxZ = Math.min(Math.max(visibleMinZ, visibleMaxZ) + MINIMAP_HALO_PAGES,
                centerZ + MINIMAP_MAX_RADIUS_PAGES);
        int maximumRadius = Math.max(
                Math.max(Math.abs(centerX - minX), Math.abs(maxX - centerX)),
                Math.max(Math.abs(centerZ - minZ), Math.abs(maxZ - centerZ)));
        int count = 0;
        int ordinal = 0;
        for (int radius = 0; radius <= maximumRadius && count < output.length;
                radius++) {
            for (int z = centerZ - radius;
                    z <= centerZ + radius && count < output.length; z++) {
                for (int x = centerX - radius;
                        x <= centerX + radius && count < output.length; x++) {
                    if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ))
                            != radius) continue;
                    if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                    output[count++] = new Page(x, z, ordinal++);
                }
            }
        }
        return count;
    }

    public record Page(int x, int z, int ordinal) {
        public int distanceSquaredTo(int focusX, int focusZ) {
            int dx = x - focusX;
            int dz = z - focusZ;
            return dx * dx + dz * dz;
        }
    }
}
