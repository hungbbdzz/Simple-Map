package com.velorise.simplemap.client;

import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Global control plane for expensive map work.
 *
 * <p>Older revisions let every subsystem own a private executor. That allowed
 * decode, projection, exact styling, overview generation and cache maintenance to
 * oversubscribe the CPU independently. V16.2 keeps one priority CPU domain, one
 * priority IO domain and one tiny delay timer. Tasks still retain subsystem-local
 * state, but admission and execution order are decided globally.</p>
 */
public final class MapWorkScheduler {
    private static final int PROCESSORS = Math.max(2,
            Runtime.getRuntime().availableProcessors());
    private static final int CPU_THREADS = Math.max(2,
            Math.min(4, Math.max(2, PROCESSORS / 3)));
    private static final int IO_THREADS = 2;

    /** Cost units are deliberately coarse; they represent retained snapshots and
     * expected CPU/IO occupancy, not milliseconds. */
    private static final long CPU_SOFT_COST = 640L;
    private static final long CPU_HARD_COST = 960L;
    private static final long IO_SOFT_COST = 320L;
    private static final long IO_HARD_COST = 512L;

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicLong CPU_QUEUED_COST = new AtomicLong();
    private static final AtomicLong IO_QUEUED_COST = new AtomicLong();
    /** Running work remains part of pressure/admission accounting until completion. */
    private static final AtomicLong CPU_ACTIVE_COST = new AtomicLong();
    private static final AtomicLong IO_ACTIVE_COST = new AtomicLong();
    private static final EnumMap<WorkType, AtomicLong> RUNTIME_EWMA_NANOS =
            new EnumMap<>(WorkType.class);
    /** At least one CPU worker remains available for minimap/fullscreen work. */
    private static final Semaphore WEAK_CPU_PERMITS =
            new Semaphore(Math.max(1, CPU_THREADS - 1));
    /** Keep one IO worker available for visible reads. Background cave saves,
     * compaction and cache maintenance share the remaining capacity. */
    private static final Semaphore WEAK_IO_PERMITS =
            new Semaphore(Math.max(1, IO_THREADS - 1));
    private static final EnumMap<MapRequestLane, AtomicLong> VIEWPORT_EPOCHS =
            new EnumMap<>(MapRequestLane.class);

