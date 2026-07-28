package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapViewLoadPlanner;
import com.velorise.simplemap.client.MapWorkScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only singleplayer bridge from Anvil world-save data to dense cave pages.
 *
 * <p>The scheduling unit is a 64x64 page (4x4 Minecraft chunks). Fullscreen
 * candidates are restricted to the visible rectangle and admitted in stable screen
 * scanlines with low concurrency. The page itself may decode its sixteen chunks in
 * parallel, but publication remains page-coherent and LOD derives afterwards.</p>
 */
public final class CaveWorldSaveReader {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final CaveWorldSaveReader INSTANCE = new CaveWorldSaveReader();
    private static final boolean DISABLED =
            Boolean.getBoolean("simplemap.disableCaveWorldSaveReader");
    private static final int MAX_QUEUED_PAGES = 1024;
    /** Adaptive page transaction limits; global CPU/IO pressure can reduce these to one. */
    private static final int PRESSURE_IN_FLIGHT_PAGES = 1;
    private static final int GAMEPLAY_IN_FLIGHT_PAGES = 2;
    private static final int FULLSCREEN_IN_FLIGHT_PAGES = 4;
    private static final long MISSING_RETRY_MS = 30_000L;
    private static final long FAILED_RETRY_MS = 8_000L;
    /**
     * A page that already contains useful leaves is not a failed page. Retry its
     * unresolved holes quickly while preserving the leaves that were published by
     * the previous pass. This keeps the player neighbourhood coherent when one
     * Anvil chunk is briefly busy or its decode is deferred by pressure control.
     */
    private static final long PARTIAL_REPAIR_RETRY_MS = 250L;
    private static final long DEFERRED_RETRY_MS = 120L;
    private static final long VIEWPORT_REFRESH_MS = 50L;
    private static final long SOURCE_PREFETCH_INTERVAL_MS = 100L;
    /** Hold one source slice long enough to complete its coherent row-major window,
     * but revisit a permanently deferred/corrupt page on the next repair cycle. */

    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final CaveDisplayProjector projector = new CaveDisplayProjector();
    private final DecodedWorldRegionCache sourceCache = DecodedWorldRegionCache.getInstance();
    private final SurfaceWorldSaveReconstructor surfaceReconstructor =
            SurfaceWorldSaveReconstructor.getInstance();
    private final PriorityQueue<PageTask> queue = new PriorityQueue<>();
    private final Map<PageTaskKey, PageTask> queued = new HashMap<>();
    private final Map<PageTaskKey, PageTask> inFlightTasks = new HashMap<>();
    private final Map<PageRetryKey, Long> retryAfter = new HashMap<>();
    private final PriorityDecodeExecutor pageWorkers = new PriorityDecodeExecutor(4);
    private final EnumMap<MapRequestLane, ViewportState> viewports =
            new EnumMap<>(MapRequestLane.class);
    private final MapPipelineTelemetry pipelineTelemetry = MapPipelineTelemetry.getInstance();

    private long epoch = 1L;
    private long sequence;
    private String prefetchDimension = "";
    private int prefetchMinPageX = Integer.MIN_VALUE;
    private int prefetchMaxPageX = Integer.MIN_VALUE;
    private int prefetchMinPageZ = Integer.MIN_VALUE;
    private int prefetchMaxPageZ = Integer.MIN_VALUE;
    private long[] prefetchRegionPlan = new long[0];
    private int prefetchRegionCursor;
    private int prefetchPageCursor;
    private long lastSourcePrefetchMs;

    private CaveWorldSaveReader() {
        for (MapRequestLane lane : MapRequestLane.values()) {
            viewports.put(lane, new ViewportState());
        }
    }

    public static CaveWorldSaveReader getInstance() {
        return INSTANCE;
    }

    public synchronized int queuedCount() {
        return queued.size();
    }

    public synchronized int inFlightCount() {
        return inFlightTasks.size();
    }

    public synchronized void reset() {
        epoch++;
        for (PageTask task : queued.values()) task.token.cancel();
        for (PageTask task : inFlightTasks.values()) task.token.cancel();
        queue.clear();
        queued.clear();
        inFlightTasks.clear();
        retryAfter.clear();
        for (ViewportState state : viewports.values()) state.clear();
        prefetchDimension = "";
        prefetchMinPageX = prefetchMaxPageX = Integer.MIN_VALUE;
        prefetchMinPageZ = prefetchMaxPageZ = Integer.MIN_VALUE;
        prefetchRegionPlan = new long[0];
        prefetchRegionCursor = 0;
        prefetchPageCursor = 0;
        lastSourcePrefetchMs = 0L;
    }

