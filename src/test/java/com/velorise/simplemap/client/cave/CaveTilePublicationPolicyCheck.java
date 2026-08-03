package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapRequestLane;

public final class CaveTilePublicationPolicyCheck {
    private CaveTilePublicationPolicyCheck() { }

    public static void main(String[] args) {
        long now = 10_000L;
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, false, 0, now, now),
                "zero tiles");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, false, false, 1, now, now),
                "first publication");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.MINIMAP, true, false, 1, now, now),
                "minimap latency");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, true, 16, now, now),
                "projection replacement");
        require(!CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, false, 1, now,
                now + CaveTilePublicationPolicy.MAX_HOLD_MS - 1L),
                "bounded coalescing");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, false, 1, now,
                now + CaveTilePublicationPolicy.MAX_HOLD_MS),
                "deadline publication");
        require(CaveTilePublicationPolicy.shouldPublish(
                MapRequestLane.FULLSCREEN, true, false,
                CaveTilePublicationPolicy.MIN_BATCH_TILES, now, now + 1L),
                "batch publication");
        System.out.println("CAVE_TILE_PUBLICATION_POLICY_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
