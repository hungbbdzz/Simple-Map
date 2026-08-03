package com.velorise.simplemap.client.lod;

import java.util.Arrays;
import java.util.Collection;
import java.util.function.BooleanSupplier;

/**
 * Worker-only compositor for the region-centric LOD hierarchy.
 *
 * <p>Level 0 composes 8x8 exact leaves into one 512-block fallback. Higher
 * levels compose 2x2 direct children, matching Xaero's factor-2 texture levels.
 * Coverage is carried separately from ARGB alpha and the immutable result can be
 * validated before publication.</p>
 */
public final class RegionLodDeriver {
    public static final int TEXTURE_SIZE = 64;
    private static final int LEAF_CHILD_OUTPUT_SIZE = 8;
    private static final int LEAF_SAMPLE_SPAN =
            TEXTURE_SIZE / LEAF_CHILD_OUTPUT_SIZE;

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

    /**
     * One exact 64x64 Surface leaf reduced to the 8x8 footprint it occupies in a
     * level-0 512x512 region node. Keeping this summary instead of another full
     * page copy lets the region hierarchy be rebuilt from retained exact CPU
     * authority without recapturing the complete 512x512 source window.
     */
    public static final class ReducedChildSnapshot {
        private final int childIndex;
        private final long revision;
        private final int[] pixels;
        private final long[] knownRows;
        private final long[] completeRows;

