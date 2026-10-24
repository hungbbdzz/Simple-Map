package com.velorise.simplemap.client;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;

import com.velorise.simplemap.client.cave.CaveTextureManager.CaveReliefColorizer;
import com.velorise.simplemap.client.color.SurfaceColorizer.BlockTintPolicy;
import com.velorise.simplemap.client.color.*;
import com.velorise.simplemap.client.color.SurfaceColorizer.MapColorProfile;
import net.minecraft.world.level.biome.Biome;
import com.velorise.simplemap.client.RegionSurfaceLodService.RegionLodGraph.PreparedBranch;
import com.velorise.simplemap.client.RegionSurfaceLodService.RegionLodGraph.RegionLodDeriver;
import com.velorise.simplemap.client.RegionSurfaceLodService.RegionLodGraph;
import java.util.function.IntFunction;

import java.util.Arrays;
import java.util.HashMap;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy.MapMemoryLeaseManager;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph;

import com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand;
import com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand.CaveView;
import com.velorise.simplemap.client.session.MapSessionManager.MapSession;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.MapWorkKey;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.MapWorkStage;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RegionRecord;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
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
    // Scale beyond the old four-worker ceiling, but never consume the whole CPU.
    // Minecraft render, integrated-server and chunk-meshing threads keep at least
    // two logical processors and map CPU concurrency is capped at eight.
    private static final int CPU_THREADS = Math.max(2,
            Math.min(8, Math.max(2, PROCESSORS - 2)));
    private static final int IO_THREADS = Math.max(2,
            Math.min(4, Math.max(2, PROCESSORS / 4)));
    private static final MapRequestLane[] REQUEST_LANES = MapRequestLane.values();

    /** Cost units are deliberately coarse; they represent retained snapshots and
     * expected CPU/IO occupancy, not milliseconds. */
    private static final long CPU_SOFT_COST = 1_280L;
    private static final long CPU_HARD_COST = 1_920L;
    private static final long IO_SOFT_COST = 640L;
    private static final long IO_HARD_COST = 1_024L;

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
    private static final EnumMap<MapRequestLane, AtomicLong> COMPLETED_BY_LANE =
            new EnumMap<>(MapRequestLane.class);
    private static final EnumMap<MapRequestLane, AtomicLong> DENIED_BY_LANE =
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
        for (MapRequestLane lane : REQUEST_LANES) {
            VIEWPORT_EPOCHS.put(lane, new AtomicLong(1L));
            COMPLETED_BY_LANE.put(lane, new AtomicLong());
            DENIED_BY_LANE.put(lane, new AtomicLong());
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
                new FairTaskQueue(), runnable -> {
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
        REGION_PROJECTION(88, true),
        /*
         * Source decode/fanout is an upstream dependency for exact/branch work.
         * PASS138 could keep all eight CPU workers busy refining already-ready
         * branches while visible Anvil source waited behind them. The current log
         * shows SOURCE_QUEUE max >1 s and source fanout bursts coincident with
         * stalls. Promote bounded source work above refinement; PASS139 separately
         * caps source concurrency so this cannot monopolize the worker pool.
         */
        SOURCE_DECODE(87, false),
        SOURCE_PROJECTION(86, false),
        BRANCH_DERIVE(84, true),
        EXACT_BUILD(80, true),
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
        // PriorityBlockingQueue's weakly-consistent iterator is sufficient here:
        // tasks added after the epoch bump already capture the new epoch, while any
        // stale task missed by a concurrent iterator still self-cancels before run.
        // Avoid materialising an Object[] on every page-boundary/layer handoff.
        var iterator = pool.getQueue().iterator();
        while (iterator.hasNext()) {
            Runnable queued = iterator.next();
            if (!(queued instanceof PrioritizedTask task)
                    || !task.isStaleViewportTask(lane, currentEpoch)) continue;
            iterator.remove();
            task.cancelStaleBeforeRun();
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
        for (MapRequestLane lane : REQUEST_LANES) {
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
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long submittedViewportEpoch = type != null && type.viewportScoped()
                ? viewportEpoch(effectiveLane) : Long.MIN_VALUE;
        BooleanSupplier suppliedValid = valid == null ? () -> true : valid;
        BooleanSupplier effectiveValid = () -> suppliedValid.getAsBoolean()
                && (submittedViewportEpoch == Long.MIN_VALUE
                || isViewportCurrent(effectiveLane, submittedViewportEpoch));
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
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long submittedViewportEpoch = type != null && type.viewportScoped()
                ? viewportEpoch(effectiveLane) : Long.MIN_VALUE;
        BooleanSupplier suppliedValid = valid == null ? () -> true : valid;
        BooleanSupplier effectiveValid = () -> suppliedValid.getAsBoolean()
                && (submittedViewportEpoch == Long.MIN_VALUE
                || isViewportCurrent(effectiveLane, submittedViewportEpoch));
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

    /**
     * Schedules one tiny control-plane continuation without creating a private
     * subsystem timer. This is intentionally not work admission: the continuation
     * must still call tryCpu/tryIo and retain its own pending state when saturated.
     */
    public static void scheduleControl(long delayMs, Runnable runnable) {
        if (runnable == null) return;
        DELAYER.schedule(runnable, Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS);
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

    /** Allocation-free pressure probes for hot admission/governor paths. */
    public static long cpuTotalCost() {
        return CPU_QUEUED_COST.get() + CPU_ACTIVE_COST.get();
    }

    public static long ioTotalCost() {
        return IO_QUEUED_COST.get() + IO_ACTIVE_COST.get();
    }

    public static int cpuActiveCount() {
        return CPU.getActiveCount();
    }

    public static int cpuQueuedCount() {
        return CPU.getQueue().size();
    }

    public static int ioQueuedCount() {
        return IO.getQueue().size();
    }

    public static long ioQueuedCost() {
        return IO_QUEUED_COST.get();
    }

    public static Snapshot snapshot() {
        int laneCount = REQUEST_LANES.length;
        int[] cpuQueuedByLane = new int[laneCount];
        int[] ioQueuedByLane = new int[laneCount];
        long[] completedByLane = new long[laneCount];
        long[] deniedByLane = new long[laneCount];
        FairTaskQueue cpuQueue = (FairTaskQueue) CPU.getQueue();
        FairTaskQueue ioQueue = (FairTaskQueue) IO.getQueue();
        for (MapRequestLane lane : REQUEST_LANES) {
            int index = lane.ordinal();
            cpuQueuedByLane[index] = cpuQueue.queued(lane);
            ioQueuedByLane[index] = ioQueue.queued(lane);
            completedByLane[index] = COMPLETED_BY_LANE.get(lane).get();
            deniedByLane[index] = DENIED_BY_LANE.get(lane).get();
        }
        return new Snapshot(CPU.getActiveCount(), CPU.getQueue().size(),
                CPU_QUEUED_COST.get(), CPU_ACTIVE_COST.get(),
                IO.getActiveCount(), IO.getQueue().size(),
                IO_QUEUED_COST.get(), IO_ACTIVE_COST.get(),
                cpuQueuedByLane, ioQueuedByLane, completedByLane, deniedByLane);
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
        MapMemoryLeaseManager.Category memoryCategory = memoryCategory(effectiveType);
        long estimatedBytes = estimateRetainedBytes(effectiveType, cost);
        MapMemoryLeaseManager.Lease memoryLease = MapMemoryLeaseManager.tryAcquire(
                memoryCategory, estimatedBytes, effectiveLane);
        if (memoryLease == null) {
            DENIED_BY_LANE.get(effectiveLane).incrementAndGet();
            return false;
        }

        long after = queuedCost.addAndGet(cost) + activeCost.get();
        boolean weakLane = effectiveLane == MapRequestLane.BACKGROUND
                || effectiveLane == MapRequestLane.PREFETCH;
        if (after > hardCost || (weakLane && after > softCost)) {
            queuedCost.addAndGet(-cost);
            memoryLease.close();
            DENIED_BY_LANE.get(effectiveLane).incrementAndGet();
            return false;
        }

        long viewportEpoch = effectiveType.viewportScoped()
                ? viewportEpoch(effectiveLane) : Long.MIN_VALUE;
        PrioritizedTask task = new PrioritizedTask(runnable, effectiveValid,
                effectiveLane, effectiveType, priority,
                SEQUENCE.getAndIncrement(), cost, queuedCost, activeCost,
                viewportEpoch, mustRun, cpuDomain, pool, memoryLease);
        try {
            pool.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            task.cancelBeforeRun();
            DENIED_BY_LANE.get(effectiveLane).incrementAndGet();
            return false;
        }
    }

    private static MapMemoryLeaseManager.Category memoryCategory(WorkType type) {
        return switch (type) {
            case SOURCE_DECODE, DISK_READ ->
                    MapMemoryLeaseManager.Category.PENDING_SOURCE;
            case MINIMAP_EXACT, REGION_PROJECTION, EXACT_BUILD, SOURCE_PROJECTION, LEGACY_BUILD ->
                    MapMemoryLeaseManager.Category.PENDING_PROJECTION;
            case BRANCH_DERIVE -> MapMemoryLeaseManager.Category.PENDING_LOD;
            case DISK_WRITE, CACHE_MAINTENANCE ->
                    MapMemoryLeaseManager.Category.IO_BUFFER;
        };
    }

    private static long estimateRetainedBytes(WorkType type, int cost) {
        long unit = switch (type) {
            case MINIMAP_EXACT, REGION_PROJECTION, EXACT_BUILD, SOURCE_PROJECTION -> 96L << 10;
            case SOURCE_DECODE, DISK_READ -> 80L << 10;
            case BRANCH_DERIVE -> 64L << 10;
            case LEGACY_BUILD -> 48L << 10;
            case DISK_WRITE, CACHE_MAINTENANCE -> 64L << 10;
        };
        return Math.max(64L << 10, Math.multiplyExact((long) cost, unit));
    }

    public record Snapshot(int cpuActive, int cpuQueued, long cpuQueuedCost,
            long cpuActiveCost, int ioActive, int ioQueued, long ioQueuedCost,
            long ioActiveCost, int[] cpuQueuedByLane, int[] ioQueuedByLane,
            long[] completedByLane, long[] deniedByLane) {
        public Snapshot {
            cpuQueuedByLane = cpuQueuedByLane.clone();
            ioQueuedByLane = ioQueuedByLane.clone();
            completedByLane = completedByLane.clone();
            deniedByLane = deniedByLane.clone();
        }

        @Override public int[] cpuQueuedByLane() { return cpuQueuedByLane.clone(); }
        @Override public int[] ioQueuedByLane() { return ioQueuedByLane.clone(); }
        @Override public long[] completedByLane() { return completedByLane.clone(); }
        @Override public long[] deniedByLane() { return deniedByLane.clone(); }

        public int cpuQueued(MapRequestLane lane) {
            return cpuQueuedByLane[(lane == null
                    ? MapRequestLane.FULLSCREEN : lane).ordinal()];
        }

        public int ioQueued(MapRequestLane lane) {
            return ioQueuedByLane[(lane == null
                    ? MapRequestLane.FULLSCREEN : lane).ordinal()];
        }

        public long completed(MapRequestLane lane) {
            return completedByLane[(lane == null
                    ? MapRequestLane.FULLSCREEN : lane).ordinal()];
        }

        public long denied(MapRequestLane lane) {
            return deniedByLane[(lane == null
                    ? MapRequestLane.FULLSCREEN : lane).ordinal()];
        }

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

    /**
     * Weighted deficit-round-robin queue. Strict priority is preserved inside a
     * lane, while continuously arriving minimap work can no longer prevent
     * fullscreen/background/prefetch work from ever reaching a worker.
     */
    private static final class FairTaskQueue extends AbstractQueue<Runnable>
            implements BlockingQueue<Runnable> {
        private static final MapRequestLane[] LANES = MapRequestLane.values();
        private static final long MAX_DEFICIT = 4_096L;

        private final EnumMap<MapRequestLane, PriorityQueue<PrioritizedTask>> queues =
                new EnumMap<>(MapRequestLane.class);
        private final long[] deficits = new long[LANES.length];
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private int cursor;
        private int size;

        private FairTaskQueue() {
            for (MapRequestLane lane : LANES) {
                queues.put(lane, new PriorityQueue<>());
            }
        }

        @Override
        public boolean offer(Runnable runnable) {
            if (!(runnable instanceof PrioritizedTask task)) {
                throw new IllegalArgumentException(
                        "Map scheduler queue accepts PrioritizedTask only");
            }
            lock.lock();
            try {
                queues.get(task.lane).offer(task);
                size++;
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void put(Runnable runnable) {
            offer(runnable);
        }

        @Override
        public boolean offer(Runnable runnable, long timeout, TimeUnit unit) {
            return offer(runnable);
        }

        @Override
        public Runnable poll() {
            lock.lock();
            try {
                return pollFairLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable take() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (size == 0) notEmpty.await();
                return pollFairLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable poll(long timeout, TimeUnit unit)
                throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            lock.lockInterruptibly();
            try {
                while (size == 0) {
                    if (nanos <= 0L) return null;
                    nanos = notEmpty.awaitNanos(nanos);
                }
                return pollFairLocked();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public Runnable peek() {
            lock.lock();
            try {
                if (size == 0) return null;
                PrioritizedTask best = null;
                for (MapRequestLane lane : LANES) {
                    PrioritizedTask candidate = queues.get(lane).peek();
                    if (candidate != null
                            && (best == null || candidate.compareTo(best) < 0)) {
                        best = candidate;
                    }
                }
                return best;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int size() {
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean remove(Object value) {
            lock.lock();
            try {
                if (!(value instanceof PrioritizedTask task)) return false;
                boolean removed = queues.get(task.lane).remove(task);
                if (removed) size--;
                return removed;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean contains(Object value) {
            lock.lock();
            try {
                if (!(value instanceof PrioritizedTask task)) return false;
                return queues.get(task.lane).contains(task);
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void clear() {
            List<PrioritizedTask> cancelled = new ArrayList<>();
            lock.lock();
            try {
                for (PriorityQueue<PrioritizedTask> queue : queues.values()) {
                    cancelled.addAll(queue);
                    queue.clear();
                }
                java.util.Arrays.fill(deficits, 0L);
                size = 0;
            } finally {
                lock.unlock();
            }
            // ThreadPoolExecutor treats queue.clear() as removal, not task
            // cancellation. Release durable scheduler and memory accounting here.
            for (PrioritizedTask task : cancelled) task.cancelBeforeRun();
        }

        @Override
        public Iterator<Runnable> iterator() {
            return snapshotLocked().iterator();
        }

        @Override
        public Object[] toArray() {
            return snapshotLocked().toArray();
        }

        @Override
        public <T> T[] toArray(T[] target) {
            return snapshotLocked().toArray(target);
        }

        @Override
        public int drainTo(Collection<? super Runnable> destination) {
            return drainTo(destination, Integer.MAX_VALUE);
        }

        @Override
        public int drainTo(Collection<? super Runnable> destination, int maxElements) {
            if (destination == null || destination == this) {
                throw new IllegalArgumentException("Invalid drain destination");
            }
            lock.lock();
            try {
                int drained = 0;
                while (drained < maxElements && size > 0) {
                    Runnable task = pollFairLocked();
                    if (task == null) break;
                    destination.add(task);
                    drained++;
                }
                return drained;
            } finally {
                lock.unlock();
            }
        }

        int queued(MapRequestLane lane) {
            lock.lock();
            try {
                PriorityQueue<PrioritizedTask> queue = queues.get(lane);
                return queue == null ? 0 : queue.size();
            } finally {
                lock.unlock();
            }
        }

        private PrioritizedTask pollFairLocked() {
            if (size == 0) return null;
            int attempts = 0;
            int maximumAttempts = LANES.length * 256;
            while (attempts++ < maximumAttempts) {
                MapRequestLane lane = LANES[cursor];
                int laneIndex = cursor;
                PriorityQueue<PrioritizedTask> queue = queues.get(lane);
                PrioritizedTask task = queue.peek();
                if (task == null) {
                    deficits[laneIndex] = 0L;
                    cursor = (cursor + 1) % LANES.length;
                    continue;
                }

                long charge = Math.max(1L, task.cost);
                if (deficits[laneIndex] < charge) {
                    deficits[laneIndex] = Math.min(MAX_DEFICIT,
                            deficits[laneIndex] + quantum(lane));
                }
                if (charge > deficits[laneIndex]) {
                    cursor = (cursor + 1) % LANES.length;
                    continue;
                }

                queue.poll();
                size--;
                deficits[laneIndex] -= charge;
                PrioritizedTask next = queue.peek();
                if (next == null
                        || Math.max(1L, next.cost) > deficits[laneIndex]) {
                    cursor = (cursor + 1) % LANES.length;
                }
                return task;
            }

            // A task with an unexpectedly high declared cost must still make
            // progress. Choose the oldest head and reset only its lane deficit.
            PrioritizedTask oldest = null;
            MapRequestLane oldestLane = null;
            for (MapRequestLane lane : LANES) {
                PrioritizedTask candidate = queues.get(lane).peek();
                if (candidate != null
                        && (oldest == null || candidate.sequence < oldest.sequence)) {
                    oldest = candidate;
                    oldestLane = lane;
                }
            }
            if (oldest == null) return null;
            queues.get(oldestLane).poll();
            size--;
            deficits[oldestLane.ordinal()] = 0L;
            return oldest;
        }

        private List<Runnable> snapshotLocked() {
            lock.lock();
            try {
                List<Runnable> snapshot = new ArrayList<>(size);
                for (MapRequestLane lane : LANES) {
                    snapshot.addAll(queues.get(lane));
                }
                return snapshot;
            } finally {
                lock.unlock();
            }
        }

        private static int quantum(MapRequestLane lane) {
            return switch (lane) {
                case MINIMAP -> 128;
                case FULLSCREEN -> 96;
                case BACKGROUND -> 32;
                case PREFETCH -> 16;
            };
        }
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
        private final MapMemoryLeaseManager.Lease memoryLease;
        private final AtomicBoolean memoryLeaseHeld = new AtomicBoolean(true);

        private PrioritizedTask(Runnable command, BooleanSupplier valid,
                MapRequestLane lane, WorkType type, int priority, long sequence,
                int cost, AtomicLong queuedCost, AtomicLong activeCost,
                long viewportEpoch, boolean mustRun, boolean cpuDomain,
                ThreadPoolExecutor owner, MapMemoryLeaseManager.Lease memoryLease) {
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
            this.memoryLease = memoryLease;
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
                            cancelBeforeRun();
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
                COMPLETED_BY_LANE.get(lane).incrementAndGet();
                recordRuntime(type, System.nanoTime() - startedNanos);
                releaseMemoryLease();
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

        private void releaseMemoryLease() {
            if (memoryLeaseHeld.compareAndSet(true, false) && memoryLease != null) {
                memoryLease.close();
            }
        }

        private void cancelBeforeRun() {
            releaseQueuedCost();
            releaseMemoryLease();
        }

        /**
         * A purged CompletableFuture task still needs a terminal signal so its
         * subsystem releases retained source views and pending-batch ownership.
         * The future wrapper includes the submitted viewport epoch in its validity
         * check, therefore invoking this tiny command cannot run the stale supplier.
         */
        private void cancelStaleBeforeRun() {
            releaseQueuedCost();
            releaseMemoryLease();
            if (!mustRun) return;
            try {
                command.run();
            } catch (Throwable ignoredFailure) {
                // Future wrappers own result propagation.
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

    /**
     * Single admission point for map observation work.
     *
     * <p>Low-level scanners and texture managers keep their specialized queues, but
     * only this scheduler decides which lane may receive work during a client tick.
     * This prevents viewport, mutation, cave-band warmup and archive paths from all
     * independently spending their maximum budgets in the same frame.</p>
     */
    public static final class MapObservationScheduler {
        public enum Lane {
            LIVE_CRITICAL,
            MUTATION_REPAIR,
            LIVE_VISIBLE,
            SAVED_VISIBLE,
            LAYER_WARMUP,
            ARCHIVE_BACKGROUND,
            PUBLICATION
        }

        public record LaneSnapshot(long runs, long units,
                long totalNanos, long lastNanos) {
        }

        public record Snapshot(Map<Lane, LaneSnapshot> lanes,
                int mutationColumns, int mutationChunks, int mutationRegions,
                int generatedChunks, boolean pressure, int decodedRegions,
                int decodedChunks, long decodedBytes, long decodedTargetBytes,
                int decodeQueue, double heapPressure, int workGraphRegions,
                int workGraphDirty, int workGraphRunning, int workGraphReady) {
        }

        private static final class MutableLane {
            private long runs;
            private long units;
            private long totalNanos;
            private long lastNanos;
        }

        private static final MapObservationScheduler INSTANCE = new MapObservationScheduler();

        private Level observedLevel;
        private long lastMovementEpoch = Long.MIN_VALUE;
        private long lastTeleportEpoch = Long.MIN_VALUE;

        private final EnumMap<Lane, MutableLane> lanes = new EnumMap<>(Lane.class);
        private volatile int mutationColumns;
        private volatile int mutationChunks;
        private volatile int mutationRegions;
        private volatile int generatedChunks;
        private volatile boolean pressure;
        private volatile int decodedRegions;
        private volatile int decodedChunks;
        private volatile long decodedBytes;
        private volatile long decodedTargetBytes;
        private volatile int decodeQueue;
        private volatile double heapPressure;
        private volatile int workGraphRegions;
        private volatile int workGraphDirty;
        private volatile int workGraphRunning;
        private volatile int workGraphReady;

        private MapObservationScheduler() {
            for (Lane lane : Lane.values()) lanes.put(lane, new MutableLane());
        }

        public static MapObservationScheduler getInstance() {
            return INSTANCE;
        }

        public synchronized void record(Lane lane, long elapsedNanos, int units) {
            MutableLane value = lanes.get(lane);
            value.runs++;
            value.units += Math.max(0, units);
            value.totalNanos += Math.max(0L, elapsedNanos);
            value.lastNanos = Math.max(0L, elapsedNanos);
        }

        public void updateQueues(int mutationColumns, int mutationChunks, int mutationRegions,
                int generatedChunks, boolean pressure) {
            this.mutationColumns = mutationColumns;
            this.mutationChunks = mutationChunks;
            this.mutationRegions = mutationRegions;
            this.generatedChunks = generatedChunks;
            this.pressure = pressure;
        }

        public void updateDecodedSource(int regions, int chunks, long bytes,
                long targetBytes, int queue, double heapPressure) {
            this.decodedRegions = regions;
            this.decodedChunks = chunks;
            this.decodedBytes = bytes;
            this.decodedTargetBytes = targetBytes;
            this.decodeQueue = queue;
            this.heapPressure = heapPressure;
        }

        public void updateWorkGraph(int regions, int dirty, int running, int ready) {
            this.workGraphRegions = regions;
            this.workGraphDirty = dirty;
            this.workGraphRunning = running;
            this.workGraphReady = ready;
        }

        public synchronized Snapshot snapshot() {
            EnumMap<Lane, LaneSnapshot> copy = new EnumMap<>(Lane.class);
            for (Map.Entry<Lane, MutableLane> entry : lanes.entrySet()) {
                MutableLane lane = entry.getValue();
                copy.put(entry.getKey(), new LaneSnapshot(
                        lane.runs, lane.units, lane.totalNanos, lane.lastNanos));
            }
            return new Snapshot(Map.copyOf(copy), mutationColumns, mutationChunks, mutationRegions,
                    generatedChunks, pressure, decodedRegions, decodedChunks,
                    decodedBytes, decodedTargetBytes, decodeQueue, heapPressure,
                    workGraphRegions, workGraphDirty, workGraphRunning, workGraphReady);
        }

        public Snapshot telemetry() {
            return snapshot();
        }

        public synchronized void reset() {
            observedLevel = null;
            lastMovementEpoch = Long.MIN_VALUE;
            lastTeleportEpoch = Long.MIN_VALUE;
            MapActivityGate.getInstance().reset();
            GeneratedChunkIndex.getInstance().reset();
            MapMutationBus.getInstance().reset();
            MapViewportCoordinator.getInstance().reset();
            for (MutableLane lane : lanes.values()) {
                lane.runs = 0L;
                lane.units = 0L;
                lane.totalNanos = 0L;
                lane.lastNanos = 0L;
            }
            mutationColumns = 0;
            mutationChunks = 0;
            mutationRegions = 0;
            generatedChunks = 0;
            pressure = false;
            decodedRegions = 0;
            decodedChunks = 0;
            decodedBytes = 0L;
            decodedTargetBytes = 0L;
            decodeQueue = 0;
            heapPressure = 0.0D;
            workGraphRegions = 0;
            workGraphDirty = 0;
            workGraphRunning = 0;
            workGraphReady = 0;
        }

        public void tick(Minecraft minecraft, boolean mapUnlocked, boolean mapScreenOpen) {
            if (minecraft == null || minecraft.level == null || minecraft.player == null) {
                reset();
                return;
            }
            if (observedLevel != minecraft.level) {
                observedLevel = minecraft.level;
                GeneratedChunkIndex.getInstance().reset();
                GeneratedChunkIndex.getInstance().observeLevel(minecraft.level);
            }

            MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
            MapPerformanceGovernor.ObservationProfile profile =
                    governor.observationProfile(minecraft);

            MapActivityGate activityGate = MapActivityGate.getInstance();
            long teleportEpoch = activityGate.teleportEpoch();
            if (lastTeleportEpoch != teleportEpoch) {
                lastTeleportEpoch = teleportEpoch;
                if (teleportEpoch > 0L) {
                    ChunkScanner.getInstance().beginTeleportRecovery();
                }
            }
            boolean movementWindow = activityGate.blocksMapWork();
            if (movementWindow) {
                long epoch = activityGate.movementEpoch();
                if (lastMovementEpoch != epoch) {
                    lastMovementEpoch = epoch;
                    MapMutationBus.getInstance().dropQueuedWorkForMovement();
                    MapViewportCoordinator.getInstance().prepareMovementStreaming();
                }
            }

            long started = System.nanoTime();
            if (!movementWindow) {
                MapMutationBus.getInstance().tick(minecraft,
                        profile.mutationColumnBudget(), profile.mutationChunkBudget(),
                        profile.mutationBudgetNanos());
            }
            record(Lane.MUTATION_REPAIR,
                    System.nanoTime() - started,
                    movementWindow ? 0 : profile.mutationColumnBudget());

            MapMutationBus mutationBus = MapMutationBus.getInstance();
            boolean authoritativeBacklog = !movementWindow
                    && mutationBus.hasBacklog(513, 17);
            if (mapUnlocked && !mapScreenOpen) {
                int renderDistance = minecraft.options.renderDistance().get();
                int baseRadius = (int) Math.max(16, (renderDistance - 1.5) * 16);
                int radius = Math.max(16,
                        (int) Math.round(baseRadius * profile.liveRadiusFactor()));
                started = System.nanoTime();
                ChunkScanner.getInstance().scanAroundPlayerUniform(
                        minecraft, radius, authoritativeBacklog);
                record(Lane.LIVE_CRITICAL,
                        System.nanoTime() - started,
                        authoritativeBacklog ? -radius : radius);
            }

            CaveMapManager.getInstance().tickWorkGraphCompletions();

            started = System.nanoTime();
            MapViewportCoordinator.getInstance().tick(minecraft, profile);
            record(Lane.LIVE_VISIBLE,
                    System.nanoTime() - started, profile.allowVisibleScan() ? 1 : 0);

            MapMutationBus.Snapshot mutations = MapMutationBus.getInstance().snapshot();
            int generatedChunks = GeneratedChunkIndex.getInstance().entryCount();
            updateQueues(mutations.pendingColumns(), mutations.pendingChunks(),
                    mutations.pendingRegions(),
                    generatedChunks, governor.underPressure());
            updateDecodedSource(0, 0, 0L, 0L, 0, 0.0);
            MapWorkGraph.Snapshot graph = MapWorkGraph.getInstance().snapshot();
            updateWorkGraph(graph.regions(), graph.dirtyStages(),
                    graph.runningStages(), graph.readyStages());
        }
    }


    public static final class MapProcessor {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapProcessor INSTANCE = new MapProcessor();

    private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<String, Task> queued = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong processorEpoch = new AtomicLong(1L);
    private final AtomicLong surfaceHintEpoch = new AtomicLong(1L);
    private final Set<String> retryScheduled = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService retryExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-MapProcessor-Retry");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                        Thread.NORM_PRIORITY - 2));
                return thread;
            });

    private MapProcessor() {
        // Region admission is intentionally serial. Heavy decode/style work still
        // has its own bounded workers, while request order remains deterministic.
        for (int i = 0; i < 1; i++) {
            Thread worker = new Thread(this::runLoop, "SimpleMap-MapProcessor-" + (i + 1));
            worker.setDaemon(true);
            worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            worker.start();
        }
    }

    public static MapProcessor getInstance() {
        return INSTANCE;
    }

    public void enqueueSurfaceLoad(int regionX, int regionZ, int priority) {
        MapSession session = MapSessionManager.getInstance().active();
        if (session == null || session.rootToken().isCancelled()) return;
        long generation = MapManager.getInstance().getGeneration();
        enqueue(new Task("surface:" + session.sessionId() + ':' + generation + ':' + regionX + ',' + regionZ,
                Kind.SURFACE_LOAD, session.stamp(), generation, Integer.MIN_VALUE,
                regionX, regionZ, priority, sequence.getAndIncrement()));
    }

    public void enqueueCaveLoad(int layerY, int regionX, int regionZ, int priority) {
        if (layerY == Integer.MIN_VALUE) {
            enqueueFullCaveLoad(regionX, regionZ, priority);
            return;
        }
        MapSession session = MapSessionManager.getInstance().active();
        if (session == null || session.rootToken().isCancelled()) return;
        long generation = CaveMapManager.getInstance().getLayerGeneration();
        int bandY = com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand.key(
                com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand.CaveView.LAYERED, layerY);
        enqueue(new Task("cave:" + session.sessionId() + ':' + generation + ':' + bandY + ':' + regionX + ',' + regionZ,
                Kind.CAVE_LOAD, session.stamp(), generation, layerY,
                regionX, regionZ, priority, sequence.getAndIncrement()));
    }

    public void enqueueFullCaveLoad(int regionX, int regionZ, int priority) {
        MapSession session = MapSessionManager.getInstance().active();
        if (session == null || session.rootToken().isCancelled()) return;
        long generation = CaveMapManager.getInstance().getGeneration();
        enqueue(new Task("full:" + session.sessionId() + ':' + generation + ':' + regionX + ',' + regionZ,
                Kind.FULL_CAVE_LOAD, session.stamp(), generation, Integer.MIN_VALUE,
                regionX, regionZ, priority, sequence.getAndIncrement()));
    }

    /* Compatibility aliases retained for existing call sites. */
    public void enqueueEnsureSeedForCave(int layerY, int rx, int rz, int priority) {
        enqueueCaveLoad(layerY, rx, rz, priority);
    }

    public void enqueueEnsureSeedForSurface(int rx, int rz, int priority) {
        enqueueSurfaceLoad(rx, rz, priority);
    }

    public void enqueuePrepareCPU(boolean cave, int layerY, int rx, int rz, int priority) {
        if (cave) enqueueCaveLoad(layerY, rx, rz, priority);
        else enqueueSurfaceLoad(rx, rz, priority);
    }

    public void enqueueUpload(boolean cave, int layerY, int rx, int rz, int priority) {
        if (cave) CaveTextureManager.getInstance().markRegionTextureDirty(layerY, rx, rz);
        else MapTextureManager.getInstance().markRegionDirty(rx, rz);
    }

    public void clear() {
        processorEpoch.incrementAndGet();
        surfaceHintEpoch.incrementAndGet();
        queue.clear();
        queued.clear();
        retryScheduled.clear();
    }

    /** Drops every queued surface disk request after a discontinuous jump. */
    public void clearSurfaceLoads() {
        surfaceHintEpoch.incrementAndGet();
        for (Task task : queued.values()) {
            if (task.kind != Kind.SURFACE_LOAD) continue;
            if (queued.remove(task.key, task)) queue.remove(task);
        }
    }

    /**
     * Retains queued source reads that are still useful to an overlapping panned
     * viewport and removes only regions that have moved completely out of demand.
     */
    public void retainSurfaceLoadsInRegions(int minRegionX, int maxRegionX,
            int minRegionZ, int maxRegionZ) {
        int safeMinX = Math.min(minRegionX, maxRegionX);
        int safeMaxX = Math.max(minRegionX, maxRegionX);
        int safeMinZ = Math.min(minRegionZ, maxRegionZ);
        int safeMaxZ = Math.max(minRegionZ, maxRegionZ);
        surfaceHintEpoch.incrementAndGet();
        for (Task task : queued.values()) {
            if (task.kind != Kind.SURFACE_LOAD) continue;
            if (task.regionX >= safeMinX && task.regionX <= safeMaxX
                    && task.regionZ >= safeMinZ && task.regionZ <= safeMaxZ) continue;
            if (queued.remove(task.key, task)) queue.remove(task);
        }
    }

    private void enqueue(Task task) {
        MapWorkGraph.Admission admission = MapWorkGraph.getInstance()
                .request(task.workKey, task.generation);
        if (admission == MapWorkGraph.Admission.CANCELLED
                || admission == MapWorkGraph.Admission.STALE) return;
        // ACCEPTED and COALESCED both need an execution hint. The semantic state
        // may already be dirty while its previous queue hint was cleared or
        // rejected; treating COALESCED as "nothing to enqueue" would strand it.
        enqueueHint(task);
    }

    private void enqueueHint(Task task) {
        // Queue admission is deliberately lossless. Deduplication keeps one task
        // per logical region, while Region/dirty state remains authoritative. A
        // full queue must never evict a lower-priority known-region request.
        queued.compute(task.key, (key, existing) -> {
            if (existing == null) {
                queue.offer(task);
                return task;
            }
            boolean projectionChanged = task.kind == Kind.CAVE_LOAD
                    && existing.kind == Kind.CAVE_LOAD
                    && task.layerY != existing.layerY;
            if ((projectionChanged || task.priority > existing.priority)
                    && queue.remove(existing)) {
                queue.offer(task);
                return task;
            }
            return existing;
        });
    }

    private void runLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Task task = queue.take();
                if (!queued.remove(task.key, task)) continue;
                process(task);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable throwable) {
                LOGGER.error("Unhandled SimpleMap background task failure", throwable);
            }
        }
    }

    private void process(Task task) {
        if (!MapSessionManager.getInstance().isCurrent(task.sessionId)) {
            MapWorkGraph.getInstance().cancelSession(task.sessionId);
            return;
        }
        RegionRecord.Lease lease = MapWorkGraph.getInstance().tryBegin(task.workKey);
        if (lease == null) {
            scheduleRetryIfNeeded(task);
            return;
        }
        boolean completionOwnedByManager = false;
        try {
            switch (task.kind) {
                case SURFACE_LOAD -> {
                    MapManager manager = MapManager.getInstance();
                    if (manager.isGenerationCurrent(task.generation)) {
                        completionOwnedByManager = manager.requestRegionLoad(task.workKey,
                                task.generation);
                    }
                }
                case CAVE_LOAD -> {
                    CaveMapManager manager = CaveMapManager.getInstance();
                    if (manager.isLayerGenerationCurrent(task.generation, task.layerY)) {
                        int activeTopY = manager.getActiveLayerY();
                        int targetY = task.layerY == Integer.MIN_VALUE
                                ? Integer.MIN_VALUE
                                : (activeTopY != Integer.MIN_VALUE ? activeTopY : task.layerY);
                        completionOwnedByManager = manager.requestVisibleRegion(
                                task.workKey, task.generation, targetY);
                    }
                }
                case FULL_CAVE_LOAD -> {
                    CaveMapManager manager = CaveMapManager.getInstance();
                    if (manager.isGenerationCurrent(task.generation)) {
                        completionOwnedByManager = manager.requestRegionLoad(
                                task.workKey, task.generation);
                    }
                }
            }
        } finally {
            if (completionOwnedByManager) {
                // Manager-owned source/projection work remains RUNNING until its
                // asynchronous archive or disk result has committed.
            } else {
                MapWorkGraph.getInstance().defer(lease);
            }
        }
    }

    private void scheduleRetryIfNeeded(Task task) {
        RegionRecord.Snapshot snapshot = MapWorkGraph.getInstance().snapshot(task.workKey);
        if (snapshot == null || snapshot.cancelled()) return;
        RegionRecord.StageSnapshot stage = snapshot.stages().get(
                new RegionRecord.StageKey(task.workKey.stage(),
                        task.workKey.projectionId()));
        if (stage == null || (stage.state() != RegionRecord.StageState.RUNNING
                && stage.state() != RegionRecord.StageState.DIRTY)) return;
        if (!retryScheduled.add(task.key)) return;
        long expectedProcessorEpoch = processorEpoch.get();
        long expectedSurfaceEpoch = surfaceHintEpoch.get();
        retryExecutor.schedule(() -> {
            retryScheduled.remove(task.key);
            if (processorEpoch.get() != expectedProcessorEpoch) return;
            if (task.kind == Kind.SURFACE_LOAD
                    && surfaceHintEpoch.get() != expectedSurfaceEpoch) return;
            if (!MapSessionManager.getInstance().isCurrent(task.sessionId)) return;
            RegionRecord.Snapshot current = MapWorkGraph.getInstance()
                    .snapshot(task.workKey);
            if (current == null || current.cancelled()) return;
            RegionRecord.StageSnapshot currentStage = current.stages().get(
                    new RegionRecord.StageKey(task.workKey.stage(),
                            task.workKey.projectionId()));
            if (currentStage == null || (currentStage.state()
                    != RegionRecord.StageState.RUNNING
                    && currentStage.state() != RegionRecord.StageState.DIRTY)) return;
            enqueueHint(task.withSequence(sequence.getAndIncrement()));
        }, 25L, TimeUnit.MILLISECONDS);
    }

    private enum Kind {
        SURFACE_LOAD,
        CAVE_LOAD,
        FULL_CAVE_LOAD
    }

    private static final class Task implements Comparable<Task> {
        private final String key;
        private final Kind kind;
        private final long sessionId;
        private final long generation;
        private final int layerY;
        private final int regionX;
        private final int regionZ;
        private final int priority;
        private final long sequence;
        private final MapWorkKey workKey;

        private Task(String key, Kind kind, RevisionStamp stamp, long generation,
                int layerY, int regionX, int regionZ, int priority, long sequence) {
            this.key = Objects.requireNonNull(key);
            this.kind = Objects.requireNonNull(kind);
            RevisionStamp owner = Objects.requireNonNull(stamp, "stamp");
            this.sessionId = owner.sessionId();
            this.generation = generation;
            this.layerY = layerY;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.priority = priority;
            this.sequence = sequence;
            int projectionId = switch (kind) {
                case CAVE_LOAD -> com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand.key(
                        com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand.CaveView.LAYERED, layerY);
                case SURFACE_LOAD, FULL_CAVE_LOAD -> Integer.MIN_VALUE;
            };
            this.workKey = new MapWorkKey(owner, regionX, regionZ,
                    switch (kind) {
                        case SURFACE_LOAD -> MapWorkStage.SOURCE_READ;
                        case CAVE_LOAD -> MapWorkStage.CAVE_PROJECTION;
                        case FULL_CAVE_LOAD -> MapWorkStage.FULL_CAVE_PROJECTION;
                    }, projectionId);
        }

        private Task withSequence(long newSequence) {
            return new Task(key, kind, workKey.stamp(), generation, layerY,
                    regionX, regionZ, priority, newSequence);
        }

        @Override
        public int compareTo(Task other) {
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}



    /**
 * Authoritative region work-state graph. Queue entries are disposable execution
 * hints; all semantic demand, revision and publication state lives here.
 */
public static final class MapWorkGraph {
    public enum Admission { ACCEPTED, COALESCED, CANCELLED, STALE }

    /** Ordered stages owned by a region, not by an executor queue. */
    public enum MapWorkStage {
        SOURCE_CAPTURE,
        SOURCE_READ,
        SOURCE_DECODE,
        SOURCE_COMMIT,
        CAVE_PROJECTION,
        FULL_CAVE_PROJECTION,
        STYLE,
        LOD_DERIVE,
        GPU_PREPARE,
        GPU_UPLOAD,
        CACHE_COMMIT
    }

    /**
     * Immutable ownership stamp carried by asynchronous map work and prepared output.
     * A plain stage revision is not sufficient: publication must also prove that the
     * world session, source generation, style generation and projection generation
     * still match the owner that produced the payload.
     */
    public record RevisionStamp(long sessionId, long sourceGeneration,
            long styleGeneration, long projectionGeneration) {

        public RevisionStamp {
            if (sessionId < 0L || sourceGeneration < 0L
                    || styleGeneration < 0L || projectionGeneration < 0L) {
                throw new IllegalArgumentException("Revision generations cannot be negative");
            }
        }

        public static RevisionStamp capture(MapSession session) {
            return session == null ? sessionOnly(0L) : session.stamp();
        }

        /** Compatibility stamp for legacy call sites not migrated to the full tuple yet. */
        public static RevisionStamp sessionOnly(long sessionId) {
            return new RevisionStamp(Math.max(0L, sessionId), 0L, 0L, 0L);
        }

        public boolean isComplete() {
            return sessionId > 0L && sourceGeneration > 0L
                    && styleGeneration > 0L && projectionGeneration > 0L;
        }

        public boolean matches(MapSession session) {
            if (session == null || session.sessionId() != sessionId) return false;
            if (!isComplete()) return session.state() == MapSession.State.ACTIVE
                    && !session.rootToken().isCancelled();
            return session.matches(this);
        }

        public boolean isCurrent() {
            return MapSessionManager.getInstance().isCurrent(this);
        }
    }

    /**
     * Stable identity for one region-stage demand. Queue entries may disappear;
     * this key remains represented by its RegionRecord until the requested
     * revision is completed or the owning map session is cancelled.
     */
    public record MapWorkKey(long sessionId, int regionX, int regionZ,
            MapWorkStage stage, int projectionId, RevisionStamp stamp) {

        public MapWorkKey {
            stage = Objects.requireNonNull(stage, "stage");
            stamp = stamp == null ? RevisionStamp.sessionOnly(sessionId) : stamp;
            if (stamp.sessionId() != 0L && stamp.sessionId() != sessionId) {
                throw new IllegalArgumentException("Work key session and revision stamp disagree");
            }
        }

        /** Compatibility constructor retained while legacy manager adapters migrate. */
        public MapWorkKey(long sessionId, int regionX, int regionZ,
                MapWorkStage stage, int projectionId) {
            this(sessionId, regionX, regionZ, stage, projectionId,
                    RevisionStamp.sessionOnly(sessionId));
        }

        public MapWorkKey(RevisionStamp stamp, int regionX, int regionZ,
                MapWorkStage stage, int projectionId) {
            this(Objects.requireNonNull(stamp, "stamp").sessionId(), regionX, regionZ,
                    stage, projectionId, stamp);
        }
    }

    /** Allocation-light summary for the debug/metrics control plane. */
    public record Snapshot(int regions, int dirtyStages, int runningStages,
            int preparedStages, int readyStages, int publishedStages,
            int cancelledStages) { }

    private static final MapWorkGraph INSTANCE = new MapWorkGraph();
    private final ConcurrentHashMap<RegionRecord.RegionKey, RegionRecord> records =
            new ConcurrentHashMap<>();
    /**
     * Session ids are monotonic, so one high-water mark prevents late callbacks
     * from recreating closed records without retaining one boxed tombstone per
     * dimension switch for the lifetime of the client.
     */
    private final AtomicLong cancelledThroughSession = new AtomicLong();

    private MapWorkGraph() { }

    public static MapWorkGraph getInstance() {
        return INSTANCE;
    }

    public Admission request(MapWorkKey key, long revision) {
        return request(key, revision, RegionRecord.ALL_LEAVES);
    }

    public Admission request(MapWorkKey key, long revision, long dirtyMask) {
        if (key == null || isCancelledSession(key.sessionId())) {
            return Admission.CANCELLED;
        }
        if (key.stamp() != null && key.stamp().isComplete()
                && !MapSessionManager.getInstance().isCurrent(key.stamp())) {
            return Admission.STALE;
        }
        RegionRecord.RegionKey regionKey = regionKey(key);
        RegionRecord record = records.computeIfAbsent(regionKey, RegionRecord::new);
        if (record.isCancelled()) return Admission.CANCELLED;
        return record.request(key, revision, dirtyMask)
                ? Admission.ACCEPTED : Admission.COALESCED;
    }

    public RegionRecord.Lease tryBegin(MapWorkKey key) {
        if (!isUsable(key)) return null;
        RegionRecord record = records.get(regionKey(key));
        return record == null ? null : record.tryBegin(key);
    }

    public void complete(RegionRecord.Lease lease) {
        if (lease == null || !isUsable(lease.key())) return;
        RegionRecord record = records.get(regionKey(lease.key()));
        if (record != null) record.complete(lease);
    }

    public void complete(MapWorkKey key, long revision) {
        if (!isUsable(key)) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.complete(key, revision);
    }

    public void markPrepared(MapWorkKey key, long revision, long mask) {
        if (!isUsable(key)) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.markPrepared(key, revision, mask);
    }

    public void markGpuPublished(MapWorkKey key, long revision, long residentMask) {
        if (!isUsable(key)) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.markGpuPublished(key, revision, residentMask);
    }

    public void markGpuEvicted(MapWorkKey key, long revision, long evictedMask) {
        if (key == null || isCancelledSession(key.sessionId())) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null && !record.isCancelled()) {
            record.markGpuEvicted(key, revision, evictedMask);
        }
    }

    public void markCacheCommitted(MapWorkKey key, long revision, long committedMask) {
        if (!isUsable(key)) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.markCacheCommitted(key, revision, committedMask);
    }

    public void markCacheDirty(MapWorkKey key, long revision, long dirtyMask) {
        if (key == null || isCancelledSession(key.sessionId())) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null && !record.isCancelled()) {
            record.markCacheDirty(key, revision, dirtyMask);
        }
    }

    public void markSourceChunkDirty(RevisionStamp stamp, int regionX, int regionZ,
            int localChunkIndex, long revision) {
        if (stamp == null || !stamp.isCurrent()) return;
        RegionRecord.RegionKey key = new RegionRecord.RegionKey(stamp.sessionId(), regionX, regionZ);
        RegionRecord record = records.computeIfAbsent(key, RegionRecord::new);
        record.markSourceChunkDirty(localChunkIndex, revision);
    }

    public void clearSourceChunkDirty(RevisionStamp stamp, int regionX, int regionZ,
            int localChunkIndex) {
        if (stamp == null || isCancelledSession(stamp.sessionId())) return;
        RegionRecord record = records.get(new RegionRecord.RegionKey(stamp.sessionId(), regionX, regionZ));
        if (record != null) record.clearSourceChunkDirty(localChunkIndex);
    }

    public void defer(RegionRecord.Lease lease) {
        if (lease == null) return;
        RegionRecord record = records.get(regionKey(lease.key()));
        if (record != null) record.defer(lease);
    }

    public void defer(MapWorkKey key) {
        if (key == null) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.defer(key);
    }

    public RegionRecord.Snapshot snapshot(MapWorkKey key) {
        if (key == null) return null;
        RegionRecord record = records.get(regionKey(key));
        return record == null ? null : record.snapshot();
    }

    public RegionRecord.Snapshot snapshot(long sessionId, int regionX, int regionZ) {
        RegionRecord record = records.get(new RegionRecord.RegionKey(sessionId, regionX, regionZ));
        return record == null ? null : record.snapshot();
    }

    /** Reconstructable queue hints for stages whose durable state is still dirty. */
    public List<MapWorkKey> dirtyWorkKeys(long sessionId) {
        List<MapWorkKey> result = new ArrayList<>();
        for (Map.Entry<RegionRecord.RegionKey, RegionRecord> entry : records.entrySet()) {
            if (entry.getKey().sessionId() != sessionId) continue;
            RegionRecord.Snapshot snapshot = entry.getValue().snapshot();
            for (Map.Entry<RegionRecord.StageKey, RegionRecord.StageSnapshot> stage
                    : snapshot.stages().entrySet()) {
                if (stage.getValue().state() != RegionRecord.StageState.DIRTY) continue;
                RevisionStamp stamp = revisionStampFor(sessionId, snapshot);
                result.add(new MapWorkKey(stamp, entry.getKey().regionX(),
                        entry.getKey().regionZ(), stage.getKey().stage(),
                        stage.getKey().projectionId()));
            }
        }
        return result;
    }

    public void cancelSession(long sessionId) {
        cancelledThroughSession.accumulateAndGet(sessionId, Math::max);
        for (Map.Entry<RegionRecord.RegionKey, RegionRecord> entry : records.entrySet()) {
            if (entry.getKey().sessionId() != sessionId) continue;
            entry.getValue().cancel();
            records.remove(entry.getKey(), entry.getValue());
        }
    }

    /** Clears live records. Session tombstones intentionally remain authoritative. */
    public void clear() {
        for (RegionRecord record : records.values()) record.cancel();
        records.clear();
    }

    public int recordCount() {
        return records.size();
    }

    public Snapshot snapshot() {
        int dirty = 0;
        int running = 0;
        int prepared = 0;
        int ready = 0;
        int published = 0;
        int cancelled = 0;
        for (RegionRecord record : records.values()) {
            for (RegionRecord.StageSnapshot stage : record.snapshot().stages().values()) {
                switch (stage.state()) {
                    case DIRTY -> dirty++;
                    case RUNNING -> running++;
                    case PREPARED -> prepared++;
                    case READY -> ready++;
                    case PUBLISHED -> published++;
                    case CANCELLED -> cancelled++;
                    case CLEAN -> { }
                }
            }
        }
        return new Snapshot(records.size(), dirty, running, prepared,
                ready, published, cancelled);
    }

    private boolean isUsable(MapWorkKey key) {
        if (key == null || isCancelledSession(key.sessionId())) return false;
        RevisionStamp stamp = key.stamp();
        return stamp == null || !stamp.isComplete()
                || MapSessionManager.getInstance().isCurrent(stamp);
    }

    private boolean isCancelledSession(long sessionId) {
        return sessionId > 0L && sessionId <= cancelledThroughSession.get();
    }

    private static RevisionStamp revisionStampFor(long sessionId,
            RegionRecord.Snapshot snapshot) {
        RevisionStamp active = MapSessionManager.getInstance().activeStamp();
        if (active != null && active.sessionId() == sessionId) return active;
        if (snapshot.sourceGeneration() > 0L && snapshot.styleGeneration() > 0L
                && snapshot.projectionGeneration() > 0L) {
            return new RevisionStamp(sessionId, snapshot.sourceGeneration(),
                    snapshot.styleGeneration(), snapshot.projectionGeneration());
        }
        return RevisionStamp.sessionOnly(sessionId);
    }

    private static RegionRecord.RegionKey regionKey(MapWorkKey key) {
        return new RegionRecord.RegionKey(key.sessionId(), key.regionX(), key.regionZ());
    }

    /**
     * Durable authority for all work concerning one map region. Executor queues only
     * hold hints; this record retains requested revisions, dirty masks and publication
     * state when work is coalesced, deferred, evicted or temporarily cannot acquire
     * resources.
     */
    public static final class RegionRecord {
        public static final int CHUNKS_PER_REGION = 32 * 32;
        public static final int SOURCE_MASK_WORDS = CHUNKS_PER_REGION / Long.SIZE;
        public static final long ALL_LEAVES = -1L;

        public enum StageState {
            CLEAN,
            DIRTY,
            RUNNING,
            PREPARED,
            READY,
            PUBLISHED,
            CANCELLED
        }

        /** A short-lived claim issued to exactly one worker for a region stage. */
        public record RegionKey(long sessionId, int regionX, int regionZ) { }

        public record Lease(MapWorkKey key, long revision, long dirtyMask) { }

        /** Read-only stage state for telemetry and manager adapters. */
        public record StageSnapshot(long targetRevision, long runningRevision,
                long completedRevision, long preparedRevision,
                long gpuPublishedRevision, long gpuResidentRevision,
                long cacheCommittedRevision, long dirtyMask, StageState state) {
            public boolean running() { return state == StageState.RUNNING; }
            public boolean ready() {
                return state == StageState.PREPARED || state == StageState.READY
                        || state == StageState.PUBLISHED;
            }
            public boolean published() { return state == StageState.PUBLISHED; }
        }

        /** A stage may retain multiple projection identities, e.g. cave bands. */
        public record StageKey(MapWorkStage stage, int projectionId) { }

        /** Complete immutable per-region inspection snapshot. */
        public record Snapshot(RegionKey key, long sourceGeneration,
                long styleGeneration, long projectionGeneration,
                long[] dirtySourceChunks, long dirtyExactMask, long dirtyLodMask,
                long cacheDirtyMask, long gpuResidentMask,
                Map<StageKey, StageSnapshot> stages, boolean cancelled) {
            public Snapshot {
                dirtySourceChunks = dirtySourceChunks == null
                        ? new long[SOURCE_MASK_WORDS]
                        : Arrays.copyOf(dirtySourceChunks, dirtySourceChunks.length);
                stages = stages == null ? Map.of() : Map.copyOf(stages);
            }

            @Override
            public long[] dirtySourceChunks() {
                return Arrays.copyOf(dirtySourceChunks, dirtySourceChunks.length);
            }
        }

        private final RegionKey key;
        private final Map<StageSlot, StageRecord> stages = new HashMap<>();
        private final long[] dirtySourceChunks = new long[SOURCE_MASK_WORDS];
        private long dirtyExactMask;
        private long dirtyLodMask;
        private long cacheDirtyMask;
        private long gpuResidentMask;
        private long sourceGeneration;
        private long styleGeneration;
        private long projectionGeneration;
        private boolean cancelled;

        public RegionRecord(RegionKey key) {
            this.key = key;
        }

        public synchronized boolean request(MapWorkKey workKey, long revision) {
            return request(workKey, revision, defaultDirtyMask(workKey.stage()));
        }

        public synchronized boolean request(MapWorkKey workKey, long revision, long dirtyMask) {
            if (cancelled || workKey == null) return false;
            StageRecord stage = stages.computeIfAbsent(StageSlot.of(workKey),
                    ignored -> new StageRecord());
            long requested = Math.max(1L, revision);
            long effectiveMask = dirtyMask == 0L ? defaultDirtyMask(workKey.stage()) : dirtyMask;
            boolean changed = requested > stage.targetRevision
                    || (effectiveMask & ~stage.dirtyMask) != 0L
                    || stage.state == StageState.CLEAN;
            stage.targetRevision = Math.max(stage.targetRevision, requested);
            stage.dirtyMask |= effectiveMask;
            updateGeneration(workKey, requested);
            updateRegionMasksForRequest(workKey.stage(), effectiveMask);
            if (stage.state != StageState.RUNNING
                    && (stage.completedRevision < stage.targetRevision
                            || stage.dirtyMask != 0L)) {
                stage.state = StageState.DIRTY;
            }
            return changed || stage.state == StageState.DIRTY;
        }

        public synchronized Lease tryBegin(MapWorkKey workKey) {
            if (cancelled || workKey == null) return null;
            StageRecord stage = stages.get(StageSlot.of(workKey));
            if (stage == null || stage.state != StageState.DIRTY
                    || (stage.completedRevision >= stage.targetRevision
                            && stage.dirtyMask == 0L)) return null;
            stage.state = StageState.RUNNING;
            stage.runningRevision = stage.targetRevision;
            return new Lease(workKey, stage.targetRevision, stage.dirtyMask);
        }

        public synchronized void complete(Lease lease) {
            if (cancelled || lease == null) return;
            complete(lease.key(), lease.revision(), lease.dirtyMask());
        }

        public synchronized void complete(MapWorkKey workKey, long revision) {
            complete(workKey, revision, defaultDirtyMask(workKey == null
                    ? null : workKey.stage()));
        }

        public synchronized void complete(MapWorkKey workKey, long revision, long completedMask) {
            if (cancelled || workKey == null) return;
            StageRecord stage = stages.get(StageSlot.of(workKey));
            if (stage == null) return;
            stage.runningRevision = 0L;
            stage.completedRevision = Math.max(stage.completedRevision, revision);
            if (revision >= stage.targetRevision) stage.dirtyMask &= ~completedMask;
            stage.state = stage.completedRevision >= stage.targetRevision
                    && stage.dirtyMask == 0L
                    ? StageState.READY : StageState.DIRTY;
            clearRegionMasksOnCompletion(workKey.stage(), completedMask,
                    stage.completedRevision >= stage.targetRevision);
        }

        public synchronized void markPrepared(MapWorkKey workKey, long revision, long preparedMask) {
            if (cancelled || workKey == null) return;
            StageRecord stage = stages.get(StageSlot.of(workKey));
            if (stage == null) return;
            stage.runningRevision = 0L;
            stage.preparedRevision = Math.max(stage.preparedRevision, revision);
            stage.completedRevision = Math.max(stage.completedRevision, revision);
            if (revision >= stage.targetRevision) stage.dirtyMask &= ~preparedMask;
            stage.state = stage.preparedRevision >= stage.targetRevision
                    && stage.dirtyMask == 0L
                    ? StageState.PREPARED : StageState.DIRTY;
        }

        public synchronized void markGpuPublished(MapWorkKey workKey, long revision,
                long residentMask) {
            if (cancelled || workKey == null) return;
            StageRecord stage = stages.get(StageSlot.of(workKey));
            if (stage == null) return;
            stage.gpuPublishedRevision = Math.max(stage.gpuPublishedRevision, revision);
            stage.gpuResidentRevision = Math.max(stage.gpuResidentRevision, revision);
            stage.completedRevision = Math.max(stage.completedRevision, revision);
            stage.preparedRevision = Math.max(stage.preparedRevision, revision);
            stage.dirtyMask &= ~residentMask;
            gpuResidentMask |= residentMask;
            dirtyExactMask &= ~residentMask;
            stage.state = revision >= stage.targetRevision
                    && stage.dirtyMask == 0L
                    ? StageState.PUBLISHED : StageState.DIRTY;
        }

        public synchronized void markGpuEvicted(MapWorkKey workKey, long revision,
                long evictedMask) {
            if (cancelled || workKey == null) return;
            StageRecord stage = stages.get(StageSlot.of(workKey));
            if (stage == null) return;
            gpuResidentMask &= ~evictedMask;
            if (gpuResidentMask == 0L || revision >= stage.gpuResidentRevision) {
                stage.gpuResidentRevision = 0L;
            }
            stage.dirtyMask |= evictedMask;
            dirtyExactMask |= evictedMask;
            stage.state = stage.preparedRevision >= stage.targetRevision
                    ? StageState.PREPARED : StageState.DIRTY;
        }

        public synchronized void markCacheCommitted(MapWorkKey workKey, long revision,
                long committedMask) {
            if (cancelled || workKey == null) return;
            StageRecord stage = stages.get(StageSlot.of(workKey));
            if (stage == null) return;
            stage.cacheCommittedRevision = Math.max(stage.cacheCommittedRevision, revision);
            stage.completedRevision = Math.max(stage.completedRevision, revision);
            stage.dirtyMask &= ~committedMask;
            cacheDirtyMask &= ~committedMask;
            stage.state = revision >= stage.targetRevision
                    && stage.dirtyMask == 0L
                    ? StageState.PUBLISHED : StageState.DIRTY;
        }

        public synchronized void markCacheDirty(MapWorkKey workKey, long revision,
                long dirtyMask) {
            if (cancelled || workKey == null) return;
            StageRecord stage = stages.computeIfAbsent(StageSlot.of(workKey),
                    ignored -> new StageRecord());
            stage.targetRevision = Math.max(stage.targetRevision, Math.max(1L, revision));
            stage.dirtyMask |= dirtyMask;
            cacheDirtyMask |= dirtyMask;
            stage.state = stage.state == StageState.RUNNING
                    ? StageState.RUNNING : StageState.DIRTY;
        }

        public synchronized void markSourceChunkDirty(int localChunkIndex, long revision) {
            if (cancelled || localChunkIndex < 0 || localChunkIndex >= CHUNKS_PER_REGION) return;
            int word = localChunkIndex >>> 6;
            long bit = 1L << (localChunkIndex & 63);
            dirtySourceChunks[word] |= bit;
            sourceGeneration = Math.max(sourceGeneration, Math.max(1L, revision));
        }

        public synchronized void clearSourceChunkDirty(int localChunkIndex) {
            if (localChunkIndex < 0 || localChunkIndex >= CHUNKS_PER_REGION) return;
            dirtySourceChunks[localChunkIndex >>> 6] &= ~(1L << (localChunkIndex & 63));
        }

        public synchronized void defer(Lease lease) {
            if (cancelled || lease == null) return;
            defer(lease.key());
        }

        public synchronized void defer(MapWorkKey workKey) {
            if (cancelled || workKey == null) return;
            StageRecord stage = stages.get(StageSlot.of(workKey));
            if (stage != null) {
                stage.runningRevision = 0L;
                stage.state = StageState.DIRTY;
            }
        }

        public synchronized void cancel() {
            cancelled = true;
            for (StageRecord stage : stages.values()) {
                stage.runningRevision = 0L;
                stage.state = StageState.CANCELLED;
            }
        }

        public synchronized boolean isCancelled() {
            return cancelled;
        }

        public synchronized Snapshot snapshot() {
            Map<StageKey, StageSnapshot> snapshot = new HashMap<>();
            for (Map.Entry<StageSlot, StageRecord> entry : stages.entrySet()) {
                StageRecord value = entry.getValue();
                StageSlot slot = entry.getKey();
                snapshot.put(new StageKey(slot.stage, slot.projectionId),
                        new StageSnapshot(value.targetRevision, value.runningRevision,
                                value.completedRevision, value.preparedRevision,
                                value.gpuPublishedRevision, value.gpuResidentRevision,
                                value.cacheCommittedRevision, value.dirtyMask, value.state));
            }
            return new Snapshot(key, sourceGeneration, styleGeneration,
                    projectionGeneration, dirtySourceChunks, dirtyExactMask,
                    dirtyLodMask, cacheDirtyMask, gpuResidentMask, snapshot, cancelled);
        }

        private void updateGeneration(MapWorkKey workKey, long revision) {
            RevisionStamp stamp = workKey.stamp();
            if (stamp != null && stamp.isComplete()) {
                sourceGeneration = Math.max(sourceGeneration, stamp.sourceGeneration());
                styleGeneration = Math.max(styleGeneration, stamp.styleGeneration());
                projectionGeneration = Math.max(projectionGeneration,
                        stamp.projectionGeneration());
                return;
            }
            switch (workKey.stage()) {
                case SOURCE_CAPTURE, SOURCE_READ, SOURCE_DECODE, SOURCE_COMMIT ->
                        sourceGeneration = Math.max(sourceGeneration, revision);
                case CAVE_PROJECTION, FULL_CAVE_PROJECTION ->
                        projectionGeneration = Math.max(projectionGeneration, revision);
                case STYLE -> styleGeneration = Math.max(styleGeneration, revision);
                case LOD_DERIVE, GPU_PREPARE, GPU_UPLOAD, CACHE_COMMIT -> { }
            }
        }

        private void updateRegionMasksForRequest(MapWorkStage stage, long mask) {
            if (stage == null) return;
            switch (stage) {
                case SOURCE_CAPTURE, SOURCE_READ, SOURCE_DECODE, SOURCE_COMMIT -> {
                    if (mask == ALL_LEAVES) Arrays.fill(dirtySourceChunks, ALL_LEAVES);
                }
                case STYLE, GPU_PREPARE, GPU_UPLOAD -> dirtyExactMask |= mask;
                case LOD_DERIVE -> dirtyLodMask |= mask;
                case CACHE_COMMIT -> cacheDirtyMask |= mask;
                case CAVE_PROJECTION, FULL_CAVE_PROJECTION -> dirtyExactMask |= mask;
            }
        }

        private void clearRegionMasksOnCompletion(MapWorkStage stage, long mask,
                boolean targetReached) {
            if (!targetReached || stage == null) return;
            switch (stage) {
                case SOURCE_CAPTURE, SOURCE_READ, SOURCE_DECODE, SOURCE_COMMIT -> {
                    if (mask == ALL_LEAVES) Arrays.fill(dirtySourceChunks, 0L);
                }
                case STYLE, GPU_PREPARE, GPU_UPLOAD,
                        CAVE_PROJECTION, FULL_CAVE_PROJECTION -> dirtyExactMask &= ~mask;
                case LOD_DERIVE -> dirtyLodMask &= ~mask;
                case CACHE_COMMIT -> cacheDirtyMask &= ~mask;
            }
        }

        private static long defaultDirtyMask(MapWorkStage stage) {
            return stage == null ? 0L : ALL_LEAVES;
        }

        private record StageSlot(MapWorkStage stage, int projectionId) {
            private static StageSlot of(MapWorkKey key) {
                return new StageSlot(key.stage(), key.projectionId());
            }
        }

        private static final class StageRecord {
            private long targetRevision;
            private long runningRevision;
            private long completedRevision;
            private long preparedRevision;
            private long gpuPublishedRevision;
            private long gpuResidentRevision;
            private long cacheCommittedRevision;
            private long dirtyMask;
            private StageState state = StageState.CLEAN;
        }
    }
}



    /**
 * CPU-only texture preparation routed through the global map work planner.
 * Immutable primitive snapshots remain safe away from the client/render thread,
 * but exact, legacy and cave styling no longer own competing private executors.
 */
public static final class MapTextureBuildWorker {
    private static final int SIZE = 512;
    private static final int PIXELS = SIZE * SIZE;

    private MapTextureBuildWorker() {
    }

    static CompletableFuture<PreparedPair> tryBuildSurface(
            long[] pixels, int[] tints, List<String> biomePalette,
            List<String> blockPalette, IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision) {
        return tryBuildSurface(pixels, tints, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, light, profile,
                revision, () -> true);
    }

    static CompletableFuture<PreparedPair> tryBuildSurface(
            long[] pixels, int[] tints, List<String> biomePalette,
            List<String> blockPalette, IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid) {
        return submit(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 64, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            check(valid);
            int[] styled = SurfaceColorizer.colorize(
                    pixels, tints, biomePalette, blockPalette, biomeLookup,
                    blockColors, tintPolicies, tintDisabledBlocks, colourMode,
                    showFlowers, terrainSlopes, profile, valid);
            byte[] smoothed = buildSmoothedLight(light, valid);
            int[] glow = new int[PIXELS];
            for (int z = 0; z < SIZE; z++) {
                if ((z & 31) == 0) check(valid);
                for (int x = 0; x < SIZE; x++) {
                    int index = z * SIZE + x;
                    int color = styled[index];
                    int level = smoothed == null ? 0 : smoothed[index] & 0xFF;
                    int alpha = color == 0 || level == 0 ? 0
                            : Math.min(255, Math.round(
                                    (float) Math.pow(level / 15.0f, 1.65f) * 255.0f));
                    int warm = tintTowardWarmLight(color, level);
                    glow[index] = (warm & 0x00FFFFFF) | (alpha << 24);
                }
            }
            return new PreparedPair(styled, glow, revision,
                    buildPageKnownRows(pixels));
        });
    }

    static CompletableFuture<PreparedSingle> tryBuildCave(
            int[] source, short[] heights, int terrainSlopes, int profile,
            long revision, BooleanSupplier stillValid) {
        return submit(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 48, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            return new PreparedSingle(CaveReliefColorizer.colorize(
                    source, heights, terrainSlopes, profile, valid), revision);
        });
    }

    static CompletableFuture<PreparedPair> tryBuildSurfacePage(
            long[] pixels, int[] tints, int stride, int halo,
            int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid) {
        return tryBuildSurfacePage(pixels, tints, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, light, profile,
                revision, stillValid, MapRequestLane.FULLSCREEN.executorPriority(), null);
    }

    static CompletableFuture<PreparedPair> tryBuildSurfacePage(
            long[] pixels, int[] tints, int stride, int halo,
            int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid,
            int executorPriority, RevisionStamp stamp) {
        return tryBuildSurfacePage(pixels, tints, null, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, light, profile, revision,
                stillValid, executorPriority, stamp);
    }

    static CompletableFuture<PreparedPair> tryBuildSurfacePage(
            long[] pixels, int[] tints, byte[] known, int stride, int halo,
            int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid,
            int executorPriority, RevisionStamp stamp) {
        return tryBuildSurfacePage(pixels, tints, known, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, light, profile, revision,
                stillValid, executorPriority, stamp,
                MapPageLayout.FULL_SUBTILE_MASK);
    }

    static CompletableFuture<PreparedPair> tryBuildSurfacePage(
            long[] pixels, int[] tints, byte[] known, int stride, int halo,
            int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid,
            int executorPriority, RevisionStamp stamp,
            int requestedSubtileMask) {
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(executorPriority);
        MapWorkScheduler.WorkType type = lane == MapRequestLane.MINIMAP
                ? MapWorkScheduler.WorkType.MINIMAP_EXACT
                : MapWorkScheduler.WorkType.EXACT_BUILD;
        return submit(lane, type, lane.priorityBase(), 8, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            check(valid);
            long[] knownRows = known == null
                    ? buildKnownRowsForPageWindow(pixels, stride, halo)
                    : buildKnownRowsForPageWindow(known, stride, halo);
            int updateSubtiles = requestedSubtileMask
                    & MapPageLayout.completeSubtileMask(knownRows);
            int[] pageStyled = SurfaceColorizer.colorizePageWindow(
                    pixels, tints, known, stride, halo, worldPageStartX, worldPageStartZ,
                    biomePalette, blockPalette, biomeLookup, blockColors,
                    tintPolicies, tintDisabledBlocks, colourMode, showFlowers,
                    terrainSlopes, profile, updateSubtiles, valid);
            byte[] pageLight = buildSmoothedLightPageWindow(light, stride, halo,
                    updateSubtiles, valid);
            int[] pageGlow = new int[pageStyled.length];
            for (int i = 0; i < pageStyled.length; i++) {
                if ((i & 1023) == 0) check(valid);
                int localX = i % MapPageLayout.PAGE_SIZE;
                int localZ = i / MapPageLayout.PAGE_SIZE;
                int subtile = MapPageLayout.subtileIndex(
                        localX / MapPageLayout.SUBTILE_SIZE,
                        localZ / MapPageLayout.SUBTILE_SIZE);
                if ((updateSubtiles & (1 << subtile)) == 0) continue;
                int color = pageStyled[i];
                int level = pageLight == null ? 0 : pageLight[i] & 0xFF;
                int alpha = color == 0 || level == 0 ? 0
                        : Math.min(255, Math.round(
                                (float) Math.pow(level / 15.0f, 1.65f) * 255.0f));
                int warm = tintTowardWarmLight(color, level);
                pageGlow[i] = (warm & 0x00FFFFFF) | (alpha << 24);
            }
            return new PreparedPair(pageStyled, pageGlow, revision,
                    new long[][] { knownRows }, stamp, updateSubtiles);
        });
    }

    static CompletableFuture<PreparedSurfaceRegionBatch> tryBuildSurfaceBatch(
            SurfaceRegionSourceDatabase.BatchSourcePlan sourcePlan,
            MapStyleSnapshot style, long[] pageRevisions,
            boolean[] activePages, int[] requestedSubtileMasks,
            BooleanSupplier stillValid,
            int executorPriority) {
        if (sourcePlan == null || style == null || pageRevisions == null
                || activePages == null || pageRevisions.length
                        != sourcePlan.pagesWide() * sourcePlan.pagesHigh()
                || activePages.length != pageRevisions.length
                || requestedSubtileMasks == null
                || requestedSubtileMasks.length != pageRevisions.length) {
            return null;
        }
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(executorPriority);
        MapWorkScheduler.WorkType type = lane == MapRequestLane.MINIMAP
                ? MapWorkScheduler.WorkType.MINIMAP_EXACT
                : MapWorkScheduler.WorkType.EXACT_BUILD;
        int pageCount = sourcePlan.pagesWide() * sourcePlan.pagesHigh();
        int activeCount = 0;
        for (boolean active : activePages) if (active) activeCount++;
        if (activeCount == 0) return null;
        final int admittedPages = activeCount;
        return submit(lane, type, lane.priorityBase(),
                Math.max(8, admittedPages * 6),
                stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            SurfaceRegionSourceDatabase.AssembledBatchWindow source =
                    sourcePlan.assemble(valid);
            MapTextureBuildWorker.PreparedPair[] prepared =
                    new MapTextureBuildWorker.PreparedPair[pageCount];
            int compactStride = MapPageLayout.PAGE_SNAPSHOT_SIZE;
            int compactPixels = compactStride * compactStride;
            int halo = source.halo();

            /*
             * A 1x1 transaction is already exactly one PAGE_SNAPSHOT_SIZE window.
             * PASS102 nevertheless allocated a second long/int/byte/byte 66x66
             * scratch set and copied the assembled source into it. Minimap is now
             * deliberately 1x1, so that duplicate accounted for four avoidable
             * primitive arrays on every live page build. Borrow the immutable
             * worker-local assembly directly for 1x1 and keep reusable compact
             * scratch only for multi-page fullscreen batches.
             */
            boolean directSinglePage = source.pagesWide() == 1
                    && source.pagesHigh() == 1
                    && source.stride() == compactStride
                    && source.height() == compactStride;
            long[] pixels = directSinglePage ? source.pixelsUnsafe()
                    : new long[compactPixels];
            int[] tints = directSinglePage ? source.tintsUnsafe()
                    : new int[compactPixels];
            byte[] lights = directSinglePage ? source.lightUnsafe()
                    : new byte[compactPixels];
            byte[] known = directSinglePage ? source.knownUnsafe()
                    : new byte[compactPixels];
            byte[] smoothedLights = new byte[MapPageLayout.PAGE_SIZE
                    * MapPageLayout.PAGE_SIZE];
            List<String> biomePalette = source.biomePalette();
            List<String> blockPalette = source.blockPalette();
            IntFunction<Biome> biomeLookup = style.biomeLookup(biomePalette);

            for (int pageZ = 0; pageZ < source.pagesHigh(); pageZ++) {
                for (int pageX = 0; pageX < source.pagesWide(); pageX++) {
                    int pageIndex = pageZ * source.pagesWide() + pageX;
                    if (!activePages[pageIndex]) continue;
                    check(valid);
                    int sourceX = pageX * MapPageLayout.PAGE_SIZE;
                    int sourceZ = pageZ * MapPageLayout.PAGE_SIZE;
                    if (!directSinglePage) {
                        for (int z = 0; z < compactStride; z++) {
                            int from = (sourceZ + z) * source.stride() + sourceX;
                            int to = z * compactStride;
                            System.arraycopy(source.pixelsUnsafe(), from,
                                    pixels, to, compactStride);
                            System.arraycopy(source.tintsUnsafe(), from,
                                    tints, to, compactStride);
                            System.arraycopy(source.lightUnsafe(), from,
                                    lights, to, compactStride);
                            System.arraycopy(source.knownUnsafe(), from,
                                    known, to, compactStride);
                        }
                    }
                    int worldPageStartX = source.worldPageStartX()
                            + pageX * MapPageLayout.PAGE_SIZE;
                    int worldPageStartZ = source.worldPageStartZ()
                            + pageZ * MapPageLayout.PAGE_SIZE;
                    long[] knownRows = buildKnownRowsForPageWindow(
                            known, compactStride, halo);
                    int updateSubtiles = requestedSubtileMasks[pageIndex]
                            & MapPageLayout.completeSubtileMask(knownRows);
                    int[] styled = SurfaceColorizer.colorizePageWindow(
                            pixels, tints, known, compactStride, halo,
                            worldPageStartX, worldPageStartZ,
                            biomePalette, blockPalette, biomeLookup,
                            style.blockColors(), style.tintPolicies(),
                            style.tintDisabledBlocks(), style.colourMode(),
                            style.showFlowers(), style.terrainSlopes(),
                            style.profile(), updateSubtiles, valid);
                    byte[] smoothed = buildSmoothedLightPageWindowInto(
                            lights, compactStride, halo, valid, smoothedLights,
                            updateSubtiles);
                    int[] glow = new int[styled.length];
                    for (int index = 0; index < styled.length; index++) {
                        int localX = index % MapPageLayout.PAGE_SIZE;
                        int localZ = index / MapPageLayout.PAGE_SIZE;
                        int subtile = MapPageLayout.subtileIndex(
                                localX / MapPageLayout.SUBTILE_SIZE,
                                localZ / MapPageLayout.SUBTILE_SIZE);
                        if ((updateSubtiles & (1 << subtile)) == 0) continue;
                        int color = styled[index];
                        int level = smoothed == null ? 0 : smoothed[index] & 0xFF;
                        int alpha = color == 0 || level == 0 ? 0
                                : Math.min(255, Math.round((float) Math.pow(
                                        level / 15.0f, 1.65f) * 255.0f));
                        int warm = tintTowardWarmLight(color, level);
                        glow[index] = (warm & 0x00FFFFFF) | (alpha << 24);
                    }
                    prepared[pageIndex] = new PreparedPair(styled, glow,
                            pageRevisions[pageIndex], new long[][] {
                                    knownRows }, source.stamp(), updateSubtiles);
                }
            }
            return new PreparedSurfaceRegionBatch(source.stamp(),
                    source.regionX(), source.regionZ(), source.batchPageX(),
                    source.batchPageZ(), source.pagesWide(), source.pagesHigh(),
                    source.sourceRevision(), prepared);
        });
    }

    /**
     * Lightweight direct projection from the already resident region. PASS118
     * uses a 4-block supersample grid and linearly reduces 2x2 styled samples into
     * each final level-0 texel. This removes the point-sampled 8x8 alias pattern
     * without cloning/colorizing the full 512x512 region.
     */
    static CompletableFuture<PreparedBranch> tryBuildRegionLodLevel0(
            RegionLodGraph.Lease lease, MapManager.RegionLodSnapshot source,
            MapStyleSnapshot style, BooleanSupplier stillValid,
            int executorPriority) {
        int sampledOutputSize = source == null ? 0
                : source.stride() - source.halo() * 2;
        if (lease == null || source == null || style == null
                || lease.key().level() != 0 || source.halo() != 1
                || source.sampleStep() != 4 || sampledOutputSize != 128
                || source.pixelsUnsafe().length
                        != source.stride() * source.stride()
                || source.tintsUnsafe().length
                        != source.pixelsUnsafe().length
                || source.knownUnsafe().length
                        != source.pixelsUnsafe().length) {
            return null;
        }
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                executorPriority);
        return submit(lane, MapWorkScheduler.WorkType.SOURCE_PROJECTION,
                lane.priorityBase(), 12, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            check(valid);
            int[] supersampled = SurfaceColorizer.colorizeSampleWindow(
                    source.pixelsUnsafe(), source.tintsUnsafe(), source.knownUnsafe(),
                    source.stride(),
                    source.halo(), sampledOutputSize,
                    source.regionX() * MapPageLayout.REGION_SIZE
                            + source.sampleStep() / 2,
                    source.regionZ() * MapPageLayout.REGION_SIZE
                            + source.sampleStep() / 2,
                    source.sampleStep(),
                    Arrays.asList(source.biomePaletteUnsafe()),
                    Arrays.asList(source.blockPaletteUnsafe()),
                    style.biomeLookup(), style.blockColors(),
                    style.tintPolicies(), style.tintDisabledBlocks(),
                    style.colourMode(), style.showFlowers(),
                    style.terrainSlopes(), style.profile(), valid);
            int[] output = new int[64 * 64];
            long[] knownRows = new long[64];
            byte[] known = source.knownUnsafe();
            int stride = source.stride();
            int halo = source.halo();
            for (int y = 0; y < 64; y++) {
                if ((y & 15) == 0) check(valid);
                int sampleY = y << 1;
                for (int x = 0; x < 64; x++) {
                    int sampleX = x << 1;
                    int knownCount = 0;
                    long red = 0L;
                    long green = 0L;
                    long blue = 0L;
                    int colored = 0;
                    for (int dz = 0; dz < 2; dz++) {
                        int sourceY = sampleY + dz;
                        int knownRowOffset = (sourceY + halo) * stride + halo;
                        int colorRowOffset = sourceY * sampledOutputSize;
                        for (int dx = 0; dx < 2; dx++) {
                            int sourceX = sampleX + dx;
                            if (known[knownRowOffset + sourceX] == 0) continue;
                            knownCount++;
                            int color = supersampled[colorRowOffset + sourceX];
                            if ((color >>> 24) == 0) continue;
                            colored++;
                            red += color & 0xFF;
                            green += (color >>> 8) & 0xFF;
                            blue += (color >>> 16) & 0xFF;
                        }
                    }
                    if (knownCount > 0) knownRows[y] |= 1L << x;
                    if (colored > 0) {
                        output[y * 64 + x] = 0xFF000000
                                | ((int) (blue / colored) << 16)
                                | ((int) (green / colored) << 8)
                                | (int) (red / colored);
                    }
                }
            }

            long knownMask = 0L;
            long completeMask = 0L;
            for (int childZ = 0; childZ < 8; childZ++) {
                for (int childX = 0; childX < 8; childX++) {
                    long segment = 0xFFL << (childX * 8);
                    boolean any = false;
                    boolean all = true;
                    int firstY = childZ * 8;
                    for (int y = firstY; y < firstY + 8; y++) {
                        long rowSegment = knownRows[y] & segment;
                        any |= rowSegment != 0L;
                        all &= rowSegment == segment;
                    }
                    long bit = 1L << (childZ * 8 + childX);
                    if (any) knownMask |= bit;
                    if (all) completeMask |= bit;
                }
            }
            int[] dirty = regionLodDirtyRect(lease.dirtyChildMask());
            return new PreparedBranch(lease.key(), lease.stamp(),
                    lease.revision(), 64, 64, output, knownMask,
                    completeMask, knownRows, Arrays.copyOf(knownRows, 64),
                    lease.childVersionSums(), dirty[0], dirty[1],
                    dirty[2], dirty[3]);
        });
    }

    static CompletableFuture<PreparedBranch> tryDeriveRegionLodParent(
            RegionLodGraph.Lease lease,
            java.util.Collection<RegionLodDeriver.ChildSnapshot> children,
            BooleanSupplier stillValid, int executorPriority) {
        if (lease == null || lease.key().level() <= 0) return null;
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                executorPriority);
        return submit(lane, MapWorkScheduler.WorkType.BRANCH_DERIVE,
                lane.priorityBase(), 48, stillValid,
                () -> RegionLodDeriver.derive(
                        lease, children, guarded(stillValid)));
    }

    static CompletableFuture<PreparedBranch> tryDeriveRegionLodLevel0(
            RegionLodGraph.Lease lease,
            java.util.Collection<RegionLodDeriver.ReducedChildSnapshot> children,
            BooleanSupplier stillValid, int executorPriority) {
        if (lease == null || lease.key().level() != 0) return null;
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                executorPriority);
        return submit(lane, MapWorkScheduler.WorkType.BRANCH_DERIVE,
                lane.priorityBase(), 16, stillValid,
                () -> RegionLodDeriver
                        .deriveLevel0(lease, children, guarded(stillValid)));
    }

    private static int[] regionLodDirtyRect(long dirtyChildMask) {
        if (dirtyChildMask == 0L || dirtyChildMask == -1L) {
            return new int[] { 0, 0, 63, 63 };
        }
        int minX = 64;
        int minY = 64;
        int maxX = -1;
        int maxY = -1;
        for (int child = 0; child < 64; child++) {
            if ((dirtyChildMask & (1L << child)) == 0L) continue;
            int x = (child & 7) * 8;
            int y = (child >>> 3) * 8;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + 7);
            maxY = Math.max(maxY, y + 7);
        }
        return maxX < minX ? new int[] { 0, 0, 63, 63 }
                : new int[] { minX, minY, maxX, maxY };
    }

    static CompletableFuture<PreparedSingle> tryBuildCavePage(
            int[] source, short[] heights, int pageX, int pageZ,
            int terrainSlopes, int profile, long revision,
            BooleanSupplier stillValid) {
        return submit(MapRequestLane.FULLSCREEN,
                MapWorkScheduler.WorkType.EXACT_BUILD, 0, 24, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            int[] fullStyled = CaveReliefColorizer.colorize(
                    source, heights, terrainSlopes, profile, valid);
            int pagePixels = MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE;
            int[] pageStyled = new int[pagePixels];
            int startX = pageX * MapPageLayout.PAGE_SIZE;
            int startZ = pageZ * MapPageLayout.PAGE_SIZE;
            for (int pz = 0; pz < MapPageLayout.PAGE_SIZE; pz++) {
                if ((pz & 15) == 0) check(valid);
                int z = startZ + pz;
                System.arraycopy(fullStyled, z * SIZE + startX,
                        pageStyled, pz * MapPageLayout.PAGE_SIZE,
                        MapPageLayout.PAGE_SIZE);
            }
            return new PreparedSingle(pageStyled, revision);
        });
    }

    static CompletableFuture<PreparedSingle> tryBuildSingle(
            int[] source, int profile, long revision) {
        return tryBuildSingle(source, profile, revision, () -> true);
    }

    static CompletableFuture<PreparedSingle> tryBuildSingle(
            int[] source, int profile, long revision,
            BooleanSupplier stillValid) {
        return submit(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 48, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            int[] styled = new int[PIXELS];
            for (int i = 0; i < styled.length; i++) {
                if ((i & 8191) == 0) check(valid);
                styled[i] = MapColorProfile.apply(source[i], profile);
            }
            return new PreparedSingle(styled, revision);
        });
    }

    private static <T> CompletableFuture<T> submit(MapRequestLane lane,
            MapWorkScheduler.WorkType type, int priority, int cost,
            BooleanSupplier stillValid, Supplier<T> supplier) {
        if (supplier == null) return null;
        BooleanSupplier valid = stillValid == null ? () -> true : stillValid;
        /*
         * Viewport-scoped tasks may be purged before a worker starts them. The old
         * tryCpu wrapper left its manually-created future permanently incomplete in
         * that case, so pendingSurfaceBatches/page.pending accumulated while the
         * scheduler itself reported zero queued work. tryCpuFuture deliberately runs
         * a tiny terminal command for invalid tasks and cancels the future, allowing
         * all ownership/requeue callbacks to release their state.
         */
        return MapWorkScheduler.tryCpuFuture(lane, type, priority, cost, valid, () -> {
            check(valid);
            return supplier.get();
        });
    }

    private static BooleanSupplier guarded(BooleanSupplier valid) {
        return valid == null ? () -> true : valid;
    }

    private static void check(BooleanSupplier valid) {
        if (!valid.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
        }
    }

    private static byte[] buildSmoothedLight(byte[] levels, BooleanSupplier valid) {
        if (levels == null) return null;
        int[] horizontal = new int[PIXELS];
        byte[] result = new byte[PIXELS];
        for (int z = 0; z < SIZE; z++) {
            if ((z & 31) == 0 && !valid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            int row = z * SIZE;
            for (int x = 0; x < SIZE; x++) {
                int sum = (levels[row + x] & 0xFF) * 2;
                if (x > 0) sum += levels[row + x - 1] & 0xFF;
                if (x + 1 < SIZE) sum += levels[row + x + 1] & 0xFF;
                horizontal[row + x] = sum;
            }
        }
        for (int z = 0; z < SIZE; z++) {
            if ((z & 31) == 0 && !valid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            int verticalWeight = (z > 0 ? 1 : 0) + 2 + (z + 1 < SIZE ? 1 : 0);
            int row = z * SIZE;
            for (int x = 0; x < SIZE; x++) {
                int sum = horizontal[row + x] * 2;
                if (z > 0) sum += horizontal[row - SIZE + x];
                if (z + 1 < SIZE) sum += horizontal[row + SIZE + x];
                int horizontalWeight = (x > 0 ? 1 : 0) + 2 + (x + 1 < SIZE ? 1 : 0);
                int filtered = Math.round((float) sum / (horizontalWeight * verticalWeight));
                int original = levels[row + x] & 0xFF;
                result[row + x] = (byte) Math.max(original, filtered);
            }
        }
        return result;
    }

    private static byte[] buildSmoothedLightPageWindow(byte[] levels, int stride,
            int halo, BooleanSupplier valid) {
        return buildSmoothedLightPageWindow(levels, stride, halo,
                MapPageLayout.FULL_SUBTILE_MASK, valid);
    }

    private static byte[] buildSmoothedLightPageWindow(byte[] levels, int stride,
            int halo, int requestedSubtiles, BooleanSupplier valid) {
        if (levels == null) return null;
        return buildSmoothedLightPageWindowInto(levels, stride, halo, valid,
                new byte[MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE],
                requestedSubtiles);
    }

    private static byte[] buildSmoothedLightPageWindowInto(byte[] levels,
            int stride, int halo, BooleanSupplier valid, byte[] result) {
        return buildSmoothedLightPageWindowInto(levels, stride, halo, valid,
                result, MapPageLayout.FULL_SUBTILE_MASK);
    }

    private static byte[] buildSmoothedLightPageWindowInto(byte[] levels,
            int stride, int halo, BooleanSupplier valid, byte[] result,
            int requestedSubtiles) {
        if (levels == null) return null;
        int pageSize = MapPageLayout.PAGE_SIZE;
        if (levels.length != stride * stride || stride < pageSize + halo * 2
                || result == null || result.length < pageSize * pageSize) {
            return null;
        }
        for (int localZ = 0; localZ < pageSize; localZ++) {
            if ((localZ & 15) == 0 && !valid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            int subtileRow = (localZ / MapPageLayout.SUBTILE_SIZE)
                    * MapPageLayout.SUBTILES_PER_PAGE;
            int z = halo + localZ;
            for (int localX = 0; localX < pageSize; localX++) {
                int subtile = subtileRow
                        + localX / MapPageLayout.SUBTILE_SIZE;
                if ((requestedSubtiles & (1 << subtile)) == 0) continue;
                int x = halo + localX;
                int weighted = 0;
                int weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int sampleZ = z + dz;
                    if (sampleZ < 0 || sampleZ >= stride) continue;
                    int wz = dz == 0 ? 2 : 1;
                    int row = sampleZ * stride;
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= stride) continue;
                        int wx = dx == 0 ? 2 : 1;
                        int sampleWeight = wx * wz;
                        weighted += (levels[row + sampleX] & 0xFF) * sampleWeight;
                        weight += sampleWeight;
                    }
                }
                int original = levels[z * stride + x] & 0xFF;
                int filtered = weight == 0 ? original
                        : Math.round((float) weighted / weight);
                result[localZ * pageSize + localX] =
                        (byte) Math.max(original, filtered);
            }
        }
        return result;
    }

    private static long[] buildKnownRowsForPageWindow(long[] packedPixels,
            int stride, int halo) {
        long[] rows = new long[MapPageLayout.PAGE_SIZE];
        int pageSize = MapPageLayout.PAGE_SIZE;
        if (packedPixels == null || packedPixels.length != stride * stride
                || stride < pageSize + halo * 2) return rows;
        for (int localZ = 0; localZ < pageSize; localZ++) {
            int row = (halo + localZ) * stride + halo;
            long mask = 0L;
            for (int localX = 0; localX < pageSize; localX++) {
                if (!MapBlockData.isEmpty(packedPixels[row + localX])) {
                    mask |= 1L << localX;
                }
            }
            rows[localZ] = mask;
        }
        return rows;
    }

    /** Coverage is independent of material: a scanned void column is still known. */
    private static long[] buildKnownRowsForPageWindow(byte[] knownPixels,
            int stride, int halo) {
        long[] rows = new long[MapPageLayout.PAGE_SIZE];
        int pageSize = MapPageLayout.PAGE_SIZE;
        if (knownPixels == null || knownPixels.length != stride * stride
                || stride < pageSize + halo * 2) return rows;
        for (int localZ = 0; localZ < pageSize; localZ++) {
            int row = (halo + localZ) * stride + halo;
            long mask = 0L;
            for (int localX = 0; localX < pageSize; localX++) {
                if (knownPixels[row + localX] != 0) mask |= 1L << localX;
            }
            rows[localZ] = mask;
        }
        return rows;
    }

    private static int tintTowardWarmLight(int abgr, int light) {
        if (abgr == 0 || light <= 6) return abgr;
        float strength = Math.min(0.60f, ((light - 6) / 9.0f) * 0.60f);
        int red = abgr & 0xFF;
        int green = (abgr >>> 8) & 0xFF;
        int blue = (abgr >>> 16) & 0xFF;
        red = Math.round(red + (255 - red) * strength);
        green = Math.round(green + (190 - green) * strength);
        blue = Math.round(blue + (88 - blue) * strength);
        return (abgr & 0xFF000000) | (blue << 16) | (green << 8) | red;
    }

    private static long[][] buildPageKnownRows(long[] packedPixels) {
        long[][] pages = new long[MapPageLayout.PAGES_PER_REGION * MapPageLayout.PAGES_PER_REGION]
                [MapPageLayout.PAGE_SIZE];
        if (packedPixels == null || packedPixels.length < PIXELS) return pages;
        for (int z = 0; z < SIZE; z++) {
            int pageZ = z / MapPageLayout.PAGE_SIZE;
            int localZ = z % MapPageLayout.PAGE_SIZE;
            int fullRow = z * SIZE;
            for (int pageX = 0; pageX < MapPageLayout.PAGES_PER_REGION; pageX++) {
                long mask = 0L;
                int startX = pageX * MapPageLayout.PAGE_SIZE;
                for (int x = 0; x < MapPageLayout.PAGE_SIZE; x++) {
                    if (!MapBlockData.isEmpty(packedPixels[fullRow + startX + x])) {
                        mask |= 1L << x;
                    }
                }
                pages[pageZ * MapPageLayout.PAGES_PER_REGION + pageX][localZ] = mask;
            }
        }
        return pages;
    }

    record PreparedPair(int[] styled, int[] glow, long revision,
            long[][] pageKnownRows, RevisionStamp stamp,
            int updateSubtileMask) {
        PreparedPair(int[] styled, int[] glow, long revision,
                long[][] pageKnownRows) {
            this(styled, glow, revision, pageKnownRows, null,
                    MapPageLayout.FULL_SUBTILE_MASK);
        }

        PreparedPair(int[] styled, int[] glow, long revision,
                long[][] pageKnownRows, RevisionStamp stamp) {
            this(styled, glow, revision, pageKnownRows, stamp,
                    MapPageLayout.FULL_SUBTILE_MASK);
        }
    }

    public static final class PreparedSurfaceRegionBatch {
        private final RevisionStamp stamp;
        private final int regionX;
        private final int regionZ;
        private final int batchPageX;
        private final int batchPageZ;
        private final int pagesWide;
        private final int pagesHigh;
        private final long sourceRevision;
        private final PreparedPair[] pages;

        PreparedSurfaceRegionBatch(RevisionStamp stamp, int regionX, int regionZ,
                int batchPageX, int batchPageZ, int pagesWide, int pagesHigh,
                long sourceRevision, PreparedPair[] pages) {
            this.stamp = stamp;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.batchPageX = batchPageX;
            this.batchPageZ = batchPageZ;
            this.pagesWide = pagesWide;
            this.pagesHigh = pagesHigh;
            this.sourceRevision = sourceRevision;
            this.pages = Arrays.copyOf(pages, pages.length);
        }

        public RevisionStamp stamp() { return stamp; }
        public int regionX() { return regionX; }
        public int regionZ() { return regionZ; }
        public int batchPageX() { return batchPageX; }
        public int batchPageZ() { return batchPageZ; }
        public int pagesWide() { return pagesWide; }
        public int pagesHigh() { return pagesHigh; }
        public long sourceRevision() { return sourceRevision; }

        PreparedPair page(int localPageX, int localPageZ) {
            if (localPageX < 0 || localPageX >= pagesWide
                    || localPageZ < 0 || localPageZ >= pagesHigh) return null;
            return pages[localPageZ * pagesWide + localPageX];
        }
    }

    public static final class MapStyleSnapshot {
        private final RevisionStamp stamp;
        private final Biome[] biomes;
        private final java.util.Map<String, Biome> biomesById;
        private final java.util.Map<String, Integer> blockColors;
        private final java.util.Map<String, BlockTintPolicy> tintPolicies;
        private final java.util.Set<String> tintDisabledBlocks;
        private final IntFunction<Biome> biomeLookup;
        private final int colourMode;
        private final boolean showFlowers;
        private final int terrainSlopes;
        private final int profile;

        public MapStyleSnapshot(RevisionStamp stamp, Biome[] biomes,
                java.util.Map<String, Integer> blockColors,
                java.util.Map<String, BlockTintPolicy> tintPolicies,
                java.util.Set<String> tintDisabledBlocks, int colourMode,
                boolean showFlowers, int terrainSlopes, int profile) {
            this(stamp, biomes, blockColors, tintPolicies, tintDisabledBlocks,
                    colourMode, showFlowers, terrainSlopes, profile, false);
        }

        static MapStyleSnapshot takeOwnership(RevisionStamp stamp, Biome[] biomes,
                java.util.Map<String, Integer> blockColors,
                java.util.Map<String, BlockTintPolicy> tintPolicies,
                java.util.Set<String> tintDisabledBlocks, int colourMode,
                boolean showFlowers, int terrainSlopes, int profile) {
            return new MapStyleSnapshot(stamp, biomes, blockColors, tintPolicies,
                    tintDisabledBlocks, colourMode, showFlowers, terrainSlopes,
                    profile, true);
        }

        private MapStyleSnapshot(RevisionStamp stamp, Biome[] biomes,
                java.util.Map<String, Integer> blockColors,
                java.util.Map<String, BlockTintPolicy> tintPolicies,
                java.util.Set<String> tintDisabledBlocks, int colourMode,
                boolean showFlowers, int terrainSlopes, int profile,
                boolean takeOwnership) {
            this.stamp = stamp;
            this.biomes = biomes == null ? new Biome[0]
                    : (takeOwnership ? biomes : biomes.clone());
            this.biomesById = java.util.Map.of();
            this.blockColors = blockColors == null ? java.util.Map.of()
                    : (takeOwnership ? blockColors : java.util.Map.copyOf(blockColors));
            this.tintPolicies = tintPolicies == null ? java.util.Map.of()
                    : (takeOwnership ? tintPolicies : java.util.Map.copyOf(tintPolicies));
            this.tintDisabledBlocks = tintDisabledBlocks == null ? java.util.Set.of()
                    : (takeOwnership ? tintDisabledBlocks
                    : java.util.Set.copyOf(tintDisabledBlocks));
            this.biomeLookup = index -> index >= 0 && index < this.biomes.length
                    ? this.biomes[index] : null;
            this.colourMode = colourMode;
            this.showFlowers = showFlowers;
            this.terrainSlopes = terrainSlopes;
            this.profile = profile;
        }

        static MapStyleSnapshot takeOwnershipById(RevisionStamp stamp,
                java.util.Map<String, Biome> biomesById,
                java.util.Map<String, Integer> blockColors,
                java.util.Map<String, BlockTintPolicy> tintPolicies,
                java.util.Set<String> tintDisabledBlocks, int colourMode,
                boolean showFlowers, int terrainSlopes, int profile) {
            return new MapStyleSnapshot(stamp, biomesById, blockColors,
                    tintPolicies, tintDisabledBlocks, colourMode, showFlowers,
                    terrainSlopes, profile);
        }

        private MapStyleSnapshot(RevisionStamp stamp, java.util.Map<String, Biome> biomesById,
                java.util.Map<String, Integer> blockColors,
                java.util.Map<String, BlockTintPolicy> tintPolicies,
                java.util.Set<String> tintDisabledBlocks, int colourMode,
                boolean showFlowers, int terrainSlopes, int profile) {
            this.stamp = stamp;
            this.biomes = new Biome[0];
            this.biomesById = biomesById == null || biomesById.isEmpty()
                    ? java.util.Map.of() : java.util.Map.copyOf(biomesById);
            this.blockColors = blockColors == null ? java.util.Map.of() : blockColors;
            this.tintPolicies = tintPolicies == null ? java.util.Map.of() : tintPolicies;
            this.tintDisabledBlocks = tintDisabledBlocks == null
                    ? java.util.Set.of() : tintDisabledBlocks;
            this.biomeLookup = index -> null;
            this.colourMode = colourMode;
            this.showFlowers = showFlowers;
            this.terrainSlopes = terrainSlopes;
            this.profile = profile;
        }

        public RevisionStamp stamp() { return stamp; }
        public IntFunction<Biome> biomeLookup() { return biomeLookup; }

        public IntFunction<Biome> biomeLookup(List<String> biomePalette) {
            if (biomesById.isEmpty()) return biomeLookup;
            List<String> palette = biomePalette == null ? List.of() : biomePalette;
            return index -> {
                if (index < 0 || index >= palette.size()) return null;
                String id = palette.get(index);
                if (id == null || id.isBlank()) id = "minecraft:plains";
                return biomesById.get(id);
            };
        }
        public java.util.Map<String, Integer> blockColors() { return blockColors; }
        public java.util.Map<String, BlockTintPolicy> tintPolicies() { return tintPolicies; }
        public java.util.Set<String> tintDisabledBlocks() { return tintDisabledBlocks; }
        public int colourMode() { return colourMode; }
        public boolean showFlowers() { return showFlowers; }
        public int terrainSlopes() { return terrainSlopes; }
        public int profile() { return profile; }
    }

    record PreparedSingle(int[] styled, long revision) {
    }

}

}