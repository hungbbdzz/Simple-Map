package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static architecture guard for fast new-layer projection from immutable source data. */
public final class CaveVerticalArchiveProjectionCheck {
    private CaveVerticalArchiveProjectionCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String source = Files.readString(root.resolve("DecodedWorldChunkSource.java"));
        String projector = Files.readString(root.resolve("CaveDisplayProjector.java"));
        String archiveProjector = Files.readString(root.resolve("CaveArchiveProjector.java"));
        String projectionV2 = Files.readString(root.resolve(
                "projection/CaveProjectionServiceV2.java"));
        String repository = Files.readString(root.resolve("CaveTileRepository.java"));

        require(source.contains("byte[] caveColumnKinds")
                        && source.contains("sectionKind(int localX, int y, int localZ)")
                        && source.contains("classifyCaveColumns()"),
                "decoded Anvil source lacks reusable column/section skip metadata");
        require(projector.contains("source.sectionKind(localX, y, localZ)"),
                "dense cave projector does not consume column-aware section summaries");
        require(source.contains("ensureVerticalArchive(")
                        && source.contains("buildVerticalArchive")
                        && source.contains("CaveChunkTile.Snapshot archive = ensureVerticalArchive(token)")
                        && source.contains("CaveArchiveProjector.project"),
                "visible cave projection can still run before the reusable vertical archive");
        require(archiveProjector.contains("column.firstVisibleLayeredIndex(maximumY, minimumY)")
                        && archiveProjector.contains("LAYER_DEPTH")
                        && !archiveProjector.contains("continuityTarget("),
                "archive projection is not column-local in the exact 32-block Layered band");
        require(projectionV2.contains("private static final int BAND_HEIGHT = 32;")
                        && projectionV2.contains("int bandTop = topY;"),
                "V2 archive cache quantizes away exact Top-Y or uses the wrong depth");
        require(repository.contains("CaveProjectionServiceV2.getInstance()")
                        && repository.contains("archiveV2Tiles")
                        && repository.contains("v2.complete()"),
                "page resolution does not consume the resident archive directly");
        System.out.println("CAVE_VERTICAL_ARCHIVE_PROJECTION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
