package com.velorise.simplemap.client.pipeline;

import java.util.HashMap;
import java.util.Map;

/**
 * Durable authority for all work concerning one map region. Queues only hold
 * execution hints; this record retains the requested revision if a hint is
 * coalesced, deferred, cancelled, or temporarily cannot acquire resources.
 */
public final class RegionRecord {
    public enum StageState { CLEAN, DIRTY, RUNNING, READY, CANCELLED }

    /** A short-lived claim issued to exactly one worker for a region stage. */
    public record Lease(MapWorkKey key, long revision) { }

    /** Read-only stage state for telemetry and future manager adapters. */
    public record StageSnapshot(long targetRevision, long completedRevision,
                                StageState state) { }

    /**
     * One stage may have more than one retained projection (for example cave
     * bands). Keeping that identity in inspection data prevents a newer band
     * from hiding an older dirty band in the control plane.
     */
    public record StageKey(MapWorkStage stage, int projectionId) { }

    /** Complete per-region snapshot: one record, many independently dirty stages. */
    public record Snapshot(RegionKey key, long sourceRevision, long styleRevision,
                           long projectionRevision, Map<StageKey, StageSnapshot> stages) { }

    private final RegionKey key;
    private final Map<StageSlot, StageRecord> stages = new HashMap<>();
    private long sourceRevision;
    private long styleRevision;
    private long projectionRevision;
    private boolean cancelled;

    RegionRecord(RegionKey key) {
        this.key = key;
    }

    synchronized boolean request(MapWorkKey workKey, long revision) {
        if (cancelled) return false;
        StageRecord stage = stages.computeIfAbsent(StageSlot.of(workKey), ignored -> new StageRecord());
        long requested = Math.max(1L, revision);
        boolean changed = requested > stage.targetRevision || stage.state == StageState.CLEAN;
        stage.targetRevision = Math.max(stage.targetRevision, requested);
        updateGeneration(workKey.stage(), requested);
        if (stage.state != StageState.RUNNING && stage.completedRevision < stage.targetRevision) {
            stage.state = StageState.DIRTY;
        }
        return changed || stage.state == StageState.DIRTY;
    }

    synchronized Lease tryBegin(MapWorkKey workKey) {
        if (cancelled) return null;
        StageRecord stage = stages.get(StageSlot.of(workKey));
        if (stage == null || stage.state != StageState.DIRTY
                || stage.completedRevision >= stage.targetRevision) return null;
        stage.state = StageState.RUNNING;
        return new Lease(workKey, stage.targetRevision);
    }

    synchronized void complete(Lease lease) {
        if (cancelled || lease == null) return;
        complete(lease.key(), lease.revision());
    }

    synchronized void complete(MapWorkKey key, long revision) {
        if (cancelled || key == null) return;
        StageRecord stage = stages.get(StageSlot.of(key));
        if (stage == null) return;
        stage.completedRevision = Math.max(stage.completedRevision, revision);
        stage.state = stage.completedRevision >= stage.targetRevision
                ? StageState.READY : StageState.DIRTY;
    }

    synchronized void defer(Lease lease) {
        if (cancelled || lease == null) return;
        defer(lease.key());
    }

    synchronized void defer(MapWorkKey key) {
        if (cancelled || key == null) return;
        StageRecord stage = stages.get(StageSlot.of(key));
        if (stage != null) stage.state = StageState.DIRTY;
    }

    synchronized void cancel() {
        cancelled = true;
        for (StageRecord stage : stages.values()) stage.state = StageState.CANCELLED;
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
                    new StageSnapshot(value.targetRevision, value.completedRevision, value.state));
        }
        return new Snapshot(key, sourceRevision, styleRevision, projectionRevision, Map.copyOf(snapshot));
    }

    private void updateGeneration(MapWorkStage stage, long revision) {
        switch (stage) {
            case SOURCE_CAPTURE, SOURCE_READ, SOURCE_DECODE, SOURCE_COMMIT ->
                    sourceRevision = Math.max(sourceRevision, revision);
            case CAVE_PROJECTION, FULL_CAVE_PROJECTION ->
                    projectionRevision = Math.max(projectionRevision, revision);
            case STYLE, LOD_DERIVE, GPU_PREPARE, GPU_UPLOAD, CACHE_COMMIT ->
                    styleRevision = Math.max(styleRevision, revision);
        }
    }

    private record StageSlot(MapWorkStage stage, int projectionId) {
        private static StageSlot of(MapWorkKey key) {
            return new StageSlot(key.stage(), key.projectionId());
        }
    }

    private static final class StageRecord {
        private long targetRevision;
        private long completedRevision;
        private StageState state = StageState.CLEAN;
    }
}
