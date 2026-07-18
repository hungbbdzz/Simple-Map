package com.velorise.simplemap.client;

import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/** CPU-only conversion of packed map pixels to final ABGR texture pixels. */
public final class SurfaceColorizer {
    private static final int SIZE = 512;
    private static final int VANILLA_WATER = 0xFF3F76E4;

    private SurfaceColorizer() {
    }

    public static int[] colorize(long[] pixels,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            int colourMode,
            boolean showFlowers,
            int terrainSlopes,
            int profile) {
        return colorize(pixels, biomePalette, blockPalette, biomeLookup, blockColors,
                tintPolicies, colourMode, showFlowers, terrainSlopes, profile, () -> true);
    }

    public static int[] colorize(long[] pixels,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            int colourMode,
            boolean showFlowers,
            int terrainSlopes,
            int profile,
            BooleanSupplier stillValid) {
        int[] output = new int[SIZE * SIZE];
        for (int pz = 0; pz < SIZE; pz++) {
            if ((pz & 31) == 0 && !stillValid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Stale SimpleMap texture job");
            }
            for (int px = 0; px < SIZE; px++) {
                int index = pz * SIZE + px;
                long packed = pixels[index];
                if (MapBlockData.isEmpty(packed)) continue;
                if (MapBlockData.isFlower(packed) && !showFlowers) continue;

                int baseColor = resolveBaseColor(packed, blockPalette, blockColors, tintPolicies,
                        biomeLookup, pixels, px, pz, colourMode);
                if (baseColor == 0) continue;

                int red = (baseColor >>> 16) & 0xFF;
                int green = (baseColor >>> 8) & 0xFF;
                int blue = baseColor & 0xFF;
                int reliefY = MapBlockData.reliefY(packed);

                float shade = calculateShade(pixels, px, pz, reliefY, terrainSlopes);
                shade *= 1.0f + microNoise(index, packed, reliefY);
                red = clamp(Math.round(red * shade));
                green = clamp(Math.round(green * shade));
                blue = clamp(Math.round(blue * shade));

                int argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
                if (colourMode == 0) {
                    argb = applyAccurateFinish(argb,
                            MapBlockData.isLeaves(packed),
                            MapBlockData.isFluid(packed));
                }
                int abgr = 0xFF000000 | ((argb & 0xFF) << 16)
                        | (argb & 0x0000FF00)
                        | ((argb >>> 16) & 0xFF);
                output[index] = MapColorProfile.apply(abgr, profile);
            }
        }
        return output;
    }

    private static int resolveBaseColor(long packed,
            List<String> blockPalette,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            IntFunction<Biome> biomeLookup,
            long[] allPixels,
            int px, int pz,
            int colourMode) {
        String blockName = blockId(packed, blockPalette);

        if (MapBlockData.isFluid(packed)) {
            // Fluid + glowing is the compact representation used for lava.
            if (MapBlockData.isGlowing(packed)) return 0xFFF3A52B;

            int waterTint = colourMode == 0
                    ? BiomeBlend.blendWater(allPixels, biomeLookup, px, pz)
                    : VANILLA_WATER;
            if (waterTint == -1) waterTint = VANILLA_WATER;

            // For water pixels blockId points at the solid floor, not the water
            // block. This lets shallow water reveal sand/stone/grass instead of
            // becoming one flat blue sheet.
            int floorColor = blockName == null
                    ? 0xFF5E6B63
                    : blockColors.getOrDefault(blockName, 0xFF5E6B63);
            int depth = Math.min(64, MapBlockData.waterDepth(packed));
            float waterAmount;
            if (colourMode == 1) {
                waterAmount = clamp01(0.42f + depth * 0.035f);
            } else {
                waterAmount = clamp01(0.25f + depth * 0.045f);
            }
            waterAmount = Math.min(colourMode == 1 ? 0.94f : 0.92f, waterAmount);
            int mixed = mixRgb(floorColor, waterTint, waterAmount);

            // Exponential attenuation gives deep water a readable dark core while
            // preserving bottom detail in shallow rivers and shorelines.
            float attenuation = (float) Math.pow(0.975, Math.max(0, depth - 2));
            attenuation = Math.max(0.46f, attenuation);
            return scaleRgb(mixed, attenuation);
        }

        int texture = blockName == null ? 0 : blockColors.getOrDefault(blockName, 0);
        BlockTintPolicy policy = blockName == null
                ? BlockTintPolicy.NONE
                : tintPolicies.getOrDefault(blockName, BlockTintPolicy.NONE);

        if (MapBlockData.isLeaves(packed)) {
            if (texture == 0) texture = 0xFF71845F;
            if (colourMode != 0) return texture;
            return switch (policy) {
                case SPRUCE -> applyTintPreservingTexture(texture, 0xFF619961, 0.82f);
                case BIRCH -> applyTintPreservingTexture(texture, 0xFF80A755, 0.82f);
                case FOLIAGE -> {
                    int tint = BiomeBlend.blendFoliage(allPixels, biomeLookup, px, pz);
                    yield tint == -1 ? texture : applyTintPreservingTexture(texture, tint, 0.88f);
                }
                case GRASS -> {
                    int tint = BiomeBlend.blendGrass(allPixels, biomeLookup, px, pz);
                    yield tint == -1 ? texture : applyTintPreservingTexture(texture, tint, 0.84f);
                }
                case NONE -> texture;
            };
        }

        if (blockName != null && (blockName.contains("glass") || blockName.contains("barrier"))) {
            return 0;
        }

        if (colourMode == 0 && texture != 0) {
            if (policy == BlockTintPolicy.GRASS) {
                int tint = BiomeBlend.blendGrass(allPixels, biomeLookup, px, pz);
                return tint == -1 ? texture : applyTintPreservingTexture(texture, tint, 0.88f);
            }
            if (policy == BlockTintPolicy.FOLIAGE) {
                int tint = BiomeBlend.blendFoliage(allPixels, biomeLookup, px, pz);
                return tint == -1 ? texture : applyTintPreservingTexture(texture, tint, 0.84f);
            }
        }

        return texture;
    }

