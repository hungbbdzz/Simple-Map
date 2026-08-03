package com.velorise.simplemap.client.cave;

/** Dependency-free invariants for immutable layered-cave projection bands. */
public final class CaveLayerBandCheck {
    private CaveLayerBandCheck() { }

    public static void main(String[] args) {
        layeredBandHasOneProjection();
        negativeBandsUseFloorDivision();
        fullViewHasNoFixedTopY();
        System.out.println("Simple Map immutable Cave layer-band checks passed");
    }

    private static void layeredBandHasOneProjection() {
        for (int y = -64; y <= 319; y++) {
            int key = CaveLayerBand.key(CaveView.LAYERED, y);
            int projection = CaveLayerBand.projectionTopY(CaveView.LAYERED, y);
            require(projection == CaveLayerBand.upperY(key),
                    "layer projection does not include the full band");
            require(CaveLayerBand.same(CaveView.LAYERED, y, projection),
                    "projection escaped its source band");
            for (int member = CaveLayerBand.lowerY(key);
                    member <= CaveLayerBand.upperY(key); member++) {
                require(CaveLayerBand.projectionTopY(CaveView.LAYERED, member)
                                == projection,
                        "one band produced multiple projection Top-Y values");
            }
        }
    }

    private static void negativeBandsUseFloorDivision() {
        require(CaveLayerBand.key(CaveView.LAYERED, -1) == -16,
                "negative Y did not use floor division");
        require(CaveLayerBand.projectionTopY(CaveView.LAYERED, -1) == -1,
                "negative band upper edge changed");
        require(CaveLayerBand.projectionTopY(CaveView.LAYERED, -16) == -1,
                "negative band is not immutable");
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
