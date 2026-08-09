package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS91 guard for non-blocking scanline publication and exact Top-Y retirement. */
public final class CavePass91ProjectionSweepCheck {
    private CavePass91ProjectionSweepCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        require(manager.contains("activateProjection(\n                dimension, CaveView.LAYERED, projectionTopY)")
                        && manager.contains("retargetedKeys")
                        && manager.contains("completedBuilds.removeIf")
                        && manager.contains("detachPendingLocked(info, true)")
                        && manager.contains("regionExactBacklog.entrySet().removeIf"),
                "same-band Top-Y products are not retired as one projection sweep");
        require(manager.contains("CompletedBuild is already globally ordered")
                        && manager.contains("return true;\n    }\n\n\n    private boolean isBuildAheadEligible")
                        && !manager.contains("return planner.publicationAllows(\n                completed.info().key.globalPageX()"),
                "CPU-ready pages are still trapped behind a strict publication prefix");
        require(manager.contains("a slow page must never suppress another coherent resident page")
                        && !manager.contains("return planner.renderAllows(globalPageX, globalPageZ)"),
                "fullscreen rendering still hides coherent exact pages behind a missing prefix");

        String region = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java"));
        require(region.contains("synchronized void activateProjection")
                        && region.contains("readyPages.removeIf")
                        && region.contains("pages.entrySet().removeIf")
                        && region.contains("regions.entrySet().removeIf"),
                "region projection queues do not retire obsolete Top-Y work");
        System.out.println("CAVE_PASS91_PROJECTION_SWEEP_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
