package com.velorise.simplemap.client.session;

import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single lifecycle authority for map background work.  Replacing a world or
 * dimension closes the old root token before the new session is published.
 */
public final class MapSessionManager {
    private static final MapSessionManager INSTANCE = new MapSessionManager();

    private final AtomicLong nextSessionId = new AtomicLong(1L);
    private final AtomicLong sourceGeneration = new AtomicLong(1L);
    private final AtomicLong styleGeneration = new AtomicLong(1L);
    private final AtomicLong projectionGeneration = new AtomicLong(1L);
    private final AtomicReference<MapSession> active = new AtomicReference<>();

    private MapSessionManager() {
    }

    public static MapSessionManager getInstance() {
        return INSTANCE;
    }

    public synchronized MapSession open(String worldIdentity, String dimensionIdentity) {
        MapSession previous = active.getAndSet(null);
        if (previous != null) {
            previous.beginFlush();
            previous.close();
            MapWorkGraph.getInstance().cancelSession(previous.sessionId());
        }
        long id = nextSessionId.getAndIncrement();
        long source = sourceGeneration.incrementAndGet();
        MapSession created = new MapSession(id, worldIdentity, dimensionIdentity,
                source, styleGeneration.get(), projectionGeneration.get(),
                () -> active.get() != null && active.get().sessionId() == id);
        created.beginOpening();
        active.set(created);
        created.activate();
        return created;
    }

    public synchronized void closeActive() {
        MapSession previous = active.getAndSet(null);
        if (previous != null) {
            previous.beginFlush();
            previous.close();
            MapWorkGraph.getInstance().cancelSession(previous.sessionId());
        }
        sourceGeneration.incrementAndGet();
    }

    /** Resource/style changes invalidate output without reopening the world. */
    public long bumpStyleGeneration() {
        return styleGeneration.incrementAndGet();
    }

    /** Projection changes invalidate view-specific output without losing source. */
    public long bumpProjectionGeneration() {
        return projectionGeneration.incrementAndGet();
    }

    public MapSession active() {
        return active.get();
    }

    public boolean isCurrent(long sessionId) {
        MapSession session = active.get();
        return session != null && session.state() == MapSession.State.ACTIVE
                && session.sessionId() == sessionId && !session.rootToken().isCancelled();
    }
}
