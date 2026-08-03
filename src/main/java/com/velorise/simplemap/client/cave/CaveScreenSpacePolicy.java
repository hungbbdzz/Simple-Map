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
    /*
     * Below twenty screen pixels an exact 64x64 cave page is already smaller than
     * one third of its native edge and wide fullscreen views exceed the exact atlas
     * working set. Keeping exact traversal active at 0.25-0.30x admitted 390-500
     * pages and produced 80-90 ms render-plan/client-thread spikes. Branch textures
     * are density-correct here and exact work remains available as bounded seeds.
     */
    private static final float BRANCH_FIRST_PAGE_PIXELS = 20.0f;
    private static final float SPARSE_EXACT_PAGE_PIXELS = 24.0f;

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
     * At this density one 64x64 exact page contributes no more than 20 screen pixels
     * per edge. Rendering remains branch-only, but a bounded coherent leaf seed is
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
        int normal;
        if (lane == MapRequestLane.MINIMAP) normal = pressured ? 1 : 4;
        else if (lane == MapRequestLane.BACKGROUND || lane == MapRequestLane.PREFETCH) normal = 1;
        else if (branchOnly(scale, lane)) normal = pressured ? 1 : 3;
        else if (pressured) normal = 1;
        else if (scale >= 0.55f) normal = 8;
        else if (scale >= 0.35f) normal = 6;
        else if (scale >= 0.20f) normal = 4;
        else normal = 2;
        return CaveModeTransitionPolicy.exactAdmissionBudget(normal);
    }

    /** Delay expensive exact refinement while branch/root coverage is foreground. */
    public static long exactEnumerationRetryMs(float scale, MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP) return 25L;
        if (branchFirst(scale, lane)) return 55L;
        if (sparseExact(scale, lane)) return 70L;
        if (scale < 0.35f) return 60L;
        if (scale < 0.55f) return 40L;
        return 25L;
    }

    public static long completedPlanPauseMs(float scale, MapRequestLane lane) {
        if (branchFirst(scale, lane)) return 180L;
        if (sparseExact(scale, lane)) return 160L;
        return 120L;
    }

    /**
     * World-save decoding feeds both exact leaves and the branch hierarchy. It is
     * deliberately budgeted separately from exact rendering: screen-space LOD may
     * suppress leaf publication, but it must not starve the source cache that makes
     * coarse FULL coverage possible.
     */
    public static int sourceAdmissionBudget(float scale, MapRequestLane lane,
            boolean pressured) {
        int normal;
        if (lane == MapRequestLane.MINIMAP) normal = pressured ? 1 : 4;
        else if (lane == MapRequestLane.BACKGROUND || lane == MapRequestLane.PREFETCH) normal = 1;
        else if (branchOnly(scale, lane)) normal = pressured ? 2 : 6;
        else if (pressured) normal = 2;
        else if (scale >= 0.55f) normal = 12;
        else if (scale >= 0.35f) normal = 10;
        else if (scale >= 0.18f) normal = 8;
        else normal = 6;
        return CaveModeTransitionPolicy.sourceAdmissionBudget(normal);
    }

    public static long sourceEnumerationRetryMs(float scale, MapRequestLane lane,
            boolean pressured) {
        if (lane == MapRequestLane.MINIMAP) return pressured ? 70L : 25L;
        if (branchFirst(scale, lane)) return pressured ? 140L : 50L;
        if (sparseExact(scale, lane)) return pressured ? 140L : 60L;
        return pressured ? 100L : 30L;
    }

    public static long completedSourcePlanPauseMs(float scale,
            MapRequestLane lane, boolean pressured) {
        if (branchFirst(scale, lane)) return pressured ? 500L : 140L;
        if (sparseExact(scale, lane)) return pressured ? 550L : 160L;
        return pressured ? 650L : 120L;
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
