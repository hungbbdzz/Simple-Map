package com.velorise.simplemap.client.gpu;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static architecture guard for asynchronous exact-cave atlas uploads. */
public final class CavePboPipelineCheck {
    private CavePboPipelineCheck() { }

    public static void main(String[] args) throws Exception {
        String atlas = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveTextureAtlas.java"));
        require(atlas.contains("new CaveAtlasPboUploader()"),
                "exact cave atlas must use the pooled PBO uploader");

        String uploader = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/gpu/CaveAtlasPboUploader.java"));
        require(uploader.contains("PBO_RING_SIZE = 3"),
                "PBO uploader must retain a non-blocking ring");
        require(uploader.contains("glBufferData")
                        && uploader.contains("glBufferSubData")
                        && uploader.contains("GL_PIXEL_UNPACK_BUFFER"),
                "PBO uploader must orphan, stage and DMA through unpack storage");
        require(uploader.contains("uploadDirect("),
                "PBO uploader must preserve a direct driver fallback");

        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        require(manager.contains("measuredFrameNanos * 7L / 12L")
                        && manager.contains("measuredFrameNanos / 5L"),
                "cave uploads must use fullscreen/gameplay frame shares");
        System.out.println("CAVE_PBO_PIPELINE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
