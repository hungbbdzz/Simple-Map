package com.velorise.simplemap.client;

/**
 * Describes how an accurate-mode block texture should be combined with biome
 * colour. The model tint-index is authoritative: pre-coloured modded leaves that
 * do not request a tint stay texture-only instead of being recoloured green.
 */
public enum BlockTintPolicy {
    NONE,
    FOLIAGE,
    GRASS,
    SPRUCE,
    BIRCH
}
