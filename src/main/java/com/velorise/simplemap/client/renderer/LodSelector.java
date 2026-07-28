package com.velorise.simplemap.client.renderer;

/** Density-first hierarchy selection; geometry pressure must not silently drop coverage. */
public final class LodSelector {
    private LodSelector() { }

    public static int surfaceLevel(float logicalPixelsPerBlock) {
        if (logicalPixelsPerBlock >= 0.50f) return 0;
        if (logicalPixelsPerBlock >= 0.25f) return 1;
        if (logicalPixelsPerBlock >= 0.125f) return 2;
        return 3;
    }
}
