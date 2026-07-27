package com.velorise.simplemap.client.cave;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Per-tile scan context shared by all 256 X/Z columns of one Minecraft chunk.
 *
 * Section palette classification is computed once for the tile burst rather than
 * repeated for every column. Only sections made entirely from context-independent
 * full-motion blocks are treated as fast solid sections; dynamic/partial states
 * remain on the conservative mixed path.
 */
public final class CaveTileScanContext {
    public static final byte UNKNOWN = 0;
    public static final byte ALL_AIR = 1;
    public static final byte ALL_SOLID_FAST = 2;
    public static final byte MIXED = 3;

    private final LevelChunk chunk;
    private final byte[] sectionKinds;

    private CaveTileScanContext(LevelChunk chunk) {
        this.chunk = chunk;
        this.sectionKinds = new byte[chunk.getSections().length];
    }

    public static CaveTileScanContext create(Level level, int chunkX, int chunkZ) {
        if (level == null || !level.hasChunk(chunkX, chunkZ)) return null;
        return new CaveTileScanContext(level.getChunk(chunkX, chunkZ));
    }

    public LevelChunk chunk() {
        return chunk;
    }

    public byte sectionKind(Level level, int y, CaveStateClassifier classifier) {
        int index = level.getSectionIndex(y);
        LevelChunkSection[] sections = chunk.getSections();
        if (index < 0 || index >= sections.length) return MIXED;

        byte cached = sectionKinds[index];
        if (cached != UNKNOWN) return cached;

        LevelChunkSection section = sections[index];
        byte resolved;
        if (section == null || section.hasOnlyAir()) {
            resolved = ALL_AIR;
        } else {
            /*
             * maybeHas() evaluates the section palette, not every world position.
             * Treat anything except SOLID_FAST as potentially open/dynamic so this
             * optimization can never skip stairs, slabs, fluids or modded shapes.
             */
            boolean mayContainOpenOrDynamic = section.maybeHas(state -> {
                if (classifier.classify(state) != CaveStateClassifier.SOLID_FAST) return true;
                // Full-cube foliage/trunks must not turn a tree-heavy palette into
                // an underground solid section. The column scanner already treats
                // these as surface noise; keep the section fast path consistent.
                return state.is(BlockTags.LEAVES)
                        || state.is(BlockTags.LOGS)
                        || state.canBeReplaced();
            });
            resolved = mayContainOpenOrDynamic ? MIXED : ALL_SOLID_FAST;
        }
        sectionKinds[index] = resolved;
        return resolved;
    }

    public int sectionBottom(Level level, int y) {
        int minimum = level.getMinBuildHeight();
        return Math.max(minimum, Math.floorDiv(y, 16) * 16);
    }
}
