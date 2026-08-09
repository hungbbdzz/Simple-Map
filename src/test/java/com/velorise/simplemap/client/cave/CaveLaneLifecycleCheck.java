package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static guard for the current projection-controller lane lifecycle. */
public final class CaveLaneLifecycleCheck {
    private CaveLaneLifecycleCheck() { }

    public static void main(String[] args) throws Exception {
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapViewportCoordinator.java"));
        require(coordinator.contains("CaveProjectionController.getInstance().request("),
                "visible cave demand must enter the projection controller");
        require(coordinator.contains("request.lane);"),
                "visible cave demand must preserve viewport lane ownership");
        require(coordinator.contains(
                        "UnifiedCaveTextureManager.getInstance().suspendLane(MapRequestLane.MINIMAP)"),
                "fullscreen handoff must revoke hidden minimap ownership");
        require(coordinator.contains(
                        "UnifiedCaveTextureManager.getInstance().suspendLane(MapRequestLane.FULLSCREEN)"),
                "movement handoff must revoke fullscreen ownership");

        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        require(manager.contains("now - planner.lastEnumerationMs > ACTIVE_PLANNER_GRACE_MS"),
                "attached lease must require a live viewport planner");
        System.out.println("CAVE_LANE_LIFECYCLE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
