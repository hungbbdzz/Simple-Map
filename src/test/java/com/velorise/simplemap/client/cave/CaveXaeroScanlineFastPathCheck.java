package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS58 guard for scanline ordering and archive-first layer scrubbing. */
public final class CaveXaeroScanlineFastPathCheck {
    private CaveXaeroScanlineFastPathCheck() { }

    public static void main(String[] args) throws Exception {
        long[] plan = CaveLoadHierarchy.buildVisiblePagePlan(
                -3, 7, 4, 12, 2, 8, true);
        int width = 11;
        for (int ordinal = 0; ordinal < plan.length; ordinal++) {
            int expectedX = -3 + ordinal % width;
            int expectedZ = 4 + ordinal / width;
            require(CaveLoadHierarchy.x(plan[ordinal]) == expectedX
                            && CaveLoadHierarchy.z(plan[ordinal]) == expectedZ,
                    "fullscreen page order is not a stable scanline");
        }

        Path cave = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String manager = Files.readString(cave.resolve("UnifiedCaveTextureManager.java"));
        String reader = Files.readString(cave.resolve("CaveWorldSaveReader.java"));
        String sourceCache = Files.readString(cave.resolve("DecodedWorldRegionCache.java"));
        String repository = Files.readString(cave.resolve("CaveTileRepository.java"));
        String archiveService = Files.readString(cave.resolve(
                "archive/CaveArchiveV2Service.java"));
        String projection = Files.readString(cave.resolve(
                "projection/CaveProjectionServiceV2.java"));

        require(manager.contains("case FULLSCREEN -> 64")
                        && manager.contains("FULLSCREEN_BUILD_AHEAD_PAGES = 64")
                        && manager.contains("FULLSCREEN_ROW_REVEAL_MS = 16L")
                        && manager.contains("CAVE_PUBLICATION_PAGE_ADVANCE"),
                "fullscreen pipeline lacks a deep but bounded scanline runway");
        require(manager.contains("lane == MapRequestLane.FULLSCREEN")
                        && manager.contains("candidateOrdinal < currentOrdinal"),
                "CPU build scheduling can still publish lower rows first");
        require(reader.contains("requiredForegroundDecodes")
                        && reader.contains("reserveForegroundDecodes")
                        && reader.contains("requestReservedLease")
                        && sourceCache.contains("class PageReservation")
                        && sourceCache.contains("reservedForegroundDecodes"),
                "one 64x64 page can still fragment into repeated partial source passes");
        require(repository.contains("projectionService.layered")
                        && repository.contains("CaveProjectionTile[] archiveV2Tiles"),
                "Layered page build lacks the in-memory archive fast path");
        require(reader.contains("CAVE_SOURCE_ARCHIVE_BYPASS")
                        && archiveService.contains("hasCompletePage"),
                "complete archive pages still start redundant Anvil transactions");
        require(!projection.contains(
                        "public synchronized CaveProjectionTile layered")
                        && projection.contains("Do not hold the global"),
                "Layered archive projection is still globally serialized");
        System.out.println("CAVE_XAERO_SCANLINE_FAST_PATH_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
