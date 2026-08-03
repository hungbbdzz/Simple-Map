package com.velorise.simplemap.client.gpu;

import java.util.Arrays;

/**
 * Immutable primitive logical-tile plan for the page-table renderer.
 *
 * <p>Atlas texture and UV data deliberately do not live in the plan. They are
 * resolved from the current front page table at draw time. Keeping cached fallback
 * locations here duplicated authoritative page-table data, retained atlas objects
 * after migration and allocated twenty unnecessary bytes per visible instance.</p>
 */
public final class MapGpuInstancePlan {
    private final TileKey[] keys;
    private final int[] phases;
    private final float[] rects;
    private final int size;

    private MapGpuInstancePlan(TileKey[] keys, int[] phases,
            float[] rects, int size) {
        this.keys = keys;
        this.phases = phases;
        this.rects = rects;
        this.size = size;
    }

    public int size() { return size; }
    public TileKey key(int index) { return keys[index]; }
    public int phase(int index) { return phases[index]; }

    public boolean hasPhase(int phase) {
        int start = firstIndexAtOrAfter(phase);
        return start < size && phases[start] == phase;
    }

    /** First sorted instance whose phase is greater than or equal to target. */
    public int firstIndexAtOrAfter(int targetPhase) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (phases[middle] < targetPhase) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /** Exclusive end of one sorted phase range. */
    public int firstIndexAfter(int targetPhase) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (phases[middle] <= targetPhase) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    public float x(int index) { return rects[index * 4]; }
    public float y(int index) { return rects[index * 4 + 1]; }
    public float width(int index) { return rects[index * 4 + 2]; }
    public float height(int index) { return rects[index * 4 + 3]; }

    public static final class Builder {
        private TileKey[] keys = new TileKey[128];
        private int[] phases = new int[128];
        private float[] rects = new float[512];
        private int size;

        public boolean add(TileKey key, int phase,
                int x, int y, int width, int height) {
            if (key == null || width <= 0 || height <= 0) return false;
            ensure(size + 1);
            keys[size] = key;
            phases[size] = phase;
            int offset = size * 4;
            rects[offset] = x;
            rects[offset + 1] = y;
            rects[offset + 2] = width;
            rects[offset + 3] = height;
            size++;
            return true;
        }

        public MapGpuInstancePlan build() {
            if (size == 0) {
                return new MapGpuInstancePlan(new TileKey[0], new int[0],
                        new float[0], 0);
            }
            // Primitive stable phase order. Actual texture grouping happens after
            // page-table resolution, so sorting by a stale fallback texture only
            // increased retained memory and could not guarantee fewer submissions.
            int[] order = new int[size];
            for (int i = 0; i < size; i++) order[i] = i;
            sortOrder(order, 0, size - 1);
            TileKey[] outKeys = new TileKey[size];
            int[] outPhases = new int[size];
            float[] outRects = new float[size * 4];
            for (int target = 0; target < size; target++) {
                int source = order[target];
                outKeys[target] = keys[source];
                outPhases[target] = phases[source];
                System.arraycopy(rects, source * 4, outRects, target * 4, 4);
            }
            return new MapGpuInstancePlan(outKeys, outPhases, outRects, size);
        }

        private void sortOrder(int[] order, int low, int high) {
            while (low < high) {
                if (high - low < 16) {
                    insertionSort(order, low, high);
                    return;
                }
                int left = low;
                int right = high;
                int pivot = order[(low + high) >>> 1];
                while (left <= right) {
                    while (compare(order[left], pivot) < 0) left++;
                    while (compare(order[right], pivot) > 0) right--;
                    if (left <= right) {
                        int swap = order[left];
                        order[left++] = order[right];
                        order[right--] = swap;
                    }
                }
                if (right - low < high - left) {
                    if (low < right) sortOrder(order, low, right);
                    low = left;
                } else {
                    if (left < high) sortOrder(order, left, high);
                    high = right;
                }
            }
        }

        private void insertionSort(int[] order, int low, int high) {
            for (int index = low + 1; index <= high; index++) {
                int value = order[index];
                int cursor = index - 1;
                while (cursor >= low && compare(order[cursor], value) > 0) {
                    order[cursor + 1] = order[cursor--];
                }
                order[cursor + 1] = value;
            }
        }

        private int compare(int left, int right) {
            int phase = Integer.compare(phases[left], phases[right]);
            return phase != 0 ? phase : Integer.compare(left, right);
        }

        private void ensure(int capacity) {
            if (capacity <= keys.length) return;
            int next = Math.max(capacity, keys.length * 2);
            keys = Arrays.copyOf(keys, next);
            phases = Arrays.copyOf(phases, next);
            rects = Arrays.copyOf(rects, next * 4);
        }
    }
}
