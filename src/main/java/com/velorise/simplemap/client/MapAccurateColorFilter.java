package com.velorise.simplemap.client;

/** Shared final colour transform for Accurate surface and cave rendering. */
public final class MapAccurateColorFilter {
    private MapAccurateColorFilter() { }

    public static int applyArgb(int argb, boolean leaves, boolean fluid) {
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
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** NativeImage/cave pages use ABGR integers. */
    public static int applyAbgr(int abgr, boolean leaves, boolean fluid) {
        int argb = (abgr & 0xFF000000)
                | ((abgr & 0xFF) << 16)
                | (abgr & 0x0000FF00)
                | ((abgr >>> 16) & 0xFF);
        int filtered = applyArgb(argb, leaves, fluid);
        return (filtered & 0xFF000000)
                | ((filtered & 0xFF) << 16)
                | (filtered & 0x0000FF00)
                | ((filtered >>> 16) & 0xFF);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
