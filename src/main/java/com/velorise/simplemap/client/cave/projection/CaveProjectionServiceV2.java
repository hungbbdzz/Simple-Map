package com.velorise.simplemap.client.cave.projection;

import com.velorise.simplemap.client.cave.archive.CaveArchiveV2Service;
import com.velorise.simplemap.client.cave.archive.CompactCaveTile;

import java.util.HashMap;
import java.util.Map;

/**
 * M8 archive-only Layered and Full Cave projection service.
 * Top-Y scrubbing uses nearest cached bands; exact refinement is requested only
 * after the caller's debounce window.
 */
public final class CaveProjectionServiceV2 {
    public record Summary(int bandEntries, int coarseEntries,
            long layeredHits, long layeredMisses, long fullBuilds,
            long staleRejected) { }

    private static final CaveProjectionServiceV2 INSTANCE =
            new CaveProjectionServiceV2();
    private static final int BAND_HEIGHT = 16;

    private final CaveBandCache bandCache = new CaveBandCache(4096);
    private final Map<Long, CaveCoarseSummary> fullSummaries = new HashMap<>();
    private long layeredHits;
    private long layeredMisses;
    private long fullBuilds;
    private long staleRejected;

    private CaveProjectionServiceV2() { }

    public static CaveProjectionServiceV2 getInstance() { return INSTANCE; }

    public CaveProjectionTile layered(int chunkX, int chunkZ, int topY,
            long styleRevision) {
        CompactCaveTile tile = CaveArchiveV2Service.getInstance().get(chunkX, chunkZ);
        if (tile == null) return null;
        int bandTop = quantizeBand(topY);
        CaveBandCache.Key key = new CaveBandCache.Key(chunkX, chunkZ,
                bandTop, tile.revision(), styleRevision);
        CaveProjectionTile cached = bandCache.get(key);
        if (cached != null) {
            layeredHits++;
            return cached;
        }
        layeredMisses++;
        CaveProjectionTile projected = projectLayered(tile, bandTop);
        bandCache.put(key, projected);
        return projected;
    }

    public synchronized CaveCoarseSummary fullSummary(int chunkX, int chunkZ) {
        CompactCaveTile tile = CaveArchiveV2Service.getInstance().get(chunkX, chunkZ);
        if (tile == null) return null;
        long key = CaveArchiveV2Service.pack(chunkX, chunkZ);
        CaveCoarseSummary cached = fullSummaries.get(key);
        if (cached != null && cached.archiveRevision() == tile.revision()) {
            return cached;
        }
        CaveCoarseSummary summary = summarize(tile);
        fullSummaries.put(key, summary);
        fullBuilds++;
        return summary;
    }

    public synchronized Summary summary() {
        return new Summary(bandCache.size(), fullSummaries.size(),
                layeredHits, layeredMisses, fullBuilds, staleRejected);
    }

    public synchronized void clear() {
        bandCache.clear();
        fullSummaries.clear();
    }

    private static CaveProjectionTile projectLayered(CompactCaveTile tile,
            int bandTop) {
        int bandBottom = bandTop - BAND_HEIGHT + 1;
        int[] pixels = new int[256];
        byte[] completeness = new byte[256];
        for (int column = 0; column < 256; column++) {
            CompactCaveTile.ColumnStatus status = tile.status(column);
            completeness[column] = (byte) status.ordinal();
            for (int run = tile.runStart(column); run < tile.runEnd(column); run++) {
                int floor = tile.floorY(run);
                int top = tile.topY(run);
                if (floor > bandTop) continue;
                if (top < bandBottom) break;
                pixels[column] = resolveColor(tile, run);
                break;
            }
        }
        return new CaveProjectionTile(tile.chunkX(), tile.chunkZ(), bandTop,
                tile.revision(), pixels, completeness);
    }

    private static CaveCoarseSummary summarize(CompactCaveTile tile) {
        int known = 0;
        int complete = 0;
        int water = 0;
        int emissive = 0;
        int minDepth = Integer.MAX_VALUE;
        int maxDepth = Integer.MIN_VALUE;
        Map<Integer, Integer> materialFrequency = new HashMap<>();
        for (int column = 0; column < 256; column++) {
            CompactCaveTile.ColumnStatus status = tile.status(column);
            if (status != CompactCaveTile.ColumnStatus.UNKNOWN
                    && status != CompactCaveTile.ColumnStatus.CORRUPT) known++;
            if (status == CompactCaveTile.ColumnStatus.COMPLETE) complete++;
            if (tile.runStart(column) >= tile.runEnd(column)) continue;
            int run = tile.runStart(column);
            int flags = tile.flags(run) & 0xFF;
            if ((flags & CompactCaveTile.FLAG_WATER) != 0) water++;
            if ((flags & CompactCaveTile.FLAG_EMISSIVE) != 0) emissive++;
            int depth = tile.floorY(run);
            minDepth = Math.min(minDepth, depth);
            maxDepth = Math.max(maxDepth, depth);
            materialFrequency.merge(tile.materialId(run), 1, Integer::sum);
        }
        int dominant = 0;
        int dominantCount = -1;
        for (Map.Entry<Integer, Integer> entry : materialFrequency.entrySet()) {
            if (entry.getValue() > dominantCount) {
                dominant = entry.getKey();
                dominantCount = entry.getValue();
            }
        }
        if (minDepth == Integer.MAX_VALUE) minDepth = 0;
        if (maxDepth == Integer.MIN_VALUE) maxDepth = 0;
        return new CaveCoarseSummary(tile.chunkX(), tile.chunkZ(), tile.revision(),
                known / 256.0f, dominant, water / 256.0f,
                emissive / 256.0f, minDepth, maxDepth, complete / 256.0f);
    }

    private static int resolveColor(CompactCaveTile tile, int run) {
        int material = tile.materialId(run);
        if ((tile.flags(run) & CompactCaveTile.FLAG_LEGACY_COLOR) != 0) {
            int argb = material;
            return 0xFF000000 | ((argb & 0xFF) << 16)
                    | (argb & 0x0000FF00) | ((argb >>> 16) & 0xFF);
        }
        int hash = material * 0x9E3779B9;
        int red = 48 + ((hash >>> 16) & 0x7F);
        int green = 48 + ((hash >>> 8) & 0x7F);
        int blue = 48 + (hash & 0x7F);
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private static int quantizeBand(int topY) {
        return Math.floorDiv(topY, BAND_HEIGHT) * BAND_HEIGHT + BAND_HEIGHT - 1;
    }
}
