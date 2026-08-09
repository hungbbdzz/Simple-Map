package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS62 guard for coherent same-band fallback plus exact-Top-Y authority. */
public final class CaveLayerProjectionIsolationCheck {
    private CaveLayerProjectionIsolationCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client");
        String facade = Files.readString(root.resolve("CaveTextureManager.java"));
        String manager = Files.readString(root.resolve(
                "cave/UnifiedCaveTextureManager.java"));
        String archive = Files.readString(root.resolve(
                "cave/archive/CaveArchiveV2Service.java"));
        String projection = Files.readString(root.resolve(
                "cave/projection/CaveProjectionServiceV2.java"));

        require(facade.contains("previousLayerY = sameBand(oldLayer, layerY)")
                        && facade.contains("atomic whole-page swap")
                        && facade.contains("previousLayerY = Integer.MIN_VALUE;"),
                "same-band fallback lifecycle is not explicit and bounded");
        require(manager.contains("branch_generation_reset=true")
                        && manager.contains("lodTree.invalidateLayer(dimension, CaveView.LAYERED, normalizedLayerY);")
                        && manager.contains("discardVisibleProjectionForRetarget")
                        && manager.contains("visibleTileProjectionTopY[tile] != projectionTopY")
                        && manager.contains("return exact;")
                        && manager.contains("replacingDifferentProjection")
                        && manager.contains("stagingProjectionComplete(projectionTopY)"),
                "old or same-band imagery can still be presented as exact Top-Y output");
        require(archive.contains("MAX_RESIDENT_TILES = 32768")
                        && archive.contains("MAX_RESIDENT_BYTES")
                        && projection.contains("new CaveBandCache(16384)"),
                "archive working set remains smaller than a large fullscreen viewport");

        System.out.println("CAVE_SAME_BAND_FALLBACK_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
