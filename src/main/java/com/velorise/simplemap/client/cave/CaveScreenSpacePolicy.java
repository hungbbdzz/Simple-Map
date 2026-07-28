package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

/**
 * Converts cave-map screen density into bounded exact-page work.
 *
 * <p>An exact page covers 64x64 blocks. At 0.0625x it occupies only 4x4
 * screen pixels, so decoding sixteen chunks and projecting a full vertical
 * cave page for every visible leaf is wasteful. Xaero avoids this by making
 * coarse branch data the foreground representation at far zoom and admitting
 * only a very small number of leaf refinements.</p>
 */
public final class CaveScreenSpacePolicy {
    public static final int EXACT_PAGE_WORLD_SIZE = 64;
    private static final float BRANCH_FIRST_PAGE_PIXELS = 8.0f;
    private static final float SPARSE_EXACT_PAGE_PIXELS = 16.0f;

    private CaveScreenSpacePolicy() {
    }

    public static float exactPagePixels(float pixelsPerBlock) {
        return EXACT_PAGE_WORLD_SIZE * Math.max(0.0001f, pixelsPerBlock);
    }

    /** Minimap always keeps exact leaves; far-zoom fullscreen is branch-first. */
    public static boolean branchFirst(float scale, MapRequestLane lane) {
        return lane == MapRequestLane.FULLSCREEN
                && exactPagePixels(scale) <= BRANCH_FIRST_PAGE_PIXELS;
    }

    /**
     * At this density one 64x64 exact page contributes fewer than 8 screen pixels
     * per edge. Rendering remains branch-only, but one slow coherent leaf seed is
     * still admitted so cold areas can eventually create their branch hierarchy.
     */
    public static boolean branchOnly(float scale, MapRequestLane lane) {
        return branchFirst(scale, lane);
    }

    public static boolean sparseExact(float scale, MapRequestLane lane) {
        return lane == MapRequestLane.FULLSCREEN
                && exactPagePixels(scale) < SPARSE_EXACT_PAGE_PIXELS;
    }

    public static int exactAdmissionBudget(float scale, MapRequestLane lane,
            boolean pressured) {
        if (lane == MapRequestLane.MINIMAP) return pressured ? 1 : 2;
        if (lane == MapRequestLane.BACKGROUND || lane == MapRequestLane.PREFETCH) return 1;
        // A cold far-zoom viewport has no branch/root texture until exact leaves
        // have supplied it with pixels. One leaf every long polling interval was
        // too conservative: a large FULL viewport could remain black for minutes.
        // Keep pressure contraction, but allow a small coherent seed pair while
        // the worker and GPU governors independently bound build/publication cost.
        if (branchOnly(scale, lane)) return pressured ? 1 : 2;
        // Close-zoom fullscreen can keep several region leaves in flight while the
        // worker/IO and GPU governors enforce their own independent limits. Under
        // frame pressure it immediately contracts to one.
        if (pressured) return 1;
        if (scale >= 0.55f) return 3;
        if (scale >= 0.35f) return 2;
        return 1;
    }

    /** Delay expensive exact refinement while branch/root coverage is foreground. */
    public static long exactEnumerationRetryMs(float scale, MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP) return 50L;
        if (branchFirst(scale, lane)) return 120L;
        if (sparseExact(scale, lane)) return 160L;
        if (scale < 0.35f) return 140L;
        if (scale < 0.55f) return 90L;
        return 70L;
    }

    public static long completedPlanPauseMs(float scale, MapRequestLane lane) {
        if (branchFirst(scale, lane)) return 600L;
        if (sparseExact(scale, lane)) return 750L;
        return 750L;
    }

    /**
     * World-save decoding feeds both exact leaves and the branch hierarchy. It is
     * deliberately budgeted separately from exact rendering: screen-space LOD may
     * suppress leaf publication, but it must not starve the source cache that makes
     * coarse FULL coverage possible.
     */
    public static int sourceAdmissionBudget(float scale, MapRequestLane lane,
            boolean pressured) {
        if (lane == MapRequestLane.MINIMAP) return pressured ? 1 : 3;
        if (lane == MapRequestLane.BACKGROUND || lane == MapRequestLane.PREFETCH) {
            return 1;
        }
        if (branchOnly(scale, lane)) return pressured ? 2 : 4;
        if (pressured) return 2;
        if (scale >= 0.55f) return 4;
        if (scale >= 0.35f) return 3;
        return 2;
    }

    public static long sourceEnumerationRetryMs(float scale, MapRequestLane lane,
            boolean pressured) {
        if (lane == MapRequestLane.MINIMAP) return pressured ? 90L : 50L;
        if (branchFirst(scale, lane)) return pressured ? 180L : 100L;
        if (sparseExact(scale, lane)) return pressured ? 180L : 120L;
        return pressured ? 120L : 60L;
    }

    public static long completedSourcePlanPauseMs(float scale,
            MapRequestLane lane, boolean pressured) {
        if (branchFirst(scale, lane)) return pressured ? 600L : 300L;
        if (sparseExact(scale, lane)) return pressured ? 650L : 400L;
        return pressured ? 750L : 500L;
    }

    /**
     * At far zoom, live chunks are useful as a single exact seed, not as a request
     * for every loaded chunk intersecting a huge viewport.
     */
    public static boolean restrictLiveProjectionToFocusPage(float scale,
            MapRequestLane lane) {
        return branchOnly(scale, lane);
    }
}
