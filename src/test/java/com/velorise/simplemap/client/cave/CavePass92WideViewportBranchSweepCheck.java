package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;
import java.nio.file.Files;
import java.nio.file.Path;

/** PASS92 guard for wide-view branch coverage without player-centred source clamps. */
public final class CavePass92WideViewportBranchSweepCheck {
    private CavePass92WideViewportBranchSweepCheck() { }

    public static void main(String[] args) throws Exception {
        float farScale = 0.2973f;
        require(CaveScreenSpacePolicy.branchOnly(
                        farScale, MapRequestLane.FULLSCREEN),
                "test scale no longer selects the fullscreen branch hierarchy");
        require(!CaveScreenSpacePolicy.restrictLiveProjectionToFocusPage(
                        farScale, MapRequestLane.FULLSCREEN),
                "branch presentation still restricts source projection to the player page");
        require(CaveScreenSpacePolicy.sourceAdmissionBudget(
                        farScale, MapRequestLane.FULLSCREEN, false) >= 48,
                "wide viewport source admission is still a tiny exact-leaf slice");

        Path client = Path.of("src/main/java/com/velorise/simplemap/client");
        Path cave = client.resolve("cave");
        String pipeline = Files.readString(cave.resolve("CavePipeline.java"));
        String manager = Files.readString(
                cave.resolve("UnifiedCaveTextureManager.java"));
        String projection = Files.readString(
                cave.resolve("CaveRegionProjectionService.java"));
        String lod = Files.readString(cave.resolve("CaveLodTree.java"));
        String renderer = Files.readString(client.resolve("MapRenderer.java"));

        require(!pipeline.contains("restrictLiveProjectionToFocusPage("),
                "live cave source coverage is still clamped to the focus page");
        require(manager.contains("isProjectionStillOwned")
                        && manager.contains("planner.matches(key)")
                        && manager.contains("if (!branchOnlyPlan)")
                        && manager.contains("retireBranchOnlyExactWorkLocked")
                        && manager.contains("CAVE_BRANCH_ONLY_EXACT_RETIRE")
                        && manager.contains("regionExactBacklog.remove(key)"),
                "branch-only viewport still depends on exact requests or retains exact backlog");
        require(projection.contains("viewportScanlinePriority")
                        && projection.contains("if ((request.completedMask & bit) == 0L) continue")
                        && projection.contains("if (page == null) continue")
                        && projection.contains("order=viewport_scanline_sweep_top_left"),
                "region projection can still be centre-prioritized or blocked by one incomplete child");
        require(lod.contains("visible.sort(Comparator")
                        && lod.contains("update.key().globalPageZ()")
                        && lod.contains("update.key().globalPageX()")
                        && lod.contains("key.nodeZ() * (1 << key.level())")
                        && lod.contains("key.nodeX() * (1 << key.level())")
                        && !lod.contains("squaredDistance(update"),
                "branch updates are not deterministically row-major");

        int hierarchyMethod = renderer.indexOf("private void collectCaveHierarchy");
        int branchTraversal = renderer.indexOf("if (!centerOutTraversal)", hierarchyMethod);
        int zLoop = renderer.indexOf("for (int nodeZ", branchTraversal);
        int xLoop = renderer.indexOf("for (int nodeX", zLoop);
        require(hierarchyMethod >= 0 && branchTraversal > hierarchyMethod
                        && zLoop > branchTraversal && xLoop > zLoop,
                "fullscreen branch traversal is not Z-row then X-column");

        System.out.println("CAVE_PASS92_WIDE_VIEWPORT_BRANCH_SWEEP_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
