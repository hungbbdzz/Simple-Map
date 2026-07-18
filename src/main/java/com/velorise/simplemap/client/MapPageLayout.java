package com.velorise.simplemap.client;

/** Shared spatial layout for CPU snapshots and GPU map pages. */
public final class MapPageLayout {
    public static final int REGION_SIZE = 512;
    public static final int PAGE_SIZE = 64;
    public static final int PAGES_PER_REGION = REGION_SIZE / PAGE_SIZE;
    public static final int PAGES_PER_REGION_SQUARED = PAGES_PER_REGION * PAGES_PER_REGION;

    private MapPageLayout() {
    }

    public static int regionFromGlobalPage(int globalPage) {
        return Math.floorDiv(globalPage, PAGES_PER_REGION);
    }

    public static int localPage(int globalPage) {
        return Math.floorMod(globalPage, PAGES_PER_REGION);
    }

    public static int pageIndex(int pageX, int pageZ) {
        return pageZ * PAGES_PER_REGION + pageX;
    }

    public static int pageX(int index) {
        return index & (PAGES_PER_REGION - 1);
    }

    public static int pageZ(int index) {
        return index >>> 3;
    }

    public static int globalPageFromBlock(int block) {
        return Math.floorDiv(block, PAGE_SIZE);
    }

    public static int localPageFromBlock(int block) {
        return Math.floorMod(block, REGION_SIZE) / PAGE_SIZE;
    }
}
