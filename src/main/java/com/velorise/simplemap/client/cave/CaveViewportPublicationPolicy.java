package com.velorise.simplemap.client.cave;

/**
 * Global publication cadence for incremental Layered-Cave fullscreen updates.
 *
 * <p>Per-page tile coalescing is insufficient because independent page deadlines
 * drift across the viewport. A shared gate aligns page commits into one redraw
 * burst while preserving immediate first publication after a Y-projection change.</p>
 */
final class CaveViewportPublicationPolicy {
    static final long FULLSCREEN_LAYERED_INTERVAL_MS = 16L;

    private CaveViewportPublicationPolicy() { }

    static boolean windowOpen(boolean layeredFullscreenActive,
            boolean projectionChanged, long nextWindowMs, long nowMs) {
        return !layeredFullscreenActive || projectionChanged || nowMs >= nextWindowMs;
    }

    static long nextWindow(long nowMs) {
        return nowMs + FULLSCREEN_LAYERED_INTERVAL_MS;
    }
}
