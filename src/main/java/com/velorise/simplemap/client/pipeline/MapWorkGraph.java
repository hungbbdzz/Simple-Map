package com.velorise.simplemap.client.pipeline;

import com.velorise.simplemap.client.session.MapSessionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Authoritative region work-state graph. Queue entries are disposable execution
 * hints; all semantic demand, revision and publication state lives here.
 */
public final class MapWorkGraph {
    public enum Admission { ACCEPTED, COALESCED, CANCELLED, STALE }

    /** Allocation-light summary for the debug/metrics control plane. */
    public record Snapshot(int regions, int dirtyStages, int runningStages,
            int preparedStages, int readyStages, int publishedStages,
            int cancelledStages) { }

    private static final MapWorkGraph INSTANCE = new MapWorkGraph();
    private final ConcurrentHashMap<RegionKey, RegionRecord> records =
            new ConcurrentHashMap<>();
    /**
     * Session ids are monotonic, so one high-water mark prevents late callbacks
     * from recreating closed records without retaining one boxed tombstone per
     * dimension switch for the lifetime of the client.
     */
    private final AtomicLong cancelledThroughSession = new AtomicLong();

    private MapWorkGraph() { }

    public static MapWorkGraph getInstance() {
        return INSTANCE;
    }

    public Admission request(MapWorkKey key, long revision) {
        return request(key, revision, RegionRecord.ALL_LEAVES);
    }

    public Admission request(MapWorkKey key, long revision, long dirtyMask) {
        if (key == null || isCancelledSession(key.sessionId())) {
            return Admission.CANCELLED;
        }
        if (key.stamp() != null && key.stamp().isComplete()
                && !MapSessionManager.getInstance().isCurrent(key.stamp())) {
            return Admission.STALE;
        }
        RegionKey regionKey = regionKey(key);
        RegionRecord record = records.computeIfAbsent(regionKey, RegionRecord::new);
        if (record.isCancelled()) return Admission.CANCELLED;
        return record.request(key, revision, dirtyMask)
                ? Admission.ACCEPTED : Admission.COALESCED;
    }

    public RegionRecord.Lease tryBegin(MapWorkKey key) {
        if (!isUsable(key)) return null;
        RegionRecord record = records.get(regionKey(key));
        return record == null ? null : record.tryBegin(key);
    }

    public void complete(RegionRecord.Lease lease) {
        if (lease == null || !isUsable(lease.key())) return;
        RegionRecord record = records.get(regionKey(lease.key()));
        if (record != null) record.complete(lease);
    }

    public void complete(MapWorkKey key, long revision) {
        if (!isUsable(key)) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.complete(key, revision);
    }

    public void markPrepared(MapWorkKey key, long revision, long mask) {
        if (!isUsable(key)) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.markPrepared(key, revision, mask);
    }

    public void markGpuPublished(MapWorkKey key, long revision, long residentMask) {
        if (!isUsable(key)) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.markGpuPublished(key, revision, residentMask);
    }

