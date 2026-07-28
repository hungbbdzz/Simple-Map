package com.velorise.simplemap.client.cave.archive;

import com.velorise.simplemap.client.cave.CaveChunkTile;
import com.velorise.simplemap.client.cave.CaveColumnData;

import java.util.Arrays;
import java.util.BitSet;

/**
 * M7 style-independent structure-of-arrays cave archive for one 16x16 chunk.
 *
 * <p>During migration legacy scan colors are preserved in materialIds with the
 * LEGACY_COLOR flag. New scanners can commit registry material/biome identities
 * without changing the projection API.</p>
 */
public final class CompactCaveTile {
    public enum ColumnStatus {
        UNKNOWN, PARTIAL, COMPLETE, COMPLETE_TRUNCATED, CORRUPT
    }

    public static final byte FLAG_WATER = 1;
    public static final byte FLAG_FLUID = 1 << 1;
    public static final byte FLAG_EMISSIVE = 1 << 2;
    public static final byte FLAG_LEGACY_COLOR = 1 << 6;
    public static final int COLUMNS = 256;

    private final int chunkX;
    private final int chunkZ;
    private final long revision;
    private final int[] columnOffsets;
    private final short[] runTopY;
    private final short[] runFloorY;
    private final int[] materialIds;
    private final short[] biomeIds;
    private final byte[] blockLight;
    private final byte[] skyLight;
    private final byte[] fluidDepth;
    private final byte[] flags;
    private final byte[] statuses;

    public CompactCaveTile(int chunkX, int chunkZ, long revision,
            int[] columnOffsets, short[] runTopY, short[] runFloorY,
            int[] materialIds, short[] biomeIds, byte[] blockLight,
            byte[] skyLight, byte[] fluidDepth, byte[] flags,
            byte[] statuses) {
        if (columnOffsets == null || columnOffsets.length != COLUMNS + 1) {
            throw new IllegalArgumentException("columnOffsets");
        }
        int runs = columnOffsets[COLUMNS];
        if (runs < 0 || runTopY == null || runTopY.length != runs
                || runFloorY == null || runFloorY.length != runs
                || materialIds == null || materialIds.length != runs
                || biomeIds == null || biomeIds.length != runs
                || blockLight == null || blockLight.length != runs
                || skyLight == null || skyLight.length != runs
                || fluidDepth == null || fluidDepth.length != runs
                || flags == null || flags.length != runs
                || statuses == null || statuses.length != COLUMNS) {
            throw new IllegalArgumentException("compact cave arrays");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.revision = Math.max(1L, revision);
        this.columnOffsets = Arrays.copyOf(columnOffsets, columnOffsets.length);
        this.runTopY = Arrays.copyOf(runTopY, runs);
        this.runFloorY = Arrays.copyOf(runFloorY, runs);
        this.materialIds = Arrays.copyOf(materialIds, runs);
        this.biomeIds = Arrays.copyOf(biomeIds, runs);
        this.blockLight = Arrays.copyOf(blockLight, runs);
        this.skyLight = Arrays.copyOf(skyLight, runs);
        this.fluidDepth = Arrays.copyOf(fluidDepth, runs);
        this.flags = Arrays.copyOf(flags, runs);
        this.statuses = Arrays.copyOf(statuses, COLUMNS);
    }

    public int chunkX() { return chunkX; }
    public int chunkZ() { return chunkZ; }
    public long revision() { return revision; }
    public int runCount() { return runTopY.length; }
    public int runStart(int column) { return columnOffsets[column]; }
    public int runEnd(int column) { return columnOffsets[column + 1]; }
    public short topY(int run) { return runTopY[run]; }
    public short floorY(int run) { return runFloorY[run]; }
    public int materialId(int run) { return materialIds[run]; }
    public short biomeId(int run) { return biomeIds[run]; }
    public byte blockLight(int run) { return blockLight[run]; }
    public byte skyLight(int run) { return skyLight[run]; }
    public byte fluidDepth(int run) { return fluidDepth[run]; }
    public byte flags(int run) { return flags[run]; }
    public ColumnStatus status(int column) {
        int ordinal = statuses[column] & 0xFF;
        return ordinal >= ColumnStatus.values().length
                ? ColumnStatus.CORRUPT : ColumnStatus.values()[ordinal];
    }

    public long estimatedBytes() {
        return (long) columnOffsets.length * Integer.BYTES
                + (long) runTopY.length * (Short.BYTES * 3L + Integer.BYTES + 5L)
                + statuses.length;
    }

    public static CompactCaveTile fromLegacy(CaveChunkTile.Snapshot snapshot) {
        if (snapshot == null) return null;
        int[] offsets = new int[COLUMNS + 1];
        int totalRuns = 0;
        for (int column = 0; column < COLUMNS; column++) {
            offsets[column] = totalRuns;
            CaveColumnData data = snapshot.columns()[column];
            if (snapshot.scanned().get(column) && data != null) totalRuns += data.count();
        }
        offsets[COLUMNS] = totalRuns;
        short[] top = new short[totalRuns];
        short[] floor = new short[totalRuns];
        int[] material = new int[totalRuns];
        short[] biome = new short[totalRuns];
        byte[] block = new byte[totalRuns];
        byte[] sky = new byte[totalRuns];
        byte[] fluidDepth = new byte[totalRuns];
        byte[] flags = new byte[totalRuns];
        byte[] statuses = new byte[COLUMNS];
        int cursor = 0;
        BitSet scanned = snapshot.scanned();
        BitSet fullHeight = snapshot.fullHeight();
        for (int column = 0; column < COLUMNS; column++) {
            CaveColumnData data = snapshot.columns()[column];
            if (!scanned.get(column)) {
                statuses[column] = (byte) ColumnStatus.UNKNOWN.ordinal();
                continue;
            }
            boolean complete = fullHeight.get(column);
            statuses[column] = (byte) (complete
                    ? ColumnStatus.COMPLETE.ordinal()
                    : ColumnStatus.COMPLETE_TRUNCATED.ordinal());
            if (data == null) continue;
            for (int run = 0; run < data.count(); run++) {
                top[cursor] = data.topY(run);
                floor[cursor] = data.bottomY(run);
                material[cursor] = data.color(run);
                byte legacyFlags = FLAG_LEGACY_COLOR;
                byte old = data.flags(run);
                if ((old & CaveColumnData.FLAG_WATER) != 0) legacyFlags |= FLAG_WATER;
                if ((old & CaveColumnData.FLAG_FLUID) != 0) legacyFlags |= FLAG_FLUID;
                if ((old & CaveColumnData.FLAG_EMISSIVE) != 0) legacyFlags |= FLAG_EMISSIVE;
                flags[cursor] = legacyFlags;
                cursor++;
            }
        }
        return new CompactCaveTile(snapshot.chunkX(), snapshot.chunkZ(),
                snapshot.revision(), offsets, top, floor, material, biome,
                block, sky, fluidDepth, flags, statuses);
    }
}
