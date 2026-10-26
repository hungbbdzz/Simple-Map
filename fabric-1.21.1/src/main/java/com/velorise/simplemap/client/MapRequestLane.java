package com.velorise.simplemap.client;

/**
 * Shared request lanes for map observation, source decoding, page building and
 * publication. Lower executor priority values run first; higher rank/base
 * priority values win when multiple viewports request the same page.
 */
public enum MapRequestLane {
    MINIMAP(4, 2_000_000, 0, 10_000L),
    FULLSCREEN(3, 1_250_000, 1, 10_000L),
    BACKGROUND(2, 350_000, 2, 5_000L),
    PREFETCH(1, 100_000, 3, 10_000L);

    private final int rank;
    private final int priorityBase;
    private final int executorPriority;
    private final long requestTtlMs;

    MapRequestLane(int rank, int priorityBase, int executorPriority,
            long requestTtlMs) {
        this.rank = rank;
        this.priorityBase = priorityBase;
        this.executorPriority = executorPriority;
        this.requestTtlMs = requestTtlMs;
    }

    public int rank() {
        return rank;
    }

    public int priorityBase() {
        return priorityBase;
    }

    public int executorPriority() {
        return executorPriority;
    }

    public long requestTtlMs() {
        return requestTtlMs;
    }

    public boolean strongerThan(MapRequestLane other) {
        return other == null || rank > other.rank;
    }
}
