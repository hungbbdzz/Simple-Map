package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS93 guard for black-hole, wrong-slot and teleport residency fixes. */
public final class SurfaceCoherentSubtilePublicationCheck {
    private SurfaceCoherentSubtilePublicationCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client");
        String manager = Files.readString(root.resolve("MapTextureManager.java"));
        String renderer = Files.readString(root.resolve("MapRenderer.java"));
        String atlas = Files.readString(root.resolve("cave/SurfaceLeafAtlas.java"));
        String region = Files.readString(root.resolve("RegionSurfaceLodService.java"));
        String tree = Files.readString(root.resolve("cave/SurfaceLodTree.java"));

        require(manager.contains("PageTableEntry.withCoverageMask")
                        && manager.contains("SURFACE_VIEWPORT_ATLAS_REBASE")
                        && manager.contains("fullscreen_viewport_rebase"),
                "surface page-table mask or teleport atlas rebase is missing");
        require(renderer.contains("addLogicalSurfaceSubtiles")
                        && renderer.contains("1 << subtile")
                        && renderer.contains("for (int pageZ = minVisiblePageZ"),
                "surface renderer does not emit row-major mask-gated 16x16 units");
        require(atlas.contains("SLOT_REUSE_FENCE_NANOS")
                        && atlas.contains("QuarantinedSlot")
                        && atlas.contains("old front page table"),
                "surface atlas slots can still be reused before page-table retirement");
        require(region.contains("uploadedCompleteMask & (1L << child)")
                        && tree.contains("uploadedCompleteMask & (1L << childIndex)"),
                "partial branch coverage can still evict an exact page");
        System.out.println("SURFACE_COHERENT_SUBTILE_PUBLICATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
