package com.velorise.simplemap.client;

import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-scoped source database and supertile capture boundary. Minecraft data
 * is copied into immutable 16x16 segments once per source revision. A 4x4 or 8x8
 * leaf transaction then assembles one palette-remapped window from those segments
 * rather than cloning/remapping source separately for every 64x64 page.
 */
public final class SurfaceRegionSourceDatabase {
    public static final int DEFAULT_BATCH_PAGES = 4;
    private static final SurfaceRegionSourceDatabase INSTANCE =
            new SurfaceRegionSourceDatabase();

    private static final int MAX_SOURCE_REGIONS = 24;
    private static final long CHUNK_SOURCE_BYTES = 4_608L;
    private final LinkedHashMap<SourceKey, SurfaceRegionSource> sources =
            new LinkedHashMap<>(32, 0.75f, true);
    private final Set<SurfaceRegionSource> retiredSources =
            new LinkedHashSet<>();
    private long captureWindowStartedNanos;
    private int captureWindowRemaining;
    private final AtomicLong captureAttempts = new AtomicLong();
    private final AtomicLong captureReady = new AtomicLong();
    private final AtomicLong captureDeferred = new AtomicLong();
    private final AtomicLong partialReady = new AtomicLong();
    private final AtomicLong missingRegionsTotal = new AtomicLong();
    private final AtomicLong missingChunksTotal = new AtomicLong();
    private final AtomicLong dirtyChunksTotal = new AtomicLong();
    private final AtomicLong focusedBatchPlans = new AtomicLong();
    private final AtomicLong expandedBatchPlans = new AtomicLong();
    private volatile int lastRequiredChunks;
    private volatile int lastPresentChunks;
    private volatile int lastMissingChunks;
    private volatile int lastDirtyChunks;
    private volatile int lastMissingRegions;
    private volatile boolean lastPipelineReady;
    private volatile boolean lastStrictReady;

    private SurfaceRegionSourceDatabase() { }

    public static SurfaceRegionSourceDatabase getInstance() { return INSTANCE; }


    public record Snapshot(int regions, int residentChunks,
            int pinnedViews, int closingRegions) { }

    public Snapshot snapshot() {
        synchronized (sources) {
            pruneRetiredLocked();
            int chunks = 0;
            int views = 0;
            for (SurfaceRegionSource source : sources.values()) {
                chunks += source.residentChunkCount();
                views += source.activeViewCount();
            }
            int closing = 0;
            for (SurfaceRegionSource source : retiredSources) {
                chunks += source.residentChunkCount();
                views += source.activeViewCount();
                closing++;
            }
            return new Snapshot(sources.size(), chunks, views, closing);
        }
    }

    public DebugSnapshot debugSnapshot() {
        int regions;
        int residentChunks = 0;
        int dirtyChunks = 0;
        int pinnedViews = 0;
        int closingRegions = 0;
        synchronized (sources) {
            pruneRetiredLocked();
            regions = sources.size();
            for (SurfaceRegionSource source : sources.values()) {
                residentChunks += source.debugResidentChunkCount();
                dirtyChunks += source.debugDirtyChunkCount();
                pinnedViews += source.activeViewCount();
            }
            for (SurfaceRegionSource source : retiredSources) {
                residentChunks += source.debugResidentChunkCount();
                dirtyChunks += source.debugDirtyChunkCount();
                pinnedViews += source.activeViewCount();
                closingRegions++;
            }
        }
        return new DebugSnapshot(regions, residentChunks, dirtyChunks,
                pinnedViews, closingRegions, captureAttempts.get(),
                captureReady.get(), captureDeferred.get(), partialReady.get(),
                missingRegionsTotal.get(), missingChunksTotal.get(),
                dirtyChunksTotal.get(), focusedBatchPlans.get(),
                expandedBatchPlans.get(), lastRequiredChunks, lastPresentChunks,
                lastMissingChunks, lastDirtyChunks, lastMissingRegions,
                lastPipelineReady, lastStrictReady);
    }

    public record DebugSnapshot(int regions, int residentChunks, int dirtyChunks,
            int pinnedViews, int closingRegions, long captureAttempts,
            long captureReady, long captureDeferred, long falseReady,
            long missingRegionsTotal, long missingChunksTotal,
            long dirtyChunksTotal, long focusedBatchPlans,
            long expandedBatchPlans, int lastRequiredChunks,
            int lastPresentChunks, int lastMissingChunks, int lastDirtyChunks,
            int lastMissingRegions, boolean lastPipelineReady,
            boolean lastStrictReady) {
        public static DebugSnapshot empty() {
            return new DebugSnapshot(0, 0, 0, 0, 0, 0L, 0L, 0L, 0L,
                    0L, 0L, 0L, 0L, 0L, 0, 0, 0, 0, 0, false, false);
        }
    }

    private void resetDebugCounters() {
        captureAttempts.set(0L);
        captureReady.set(0L);
        captureDeferred.set(0L);
        partialReady.set(0L);
        missingRegionsTotal.set(0L);
        missingChunksTotal.set(0L);
        dirtyChunksTotal.set(0L);
        focusedBatchPlans.set(0L);
        expandedBatchPlans.set(0L);
        lastRequiredChunks = 0;
        lastPresentChunks = 0;
        lastMissingChunks = 0;
        lastDirtyChunks = 0;
        lastMissingRegions = 0;
        lastPipelineReady = false;
        lastStrictReady = false;
    }

