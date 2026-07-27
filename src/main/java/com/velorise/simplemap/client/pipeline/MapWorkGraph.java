package com.velorise.simplemap.client.pipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One authoritative work graph for CPU/IO/GPU stages. A work key identifies
 * a stage; its backing record is always the single RegionRecord for that
 * session/region combination.
 */
public final class MapWorkGraph {
    public enum Admission { ACCEPTED, COALESCED, CANCELLED }

    /** Allocation-free-at-call-site summary for the debug/metrics control plane. */
    public record Snapshot(int regions, int dirtyStages, int runningStages,
                           int readyStages, int cancelledStages) { }

    private static final MapWorkGraph INSTANCE = new MapWorkGraph();
    private final ConcurrentHashMap<RegionKey, RegionRecord> records = new ConcurrentHashMap<>();

    private MapWorkGraph() { }

    public static MapWorkGraph getInstance() {
        return INSTANCE;
    }

    public Admission request(MapWorkKey key, long revision) {
        RegionKey regionKey = new RegionKey(key.sessionId(), key.regionX(), key.regionZ());
        RegionRecord record = records.computeIfAbsent(regionKey, RegionRecord::new);
        if (record.isCancelled()) return Admission.CANCELLED;
        return record.request(key, revision) ? Admission.ACCEPTED : Admission.COALESCED;
    }

    public RegionRecord.Lease tryBegin(MapWorkKey key) {
        RegionRecord record = records.get(new RegionKey(key.sessionId(), key.regionX(), key.regionZ()));
        return record == null ? null : record.tryBegin(key);
    }

    public void complete(RegionRecord.Lease lease) {
        if (lease == null) return;
        RegionRecord record = records.get(regionKey(lease.key()));
        if (record != null) record.complete(lease);
    }

    /** Completes a work stage from an asynchronous manager callback. */
    public void complete(MapWorkKey key, long revision) {
        if (key == null) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.complete(key, revision);
    }

    public void defer(RegionRecord.Lease lease) {
        if (lease == null) return;
        RegionRecord record = records.get(regionKey(lease.key()));
        if (record != null) record.defer(lease);
    }

    /** Returns a manager-owned stage to DIRTY when its external work is aborted. */
    public void defer(MapWorkKey key) {
        if (key == null) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.defer(key);
    }

    public RegionRecord.Snapshot snapshot(MapWorkKey key) {
        if (key == null) return null;
        RegionRecord record = records.get(regionKey(key));
        return record == null ? null : record.snapshot();
    }

    /**
     * Control-plane lookup that does not require callers to invent a stage or
     * projection. This is the intended entry point for diagnostics and future
     * debug overlays: a region has one authoritative record regardless of the
     * number of CPU/IO/GPU stages currently attached to it.
     */
    public RegionRecord.Snapshot snapshot(long sessionId, int regionX, int regionZ) {
        RegionRecord record = records.get(new RegionKey(sessionId, regionX, regionZ));
        return record == null ? null : record.snapshot();
    }

    public void cancelSession(long sessionId) {
        for (Map.Entry<RegionKey, RegionRecord> entry : records.entrySet()) {
            if (entry.getKey().sessionId() != sessionId) continue;
            entry.getValue().cancel();
            records.remove(entry.getKey(), entry.getValue());
        }
    }

    public void clear() {
        records.clear();
    }

    public int recordCount() {
        return records.size();
    }

    /**
     * A coarse graph health snapshot. It intentionally avoids exposing mutable
     * records to render/debug callers, while still showing whether work is
     * waiting, executing or genuinely committed.
     */
    public Snapshot snapshot() {
        int dirty = 0;
        int running = 0;
        int ready = 0;
        int cancelled = 0;
        for (RegionRecord record : records.values()) {
            for (RegionRecord.StageSnapshot stage : record.snapshot().stages().values()) {
                switch (stage.state()) {
                    case DIRTY -> dirty++;
                    case RUNNING -> running++;
                    case READY -> ready++;
                    case CANCELLED -> cancelled++;
                    case CLEAN -> { }
                }
            }
        }
        return new Snapshot(records.size(), dirty, running, ready, cancelled);
    }

    private static RegionKey regionKey(MapWorkKey key) {
        return new RegionKey(key.sessionId(), key.regionX(), key.regionZ());
    }
}
