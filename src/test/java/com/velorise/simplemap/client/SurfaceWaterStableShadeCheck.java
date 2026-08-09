package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS123 guard for the supplied beta's known-good water compositor. */
public final class SurfaceWaterStableShadeCheck {
    private SurfaceWaterStableShadeCheck() { }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/SurfaceColorizer.java"));
        require(source.contains("0.25f + depth * 0.045f")
                        && source.contains("0.42f + depth * 0.035f")
                        && source.contains("Math.pow(0.975, Math.max(0, depth - 2))")
                        && source.contains("Math.max(0.46f, attenuation)")
                        && !source.contains("depthExtinction")
                        && !source.contains("float waterAlpha = 191.0f / 255.0f"),
                "runtime water path no longer matches the proven beta compositor");
        require(!source.contains("static float waterDepthShade")
                        && source.contains("if (MapBlockData.isFluid(packed)) return 0.0f"),
                "a synthetic fluid terrain/noise shade remains active");

        String branch = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/LodBranchDiskCache.java"));
        require(branch.contains("private static final int VERSION = 16;"),
                "old derived ocean textures were not invalidated");
        System.out.println("SURFACE_WATER_STABLE_SHADE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
