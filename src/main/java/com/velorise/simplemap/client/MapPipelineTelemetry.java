package com.velorise.simplemap.client;

import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Low-overhead cross-pipeline counters used to distinguish source, CPU page,
 * GPU page and renderer failures. Counters are intentionally cumulative so an
 * alpha build can compare deltas around one reproduction without retaining per
 * page objects or allocating log messages on hot paths.
 */
public final class MapPipelineTelemetry {
    private static final MapPipelineTelemetry INSTANCE = new MapPipelineTelemetry();

    private final EnumMap<MapRequestLane, AtomicLong> viewportRequests =
            new EnumMap<>(MapRequestLane.class);
    private final EnumMap<MapRequestLane, AtomicLong> pageAdmissions =
            new EnumMap<>(MapRequestLane.class);
    private final AtomicLong exactBuildQueued = new AtomicLong();
    private final AtomicLong exactBuildCompleted = new AtomicLong();
    private final AtomicLong exactBuildDiscarded = new AtomicLong();
    private final AtomicLong exactGpuReady = new AtomicLong();
    private final AtomicLong exactPagesDrawn = new AtomicLong();
    private final AtomicLong branchNodesDrawn = new AtomicLong();
    private final AtomicLong legacyFallbacksDrawn = new AtomicLong();
    private final AtomicLong noContentRenderPasses = new AtomicLong();
    private final AtomicLong renderPlanBuilds = new AtomicLong();
    private final AtomicLong renderPlanReuses = new AtomicLong();
    private final AtomicLong renderPlanQuads = new AtomicLong();
    private final AtomicLong renderPlanTextureGroups = new AtomicLong();
    private final AtomicLong rawBatchSubmissions = new AtomicLong();
    private final AtomicLong sourcePresent = new AtomicLong();
    private final AtomicLong sourceAbsent = new AtomicLong();
    private final AtomicLong sourceDeferred = new AtomicLong();
    private final AtomicLong sourceFailed = new AtomicLong();
    private final AtomicLong tasksCancelledBeforeRun = new AtomicLong();
    private final AtomicLong tasksCompletedButDiscarded = new AtomicLong();
    private final AtomicLong branchUpdatesQueued = new AtomicLong();
    private final AtomicLong branchUpdatesDropped = new AtomicLong();
    private final AtomicLong sourceLeasesOpened = new AtomicLong();
    private final AtomicLong sourceLeasesClosed = new AtomicLong();
    private final AtomicLong sourceDecodesCancelledNoConsumers = new AtomicLong();
    private final AtomicLongArray stageCount =
            new AtomicLongArray(MapPipelineStage.values().length);
    private final AtomicLongArray stageTotalNanos =
            new AtomicLongArray(MapPipelineStage.values().length);
    private final AtomicLongArray stageMaxNanos =
            new AtomicLongArray(MapPipelineStage.values().length);

    private MapPipelineTelemetry() {
        for (MapRequestLane lane : MapRequestLane.values()) {
            viewportRequests.put(lane, new AtomicLong());
            pageAdmissions.put(lane, new AtomicLong());
        }
    }

    public static MapPipelineTelemetry getInstance() {
        return INSTANCE;
    }

    public void recordViewportRequest(MapRequestLane lane) {
        viewportRequests.get(safe(lane)).incrementAndGet();
    }

    public void recordPageAdmission(MapRequestLane lane) {
        pageAdmissions.get(safe(lane)).incrementAndGet();
    }

    public void recordExactBuildQueued() {
        exactBuildQueued.incrementAndGet();
    }

    public void recordExactBuildCompleted() {
        exactBuildCompleted.incrementAndGet();
    }

    public void recordExactBuildDiscarded() {
        exactBuildDiscarded.incrementAndGet();
    }

    public void recordExactGpuReady() {
        exactGpuReady.incrementAndGet();
    }

    public void recordRenderResult(MapDrawResult result) {
        if (result == null || !result.drewAnyMapContent()) {
            noContentRenderPasses.incrementAndGet();
            return;
        }
        exactPagesDrawn.addAndGet(Math.max(0, result.exactPagesDrawn()));
        branchNodesDrawn.addAndGet(Math.max(0, result.branchNodesDrawn()));
        legacyFallbacksDrawn.addAndGet(Math.max(0, result.legacyFallbacksDrawn()));
    }

