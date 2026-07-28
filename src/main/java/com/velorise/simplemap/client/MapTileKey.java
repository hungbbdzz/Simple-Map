package com.velorise.simplemap.client;

/** Stable logical identity for one renderer-visible map tile. */
public record MapTileKey(int projection, int phase, int worldX, int worldZ,
        int worldWidth, int worldHeight) {
    public static final int PROJECTION_SURFACE = 0;
    public static final int PROJECTION_CAVE = 1;
    public static final int PROJECTION_GLOW = 2;

    public MapTileKey {
        if (worldWidth <= 0 || worldHeight <= 0) {
            throw new IllegalArgumentException("Invalid tile extent");
        }
    }
}
