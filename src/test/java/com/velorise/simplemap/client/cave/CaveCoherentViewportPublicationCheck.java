package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static guard for region-coherent source and deterministic scanline sweep. */
public final class CaveCoherentViewportPublicationCheck {
    private CaveCoherentViewportPublicationCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String projection = Files.readString(
                root.resolve("CaveRegionProjectionService.java"));
        String manager = Files.readString(
                root.resolve("UnifiedCaveTextureManager.java"));
        String archive = Files.readString(
                root.resolve("archive/CaveArchiveV2Service.java"));
        String repository = Files.readString(root.resolve("CaveTileRepository.java"));

        require(importer.contains("foregroundSubmittedMask")
                        && importer.contains("foregroundProjectionMask")
                        && importer.contains("demand.foregroundMask")
                        && importer.contains("order=per_page_versioned_children")
                        && importer.contains("setForegroundDemand")
                        && !importer.contains("visiblePageMask |= entry.getValue()"),
                "native region source does not preserve current-viewport child ownership");
        require(projection.contains("releaseForegroundBatchLocked")
                        && projection.contains("releasedForegroundMask")
                        && projection.contains("CAVE_REGION_FOREGROUND_FRONTIER_READY")
                        && projection.contains("REGION_PAGE_SLICE = 24")
                        && projection.contains("long workMask")
                        && projection.contains("long foregroundMask")
                        && projection.contains("owned |= foregroundMask"),
                "final region pixels lack bounded scanline release or single-writer ownership");
        require(!manager.contains("FULLSCREEN_WAVEFRONT_GRACE_MS")
                                                && manager.contains(
                                "advanced < FULLSCREEN_PUBLICATION_ADVANCE_BURST")
                        && manager.contains("fullscreenPublicationPageResolved")
                        && manager.contains("order=viewport_scanline_sweep_top_left"),
                "fullscreen publication lacks deterministic viewport scanline ordering");
        require(archive.contains("contentFingerprint")
                        && archive.contains("indexedFingerprints")
                        && repository.contains("hasProjectionAuthorityPage"),
                "archive-backed pages can still be invalidated by presentation-cache churn");
        System.out.println("CAVE_COHERENT_VIEWPORT_PUBLICATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
