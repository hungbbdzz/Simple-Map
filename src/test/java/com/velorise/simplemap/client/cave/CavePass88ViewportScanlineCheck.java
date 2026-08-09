package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS88 guard for deterministic fullscreen publication and exact first display. */
public final class CavePass88ViewportScanlineCheck {
    private CavePass88ViewportScanlineCheck() { }

    public static void main(String[] args) throws Exception {
        long[] plan = CaveLoadHierarchy.buildVisiblePagePlan(
                -3, 4, -2, 5, 0, 0, true);
        for (int ordinal = 0; ordinal < plan.length; ordinal++) {
            int x = CaveLoadHierarchy.x(plan[ordinal]);
            int z = CaveLoadHierarchy.z(plan[ordinal]);
            require(ordinal == CaveLoadHierarchy.scanlineOrdinal(
                            -3, 4, -2, 5, x, z),
                    "fullscreen page plan is not top-left scanline order");
        }

        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        String projection = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java"));
        String reader = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveWorldSaveReader.java"));
        String importer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java"));
        String branchCache = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/LodBranchDiskCache.java"));
        String renderer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapRenderer.java"));
        String memoryPolicy = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapMemoryBudgetPolicy.java"));

        require(manager.contains("FULLSCREEN_STICKY_HALO_PAGES = 0")
                        && manager.contains("FULLSCREEN_BUILD_AHEAD_PAGES = 640")
                        && manager.contains("releaseObsoleteFullscreenResidencyLocked")
                        && manager.contains("discardVisibleProjectionForRetarget")
                        && manager.contains("order=viewport_scanline_sweep_top_left"),
                "fullscreen residency/scanline controls are missing");
        require(!manager.contains("order=center_out_with_stall_bypass")
                        && !manager.contains("strict_center_out_prefix")
                        && !manager.contains("FULLSCREEN_PUBLICATION_STALL_MS")
                        && !projection.contains("FOREGROUND_STALL_RELEASE_MS"),
                "random stall bypass publication remains active");
        require(projection.contains("order=viewport_scanline_sweep_top_left")
                        && reader.contains("order=viewport_scanline_sweep_top_left")
                        && importer.contains("buildSourceOrder"),
                "source, projection and publication no longer share scanline order");
        require(manager.contains("visibleTileProjectionTopY[tile] != projectionTopY")
                        && manager.contains("return exact;")
                        && !manager.contains("CaveLayerBand.same(CaveView.LAYERED"),
                "old/same-band cave imagery can still masquerade as first-pass exact output");
        require(branchCache.contains("private static final int VERSION = 11;"),
                "derived surface/cave branch cache was not invalidated");
        require(memoryPolicy.contains("new Profile(24, 36, 16, 12, 12, 96, 20L)"),
                "balanced GPU profile lacks exact-page headroom for a large viewport");
        int exactLevel = renderer.indexOf("if (level <= 0)");
        int exactTraversal = renderer.indexOf("if (!centerOutTraversal)", exactLevel);
        require(exactLevel >= 0 && exactTraversal > exactLevel
                        && !renderer.substring(exactLevel, exactTraversal)
                                .contains("collectCaveLevelZeroUnderlay"),
                "fresh exact cave output still renders a blurry branch underlay");
        System.out.println("CAVE_PASS88_VIEWPORT_SCANLINE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
