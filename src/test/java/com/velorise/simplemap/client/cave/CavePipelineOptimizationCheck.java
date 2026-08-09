package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static guard for Phase 0/1 cave admission and warm-cache coverage. */
public final class CavePipelineOptimizationCheck {
    private CavePipelineOptimizationCheck() { }

    public static void main(String[] args) throws Exception {
        String reader = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveWorldSaveReader.java"))
                .replace("\r\n", "\n");
        require(reader.contains(
                        "if (DISABLED || minecraft == null || minecraft.level == null) return;"),
                "presentation cache replay must not require an integrated server");
        require(reader.contains("if (serverLevel == null) return false;"),
                "remote clients must not claim direct access to server Anvil files");

        String pipeline = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CavePipeline.java"));
        require(pipeline.contains("minChunkX = playerChunkX - liveRadius")
                        && pipeline.contains("maxChunkX = playerChunkX + liveRadius"),
                "fullscreen live projection must cover the full loaded radius");
        require(pipeline.contains("CaveRegionImageCache.getInstance().setBaseDirectory"),
                "world cache setup must configure CIMG storage");

        String scheduler = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveDisplayScheduler.java"));
        require(scheduler.contains("MAX_TASKS = 256"),
                "live projection queue must retain the expanded task budget");
        require(scheduler.contains("governor.hasStreamingHeadroom() ? 8 : 4"),
                "fullscreen page admission must scale with streaming headroom");
        System.out.println("CAVE_PIPELINE_OPTIMIZATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
