package com.velorise.simplemap.client;

import com.velorise.simplemap.client.session.MapSession;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import com.velorise.simplemap.client.pipeline.MapWorkKey;
import com.velorise.simplemap.client.pipeline.MapWorkStage;
import com.velorise.simplemap.client.pipeline.RegionRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Distance-prioritized data request scheduler.
 *
 * This worker never creates, registers, uploads or releases GPU textures.
 * Render-thread texture work remains inside the texture managers.
 */
public final class MapProcessor {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapProcessor INSTANCE = new MapProcessor();

    private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<String, Task> queued = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

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
                Kind.SURFACE_LOAD, session.sessionId(), generation, Integer.MIN_VALUE,
                regionX, regionZ, priority, sequence.getAndIncrement()));
    }

    public void enqueueCaveLoad(int layerY, int regionX, int regionZ, int priority) {
        MapSession session = MapSessionManager.getInstance().active();
        if (session == null || session.rootToken().isCancelled()) return;
        long generation = CaveMapManager.getInstance().getLayerGeneration();
        int bandY = com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                com.velorise.simplemap.client.cave.CaveView.LAYERED, layerY);
        enqueue(new Task("cave:" + session.sessionId() + ':' + generation + ':' + bandY + ':' + regionX + ',' + regionZ,
                Kind.CAVE_LOAD, session.sessionId(), generation, layerY,
                regionX, regionZ, priority, sequence.getAndIncrement()));
    }

    public void enqueueFullCaveLoad(int regionX, int regionZ, int priority) {
        MapSession session = MapSessionManager.getInstance().active();
        if (session == null || session.rootToken().isCancelled()) return;
        long generation = FullCaveMapManager.getInstance().getGeneration();
        enqueue(new Task("full:" + session.sessionId() + ':' + generation + ':' + regionX + ',' + regionZ,
                Kind.FULL_CAVE_LOAD, session.sessionId(), generation, Integer.MIN_VALUE,
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
        queue.clear();
        queued.clear();
    }

    /** Drops queued surface disk requests when the fullscreen viewport changes. */
    public void clearSurfaceLoads() {
        for (Task task : queued.values()) {
            if (task.kind != Kind.SURFACE_LOAD) continue;
            if (queued.remove(task.key, task)) queue.remove(task);
        }
    }

    private void enqueue(Task task) {
        MapWorkGraph.Admission admission = MapWorkGraph.getInstance()
                .request(task.workKey, task.generation);
        if (admission != MapWorkGraph.Admission.ACCEPTED) return;
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
        if (lease == null) return;
        boolean admitted = false;
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
                        if (activeTopY != Integer.MIN_VALUE) {
                            // Exact Top-Y may have moved inside the retained band while
                            // this serial disk admission waited. Load into the current
                            // projection instead of dropping useful same-band work.
                            completionOwnedByManager = manager.requestVisibleRegion(
                                    task.workKey, task.generation, activeTopY);
                        }
                    }
                }
                case FULL_CAVE_LOAD -> {
                    FullCaveMapManager manager = FullCaveMapManager.getInstance();
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
            } else if (admitted) MapWorkGraph.getInstance().complete(lease);
            else MapWorkGraph.getInstance().defer(lease);
        }
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

        private Task(String key, Kind kind, long sessionId, long generation, int layerY,
                int regionX, int regionZ, int priority, long sequence) {
            this.key = Objects.requireNonNull(key);
            this.kind = Objects.requireNonNull(kind);
            this.sessionId = sessionId;
            this.generation = generation;
            this.layerY = layerY;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.priority = priority;
            this.sequence = sequence;
            int projectionId = switch (kind) {
                case CAVE_LOAD -> com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                        com.velorise.simplemap.client.cave.CaveView.LAYERED, layerY);
                case SURFACE_LOAD, FULL_CAVE_LOAD -> Integer.MIN_VALUE;
            };
            this.workKey = new MapWorkKey(sessionId, regionX, regionZ,
                    switch (kind) {
                        case SURFACE_LOAD -> MapWorkStage.SOURCE_READ;
                        case CAVE_LOAD -> MapWorkStage.CAVE_PROJECTION;
                        case FULL_CAVE_LOAD -> MapWorkStage.FULL_CAVE_PROJECTION;
                    }, projectionId);
        }

        @Override
        public int compareTo(Task other) {
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
