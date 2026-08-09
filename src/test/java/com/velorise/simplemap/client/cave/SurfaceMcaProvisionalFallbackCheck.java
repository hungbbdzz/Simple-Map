package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS123 guard for single live Surface authority over decoded disk fallback. */
public final class SurfaceMcaProvisionalFallbackCheck {
    private SurfaceMcaProvisionalFallbackCheck() { }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/SurfaceWorldSaveReconstructor.java"));
        int marker = source.indexOf("SURFACE_MCA_LIVE_AUTHORITY_HANDOFF");
        require(marker >= 0, "missing live Surface authority handoff event");
        int enqueue = source.lastIndexOf("enqueueLiveSurfaceAuthorityChunk", marker);
        int terminal = source.indexOf("return ApplyResult.TERMINAL;", marker);
        int regionApply = source.indexOf("int firstX = key.chunkX << 4;", marker);
        require(enqueue >= 0 && terminal > marker && terminal < regionApply,
                "decoded disk Surface can still publish ahead of a live FULL chunk");
        require(!source.contains("apply_disk_then_live_refresh"),
                "old disk-then-live double publication path remains active");
        require(source.contains("manager.isChunkSurfaceComplete(key.chunkX, key.chunkZ)"),
                "disk result can overwrite a publication that already won");
        System.out.println("SURFACE_PASS123_SINGLE_LIVE_AUTHORITY_PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
