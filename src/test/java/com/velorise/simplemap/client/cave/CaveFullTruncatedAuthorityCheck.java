package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;
import com.velorise.simplemap.client.cave.projection.CaveProjectionServiceV2;
import com.velorise.simplemap.client.cave.projection.CaveProjectionTile;

/** PASS84: Full accepts final truncated run sets without weakening Layered Top-Y. */
public final class CaveFullTruncatedAuthorityCheck {
    private CaveFullTruncatedAuthorityCheck() { }

    public static void main(String[] args) {
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        CaveProjectionServiceV2 projections = CaveProjectionServiceV2.getInstance();
        archive.clear();
        projections.clear();

        for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
            for (int chunkX = 0; chunkX < 4; chunkX++) {
                CompactCaveTile tile = tile(chunkX, chunkZ);
                require(!tile.completeCoverage(),
                        "truncated tile incorrectly became arbitrary Top-Y complete");
                require(tile.fullProjectionCoverage(),
                        "truncated tile is not Full-projection ready");
                require(archive.ingest(tile), "archive ingest failed");
            }
        }

        require(!archive.hasCompletePage(0, 0),
                "Layered exact authority was weakened");
        require(archive.hasFullProjectionPage(0, 0),
                "Full page did not accept 16 final truncated chunks");
        require(archive.hasIndexedFullProjectionPage(0, 0),
                "Full readiness did not survive indexed authority");

        CaveProjectionTile full = projections.full(0, 0, 0L);
        CaveProjectionTile layered = projections.layered(0, 0, 40, 0L);
        require(full != null && full.complete() && full.knownColumns() == 256,
                "Full projection rejected final truncated columns");
        require(layered != null && !layered.complete(),
                "Layered projection accepted truncated exact Top-Y authority");
        require(full.pixel(0) == 0xFF557799,
                "Full projection lost retained cave material");
        System.out.println("CAVE_FULL_TRUNCATED_AUTHORITY_PASS");
    }

    private static CompactCaveTile tile(int chunkX, int chunkZ) {
        int[] offsets = new int[257];
        short[] top = new short[256];
        short[] floor = new short[256];
        int[] material = new int[256];
        short[] biome = new short[256];
        byte[] block = new byte[256];
        byte[] sky = new byte[256];
        byte[] fluid = new byte[256];
        byte[] flags = new byte[256];
        byte[] statuses = new byte[256];
        for (int column = 0; column < 256; column++) {
            offsets[column] = column;
            top[column] = 44;
            floor[column] = 31;
            material[column] = 0xFF557799;
            flags[column] = CompactCaveTile.FLAG_LEGACY_COLOR;
            statuses[column] = (byte)
                    CompactCaveTile.ColumnStatus.COMPLETE_TRUNCATED.ordinal();
        }
        offsets[256] = 256;
        return new CompactCaveTile(chunkX, chunkZ,
                100L + chunkZ * 4L + chunkX, offsets, top, floor, material,
                biome, block, sky, fluid, flags, statuses);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