        public ReducedChildSnapshot(int childIndex, long revision,
                int[] pixels, long[] knownRows, long[] completeRows) {
            if (childIndex < 0 || childIndex >= RegionLodGraph.CHILD_COUNT) {
                throw new IllegalArgumentException("childIndex");
            }
            if (pixels == null || pixels.length
                    != LEAF_CHILD_OUTPUT_SIZE * LEAF_CHILD_OUTPUT_SIZE
                    || knownRows == null
                    || knownRows.length != LEAF_CHILD_OUTPUT_SIZE
                    || completeRows == null
                    || completeRows.length != LEAF_CHILD_OUTPUT_SIZE) {
                throw new IllegalArgumentException(
                        "Reduced LOD child requires 8x8 pixels and coverage rows");
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

    /** Reduces one coherent exact Surface page once, at publication time. */
    public static ReducedChildSnapshot reduceExactLeaf(int childIndex,
            long revision, int[] pixels, long[] knownRows) {
        if (pixels == null || pixels.length != TEXTURE_SIZE * TEXTURE_SIZE
                || knownRows == null || knownRows.length != TEXTURE_SIZE) {
            return null;
        }
        int[] reducedPixels = new int[LEAF_CHILD_OUTPUT_SIZE
                * LEAF_CHILD_OUTPUT_SIZE];
        long[] reducedKnownRows = new long[LEAF_CHILD_OUTPUT_SIZE];
        long[] reducedCompleteRows = new long[LEAF_CHILD_OUTPUT_SIZE];
        for (int outputY = 0; outputY < LEAF_CHILD_OUTPUT_SIZE; outputY++) {
            int sourceStartY = outputY * LEAF_SAMPLE_SPAN;
            for (int outputX = 0; outputX < LEAF_CHILD_OUTPUT_SIZE; outputX++) {
                int sourceStartX = outputX * LEAF_SAMPLE_SPAN;
                ReducedColor reduced = reduceBlock(pixels, knownRows,
                        sourceStartX, sourceStartY, LEAF_SAMPLE_SPAN);
                if (reduced.known) {
                    reducedPixels[outputY * LEAF_CHILD_OUTPUT_SIZE + outputX] =
                            reduced.abgr;
                    reducedKnownRows[outputY] |= 1L << outputX;
                }
                if (blockComplete(knownRows, sourceStartX, sourceStartY,
                        LEAF_SAMPLE_SPAN)) {
                    reducedCompleteRows[outputY] |= 1L << outputX;
                }
            }
        }
        return new ReducedChildSnapshot(childIndex, revision,
                reducedPixels, reducedKnownRows, reducedCompleteRows);
    }

    /** Composes reduced exact leaves into one level-0 64x64 region branch. */
    public static PreparedBranch deriveLevel0(RegionLodGraph.Lease lease,
            Collection<ReducedChildSnapshot> children,
            BooleanSupplier stillValid) {
        if (lease == null || lease.key().level() != 0) {
            throw new IllegalArgumentException("level-0 lease required");
        }
        BooleanSupplier valid = stillValid == null ? () -> true : stillValid;
        ReducedChildSnapshot[] indexed =
                new ReducedChildSnapshot[RegionLodGraph.CHILD_COUNT];
        if (children != null) {
            for (ReducedChildSnapshot child : children) {
                if (child == null) continue;
                if (indexed[child.childIndex] != null) {
                    throw new IllegalArgumentException(
                            "Duplicate reduced LOD child " + child.childIndex);
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
            boolean expectedKnown = (lease.knownMask() & bit) != 0L;
            ReducedChildSnapshot child = indexed[childIndex];
            if (child == null) {
                if (expectedKnown) {
                    throw new IllegalArgumentException(
                            "Missing authoritative reduced child " + childIndex);
                }
                continue;
            }
            if (expectedVersions[childIndex] > 0L
                    && child.revision != expectedVersions[childIndex]) {
                throw new IllegalArgumentException(
                        "Stale reduced child " + childIndex + ": expected "
                                + expectedVersions[childIndex] + ", got "
                                + child.revision);
            }
            int childX = childIndex & 7;
            int childZ = childIndex >>> 3;
            int destinationStartX = childX * LEAF_CHILD_OUTPUT_SIZE;
            int destinationStartY = childZ * LEAF_CHILD_OUTPUT_SIZE;
            boolean anyKnown = false;
            boolean allComplete = true;
            for (int row = 0; row < LEAF_CHILD_OUTPUT_SIZE; row++) {
                long known = child.knownRows[row] & 0xFFL;
                long complete = child.completeRows[row] & 0xFFL;
                anyKnown |= known != 0L;
                allComplete &= complete == 0xFFL;
                int destinationY = destinationStartY + row;
                int destinationOffset = destinationY * TEXTURE_SIZE
                        + destinationStartX;
                System.arraycopy(child.pixels, row * LEAF_CHILD_OUTPUT_SIZE,
                        output, destinationOffset, LEAF_CHILD_OUTPUT_SIZE);
                outputKnownRows[destinationY] |= known << destinationStartX;
                outputCompleteRows[destinationY] |=
                        complete << destinationStartX;
            }
            if (anyKnown) outputKnownMask |= bit;
            if (allComplete) outputCompleteMask |= bit;
        }
        int[] dirtyRect = dirtyRect(lease.key().level(),
                lease.dirtyChildMask());
        return new PreparedBranch(lease.key(), lease.stamp(), lease.revision(),
                TEXTURE_SIZE, TEXTURE_SIZE, output, outputKnownMask,
                outputCompleteMask, outputKnownRows, outputCompleteRows,
                expectedVersions, dirtyRect[0], dirtyRect[1],
                dirtyRect[2], dirtyRect[3]);
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

        int childCount = RegionLodGraph.childCountForLevel(lease.key().level());
        for (int childIndex = 0; childIndex < childCount; childIndex++) {
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
                    outputKnownRows, outputCompleteRows, valid,
                    lease.key().level());
            if (reduction.anyKnown()) outputKnownMask |= bit;
            if (reduction.allComplete()) outputCompleteMask |= bit;
        }

        int[] dirtyRect = dirtyRect(lease.key().level(),
                lease.dirtyChildMask());
        return new PreparedBranch(lease.key(), lease.stamp(), lease.revision(),
                TEXTURE_SIZE, TEXTURE_SIZE, output, outputKnownMask,
                outputCompleteMask, outputKnownRows, outputCompleteRows,
                expectedVersions, dirtyRect[0], dirtyRect[1],
                dirtyRect[2], dirtyRect[3]);
    }

    private static ChildReduction reduceChild(ChildSnapshot child,
            int[] output, long[] outputKnownRows, long[] outputCompleteRows,
            BooleanSupplier valid, int level) {
        int childrenPerAxis = level <= 0
                ? RegionLodGraph.LEAF_CHILDREN_PER_AXIS
                : RegionLodGraph.PARENT_CHILDREN_PER_AXIS;
        int childOutputSize = TEXTURE_SIZE / childrenPerAxis;
        int sampleSpan = TEXTURE_SIZE / childOutputSize;
        int childX = child.childIndex % childrenPerAxis;
        int childZ = child.childIndex / childrenPerAxis;
        int destinationStartX = childX * childOutputSize;
        int destinationStartY = childZ * childOutputSize;
        boolean anyKnown = false;
        boolean allComplete = true;

        for (int outputY = 0; outputY < childOutputSize; outputY++) {
            check(valid);
            int sourceStartY = outputY * sampleSpan;
            int destinationY = destinationStartY + outputY;
            int destinationRow = destinationY * TEXTURE_SIZE;
            for (int outputX = 0; outputX < childOutputSize; outputX++) {
                int sourceStartX = outputX * sampleSpan;
                int destinationX = destinationStartX + outputX;
                ReducedColor reduced = reduceBlock(child, sourceStartX,
                        sourceStartY, sampleSpan);
                boolean complete = blockComplete(child, sourceStartX,
                        sourceStartY, sampleSpan);
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
            int sourceStartX, int sourceStartY, int sampleSpan) {
        long mask = ((1L << sampleSpan) - 1L) << sourceStartX;
        for (int y = 0; y < sampleSpan; y++) {
            if ((child.completeRows[sourceStartY + y] & mask) != mask) {
                return false;
            }
        }
        return true;
    }

    private static boolean blockComplete(long[] knownRows,
            int sourceStartX, int sourceStartY, int sampleSpan) {
        long mask = ((1L << sampleSpan) - 1L) << sourceStartX;
        for (int y = 0; y < sampleSpan; y++) {
            if ((knownRows[sourceStartY + y] & mask) != mask) return false;
        }
        return true;
    }

    private static ReducedColor reduceBlock(ChildSnapshot child,
            int sourceStartX, int sourceStartY, int sampleSpan) {
        long alpha = 0L;
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int knownCount = 0;
        int coloredCount = 0;
        for (int y = 0; y < sampleSpan; y++) {
            int sourceY = sourceStartY + y;
            long knownRow = child.knownRows[sourceY];
            int sourceRow = sourceY * TEXTURE_SIZE;
            for (int x = 0; x < sampleSpan; x++) {
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

    private static ReducedColor reduceBlock(int[] pixels, long[] knownRows,
            int sourceStartX, int sourceStartY, int sampleSpan) {
        long alpha = 0L;
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int knownCount = 0;
        int coloredCount = 0;
        for (int y = 0; y < sampleSpan; y++) {
            int sourceY = sourceStartY + y;
            long knownRow = knownRows[sourceY];
            int sourceRow = sourceY * TEXTURE_SIZE;
            for (int x = 0; x < sampleSpan; x++) {
                int sourceX = sourceStartX + x;
                if ((knownRow & (1L << sourceX)) == 0L) continue;
                knownCount++;
                int color = pixels[sourceRow + sourceX];
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

    private static int[] dirtyRect(int level, long dirtyChildMask) {
        if (dirtyChildMask == 0L) return new int[] { 0, 0, 63, 63 };
        int minX = TEXTURE_SIZE;
        int minY = TEXTURE_SIZE;
        int maxX = -1;
        int maxY = -1;
        int childrenPerAxis = level <= 0
                ? RegionLodGraph.LEAF_CHILDREN_PER_AXIS
                : RegionLodGraph.PARENT_CHILDREN_PER_AXIS;
        int childCount = RegionLodGraph.childCountForLevel(level);
        int childOutputSize = TEXTURE_SIZE / childrenPerAxis;
        for (int child = 0; child < childCount; child++) {
            if ((dirtyChildMask & (1L << child)) == 0L) continue;
            int x = (child % childrenPerAxis) * childOutputSize;
            int y = (child / childrenPerAxis) * childOutputSize;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + childOutputSize - 1);
            maxY = Math.max(maxY, y + childOutputSize - 1);
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
