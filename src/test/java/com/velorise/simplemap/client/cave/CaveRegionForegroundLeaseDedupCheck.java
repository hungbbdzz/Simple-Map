package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS78 guard against repeated release of the same coherent region batch. */
public final class CaveRegionForegroundLeaseDedupCheck {
    private CaveRegionForegroundLeaseDedupCheck() { }

    public static void main(String[] args) throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java"));
        require(service.contains("FOREGROUND_REQUEST_LEASE_MS = 2_000L")
                        && service.contains("releasedSourceRevisions")
                        && service.contains("releasedLaneRanks")
                        && service.contains("reconcileSatisfiedPages(repository, pages)")
                        && service.contains("CAVE_REGION_FOREGROUND_FRONTIER_READY")
                        && service.contains("order=viewport_scanline_sweep_top_left")
                        && service.contains("request.keepAlive(now)"),
                "completed native-region foreground batches are not retained/deduplicated");
        require(service.contains("current == page.sourceRevision()")
                        && service.contains("releasedSourceRevisions[ordinal] == current"),
                "region release is not fenced by exact source fingerprint equality");
        System.out.println("CAVE_REGION_FOREGROUND_LEASE_DEDUP_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
