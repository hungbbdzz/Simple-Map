package com.velorise.simplemap.client.cave;

import com.mojang.datafixers.DataFixer;
import com.velorise.simplemap.client.GeneratedChunkIndex;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shared decoded Anvil-chunk cache used by every cave projection.
 *
 * <p>Full Cave and multiple Layered Top-Y requests previously repeated the same
 * ChunkStorage read, DataFixer pass and palette decode. Xaero keeps region/world
 * data resident while different cave layers are rendered. This cache provides the
 * same source-level reuse without coupling the projection result to one cave band.</p>
 */
final class CaveSavedChunkCache {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final CaveSavedChunkCache INSTANCE = new CaveSavedChunkCache();
    private static final int MAX_RESIDENT_SOURCES = 768;
    private static final int MAX_IN_FLIGHT_SOURCES = 96;
    /** Surface warmup cannot occupy every decoder slot needed by an active cave view. */
    private static final int MAX_PREFETCH_IN_FLIGHT_SOURCES = 64;
    private static final long MISSING_RETRY_MS = 30_000L;
    private static final long FAILED_RETRY_MS = 8_000L;

    private final LinkedHashMap<Key, Entry> entries =
            new LinkedHashMap<>(256, 0.75f, true);
    private final ExecutorService decodeWorkers;
    private long epoch = 1L;
    private int inFlight;
    private int inFlightPrefetch;

