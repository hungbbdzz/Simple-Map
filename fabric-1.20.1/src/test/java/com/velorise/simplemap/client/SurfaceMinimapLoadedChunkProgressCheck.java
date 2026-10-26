package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS123 guard for coherent FULL-centre Surface progress and single live authority. */
public final class SurfaceMinimapLoadedChunkProgressCheck {
    private SurfaceMinimapLoadedChunkProgressCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client");
        String scanner = Files.readString(root.resolve("ChunkScanner.java"));
        String decoded = Files.readString(
                root.resolve("cave/DecodedWorldChunkSource.java"));
        String reconstructor = Files.readString(
                root.resolve("cave/SurfaceWorldSaveReconstructor.java"));

        int readyStart = scanner.indexOf("private static LevelChunk readySurfaceChunk");
        int readyEnd = scanner.indexOf("private static boolean isSurfaceChunkReady",
                readyStart);
        require(readyStart >= 0 && readyEnd > readyStart,
                "missing Surface readiness method");
        String ready = scanner.substring(readyStart, readyEnd);
        require(ready.contains("ChunkStatus.FULL")
                        && ready.contains("EmptyLevelChunk"),
                "live Surface can publish from a non-FULL/empty centre body");
        require(decoded.contains("authoritativeSurfaceSource")
                        && decoded.contains("\"full\".equals(chunkStatus)")
                        && decoded.contains("\"minecraft:full\".equals(chunkStatus)"),
                "non-FULL Anvil chunks can still become authoritative Surface data");
        require(reconstructor.contains("SURFACE_MCA_LIVE_AUTHORITY_HANDOFF")
                        && reconstructor.contains("enqueueLiveSurfaceAuthorityChunk")
                        && reconstructor.contains("hasAuthoritativeSurfaceSource()")
                        && reconstructor.contains("drop_disk_keep_live_writer"),
                "disk/live Surface does not preserve one live writer authority");
        require(reconstructor.contains("publishCompletedChunk("),
                "accepted disk-only Surface reconstruction does not publish retained source");
        System.out.println("SURFACE_PASS123_STABLE_LIVE_AUTHORITY_PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
