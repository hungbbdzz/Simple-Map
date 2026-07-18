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
                float shade = terrainSlopes <= 0 || heights == null
                        ? 1.0f : shade(heights, x, z, terrainSlopes);
                int red = clamp(Math.round((color & 0xFF) * shade));
                int green = clamp(Math.round(((color >>> 8) & 0xFF) * shade));
                int blue = clamp(Math.round(((color >>> 16) & 0xFF) * shade));
                int styled = (color & 0xFF000000) | (blue << 16) | (green << 8) | red;
                output[index] = MapColorProfile.apply(styled, profile);
            }
        }
        return output;
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
        return clamp(1.0f + directional + ridge - pit - edge, 0.48f, 1.24f);
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
