package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression guard for PASS66 physical-density rendering and foreground completion. */
public final class PhysicalDensityForegroundCompletionCheck {
    private PhysicalDensityForegroundCompletionCheck() { }

    public static void main(String[] args) throws Exception {
        float density = MapRenderScalePolicy.physicalPixelsPerBlock(0.423f, 3.0);
        require(Math.abs(density - 1.269f) < 0.0001f,
                "logical zoom was not converted to physical framebuffer density");
        require(MapLodPolicy.branchLevel(density, 3) == 0,
                "a physically one-pixel-per-block view still selected a coarse branch");

        String fullscreen = read("FullscreenMapFramebufferRenderer.java");
        require(fullscreen.contains("physicalViewportWidth")
                        && fullscreen.contains("physicalViewportHeight")
                        && fullscreen.contains("MapRenderScalePolicy.physicalPixels")
                        && fullscreen.contains("pixelAlignedAxis")
                        && fullscreen.contains("EXACT_STREAMING_REDRAW_NANOS = 125_000_000L")
                        && fullscreen.contains("float cavePolicyScale = renderPixelsPerBlock"),
                "fullscreen map still rasterizes at logical GUI resolution");

        String minimap = read("MinimapFramebufferRenderer.java");
        require(minimap.contains("int targetSize = MapRenderScalePolicy.physicalPixels")
                        && minimap.contains("MapRenderScalePolicy.physicalPixels")
                        && minimap.contains("markLastGood"),
                "minimap lacks physical-resolution rendering or last-good publication");

        String scanner = read("ChunkScanner.java");
        require(scanner.contains("SURFACE_CHUNK_SLICE = 256")
                        && scanner.contains("250_000_000L"),
                "loaded render-distance chunks are still split across slow foreground passes");

        String coordinator = read("MapViewportCoordinator.java");
        require(coordinator.contains("LOADED_SURFACE_HALO_GAME_BUDGET_NANOS = 6_000_000L")
                        && coordinator.contains("LOADED_SURFACE_HALO_FULLSCREEN_BUDGET_NANOS = 5_000_000L"),
                "live render-distance halo lost its foreground completion budget");

        String cave = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        require(cave.contains("CAVE_INACTIVE_VIEW_PRODUCTS_RETIRED")
                        && cave.contains("inactiveProducts")
                        && cave.contains("info.close()"),
                "inactive cave modes can still occupy the exact atlas indefinitely");

        System.out.println("PASS66_PHYSICAL_DENSITY_FOREGROUND_COMPLETION_PASS");
    }

    private static String read(String file) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/" + file));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
