package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards the PASS83 fix that prevents stale known-empty state from hiding Full archive data. */
public final class CavePass83PresenceReconciliationCheck {
    private CavePass83PresenceReconciliationCheck() { }

    public static void main(String[] args) throws Exception {
        String repository = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/CaveTileRepository.java"));
        require(repository.contains("if (!knownAbsent[order])")
                        && repository.contains("previousAbsent == layerY")
                        && repository.contains("absentDisplayTiles.removeInt(key)"),
                "resolved-present chunks do not clear stale display absence authority");
        require(repository.indexOf("if (!knownAbsent[order])")
                        < repository.indexOf("absentDisplayTiles.put(key, layerY)"),
                "presence reconciliation is not evaluated before absence publication");

        CaveTileRepository live = CaveTileRepository.getInstance();
        live.clearRuntime(false);
        long generation = live.generation();
        int firstChunkX = 40;
        int firstChunkZ = -24;
        int pageX = firstChunkX >> 2;
        int pageZ = firstChunkZ >> 2;
        live.markDisplayTileAbsent(CaveView.FULL, Integer.MIN_VALUE,
                firstChunkX, firstChunkZ, generation);
        long absentRevision = live.getPageRevision(
                CaveView.FULL, Integer.MIN_VALUE, pageX, pageZ);
        boolean reconciled = live.commitDisplayPage(java.util.List.of(),
                CaveView.FULL, Integer.MIN_VALUE, firstChunkX, firstChunkZ,
                new boolean[16], generation);
        long presentRevision = live.getPageRevision(
                CaveView.FULL, Integer.MIN_VALUE, pageX, pageZ);
        require(reconciled && presentRevision != absentRevision,
                "resolved-present page did not remove stale Full known-empty authority");
        System.out.println("CAVE_PASS83_PRESENCE_RECONCILIATION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
