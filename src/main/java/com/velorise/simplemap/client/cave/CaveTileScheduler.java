package com.velorise.simplemap.client.cave;

import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;

/** Client-thread scheduler that completes centre-first chunk tiles transactionally. */
public final class CaveTileScheduler {
    public enum Lane {
        BACKGROUND(0),
        REVALIDATE(1),
        FOREGROUND(2),
        VIEWPORT(3);

        private final int rank;

        Lane(int rank) {
            this.rank = rank;
        }
    }

    private static final int MAX_TASKS = 4096;
    private static final int MAX_HEAP_TASKS = MAX_TASKS * 4;
    private static final long VIEWPORT_REFRESH_NANOS = 200_000_000L;

    private final CaveTileRepository repository;
    private final CaveTileScanner scanner;
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private final Map<Long, Task> queued = new HashMap<>();
    private long sequence;

    /* Viewport tasks are disposable. When the visible chunk rectangle changes, old
     * map-screen tasks must not continue competing with the new centre of interest. */
    private long viewportGeneration = 1L;
    private int viewportMinX = Integer.MIN_VALUE;
    private int viewportMaxX = Integer.MIN_VALUE;
    private int viewportMinZ = Integer.MIN_VALUE;
    private int viewportMaxZ = Integer.MIN_VALUE;
    private long lastViewportEnqueueNanos;

    private int foregroundCenterX = Integer.MIN_VALUE;
    private int foregroundCenterZ = Integer.MIN_VALUE;
    private int foregroundRadius = -1;
    private long foregroundEnqueueTick = Long.MIN_VALUE;

    private int backgroundCenterX = Integer.MIN_VALUE;
    private int backgroundCenterZ = Integer.MIN_VALUE;
    private int backgroundRadius = -1;
    private long backgroundEnqueueTick = Long.MIN_VALUE;

    public CaveTileScheduler(CaveTileRepository repository, CaveTileScanner scanner) {
        this.repository = repository;
        this.scanner = scanner;
    }

    public void reset() {
        queue.clear();
        queued.clear();
        viewportGeneration++;
        viewportMinX = viewportMaxX = viewportMinZ = viewportMaxZ = Integer.MIN_VALUE;
        lastViewportEnqueueNanos = 0L;
        foregroundCenterX = foregroundCenterZ = Integer.MIN_VALUE;
        foregroundRadius = -1;
        foregroundEnqueueTick = Long.MIN_VALUE;
        backgroundCenterX = backgroundCenterZ = Integer.MIN_VALUE;
        backgroundRadius = -1;
        backgroundEnqueueTick = Long.MIN_VALUE;
    }

    public int queuedTaskCount() {
        return queued.size();
    }

    public void enqueue(int chunkX, int chunkZ, int priority) {
        enqueueInternal(chunkX, chunkZ, priority, Lane.FOREGROUND, 0L);
    }

    public void enqueueBackground(int chunkX, int chunkZ, int priority) {
        enqueueInternal(chunkX, chunkZ, priority, Lane.BACKGROUND, 0L);
    }

    public void enqueueRevalidation(int chunkX, int chunkZ, int priority) {
        enqueueInternal(chunkX, chunkZ, priority, Lane.REVALIDATE, 0L);
    }

    private void enqueueInternal(int chunkX, int chunkZ, int priority,
            Lane lane, long expectedViewportGeneration) {
        CaveChunkTile loaded = repository.getLoadedTile(chunkX, chunkZ);
        if (loaded != null && !loaded.needsScanWork()) return;
        long key = CaveTileRepository.pack(chunkX, chunkZ);
        Task existing = queued.get(key);
        if (existing != null) {
            boolean newerViewport = lane == Lane.VIEWPORT
                    && (existing.lane != Lane.VIEWPORT
                            || existing.viewportGeneration != expectedViewportGeneration);
            boolean strongerLane = lane.rank > existing.lane.rank;
            if (!newerViewport && !strongerLane && priority <= existing.priority) return;
            // Lazy cancellation: replace the map entry and leave the old heap node.
            // Polling checks identity before processing, avoiding PriorityQueue.remove()
            // which is O(n) and became expensive while panning large viewports.
        } else if (queued.size() >= MAX_TASKS) {
            dropWorst(lane, priority);
            if (queued.size() >= MAX_TASKS) return;
        }
        Task task = new Task(key, chunkX, chunkZ, priority,
                repository.generation(), sequence++, lane,
                lane == Lane.VIEWPORT ? expectedViewportGeneration : 0L);
        queued.put(key, task);
        queue.offer(task);
        if (queue.size() > MAX_HEAP_TASKS) compactHeap();
    }

