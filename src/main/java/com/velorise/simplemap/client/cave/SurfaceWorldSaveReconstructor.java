package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapBlockData;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.MapWorkScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

    private final Set<Key> pending = ConcurrentHashMap.newKeySet();

    private SurfaceWorldSaveReconstructor() {
    }

    static SurfaceWorldSaveReconstructor getInstance() {
        return INSTANCE;
    }

    void reset() {
        pending.clear();
    }

    void request(ServerLevel level, int chunkX, int chunkZ,
            DecodedWorldRegionCache.SourceLease sourceLease) {
        if (sourceLease == null) return;
        if (level == null || pending.size() >= MAX_PENDING) {
            sourceLease.close();
            return;
        }
        if (GeneratedChunkIndex.getInstance().state(level, chunkX, chunkZ)
                == GeneratedChunkIndex.State.LIVE) {
            sourceLease.close();
            return;
        }
        CompletableFuture<DecodedWorldRegionCache.Result> sourceFuture = sourceLease.future();
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
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                try {
                    apply(key, generation, columns);
                } finally {
                    pending.remove(key);
                }
            });
        }, MapWorkScheduler.cpuExecutor(sourceLease.lane(),
                MapWorkScheduler.WorkType.SOURCE_PROJECTION,
                sourceLease.lane().priorityBase(), 10, () -> true)).exceptionally(throwable -> {
            pending.remove(key);
            sourceLease.close();
            return null;
        });
    }

    private void apply(Key key, long generation,
            DecodedWorldChunkSource.SurfaceProjection[] columns) {
        Minecraft minecraft = Minecraft.getInstance();
        MapManager manager = MapManager.getInstance();
        if (!manager.isGenerationCurrent(generation)
                || !manager.isViewingLiveDimension()
                || minecraft.level == null
                || !minecraft.level.dimension().location().toString().equals(key.dimension)) return;

        int firstX = key.chunkX << 4;
        int firstZ = key.chunkZ << 4;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int blockX = firstX + localX;
                int blockZ = firstZ + localZ;
                MapBlockData existing = manager.getBlockData(blockX, blockZ);
                if (existing != null && !existing.isEmpty()) continue;
                DecodedWorldChunkSource.SurfaceProjection projection =
                        columns[(localZ << 4) | localX];
                if (projection == null || projection.empty()) continue;
                MapManager.Region region = manager.getRegion(blockX >> 9, blockZ >> 9, true);
                if (region == null || !region.isLoaded()) continue;
                int blockIndex = region.getOrAddBlockIndex(projection.blockId());
                int biomeIndex = region.getOrAddBiomeIndex(projection.biomeId());
                MapBlockData data = MapBlockData.builder()
                        .topY(projection.topY())
                        .floorY(projection.floorY())
                        .blockId(blockIndex)
                        .biomeId(biomeIndex)
                        .light(projection.blockLight())
                        .glowing(projection.glowing())
                        .fluid(projection.fluid())
                        .flower(projection.flower())
                        .leaves(projection.leaves())
                        .build();
                manager.setBlockData(blockX, blockZ, data, projection.tint());
            }
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private record Key(String dimension, int chunkX, int chunkZ) {
    }
}
