package com.velorise.simplemap.client.cave;

/**
 * Stable visible-page traversal shared by cave cache, world-save reads and page
 * publication. Every foreground stage uses the same centre-out order, so a zoom or
 * pan warms the inspected area first and publication cannot reveal an unrelated
 * screen edge ahead of its source/build dependencies. The order expands in compact
 * Chebyshev rings and remains deterministic across all three pipelines.
 */
final class CaveLoadHierarchy {
    static final int PAGES_PER_REGION = 8;
    static final int CHUNKS_PER_PAGE = 4;
    static final int PAGES_PER_REGION_COUNT = 64;
    static final int CHUNKS_PER_PAGE_COUNT = 16;
    private static final int[] PAGE_ORDER = buildCenterOutOrder(PAGES_PER_REGION);
    private static final int[] CHUNK_ORDER = buildCenterOutOrder(CHUNKS_PER_PAGE);

    private CaveLoadHierarchy() {
    }

    /** Returns a centre-out storage index for coherent region source prefetch. */
    static int orderedPageIndex(int order) {
        return PAGE_ORDER[Math.floorMod(order, PAGES_PER_REGION_COUNT)];
    }

    /** Compatibility overload; region-local source order is deterministic. */
    static int orderedPageIndex(int order, int anchorX, int anchorZ) {
        return orderedPageIndex(order);
    }

    /** Returns a storage index in deterministic centre-out order. */
    static int orderedChunkIndex(int order) {
        return CHUNK_ORDER[Math.floorMod(order, CHUNKS_PER_PAGE_COUNT)];
    }


    /**
     * Builds an exact-page plan restricted to the visible rectangle. Fullscreen,
     * minimap and source readers intentionally share the same centre-out ordering;
     * only their admission budgets differ.
     */
    static long[] buildVisiblePagePlan(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
            boolean fullscreen) {
        return buildVisiblePagePlan(minPageX, maxPageX, minPageZ, maxPageZ,
                centerPageX, centerPageZ, fullscreen,
                false, 0, -1, 0, -1);
    }

    /**
     * Builds one deterministic centre-out plan. The overlap parameters remain in
     * the signature because viewport handoff uses them independently, but ordering
     * no longer restarts from a remote top-left edge after pan or zoom.
     */
    static long[] buildVisiblePagePlan(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
            boolean fullscreen, boolean continuousPan,
            int previousMinPageX, int previousMaxPageX,
            int previousMinPageZ, int previousMaxPageZ) {
        return buildCenterOutPlan(minPageX, maxPageX, minPageZ, maxPageZ,
                centerPageX, centerPageZ);
    }

    static long[] buildRegionPlan(int minRegionX, int maxRegionX,
            int minRegionZ, int maxRegionZ,
            double centerRegionX, double centerRegionZ) {
        int centerX = clamp((int) Math.floor(centerRegionX),
                minRegionX, maxRegionX);
        int centerZ = clamp((int) Math.floor(centerRegionZ),
                minRegionZ, maxRegionZ);
        return buildCenterOutPlan(minRegionX, maxRegionX,
                minRegionZ, maxRegionZ, centerX, centerZ);
    }

    /** Returns the first ordinal in the target page's centre-out ring. */
    static int centerOutRingStart(int minX, int maxX, int minZ, int maxZ,
            int centerX, int centerZ, int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return -1;
        int safeCenterX = clamp(centerX, minX, maxX);
        int safeCenterZ = clamp(centerZ, minZ, maxZ);
        int radius = Math.max(Math.abs(x - safeCenterX),
                Math.abs(z - safeCenterZ));
        return clippedArea(minX, maxX, minZ, maxZ,
                safeCenterX, safeCenterZ, radius - 1);
    }

    /** Returns the ordinal used by {@link #buildVisiblePagePlan}. */
    static int centerOutOrdinal(int minX, int maxX, int minZ, int maxZ,
            int centerX, int centerZ, int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return -1;
        int safeCenterX = clamp(centerX, minX, maxX);
        int safeCenterZ = clamp(centerZ, minZ, maxZ);
        int radius = Math.max(Math.abs(x - safeCenterX),
                Math.abs(z - safeCenterZ));
        int ordinal = clippedArea(minX, maxX, minZ, maxZ,
                safeCenterX, safeCenterZ, radius - 1);
        int firstZ = Math.max(minZ, safeCenterZ - radius);
        int lastZ = Math.min(maxZ, safeCenterZ + radius);
        for (int candidateZ = firstZ; candidateZ <= lastZ; candidateZ++) {
            int top = safeCenterZ - radius;
            int bottom = safeCenterZ + radius;
            if (candidateZ == top || candidateZ == bottom) {
                int firstX = Math.max(minX, safeCenterX - radius);
                int lastX = Math.min(maxX, safeCenterX + radius);
                if (candidateZ == z) return ordinal + x - firstX;
                ordinal += Math.max(0, lastX - firstX + 1);
                continue;
            }
            int left = safeCenterX - radius;
            if (left >= minX && left <= maxX) {
                if (candidateZ == z && x == left) return ordinal;
                ordinal++;
            }
            int right = safeCenterX + radius;
            if (right != left && right >= minX && right <= maxX) {
                if (candidateZ == z && x == right) return ordinal;
                ordinal++;
            }
        }
        return -1;
    }

    private static long[] buildCenterOutPlan(int minX, int maxX,
            int minZ, int maxZ, int centerX, int centerZ) {
        int width = Math.max(0, maxX - minX + 1);
        int height = Math.max(0, maxZ - minZ + 1);
        int total = width * height;
        if (total == 0) return new long[0];
        int safeCenterX = clamp(centerX, minX, maxX);
        int safeCenterZ = clamp(centerZ, minZ, maxZ);
        int maximumRadius = Math.max(
                Math.max(safeCenterX - minX, maxX - safeCenterX),
                Math.max(safeCenterZ - minZ, maxZ - safeCenterZ));
        long[] plan = new long[total];
        int ordinal = 0;
        for (int radius = 0; radius <= maximumRadius; radius++) {
            int left = safeCenterX - radius;
            int right = safeCenterX + radius;
            int top = safeCenterZ - radius;
            int bottom = safeCenterZ + radius;
            if (top >= minZ && top <= maxZ) {
                for (int x = Math.max(minX, left);
                        x <= Math.min(maxX, right); x++) {
                    plan[ordinal++] = pack(x, top);
                }
            }
            if (radius == 0) continue;
            for (int z = Math.max(minZ, top + 1);
                    z <= Math.min(maxZ, bottom - 1); z++) {
                if (left >= minX && left <= maxX) {
                    plan[ordinal++] = pack(left, z);
                }
                if (right != left && right >= minX && right <= maxX) {
                    plan[ordinal++] = pack(right, z);
                }
            }
            if (bottom >= minZ && bottom <= maxZ) {
                for (int x = Math.max(minX, left);
                        x <= Math.min(maxX, right); x++) {
                    plan[ordinal++] = pack(x, bottom);
                }
            }
        }
        return plan;
    }

    private static int clippedArea(int minX, int maxX, int minZ, int maxZ,
            int centerX, int centerZ, int radius) {
        if (radius < 0) return 0;
        int firstX = Math.max(minX, centerX - radius);
        int lastX = Math.min(maxX, centerX + radius);
        int firstZ = Math.max(minZ, centerZ - radius);
        int lastZ = Math.min(maxZ, centerZ + radius);
        if (firstX > lastX || firstZ > lastZ) return 0;
        return (lastX - firstX + 1) * (lastZ - firstZ + 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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

}
