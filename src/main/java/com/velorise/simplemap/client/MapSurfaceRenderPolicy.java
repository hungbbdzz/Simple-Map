package com.velorise.simplemap.client;

/** Surface-only hierarchy choices that are independent from Minecraft rendering. */
public final class MapSurfaceRenderPolicy {
    private MapSurfaceRenderPolicy() {
    }

    /**
     * At density-correct L1 far zoom, a known branch quadrant is the primary
     * representation. Exact L0 remains only for quadrants not yet represented by
     * the branch, preventing nearest-filtered exact pages from covering L1 again.
     */
    public static boolean useBranchInsteadOfExact(float scale, int level,
            boolean childKnown) {
        return level == 1 && scale < 0.50f && childKnown;
    }
}