    private static final ThreadPoolExecutor CPU = createPool(
            CPU_THREADS, "SimpleMap-CPU");
    private static final ThreadPoolExecutor IO = createPool(
            IO_THREADS, "SimpleMap-IO");
    private static final ScheduledThreadPoolExecutor DELAYER =
            new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-Delay");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                        Thread.NORM_PRIORITY - 2));
                return thread;
            });

    static {
        for (MapRequestLane lane : MapRequestLane.values()) {
            VIEWPORT_EPOCHS.put(lane, new AtomicLong(1L));
        }
        for (WorkType type : WorkType.values()) {
            RUNTIME_EWMA_NANOS.put(type, new AtomicLong(500_000L));
        }
        CPU.allowCoreThreadTimeOut(false);
        IO.allowCoreThreadTimeOut(false);
        DELAYER.setRemoveOnCancelPolicy(true);
        DELAYER.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    private MapWorkScheduler() {
    }

    private static ThreadPoolExecutor createPool(int threads, String name) {
        return new ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                            Thread.NORM_PRIORITY - 1));
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Work type controls global ordering after the viewport lane. */
    public enum WorkType {
        MINIMAP_EXACT(90, true),
        EXACT_BUILD(80, true),
        SOURCE_DECODE(70, false),
        SOURCE_PROJECTION(65, false),
        BRANCH_DERIVE(45, false),
        DISK_READ(40, false),
        LEGACY_BUILD(25, true),
        DISK_WRITE(15, false),
        CACHE_MAINTENANCE(5, false);

        private final int rank;
        private final boolean viewportScoped;

        WorkType(int rank, boolean viewportScoped) {
            this.rank = rank;
            this.viewportScoped = viewportScoped;
        }

        int rank() {
            return rank;
        }

        boolean viewportScoped() {
            return viewportScoped;
        }
    }

    public static long bumpViewport(MapRequestLane lane) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        long epoch = VIEWPORT_EPOCHS.get(effective).incrementAndGet();
        /*
         * A priority queue does not remove invalid tasks by itself. Repeated pan,
         * zoom and layer changes could therefore retain stale pixel snapshots until
         * workers eventually reached them. That is bounded by cost, so it is not a
         * permanent leak, but it behaves like one and can delay centre-page work.
         * Purge stale viewport tasks immediately when the epoch changes.
         */
        purgeStaleViewportTasks(CPU, effective, epoch);
        purgeStaleViewportTasks(IO, effective, epoch);
        return epoch;
    }

    private static void purgeStaleViewportTasks(ThreadPoolExecutor pool,
            MapRequestLane lane, long currentEpoch) {
        for (Runnable queued : pool.getQueue().toArray(new Runnable[0])) {
            if (!(queued instanceof PrioritizedTask task)
                    || !task.isStaleViewportTask(lane, currentEpoch)) continue;
            if (pool.getQueue().remove(task)) task.releaseQueuedCost();
        }
    }

    public static long viewportEpoch(MapRequestLane lane) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        return VIEWPORT_EPOCHS.get(effective).get();
    }

    public static boolean isViewportCurrent(MapRequestLane lane, long epoch) {
        return viewportEpoch(lane) == epoch;
    }

    public static MapRequestLane laneForExecutorPriority(int priority) {
        for (MapRequestLane lane : MapRequestLane.values()) {
            if (lane.executorPriority() == priority) return lane;
        }
        if (priority <= MapRequestLane.MINIMAP.executorPriority()) {
            return MapRequestLane.MINIMAP;
        }
        if (priority >= MapRequestLane.PREFETCH.executorPriority()) {
            return MapRequestLane.PREFETCH;
        }
        return MapRequestLane.FULLSCREEN;
    }

    public static boolean tryCpu(MapRequestLane lane, WorkType type,
            int priority, int cost, BooleanSupplier valid, Runnable runnable) {
        return submit(CPU, CPU_QUEUED_COST, CPU_ACTIVE_COST,
                CPU_SOFT_COST, CPU_HARD_COST,
                lane, type, priority, cost, valid, runnable, false, true);
    }

    public static boolean tryIo(MapRequestLane lane, WorkType type,
            int priority, int cost, BooleanSupplier valid, Runnable runnable) {
        return submit(IO, IO_QUEUED_COST, IO_ACTIVE_COST,
                IO_SOFT_COST, IO_HARD_COST,
                lane, type, priority, cost, valid, runnable, false, false);
    }


    /**
     * Submit a CompletableFuture-style IO operation without exposing executor
     * rejection to the caller. A null result means the bounded IO domain is
     * currently saturated; the subsystem must retain its dirty/requested state
     * and retry later. Never run the supplier inline on the render thread.
     */
    public static <T> CompletableFuture<T> tryIoFuture(MapRequestLane lane,
            WorkType type, int priority, int cost, BooleanSupplier valid,
            Supplier<T> supplier) {
        if (supplier == null) return null;
        CompletableFuture<T> future = new CompletableFuture<>();
        BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
        boolean accepted = submit(IO, IO_QUEUED_COST, IO_ACTIVE_COST,
                IO_SOFT_COST, IO_HARD_COST,
                lane, type, priority, cost, () -> true, () -> {
                    try {
                        if (!effectiveValid.getAsBoolean()) {
                            future.cancel(false);
                            return;
                        }
                        future.complete(supplier.get());
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                }, true, false);
        return accepted ? future : null;
    }

    /** Same non-throwing admission contract for CPU work. */
    public static <T> CompletableFuture<T> tryCpuFuture(MapRequestLane lane,
            WorkType type, int priority, int cost, BooleanSupplier valid,
            Supplier<T> supplier) {
        if (supplier == null) return null;
        CompletableFuture<T> future = new CompletableFuture<>();
        BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
        boolean accepted = submit(CPU, CPU_QUEUED_COST, CPU_ACTIVE_COST,
                CPU_SOFT_COST, CPU_HARD_COST,
                lane, type, priority, cost, () -> true, () -> {
                    try {
                        if (!effectiveValid.getAsBoolean()) {
                            future.cancel(false);
                            return;
                        }
                        future.complete(supplier.get());
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                }, true, true);
        return accepted ? future : null;
    }

    /** Compatibility adapter for CompletableFuture APIs. Prefer tryCpuFuture. */
    @Deprecated
    public static Executor cpuExecutor(MapRequestLane lane, WorkType type,
            int priority, int cost, BooleanSupplier valid) {
        return command -> {
            BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
            if (tryCpu(lane, type, priority, cost, effectiveValid, command)) return;
            scheduleCpuAttempt(10L, lane, type, priority, cost,
                    effectiveValid, command, 0);
        };
    }

    /**
     * Compatibility adapter for APIs that accept only Executor. IO saturation is
     * never propagated to Minecraft's render/client tick. The command is moved to
     * the bounded retry path instead. New code should prefer tryIoFuture/tryIo so
     * it can explicitly retain dirty state when admission is denied.
     */
    @Deprecated
    public static Executor ioExecutor(MapRequestLane lane, WorkType type,
            int priority, int cost, BooleanSupplier valid) {
        return command -> {
            BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
            if (tryIo(lane, type, priority, cost, effectiveValid, command)) return;
            scheduleIoAttempt(10L, TimeUnit.MILLISECONDS, lane, type,
                    priority, cost, effectiveValid, command, 0);
        };
    }

    private static void scheduleCpuAttempt(long delayMs,
            MapRequestLane lane, WorkType type, int priority, int cost,
            BooleanSupplier valid, Runnable runnable, int attempt) {
        DELAYER.schedule(() -> {
            BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
            if (!effectiveValid.getAsBoolean()) {
                // Executor compatibility tasks normally use an always-valid token.
                // Do not execute stale projection work merely to satisfy a future.
                return;
            }
            if (tryCpu(lane, type, priority, cost, effectiveValid, runnable)) return;
            if (attempt < 64) {
                long retryDelayMs = Math.min(250L,
                        5L << Math.min(6, attempt));
                scheduleCpuAttempt(retryDelayMs, lane, type, priority, cost,
                        effectiveValid, runnable, attempt + 1);
            }
        }, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    public static void scheduleIo(long delay, TimeUnit unit,
            MapRequestLane lane, WorkType type, int priority, int cost,
            BooleanSupplier valid, Runnable runnable) {
        scheduleIoAttempt(Math.max(0L, delay),
                unit == null ? TimeUnit.MILLISECONDS : unit,
                lane, type, priority, cost, valid, runnable, 0);
    }

    private static void scheduleIoAttempt(long delay, TimeUnit unit,
            MapRequestLane lane, WorkType type, int priority, int cost,
            BooleanSupplier valid, Runnable runnable, int attempt) {
        DELAYER.schedule(() -> {
            BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
            if (!effectiveValid.getAsBoolean()) return;
            if (tryIo(lane, type, priority, cost, effectiveValid, runnable)) return;

            // Debounced saves and compaction jobs often mark themselves as
            // scheduled before reaching this control plane. Silently dropping a
            // rejected delayed submission can therefore leave that subsystem
            // permanently stuck in the scheduled state. Retry with bounded
            // exponential backoff instead; foreground IO will naturally outrank
            // these weak maintenance retries in the global priority queue.
            long retryDelayMs = Math.min(500L, 10L << Math.min(6, attempt));
            // Pull-driven IO requests represent retained dirty/source state. Do
            // not silently abandon them after an arbitrary retry count; validity
            // or successful admission is the terminal condition.
            scheduleIoAttempt(retryDelayMs, TimeUnit.MILLISECONDS,
                    lane, type, priority, cost, effectiveValid, runnable,
                    Math.min(64, attempt + 1));
        }, delay, unit);
    }

    public static Snapshot snapshot() {
        return new Snapshot(CPU.getActiveCount(), CPU.getQueue().size(),
                CPU_QUEUED_COST.get(), CPU_ACTIVE_COST.get(),
                IO.getActiveCount(), IO.getQueue().size(),
                IO_QUEUED_COST.get(), IO_ACTIVE_COST.get());
    }

    /** Cheap preflight used before allocating a large immutable source snapshot. */
    public static boolean canAdmitCpu(MapRequestLane lane, int requestedCost) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        long total = CPU_QUEUED_COST.get() + CPU_ACTIVE_COST.get()
                + Math.max(1, requestedCost);
        boolean weak = effective == MapRequestLane.BACKGROUND
                || effective == MapRequestLane.PREFETCH;
        return total <= CPU_HARD_COST && (!weak || total <= CPU_SOFT_COST);
    }

    public static boolean canAdmitIo(MapRequestLane lane, int requestedCost) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        long total = IO_QUEUED_COST.get() + IO_ACTIVE_COST.get()
                + Math.max(1, requestedCost);
        boolean weak = effective == MapRequestLane.BACKGROUND
                || effective == MapRequestLane.PREFETCH;
        return total <= IO_HARD_COST && (!weak || total <= IO_SOFT_COST);
    }

    public static long predictedRuntimeNanos(WorkType type) {
        WorkType effective = type == null ? WorkType.CACHE_MAINTENANCE : type;
        return RUNTIME_EWMA_NANOS.get(effective).get();
    }

    private static boolean submit(ThreadPoolExecutor pool, AtomicLong queuedCost,
            AtomicLong activeCost, long softCost, long hardCost,
            MapRequestLane lane, WorkType type,
            int priority, int requestedCost, BooleanSupplier valid, Runnable runnable,
            boolean mustRun, boolean cpuDomain) {
        if (runnable == null) return false;
        MapRequestLane effectiveLane = lane == null ? MapRequestLane.FULLSCREEN : lane;
        WorkType effectiveType = type == null ? WorkType.CACHE_MAINTENANCE : type;
        BooleanSupplier effectiveValid = valid == null ? () -> true : valid;
        if (!effectiveValid.getAsBoolean()) return false;

        int cost = Math.max(1, requestedCost);
        long after = queuedCost.addAndGet(cost) + activeCost.get();
        boolean weakLane = effectiveLane == MapRequestLane.BACKGROUND
                || effectiveLane == MapRequestLane.PREFETCH;
        if (after > hardCost || (weakLane && after > softCost)) {
            queuedCost.addAndGet(-cost);
            return false;
        }

        long viewportEpoch = effectiveType.viewportScoped()
                ? viewportEpoch(effectiveLane) : Long.MIN_VALUE;
        PrioritizedTask task = new PrioritizedTask(runnable, effectiveValid,
                effectiveLane, effectiveType, priority,
                SEQUENCE.getAndIncrement(), cost, queuedCost, activeCost,
                viewportEpoch, mustRun, cpuDomain, pool);
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            queuedCost.addAndGet(-cost);
            return false;
        }
    }

    public record Snapshot(int cpuActive, int cpuQueued, long cpuQueuedCost,
            long cpuActiveCost, int ioActive, int ioQueued, long ioQueuedCost,
            long ioActiveCost) {
        public long cpuTotalCost() {
            return cpuQueuedCost + cpuActiveCost;
        }

        public long ioTotalCost() {
            return ioQueuedCost + ioActiveCost;
        }
    }

    private static void recordRuntime(WorkType type, long nanos) {
        if (type == null || nanos <= 0L) return;
        long sample = Math.max(20_000L, Math.min(250_000_000L, nanos));
        AtomicLong ewma = RUNTIME_EWMA_NANOS.get(type);
        long previous;
        long next;
        do {
            previous = ewma.get();
            next = previous + ((sample - previous) >> 3);
        } while (!ewma.compareAndSet(previous, Math.max(20_000L, next)));
    }

    private static final class PrioritizedTask
            implements Runnable, Comparable<PrioritizedTask> {
        private final Runnable command;
        private final BooleanSupplier valid;
        private final MapRequestLane lane;
        private final WorkType type;
        private final int priority;
        private final long sequence;
        private final int cost;
        private final AtomicLong queuedCost;
        private final AtomicLong activeCost;
        private final AtomicBoolean queuedCostHeld = new AtomicBoolean(true);
        private final long viewportEpoch;
        private final boolean mustRun;
        private final boolean cpuDomain;
        private final ThreadPoolExecutor owner;

        private PrioritizedTask(Runnable command, BooleanSupplier valid,
                MapRequestLane lane, WorkType type, int priority, long sequence,
                int cost, AtomicLong queuedCost, AtomicLong activeCost,
                long viewportEpoch, boolean mustRun, boolean cpuDomain,
                ThreadPoolExecutor owner) {
            this.command = command;
            this.valid = valid;
            this.lane = lane;
            this.type = type;
            this.priority = priority;
            this.sequence = sequence;
            this.cost = cost;
            this.queuedCost = queuedCost;
            this.activeCost = activeCost;
            this.viewportEpoch = viewportEpoch;
            this.mustRun = mustRun;
            this.cpuDomain = cpuDomain;
            this.owner = owner;
        }

        @Override
        public void run() {
            boolean weakPermit = false;
            boolean weakLane = lane == MapRequestLane.BACKGROUND
                    || lane == MapRequestLane.PREFETCH;
            Semaphore permitDomain = cpuDomain ? WEAK_CPU_PERMITS : WEAK_IO_PERMITS;
            if (weakLane) {
                weakPermit = permitDomain.tryAcquire();
                if (!weakPermit) {
                    DELAYER.schedule(() -> {
                        try {
                            owner.execute(this);
                        } catch (RejectedExecutionException rejected) {
                            releaseQueuedCost();
                        }
                    }, 2L, TimeUnit.MILLISECONDS);
                    return;
                }
            }
            releaseQueuedCost();
            activeCost.addAndGet(cost);
            long startedNanos = System.nanoTime();
            try {
                if (!mustRun) {
                    try {
                        if (!valid.getAsBoolean()) return;
                    } catch (Throwable invalid) {
                        return;
                    }
                    if (type.viewportScoped()
                            && !MapWorkScheduler.isViewportCurrent(lane, viewportEpoch)) return;
                }
                // CompletableFuture executors must invoke their command so the future
                // reaches a terminal state. Their subsystem token remains the final
                // cancellation authority after queue admission.
                command.run();
            } catch (Throwable ignoredFailure) {
                // Subsystems own logging/result propagation. The shared worker must
                // remain alive even if a maintenance task fails unexpectedly.
            } finally {
                activeCost.addAndGet(-cost);
                recordRuntime(type, System.nanoTime() - startedNanos);
                if (weakPermit) permitDomain.release();
            }
        }

        private boolean isStaleViewportTask(MapRequestLane targetLane,
                long currentEpoch) {
            return type.viewportScoped() && lane == targetLane
                    && viewportEpoch != currentEpoch;
        }

        private void releaseQueuedCost() {
            if (queuedCostHeld.compareAndSet(true, false)) {
                queuedCost.addAndGet(-cost);
            }
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            int byLane = Integer.compare(other.lane.rank(), lane.rank());
            if (byLane != 0) return byLane;
            int byType = Integer.compare(other.type.rank(), type.rank());
            if (byType != 0) return byType;
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
