package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Client-thread scheduler for transactional dense cave tiles.
 *
 * <p>Initial loads remain 256-column atomic transactions. Known LIVE tiles can be
 * patched by a coalesced set of dirty columns; the old tile remains renderable until
 * the patch snapshot is validated and committed. A patch never seeds from DISK or
 * WORLD_SAVE authority, because doing so would falsely promote unchanged columns to
 * LIVE.</p>
 */
final class CaveDisplayScheduler {
    private static final int MAX_TASKS = 512;
    private static final int COLUMN_BURST = 12;
    private static final long VIEWPORT_REFRESH_NANOS = 150_000_000L;
    private static final long FULLSCREEN_PAGE_STALL_NANOS = 2_000_000_000L;

    private final CaveTileRepository repository;
    private final CaveChunkReadinessTracker readiness;
    private final CaveDisplayProjector projector = new CaveDisplayProjector();
    private final PriorityQueue<Task> queue = new PriorityQueue<>();
    private final PriorityQueue<Task> deferred = new PriorityQueue<>(
            Comparator.comparingLong((Task task) -> task.retryAfterTick)
                    .thenComparingLong(task -> task.sequence));
    private final Map<TaskKey, Task> queued = new HashMap<>();
    private long sequence;
    private CaveView activeView;
    /** Retained 16-block identity. */
    private int activeLayerY = Integer.MIN_VALUE;
    /** Exact Top-Y currently requested inside the active band. */
    private int activeProjectionTopY = Integer.MIN_VALUE;
    private final EnumMap<MapRequestLane, ViewportState> viewports =
            new EnumMap<>(MapRequestLane.class);

    CaveDisplayScheduler(CaveTileRepository repository,
            CaveChunkReadinessTracker readiness) {
        this.repository = repository;
        this.readiness = readiness;
        for (MapRequestLane lane : MapRequestLane.values()) {
            viewports.put(lane, new ViewportState());
        }
    }

    void reset() {
        queue.clear();
        queued.clear();
        deferred.clear();
        activeView = null;
        activeLayerY = Integer.MIN_VALUE;
        activeProjectionTopY = Integer.MIN_VALUE;
        for (ViewportState state : viewports.values()) state.clear();
    }

    int queuedTaskCount() {
        return queued.size();
    }

    /** Cancels only unpublished work after a teleport/world packet transition. */
    void cancelInFlight() {
        for (Task task : queued.values()) task.cancelled = true;
        queue.clear();
        queued.clear();
        deferred.clear();
        for (ViewportState state : viewports.values()) state.clear();
    }

