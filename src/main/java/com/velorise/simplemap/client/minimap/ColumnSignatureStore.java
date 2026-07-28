package com.velorise.simplemap.client.minimap;

import java.util.Arrays;

/** Primitive signature cache used to retain unchanged minimap pixels. */
public final class ColumnSignatureStore {
    private final long[] signatures;
    private final int[] colors;
    private long revision;

    public ColumnSignatureStore(int pixels) {
        signatures = new long[Math.max(1, pixels)];
        colors = new int[signatures.length];
        Arrays.fill(signatures, Long.MIN_VALUE);
    }

    public synchronized boolean update(int index, long signature, int color) {
        if (index < 0 || index >= signatures.length) return false;
        if (signatures[index] == signature && colors[index] == color) return false;
        signatures[index] = signature;
        colors[index] = color;
        revision++;
        return true;
    }

    public synchronized int color(int index) {
        return index < 0 || index >= colors.length ? 0 : colors[index];
    }

    public synchronized long signature(int index) {
        return index < 0 || index >= signatures.length
                ? Long.MIN_VALUE : signatures[index];
    }

    public synchronized long revision() { return revision; }
    public synchronized int[] snapshotColors() {
        return Arrays.copyOf(colors, colors.length);
    }

    public synchronized void clear() {
        Arrays.fill(signatures, Long.MIN_VALUE);
        Arrays.fill(colors, 0);
        revision++;
    }

    public static long signature(int materialId, int biomeId, int topY,
            int light, int slope, int flags, long styleRevision) {
        long value = materialId & 0xFFFFFL;
        value = value * 0x9E3779B97F4A7C15L + (biomeId & 0xFFFFL);
        value = value * 0x9E3779B97F4A7C15L + (topY & 0xFFFFL);
        value = value * 0x9E3779B97F4A7C15L + (light & 0xFFL);
        value = value * 0x9E3779B97F4A7C15L + (slope & 0xFFL);
        value = value * 0x9E3779B97F4A7C15L + (flags & 0xFFL);
        return value ^ Long.rotateLeft(styleRevision, 17);
    }
}
