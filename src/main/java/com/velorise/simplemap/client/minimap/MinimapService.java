package com.velorise.simplemap.client.minimap;

import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;

/**
 * Dedicated minimap ring/last-good state.
 *
 * <p>{@code MapViewportCoordinator} is the sole surface demand authority. This
 * service intentionally does not submit a second exact-page request every 50 ms;
 * it only tracks the stable minimap footprint and retained-frame state.</p>
 */
public final class MinimapService {
    public record Summary(long ringGeneration, int diameter,
            long requests, long skipped, long lastGoodRevision,
            boolean lastGoodAvailable) { }

    private static final MinimapService INSTANCE = new MinimapService();
    private static final int RING_DIAMETER = 13;

    private final FixedTileRing ring = new FixedTileRing(RING_DIAMETER);
    private final ColumnSignatureStore signatures =
            new ColumnSignatureStore(RING_DIAMETER * 16 * RING_DIAMETER * 16);
    private long requests;
    private long skipped;
    private long lastGoodRevision;
    private boolean lastGoodAvailable;
    private long lastRequestNanos;
    private long sessionId;

    private MinimapService() { }

    public static MinimapService getInstance() { return INSTANCE; }

    public void update(double centerX, double centerZ, float zoom) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null || !stamp.isCurrent()) return;
        if (sessionId != stamp.sessionId()) resetForSession(stamp.sessionId());
        int chunkX = ((int) Math.floor(centerX)) >> 4;
        int chunkZ = ((int) Math.floor(centerZ)) >> 4;
        boolean moved = ring.recenter(chunkX, chunkZ);
        long now = System.nanoTime();
        if (!moved && now - lastRequestNanos < 50_000_000L) {
            skipped++;
            return;
        }
        lastRequestNanos = now;
        requests++;
    }

    /** Called after a minimap frame successfully drew non-empty content. */
    public synchronized void markLastGood(long revision) {
        lastGoodRevision = Math.max(lastGoodRevision, Math.max(1L, revision));
        lastGoodAvailable = true;
    }

    public synchronized boolean hasLastGood() { return lastGoodAvailable; }

    public synchronized Summary summary() {
        return new Summary(ring.generation(), ring.diameter(), requests,
                skipped, lastGoodRevision, lastGoodAvailable);
    }

    public synchronized void clear() {
        sessionId = 0L;
        signatures.clear();
        lastGoodRevision = 0L;
        lastGoodAvailable = false;
        lastRequestNanos = 0L;
    }

    private synchronized void resetForSession(long newSessionId) {
        sessionId = newSessionId;
        signatures.clear();
        lastGoodRevision = 0L;
        lastGoodAvailable = false;
    }
}
