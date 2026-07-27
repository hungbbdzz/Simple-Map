package com.velorise.simplemap.client.cave;

import com.mojang.datafixers.DataFixer;
import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapRequestLane;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared decoded Anvil world-region cache used by surface, Full Cave and every
 * Layered Cave projection.
 *
 * <p>Entries are grouped by the native 32x32-chunk Anvil region. A decoded chunk
 * contains section palettes, biomes, lights, heightmaps and block-entity visual
 * metadata once; projections never repeat ChunkStorage, DataFixer or palette
 * decode. Residency is byte-budgeted from the current JVM heap rather than a
 * fixed chunk count.</p>
 */
final class DecodedWorldRegionCache {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final DecodedWorldRegionCache INSTANCE = new DecodedWorldRegionCache();
    private static final long MISSING_RETRY_MS = 30_000L;
    private static final long FAILED_RETRY_MS = 8_000L;

    private final LinkedHashMap<RegionKey, DecodedRegion> regions =
            new LinkedHashMap<>(32, 0.75f, true);
    private final PriorityDecodeExecutor decodeWorkers;
    private long epoch = 1L;
    private int inFlight;
    private int inFlightPrefetch;
    private long residentBytes;
    private final MapPipelineTelemetry pipelineTelemetry = MapPipelineTelemetry.getInstance();

    private DecodedWorldRegionCache() {
        int available = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(2, Math.min(6, Math.max(1, available / 3)));
        decodeWorkers = new PriorityDecodeExecutor(threads);
    }

    static DecodedWorldRegionCache getInstance() {
        return INSTANCE;
    }

    synchronized void reset() {
        epoch++;
        for (DecodedRegion region : regions.values()) {
            for (Entry entry : region.entries) {
                if (entry != null && entry.token != null) entry.token.cancel();
            }
        }
        regions.clear();
        residentBytes = 0L;
        inFlight = 0;
        inFlightPrefetch = 0;
    }

    synchronized int residentCount() {
        int count = 0;
        for (DecodedRegion region : regions.values()) count += region.presentCount;
        return count;
    }

    synchronized long residentBytes() {
        return residentBytes;
    }

    synchronized long targetBytes() {
        return targetBytesLocked();
    }

    synchronized int inFlightCount() {
        return inFlight;
    }

    synchronized void maintain() {
        trimLocked();
    }

    synchronized Stats stats() {
        AdaptiveWorldSourceBudget.Snapshot budget = budgetLocked();
        return new Stats(regions.size(), residentCount(), residentBytes,
                budget.targetBytes(), inFlight, inFlightPrefetch,
                decodeWorkers.queuedTasks(), budget.pressure(), budget.headroomBytes());
    }

    CompletableFuture<Result> request(ServerLevel level, int chunkX, int chunkZ) {
        SourceLease lease = requestLease(level, chunkX, chunkZ, MapRequestLane.FULLSCREEN);
        return lease.future().whenComplete((ignored, failure) -> lease.close());
    }

    CompletableFuture<Result> request(ServerLevel level, int chunkX, int chunkZ,
            MapRequestLane lane) {
        SourceLease lease = requestLease(level, chunkX, chunkZ, lane);
        return lease.future().whenComplete((ignored, failure) -> lease.close());
    }

    CompletableFuture<Result> prefetch(ServerLevel level, int chunkX, int chunkZ) {
        SourceLease lease = prefetchLease(level, chunkX, chunkZ);
        return lease.future().whenComplete((ignored, failure) -> lease.close());
    }

    SourceLease requestLease(ServerLevel level, int chunkX, int chunkZ,
            MapRequestLane lane) {
        return requestInternal(level, chunkX, chunkZ,
                lane == null ? MapRequestLane.FULLSCREEN : lane);
    }

    SourceLease prefetchLease(ServerLevel level, int chunkX, int chunkZ) {
        return requestInternal(level, chunkX, chunkZ, MapRequestLane.PREFETCH);
    }

