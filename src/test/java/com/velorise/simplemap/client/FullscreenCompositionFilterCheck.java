package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression guard: density-correct LOD must not be blurred a second time. */
public final class FullscreenCompositionFilterCheck {
    private FullscreenCompositionFilterCheck() { }

    public static void main(String[] args) throws Exception {
        String renderer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/FullscreenMapFramebufferRenderer.java"));
        String caveAtlas = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveAtlasTexture.java"));
        String caveBranch = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveBranchAtlas.java"));
        String surfaceBranch = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/SurfaceBranchAtlas.java"));
        String surfaceLod = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/RegionSurfaceLodService.java"));

        require(renderer.contains("configureCompositionFilter(physicalDisplayScale")
                        && renderer.contains("physicalViewportWidth")
                        && renderer.contains("compositionFilter == GL11.GL_NEAREST")
                        && !renderer.contains("GL11.GL_LINEAR : GL11.GL_NEAREST"),
                "fullscreen target can blur the completed density-matched map");
        require(caveAtlas.contains("linearMinification ? GL11.GL_LINEAR : GL11.GL_NEAREST"),
                "atlas no longer distinguishes exact leaves from prefiltered hierarchy levels");
        require(caveBranch.contains("new CaveAtlasTexture(atlasSize, true,")
                        && surfaceBranch.contains("new CaveAtlasTexture(atlasSize, true,"),
                "branch atlas no longer uses one controlled minification stage");
        require(surfaceLod.contains("int flags = 0;")
                        && !surfaceLod.contains("int flags = PageTableEntry.FLAG_LINEAR;"),
                "surface branch publication still requests linear sampling");
        System.out.println("FULLSCREEN_DENSITY_MATCHED_LOD_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
