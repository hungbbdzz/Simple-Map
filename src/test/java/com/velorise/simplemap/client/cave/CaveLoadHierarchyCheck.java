package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

/** Dependency-free ordering invariants for fullscreen Cave source/build/display. */
public final class CaveLoadHierarchyCheck {
    private CaveLoadHierarchyCheck() { }

    public static void main(String[] args) {
        verifyRectangle(-4, 5, -2, 6, 1, 2);
        verifyRectangle(1, 5, 0, 3, 3, 1);
        verifyRectangle(-8, -1, -8, -1, -3, -5);
        verifyRectangle(0, 0, 0, 0, 0, 0);
        verifyFarZoomPolicy();
        exhaustiveSmallRectangles();
        System.out.println("Simple Map Cave centre-out hierarchy checks passed");
    }

    private static void verifyFarZoomPolicy() {
        require(CaveScreenSpacePolicy.branchOnly(0.2956f,
                        MapRequestLane.FULLSCREEN),
                "logged 0.296x cave viewport still admits an exact-page flood");
        require(CaveScreenSpacePolicy.restrictLiveProjectionToFocusPage(0.2488f,
                        MapRequestLane.FULLSCREEN),
                "dimension-switch far zoom still fills the live projection queue");
        require(!CaveScreenSpacePolicy.branchOnly(0.35f,
                        MapRequestLane.FULLSCREEN),
                "near zoom lost exact cave refinement");
        require(!CaveScreenSpacePolicy.branchOnly(0.10f,
                        MapRequestLane.MINIMAP),
                "minimap was incorrectly changed to branch-only");
    }

    private static void exhaustiveSmallRectangles() {
        for (int width = 1; width <= 9; width++) {
            for (int height = 1; height <= 9; height++) {
                int minX = -4;
                int maxX = minX + width - 1;
                int minZ = 3;
                int maxZ = minZ + height - 1;
                for (int centerX = minX - 1; centerX <= maxX + 1; centerX++) {
                    for (int centerZ = minZ - 1; centerZ <= maxZ + 1; centerZ++) {
                        verifyRectangle(minX, maxX, minZ, maxZ,
                                centerX, centerZ);
                    }
                }
            }
        }
    }

    private static void verifyRectangle(int minX, int maxX,
            int minZ, int maxZ, int centerX, int centerZ) {
        long[] plan = CaveLoadHierarchy.buildVisiblePagePlan(
                minX, maxX, minZ, maxZ, centerX, centerZ, true);
        int width = maxX - minX + 1;
        int expected = width * (maxZ - minZ + 1);
        require(plan.length == expected, "visible plan size changed");
        boolean[] seen = new boolean[expected];
        for (int ordinal = 0; ordinal < plan.length; ordinal++) {
            int x = CaveLoadHierarchy.x(plan[ordinal]);
            int z = CaveLoadHierarchy.z(plan[ordinal]);
            require(CaveLoadHierarchy.centerOutOrdinal(
                    minX, maxX, minZ, maxZ,
                    centerX, centerZ, x, z) == ordinal,
                    "source/publication ordinal mismatch");
            int ringStart = CaveLoadHierarchy.centerOutRingStart(
                    minX, maxX, minZ, maxZ,
                    centerX, centerZ, x, z);
            require(ringStart >= 0 && ringStart <= ordinal,
                    "publication ring boundary mismatch");
            int rowMajorIndex = (z - minZ) * width + x - minX;
            require(!seen[rowMajorIndex], "duplicate visible page");
            seen[rowMajorIndex] = true;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
