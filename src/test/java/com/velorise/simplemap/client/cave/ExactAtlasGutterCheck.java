package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS78 guard against cross-slot sampling stripes during fractional zoom. */
public final class ExactAtlasGutterCheck {
    private ExactAtlasGutterCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client");
        String surface = Files.readString(root.resolve("cave/SurfaceLeafAtlas.java"));
        String cave = Files.readString(root.resolve("cave/CaveTextureAtlas.java"));
        String memory = Files.readString(root.resolve("MapMemoryBudgetPolicy.java"));
        require(surface.contains("PITCH = SIZE + 2")
                        && surface.contains("* PITCH + 1")
                        && surface.contains("AtlasGutter.copyOnePixelBorder")
                        && surface.contains("upload(slot, colorPixels, glowPixels);"),
                "surface exact leaves do not replicate atlas edges after subtile updates");
        require(cave.contains("int pitch = pageSize + 2")
                        && cave.contains("* pitch + 1")
                        && cave.contains("AtlasGutter.copyOnePixelBorder")
                        && cave.contains("pitch, pitch, guttered"),
                "cave exact mip atlases do not use isolated guttered slots");
        require(memory.contains("long surfaceSide = 66L * surfaceLeafColumns()")
                        && memory.contains("(size + 2) * caveColumns"),
                "GPU memory planning does not account for exact-atlas gutters");
        System.out.println("EXACT_ATLAS_GUTTER_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
