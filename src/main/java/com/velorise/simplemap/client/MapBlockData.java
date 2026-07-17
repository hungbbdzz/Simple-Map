package com.velorise.simplemap.client;

/**
 * Immutable raw data for a single map pixel (one block column).
 *
 * Runtime regions store this value in a packed {@code long[]} instead of one
 * Java object per pixel. Version 2 added a second height used by fluids: topY is
 * the visible fluid surface, while floorY is the solid terrain below it. Version
 * 3 stores the exact per-pixel BlockColors tint alongside this packed value in
 * the region file. For non-fluid pixels floorY equals topY.
 *
 * Packed layout (low to high bits):
 *   0..15   topY
 *   16..31  block palette index (for water this is the floor block)
 *   32..39  biome palette index
 *   40..47  flags
 *   48..63  floorY
 */
public final class MapBlockData {

    public static final short EMPTY_Y = Short.MIN_VALUE;
    public static final byte NO_BIOME = -1;
    public static final short NO_BLOCK = -1;

    public static final int FILE_MAGIC   = 0x534D4150; // "SMAP"
    public static final int FILE_VERSION = 3;

    public static final long EMPTY_PACKED = packRaw(
            EMPTY_Y, NO_BLOCK, NO_BIOME, (byte) 0, EMPTY_Y);

    public final short topY;
    public final short blockId;
    public final byte biomeId;
    public final byte flags;
    public final short floorY;

    /** Compatibility constructor: non-fluid terrain has one height. */
    public MapBlockData(short topY, short blockId, byte biomeId, byte flags) {
        this(topY, blockId, biomeId, flags, topY);
    }

    public MapBlockData(short topY, short blockId, byte biomeId, byte flags, short floorY) {
        this.topY = topY;
        this.blockId = blockId;
        this.biomeId = biomeId;
        this.flags = flags;
        this.floorY = floorY;
    }

    public boolean isEmpty() {
        return topY == EMPTY_Y;
    }

    public int getWaterDepth() {
        return isFluid() && !isGlowing() ? Math.max(0, topY - floorY) : 0;
    }

    public int getReliefY() {
        return isFluid() && !isGlowing() ? floorY : topY;
    }

    public long pack() {
        return packRaw(topY, blockId, biomeId, flags, floorY);
    }

    public static long pack(MapBlockData data) {
        return data == null ? EMPTY_PACKED : data.pack();
    }

    public static MapBlockData unpack(long packed) {
        return new MapBlockData(topY(packed), blockId(packed), biomeId(packed),
                flags(packed), floorY(packed));
    }

    /** Compatibility overload used by older call sites. */
    public static long packRaw(short topY, short blockId, byte biomeId, byte flags) {
        return packRaw(topY, blockId, biomeId, flags, topY);
    }

    public static long packRaw(short topY, short blockId, byte biomeId, byte flags, short floorY) {
        return ((long) topY & 0xFFFFL)
                | (((long) blockId & 0xFFFFL) << 16)
                | (((long) biomeId & 0xFFL) << 32)
                | (((long) flags & 0xFFL) << 40)
                | (((long) floorY & 0xFFFFL) << 48);
    }

    public static short topY(long packed) {
        return (short) packed;
    }

    public static short blockId(long packed) {
        return (short) (packed >>> 16);
    }

    public static byte biomeId(long packed) {
        return (byte) (packed >>> 32);
    }

    public static byte flags(long packed) {
        return (byte) (packed >>> 40);
    }

    public static short floorY(long packed) {
        return (short) (packed >>> 48);
    }

    public static int reliefY(long packed) {
        return isFluid(packed) && !isGlowing(packed) ? floorY(packed) : topY(packed);
    }

    public static int waterDepth(long packed) {
        return isFluid(packed) && !isGlowing(packed)
                ? Math.max(0, topY(packed) - floorY(packed)) : 0;
    }

    public static boolean isEmpty(long packed) {
        return topY(packed) == EMPTY_Y;
    }

    public static int getBlockLight(long packed) {
        return flags(packed) & 0x0F;
    }

    public static boolean isGlowing(long packed) {
        return (flags(packed) & 0x10) != 0;
    }

    public static boolean isFluid(long packed) {
        return (flags(packed) & 0x20) != 0;
    }

    public static boolean isFlower(long packed) {
        return (flags(packed) & 0x40) != 0;
    }

    public static boolean isLeaves(long packed) {
        return (flags(packed) & 0x80) != 0;
    }

    public int getBlockLight() {
        return flags & 0x0F;
    }

    public boolean isGlowing() {
        return (flags & 0x10) != 0;
    }

    public boolean isFluid() {
        return (flags & 0x20) != 0;
    }

    public boolean isFlower() {
        return (flags & 0x40) != 0;
    }

    public boolean isLeaves() {
        return (flags & 0x80) != 0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private short topY = EMPTY_Y;
        private short blockId = NO_BLOCK;
        private byte biomeId = NO_BIOME;
        private short floorY = EMPTY_Y;
        private int light;
        private boolean glowing;
        private boolean fluid;
        private boolean flower;
        private boolean leaves;

        public Builder topY(int y) { this.topY = (short) y; return this; }
        public Builder floorY(int y) { this.floorY = (short) y; return this; }
        public Builder blockId(int id) { this.blockId = (short) id; return this; }
        public Builder biomeId(int id) { this.biomeId = (byte) id; return this; }
        public Builder light(int value) { this.light = Math.max(0, Math.min(15, value)); return this; }
        public Builder glowing(boolean value) { this.glowing = value; return this; }
        public Builder fluid(boolean value) { this.fluid = value; return this; }
        public Builder flower(boolean value) { this.flower = value; return this; }
        public Builder leaves(boolean value) { this.leaves = value; return this; }

        public MapBlockData build() {
            byte resultFlags = (byte) (light & 0x0F);
            if (glowing) resultFlags |= 0x10;
            if (fluid) resultFlags |= 0x20;
            if (flower) resultFlags |= 0x40;
            if (leaves) resultFlags |= (byte) 0x80;
            short resolvedFloor = floorY == EMPTY_Y ? topY : floorY;
            return new MapBlockData(topY, blockId, biomeId, resultFlags, resolvedFloor);
        }
    }
}
