package com.velorise.simplemap.client.pipeline;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Durable authority for all work concerning one map region. Executor queues only
 * hold hints; this record retains requested revisions, dirty masks and publication
 * state when work is coalesced, deferred, evicted or temporarily cannot acquire
 * resources.
 */
public final class RegionRecord {
    public static final int CHUNKS_PER_REGION = 32 * 32;
    public static final int SOURCE_MASK_WORDS = CHUNKS_PER_REGION / Long.SIZE;
    public static final long ALL_LEAVES = -1L;

    public enum StageState {
        CLEAN,
        DIRTY,
        RUNNING,
        PREPARED,
        READY,
        PUBLISHED,
        CANCELLED
    }

    /** A short-lived claim issued to exactly one worker for a region stage. */
    public record Lease(MapWorkKey key, long revision, long dirtyMask) { }

    /** Read-only stage state for telemetry and manager adapters. */
    public record StageSnapshot(long targetRevision, long runningRevision,
            long completedRevision, long preparedRevision,
            long gpuPublishedRevision, long gpuResidentRevision,
            long cacheCommittedRevision, long dirtyMask, StageState state) {
        public boolean running() { return state == StageState.RUNNING; }
        public boolean ready() {
            return state == StageState.PREPARED || state == StageState.READY
                    || state == StageState.PUBLISHED;
        }
        public boolean published() { return state == StageState.PUBLISHED; }
    }

    /** A stage may retain multiple projection identities, e.g. cave bands. */
    public record StageKey(MapWorkStage stage, int projectionId) { }

    /** Complete immutable per-region inspection snapshot. */
    public record Snapshot(RegionKey key, long sourceGeneration,
            long styleGeneration, long projectionGeneration,
            long[] dirtySourceChunks, long dirtyExactMask, long dirtyLodMask,
            long cacheDirtyMask, long gpuResidentMask,
            Map<StageKey, StageSnapshot> stages, boolean cancelled) {
        public Snapshot {
            dirtySourceChunks = dirtySourceChunks == null
                    ? new long[SOURCE_MASK_WORDS]
                    : Arrays.copyOf(dirtySourceChunks, dirtySourceChunks.length);
            stages = stages == null ? Map.of() : Map.copyOf(stages);
        }

        @Override
        public long[] dirtySourceChunks() {
            return Arrays.copyOf(dirtySourceChunks, dirtySourceChunks.length);
        }
    }

    private final RegionKey key;
    private final Map<StageSlot, StageRecord> stages = new HashMap<>();
    private final long[] dirtySourceChunks = new long[SOURCE_MASK_WORDS];
    private long dirtyExactMask;
    private long dirtyLodMask;
    private long cacheDirtyMask;
    private long gpuResidentMask;
    private long sourceGeneration;
    private long styleGeneration;
    private long projectionGeneration;
    private boolean cancelled;

    RegionRecord(RegionKey key) {
        this.key = key;
    }

    synchronized boolean request(MapWorkKey workKey, long revision) {
        return request(workKey, revision, defaultDirtyMask(workKey.stage()));
    }

    synchronized boolean request(MapWorkKey workKey, long revision, long dirtyMask) {
        if (cancelled || workKey == null) return false;
        StageRecord stage = stages.computeIfAbsent(StageSlot.of(workKey),
                ignored -> new StageRecord());
        long requested = Math.max(1L, revision);
        long effectiveMask = dirtyMask == 0L ? defaultDirtyMask(workKey.stage()) : dirtyMask;
        boolean changed = requested > stage.targetRevision
                || (effectiveMask & ~stage.dirtyMask) != 0L
                || stage.state == StageState.CLEAN;
        stage.targetRevision = Math.max(stage.targetRevision, requested);
        stage.dirtyMask |= effectiveMask;
        updateGeneration(workKey, requested);
        updateRegionMasksForRequest(workKey.stage(), effectiveMask);
        if (stage.state != StageState.RUNNING
                && (stage.completedRevision < stage.targetRevision
                        || stage.dirtyMask != 0L)) {
            stage.state = StageState.DIRTY;
        }
        return changed || stage.state == StageState.DIRTY;
    }

    synchronized Lease tryBegin(MapWorkKey workKey) {
        if (cancelled || workKey == null) return null;
        StageRecord stage = stages.get(StageSlot.of(workKey));
        if (stage == null || stage.state != StageState.DIRTY
                || (stage.completedRevision >= stage.targetRevision
                        && stage.dirtyMask == 0L)) return null;
        stage.state = StageState.RUNNING;
        stage.runningRevision = stage.targetRevision;
        return new Lease(workKey, stage.targetRevision, stage.dirtyMask);
    }

    synchronized void complete(Lease lease) {
        if (cancelled || lease == null) return;
        complete(lease.key(), lease.revision(), lease.dirtyMask());
    }

