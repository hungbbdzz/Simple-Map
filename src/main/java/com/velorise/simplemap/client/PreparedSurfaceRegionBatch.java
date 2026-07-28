package com.velorise.simplemap.client;

import com.velorise.simplemap.client.pipeline.RevisionStamp;

import java.util.Arrays;

/** Immutable result of one multi-leaf surface projection transaction. */
public final class PreparedSurfaceRegionBatch {
    private final RevisionStamp stamp;
    private final int regionX;
    private final int regionZ;
    private final int batchPageX;
    private final int batchPageZ;
    private final int pagesWide;
    private final int pagesHigh;
    private final long sourceRevision;
    private final MapTextureBuildWorker.PreparedPair[] pages;

    PreparedSurfaceRegionBatch(RevisionStamp stamp, int regionX, int regionZ,
            int batchPageX, int batchPageZ, int pagesWide, int pagesHigh,
            long sourceRevision, MapTextureBuildWorker.PreparedPair[] pages) {
        this.stamp = stamp;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.batchPageX = batchPageX;
        this.batchPageZ = batchPageZ;
        this.pagesWide = pagesWide;
        this.pagesHigh = pagesHigh;
        this.sourceRevision = sourceRevision;
        this.pages = Arrays.copyOf(pages, pages.length);
    }

    public RevisionStamp stamp() { return stamp; }
    public int regionX() { return regionX; }
    public int regionZ() { return regionZ; }
    public int batchPageX() { return batchPageX; }
    public int batchPageZ() { return batchPageZ; }
    public int pagesWide() { return pagesWide; }
    public int pagesHigh() { return pagesHigh; }
    public long sourceRevision() { return sourceRevision; }

    MapTextureBuildWorker.PreparedPair page(int localPageX, int localPageZ) {
        if (localPageX < 0 || localPageX >= pagesWide
                || localPageZ < 0 || localPageZ >= pagesHigh) return null;
        return pages[localPageZ * pagesWide + localPageX];
    }
}
