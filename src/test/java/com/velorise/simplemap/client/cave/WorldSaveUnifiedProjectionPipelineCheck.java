package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * PASS86 source-architecture guard: one Anvil/decode owner, projection-specific
 * fan-out, and no production call path that schedules the old Surface/Cave readers.
 */
public final class WorldSaveUnifiedProjectionPipelineCheck {
    private WorldSaveUnifiedProjectionPipelineCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String pipeline = Files.readString(
                root.resolve("WorldSaveProjectionPipeline.java"));
        String reader = Files.readString(root.resolve("CaveWorldSaveReader.java"));
        String importer = Files.readString(
                root.resolve("CaveNativeRegionImportService.java"));
        String source = Files.readString(root.resolve("DecodedWorldChunkSource.java"));
        String surface = Files.readString(
                root.resolve("SurfaceWorldSaveReconstructor.java"));
        String cache = Files.readString(root.resolve("DecodedWorldRegionCache.java"));

        require(pipeline.contains("Single world-save source pipeline shared by Surface")
                        && pipeline.contains("regionImporter.requestSurfaceViewport")
                        && pipeline.contains("regionImporter.requestViewport")
                        && pipeline.contains("DecodedWorldRegionCache.getInstance()"),
                "Surface, Layered and Full are not routed through one source owner");
        require(reader.contains("WorldProjection.SURFACE")
                        && reader.contains("WorldProjection.FULL")
                        && reader.contains("WorldProjection.LAYERED")
                        && reader.contains("simplemap.useLegacyWorldSavePipelines"),
                "production reader entry points do not delegate to the unified pipe");
        require(importer.contains("ProjectionBundle bundle")
                        && importer.contains("prepareProjectionBundle(surfaceNeeded, caveNeeded")
                        && importer.contains("surfaceRequiredSources")
                        && importer.contains("caveRequiredSources")
                        && importer.contains("reconcileResolution()")
                        && importer.contains("acceptProjection(region.level")
                        && importer.contains("WORLD_SOURCE_FANOUT"),
                "native-region source cells are not selectively fanned out by projection demand");
        require(source.contains("prepareProjectionBundle(boolean surfaceRequested")
                        && source.contains("surfaceRequested")
                        && source.contains("caveRequested")
                        && source.contains("ensureVerticalArchive(token)")
                        && source.contains("record ProjectionBundle"),
                "decoded chunk is not the immutable common input for all projections");
        require(surface.contains("acceptProjection(ServerLevel level")
                        && !surface.substring(surface.indexOf(
                                "boolean acceptProjection(ServerLevel level"))
                                .contains("requestLease("),
                "unified Surface fan-out opens another source lease");
        require(cache.contains("DecodedWorldChunkSource")
                        && cache.contains("SourceLease"),
                "decoded Anvil source cache is missing from the common pipeline");
        System.out.println("WORLD_SAVE_UNIFIED_PROJECTION_PIPELINE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
