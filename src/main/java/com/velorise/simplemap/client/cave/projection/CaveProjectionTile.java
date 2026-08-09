package com.velorise.simplemap.client.cave.projection;

import java.util.Arrays;

/** Immutable 16x16 cave projection output and its styling metadata. */
public final class CaveProjectionTile {
    private final int chunkX;
    private final int chunkZ;
    private final int bandTopY;
    private final long archiveRevision;
    private final int[] pixels;
    private final short[] floorY;
    private final short[] topY;
    private final byte[] flags;
    private final byte[] light;
    private final byte[] completeness;
    private final int knownColumns;
    private final boolean complete;

    public CaveProjectionTile(int chunkX, int chunkZ, int bandTopY,
            long archiveRevision, int[] pixels, short[] floorY, short[] topY,
            byte[] flags, byte[] light, byte[] completeness) {
        if (pixels == null || pixels.length != 256
                || floorY == null || floorY.length != 256
                || topY == null || topY.length != 256
                || flags == null || flags.length != 256
                || light == null || light.length != 256
                || completeness == null || completeness.length != 256) {
            throw new IllegalArgumentException("projection arrays");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.bandTopY = bandTopY;
        this.archiveRevision = archiveRevision;
        /*
         * ProjectionServiceV2 is the sole constructor caller and hands over freshly
         * allocated arrays that are never mutated afterwards. Copying all six here
         * doubled every cave projection allocation before the cache had even seen
         * the tile. Retain ownership exactly like Xaero retains a tile buffer;
         * public bulk accessors below still return defensive copies.
         */
        this.pixels = pixels;
        this.floorY = floorY;
        this.topY = topY;
        this.flags = flags;
        this.light = light;
        this.completeness = completeness;
        int known = 0;
        boolean authoritative = true;
        boolean fullProjection = bandTopY == Integer.MIN_VALUE;
        for (byte status : completeness) {
            int value = Byte.toUnsignedInt(status);
            // CompactCaveTile.ColumnStatus: UNKNOWN=0, PARTIAL=1, COMPLETE=2,
            // COMPLETE_TRUNCATED=3, CORRUPT=4. Layered Top-Y requires COMPLETE.
            // Full Cave can also finalize COMPLETE_TRUNCATED because it consumes the
            // discovered run set directly and never asks for an unseen lower band.
            if (value != 0 && value != 4) known++;
            if (value != 2 && !(fullProjection && value == 3)) {
                authoritative = false;
            }
        }
        this.knownColumns = known;
        this.complete = authoritative;
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public int bandTopY() { return bandTopY; }
    public long archiveRevision() { return archiveRevision; }
    public int knownColumns() { return knownColumns; }
    public boolean complete() { return complete; }

    public boolean known(int index) {
        int status = Byte.toUnsignedInt(completeness[index]);
        return status != 0 && status != 4;
    }

    public int pixel(int index) { return pixels[index]; }
    public short floorY(int index) { return floorY[index]; }
    public short topY(int index) { return topY[index]; }
    public byte flags(int index) { return flags[index]; }
    public byte light(int index) { return light[index]; }

    public int[] pixels() { return Arrays.copyOf(pixels, pixels.length); }
    public short[] floors() { return Arrays.copyOf(floorY, floorY.length); }
    public short[] tops() { return Arrays.copyOf(topY, topY.length); }
    public byte[] flags() { return Arrays.copyOf(flags, flags.length); }
    public byte[] lights() { return Arrays.copyOf(light, light.length); }
    public byte[] completeness() {
        return Arrays.copyOf(completeness, completeness.length);
    }
}
