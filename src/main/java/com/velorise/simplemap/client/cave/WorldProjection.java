package com.velorise.simplemap.client.cave;

/**
 * Presentation selected from one immutable decoded world-save source.
 *
 * <p>SURFACE, LAYERED and FULL are not separate readers. They are projection
 * functions over the same decoded chunk palette/biome/light/block-entity source.
 * This is the source/projection split used by the unified world-save pipeline.</p>
 */
public enum WorldProjection {
    SURFACE,
    LAYERED,
    FULL;

    public boolean isCave() {
        return this != SURFACE;
    }

    public CaveView caveView() {
        return switch (this) {
            case LAYERED -> CaveView.LAYERED;
            case FULL -> CaveView.FULL;
            case SURFACE -> throw new IllegalStateException(
                    "Surface has no CaveView");
        };
    }

    public int canonicalTopY(int requestedTopY) {
        return this == FULL ? Integer.MIN_VALUE : requestedTopY;
    }
}