    private CaveSavedChunkCache() {
        int available = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(2, Math.min(4, Math.max(1, available / 3)));
        decodeWorkers = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "SimpleMap-CaveChunkDecode");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        });
    }

    static CaveSavedChunkCache getInstance() {
        return INSTANCE;
    }

    synchronized void reset() {
        epoch++;
        entries.clear();
        inFlight = 0;
        inFlightPrefetch = 0;
    }

    synchronized int residentCount() {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.result != null && entry.result.state == State.PRESENT) count++;
        }
        return count;
    }

    synchronized int inFlightCount() {
        return inFlight;
    }

    CompletableFuture<Result> request(ServerLevel level, int chunkX, int chunkZ) {
        return request(level, chunkX, chunkZ, true);
    }

    private CompletableFuture<Result> request(ServerLevel level, int chunkX, int chunkZ,
            boolean foreground) {
        if (level == null) return CompletableFuture.completedFuture(Result.deferred());
        Key key = new Key(level.dimension().location().toString(), chunkX, chunkZ);
        long now = System.currentTimeMillis();
        long requestEpoch;
        synchronized (this) {
            Entry entry = entries.get(key);
            if (entry != null) {
                entry.lastAccessMs = now;
                if (entry.future != null) return entry.future;
                if (entry.result != null) {
                    if (entry.result.state == State.PRESENT) {
                        return CompletableFuture.completedFuture(entry.result);
                    }
                    if (now < entry.retryAfterMs) {
                        return CompletableFuture.completedFuture(entry.result);
                    }
                }
            } else {
                entry = new Entry();
                entries.put(key, entry);
            }
            if (inFlight >= MAX_IN_FLIGHT_SOURCES
                    || (!foreground && inFlightPrefetch >= MAX_PREFETCH_IN_FLIGHT_SOURCES)) {
                // Capacity deferral is deliberately not cached. An active cave page can
                // retry almost immediately instead of inheriting a long failure backoff.
                return CompletableFuture.completedFuture(Result.deferred());
            }
            requestEpoch = epoch;
            inFlight++;
            if (!foreground) inFlightPrefetch++;
            Entry target = entry;
            CompletableFuture<Result> future;
            try {
                future = level.getChunkSource().chunkMap.read(new ChunkPos(chunkX, chunkZ))
                        .handleAsync((optional, readFailure) -> decode(level, chunkX, chunkZ,
                                optional, readFailure), decodeWorkers);
            } catch (Throwable throwable) {
                inFlight--;
                if (!foreground) inFlightPrefetch = Math.max(0, inFlightPrefetch - 1);
                Result failed = Result.failed();
                target.result = failed;
                target.retryAfterMs = now + FAILED_RETRY_MS;
                return CompletableFuture.completedFuture(failed);
            }
            target.future = future;
            target.prefetch = !foreground;
            target.lastAccessMs = now;
            future.whenComplete((result, throwable) -> finish(key, target, requestEpoch,
                    result, throwable));
            trimLocked();
            return future;
        }
    }

    void prefetch(ServerLevel level, int chunkX, int chunkZ) {
        request(level, chunkX, chunkZ, false);
    }

    private Result decode(ServerLevel level, int chunkX, int chunkZ,
            Optional<CompoundTag> optional, Throwable readFailure) {
        if (readFailure != null) {
            LOGGER.debug("Could not read cave source chunk {},{}", chunkX, chunkZ,
                    unwrap(readFailure));
            GeneratedChunkIndex.getInstance().markSavedFailure(level, chunkX, chunkZ);
            return Result.failed();
        }
        if (optional == null || optional.isEmpty()) {
            GeneratedChunkIndex.getInstance().markSavedAbsent(level, chunkX, chunkZ);
            return Result.absent();
        }
        try {
            CompoundTag tag = optional.get();
            int version = tag.contains("DataVersion", Tag.TAG_ANY_NUMERIC)
                    ? tag.getInt("DataVersion") : -1;
            DataFixer fixer = Minecraft.getInstance().getFixerUpper();
            tag = DataFixTypes.CHUNK.updateToCurrentVersion(fixer, tag, version);
            Registry<Biome> biomeRegistry = level.registryAccess()
                    .registryOrThrow(Registries.BIOME);
            NbtCaveChunkSource source = NbtCaveChunkSource.decode(tag,
                    chunkX, chunkZ, level.getMinBuildHeight(),
                    level.getMaxBuildHeight(), biomeRegistry);
            if (source == null) {
                GeneratedChunkIndex.getInstance().markSavedFailure(level, chunkX, chunkZ);
                return Result.failed();
            }
            GeneratedChunkIndex.getInstance().markSavedPresent(level, chunkX, chunkZ);
            return Result.present(source);
        } catch (Throwable throwable) {
            LOGGER.debug("Could not decode reusable cave source chunk {},{}",
                    chunkX, chunkZ, throwable);
            GeneratedChunkIndex.getInstance().markSavedFailure(level, chunkX, chunkZ);
            return Result.failed();
        }
    }

    private void finish(Key key, Entry entry, long requestEpoch,
            Result result, Throwable throwable) {
        synchronized (this) {
            inFlight = Math.max(0, inFlight - 1);
            if (entry.prefetch) inFlightPrefetch = Math.max(0, inFlightPrefetch - 1);
            if (entry.future == null) return;
            entry.future = null;
            if (requestEpoch != epoch || entries.get(key) != entry) return;
            Result finalResult = throwable == null && result != null
                    ? result : Result.failed();
            entry.result = finalResult;
            long now = System.currentTimeMillis();
            entry.lastAccessMs = now;
            entry.retryAfterMs = switch (finalResult.state) {
                case PRESENT -> 0L;
                case ABSENT -> now + MISSING_RETRY_MS;
                case FAILED, DEFERRED -> now + FAILED_RETRY_MS;
            };
            trimLocked();
        }
    }

    private void trimLocked() {
        if (entries.size() <= MAX_RESIDENT_SOURCES) return;
        var iterator = entries.entrySet().iterator();
        while (entries.size() > MAX_RESIDENT_SOURCES && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.future != null) continue;
            iterator.remove();
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

    record Result(State state, NbtCaveChunkSource source) {
        static Result present(NbtCaveChunkSource source) {
            return new Result(State.PRESENT, source);
        }

        static Result absent() {
            return new Result(State.ABSENT, null);
        }

        static Result failed() {
            return new Result(State.FAILED, null);
        }

        static Result deferred() {
            return new Result(State.DEFERRED, null);
        }
    }

    private record Key(String dimension, int chunkX, int chunkZ) {
    }

    private static final class Entry {
        private CompletableFuture<Result> future;
        private Result result;
        private long retryAfterMs;
        private long lastAccessMs;
        private boolean prefetch;
    }
}
