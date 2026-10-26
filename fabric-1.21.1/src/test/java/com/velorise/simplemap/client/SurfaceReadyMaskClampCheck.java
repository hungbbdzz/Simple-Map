package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS109 guard against partial-page worker admission and zero-update churn. */
public final class SurfaceReadyMaskClampCheck {
    private SurfaceReadyMaskClampCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapTextureManager.java"));
        require(manager.contains("required != MapPageLayout.FULL_SUBTILE_MASK")
                        && manager.contains("requestedSubtileMasks[index] = 0"),
                "incomplete retained pages can still be attached to a Surface batch");
        require(manager.contains("(requestedSubtileMasks[index] & required)"),
                "Surface dirty masks are not clamped to retained body authority");
        require(manager.contains("if (requestedSubtileMasks[index] == 0)")
                        && manager.contains("attachPage[index] = false")
                        && manager.contains("requestedPageHasReadyWork")
                        && manager.contains("settleSurfacePageWithoutCompleteSource(requested, lane)"),
                "zero-work Surface leaves can still reach worker submission");
        System.out.println("SURFACE_PASS109_READY_MASK_CLAMP_PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
