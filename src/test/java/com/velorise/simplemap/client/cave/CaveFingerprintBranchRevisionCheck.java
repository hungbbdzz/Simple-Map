package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS78 guard: source fingerprints are identities, never monotonic counters. */
public final class CaveFingerprintBranchRevisionCheck {
    private CaveFingerprintBranchRevisionCheck() { }

    public static void main(String[] args) throws Exception {
        String lod = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveLodTree.java"));
        require(!lod.contains("update.sourceRevision() >= previous.sourceRevision()")
                        && !lod.contains("Math.max(node.childRevisions[childIndex], update.sourceRevision())")
                        && lod.contains("node.childRevisions[childIndex] = update.sourceRevision();")
                        && lod.contains("== Math.max(1L, sourceRevision)"),
                "branch tree still orders content fingerprints numerically");
        require(lod.contains("previous.sourceRevision() == effectiveRevision")
                        && lod.contains("previous.knownColumns() >= knownColumns")
                        && lod.contains("Priority-only promotion")
                        && lod.contains("recordBranchUpdateDropped"),
                "pending branch-page replacement lacks identity-first coalescing");
        System.out.println("CAVE_FINGERPRINT_BRANCH_REVISION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
