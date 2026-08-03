package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveContextCache;
import com.velorise.simplemap.client.cave.CavePipeline;
import com.velorise.simplemap.client.cave.CaveWorldSaveReader;
import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Single admission point for map observation work.
 *
 * <p>Low-level scanners and texture managers keep their specialized queues, but
 * only this scheduler decides which lane may receive work during a client tick.
 * This prevents viewport, mutation, cave-band warmup and archive paths from all
 * independently spending their maximum budgets in the same frame.</p>
 */
public final class MapObservationScheduler {
    private static final MapObservationScheduler INSTANCE = new MapObservationScheduler();

    private Level observedLevel;
    private long lastMovementEpoch = Long.MIN_VALUE;
    private final MapObservationTelemetry telemetry = MapObservationTelemetry.getInstance();

    private MapObservationScheduler() {
    }

    public static MapObservationScheduler getInstance() {
        return INSTANCE;
    }

    public MapObservationTelemetry.Snapshot telemetry() {
        return telemetry.snapshot();
    }

    public void reset() {
        observedLevel = null;
        lastMovementEpoch = Long.MIN_VALUE;
        MapActivityGate.getInstance().reset();
        GeneratedChunkIndex.getInstance().reset();
        CaveContextCache.getInstance().reset();
        MapMutationBus.getInstance().reset();
        MapViewportCoordinator.getInstance().reset();
        telemetry.reset();
    }

    public void tick(Minecraft minecraft, boolean mapUnlocked, boolean mapScreenOpen) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            reset();
            return;
        }
        if (observedLevel != minecraft.level) {
            observedLevel = minecraft.level;
            GeneratedChunkIndex.getInstance().reset();
            GeneratedChunkIndex.getInstance().observeLevel(minecraft.level);
            CaveContextCache.getInstance().reset();
            CaveWorldSaveReader.getInstance().clearSourceCache();
        }

        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        MapPerformanceGovernor.ObservationProfile profile =
                governor.observationProfile(minecraft);

        CavePipeline pipeline = CavePipeline.getInstance();
        pipeline.tickClientState(minecraft);

        MapActivityGate activityGate = MapActivityGate.getInstance();
        boolean movementWindow = activityGate.blocksMapWork();
        if (movementWindow) {
            long epoch = activityGate.movementEpoch();
            if (lastMovementEpoch != epoch) {
                lastMovementEpoch = epoch;
                MapMutationBus.getInstance().dropQueuedWorkForMovement();
                MapViewportCoordinator.getInstance().prepareMovementStreaming();
            }
        }

        long started = System.nanoTime();
        if (!movementWindow) {
            MapMutationBus.getInstance().tick(minecraft,
                    profile.mutationColumnBudget(), profile.mutationChunkBudget(),
                    profile.mutationBudgetNanos());
        }
        telemetry.record(MapObservationTelemetry.Lane.MUTATION_REPAIR,
                System.nanoTime() - started,
                movementWindow ? 0 : profile.mutationColumnBudget());

        MapMutationBus mutationBus = MapMutationBus.getInstance();
        boolean authoritativeBacklog = !movementWindow
                && mutationBus.hasBacklog(513, 17);
        if (mapUnlocked && !mapScreenOpen) {
            int renderDistance = minecraft.options.renderDistance().get();
            int baseRadius = (int) Math.max(16, (renderDistance - 1.5) * 16);
            int radius = Math.max(16,
                    (int) Math.round(baseRadius * profile.liveRadiusFactor()));
            started = System.nanoTime();
            /*
             * Packet-driven mutation transactions already own complete loaded
             * chunks. Re-reading the same terrain through the rolling scanner while
             * that queue is backlogged doubled Level/heightmap/palette work and was
             * the dominant 600-1400 MiB/s gameplay allocation source. Cave may still
             * advance its projection, but the scanner suppresses its duplicate
             * surface/archive fallback until the authoritative queue catches up.
             */
            ChunkScanner.getInstance().scanAroundPlayerUniform(
                    minecraft, radius, authoritativeBacklog);
            telemetry.record(MapObservationTelemetry.Lane.LIVE_CRITICAL,
                    System.nanoTime() - started,
                    authoritativeBacklog ? -radius : radius);
        }

        started = System.nanoTime();
        pipeline.tickObservation(minecraft, profile.allowLayerWarmup());
        telemetry.record(MapObservationTelemetry.Lane.LAYER_WARMUP,
                System.nanoTime() - started, profile.allowLayerWarmup() ? 1 : 0);
        // Manager adapters publish completion only after the asynchronous cave
        // archive has advanced. This is deliberately after tickObservation so a
        // stage never becomes READY merely because its IO was requested.
        CaveMapManager.getInstance().tickWorkGraphCompletions();
        FullCaveMapManager.getInstance().tickWorkGraphCompletions();

        started = System.nanoTime();
        MapViewportCoordinator.getInstance().tick(minecraft, profile);
        telemetry.record(MapObservationTelemetry.Lane.LIVE_VISIBLE,
                System.nanoTime() - started, profile.allowVisibleScan() ? 1 : 0);

        MapMutationBus.Snapshot mutations = MapMutationBus.getInstance().snapshot();
        int generatedChunks = GeneratedChunkIndex.getInstance().entryCount();
        telemetry.updateQueues(mutations.pendingColumns(), mutations.pendingChunks(),
                mutations.pendingRegions(),
                generatedChunks, governor.underPressure());
        CaveWorldSaveReader.SourceCacheSnapshot source =
                CaveWorldSaveReader.getInstance().sourceCacheSnapshot();
        telemetry.updateDecodedSource(source.regions(), source.decodedChunks(),
                source.residentBytes(), source.targetBytes(), source.decodeQueue(),
                source.heapPressure());
        MapWorkGraph.Snapshot graph = MapWorkGraph.getInstance().snapshot();
        telemetry.updateWorkGraph(graph.regions(), graph.dirtyStages(),
                graph.runningStages(), graph.readyStages());
    }
}
