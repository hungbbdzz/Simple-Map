package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;

/** PASS77 guard for content-derived archive identity across import paths. */
public final class CaveArchiveContentIdentityCheck {
    private CaveArchiveContentIdentityCheck() { }

    public static void main(String[] args) {
        CompactCaveTile first = tile(4, -7, 11L, 0xFF556677);
        CompactCaveTile replay = tile(4, -7, 9_999L, 0xFF556677);
        CompactCaveTile changed = tile(4, -7, 10_000L, 0xFF776655);
        require(first.contentFingerprint() == replay.contentFingerprint(),
                "scanner/session revision leaked into archive content identity");
        require(first.contentFingerprint() != changed.contentFingerprint(),
                "material change did not advance archive content identity");

        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        archive.clear();
        CaveArchiveV2Service.Summary before = archive.summary();
        require(archive.ingest(first), "first archive ingest was rejected");
        CaveArchiveV2Service.Summary afterFirst = archive.summary();
        require(!archive.ingest(replay),
                "byte-identical persistence/native replay replaced the source");
        CaveArchiveV2Service.Summary afterReplay = archive.summary();
        require(afterReplay.replaced() == afterFirst.replaced(),
                "byte-identical replay increased archive replacement count");
        require(afterReplay.staleIgnored() > afterFirst.staleIgnored(),
                "byte-identical replay was not recorded as idempotent");
        require(archive.ingest(changed), "changed archive content was ignored");
        require(archive.summary().replaced() > afterReplay.replaced(),
                "changed archive content did not replace indexed source");
        require(afterFirst.ingested() == before.ingested() + 1,
                "first ingest telemetry changed unexpectedly");
        System.out.println("CAVE_ARCHIVE_CONTENT_IDENTITY_PASS");
    }

    private static CompactCaveTile tile(int chunkX, int chunkZ,
            long revision, int color) {
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
            top[column] = 44;
            floor[column] = 31;
            colors[column] = color;
            flags[column] = CompactCaveTile.FLAG_LEGACY_COLOR;
            statuses[column] = (byte)
                    CompactCaveTile.ColumnStatus.COMPLETE.ordinal();
        }
        offsets[256] = 256;
        return new CompactCaveTile(chunkX, chunkZ, revision, offsets,
                top, floor, colors, biomes, block, sky, fluid, flags, statuses);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
