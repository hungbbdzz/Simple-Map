package com.velorise.simplemap.client;

import net.minecraft.world.level.biome.Biome;

/**
 * Thread-safe utility that converts a Minecraft {@link Biome} object into
 * RGB colors for grass, foliage (leaves) and water WITHOUT calling any
 * World/Level APIs, making it safe to run on worker threads.
 *
 * The calculation mirrors Minecraft's own BiomeSpecialEffects color lookups
 * (see net.minecraft.world.level.biome.BiomeSpecialEffects):
 *   - Grass & Foliage colors are derived from temperature + downfall and
 *     blended over Minecraft's colormap lookup table.
 *   - Water color comes from biome.getWaterColor().
 *
 * After retrieving the base color, BiomeBlend.blend3x3() should be called
 * to spatially average colors across neighboring pixels for a smooth border.
 */
public final class BiomeColors {

    private BiomeColors() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the ARGB grass color for a given biome.
     * Returns a default plains-like green if the biome has no override.
     */
    public static int getGrassColor(Biome biome) {
        // Biome's special effects may contain a direct grass-color override
        var effects = biome.getSpecialEffects();
        var override = effects.getGrassColorOverride();
        if (override.isPresent()) return 0xFF000000 | override.get();

        // Fall back to temperature/downfall-based colormap
        float temp  = clamp01(biome.getBaseTemperature());
        float humid = clamp01(effects.getFoliageColorOverride().isPresent() ? 0.5f
                : (float) biome.getModifiedClimateSettings().downfall());
        return sampleGrassColormap(temp, humid);
    }

    /**
     * Returns the ARGB foliage (leaves) color for a given biome.
     */
    public static int getFoliageColor(Biome biome) {
        var effects = biome.getSpecialEffects();
        var override = effects.getFoliageColorOverride();
        if (override.isPresent()) return 0xFF000000 | override.get();

        float temp  = clamp01(biome.getBaseTemperature());
        float humid = clamp01((float) biome.getModifiedClimateSettings().downfall());
        return sampleFoliageColormap(temp, humid);
    }

    /**
     * Returns the ARGB water color for a given biome.
     */
    public static int getWaterColor(Biome biome) {
        int col = biome.getSpecialEffects().getWaterColor();
        return 0xFF000000 | col;
    }

    // -------------------------------------------------------------------------
    // Colormap samplers
    // -------------------------------------------------------------------------

    private static int sampleGrassColormap(float temperature, float downfall) {
        return 0xFF000000 | net.minecraft.world.level.GrassColor.get(temperature, downfall);
    }

    private static int sampleFoliageColormap(float temperature, float downfall) {
        return 0xFF000000 | net.minecraft.world.level.FoliageColor.get(temperature, downfall);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }
}
