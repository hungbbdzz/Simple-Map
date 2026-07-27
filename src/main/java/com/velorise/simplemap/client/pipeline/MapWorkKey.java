package com.velorise.simplemap.client.pipeline;

/**
 * Stable identity for one region-stage demand. Queue entries may disappear;
 * this key remains represented by its RegionRecord until the requested
 * revision is completed or the owning map session is cancelled.
 */
public record MapWorkKey(long sessionId, int regionX, int regionZ,
        MapWorkStage stage, int projectionId) {
}
