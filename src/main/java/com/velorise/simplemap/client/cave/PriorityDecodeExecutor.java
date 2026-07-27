package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;

import java.util.concurrent.Executor;

/**
 * Compatibility adapter over the global map CPU control plane.
 *
 * <p>Decode and exact-page workers no longer create private thread pools. The
 * supplied priority is translated back to the shared request lane so minimap,
 * fullscreen and prefetch work are ordered against every other map CPU task.</p>
 */
final class PriorityDecodeExecutor {
    static final int FOREGROUND = 0;
    static final int PREFETCH = 1;

    private final MapWorkScheduler.WorkType workType;
    private final int cost;

    PriorityDecodeExecutor(int ignoredThreads) {
        this(MapWorkScheduler.WorkType.SOURCE_DECODE, 12);
    }

    PriorityDecodeExecutor(MapWorkScheduler.WorkType workType, int cost) {
        this.workType = workType == null
                ? MapWorkScheduler.WorkType.SOURCE_DECODE : workType;
        this.cost = Math.max(1, cost);
    }

    Executor dynamic(PrioritySupplier supplier) {
        return command -> {
            int executorPriority = Math.max(FOREGROUND, supplier.priority());
            MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                    executorPriority);
            MapWorkScheduler.cpuExecutor(lane, workType,
                    lane.priorityBase(), cost, () -> true).execute(command);
        };
    }

    int queuedTasks() {
        return MapWorkScheduler.snapshot().cpuQueued();
    }

    @FunctionalInterface
    interface PrioritySupplier {
        int priority();
    }
}
