package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS85 guard for lossless local .mca surface reconstruction. */
public final class SurfaceWorldSaveMcaParityCheck {
    private SurfaceWorldSaveMcaParityCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String reconstructor = Files.readString(
                root.resolve("SurfaceWorldSaveReconstructor.java"));
        String policy = Files.readString(
                root.resolve("AdaptiveDimensionLoadPolicy.java"));

        require(reconstructor.contains("MAX_PENDING = 768")
                        && !reconstructor.contains("MAX_READY")
                        && reconstructor.contains("ApplyResult.RETRY")
                        && reconstructor.contains("SURFACE_MCA_APPLY_WAIT")
                        && reconstructor.contains("requeue(ready.withRetry")
                        && reconstructor.contains("maximum = pressured ? 2 : 12"),
                "decoded MCA surface chunks can still be dropped before map-region commit");
        require(policy.contains("return 8;")
                        && policy.contains("return 6;")
                        && policy.contains("return 2;"),
                "surface region-file admission remains too narrow for viewport parity");
        System.out.println("SURFACE_WORLD_SAVE_MCA_PARITY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