    private SourceLease requestInternal(ServerLevel level, int chunkX, int chunkZ,
            MapRequestLane lane) {
        boolean foreground = lane != MapRequestLane.PREFETCH;
        if (level == null) return SourceLease.detached(Result.deferred(), pipelineTelemetry);
        String dimension = level.dimension().location().toString();
        RegionKey regionKey = new RegionKey(dimension,
                Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
        int localIndex = Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32);
        long now = System.currentTimeMillis();
        long requestEpoch;
        synchronized (this) {
            DecodedRegion region = regions.computeIfAbsent(regionKey, DecodedRegion::new);
            region.lastAccessMs = now;
            Entry entry = region.entries[localIndex];
            if (entry == null) {
                entry = new Entry();
                region.entries[localIndex] = entry;
            }
            entry.lastAccessMs = now;

            if (entry.future != null && entry.token != null && entry.token.isCancelled()) {
                return SourceLease.detached(Result.deferred(), pipelineTelemetry);
            }
            if (entry.future != null) {
                acquireLeaseLocked(entry, lane);
                return new SourceLease(this, regionKey, region, localIndex, entry,
                        lane, entry.future, pipelineTelemetry);
            }
            if (entry.result != null) {
                if (entry.result.state == State.PRESENT || now < entry.retryAfterMs) {
                    acquireLeaseLocked(entry, lane);
                    return new SourceLease(this, regionKey, region, localIndex, entry,
                            lane, CompletableFuture.completedFuture(entry.result),
                            pipelineTelemetry);
                }
            }

            AdaptiveWorldSourceBudget.Snapshot budget = budgetLocked();
            int maximumInFlight = budget.maximumInFlight();
            int maximumPrefetch = budget.maximumPrefetch();
            if (inFlight >= maximumInFlight
                    || (!foreground && inFlightPrefetch >= maximumPrefetch)) {
                return SourceLease.detached(Result.deferred(), pipelineTelemetry);
            }

            requestEpoch = epoch;
            inFlight++;
            Entry target = entry;
            acquireLeaseLocked(target, lane);
            CompletableFuture<Result> future;
            try {
                MapCancellationToken token = new MapCancellationToken(
                        () -> isEpochCurrent(requestEpoch));
                target.token = token;
                target.decodeRequestedNanos = System.nanoTime();
                CompletableFuture<Optional<CompoundTag>> readFuture =
                        level.getChunkSource().chunkMap.read(new ChunkPos(chunkX, chunkZ));
                AtomicLong readCompletedNanos = new AtomicLong();
                readFuture.whenComplete((ignored, failure) ->
                        readCompletedNanos.compareAndSet(0L, System.nanoTime()));
                future = readFuture.handleAsync((optional, readFailure) -> {
                    long decodeStart = System.nanoTime();
                    long readFinished = readCompletedNanos.get();
                    if (readFinished == 0L) readFinished = decodeStart;
                    pipelineTelemetry.recordStageNanos(MapPipelineStage.ANVIL_READ,
                            Math.max(0L, readFinished - target.decodeRequestedNanos));
                    pipelineTelemetry.recordStageNanos(MapPipelineStage.SOURCE_QUEUE,
                            Math.max(0L, decodeStart - readFinished));
                    return decode(level, chunkX, chunkZ, optional, readFailure, token);
                }, decodeWorkers.dynamic(() -> target.strongestLane == null
                        ? MapRequestLane.PREFETCH.executorPriority()
                        : target.strongestLane.executorPriority()));
            } catch (Throwable throwable) {
                target.token = null;
                inFlight--;
                if (target.countedAsPrefetch) {
                    inFlightPrefetch = Math.max(0, inFlightPrefetch - 1);
                    target.countedAsPrefetch = false;
                }
                Result failed = Result.failed();
                target.result = failed;
                target.retryAfterMs = now + FAILED_RETRY_MS;
                return new SourceLease(this, regionKey, region, localIndex, target,
                        lane, CompletableFuture.completedFuture(failed), pipelineTelemetry);
            }
            target.future = future;
            target.lastAccessMs = now;
            reconcilePrefetchAccountingLocked(target);
            future.whenComplete((result, throwable) -> finish(regionKey, region, localIndex,
                    target, requestEpoch, result, throwable));
            trimLocked();
            return new SourceLease(this, regionKey, region, localIndex, target,
                    lane, future, pipelineTelemetry);
        }
    }

    private void acquireLeaseLocked(Entry entry, MapRequestLane lane) {
        MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
        entry.leaseCounts[effective.ordinal()]++;
        entry.totalLeases++;
        recomputeStrongestLaneLocked(entry);
        reconcilePrefetchAccountingLocked(entry);
    }

    private void releaseLease(RegionKey regionKey, DecodedRegion region, int localIndex,
            Entry entry, MapRequestLane lane) {
        synchronized (this) {
            if (regions.get(regionKey) != region || region.entries[localIndex] != entry) return;
            MapRequestLane effective = lane == null ? MapRequestLane.FULLSCREEN : lane;
            int index = effective.ordinal();
            if (entry.leaseCounts[index] > 0) {
                entry.leaseCounts[index]--;
                entry.totalLeases = Math.max(0, entry.totalLeases - 1);
            }
            recomputeStrongestLaneLocked(entry);
            reconcilePrefetchAccountingLocked(entry);
            if (entry.totalLeases == 0 && entry.future != null && entry.token != null
                    && !entry.token.isCancelled()) {
                entry.token.cancel();
                pipelineTelemetry.recordSourceDecodeCancelledNoConsumers();
            }
        }
    }

