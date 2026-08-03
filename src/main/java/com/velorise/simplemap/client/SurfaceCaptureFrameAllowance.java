package com.velorise.simplemap.client;

/**
 * Allocation-free source-capture allowance keyed to a physical render frame.
 *
 * <p>A wall-clock budget can refill several times while one already-slow frame
 * is still running. This helper admits a fixed number of source chunks exactly
 * once per frame id. Compatibility callers without a frame id use a conservative
 * 50 ms fallback window.</p>
 */
final class SurfaceCaptureFrameAllowance {
    private static final long FALLBACK_WINDOW_NANOS = 50_000_000L;

    private long frameId = Long.MIN_VALUE;
    private long allowanceFrameId = Long.MAX_VALUE;
    private long fallbackWindowStartedNanos;
    private int remaining;

    synchronized void beginFrame(long frameId) {
        this.frameId = frameId;
    }

    synchronized int claim(boolean minimapLane, boolean pressured, long nowNanos) {
        boolean resetAllowance;
        if (frameId != Long.MIN_VALUE) {
            resetAllowance = allowanceFrameId != frameId;
            if (resetAllowance) allowanceFrameId = frameId;
        } else {
            long elapsed = nowNanos - fallbackWindowStartedNanos;
            resetAllowance = fallbackWindowStartedNanos == 0L
                    || elapsed < 0L || elapsed >= FALLBACK_WINDOW_NANOS;
            if (resetAllowance) fallbackWindowStartedNanos = nowNanos;
        }
        if (resetAllowance) {
            remaining = pressured
                    ? (minimapLane ? 16 : 8)
                    : (minimapLane ? 64 : 48);
        }
        int requested = pressured
                ? (minimapLane ? 8 : 4)
                : (minimapLane ? 32 : 24);
        int granted = Math.min(requested, remaining);
        remaining -= granted;
        return granted;
    }

    synchronized void reset() {
        frameId = Long.MIN_VALUE;
        allowanceFrameId = Long.MAX_VALUE;
        fallbackWindowStartedNanos = 0L;
        remaining = 0;
    }
}
