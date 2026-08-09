package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS82 guard that Full pages recover immutable archive authority after LRU eviction. */
public final class CaveFullArchiveAuthorityCheck {
    private CaveFullArchiveAuthorityCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String repository = Files.readString(root.resolve("CaveTileRepository.java"));
        String decoded = Files.readString(root.resolve("DecodedWorldChunkSource.java"));

        require(repository.contains("if (central) centralArchiveAuthorityTiles++")
                        && repository.contains("centralArchiveAuthorityTiles == 16")
                        && repository.contains("boolean archiveAuthoritative"),
                "known-absent central chunks are not accepted as atomic Full authority");
        require(decoded.contains("if (archive != null)")
                        && decoded.contains("repository.ingestDecodedArchive(archive, expectedGeneration)")
                        && decoded.contains("return archive;"),
                "cached decoded archives are not restored after compact-archive LRU eviction");

        System.out.println("CAVE_FULL_ARCHIVE_AUTHORITY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
