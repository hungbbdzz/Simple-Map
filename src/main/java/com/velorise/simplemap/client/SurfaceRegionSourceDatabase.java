package com.velorise.simplemap.client;

import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int MAX_CAPTURE_REGIONS = 4;
    // Chunk completion wakes the affected capture immediately. Keep only a slow
    // safety retry for unchanged partial windows; rebuilding the same incomplete
    // palette/remap transaction twice per second was a major allocation spike.
    private static final long UNCHANGED_PARTIAL_BACKGROUND_RETRY_NANOS = 4_000_000_000L;
    private static final long UNCHANGED_PARTIAL_FOREGROUND_RETRY_NANOS = 180_000_000L;
    private static final long CHUNK_SOURCE_BYTES = 4_608L;
    private static final int MAX_REGION_WARM_IN_FLIGHT = 2;
    private static final int MAX_PAGE_WARM_IN_FLIGHT = 24;
    private static final ThreadLocal<CaptureScratch> CAPTURE_SCRATCH =
            ThreadLocal.withInitial(CaptureScratch::new);
    private final LinkedHashMap<SourceKey, SurfaceRegionSource> sources =
            new LinkedHashMap<>(32, 0.75f, true);
    private final Set<SurfaceRegionSource> retiredSources =
            new LinkedHashSet<>();
    private final Set<SourceKey> warmingRegions =
            ConcurrentHashMap.newKeySet();
    private final AtomicInteger warmingRegionCount = new AtomicInteger();
    private final Set<PageWarmKey> warmingPages = ConcurrentHashMap.newKeySet();
    private final AtomicInteger warmingPageCount = new AtomicInteger();
    /** Event-driven retry fence for loaded pages that made no retained-source progress. */
    private final ConcurrentHashMap<PageWarmKey, Long> pageWarmRetryAfterNanos =
            new ConcurrentHashMap<>();
    private static final long PAGE_WARM_NO_PROGRESS_RETRY_NANOS = 750_000_000L;
    /** Last materialized coverage per focused batch; avoids rebuilding unchanged partials. */
    private final Long2ObjectOpenHashMap<BatchProgressState> batchProgress =
            new Long2ObjectOpenHashMap<>(128);
    private final SurfaceCaptureFrameAllowance captureAllowance =
            new SurfaceCaptureFrameAllowance();
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

    /**
     * One retained chunk commit is the exact-page wake authority. Keeping this in
     * one helper prevents live scan, loaded-region warm and region-wide warm from
     * diverging again. The page-warm retry fence is also cleared so a newly arrived
     * chunk is consumed immediately instead of waiting for its no-progress timeout.
     */
    private void wakeExactPageForRetainedChunk(RevisionStamp stamp, SourceKey key,
            int localChunkX, int localChunkZ) {
        int localPageX = localChunkX >>> 2;
        int localPageZ = localChunkZ >>> 2;
        pageWarmRetryAfterNanos.remove(new PageWarmKey(
                key.sessionId(), key.regionX(), key.regionZ(),
                localPageX, localPageZ));
        int worldChunkX = key.regionX() * SurfaceRegionSource.CHUNKS_PER_AXIS
                + localChunkX;
        int worldChunkZ = key.regionZ() * SurfaceRegionSource.CHUNKS_PER_AXIS
                + localChunkZ;
        MapTextureManager.getInstance().markPageDirtyForChunk(
                worldChunkX, worldChunkZ);
        MapTextureManager.getInstance().wakeRegionCaptureForChunk(
                worldChunkX, worldChunkZ);
    }

    /**
     * Publishes one complete live 16x16 Surface chunk directly into the retained
     * source database. The old path waited for a later page capture probe to notice
     * that MapManager had finally reached 256 columns, which produced thousands of
     * partial/deferred probes while Cave source was already advancing by chunk.
     */
    public boolean publishCompletedChunk(RevisionStamp stamp, int chunkX,
            int chunkZ, MapRequestLane lane) {
        if (stamp == null || !stamp.isCurrent()) return false;
        int regionX = Math.floorDiv(chunkX, SurfaceRegionSource.CHUNKS_PER_AXIS);
        int regionZ = Math.floorDiv(chunkZ, SurfaceRegionSource.CHUNKS_PER_AXIS);
        int localChunkX = Math.floorMod(chunkX, SurfaceRegionSource.CHUNKS_PER_AXIS);
        int localChunkZ = Math.floorMod(chunkZ, SurfaceRegionSource.CHUNKS_PER_AXIS);
        MapManager.Region region = MapManager.getInstance().getRegion(
                regionX, regionZ, false);
        if (region == null || !region.isLoaded()
                || !region.isChunkSurfaceComplete(localChunkX, localChunkZ)) {
            return false;
        }
        SourceKey key = new SourceKey(stamp.sessionId(), regionX, regionZ);
        SurfaceRegionSource source = getOrCreateSource(key, stamp,
                regionX, regionZ);
        MapManager.RegionSourcePalette palette =
                region.snapshotSourcePaletteIfChanged(source.paletteRevision());
        if (palette != null) source.updatePalette(palette);
        long revision = region.chunkRevision(localChunkX, localChunkZ);
        if (!source.needsCapture(localChunkX, localChunkZ, revision)) return true;
        int localPageX = localChunkX >>> 2;
        int localPageZ = localChunkZ >>> 2;
        boolean leafWasReady = source.leafSourceReady(localPageX, localPageZ);

        byte[] light = null;
        MapLightManager.LightRegion lightRegion = MapLightManager.getInstance()
                .getRegion(regionX, regionZ, false);
        if (lightRegion != null && lightRegion.isLoaded()) {
            light = lightRegion.snapshotWindow(localChunkX << 4,
                    localChunkZ << 4, 16, 16);
        }
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        MapMemoryLeaseManager.Lease lease = acquireChunkLease(key, effectiveLane);
        if (lease == null) return false;
        MapManager.RegionChunkSnapshot captured = region.snapshotChunk(
                localChunkX, localChunkZ, light);
        if (captured == null) {
            lease.close();
            return false;
        }
        boolean committed = source.commit(ChunkSnapshot.takeOwnership(
                captured.localChunkX(), captured.localChunkZ(),
                captured.sourceRevision(), captured.packedPixelsUnsafe(),
                captured.tintsUnsafe(), captured.lightLevelsUnsafe()), lease);
        if (!committed) return false;
        int localChunkIndex = localChunkZ * SurfaceRegionSource.CHUNKS_PER_AXIS
                + localChunkX;
        MapWorkGraph.getInstance().clearSourceChunkDirty(stamp,
                regionX, regionZ, localChunkIndex);
        /*
         * The retained source commit is the single publication authority. Do not
         * depend on the caller also remembering to dirty the exact page: live scans,
         * cache replay and saved-world reconstruction all converge through this path.
         * dirtyPageQueued coalesces the harmless duplicate from the live scanner.
         */
        wakeExactPageForRetainedChunk(stamp, key, localChunkX, localChunkZ);
        if (!leafWasReady && source.leafSourceReady(localPageX, localPageZ)) {
            Minecraft minecraft = Minecraft.getInstance();
            Runnable wakeLod = () -> RegionSurfaceLodService.getInstance()
                    .onRegionSourceWarmed(regionX, regionZ);
            if (minecraft.isSameThread()) wakeLod.run();
            else minecraft.execute(wakeLod);
        }
        return true;
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

    /**
     * Clean retained 16x16 source chunks currently available to one exact leaf.
     * The live minimap uses this instead of consulting MapManager.Region.
     */
    public int leafPresentSubtileMask(RevisionStamp stamp, int regionX,
            int regionZ, int localPageX, int localPageZ) {
        if (stamp == null || !stamp.isCurrent()) return 0;
        synchronized (sources) {
            SurfaceRegionSource source = sources.get(new SourceKey(
                    stamp.sessionId(), regionX, regionZ));
            return source == null ? 0
                    : source.leafPresentSubtileMask(localPageX, localPageZ);
        }
    }

    /**
     * Warms only one 64x64 exact page (4x4 chunks) into the retained source.
     *
     * Renderer demand is page-scoped. Scanning all 32x32 chunks of a loaded
     * MapManager.Region for every unresolved exact page made the writer do up to
     * 1024 revision checks when Xaero's MapTileChunk transaction needs only 16.
     * Region-wide warming remains available for coarse/LOD reconstruction.
     */
    public boolean warmLoadedPage(RevisionStamp stamp, int regionX, int regionZ,
            int localPageX, int localPageZ, MapRequestLane lane) {
        if (stamp == null || !stamp.isCurrent()
                || localPageX < 0 || localPageX >= MapPageLayout.PAGES_PER_REGION
                || localPageZ < 0 || localPageZ >= MapPageLayout.PAGES_PER_REGION) {
            return false;
        }
        PageWarmKey warmKey = new PageWarmKey(stamp.sessionId(), regionX, regionZ,
                localPageX, localPageZ);
        Long retryAfter = pageWarmRetryAfterNanos.get(warmKey);
        long nowNanos = System.nanoTime();
        if (retryAfter != null) {
            if (nowNanos < retryAfter) return true;
            pageWarmRetryAfterNanos.remove(warmKey, retryAfter);
        }
        if (!warmingPages.add(warmKey)) return true;
        if (warmingPageCount.incrementAndGet() > MAX_PAGE_WARM_IN_FLIGHT) {
            releaseWarmPage(warmKey);
            return false;
        }
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        CompletableFuture<Integer> future = MapWorkScheduler.tryCpuFuture(
                effectiveLane, MapWorkScheduler.WorkType.SOURCE_DECODE,
                effectiveLane.priorityBase() + 180_000, 16,
                stamp::isCurrent,
                () -> warmLoadedPageNow(stamp, warmKey, effectiveLane));
        if (future == null) {
            releaseWarmPage(warmKey);
            return false;
        }
        future.whenComplete((captured, failure) -> {
            releaseWarmPage(warmKey);
            int progress = captured == null ? 0 : captured;
            if (failure == null && progress > 0 && stamp.isCurrent()) {
                pageWarmRetryAfterNanos.remove(warmKey);
                Minecraft.getInstance().execute(() ->
                        RegionSurfaceLodService.getInstance()
                                .onRegionSourceWarmed(regionX, regionZ));
            } else if (failure == null && stamp.isCurrent()) {
                // Xaero advances a persistent writer cursor instead of rescanning an
                // unavailable tile every render frame. Retain the same event-driven
                // behaviour here: chunk publication clears this fence immediately.
                pageWarmRetryAfterNanos.put(warmKey, System.nanoTime()
                        + PAGE_WARM_NO_PROGRESS_RETRY_NANOS);
            }
        });
        return true;
    }

    private void releaseWarmPage(PageWarmKey key) {
        warmingPages.remove(key);
        warmingPageCount.updateAndGet(value -> Math.max(0, value - 1));
    }

    private int warmLoadedPageNow(RevisionStamp stamp, PageWarmKey key,
            MapRequestLane lane) {
        MapManager.Region region = MapManager.getInstance().getRegion(
                key.regionX(), key.regionZ(), false);
        if (region == null || !region.isLoaded() || !stamp.isCurrent()) return 0;
        SourceKey sourceKey = new SourceKey(
                key.sessionId(), key.regionX(), key.regionZ());
        SurfaceRegionSource source = getOrCreateSource(
                sourceKey, stamp, key.regionX(), key.regionZ());
        MapManager.RegionSourcePalette palette =
                region.snapshotSourcePaletteIfChanged(source.paletteRevision());
        if (palette != null) source.updatePalette(palette);

        int startChunkX = key.localPageX() * 4;
        int startChunkZ = key.localPageZ() * 4;
        int captured = 0;
        for (int chunkZ = startChunkZ;
                chunkZ < startChunkZ + 4 && stamp.isCurrent(); chunkZ++) {
            for (int chunkX = startChunkX;
                    chunkX < startChunkX + 4 && stamp.isCurrent(); chunkX++) {
                long revision = region.chunkRevision(chunkX, chunkZ);
                if (!source.needsCapture(chunkX, chunkZ, revision)
                        || !region.isChunkSurfaceComplete(chunkX, chunkZ)) {
                    continue;
                }
                MapMemoryLeaseManager.Lease chunkLease =
                        acquireChunkLease(sourceKey, lane);
                if (chunkLease == null) return captured;
                MapManager.RegionChunkSnapshot snapshot =
                        region.snapshotChunk(chunkX, chunkZ, null);
                if (snapshot == null) {
                    chunkLease.close();
                    continue;
                }
                boolean committed = source.commit(ChunkSnapshot.takeOwnership(
                        snapshot.localChunkX(), snapshot.localChunkZ(),
                        snapshot.sourceRevision(), snapshot.packedPixelsUnsafe(),
                        snapshot.tintsUnsafe(), snapshot.lightLevelsUnsafe()),
                        chunkLease);
                if (!committed) continue;
                captured++;
                MapWorkGraph.getInstance().clearSourceChunkDirty(stamp,
                        key.regionX(), key.regionZ(), chunkZ * 32 + chunkX);
                wakeExactPageForRetainedChunk(stamp, sourceKey, chunkX, chunkZ);
            }
        }
        return captured;
    }

    public boolean warmLoadedRegion(RevisionStamp stamp, int regionX,
            int regionZ, MapRequestLane lane) {
        if (stamp == null || !stamp.isCurrent()) return false;
        SourceKey key = new SourceKey(stamp.sessionId(), regionX, regionZ);
        if (!warmingRegions.add(key)) return true;
        if (warmingRegionCount.incrementAndGet() > MAX_REGION_WARM_IN_FLIGHT) {
            releaseWarmRegion(key);
            return false;
        }
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        CompletableFuture<Integer> future = MapWorkScheduler.tryCpuFuture(
                effectiveLane, MapWorkScheduler.WorkType.SOURCE_DECODE,
                effectiveLane.priorityBase() + 120_000, 48,
                stamp::isCurrent,
                () -> warmLoadedRegionNow(stamp, key, effectiveLane));
        if (future == null) {
            releaseWarmRegion(key);
            return false;
        }
        future.whenComplete((captured, failure) -> {
            releaseWarmRegion(key);
            if (failure == null && captured != null && captured > 0
                    && stamp.isCurrent()) {
                Minecraft.getInstance().execute(() ->
                        RegionSurfaceLodService.getInstance()
                                .onRegionSourceWarmed(key.regionX(), key.regionZ()));
            }
        });
        return true;
    }

    private void releaseWarmRegion(SourceKey key) {
        warmingRegions.remove(key);
        warmingRegionCount.updateAndGet(value -> Math.max(0, value - 1));
    }

    private int warmLoadedRegionNow(RevisionStamp stamp, SourceKey key,
            MapRequestLane lane) {
        MapManager.Region region = MapManager.getInstance().getRegion(
                key.regionX(), key.regionZ(), false);
        if (region == null || !region.isLoaded() || !stamp.isCurrent()) return 0;
        SurfaceRegionSource source = getOrCreateSource(key, stamp,
                key.regionX(), key.regionZ());
        MapManager.RegionSourcePalette palette =
                region.snapshotSourcePaletteIfChanged(source.paletteRevision());
        if (palette != null) source.updatePalette(palette);
        int captured = 0;
        for (int chunkZ = 0; chunkZ < 32 && stamp.isCurrent(); chunkZ++) {
            for (int chunkX = 0; chunkX < 32 && stamp.isCurrent(); chunkX++) {
                long revision = region.chunkRevision(chunkX, chunkZ);
                if (!source.needsCapture(chunkX, chunkZ, revision)
                        || !region.isChunkSurfaceComplete(chunkX, chunkZ)) {
                    continue;
                }
                MapMemoryLeaseManager.Lease chunkLease =
                        acquireChunkLease(key, lane);
                if (chunkLease == null) return captured;
                MapManager.RegionChunkSnapshot snapshot =
                        region.snapshotChunk(chunkX, chunkZ, null);
                if (snapshot == null) {
                    chunkLease.close();
                    continue;
                }
                boolean committed = source.commit(ChunkSnapshot.takeOwnership(
                        snapshot.localChunkX(), snapshot.localChunkZ(),
                        snapshot.sourceRevision(), snapshot.packedPixelsUnsafe(),
                        snapshot.tintsUnsafe(), snapshot.lightLevelsUnsafe()),
                        chunkLease);
                if (committed) {
                    captured++;
                    MapWorkGraph.getInstance().clearSourceChunkDirty(stamp,
                            key.regionX(), key.regionZ(), chunkZ * 32 + chunkX);
                    wakeExactPageForRetainedChunk(stamp, key, chunkX, chunkZ);
                }
            }
        }
        return captured;
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
        synchronized (batchProgress) {
            batchProgress.clear();
        }
        resetCaptureAllowance();
        warmingRegions.removeIf(key -> key.sessionId() == sessionId);
        warmingPages.removeIf(key -> key.sessionId() == sessionId);
        pageWarmRetryAfterNanos.keySet().removeIf(
                key -> key.sessionId() == sessionId);
    }

    public void clear() {
        synchronized (sources) {
            for (SurfaceRegionSource source : sources.values()) {
                retireSourceLocked(source);
            }
            sources.clear();
            resetDebugCounters();
        }
        synchronized (batchProgress) {
            batchProgress.clear();
        }
        resetCaptureAllowance();
        warmingRegions.clear();
        warmingRegionCount.set(0);
        warmingPages.clear();
        warmingPageCount.set(0);
        pageWarmRetryAfterNanos.clear();
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

        CaptureScratch scratch = CAPTURE_SCRATCH.get();
        MapMemoryLeaseManager.Lease memoryLease = null;
        try {
            int halo = MapPageLayout.PAGE_HALO;
            int width = pagesWide * MapPageLayout.PAGE_SIZE + halo * 2;
            int height = pagesHigh * MapPageLayout.PAGE_SIZE + halo * 2;
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
            if (sourceRegionCount <= 0 || sourceRegionCount > MAX_CAPTURE_REGIONS) {
                return null;
            }
            scratch.begin(sourceRegionCount, claimCaptureAllowance(lane),
                    captureAttemptBudgetNanos(lane));

            int coordinateCount = 0;
            for (int sourceRegionZ = minRegionZ; sourceRegionZ <= maxRegionZ;
                    sourceRegionZ++) {
                for (int sourceRegionX = minRegionX; sourceRegionX <= maxRegionX;
                        sourceRegionX++) {
                    scratch.coordinatePlan[coordinateCount++] = packRegion(
                            sourceRegionX, sourceRegionZ);
                }
            }
            int focusWorldX = focusWorldStartX + MapPageLayout.PAGE_SIZE / 2;
            int focusWorldZ = focusWorldStartZ + MapPageLayout.PAGE_SIZE / 2;
            for (int i = 1; i < coordinateCount; i++) {
                long candidate = scratch.coordinatePlan[i];
                long candidateDistance = regionDistanceSquared(
                        unpackRegionX(candidate), unpackRegionZ(candidate),
                        focusWorldX, focusWorldZ);
                int insertion = i;
                while (insertion > 0) {
                    long previous = scratch.coordinatePlan[insertion - 1];
                    long previousDistance = regionDistanceSquared(
                            unpackRegionX(previous), unpackRegionZ(previous),
                            focusWorldX, focusWorldZ);
                    if (previousDistance <= candidateDistance) break;
                    scratch.coordinatePlan[insertion] = previous;
                    insertion--;
                }
                scratch.coordinatePlan[insertion] = candidate;
            }

            for (int coordinateIndex = 0; coordinateIndex < coordinateCount;
                    coordinateIndex++) {
                long packedCoordinate = scratch.coordinatePlan[coordinateIndex];
                int sourceRegionX = unpackRegionX(packedCoordinate);
                int sourceRegionZ = unpackRegionZ(packedCoordinate);
                int probeIndex = probeIndex(sourceRegionX, sourceRegionZ,
                        minRegionX, minRegionZ, sourceRegionWidth);
                SurfaceRegionSource.Probe probe = refreshAndProbe(stamp,
                        sourceRegionX, sourceRegionZ, windowStartX,
                        windowStartZ, windowEndX, windowEndZ,
                        focusWorldStartX, focusWorldStartZ,
                        focusWorldEndX, focusWorldEndZ,
                        includeLight, lane, scratch.captureBudget);
                scratch.probes[probeIndex] = probe;
                if (probe != null) {
                    probe.copyReadinessUnsafe(scratch.presentMasks,
                            scratch.dirtyMasks,
                            probeIndex * SurfaceRegionSource.DIRTY_WORDS,
                            scratch.revisions, probeIndex * 2);
                }
            }

            Readiness readiness = inspectReadiness(scratch,
                    minRegionX, minRegionZ, sourceRegionWidth, sourceRegionHeight,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    windowStartX, windowStartZ, windowEndX, windowEndZ);
            Readiness focusBodyReadiness = inspectReadiness(scratch,
                    minRegionX, minRegionZ, sourceRegionWidth, sourceRegionHeight,
                    Math.floorDiv(focusWorldStartX, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldEndX, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldStartZ, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldEndZ, MapPageLayout.REGION_SIZE),
                    focusWorldStartX, focusWorldStartZ,
                    focusWorldEndX, focusWorldEndZ);
            Readiness focusReadiness = inspectReadiness(scratch,
                    minRegionX, minRegionZ, sourceRegionWidth, sourceRegionHeight,
                    Math.floorDiv(focusWorldStartX - halo, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldEndX + halo, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldStartZ - halo, MapPageLayout.REGION_SIZE),
                    Math.floorDiv(focusWorldEndZ + halo, MapPageLayout.REGION_SIZE),
                    focusWorldStartX - halo, focusWorldStartZ - halo,
                    focusWorldEndX + halo, focusWorldEndZ + halo);

            // Publication follows the Minecraft chunk, not the 64x64 storage page.
            // MapTextureManager already turns every complete 16x16 body chunk into
            // a subtile bit and uploads only those bits. Holding this plan until all
            // sixteen chunks exist defeated that path and left End/remote maps black.
            // Unknown chunks stay masked; previously published subtiles stay visible.
            boolean pipelineReady = focusBodyReadiness.requiredChunks() > 0
                    && focusBodyReadiness.presentChunks() > 0;
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

            if (!pipelineReady) {
                captureDeferred.incrementAndGet();
                emitReadinessEvent("BATCH_SOURCE_DEFERRED", stamp,
                        regionX, regionZ, batchPageX, batchPageZ,
                        pagesWide, pagesHigh, readiness,
                        focusBodyReadiness, focusReadiness);
                return null;
            }

            long progressSignature = mixFingerprint(readiness.fingerprint()
                    ^ Long.rotateLeft(focusBodyReadiness.fingerprint(), 17)
                    ^ Long.rotateLeft(focusReadiness.fingerprint(), 39)
                    ^ stamp.sourceGeneration()
                    ^ Long.rotateLeft(stamp.styleGeneration(), 11)
                    ^ Long.rotateLeft(stamp.projectionGeneration(), 29));
            long progressKey = batchProgressKey(stamp.sessionId(), regionX, regionZ,
                    batchPageX, batchPageZ, focusPageX, focusPageZ,
                    pagesWide, pagesHigh, includeLight);
            long nowNanos = System.nanoTime();
            if (!shouldMaterializeBatch(progressKey, stamp.sessionId(), regionX,
                    regionZ, batchPageX, batchPageZ, focusPageX, focusPageZ,
                    pagesWide, pagesHigh, includeLight, progressSignature,
                    strictReady, lane, nowNanos)) {
                captureDeferred.incrementAndGet();
                return null;
            }

            long bytes = (long) width * height
                    * (Long.BYTES + Integer.BYTES + Byte.BYTES + Byte.BYTES);
            memoryLease = MapMemoryLeaseManager.tryAcquire(
                    MapMemoryLeaseManager.Category.PENDING_SOURCE,
                    bytes, lane);
            if (memoryLease == null) return null;

            if (strictReady) {
                captureReady.incrementAndGet();
            } else {
                partialReady.incrementAndGet();
                emitReadinessEvent("BATCH_PARTIAL_READY", stamp,
                        regionX, regionZ, batchPageX, batchPageZ,
                        pagesWide, pagesHigh, readiness,
                        focusBodyReadiness, focusReadiness);
            }

            RegionProbeSlice[] slices = new RegionProbeSlice[sourceRegionCount];
            int sliceCount = 0;
            long sourceRevision = 0L;
            for (int index = 0; index < sourceRegionCount; index++) {
                SurfaceRegionSource.Probe probe = scratch.probes[index];
                if (probe == null) continue;
                SurfaceRegionSource.ProbeMetadata metadata =
                        probe.snapshotMetadataUnsafe();
                // Palette identity must stay stable across admission. Source data
                // itself may advance meanwhile: a newer chunk snapshot with the
                // same palette is safe and is preferable to cancelling a minimap
                // plan just because an unrelated chunk in this 512x512 region
                // published during the capture.
                if (metadata == null
                        || metadata.paletteRevision() != scratch.revisions[index * 2 + 1]) {
                    captureDeferred.incrementAndGet();
                    return null;
                }
                int coordinateX = minRegionX + index % sourceRegionWidth;
                int coordinateZ = minRegionZ + index / sourceRegionWidth;
                sourceRevision = Math.max(sourceRevision, metadata.sourceRevision());
                slices[sliceCount++] = new RegionProbeSlice(
                        new RegionCoordinate(coordinateX, coordinateZ), probe,
                        metadata.sourceRevision(), metadata.paletteRevision(),
                        metadata.biomePalette(), metadata.blockPalette());
            }
            RegionProbeSlice[] retainedSlices = sliceCount == slices.length
                    ? slices : Arrays.copyOf(slices, sliceCount);
            BatchSourcePlan plan = new BatchSourcePlan(stamp, regionX, regionZ,
                    batchPageX, batchPageZ, pagesWide, pagesHigh,
                    worldPageStartX, worldPageStartZ, width, height, halo,
                    Math.max(1L, sourceRevision), retainedSlices, memoryLease);
            // Transfer all probe lifetime pins atomically only after the plan was
            // constructed successfully. The finally block still owns them before
            // this point and therefore closes every pin on any admission failure.
            for (int index = 0; index < sourceRegionCount; index++) {
                scratch.probes[index] = null;
            }
            memoryLease = null;
            markBatchMaterialized(progressKey, stamp.sessionId(), regionX, regionZ,
                    batchPageX, batchPageZ, focusPageX, focusPageZ,
                    pagesWide, pagesHigh, includeLight, progressSignature,
                    strictReady, nowNanos);
            return plan;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } finally {
            scratch.closeProbes(MAX_CAPTURE_REGIONS);
            if (memoryLease != null) memoryLease.close();
            long captureNanos = System.nanoTime() - startedNanos;
            MapPipelineTelemetry.getInstance().recordStageNanos(
                    MapPipelineStage.SURFACE_CAPTURE, captureNanos);
            // SURFACE_CAPTURE intentionally covers the complete retained-source
            // plan, not only mutable Region.snapshotChunk(). Emit a sampled slow
            // event with lane/geometry so the next log can distinguish a harmless
            // fullscreen throughput spike from a latency-sensitive minimap capture.
            if (captureNanos >= 4_000_000L) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String slowKey = "SURFACE_CAPTURE_SLOW:"
                        + (lane == null ? "null" : lane.name());
                if (recorder.shouldEmitEvent(slowKey, 250L)) {
                    recorder.event("SURFACE_CAPTURE_SLOW",
                            "lane=" + lane + " pages=" + pagesWide + 'x' + pagesHigh
                                    + " focus=" + focusPageX + ',' + focusPageZ
                                    + " elapsed_ms="
                                    + String.format(java.util.Locale.ROOT, "%.3f",
                                            captureNanos / 1_000_000.0));
                }
            }
        }
    }

    private SurfaceRegionSource.Probe refreshAndProbe(RevisionStamp stamp,
            int regionX, int regionZ, int windowStartX, int windowStartZ,
            int windowEndX, int windowEndZ, int focusWorldStartX,
            int focusWorldStartZ, int focusWorldEndX, int focusWorldEndZ,
            boolean includeLight, MapRequestLane lane,
            CaptureBudget remainingCaptures) {
        SourceKey key = new SourceKey(stamp.sessionId(), regionX, regionZ);

        /*
         * PASS109 / Xaero single retained authority:
         *
         * No renderer lane is allowed to rebuild source state while capturing a
         * batch plan. PASS108 kept MINIMAP probe-only but FULLSCREEN/BACKGROUND
         * could still snapshot MapManager.Region here. The same logical page could
         * therefore be considered ready by two different authorities and revisions,
         * which is exactly the sort of lane-dependent asynchronous publication seen
         * in the PASS108 run.
         *
         * Live ChunkScanner, saved-world reconstruction and region warming all
         * converge through publishCompletedChunk()/warmLoadedPage()/
         * warmLoadedRegion(). Rendering
         * consumes only the immutable retained source. If it is not ready, the
         * demand path requests/wakes the writer and this probe simply defers.
         */
        synchronized (sources) {
            SurfaceRegionSource retained = sources.get(key);
            return retained == null ? null : retained.acquireProbe();
        }
    }

    private void captureSourceChunk(RevisionStamp stamp, SourceKey key,
            MapRequestLane lane, SurfaceRegionSource source,
            MapManager.Region region, MapLightManager.LightRegion lightRegion,
            int regionX, int regionZ, int chunkX, int chunkZ,
            CaptureBudget remainingCaptures) {
        // The live scanner can be part-way through a 16x16 chunk. Capturing that
        // transient state made a page look resident after only a few columns.
        if (!region.isChunkSurfaceComplete(chunkX, chunkZ)) return;
        remainingCaptures.remaining--;
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

    /**
     * Opens the source-capture allowance for one actual rendered frame. A slow
     * 35-60 ms frame must not refill a nominal 16 ms wall-clock window two or
     * three times while the same physical frame is still executing.
     */
    public void beginPublicationFrame(long frameId) {
        captureAllowance.beginFrame(frameId);
    }

    private int claimCaptureAllowance(MapRequestLane lane) {
        return captureAllowance.claim(lane == MapRequestLane.MINIMAP,
                MapPerformanceGovernor.getInstance().underPressure(),
                System.nanoTime());
    }

    private void resetCaptureAllowance() {
        captureAllowance.reset();
    }

    /**
     * Count limits alone cannot protect frame time because a newly completed
     * water/modded chunk can cost far more than a cached flat chunk. Each capture
     * attempt therefore gets a short wall-clock slice in addition to the shared
     * per-frame chunk allowance.
     */
    private static long captureAttemptBudgetNanos(MapRequestLane lane) {
        boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
        if (pressured) {
            return lane == MapRequestLane.MINIMAP ? 450_000L : 250_000L;
        }
        if (lane == MapRequestLane.MINIMAP) return 1_000_000L;
        if (lane == MapRequestLane.FULLSCREEN) return 750_000L;
        return 400_000L;
    }

    private static Readiness inspectReadiness(CaptureScratch scratch,
            int probeMinRegionX, int probeMinRegionZ,
            int probeRegionWidth, int probeRegionHeight,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int windowStartX, int windowStartZ, int windowEndX, int windowEndZ) {
        int required = 0;
        int present = 0;
        int missing = 0;
        int dirty = 0;
        int missingRegions = 0;
        long fingerprint = 0x9E3779B97F4A7C15L;
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                int relativeRegionX = regionX - probeMinRegionX;
                int relativeRegionZ = regionZ - probeMinRegionZ;
                int regionIndex = relativeRegionX < 0
                        || relativeRegionX >= probeRegionWidth
                        || relativeRegionZ < 0
                        || relativeRegionZ >= probeRegionHeight
                        ? -1 : relativeRegionZ * probeRegionWidth
                                + relativeRegionX;
                int regionWorldX = regionX * MapPageLayout.REGION_SIZE;
                int regionWorldZ = regionZ * MapPageLayout.REGION_SIZE;
                int minX = Math.max(0, windowStartX - regionWorldX);
                int minZ = Math.max(0, windowStartZ - regionWorldZ);
                int maxX = Math.min(MapPageLayout.REGION_SIZE - 1,
                        windowEndX - regionWorldX);
                int maxZ = Math.min(MapPageLayout.REGION_SIZE - 1,
                        windowEndZ - regionWorldZ);
                if (minX > maxX || minZ > maxZ) continue;
                if (regionIndex < 0 || scratch.probes[regionIndex] == null) {
                    missingRegions++;
                    fingerprint = mixFingerprint(fingerprint
                            ^ packRegion(regionX, regionZ));
                } else {
                    fingerprint = mixFingerprint(fingerprint
                            ^ scratch.revisions[regionIndex * 2]
                            ^ Long.rotateLeft(
                                    scratch.revisions[regionIndex * 2 + 1], 23));
                }
                for (int chunkZ = minZ >>> 4; chunkZ <= maxZ >>> 4; chunkZ++) {
                    for (int chunkX = minX >>> 4; chunkX <= maxX >>> 4; chunkX++) {
                        required++;
                        int state = 0;
                        if (regionIndex >= 0 && scratch.probes[regionIndex] != null) {
                            int chunkIndex = chunkZ * SurfaceRegionSource.CHUNKS_PER_AXIS
                                    + chunkX;
                            int wordIndex = regionIndex
                                    * SurfaceRegionSource.DIRTY_WORDS
                                    + (chunkIndex >>> 6);
                            long bit = 1L << (chunkIndex & 63);
                            if ((scratch.presentMasks[wordIndex] & bit) != 0L) {
                                state = (scratch.dirtyMasks[wordIndex] & bit) != 0L
                                        ? 1 : 2;
                            }
                        }
                        if (state == 0) missing++;
                        else if (state == 1) dirty++;
                        else present++;
                        long position = ((long) (regionZ - minRegionZ) << 24)
                                ^ ((long) (regionX - minRegionX) << 16)
                                ^ ((long) chunkZ << 8) ^ chunkX;
                        fingerprint = mixFingerprint(fingerprint
                                ^ (position << 2) ^ state);
                    }
                }
            }
        }
        return new Readiness(required, present, missing, dirty, missingRegions,
                fingerprint);
    }

    private void emitReadinessEvent(String type, RevisionStamp stamp,
            int regionX, int regionZ, int batchPageX, int batchPageZ,
            int pagesWide, int pagesHigh, Readiness readiness,
            Readiness focusBodyReadiness, Readiness focusReadiness) {
        String batchId = regionX + "," + regionZ + ":" + batchPageX + ","
                + batchPageZ + ":" + pagesWide + "x" + pagesHigh;
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        if (!recorder.shouldEmitEvent(type + ':' + batchId, 1000L)) return;
        recorder.event(type,
                "batch=" + batchId + " session=" + stamp.sessionId()
                        + " required=" + readiness.requiredChunks()
                        + " present=" + readiness.presentChunks()
                        + " missing=" + readiness.missingChunks()
                        + " dirty=" + readiness.dirtyChunks()
                        + " body_present=" + focusBodyReadiness.presentChunks()
                        + " body_missing=" + focusBodyReadiness.missingChunks()
                        + " body_dirty=" + focusBodyReadiness.dirtyChunks()
                        + " focus_present=" + focusReadiness.presentChunks()
                        + " focus_missing=" + focusReadiness.missingChunks()
                        + " focus_dirty=" + focusReadiness.dirtyChunks());
    }

    private boolean shouldMaterializeBatch(long key, long sessionId,
            int regionX, int regionZ, int batchPageX, int batchPageZ,
            int focusPageX, int focusPageZ, int pagesWide, int pagesHigh,
            boolean includeLight, long signature, boolean strictReady,
            MapRequestLane lane, long nowNanos) {
        synchronized (batchProgress) {
            BatchProgressState state = batchProgress.get(key);
            if (state == null || !state.matches(sessionId, regionX, regionZ,
                    batchPageX, batchPageZ, focusPageX, focusPageZ,
                    pagesWide, pagesHigh, includeLight)) {
                return true;
            }
            if (state.signature != signature || (strictReady && !state.strictReady)) {
                return true;
            }
            long retryNanos = lane == MapRequestLane.MINIMAP
                    || lane == MapRequestLane.FULLSCREEN
                    ? UNCHANGED_PARTIAL_FOREGROUND_RETRY_NANOS
                    : UNCHANGED_PARTIAL_BACKGROUND_RETRY_NANOS;
            return nowNanos - state.materializedNanos >= retryNanos;
        }
    }

    private void markBatchMaterialized(long key, long sessionId,
            int regionX, int regionZ, int batchPageX, int batchPageZ,
            int focusPageX, int focusPageZ, int pagesWide, int pagesHigh,
            boolean includeLight, long signature, boolean strictReady,
            long nowNanos) {
        synchronized (batchProgress) {
            batchProgress.put(key, new BatchProgressState(sessionId,
                    regionX, regionZ, batchPageX, batchPageZ,
                    focusPageX, focusPageZ, pagesWide, pagesHigh,
                    includeLight, signature, strictReady, nowNanos));
            if (batchProgress.size() > 512) {
                var iterator = batchProgress.long2ObjectEntrySet().iterator();
                while (batchProgress.size() > 384 && iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        }
    }

    private static long batchProgressKey(long sessionId, int regionX, int regionZ,
            int batchPageX, int batchPageZ, int focusPageX, int focusPageZ,
            int pagesWide, int pagesHigh, boolean includeLight) {
        long value = sessionId;
        value = mixFingerprint(value ^ regionX);
        value = mixFingerprint(value ^ Long.rotateLeft(regionZ, 13));
        value = mixFingerprint(value ^ ((long) batchPageX << 3) ^ batchPageZ);
        value = mixFingerprint(value ^ ((long) focusPageX << 11)
                ^ ((long) focusPageZ << 15));
        value = mixFingerprint(value ^ ((long) pagesWide << 19)
                ^ ((long) pagesHigh << 23) ^ (includeLight ? 1L << 31 : 0L));
        return value;
    }

    private static long mixFingerprint(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
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
            int missingChunks, int dirtyChunks, int missingRegions,
            long fingerprint) { }

    private static final class CaptureBudget {
        private int remaining;
        private long deadlineNanos;
    }

    private static final class CaptureScratch {
        private final SurfaceRegionSource.Probe[] probes =
                new SurfaceRegionSource.Probe[MAX_CAPTURE_REGIONS];
        private final long[] coordinatePlan = new long[MAX_CAPTURE_REGIONS];
        private final long[] presentMasks = new long[MAX_CAPTURE_REGIONS
                * SurfaceRegionSource.DIRTY_WORDS];
        private final long[] dirtyMasks = new long[MAX_CAPTURE_REGIONS
                * SurfaceRegionSource.DIRTY_WORDS];
        /** source revision + palette revision for each region. */
        private final long[] revisions = new long[MAX_CAPTURE_REGIONS * 2];
        private final CaptureBudget captureBudget = new CaptureBudget();

        private void begin(int regionCount, int captureAllowance,
                long timeBudgetNanos) {
            closeProbes(MAX_CAPTURE_REGIONS);
            Arrays.fill(coordinatePlan, 0L);
            Arrays.fill(presentMasks, 0L);
            Arrays.fill(dirtyMasks, 0L);
            Arrays.fill(revisions, 0L);
            captureBudget.remaining = Math.max(0, captureAllowance);
            long now = System.nanoTime();
            long budget = Math.max(100_000L, timeBudgetNanos);
            captureBudget.deadlineNanos = now > Long.MAX_VALUE - budget
                    ? Long.MAX_VALUE : now + budget;
            for (int index = regionCount; index < MAX_CAPTURE_REGIONS; index++) {
                probes[index] = null;
            }
        }

        private void closeProbes(int count) {
            int limit = Math.min(count, probes.length);
            for (int index = 0; index < limit; index++) {
                SurfaceRegionSource.Probe probe = probes[index];
                probes[index] = null;
                if (probe != null) probe.close();
            }
        }
    }

    private static final class BatchProgressState {
        private final long sessionId;
        private final int regionX;
        private final int regionZ;
        private final int batchPageX;
        private final int batchPageZ;
        private final int focusPageX;
        private final int focusPageZ;
        private final int pagesWide;
        private final int pagesHigh;
        private final boolean includeLight;
        private final long signature;
        private final boolean strictReady;
        private final long materializedNanos;

        private BatchProgressState(long sessionId, int regionX, int regionZ,
                int batchPageX, int batchPageZ, int focusPageX, int focusPageZ,
                int pagesWide, int pagesHigh, boolean includeLight,
                long signature, boolean strictReady, long materializedNanos) {
            this.sessionId = sessionId;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.batchPageX = batchPageX;
            this.batchPageZ = batchPageZ;
            this.focusPageX = focusPageX;
            this.focusPageZ = focusPageZ;
            this.pagesWide = pagesWide;
            this.pagesHigh = pagesHigh;
            this.includeLight = includeLight;
            this.signature = signature;
            this.strictReady = strictReady;
            this.materializedNanos = materializedNanos;
        }

        private boolean matches(long candidateSessionId, int candidateRegionX,
                int candidateRegionZ, int candidateBatchPageX,
                int candidateBatchPageZ, int candidateFocusPageX,
                int candidateFocusPageZ, int candidatePagesWide,
                int candidatePagesHigh, boolean candidateIncludeLight) {
            return sessionId == candidateSessionId
                    && regionX == candidateRegionX
                    && regionZ == candidateRegionZ
                    && batchPageX == candidateBatchPageX
                    && batchPageZ == candidateBatchPageZ
                    && focusPageX == candidateFocusPageX
                    && focusPageZ == candidateFocusPageZ
                    && pagesWide == candidatePagesWide
                    && pagesHigh == candidatePagesHigh
                    && includeLight == candidateIncludeLight;
        }
    }

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
            byte[] destinationLights, byte[] destinationKnown,
            int[] biomeRemap, int[] blockRemap,
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
                        destinationKnown[destinationIndex] = 1;
                        destinationTints[destinationIndex] = sourceTints[sourceIndex];
                        destinationLights[destinationIndex] = sourceLights[sourceIndex];
                        if (blockRemap == null && biomeRemap == null) {
                            // Single-region batches already use the exact retained
                            // region palette. Preserve packed IDs verbatim instead
                            // of allocating union maps/remap arrays for every page.
                            destinationPixels[destinationIndex] = packed;
                            continue;
                        }
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

    private static void mergePalette(String[] source, List<String> output,
            Map<String, Integer> indices, String fallback, int maximumEntries) {
        if (source == null) return;
        for (String raw : source) {
            String id = raw == null || raw.isBlank() ? fallback : raw;
            if (indices.containsKey(id)) continue;
            int mapped = output.size() >= maximumEntries
                    ? Math.max(0, maximumEntries - 1) : output.size();
            if (mapped == output.size()) output.add(id);
            indices.put(id, mapped);
        }
    }

    private static int[] buildRemapAgainstPalette(String[] source,
            Map<String, Integer> indices, String fallback) {
        if (source == null) return new int[0];
        int[] remap = new int[source.length];
        Integer fallbackIndex = indices.get(fallback);
        int safeFallback = fallbackIndex == null ? 0 : fallbackIndex;
        for (int index = 0; index < source.length; index++) {
            String id = source[index] == null || source[index].isBlank()
                    ? fallback : source[index];
            Integer mapped = indices.get(id);
            if (mapped == null) {
                throw new CancellationException(
                        "Surface palette advanced after batch admission");
            }
            remap[index] = mapped == null ? safeFallback : mapped;
        }
        return remap;
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
    private record PageWarmKey(long sessionId, int regionX, int regionZ,
            int localPageX, int localPageZ) { }
    private record RegionCoordinate(int regionX, int regionZ) { }
    private record RegionProbeSlice(RegionCoordinate coordinate,
            SurfaceRegionSource.Probe probe, long sourceRevision,
            long paletteRevision, String[] biomePalette,
            String[] blockPalette) { }

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
        private final RegionProbeSlice[] slices;
        private final MapMemoryLeaseManager.Lease memoryLease;
        private int activeAssemblies;
        private boolean closeRequested;
        private boolean closed;

        private BatchSourcePlan(RevisionStamp stamp, int regionX, int regionZ,
                int batchPageX, int batchPageZ, int pagesWide, int pagesHigh,
                int worldPageStartX, int worldPageStartZ, int stride, int height,
                int halo, long sourceRevision, RegionProbeSlice[] slices,
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
            // captureBatchPlan passes a fresh, exact-sized array owned by this plan.
            this.slices = slices;
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

        /**
         * Visits the immutable raw palette entries captured at admission without
         * constructing the final union palette on the render thread. Style
         * resources are client-thread-affine, while palette union/remap remains
         * worker-owned in {@link #assemble(BooleanSupplier)}.
         */
        void forEachBiomePaletteEntry(Consumer<String> consumer) {
            if (consumer == null) return;
            for (RegionProbeSlice slice : slices) {
                for (String id : slice.biomePalette()) consumer.accept(id);
            }
        }

        void forEachBlockPaletteEntry(Consumer<String> consumer) {
            if (consumer == null) return;
            for (RegionProbeSlice slice : slices) {
                for (String id : slice.blockPalette()) consumer.accept(id);
            }
        }

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
                byte[] known = new byte[stride * height];
                Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
                Arrays.fill(tints, SurfaceTintData.UNKNOWN);
                int windowStartX = worldPageStartX - halo;
                int windowStartZ = worldPageStartZ - halo;
                /*
                 * The overwhelmingly common 1x1 page transaction stays inside one
                 * 512x512 retained source region. Xaero keeps that tile's retained
                 * palette identity; rebuilding a HashMap union and two remap arrays
                 * for such a local transaction is pure allocation churn. Only
                 * cross-region batches need palette union/remapping.
                 */
                if (slices.length == 1) {
                    RegionProbeSlice slice = slices[0];
                    SurfaceRegionSource.View view = slice.probe().acquireView(
                            slice.paletteRevision());
                    if (view == null) {
                        throw new CancellationException(
                                "Surface palette advanced after batch admission");
                    }
                    try {
                        copyRegionIntersection(slice.coordinate(), view,
                                windowStartX, windowStartZ, stride, height,
                                pixels, tints, lights, known, null, null, valid);
                        return new AssembledBatchWindow(this, pixels, tints, lights, known,
                                Arrays.asList(slice.biomePalette()),
                                Arrays.asList(slice.blockPalette()));
                    } finally {
                        view.close();
                    }
                }

                // Cross-region palette union/remap remains worker-owned.
                List<String> biomePalette = new ArrayList<>();
                List<String> blockPalette = new ArrayList<>();
                Map<String, Integer> biomeIds = new HashMap<>();
                Map<String, Integer> blockIds = new HashMap<>();
                for (RegionProbeSlice slice : slices) {
                    mergePalette(slice.biomePalette(), biomePalette, biomeIds,
                            "minecraft:plains", 255);
                    mergePalette(slice.blockPalette(), blockPalette, blockIds,
                            "minecraft:air", 65_535);
                }
                for (RegionProbeSlice slice : slices) {
                    if (valid != null && !valid.getAsBoolean()) {
                        throw new CancellationException("Surface batch assembly cancelled");
                    }
                    SurfaceRegionSource.View view = slice.probe().acquireView(
                            slice.paletteRevision());
                    if (view == null) {
                        throw new CancellationException(
                                "Surface palette advanced after batch admission");
                    }
                    try {
                        int[] biomeRemap = buildRemapAgainstPalette(
                                view.biomePaletteUnsafe(), biomeIds,
                                "minecraft:plains");
                        int[] blockRemap = buildRemapAgainstPalette(
                                view.blockPaletteUnsafe(), blockIds,
                                "minecraft:air");
                        copyRegionIntersection(slice.coordinate(), view,
                                windowStartX, windowStartZ, stride, height,
                                pixels, tints, lights, known, biomeRemap,
                                blockRemap, valid);
                    } finally {
                        view.close();
                    }
                }
                return new AssembledBatchWindow(this, pixels, tints, lights, known,
                        List.copyOf(biomePalette), List.copyOf(blockPalette));
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
            for (RegionProbeSlice slice : slices) slice.probe().close();
            memoryLease.close();
        }
    }

    /** Worker-local primitive window assembled from a {@link BatchSourcePlan}. */
    static final class AssembledBatchWindow {
        private final BatchSourcePlan plan;
        private final long[] pixels;
        private final int[] tints;
        private final byte[] light;
        private final byte[] known;
        private final List<String> biomePalette;
        private final List<String> blockPalette;

        private AssembledBatchWindow(BatchSourcePlan plan, long[] pixels,
                int[] tints, byte[] light, byte[] known,
                List<String> biomePalette, List<String> blockPalette) {
            this.plan = plan;
            this.pixels = pixels;
            this.tints = tints;
            this.light = light;
            this.known = known;
            this.biomePalette = biomePalette;
            this.blockPalette = blockPalette;
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
        List<String> biomePalette() { return biomePalette; }
        List<String> blockPalette() { return blockPalette; }
        long[] pixelsUnsafe() { return pixels; }
        int[] tintsUnsafe() { return tints; }
        byte[] lightUnsafe() { return light; }
        byte[] knownUnsafe() { return known; }
    }
}