    synchronized void complete(MapWorkKey workKey, long revision) {
        complete(workKey, revision, defaultDirtyMask(workKey == null
                ? null : workKey.stage()));
    }

    synchronized void complete(MapWorkKey workKey, long revision, long completedMask) {
        if (cancelled || workKey == null) return;
        StageRecord stage = stages.get(StageSlot.of(workKey));
        if (stage == null) return;
        stage.runningRevision = 0L;
        stage.completedRevision = Math.max(stage.completedRevision, revision);
        if (revision >= stage.targetRevision) stage.dirtyMask &= ~completedMask;
        stage.state = stage.completedRevision >= stage.targetRevision
                && stage.dirtyMask == 0L
                ? StageState.READY : StageState.DIRTY;
        clearRegionMasksOnCompletion(workKey.stage(), completedMask,
                stage.completedRevision >= stage.targetRevision);
    }

    synchronized void markPrepared(MapWorkKey workKey, long revision, long preparedMask) {
        if (cancelled || workKey == null) return;
        StageRecord stage = stages.get(StageSlot.of(workKey));
        if (stage == null) return;
        stage.runningRevision = 0L;
        stage.preparedRevision = Math.max(stage.preparedRevision, revision);
        stage.completedRevision = Math.max(stage.completedRevision, revision);
        if (revision >= stage.targetRevision) stage.dirtyMask &= ~preparedMask;
        stage.state = stage.preparedRevision >= stage.targetRevision
                && stage.dirtyMask == 0L
                ? StageState.PREPARED : StageState.DIRTY;
    }

    synchronized void markGpuPublished(MapWorkKey workKey, long revision,
            long residentMask) {
        if (cancelled || workKey == null) return;
        StageRecord stage = stages.get(StageSlot.of(workKey));
        if (stage == null) return;
        stage.gpuPublishedRevision = Math.max(stage.gpuPublishedRevision, revision);
        stage.gpuResidentRevision = Math.max(stage.gpuResidentRevision, revision);
        stage.completedRevision = Math.max(stage.completedRevision, revision);
        stage.preparedRevision = Math.max(stage.preparedRevision, revision);
        stage.dirtyMask &= ~residentMask;
        gpuResidentMask |= residentMask;
        dirtyExactMask &= ~residentMask;
        stage.state = revision >= stage.targetRevision
                && stage.dirtyMask == 0L
                ? StageState.PUBLISHED : StageState.DIRTY;
    }

    synchronized void markGpuEvicted(MapWorkKey workKey, long revision,
            long evictedMask) {
        if (cancelled || workKey == null) return;
        StageRecord stage = stages.get(StageSlot.of(workKey));
        if (stage == null) return;
        gpuResidentMask &= ~evictedMask;
        if (gpuResidentMask == 0L || revision >= stage.gpuResidentRevision) {
            stage.gpuResidentRevision = 0L;
        }
        stage.dirtyMask |= evictedMask;
        dirtyExactMask |= evictedMask;
        stage.state = stage.preparedRevision >= stage.targetRevision
                ? StageState.PREPARED : StageState.DIRTY;
    }

    synchronized void markCacheCommitted(MapWorkKey workKey, long revision,
            long committedMask) {
        if (cancelled || workKey == null) return;
        StageRecord stage = stages.get(StageSlot.of(workKey));
        if (stage == null) return;
        stage.cacheCommittedRevision = Math.max(stage.cacheCommittedRevision, revision);
        stage.completedRevision = Math.max(stage.completedRevision, revision);
        stage.dirtyMask &= ~committedMask;
        cacheDirtyMask &= ~committedMask;
        stage.state = revision >= stage.targetRevision
                && stage.dirtyMask == 0L
                ? StageState.PUBLISHED : StageState.DIRTY;
    }

    synchronized void markCacheDirty(MapWorkKey workKey, long revision,
            long dirtyMask) {
        if (cancelled || workKey == null) return;
        StageRecord stage = stages.computeIfAbsent(StageSlot.of(workKey),
                ignored -> new StageRecord());
        stage.targetRevision = Math.max(stage.targetRevision, Math.max(1L, revision));
        stage.dirtyMask |= dirtyMask;
        cacheDirtyMask |= dirtyMask;
        stage.state = stage.state == StageState.RUNNING
                ? StageState.RUNNING : StageState.DIRTY;
    }

    synchronized void markSourceChunkDirty(int localChunkIndex, long revision) {
        if (cancelled || localChunkIndex < 0 || localChunkIndex >= CHUNKS_PER_REGION) return;
        int word = localChunkIndex >>> 6;
        long bit = 1L << (localChunkIndex & 63);
        dirtySourceChunks[word] |= bit;
        sourceGeneration = Math.max(sourceGeneration, Math.max(1L, revision));
    }

