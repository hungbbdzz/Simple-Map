package com.velorise.simplemap.client;

/** Shared spatial layout for CPU snapshots and GPU map pages. */
public final class MapPageLayout {
    public static final int REGION_SIZE = 512;
    public static final int PAGE_SIZE = 64;
    /** Minecraft chunk-sized progressive publication unit inside one GPU page. */
    public static final int SUBTILE_SIZE = 16;
    public static final int SUBTILES_PER_PAGE = PAGE_SIZE / SUBTILE_SIZE;
    public static final int SUBTILES_PER_PAGE_SQUARED =
            SUBTILES_PER_PAGE * SUBTILES_PER_PAGE;
    public static final int FULL_SUBTILE_MASK =
            (1 << SUBTILES_PER_PAGE_SQUARED) - 1;
    /** Samples required by 3x3 biome tint, immediate relief and radius-2 depth. */
    public static final int PAGE_HALO = 2;
    public static final int PAGE_SNAPSHOT_SIZE = PAGE_SIZE + PAGE_HALO * 2;
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

    public static int subtileIndex(int localSubtileX, int localSubtileZ) {
        return localSubtileZ * SUBTILES_PER_PAGE + localSubtileX;
    }

    /**
     * Returns one bit for every fully-known 16x16 chunk-sized part of a 64x64
     * exact page. A partially scanned chunk is deliberately not publishable.
     */
    public static int completeSubtileMask(long[] knownRows) {
        if (knownRows == null || knownRows.length < PAGE_SIZE) return 0;
        int result = 0;
        long subtileBits = (1L << SUBTILE_SIZE) - 1L;
        for (int subtileZ = 0; subtileZ < SUBTILES_PER_PAGE; subtileZ++) {
            for (int subtileX = 0; subtileX < SUBTILES_PER_PAGE; subtileX++) {
                long expected = subtileBits << (subtileX * SUBTILE_SIZE);
                boolean complete = true;
                int firstRow = subtileZ * SUBTILE_SIZE;
                for (int row = firstRow; row < firstRow + SUBTILE_SIZE; row++) {
                    if ((knownRows[row] & expected) != expected) {
                        complete = false;
                        break;
                    }
                }
                if (complete) result |= 1 << subtileIndex(subtileX, subtileZ);
            }
        }
        return result;
    }
    /**
     * Returns chunk-sized publication bits only when both the 16x16 body and the
     * projection halo required by tint/slope/depth sampling are known. Publishing
     * a body-only subtile lets a frontier chunk look "complete" and then visibly
     * change when its neighbour arrives. Xaero waits for neighbouring chunks before
     * committing a tile; this is the equivalent invariant for SimpleMap pages.
     */
    public static int completeSubtileMaskWithHalo(byte[] known, int stride,
            int halo) {
        if (known == null || stride <= 0 || halo < 0
                || known.length < stride * stride) return 0;
        int result = 0;
        for (int subtileZ = 0; subtileZ < SUBTILES_PER_PAGE; subtileZ++) {
            for (int subtileX = 0; subtileX < SUBTILES_PER_PAGE; subtileX++) {
                int bodyMinX = halo + subtileX * SUBTILE_SIZE;
                int bodyMinZ = halo + subtileZ * SUBTILE_SIZE;
                int minX = bodyMinX - halo;
                int minZ = bodyMinZ - halo;
                int maxX = bodyMinX + SUBTILE_SIZE - 1 + halo;
                int maxZ = bodyMinZ + SUBTILE_SIZE - 1 + halo;
                if (minX < 0 || minZ < 0 || maxX >= stride || maxZ >= stride) {
                    continue;
                }
                boolean complete = true;
                for (int z = minZ; z <= maxZ && complete; z++) {
                    int row = z * stride;
                    for (int x = minX; x <= maxX; x++) {
                        if (known[row + x] == 0) {
                            complete = false;
                            break;
                        }
                    }
                }
                if (complete) result |= 1 << subtileIndex(subtileX, subtileZ);
            }
        }
        return result;
    }

}