    /** Removes queued work for a viewport that is no longer visible. */
    public synchronized void suspendLane(MapRequestLane lane) {
        if (lane == null) return;
        ViewportState state = viewports.get(lane);
        if (state != null) state.clear();
        java.util.Iterator<Map.Entry<PageTaskKey, PageTask>> iterator =
                queued.entrySet().iterator();
        while (iterator.hasNext()) {
            PageTask task = iterator.next().getValue();
            if (task.lane != lane) continue;
            task.token.cancel();
            queue.remove(task);
            iterator.remove();
            pipelineTelemetry.recordTaskCancelledBeforeRun();
        }
        for (PageTask task : inFlightTasks.values()) {
            if (task.lane == lane) task.token.cancel();
        }
    }

    /** Clears decoded world-save sources only when the world/dimension cache changes. */
    public synchronized void clearSourceCache() {
        sourceCache.reset();
        surfaceReconstructor.reset();
        prefetchDimension = "";
        prefetchRegionPlan = new long[0];
        prefetchRegionCursor = 0;
        prefetchPageCursor = 0;
    }

    /**
     * Quietly decodes generated chunks while the surface map is being viewed. The
     * work is source-only: it does not build a cave projection or allocate GPU pages.
     * Switching to Full/Layered Cave can then project the already-decoded sections.
     */
    public void prefetchVisibleSources(Minecraft minecraft,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            double centerChunkX, double centerChunkZ, float scale) {
        sourceCache.maintain();
        if (DISABLED || minecraft == null || minecraft.level == null
                || minecraft.getSingleplayerServer() == null) return;
        ServerLevel serverLevel = minecraft.getSingleplayerServer()
                .getLevel(minecraft.level.dimension());
        if (serverLevel == null) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (now - lastSourcePrefetchMs < SOURCE_PREFETCH_INTERVAL_MS) return;
            lastSourcePrefetchMs = now;
            int minPageX = Math.floorDiv(Math.min(minChunkX, maxChunkX), 4);
            int maxPageX = Math.floorDiv(Math.max(minChunkX, maxChunkX), 4);
            int minPageZ = Math.floorDiv(Math.min(minChunkZ, maxChunkZ), 4);
            int maxPageZ = Math.floorDiv(Math.max(minChunkZ, maxChunkZ), 4);
            String dimension = serverLevel.dimension().location().toString();
            boolean changed = !dimension.equals(prefetchDimension)
                    || minPageX != prefetchMinPageX || maxPageX != prefetchMaxPageX
                    || minPageZ != prefetchMinPageZ || maxPageZ != prefetchMaxPageZ;
            if (changed) {
                prefetchDimension = dimension;
                prefetchMinPageX = minPageX;
                prefetchMaxPageX = maxPageX;
                prefetchMinPageZ = minPageZ;
                prefetchMaxPageZ = maxPageZ;
                prefetchRegionPlan = CaveLoadHierarchy.buildRegionPlan(
                        Math.floorDiv(minPageX, 8), Math.floorDiv(maxPageX, 8),
                        Math.floorDiv(minPageZ, 8), Math.floorDiv(maxPageZ, 8),
                        centerChunkX / 32.0, centerChunkZ / 32.0);
                prefetchRegionCursor = 0;
                prefetchPageCursor = 0;
            } else if (prefetchRegionCursor >= prefetchRegionPlan.length) {
                return;
            }

            MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
            boolean movingFast = minecraft.player != null
                    && minecraft.player.getDeltaMovement().horizontalDistanceSqr() >= 0.18;
            int pageBudget;
            if (governor.underPressure()) pageBudget = 1;
            else if (movingFast) pageBudget = 1;
            else if (governor.isFullscreenOpen()) pageBudget = scale < 0.12f ? 2 : 4;
            else pageBudget = scale < 0.12f ? 1 : scale < 0.30f ? 2 : 3;
            int admitted = 0;
            while (prefetchRegionCursor < prefetchRegionPlan.length
                    && admitted < pageBudget) {
                long packedRegion = prefetchRegionPlan[prefetchRegionCursor];
                int regionX = CaveLoadHierarchy.x(packedRegion);
                int regionZ = CaveLoadHierarchy.z(packedRegion);
                int ordered = CaveLoadHierarchy.orderedPageIndex(prefetchPageCursor++);
                if (prefetchPageCursor >= CaveLoadHierarchy.PAGES_PER_REGION_COUNT) {
                    prefetchPageCursor = 0;
                    prefetchRegionCursor++;
                }
                int pageX = (regionX << 3) + (ordered & 7);
                int pageZ = (regionZ << 3) + (ordered >>> 3);
                if (pageX < minPageX || pageX > maxPageX
                        || pageZ < minPageZ || pageZ > maxPageZ) continue;
                int firstChunkX = pageX << 2;
                int firstChunkZ = pageZ << 2;
                for (int localX = 0; localX < 4; localX++) {
                    for (int localZ = 0; localZ < 4; localZ++) {
                        int sourceChunkX = firstChunkX + localX;
                        int sourceChunkZ = firstChunkZ + localZ;
                        DecodedWorldRegionCache.SourceLease sourceLease =
                                sourceCache.prefetchLease(serverLevel, sourceChunkX, sourceChunkZ);
                        surfaceReconstructor.request(serverLevel, sourceChunkX, sourceChunkZ,
                                sourceLease);
                    }
                }
                admitted++;
            }
        }
    }

    public void requestVisible(Minecraft minecraft, CaveView view, int layerY,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            double centerChunkX, double centerChunkZ, float scale) {
        requestVisible(minecraft, view, layerY,
                minChunkX, maxChunkX, minChunkZ, maxChunkZ,
                centerChunkX, centerChunkZ, scale, MapRequestLane.FULLSCREEN);
    }

    public void requestVisible(Minecraft minecraft, CaveView view, int layerY,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            double centerChunkX, double centerChunkZ, float scale,
            MapRequestLane lane) {
        sourceCache.maintain();
        if (DISABLED || minecraft == null || minecraft.level == null
                || minecraft.getSingleplayerServer() == null) return;
        ServerLevel serverLevel = minecraft.getSingleplayerServer()
                .getLevel(minecraft.level.dimension());
        if (serverLevel == null) return;
        GeneratedChunkIndex.getInstance().observeLevel(serverLevel);

        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        int minimumPageX = Math.floorDiv(Math.min(minChunkX, maxChunkX), 4);
        int maximumPageX = Math.floorDiv(Math.max(minChunkX, maxChunkX), 4);
        int minimumPageZ = Math.floorDiv(Math.min(minChunkZ, maxChunkZ), 4);
        int maximumPageZ = Math.floorDiv(Math.max(minChunkZ, maxChunkZ), 4);
        int rawCenterPageX = (int) Math.floor(centerChunkX / 4.0);
        int rawCenterPageZ = (int) Math.floor(centerChunkZ / 4.0);
        if (effectiveLane == MapRequestLane.MINIMAP) {
            minimumPageX = Math.max(minimumPageX - MapViewLoadPlanner.MINIMAP_HALO_PAGES,
                    rawCenterPageX - MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES);
            maximumPageX = Math.min(maximumPageX + MapViewLoadPlanner.MINIMAP_HALO_PAGES,
                    rawCenterPageX + MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES);
            minimumPageZ = Math.max(minimumPageZ - MapViewLoadPlanner.MINIMAP_HALO_PAGES,
                    rawCenterPageZ - MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES);
            maximumPageZ = Math.min(maximumPageZ + MapViewLoadPlanner.MINIMAP_HALO_PAGES,
                    rawCenterPageZ + MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES);
        }
        int centerPageX = clamp(rawCenterPageX, minimumPageX, maximumPageX);
        int centerPageZ = clamp(rawCenterPageZ, minimumPageZ, maximumPageZ);
        String dimension = serverLevel.dimension().location().toString();
        long now = System.currentTimeMillis();
        long repositoryGeneration = repository.generation();

        synchronized (this) {
            ViewportState state = viewports.get(effectiveLane);
            boolean changed = !state.matches(dimension, view, normalizedLayer, layerY,
                    minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                    centerPageX, centerPageZ, effectiveLane);
            if (changed) {
                state.reset(dimension, view, normalizedLayer, layerY,
                        minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                        centerPageX, centerPageZ, effectiveLane);
                pruneUnwantedQueuedLocked(now);
            } else {
                // Fullscreen focus is not a source-order signal. Keep the
                // row-major source traversal stable while the viewport shape matches.
                state.centerPageX = centerPageX;
                state.centerPageZ = centerPageZ;
            }
            state.lastSeenMs = now;

            boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
            long refreshInterval = Math.max(VIEWPORT_REFRESH_MS,
                    CaveScreenSpacePolicy.sourceEnumerationRetryMs(
                            scale, effectiveLane, pressured));
            boolean refreshDue = changed
                    || now - state.lastRequestMs >= refreshInterval;
            if (refreshDue && now >= state.nextPassMs) {
                state.lastRequestMs = now;
                retryAfter.entrySet().removeIf(entry -> entry.getValue() <= now);
                long currentEpoch = epoch;
                int admission = CaveScreenSpacePolicy.sourceAdmissionBudget(
                        scale, effectiveLane, pressured);
                if (effectiveLane == MapRequestLane.FULLSCREEN) {
                    if (state.pagePlan.length > 0 && admission > 0) {
                        if (state.pageCursor >= state.pagePlan.length) {
                            state.pageCursor = 0;
                        }
                        int capacity = Math.max(0,
                                laneAdmissionLimitLocked(effectiveLane)
                                        - activeTaskCountLocked(effectiveLane));
                        int admissionLimit = Math.min(admission, capacity);
                        int admitted = 0;
                        int considered = 0;
                        int considerationBudget = Math.max(16, admissionLimit * 8);
                        while (state.pageCursor < state.pagePlan.length
                                && admitted < admissionLimit
                                && considered < considerationBudget
                                && queued.size() < MAX_QUEUED_PAGES) {
                            int ordinal = state.pageCursor;
                            long packedPage = state.pagePlan[state.pageCursor++];
                            considered++;
                            int globalPageX = CaveLoadHierarchy.x(packedPage);
                            int globalPageZ = CaveLoadHierarchy.z(packedPage);
                            if (admitVisiblePageLocked(serverLevel, state,
                                    dimension, view, layerY, normalizedLayer,
                                    globalPageX, globalPageZ, ordinal,
                                    effectiveLane, currentEpoch,
                                    repositoryGeneration, now)) {
                                pipelineTelemetry.recordPageAdmission(effectiveLane);
                                admitted++;
                            }
                        }
                        state.updateSliceIndex = state.pageCursor
                                / MapViewLoadPlanner.FULLSCREEN_SLICE_SIZE;
                        if (state.pageCursor >= state.pagePlan.length) {
                            state.completedCycles++;
                            state.nextPassMs = now
                                    + CaveScreenSpacePolicy.completedSourcePlanPauseMs(
                                            scale, effectiveLane, pressured);
                        }
                    }
                } else {
                    int considered = 0;
                    if (state.pageCursor >= state.pagePlan.length) {
                        state.pageCursor = 0;
                    }
                    while (state.pageCursor < state.pagePlan.length
                            && considered < admission
                            && queued.size() < MAX_QUEUED_PAGES) {
                        int ordinal = state.pageCursor;
                        long packedPage = state.pagePlan[state.pageCursor++];
                        int globalPageX = CaveLoadHierarchy.x(packedPage);
                        int globalPageZ = CaveLoadHierarchy.z(packedPage);
                        considered++;
                        if (admitVisiblePageLocked(serverLevel, state,
                                dimension, view, layerY, normalizedLayer,
                                globalPageX, globalPageZ, ordinal,
                                effectiveLane, currentEpoch,
                                repositoryGeneration, now)) {
                            pipelineTelemetry.recordPageAdmission(effectiveLane);
                        }
                    }
                    if (state.pageCursor >= state.pagePlan.length) {
                        state.nextPassMs = now
                                + CaveScreenSpacePolicy.completedSourcePlanPauseMs(
                                        scale, effectiveLane, pressured);
                    }
                }

            }
        }
        pump(serverLevel);
    }

    private boolean admitVisiblePageLocked(ServerLevel serverLevel,
            ViewportState state, String dimension, CaveView view, int layerY,
            int normalizedLayer, int globalPageX, int globalPageZ, int ordinal,
            MapRequestLane lane, long currentEpoch, long repositoryGeneration,
            long now) {
        if (repository.hasFreshDisplayPageSource(view, layerY,
                globalPageX, globalPageZ, DenseCaveTile.Source.WORLD_SAVE)) return false;

        PageTaskKey key = new PageTaskKey(dimension,
                globalPageX, globalPageZ, view, normalizedLayer);
        PageRetryKey retryKey = new PageRetryKey(key, layerY);
        Long blockedUntil = retryAfter.get(retryKey);
        if (blockedUntil != null && blockedUntil > now) return false;

        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        int knownPresent = 0;
        int knownAbsent = 0;
        GeneratedChunkIndex generated = GeneratedChunkIndex.getInstance();
        for (int localX = 0; localX < 4; localX++) {
            for (int localZ = 0; localZ < 4; localZ++) {
                GeneratedChunkIndex.State generatedState = generated.state(serverLevel,
                        firstChunkX + localX, firstChunkZ + localZ);
                if (generatedState == GeneratedChunkIndex.State.LIVE
                        || generatedState == GeneratedChunkIndex.State.SAVED_PRESENT) {
                    knownPresent++;
                } else if (generatedState == GeneratedChunkIndex.State.KNOWN_ABSENT) {
                    knownAbsent++;
                }
            }
        }
        if (knownAbsent == 16) {
            boolean[] absentPage = new boolean[16];
            java.util.Arrays.fill(absentPage, true);
            repository.commitDisplayPage(java.util.List.of(), view, layerY,
                    firstChunkX, firstChunkZ, absentPage, repositoryGeneration);
            return true;
        }

        int dx = globalPageX - state.centerPageX;
        int dz = globalPageZ - state.centerPageZ;
        int priority;
        if (lane == MapRequestLane.FULLSCREEN) {
            // Fullscreen source order follows the stable row-major ordinal. Known
            // live/saved chunks are only a tie-breaking readiness bonus; distance
            // to the cursor or viewport centre never changes the visible frontier.
            priority = lane.priorityBase() + 220_000
                    - Math.min(180_000, ordinal * 900)
                    + knownPresent * 2_000;
        } else {
            int distancePenalty = Math.min(850_000,
                    (dx * dx + dz * dz) * 2_000);
            priority = lane.priorityBase() + 180_000 - distancePenalty
                    + knownPresent * 4_000;
        }
        return enqueueLocked(key, layerY, priority, lane,
                currentEpoch, repositoryGeneration);
    }

    private int activeTaskCountLocked(MapRequestLane lane) {
        int count = 0;
        for (PageTask task : queued.values()) {
            if (task.lane == lane) count++;
        }
        for (PageTask task : inFlightTasks.values()) {
            if (task.lane == lane) count++;
        }
        return count;
    }

    private boolean enqueueLocked(PageTaskKey key, int projectionTopY, int priority,
            MapRequestLane lane, long taskEpoch, long repositoryGeneration) {
        PageTask inFlight = inFlightTasks.get(key);
        // A stale exact projection is allowed to finish decoding, but the next
        // exact projection waits for that page slot instead of running two 16-chunk
        // page transactions concurrently. isCurrent() prevents stale publication.
        if (inFlight != null) return false;
        PageTask existing = queued.get(key);
        if (existing != null) {
            if (existing.projectionTopY == projectionTopY
                    && existing.priority >= priority
                    && !lane.strongerThan(existing.lane)) return false;
            existing.token.cancel();
            PageTask replacement = new PageTask(key, projectionTopY, priority, lane,
                    sequence++, taskEpoch, repositoryGeneration);
            queued.put(key, replacement);
            queue.offer(replacement);
            return true;
        }
        if (queued.size() >= MAX_QUEUED_PAGES) return false;
        PageTask task = new PageTask(key, projectionTopY, priority, lane,
                sequence++, taskEpoch, repositoryGeneration);
        queued.put(key, task);
        queue.offer(task);
        return true;
    }

    private void pump(ServerLevel serverLevel) {
        while (true) {
            PageTask task;
            synchronized (this) {
                int inFlightLimit = adaptiveInFlightLimitLocked();
                if (inFlightTasks.size() >= inFlightLimit) return;
                task = pollValidLocked();
                if (task == null) return;
                if (task.epoch != epoch
                        || !repository.isGenerationCurrent(task.repositoryGeneration)
                        || repository.hasFreshDisplayPageSource(task.key.view(),
                                task.projectionTopY, task.key.globalPageX(),
                                task.key.globalPageZ(), DenseCaveTile.Source.WORLD_SAVE)) continue;
                inFlightTasks.put(task.key, task);
            }
            readPage(serverLevel, task);
        }
    }

    /**
     * Separates fullscreen throughput from gameplay safety. The old fixed 1-page
     * gate left NVMe and multicore systems idle; this limit expands only while the
     * shared scheduler and frame governor report headroom.
     */
    private int adaptiveInFlightLimitLocked() {
        if (MapPerformanceGovernor.getInstance().underPressure()) {
            return PRESSURE_IN_FLIGHT_PAGES;
        }
        MapWorkScheduler.Snapshot work = MapWorkScheduler.snapshot();
        boolean schedulerBusy = work.cpuTotalCost() > 720
                || work.ioTotalCost() > 520;
        int limit = hasQueuedLaneLocked(MapRequestLane.FULLSCREEN)
                ? FULLSCREEN_IN_FLIGHT_PAGES : GAMEPLAY_IN_FLIGHT_PAGES;
        if (schedulerBusy) limit = Math.min(limit, GAMEPLAY_IN_FLIGHT_PAGES);
        // Preserve a dedicated player-centred transaction when fullscreen work is
        // already occupying the decode window.
        if (hasQueuedMinimapLocked() && !hasInFlightMinimapLocked()) {
            limit = Math.max(limit, 2);
        }
        return Math.max(1, limit);
    }

    private int laneAdmissionLimitLocked(MapRequestLane lane) {
        if (lane == MapRequestLane.MINIMAP) return 2;
        if (lane == MapRequestLane.FULLSCREEN) {
            return MapPerformanceGovernor.getInstance().underPressure() ? 2 : 6;
        }
        return lane == MapRequestLane.BACKGROUND ? 1 : 2;
    }

    private boolean hasQueuedLaneLocked(MapRequestLane lane) {
        for (PageTask task : queued.values()) {
            if (task.lane == lane) return true;
        }
        return false;
    }

    private PageTask pollValidLocked() {
        long now = System.currentTimeMillis();
        while (true) {
            PageTask task = queue.poll();
            if (task == null) return null;
            if (!queued.remove(task.key, task)) continue;
            if (!wantedByAnyViewportLocked(task, now)) {
                task.token.cancel();
                pipelineTelemetry.recordTaskCancelledBeforeRun();
                continue;
            }
            return task;
        }
    }

    @SuppressWarnings("unchecked")
    private void readPage(ServerLevel serverLevel, PageTask task) {
        int firstChunkX = task.key.globalPageX() << 2;
        int firstChunkZ = task.key.globalPageZ() << 2;
        DecodedWorldRegionCache.SourceLease[] leases =
                new DecodedWorldRegionCache.SourceLease[16];
        CompletableFuture<DecodedWorldRegionCache.Result>[] sources =
                new CompletableFuture[16];
        boolean[] alreadyResolved = new boolean[16];
        int retainedResolvedCount = 0;
        long sourceWaitStart = System.nanoTime();
        for (int order = 0; order < 16; order++) {
            int index = CaveLoadHierarchy.orderedChunkIndex(order);
            int localX = index & 3;
            int localZ = index >>> 2;
            if (repository.hasFreshDisplayTileOrKnownEmpty(task.key.view(),
                    task.projectionTopY, firstChunkX + localX,
                    firstChunkZ + localZ, DenseCaveTile.Source.WORLD_SAVE)) {
                alreadyResolved[index] = true;
                retainedResolvedCount++;
                sources[index] = CompletableFuture.completedFuture(null);
                continue;
            }
            // Foreground requests retain decoder capacity even if surface warmup has
            // already filled the prefetch window.
            leases[index] = sourceCache.requestLease(serverLevel,
                    firstChunkX + localX, firstChunkZ + localZ, task.lane);
            sources[index] = leases[index].future();
        }
        final boolean hasRetainedResolution = retainedResolvedCount > 0;

        CompletableFuture.allOf(sources).whenCompleteAsync((ignored, sourceFailure) -> {
            boolean failed = false;
            boolean deferred = false;
            boolean anyPresent = false;
            boolean anyUnknown = sourceFailure != null;
            int absentCount = 0;
            List<DenseCaveTile> resolvedTiles = new ArrayList<>(16);
            boolean[] absentPage = new boolean[16];
            try {
                pipelineTelemetry.recordStageNanos(MapPipelineStage.SOURCE_WAIT,
                        System.nanoTime() - sourceWaitStart);
                task.token.checkpoint("cave-page-sources-ready");
                /* Merge every resolved leaf immediately and retain old pixels for
                 * unknown leaves. A single slow/deferred Anvil chunk must not hold
                 * the other fifteen leaves hostage. Subsequent passes request only
                 * the unresolved leaves above. */
                for (int order = 0; order < 16; order++) {
                    int index = CaveLoadHierarchy.orderedChunkIndex(order);
                    if (alreadyResolved[index]) continue;
                    task.token.checkpoint("cave-page-chunk-" + order);
                    if (!isCurrent(task)) return;
                    DecodedWorldRegionCache.Result result;
                    try {
                        result = sources[index].join();
                    } catch (Throwable throwable) {
                        failed = true;
                        anyUnknown = true;
                        continue;
                    }
                    if (result == null) {
                        failed = true;
                        anyUnknown = true;
                        continue;
                    }

                    pipelineTelemetry.recordSourceState(result.state().name());
                    switch (result.state()) {
                        case PRESENT -> {
                            task.token.checkpoint("cave-project-start-" + order);
                            long projectionStart = System.nanoTime();
                            DenseCaveTile tile = result.source().projectCave(
                                    projector, task.key.view(), task.projectionTopY,
                                    DenseCaveTile.Source.WORLD_SAVE, task.token);
                            pipelineTelemetry.recordStageNanos(MapPipelineStage.CAVE_PROJECTION,
                                    System.nanoTime() - projectionStart);
                            task.token.checkpoint("cave-project-finished-" + order);
                            resolvedTiles.add(tile);
                            anyPresent = true;
                        }
                        case ABSENT -> {
                            int localX = index & 3;
                            int localZ = index >>> 2;
                            // CaveTileRepository uses X-major page indexing.
                            absentPage[localX * 4 + localZ] = true;
                            absentCount++;
                        }
                        case FAILED -> {
                            failed = true;
                            anyUnknown = true;
                        }
                        case DEFERRED -> {
                            deferred = true;
                            anyUnknown = true;
                        }
                    }
                }

                if (isCurrent(task)
                        && (!resolvedTiles.isEmpty() || absentCount > 0)) {
                    repository.commitDisplayPage(resolvedTiles, task.key.view(),
                            task.projectionTopY, firstChunkX, firstChunkZ,
                            absentPage, task.repositoryGeneration);
                }
            } catch (CancellationException cancelled) {
                deferred = true;
                anyUnknown = true;
            } catch (Throwable pageFailure) {
                failed = true;
                anyUnknown = true;
                LOGGER.debug("Could not build cave saved page {},{}",
                        task.key.globalPageX(), task.key.globalPageZ(), pageFailure);
            } finally {
                for (DecodedWorldRegionCache.SourceLease lease : leases) {
                    if (lease != null) lease.close();
                }
                boolean partialProgress = hasRetainedResolution
                        || anyPresent || absentCount > 0;
                long retry = deferred ? DEFERRED_RETRY_MS
                        : anyUnknown && partialProgress ? PARTIAL_REPAIR_RETRY_MS
                        : failed || anyUnknown ? FAILED_RETRY_MS
                        : !anyPresent && absentCount == 16 ? MISSING_RETRY_MS : 0L;
                finish(task, retry);
                pump(serverLevel);
            }
        }, pageWorkers.dynamic(task.lane::executorPriority));
    }

    private synchronized boolean isCurrent(PageTask task) {
        boolean current = task.epoch == epoch
                && repository.isGenerationCurrent(task.repositoryGeneration)
                && wantedByAnyViewportLocked(task, System.currentTimeMillis());
        if (!current) task.token.cancel();
        return current;
    }

    private void finish(PageTask task, long retryDelayMs) {
        synchronized (this) {
            if (!inFlightTasks.remove(task.key, task)) return;
            task.token.cancel();
            PageRetryKey retryKey = new PageRetryKey(task.key, task.projectionTopY);
            long now = System.currentTimeMillis();
            if (retryDelayMs > 0L && task.epoch == epoch
                    && wantedByAnyViewportLocked(task, now)) {
                retryAfter.put(retryKey, now + retryDelayMs);
            } else {
                retryAfter.remove(retryKey);
            }
        }
    }


    private synchronized void pruneUnwantedQueuedLocked(long now) {
        var iterator = queued.entrySet().iterator();
        while (iterator.hasNext()) {
            PageTask task = iterator.next().getValue();
            if (wantedByAnyViewportLocked(task, now)) continue;
            iterator.remove();
            task.token.cancel();
            pipelineTelemetry.recordTaskCancelledBeforeRun();
        }
    }

    private boolean wantedByAnyViewportLocked(PageTask task, long now) {
        for (Map.Entry<MapRequestLane, ViewportState> entry : viewports.entrySet()) {
            MapRequestLane lane = entry.getKey();
            ViewportState state = entry.getValue();
            if (state.isFresh(now, lane) && state.contains(task)) return true;
        }
        return false;
    }

    private boolean hasQueuedMinimapLocked() {
        for (PageTask task : queued.values()) {
            if (task.lane == MapRequestLane.MINIMAP) return true;
        }
        return false;
    }

    private boolean hasInFlightMinimapLocked() {
        for (PageTask task : inFlightTasks.values()) {
            if (task.lane == MapRequestLane.MINIMAP) return true;
        }
        return false;
    }

    private static final class ViewportState {
        private String dimension = "";
        private CaveView view;
        private int layerY = Integer.MIN_VALUE;
        private int projectionTopY = Integer.MIN_VALUE;
        private int minPageX = Integer.MIN_VALUE;
        private int maxPageX = Integer.MIN_VALUE;
        private int minPageZ = Integer.MIN_VALUE;
        private int maxPageZ = Integer.MIN_VALUE;
        private int centerPageX = Integer.MIN_VALUE;
        private int centerPageZ = Integer.MIN_VALUE;
        private long[] pagePlan = new long[0];
        private int pageCursor;
        private int updateSliceIndex;
        private long completedCycles;
        private long nextPassMs;
        private long lastRequestMs;
        private long lastSeenMs;

        private boolean matches(String dimension, CaveView view, int layerY,
                int projectionTopY, int minPageX, int maxPageX,
                int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            boolean focusMatches = lane == MapRequestLane.FULLSCREEN
                    || (this.centerPageX == centerPageX
                            && this.centerPageZ == centerPageZ);
            return this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY && this.projectionTopY == projectionTopY
                    && this.minPageX == minPageX && this.maxPageX == maxPageX
                    && this.minPageZ == minPageZ && this.maxPageZ == maxPageZ
                    && focusMatches;
        }

        private void reset(String dimension, CaveView view, int layerY,
                int projectionTopY, int minPageX, int maxPageX,
                int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            boolean continuousPan = lane == MapRequestLane.FULLSCREEN
                    && this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && rectanglesOverlap(this.minPageX, this.maxPageX,
                            this.minPageZ, this.maxPageZ,
                            minPageX, maxPageX, minPageZ, maxPageZ);
            int previousMinPageX = this.minPageX;
            int previousMaxPageX = this.maxPageX;
            int previousMinPageZ = this.minPageZ;
            int previousMaxPageZ = this.maxPageZ;
            this.dimension = dimension;
            this.view = view;
            this.layerY = layerY;
            this.projectionTopY = projectionTopY;
            this.minPageX = minPageX;
            this.maxPageX = maxPageX;
            this.minPageZ = minPageZ;
            this.maxPageZ = maxPageZ;
            this.centerPageX = centerPageX;
            this.centerPageZ = centerPageZ;
            pagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, lane == MapRequestLane.FULLSCREEN,
                    continuousPan, previousMinPageX, previousMaxPageX,
                    previousMinPageZ, previousMaxPageZ);
            pageCursor = 0;
            updateSliceIndex = 0;
            completedCycles = 0L;
            nextPassMs = 0L;
            lastRequestMs = 0L;
        }

        private static boolean rectanglesOverlap(int firstMinX, int firstMaxX,
                int firstMinZ, int firstMaxZ, int secondMinX, int secondMaxX,
                int secondMinZ, int secondMaxZ) {
            return firstMinX <= secondMaxX && firstMaxX >= secondMinX
                    && firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ;
        }

        private boolean contains(PageTask task) {
            return task.key.dimension().equals(dimension)
                    && task.key.view() == view && task.key.layerY() == layerY
                    && task.projectionTopY == projectionTopY
                    && task.key.globalPageX() >= minPageX
                    && task.key.globalPageX() <= maxPageX
                    && task.key.globalPageZ() >= minPageZ
                    && task.key.globalPageZ() <= maxPageZ;
        }

        private boolean isFresh(long now, MapRequestLane lane) {
            return lastSeenMs != 0L && now - lastSeenMs <= lane.requestTtlMs();
        }

        private void clear() {
            dimension = "";
            view = null;
            layerY = Integer.MIN_VALUE;
            projectionTopY = Integer.MIN_VALUE;
            minPageX = maxPageX = minPageZ = maxPageZ = Integer.MIN_VALUE;
            centerPageX = centerPageZ = Integer.MIN_VALUE;
            pagePlan = new long[0];
            pageCursor = 0;
            updateSliceIndex = 0;
            completedCycles = 0L;
            nextPassMs = 0L;
            lastRequestMs = 0L;
            lastSeenMs = 0L;
        }
    }

    public SourceCacheSnapshot sourceCacheSnapshot() {
        DecodedWorldRegionCache.Stats stats = sourceCache.stats();
        return new SourceCacheSnapshot(stats.regions(), stats.decodedChunks(),
                stats.residentBytes(), stats.targetBytes(), stats.inFlight(),
                stats.prefetchInFlight(), stats.decodeQueue(),
                stats.heapPressure(), stats.heapHeadroomBytes());
    }

    public record SourceCacheSnapshot(int regions, int decodedChunks,
            long residentBytes, long targetBytes, int inFlight,
            int prefetchInFlight, int decodeQueue, double heapPressure,
            long heapHeadroomBytes) {
    }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record PageTaskKey(String dimension, int globalPageX, int globalPageZ,
            CaveView view, int layerY) {
    }

    private record PageRetryKey(PageTaskKey page, int projectionTopY) {
    }

    private static final class PageTask implements Comparable<PageTask> {
        private final PageTaskKey key;
        private final int projectionTopY;
        private final int priority;
        private final MapRequestLane lane;
        private final long sequence;
        private final long epoch;
        private final long repositoryGeneration;
        private final MapCancellationToken token = new MapCancellationToken(null);

        private PageTask(PageTaskKey key, int projectionTopY, int priority,
                MapRequestLane lane, long sequence,
                long epoch, long repositoryGeneration) {
            this.key = key;
            this.projectionTopY = projectionTopY;
            this.priority = priority;
            this.lane = lane;
            this.sequence = sequence;
            this.epoch = epoch;
            this.repositoryGeneration = repositoryGeneration;
        }

        @Override
        public int compareTo(PageTask other) {
            int byLane = Integer.compare(other.lane.rank(), lane.rank());
            if (byLane != 0) return byLane;
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
