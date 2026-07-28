package com.velorise.simplemap.client.gpu;

import java.util.Arrays;

/**
 * Immutable geometry plan containing only logical tile keys and primitive world
 * rectangles. Atlas UVs are resolved from the front page table at draw time.
 */
public final class MapInstancePlan {
    private final TileKey[] keys;
    private final int[] phases;
    private final float[] rectangles;
    private final long viewportRevision;

    public MapInstancePlan(TileKey[] keys, int[] phases, float[] rectangles,
            long viewportRevision) {
        if (keys == null || phases == null || rectangles == null
                || phases.length != keys.length
                || rectangles.length != keys.length * 4) {
            throw new IllegalArgumentException("Invalid instance plan arrays");
        }
        this.keys = Arrays.copyOf(keys, keys.length);
        this.phases = Arrays.copyOf(phases, phases.length);
        this.rectangles = Arrays.copyOf(rectangles, rectangles.length);
        this.viewportRevision = viewportRevision;
    }

    public int size() { return keys.length; }
    public long viewportRevision() { return viewportRevision; }
    public TileKey key(int index) { return keys[index]; }
    public int phase(int index) { return phases[index]; }
    public float x(int index) { return rectangles[index * 4]; }
    public float y(int index) { return rectangles[index * 4 + 1]; }
    public float width(int index) { return rectangles[index * 4 + 2]; }
    public float height(int index) { return rectangles[index * 4 + 3]; }

    public static final class Builder {
        private TileKey[] keys = new TileKey[128];
        private int[] phases = new int[128];
        private float[] rectangles = new float[128 * 4];
        private int size;

        public void add(TileKey key, int phase, float x, float y,
                float width, float height) {
            if (key == null || width <= 0.0f || height <= 0.0f) return;
            ensure(size + 1);
            keys[size] = key;
            phases[size] = phase;
            int offset = size * 4;
            rectangles[offset] = x;
            rectangles[offset + 1] = y;
            rectangles[offset + 2] = width;
            rectangles[offset + 3] = height;
            size++;
        }

        public MapInstancePlan build(long viewportRevision) {
            return new MapInstancePlan(Arrays.copyOf(keys, size),
                    Arrays.copyOf(phases, size),
                    Arrays.copyOf(rectangles, size * 4), viewportRevision);
        }

        private void ensure(int capacity) {
            if (capacity <= keys.length) return;
            int next = Math.max(capacity, keys.length * 2);
            keys = Arrays.copyOf(keys, next);
            phases = Arrays.copyOf(phases, next);
            rectangles = Arrays.copyOf(rectangles, next * 4);
        }
    }
}
