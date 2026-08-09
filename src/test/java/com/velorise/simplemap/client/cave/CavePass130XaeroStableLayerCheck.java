package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level guard for PASS130 stable minimap cave-layer flow. */
public final class CavePass130XaeroStableLayerCheck {
    private CavePass130XaeroStableLayerCheck() { }

    public static void main(String[] args) throws Exception {
        Path client = Path.of("src/main/java/com/velorise/simplemap/client");
        String facade = Files.readString(client.resolve("CaveTextureManager.java"));
        String renderer = Files.readString(client.resolve("MapRenderer.java"));
        String tree = Files.readString(client.resolve("cave/CaveLodTree.java"));
        String controller = Files.readString(
                client.resolve("cave/v2/CaveProjectionController.java"));

        require(facade.contains("minimapWriterLayerY")
                        && facade.contains("minimapPreviousLayerY")
                        && facade.contains("MINIMAP_LAYER_STABLE_MS")
                        && facade.contains("playerMoving()")
                        && facade.contains("CAVE_MINIMAP_WRITER_LAYER_COMMIT")
                        && facade.contains("CAVE_MINIMAP_WRITER_WINDOW_EXPANDED"),
                "minimap Cave writer is not separated from transient target Y");
        require(controller.contains("projectionLayerForLane(layerY, lane)"),
                "focus projection still bypasses the stable minimap writer layer");
        require(renderer.contains("DenseCaveTile.normalizeLayer(CaveView.LAYERED, caveLayerY)")
                        && renderer.contains("peekFallbackPage(caveLayerY")
                        && renderer.contains("PHASE_L1_EXACT_UNDERLAY"),
                "minimap plan lacks band-stable identity or previous-layer fallback");
        require(tree.contains("MINIMAP_BRANCH_QUIET_NANOS")
                        && tree.contains("MINIMAP_BRANCH_MAX_HOLD_NANOS")
                        && tree.contains("CAVE_BRANCH_PAGE_COALESCED")
                        && tree.contains("lastQueuedNanos")
                        && tree.contains("branchUpdateReady"),
                "Cave branch publication is still eager per child/page revision");
        System.out.println("CAVE_PASS130_XAERO_STABLE_LAYER_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
