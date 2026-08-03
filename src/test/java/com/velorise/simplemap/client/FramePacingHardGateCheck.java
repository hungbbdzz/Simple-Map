package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static regression guard for the runtime-validated travel-streaming policy. */
public final class FramePacingHardGateCheck {
    private FramePacingHardGateCheck() { }

    public static void main(String[] args) throws Exception {
        String gate = readClient("MapActivityGate.java");
        String lanes = readClient("MapRequestLane.java");
        String screen = readClient("MapScreen.java");
        String governor = readClient("MapPerformanceGovernor.java");
        String minimap = readClient("MinimapFramebufferRenderer.java");
        String mutation = readClient("MapMutationBus.java");
        String scanner = readClient("ChunkScanner.java");
        String viewport = readClient("MapViewportCoordinator.java");
        String texture = readClient("MapTextureManager.java");
        String caveScheduler = readCave("CaveDisplayScheduler.java");

        require(gate.contains("MOVEMENT_SETTLE_NANOS = 3_000_000_000L"),
                "movement settle window changed unexpectedly");
        require(gate.contains("blocksForegroundStreaming()"),
                "teleport quarantine is no longer separate from travel streaming");
        require(lanes.contains("MINIMAP(4, 2_000_000, 0, 10_000L)"),
                "minimap lease regressed below the runtime-validated TTL");
        require(lanes.contains("FULLSCREEN(3, 1_250_000, 1, 10_000L)"),
                "fullscreen lease regressed below the runtime-validated TTL");
        require(screen.contains("VIEWPORT_INTERACTION_SETTLE_NANOS = 750_000_000L"),
                "wheel/drag interaction hold no longer covers repeated input");

        require(governor.contains("movementStreamingBudgetNanos(boolean cave)"),
                "bounded movement writer budget was removed");
        require(governor.contains("active ? 900_000L : 1_250_000L")
                        && governor.contains("active ? 650_000L : 900_000L")
                        && governor.contains("active ? 1_600_000L : 2_200_000L")
                        && governor.contains("active ? 1_100_000L : 1_500_000L")
                        && governor.contains("serverMspt >= 40.0D"),
                "adaptive travel writer budgets changed unexpectedly");
        require(governor.contains("if (MapActivityGate.getInstance().blocksMapWork()) return 1"),
                "movement upload page cap regressed");

        require(minimap.contains("TARGET_SIZE = 384")
                        && minimap.contains("CONTENT_SIZE = 256")
                        && minimap.contains("OVERSCAN = 64"),
                "high-resolution retained minimap/guard band regressed");
        require(minimap.contains("GL_TEXTURE_MIN_FILTER")
                        && minimap.contains("GL_TEXTURE_MAG_FILTER")
                        && minimap.contains("GL11.GL_LINEAR")
                        && minimap.contains("GL11.GL_NEAREST"),
                "split minification/magnification sampling was removed");

        int surfaceEnqueue = mutation.indexOf("enqueueLoadedSurfaceChunk(chunkX, chunkZ)");
        int caveEnqueue = mutation.indexOf("enqueueLoadedChunk(chunkX, chunkZ)");
        int movementReturn = mutation.indexOf(
                "if (MapActivityGate.getInstance().blocksMapWork()) return", surfaceEnqueue);
        require(surfaceEnqueue >= 0 && caveEnqueue > surfaceEnqueue
                        && movementReturn > caveEnqueue
                        && mutation.substring(surfaceEnqueue, movementReturn)
                                .contains("isHotChunk(chunkX, chunkZ)"),
                "chunk packet travel ingress lost Surface authority or hot Cave filtering");

        require(scanner.contains("LongArrayFIFOQueue loadedSurfaceChunks")
                        && scanner.contains("LOADED_SURFACE_QUEUE_LIMIT = 384")
                        && scanner.contains("SURFACE_CHUNK_SLICE")
                        && scanner.contains("foregroundUrgentSlice")
                        && scanner.indexOf("scanUrgentLoadedChunks(mc, 0, false, false, urgentDeadline)")
                                < scanner.indexOf("scanQueuedSurfaceChunks(mc, deadline)"),
                "surface fair-share FIFO/coherent chunk slicing regressed");
        require(mutation.contains("SURFACE_TRAVEL_RADIUS_CHUNKS = 6")
                        && mutation.contains("isTravelChunk(chunkX, chunkZ, SURFACE_TRAVEL_RADIUS_CHUNKS)"),
                "surface packet frontier is no longer filtered to the travel window");
        require(scanner.contains("loadedSurfaceChunkCursors.get(chunkKey)")
                        && scanner.contains("!loadedSurfaceChunkSet.contains(key)")
                        && scanner.contains("loadedSurfaceChunkCursors.containsKey(chunkKey)")
                        && scanner.contains("cursor == 0")
                        && scanner.contains("SURFACE_QUEUE_STALE_RADIUS_CHUNKS = 8"),
                "surface cursor ownership or stale FIFO pruning regressed");
        require(caveScheduler.contains("LongArrayFIFOQueue loadedChunkFrontier")
                        && caveScheduler.contains("MAX_LOADED_CHUNK_FRONTIER = 192")
                        && caveScheduler.contains("admitLoadedChunk"),
                "cave travel FIFO/coherent tile admission regressed");
        require(viewport.contains("prepareMovementStreaming()"),
                "viewport scheduler no longer preserves visible lanes during movement");
        require(texture.contains("replaceMinimapDemandWindowIfMoved")
                        && texture.contains("clearAllPageDemandLane(MapRequestLane.MINIMAP)"),
                "minimap travel window no longer retires stale hot-set leases");
        System.out.println("FRAME_PACING_STREAMING_GATE_PASS");
    }

    private static String readClient(String file) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client", file));
    }

    private static String readCave(String file) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave", file));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
