package com.velorise.simplemap.client.gpu;

/** Immutable front-page-table entry consumed by the renderer. */
public record PageTableEntry(int storageId, int slot, long storageGeneration,
        long contentRevision, int level, int flags,
        float sourceX, float sourceY, int sourceSize, int atlasSize) {
    public static final int FLAG_RESIDENT = 1;
    public static final int FLAG_COMPLETE = 1 << 1;
    public static final int FLAG_PROTECTED = 1 << 2;
    public static final int FLAG_LINEAR = 1 << 3;

    public PageTableEntry {
        if (storageId < 0) throw new IllegalArgumentException("storageId");
        if (slot < 0) throw new IllegalArgumentException("slot");
        if (sourceSize <= 0 || atlasSize <= 0) {
            throw new IllegalArgumentException("texture dimensions");
        }
    }

    public boolean resident() {
        return (flags & FLAG_RESIDENT) != 0;
    }
}
