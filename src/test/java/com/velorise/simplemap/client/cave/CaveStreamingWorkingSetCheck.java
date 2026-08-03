package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static regression guard for the runtime-validated cave streaming working set. */
public final class CaveStreamingWorkingSetCheck {
    private CaveStreamingWorkingSetCheck() { }

    public static void main(String[] args) throws Exception {
        String transition = read("CaveModeTransitionPolicy.java");
        String scheduler = read("CaveDisplayScheduler.java");
        String manager = read("UnifiedCaveTextureManager.java");
        String mode = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/CaveMode.java"));

        require(transition.contains("exactAdmissionBudget(int normal)")
                        && transition.contains("sourceAdmissionBudget(int normal)")
                        && transition.contains("loadedFrontierBudget(int normal)")
                        && transition.contains("viewportChunkBudget(int normal)")
                        && transition.contains("liveRadius(int normal)")
                        && transition.contains("foregroundBudget(long normal)")
                        && transition.contains("return Math.max(0, normal)")
                        && transition.contains("return Math.max(0L, normal)"),
                "mode switch again throttles the visible viewport instead of the deadline");
        require(manager.contains("ACTIVE_VIEWPORT_RESERVED_SLOTS")
                        && manager.contains("modeRetireQueue")
                        && manager.contains("drainModeRetirementsLocked")
                        && manager.contains("onModeChanged(CaveView nextView)"),
                "active-mode atlas working-set handoff was removed");
        require(mode.contains("UnifiedCaveTextureManager.getInstance()")
                        && mode.contains("onModeChanged(nextView)"),
                "cave mode no longer transfers GPU ownership immediately");
        require(manager.contains("visibleTileMask")
                        && manager.contains("CAVE_FULL_TILE_SWAP")
                        && manager.contains("FIRST_PUBLICATION_MIN_KNOWN_COLUMNS = 256")
                        && manager.contains("commitStagedTiles"),
                "FULL projection lost transactional 16x16 tile publication");
        require(manager.contains("publicationCursor")
                        && manager.contains("isCompletionPublicationEligible")
                        && manager.contains("FULLSCREEN_PUBLICATION_BURST"),
                "fullscreen publication frontier is no longer bounded centre-out");
        require(scheduler.contains("CaveLoadHierarchy.centerOutOrdinal")
                        && scheduler.contains("localDistancePenalty")
                        && scheduler.contains("buildVisiblePagePlan"),
                "cave task priority no longer follows the centre-out page plan");
        require(scheduler.contains("COLUMN_BURST = 32")
                        && scheduler.contains("LOADED_CHUNKS_ADMITTED_PER_PULSE = 6"),
                "coherent foreground cave throughput regressed");
        System.out.println("CAVE_STREAMING_WORKING_SET_PASS");
    }

    private static String read(String file) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave", file));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
