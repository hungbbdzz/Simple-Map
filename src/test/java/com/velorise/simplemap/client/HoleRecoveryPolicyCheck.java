package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static guard for PASS57 no-hole streaming invariants. */
public final class HoleRecoveryPolicyCheck {
    public static void main(String[] args) throws Exception {
        String root = Files.isDirectory(Path.of("src/main/java"))
                ? "src/main/java/com/velorise/simplemap/client/" : "";
        String plan = Files.readString(Path.of(root + "MapRenderPlan.java"));
        String renderer = Files.readString(Path.of(root + "MapRenderer.java"));
        String textures = Files.readString(Path.of(root + "MapTextureManager.java"));
        String cave = Files.readString(Path.of(root + "CaveTextureManager.java"));

        require(plan.contains("PHASE_LEGACY_UNDERLAY = 2"),
                "missing stable legacy underlay phase");
        require(renderer.contains("collectLegacySurfaceCoverage(builder, surfaceTextures"),
                "surface renderer does not retain the 512x512 fallback");
        require(textures.contains("planner.advanceFullscreenSlice();"),
                "fullscreen planner is not cyclic");
        require(!textures.contains("if (unsettled || admitted > 0) return;"),
                "completion frontier still blocks later surface rows");
        require(textures.contains("SURFACE_PAGE_WAITING_COMPLETE_SOURCE"),
                "zero-subtile source waits are not terminally settled");
        require(cave.contains("previousLayerY = sameBand(oldLayer, layerY)")
                        && cave.contains("atomic whole-page swap"),
                "layered cave no longer retains a bounded same-band fallback");
        require(cave.contains("previousLayerY = Integer.MIN_VALUE;"),
                "completed same-band handoff cannot retire its fallback");
        System.out.println("HOLE_RECOVERY_POLICY_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
