package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;
import java.nio.file.Files;
import java.nio.file.Path;

/** PASS94 guard: cache warmup must never retarget the visible cave projection. */
public final class CavePass94ProjectionIsolationCheck {
    private CavePass94ProjectionIsolationCheck() { }

    public static void main(String[] args) throws Exception {
        require(CaveProjectionOwnershipPolicy.ownsActiveProjection(
                        MapRequestLane.MINIMAP),
                "minimap lost visible projection ownership");
        require(CaveProjectionOwnershipPolicy.ownsActiveProjection(
                        MapRequestLane.FULLSCREEN),
                "fullscreen lost visible projection ownership");
        require(!CaveProjectionOwnershipPolicy.ownsActiveProjection(
                        MapRequestLane.BACKGROUND),
                "background warmup can still retarget the visible projection");
        require(!CaveProjectionOwnershipPolicy.ownsActiveProjection(
                        MapRequestLane.PREFETCH),
                "prefetch can still retarget the visible projection");

        Path cave = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String pipeline = Files.readString(cave.resolve("CavePipeline.java"));
        String manager = Files.readString(
                cave.resolve("UnifiedCaveTextureManager.java"));
        int warmStart = pipeline.indexOf("public void warmLayerBand");
        int warmEnd = pipeline.indexOf("private void markAndEnqueueWarmupRing", warmStart);
        require(warmStart >= 0 && warmEnd > warmStart,
                "warmLayerBand implementation was not found");
        String warmBody = pipeline.substring(warmStart, warmEnd);
        require(!warmBody.contains("requestVisiblePages("),
                "adjacent-band warmup still enters the presentation pipeline");
        require(manager.contains("view == CaveView.LAYERED && ownsActiveProjection")
                        && manager.contains("CaveProjectionOwnershipPolicy")
                        && manager.contains("if (ownsActiveProjection"),
                "visible projection activation is not lane-isolated");

        System.out.println("CAVE_PASS94_PROJECTION_ISOLATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
