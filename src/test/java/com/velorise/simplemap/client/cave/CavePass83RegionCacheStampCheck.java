package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards region-level CIMG source-stamp validation before render-thread handoff. */
public final class CavePass83RegionCacheStampCheck {
    private CavePass83RegionCacheStampCheck() { }

    public static void main(String[] args) throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        require(manager.contains("validRegionImagePageMask(image)")
                        && manager.contains("CAVE_REGION_IMAGE_STALE_DISCARDED")
                        && manager.contains("CAVE_REGION_IMAGE_PARTIAL_STALE")
                        && manager.contains("sourceTimestampMs()"),
                "CIMG pages are not prevalidated/coalesced at region load time");
        require(!manager.contains("CAVE_REGION_IMAGE_STALE_PAGE_SKIPPED"),
                "stale CIMG pages are still processed and logged one by one");
        require(manager.contains("new RegionCacheInstall(\n                    image")
                        && manager.contains("requestEpoch, validPageMask"),
                "render handoff does not carry the validated page mask");
        System.out.println("CAVE_PASS83_REGION_CACHE_STAMP_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
