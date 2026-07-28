package com.velorise.simplemap.client.pipeline;

import com.velorise.simplemap.client.session.MapSession;
import com.velorise.simplemap.client.session.MapSessionManager;

/**
 * Immutable ownership stamp carried by asynchronous map work and prepared output.
 * A plain stage revision is not sufficient: publication must also prove that the
 * world session, source generation, style generation and projection generation
 * still match the owner that produced the payload.
 */
public record RevisionStamp(long sessionId, long sourceGeneration,
        long styleGeneration, long projectionGeneration) {

    public RevisionStamp {
        if (sessionId < 0L || sourceGeneration < 0L
                || styleGeneration < 0L || projectionGeneration < 0L) {
            throw new IllegalArgumentException("Revision generations cannot be negative");
        }
    }

    public static RevisionStamp capture(MapSession session) {
        return session == null ? sessionOnly(0L) : session.stamp();
    }

    /** Compatibility stamp for legacy call sites not migrated to the full tuple yet. */
    public static RevisionStamp sessionOnly(long sessionId) {
        return new RevisionStamp(Math.max(0L, sessionId), 0L, 0L, 0L);
    }

    public boolean isComplete() {
        return sessionId > 0L && sourceGeneration > 0L
                && styleGeneration > 0L && projectionGeneration > 0L;
    }

    public boolean matches(MapSession session) {
        if (session == null || session.sessionId() != sessionId) return false;
        if (!isComplete()) return session.state() == MapSession.State.ACTIVE
                && !session.rootToken().isCancelled();
        return session.matches(this);
    }

    public boolean isCurrent() {
        return MapSessionManager.getInstance().isCurrent(this);
    }
}
