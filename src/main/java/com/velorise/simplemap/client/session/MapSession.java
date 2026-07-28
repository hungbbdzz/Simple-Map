package com.velorise.simplemap.client.session;

import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.pipeline.RevisionStamp;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Identity plus cooperative cancellation for one connected map world and
 * dimension. Background work must capture a RevisionStamp and validate it before
 * committing CPU, disk or GPU output.
 */
public final class MapSession {
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
    private final MapCancellationToken rootToken;
    private volatile State state;

    MapSession(long sessionId, String worldIdentity, String dimensionIdentity,
            long sourceGeneration, long styleGeneration, long projectionGeneration,
            BooleanSupplier current) {
        this.sessionId = sessionId;
        this.worldIdentity = Objects.requireNonNullElse(worldIdentity, "unknown");
        this.dimensionIdentity = Objects.requireNonNullElse(dimensionIdentity,
                "minecraft:overworld");
        this.sourceGeneration = sourceGeneration;
        this.styleGeneration = styleGeneration;
        this.projectionGeneration = projectionGeneration;
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
        if (state != State.CLOSED) styleGeneration = Math.max(styleGeneration, generation);
    }

    void updateProjectionGeneration(long generation) {
        if (state != State.CLOSED) {
            projectionGeneration = Math.max(projectionGeneration, generation);
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
        return new RevisionStamp(sessionId, sourceGeneration,
                styleGeneration, projectionGeneration);
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
}
