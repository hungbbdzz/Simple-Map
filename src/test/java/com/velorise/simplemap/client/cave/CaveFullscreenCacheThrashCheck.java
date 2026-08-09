package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS60 guard for fullscreen CVD replay, real source authority and fast wavefront reveal. */
public final class CaveFullscreenCacheThrashCheck {
    private CaveFullscreenCacheThrashCheck() { }

    public static void main(String[] args) throws Exception {
        Path cave = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String repository = Files.readString(cave.resolve("CaveTileRepository.java"));
        String manager = Files.readString(cave.resolve("UnifiedCaveTextureManager.java"));

        require(repository.contains("requestDisplayBatchLoadLocked(requested,\n"
                        + "                    lane == null ? MapRequestLane.FULLSCREEN : lane);")
                        && !repository.contains("boolean expandRegions")
                        && !repository.contains("requestedRegions.contains(region)"),
                "fullscreen CVD replay can still expand one page into a whole region");

        int resolvedStart = repository.indexOf("hasFreshDisplayTileOrKnownEmpty");
        int resolvedEnd = repository.indexOf("hasFreshDisplayPageSource", resolvedStart);
        require(resolvedStart >= 0 && resolvedEnd > resolvedStart,
                "resolved-leaf authority method is missing");
        String resolvedMethod = repository.substring(resolvedStart, resolvedEnd);
        require(!resolvedMethod.contains("indexedDisplayTiles.contains(key)")
                        && !resolvedMethod.contains("pendingDisplayLoads.containsKey(key)"),
                "disk index/pending IO is still treated as a resident source leaf");

        require(manager.contains("FULLSCREEN_PUBLICATION_ADVANCE_BURST = 128")
                                                && manager.contains("advanced < FULLSCREEN_PUBLICATION_ADVANCE_BURST")
                        && manager.contains("fullscreenPublicationPageResolved")
                                                && manager.contains("order=viewport_scanline_sweep_top_left")
                        && !manager.contains("gapGraceExpired"),
                "fullscreen publication no longer uses strict scanline reveal");

        System.out.println("CAVE_FULLSCREEN_CACHE_THRASH_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
