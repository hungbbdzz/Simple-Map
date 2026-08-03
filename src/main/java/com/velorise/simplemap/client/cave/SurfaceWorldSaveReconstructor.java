package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapBlockData;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapWorkScheduler;
import com.velorise.simplemap.client.SurfaceTintData;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Surface projection consumer for the shared decoded world-region cache.
 *
 * <p>This closes the old split where surface used only .smdat/live columns while
 * cave decoded Anvil sections separately. A generated chunk decoded for either
 * view can now populate missing surface columns and later serve every cave band.</p>
 */
final class SurfaceWorldSaveReconstructor {
    private static final SurfaceWorldSaveReconstructor INSTANCE =
            new SurfaceWorldSaveReconstructor();
    private static final int MAX_PENDING = 192;
    private static final int MAX_READY = 64;
    private static final long PRESSURED_APPLY_BUDGET_NANOS = 250_000L;
    private static final long HEALTHY_APPLY_BUDGET_NANOS = 700_000L;

    private final Set<Key> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ReadyProjection> readyApplications =
            new ConcurrentLinkedQueue<>();
    private final AtomicInteger readyApplicationCount = new AtomicInteger();

    private SurfaceWorldSaveReconstructor() {
    }

    static SurfaceWorldSaveReconstructor getInstance() {
        return INSTANCE;
    }

    void reset() {
        pending.clear();
        readyApplications.clear();
        readyApplicationCount.set(0);
    }

    void request(ServerLevel level, int chunkX, int chunkZ,
            DecodedWorldRegionCache.SourceLease sourceLease) {
        if (sourceLease == null) return;
        if (level == null || pending.size() >= MAX_PENDING) {
            sourceLease.close();
            return;
        }
        if (MapManager.getInstance().isChunkSurfaceComplete(chunkX, chunkZ)) {
            sourceLease.close();
            return;
        }
        CompletableFuture<DecodedWorldRegionCache.Result> sourceFuture = sourceLease.future();
        if (sourceFuture.isDone()) {
            try {
                DecodedWorldRegionCache.Result immediate = sourceFuture.getNow(null);
                if (immediate == null
                        || immediate.state() != DecodedWorldRegionCache.State.PRESENT
                        || immediate.source() == null) {
                    sourceLease.close();
                    return;
                }
            } catch (RuntimeException failure) {
                sourceLease.close();
                return;
            }
        }
        Key key = new Key(level.dimension().location().toString(), chunkX, chunkZ);
        if (!pending.add(key)) {
            sourceLease.close();
            return;
        }
        long generation = MapManager.getInstance().getGeneration();
        sourceFuture.thenAcceptAsync(result -> {
            if (result == null || result.state() != DecodedWorldRegionCache.State.PRESENT
                    || result.source() == null) {
                pending.remove(key);
                sourceLease.close();
                return;
            }
            long projectionStart = System.nanoTime();
            DecodedWorldChunkSource.SurfaceProjection[] columns = result.source()
                    .projectSurface(com.velorise.simplemap.client.MapConfig.displayFlowers);
            MapPipelineTelemetry.getInstance().recordStageNanos(
                    MapPipelineStage.SURFACE_PROJECTION,
                    System.nanoTime() - projectionStart);
            sourceLease.close();
            if (readyApplicationCount.incrementAndGet() > MAX_READY) {
                readyApplicationCount.decrementAndGet();
                pending.remove(key);
                return;
            }
            readyApplications.offer(new ReadyProjection(key, generation, columns));
        }, MapWorkScheduler.cpuExecutor(sourceLease.lane(),
                MapWorkScheduler.WorkType.SOURCE_PROJECTION,
                sourceLease.lane().priorityBase(), 10, () -> true)).exceptionally(throwable -> {
            pending.remove(key);
            sourceLease.close();
            return null;
        });
    }

