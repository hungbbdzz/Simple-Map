package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.projection.CaveBandCache;
import com.velorise.simplemap.client.cave.projection.CaveProjectionTile;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS90 guard for bounded derived projection/page working sets. */
public final class CavePass90ProjectionWorkingSetCheck {
    private CavePass90ProjectionWorkingSetCheck() { }

    public static void main(String[] args) throws Exception {
        CaveBandCache cache = new CaveBandCache(32);
        CaveProjectionTile full = tile(Integer.MIN_VALUE);
        CaveProjectionTile current = tile(49);
        CaveProjectionTile previous = tile(25);
        CaveProjectionTile stale = tile(-8);
        cache.put(new CaveBandCache.Key("minecraft:overworld", 0, 0,
                Integer.MIN_VALUE, 1L, 1L), full);
        cache.put(new CaveBandCache.Key("minecraft:overworld", 0, 0,
                49, 1L, 1L), current);
        cache.put(new CaveBandCache.Key("minecraft:overworld", 0, 0,
                25, 1L, 1L), previous);
        cache.put(new CaveBandCache.Key("minecraft:overworld", 0, 0,
                -8, 1L, 1L), stale);
        require(cache.retainExactProjectionForBand("minecraft:overworld", 48, 49) == 0,
                "unrelated retained bands should survive");
        cache.put(new CaveBandCache.Key("minecraft:overworld", 0, 1,
                50, 1L, 1L), stale);
        require(cache.retainExactProjectionForBand("minecraft:overworld", 48, 49) == 0,
                "same-band exact projections should be lazily retained");
        require(cache.size() == 5, "dimension/band LRU retention changed unexpectedly");
        cache.put(new CaveBandCache.Key("minecraft:the_nether", 0, 1,
                50, 1L, 1L), stale);
        require(cache.retainExactProjectionForBand("minecraft:overworld", 48, 49) == 0,
                "retarget crossed the dimension ownership boundary");
        require(cache.size() == 6, "dimension-qualified projection identity collapsed");

        String manager = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        require(manager.contains("CAVE_LAYER_WORKING_SET_RETAINED")
                        && manager.contains("policy=bounded_all_bands")
                        && manager.contains("canRenderLastGoodWithinBand")
                        && manager.contains("branch_policy=last_good_retained")
                        && manager.contains("FULLSCREEN_BUILD_AHEAD_PAGES = 640")
                        && manager.contains("case FULLSCREEN -> 640"),
                "exact page or projection working-set retention is missing");

        String lodTree = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveLodTree.java"));
        require(lodTree.contains("int parkLayer(")
                        && lodTree.contains("int parkInactiveViews("),
                "bounded branch CPU retention is missing");

        String regionProjection = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveRegionProjectionService.java"));
        require(regionProjection.contains("retireForegroundRegion(")
                        && regionProjection.contains("focusDistanceSquared")
                        && regionProjection.contains("CAVE_REGION_FOREGROUND_READY_PRUNED")
                        && regionProjection.contains("CAVE_REGION_FOREGROUND_QUEUE_STALE_PRUNED")
                        && regionProjection.contains("boolean regionOffer"),
                "Xaero-style foreground-window retirement/focus ordering is missing");
        require(manager.contains("regionAuthorityOwns && projectionHasSource")
                        && manager.contains("if (imported.complete()")
                        && manager.contains("detachPendingLocked(info, true)"),
                "PASS127 native single-writer/coherent branch fencing is missing");

        String surfaceColorizer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/SurfaceColorizer.java"));
        require(surfaceColorizer.contains("XAERO_VOID_ABGR = 0xFF17000A")
                        && surfaceColorizer.contains("known[index] != 0"),
                "mapped-void Surface semantics are missing");

        String nativeImport = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveNativeRegionImportService.java"));
        require(nativeImport.contains("projections.retireForegroundRegion")
                        && nativeImport.contains("sourcePageReady && anyPresent"),
                "native-region viewport/background coherence fencing is missing");
        require(regionProjection.contains(
                            "request.foregroundLane = effectiveForegroundLane")
                        && manager.contains(
                            "planner.fullscreen != (effectiveLane == MapRequestLane.FULLSCREEN)")
                        && manager.contains(
                            "Keep coherent branch source stamps for parked views"),
                "PASS128 minimap/fullscreen writer fence or retained branch stamp is missing");
        System.out.println("CAVE_PASS90_PROJECTION_WORKING_SET_PASS");
    }

    private static CaveProjectionTile tile(int topY) {
        return new CaveProjectionTile(0, 0, topY, 1L,
                new int[256], new short[256], new short[256],
                new byte[256], new byte[256], new byte[256]);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
