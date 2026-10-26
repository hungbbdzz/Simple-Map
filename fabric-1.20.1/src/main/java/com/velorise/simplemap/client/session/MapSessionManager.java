package com.velorise.simplemap.client.session;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single lifecycle authority for map background work. Replacing a world or
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

    /** Resource/style changes update the active session atomically. */
    public synchronized long bumpStyleGeneration() {
        long generation = styleGeneration.incrementAndGet();
        MapSession session = active.get();
        if (session != null) session.updateStyleGeneration(generation);
        return generation;
    }

    /** Projection changes update the active session without discarding source. */
    public synchronized long bumpProjectionGeneration() {
        long generation = projectionGeneration.incrementAndGet();
        MapSession session = active.get();
        if (session != null) session.updateProjectionGeneration(generation);
        return generation;
    }

    public MapSession active() {
        return active.get();
    }

    public RevisionStamp activeStamp() {
        MapSession session = active.get();
        return session == null ? null : session.stamp();
    }

    public boolean isCurrent(long sessionId) {
        MapSession session = active.get();
        return session != null && session.state() == MapSession.State.ACTIVE
                && session.sessionId() == sessionId && !session.rootToken().isCancelled();
    }

    public boolean isCurrent(RevisionStamp stamp) {
        if (stamp == null) return false;
        MapSession session = active.get();
        return stamp.matches(session);
    }


    /**
 * Identity plus cooperative cancellation for one connected map world and
 * dimension. Background work must capture a RevisionStamp and validate it before
 * committing CPU, disk or GPU output.
 */
public static final class MapSession {
    public enum State {
        CREATED,
        OPENING,
        ACTIVE,
        FLUSHING,
        CLOSED
    }

    private final long sessionId;
    private final String worldIdentity;
    private final String dimensionIdentity;
    private final long sourceGeneration;
    private volatile long styleGeneration;
    private volatile long projectionGeneration;
    /** Immutable revision metadata reused until a generation changes. */
    private volatile RevisionStamp cachedStamp;
    private final MapCancellationToken rootToken;
    private volatile State state;

    public MapSession(long sessionId, String worldIdentity, String dimensionIdentity,
            long sourceGeneration, long styleGeneration, long projectionGeneration,
            BooleanSupplier current) {
        this.sessionId = sessionId;
        this.worldIdentity = Objects.requireNonNullElse(worldIdentity, "unknown");
        this.dimensionIdentity = Objects.requireNonNullElse(dimensionIdentity,
                "minecraft:overworld");
        this.sourceGeneration = sourceGeneration;
        this.styleGeneration = styleGeneration;
        this.projectionGeneration = projectionGeneration;
        this.cachedStamp = new RevisionStamp(sessionId, sourceGeneration,
                styleGeneration, projectionGeneration);
        this.state = State.CREATED;
        this.rootToken = new MapCancellationToken(() ->
                state == State.ACTIVE && current.getAsBoolean());
    }

    void activate() {
        state = State.ACTIVE;
    }

    void beginOpening() {
        if (state == State.CREATED) state = State.OPENING;
    }

    void beginFlush() {
        if (state != State.CLOSED) state = State.FLUSHING;
    }

    void close() {
        state = State.CLOSED;
        rootToken.cancel();
    }

    void updateStyleGeneration(long generation) {
        if (state == State.CLOSED) return;
        long updated = Math.max(styleGeneration, generation);
        if (updated != styleGeneration) {
            styleGeneration = updated;
            cachedStamp = new RevisionStamp(sessionId, sourceGeneration,
                    styleGeneration, projectionGeneration);
        }
    }

    void updateProjectionGeneration(long generation) {
        if (state == State.CLOSED) return;
        long updated = Math.max(projectionGeneration, generation);
        if (updated != projectionGeneration) {
            projectionGeneration = updated;
            cachedStamp = new RevisionStamp(sessionId, sourceGeneration,
                    styleGeneration, projectionGeneration);
        }
    }

    public long sessionId() { return sessionId; }
    public String worldIdentity() { return worldIdentity; }
    public String dimensionIdentity() { return dimensionIdentity; }
    public long sourceGeneration() { return sourceGeneration; }
    public long styleGeneration() { return styleGeneration; }
    public long projectionGeneration() { return projectionGeneration; }
    public State state() { return state; }
    public MapCancellationToken rootToken() { return rootToken; }

    public RevisionStamp stamp() {
        return cachedStamp;
    }

    public boolean matches(RevisionStamp stamp) {
        return stamp != null && matches(stamp.sessionId(), stamp.sourceGeneration(),
                stamp.styleGeneration(), stamp.projectionGeneration());
    }

    public boolean matches(long candidateSessionId, long candidateSourceGeneration,
            long candidateStyleGeneration, long candidateProjectionGeneration) {
        return state == State.ACTIVE
                && sessionId == candidateSessionId
                && sourceGeneration == candidateSourceGeneration
                && styleGeneration == candidateStyleGeneration
                && projectionGeneration == candidateProjectionGeneration
                && !rootToken.isCancelled();
    }

    public static final class MapCancellationToken implements BooleanSupplier {
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        private final BooleanSupplier generationValid;

        public MapCancellationToken(BooleanSupplier generationValid) {
            this.generationValid = generationValid == null ? () -> true : generationValid;
        }

        public void cancel() {
            cancelled.set(true);
        }

        public boolean isCancelled() {
            return cancelled.get() || !generationValid.getAsBoolean();
        }

        @Override
        public boolean getAsBoolean() {
            return !isCancelled();
        }

        public void checkpoint(String stage) {
            if (isCancelled()) {
                throw new java.util.concurrent.CancellationException("Map task cancelled at " + stage);
            }
        }
    }
}
}
