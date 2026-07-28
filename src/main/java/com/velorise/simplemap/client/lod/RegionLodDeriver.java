package com.velorise.simplemap.client.lod;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.BooleanSupplier;

/**
 * Worker-only 8x8 child compositor for the region-centric LOD hierarchy.
 *
 * <p>Each child is a fixed 64x64 texture. The child is reduced directly into
 * one 8x8 quadrant of the 64x64 parent, so one region-level derivation does not
 * construct seven factor-2 intermediate objects. Coverage is carried separately
 * from ARGB alpha and the immutable result can be validated before publication.</p>
 */
public final class RegionLodDeriver {
    public static final int TEXTURE_SIZE = 64;
    private static final int CHILD_OUTPUT_SIZE = 8;
    private static final int SAMPLE_SPAN = TEXTURE_SIZE / CHILD_OUTPUT_SIZE;

    private RegionLodDeriver() { }

    public static final class ChildSnapshot {
        private final int childIndex;
        private final long revision;
        private final int[] pixels;
        private final long[] knownRows;
        private final long[] completeRows;

        public ChildSnapshot(int childIndex, long revision, int[] pixels,
                long[] knownRows, long[] completeRows) {
            if (childIndex < 0 || childIndex >= RegionLodGraph.CHILD_COUNT) {
                throw new IllegalArgumentException("childIndex");
            }
            if (pixels == null || pixels.length != TEXTURE_SIZE * TEXTURE_SIZE
                    || knownRows == null || knownRows.length != TEXTURE_SIZE
                    || completeRows == null
                    || completeRows.length != TEXTURE_SIZE) {
                throw new IllegalArgumentException(
                        "LOD child requires 64x64 pixels and coverage rows");
            }
            this.childIndex = childIndex;
            this.revision = Math.max(1L, revision);
            this.pixels = Arrays.copyOf(pixels, pixels.length);
            this.knownRows = Arrays.copyOf(knownRows, knownRows.length);
            this.completeRows = Arrays.copyOf(completeRows,
                    completeRows.length);
        }

        public int childIndex() { return childIndex; }
        public long revision() { return revision; }
        public int[] pixels() { return Arrays.copyOf(pixels, pixels.length); }
        public long[] knownRows() {
            return Arrays.copyOf(knownRows, knownRows.length);
        }
        public long[] completeRows() {
            return Arrays.copyOf(completeRows, completeRows.length);
        }
    }

    public static PreparedBranch derive(RegionLodGraph.Lease lease,
            Collection<ChildSnapshot> children, BooleanSupplier stillValid) {
        if (lease == null) throw new IllegalArgumentException("lease");
        BooleanSupplier valid = stillValid == null ? () -> true : stillValid;
        ChildSnapshot[] indexed = new ChildSnapshot[RegionLodGraph.CHILD_COUNT];
        if (children != null) {
            for (ChildSnapshot child : children) {
                if (child == null) continue;
                if (indexed[child.childIndex] != null) {
                    throw new IllegalArgumentException(
                            "Duplicate LOD child " + child.childIndex);
                }
                indexed[child.childIndex] = child;
            }
        }

        long[] expectedVersions = lease.childVersionSums();
        int[] output = new int[TEXTURE_SIZE * TEXTURE_SIZE];
        long[] outputKnownRows = new long[TEXTURE_SIZE];
        long[] outputCompleteRows = new long[TEXTURE_SIZE];
        long outputKnownMask = 0L;
        long outputCompleteMask = 0L;

        for (int childIndex = 0;
                childIndex < RegionLodGraph.CHILD_COUNT; childIndex++) {
            check(valid);
            long bit = 1L << childIndex;
            ChildSnapshot child = indexed[childIndex];
            boolean expectedKnown = (lease.knownMask() & bit) != 0L;
            if (child == null) {
                if (expectedKnown) {
                    throw new IllegalArgumentException(
                            "Missing authoritative LOD child " + childIndex);
                }
                continue;
            }
            long expectedRevision = expectedVersions[childIndex];
            if (expectedRevision > 0L && child.revision != expectedRevision) {
                throw new IllegalArgumentException(
                        "Stale LOD child " + childIndex + ": expected "
                                + expectedRevision + ", got " + child.revision);
            }

            ChildReduction reduction = reduceChild(child, output,
                    outputKnownRows, outputCompleteRows, valid);
            if (reduction.anyKnown()) outputKnownMask |= bit;
            if (reduction.allComplete()) outputCompleteMask |= bit;
        }

        int[] dirtyRect = dirtyRect(lease.dirtyChildMask());
        return new PreparedBranch(lease.key(), lease.stamp(), lease.revision(),
                TEXTURE_SIZE, TEXTURE_SIZE, output, outputKnownMask,
                outputCompleteMask, outputKnownRows, outputCompleteRows,
                expectedVersions, dirtyRect[0], dirtyRect[1],
                dirtyRect[2], dirtyRect[3]);
    }

