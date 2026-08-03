package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveChunkTile;
import com.velorise.simplemap.client.cave.CaveColumnData;
import com.velorise.simplemap.client.cave.CavePipeline;
import com.velorise.simplemap.client.cave.CaveTileRepository;
import com.velorise.simplemap.client.cave.CaveView;

import java.io.File;

/** Compatibility API over the v4 CaveChunkTile repository. */
public final class VerticalCaveArchiveManager {
    public static final int TILE_SHIFT = 6;
    public static final int TILE_SIZE = 1 << TILE_SHIFT;
    public static final int MAX_SURFACES_PER_COLUMN = CaveColumnData.MAX_RUNS;

    private static final VerticalCaveArchiveManager INSTANCE = new VerticalCaveArchiveManager();
    private final CavePipeline pipeline = CavePipeline.getInstance();

    private VerticalCaveArchiveManager() {
    }

    public static VerticalCaveArchiveManager getInstance() {
        return INSTANCE;
    }

    public long getGeneration() {
        return pipeline.generation();
    }

    /** The v3 archive directory is ignored; v4 is configured by CaveMapManager. */
    public synchronized void setCacheDirectory(File directory) {
    }

    public boolean isColumnReady(int blockX, int blockZ) {
        return true;
    }

    public boolean isColumnScanned(int blockX, int blockZ) {
        return pipeline.repository().isColumnScanned(blockX, blockZ);
    }

    public boolean invalidateColumn(int blockX, int blockZ) {
        pipeline.invalidateColumn(blockX, blockZ);
        return true;
    }

    public Candidate getCandidate(int blockX, int blockZ, int maximumY, int minimumY) {
        CaveColumnData.Candidate candidate = pipeline.getCandidate(
                blockX, blockZ, maximumY, minimumY);
        return candidate == null ? null
                : new Candidate(candidate.topY(), candidate.bottomY(), candidate.color());
    }

    /**
     * Legacy compatibility entry point. Bounds inferred from candidates are kept
     * partial because this overload cannot prove that the full world height was
     * scanned. Internal scanners should use the metadata-aware overload below.
     */
    public boolean recordColumn(int blockX, int blockZ, Candidate[] candidates) {
        int scannedMinimumY = Short.MIN_VALUE;
        int scannedMaximumY = Short.MIN_VALUE;
        if (candidates != null && candidates.length > 0) {
            scannedMinimumY = Integer.MAX_VALUE;
            scannedMaximumY = Integer.MIN_VALUE;
            for (Candidate candidate : candidates) {
                if (candidate == null) continue;
                scannedMinimumY = Math.min(scannedMinimumY, candidate.bottomY());
                scannedMaximumY = Math.max(scannedMaximumY, candidate.topY());
            }
            if (scannedMinimumY == Integer.MAX_VALUE) {
                scannedMinimumY = Short.MIN_VALUE;
                scannedMaximumY = Short.MIN_VALUE;
            }
        }
        return recordColumn(blockX, blockZ, candidates,
                scannedMinimumY, scannedMaximumY, false);
    }

    public boolean recordColumn(int blockX, int blockZ, Candidate[] candidates,
            int scannedMinimumY, int scannedMaximumY, boolean reachedMinimumY) {
        CaveColumnData.Builder builder = new CaveColumnData.Builder();
        if (candidates != null) {
            for (Candidate candidate : candidates) {
                if (candidate != null) builder.add(candidate.topY(), candidate.bottomY(),
                        candidate.color(), (byte) 0);
            }
        }
        return recordColumnData(blockX, blockZ,
                builder.build(scannedMinimumY, scannedMaximumY, reachedMinimumY));
    }

    /** Commits an already-built immutable column without allocating a compatibility array. */
    public boolean recordColumnData(int blockX, int blockZ, CaveColumnData data) {
        CaveChunkTile tile = pipeline.repository().getOrCreateLiveTile(blockX >> 4, blockZ >> 4);
        return pipeline.repository().commitColumn(tile,
                CaveChunkTile.index(blockX & 15, blockZ & 15),
                data == null ? CaveColumnData.empty() : data);
    }

    public boolean hasRegionData(int regionX, int regionZ) {
        return pipeline.hasRegionData(regionX, regionZ);
    }

    public long getRegionRevision(int regionX, int regionZ) {
        return pipeline.getRegionRevision(regionX, regionZ);
    }

    public Projection projectRegion(int layerY, int regionX, int regionZ, boolean fullView) {
        CaveTileRepository.ResolvedRegion resolved = pipeline.resolveRegion(
                fullView ? CaveView.FULL : CaveView.LAYERED,
                layerY, regionX, regionZ);
        byte[] scanned = new byte[512 * 512];
        int scannedColumns = 0;
        for (int index = 0; index < scanned.length; index++) {
            if (resolved.heights()[index] != FullCaveMapManager.NO_SURFACE) {
                scanned[index] = 1;
                scannedColumns++;
            }
        }
        return new Projection(resolved.pixels(), resolved.heights(), scanned,
                scannedColumns, 0, resolved.revision());
    }

    /** Saving/flush is owned by CaveMapManager to avoid duplicate work. */
    public void tickSave() {
    }

    public synchronized void flushAndClear() {
    }

    public record Candidate(short topY, short bottomY, int color) {
        public Candidate(int y, int color) {
            this(y, y, color);
        }

        public Candidate(int topY, int bottomY, int color) {
            this(clampShort(topY), clampShort(Math.min(topY, bottomY)), color);
        }

        private static short clampShort(int value) {
            return (short) Math.max(Short.MIN_VALUE + 1,
                    Math.min(Short.MAX_VALUE, value));
        }
    }

    public record Projection(int[] colors, short[] heights, byte[] scanned,
            int scannedColumns, int sourceTiles, long revision) {
        public boolean hasData() {
            return scannedColumns > 0;
        }
    }
}
