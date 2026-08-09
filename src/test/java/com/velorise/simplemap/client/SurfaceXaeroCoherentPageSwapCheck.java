package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS109 guard for Xaero-style retained/loading Surface publication. */
public final class SurfaceXaeroCoherentPageSwapCheck {
    private SurfaceXaeroCoherentPageSwapCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client");
        String manager = Files.readString(root.resolve("MapTextureManager.java"));
        String sourceDb = Files.readString(root.resolve("SurfaceRegionSourceDatabase.java"));

        require(manager.contains("SURFACE_PAGE_LOADING_BANK")
                        && manager.contains("mergedSubtiles != MapPageLayout.FULL_SUBTILE_MASK"),
                "first partial Surface page can still enter the exact atlas");
        require(manager.contains("int uploadSubtiles = MapPageLayout.FULL_SUBTILE_MASK")
                        && manager.contains("SURFACE_PAGE_ATOMIC_REFRESH"),
                "Surface replacement is not an atomic full-page refresh");
        require(manager.contains("retainedMask != MapPageLayout.FULL_SUBTILE_MASK")
                        && manager.contains("ensureRetainedSurfaceSource"),
                "renderer demand can still admit an incomplete retained page");
        require(!manager.contains("SurfacePageBuildInputs inputs = captureSurfacePageBuildInputs(address)"),
                "renderer can still fall back to a mutable Region page snapshot");
        require(manager.contains(".warmLoadedPage(")
                        && !manager.substring(
                                manager.indexOf("private void ensureRetainedSurfaceSource"),
                                manager.indexOf("private boolean pageSnapshotCoversRetainedSource"))
                                .contains(".warmLoadedRegion("),
                "exact page demand still rescans the whole 512x512 loaded region");
        require(sourceDb.contains("markPageDirtyForChunk(")
                        && sourceDb.contains("wakeRegionCaptureForChunk("),
                "retained warming can commit source without waking exact-page publication");
        require(sourceDb.contains("PAGE_WARM_NO_PROGRESS_RETRY_NANOS")
                        && sourceDb.contains("pageWarmRetryAfterNanos.remove(new PageWarmKey("),
                "unresolved exact-page warming can still rescan the same 4x4 source every frame");

        int probeStart = sourceDb.indexOf("private SurfaceRegionSource.Probe refreshAndProbe");
        int probeEnd = sourceDb.indexOf("private void captureSourceChunk", probeStart);
        require(probeStart >= 0 && probeEnd > probeStart,
                "retained source probe method not found");
        String probe = sourceDb.substring(probeStart, probeEnd);
        require(probe.contains("retained.acquireProbe()")
                        && !probe.contains("MapManager.getInstance().getRegion")
                        && !probe.contains("captureSourceChunk("),
                "a renderer lane can still recapture mutable MapManager.Region state");

        System.out.println("SURFACE_XAERO_COHERENT_PAGE_SWAP_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