    public void recordRenderPlanBuild(int quads, int textureGroups) {
        renderPlanBuilds.incrementAndGet();
        renderPlanQuads.addAndGet(Math.max(0, quads));
        renderPlanTextureGroups.addAndGet(Math.max(0, textureGroups));
    }

    public void recordRenderPlanReuse() {
        renderPlanReuses.incrementAndGet();
    }

    public void recordRawBatchSubmissions(int submissions) {
        rawBatchSubmissions.addAndGet(Math.max(0, submissions));
    }

    public void recordSourceState(String stateName) {
        if (stateName == null) return;
        switch (stateName) {
            case "PRESENT" -> sourcePresent.incrementAndGet();
            case "ABSENT" -> sourceAbsent.incrementAndGet();
            case "DEFERRED" -> sourceDeferred.incrementAndGet();
            case "FAILED" -> sourceFailed.incrementAndGet();
            default -> { }
        }
    }

    public void recordTaskCancelledBeforeRun() {
        tasksCancelledBeforeRun.incrementAndGet();
    }

    public void recordTaskCompletedButDiscarded() {
        tasksCompletedButDiscarded.incrementAndGet();
    }


    public void recordBranchUpdateQueued() {
        branchUpdatesQueued.incrementAndGet();
    }

    public void recordBranchUpdateDropped() {
        branchUpdatesDropped.incrementAndGet();
    }

    public void recordSourceLeaseOpened() {
        sourceLeasesOpened.incrementAndGet();
    }

    public void recordSourceLeaseClosed() {
        sourceLeasesClosed.incrementAndGet();
    }

    public void recordSourceDecodeCancelledNoConsumers() {
        sourceDecodesCancelledNoConsumers.incrementAndGet();
    }

    public void recordStageNanos(MapPipelineStage stage, long nanos) {
        if (stage == null || nanos < 0L) return;
        int index = stage.ordinal();
        stageCount.incrementAndGet(index);
        stageTotalNanos.addAndGet(index, nanos);
        long previous;
        do {
            previous = stageMaxNanos.get(index);
            if (nanos <= previous) break;
        } while (!stageMaxNanos.compareAndSet(index, previous, nanos));
    }

    public StageSnapshot stageSnapshot(MapPipelineStage stage) {
        if (stage == null) return new StageSnapshot(0L, 0L, 0L);
        int index = stage.ordinal();
        return new StageSnapshot(stageCount.get(index), stageTotalNanos.get(index),
                stageMaxNanos.get(index));
    }

    public void reset() {
        for (AtomicLong counter : viewportRequests.values()) counter.set(0L);
        for (AtomicLong counter : pageAdmissions.values()) counter.set(0L);
        exactBuildQueued.set(0L);
        exactBuildCompleted.set(0L);
        exactBuildDiscarded.set(0L);
        exactGpuReady.set(0L);
        exactPagesDrawn.set(0L);
        branchNodesDrawn.set(0L);
        legacyFallbacksDrawn.set(0L);
        noContentRenderPasses.set(0L);
        renderPlanBuilds.set(0L);
        renderPlanReuses.set(0L);
        renderPlanQuads.set(0L);
        renderPlanTextureGroups.set(0L);
        rawBatchSubmissions.set(0L);
        sourcePresent.set(0L);
        sourceAbsent.set(0L);
        sourceDeferred.set(0L);
        sourceFailed.set(0L);
        tasksCancelledBeforeRun.set(0L);
        tasksCompletedButDiscarded.set(0L);
        branchUpdatesQueued.set(0L);
        branchUpdatesDropped.set(0L);
        sourceLeasesOpened.set(0L);
        sourceLeasesClosed.set(0L);
        sourceDecodesCancelledNoConsumers.set(0L);
        for (MapPipelineStage stage : MapPipelineStage.values()) {
            int index = stage.ordinal();
            stageCount.set(index, 0L);
            stageTotalNanos.set(index, 0L);
            stageMaxNanos.set(index, 0L);
        }
    }

