package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS95 guard: Cave does not derive hierarchy levels the renderer can never select. */
public final class CaveRenderHierarchyWorkCapCheck {
    private CaveRenderHierarchyWorkCapCheck() { }

    public static void main(String[] args) throws Exception {
        String lod = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveLodTree.java"));
        String renderer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapRenderer.java"));
        require(lod.contains("RENDER_MAX_LEVEL = 3")
                        && lod.contains("child.key.level() >= RENDER_MAX_LEVEL")
                        && renderer.contains("MapLodPolicy.branchLevel(activePolicyScale, 3)"),
                "branch builder still amplifies every exact update into invisible L4-L7 work");
        System.out.println("CAVE_RENDER_HIERARCHY_WORK_CAP_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
