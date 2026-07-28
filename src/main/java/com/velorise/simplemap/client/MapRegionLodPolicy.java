package com.velorise.simplemap.client;

/**
 * Shared density policy for the M4 region hierarchy.
 *
 * <p>Region LOD is a coarse coverage/replacement hierarchy, not the final
 * density-correct factor-2 refinement. Level 0 is one 512x512 region represented
 * by a 64x64 texture (8 blocks/texel). Higher levels group 8x8 direct children.
 * The old refinement adapter may draw a sharper L1/L2 texture above this layer
 * until M5 moves both representations behind the page table.</p>
 */
public final class MapRegionLodPolicy {
    public static final int MAX_LEVEL = 3;
    public static final float DIRECT_PROJECTION_THRESHOLD = 0.50f;

    private MapRegionLodPolicy() { }

    public static boolean directProjectionEnabled(float logicalScale) {
        return Float.isFinite(logicalScale)
                && logicalScale > 0.0f
                && logicalScale < DIRECT_PROJECTION_THRESHOLD;
    }

    public static int targetLevel(float logicalScale) {
        if (logicalScale >= 0.0625f) return 0;
        if (logicalScale >= 0.0078125f) return 1;
        if (logicalScale >= 0.0009765625f) return 2;
        return MAX_LEVEL;
    }

    public static int regionSpan(int level) {
        int clamped = Math.max(0, Math.min(MAX_LEVEL, level));
        int span = 1;
        for (int current = 0; current < clamped; current++) span *= 8;
        return span;
    }

    public static int worldSize(int level) {
        long size = 512L * regionSpan(level);
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }
}