    /**
     * Strong relief intended to read cliffs, pits and stepped terrain at minimap
     * scale. Water uses its stored floorY, so a lake no longer looks like a raised
     * plateau merely because its surface is level with the shore.
     */
    private static float calculateShade(long[] pixels, int px, int pz,
            int centerY, int terrainSlopes) {
        if (terrainSlopes <= 0) return 1.0f;
        int north = neighborY(pixels, px, pz - 1, centerY);
        if (terrainSlopes == 1) {
            int delta = centerY - north;
            float stepShadow = delta < 0 ? Math.max(-0.34f, delta * 0.060f)
                    : Math.min(0.20f, delta * 0.040f);
            return clamp(1.0f + stepShadow, 0.58f, 1.28f);
        }

        int south = neighborY(pixels, px, pz + 1, centerY);
        int west = neighborY(pixels, px - 1, pz, centerY);
        int east = neighborY(pixels, px + 1, pz, centerY);

        float dx = clamp((west - east) * 0.5f, -16.0f, 16.0f);
        float dz = clamp((north - south) * 0.5f, -16.0f, 16.0f);
        float directional = dx * 0.045f + dz * 0.062f; // light from north-west

        int highest = Math.max(Math.max(north, south), Math.max(west, east));
        int lowest = Math.min(Math.min(north, south), Math.min(west, east));
        int dropBelowRim = Math.max(0, highest - centerY);
        int riseAboveNeighbours = Math.max(0, centerY - lowest);
        float pitShadow = Math.min(0.46f, dropBelowRim * 0.065f);
        float ridgeLight = Math.min(0.10f, riseAboveNeighbours * 0.018f);

        float roughness = Math.min(0.22f,
                (Math.abs(west - east) + Math.abs(north - south)) * 0.014f);
        float shade = 1.0f + directional + ridgeLight - pitShadow - roughness;
        return clamp(shade, 0.42f, 1.28f);
    }

    private static int neighborY(long[] pixels, int px, int pz, int fallback) {
        if (px < 0 || px >= SIZE || pz < 0 || pz >= SIZE) return fallback;
        long packed = pixels[pz * SIZE + px];
        return MapBlockData.isEmpty(packed) ? fallback : MapBlockData.reliefY(packed);
    }

    private static float microNoise(int linearIndex, long packed, int y) {
        long hash = ((long) linearIndex * 312251L) ^ ((long) y * 4390321L);
        hash = (hash ^ (hash >>> 16)) * 0x85ebca6bL;
        hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35L;
        float noise = ((hash & 0xFFFFL) / 65535.0f) * 2.0f - 1.0f;
        if (MapBlockData.isLeaves(packed)) return noise * 0.075f;
        if (MapBlockData.isFluid(packed)) return noise * 0.012f;
        return noise * 0.022f;
    }