    private void recomputeStrongestLaneLocked(Entry entry) {
        MapRequestLane strongest = null;
        for (MapRequestLane candidate : MapRequestLane.values()) {
            if (entry.leaseCounts[candidate.ordinal()] <= 0) continue;
            if (candidate.strongerThan(strongest)) strongest = candidate;
        }
        entry.strongestLane = strongest == null ? MapRequestLane.PREFETCH : strongest;
    }

    private void reconcilePrefetchAccountingLocked(Entry entry) {
        boolean shouldCount = entry.future != null
                && entry.strongestLane == MapRequestLane.PREFETCH;
        if (shouldCount == entry.countedAsPrefetch) return;
        if (shouldCount) inFlightPrefetch++;
        else inFlightPrefetch = Math.max(0, inFlightPrefetch - 1);
        entry.countedAsPrefetch = shouldCount;
    }

    private Result decode(ServerLevel level, int chunkX, int chunkZ,
            Optional<CompoundTag> optional, Throwable readFailure,
            MapCancellationToken token) {
        if (readFailure != null) {
            LOGGER.debug("Could not read shared world source chunk {},{}", chunkX, chunkZ,
                    unwrap(readFailure));
            GeneratedChunkIndex.getInstance().markSavedFailure(level, chunkX, chunkZ);
            return Result.failed();
        }
        if (optional == null || optional.isEmpty()) {
            GeneratedChunkIndex.getInstance().markSavedAbsent(level, chunkX, chunkZ);
            return Result.absent();
        }
        try {
            token.checkpoint("anvil-read-finished");
            CompoundTag tag = optional.get();
            int version = tag.contains("DataVersion", Tag.TAG_ANY_NUMERIC)
                    ? tag.getInt("DataVersion") : -1;
            DataFixer fixer = Minecraft.getInstance().getFixerUpper();
            token.checkpoint("datafix-start");
            long dataFixStart = System.nanoTime();
            tag = DataFixTypes.CHUNK.updateToCurrentVersion(fixer, tag, version);
            pipelineTelemetry.recordStageNanos(MapPipelineStage.DATA_FIX,
                    System.nanoTime() - dataFixStart);
            token.checkpoint("datafix-finished");
            Registry<Biome> biomeRegistry = level.registryAccess()
                    .registryOrThrow(Registries.BIOME);
            long chunkDecodeStart = System.nanoTime();
            DecodedWorldChunkSource source = DecodedWorldChunkSource.decode(tag,
                    chunkX, chunkZ, level.getMinBuildHeight(),
                    level.getMaxBuildHeight(), biomeRegistry, token);
            pipelineTelemetry.recordStageNanos(MapPipelineStage.CHUNK_DECODE,
                    System.nanoTime() - chunkDecodeStart);
            token.checkpoint("chunk-decode-finished");
            if (source == null) {
                GeneratedChunkIndex.getInstance().markSavedFailure(level, chunkX, chunkZ);
                return Result.failed();
            }
            GeneratedChunkIndex.getInstance().markSavedPresent(level, chunkX, chunkZ);
            return Result.present(source, source.estimatedBytes());
        } catch (CancellationException cancelled) {
            return Result.deferred();
        } catch (Throwable throwable) {
            LOGGER.debug("Could not decode shared world source chunk {},{}",
                    chunkX, chunkZ, throwable);
            GeneratedChunkIndex.getInstance().markSavedFailure(level, chunkX, chunkZ);
            return Result.failed();
        }
    }

    private synchronized boolean isEpochCurrent(long requestEpoch) {
        return requestEpoch == epoch;
    }

    private void finish(RegionKey regionKey, DecodedRegion region, int localIndex,
            Entry entry, long requestEpoch, Result result, Throwable throwable) {
        synchronized (this) {
            if (requestEpoch == epoch) {
                inFlight = Math.max(0, inFlight - 1);
                if (entry.countedAsPrefetch) {
                    inFlightPrefetch = Math.max(0, inFlightPrefetch - 1);
                    entry.countedAsPrefetch = false;
                }
            }
            if (entry.future == null) return;
            entry.future = null;
            entry.token = null;
            if (requestEpoch != epoch || regions.get(regionKey) != region
                    || region.entries[localIndex] != entry) return;

            if (entry.result != null && entry.result.state == State.PRESENT) {
                residentBytes = Math.max(0L, residentBytes - entry.result.estimatedBytes);
                region.residentBytes = Math.max(0L,
                        region.residentBytes - entry.result.estimatedBytes);
                region.presentCount = Math.max(0, region.presentCount - 1);
            }

            Result finalResult = throwable == null && result != null
                    ? result : Result.failed();
            entry.result = finalResult;
            long now = System.currentTimeMillis();
            entry.lastAccessMs = now;
            region.lastAccessMs = now;
            entry.retryAfterMs = switch (finalResult.state) {
                case PRESENT -> 0L;
                case ABSENT -> now + MISSING_RETRY_MS;
                case FAILED, DEFERRED -> now + FAILED_RETRY_MS;
            };
            if (finalResult.state == State.PRESENT) {
                residentBytes += finalResult.estimatedBytes;
                region.residentBytes += finalResult.estimatedBytes;
                region.presentCount++;
            }
            trimLocked();
        }
    }

