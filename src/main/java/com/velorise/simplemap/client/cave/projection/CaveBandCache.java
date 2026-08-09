package com.velorise.simplemap.client.cave.projection;

import java.util.LinkedHashMap;

/** Bounded dimension-qualified Cave projection cache. */
public final class CaveBandCache {
    /**
     * Dimension is part of source identity. Same chunk coordinates/revisions in
     * Overworld, Nether, End or a custom dimension must never share a projection.
     */
    public record Key(String dimension, int chunkX, int chunkZ, int bandTopY,
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

    /**
     * Xaero's {@code MapLayer} keeps already-written regions even when the exact
     * caveStart inside that 16-block layer changes; old regions simply become
     * lazily outdated and are rewritten on demand. Do the same here. Exact Top-Y
     * remains part of the key, so retaining an older projection cannot alias the
     * active projection. The access-order LRU is the eviction boundary.
     *
     * <p>This method is intentionally a no-op compatibility hook for older callers
     * that treated a same-band Top-Y change as eager cache destruction.</p>
     */
    public synchronized int retainExactProjectionForBand(String dimension,
            int normalizedBandY, int activeTopY) {
        return 0;
    }

    /** Drop a dimension only on a true dimension-data invalidation, not navigation. */
    public synchronized int invalidateDimension(String dimension) {
        int before = cache.size();
        cache.entrySet().removeIf(entry -> entry.getKey().dimension().equals(dimension));
        return before - cache.size();
    }

    public synchronized int size() { return cache.size(); }
    public synchronized void clear() { cache.clear(); }

    private static int normalizeBand(int topY) {
        return Math.floorDiv(topY, 16) * 16;
    }
}
