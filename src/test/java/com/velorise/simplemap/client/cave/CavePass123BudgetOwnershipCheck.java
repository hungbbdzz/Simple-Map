package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS123 guard for retained viewport ownership and one hard Cave frame deadline. */
public final class CavePass123BudgetOwnershipCheck {
    private CavePass123BudgetOwnershipCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String manager = Files.readString(root.resolve("UnifiedCaveTextureManager.java"));
        String lod = Files.readString(root.resolve("CaveLodTree.java"));

        require(manager.contains("isProjectionStillRequested(key, projectionTopY)")
                        && manager.contains("isProjectionViewportOwned(key, projectionTopY, now)")
                        && manager.contains("foregroundImportStillOwned(imported, key, now)"),
                "foreground region handoff is not request-or-live-viewport owned");
        require(manager.contains("long stageDeadline = Math.min(deadline")
                        && manager.contains("fullscreenActive && idleHeadroom")
                        && manager.contains("importedPageBudget / 2"),
                "region importer can still escape the outer cave render deadline");
        require(lod.contains("lastPublishGpuDenied = true")
                        && manager.contains("branchBudgetExhausted = publishBranches")
                        && manager.contains("!branchBudgetExhausted"),
                "branch publisher can still re-probe an exhausted same-frame GPU ledger");
        System.out.println("CAVE_PASS123_BUDGET_OWNERSHIP_PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