    /**
     * Colours a texture without replacing its luminance with a bright flat tint.
     * The previous implementation divided texture luminance by 150, which pushed
     * many leaves above 1.0 and caused the pale/white film visible in Accurate.
     */
    static int applyTintPreservingTexture(int textureArgb, int tintArgb, float strength) {
        float baseR = ((textureArgb >>> 16) & 0xFF);
        float baseG = ((textureArgb >>> 8) & 0xFF);
        float baseB = (textureArgb & 0xFF);
        float tintR = ((tintArgb >>> 16) & 0xFF) / 255.0f;
        float tintG = ((tintArgb >>> 8) & 0xFF) / 255.0f;
        float tintB = (tintArgb & 0xFF) / 255.0f;
        float tintLuma = Math.max(0.18f, tintR * 0.2126f + tintG * 0.7152f + tintB * 0.0722f);

        float factorR = clamp(tintR / tintLuma, 0.42f, 1.58f);
        float factorG = clamp(tintG / tintLuma, 0.42f, 1.58f);
        float factorB = clamp(tintB / tintLuma, 0.42f, 1.58f);
        float modR = baseR * factorR;
        float modG = baseG * factorG;
        float modB = baseB * factorB;
        float amount = clamp(strength, 0.0f, 1.0f);
        float outR = baseR + (modR - baseR) * amount;
        float outG = baseG + (modG - baseG) * amount;
        float outB = baseB + (modB - baseB) * amount;

        float baseLuma = baseR * 0.2126f + baseG * 0.7152f + baseB * 0.0722f;
        float outLuma = Math.max(1.0f, outR * 0.2126f + outG * 0.7152f + outB * 0.0722f);
        float preserve = clamp(baseLuma / outLuma, 0.70f, 1.00f);
        int r = clamp(Math.round(outR * preserve));
        int g = clamp(Math.round(outG * preserve));
        int b = clamp(Math.round(outB * preserve));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }


    /**
     * Accurate mode should stay slightly darker and more grounded than vanilla,
     * especially on normal Minecraft foliage and terrain. Apply a gentle
     * mid-tone compression instead of the faint bright film users reported.
     */
    private static int applyAccurateFinish(int argb, boolean leaves, boolean fluid) {
        float red = ((argb >>> 16) & 0xFF) / 255.0f;
        float green = ((argb >>> 8) & 0xFF) / 255.0f;
        float blue = (argb & 0xFF) / 255.0f;

        float luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
        float saturation = fluid ? 1.00f : (leaves ? 1.04f : 1.02f);
        red = luminance + (red - luminance) * saturation;
        green = luminance + (green - luminance) * saturation;
        blue = luminance + (blue - luminance) * saturation;

        float gamma = fluid ? 1.04f : (leaves ? 1.14f : 1.10f);
        red = (float) Math.pow(clamp01(red), gamma);
        green = (float) Math.pow(clamp01(green), gamma);
        blue = (float) Math.pow(clamp01(blue), gamma);

        float contrast = fluid ? 1.02f : (leaves ? 1.08f : 1.05f);
        float brightness = fluid ? 0.97f : (leaves ? 0.90f : 0.94f);
        red = ((red - 0.5f) * contrast + 0.5f) * brightness;
        green = ((green - 0.5f) * contrast + 0.5f) * brightness;
        blue = ((blue - 0.5f) * contrast + 0.5f) * brightness;

        int r = clamp(Math.round(clamp01(red) * 255.0f));
        int g = clamp(Math.round(clamp01(green) * 255.0f));
        int b = clamp(Math.round(clamp01(blue) * 255.0f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mixRgb(int first, int second, float amount) {
        float inverse = 1.0f - amount;
        int r = clamp(Math.round(((first >>> 16) & 0xFF) * inverse
                + ((second >>> 16) & 0xFF) * amount));
        int g = clamp(Math.round(((first >>> 8) & 0xFF) * inverse
                + ((second >>> 8) & 0xFF) * amount));
        int b = clamp(Math.round((first & 0xFF) * inverse + (second & 0xFF) * amount));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int scaleRgb(int argb, float amount) {
        int r = clamp(Math.round(((argb >>> 16) & 0xFF) * amount));
        int g = clamp(Math.round(((argb >>> 8) & 0xFF) * amount));
        int b = clamp(Math.round((argb & 0xFF) * amount));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String blockId(long packed, List<String> palette) {
        int index = MapBlockData.blockId(packed) & 0xFFFF;
        return index == (MapBlockData.NO_BLOCK & 0xFFFF) || index >= palette.size()
                ? null : palette.get(index);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }
}
