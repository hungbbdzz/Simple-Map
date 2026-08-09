package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;
import com.velorise.simplemap.client.cave.projection.CaveProjectionServiceV2;
import com.velorise.simplemap.client.cave.projection.CaveProjectionTile;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cave projection guard for Xaero-style cave-start filtering, exact Layered
 * floor semantics and non-blocking branch publication.
 */
public final class CaveXaeroProjectionTransactionCheck {
    private CaveXaeroProjectionTransactionCheck() { }

    public static void main(String[] args) throws Exception {
        verifyTallLayeredCavern();
        verifyArchitectureFences();
        System.out.println("CAVE_XAERO_PROJECTION_TRANSACTION_PASS");
    }

    private static void verifyTallLayeredCavern() {
        int[] offsets = new int[257];
        short[] top = new short[256];
        short[] floor = new short[256];
        int[] colors = new int[256];
        short[] biomes = new short[256];
        byte[] block = new byte[256];
        byte[] sky = new byte[256];
        byte[] fluid = new byte[256];
        byte[] flags = new byte[256];
        byte[] statuses = new byte[256];
        for (int column = 0; column < 256; column++) {
            offsets[column] = column;
            // Top 70 / floor 20 crosses [32,63], but Xaero's Top-Y scan does not
            // invent a floor at the band edge. Because the real floor is below
            // lowY, this column is empty for this Layered transaction.
            top[column] = 70;
            floor[column] = 20;
            colors[column] = 0xFF778899;
            flags[column] = CompactCaveTile.FLAG_LEGACY_COLOR;
            statuses[column] = (byte) CompactCaveTile.ColumnStatus.COMPLETE.ordinal();
        }
        offsets[256] = 256;
        CompactCaveTile tile = new CompactCaveTile(41, -17, 9L, offsets,
                top, floor, colors, biomes, block, sky, fluid, flags, statuses);
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        archive.clear();
        require(archive.ingest(tile), "archive ingest failed");
        CaveProjectionServiceV2 service = CaveProjectionServiceV2.getInstance();
        service.clear();
        CaveProjectionTile projected = service.layered(41, -17, 63, 0L);
        require(projected != null && projected.complete(),
                "Layered archive projection is not authoritative");
        require(projected.pixel(0) == 0,
                "Layered archive invented a floor below the exact Top-Y band");
    }

    private static void verifyArchitectureFences() throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client/cave");
        String projector = Files.readString(root.resolve("CaveDisplayProjector.java"));
        String manager = Files.readString(root.resolve("UnifiedCaveTextureManager.java"));
        String lod = Files.readString(root.resolve("CaveLodTree.java"));
        String repository = Files.readString(root.resolve("CaveTileRepository.java"));

        require(projector.contains("boolean startsAboveTerrain = !full && highY >= surfaceY")
                        && projector.contains("full || startsAboveTerrain"),
                "Layered scanning can still map the sky-to-surface transition");
        require(manager.contains("branch_generation_reset=true")
                        && manager.contains("branchSemanticallyReady"),
                "Layered branches can still mix exact Top-Y transactions");
        require(lod.contains("MapRequestLane uploadLane = node.requestLane")
                        && lod.contains("if (foreground) break;")
                        && lod.contains("CAVE_BRANCH_BACKGROUND_DENIAL_BYPASSED")
                        && !lod.contains("MapRequestLane.FULLSCREEN, false))"),
                "branch publication is still fixed-lane or spinning after foreground denial");
        require(repository.contains("The vertical archive owns cave-selection semantics")
                        && repository.contains("column.firstVisibleFullIndex()")
                        && repository.contains("column.firstVisibleLayeredIndex(maximum, minimum)")
                        && !repository.contains("column.fullIndex(Integer.MIN_VALUE, neighbourY)"),
                "page assembly still changes cave level from neighbour/tile scoring");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
