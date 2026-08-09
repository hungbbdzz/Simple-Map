package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.ChunkScanner;
import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;
import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Native 32x32-chunk Anvil region import authority for all world projections.
 *
 * <p>A requested native region owns one 34x34 source window: the complete 32x32
 * region plus one chunk of styling halo on every side. Chunks are decoded once
 * through Minecraft's shared RegionFileStorage-backed reader, converted to the
 * style-independent vertical cave archive, and then reused by every Full and
 * Layered projection. Overlapping 6x6 page windows no longer start independent
 * Anvil transactions for the same chunk.</p>
 *
 * <p>Only source cells required by the current visible page halo are admitted.
 * Decoded archive cells remain resident in the shared source cache, so a later
 * pan reuses overlap and adds only the newly exposed edge instead of draining the
 * rest of the native region in the background.</p>
 */
final class CaveNativeRegionImportService {
    private static final CaveNativeRegionImportService INSTANCE =
            new CaveNativeRegionImportService();

    static final int REGION_CHUNKS = 32;
    static final int REGION_PAGES = 8;
    static final int SOURCE_HALO = 1;
    static final int SOURCE_EDGE = REGION_CHUNKS + SOURCE_HALO * 2;
    static final int SOURCE_COUNT = SOURCE_EDGE * SOURCE_EDGE;
    private static final int MAX_REGIONS = 48;
    /*
     * PASS102 could open 96 Anvil source transactions while every completed read
     * queued a SOURCE_DECODE and then SOURCE_PROJECTION onto the same eight-worker
     * global CPU domain. The PASS102 validation run accumulated 18,633 reads and a
     * SOURCE_QUEUE tail of 8.39 s while GPU utilization stayed low. Xaero's world
     * loader intentionally advances a bounded region queue instead of flooding all
     * visible source cells at once. Keep at most four CPU waves in flight here and
     * admit one compact region slice at a time.
     */
    private static final int NORMAL_ACTIVE_SOURCES = 32;
    private static final int PRESSURE_ACTIVE_SOURCES = 12;
    private static final int SOURCE_SLICE = 8;
    private static final int SURFACE_PROTO_ACK_SLICE = 32;
    private static final long FAILED_RETRY_MS = 2_000L;
    // An Anvil chunk whose Status is not FULL can be useful for Cave archive,
    // but it can never be authoritative Surface input. Do not reopen/DataFix the
    // same proto chunk every two seconds while waiting for Minecraft to save FULL.
    private static final long SURFACE_PROTO_RETRY_MS = 30_000L;
    private static final long COMPLETED_RETENTION_MS = 30_000L;

    private final DecodedWorldRegionCache sourceCache =
            DecodedWorldRegionCache.getInstance();
    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final CaveRegionProjectionService projections =
            CaveRegionProjectionService.getInstance();
    private final SurfaceWorldSaveReconstructor surfaceReconstructor =
            SurfaceWorldSaveReconstructor.getInstance();
    private final PriorityDecodeExecutor archiveWorkers = new PriorityDecodeExecutor(
            MapWorkScheduler.WorkType.SOURCE_PROJECTION, 18);
    private final LinkedHashMap<RegionKey, RegionImport> imports =
            new LinkedHashMap<>(16, 0.75f, true);

    private long epoch = 1L;
    private long sequence;
    private long viewportGeneration;
    private long surfaceViewportGeneration;
    private int activeSources;

    private CaveNativeRegionImportService() {
    }

    static CaveNativeRegionImportService getInstance() {
        return INSTANCE;
    }

    synchronized void reset() {
        epoch++;
        for (RegionImport region : imports.values()) region.close();
        imports.clear();
        activeSources = 0;
        viewportGeneration = 0L;
        surfaceViewportGeneration = 0L;
    }

