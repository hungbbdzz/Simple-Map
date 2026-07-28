package com.velorise.simplemap.client.cave.projection;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded Layered-Cave projection cache quantized to Y bands. */
public final class CaveBandCache {
    public record Key(int chunkX, int chunkZ, int bandTopY,
            long archiveRevision, long styleRevision) { }

    private final int maximumEntries;
    private final LinkedHashMap<Key, CaveProjectionTile> cache;

    public CaveBandCache(int maximumEntries) {
        this.maximumEntries = Math.max(16, maximumEntries);
        this.cache = new LinkedHashMap<>(64, 0.75f, true);
    }

    public synchronized CaveProjectionTile get(Key key) {
        return cache.get(key);
    }

    public synchronized void put(Key key, CaveProjectionTile value) {
        if (key == null || value == null) return;
        cache.put(key, value);
        var iterator = cache.entrySet().iterator();
        while (cache.size() > maximumEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    public synchronized void invalidateStyle(long currentStyleRevision) {
        cache.entrySet().removeIf(entry ->
                entry.getKey().styleRevision() != currentStyleRevision);
    }

    public synchronized int size() { return cache.size(); }
    public synchronized void clear() { cache.clear(); }
}
