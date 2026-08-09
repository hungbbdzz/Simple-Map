package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS70 guard for Xaero-style nearest-first ordering and archive fast paths. */
public final class CaveXaeroWavefrontFastPathCheck {
    private CaveXaeroWavefrontFastPathCheck() { }

    public static void main(String[] args) throws Exception {
        long[] plan = CaveLoadHierarchy.buildVisiblePagePlan(
                -3, 7, 4, 12, 2, 8, true);
        for (int ordinal = 0; ordinal < plan.length; ordinal++) {
            int pageX = CaveLoadHierarchy.x(plan[ordinal]);
            int pageZ = CaveLoadHierarchy.z(plan[ordinal]);
            require(CaveLoadHierarchy.scanlineOrdinal(
                            -3, 7, 4, 12, pageX, pageZ) == ordinal,
                    "fullscreen page order is not stable top-left scanline order");
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

        require(manager.contains("case FULLSCREEN -> 640")
                        && manager.contains("FULLSCREEN_BUILD_AHEAD_PAGES = 640")
                        && !manager.contains("FULLSCREEN_WAVEFRONT_GRACE_MS")
                        && manager.contains("CAVE_PUBLICATION_WAVEFRONT_ADVANCE"),
                "fullscreen pipeline lacks a bounded viewport-scanline runway");
        require(manager.contains("buildOwnership(request, now)")
                        && manager.contains("CAVE_BUILD_LANE_PROMOTED"),
                "visible refreshes can still enter CPU scheduling as BACKGROUND");
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
        System.out.println("CAVE_XAERO_WAVEFRONT_FAST_PATH_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
