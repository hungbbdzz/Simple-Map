package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;
import com.velorise.simplemap.client.cave.projection.CaveProjectionServiceV2;
import com.velorise.simplemap.client.cave.projection.CaveProjectionTile;

/** Runtime-independent verification that FULL exact pages can come from archive RAM. */
public final class CaveFullArchiveProjectionCheck {
    public static void main(String[] args) {
        int[] offsets = new int[257];
        short[] top = new short[512];
        short[] floor = new short[512];
        int[] colors = new int[512];
        short[] biomes = new short[512];
        byte[] block = new byte[512];
        byte[] sky = new byte[512];
        byte[] fluid = new byte[512];
        byte[] flags = new byte[512];
        byte[] statuses = new byte[256];
        for (int column = 0; column < 256; column++) {
            int first = column * 2;
            offsets[column] = first;
            // Highest run is a one-block ceiling crack and must not own Full Cave.
            top[first] = 80;
            floor[first] = 79;
            colors[first] = 0xFF112233;
            flags[first] = CompactCaveTile.FLAG_LEGACY_COLOR;
            // Lower run is a coherent room/tunnel network.
            top[first + 1] = 45;
            floor[first + 1] = 32;
            colors[first + 1] = 0xFF336699;
            flags[first + 1] = CompactCaveTile.FLAG_LEGACY_COLOR;
            statuses[column] = (byte) CompactCaveTile.ColumnStatus.COMPLETE.ordinal();
        }
        offsets[256] = 512;
        CompactCaveTile compact = new CompactCaveTile(7, -3, 42L, offsets,
                top, floor, colors, biomes, block, sky, fluid, flags, statuses);
        CaveArchiveV2Service archive = CaveArchiveV2Service.getInstance();
        archive.clear();
        require(archive.ingest(compact), "archive ingest failed");
        CaveProjectionServiceV2 projections = CaveProjectionServiceV2.getInstance();
        projections.clear();
        CaveProjectionTile projected = projections.full(7, -3, 0L);
        require(projected != null && projected.complete(),
                "FULL archive projection is not authoritative");
        require(projected.pixel(0) == 0xFF336699,
                "FULL archive projection changed the raw cave colour");
        require(projected.floorY(255) == 32 && projected.topY(255) == 45,
                "FULL archive projection selected the highest crack instead of the coherent room");
        System.out.println("CAVE_FULL_ARCHIVE_PROJECTION_PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
