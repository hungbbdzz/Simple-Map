package com.velorise.simplemap.client;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Latency-oriented cave-page adapter over the unified CPU control plane.
 * Retained only for callers that build a page from a legacy 512 snapshot.
 */
final class CavePageBuildWorker {
    private static final int MAX_QUEUED = 96;
    private static final AtomicInteger QUEUED = new AtomicInteger();

    private CavePageBuildWorker() {
    }

    static CompletableFuture<PreparedPage> tryBuild(
            int[] source, short[] heights, int pageX, int pageZ,
            int terrainSlopes, int profile, long revision, int priority,
            BooleanSupplier stillValid) {
        if (source == null
                || source.length != MapPageLayout.REGION_SIZE * MapPageLayout.REGION_SIZE) {
            return null;
        }
        if (QUEUED.incrementAndGet() > MAX_QUEUED) {
            QUEUED.decrementAndGet();
            return null;
        }

        CompletableFuture<PreparedPage> future = new CompletableFuture<>();
        MapRequestLane lane = priority >= MapRequestLane.MINIMAP.priorityBase()
                ? MapRequestLane.MINIMAP : MapRequestLane.FULLSCREEN;
        MapWorkScheduler.WorkType type = lane == MapRequestLane.MINIMAP
                ? MapWorkScheduler.WorkType.MINIMAP_EXACT
                : MapWorkScheduler.WorkType.EXACT_BUILD;
        BooleanSupplier valid = () -> !future.isCancelled()
                && (stillValid == null || stillValid.getAsBoolean());
        boolean accepted = MapWorkScheduler.tryCpu(lane, type, priority, 12,
                valid, () -> {
                    try {
                        if (!valid.getAsBoolean()) {
                            throw new java.util.concurrent.CancellationException();
                        }
                        int[] styled = CaveReliefColorizer.colorizePage(
                                source, heights, pageX, pageZ,
                                terrainSlopes, profile, valid);
                        future.complete(new PreparedPage(styled, revision));
                    } catch (Throwable throwable) {
                        future.completeExceptionally(throwable);
                    } finally {
                        QUEUED.decrementAndGet();
                    }
                });
        if (!accepted) {
            QUEUED.decrementAndGet();
            return null;
        }
        return future;
    }

    static int queueDepth() {
        return QUEUED.get();
    }

    record PreparedPage(int[] styled, long revision) {
    }
}