    synchronized void clearSourceChunkDirty(int localChunkIndex) {
        if (localChunkIndex < 0 || localChunkIndex >= CHUNKS_PER_REGION) return;
        dirtySourceChunks[localChunkIndex >>> 6] &= ~(1L << (localChunkIndex & 63));
    }

    synchronized void defer(Lease lease) {
        if (cancelled || lease == null) return;
        defer(lease.key());
    }

    synchronized void defer(MapWorkKey workKey) {
        if (cancelled || workKey == null) return;
        StageRecord stage = stages.get(StageSlot.of(workKey));
        if (stage != null) {
            stage.runningRevision = 0L;
            stage.state = StageState.DIRTY;
        }
    }

    synchronized void cancel() {
        cancelled = true;
        for (StageRecord stage : stages.values()) {
            stage.runningRevision = 0L;
            stage.state = StageState.CANCELLED;
        }
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }

    synchronized Snapshot snapshot() {
        Map<StageKey, StageSnapshot> snapshot = new HashMap<>();
        for (Map.Entry<StageSlot, StageRecord> entry : stages.entrySet()) {
            StageRecord value = entry.getValue();
            StageSlot slot = entry.getKey();
            snapshot.put(new StageKey(slot.stage, slot.projectionId),
                    new StageSnapshot(value.targetRevision, value.runningRevision,
                            value.completedRevision, value.preparedRevision,
                            value.gpuPublishedRevision, value.gpuResidentRevision,
                            value.cacheCommittedRevision, value.dirtyMask, value.state));
        }
        return new Snapshot(key, sourceGeneration, styleGeneration,
                projectionGeneration, dirtySourceChunks, dirtyExactMask,
                dirtyLodMask, cacheDirtyMask, gpuResidentMask, snapshot, cancelled);
    }

    private void updateGeneration(MapWorkKey workKey, long revision) {
        RevisionStamp stamp = workKey.stamp();
        if (stamp != null && stamp.isComplete()) {
            sourceGeneration = Math.max(sourceGeneration, stamp.sourceGeneration());
            styleGeneration = Math.max(styleGeneration, stamp.styleGeneration());
            projectionGeneration = Math.max(projectionGeneration,
                    stamp.projectionGeneration());
            return;
        }
        switch (workKey.stage()) {
            case SOURCE_CAPTURE, SOURCE_READ, SOURCE_DECODE, SOURCE_COMMIT ->
                    sourceGeneration = Math.max(sourceGeneration, revision);
            case CAVE_PROJECTION, FULL_CAVE_PROJECTION ->
                    projectionGeneration = Math.max(projectionGeneration, revision);
            case STYLE -> styleGeneration = Math.max(styleGeneration, revision);
            case LOD_DERIVE, GPU_PREPARE, GPU_UPLOAD, CACHE_COMMIT -> { }
        }
    }

    private void updateRegionMasksForRequest(MapWorkStage stage, long mask) {
        if (stage == null) return;
        switch (stage) {
            case SOURCE_CAPTURE, SOURCE_READ, SOURCE_DECODE, SOURCE_COMMIT -> {
                if (mask == ALL_LEAVES) Arrays.fill(dirtySourceChunks, ALL_LEAVES);
            }
            case STYLE, GPU_PREPARE, GPU_UPLOAD -> dirtyExactMask |= mask;
            case LOD_DERIVE -> dirtyLodMask |= mask;
            case CACHE_COMMIT -> cacheDirtyMask |= mask;
            case CAVE_PROJECTION, FULL_CAVE_PROJECTION -> dirtyExactMask |= mask;
        }
    }

    private void clearRegionMasksOnCompletion(MapWorkStage stage, long mask,
            boolean targetReached) {
        if (!targetReached || stage == null) return;
        switch (stage) {
            case SOURCE_CAPTURE, SOURCE_READ, SOURCE_DECODE, SOURCE_COMMIT -> {
                if (mask == ALL_LEAVES) Arrays.fill(dirtySourceChunks, 0L);
            }
            case STYLE, GPU_PREPARE, GPU_UPLOAD,
                    CAVE_PROJECTION, FULL_CAVE_PROJECTION -> dirtyExactMask &= ~mask;
            case LOD_DERIVE -> dirtyLodMask &= ~mask;
            case CACHE_COMMIT -> cacheDirtyMask &= ~mask;
        }
    }

    private static long defaultDirtyMask(MapWorkStage stage) {
        return stage == null ? 0L : ALL_LEAVES;
    }

    private record StageSlot(MapWorkStage stage, int projectionId) {
        private static StageSlot of(MapWorkKey key) {
            return new StageSlot(key.stage(), key.projectionId());
        }
    }

    private static final class StageRecord {
        private long targetRevision;
        private long runningRevision;
        private long completedRevision;
        private long preparedRevision;
        private long gpuPublishedRevision;
        private long gpuResidentRevision;
        private long cacheCommittedRevision;
        private long dirtyMask;
        private StageState state = StageState.CLEAN;
    }
}
