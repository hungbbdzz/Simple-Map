package com.velorise.simplemap.client.cave;

/**
 * Cave-mode transition marker.
 *
 * <p>The old implementation reduced the visible radius and admission counts for
 * almost two seconds after every OFF/LAYERED/FULL switch. That avoided one large
 * burst by making the map visibly stall. The current pipeline enumerates the full
 * visible working set immediately and lets the CPU/GPU deadline controllers bound
 * the amount of work actually performed in each frame. The marker is retained for
 * diagnostics and for short-lived scheduler prioritisation, but it no longer
 * shrinks the viewport or suppresses useful foreground progress.</p>
 */
public final class CaveModeTransitionPolicy {
    private static final long ACTIVE_NANOS = 750_000_000L;
    private static volatile long transitionStartedNanos;

    private CaveModeTransitionPolicy() { }

    public static void begin() {
        transitionStartedNanos = System.nanoTime();
    }

    public static void reset() {
        transitionStartedNanos = 0L;
    }

    public static long ageNanos() {
        long started = transitionStartedNanos;
        return started == 0L ? Long.MAX_VALUE
                : Math.max(0L, System.nanoTime() - started);
    }

    public static boolean active() {
        return ageNanos() < ACTIVE_NANOS;
    }

    public static int exactAdmissionBudget(int normal) {
        return Math.max(0, normal);
    }

    public static int sourceAdmissionBudget(int normal) {
        return Math.max(0, normal);
    }

    public static int loadedFrontierBudget(int normal) {
        return Math.max(0, normal);
    }

    public static int viewportChunkBudget(int normal) {
        return Math.max(0, normal);
    }

    public static int liveRadius(int normal) {
        return Math.max(0, normal);
    }

    public static long foregroundBudget(long normal) {
        long safe = Math.max(0L, normal);
        // The first visible Cave frames should publish retained/prewarmed products,
        // not consume a multi-millisecond source slice. Xaero similarly gates the
        // loadedCaving switch while its writer cursor advances under a hard limit.
        return active() ? Math.min(safe, 300_000L) : safe;
    }
}
