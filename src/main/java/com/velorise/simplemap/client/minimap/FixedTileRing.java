package com.velorise.simplemap.client.minimap;

import java.util.Arrays;

/** Fixed-size chunk ring centered on the current minimap anchor. */
public final class FixedTileRing {
    private final int diameter;
    private final long[] chunkKeys;
    private int centerChunkX = Integer.MIN_VALUE;
    private int centerChunkZ = Integer.MIN_VALUE;
    private long generation;

    public FixedTileRing(int diameter) {
        if (diameter < 3 || (diameter & 1) == 0) {
            throw new IllegalArgumentException("diameter must be odd and >= 3");
        }
        this.diameter = diameter;
        this.chunkKeys = new long[diameter * diameter];
        Arrays.fill(chunkKeys, Long.MIN_VALUE);
    }

    public synchronized boolean recenter(int chunkX, int chunkZ) {
        if (chunkX == centerChunkX && chunkZ == centerChunkZ) return false;
        centerChunkX = chunkX;
        centerChunkZ = chunkZ;
        int radius = diameter / 2;
        int index = 0;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                chunkKeys[index++] = pack(chunkX + x, chunkZ + z);
            }
        }
        generation++;
        return true;
    }

    public synchronized long generation() { return generation; }
    public int diameter() { return diameter; }
    public synchronized int centerChunkX() { return centerChunkX; }
    public synchronized int centerChunkZ() { return centerChunkZ; }
    public synchronized long[] snapshotKeys() {
        return Arrays.copyOf(chunkKeys, chunkKeys.length);
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) { return (int) (key >> 32); }
    public static int unpackZ(long key) { return (int) key; }
}
