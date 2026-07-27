package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.FullCaveMapManager;
import com.velorise.simplemap.client.MapColorProfile;
import com.velorise.simplemap.client.MapConfig;

/** Styles one independent 64x64 cave page without rebuilding its source tiles. */
public final class CavePageStyler {
    private static final int SIZE = 64;
    private static final int BORDERED_SIZE = 66;
    private static final int XAERO_GLOW_MIN_RGB_SUM = 407;

    private CavePageStyler() {
    }

    /** Compatibility overload for old callers/tests. */
    public static int[] style(int[] source, short[] heights) {
        return style(source, heights, null, null, null,
                CaveView.FULL, Integer.MIN_VALUE);
    }

    /** Compatibility path for legacy pages whose overlay was already composited. */
    public static int[] style(int[] source, short[] heights,
            short[] topHeights, byte[] flags, byte[] lights,
            CaveView view, int layerY) {
        int[] output = new int[source.length];
        boolean[] emissivePixels = new boolean[Math.min(source.length, SIZE * SIZE)];
        int slopes = MapConfig.terrainSlopes;
        int profile = MapConfig.mapColorProfile;
        int limit = Math.min(source.length, SIZE * SIZE);
        for (int index = 0; index < limit; index++) {
            int color = source[index];
            if (color == 0) continue;
            int x = index & 63;
            int z = index >> 6;
            byte pixelFlags = flags == null || index >= flags.length ? 0 : flags[index];
            int floorY = height(heights, x, z, FullCaveMapManager.NO_SURFACE);
            int visualY = floorY;
            if ((pixelFlags & DenseCaveTile.FLAG_OVERLAY) != 0
                    && topHeights != null && index < topHeights.length
                    && topHeights[index] != FullCaveMapManager.NO_SURFACE) {
                visualY = Math.round(floorY * 0.68f + topHeights[index] * 0.32f);
            }
            float relief = slopes <= 0 ? 1.0f : shade(heights, x, z, slopes);
            boolean emissive = (pixelFlags & DenseCaveTile.FLAG_EMISSIVE) != 0;
            boolean legacy = (pixelFlags & DenseCaveTile.FLAG_PRELIT_LEGACY) != 0;
            int light = lights == null || index >= lights.length
                    ? 15 : Byte.toUnsignedInt(lights[index]);
            int styled = styleMaterial(color, visualY, light, emissive,
                    legacy, relief, view, layerY);
            output[index] = MapColorProfile.apply(styled, profile);
            emissivePixels[index] = emissive;
        }
        applyEmissionHalo(output, emissivePixels);
        return output;
    }

