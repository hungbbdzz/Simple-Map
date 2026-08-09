package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS123 guard for canonical water geometry plus beta visual composition. */
public final class SurfaceWaterColumnAuthorityCheck {
    private SurfaceWaterColumnAuthorityCheck() { }

    public static void main(String[] args) throws Exception {
        String decoded = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/DecodedWorldChunkSource.java"));
        require(decoded.contains("findConnectedWaterSurfaceY")
                        && decoded.contains("WORLD_SURFACE normally starts above the liquid surface")
                        && decoded.contains("visibleY = findConnectedWaterSurfaceY"),
                "world-save Surface can still treat submerged plants as the water top");

        String scanner = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/ChunkScanner.java"));
        require(scanner.contains("findConnectedLiveWaterSurfaceY")
                        && scanner.contains("connectedSurfaceY = findConnectedLiveWaterSurfaceY")
                        && scanner.contains("enqueueLiveSurfaceAuthorityChunk"),
                "live Surface water geometry/single authority path is missing");

        String colorizer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/SurfaceColorizer.java"));
        require(colorizer.contains("String floorId = blockId(packed, blockPalette)")
                        && colorizer.contains("MapBlockData.waterDepth(packed)")
                        && colorizer.contains("0.25f + depth * 0.045f")
                        && colorizer.contains("composeWaterOverlay(waterTint, floorColor, waterDepth"),
                "water no longer uses exact stored columns with the beta compositor");
        System.out.println("SURFACE_WATER_COLUMN_AUTHORITY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