    /**
     * Registers all native regions touched by the current generated-page viewport.
     * Raw vertical archive cells are reusable by every cave presentation, but only
     * the projection that is actually visible is admitted. This keeps mode changes
     * cheap without eagerly building a second Full/Layered image for every page.
     */
    synchronized void requestViewport(ServerLevel level, String dimension,
            CaveView requestedView, int requestedTopY, long[] sourcePagePlan,
            long[] foregroundPagePlan, int focusPageX, int focusPageZ,
            MapRequestLane lane, long repositoryGeneration,
            AnvilPagePresenceIndex.Snapshot presenceSnapshot) {
        if (level == null || dimension == null || dimension.isBlank()
                || requestedView == null || sourcePagePlan == null
                || sourcePagePlan.length == 0
                || !repository.isGenerationCurrent(repositoryGeneration)) return;
        long[] effectiveForegroundPlan = foregroundPagePlan == null
                ? sourcePagePlan : foregroundPagePlan;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long currentViewportGeneration = ++viewportGeneration;
        /*
         * Foreground ownership is a lease for the current viewport. Do not clear
         * submitted masks before discovering the new lease: the fullscreen reader
         * refreshes this method frequently, and resetting identical masks caused the
         * same coherent region batch to be projected and written on every pulse.
         */
        for (RegionImport existing : imports.values()) {
            if (!existing.key.dimension.equals(dimension)
                    || existing.key.repositoryGeneration
                            != repositoryGeneration) continue;
            existing.clearCaveDemand();
        }
        /*
         * PASS114: source-support ownership and presentation ownership are not the
         * same set. Fullscreen source enumeration intentionally keeps a two-page
         * sticky halo so a continuous pan can reuse decoded Anvil/archive cells.
         * That halo must never be advertised as foreground projection demand: the
         * renderer owns only its real page plan. PASS113 treated the support halo
         * as foreground, so 49k cached-page offers were rejected/re-offered by the
         * texture manager in one run. Xaero similarly reads neighbour/support
         * chunks while only publishing MapTiles that belong to the writer window.
         */
        Map<Long, Long> sourceMasks = pageMasks(sourcePagePlan);
        Map<Long, Long> foregroundMasks = pageMasks(effectiveForegroundPlan);
        long now = System.currentTimeMillis();
        int sourcePageCount = sourceMasks.values().stream()
                .mapToInt(Long::bitCount).sum();
        int foregroundPageCount = 0;
        for (Map.Entry<Long, Long> entry : sourceMasks.entrySet()) {
            foregroundPageCount += Long.bitCount(entry.getValue()
                    & foregroundMasks.getOrDefault(entry.getKey(), 0L));
        }
        if (sourcePageCount != foregroundPageCount) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "CAVE_SOURCE_PRESENTATION_SPLIT:"
                    + effectiveLane + ':' + requestedView;
            if (recorder.shouldEmitEvent(eventKey, 1000L)) {
                recorder.event("CAVE_SOURCE_PRESENTATION_SPLIT",
                        "view=" + requestedView + " lane=" + effectiveLane
                                + " source_pages=" + sourcePageCount
                                + " foreground_pages=" + foregroundPageCount
                                + " source_only_pages="
                                + Math.max(0, sourcePageCount - foregroundPageCount));
            }
        }
        int regionOrdinal = 0;
        for (Map.Entry<Long, Long> entry : sourceMasks.entrySet()) {
            int regionX = CaveLoadHierarchy.x(entry.getKey());
            int regionZ = CaveLoadHierarchy.z(entry.getKey());
            RegionKey key = new RegionKey(dimension, regionX, regionZ,
                    repositoryGeneration);
            RegionImport region = imports.computeIfAbsent(key,
                    ignored -> new RegionImport(key, level, sequence++));
            region.level = level;
            region.lastSeenMs = now;
            region.viewportGeneration = currentViewportGeneration;
            region.applyPresence(presenceSnapshot);
            int cavePriority = effectiveLane.priorityBase()
                    + 900_000 - regionOrdinal++ * 4_000;
            long sourceMask = entry.getValue();
            long foregroundMask = foregroundMasks.getOrDefault(entry.getKey(), 0L)
                    & sourceMask;
            region.setCaveDemand(sourceMask, effectiveLane, cavePriority);
            region.retainProjection(requestedView, requestedTopY);
            region.updateFocus(focusPageX, focusPageZ);
            region.setForegroundDemand(requestedView, requestedTopY,
                    foregroundMask, effectiveLane, cavePriority + 120_000);
            if (foregroundMask == 0L) {
                projections.retireForegroundRegion(dimension, requestedView,
                        requestedTopY, regionX, regionZ);
            }
        }
        for (RegionImport existing : imports.values()) {
            if (!existing.key.dimension.equals(dimension)
                    || existing.key.repositoryGeneration
                            != repositoryGeneration) continue;
            if (existing.viewportGeneration == currentViewportGeneration) continue;
            for (ProjectionDemand demand : existing.demands.values()) {
                projections.retireForegroundRegion(existing.key.dimension,
                        demand.key.view, demand.key.projectionTopY,
                        existing.key.regionX, existing.key.regionZ);
            }
            existing.retireProjectionDemands();
        }
        pumpLocked();
        trimLocked(now);
    }

    private static Map<Long, Long> pageMasks(long[] pagePlan) {
        Map<Long, Long> masks = new LinkedHashMap<>();
        if (pagePlan == null) return masks;
        for (long packed : pagePlan) {
            int pageX = CaveLoadHierarchy.x(packed);
            int pageZ = CaveLoadHierarchy.z(packed);
            int regionX = Math.floorDiv(pageX, REGION_PAGES);
            int regionZ = Math.floorDiv(pageZ, REGION_PAGES);
            int localX = Math.floorMod(pageX, REGION_PAGES);
            int localZ = Math.floorMod(pageZ, REGION_PAGES);
            long region = CaveLoadHierarchy.pack(regionX, regionZ);
            long bit = 1L << (localZ * REGION_PAGES + localX);
            masks.merge(region, bit, (first, second) -> first | second);
        }
        return masks;
    }

    /**
     * Surface uses the same native-region source transaction as Cave. The source
     * cell is decoded once, then the transaction derives the Surface columns and
     * the reusable vertical archive before marking that cell resolved. Switching to
     * Layered or Full therefore adds only a projection demand; it never reopens the
     * .mca file or repeats palette decode.
     */
    synchronized void requestSurfaceViewport(ServerLevel level, String dimension,
            long[] pagePlan, int focusPageX, int focusPageZ, MapRequestLane lane,
            long repositoryGeneration,
            AnvilPagePresenceIndex.Snapshot presenceSnapshot) {
        if (level == null || dimension == null || dimension.isBlank()
                || pagePlan == null || pagePlan.length == 0
                || !repository.isGenerationCurrent(repositoryGeneration)) return;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long currentGeneration = ++surfaceViewportGeneration;
        Map<Long, Long> masks = new LinkedHashMap<>();
        for (long packed : pagePlan) {
            int pageX = CaveLoadHierarchy.x(packed);
            int pageZ = CaveLoadHierarchy.z(packed);
            int regionX = Math.floorDiv(pageX, REGION_PAGES);
            int regionZ = Math.floorDiv(pageZ, REGION_PAGES);
            int localX = Math.floorMod(pageX, REGION_PAGES);
            int localZ = Math.floorMod(pageZ, REGION_PAGES);
            long region = CaveLoadHierarchy.pack(regionX, regionZ);
            long bit = 1L << (localZ * REGION_PAGES + localX);
            masks.merge(region, bit, (first, second) -> first | second);
        }
        long now = System.currentTimeMillis();
        int regionOrdinal = 0;
        for (Map.Entry<Long, Long> entry : masks.entrySet()) {
            int regionX = CaveLoadHierarchy.x(entry.getKey());
            int regionZ = CaveLoadHierarchy.z(entry.getKey());
            RegionKey key = new RegionKey(dimension, regionX, regionZ,
                    repositoryGeneration);
            RegionImport region = imports.computeIfAbsent(key,
                    ignored -> new RegionImport(key, level, sequence++));
            region.level = level;
            region.lastSeenMs = now;
            region.applyPresence(presenceSnapshot);
            int priority = effectiveLane.priorityBase()
                    + 940_000 - regionOrdinal++ * 4_000;
            region.setSurfaceDemand(effectiveLane, entry.getValue(),
                    currentGeneration, priority);
            region.updateFocus(focusPageX, focusPageZ);
        }
        for (RegionImport region : imports.values()) {
            if (!region.key.dimension.equals(dimension)
                    || region.key.repositoryGeneration != repositoryGeneration) continue;
            region.retireSurfaceLaneIfStale(effectiveLane, currentGeneration);
        }
        pumpLocked();
        trimLocked(now);
    }

    synchronized void suspendSurfaceLane(MapRequestLane lane) {
        if (lane == null) return;
        for (RegionImport region : imports.values()) {
            region.clearSurfaceDemand(lane);
        }
    }

    synchronized void suspendCaveLane(MapRequestLane lane) {
        if (lane == null) return;
        for (RegionImport region : imports.values()) {
            if (region.caveLane == lane) region.retireProjectionDemands();
        }
    }

    synchronized void maintain() {
        pumpLocked();
        trimLocked(System.currentTimeMillis());
    }

    synchronized boolean ownsPage(String dimension, CaveView view,
            int projectionTopY, int globalPageX, int globalPageZ) {
        int regionX = Math.floorDiv(globalPageX, REGION_PAGES);
        int regionZ = Math.floorDiv(globalPageZ, REGION_PAGES);
        int canonicalTopY = canonicalTopY(view, projectionTopY);
        for (Map.Entry<RegionKey, RegionImport> entry : imports.entrySet()) {
            RegionKey key = entry.getKey();
            if (!key.dimension.equals(dimension) || key.regionX != regionX
                    || key.regionZ != regionZ) continue;
            RegionImport region = entry.getValue();
            ProjectionDemand demand = region.demands.get(
                    new ProjectionKey(view, canonicalTopY));
            if (demand == null) return false;
            int localX = Math.floorMod(globalPageX, REGION_PAGES);
            int localZ = Math.floorMod(globalPageZ, REGION_PAGES);
            return (demand.pageMask & (1L << (localZ * REGION_PAGES + localX))) != 0L;
        }
        return false;
    }

    synchronized DebugSnapshot debugSnapshot() {
        int complete = 0;
        int sourceResolved = 0;
        int sourceInFlight = 0;
        int demands = 0;
        for (RegionImport region : imports.values()) {
            if (region.sourceComplete) complete++;
            sourceResolved += region.resolved.cardinality();
            sourceInFlight += region.inFlight.cardinality();
            demands += region.demands.size();
        }
        return new DebugSnapshot(imports.size(), complete, activeSources,
                sourceResolved, sourceInFlight, demands);
    }

    /**
     * Retires source leases whose viewport ownership disappeared before the queued
     * Anvil read/decode reached a worker. DecodedWorldRegionCache cancels the
     * underlying token when the final consumer lease closes. PASS104 kept these
     * leases alive until completion because RegionImport stayed generation-current;
     * the validation log therefore contained WORLD_SOURCE_PROJECTION_FANOUT with
     * surface=false cave=false and a SOURCE_QUEUE tail of 8.6 seconds long after the
     * originating viewport had moved away.
     */
    private void cancelUnneededSourcesLocked() {
        for (RegionImport region : imports.values()) {
            for (int index = region.inFlight.nextSetBit(0); index >= 0;
                    index = region.inFlight.nextSetBit(index + 1)) {
                boolean stillNeeded = region.surfaceRequiredSources.get(index)
                        && !region.surfaceProjected.get(index)
                        || region.caveRequiredSources.get(index)
                                && !region.caveArchived.get(index);
                if (stillNeeded) continue;
                DecodedWorldRegionCache.SourceLease lease = region.leases[index];
                region.leases[index] = null;
                region.inFlight.clear(index);
                activeSources = Math.max(0, activeSources - 1);
                if (lease != null) lease.close();
            }
        }
    }

    private void pumpLocked() {
        cancelUnneededSourcesLocked();
        int maximum = MapPerformanceGovernor.getInstance().underPressure()
                ? PRESSURE_ACTIVE_SOURCES : NORMAL_ACTIVE_SOURCES;
        if (activeSources >= maximum) return;
        List<RegionImport> candidates = new ArrayList<>(imports.values());
        candidates.sort((first, second) -> {
            int byLane = Integer.compare(second.lane.rank(), first.lane.rank());
            if (byLane != 0) return byLane;
            // Spread source leases across the visible native regions before
            // refilling an already-busy region. Strict region priority previously
            // kept the first two .mca regions full and left the rest of a wide
            // viewport looking like missing Cave/Surface for several seconds.
            int byInFlight = Integer.compare(first.inFlight.cardinality(),
                    second.inFlight.cardinality());
            if (byInFlight != 0) return byInFlight;
            int byPriority = Integer.compare(second.priority, first.priority);
            return byPriority != 0 ? byPriority
                    : Long.compare(first.sequence, second.sequence);
        });
        long now = System.currentTimeMillis();
        for (RegionImport region : candidates) {
            if (activeSources >= maximum) break;
            if (!isCurrent(region)) continue;
            reconcileDeferredLiveSurfaceLocked(region);
            publishReadyPagesLocked(region);
            if (region.sourceComplete || region.retryAfterMs > now) continue;
            int capacity = Math.min(SOURCE_SLICE, maximum - activeSources);
            if (capacity <= 0) break;
            List<Integer> selected = region.nextSourceIndexes(capacity, now);
            /*
             * Retained SimpleMap Surface storage is already a durable source for
             * chunks that were scanned/reconstructed earlier. PASS102 reopened the
             * Anvil chunk anyway, DataFixed/decoded it, projected 256 columns, then
             * SurfaceWorldSaveReconstructor discovered isChunkSurfaceComplete() and
             * threw the projection away. Resolve Surface-only cells before source
             * admission; Cave still opens the chunk when it needs the vertical
             * archive. This mirrors Xaero's preference for retained map-region data
             * over rebuilding known terrain from the world save.
             */
            boolean retainedSurfaceAdvanced = false;
            for (var iterator = selected.iterator(); iterator.hasNext();) {
                int index = iterator.next();
                if (!region.surfaceRequiredSources.get(index)
                        || region.caveRequiredSources.get(index)) continue;
                int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                if (!MapManager.getInstance().isChunkSurfaceComplete(chunkX, chunkZ)) {
                    continue;
                }
                region.surfaceProjected.set(index);
                region.surfaceProtoDeferred.clear(index);
                region.surfaceProtoRetryAfterMs[index] = 0L;
                iterator.remove();
                retainedSurfaceAdvanced = true;
            }
            if (retainedSurfaceAdvanced) region.reconcileResolution();
            if (selected.isEmpty()) {
                region.sourceComplete = region.requiredSourcesReady();
                if (region.sourceComplete) {
                    onRegionSourceReadyLocked(region);
                } else {
                    long deferredUntil = region.nextSurfaceProtoRetryAfter(now);
                    if (deferredUntil > now) {
                        // Keep the service responsive to a newly-entered source cell
                        // without reopening the known proto cell itself.
                        region.retryAfterMs = Math.max(region.retryAfterMs,
                                Math.min(deferredUntil, now + 1_000L));
                    }
                }
                continue;
            }
            DecodedWorldRegionCache.PageReservation reservation = null;
            while (!selected.isEmpty() && reservation == null) {
                int requiredDecodes = 0;
                for (int index : selected) {
                    int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                    int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                    if (sourceCache.requiresForegroundDecode(
                            region.level, chunkX, chunkZ)) {
                        requiredDecodes++;
                    }
                }
                reservation = sourceCache.reserveForegroundDecodes(requiredDecodes);
                if (reservation == null) {
                    selected = new ArrayList<>(
                            selected.subList(0, Math.max(1, selected.size() / 2)));
                    if (selected.size() == 1) {
                        int index = selected.get(0);
                        int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                        int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                        if (sourceCache.requiresForegroundDecode(
                                region.level, chunkX, chunkZ)
                                && !sourceCache.canAdmitForegroundDecodes(1)) {
                            selected.clear();
                        }
                    }
                }
            }
            if (reservation == null || selected.isEmpty()) continue;
            int started = 0;
            for (int index : selected) {
                if (activeSources >= maximum) break;
                int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                DecodedWorldRegionCache.SourceLease lease =
                        sourceCache.requestReservedLease(region.level, chunkX, chunkZ,
                                region.lane, reservation);
                if (lease.isImmediatelyDeferred()) {
                    lease.close();
                    continue;
                }
                region.inFlight.set(index);
                region.leases[index] = lease;
                activeSources++;
                started++;
                long submittedEpoch = epoch;
                lease.future().whenCompleteAsync((result, failure) ->
                                completeSource(region, index, chunkX, chunkZ,
                                        submittedEpoch, result, failure),
                        archiveWorkers.dynamic(region.lane::executorPriority));
            }
            reservation.close();
            if (started == 0) region.retryAfterMs = now + 16L;
        }
    }

    private void completeSource(RegionImport region, int index,
            int chunkX, int chunkZ, long submittedEpoch,
            DecodedWorldRegionCache.Result result, Throwable failure) {
        boolean absent = false;
        boolean surfaceProjectionReady = false;
        boolean caveArchiveReady = false;
        boolean surfaceProtoDeferred = false;
        boolean surfaceNeeded;
        boolean caveNeeded;
        MapRequestLane projectionLane;
        synchronized (this) {
            /*
             * Snapshot unresolved products, not just historical requirement bits.
             * PASS104 could finish a source after its viewport disappeared and still
             * fan out surface=false/cave=false work. It could also rebuild the cave
             * archive on every retry when only Surface was waiting for a newer FULL
             * .mca save.
             */
            surfaceNeeded = region.surfaceRequiredSources.get(index)
                    && !region.surfaceProjected.get(index);
            caveNeeded = region.caveRequiredSources.get(index)
                    && !region.caveArchived.get(index);
            projectionLane = region.lane;
        }
        boolean hadCurrentDemand = surfaceNeeded || caveNeeded;
        try {
            if (submittedEpoch != epoch || !isCurrent(region)) return;
            if (!hadCurrentDemand || failure != null || result == null) return;
            switch (result.state()) {
                case PRESENT -> {
                    MapCancellationToken token = new MapCancellationToken(
                            () -> submittedEpoch == epoch && isCurrent(region));
                    DecodedWorldChunkSource source = result.source();
                    if (source != null) {
                        /*
                         * Surface has a stronger coherence requirement than Cave.
                         * A proto-generation Anvil chunk can already contain useful
                         * vertical sections for retained cave source while its
                         * Status is not FULL and FEATURES (trees/vegetation) are not
                         * durable yet. Build/commit each requested product
                         * independently so the cave archive is retained once and
                         * only Surface retries when the save is not authoritative.
                         */
                        boolean surfaceAuthoritative =
                                source.hasAuthoritativeSurfaceSource();
                        long projectionStart = System.nanoTime();
                        DecodedWorldChunkSource.ProjectionBundle bundle = source
                                .prepareProjectionBundle(
                                        surfaceNeeded && surfaceAuthoritative,
                                        caveNeeded,
                                        com.velorise.simplemap.client.MapConfig.displayFlowers,
                                        token);
                        MapPipelineTelemetry.getInstance().recordStageNanos(
                                MapPipelineStage.WORLD_SOURCE_FANOUT,
                                System.nanoTime() - projectionStart);
                        if (surfaceNeeded) {
                            surfaceProjectionReady = surfaceAuthoritative
                                    && bundle != null
                                    && bundle.surfaceColumns() != null
                                    && surfaceReconstructor.acceptProjection(
                                            region.level, chunkX, chunkZ,
                                            bundle.surfaceColumns(),
                                            projectionLane);
                            if (!surfaceAuthoritative) {
                                surfaceProtoDeferred = true;
                                /*
                                 * PASS114: disk Status!=FULL is not negative Surface
                                 * authority when Minecraft already has the same chunk
                                 * as a live FULL LevelChunk. Do not wait 30 seconds for
                                 * the next Anvil probe in that case. Nudge the canonical
                                 * live Surface writer; the hook marshals itself onto the
                                 * client thread and no-ops when the live chunk is absent.
                                 */
                                nudgeLiveSurfaceWriter(chunkX, chunkZ);
                                MapDebugRecorder recorder =
                                        MapDebugRecorder.getInstance();
                                String eventKey = "SURFACE_MCA_PROTO_DEFERRED:"
                                        + chunkX + ':' + chunkZ;
                                if (recorder.shouldEmitEvent(eventKey, 1000L)) {
                                    recorder.event("SURFACE_MCA_PROTO_DEFERRED",
                                            "chunk=" + chunkX + ',' + chunkZ
                                                    + " reason=status_not_full"
                                                    + " retry_ms=" + SURFACE_PROTO_RETRY_MS
                                                    + " live_writer_nudged=true");
                                }
                            }
                        }
                        caveArchiveReady = caveNeeded
                                && bundle != null
                                && bundle.verticalArchive() != null;
                    }
                }
                case ABSENT -> {
                    GeneratedChunkIndex.getInstance().markSavedAbsent(
                            region.level, chunkX, chunkZ);
                    absent = true;
                    surfaceProjectionReady = surfaceNeeded;
                    caveArchiveReady = caveNeeded;
                }
                case FAILED, DEFERRED -> {
                    // The unresolved product remains pending and retries this cell.
                }
            }
        } catch (RuntimeException ignored) {
            // Product bits remain unresolved and are retried under current demand.
        } finally {
            synchronized (this) {
                DecodedWorldRegionCache.SourceLease lease = region.leases[index];
                region.leases[index] = null;
                if (lease != null) lease.close();
                if (region.inFlight.get(index)) {
                    region.inFlight.clear(index);
                    activeSources = Math.max(0, activeSources - 1);
                }
                if (submittedEpoch != epoch || !isCurrent(region)) {
                    pumpLocked();
                    return;
                }
                if (!hadCurrentDemand) {
                    region.reconcileResolution();
                    region.retryAfterMs = 0L;
                    pumpLocked();
                    return;
                }

                boolean advanced = false;
                long nowMs = System.currentTimeMillis();
                if (absent) {
                    region.absent.set(index);
                    region.surfaceProjected.set(index);
                    region.caveArchived.set(index);
                    region.surfaceProtoDeferred.clear(index);
                    region.surfaceProtoRetryAfterMs[index] = 0L;
                    advanced = true;
                } else {
                    region.absent.clear(index);
                    if (surfaceNeeded && surfaceProjectionReady) {
                        region.surfaceProjected.set(index);
                        region.surfaceProtoDeferred.clear(index);
                        region.surfaceProtoRetryAfterMs[index] = 0L;
                        advanced = true;
                    } else if (surfaceNeeded && surfaceProtoDeferred) {
                        region.surfaceProtoDeferred.set(index);
                        region.surfaceProtoRetryAfterMs[index] = nowMs
                                + SURFACE_PROTO_RETRY_MS;
                    }
                    if (caveNeeded && caveArchiveReady) {
                        region.caveArchived.set(index);
                        advanced = true;
                    }
                }
                region.reconcileResolution();
                boolean resolvedNow = region.resolved.get(index);
                region.retryAfterMs = resolvedNow ? 0L
                        : nowMs + FAILED_RETRY_MS;

                if (advanced) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String fanoutKey = "WORLD_SOURCE_PROJECTION_FANOUT:"
                            + region.key.regionX + ':' + region.key.regionZ;
                    if (recorder.shouldEmitEvent(fanoutKey, 250L)) {
                        recorder.event("WORLD_SOURCE_PROJECTION_FANOUT",
                                "region=" + region.key.regionX + ','
                                        + region.key.regionZ
                                        + " surface=" + surfaceNeeded
                                        + " cave=" + caveNeeded
                                        + " resolved="
                                        + region.resolved.cardinality());
                    }
                    publishReadyPagesLocked(region);
                    if (region.requiredSourcesReady()) {
                        region.sourceComplete = true;
                        onRegionSourceReadyLocked(region);
                    }
                }
                pumpLocked();
            }
        }
    }

    /**
     * A proto Anvil read is only a disk-state deferral. The live writer may become
     * authoritative immediately after its nudge, so do not wait for the 30-second
     * disk retry before acknowledging that retained Surface chunk.
     */
    private static boolean reconcileDeferredLiveSurfaceLocked(RegionImport region) {
        if (region.surfaceProtoDeferred.isEmpty()) return false;
        boolean advanced = false;
        int acknowledged = 0;
        int checked = 0;
        int cursor = Math.max(0, Math.min(SOURCE_COUNT - 1,
                region.surfaceProtoReconcileCursor));
        int index = region.surfaceProtoDeferred.nextSetBit(cursor);
        boolean wrapped = false;
        if (index < 0) {
            index = region.surfaceProtoDeferred.nextSetBit(0);
            wrapped = true;
        }
        int nextCursor = cursor;
        while (index >= 0 && checked < SURFACE_PROTO_ACK_SLICE) {
            if (wrapped && index >= cursor) break;
            checked++;
            nextCursor = index + 1 >= SOURCE_COUNT ? 0 : index + 1;
            if (!region.surfaceRequiredSources.get(index)
                    || region.surfaceProjected.get(index)) {
                region.surfaceProtoDeferred.clear(index);
                region.surfaceProtoRetryAfterMs[index] = 0L;
            } else {
                int chunkX = region.firstChunkX() + index % SOURCE_EDGE;
                int chunkZ = region.firstChunkZ() + index / SOURCE_EDGE;
                if (MapManager.getInstance().isChunkSurfaceComplete(chunkX, chunkZ)) {
                    region.surfaceProjected.set(index);
                    region.surfaceProtoDeferred.clear(index);
                    region.surfaceProtoRetryAfterMs[index] = 0L;
                    advanced = true;
                    acknowledged++;
                }
            }
            int next = region.surfaceProtoDeferred.nextSetBit(index + 1);
            if (next < 0 && !wrapped) {
                wrapped = true;
                next = region.surfaceProtoDeferred.nextSetBit(0);
            }
            index = next;
        }
        region.surfaceProtoReconcileCursor = nextCursor;
        if (advanced) {
            region.reconcileResolution();
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            String eventKey = "SURFACE_PROTO_LIVE_WRITER_ACK:"
                    + region.key.regionX + ':' + region.key.regionZ;
            if (recorder.shouldEmitEvent(eventKey, 250L)) {
                recorder.event("SURFACE_PROTO_LIVE_WRITER_ACK",
                        "region=" + region.key.regionX + ',' + region.key.regionZ
                                + " acknowledged=" + acknowledged
                                + " checked=" + checked
                                + " deferred_remaining="
                                + region.surfaceProtoDeferred.cardinality());
            }
        }
        return advanced;
    }

    private static void nudgeLiveSurfaceWriter(int chunkX, int chunkZ) {
        int globalPageX = Math.floorDiv(chunkX, CaveLoadHierarchy.CHUNKS_PER_PAGE);
        int globalPageZ = Math.floorDiv(chunkZ, CaveLoadHierarchy.CHUNKS_PER_PAGE);
        int regionX = Math.floorDiv(globalPageX, CaveLoadHierarchy.PAGES_PER_REGION);
        int regionZ = Math.floorDiv(globalPageZ, CaveLoadHierarchy.PAGES_PER_REGION);
        int localPageX = Math.floorMod(globalPageX, CaveLoadHierarchy.PAGES_PER_REGION);
        int localPageZ = Math.floorMod(globalPageZ, CaveLoadHierarchy.PAGES_PER_REGION);
        int localChunkX = Math.floorMod(chunkX, CaveLoadHierarchy.CHUNKS_PER_PAGE);
        int localChunkZ = Math.floorMod(chunkZ, CaveLoadHierarchy.CHUNKS_PER_PAGE);
        int missingSubtileMask = 1 << (localChunkZ
                * CaveLoadHierarchy.CHUNKS_PER_PAGE + localChunkX);
        ChunkScanner.getInstance().nudgeRetainedSurfacePage(
                regionX, regionZ, localPageX, localPageZ, missingSubtileMask);
    }

    private void publishReadyPagesLocked(RegionImport region) {
        if (!isCurrent(region)) return;
        /*
         * A visible 64x64 child only depends on its central 4x4 Minecraft chunks.
         * The one-chunk halo is refinement input, not a publication barrier. PASS86
         * waited for the complete 6x6 window, so a slow or absent neighbour kept an
         * otherwise complete child black while Xaero would publish the child and
         * continue filling neighbouring data independently.
         */
        long centralReadyPageMask = region.centralReadyPageMask();
        /* PASS118: presentation readiness is chunk-coherent, not page-coherent.
         * A 64x64 page is allowed into projection as soon as at least one of its
         * central 16x16 children has a durable Cave source. The projection service
         * already masks incomplete children atomically, so retaining the older
         * 16/16 admission gate here only hid generated Minecraft chunks. */
        long partialPresentReadyPageMask = region.partialPresentReadyPageMask();
        long haloReadyPageMask = region.haloReadyPageMask();
        for (ProjectionDemand demand : region.demands.values()) {
            /*
             * View switching must not wait for another Anvil pass when the shared
             * vertical archive already owns all 16 chunks of a Full page. Xaero
             * changes cave presentation from retained map tiles first and lets the
             * writer refine later. Admit the same RAM fast path here; source-region
             * decoding continues in the background for halo/absence refinement.
             */
            long archiveReadyMask = demand.key.view == CaveView.FULL
                    ? fullArchiveReadyMask(region, demand.pageMask) : 0L;
            /*
             * Central readiness accepts every stable composition of generated and
             * known-absent chunks. PASS86 admitted only all-archive or all-absent
             * Full pages, which rejected the common mixed pages along explored-world
             * edges and produced the narrow strips visible in the runtime capture.
             */
            long projectionReadyMask = centralReadyPageMask
                    | partialPresentReadyPageMask;
            if (demand.key.view == CaveView.FULL) {
                projectionReadyMask |= archiveReadyMask;
            }
            long partialOnlyReadyMask = partialPresentReadyPageMask
                    & ~centralReadyPageMask & demand.pageMask;
            if (partialOnlyReadyMask != 0L) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_NATIVE_REGION_CHILD_READY:"
                        + region.key + ':' + demand.key;
                if (recorder.shouldEmitEvent(eventKey, 250L)) {
                    recorder.event("CAVE_NATIVE_REGION_CHILD_READY",
                            "region=" + region.key.regionX + ','
                                    + region.key.regionZ
                                    + " view=" + demand.key.view
                                    + " pages="
                                    + Long.bitCount(partialOnlyReadyMask)
                                    + " policy=chunk_coherent_before_page_complete");
                }
            }
            long archiveOnlyReadyMask = archiveReadyMask
                    & ~centralReadyPageMask;
            long centralBeforeHaloMask = centralReadyPageMask
                    & ~haloReadyPageMask & demand.pageMask;
            if (centralBeforeHaloMask != 0L) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_PROJECTION_CENTRAL_READY:"
                        + region.key + ':' + demand.key;
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("CAVE_PROJECTION_CENTRAL_READY",
                            "region=" + region.key.regionX + ','
                                    + region.key.regionZ
                                    + " view=" + demand.key.view
                                    + " pages="
                                    + Long.bitCount(centralBeforeHaloMask)
                                    + " policy=central_4x4_before_halo_6x6");
                }
            }
            if (demand.key.view == CaveView.FULL) {
                long mixedMask = mixedPresentAbsentReadyMask(region,
                        centralReadyPageMask & demand.pageMask);
                if (mixedMask != 0L) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_FULL_MIXED_AUTHORITY_READY:"
                            + region.key + ':' + demand.key;
                    if (recorder.shouldEmitEvent(eventKey, 500L)) {
                        recorder.event("CAVE_FULL_MIXED_AUTHORITY_READY",
                                "region=" + region.key.regionX + ','
                                        + region.key.regionZ
                                        + " pages=" + Long.bitCount(mixedMask)
                                        + " authority=archive_plus_known_absent");
                    }
                }
            }
            if (archiveOnlyReadyMask != 0L) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String eventKey = "CAVE_FULL_ARCHIVE_FASTPATH:"
                        + region.key + ':' + demand.key;
                if (recorder.shouldEmitEvent(eventKey, 500L)) {
                    recorder.event("CAVE_FULL_ARCHIVE_FASTPATH",
                            "region=" + region.key.regionX + ','
                                    + region.key.regionZ
                                    + " pages="
                                    + Long.bitCount(archiveOnlyReadyMask)
                                    + " source_ready="
                                    + Long.bitCount(centralReadyPageMask
                                            & demand.pageMask));
                }
            }
            /*
             * Publish source-stable children independently. Xaero tracks the version
             * of each region child; it does not restart all visible children because
             * one sibling finishes later. PASS82 waited for the whole native-region
             * foreground mask, then repeatedly resubmitted every page when any source
             * stamp changed. That produced holes, long centre-only phases and hundreds
             * of redundant Full projections.
             */
            /* PASS118: page admission is now child-progressive. The repository
             * revision and projection service coalesce arriving sibling revisions;
             * only complete 16x16 children become visible, so a missing sibling no
             * longer hides generated neighbours and sparse column confetti remains
             * impossible. */
            long readyDemand = projectionReadyMask & demand.pageMask;
            long foregroundProjectionMask = 0L;
            long backgroundProjectionMask = 0L;
            while (readyDemand != 0L) {
                int ordinal = Long.numberOfTrailingZeros(readyDemand);
                readyDemand &= readyDemand - 1L;
                int localPageX = ordinal % REGION_PAGES;
                int localPageZ = ordinal / REGION_PAGES;
                int globalPageX = region.key.regionX * REGION_PAGES + localPageX;
                int globalPageZ = region.key.regionZ * REGION_PAGES + localPageZ;
                int firstChunkX = globalPageX * 4;
                int firstChunkZ = globalPageZ * 4;
                long bit = 1L << ordinal;
                boolean sourcePageReady = (centralReadyPageMask & bit) != 0L;
                boolean anyPresent = ((archiveReadyMask
                        | partialPresentReadyPageMask) & bit) != 0L;
                if (sourcePageReady) {
                    boolean[] absentPage = new boolean[16];
                    anyPresent = false;
                    for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                        for (int chunkX = 0; chunkX < 4; chunkX++) {
                            int sourceX = localPageX * 4 + chunkX + SOURCE_HALO;
                            int sourceZ = localPageZ * 4 + chunkZ + SOURCE_HALO;
                            int sourceIndex = sourceZ * SOURCE_EDGE + sourceX;
                            boolean isAbsent = region.absent.get(sourceIndex);
                            absentPage[chunkX * 4 + chunkZ] = isAbsent;
                            anyPresent |= !isAbsent;
                        }
                    }
                    /* False entries also clear stale absence markers for generated
                     * chunks. Archive-only fast-path pages skip this mutation until
                     * source presence is actually resolved. */
                    repository.commitDisplayPage(List.of(), demand.key.view,
                            demand.key.projectionTopY, firstChunkX, firstChunkZ,
                            absentPage, region.key.repositoryGeneration);
                }
                long sourceRevision = repository.getPageRevision(
                        demand.key.view, demand.key.projectionTopY,
                        globalPageX, globalPageZ);
                if (sourceRevision == 0L) continue;

                if ((demand.foregroundMask & bit) != 0L) {
                    if (demand.foregroundSubmittedSourceRevisions[ordinal]
                            != sourceRevision) {
                        demand.foregroundSubmittedSourceRevisions[ordinal] =
                                sourceRevision;
                        demand.foregroundSubmittedMask |= bit;
                        foregroundProjectionMask |= bit;
                    }
                } else if (sourcePageReady && anyPresent
                        && demand.submittedSourceRevisions[ordinal]
                                != sourceRevision) {
                    /*
                     * PASS128: background projection is durable/refinement work, not
                     * presentation latency. Wait until the central 4x4 chunk page is
                     * source-coherent before scheduling its 64x64 projection. The
                     * previous partial background path repeatedly projected source
                     * revision A while sibling children were still arriving as B/C,
                     * which accounts for the BACKGROUND CAVE_RESULT_STALE churn in
                     * PASS127 logs. Foreground remains child-progressive.
                     */
                    demand.submittedSourceRevisions[ordinal] = sourceRevision;
                    backgroundProjectionMask |= bit;
                }
            }

            long foregroundReadyMask = projectionReadyMask
                    & demand.foregroundMask;
            long now = System.currentTimeMillis();
            boolean refreshForegroundLease = foregroundReadyMask != 0L
                    && (foregroundProjectionMask != 0L
                            || now - demand.lastProjectionLeaseRefreshMs >= 500L);
            if (refreshForegroundLease) {
                if (foregroundProjectionMask != 0L) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_NATIVE_REGION_INCREMENTAL_SUBMIT:"
                            + region.key + ':' + demand.key;
                    if (recorder.shouldEmitEvent(eventKey, 250L)) {
                        recorder.event("CAVE_NATIVE_REGION_INCREMENTAL_SUBMIT",
                                "region=" + region.key.regionX + ','
                                        + region.key.regionZ
                                        + " view=" + demand.key.view
                                        + " top_y=" + demand.key.projectionTopY
                                        + " changed_pages="
                                        + Long.bitCount(foregroundProjectionMask)
                                        + " archive_fast_pages="
                                        + Long.bitCount(archiveOnlyReadyMask
                                                & demand.foregroundMask)
                                        + " ready_pages="
                                        + Long.bitCount(foregroundReadyMask)
                                        + '/' + Long.bitCount(demand.foregroundMask)
                                        + " halo_ready="
                                        + Long.bitCount(haloReadyPageMask
                                                & demand.foregroundMask));
                    }
                }
                projections.requestRegion(region.level, region.key.dimension,
                        demand.key.view, demand.key.projectionTopY,
                        region.key.regionX, region.key.regionZ,
                        foregroundProjectionMask, foregroundReadyMask,
                        region.focusPageX, region.focusPageZ,
                        demand.foregroundLane, demand.priority + 120_000,
                        region.key.repositoryGeneration);
                demand.lastProjectionLeaseRefreshMs = now;
            }

            if (backgroundProjectionMask != 0L) {
                projections.requestRegion(region.level, region.key.dimension,
                        demand.key.view, demand.key.projectionTopY,
                        region.key.regionX, region.key.regionZ,
                        backgroundProjectionMask, 0L,
                        region.focusPageX, region.focusPageZ,
                        MapRequestLane.BACKGROUND, demand.priority - 80_000,
                        region.key.repositoryGeneration);
            }
        }
        if (region.visiblePageMask != 0L
                && (centralReadyPageMask & region.visiblePageMask)
                        == region.visiblePageMask
                && region.lane != MapRequestLane.BACKGROUND) {
            region.lane = MapRequestLane.BACKGROUND;
            region.priority -= 200_000;
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent(
                    "CAVE_NATIVE_REGION_VISIBLE_SOURCE_READY:" + region.key, 250L)) {
                recorder.event("CAVE_NATIVE_REGION_VISIBLE_SOURCE_READY",
                        "region=" + region.key.regionX + ',' + region.key.regionZ
                                + " visible_pages="
                                + Long.bitCount(region.visiblePageMask)
                                + " remaining_source_cells="
                                + (SOURCE_COUNT - region.resolved.cardinality())
                                + " order=per_page_versioned_children");
            }
        }
    }

    private void onRegionSourceReadyLocked(RegionImport region) {
        if (region.completedMs != 0L) return;
        region.completedMs = System.currentTimeMillis();
        publishReadyPagesLocked(region);
        long generatedMask = region.generatedPageMask();
        /*
         * Source ingestion and presentation demand are deliberately separate.
         * Completing the currently required source halo must not expand every old
         * layer/mode demand to all 64 pages of the native region.
         */
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        recorder.event("CAVE_NATIVE_REGION_SOURCE_READY",
                "region=" + region.key.regionX + ',' + region.key.regionZ
                        + " resolved=" + region.resolved.cardinality()
                        + " generated_pages=" + Long.bitCount(generatedMask)
                        + " demands=" + region.demands.size());
    }

    private static long mixedPresentAbsentReadyMask(RegionImport region,
            long pageMask) {
        long mixed = 0L;
        long remaining = pageMask;
        while (remaining != 0L) {
            int ordinal = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            int localPageX = ordinal % REGION_PAGES;
            int localPageZ = ordinal / REGION_PAGES;
            boolean anyAbsent = false;
            boolean anyPresent = false;
            for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                for (int chunkX = 0; chunkX < 4; chunkX++) {
                    int sourceX = localPageX * 4 + chunkX + SOURCE_HALO;
                    int sourceZ = localPageZ * 4 + chunkZ + SOURCE_HALO;
                    int sourceIndex = sourceZ * SOURCE_EDGE + sourceX;
                    if (region.absent.get(sourceIndex)) anyAbsent = true;
                    else anyPresent = true;
                }
            }
            if (anyAbsent && anyPresent) mixed |= 1L << ordinal;
        }
        return mixed;
    }

    private static long fullArchiveReadyMask(RegionImport region,
            long pageMask) {
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        long ready = 0L;
        long remaining = pageMask;
        while (remaining != 0L) {
            int ordinal = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            int localPageX = ordinal % REGION_PAGES;
            int localPageZ = ordinal / REGION_PAGES;
            int globalPageX = region.key.regionX * REGION_PAGES + localPageX;
            int globalPageZ = region.key.regionZ * REGION_PAGES + localPageZ;
            if (archive.hasFullProjectionPage(globalPageX, globalPageZ)) {
                ready |= 1L << ordinal;
            }
        }
        return ready;
    }

    private boolean isCurrent(RegionImport region) {
        return region != null && repository.isGenerationCurrent(
                region.key.repositoryGeneration);
    }

    private void trimLocked(long now) {
        var iterator = imports.entrySet().iterator();
        while (iterator.hasNext()) {
            RegionImport region = iterator.next().getValue();
            if (!isCurrent(region)) {
                region.close();
                iterator.remove();
                continue;
            }
            if (imports.size() <= MAX_REGIONS) continue;
            if (!region.sourceComplete || region.inFlight.cardinality() > 0
                    || now - region.completedMs < COMPLETED_RETENTION_MS) continue;
            region.close();
            iterator.remove();
        }
    }

    private static int canonicalTopY(CaveView view, int topY) {
        return view == CaveView.FULL ? Integer.MIN_VALUE : topY;
    }

    record DebugSnapshot(int regions, int completeRegions, int activeSources,
            int resolvedSourceCells, int inFlightSourceCells,
            int projectionDemands) {
    }

    private record RegionKey(String dimension, int regionX, int regionZ,
            long repositoryGeneration) {
    }

    private record ProjectionKey(CaveView view, int projectionTopY) {
    }

    private static final class ProjectionDemand {
        private final ProjectionKey key;
        private long pageMask;
        /** Source fingerprint submitted for each of the 64 native-region pages. */
        private final long[] submittedSourceRevisions = new long[64];
        /** Pages owned by the current visible viewport only. */
        private long foregroundMask;
        /** Foreground pages that have been submitted for their current source stamp. */
        private long foregroundSubmittedMask;
        /** Foreground source fingerprint submitted for each page. */
        private final long[] foregroundSubmittedSourceRevisions = new long[64];
        private long lastProjectionLeaseRefreshMs;
        private MapRequestLane foregroundLane = MapRequestLane.FULLSCREEN;
        private int priority;

        private ProjectionDemand(ProjectionKey key, long pageMask,
                int priority) {
            this.key = key;
            this.pageMask = pageMask;
            this.priority = priority;
        }
    }

    private static final class RegionImport {
        private final RegionKey key;
        private final long sequence;
        private final BitSet resolved = new BitSet(SOURCE_COUNT);
        private final BitSet absent = new BitSet(SOURCE_COUNT);
        private final BitSet inFlight = new BitSet(SOURCE_COUNT);
        private final DecodedWorldRegionCache.SourceLease[] leases =
                new DecodedWorldRegionCache.SourceLease[SOURCE_COUNT];
        private final Map<ProjectionKey, ProjectionDemand> demands =
                new HashMap<>();
        private final long[] surfacePageMasks =
                new long[MapRequestLane.values().length];
        private final long[] surfaceViewportGenerations =
                new long[MapRequestLane.values().length];
        private final int[] surfacePriorities =
                new int[MapRequestLane.values().length];
        private final BitSet surfaceRequiredSources = new BitSet(SOURCE_COUNT);
        private final BitSet caveRequiredSources = new BitSet(SOURCE_COUNT);
        private final BitSet surfaceProjected = new BitSet(SOURCE_COUNT);
        private final BitSet caveArchived = new BitSet(SOURCE_COUNT);
        private final BitSet surfaceProtoDeferred = new BitSet(SOURCE_COUNT);
        private final long[] surfaceProtoRetryAfterMs = new long[SOURCE_COUNT];
        private int surfaceProtoReconcileCursor;
        private ServerLevel level;
        private MapRequestLane lane = MapRequestLane.BACKGROUND;
        private int priority;
        private long caveVisiblePageMask;
        private MapRequestLane caveLane = MapRequestLane.BACKGROUND;
        private int cavePriority;
        private long visiblePageMask;
        private int focusPageX;
        private int focusPageZ;
        private int[] sourceOrder = new int[0];
        private int sourceCursor;
        private long lastSeenMs;
        private long viewportGeneration;
        private long retryAfterMs;
        private long completedMs;
        private long presenceRevision = Long.MIN_VALUE;
        private boolean sourceComplete;
        private long sourceOrderVisibleMask = Long.MIN_VALUE;

        private RegionImport(RegionKey key, ServerLevel level, long sequence) {
            this.key = key;
            this.level = level;
            this.sequence = sequence;
        }

        private int firstChunkX() {
            return key.regionX * REGION_CHUNKS - SOURCE_HALO;
        }

        private int firstChunkZ() {
            return key.regionZ * REGION_CHUNKS - SOURCE_HALO;
        }

        private void applyPresence(AnvilPagePresenceIndex.Snapshot snapshot) {
            if (snapshot == null || !snapshot.ready()
                    || snapshot.revision() == presenceRevision) return;
            boolean changed = false;
            for (int localZ = 0; localZ < SOURCE_EDGE; localZ++) {
                for (int localX = 0; localX < SOURCE_EDGE; localX++) {
                    int index = localZ * SOURCE_EDGE + localX;
                    int chunkX = firstChunkX() + localX;
                    int chunkZ = firstChunkZ() + localZ;
                    boolean present = snapshot.hasChunk(chunkX, chunkZ);
                    if (present) {
                        if (absent.get(index)) {
                            absent.clear(index);
                            surfaceProjected.clear(index);
                            caveArchived.clear(index);
                            resolved.clear(index);
                            surfaceProtoDeferred.clear(index);
                            surfaceProtoRetryAfterMs[index] = 0L;
                            changed = true;
                        }
                    } else if (!inFlight.get(index)) {
                        if (!resolved.get(index) || !absent.get(index)) changed = true;
                        absent.set(index);
                        surfaceProjected.set(index);
                        caveArchived.set(index);
                        resolved.set(index);
                        surfaceProtoDeferred.clear(index);
                        surfaceProtoRetryAfterMs[index] = 0L;
                    }
                }
            }
            presenceRevision = snapshot.revision();
            if (changed) {
                sourceComplete = requiredSourcesReady();
                completedMs = sourceComplete ? System.currentTimeMillis() : 0L;
                for (ProjectionDemand demand : demands.values()) {
                    java.util.Arrays.fill(demand.submittedSourceRevisions, 0L);
                    java.util.Arrays.fill(
                            demand.foregroundSubmittedSourceRevisions, 0L);
                    demand.foregroundSubmittedMask = 0L;
                }
            }
        }

        private void setCaveDemand(long pageMask, MapRequestLane lane,
                int priority) {
            caveVisiblePageMask = pageMask;
            caveLane = lane == null ? MapRequestLane.FULLSCREEN : lane;
            cavePriority = priority;
            recomputeCombinedDemand();
        }

        private void clearCaveDemand() {
            caveVisiblePageMask = 0L;
            caveLane = MapRequestLane.BACKGROUND;
            cavePriority = 0;
            recomputeCombinedDemand();
        }

        private void setSurfaceDemand(MapRequestLane lane, long pageMask,
                long generation, int priority) {
            MapRequestLane effective = lane == null
                    ? MapRequestLane.FULLSCREEN : lane;
            int laneIndex = effective.ordinal();
            surfacePageMasks[laneIndex] = pageMask;
            surfaceViewportGenerations[laneIndex] = generation;
            surfacePriorities[laneIndex] = priority;
            recomputeCombinedDemand();
        }

        private void retireSurfaceLaneIfStale(MapRequestLane lane,
                long currentGeneration) {
            if (lane == null) return;
            int index = lane.ordinal();
            if (surfaceViewportGenerations[index] == currentGeneration) return;
            clearSurfaceDemand(lane);
        }

        private void clearSurfaceDemand(MapRequestLane lane) {
            if (lane == null) return;
            int index = lane.ordinal();
            if (surfacePageMasks[index] == 0L
                    && surfaceViewportGenerations[index] == 0L) return;
            surfacePageMasks[index] = 0L;
            surfaceViewportGenerations[index] = 0L;
            surfacePriorities[index] = 0;
            recomputeCombinedDemand();
        }

        private void recomputeCombinedDemand() {
            long combined = caveVisiblePageMask;
            MapRequestLane strongestLane = caveVisiblePageMask == 0L
                    ? MapRequestLane.BACKGROUND : caveLane;
            int strongestPriority = caveVisiblePageMask == 0L ? 0 : cavePriority;
            long surfaceMask = 0L;
            for (MapRequestLane candidate : MapRequestLane.values()) {
                int index = candidate.ordinal();
                long mask = surfacePageMasks[index];
                if (mask == 0L) continue;
                surfaceMask |= mask;
                combined |= mask;
                int candidatePriority = surfacePriorities[index];
                if (candidate.strongerThan(strongestLane)
                        || candidate == strongestLane
                                && candidatePriority > strongestPriority) {
                    strongestLane = candidate;
                    strongestPriority = candidatePriority;
                }
            }
            visiblePageMask = combined;
            lane = strongestLane;
            priority = strongestPriority;
            surfaceRequiredSources.clear();
            caveRequiredSources.clear();
            addRequiredSources(surfaceMask, surfaceRequiredSources);
            addRequiredSources(caveVisiblePageMask, caveRequiredSources);
            surfaceProtoDeferred.and(surfaceRequiredSources);
            reconcileResolution();
            sourceOrderVisibleMask = Long.MIN_VALUE;
            sourceOrder = buildSourceOrder(key.regionX, key.regionZ,
                    visiblePageMask, focusPageX, focusPageZ);
            sourceCursor = 0;
            sourceComplete = requiredSourcesReady();
            if (!sourceComplete) completedMs = 0L;
        }

        /** Reconciles source completion against the projections currently demanded. */
        private void reconcileResolution() {
            /*
             * Persistence replay / a previous Cave mode may already own the immutable
             * vertical archive for this exact chunk. PASS104 still left the source bit
             * unresolved, so every new viewport reopened the same .mca chunk only to
             * feed CaveArchiveV2Service an identical CompactCaveTile. The validation
             * run recorded 26,924 stale-ignored archive ingests out of 27,448 ingests.
             * Treat retained archive residency as source completion before Anvil
             * admission, exactly like Xaero consumes its retained map region first.
             */
            CaveView demandedCaveView = null;
            if (!demands.isEmpty()) {
                demandedCaveView = demands.keySet().iterator().next().view;
            }
            CaveArchiveV2Service archive = demandedCaveView == null
                    ? null : CaveArchiveV2Service.getInstance();

            BitSet required = (BitSet) surfaceRequiredSources.clone();
            required.or(caveRequiredSources);
            for (int index = required.nextSetBit(0); index >= 0;
                    index = required.nextSetBit(index + 1)) {
                if (archive != null && caveRequiredSources.get(index)
                        && !caveArchived.get(index)) {
                    int localX = index % SOURCE_EDGE;
                    int localZ = index / SOURCE_EDGE;
                    int chunkX = firstChunkX() + localX;
                    int chunkZ = firstChunkZ() + localZ;
                    boolean retained = demandedCaveView == CaveView.FULL
                            ? archive.hasFullProjectionChunk(chunkX, chunkZ)
                            : archive.hasCompleteChunk(chunkX, chunkZ);
                    if (retained) caveArchived.set(index);
                }
                boolean complete = absent.get(index)
                        || (!surfaceRequiredSources.get(index)
                                || surfaceProjected.get(index))
                        && (!caveRequiredSources.get(index)
                                || caveArchived.get(index));
                if (complete) resolved.set(index);
                else if (!inFlight.get(index)) resolved.clear(index);
            }
            sourceComplete = requiredSourcesReady();
            if (!sourceComplete) completedMs = 0L;
        }

        private void updateFocus(int globalPageX, int globalPageZ) {
            if (sourceOrderVisibleMask == visiblePageMask
                    && focusPageX == globalPageX && focusPageZ == globalPageZ) {
                return;
            }
            focusPageX = globalPageX;
            focusPageZ = globalPageZ;
            sourceOrderVisibleMask = visiblePageMask;
            sourceOrder = buildSourceOrder(key.regionX, key.regionZ,
                    visiblePageMask, globalPageX, globalPageZ);
            sourceCursor = 0;
            sourceComplete = requiredSourcesReady();
            if (!sourceComplete) completedMs = 0L;
        }

        private void retainProjection(CaveView view, int topY) {
            ProjectionKey keep = new ProjectionKey(view,
                    canonicalTopY(view, topY));
            demands.entrySet().removeIf(entry -> !entry.getKey().equals(keep));
        }

        private void retireProjectionDemands() {
            demands.clear();
            clearCaveDemand();
            if (visiblePageMask == 0L) {
                sourceOrderVisibleMask = Long.MIN_VALUE;
                sourceOrder = new int[0];
                sourceCursor = 0;
                sourceComplete = true;
                completedMs = System.currentTimeMillis();
            }
        }

        private void setForegroundDemand(CaveView view, int topY, long pageMask,
                MapRequestLane lane, int priority) {
            int canonical = canonicalTopY(view, topY);
            ProjectionKey projectionKey = new ProjectionKey(view, canonical);
            ProjectionDemand demand = demands.computeIfAbsent(projectionKey,
                    key -> new ProjectionDemand(key, 0L, priority));
            if (demand.foregroundMask != pageMask) {
                long removed = demand.foregroundMask & ~pageMask;
                while (removed != 0L) {
                    int ordinal = Long.numberOfTrailingZeros(removed);
                    removed &= removed - 1L;
                    demand.foregroundSubmittedSourceRevisions[ordinal] = 0L;
                }
                /*
                 * Preserve overlapping child stamps while panning. Only pages that
                 * enter the viewport need another projection request.
                 */
                demand.foregroundSubmittedMask &= pageMask;
                demand.lastProjectionLeaseRefreshMs = 0L;
            }
            demand.pageMask = pageMask;
            demand.foregroundMask = pageMask;
            demand.foregroundLane = lane == null
                    ? MapRequestLane.FULLSCREEN : lane;
            demand.priority = priority;
        }

        private List<Integer> nextSourceIndexes(int maximum, long nowMs) {
            List<Integer> selected = new ArrayList<>(maximum);
            if (sourceOrder.length == 0) {
                sourceOrder = buildSourceOrder(key.regionX, key.regionZ,
                        visiblePageMask, focusPageX, focusPageZ);
            }
            int examined = 0;
            int limit = Math.max(SOURCE_COUNT, sourceOrder.length * 2);
            while (selected.size() < maximum && examined++ < limit) {
                if (sourceCursor >= sourceOrder.length) sourceCursor = 0;
                int index = sourceOrder[sourceCursor++];
                if (resolved.get(index) || inFlight.get(index)) continue;
                boolean surfacePending = surfaceRequiredSources.get(index)
                        && !surfaceProjected.get(index);
                boolean cavePending = caveRequiredSources.get(index)
                        && !caveArchived.get(index);
                if (surfacePending && !cavePending
                        && surfaceProtoRetryAfterMs[index] > nowMs) {
                    continue;
                }
                selected.add(index);
            }
            return selected;
        }

        private long nextSurfaceProtoRetryAfter(long nowMs) {
            long earliest = Long.MAX_VALUE;
            for (int index = surfaceRequiredSources.nextSetBit(0); index >= 0;
                    index = surfaceRequiredSources.nextSetBit(index + 1)) {
                if (surfaceProjected.get(index) || inFlight.get(index)) continue;
                if (caveRequiredSources.get(index) && !caveArchived.get(index)) {
                    continue;
                }
                long retry = surfaceProtoRetryAfterMs[index];
                if (retry > nowMs && retry < earliest) earliest = retry;
            }
            return earliest == Long.MAX_VALUE ? 0L : earliest;
        }

        /**
         * Pages with at least one durable, generated central child. Known-absent
         * children do not make a partial page present; a page containing only
         * unresolved/absent children waits for the normal complete-page path.
         * This mirrors Xaero's MapTileChunk rule: loaded children are publishable
         * without waiting for all sibling tiles.
         */
        private long partialPresentReadyPageMask() {
            long result = 0L;
            for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                    boolean anyPresent = false;
                    int firstX = pageX * 4 + SOURCE_HALO;
                    int firstZ = pageZ * 4 + SOURCE_HALO;
                    for (int localZ = 0; localZ < 4 && !anyPresent; localZ++) {
                        int row = firstZ + localZ;
                        for (int localX = 0; localX < 4; localX++) {
                            int index = row * SOURCE_EDGE + firstX + localX;
                            if (caveArchived.get(index) && !absent.get(index)) {
                                anyPresent = true;
                                break;
                            }
                        }
                    }
                    if (anyPresent) {
                        result |= 1L << (pageZ * REGION_PAGES + pageX);
                    }
                }
            }
            return result;
        }

        private long centralReadyPageMask() {
            long result = 0L;
            for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                    boolean ready = true;
                    int firstX = pageX * 4 + SOURCE_HALO;
                    int firstZ = pageZ * 4 + SOURCE_HALO;
                    for (int localZ = 0; localZ < 4 && ready; localZ++) {
                        int row = firstZ + localZ;
                        for (int localX = 0; localX < 4; localX++) {
                            if (!caveArchived.get(
                                    row * SOURCE_EDGE + firstX + localX)) {
                                ready = false;
                                break;
                            }
                        }
                    }
                    if (ready) result |= 1L << (pageZ * REGION_PAGES + pageX);
                }
            }
            return result;
        }

        private long haloReadyPageMask() {
            long result = 0L;
            for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                    boolean ready = true;
                    int firstX = pageX * 4;
                    int firstZ = pageZ * 4;
                    for (int localZ = 0; localZ < 6 && ready; localZ++) {
                        int row = firstZ + localZ;
                        for (int localX = 0; localX < 6; localX++) {
                            if (!caveArchived.get(
                                    row * SOURCE_EDGE + firstX + localX)) {
                                ready = false;
                                break;
                            }
                        }
                    }
                    if (ready) result |= 1L << (pageZ * REGION_PAGES + pageX);
                }
            }
            return result;
        }

        private boolean requiredSourcesReady() {
            if (visiblePageMask == 0L) return true;
            if (sourceOrder.length == 0) return false;
            for (int index : sourceOrder) {
                if (!resolved.get(index)) return false;
            }
            return true;
        }

        private long generatedPageMask() {
            long result = 0L;
            for (int pageZ = 0; pageZ < REGION_PAGES; pageZ++) {
                for (int pageX = 0; pageX < REGION_PAGES; pageX++) {
                    boolean present = false;
                    for (int chunkZ = 0; chunkZ < 4 && !present; chunkZ++) {
                        for (int chunkX = 0; chunkX < 4; chunkX++) {
                            int sourceX = pageX * 4 + chunkX + SOURCE_HALO;
                            int sourceZ = pageZ * 4 + chunkZ + SOURCE_HALO;
                            int index = sourceZ * SOURCE_EDGE + sourceX;
                            if (resolved.get(index) && !absent.get(index)) {
                                present = true;
                                break;
                            }
                        }
                    }
                    if (present) result |= 1L << (pageZ * REGION_PAGES + pageX);
                }
            }
            return result;
        }

        private void close() {
            for (int index = 0; index < leases.length; index++) {
                DecodedWorldRegionCache.SourceLease lease = leases[index];
                leases[index] = null;
                if (lease != null) lease.close();
            }
            inFlight.clear();
        }
    }

    private static void addRequiredSources(long pageMask, BitSet destination) {
        long remaining = pageMask;
        while (remaining != 0L) {
            int ordinal = Long.numberOfTrailingZeros(remaining);
            remaining &= remaining - 1L;
            int pageX = ordinal % REGION_PAGES;
            int pageZ = ordinal / REGION_PAGES;
            int firstX = pageX * CaveLoadHierarchy.CHUNKS_PER_PAGE;
            int firstZ = pageZ * CaveLoadHierarchy.CHUNKS_PER_PAGE;
            for (int localZ = 0; localZ < CaveLoadHierarchy.CHUNKS_PER_PAGE + 2;
                    localZ++) {
                int sourceZ = firstZ + localZ;
                for (int localX = 0;
                        localX < CaveLoadHierarchy.CHUNKS_PER_PAGE + 2; localX++) {
                    int sourceX = firstX + localX;
                    destination.set(sourceZ * SOURCE_EDGE + sourceX);
                }
            }
        }
    }

    private static int[] buildSourceOrder(int regionX, int regionZ,
            long visiblePageMask, int focusPageX, int focusPageZ) {
        LinkedHashSet<Integer> order = new LinkedHashSet<>(SOURCE_COUNT * 2);
        List<Integer> pages = new ArrayList<>(REGION_PAGES * REGION_PAGES);
        for (int ordinal = 0; ordinal < REGION_PAGES * REGION_PAGES; ordinal++) {
            if ((visiblePageMask & (1L << ordinal)) != 0L) pages.add(ordinal);
        }
        // Ordinals are already local z-major/x-major. Preserve that order so
        // source decode follows the same top-left viewport scanline as publication.
        for (int ordinal : pages) {
            int pageX = ordinal % REGION_PAGES;
            int pageZ = ordinal / REGION_PAGES;
            int firstX = pageX * 4;
            int firstZ = pageZ * 4;
            int[] localOrder = CaveLoadHierarchy.buildCenterOutCellOrder(6);
            for (int packed : localOrder) {
                int localX = packed % 6;
                int localZ = packed / 6;
                order.add((firstZ + localZ) * SOURCE_EDGE + firstX + localX);
            }
        }
        /*
         * Do not append the other ~1,100 source cells of the native region. The
         * current projection needs only the union of each visible page's 6x6 halo.
         * The vertical archive remains reusable, so a later pan or mode change adds
         * only the newly demanded cells instead of prefetching an entire .mca file.
         */
        int[] result = new int[order.size()];
        int cursor = 0;
        for (int index : order) result[cursor++] = index;
        return result;
    }
}
