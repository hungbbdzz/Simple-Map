package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS109 guard: future fullscreen coordinates must not become live requests. */
public final class CaveBoundedWriterWindowCheck {
    private CaveBoundedWriterWindowCheck() { }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/UnifiedCaveTextureManager.java"));
        String guard = "ordinal > planner.publicationAdmitEndOrdinal";
        String advance = "long packedPage = planner.pagePlan[planner.pageCursor++]";
        int guardIndex = source.indexOf(guard);
        int advanceIndex = source.indexOf(advance, Math.max(0, guardIndex));
        if (guardIndex < 0 || advanceIndex < 0 || guardIndex > advanceIndex) {
            throw new AssertionError(
                    "fullscreen page cursor still materializes requests beyond the publication window");
        }
        if (!source.substring(Math.max(0, guardIndex - 1200), advanceIndex)
                .contains("break;")) {
            throw new AssertionError("future fullscreen ordinal is not parked at the writer frontier");
        }
        System.out.println("CAVE_BOUNDED_WRITER_WINDOW_PASS");
    }
}
