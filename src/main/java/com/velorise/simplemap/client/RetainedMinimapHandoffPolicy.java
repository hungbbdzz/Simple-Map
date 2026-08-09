package com.velorise.simplemap.client;

/**
 * Xaero-style loading/loaded handoff policy for the retained minimap target.
 * The last complete front frame remains visible while a cold projection builds
 * in the back target; failed cold redraws are retried at a bounded cadence.
 */
final class RetainedMinimapHandoffPolicy {
    static final long COLD_RETRY_NANOS = 125_000_000L;

    private RetainedMinimapHandoffPolicy() { }

    static boolean shouldAttemptRedraw(boolean frontFrameValid,
            long nowNanos, long nextAttemptNanos) {
        return !frontFrameValid || nowNanos >= nextAttemptNanos;
    }

    static boolean retainLastGood(boolean frontFrameValid,
            boolean redrawSucceeded) {
        return frontFrameValid && !redrawSucceeded;
    }

    /**
     * A successful redraw can replace the loaded front frame when either its cold
     * source hierarchy is complete or the renderer copied the compatible loaded
     * front frame underneath the newly arrived pages. The latter is already a
     * complete visual transaction: missing pages are represented by last-good
     * pixels, so waiting for unrelated parent/root residency would freeze streaming
     * updates indefinitely.
     */
    static boolean canPublishSuccessfulRedraw(boolean lastGoodCompatible,
            boolean retainedUnderlayDrawn, boolean coldCoverageReady) {
        return !lastGoodCompatible || retainedUnderlayDrawn || coldCoverageReady;
    }

    static long nextAttemptNanos(long nowNanos) {
        long retry = nowNanos + COLD_RETRY_NANOS;
        return retry < nowNanos ? Long.MAX_VALUE : retry;
    }
}
