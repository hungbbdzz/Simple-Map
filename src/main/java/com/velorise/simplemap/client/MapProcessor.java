package com.velorise.simplemap.client;

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
    private static final int MAX_QUEUED_TASKS = 4096;

    private final PriorityBlockingQueue<Task> queue = new PriorityBlockingQueue<>();
    private final ConcurrentHashMap<String, Task> queued = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    private MapProcessor() {
        Thread worker = new Thread(this::runLoop, "SimpleMap-MapProcessor");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
        worker.start();
    }

    public static MapProcessor getInstance() {
        return INSTANCE;
    }

    public void enqueueSurfaceLoad(int regionX, int regionZ, int priority) {
        long generation = MapManager.getInstance().getGeneration();
        enqueue(new Task("surface:" + generation + ':' + regionX + ',' + regionZ,
                Kind.SURFACE_LOAD, generation, Integer.MIN_VALUE,
                regionX, regionZ, priority, sequence.getAndIncrement()));
    }

    public void enqueueCaveLoad(int layerY, int regionX, int regionZ, int priority) {
        long generation = CaveMapManager.getInstance().getLayerGeneration();
        enqueue(new Task("cave:" + generation + ':' + layerY + ':' + regionX + ',' + regionZ,
                Kind.CAVE_LOAD, generation, layerY,
                regionX, regionZ, priority, sequence.getAndIncrement()));
    }

    public void enqueueFullCaveLoad(int regionX, int regionZ, int priority) {
        long generation = FullCaveMapManager.getInstance().getGeneration();
        enqueue(new Task("full:" + generation + ':' + regionX + ',' + regionZ,
                Kind.FULL_CAVE_LOAD, generation, Integer.MIN_VALUE,
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

    private void enqueue(Task task) {
        if (!queued.containsKey(task.key) && queued.size() >= MAX_QUEUED_TASKS) {
            dropLowestPriorityTask(task.priority);
            if (queued.size() >= MAX_QUEUED_TASKS) return;
        }
        queued.compute(task.key, (key, existing) -> {
            if (existing == null) {
                queue.offer(task);
                return task;
            }
            if (task.priority > existing.priority && queue.remove(existing)) {
                queue.offer(task);
                return task;
            }
            return existing;
        });
    }

    private void dropLowestPriorityTask(int incomingPriority) {
        Task worst = null;
        for (Task candidate : queued.values()) {
            if (worst == null || candidate.priority < worst.priority
                    || (candidate.priority == worst.priority && candidate.sequence > worst.sequence)) {
                worst = candidate;
            }
        }
        if (worst == null || worst.priority >= incomingPriority) return;
        if (queued.remove(worst.key, worst)) queue.remove(worst);
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
        switch (task.kind) {
            case SURFACE_LOAD -> {
                MapManager manager = MapManager.getInstance();
                if (manager.isGenerationCurrent(task.generation)) {
                    manager.requestRegionLoad(task.regionX, task.regionZ);
                }
            }
            case CAVE_LOAD -> {
                CaveMapManager manager = CaveMapManager.getInstance();
                if (manager.isLayerGenerationCurrent(task.generation, task.layerY)) {
                    manager.requestVisibleRegion(task.layerY, task.regionX, task.regionZ);
                }
            }
            case FULL_CAVE_LOAD -> {
                FullCaveMapManager manager = FullCaveMapManager.getInstance();
                if (manager.isGenerationCurrent(task.generation)) {
                    manager.getRegion(task.regionX, task.regionZ, true);
                }
            }
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
        private final long generation;
        private final int layerY;
        private final int regionX;
        private final int regionZ;
        private final int priority;
        private final long sequence;

        private Task(String key, Kind kind, long generation, int layerY,
                int regionX, int regionZ, int priority, long sequence) {
            this.key = Objects.requireNonNull(key);
            this.kind = Objects.requireNonNull(kind);
            this.generation = generation;
            this.layerY = layerY;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.priority = priority;
            this.sequence = sequence;
        }

        @Override
        public int compareTo(Task other) {
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