    /**
     * Styles base and transparent layers independently, then composites them
     * bottom-to-top. Depth, light and emission therefore remain correct for each
     * material instead of being approximated after an early colour merge.
     */
    public static int[] style(int[] baseColors, short[] heights,
            short[] topHeights, byte[] flags, byte[] baseLights,
            byte[] overlayCounts, int[] overlayColors, byte[] overlayAlpha,
            short[] overlayY, byte[] overlayLights, byte[] overlayFlags,
            CaveView view, int layerY) {
        if (overlayCounts == null || overlayColors == null || overlayAlpha == null
                || overlayY == null || overlayLights == null || overlayFlags == null) {
            return style(baseColors, heights, topHeights, flags, baseLights,
                    view, layerY);
        }

        int[] output = new int[baseColors.length];
        boolean[] emissivePixels = new boolean[Math.min(baseColors.length, SIZE * SIZE)];
        int slopes = MapConfig.terrainSlopes;
        int profile = MapConfig.mapColorProfile;
        int limit = Math.min(baseColors.length, SIZE * SIZE);
        for (int index = 0; index < limit; index++) {
            int base = baseColors[index];
            if (base == 0) continue;
            int x = index & 63;
            int z = index >> 6;
            byte pixelFlags = flags == null || index >= flags.length ? 0 : flags[index];
            int floorY = height(heights, x, z, FullCaveMapManager.NO_SURFACE);
            int baseLight = baseLights == null || index >= baseLights.length
                    ? 15 : Byte.toUnsignedInt(baseLights[index]);
            float relief = slopes <= 0 ? 1.0f : shade(heights, x, z, slopes);
            boolean baseEmissive = (pixelFlags & DenseCaveTile.FLAG_EMISSIVE) != 0;
            boolean legacy = (pixelFlags & DenseCaveTile.FLAG_PRELIT_LEGACY) != 0;
            int composed = styleMaterial(base, floorY, baseLight, baseEmissive,
                    legacy, relief, view, layerY);
            boolean anyEmissive = baseEmissive;

            int count = Math.min(DenseCaveTile.MAX_OVERLAYS,
                    Byte.toUnsignedInt(overlayCounts[index]));
            int first = index * DenseCaveTile.MAX_OVERLAYS;
            for (int layer = count - 1; layer >= 0; layer--) {
                int entry = first + layer;
                if (entry >= overlayColors.length || entry >= overlayAlpha.length
                        || entry >= overlayY.length || entry >= overlayLights.length
                        || entry >= overlayFlags.length) continue;
                int overlay = overlayColors[entry];
                int alpha = Byte.toUnsignedInt(overlayAlpha[entry]);
                if (overlay == 0 || alpha <= 0) continue;
                boolean overlayEmissive = (overlayFlags[entry]
                        & DenseCaveTile.OVERLAY_EMISSIVE) != 0;
                int styledOverlay = styleMaterial(overlay, overlayY[entry],
                        Byte.toUnsignedInt(overlayLights[entry]), overlayEmissive,
                        false, relief, view, layerY);
                composed = blendAbgr(composed, styledOverlay, alpha);
                anyEmissive |= overlayEmissive;
            }

            output[index] = MapColorProfile.apply(composed, profile);
            emissivePixels[index] = anyEmissive;
        }
        applyEmissionHalo(output, emissivePixels);
        return output;
    }

    private static int styleMaterial(int color, int height, int light,
            boolean emissive, boolean legacyPrelit, float relief,
            CaveView view, int layerY) {
        float depth = depthBrightness(view, layerY, height);
        if (legacyPrelit) depth = 0.92f + depth * 0.08f;
        float lightShade = legacyPrelit ? 1.0f
                : 0.88f + 0.12f * (float) Math.pow(
                        clamp(light / 15.0f, 0.0f, 1.0f), 0.80f);
        if (emissive) {
            depth = 1.0f;
            lightShade = 1.0f;
            relief = 0.92f + relief * 0.08f;
            color = ensureGlowBrightness(color);
        }
        float combined = clamp(depth * lightShade * relief, 0.48f, 1.20f);
        int red = readableChannel(color & 0xFF, combined, emissive);
        int green = readableChannel((color >>> 8) & 0xFF, combined, emissive);
        int blue = readableChannel((color >>> 16) & 0xFF, combined, emissive);
        return (color & 0xFF000000) | (blue << 16) | (green << 8) | red;
    }

    /** Xaero full-cave uses a folded 64-block height band. */
    private static float depthBrightness(CaveView view, int layerY, int height) {
        if (height == FullCaveMapManager.NO_SURFACE) return 1.0f;
        if (view == CaveView.FULL) {
            int odd = (height >> 6) & 1;
            int folded = 63 * odd + (1 - 2 * odd) * (height & 63);
            float xaero = (17.0f + folded) / 80.0f;
            return 0.58f + 0.42f * clamp(xaero, 0.0f, 1.0f);
        }
        int bottom = layerY + 1 - CaveDisplayProjector.LAYER_DEPTH;
        float normalized = (height - bottom + 1.0f)
                / CaveDisplayProjector.LAYER_DEPTH;
        return 0.66f + 0.34f * clamp(normalized, 0.0f, 1.0f);
    }

