package com.velorise.simplemap.client.cave;

import net.minecraft.resources.ResourceLocation;

/**
 * Immutable draw handle for one cave texture inside a shared GPU atlas.
 *
 * <p>Leaf pages use level {@code 0}. Hierarchical branch nodes use levels
 * {@code 1..7}; their coverage masks describe direct children already folded
 * into the parent texture. Unknown parent areas remain transparent so the
 * renderer can recursively fall back to finer nodes without clearing old data.</p>
 */
public record CaveAtlasRegion(
        ResourceLocation texture,
        float sourceX,
        float sourceY,
        int sourceSize,
        int atlasSize,
        int level,
        int worldSize,
        long knownMask,
        long completeMask) {

    /** Compatibility constructor for exact 64x64 leaf pages. */
    public CaveAtlasRegion(ResourceLocation texture, float sourceX, float sourceY,
            int sourceSize, int atlasSize) {
        this(texture, sourceX, sourceY, sourceSize, atlasSize,
                0, CaveTextureAtlas.PAGE_SIZE, -1L, -1L);
    }

    public boolean childKnown(int childIndex) {
        return childIndex >= 0 && childIndex < 64
                && (knownMask & (1L << childIndex)) != 0L;
    }

    public boolean childComplete(int childIndex) {
        return childIndex >= 0 && childIndex < 64
                && (completeMask & (1L << childIndex)) != 0L;
    }

    public boolean fullyComplete() {
        long full = level <= 0 ? -1L : 0xFL;
        return completeMask == full;
    }
}