    private AdaptiveWorldSourceBudget.Snapshot budgetLocked() {
        Runtime runtime = Runtime.getRuntime();
        return AdaptiveWorldSourceBudget.evaluate(runtime.maxMemory(),
                runtime.totalMemory(), runtime.freeMemory(),
                runtime.availableProcessors(), inFlight - inFlightPrefetch,
                inFlightPrefetch);
    }

    private long targetBytesLocked() {
        return budgetLocked().targetBytes();
    }

    private void trimLocked() {
        long target = targetBytesLocked();
        int maximumRegions = Math.max(16, Math.min(256,
                (int) Math.max(1L, target / (2L << 20))));
        if (residentBytes <= target && regions.size() <= maximumRegions) return;
        var regionIterator = regions.entrySet().iterator();
        while ((residentBytes > target || regions.size() > maximumRegions)
                && regionIterator.hasNext()) {
            DecodedRegion region = regionIterator.next().getValue();
            boolean hasInFlight = false;
            for (Entry entry : region.entries) {
                if (entry != null && (entry.future != null || entry.totalLeases > 0)) {
                    hasInFlight = true;
                    break;
                }
            }
            if (hasInFlight) continue;
            residentBytes = Math.max(0L, residentBytes - region.residentBytes);
            regionIterator.remove();
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    enum State {
        PRESENT,
        ABSENT,
        FAILED,
        DEFERRED
    }

    record Result(State state, DecodedWorldChunkSource source, long estimatedBytes) {
        static Result present(DecodedWorldChunkSource source, long estimatedBytes) {
            return new Result(State.PRESENT, source, Math.max(1L, estimatedBytes));
        }

        static Result absent() {
            return new Result(State.ABSENT, null, 0L);
        }

        static Result failed() {
            return new Result(State.FAILED, null, 0L);
        }

        static Result deferred() {
            return new Result(State.DEFERRED, null, 0L);
        }
    }

    record Stats(int regions, int decodedChunks, long residentBytes,
            long targetBytes, int inFlight, int prefetchInFlight, int decodeQueue,
            double heapPressure, long heapHeadroomBytes) {
    }

    private record RegionKey(String dimension, int regionX, int regionZ) {
    }

    private static final class DecodedRegion {
        private final RegionKey key;
        private final Entry[] entries = new Entry[32 * 32];
        private long residentBytes;
        private int presentCount;
        private long lastAccessMs;

        private DecodedRegion(RegionKey key) {
            this.key = key;
        }
    }

    static final class SourceLease implements AutoCloseable {
        private final DecodedWorldRegionCache owner;
        private final RegionKey regionKey;
        private final DecodedRegion region;
        private final int localIndex;
        private final Entry entry;
        private final MapRequestLane lane;
        private final CompletableFuture<Result> future;
        private final MapPipelineTelemetry telemetry;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SourceLease(DecodedWorldRegionCache owner, RegionKey regionKey,
                DecodedRegion region, int localIndex, Entry entry, MapRequestLane lane,
                CompletableFuture<Result> future, MapPipelineTelemetry telemetry) {
            this.owner = owner;
            this.regionKey = regionKey;
            this.region = region;
            this.localIndex = localIndex;
            this.entry = entry;
            this.lane = lane;
            this.future = future;
            this.telemetry = telemetry;
            telemetry.recordSourceLeaseOpened();
        }

        static SourceLease detached(Result result, MapPipelineTelemetry telemetry) {
            return new SourceLease(null, null, null, -1, null, MapRequestLane.PREFETCH,
                    CompletableFuture.completedFuture(result), telemetry);
        }

        CompletableFuture<Result> future() {
            return future;
        }

        MapRequestLane lane() {
            return lane;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            telemetry.recordSourceLeaseClosed();
            if (owner != null) owner.releaseLease(regionKey, region, localIndex, entry, lane);
        }
    }

    private static final class Entry {
        private CompletableFuture<Result> future;
        private MapCancellationToken token;
        private Result result;
        private long retryAfterMs;
        private long lastAccessMs;
        private long decodeRequestedNanos;
        private boolean countedAsPrefetch;
        private int totalLeases;
        private final int[] leaseCounts = new int[MapRequestLane.values().length];
        private volatile MapRequestLane strongestLane = MapRequestLane.PREFETCH;
    }
}
