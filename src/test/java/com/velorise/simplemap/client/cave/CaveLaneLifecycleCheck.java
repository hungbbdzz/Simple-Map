package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

public final class CaveLaneLifecycleCheck {
    private CaveLaneLifecycleCheck() { }

    public static void main(String[] args) throws Exception {
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapViewportCoordinator.java"));
        require(coordinator.contains("setCaveLaneActive(request.lane, true)"),
                "cave demand must activate the lane");
        require(coordinator.contains("setCaveLaneActive(request.lane, false)"),
                "surface demand must suspend the cave lane");
        require(coordinator.contains("UnifiedCaveTextureManager.getInstance().suspendLane(lane)"),
                "lane transition must revoke exact cave ownership");

        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        require(manager.contains("now - planner.lastDemandMs <= ACTIVE_PLANNER_GRACE_MS"),
                "attached lease must require a live viewport planner");
        System.out.println("CAVE_LANE_LIFECYCLE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
