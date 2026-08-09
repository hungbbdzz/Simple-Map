package com.velorise.simplemap.client.cave;

/** PASS86 behavioral guard for projection parameters over one source model. */
public final class WorldProjectionModeCheck {
    private WorldProjectionModeCheck() { }

    public static void main(String[] args) {
        require(!WorldProjection.SURFACE.isCave(), "Surface classified as Cave");
        require(WorldProjection.LAYERED.isCave(), "Layered not classified as Cave");
        require(WorldProjection.FULL.isCave(), "Full not classified as Cave");
        require(WorldProjection.LAYERED.caveView() == CaveView.LAYERED,
                "Layered projection mapping changed");
        require(WorldProjection.FULL.caveView() == CaveView.FULL,
                "Full projection mapping changed");
        require(WorldProjection.LAYERED.canonicalTopY(37) == 37,
                "Layered Top-Y must remain exact");
        require(WorldProjection.FULL.canonicalTopY(37) == Integer.MIN_VALUE,
                "Full must use the canonical all-depth projection key");
        System.out.println("WORLD_PROJECTION_MODE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
