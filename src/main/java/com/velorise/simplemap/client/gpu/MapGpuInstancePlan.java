package com.velorise.simplemap.client.gpu;

import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;

/** Immutable primitive tile-instance plan for the M5 renderer. */
public final class MapGpuInstancePlan {
    private final TileKey[] keys;
    private final ResourceLocation[] fallbackTextures;
    private final int[] phases;
    private final float[] rects;
    private final float[] fallbackUvs;
    private final int size;

    private MapGpuInstancePlan(TileKey[] keys,
            ResourceLocation[] fallbackTextures, int[] phases,
            float[] rects, float[] fallbackUvs, int size) {
        this.keys = keys;
        this.fallbackTextures = fallbackTextures;
        this.phases = phases;
        this.rects = rects;
        this.fallbackUvs = fallbackUvs;
        this.size = size;
    }

    public int size() { return size; }
    public TileKey key(int index) { return keys[index]; }
    public ResourceLocation fallbackTexture(int index) {
        return fallbackTextures[index];
    }
    public int phase(int index) { return phases[index]; }
    public boolean hasPhase(int phase) {
        return Arrays.binarySearch(phases, 0, size, phase) >= 0;
    }
    public float x(int index) { return rects[index * 4]; }
    public float y(int index) { return rects[index * 4 + 1]; }
    public float width(int index) { return rects[index * 4 + 2]; }
    public float height(int index) { return rects[index * 4 + 3]; }
    public float fallbackU0(int index) { return fallbackUvs[index * 4]; }
    public float fallbackV0(int index) { return fallbackUvs[index * 4 + 1]; }
    public float fallbackU1(int index) { return fallbackUvs[index * 4 + 2]; }
    public float fallbackV1(int index) { return fallbackUvs[index * 4 + 3]; }

    public static final class Builder {
        private TileKey[] keys = new TileKey[128];
        private ResourceLocation[] textures = new ResourceLocation[128];
        private int[] phases = new int[128];
        private float[] rects = new float[512];
        private float[] uvs = new float[512];
        private int size;

        public boolean add(TileKey key, ResourceLocation fallbackTexture,
                int phase, int x, int y, int width, int height,
                float u0, float v0, float u1, float v1) {
            if (key == null || fallbackTexture == null
                    || width <= 0 || height <= 0) return false;
            ensure(size + 1);
            keys[size] = key;
            textures[size] = fallbackTexture;
            phases[size] = phase;
            int offset = size * 4;
            rects[offset] = x;
            rects[offset + 1] = y;
            rects[offset + 2] = width;
            rects[offset + 3] = height;
            uvs[offset] = u0;
            uvs[offset + 1] = v0;
            uvs[offset + 2] = u1;
            uvs[offset + 3] = v1;
            size++;
            return true;
        }

        public MapGpuInstancePlan build() {
            Integer[] order = new Integer[size];
            for (int i = 0; i < size; i++) order[i] = i;
            Arrays.sort(order, (left, right) -> {
                int phase = Integer.compare(phases[left], phases[right]);
                if (phase != 0) return phase;
                return textures[left].toString()
                        .compareTo(textures[right].toString());
            });
            TileKey[] outKeys = new TileKey[size];
            ResourceLocation[] outTextures = new ResourceLocation[size];
            int[] outPhases = new int[size];
            float[] outRects = new float[size * 4];
            float[] outUvs = new float[size * 4];
            for (int target = 0; target < size; target++) {
                int source = order[target];
                outKeys[target] = keys[source];
                outTextures[target] = textures[source];
                outPhases[target] = phases[source];
                System.arraycopy(rects, source * 4, outRects, target * 4, 4);
                System.arraycopy(uvs, source * 4, outUvs, target * 4, 4);
            }
            return new MapGpuInstancePlan(outKeys, outTextures, outPhases,
                    outRects, outUvs, size);
        }

        private void ensure(int capacity) {
            if (capacity <= keys.length) return;
            int next = Math.max(capacity, keys.length * 2);
            keys = Arrays.copyOf(keys, next);
            textures = Arrays.copyOf(textures, next);
            phases = Arrays.copyOf(phases, next);
            rects = Arrays.copyOf(rects, next * 4);
            uvs = Arrays.copyOf(uvs, next * 4);
        }
    }
}
