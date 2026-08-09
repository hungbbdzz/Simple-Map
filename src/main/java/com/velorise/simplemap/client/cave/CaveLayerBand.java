package com.velorise.simplemap.client.cave;

/**
 * Stable identity and projection for layered cave textures.
 *
 * <p>Xaero uses {@code caveStart >> 4} as the retained layer/cache identity while
 * preserving the exact cave start inside that layer. Simple Map follows the same
 * split: the band key keeps storage bounded, and {@code projectionTopY} remains
 * the exact viewer-selected value. Tiles record that exact value so stale members
 * of the same band are rebuilt instead of being mixed into an authoritative page.</p>
 */
public final class CaveLayerBand {
    public static final int HEIGHT = 16;
    private CaveLayerBand() {
    }

    public static int key(CaveView view, int topY) {
        if (view == CaveView.FULL) return Integer.MIN_VALUE;
        return Math.floorDiv(topY, HEIGHT) * HEIGHT;
    }

    /**
     * Returns the exact projection Top-Y requested inside the retained band.
     * Full Cave is column-projected and therefore has no fixed Top-Y.
     */
    public static int projectionTopY(CaveView view, int requestedTopY) {
        if (view == CaveView.FULL) return Integer.MIN_VALUE;
        return requestedTopY;
    }

    public static int lowerY(int bandKey) {
        return bandKey;
    }

    public static int upperY(int bandKey) {
        return bandKey + HEIGHT - 1;
    }

    public static boolean same(CaveView view, int firstTopY, int secondTopY) {
        return key(view, firstTopY) == key(view, secondTopY);
    }
}
