package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS95 guard for Xaero-style coarse Surface publication coalescing. */
public final class SurfaceRenderPlanCoalescingCheck {
    private SurfaceRenderPlanCoalescingCheck() { }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapRenderer.java"));
        require(source.contains("SURFACE_HIERARCHY_PLAN_REFRESH_NANOS = 250_000_000L")
                        && source.contains("SURFACE_PRESSURE_PLAN_REFRESH_NANOS = 400_000_000L")
                        && source.contains("caveMode, caveBranchOnly, hierarchyLevel")
                        && source.contains("if (hierarchyLevel > 0)"),
                "Surface hierarchy streaming can still rebuild plans at exact-leaf cadence");
        System.out.println("SURFACE_RENDER_PLAN_COALESCING_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
