package com.velorise.simplemap.client;

/**
 * Shared density policy for the M4 region hierarchy.
 *
 * <p>Level 0 is one 512x512 source region represented by a 64x64 fallback
 * texture (8 blocks/texel). Higher levels follow the same factor-2 hierarchy as
 * Xaero's {@code textureLevel}: every level groups 2x2 children and doubles the
 * blocks represented by one texel. The exact path remains chunk-progressive;
 * this hierarchy is only stable coverage while sharper children arrive.</p>
 */
public final class MapRegionLodPolicy {
    public static final int MAX_LEVEL = 3;
    public static final float DIRECT_PROJECTION_THRESHOLD = 0.50f;
    /** At this density the level-3 region texel (64 blocks) is screen-correct. */
    public static final float REGION_ONLY_THRESHOLD = 0.015625f;

    private MapRegionLodPolicy() { }

    public static boolean directProjectionEnabled(float logicalScale) {
        return Float.isFinite(logicalScale)
                && logicalScale > 0.0f
                && logicalScale < DIRECT_PROJECTION_THRESHOLD;
    }

    /**
     * Coarse region coverage is also required when the visible exact-leaf set is
     * larger than the safe Surface atlas working set. This can happen at a scale
     * slightly above 0.50x on a large fullscreen window: density alone suggests
     * exact L0, but hundreds of leaves cannot remain resident simultaneously.
     * Building a stable 512x512 underlay is cheaper than cycling exact pages.
     */
    public static boolean directProjectionEnabled(float logicalScale,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        if (directProjectionEnabled(logicalScale)) return true;
        long pagesWide = Math.max(1L, (long) maxPageX - minPageX + 1L);
        long pagesHigh = Math.max(1L, (long) maxPageZ - minPageZ + 1L);
        long exactCapacity = (long) MapMemoryBudgetPolicy.surfaceLeafColumns()
                * MapMemoryBudgetPolicy.surfaceLeafColumns();
        long safeWorkingSet = Math.max(64L, exactCapacity * 3L / 4L);
        return pagesWide * pagesHigh > safeWorkingSet;
    }


    /**
     * Extreme far zoom uses the level-3 region hierarchy as visual authority.
     * Exact chunk work may still progress in the background; this predicate only
     * prevents invisible fine quads from bloating the render plan.
     */
    public static boolean regionAuthorityOnly(float logicalScale) {
        return Float.isFinite(logicalScale)
                && logicalScale > 0.0f
                && logicalScale < REGION_ONLY_THRESHOLD;
    }

    public static int targetLevel(float logicalScale) {
        if (logicalScale >= 0.125f) return 0;
        if (logicalScale >= 0.0625f) return 1;
        if (logicalScale >= 0.03125f) return 2;
        return MAX_LEVEL;
    }

    public static int regionSpan(int level) {
        int clamped = Math.max(0, Math.min(MAX_LEVEL, level));
        return 1 << clamped;
    }

    public static int worldSize(int level) {
        long size = 512L * regionSpan(level);
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }
}
