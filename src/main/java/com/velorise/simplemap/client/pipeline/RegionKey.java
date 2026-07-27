package com.velorise.simplemap.client.pipeline;

/**
 * Stable identity for all pipeline state belonging to one mapped region in
 * one session. Projection and work stage deliberately do not belong here:
 * they are separate facets of the same region record.
 */
public record RegionKey(long sessionId, int regionX, int regionZ) {
}
