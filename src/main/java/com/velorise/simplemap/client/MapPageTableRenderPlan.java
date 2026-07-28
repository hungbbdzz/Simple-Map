package com.velorise.simplemap.client;

import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;

/** Immutable primitive instance plan. Atlas slot coordinates stay in the page table. */
final class MapPageTableRenderPlan {
    private final int[] handles;
    private final int[] phases;
    private final float[] geometry; // x, y, w, h
    private final int count;

    MapPageTableRenderPlan(int[] handles, int[] phases, float[] geometry, int count) {
        this.handles = handles;
        this.phases = phases;
        this.geometry = geometry;
        this.count = count;
    }

    int count() { return count; }
    int handle(int index) { return handles[index]; }
    int phase(int index) { return phases[index]; }
    float x(int index) { return geometry[index * 4]; }
    float y(int index) { return geometry[index * 4 + 1]; }
    float width(int index) { return geometry[index * 4 + 2]; }
    float height(int index) { return geometry[index * 4 + 3]; }

    static final class Builder {
        private int[] handles = new int[256];
        private int[] phases = new int[256];
        private float[] geometry = new float[256 * 4];
        private int count;
        private long generation;

        void add(MapTileKey key, ResourceLocation texture, int phase,
                int x, int y, int width, int height,
                float u0, float v0, float u1, float v1, int lod, int flags) {
            ensure(count + 1);
            MapGpuPageTable table = MapGpuPageTable.getInstance();
            int handle = table.handle(key);
            table.stage(handle, new MapGpuPageTable.Entry(texture, u0, v0, u1, v1,
                    ++generation, lod, flags | MapGpuPageTable.FLAG_RESIDENT));
            handles[count] = handle;
            phases[count] = phase;
            int offset = count * 4;
            geometry[offset] = x;
            geometry[offset + 1] = y;
            geometry[offset + 2] = width;
            geometry[offset + 3] = height;
            count++;
        }

        MapPageTableRenderPlan build() {
            // Stable insertion sort by phase. Plan construction is off the hot replay path.
            for (int i = 1; i < count; i++) {
                int h = handles[i];
                int p = phases[i];
                float x = geometry[i * 4];
                float y = geometry[i * 4 + 1];
                float w = geometry[i * 4 + 2];
                float z = geometry[i * 4 + 3];
                int j = i - 1;
                while (j >= 0 && phases[j] > p) {
                    handles[j + 1] = handles[j];
                    phases[j + 1] = phases[j];
                    System.arraycopy(geometry, j * 4, geometry, (j + 1) * 4, 4);
                    j--;
                }
                handles[j + 1] = h;
                phases[j + 1] = p;
                int offset = (j + 1) * 4;
                geometry[offset] = x;
                geometry[offset + 1] = y;
                geometry[offset + 2] = w;
                geometry[offset + 3] = z;
            }
            return new MapPageTableRenderPlan(Arrays.copyOf(handles, count),
                    Arrays.copyOf(phases, count), Arrays.copyOf(geometry, count * 4), count);
        }

        private void ensure(int needed) {
            if (needed <= handles.length) return;
            int next = Math.max(needed, handles.length * 2);
            handles = Arrays.copyOf(handles, next);
            phases = Arrays.copyOf(phases, next);
            geometry = Arrays.copyOf(geometry, next * 4);
        }
    }
}
