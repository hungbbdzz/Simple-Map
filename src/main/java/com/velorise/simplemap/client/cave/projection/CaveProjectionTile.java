package com.velorise.simplemap.client.cave.projection;

import java.util.Arrays;

/** Immutable 16x16 cave projection output. */
public final class CaveProjectionTile {
    private final int chunkX;
    private final int chunkZ;
    private final int bandTopY;
    private final long archiveRevision;
    private final int[] pixels;
    private final byte[] completeness;

    public CaveProjectionTile(int chunkX, int chunkZ, int bandTopY,
            long archiveRevision, int[] pixels, byte[] completeness) {
        if (pixels == null || pixels.length != 256
                || completeness == null || completeness.length != 256) {
            throw new IllegalArgumentException("projection arrays");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.bandTopY = bandTopY;
        this.archiveRevision = archiveRevision;
        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.completeness = Arrays.copyOf(completeness, completeness.length);
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int bandTopY() { return bandTopY; }
    public long archiveRevision() { return archiveRevision; }
    public int[] pixels() { return Arrays.copyOf(pixels, pixels.length); }
    public byte[] completeness() {
        return Arrays.copyOf(completeness, completeness.length);
    }
}
