package com.velorise.simplemap.client;

/**
 * Shared texel-density policy for fullscreen and minimap rendering.
 *
 * <p>Level 0 is the exact 64x64 leaf texture (one block per texel). Branch
 * level {@code n} covers {@code 64 << n} world blocks with a 64x64 texture,
 * therefore one branch texel represents {@code 1 << n} blocks. The selected
 * level never becomes coarser than the current screen footprint.</p>
 */
public final class MapLodPolicy {
    public static final int LEAF_WORLD_SIZE = 64;
    public static final int MAX_BRANCH_LEVEL = 7;

    private MapLodPolicy() {
    }

    public static int branchLevel(float pixelsPerBlock) {
        return branchLevel(pixelsPerBlock, MAX_BRANCH_LEVEL);
    }

    public static int branchLevel(float pixelsPerBlock, int maximumLevel) {
        double footprint = blocksPerScreenPixel(pixelsPerBlock);
        if (footprint < 2.0) return 0;
        int level = (int) Math.floor(Math.log(footprint) / Math.log(2.0));
        return Math.max(0, Math.min(maximumLevel, level));
    }


    /**
     * Selects the finest density-correct level that also fits the retained-node
     * budget for the current viewport. This prevents atlas thrashing on extremely
     * wide fullscreen views while keeping minimap views on a finer level.
     */
    public static int branchLevel(float pixelsPerBlock, int viewportWidth,
            int viewportHeight, double searchFactor, int targetVisibleNodes) {
        int level = branchLevel(pixelsPerBlock);
        double worldWidth = Math.max(1.0, viewportWidth * searchFactor
                * blocksPerScreenPixel(pixelsPerBlock));
        double worldHeight = Math.max(1.0, viewportHeight * searchFactor
                * blocksPerScreenPixel(pixelsPerBlock));
        // Exact leaves share one atlas binding and can therefore tolerate a much
        // larger quad count than independent branch nodes. The threshold follows
        // the selected memory profile instead of assuming the old 1024-slot atlas.
        if (level == 0) {
            long exactX = Math.max(1L, (long) Math.ceil(worldWidth / LEAF_WORLD_SIZE) + 1L);
            long exactZ = Math.max(1L, (long) Math.ceil(worldHeight / LEAF_WORLD_SIZE) + 1L);
            long exactSlots = (long) MapMemoryBudgetPolicy.surfaceLeafColumns()
                    * MapMemoryBudgetPolicy.surfaceLeafColumns();
            long safeExactSlots = Math.max(64L, exactSlots * 7L / 8L);
            if (exactX * exactZ <= safeExactSlots) return 0;
            level = 1;
        }
        while (level < MAX_BRANCH_LEVEL) {
            int columns = level <= 2
                    ? MapMemoryBudgetPolicy.branchLowColumns()
                    : MapMemoryBudgetPolicy.branchHighColumns();
            int atlasCapacity = columns * columns;
            int safeCapacity = Math.max(16, atlasCapacity * 3 / 4);
            int budget = Math.max(16, Math.min(targetVisibleNodes, safeCapacity));
            int nodeWorldSize = worldSizeForBranch(level);
            long nodesX = Math.max(1L, (long) Math.ceil(worldWidth / nodeWorldSize) + 1L);
            long nodesZ = Math.max(1L, (long) Math.ceil(worldHeight / nodeWorldSize) + 1L);
            if (nodesX * nodesZ <= budget) break;
            level++;
        }
        return level;
    }


    /**
     * Adds a small density hysteresis around adjacent LOD boundaries.
     *
     * <p>Without this guard a tiny wheel/viewport rounding change can alternate
     * between two branch levels every frame, making the renderer swap between
     * independently resident snapshots. Budget-forced coarser levels are never
     * delayed, because protecting frame time is more important than hysteresis.</p>
     */
    public static int stabilizeBranchLevel(int candidateLevel, int previousLevel,
            float pixelsPerBlock) {
        if (previousLevel < 0 || candidateLevel == previousLevel) return candidateLevel;
        if (Math.abs(candidateLevel - previousLevel) > 1) return candidateLevel;

        int densityLevel = branchLevel(pixelsPerBlock);
        // The viewport-node budget may require a coarser level than density alone.
        if (candidateLevel > previousLevel && candidateLevel > densityLevel) {
            return candidateLevel;
        }
        // When a previous budget-forced level is no longer necessary, return to the
        // density-correct level immediately rather than retaining excessive blur.
        if (candidateLevel < previousLevel && previousLevel > densityLevel) {
            return candidateLevel;
        }

        double footprint = blocksPerScreenPixel(pixelsPerBlock);
        if (candidateLevel > previousLevel) {
            double enterCoarser = (1 << candidateLevel) * 1.12;
            return footprint >= enterCoarser ? candidateLevel : previousLevel;
        }
        double leaveCoarser = (1 << previousLevel) * 0.88;
        return footprint <= leaveCoarser ? candidateLevel : previousLevel;
    }

    /**
     * Cave-specific LOD stabilization around Xaero-style user-scale boundaries.
     *
     * <p>Cave hierarchy selection is driven by logical map zoom, not GUI-scaled
     * framebuffer density. A narrow two-percent guard removes wheel jitter without
     * retaining exact L0 deep into the range where an L1 root should already be the
     * visible authority.</p>
     */
    public static int stabilizeCaveBranchLevel(int candidateLevel, int previousLevel,
            float logicalPixelsPerBlock) {
        if (previousLevel < 0 || candidateLevel == previousLevel) return candidateLevel;
        if (Math.abs(candidateLevel - previousLevel) > 1) return candidateLevel;

        double footprint = blocksPerScreenPixel(logicalPixelsPerBlock);
        if (candidateLevel > previousLevel) {
            double enterCoarser = (1 << candidateLevel) * 1.02;
            return footprint >= enterCoarser ? candidateLevel : previousLevel;
        }
        double leaveCoarser = (1 << previousLevel) * 0.98;
        return footprint <= leaveCoarser ? candidateLevel : previousLevel;
    }

    public static int leafMipLevel(float pixelsPerBlock, int maximumMip) {
        double footprint = blocksPerScreenPixel(pixelsPerBlock);
        int level = footprint < 2.0 ? 0
                : (int) Math.floor(Math.log(footprint) / Math.log(2.0));
        return Math.max(0, Math.min(maximumMip, level));
    }

    public static double blocksPerScreenPixel(float pixelsPerBlock) {
        return 1.0 / Math.max(0.0001, pixelsPerBlock);
    }

    public static int worldSizeForBranch(int level) {
        if (level <= 0) return LEAF_WORLD_SIZE;
        return LEAF_WORLD_SIZE << level;
    }

    public static int pageSpanForBranch(int level) {
        return level <= 0 ? 1 : 1 << level;
    }
}
