package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Source-level regression that remains dependency-light like the other architecture checks. */
public final class CaveAbsentDisplayPrimitiveIndexCheck {
    private CaveAbsentDisplayPrimitiveIndexCheck() {}

    public static void main(String[] args) throws Exception {
        Path source = Path.of("src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java");
        String code = Files.readString(source);
        require(code.contains("Object2IntOpenHashMap<DenseCaveTileKey> absentDisplayTiles"),
                "known-empty projection index must remain primitive-int backed");
        require(!code.contains("Map<DenseCaveTileKey, Integer> absentDisplayTiles"),
                "boxed known-empty projection map must not return");
        require(code.contains("absentDisplayTiles.getInt(key)"),
                "render hot path must use primitive getInt lookups");
        require(code.contains("Long2ObjectOpenHashMap<Long2LongOpenHashMap>")
                        && code.contains("namespaceRevisions.addTo(pack("),
                "projection-scoped page revisions must remain primitive-backed");
        require(code.contains("Long2IntOpenHashMap displayRegionChunkCounts"),
                "Cave region-presence indexes must remain primitive");
        System.out.println("CAVE_ABSENT_DISPLAY_PRIMITIVE_INDEX_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
