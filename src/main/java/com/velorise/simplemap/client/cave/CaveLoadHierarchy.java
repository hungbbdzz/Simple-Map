package com.velorise.simplemap.client.cave;

/**
 * Stable visible-page traversal shared by cave cache, world-save reads and page
 * publication. Fullscreen uses stable Xaero-style viewport slices; minimap and
 * background work retain centre-out ordering. Chunk dependencies inside a page
 * remain centre-out.
 */
final class CaveLoadHierarchy {
    static final int PAGES_PER_REGION = 8;
    static final int CHUNKS_PER_PAGE = 4;
    static final int PAGES_PER_REGION_COUNT = 64;
    static final int CHUNKS_PER_PAGE_COUNT = 16;
    private static final int[][] PAGE_ORDERS = buildAnchoredOrders(PAGES_PER_REGION);
    private static final int[] CHUNK_ORDER = buildCenterOutOrder(CHUNKS_PER_PAGE);

    private CaveLoadHierarchy() {
    }

    /** Returns a row-major storage index in deterministic centre-out order. */
    static int orderedPageIndex(int order) {
        return orderedPageIndex(order, 3, 3);
    }

    /**
     * Returns the page nearest the attention point first. The anchor is clamped to
     * this region, so regions outside the focus region begin at their nearest edge
     * rather than always revealing from the geometric centre.
     */
    static int orderedPageIndex(int order, int anchorX, int anchorZ) {
        int x = Math.max(0, Math.min(PAGES_PER_REGION - 1, anchorX));
        int z = Math.max(0, Math.min(PAGES_PER_REGION - 1, anchorZ));
        return PAGE_ORDERS[z * PAGES_PER_REGION + x]
                [Math.floorMod(order, PAGES_PER_REGION_COUNT)];
    }

    /** Returns a row-major storage index in deterministic centre-out order. */
    static int orderedChunkIndex(int order) {
        return CHUNK_ORDER[Math.floorMod(order, CHUNKS_PER_PAGE_COUNT)];
    }


    /**
     * Builds an exact-page plan restricted to the currently visible page rectangle.
     * Fullscreen uses stable screen scanlines (top-to-bottom, left-to-right) so
     * expensive cave projections fill the viewport coherently instead of appearing
     * as scattered islands. Minimap/background lanes can use centre-out ordering.
     */
    static long[] buildVisiblePagePlan(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
            boolean fullscreen) {
        return buildVisiblePagePlan(minPageX, maxPageX, minPageZ, maxPageZ,
                centerPageX, centerPageZ, fullscreen,
                false, 0, -1, 0, -1);
    }

    /**
     * Builds a delta-first fullscreen plan for a continuous pan. Pages newly
     * exposed by the new rectangle are placed first, ordered from the new viewport
     * centre outward. Retained overlap follows in stable row-major order. A cold
     * open, mode switch or teleport uses the original coherent row-major plan.
     */
    static long[] buildVisiblePagePlan(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
            boolean fullscreen, boolean continuousPan,
            int previousMinPageX, int previousMaxPageX,
            int previousMinPageZ, int previousMaxPageZ) {
        int width = Math.max(0, maxPageX - minPageX + 1);
        int height = Math.max(0, maxPageZ - minPageZ + 1);
        int total = width * height;
        long[] plan = new long[total];
        int count = 0;
        if (fullscreen && continuousPan) {
            long[] delta = new long[total];
            int deltaCount = 0;
            for (int pageZ = minPageZ; pageZ <= maxPageZ; pageZ++) {
                for (int pageX = minPageX; pageX <= maxPageX; pageX++) {
                    boolean retained = pageX >= previousMinPageX
                            && pageX <= previousMaxPageX
                            && pageZ >= previousMinPageZ
                            && pageZ <= previousMaxPageZ;
                    if (!retained) delta[deltaCount++] = pack(pageX, pageZ);
                }
            }
            sortByDistance(delta, deltaCount, centerPageX, centerPageZ);
            for (int i = 0; i < deltaCount; i++) plan[count++] = delta[i];
            for (int pageZ = minPageZ; pageZ <= maxPageZ; pageZ++) {
                for (int pageX = minPageX; pageX <= maxPageX; pageX++) {
                    if (pageX >= previousMinPageX && pageX <= previousMaxPageX
                            && pageZ >= previousMinPageZ
                            && pageZ <= previousMaxPageZ) {
                        plan[count++] = pack(pageX, pageZ);
                    }
                }
            }
            return plan;
        }

        for (int pageZ = minPageZ; pageZ <= maxPageZ; pageZ++) {
            for (int pageX = minPageX; pageX <= maxPageX; pageX++) {
                plan[count++] = pack(pageX, pageZ);
            }
        }
        if (!fullscreen) sortByDistance(plan, plan.length, centerPageX, centerPageZ);
        return plan;
    }

