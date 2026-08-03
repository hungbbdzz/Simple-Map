package com.velorise.simplemap.client.cave;

/**
 * Stable identity and projection for layered cave textures.
 *
 * <p>Each retained layer owns one deterministic 16-block vertical band and one
 * deterministic projection Top-Y. The previous implementation kept the band key
 * stable but allowed the exact Top-Y to move between two values inside the same
 * band. That caused old and new 16x16 tiles to accumulate in one page after the
 * player moved up or down. A band now always projects from its upper-edge
 * Top-Y, so revisiting it produces the same pixels and the same LOD hierarchy.</p>
 */
public final class CaveLayerBand {
    public static final int HEIGHT = 16;
    /** The projection includes the entire selected band, including its highest Y. */
    public static final int PROJECTION_OFFSET = HEIGHT - 1;

    private CaveLayerBand() {
    }

    public static int key(CaveView view, int topY) {
        if (view == CaveView.FULL) return Integer.MIN_VALUE;
        return Math.floorDiv(topY, HEIGHT) * HEIGHT;
    }

    /**
     * Returns the only legal projection Top-Y for the requested layered band.
     * Full Cave is column-projected and therefore has no fixed Top-Y.
     */
    public static int projectionTopY(CaveView view, int requestedTopY) {
        if (view == CaveView.FULL) return Integer.MIN_VALUE;
        return key(view, requestedTopY) + PROJECTION_OFFSET;
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
