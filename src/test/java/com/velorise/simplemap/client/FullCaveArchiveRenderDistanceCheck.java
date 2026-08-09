package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** Static regression guard for PASS67's Full-Cave and loaded-halo invariants. */
public final class FullCaveArchiveRenderDistanceCheck {
    public static void main(String[] args) throws Exception {
        Path client = Path.of("src/main/java/com/velorise/simplemap/client");
        String projection = Files.readString(client.resolve(
                "cave/projection/CaveProjectionServiceV2.java"));
        String repository = Files.readString(client.resolve(
                "cave/CaveTileRepository.java"));
        String worldReader = Files.readString(client.resolve(
                "cave/CaveWorldSaveReader.java"));
        String scheduler = Files.readString(client.resolve(
                "cave/CaveDisplayScheduler.java"));
        String pipeline = Files.readString(client.resolve(
                "cave/CavePipeline.java")).replace("\r\n", "\n");

        require(projection.contains("public CaveProjectionTile full("),
                "Full Cave has no exact vertical-archive projection");
        require(repository.contains("? projectionService.full("),
                "Full Cave page resolution does not consume the archive projection");
        require(repository.contains("view == CaveView.FULL")
                        && repository.contains("residentProjectionMask(")
                        && repository.contains("fullProjectionCoverage"),
                "exact readiness still ignores view-specific archive coverage");
        require(!worldReader.contains("view == CaveView.LAYERED\n                        && archive.hasCompleteChunk"),
                "world reader still restricts archive coverage to Layered Cave");
        require(worldReader.contains("if (archiveOrAbsentCoverage == 16)"),
                "Full Cave cannot bypass Anvil using archive plus known absence");
        require(scheduler.contains("MAX_LOADED_CHUNK_FRONTIER = 8192"),
                "loaded cave frontier cannot hold a full render-distance square");
        require(scheduler.contains("LOADED_CHUNKS_INSPECTED_PER_PULSE = 1024"),
                "loaded cave frontier is inspected too slowly");

        int unavailable = pipeline.indexOf("public void onChunkUnavailable");
        int nextMethod = pipeline.indexOf("public void requestColumnRecheck", unavailable);
        require(unavailable >= 0 && nextMethod > unavailable,
                "missing chunk-unavailable handling");
        String unloadBody = pipeline.substring(unavailable, nextMethod);
        require(!unloadBody.contains("markDisplayRangeStaleAllLayers"),
                "chunk unload still invalidates saved cave pixels");

        int mutation = pipeline.indexOf("public void onChunkMutation");
        int geometry = pipeline.indexOf("private static boolean changesCaveGeometry", mutation);
        String mutationBody = pipeline.substring(mutation, geometry);
        require(mutationBody.contains("if (geometryChanged) {\n            repository.markDisplayRangeStaleAllLayers"),
                "light-only updates still stale every cave layer");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
