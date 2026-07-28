package com.velorise.simplemap.client.gpu;

/**
 * Stable logical identity for one renderable map tile.
 *
 * <p>The key never contains an atlas slot. GPU storage can therefore move or be
 * recreated without invalidating world geometry. Variant distinguishes exact,
 * glow, branch, minimap and cave projections that share coordinates.</p>
 */
public record TileKey(long sessionId, int projectionId, int level,
        int tileX, int tileZ, int variant) {
    public static final int VARIANT_SURFACE_EXACT = 1;
    public static final int VARIANT_SURFACE_GLOW = 2;
    public static final int VARIANT_SURFACE_BRANCH = 3;
    public static final int VARIANT_MINIMAP = 4;
    public static final int VARIANT_CAVE_LAYERED = 5;
    public static final int VARIANT_CAVE_FULL = 6;

    public TileKey {
        if (sessionId <= 0L) throw new IllegalArgumentException("sessionId");
        if (level < 0) throw new IllegalArgumentException("level");
    }
}
