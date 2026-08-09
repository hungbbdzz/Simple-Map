package com.velorise.simplemap.client;

import java.util.Arrays;

/**
 * Immutable 16x16 surface source segment. It stores only primitive identities;
 * no BlockState, LevelChunk or ClientLevel reference can escape the client
 * thread. Packed columns retain material/biome/height/fluid identity while the
 * derived arrays make source completeness and projection inputs explicit.
 */
public final class ChunkSnapshot {
    public enum Completeness { UNKNOWN, PARTIAL, COMPLETE }

    private static final int PIXELS = 16 * 16;

    private final int localChunkX;
    private final int localChunkZ;
    private final long sourceRevision;
    private final long[] packedPixels;
    private final int[] tints;
    private final byte[] lightLevels;
    private final Completeness completeness;

    public ChunkSnapshot(int localChunkX, int localChunkZ, long sourceRevision,
            long[] packedPixels, int[] tints, byte[] lightLevels) {
        this(localChunkX, localChunkZ, sourceRevision,
                packedPixels, tints, lightLevels, true);
    }

    static ChunkSnapshot takeOwnership(int localChunkX, int localChunkZ,
            long sourceRevision, long[] packedPixels, int[] tints,
            byte[] lightLevels) {
        return new ChunkSnapshot(localChunkX, localChunkZ, sourceRevision,
                packedPixels, tints, lightLevels, false);
    }

    private ChunkSnapshot(int localChunkX, int localChunkZ, long sourceRevision,
            long[] packedPixels, int[] tints, byte[] lightLevels,
            boolean copy) {
        if (packedPixels == null || packedPixels.length != PIXELS
                || tints == null || tints.length != PIXELS
                || (lightLevels != null && lightLevels.length != PIXELS)) {
            throw new IllegalArgumentException("ChunkSnapshot requires 256 columns");
        }
        this.localChunkX = localChunkX;
        this.localChunkZ = localChunkZ;
        this.sourceRevision = Math.max(1L, sourceRevision);
        this.packedPixels = copy
                ? Arrays.copyOf(packedPixels, PIXELS) : packedPixels;
        this.tints = copy ? Arrays.copyOf(tints, PIXELS) : tints;
        this.lightLevels = lightLevels == null ? new byte[PIXELS]
                : copy ? Arrays.copyOf(lightLevels, PIXELS) : lightLevels;
        int known = 0;
        for (long packed : this.packedPixels) {
            if (!MapBlockData.isEmpty(packed)) known++;
        }
        this.completeness = known == 0 ? Completeness.UNKNOWN
                : known == PIXELS ? Completeness.COMPLETE : Completeness.PARTIAL;
    }

    public int localChunkX() { return localChunkX; }
    public int localChunkZ() { return localChunkZ; }
    public long sourceRevision() { return sourceRevision; }
    public Completeness completeness() { return completeness; }

    public long[] packedPixels() { return Arrays.copyOf(packedPixels, PIXELS); }
    public int[] tints() { return Arrays.copyOf(tints, PIXELS); }
    public byte[] lightLevels() { return Arrays.copyOf(lightLevels, PIXELS); }

    long[] packedPixelsUnsafe() { return packedPixels; }
    int[] tintsUnsafe() { return tints; }
    byte[] lightLevelsUnsafe() { return lightLevels; }
}