    public void markGpuEvicted(MapWorkKey key, long revision, long evictedMask) {
        if (key == null || isCancelledSession(key.sessionId())) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null && !record.isCancelled()) {
            record.markGpuEvicted(key, revision, evictedMask);
        }
    }

    public void markCacheCommitted(MapWorkKey key, long revision, long committedMask) {
        if (!isUsable(key)) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null) record.markCacheCommitted(key, revision, committedMask);
    }

    public void markCacheDirty(MapWorkKey key, long revision, long dirtyMask) {
        if (key == null || isCancelledSession(key.sessionId())) return;
        RegionRecord record = records.get(regionKey(key));
        if (record != null && !record.isCancelled()) {
            record.markCacheDirty(key, revision, dirtyMask);
        }
    }

    public void markSourceChunkDirty(RevisionStamp stamp, int regionX, int regionZ,
            int localChunkIndex, long revision) {
        if (stamp == null || !stamp.isCurrent()) return;
        RegionKey key = new RegionKey(stamp.sessionId(), regionX, regionZ);
        RegionRecord record = records.computeIfAbsent(key, RegionRecord::new);
        record.markSourceChunkDirty(localChunkIndex, revision);
    }

    public void clearSourceChunkDirty(RevisionStamp stamp, int regionX, int regionZ,
            int localChunkIndex) {
        if (stamp == null || isCancelledSession(stamp.sessionId())) return;
        RegionRecord record = records.get(new RegionKey(stamp.sessionId(), regionX, regionZ));
        if (record != null) record.clearSourceChunkDirty(localChunkIndex);
    }

    public void defer(RegionRecord.Lease lease) {
        if (lease == null) return;
        RegionRecord record = records.get(regionKey(lease.key()));
        if (record != null) record.defer(lease);
    }

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

    public RegionRecord.Snapshot snapshot(long sessionId, int regionX, int regionZ) {
        RegionRecord record = records.get(new RegionKey(sessionId, regionX, regionZ));
        return record == null ? null : record.snapshot();
    }

    /** Reconstructable queue hints for stages whose durable state is still dirty. */
    public List<MapWorkKey> dirtyWorkKeys(long sessionId) {
        List<MapWorkKey> result = new ArrayList<>();
        for (Map.Entry<RegionKey, RegionRecord> entry : records.entrySet()) {
            if (entry.getKey().sessionId() != sessionId) continue;
            RegionRecord.Snapshot snapshot = entry.getValue().snapshot();
            for (Map.Entry<RegionRecord.StageKey, RegionRecord.StageSnapshot> stage
                    : snapshot.stages().entrySet()) {
                if (stage.getValue().state() != RegionRecord.StageState.DIRTY) continue;
                RevisionStamp stamp = revisionStampFor(sessionId, snapshot);
                result.add(new MapWorkKey(stamp, entry.getKey().regionX(),
                        entry.getKey().regionZ(), stage.getKey().stage(),
                        stage.getKey().projectionId()));
            }
        }
        return result;
    }

    public void cancelSession(long sessionId) {
        cancelledThroughSession.accumulateAndGet(sessionId, Math::max);
        for (Map.Entry<RegionKey, RegionRecord> entry : records.entrySet()) {
            if (entry.getKey().sessionId() != sessionId) continue;
            entry.getValue().cancel();
            records.remove(entry.getKey(), entry.getValue());
        }
    }

    /** Clears live records. Session tombstones intentionally remain authoritative. */
    public void clear() {
        for (RegionRecord record : records.values()) record.cancel();
        records.clear();
    }

    public int recordCount() {
        return records.size();
    }

    public Snapshot snapshot() {
        int dirty = 0;
        int running = 0;
        int prepared = 0;
        int ready = 0;
        int published = 0;
        int cancelled = 0;
        for (RegionRecord record : records.values()) {
            for (RegionRecord.StageSnapshot stage : record.snapshot().stages().values()) {
                switch (stage.state()) {
                    case DIRTY -> dirty++;
                    case RUNNING -> running++;
                    case PREPARED -> prepared++;
                    case READY -> ready++;
                    case PUBLISHED -> published++;
                    case CANCELLED -> cancelled++;
                    case CLEAN -> { }
                }
            }
        }
        return new Snapshot(records.size(), dirty, running, prepared,
                ready, published, cancelled);
    }

    private boolean isUsable(MapWorkKey key) {
        if (key == null || isCancelledSession(key.sessionId())) return false;
        RevisionStamp stamp = key.stamp();
        return stamp == null || !stamp.isComplete()
                || MapSessionManager.getInstance().isCurrent(stamp);
    }

    private boolean isCancelledSession(long sessionId) {
        return sessionId > 0L && sessionId <= cancelledThroughSession.get();
    }

    private static RevisionStamp revisionStampFor(long sessionId,
            RegionRecord.Snapshot snapshot) {
        RevisionStamp active = MapSessionManager.getInstance().activeStamp();
        if (active != null && active.sessionId() == sessionId) return active;
        if (snapshot.sourceGeneration() > 0L && snapshot.styleGeneration() > 0L
                && snapshot.projectionGeneration() > 0L) {
            return new RevisionStamp(sessionId, snapshot.sourceGeneration(),
                    snapshot.styleGeneration(), snapshot.projectionGeneration());
        }
        return RevisionStamp.sessionOnly(sessionId);
    }

    private static RegionKey regionKey(MapWorkKey key) {
        return new RegionKey(key.sessionId(), key.regionX(), key.regionZ());
    }
}
