package com.velorise.simplemap.client;

/** Decides which render entry point owns tick-side viewport publication. */
final class ViewportDemandPublicationPolicy {
    private ViewportDemandPublicationPolicy() {
    }

    /**
     * Retained off-screen redraws are already preceded by a lightweight demand
     * refresh from MinimapRenderer/MapScreen. Publishing again from the atlas
     * replay can alternate two different overscan rectangles and rebase planning.
     */
    static boolean rendererOwnsDemand(boolean managesWindowScissor) {
        return managesWindowScissor;
    }
}