    private static ChildReduction reduceChild(ChildSnapshot child,
            int[] output, long[] outputKnownRows, long[] outputCompleteRows,
            BooleanSupplier valid) {
        int childX = child.childIndex & 7;
        int childZ = child.childIndex >>> 3;
        int destinationStartX = childX * CHILD_OUTPUT_SIZE;
        int destinationStartY = childZ * CHILD_OUTPUT_SIZE;
        boolean anyKnown = false;
        boolean allComplete = true;

        for (int outputY = 0; outputY < CHILD_OUTPUT_SIZE; outputY++) {
            check(valid);
            int sourceStartY = outputY * SAMPLE_SPAN;
            int destinationY = destinationStartY + outputY;
            int destinationRow = destinationY * TEXTURE_SIZE;
            for (int outputX = 0; outputX < CHILD_OUTPUT_SIZE; outputX++) {
                int sourceStartX = outputX * SAMPLE_SPAN;
                int destinationX = destinationStartX + outputX;
                ReducedColor reduced = reduceBlock(child, sourceStartX,
                        sourceStartY);
                boolean complete = blockComplete(child, sourceStartX,
                        sourceStartY);
                if (reduced.known) {
                    anyKnown = true;
                    output[destinationRow + destinationX] = reduced.abgr;
                    outputKnownRows[destinationY] |= 1L << destinationX;
                }
                if (complete) {
                    outputCompleteRows[destinationY] |= 1L << destinationX;
                } else {
                    allComplete = false;
                }
            }
        }
        return new ChildReduction(anyKnown, allComplete);
    }

    private static boolean blockComplete(ChildSnapshot child,
            int sourceStartX, int sourceStartY) {
        long mask = ((1L << SAMPLE_SPAN) - 1L) << sourceStartX;
        for (int y = 0; y < SAMPLE_SPAN; y++) {
            if ((child.completeRows[sourceStartY + y] & mask) != mask) {
                return false;
            }
        }
        return true;
    }

    private static ReducedColor reduceBlock(ChildSnapshot child,
            int sourceStartX, int sourceStartY) {
        long alpha = 0L;
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int knownCount = 0;
        int coloredCount = 0;
        for (int y = 0; y < SAMPLE_SPAN; y++) {
            int sourceY = sourceStartY + y;
            long knownRow = child.knownRows[sourceY];
            int sourceRow = sourceY * TEXTURE_SIZE;
            for (int x = 0; x < SAMPLE_SPAN; x++) {
                int sourceX = sourceStartX + x;
                if ((knownRow & (1L << sourceX)) == 0L) continue;
                knownCount++;
                int color = child.pixels[sourceRow + sourceX];
                int a = color >>> 24;
                if (a == 0) continue;
                coloredCount++;
                alpha += a;
                blue += (color >>> 16) & 0xFF;
                green += (color >>> 8) & 0xFF;
                red += color & 0xFF;
            }
        }
        if (knownCount == 0) return ReducedColor.UNKNOWN;
        if (coloredCount == 0) return new ReducedColor(true, 0);
        int a = (int) (alpha / coloredCount);
        int b = (int) (blue / coloredCount);
        int g = (int) (green / coloredCount);
        int r = (int) (red / coloredCount);
        return new ReducedColor(true,
                (a << 24) | (b << 16) | (g << 8) | r);
    }

    private static int[] dirtyRect(long dirtyChildMask) {
        if (dirtyChildMask == 0L) return new int[] { 0, 0, 63, 63 };
        int minX = TEXTURE_SIZE;
        int minY = TEXTURE_SIZE;
        int maxX = -1;
        int maxY = -1;
        for (int child = 0; child < RegionLodGraph.CHILD_COUNT; child++) {
            if ((dirtyChildMask & (1L << child)) == 0L) continue;
            int x = (child & 7) * CHILD_OUTPUT_SIZE;
            int y = (child >>> 3) * CHILD_OUTPUT_SIZE;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + CHILD_OUTPUT_SIZE - 1);
            maxY = Math.max(maxY, y + CHILD_OUTPUT_SIZE - 1);
        }
        return new int[] { minX, minY, maxX, maxY };
    }

    private static void check(BooleanSupplier valid) {
        if (!valid.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException(
                    "LOD derivation cancelled");
        }
    }

    private record ChildReduction(boolean anyKnown, boolean allComplete) { }

    private record ReducedColor(boolean known, int abgr) {
        private static final ReducedColor UNKNOWN = new ReducedColor(false, 0);
    }
}
