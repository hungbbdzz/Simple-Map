package com.velorise.simplemap.client;

/** Lightweight display-time color transforms. Input and output use NativeImage ABGR. */
public final class MapColorProfile {
    public static final String[] NAMES = { "Balanced", "Vibrant", "Natural", "Contrast" };

    private MapColorProfile() {
    }

    public static int apply(int abgr, int profile) {
        if (abgr == 0 || profile == 0) return abgr;

        int alpha = (abgr >>> 24) & 0xFF;
        float red = (abgr & 0xFF) / 255.0f;
        float green = ((abgr >>> 8) & 0xFF) / 255.0f;
        float blue = ((abgr >>> 16) & 0xFF) / 255.0f;

        float saturation;
        float contrast;
        float brightness;
        switch (profile) {
            case 1 -> { // Vibrant
                saturation = 1.20f;
                contrast = 1.08f;
                brightness = 1.04f;
            }
            case 2 -> { // Natural
                saturation = 0.84f;
                contrast = 0.96f;
                brightness = 1.01f;
            }
            case 3 -> { // High contrast
                saturation = 1.06f;
                contrast = 1.20f;
                brightness = 1.0f;
            }
            default -> {
                return abgr;
            }
        }

        float luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
        red = luminance + (red - luminance) * saturation;
        green = luminance + (green - luminance) * saturation;
        blue = luminance + (blue - luminance) * saturation;

        red = ((red - 0.5f) * contrast + 0.5f) * brightness;
        green = ((green - 0.5f) * contrast + 0.5f) * brightness;
        blue = ((blue - 0.5f) * contrast + 0.5f) * brightness;

        int r = Math.round(clamp(red) * 255.0f);
        int g = Math.round(clamp(green) * 255.0f);
        int b = Math.round(clamp(blue) * 255.0f);
        return (alpha << 24) | (b << 16) | (g << 8) | r;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
