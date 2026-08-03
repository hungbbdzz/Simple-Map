package com.velorise.simplemap.client;

/**
 * Allocation-free admission decisions for sustained chunk travel.
 *
 * <p>The policy is dependency-free so movement/backpressure invariants can be
 * regression-tested without starting Minecraft.</p>
 */
final class MovementMutationPolicy {
    static final int PRECISE_CHUNK_WORKING_SET = 512;
    static final int BACKLOG_COLUMN_THRESHOLD = 1_024;
    static final int BACKLOG_CHUNK_THRESHOLD = 384;

    private MovementMutationPolicy() {
    }

    static boolean schedulesSurfaceWorkForUnload() {
        return false;
    }

    static boolean shouldCompactForAuthoritativeFrontier(int pendingChunks) {
        return pendingChunks >= PRECISE_CHUNK_WORKING_SET;
    }

    static boolean shouldYieldActiveToUrgent(boolean activeChunkHot,
            boolean urgentChunkWaiting) {
        return !activeChunkHot && urgentChunkWaiting;
    }
}
