package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS108 guard: Surface source discovery must not recreate circular wavefronts. */
public final class SurfaceRectangularLiveHaloCheck {
    private SurfaceRectangularLiveHaloCheck() { }

    public static void main(String[] args) throws Exception {
        String scanner = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/ChunkScanner.java"));
        int seedStart = scanner.indexOf("private void seedForegroundSurfaceNeighborhood");
        int seedEnd = scanner.indexOf("private boolean claimSurfaceWriterPulse", seedStart);
        if (seedEnd < 0) seedEnd = scanner.indexOf("private", seedStart + 20);
        require(seedStart >= 0, "missing foreground Surface reconciliation cursor");
        String seed = scanner.substring(seedStart, Math.max(seedStart + 1, seedEnd));
        require(!seed.contains("centerChunkX != foregroundSeedChunkX")
                        && !seed.contains("centerChunkZ != foregroundSeedChunkZ")
                        && seed.contains("stableSurfaceRadiusChunks")
                        && seed.contains("effectiveRenderDistance - 1")
                        && seed.contains("foregroundSeedChunkX +")
                        && seed.contains("foregroundSeedChunkZ +"),
                "foreground Surface sweep still restarts on movement or retries the permanent outer edge");

        int orderStart = scanner.indexOf("private int[] getChunkOrder");
        int orderEnd = scanner.indexOf("private boolean isKnownSurfaceRegion", orderStart);
        require(orderStart >= 0 && orderEnd > orderStart, "missing Surface chunk order");
        String order = scanner.substring(orderStart, orderEnd);
        require(!order.contains("dx * dx + dz * dz")
                        && !order.contains("Comparator")
                        && order.contains("int[] result")
                        && order.contains("for (int dz = -requested; dz <= requested; dz++)"),
                "Surface reconciliation still uses Euclidean/boxed radial ordering");

        int priorityStart = scanner.indexOf("private void selectNearestSurfaceQueueHead");
        int priorityEnd = scanner.indexOf("private void scanUrgentLoadedChunks", priorityStart);
        require(priorityStart >= 0 && priorityEnd > priorityStart,
                "missing Surface queue priority method");
        String priority = scanner.substring(priorityStart, priorityEnd);
        require(priority.contains("Math.max(Math.abs(dx), Math.abs(dz))")
                        && !priority.contains("dx * dx + dz * dz"),
                "packet Surface priority can still produce circular publication arcs");
        System.out.println("SURFACE_PASS108_RECTANGULAR_LIVE_HALO_PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
