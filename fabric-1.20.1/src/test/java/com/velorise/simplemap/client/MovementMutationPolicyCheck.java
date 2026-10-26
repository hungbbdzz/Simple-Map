package com.velorise.simplemap.client;

/** Regression checks for sustained movement mutation admission/backpressure. */
public final class MovementMutationPolicyCheck {
    private MovementMutationPolicyCheck() {
    }

    public static void main(String[] args) {
        require(!MovementMutationPolicy.schedulesSurfaceWorkForUnload(),
                "chunk unload scheduled image work although pixels are retained");
        require(!MovementMutationPolicy.shouldCompactForAuthoritativeFrontier(511),
                "precise queue compacted below its working set");
        require(MovementMutationPolicy.shouldCompactForAuthoritativeFrontier(512),
                "precise queue did not compact at its working-set boundary");

        int preciseChunks = 0;
        int compactedChunks = 0;
        for (int delivered = 0; delivered < 10_000; delivered++) {
            if (MovementMutationPolicy.shouldCompactForAuthoritativeFrontier(
                    preciseChunks)) {
                preciseChunks--;
                compactedChunks++;
            }
            preciseChunks++;
            require(preciseChunks <= MovementMutationPolicy.PRECISE_CHUNK_WORKING_SET,
                    "travel workload exceeded precise mutation working set");
        }
        require(preciseChunks == MovementMutationPolicy.PRECISE_CHUNK_WORKING_SET,
                "travel model did not retain the intended precise frontier");
        require(compactedChunks == 10_000 - preciseChunks,
                "travel model lost or duplicated compacted chunk work");

        require(!MovementMutationPolicy.shouldYieldActiveToUrgent(true, true),
                "hot active transaction yielded and broke local publication locality");
        require(!MovementMutationPolicy.shouldYieldActiveToUrgent(false, false),
                "cold active transaction yielded without replacement work");
        require(MovementMutationPolicy.shouldYieldActiveToUrgent(false, true),
                "cold active transaction blocked a new authoritative frontier");

        require(MovementMutationPolicy.BACKLOG_CHUNK_THRESHOLD
                        < MovementMutationPolicy.PRECISE_CHUNK_WORKING_SET,
                "governor cannot detect backlog before compaction starts");
        System.out.println("MOVEMENT_MUTATION_POLICY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
