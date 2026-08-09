package com.velorise.simplemap.client.cave;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Stable visible-page traversal shared by cave cache, world-save reads and page
 * publication.
 *
 * <p>Fullscreen work is enumerated in deterministic viewport scanline order:
 * top-left to bottom-right. Source admission, CPU projection and exact publication
 * consume the same immutable ordinal, so worker completion order can never leak as
 * random islands. Minimap work remains centre-out because its navigation focus is
 * the player rather than the top-left of a large inspected viewport.</p>
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

    /** Builds a small immutable-call-site order for haloed source windows. */
    static int[] buildCenterOutCellOrder(int size) {
        return buildCenterOutOrder(size);
    }


    /** Builds an exact-page plan restricted to the visible rectangle. */
    static long[] buildVisiblePagePlan(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
            boolean fullscreen) {
        return buildVisiblePagePlan(minPageX, maxPageX, minPageZ, maxPageZ,
                centerPageX, centerPageZ, fullscreen,
                false, 0, -1, 0, -1);
    }

    /**
     * Builds one deterministic nearest-first plan. The same ordinal is consumed by
     * region cache reads, source admission, CPU build and exact publication.
     */
    static long[] buildVisiblePagePlan(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
            boolean fullscreen, boolean continuousPan,
            int previousMinPageX, int previousMaxPageX,
            int previousMinPageZ, int previousMaxPageZ) {
        return fullscreen
                ? buildViewportScanlinePlan(minPageX, maxPageX, minPageZ, maxPageZ)
                : buildCenterOutPlan(minPageX, maxPageX, minPageZ, maxPageZ,
                        centerPageX, centerPageZ);
    }


    /**
     * Stable top-left to bottom-right page plan used by fullscreen publication.
     * Coordinates are world anchored, so panning does not reshuffle overlapping
     * pages and every subsystem can derive the same ordinal in O(1).
     */
    static long[] buildViewportScanlinePlan(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ) {
        int width = Math.max(0, maxPageX - minPageX + 1);
        int height = Math.max(0, maxPageZ - minPageZ + 1);
        if (width == 0 || height == 0) return new long[0];
        long[] result = new long[width * height];
        int cursor = 0;
        for (int pageZ = minPageZ; pageZ <= maxPageZ; pageZ++) {
            for (int pageX = minPageX; pageX <= maxPageX; pageX++) {
                result[cursor++] = pack(pageX, pageZ);
            }
        }
        return result;
    }

    /**
     * Region-major source import order. Native 32x32-chunk regions are visited
     * nearest-first; all visible 64x64 pages of one region are then consumed before
     * jumping to another file. Exact render/publication may still use a separate
     * centre-out page ordinal.
     */
    static long[] buildRegionMajorPagePlan(int minPageX, int maxPageX,
            int minPageZ, int maxPageZ, int centerPageX, int centerPageZ) {
        int width = Math.max(0, maxPageX - minPageX + 1);
        int height = Math.max(0, maxPageZ - minPageZ + 1);
        if (width == 0 || height == 0) return new long[0];
        int minRegionX = Math.floorDiv(minPageX, PAGES_PER_REGION);
        int maxRegionX = Math.floorDiv(maxPageX, PAGES_PER_REGION);
        int minRegionZ = Math.floorDiv(minPageZ, PAGES_PER_REGION);
        int maxRegionZ = Math.floorDiv(maxPageZ, PAGES_PER_REGION);
        long[] regionPlan = buildRegionPlan(minRegionX, maxRegionX,
                minRegionZ, maxRegionZ,
                centerPageX / (double) PAGES_PER_REGION,
                centerPageZ / (double) PAGES_PER_REGION);
        long[] result = new long[width * height];
        int cursor = 0;
        for (long packedRegion : regionPlan) {
            int regionX = x(packedRegion);
            int regionZ = z(packedRegion);
            int firstPageX = regionX * PAGES_PER_REGION;
            int firstPageZ = regionZ * PAGES_PER_REGION;
            int[] local = PAGE_ORDER.clone();
            // The nearest page inside a native region should lead its transaction.
            for (int i = 1; i < local.length; i++) {
                int value = local[i];
                int j = i - 1;
                while (j >= 0 && compareRegionPage(value, local[j],
                        firstPageX, firstPageZ, centerPageX, centerPageZ) < 0) {
                    local[j + 1] = local[j];
                    j--;
                }
                local[j + 1] = value;
            }
            for (int localIndex : local) {
                int pageX = firstPageX + localIndex % PAGES_PER_REGION;
                int pageZ = firstPageZ + localIndex / PAGES_PER_REGION;
                if (pageX < minPageX || pageX > maxPageX
                        || pageZ < minPageZ || pageZ > maxPageZ) continue;
                result[cursor++] = pack(pageX, pageZ);
            }
        }
        return cursor == result.length ? result
                : java.util.Arrays.copyOf(result, cursor);
    }

    private static int compareRegionPage(int first, int second,
            int regionPageX, int regionPageZ, int centerPageX, int centerPageZ) {
        int firstX = regionPageX + first % PAGES_PER_REGION;
        int firstZ = regionPageZ + first / PAGES_PER_REGION;
        int secondX = regionPageX + second % PAGES_PER_REGION;
        int secondZ = regionPageZ + second / PAGES_PER_REGION;
        int firstDx = firstX - centerPageX;
        int firstDz = firstZ - centerPageZ;
        int secondDx = secondX - centerPageX;
        int secondDz = secondZ - centerPageZ;
        int byDistance = Integer.compare(firstDx * firstDx + firstDz * firstDz,
                secondDx * secondDx + secondDz * secondDz);
        if (byDistance != 0) return byDistance;
        return Integer.compare(first, second);
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

    static int scanlineOrdinal(int minX, int maxX, int minZ, int maxZ,
            int x, int z) {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return -1;
        int width = maxX - minX + 1;
        return (z - minZ) * width + (x - minX);
    }

    /**
     * Primitive O(1) ordinal lookup for the current immutable page plan. The old
     * HashMap<Long,Integer> boxed every coordinate and ordinal; a 4,165-page
     * fullscreen viewport therefore created thousands of short-lived objects every
     * time the planner recentered. Xaero advances primitive writer coordinates and
     * never builds such a boxed index. Keep the same deterministic plan while using
     * a compact open-addressed primitive table.
     */
    static OrdinalIndex buildOrdinalIndex(long[] plan) {
        if (plan == null || plan.length == 0) return OrdinalIndex.EMPTY;
        return new OrdinalIndex(plan);
    }

    static final class OrdinalIndex {
        private static final OrdinalIndex EMPTY = new OrdinalIndex();
        private final long[] keys;
        private final int[] values;
        private final boolean[] used;
        private final int mask;

        private OrdinalIndex() {
            keys = new long[0];
            values = new int[0];
            used = new boolean[0];
            mask = 0;
        }

        private OrdinalIndex(long[] plan) {
            int capacity = 1;
            int target = Math.max(4, plan.length * 2);
            while (capacity < target) capacity <<= 1;
            keys = new long[capacity];
            values = new int[capacity];
            used = new boolean[capacity];
            mask = capacity - 1;
            for (int ordinal = 0; ordinal < plan.length; ordinal++) {
                long key = plan[ordinal];
                int slot = (int) mix(key) & mask;
                while (used[slot]) {
                    if (keys[slot] == key) break;
                    slot = (slot + 1) & mask;
                }
                used[slot] = true;
                keys[slot] = key;
                values[slot] = ordinal;
            }
        }

        int getOrDefault(long key, int fallback) {
            if (used.length == 0) return fallback;
            int slot = (int) mix(key) & mask;
            while (used[slot]) {
                if (keys[slot] == key) return values[slot];
                slot = (slot + 1) & mask;
            }
            return fallback;
        }

        private static long mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdl;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53l;
            value ^= value >>> 33;
            return value;
        }
    }

    /**
     * Returns unique 512x512 region coordinates in the same order in which their
     * first visible 64x64 page appears. This lets CIMG reads fill the IO pipeline
     * region-first while exact pages refine independently when coherent.
     */
    static long[] buildRegionPlanFromPagePlan(long[] pagePlan) {
        if (pagePlan == null || pagePlan.length == 0) return new long[0];
        LinkedHashSet<Long> regions = new LinkedHashSet<>();
        for (long page : pagePlan) {
            int regionX = Math.floorDiv(x(page), PAGES_PER_REGION);
            int regionZ = Math.floorDiv(z(page), PAGES_PER_REGION);
            regions.add(pack(regionX, regionZ));
        }
        long[] plan = new long[regions.size()];
        int cursor = 0;
        for (long region : regions) plan[cursor++] = region;
        return plan;
    }

    /**
     * Removes pages whose native Anvil region header contains no generated chunk.
     * Relative wavefront priority is preserved; filtering never manufactures or
     * reorders source work.
     */
    static long[] retainPresentPages(long[] plan, Set<Long> presentPages) {
        if (plan == null || plan.length == 0 || presentPages == null) {
            return new long[0];
        }
        int retained = 0;
        for (long page : plan) if (presentPages.contains(page)) retained++;
        if (retained == plan.length) return plan;
        long[] filtered = new long[retained];
        int cursor = 0;
        for (long page : plan) {
            if (presentPages.contains(page)) filtered[cursor++] = page;
        }
        return filtered;
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