    void cancelChunk(int chunkX, int chunkZ) {
        var iterator = queued.entrySet().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next().getValue();
            if (task.key.chunkX() != chunkX || task.key.chunkZ() != chunkZ) continue;
            task.cancelled = true;
            iterator.remove();
        }
    }

    void enqueueAround(Level level, CaveView view, int layerY,
            int centerChunkX, int centerChunkZ, int radius, int basePriority) {
        activateMode(view, layerY);
        int safeRadius = Math.max(0, radius);
        for (int ring = 0; ring <= safeRadius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;
                    if (!level.hasChunk(chunkX, chunkZ)) continue;
                    int distance = dx * dx + dz * dz;
                    enqueue(chunkX, chunkZ, view, layerY,
                            basePriority - distance * 100, null, -1, false);
                }
            }
        }
    }

    void enqueueViewport(Level level, CaveView view, int layerY,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            double centerChunkX, double centerChunkZ, int basePriority,
            MapRequestLane lane) {
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        activateMode(view, layerY);
        ViewportState state = viewports.get(effectiveLane);
        long now = System.nanoTime();
        boolean changed = !state.matchesShape(view, normalizedLayer, layerY,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        if (!changed && now - state.lastEnqueueNanos < VIEWPORT_REFRESH_NANOS) return;
        state.update(view, normalizedLayer, layerY,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ, now, changed);

        if (changed) {
            var iterator = queued.entrySet().iterator();
            while (iterator.hasNext()) {
                Task task = iterator.next().getValue();
                if (!task.viewportDemand || task.persistentDemand || task.hasStarted()
                        || wantedByAnyViewport(task, now)) continue;
                task.cancelled = true;
                iterator.remove();
            }
        }

        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            /*
             * Live cave projection follows the same 64x64 page frontier as saved
             * source reconstruction and exact publication. Only one loaded 4x4
             * chunk page is admitted per refresh. This replaces the old centre-out
             * enqueue of every loaded chunk in the viewport, which produced random
             * islands and large duplicate queues.
             */
            int minPageX = Math.floorDiv(minChunkX, 4);
            int maxPageX = Math.floorDiv(maxChunkX, 4);
            int minPageZ = Math.floorDiv(minChunkZ, 4);
            int maxPageZ = Math.floorDiv(maxChunkZ, 4);
            int width = Math.max(0, maxPageX - minPageX + 1);
            int height = Math.max(0, maxPageZ - minPageZ + 1);
            int totalPages = width * height;
            if (totalPages <= 0) return;
            if (state.fullscreenPageCursor >= totalPages) {
                state.fullscreenPageCursor = 0;
                state.completedCycles++;
            }
            if (state.pageOpenedNanos == 0L) state.pageOpenedNanos = now;

            int ordinal = state.fullscreenPageCursor;
            int pageX = minPageX + ordinal % width;
            int pageZ = minPageZ + ordinal / width;
            int firstChunkX = pageX * 4;
            int firstChunkZ = pageZ * 4;
            boolean pageSettled = true;
            boolean pageHasLoadedChunks = false;
            for (int localZ = 0; localZ < 4; localZ++) {
                for (int localX = 0; localX < 4; localX++) {
                    int chunkX = firstChunkX + localX;
                    int chunkZ = firstChunkZ + localZ;
                    if (chunkX < minChunkX || chunkX > maxChunkX
                            || chunkZ < minChunkZ || chunkZ > maxChunkZ
                            || !level.hasChunk(chunkX, chunkZ)) continue;
                    pageHasLoadedChunks = true;
                    if (repository.hasFreshDisplayTileSource(view, layerY,
                            chunkX, chunkZ, DenseCaveTile.Source.LIVE)) continue;
                    pageSettled = false;
                    int localOrdinal = localZ * 4 + localX;
                    enqueue(chunkX, chunkZ, view, layerY,
                            basePriority + 220_000 - localOrdinal * 1_000,
                            effectiveLane, -1, false);
                }
            }
            if (pageSettled || !pageHasLoadedChunks
                    || now - state.pageOpenedNanos >= FULLSCREEN_PAGE_STALL_NANOS) {
                state.fullscreenPageCursor++;
                state.pageOpenedNanos = 0L;
            }
            return;
        }

        int centerX = (int) Math.floor(centerChunkX);
        int centerZ = (int) Math.floor(centerChunkZ);
        int maximumRing = Math.max(
                Math.max(Math.abs(centerX - minChunkX), Math.abs(maxChunkX - centerX)),
                Math.max(Math.abs(centerZ - minChunkZ), Math.abs(maxChunkZ - centerZ)));
        for (int ring = 0; ring <= maximumRing; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int chunkX = centerX + dx;
                    int chunkZ = centerZ + dz;
                    if (chunkX < minChunkX || chunkX > maxChunkX
                            || chunkZ < minChunkZ || chunkZ > maxChunkZ
                            || !level.hasChunk(chunkX, chunkZ)) continue;
                    double exactDx = chunkX - centerChunkX;
                    double exactDz = chunkZ - centerChunkZ;
                    int distance = (int) Math.min(900_000.0,
                            (exactDx * exactDx + exactDz * exactDz) * 1_000.0);
                    enqueue(chunkX, chunkZ, view, layerY,
                            basePriority - distance, effectiveLane, -1, false);
                }
            }
        }
    }

    /** Normal viewport demand: build a full tile only when no patch already owns it. */
    void enqueue(int chunkX, int chunkZ, CaveView view, int layerY, int priority) {
        enqueue(chunkX, chunkZ, view, layerY, priority, null, -1, false);
    }

    /** Authoritative chunk/light/manual refresh: any pending patch must become full. */
    void enqueueReplacement(int chunkX, int chunkZ, CaveView view, int layerY,
            int priority) {
        enqueue(chunkX, chunkZ, view, layerY, priority, null, -1, true);
    }

    void enqueuePatch(int chunkX, int chunkZ, CaveView view, int layerY,
            int localX, int localZ, int priority) {
        enqueue(chunkX, chunkZ, view, layerY, priority, null,
                DenseCaveTile.index(localX, localZ), false);
    }

    private void enqueue(int chunkX, int chunkZ, CaveView view, int layerY,
            int priority, MapRequestLane viewportLane, int patchColumn,
            boolean forceFullReplacement) {
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        boolean fullProjection = patchColumn < 0;
        if (fullProjection && repository.hasFreshDisplayTileSource(view,
                layerY, chunkX, chunkZ, DenseCaveTile.Source.LIVE)) return;

        TaskKey key = new TaskKey(chunkX, chunkZ, view, normalizedLayer);
        Task existing = queued.get(key);
        if (existing != null) {
            boolean retargeted = existing.projectionTopY != layerY;
            boolean priorityChanged = priority > existing.priority;
            boolean wasDeferred = deferred.remove(existing);
            boolean wasReady = queue.remove(existing);
            if (retargeted) {
                // Same 16-block band: retain page/atlas/LOD identity, but restart
                // this unpublished transaction for the new exact Top-Y.
                existing.retarget(layerY);
                existing.retryAfterTick = 0L;
            }
            existing.priority = Math.max(existing.priority, priority);
            if (fullProjection) {
                // Viewport demand must not destroy an incremental dirty-column patch.
                // An exact Top-Y retarget or authoritative replacement is full-tile.
                if (retargeted || forceFullReplacement) existing.upgradeToFullProjection();
            } else if (!retargeted) {
                existing.addPatchColumn(patchColumn);
            }
            if (viewportLane != null) {
                existing.viewportDemand = true;
            } else {
                existing.persistentDemand = true;
            }
            if (retargeted) {
                existing.sequence = sequence++;
                queue.offer(existing);
            } else if (wasDeferred) {
                deferred.offer(existing);
            } else if (wasReady || priorityChanged) {
                existing.sequence = sequence++;
                queue.offer(existing);
            }
            return;
        }
        if (queued.size() >= MAX_TASKS) return;
        Task task = new Task(key, layerY, priority, sequence++, repository.generation(),
                viewportLane != null, fullProjection);
        if (!fullProjection) task.addPatchColumn(patchColumn);
        queued.put(key, task);
        queue.offer(task);
    }

    int process(Level level, long deadlineNanos) {
        int columns = 0;
        long gameTick = level.getGameTime();
        while (System.nanoTime() < deadlineNanos) {
            Task task = pollReady(gameTick);
            if (task == null) break;
            boolean viewportRelevant = task.persistentDemand
                    || !task.viewportDemand
                    || task.hasStarted()
                    || wantedByAnyViewport(task, System.nanoTime());
            if (!repository.isGenerationCurrent(task.repositoryGeneration)
                    || !viewportRelevant) {
                continue;
            }
            if (task.fullProjection
                    && repository.hasFreshDisplayTileSource(task.key.view(),
                            task.projectionTopY, task.key.chunkX(), task.key.chunkZ(),
                            DenseCaveTile.Source.LIVE)) {
                continue;
            }

            if (task.snapshot == null) {
                task.snapshot = readiness.acquire(level,
                        task.key.chunkX(), task.key.chunkZ());
                if (task.snapshot == null) {
                    defer(task, gameTick, false);
                    continue;
                }
                task.source = new LiveCaveChunkSource(task.snapshot);
                if (task.fullProjection) {
                    task.builder = new DenseCaveTile.Builder();
                } else {
                    DenseCaveTile seed = repository.getLoadedDisplayTile(
                            task.key.view(), task.key.layerY(),
                            task.key.chunkX(), task.key.chunkZ());
                    if (seed == null || seed.source() != DenseCaveTile.Source.LIVE
                            || (task.key.view() != CaveView.FULL
                                    && seed.projectionTopY() != task.projectionTopY)) {
                        task.promoteToFullUsingCurrentSnapshot();
                        task.builder = new DenseCaveTile.Builder();
                    } else {
                        task.seedRevision = seed.revision();
                        task.builder = new DenseCaveTile.Builder(seed);
                    }
                }
            } else if (!readiness.stillValid(level, task.snapshot)) {
                defer(task, gameTick, true);
                continue;
            }

            int burst = 0;
            while (task.hasRemainingColumns() && burst < COLUMN_BURST
                    && System.nanoTime() < deadlineNanos) {
                int column = task.takeNextColumn();
                if (column < 0) break;
                projector.projectColumn(task.source, task.key.view(), task.projectionTopY,
                        column & 15, column >>> 4, task.builder);
                columns++;
                burst++;
            }

            if (!task.hasRemainingColumns()) {
                if (!readiness.stillValid(level, task.snapshot)) {
                    defer(task, gameTick, true);
                    continue;
                }
                if (!task.fullProjection && !repository.isCurrentDisplayTileRevision(
                        task.key.view(), task.key.layerY(), task.key.chunkX(),
                        task.key.chunkZ(), task.seedRevision, DenseCaveTile.Source.LIVE)) {
                    // A newer tile committed while this patch was being assembled.
                    // Restart from that tile instead of overwriting unrelated columns.
                    defer(task, gameTick, true);
                    continue;
                }
                DenseCaveTile tile = task.builder.build(task.key.chunkX(),
                        task.key.chunkZ(), task.key.view(), task.key.layerY(),
                        task.projectionTopY, System.nanoTime(), DenseCaveTile.Source.LIVE);
                repository.commitDisplayTile(tile, task.repositoryGeneration);
            } else {
                task.priority += 2_000;
                requeue(task);
            }
        }
        return columns;
    }

    private Task pollReady(long gameTick) {
        promoteDeferred(gameTick);
        return pollValid();
    }

    private void promoteDeferred(long gameTick) {
        while (true) {
            Task task = deferred.peek();
            if (task == null || task.retryAfterTick > gameTick) return;
            deferred.poll();
            if (task.cancelled || queued.get(task.key) != task) continue;
            queue.offer(task);
        }
    }

    private void defer(Task task, long gameTick, boolean restartProjection) {
        if (restartProjection) task.restartProjection();
        task.retryAfterTick = gameTick + CaveChunkReadinessTracker.RETRY_DELAY_TICKS;
        task.priority = Math.max(Integer.MIN_VALUE + 10_000, task.priority - 4_000);
        task.sequence = sequence++;
        queued.put(task.key, task);
        deferred.offer(task);
    }

    private void requeue(Task task) {
        task.sequence = sequence++;
        queued.put(task.key, task);
        queue.offer(task);
    }

    private void activateMode(CaveView view, int layerY) {
        int normalized = DenseCaveTile.normalizeLayer(view, layerY);
        if (view == activeView && normalized == activeLayerY) {
            activeProjectionTopY = layerY;
            return;
        }
        activeView = view;
        activeLayerY = normalized;
        activeProjectionTopY = layerY;
        for (ViewportState state : viewports.values()) state.clear();

        // Do not clear all work. Transactions that already started may finish and
        // publish into their retained band; idle viewport tasks from another band
        // are cancelled lazily without touching resident textures.
        var iterator = queued.entrySet().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next().getValue();
            if (!task.viewportDemand || task.persistentDemand || task.hasStarted()) continue;
            if (task.key.view() == view && task.key.layerY() == normalized) continue;
            task.cancelled = true;
            iterator.remove();
        }
    }

    private boolean wantedByAnyViewport(Task task, long nowNanos) {
        for (Map.Entry<MapRequestLane, ViewportState> entry : viewports.entrySet()) {
            MapRequestLane lane = entry.getKey();
            ViewportState state = entry.getValue();
            if (state.isFresh(nowNanos, lane) && state.contains(task)) return true;
        }
        return false;
    }

    private static final class ViewportState {
        private CaveView view;
        private int layerY = Integer.MIN_VALUE;
        private int projectionTopY = Integer.MIN_VALUE;
        private int minChunkX = Integer.MIN_VALUE;
        private int maxChunkX = Integer.MIN_VALUE;
        private int minChunkZ = Integer.MIN_VALUE;
        private int maxChunkZ = Integer.MIN_VALUE;
        private long lastEnqueueNanos;
        private int fullscreenPageCursor;
        private long pageOpenedNanos;
        private long completedCycles;

        private boolean matchesShape(CaveView view, int layerY, int projectionTopY,
                int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
            return this.view == view && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && this.minChunkX == minChunkX && this.maxChunkX == maxChunkX
                    && this.minChunkZ == minChunkZ && this.maxChunkZ == maxChunkZ;
        }

        private void update(CaveView view, int layerY, int projectionTopY,
                int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                long nowNanos, boolean changed) {
            if (changed) {
                fullscreenPageCursor = 0;
                pageOpenedNanos = 0L;
                completedCycles = 0L;
            }
            this.view = view;
            this.layerY = layerY;
            this.projectionTopY = projectionTopY;
            this.minChunkX = minChunkX;
            this.maxChunkX = maxChunkX;
            this.minChunkZ = minChunkZ;
            this.maxChunkZ = maxChunkZ;
            this.lastEnqueueNanos = nowNanos;
        }

        private boolean contains(Task task) {
            return task.key.view() == view && task.key.layerY() == layerY
                    && task.projectionTopY == projectionTopY
                    && task.key.chunkX() >= minChunkX - 1
                    && task.key.chunkX() <= maxChunkX + 1
                    && task.key.chunkZ() >= minChunkZ - 1
                    && task.key.chunkZ() <= maxChunkZ + 1;
        }

        private boolean isFresh(long nowNanos, MapRequestLane lane) {
            return lastEnqueueNanos != 0L
                    && nowNanos - lastEnqueueNanos
                            <= lane.requestTtlMs() * 1_000_000L;
        }

        private void clear() {
            view = null;
            layerY = Integer.MIN_VALUE;
            projectionTopY = Integer.MIN_VALUE;
            minChunkX = maxChunkX = minChunkZ = maxChunkZ = Integer.MIN_VALUE;
            lastEnqueueNanos = 0L;
            fullscreenPageCursor = 0;
            pageOpenedNanos = 0L;
            completedCycles = 0L;
        }
    }

    private Task pollValid() {
        while (true) {
            Task task = queue.poll();
            if (task == null) return null;
            if (task.cancelled) continue;
            if (!queued.remove(task.key, task)) continue;
            return task;
        }
    }

    private record TaskKey(int chunkX, int chunkZ, CaveView view, int layerY) {
    }

    private static final class Task implements Comparable<Task> {
        private final TaskKey key;
        private final long repositoryGeneration;
        private int projectionTopY;
        private final long[] requestedColumns = new long[4];
        private final long[] completedColumns = new long[4];
        private boolean viewportDemand;
        private boolean persistentDemand;
        private DenseCaveTile.Builder builder;
        private int priority;
        private long sequence;
        private int nextFullColumn;
        private int patchCursor;
        private int processedColumns;
        private long retryAfterTick;
        private long seedRevision;
        private CaveChunkReadinessTracker.Snapshot snapshot;
        private LiveCaveChunkSource source;
        private boolean fullProjection;
        private boolean cancelled;

        private Task(TaskKey key, int projectionTopY, int priority, long sequence,
                long repositoryGeneration, boolean viewportDemand,
                boolean fullProjection) {
            this.key = key;
            this.projectionTopY = projectionTopY;
            this.priority = priority;
            this.sequence = sequence;
            this.repositoryGeneration = repositoryGeneration;
            this.viewportDemand = viewportDemand;
            this.persistentDemand = !viewportDemand;
            this.fullProjection = fullProjection;
        }

        private boolean hasStarted() {
            return processedColumns > 0;
        }

        private void retarget(int projectionTopY) {
            this.projectionTopY = projectionTopY;
            this.fullProjection = true;
            Arrays.fill(requestedColumns, 0L);
            Arrays.fill(completedColumns, 0L);
            restartProjection();
        }

        private void addPatchColumn(int column) {
            if (fullProjection) return;
            int normalized = column & 255;
            int word = normalized >>> 6;
            long bit = 1L << (normalized & 63);
            requestedColumns[word] |= bit;
            completedColumns[word] &= ~bit;
            patchCursor = Math.min(patchCursor, normalized);
        }

        private void upgradeToFullProjection() {
            if (fullProjection) return;
            fullProjection = true;
            Arrays.fill(requestedColumns, 0L);
            Arrays.fill(completedColumns, 0L);
            restartProjection();
        }

        private void promoteToFullUsingCurrentSnapshot() {
            fullProjection = true;
            Arrays.fill(requestedColumns, 0L);
            Arrays.fill(completedColumns, 0L);
            nextFullColumn = 0;
            patchCursor = 0;
            processedColumns = 0;
            seedRevision = 0L;
        }

        private boolean hasRemainingColumns() {
            if (fullProjection) return nextFullColumn < DenseCaveTile.COLUMN_COUNT;
            for (int i = 0; i < requestedColumns.length; i++) {
                if ((requestedColumns[i] & ~completedColumns[i]) != 0L) return true;
            }
            return false;
        }

        private int takeNextColumn() {
            if (fullProjection) {
                if (nextFullColumn >= DenseCaveTile.COLUMN_COUNT) return -1;
                processedColumns++;
                return nextFullColumn++;
            }
            for (int offset = 0; offset < DenseCaveTile.COLUMN_COUNT; offset++) {
                int column = (patchCursor + offset) & 255;
                int word = column >>> 6;
                long bit = 1L << (column & 63);
                if ((requestedColumns[word] & bit) == 0L
                        || (completedColumns[word] & bit) != 0L) continue;
                completedColumns[word] |= bit;
                patchCursor = (column + 1) & 255;
                processedColumns++;
                return column;
            }
            return -1;
        }

        private void restartProjection() {
            builder = null;
            source = null;
            snapshot = null;
            seedRevision = 0L;
            nextFullColumn = 0;
            patchCursor = 0;
            processedColumns = 0;
            Arrays.fill(completedColumns, 0L);
        }

        @Override
        public int compareTo(Task other) {
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