    public Snapshot snapshot() {
        long[] requests = new long[MapRequestLane.values().length];
        long[] admissions = new long[MapRequestLane.values().length];
        for (MapRequestLane lane : MapRequestLane.values()) {
            requests[lane.ordinal()] = viewportRequests.get(lane).get();
            admissions[lane.ordinal()] = pageAdmissions.get(lane).get();
        }
        return new Snapshot(requests, admissions,
                exactBuildQueued.get(), exactBuildCompleted.get(),
                exactBuildDiscarded.get(), exactGpuReady.get(),
                exactPagesDrawn.get(), branchNodesDrawn.get(),
                legacyFallbacksDrawn.get(), noContentRenderPasses.get(),
                renderPlanBuilds.get(), renderPlanReuses.get(),
                renderPlanQuads.get(), renderPlanTextureGroups.get(),
                rawBatchSubmissions.get(),
                sourcePresent.get(), sourceAbsent.get(), sourceDeferred.get(),
                sourceFailed.get(), tasksCancelledBeforeRun.get(),
                tasksCompletedButDiscarded.get(), branchUpdatesQueued.get(),
                branchUpdatesDropped.get(), sourceLeasesOpened.get(),
                sourceLeasesClosed.get(), sourceDecodesCancelledNoConsumers.get());
    }

    public String compactSummary() {
        Snapshot s = snapshot();
        return "requests[minimap=" + s.viewportRequests(MapRequestLane.MINIMAP)
                + ",fullscreen=" + s.viewportRequests(MapRequestLane.FULLSCREEN)
                + "] admissions[minimap=" + s.pageAdmissions(MapRequestLane.MINIMAP)
                + ",fullscreen=" + s.pageAdmissions(MapRequestLane.FULLSCREEN)
                + "] exact[queued=" + s.exactBuildQueued()
                + ",completed=" + s.exactBuildCompleted()
                + ",discarded=" + s.exactBuildDiscarded()
                + ",gpu=" + s.exactGpuReady()
                + ",drawn=" + s.exactPagesDrawn()
                + "] branchDrawn=" + s.branchNodesDrawn()
                + " legacyDrawn=" + s.legacyFallbacksDrawn()
                + " noContent=" + s.noContentRenderPasses()
                + " plan[build=" + s.renderPlanBuilds()
                + ",reuse=" + s.renderPlanReuses()
                + ",quads=" + s.renderPlanQuads()
                + ",groups=" + s.renderPlanTextureGroups()
                + ",submits=" + s.rawBatchSubmissions() + "]"
                + " source[present=" + s.sourcePresent()
                + ",absent=" + s.sourceAbsent()
                + ",deferred=" + s.sourceDeferred()
                + ",failed=" + s.sourceFailed()
                + "] cancelled=" + s.tasksCancelledBeforeRun()
                + " completedDiscarded=" + s.tasksCompletedButDiscarded()
                + " branch[queued=" + s.branchUpdatesQueued()
                + ",dropped=" + s.branchUpdatesDropped() + "]"
                + " leases[open=" + s.sourceLeasesOpened()
                + ",closed=" + s.sourceLeasesClosed()
                + ",cancelNoConsumer=" + s.sourceDecodesCancelledNoConsumers() + "]"
                + " latencyMs[read=" + averageMillis(MapPipelineStage.ANVIL_READ)
                + ",datafix=" + averageMillis(MapPipelineStage.DATA_FIX)
                + ",decode=" + averageMillis(MapPipelineStage.CHUNK_DECODE)
                + ",sourceWait=" + averageMillis(MapPipelineStage.SOURCE_WAIT)
                + ",exactBuild=" + averageMillis(MapPipelineStage.EXACT_BUILD)
                + ",exactUpload=" + averageMillis(MapPipelineStage.EXACT_UPLOAD)
                + ",branch=" + averageMillis(MapPipelineStage.BRANCH_DERIVE) + "]"
                + schedulerSummary() + persistenceSummary()
                + gpuSummary() + residencySummary();
    }

    private String schedulerSummary() {
        MapWorkScheduler.Snapshot scheduler = MapWorkScheduler.snapshot();
        return " work[cpu=" + scheduler.cpuActive() + "/" + scheduler.cpuQueued()
                + ",cpuCost=" + scheduler.cpuQueuedCost()
                + ",io=" + scheduler.ioActive() + "/" + scheduler.ioQueued()
                + ",ioCost=" + scheduler.ioQueuedCost() + "]";
    }