    public void markChunkDirty(int regionX, int regionZ, int localChunkIndex) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return;
        synchronized (sources) {
            SurfaceRegionSource source = sources.get(new SourceKey(stamp.sessionId(),
                    regionX, regionZ));
            if (source != null) source.markChunkDirty(localChunkIndex);
        }
    }

    public boolean isLeafSourceReady(RevisionStamp stamp, int regionX,
            int regionZ, int localPageX, int localPageZ) {
        if (stamp == null) return false;
        synchronized (sources) {
            SurfaceRegionSource source = sources.get(new SourceKey(
                    stamp.sessionId(), regionX, regionZ));
            return source != null && source.leafSourceReady(localPageX, localPageZ);
        }
    }

    public void clearSession(long sessionId) {
        synchronized (sources) {
            var iterator = sources.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<SourceKey, SurfaceRegionSource> entry = iterator.next();
                if (entry.getKey().sessionId() != sessionId) continue;
                iterator.remove();
                retireSourceLocked(entry.getValue());
            }
            if (sources.isEmpty()) resetDebugCounters();
        }
    }

    public void clear() {
        synchronized (sources) {
            for (SurfaceRegionSource source : sources.values()) {
                retireSourceLocked(source);
            }
            sources.clear();
            resetDebugCounters();
        }
    }

    /**
     * Captures only immutable chunk references and palette remap metadata on the
     * client thread. Large batch arrays are allocated and assembled later by the
     * projection worker through {@link BatchSourcePlan#assemble(BooleanSupplier)}.
     */
    public BatchSourcePlan captureBatchPlan(RevisionStamp stamp,
            int regionX, int regionZ, int batchPageX, int batchPageZ,
            int focusPageX, int focusPageZ, int pagesWide, int pagesHigh,
            boolean includeLight, MapRequestLane lane) {
        long startedNanos = System.nanoTime();
        captureAttempts.incrementAndGet();
        if (pagesWide == 1 && pagesHigh == 1) focusedBatchPlans.incrementAndGet();
        else expandedBatchPlans.incrementAndGet();
        if (stamp == null || !stamp.isCurrent()
                || pagesWide <= 0 || pagesHigh <= 0
                || batchPageX < 0 || batchPageZ < 0
                || batchPageX + pagesWide > MapPageLayout.PAGES_PER_REGION
                || batchPageZ + pagesHigh > MapPageLayout.PAGES_PER_REGION) {
            return null;
        }
        int halo = MapPageLayout.PAGE_HALO;
        int width = pagesWide * MapPageLayout.PAGE_SIZE + halo * 2;
        int height = pagesHigh * MapPageLayout.PAGE_SIZE + halo * 2;
        long bytes = (long) width * height
                * (Long.BYTES + Integer.BYTES + Byte.BYTES);
        MapMemoryLeaseManager.Lease memoryLease =
                MapMemoryLeaseManager.tryAcquire(
                        MapMemoryLeaseManager.Category.PENDING_SOURCE,
                        bytes, lane);
        if (memoryLease == null) return null;
        int[] remainingCaptures = new int[] { claimCaptureAllowance(lane) };
        SurfaceRegionSource.Probe[] probes = null;
        SurfaceRegionSource.View[] views = null;

        try {
            int worldPageStartX = (regionX * MapPageLayout.PAGES_PER_REGION
                    + batchPageX) * MapPageLayout.PAGE_SIZE;
            int worldPageStartZ = (regionZ * MapPageLayout.PAGES_PER_REGION
                    + batchPageZ) * MapPageLayout.PAGE_SIZE;
            int windowStartX = worldPageStartX - halo;
            int windowStartZ = worldPageStartZ - halo;
            int windowEndX = windowStartX + width - 1;
            int windowEndZ = windowStartZ + height - 1;

            int focusWorldStartX = (regionX * MapPageLayout.PAGES_PER_REGION
                    + focusPageX) * MapPageLayout.PAGE_SIZE;
            int focusWorldStartZ = (regionZ * MapPageLayout.PAGES_PER_REGION
                    + focusPageZ) * MapPageLayout.PAGE_SIZE;
            int focusWorldEndX = focusWorldStartX + MapPageLayout.PAGE_SIZE - 1;
            int focusWorldEndZ = focusWorldStartZ + MapPageLayout.PAGE_SIZE - 1;

            int minRegionX = Math.floorDiv(windowStartX, MapPageLayout.REGION_SIZE);
            int maxRegionX = Math.floorDiv(windowEndX, MapPageLayout.REGION_SIZE);
            int minRegionZ = Math.floorDiv(windowStartZ, MapPageLayout.REGION_SIZE);
            int maxRegionZ = Math.floorDiv(windowEndZ, MapPageLayout.REGION_SIZE);
            int sourceRegionWidth = maxRegionX - minRegionX + 1;
            int sourceRegionHeight = maxRegionZ - minRegionZ + 1;
            int sourceRegionCount = sourceRegionWidth * sourceRegionHeight;
            probes = new SurfaceRegionSource.Probe[sourceRegionCount];
            long[] coordinatePlan = new long[sourceRegionCount];
            int coordinateCount = 0;
            for (int sourceRegionZ = minRegionZ; sourceRegionZ <= maxRegionZ; sourceRegionZ++) {
                for (int sourceRegionX = minRegionX; sourceRegionX <= maxRegionX; sourceRegionX++) {
                    coordinatePlan[coordinateCount++] = packRegion(
                            sourceRegionX, sourceRegionZ);
                }
            }
            int focusWorldX = focusWorldStartX + MapPageLayout.PAGE_SIZE / 2;
            int focusWorldZ = focusWorldStartZ + MapPageLayout.PAGE_SIZE / 2;
            // A batch window crosses at most four 512x512 regions. Sort the
            // primitive packed coordinates in-place instead of allocating a list,
            // record objects and comparator captures for every rejected attempt.
            for (int i = 1; i < coordinateCount; i++) {
                long candidate = coordinatePlan[i];
                long candidateDistance = regionDistanceSquared(
                        unpackRegionX(candidate), unpackRegionZ(candidate),
                        focusWorldX, focusWorldZ);
                int insertion = i;
                while (insertion > 0) {
                    long previous = coordinatePlan[insertion - 1];
                    long previousDistance = regionDistanceSquared(
                            unpackRegionX(previous), unpackRegionZ(previous),
                            focusWorldX, focusWorldZ);
                    if (previousDistance <= candidateDistance) break;
                    coordinatePlan[insertion] = previous;
                    insertion--;
                }
                coordinatePlan[insertion] = candidate;
            }
            for (int coordinateIndex = 0; coordinateIndex < coordinateCount;
                    coordinateIndex++) {
                long packedCoordinate = coordinatePlan[coordinateIndex];
                int sourceRegionX = unpackRegionX(packedCoordinate);
                int sourceRegionZ = unpackRegionZ(packedCoordinate);
                SurfaceRegionSource.Probe probe = refreshAndProbe(stamp,
                        sourceRegionX, sourceRegionZ, windowStartX,
                        windowStartZ, windowEndX, windowEndZ,
                        focusWorldStartX, focusWorldStartZ,
                        focusWorldEndX, focusWorldEndZ,
                        includeLight, lane, remainingCaptures);
                probes[probeIndex(sourceRegionX, sourceRegionZ,
                        minRegionX, minRegionZ, sourceRegionWidth)] = probe;
            }

            Readiness readiness = inspectReadiness(probes,
                    minRegionX, minRegionZ, sourceRegionWidth, sourceRegionHeight,
                    minRegionX, maxRegionX,
                    minRegionZ, maxRegionZ, windowStartX, windowStartZ,
                    windowEndX, windowEndZ);
            Readiness focusBodyReadiness = inspectReadiness(probes,
                    minRegionX, minRegionZ, sourceRegionWidth, sourceRegionHeight,
                    Math.floorDiv(focusWorldStartX, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldEndX, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldStartZ, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldEndZ, MapPageLayout.REGION_SIZE),
                    focusWorldStartX, focusWorldStartZ,
                    focusWorldEndX, focusWorldEndZ);
            Readiness focusReadiness = inspectReadiness(probes,
                    minRegionX, minRegionZ, sourceRegionWidth, sourceRegionHeight,
                    Math.floorDiv(focusWorldStartX - halo, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldEndX + halo, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldStartZ - halo, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldEndZ + halo, MapPageLayout.REGION_SIZE),
                    focusWorldStartX - halo, focusWorldStartZ - halo,
                    focusWorldEndX + halo, focusWorldEndZ + halo);
            // A halo-only hit must not release an all-UNKNOWN focused page. At
            // least one body chunk is required for progressive publication.
            boolean pipelineReady = focusBodyReadiness.presentChunks() > 0;
            boolean strictReady = readiness.missingChunks() == 0
                    && readiness.dirtyChunks() == 0;
            lastRequiredChunks = readiness.requiredChunks();
            lastPresentChunks = readiness.presentChunks();
            lastMissingChunks = readiness.missingChunks();
            lastDirtyChunks = readiness.dirtyChunks();
            lastMissingRegions = readiness.missingRegions();
            lastPipelineReady = pipelineReady;
            lastStrictReady = strictReady;
            missingRegionsTotal.addAndGet(readiness.missingRegions());
            missingChunksTotal.addAndGet(readiness.missingChunks());
            dirtyChunksTotal.addAndGet(readiness.dirtyChunks());
            String batchId = regionX + "," + regionZ + ":" + batchPageX + ","
                    + batchPageZ + ":" + pagesWide + "x" + pagesHigh;
            if (!pipelineReady) {
                captureDeferred.incrementAndGet();
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("BATCH_SOURCE_DEFERRED:" + batchId,
                        1000L)) {
                    recorder.event("BATCH_SOURCE_DEFERRED",
                            "batch=" + batchId + " required="
                                    + readiness.requiredChunks() + " present="
                                    + readiness.presentChunks() + " missing="
                                    + readiness.missingChunks() + " dirty="
                                    + readiness.dirtyChunks() + " body_present="
                                    + focusBodyReadiness.presentChunks() + " body_missing="
                                    + focusBodyReadiness.missingChunks() + " body_dirty="
                                    + focusBodyReadiness.dirtyChunks() + " focus_present="
                                    + focusReadiness.presentChunks() + " focus_missing="
                                    + focusReadiness.missingChunks() + " focus_dirty="
                                    + focusReadiness.dirtyChunks());
                }
                closeProbes(probes);
                memoryLease.close();
                return null;
            }
            if (strictReady) {
                captureReady.incrementAndGet();
            } else {
                partialReady.incrementAndGet();
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("BATCH_PARTIAL_READY:" + batchId,
                        1000L)) {
                    recorder.event("BATCH_PARTIAL_READY",
                            "batch=" + batchId + " body_present="
                                    + focusBodyReadiness.presentChunks() + " body_missing="
                                    + focusBodyReadiness.missingChunks() + " body_dirty="
                                    + focusBodyReadiness.dirtyChunks() + " focus_present="
                                    + focusReadiness.presentChunks() + " focus_missing="
                                    + focusReadiness.missingChunks() + " focus_dirty="
                                    + focusReadiness.dirtyChunks() + " batch_present="
                                    + readiness.presentChunks() + " batch_missing="
                                    + readiness.missingChunks() + " batch_dirty="
                                    + readiness.dirtyChunks());
                }
            }

            List<String> biomePalette = new ArrayList<>();
            List<String> blockPalette = new ArrayList<>();
            Map<String, Integer> biomeIds = new HashMap<>();
            Map<String, Integer> blockIds = new HashMap<>();
            List<RegionSlice> slices = new ArrayList<>(sourceRegionCount);
            long sourceRevision = 0L;
            views = new SurfaceRegionSource.View[sourceRegionCount];
            for (int index = 0; index < probes.length; index++) {
                SurfaceRegionSource.Probe probe = probes[index];
                if (probe != null) views[index] = probe.acquireView();
            }
            closeProbes(probes);
            probes = null;
            for (int index = 0; index < views.length; index++) {
                SurfaceRegionSource.View view = views[index];
                if (view == null) continue;
                int coordinateX = minRegionX + index % sourceRegionWidth;
                int coordinateZ = minRegionZ + index / sourceRegionWidth;
                RegionCoordinate coordinate = new RegionCoordinate(
                        coordinateX, coordinateZ);
                sourceRevision = Math.max(sourceRevision, view.sourceRevision());
                int[] biomeRemap = buildBiomeRemap(view.biomePaletteUnsafe(),
                        biomePalette, biomeIds);
                int[] blockRemap = buildBlockRemap(view.blockPaletteUnsafe(),
                        blockPalette, blockIds);
                slices.add(new RegionSlice(coordinate, view, biomeRemap, blockRemap));
            }

            return new BatchSourcePlan(stamp, regionX, regionZ,
                    batchPageX, batchPageZ, pagesWide, pagesHigh,
                    worldPageStartX, worldPageStartZ, width, height, halo,
                    Math.max(1L, sourceRevision), slices.toArray(RegionSlice[]::new),
                    List.copyOf(biomePalette), List.copyOf(blockPalette), memoryLease);
        } catch (RuntimeException | Error failure) {
            closeProbes(probes);
            closeViews(views);
            memoryLease.close();
            throw failure;
        } finally {
            MapPipelineTelemetry.getInstance().recordStageNanos(
                    MapPipelineStage.SURFACE_CAPTURE,
                    System.nanoTime() - startedNanos);
        }
    }

    private SurfaceRegionSource.Probe refreshAndProbe(RevisionStamp stamp,
            int regionX, int regionZ, int windowStartX, int windowStartZ,
            int windowEndX, int windowEndZ, int focusWorldStartX,
            int focusWorldStartZ, int focusWorldEndX, int focusWorldEndZ,
            boolean includeLight, MapRequestLane lane, int[] remainingCaptures) {
        MapManager.Region region = MapManager.getInstance().getRegion(regionX, regionZ, false);
        if (region == null || !region.isLoaded()) return null;
        SourceKey key = new SourceKey(stamp.sessionId(), regionX, regionZ);
        SurfaceRegionSource source = getOrCreateSource(key, stamp, regionX, regionZ);
        MapManager.RegionSourcePalette palette =
                region.snapshotSourcePaletteIfChanged(source.paletteRevision());
        if (palette != null) source.updatePalette(palette);

        int regionWorldX = regionX * MapPageLayout.REGION_SIZE;
        int regionWorldZ = regionZ * MapPageLayout.REGION_SIZE;
        int localMinX = Math.max(0, windowStartX - regionWorldX);
        int localMinZ = Math.max(0, windowStartZ - regionWorldZ);
        int localMaxX = Math.min(MapPageLayout.REGION_SIZE - 1,
                windowEndX - regionWorldX);
        int localMaxZ = Math.min(MapPageLayout.REGION_SIZE - 1,
                windowEndZ - regionWorldZ);
        if (localMinX > localMaxX || localMinZ > localMaxZ) return source.acquireProbe();

        MapLightManager.LightRegion lightRegion = includeLight
                ? MapLightManager.getInstance().getRegion(regionX, regionZ, true) : null;
        int minChunkX = localMinX >>> 4;
        int maxChunkX = localMaxX >>> 4;
        int minChunkZ = localMinZ >>> 4;
        int maxChunkZ = localMaxZ >>> 4;
        /*
         * Do not allocate/sort a ChunkCoordinate object for every missing chunk on
         * every capture attempt. The previous path was especially expensive while
         * flying into new terrain: thousands of rejected plans created short-lived
         * lists and comparator traffic on the client thread. Three centre-out
         * priority tiers preserve the focus-body/halo ordering with zero per-chunk
         * allocation.
         */
        long focusCenterWorldX = ((long) focusWorldStartX + focusWorldEndX) >> 1;
        long focusCenterWorldZ = ((long) focusWorldStartZ + focusWorldEndZ) >> 1;
        int focusChunkX = clamp((int) Math.floorDiv(
                focusCenterWorldX - regionWorldX, 16L), minChunkX, maxChunkX);
        int focusChunkZ = clamp((int) Math.floorDiv(
                focusCenterWorldZ - regionWorldZ, 16L), minChunkZ, maxChunkZ);
        int maximumRing = Math.max(
                Math.max(Math.abs(focusChunkX - minChunkX),
                        Math.abs(maxChunkX - focusChunkX)),
                Math.max(Math.abs(focusChunkZ - minChunkZ),
                        Math.abs(maxChunkZ - focusChunkZ)));
        capture:
        for (int tier = 0; tier < 3; tier++) {
            for (int ring = 0; ring <= maximumRing; ring++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                        if (remainingCaptures == null || remainingCaptures[0] <= 0) {
                            break capture;
                        }
                        int chunkX = focusChunkX + dx;
                        int chunkZ = focusChunkZ + dz;
                        if (chunkX < minChunkX || chunkX > maxChunkX
                                || chunkZ < minChunkZ || chunkZ > maxChunkZ) continue;
                        if (captureTier(regionWorldX, regionWorldZ, chunkX, chunkZ,
                                focusWorldStartX, focusWorldStartZ,
                                focusWorldEndX, focusWorldEndZ) != tier) continue;
                        long revision = region.chunkRevision(chunkX, chunkZ);
                        if (!source.needsCapture(chunkX, chunkZ, revision)) continue;
                        captureSourceChunk(stamp, key, lane, source, region,
                                lightRegion, regionX, regionZ, chunkX, chunkZ,
                                remainingCaptures);
                    }
                }
            }
        }
        return source.acquireProbe();
    }

    private void captureSourceChunk(RevisionStamp stamp, SourceKey key,
            MapRequestLane lane, SurfaceRegionSource source,
            MapManager.Region region, MapLightManager.LightRegion lightRegion,
            int regionX, int regionZ, int chunkX, int chunkZ,
            int[] remainingCaptures) {
        // The live scanner can be part-way through a 16x16 chunk. Capturing that
        // transient state made a page look resident after only a few columns.
        if (!region.isChunkSurfaceComplete(chunkX, chunkZ)) return;
        remainingCaptures[0]--;
        byte[] light = null;
        if (lightRegion != null && lightRegion.isLoaded()) {
            light = lightRegion.snapshotWindow(chunkX * 16, chunkZ * 16, 16, 16);
        }
        MapMemoryLeaseManager.Lease chunkLease = acquireChunkLease(key, lane);
        if (chunkLease == null) return;
        MapManager.RegionChunkSnapshot captured =
                region.snapshotChunk(chunkX, chunkZ, light);
        if (captured == null) {
            chunkLease.close();
            return;
        }
        boolean committed = source.commit(ChunkSnapshot.takeOwnership(
                captured.localChunkX(), captured.localChunkZ(),
                captured.sourceRevision(), captured.packedPixelsUnsafe(),
                captured.tintsUnsafe(), captured.lightLevelsUnsafe()), chunkLease);
        if (committed) {
            MapWorkGraph.getInstance().clearSourceChunkDirty(stamp,
                    regionX, regionZ, chunkZ * 32 + chunkX);
        }
    }

    private static int captureTier(int regionWorldX, int regionWorldZ,
            int chunkX, int chunkZ, int focusWorldStartX, int focusWorldStartZ,
            int focusWorldEndX, int focusWorldEndZ) {
        int chunkWorldStartX = regionWorldX + chunkX * 16;
        int chunkWorldStartZ = regionWorldZ + chunkZ * 16;
        int chunkWorldEndX = chunkWorldStartX + 15;
        int chunkWorldEndZ = chunkWorldStartZ + 15;
        if (intersects(chunkWorldStartX, chunkWorldStartZ,
                chunkWorldEndX, chunkWorldEndZ, focusWorldStartX,
                focusWorldStartZ, focusWorldEndX, focusWorldEndZ)) return 0;
        return intersects(chunkWorldStartX, chunkWorldStartZ,
                chunkWorldEndX, chunkWorldEndZ,
                focusWorldStartX - MapPageLayout.PAGE_HALO,
                focusWorldStartZ - MapPageLayout.PAGE_HALO,
                focusWorldEndX + MapPageLayout.PAGE_HALO,
                focusWorldEndZ + MapPageLayout.PAGE_HALO) ? 1 : 2;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private synchronized int claimCaptureAllowance(MapRequestLane lane) {
        long now = System.nanoTime();
        boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
        if (captureWindowStartedNanos == 0L
                || now - captureWindowStartedNanos >= 16_000_000L) {
            captureWindowStartedNanos = now;
            captureWindowRemaining = pressured
                    ? (lane == MapRequestLane.MINIMAP ? 16 : 8)
                    : (lane == MapRequestLane.MINIMAP ? 64 : 48);
        }
        int requested = pressured
                ? (lane == MapRequestLane.MINIMAP ? 8 : 4)
                : (lane == MapRequestLane.MINIMAP ? 32 : 24);
        int granted = Math.min(requested, captureWindowRemaining);
        captureWindowRemaining -= granted;
        return granted;
    }

    private static void closeViews(SurfaceRegionSource.View[] views) {
        if (views == null) return;
        for (SurfaceRegionSource.View view : views) {
            if (view != null) view.close();
        }
    }

    private static void closeProbes(SurfaceRegionSource.Probe[] probes) {
        if (probes == null) return;
        for (SurfaceRegionSource.Probe probe : probes) {
            if (probe != null) probe.close();
        }
    }

    private static Readiness inspectReadiness(
            SurfaceRegionSource.Probe[] probes,
            int probeMinRegionX, int probeMinRegionZ,
            int probeRegionWidth, int probeRegionHeight,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int windowStartX, int windowStartZ, int windowEndX, int windowEndZ) {
        int required = 0;
        int present = 0;
        int missing = 0;
        int dirty = 0;
        int missingRegions = 0;
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                int relativeRegionX = regionX - probeMinRegionX;
                int relativeRegionZ = regionZ - probeMinRegionZ;
                SurfaceRegionSource.Probe probe = relativeRegionX < 0
                        || relativeRegionX >= probeRegionWidth
                        || relativeRegionZ < 0
                        || relativeRegionZ >= probeRegionHeight
                        ? null : probes[relativeRegionZ * probeRegionWidth
                                + relativeRegionX];
                int regionWorldX = regionX * MapPageLayout.REGION_SIZE;
                int regionWorldZ = regionZ * MapPageLayout.REGION_SIZE;
                int minX = Math.max(0, windowStartX - regionWorldX);
                int minZ = Math.max(0, windowStartZ - regionWorldZ);
                int maxX = Math.min(MapPageLayout.REGION_SIZE - 1,
                        windowEndX - regionWorldX);
                int maxZ = Math.min(MapPageLayout.REGION_SIZE - 1,
                        windowEndZ - regionWorldZ);
                if (minX > maxX || minZ > maxZ) continue;
                if (probe == null) missingRegions++;
                for (int chunkZ = minZ >>> 4; chunkZ <= maxZ >>> 4; chunkZ++) {
                    for (int chunkX = minX >>> 4; chunkX <= maxX >>> 4; chunkX++) {
                        required++;
                        int state = probe == null ? 0
                                : probe.chunkReadinessUnsafe(chunkX, chunkZ);
                        if (state == 0) {
                            missing++;
                        } else if (state == 1) {
                            dirty++;
                        } else {
                            present++;
                        }
                    }
                }
            }
        }
        return new Readiness(required, present, missing, dirty, missingRegions);
    }

    private static int probeIndex(int regionX, int regionZ,
            int minRegionX, int minRegionZ, int regionWidth) {
        return (regionZ - minRegionZ) * regionWidth + regionX - minRegionX;
    }

    private static long packRegion(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFF_FFFFL);
    }

    private static int unpackRegionX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackRegionZ(long packed) {
        return (int) packed;
    }

    private static boolean intersects(int leftMinX, int leftMinZ,
            int leftMaxX, int leftMaxZ, int rightMinX, int rightMinZ,
            int rightMaxX, int rightMaxZ) {
        return leftMinX <= rightMaxX && leftMaxX >= rightMinX
                && leftMinZ <= rightMaxZ && leftMaxZ >= rightMinZ;
    }

    private static long regionDistanceSquared(int regionX, int regionZ,
            int focusWorldX, int focusWorldZ) {
        long centerX = (long) regionX * MapPageLayout.REGION_SIZE
                + MapPageLayout.REGION_SIZE / 2L;
        long centerZ = (long) regionZ * MapPageLayout.REGION_SIZE
                + MapPageLayout.REGION_SIZE / 2L;
        long dx = centerX - focusWorldX;
        long dz = centerZ - focusWorldZ;
        return dx * dx + dz * dz;
    }

    private record Readiness(int requiredChunks, int presentChunks,
            int missingChunks, int dirtyChunks, int missingRegions) { }

    private SurfaceRegionSource getOrCreateSource(SourceKey key,
            RevisionStamp stamp, int regionX, int regionZ) {
        synchronized (sources) {
            pruneRetiredLocked();
            SurfaceRegionSource source = sources.get(key);
            if (source != null) return source;
            source = new SurfaceRegionSource(stamp, regionX, regionZ);
            sources.put(key, source);
            trimSources(key);
            return source;
        }
    }

    private MapMemoryLeaseManager.Lease acquireChunkLease(SourceKey protectedKey,
            MapRequestLane lane) {
        MapMemoryLeaseManager.Lease lease = MapMemoryLeaseManager.tryAcquire(
                MapMemoryLeaseManager.Category.PENDING_SOURCE,
                CHUNK_SOURCE_BYTES, lane);
        if (lease != null) return lease;
        synchronized (sources) {
            var iterator = sources.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<SourceKey, SurfaceRegionSource> entry = iterator.next();
                if (entry.getKey().equals(protectedKey)) continue;
                iterator.remove();
                retireSourceLocked(entry.getValue());
                break;
            }
        }
        return MapMemoryLeaseManager.tryAcquire(
                MapMemoryLeaseManager.Category.PENDING_SOURCE,
                CHUNK_SOURCE_BYTES, lane);
    }

    private void trimSources(SourceKey protectedKey) {
        while (sources.size() > MAX_SOURCE_REGIONS) {
            var iterator = sources.entrySet().iterator();
            if (!iterator.hasNext()) return;
            Map.Entry<SourceKey, SurfaceRegionSource> entry = iterator.next();
            if (entry.getKey().equals(protectedKey) && sources.size() > 1) {
                entry = null;
                while (iterator.hasNext()) {
                    Map.Entry<SourceKey, SurfaceRegionSource> candidate = iterator.next();
                    if (!candidate.getKey().equals(protectedKey)) {
                        entry = candidate;
                        break;
                    }
                }
                if (entry == null) return;
            }
            iterator.remove();
            retireSourceLocked(entry.getValue());
        }
    }

    private void retireSourceLocked(SurfaceRegionSource source) {
        if (source == null) return;
        retiredSources.add(source);
        source.close();
        pruneRetiredLocked();
    }

    private void pruneRetiredLocked() {
        retiredSources.removeIf(SurfaceRegionSource::released);
    }

    private static void copyRegionIntersection(RegionCoordinate coordinate,
            SurfaceRegionSource.View view, int windowStartX, int windowStartZ,
            int destinationWidth, int destinationHeight,
            long[] destinationPixels, int[] destinationTints,
            byte[] destinationLights, int[] biomeRemap, int[] blockRemap,
            BooleanSupplier valid) {
        int regionStartX = coordinate.regionX * MapPageLayout.REGION_SIZE;
        int regionStartZ = coordinate.regionZ * MapPageLayout.REGION_SIZE;
        int minWorldX = Math.max(windowStartX, regionStartX);
        int minWorldZ = Math.max(windowStartZ, regionStartZ);
        int maxWorldX = Math.min(windowStartX + destinationWidth - 1,
                regionStartX + MapPageLayout.REGION_SIZE - 1);
        int maxWorldZ = Math.min(windowStartZ + destinationHeight - 1,
                regionStartZ + MapPageLayout.REGION_SIZE - 1);
        if (minWorldX > maxWorldX || minWorldZ > maxWorldZ) return;

        int minChunkX = (minWorldX - regionStartX) >>> 4;
        int maxChunkX = (maxWorldX - regionStartX) >>> 4;
        int minChunkZ = (minWorldZ - regionStartZ) >>> 4;
        int maxChunkZ = (maxWorldZ - regionStartZ) >>> 4;
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (valid != null && !valid.getAsBoolean()) {
                    throw new CancellationException("Surface batch assembly cancelled");
                }
                ChunkSnapshot chunk = view.chunkUnsafe(chunkX, chunkZ);
                if (chunk == null) continue;
                long[] sourcePixels = chunk.packedPixelsUnsafe();
                int[] sourceTints = chunk.tintsUnsafe();
                byte[] sourceLights = chunk.lightLevelsUnsafe();
                int chunkWorldX = regionStartX + chunkX * 16;
                int chunkWorldZ = regionStartZ + chunkZ * 16;
                int copyMinX = Math.max(minWorldX, chunkWorldX);
                int copyMinZ = Math.max(minWorldZ, chunkWorldZ);
                int copyMaxX = Math.min(maxWorldX, chunkWorldX + 15);
                int copyMaxZ = Math.min(maxWorldZ, chunkWorldZ + 15);
                for (int worldZ = copyMinZ; worldZ <= copyMaxZ; worldZ++) {
                    int sourceRow = (worldZ - chunkWorldZ) * 16;
                    int destinationRow = (worldZ - windowStartZ) * destinationWidth;
                    for (int worldX = copyMinX; worldX <= copyMaxX; worldX++) {
                        int sourceIndex = sourceRow + worldX - chunkWorldX;
                        int destinationIndex = destinationRow + worldX - windowStartX;
                        long packed = sourcePixels[sourceIndex];
                        destinationTints[destinationIndex] = sourceTints[sourceIndex];
                        destinationLights[destinationIndex] = sourceLights[sourceIndex];
                        if (MapBlockData.isEmpty(packed)) continue;
                        short sourceBlock = MapBlockData.blockId(packed);
                        short outputBlock = MapBlockData.NO_BLOCK;
                        if (sourceBlock != MapBlockData.NO_BLOCK) {
                            int index = sourceBlock & 0xFFFF;
                            if (index < blockRemap.length) outputBlock = (short) blockRemap[index];
                        }
                        byte sourceBiome = MapBlockData.biomeId(packed);
                        byte outputBiome = MapBlockData.NO_BIOME;
                        if (sourceBiome != MapBlockData.NO_BIOME) {
                            int index = sourceBiome & 0xFF;
                            if (index < biomeRemap.length) outputBiome = (byte) biomeRemap[index];
                        }
                        destinationPixels[destinationIndex] = MapBlockData.packRaw(
                                MapBlockData.topY(packed), outputBlock, outputBiome,
                                MapBlockData.flags(packed), MapBlockData.floorY(packed));
                    }
                }
            }
        }
    }

    private static int[] buildBiomeRemap(String[] source, List<String> output,
            Map<String, Integer> indices) {
        int[] remap = new int[source.length];
        for (int index = 0; index < source.length; index++) {
            String id = source[index] == null || source[index].isBlank()
                    ? "minecraft:plains" : source[index];
            Integer mapped = indices.get(id);
            if (mapped == null) {
                mapped = output.size() >= 255 ? 254 : output.size();
                if (mapped == output.size()) output.add(id);
                indices.put(id, mapped);
            }
            remap[index] = mapped;
        }
        return remap;
    }

    private static int[] buildBlockRemap(String[] source, List<String> output,
            Map<String, Integer> indices) {
        int[] remap = new int[source.length];
        for (int index = 0; index < source.length; index++) {
            String id = source[index] == null || source[index].isBlank()
                    ? "minecraft:air" : source[index];
            Integer mapped = indices.get(id);
            if (mapped == null) {
                mapped = Math.min(65_534, output.size());
                if (mapped == output.size()) output.add(id);
                indices.put(id, mapped);
            }
            remap[index] = mapped;
        }
        return remap;
    }

    private record SourceKey(long sessionId, int regionX, int regionZ) { }
    private record RegionCoordinate(int regionX, int regionZ) { }
    private record RegionSlice(RegionCoordinate coordinate,
            SurfaceRegionSource.View view, int[] biomeRemap,
            int[] blockRemap) { }

    /**
     * Immutable, worker-consumable source transaction. It retains immutable chunk
     * snapshots and palette metadata but intentionally owns no assembled pixel
     * arrays. The retained memory lease reserves the future primitive window so
     * worker assembly cannot oversubscribe heap after client-thread admission.
     */
    public static final class BatchSourcePlan implements AutoCloseable {
        private final RevisionStamp stamp;
        private final int regionX;
        private final int regionZ;
        private final int batchPageX;
        private final int batchPageZ;
        private final int pagesWide;
        private final int pagesHigh;
        private final int worldPageStartX;
        private final int worldPageStartZ;
        private final int stride;
        private final int height;
        private final int halo;
        private final long sourceRevision;
        private final RegionSlice[] slices;
        private final List<String> biomePalette;
        private final List<String> blockPalette;
        private final MapMemoryLeaseManager.Lease memoryLease;
        private int activeAssemblies;
        private boolean closeRequested;
        private boolean closed;

        private BatchSourcePlan(RevisionStamp stamp, int regionX, int regionZ,
                int batchPageX, int batchPageZ, int pagesWide, int pagesHigh,
                int worldPageStartX, int worldPageStartZ, int stride, int height,
                int halo, long sourceRevision, RegionSlice[] slices,
                List<String> biomePalette, List<String> blockPalette,
                MapMemoryLeaseManager.Lease memoryLease) {
            this.stamp = stamp;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.batchPageX = batchPageX;
            this.batchPageZ = batchPageZ;
            this.pagesWide = pagesWide;
            this.pagesHigh = pagesHigh;
            this.worldPageStartX = worldPageStartX;
            this.worldPageStartZ = worldPageStartZ;
            this.stride = stride;
            this.height = height;
            this.halo = halo;
            this.sourceRevision = sourceRevision;
            this.slices = Arrays.copyOf(slices, slices.length);
            this.biomePalette = biomePalette;
            this.blockPalette = blockPalette;
            this.memoryLease = memoryLease;
        }

        public RevisionStamp stamp() { return stamp; }
        public int regionX() { return regionX; }
        public int regionZ() { return regionZ; }
        public int batchPageX() { return batchPageX; }
        public int batchPageZ() { return batchPageZ; }
        public int pagesWide() { return pagesWide; }
        public int pagesHigh() { return pagesHigh; }
        public int worldPageStartX() { return worldPageStartX; }
        public int worldPageStartZ() { return worldPageStartZ; }
        public int stride() { return stride; }
        public int height() { return height; }
        public int halo() { return halo; }
        public long sourceRevision() { return sourceRevision; }
        public List<String> biomePalette() { return biomePalette; }
        public List<String> blockPalette() { return blockPalette; }

        AssembledBatchWindow assemble(BooleanSupplier valid) {
            synchronized (this) {
                if (closed || closeRequested) {
                    throw new IllegalStateException("Batch source plan is closed");
                }
                activeAssemblies++;
            }
            long startedNanos = System.nanoTime();
            try {
                long[] pixels = new long[stride * height];
                int[] tints = new int[stride * height];
                byte[] lights = new byte[stride * height];
                Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
                Arrays.fill(tints, SurfaceTintData.UNKNOWN);
                int windowStartX = worldPageStartX - halo;
                int windowStartZ = worldPageStartZ - halo;
                for (RegionSlice slice : slices) {
                    if (valid != null && !valid.getAsBoolean()) {
                        throw new CancellationException("Surface batch assembly cancelled");
                    }
                    copyRegionIntersection(slice.coordinate(), slice.view(),
                            windowStartX, windowStartZ, stride, height,
                            pixels, tints, lights, slice.biomeRemap(),
                            slice.blockRemap(), valid);
                }
                return new AssembledBatchWindow(this, pixels, tints, lights);
            } finally {
                MapPipelineTelemetry.getInstance().recordStageNanos(
                        MapPipelineStage.SURFACE_ASSEMBLY,
                        System.nanoTime() - startedNanos);
                endAssembly();
            }
        }

        private synchronized void endAssembly() {
            if (activeAssemblies > 0) activeAssemblies--;
            if (activeAssemblies == 0 && closeRequested) releaseRetainedState();
        }

        @Override
        public synchronized void close() {
            if (closed || closeRequested) return;
            closeRequested = true;
            if (activeAssemblies == 0) releaseRetainedState();
        }

        private void releaseRetainedState() {
            if (closed) return;
            closed = true;
            for (RegionSlice slice : slices) slice.view().close();
            memoryLease.close();
        }
    }

    /** Worker-local primitive window assembled from a {@link BatchSourcePlan}. */
    static final class AssembledBatchWindow {
        private final BatchSourcePlan plan;
        private final long[] pixels;
        private final int[] tints;
        private final byte[] light;

        private AssembledBatchWindow(BatchSourcePlan plan, long[] pixels,
                int[] tints, byte[] light) {
            this.plan = plan;
            this.pixels = pixels;
            this.tints = tints;
            this.light = light;
        }

        RevisionStamp stamp() { return plan.stamp(); }
        int regionX() { return plan.regionX(); }
        int regionZ() { return plan.regionZ(); }
        int batchPageX() { return plan.batchPageX(); }
        int batchPageZ() { return plan.batchPageZ(); }
        int pagesWide() { return plan.pagesWide(); }
        int pagesHigh() { return plan.pagesHigh(); }
        int worldPageStartX() { return plan.worldPageStartX(); }
        int worldPageStartZ() { return plan.worldPageStartZ(); }
        int stride() { return plan.stride(); }
        int height() { return plan.height(); }
        int halo() { return plan.halo(); }
        long sourceRevision() { return plan.sourceRevision(); }
        List<String> biomePalette() { return plan.biomePalette(); }
        List<String> blockPalette() { return plan.blockPalette(); }
        long[] pixelsUnsafe() { return pixels; }
        int[] tintsUnsafe() { return tints; }
        byte[] lightUnsafe() { return light; }
    }
}
