package com.velorise.simplemap.client.session;

import com.velorise.simplemap.client.pipeline.RevisionStamp;

public final class MapSessionStampCheck {
    private MapSessionStampCheck() { }

    public static void main(String[] args) {
        MapSession session = new MapSession(7L, "world", "minecraft:overworld",
                3L, 5L, 11L, () -> true);
        session.activate();
        RevisionStamp first = session.stamp();
        require(first == session.stamp(), "unchanged stamp must be reused");
        session.updateStyleGeneration(6L);
        RevisionStamp styled = session.stamp();
        require(styled != first && styled == session.stamp(),
                "style change must replace cache once");
        session.updateProjectionGeneration(12L);
        RevisionStamp projected = session.stamp();
        require(projected != styled && projected == session.stamp(),
                "projection change must replace cache once");
        System.out.println("MAP_SESSION_STAMP_CACHE_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