    private String persistenceSummary() {
        MapManager manager = MapManager.getInstance();
        MapLightManager light = MapLightManager.getInstance();
        return " persist[surfaceDirty=" + manager.dirtyRegionCount()
                + ",surfacePending=" + RegionDataStore.pendingSaveCount()
                + ",surfaceFlight=" + RegionDataStore.inFlightSaveCount()
                + ",lightDirty=" + light.dirtyRegionCount()
                + ",lightPending=" + light.pendingSaveCount()
                + ",lightFlight=" + light.inFlightSaveCount() + "]";
    }

    private String gpuSummary() {
        MapGpuBudgetController.Snapshot gpu =
                MapGpuBudgetController.getInstance().snapshot();
        return " gpu[reservedMs=" + millis(gpu.reservedNanos())
                + ",minimapMs=" + millis(gpu.minimapReservedNanos())
                + ",bytesKiB=" + (gpu.reservedBytes() / 1024L)
                + ",minimapKiB=" + (gpu.minimapReservedBytes() / 1024L)
                + ",surface=" + millis(gpu.surfaceExactPredictionNanos())
                + ",cave=" + millis(gpu.caveExactPredictionNanos())
                + ",branch=" + millis(gpu.branchPredictionNanos()) + "]";
    }

    private String residencySummary() {
        MapResidencyManager.Snapshot residency =
                MapResidencyManager.getInstance().snapshot();
        MapAtlasMemoryTracker.Snapshot atlas =
                MapAtlasMemoryTracker.getInstance().snapshot();
        return " residency[entries=" + residency.residentEntries()
                + ",pinned=" + residency.pinnedEntries()
                + ",MiB=" + formatMiB(residency.estimatedBytes())
                + "/" + formatMiB(residency.budgetBytes())
                + ",evict=" + residency.globalEvictions()
                + ",fail=" + residency.budgetFailures() + "]"
                + " atlas[allocated=" + formatMiB(atlas.allocatedBytes())
                + ",planned=" + formatMiB(atlas.plannedAtlasBytes())
                + ",available=" + (atlas.detectedAvailableVramBytes() > 0L
                        ? formatMiB(atlas.detectedAvailableVramBytes()) : "unknown")
                + "]";
    }

    private static String formatMiB(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private String averageMillis(MapPipelineStage stage) {
        StageSnapshot snapshot = stageSnapshot(stage);
        if (snapshot.count() == 0L) return "0";
        double millis = (snapshot.totalNanos() / (double) snapshot.count()) / 1_000_000.0;
        return String.format(java.util.Locale.ROOT, "%.2f", millis);
    }

    private static MapRequestLane safe(MapRequestLane lane) {
        return lane == null ? MapRequestLane.FULLSCREEN : lane;
    }

    public record Snapshot(long[] viewportRequestsByLane,
            long[] pageAdmissionsByLane,
            long exactBuildQueued,
            long exactBuildCompleted,
            long exactBuildDiscarded,
            long exactGpuReady,
            long exactPagesDrawn,
            long branchNodesDrawn,
            long legacyFallbacksDrawn,
            long noContentRenderPasses,
            long renderPlanBuilds,
            long renderPlanReuses,
            long renderPlanQuads,
            long renderPlanTextureGroups,
            long rawBatchSubmissions,
            long sourcePresent,
            long sourceAbsent,
            long sourceDeferred,
            long sourceFailed,
            long tasksCancelledBeforeRun,
            long tasksCompletedButDiscarded,
            long branchUpdatesQueued,
            long branchUpdatesDropped,
            long sourceLeasesOpened,
            long sourceLeasesClosed,
            long sourceDecodesCancelledNoConsumers) {

        public long viewportRequests(MapRequestLane lane) {
            return viewportRequestsByLane[safe(lane).ordinal()];
        }

        public long pageAdmissions(MapRequestLane lane) {
            return pageAdmissionsByLane[safe(lane).ordinal()];
        }
    }

    public record StageSnapshot(long count, long totalNanos, long maxNanos) {
        public double averageMillis() {
            return count == 0L ? 0.0 : (totalNanos / (double) count) / 1_000_000.0;
        }

        public double maxMillis() {
            return maxNanos / 1_000_000.0;
        }
    }
}
