package com.velorise.simplemap.client.cave;

/** Dependency-free invariants for Xaero-style layered-cave identities. */
public final class CaveLayerBandCheck {
    private CaveLayerBandCheck() { }

    public static void main(String[] args) {
        layeredBandRetainsExactProjection();
        negativeBandsUseFloorDivision();
        fullViewHasNoFixedTopY();
        System.out.println("Simple Map immutable Cave layer-band checks passed");
    }

    private static void layeredBandRetainsExactProjection() {
        for (int y = -64; y <= 319; y++) {
            int key = CaveLayerBand.key(CaveView.LAYERED, y);
            int projection = CaveLayerBand.projectionTopY(CaveView.LAYERED, y);
            require(projection == y,
                    "layer projection changed the selected Top-Y");
            require(CaveLayerBand.same(CaveView.LAYERED, y, projection),
                    "projection escaped its source band");
            for (int member = CaveLayerBand.lowerY(key);
                    member <= CaveLayerBand.upperY(key); member++) {
                require(CaveLayerBand.projectionTopY(CaveView.LAYERED, member)
                                == member,
                        "retained band discarded an exact Top-Y value");
                require(CaveLayerBand.key(CaveView.LAYERED, member) == key,
                        "member escaped its retained cache identity");
            }
        }
    }

    private static void negativeBandsUseFloorDivision() {
        require(CaveLayerBand.key(CaveView.LAYERED, -1) == -16,
                "negative Y did not use floor division");
        require(CaveLayerBand.projectionTopY(CaveView.LAYERED, -1) == -1,
                "negative exact Top-Y changed");
        require(CaveLayerBand.projectionTopY(CaveView.LAYERED, -16) == -16,
                "negative band boundary changed exact Top-Y");
        require(CaveLayerBand.projectionTopY(CaveView.LAYERED, -17) == -17,
                "adjacent negative band collapsed");
    }

    private static void fullViewHasNoFixedTopY() {
        require(CaveLayerBand.key(CaveView.FULL, 120) == Integer.MIN_VALUE,
                "Full Cave acquired a vertical band");
        require(CaveLayerBand.projectionTopY(CaveView.FULL, 120)
                        == Integer.MIN_VALUE,
                "Full Cave acquired a fixed projection Top-Y");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
