package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards per-page versioned native-region publication and its single-writer lease. */
public final class CavePass83IncrementalProjectionCheck {
    private CavePass83IncrementalProjectionCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String importer = Files.readString(root.resolve("CaveNativeRegionImportService.java"));
        String projection = Files.readString(root.resolve("CaveRegionProjectionService.java"));

        require(importer.contains("foregroundProjectionMask")
                        && importer.contains("foregroundSubmittedSourceRevisions[ordinal]")
                        && importer.contains("CAVE_NATIVE_REGION_INCREMENTAL_SUBMIT")
                        && importer.contains("foregroundProjectionMask, foregroundReadyMask"),
                "native-region foreground pages are not submitted independently by source revision");
        require(importer.contains("lastProjectionLeaseRefreshMs")
                        && importer.contains("now - demand.lastProjectionLeaseRefreshMs >= 500L"),
                "foreground single-writer ownership is not refreshed while pages await publication");
        require(!importer.contains("CAVE_NATIVE_REGION_SOURCE_REVISION_RESUBMITTED"),
                "whole-region source-revision resubmission path is still active");

        require(projection.contains("long workMask,\n            long foregroundMask")
                        && projection.contains("request.pageMask = (request.pageMask & foregroundMask) | workMask")
                        && projection.contains("owned |= foregroundMask"),
                "region projection does not separate changed child work from viewport ownership");
        System.out.println("CAVE_PASS83_INCREMENTAL_PROJECTION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
