package com.velorise.simplemap.client;

/**
 * Persistent per-pixel tint result captured from Minecraft's registered
 * {@code BlockColors} provider while the source chunk is loaded.
 *
 * <p>
 * Two sentinels are required:
 * </p>
 * <ul>
 * <li>{@link #UNKNOWN}: legacy cache data that predates tint capture.</li>
 * <li>{@link #NONE}: the provider explicitly returned no tint for this
 * block.</li>
 * </ul>
 * Actual tint values are stored as {@code 0x00RRGGBB}.
 */
public final class SurfaceTintData {
    public static final int UNKNOWN = 0x80000000;
    public static final int NONE = 0x81000000;

    private SurfaceTintData() {
    }

    public static int fromProviderResult(int color) {
        return color == -1 ? NONE : color & 0x00FFFFFF;
    }

    public static boolean hasColor(int stored) {
        return stored != UNKNOWN && stored != NONE;
    }

    public static int rgb(int stored) {
        return stored & 0x00FFFFFF;
    }
}
