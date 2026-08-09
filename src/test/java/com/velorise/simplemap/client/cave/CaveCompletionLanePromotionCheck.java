package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression guard for PASS69 completion-lane publication and cache fast paths. */
public final class CaveCompletionLanePromotionCheck {
    public static void main(String[] args) throws Exception {
        Path cave = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String manager = normalize(Files.readString(
                cave.resolve("UnifiedCaveTextureManager.java")));
        String repository = normalize(Files.readString(
                cave.resolve("CaveTileRepository.java")));

        require(manager.contains("reclassifyCompletedLane(candidate, now)"),
                "CPU-ready cave completions still retain their historical lane");
        require(manager.contains("CAVE_COMPLETED_LANE_PROMOTED"),
                "completion-lane promotion lacks runtime telemetry");
        require(manager.contains("buildOwnership(request, now)")
                        && manager.contains("CAVE_BUILD_LANE_PROMOTED")
                        && manager.contains("plannerActive(fullscreenPlanner, now)"),
                "active viewport ownership is still resolved only after CPU completion");
        require(manager.contains("CAVE_WEAK_COMPLETION_BYPASSED"),
                "a denied weak completion can still stop the publication drain");
        require(manager.contains("if (pendingInfo.pending.isDone())")
                        && manager.contains("promotePendingLaneLocked"),
                "foreground demand still rebuilds an already-completed payload");
        require(manager.contains("uploadLane == MapRequestLane.MINIMAP\n"
                        + "                        || uploadLane == MapRequestLane.FULLSCREEN"),
                "fullscreen cave residency restoration lacks foreground GPU admission");
        require(repository.indexOf("fastCacheKey")
                        < repository.indexOf("ProjectionWorkspace workspace"),
                "resolved cave page cache is still checked after the 6x6 gather");
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
