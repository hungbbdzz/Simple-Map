package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guard for CPU branch staging without blurry first-contact publication. */
public final class CaveRegionParentFirstPublicationCheck {
    private CaveRegionParentFirstPublicationCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String manager = Files.readString(root.resolve("UnifiedCaveTextureManager.java"));
        String lod = Files.readString(root.resolve("CaveLodTree.java"));
        require(manager.contains("regionExactBacklog")
                        && manager.contains("MAX_REGION_EXACT_BACKLOG")
                        && manager.contains("lodTree.updatePage(key.dimension()")
                        && manager.contains("CAVE_REGION_BRANCH_AUTHORITY_STAGED"),
                "region-imported pages no longer feed the reusable branch hierarchy");
        require(lod.contains("boolean hasPublishedCoverage(")
                        && lod.contains("uploadedCompleteMask"),
                "versioned branch authority disappeared");
        int resolvedStart = manager.indexOf("private boolean fullscreenPublicationPageResolved");
        int resolvedEnd = manager.indexOf("private boolean isCompletionPublicationEligible", resolvedStart);
        require(resolvedStart >= 0 && resolvedEnd > resolvedStart,
                "fullscreen publication resolver is missing");
        String resolver = manager.substring(resolvedStart, resolvedEnd);
        require(!resolver.contains("lodTree.hasPublishedCoverage")
                        && !resolver.contains("lodTree.coversPage"),
                "a blurry branch can still open first-contact exact publication");
        require(manager.contains("order=viewport_scanline_sweep_top_left")
                        && manager.contains("discardVisibleProjectionForRetarget"),
                "clear scanline publication or exact-only Top-Y retarget is missing");
        System.out.println("CAVE_REGION_PARENT_FIRST_PUBLICATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
