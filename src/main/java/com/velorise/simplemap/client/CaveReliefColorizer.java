package com.velorise.simplemap.client;

import java.util.function.BooleanSupplier;

/** Applies stable height-based relief to cached cave pixels on the CPU worker. */
final class CaveReliefColorizer {
    private static final int SIZE = 512;

    private CaveReliefColorizer() {
    }

    static int[] colorize(int[] source, short[] heights, int terrainSlopes,
            int profile, BooleanSupplier stillValid) {
        int[] output = new int[source.length];
        for (int z = 0; z < SIZE; z++) {
            if ((z & 31) == 0 && !stillValid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Stale cave relief job");
            }
            for (int x = 0; x < SIZE; x++) {
                int index = z * SIZE + x;
                int color = source[index];
                if (color == 0) continue;
                output[index] = stylePixel(color, heights, x, z, terrainSlopes, profile);
            }
        }
        return output;
    }

    /**
     * Styles one 64x64 page directly from a 512x512 immutable region snapshot.
     *
     * The old page path styled all 262,144 region pixels and then copied only 4,096
     * of them. Direct page styling removes that 64x amplification while relief still
     * reads the full height array, so shading remains continuous across page borders.
     */
    static int[] colorizePage(int[] source, short[] heights, int pageX, int pageZ,
            int terrainSlopes, int profile, BooleanSupplier stillValid) {
        int pageSize = MapPageLayout.PAGE_SIZE;
        int[] output = new int[pageSize * pageSize];
        int startX = pageX * pageSize;
        int startZ = pageZ * pageSize;

        for (int localZ = 0; localZ < pageSize; localZ++) {
            if ((localZ & 15) == 0 && !stillValid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Stale cave page job");
            }
            int z = startZ + localZ;
            int sourceRow = z * SIZE;
            int outputRow = localZ * pageSize;
            for (int localX = 0; localX < pageSize; localX++) {
                int x = startX + localX;
                int color = source[sourceRow + x];
                if (color == 0) continue;
                output[outputRow + localX] = stylePixel(
                        color, heights, x, z, terrainSlopes, profile);
            }
        }
        return output;
    }

    private static int stylePixel(int color, short[] heights, int x, int z,
            int terrainSlopes, int profile) {
        float shade = terrainSlopes <= 0 || heights == null
                ? 1.0f : shade(heights, x, z, terrainSlopes);
        int red = clamp(Math.round((color & 0xFF) * shade));
        int green = clamp(Math.round(((color >>> 8) & 0xFF) * shade));
        int blue = clamp(Math.round(((color >>> 16) & 0xFF) * shade));
        int styled = (color & 0xFF000000) | (blue << 16) | (green << 8) | red;
        return MapColorProfile.apply(styled, profile);
    }

    private static float shade(short[] heights, int x, int z, int mode) {
        int index = z * SIZE + x;
        int center = heights[index];
        if (center == FullCaveMapManager.NO_SURFACE) return 1.0f;
        int north = height(heights, x, z - 1, center);
        if (mode == 1) {
            int delta = center - north;
            return clamp(1.0f + delta * 0.045f, 0.70f, 1.20f);
        }
        int south = height(heights, x, z + 1, center);
        int west = height(heights, x - 1, z, center);
        int east = height(heights, x + 1, z, center);
        float dx = clamp((west - east) * 0.5f, -14.0f, 14.0f);
        float dz = clamp((north - south) * 0.5f, -14.0f, 14.0f);
        int rim = Math.max(Math.max(north, south), Math.max(west, east));
        int floor = Math.min(Math.min(north, south), Math.min(west, east));
        float directional = dx * 0.043f + dz * 0.060f;
        float pit = Math.min(0.38f, Math.max(0, rim - center) * 0.060f);
        float ridge = Math.min(0.10f, Math.max(0, center - floor) * 0.018f);
        float edge = Math.min(0.18f,
                (Math.abs(west - east) + Math.abs(north - south)) * 0.012f);
        float depthOcclusion = multiScaleDepthOcclusion(heights, x, z, center);
        return clamp(1.0f + directional + ridge - pit - edge - depthOcclusion, 0.46f, 1.24f);
    }

    private static float multiScaleDepthOcclusion(short[] heights, int x, int z, int center) {
        float shadow = depthAtRadius(heights, x, z, center, 2, 0.018f)
                + depthAtRadius(heights, x, z, center, 8, 0.010f)
                + depthAtRadius(heights, x, z, center, 24, 0.0035f);
        return Math.min(0.24f, shadow);
    }

    private static float depthAtRadius(short[] heights, int x, int z,
            int center, int radius, float weight) {
        int sum = Math.max(0, height(heights, x - radius, z, center) - center)
                + Math.max(0, height(heights, x + radius, z, center) - center)
                + Math.max(0, height(heights, x, z - radius, center) - center)
                + Math.max(0, height(heights, x, z + radius, center) - center)
                + Math.max(0, height(heights, x - radius, z - radius, center) - center)
                + Math.max(0, height(heights, x + radius, z - radius, center) - center)
                + Math.max(0, height(heights, x - radius, z + radius, center) - center)
                + Math.max(0, height(heights, x + radius, z + radius, center) - center);
        return Math.min(0.16f, (sum * 0.125f) * weight);
    }

    private static int height(short[] heights, int x, int z, int fallback) {
        if (x < 0 || x >= SIZE || z < 0 || z >= SIZE) return fallback;
        int value = heights[z * SIZE + x];
        return value == FullCaveMapManager.NO_SURFACE ? fallback : value;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
