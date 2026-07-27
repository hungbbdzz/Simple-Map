package com.velorise.simplemap.client.cave;

/**
 * Stable identity for layered cave textures.
 *
 * <p>Exact Top-Y remains the projection parameter, while cache, page, atlas and
 * LOD identity use a 16-block band. Moving the slider inside one band therefore
 * replaces pixels in-place instead of abandoning a complete texture hierarchy.</p>
 */
public final class CaveLayerBand {
    public static final int HEIGHT = 16;

    private CaveLayerBand() {
    }

    public static int key(CaveView view, int topY) {
        if (view == CaveView.FULL) return Integer.MIN_VALUE;
        return Math.floorDiv(topY, HEIGHT) * HEIGHT;
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