    /**
     * Applies completed disk projections on the client thread with an actual time
     * slice. CompletableFuture callbacks used to enqueue every completed chunk via
     * Minecraft.execute(), so a fast Anvil burst could commit dozens of palettes
     * in one frame even though background worker queues looked empty in the log.
     */
    void drainReadyApplications() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) return;
        boolean pressured = MapPerformanceGovernor.getInstance().underPressure();
        long deadline = System.nanoTime() + (pressured
                ? PRESSURED_APPLY_BUDGET_NANOS : HEALTHY_APPLY_BUDGET_NANOS);
        int maximum = pressured ? 1 : 3;
        int applied = 0;
        while (applied < maximum) {
            ReadyProjection ready = readyApplications.poll();
            if (ready == null) break;
            readyApplicationCount.updateAndGet(value -> Math.max(0, value - 1));
            try {
                apply(ready.key(), ready.generation(), ready.columns());
            } finally {
                pending.remove(ready.key());
            }
            applied++;
            if (System.nanoTime() >= deadline) break;
        }
    }

    private void apply(Key key, long generation,
            DecodedWorldChunkSource.SurfaceProjection[] columns) {
        MapManager manager = MapManager.getInstance();
        if (!manager.isGenerationCurrent(generation)
                || !manager.getCurrentDimensionResourceId().equals(key.dimension)) return;
        Minecraft minecraft = Minecraft.getInstance();
        // Ownership can change after the Anvil request starts. Never overwrite a
        // now-live chunk; its scanner will publish the authoritative transaction.
        if (minecraft.level != null
                && minecraft.level.dimension().location().toString()
                        .equals(key.dimension)
                && minecraft.level.hasChunk(key.chunkX, key.chunkZ)) return;

        int firstX = key.chunkX << 4;
        int firstZ = key.chunkZ << 4;
        MapManager.Region region = manager.getRegion(firstX >> 9, firstZ >> 9, true);
        if (region == null || !region.isLoaded()) return;
        long[] packed = new long[256];
        int[] tints = new int[256];
        boolean[] valid = new boolean[256];
        java.util.Arrays.fill(packed, MapBlockData.EMPTY_PACKED);
        java.util.Arrays.fill(tints, SurfaceTintData.UNKNOWN);
        int validColumns = 0;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int column = (localZ << 4) | localX;
                DecodedWorldChunkSource.SurfaceProjection projection =
                        columns[column];
                if (projection == null) continue;
                valid[column] = true;
                validColumns++;
                if (projection.empty()) continue;
                int blockIndex = region.getOrAddBlockIndex(projection.blockId());
                int biomeIndex = region.getOrAddBiomeIndex(projection.biomeId());
                MapBlockData data = MapBlockData.create(
                        projection.topY(), projection.floorY(), blockIndex, biomeIndex,
                        projection.blockLight(), projection.glowing(), projection.fluid(),
                        projection.flower(), projection.leaves());
                packed[column] = data.pack();
                tints[column] = projection.tint();
            }
        }
        if (validColumns == 0) return;
        manager.commitSurfaceChunkSlice(key.chunkX, key.chunkZ, 0,
                packed, tints, valid, 0, 256);
        if (validColumns == 256) {
            MapManager.SurfaceChunkCommit completed = manager
                    .finishSurfaceChunkTransaction(key.chunkX, key.chunkZ);
            if (completed.chunkComplete()) {
                // Disk reconstruction is the same authoritative 16x16 progress
                // signal as a live scan. Wake a backed-off page immediately so
                // the new chunk appears without returning to frame-rate polling.
                com.velorise.simplemap.client.MapTextureManager.getInstance()
                        .wakeRegionCaptureForChunk(key.chunkX, key.chunkZ);
            }
        }
    }

    int pendingCount() {
        return pending.size();
    }

    boolean isPending(ServerLevel level, int chunkX, int chunkZ) {
        return level != null && pending.contains(new Key(
                level.dimension().location().toString(), chunkX, chunkZ));
    }

    private record Key(String dimension, int chunkX, int chunkZ) {
    }

    private record ReadyProjection(Key key, long generation,
            DecodedWorldChunkSource.SurfaceProjection[] columns) {
    }
}