    private static void sortByDistance(long[] pages, int length,
            double centerPageX, double centerPageZ) {
        for (int i = 1; i < length; i++) {
            long value = pages[i];
            int j = i - 1;
            while (j >= 0 && compare(value, pages[j], centerPageX, centerPageZ) < 0) {
                pages[j + 1] = pages[j];
                j--;
            }
            pages[j + 1] = value;
        }
    }


    static long[] buildRegionPlan(int minRegionX, int maxRegionX,
            int minRegionZ, int maxRegionZ, double centerRegionX, double centerRegionZ) {
        int width = Math.max(0, maxRegionX - minRegionX + 1);
        int height = Math.max(0, maxRegionZ - minRegionZ + 1);
        long[] plan = new long[width * height];
        int count = 0;
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                plan[count++] = pack(regionX, regionZ);
            }
        }

        for (int i = 1; i < plan.length; i++) {
            long value = plan[i];
            int j = i - 1;
            while (j >= 0 && compare(value, plan[j], centerRegionX, centerRegionZ) < 0) {
                plan[j + 1] = plan[j];
                j--;
            }
            plan[j + 1] = value;
        }
        return plan;
    }

    static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    static int x(long packed) {
        return (int) (packed >> 32);
    }

    static int z(long packed) {
        return (int) packed;
    }


    private static int[][] buildAnchoredOrders(int size) {
        int[][] result = new int[size * size][];
        for (int anchorZ = 0; anchorZ < size; anchorZ++) {
            for (int anchorX = 0; anchorX < size; anchorX++) {
                int[] order = new int[size * size];
                for (int i = 0; i < order.length; i++) order[i] = i;
                for (int i = 1; i < order.length; i++) {
                    int value = order[i];
                    int j = i - 1;
                    while (j >= 0 && compareCellToAnchor(value, order[j], size,
                            anchorX, anchorZ) < 0) {
                        order[j + 1] = order[j];
                        j--;
                    }
                    order[j + 1] = value;
                }
                result[anchorZ * size + anchorX] = order;
            }
        }
        return result;
    }

    private static int compareCellToAnchor(int first, int second, int size,
            int anchorX, int anchorZ) {
        int firstX = first % size;
        int firstZ = first / size;
        int secondX = second % size;
        int secondZ = second / size;
        int firstDx = firstX - anchorX;
        int firstDz = firstZ - anchorZ;
        int secondDx = secondX - anchorX;
        int secondDz = secondZ - anchorZ;
        int distance = Integer.compare(firstDx * firstDx + firstDz * firstDz,
                secondDx * secondDx + secondDz * secondDz);
        if (distance != 0) return distance;
        int byRing = Integer.compare(Math.max(Math.abs(firstDx), Math.abs(firstDz)),
                Math.max(Math.abs(secondDx), Math.abs(secondDz)));
        if (byRing != 0) return byRing;
        int byZ = Integer.compare(firstZ, secondZ);
        return byZ != 0 ? byZ : Integer.compare(firstX, secondX);
    }

    private static int[] buildCenterOutOrder(int size) {
        int[] order = new int[size * size];
        for (int i = 0; i < order.length; i++) order[i] = i;
        double center = (size - 1) * 0.5;
        for (int i = 1; i < order.length; i++) {
            int value = order[i];
            int j = i - 1;
            while (j >= 0 && compareCell(value, order[j], size, center) < 0) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = value;
        }
        return order;
    }

    private static int compareCell(int first, int second, int size, double center) {
        int firstX = first % size;
        int firstZ = first / size;
        int secondX = second % size;
        int secondZ = second / size;
        double firstDx = firstX - center;
        double firstDz = firstZ - center;
        double secondDx = secondX - center;
        double secondDz = secondZ - center;
        int distance = Double.compare(firstDx * firstDx + firstDz * firstDz,
                secondDx * secondDx + secondDz * secondDz);
        if (distance != 0) return distance;
        int byRing = Double.compare(Math.max(Math.abs(firstDx), Math.abs(firstDz)),
                Math.max(Math.abs(secondDx), Math.abs(secondDz)));
        if (byRing != 0) return byRing;
        int byZ = Integer.compare(firstZ, secondZ);
        return byZ != 0 ? byZ : Integer.compare(firstX, secondX);
    }

    private static int compare(long first, long second,
            double centerRegionX, double centerRegionZ) {
        int firstX = x(first);
        int firstZ = z(first);
        int secondX = x(second);
        int secondZ = z(second);
        double firstDx = firstX - centerRegionX;
        double firstDz = firstZ - centerRegionZ;
        double secondDx = secondX - centerRegionX;
        double secondDz = secondZ - centerRegionZ;
        int distance = Double.compare(firstDx * firstDx + firstDz * firstDz,
                secondDx * secondDx + secondDz * secondDz);
        if (distance != 0) return distance;
        int byX = Integer.compare(firstX, secondX);
        return byX != 0 ? byX : Integer.compare(firstZ, secondZ);
    }
}
