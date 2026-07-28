package com.velorise.simplemap.client.cave.archive;

import com.velorise.simplemap.client.cave.CaveChunkTile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Session-scoped compact cave archive shared by every cave projection. */
public final class CaveArchiveV2Service {
    public record Summary(int tiles, long bytes, long ingested,
            long replaced, long staleIgnored) { }

    private static final CaveArchiveV2Service INSTANCE =
            new CaveArchiveV2Service();
    private static final int MAX_RESIDENT_TILES = 4096;

    private final LinkedHashMap<Long, CompactCaveTile> tiles =
            new LinkedHashMap<>(256, 0.75f, true);
    private long bytes;
    private long ingested;
    private long replaced;
    private long staleIgnored;

    private CaveArchiveV2Service() { }

    public static CaveArchiveV2Service getInstance() { return INSTANCE; }

    public synchronized boolean ingest(CaveChunkTile.Snapshot snapshot) {
        CompactCaveTile compact = CompactCaveTile.fromLegacy(snapshot);
        if (compact == null) return false;
        long key = pack(compact.chunkX(), compact.chunkZ());
        CompactCaveTile previous = tiles.get(key);
        if (previous != null && previous.revision() > compact.revision()) {
            staleIgnored++;
            return false;
        }
        if (previous != null) {
            bytes -= previous.estimatedBytes();
            replaced++;
        }
        tiles.put(key, compact);
        bytes += compact.estimatedBytes();
        ingested++;
        trim();
        return true;
    }

    public synchronized CompactCaveTile get(int chunkX, int chunkZ) {
        return tiles.get(pack(chunkX, chunkZ));
    }

    public synchronized Summary summary() {
        return new Summary(tiles.size(), bytes, ingested, replaced, staleIgnored);
    }

    public synchronized void clear() {
        tiles.clear();
        bytes = 0L;
    }

    private void trim() {
        var iterator = tiles.entrySet().iterator();
        while (tiles.size() > MAX_RESIDENT_TILES && iterator.hasNext()) {
            Map.Entry<Long, CompactCaveTile> entry = iterator.next();
            bytes -= entry.getValue().estimatedBytes();
            iterator.remove();
        }
    }

    public static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }
}
