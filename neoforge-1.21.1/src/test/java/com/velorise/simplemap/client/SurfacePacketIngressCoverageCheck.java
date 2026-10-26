package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS108 guard: Minecraft chunk-data arrival is Surface authority across the render view. */
public final class SurfacePacketIngressCoverageCheck {
    private SurfacePacketIngressCoverageCheck() { }

    public static void main(String[] args) throws Exception {
        String mutation = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapMutationBus.java"));
        int start = mutation.indexOf("public synchronized void onChunkData");
        int end = mutation.indexOf("public synchronized void onChunkUnload", start);
        require(start >= 0 && end > start, "missing chunk-data mutation ingress");
        String method = mutation.substring(start, end);
        require(method.contains("markLive(level, chunkX, chunkZ)")
                        && method.contains("enqueueLoadedSurfaceChunk(chunkX, chunkZ)")
                        && !method.contains("SURFACE_TRAVEL_RADIUS_CHUNKS"),
                "Surface packet ingress is still clipped to a fixed player radius");
        require(method.contains("isHotChunk(chunkX, chunkZ)"),
                "Cave packet work lost its separate hot-window gate");
        String scanner = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/ChunkScanner.java"));
        require(scanner.contains("LOADED_SURFACE_QUEUE_LIMIT = 8_192"),
                "Surface packet queue cannot hold one maximum rectangular render view");
        System.out.println("SURFACE_PASS108_PACKET_INGRESS_COVERAGE_PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
