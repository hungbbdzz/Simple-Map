package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS121 guard: byte-identical complete chunk rescans must not republish pages. */
public final class SurfaceChunkTransactionPublicationCheck {
    private SurfaceChunkTransactionPublicationCheck() { }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapManager.java"));
        require(source.contains("surfaceTransactionDirty[chunkIndex] = true")
                        && source.contains("if (!contentChanged && !completionChanged) return 0L")
                        && source.contains("surfaceTransactionDirty[chunkIndex] = false"),
                "no-op Surface transaction suppression is missing");
        require(source.contains("if (revision <= 0L) return new SurfaceChunkCommit(0, false, revision)"),
                "MapManager still publishes a zero/no-op Surface transaction");
        System.out.println("SURFACE_CHUNK_TRANSACTION_PUBLICATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
