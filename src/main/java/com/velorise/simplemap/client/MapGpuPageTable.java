package com.velorise.simplemap.client;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Double-buffered logical tile to atlas mapping.
 *
 * <p>The renderer only reads {@code front}. Producers mutate {@code back}; a
 * frame-boundary swap publishes all mappings atomically. Handles are stable for
 * the lifetime of the client and therefore geometry does not depend on atlas
 * slot coordinates.</p>
 */
public final class MapGpuPageTable {
    public static final int FLAG_RESIDENT = 1;
    public static final int FLAG_COMPLETE = 1 << 1;
    public static final int FLAG_GLOW = 1 << 2;

    public record Entry(ResourceLocation texture, float u0, float v0,
            float u1, float v1, long generation, int lod, int flags) {
        public boolean resident() {
            return texture != null && (flags & FLAG_RESIDENT) != 0;
        }
    }

    private static final MapGpuPageTable INSTANCE = new MapGpuPageTable();
    private final Map<MapTileKey, Integer> handles = new HashMap<>();
    private final ArrayList<Entry> front = new ArrayList<>();
    private final ArrayList<Entry> back = new ArrayList<>();
    private long frontRevision;
    private long backRevision;
    private boolean dirty;

    private MapGpuPageTable() {}

    public static MapGpuPageTable getInstance() {
        return INSTANCE;
    }

    public synchronized int handle(MapTileKey key) {
        Integer existing = handles.get(key);
        if (existing != null) return existing;
        int handle = handles.size();
        handles.put(key, handle);
        ensure(front, handle);
        ensure(back, handle);
        return handle;
    }

    public synchronized void stage(int handle, Entry entry) {
        if (handle < 0 || entry == null) return;
        ensure(back, handle);
        Entry previous = back.set(handle, entry);
        if (!entry.equals(previous)) {
            dirty = true;
            backRevision++;
        }
    }

    /** Atomically exposes all staged entries to the renderer. */
    public synchronized long swapAtFrameBoundary() {
        if (!dirty) return frontRevision;
        while (front.size() < back.size()) front.add(null);
        for (int index = 0; index < back.size(); index++) {
            front.set(index, back.get(index));
        }
        frontRevision = backRevision;
        dirty = false;
        return frontRevision;
    }

    public synchronized Entry front(int handle) {
        return handle >= 0 && handle < front.size() ? front.get(handle) : null;
    }

    public synchronized long frontRevision() {
        return frontRevision;
    }

    public synchronized int entries() {
        return handles.size();
    }

    public synchronized void clearSessionMappings() {
        handles.clear();
        front.clear();
        back.clear();
        frontRevision++;
        backRevision = frontRevision;
        dirty = false;
    }

    private static void ensure(ArrayList<Entry> list, int index) {
        while (list.size() <= index) list.add(null);
    }
}