    private static float shade(short[] heights, int x, int z, int mode) {
        int center = height(heights, x, z, FullCaveMapManager.NO_SURFACE);
        if (center == FullCaveMapManager.NO_SURFACE) return 1.0f;
        int north = height(heights, x, z - 1, center);
        if (mode == 1) return clamp(1.0f + (center - north) * 0.035f, 0.82f, 1.16f);
        int south = height(heights, x, z + 1, center);
        int west = height(heights, x - 1, z, center);
        int east = height(heights, x + 1, z, center);
        float dx = clamp((west - east) * 0.5f, -12.0f, 12.0f);
        float dz = clamp((north - south) * 0.5f, -12.0f, 12.0f);
        int rim = Math.max(Math.max(north, south), Math.max(west, east));
        int floor = Math.min(Math.min(north, south), Math.min(west, east));
        float pit = Math.min(0.20f, Math.max(0, rim - center) * 0.038f);
        float ridge = Math.min(0.10f, Math.max(0, center - floor) * 0.017f);
        float edge = Math.min(0.10f,
                (Math.abs(west - east) + Math.abs(north - south)) * 0.007f);
        return clamp(1.0f + dx * 0.040f + dz * 0.055f + ridge - pit - edge,
                0.74f, 1.18f);
    }

    private static int height(short[] heights, int x, int z, int fallback) {
        if (heights == null) return fallback;
        if (heights.length == BORDERED_SIZE * BORDERED_SIZE) {
            int bx = x + 1;
            int bz = z + 1;
            if (bx < 0 || bx >= BORDERED_SIZE || bz < 0 || bz >= BORDERED_SIZE) return fallback;
            int value = heights[bz * BORDERED_SIZE + bx];
            return value == FullCaveMapManager.NO_SURFACE ? fallback : value;
        }
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return fallback;
        int value = heights[z * SIZE + x];
        return value == FullCaveMapManager.NO_SURFACE ? fallback : value;
    }

    private static int ensureGlowBrightness(int abgr) {
        int red = abgr & 0xFF;
        int green = (abgr >>> 8) & 0xFF;
        int blue = (abgr >>> 16) & 0xFF;
        int total = red + green + blue;
        if (total <= 0 || total >= XAERO_GLOW_MIN_RGB_SUM) return abgr;
        float multiplier = XAERO_GLOW_MIN_RGB_SUM / (float) total;
        red = clamp(Math.round(red * multiplier));
        green = clamp(Math.round(green * multiplier));
        blue = clamp(Math.round(blue * multiplier));
        return (abgr & 0xFF000000) | (blue << 16) | (green << 8) | red;
    }

    private static void applyEmissionHalo(int[] pixels, boolean[] emissive) {
        int[] base = pixels.clone();
        int limit = Math.min(emissive.length, SIZE * SIZE);
        for (int index = 0; index < limit; index++) {
            if (!emissive[index] || base[index] == 0) continue;
            int x = index & 63;
            int z = index >> 6;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = x + dx;
                    int nz = z + dz;
                    if (nx < 0 || nx >= SIZE || nz < 0 || nz >= SIZE) continue;
                    int neighbour = nz * SIZE + nx;
                    if (base[neighbour] == 0 || emissive[neighbour]) continue;
                    pixels[neighbour] = blendAbgr(pixels[neighbour], base[index],
                            dx == 0 || dz == 0 ? 18 : 10);
                }
            }
        }
    }

    private static int blendAbgr(int base, int overlay, int alpha) {
        if (base == 0) return overlay;
        int amount = Math.max(0, Math.min(255, alpha));
        int inverse = 255 - amount;
        int red = ((base & 0xFF) * inverse + (overlay & 0xFF) * amount) / 255;
        int green = (((base >>> 8) & 0xFF) * inverse
                + ((overlay >>> 8) & 0xFF) * amount) / 255;
        int blue = (((base >>> 16) & 0xFF) * inverse
                + ((overlay >>> 16) & 0xFF) * amount) / 255;
        return (base & 0xFF000000) | (blue << 16) | (green << 8) | red;
    }

    private static int readableChannel(int value, float shade, boolean emissive) {
        float lift = emissive ? 1.03f : 1.06f;
        float bias = emissive ? 4.0f : 5.0f;
        return clamp(Math.round((value * lift + bias) * shade));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
