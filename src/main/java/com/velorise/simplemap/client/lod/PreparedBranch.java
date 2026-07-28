package com.velorise.simplemap.client.lod;

import com.velorise.simplemap.client.pipeline.RevisionStamp;

import java.util.Arrays;

/**
 * Immutable worker output for one region-centric LOD node. It contains no GPU
 * handle and no mutable source object, so render-thread publication can validate
 * the complete revision tuple before uploading.
 */
public final class PreparedBranch {
    private final RegionLodGraph.NodeKey key;
    private final RevisionStamp stamp;
    private final long revision;
    private final int width;
    private final int height;
    private final int[] pixels;
    private final long knownMask;
    private final long completeMask;
    private final long[] knownRows;
    private final long[] completeRows;
    private final long[] childVersionSums;
    private final int dirtyMinX;
    private final int dirtyMinY;
    private final int dirtyMaxX;
    private final int dirtyMaxY;

    public PreparedBranch(RegionLodGraph.NodeKey key, RevisionStamp stamp,
            long revision, int width, int height, int[] pixels,
            long knownMask, long completeMask, long[] childVersionSums,
            int dirtyMinX, int dirtyMinY, int dirtyMaxX, int dirtyMaxY) {
        this(key, stamp, revision, width, height, pixels, knownMask,
                completeMask, rowsFromMask(knownMask),
                rowsFromMask(completeMask), childVersionSums,
                dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY);
    }

    public PreparedBranch(RegionLodGraph.NodeKey key, RevisionStamp stamp,
            long revision, int width, int height, int[] pixels,
            long knownMask, long completeMask, long[] knownRows,
            long[] completeRows, long[] childVersionSums,
            int dirtyMinX, int dirtyMinY, int dirtyMaxX, int dirtyMaxY) {
        if (key == null || stamp == null) {
            throw new IllegalArgumentException("Branch key and stamp are required");
        }
        if (width <= 0 || height <= 0 || pixels == null
                || pixels.length != width * height) {
            throw new IllegalArgumentException("Invalid branch pixel payload");
        }
        if (childVersionSums == null
                || childVersionSums.length != RegionLodGraph.CHILD_COUNT) {
            throw new IllegalArgumentException("Branch requires 64 child versions");
        }
        if (knownRows == null || knownRows.length != height
                || completeRows == null || completeRows.length != height) {
            throw new IllegalArgumentException("Branch requires coverage rows");
        }
        this.key = key;
        this.stamp = stamp;
        this.revision = Math.max(1L, revision);
        this.width = width;
        this.height = height;
        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.knownMask = knownMask;
        this.completeMask = completeMask;
        this.knownRows = Arrays.copyOf(knownRows, knownRows.length);
        this.completeRows = Arrays.copyOf(completeRows, completeRows.length);
        this.childVersionSums = Arrays.copyOf(childVersionSums,
                childVersionSums.length);
        this.dirtyMinX = Math.max(0, Math.min(width - 1, dirtyMinX));
        this.dirtyMinY = Math.max(0, Math.min(height - 1, dirtyMinY));
        this.dirtyMaxX = Math.max(this.dirtyMinX,
                Math.min(width - 1, dirtyMaxX));
        this.dirtyMaxY = Math.max(this.dirtyMinY,
                Math.min(height - 1, dirtyMaxY));
    }

    public RegionLodGraph.NodeKey key() { return key; }
    public RevisionStamp stamp() { return stamp; }
    public long revision() { return revision; }
    public int width() { return width; }
    public int height() { return height; }
    public int[] pixels() { return Arrays.copyOf(pixels, pixels.length); }
    public long knownMask() { return knownMask; }
    public long completeMask() { return completeMask; }
    public long[] knownRows() { return Arrays.copyOf(knownRows, knownRows.length); }
    public long[] completeRows() {
        return Arrays.copyOf(completeRows, completeRows.length);
    }
    public long[] childVersionSums() {
        return Arrays.copyOf(childVersionSums, childVersionSums.length);
    }
    public int dirtyMinX() { return dirtyMinX; }
    public int dirtyMinY() { return dirtyMinY; }
    public int dirtyMaxX() { return dirtyMaxX; }
    public int dirtyMaxY() { return dirtyMaxY; }


    private static long[] rowsFromMask(long childMask) {
        long[] rows = new long[64];
        for (int child = 0; child < 64; child++) {
            if ((childMask & (1L << child)) == 0L) continue;
            int childX = child & 7;
            int childY = child >>> 3;
            long bits = 0xFFL << (childX * 8);
            int startY = childY * 8;
            for (int y = startY; y < startY + 8; y++) rows[y] |= bits;
        }
        return rows;
    }
}
