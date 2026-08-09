package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS89 guard for CPU-complete row admission and contiguous GPU reveal. */
public final class CavePass89RowAtomicPublicationCheck {
    private CavePass89RowAtomicPublicationCheck() { }

    public static void main(String[] args) throws Exception {
        long[] plan = CaveLoadHierarchy.buildViewportScanlinePlan(-2, 2, 4, 6);
        require(plan.length == 15, "unexpected row-major plan size");
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 5; column++) {
                int ordinal = row * 5 + column;
                require(CaveLoadHierarchy.x(plan[ordinal]) == -2 + column
                                && CaveLoadHierarchy.z(plan[ordinal]) == 4 + row,
                        "page plan is not a fixed world-anchored scanline");
            }
        }

        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        String gpu = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapGpuBudgetController.java"));
        require(manager.contains("publicationAdmitEndOrdinal")
                        && manager.contains("FULLSCREEN_PUBLICATION_WINDOW_PAGES = 128")
                        && manager.contains("renderAllows")
                        && manager.contains("targetAdmitEnd")
                        && manager.contains("order=viewport_scanline_sweep_top_left")
                        && !manager.contains("CAVE_PUBLICATION_ROW_OPEN")
                        && !manager.contains("boolean rowPrepared"),
                "bounded scanline-prefix publication frontiers are missing");
        require(manager.contains("case FULLSCREEN -> 640")
                        && manager.contains("FULLSCREEN_PUBLICATION_BURST = 32")
                        && manager.contains("FULLSCREEN_BUILD_AHEAD_PAGES = 640"),
                "fullscreen source/build runway is still capped too narrowly");
        require(count(manager, "drainRegionProjectedPages(importedPageBudget, deadline, now);") >= 2,
                "CPU-ready region rows still pay an avoidable frame bubble");
        require(gpu.contains("FULLSCREEN_CAVE_WAVEFRONT_EXTRA_NANOS")
                        && gpu.contains("tryAdmitFullscreenCaveWavefront")
                        && gpu.contains("lane != MapRequestLane.FULLSCREEN"),
                "focused cave wavefront GPU allowance is missing or unbounded");
        require(!manager.contains("publicationOrdinal = 0;")
                        && !manager.contains("order=center_out_with_stall_bypass"),
                "single-page auto-open or random stall bypass remains active");
        System.out.println("CAVE_PASS90_SCANLINE_WINDOW_PUBLICATION_PASS");
    }

    private static int count(String source, String token) {
        int result = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            result++;
            offset += token.length();
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
