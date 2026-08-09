package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

/**
 * Separates the visible projection owner from speculative cache warmup.
 *
 * <p>Only a visible HUD/fullscreen lane may retarget the active Layered Top-Y.
 * Background and prefetch work can populate immutable source/archive caches, but
 * must never retire or replace the projection currently being presented.</p>
 */
final class CaveProjectionOwnershipPolicy {
    private CaveProjectionOwnershipPolicy() { }

    static boolean ownsActiveProjection(MapRequestLane lane) {
        return lane == MapRequestLane.MINIMAP || lane == MapRequestLane.FULLSCREEN;
    }
}
