package com.velorise.simplemap.client;

/**
 * Chunk-granular Surface observation state. Material payload and observation are
 * deliberately independent: a fully scanned void chunk is complete map data.
 */
public final class SurfaceChunkCoverage {
    public static final int CHUNKS_PER_AXIS = 32;
    public static final int CHUNK_COUNT = CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;
    public static final int WORDS = CHUNK_COUNT / Long.SIZE;

    private SurfaceChunkCoverage() {
    }

    public static boolean isComplete(long[] mask, int chunkIndex) {
        return mask != null && mask.length == WORDS
                && chunkIndex >= 0 && chunkIndex < CHUNK_COUNT
                && (mask[chunkIndex >>> 6] & (1L << (chunkIndex & 63))) != 0L;
    }

    public static boolean markComplete(long[] mask, int chunkIndex) {
        if (mask == null || mask.length != WORDS
                || chunkIndex < 0 || chunkIndex >= CHUNK_COUNT) return false;
        int word = chunkIndex >>> 6;
        long bit = 1L << (chunkIndex & 63);
        boolean changed = (mask[word] & bit) == 0L;
        mask[word] |= bit;
        return changed;
    }

    /** Legacy v1-v3 files had no explicit coverage; only all-solid chunks qualify. */
    public static long[] inferLegacy(long[] pixels, int regionSize,
            long emptyPacked) {
        long[] mask = new long[WORDS];
        if (pixels == null || regionSize != CHUNKS_PER_AXIS * 16
                || pixels.length != regionSize * regionSize) return mask;
        for (int chunkZ = 0; chunkZ < CHUNKS_PER_AXIS; chunkZ++) {
            for (int chunkX = 0; chunkX < CHUNKS_PER_AXIS; chunkX++) {
                boolean complete = true;
                for (int z = 0; z < 16 && complete; z++) {
                    int row = (chunkZ * 16 + z) * regionSize + chunkX * 16;
                    for (int x = 0; x < 16; x++) {
                        if (pixels[row + x] == emptyPacked) {
                            complete = false;
                            break;
                        }
                    }
                }
                if (complete) markComplete(mask, chunkZ * CHUNKS_PER_AXIS + chunkX);
            }
        }
        return mask;
    }
}
