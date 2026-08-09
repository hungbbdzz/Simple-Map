package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS63 guard for atomic source authority and shared Accurate colour finishing. */
public final class CaveAtomicSourceAccurateColorCheck {
    private CaveAtomicSourceAccurateColorCheck() { }

    public static void main(String[] args) throws Exception {
        String reader = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveWorldSaveReader.java"));
        String caveStyler = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CavePageStyler.java"));
        String surface = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/SurfaceColorizer.java"));
        String filter = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MapAccurateColorFilter.java"));
        String policy = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveScreenSpacePolicy.java"));

        require(reader.contains("pendingPageAssemblies")
                        && reader.contains("PageAssembly")
                        && reader.contains("resolved=16 halo_resolved=")
                        && reader.contains("atomic=true changed=")
                        && reader.contains("archiveOrAbsentCoverage == 16")
                        && reader.contains("CAVE_SOURCE_WINDOW_COUNT")
                        && !reader.contains("Commit every resolved source leaf"),
                "world-save source authority is no longer page-atomic with a styling halo");
        require(caveStyler.contains("MapAccurateColorFilter.applyAbgr")
                        && surface.contains("MapAccurateColorFilter.applyArgb")
                        && filter.contains("public static int applyArgb")
                        && filter.contains("public static int applyAbgr"),
                "surface and cave Accurate modes no longer share the final filter");
        require(policy.contains("pressured ? 4 : 16")
                        && policy.contains("return 72L")
                        && policy.contains("return false;"),
                "far-zoom exact refinement can flood the atlas again");
        System.out.println("CAVE_ATOMIC_SOURCE_ACCURATE_COLOR_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
