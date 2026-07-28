package com.velorise.simplemap.client.pipeline;

import java.util.Objects;

/**
 * Stable identity for one region-stage demand. Queue entries may disappear;
 * this key remains represented by its RegionRecord until the requested
 * revision is completed or the owning map session is cancelled.
 */
public record MapWorkKey(long sessionId, int regionX, int regionZ,
        MapWorkStage stage, int projectionId, RevisionStamp stamp) {

    public MapWorkKey {
        stage = Objects.requireNonNull(stage, "stage");
        stamp = stamp == null ? RevisionStamp.sessionOnly(sessionId) : stamp;
        if (stamp.sessionId() != 0L && stamp.sessionId() != sessionId) {
            throw new IllegalArgumentException("Work key session and revision stamp disagree");
        }
    }

    /** Compatibility constructor retained while legacy manager adapters migrate. */
    public MapWorkKey(long sessionId, int regionX, int regionZ,
            MapWorkStage stage, int projectionId) {
        this(sessionId, regionX, regionZ, stage, projectionId,
                RevisionStamp.sessionOnly(sessionId));
    }

    public MapWorkKey(RevisionStamp stamp, int regionX, int regionZ,
            MapWorkStage stage, int projectionId) {
        this(Objects.requireNonNull(stamp, "stamp").sessionId(), regionX, regionZ,
                stage, projectionId, stamp);
    }
}