    public void enqueueAround(Level level, int centerChunkX, int centerChunkZ,
            int chunkRadius, int basePriority, Lane lane) {
        int safeRadius = Math.max(0, chunkRadius);
        if (!shouldRefreshAround(level, centerChunkX, centerChunkZ, safeRadius, lane)) return;
        for (int ring = 0; ring <= safeRadius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    if (!level.hasChunk(chunkX, chunkZ)) continue;
                    int distance = dx * dx + dz * dz;
                    enqueueInternal(chunkX, chunkZ, basePriority - distance * 100,
                            lane, 0L);
                }
            }
        }
    }

    public void enqueueViewport(Level level, int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ, double centerChunkX, double centerChunkZ,
            int basePriority) {
        boolean changed = updateViewportGeneration(
                minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        long now = System.nanoTime();
        if (!changed && now - lastViewportEnqueueNanos < VIEWPORT_REFRESH_NANOS) return;
        lastViewportEnqueueNanos = now;
        long currentViewportGeneration = viewportGeneration;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!level.hasChunk(chunkX, chunkZ)) continue;
                double dx = chunkX - centerChunkX;
                double dz = chunkZ - centerChunkZ;
                int distance = (int) Math.min(100_000.0, (dx * dx + dz * dz) * 100.0);
                enqueueInternal(chunkX, chunkZ, basePriority - distance,
                        Lane.VIEWPORT, currentViewportGeneration);
            }
        }
    }

    public int process(Level level, long deadlineNanos) {
        int columns = 0;
        while (System.nanoTime() < deadlineNanos) {
            Task task = queue.poll();
            if (task == null) break;
            if (!queued.remove(task.key, task)) continue;
            if (!repository.isGenerationCurrent(task.generation)
                    || (task.lane == Lane.VIEWPORT
                            && task.viewportGeneration != viewportGeneration)
                    || !level.hasChunk(task.chunkX, task.chunkZ)) continue;

            CaveChunkTile tile = repository.getOrCreateLiveTile(task.chunkX, task.chunkZ);
            CaveTileScanContext context = CaveTileScanContext.create(
                    level, task.chunkX, task.chunkZ);
            if (context == null) continue;

            int processedThisTask = 0;
            boolean tileChanged = false;
            while (System.nanoTime() < deadlineNanos) {
                int column = tile.nextPendingColumn();
                if (column < 0) break;
                boolean revalidation = tile.isColumnScanned(column & 15, column >>> 4);
                int blockX = (task.chunkX << 4) + (column & 15);
                int blockZ = (task.chunkZ << 4) + (column >>> 4);
                long started = System.nanoTime();
                CaveColumnData data = scanner.scanColumn(
                        level, blockX, blockZ, context);
                long elapsed = System.nanoTime() - started;
                if (data == null) break;
                boolean changed = repository.commitColumnDeferred(tile, column, data);
                tileChanged |= changed;
                telemetry.recordColumnScan(elapsed, revalidation, changed);
                columns++;
                processedThisTask++;
                // Finish coherent tile bursts, but yield near the frame deadline.
                if (processedThisTask >= 64
                        && System.nanoTime() + 150_000L >= deadlineNanos) break;
            }
            if (tileChanged) repository.publishTileChanges(tile);
            if (tile.needsScanWork()) {
                /*
                 * A partially completed tile is cheaper and visually more useful to
                 * finish than starting another tile. Promote continuation according
                 * to the number of committed columns. Background/revalidation work
                 * cannot outrank visible foreground or viewport lanes.
                 */
                int continuationBoost = Math.min(12_000,
                        tile.scannedColumnCount() * 40);
                enqueueInternal(task.chunkX, task.chunkZ,
                        Math.max(1, task.priority + continuationBoost),
                        task.lane, task.viewportGeneration);
            }
        }
        return columns;
    }

    private boolean updateViewportGeneration(int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ) {
        if (minChunkX == viewportMinX && maxChunkX == viewportMaxX
                && minChunkZ == viewportMinZ && maxChunkZ == viewportMaxZ) return false;
        viewportMinX = minChunkX;
        viewportMaxX = maxChunkX;
        viewportMinZ = minChunkZ;
        viewportMaxZ = maxChunkZ;
        viewportGeneration++;

        Iterator<Map.Entry<Long, Task>> iterator = queued.entrySet().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next().getValue();
            if (task.lane != Lane.VIEWPORT) continue;
            iterator.remove();
        }
        return true;
    }

    private boolean shouldRefreshAround(Level level, int centerChunkX, int centerChunkZ,
            int radius, Lane lane) {
        long tick = level == null ? 0L : level.getGameTime();
        if (lane == Lane.FOREGROUND) {
            boolean same = centerChunkX == foregroundCenterX
                    && centerChunkZ == foregroundCenterZ
                    && radius == foregroundRadius;
            if (same && foregroundEnqueueTick != Long.MIN_VALUE
                    && tick - foregroundEnqueueTick < 10L) return false;
            foregroundCenterX = centerChunkX;
            foregroundCenterZ = centerChunkZ;
            foregroundRadius = radius;
            foregroundEnqueueTick = tick;
            return true;
        }
        if (lane == Lane.BACKGROUND) {
            boolean same = centerChunkX == backgroundCenterX
                    && centerChunkZ == backgroundCenterZ
                    && radius == backgroundRadius;
            if (same && backgroundEnqueueTick != Long.MIN_VALUE
                    && tick - backgroundEnqueueTick < 20L) return false;
            backgroundCenterX = centerChunkX;
            backgroundCenterZ = centerChunkZ;
            backgroundRadius = radius;
            backgroundEnqueueTick = tick;
            return true;
        }
        return true;
    }

    private void compactHeap() {
        queue.clear();
        queue.addAll(queued.values());
    }

    private void dropWorst(Lane incomingLane, int incomingPriority) {
        Task worst = null;
        for (Task task : queued.values()) {
            if (worst == null || task.lane.rank < worst.lane.rank
                    || (task.lane == worst.lane && task.priority < worst.priority)
                    || (task.lane == worst.lane && task.priority == worst.priority
                            && task.sequence > worst.sequence)) {
                worst = task;
            }
        }
        if (worst == null) return;
        if (worst.lane.rank > incomingLane.rank) return;
        if (worst.lane == incomingLane && worst.priority >= incomingPriority) return;
        queued.remove(worst.key, worst);
    }

    private static final class Task implements Comparable<Task> {
        private final long key;
        private final int chunkX;
        private final int chunkZ;
        private final int priority;
        private final long generation;
        private final long sequence;
        private final Lane lane;
        private final long viewportGeneration;

        private Task(long key, int chunkX, int chunkZ, int priority,
                long generation, long sequence, Lane lane,
                long viewportGeneration) {
            this.key = key;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.priority = priority;
            this.generation = generation;
            this.sequence = sequence;
            this.lane = lane;
            this.viewportGeneration = viewportGeneration;
        }

        @Override
        public int compareTo(Task other) {
            int laneOrder = Integer.compare(other.lane.rank, lane.rank);
            if (laneOrder != 0) return laneOrder;
            int priorityOrder = Integer.compare(other.priority, priority);
            return priorityOrder != 0 ? priorityOrder : Long.compare(sequence, other.sequence);
        }
    }
}
