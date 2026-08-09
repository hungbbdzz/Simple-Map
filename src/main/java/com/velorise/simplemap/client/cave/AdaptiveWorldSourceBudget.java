package com.velorise.simplemap.client.cave;

/**
 * Pure policy for the decoded world-source cache.
 *
 * <p>Kept independent from Minecraft classes so heap-pressure behavior can be
 * unit tested without launching the game. The policy reserves most heap for the
 * game/modpack and contracts both residency and decode concurrency before the
 * JVM approaches a GC spiral.</p>
 */
final class AdaptiveWorldSourceBudget {
    static final long MIB = 1L << 20;
    static final long MIN_TARGET = 32L * MIB;
    static final long MAX_TARGET = 512L * MIB;

    private AdaptiveWorldSourceBudget() {
    }

    static Snapshot evaluate(long maximumHeap, long committedHeap, long freeCommitted,
            int processors, int pendingForeground, int pendingBackground) {
        long max = Math.max(64L * MIB, maximumHeap);
        long committed = Math.max(0L, Math.min(max, committedHeap));
        long free = Math.max(0L, Math.min(committed, freeCommitted));
        long used = Math.max(0L, committed - free);
        long headroom = Math.max(0L, max - used);
        double pressure = Math.min(1.0D, used / (double) max);

        long target = Math.max(64L * MIB, Math.min(MAX_TARGET, max / 10L));
        if (pressure >= 0.94D) target /= 8L;
        else if (pressure >= 0.89D) target /= 4L;
        else if (pressure >= 0.82D) target /= 2L;
        target = Math.min(target, Math.max(MIN_TARGET, headroom / 2L));
        target = clamp(target, MIN_TARGET, MAX_TARGET);

        int cpu = Math.max(1, processors);
        // A fullscreen page is an atomic 16-chunk transaction. The previous
        // 16-20 source ceiling let the page scheduler open four pages but denied
        // leaves in three of them; those pages warmed cache without publishing.
        // Healthy cold builds now reserve several whole pages, while the same heap
        // pressure thresholds still collapse concurrency before a GC spiral.
        int baseInFlight = Math.max(32, Math.min(256, cpu * 12));
        if (pressure >= 0.94D) baseInFlight = 16;
        else if (pressure >= 0.89D) baseInFlight = Math.max(32, baseInFlight / 4);
        else if (pressure >= 0.82D) baseInFlight = Math.max(64, baseInFlight / 2);

        // Foreground cave/surface requests must not be starved by speculative
        // viewport prefetch. Backlog can expand admission slightly while heap is
        // healthy, but never beyond the CPU-derived ceiling.
        int demand = Math.max(0, pendingForeground) + Math.max(0, pendingBackground) / 4;
        int maximumInFlight = Math.min(288,
                baseInFlight + Math.min(32, demand / 8));
        int maximumPrefetch = Math.max(4,
                maximumInFlight / (pressure >= 0.82D ? 8 : 4));
        return new Snapshot(target, maximumInFlight, maximumPrefetch, pressure, headroom);
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Snapshot(long targetBytes, int maximumInFlight, int maximumPrefetch,
            double pressure, long headroomBytes) {
    }
}
