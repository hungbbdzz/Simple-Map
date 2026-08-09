package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS84 guard against persisting transient BlockColors values per chunk. */
public final class SurfaceBiomeTintSeamCheck {
    private SurfaceBiomeTintSeamCheck() { }

    public static void main(String[] args) throws Exception {
        require(SurfaceColorizer.usesStoredProviderTint(BlockTintPolicy.NONE),
                "custom provider tint must remain supported");
        require(!SurfaceColorizer.usesStoredProviderTint(BlockTintPolicy.GRASS)
                        && !SurfaceColorizer.usesStoredProviderTint(BlockTintPolicy.FOLIAGE)
                        && !SurfaceColorizer.usesStoredProviderTint(BlockTintPolicy.SPRUCE)
                        && !SurfaceColorizer.usesStoredProviderTint(BlockTintPolicy.BIRCH),
                "biome-driven tint can still be frozen per 16x16 chunk");

        String scanner = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/ChunkScanner.java"));
        require(scanner.contains("visual.tintPolicy() != BlockTintPolicy.NONE")
                        && scanner.contains("return SurfaceTintData.NONE;"),
                "scanner still persists transient standard biome tint");
        String cache = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/LodBranchDiskCache.java"));
        require(cache.contains("private static final int VERSION = 11;"),
                "old seam-bearing derived branch cache is still reusable");
        System.out.println("SURFACE_BIOME_TINT_SEAM_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
