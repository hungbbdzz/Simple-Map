package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression guard for PASS68's archive authority and live-halo publication fixes. */
public final class ArchiveStampPublicationHaloCheck {
    public static void main(String[] args) throws Exception {
        Path client = Path.of("src/main/java/com/velorise/simplemap/client");
        String repository = normalize(Files.readString(client.resolve(
                "cave/CaveTileRepository.java")));
        String reader = normalize(Files.readString(client.resolve(
                "cave/CaveWorldSaveReader.java")));
        String texture = normalize(Files.readString(client.resolve(
                "cave/UnifiedCaveTextureManager.java")));
        String viewport = normalize(Files.readString(client.resolve(
                "MapViewportCoordinator.java")));

        require(!repository.contains(
                        "if (staleDisplayTiles.contains(key)) return 0L;"),
                "stale dense leaves still erase a complete archive page stamp");
        require(repository.contains("boolean denseStale = staleDisplayTiles.contains(key)")
                        && repository.contains("archived.completeCoverage()"),
                "display-page fingerprint does not fall back to the vertical archive");
        require(repository.contains("staleDisplayTiles.contains(displayKey)\n                            ? null : displayTiles.get(displayKey)"),
                "page resolution can still select a stale dense leaf over archive data");

        require(reader.contains("CAVE_SOURCE_RETAINED_PAGE_SKIPPED")
                        && reader.contains("boolean retainedOnly"),
                "retained-only archive pages are still committed repeatedly");
        require(reader.contains("archiveResolvedPages.put(archiveKey, archiveResolutionStamp)"),
                "archive bypass is still keyed to a volatile repository generation");

        require(texture.contains("FULLSCREEN_PUBLICATION_ADVANCE_BURST = 128"),
                "fullscreen reveal cursor still advances too slowly");
        require(texture.contains("A refresh build must not close a wavefront coordinate"),
                "a pending refresh can still block an already-visible exact page");
        require(texture.contains("preservePublicationFrontier")
                        && texture.contains("resetPublicationWavefront();")
                        && texture.contains("publicationAdmitEndOrdinal"),
                "overlapping viewport no longer resets into a coherent row frontier");

        require(viewport.contains(
                        "LOADED_SURFACE_HALO_FULLSCREEN_BUDGET_NANOS = 5_000_000L"),
                "fullscreen loaded-surface halo remains under-budgeted");
        require(viewport.contains("MapRequestLane.MINIMAP,\n                            false));")
                        && viewport.contains(
                                "publication.requestSurface(MapRequestLane.MINIMAP, false);"),
                "loaded render-distance halo is still downgraded to the 3x3 BACKGROUND lane");
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
