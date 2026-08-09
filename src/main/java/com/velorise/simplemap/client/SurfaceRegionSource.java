package com.velorise.simplemap.client;

import com.velorise.simplemap.client.pipeline.RevisionStamp;

import java.util.Arrays;

/**
 * Persistent region-scoped surface source database. A region owns 32x32
 * immutable chunk segments and the durable dirty masks needed to rebuild 8x8
 * exact leaves without treating a page queue as authority.
 */
public final class SurfaceRegionSource {
    public static final int CHUNKS_PER_AXIS = 32;
    public static final int CHUNK_COUNT = CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;
    public static final int DIRTY_WORDS = CHUNK_COUNT / Long.SIZE;

    /**
     * Allocation-light lifetime pin used while the scheduler only needs to
     * inspect source readiness. Deferred plans must not clone the complete
     * 32x32 chunk table and dirty mask merely to discover that their focus
     * page is still empty. A real immutable {@link View} is materialized only
     * after the batch passes admission.
     */
    public static final class Probe implements AutoCloseable {
        private final SurfaceRegionSource owner;
        private boolean closed;

        private Probe(SurfaceRegionSource owner) {
            this.owner = owner;
        }

        int chunkReadinessUnsafe(int localChunkX, int localChunkZ) {
            synchronized (this) {
                if (closed) return 0;
            }
            return owner.chunkReadiness(localChunkX, localChunkZ);
        }

        void copyReadinessUnsafe(long[] presentDestination,
                long[] dirtyDestination, int wordOffset,
                long[] revisionDestination, int revisionOffset) {
            synchronized (this) {
                if (closed) return;
            }
            owner.copyReadiness(presentDestination, dirtyDestination,
                    wordOffset, revisionDestination, revisionOffset);
        }

        ProbeMetadata snapshotMetadataUnsafe() {
            synchronized (this) {
                if (closed) return null;
            }
            return owner.snapshotProbeMetadata();
        }

        View acquireView(long expectedPaletteRevision) {
            synchronized (this) {
                if (closed) return null;
            }
            return owner.acquireView(expectedPaletteRevision);
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            owner.releaseView();
        }
    }

    static record ProbeMetadata(long sourceRevision, long paletteRevision,
            String[] biomePalette, String[] blockPalette) { }

    public static final class View implements AutoCloseable {
        private final SurfaceRegionSource owner;
        private final RevisionStamp stamp;
        private final int regionX;
        private final int regionZ;
        private final long sourceRevision;
        private final ChunkSnapshot[] chunks;
        private final String[] biomePalette;
        private final String[] blockPalette;
        private final long[] dirtyChunkMask;
        private final long dirtyLeafMask;
        private boolean closed;

        private View(SurfaceRegionSource owner, RevisionStamp stamp,
                int regionX, int regionZ,
                long sourceRevision, ChunkSnapshot[] chunks,
                String[] biomePalette, String[] blockPalette,
                long[] dirtyChunkMask, long dirtyLeafMask) {
            this.owner = owner;
            this.stamp = stamp;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.sourceRevision = sourceRevision;
            this.chunks = Arrays.copyOf(chunks, chunks.length);
            // Palette arrays are immutable after publication. SurfaceRegionSource
            // swaps the whole reference on palette revision instead of mutating it.
            this.biomePalette = biomePalette;
            this.blockPalette = blockPalette;
            this.dirtyChunkMask = Arrays.copyOf(dirtyChunkMask,
                    dirtyChunkMask.length);
            this.dirtyLeafMask = dirtyLeafMask;
        }

        public RevisionStamp stamp() { return stamp; }
        public int regionX() { return regionX; }
        public int regionZ() { return regionZ; }
        public long sourceRevision() { return sourceRevision; }
        public long dirtyLeafMask() { return dirtyLeafMask; }
        public ChunkSnapshot[] chunks() {
            return Arrays.copyOf(chunks, chunks.length);
        }
        public String[] biomePalette() {
            return Arrays.copyOf(biomePalette, biomePalette.length);
        }
        public String[] blockPalette() {
            return Arrays.copyOf(blockPalette, blockPalette.length);
        }
        public long[] dirtyChunkMask() {
            return Arrays.copyOf(dirtyChunkMask, dirtyChunkMask.length);
        }

        ChunkSnapshot chunkUnsafe(int localChunkX, int localChunkZ) {
            if (localChunkX < 0 || localChunkX >= CHUNKS_PER_AXIS
                    || localChunkZ < 0 || localChunkZ >= CHUNKS_PER_AXIS) return null;
            return chunks[localChunkZ * CHUNKS_PER_AXIS + localChunkX];
        }
        String[] biomePaletteUnsafe() { return biomePalette; }
        String[] blockPaletteUnsafe() { return blockPalette; }
        boolean chunkDirtyUnsafe(int localChunkX, int localChunkZ) {
            int index = localChunkZ * CHUNKS_PER_AXIS + localChunkX;
            return (dirtyChunkMask[index >>> 6] & (1L << (index & 63))) != 0L;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            owner.releaseView();
        }
    }

    private final RevisionStamp stamp;
    private final int regionX;
    private final int regionZ;
    private final ChunkSnapshot[] chunks = new ChunkSnapshot[CHUNK_COUNT];
    private final MapMemoryLeaseManager.Lease[] chunkLeases =
            new MapMemoryLeaseManager.Lease[CHUNK_COUNT];
    /** Persistent presence bits let capture probes inspect 1,024 chunks in one lock. */
    private final long[] presentChunkMask = new long[DIRTY_WORDS];
    private final long[] dirtyChunkMask = new long[DIRTY_WORDS];
    private String[] biomePalette = new String[0];
    private String[] blockPalette = new String[0];
    private long sourceRevision;
    private long paletteRevision;
    private long dirtyLeafMask = -1L;
    private int activeViews;
    private boolean closeRequested;
    private boolean released;

    public SurfaceRegionSource(RevisionStamp stamp, int regionX, int regionZ) {
        this.stamp = stamp;
        this.regionX = regionX;
        this.regionZ = regionZ;
        Arrays.fill(dirtyChunkMask, -1L);
    }

    public synchronized void updatePalette(MapManager.RegionSourcePalette palette) {
        if (released || closeRequested || palette == null
                || palette.paletteRevision() <= paletteRevision) return;
        biomePalette = palette.biomePaletteUnsafe();
        blockPalette = palette.blockPaletteUnsafe();
        paletteRevision = palette.paletteRevision();
        sourceRevision = Math.max(sourceRevision, palette.sourceRevision());
    }

    public synchronized long paletteRevision() {
        return paletteRevision;
    }

    public synchronized void markChunkDirty(int localChunkIndex) {
        if (released || closeRequested || localChunkIndex < 0
                || localChunkIndex >= CHUNK_COUNT) return;
        dirtyChunkMask[localChunkIndex >>> 6] |= 1L << (localChunkIndex & 63);
        int chunkX = localChunkIndex & 31;
        int chunkZ = localChunkIndex >>> 5;
        int leaf = (chunkZ >>> 2) * MapPageLayout.PAGES_PER_REGION + (chunkX >>> 2);
        dirtyLeafMask |= 1L << leaf;
    }

    public synchronized boolean needsCapture(int localChunkX, int localChunkZ,
            long currentRevision) {
        if (released || closeRequested) return false;
        int index = localChunkZ * CHUNKS_PER_AXIS + localChunkX;
        ChunkSnapshot existing = chunks[index];
        long bit = 1L << (index & 63);
        return existing == null || (dirtyChunkMask[index >>> 6] & bit) != 0L
                || existing.sourceRevision() < currentRevision;
    }

    public synchronized boolean leafSourceReady(int localPageX, int localPageZ) {
        if (released || closeRequested || localPageX < 0
                || localPageX >= MapPageLayout.PAGES_PER_REGION
                || localPageZ < 0
                || localPageZ >= MapPageLayout.PAGES_PER_REGION) return false;
        int startChunkX = localPageX * 4;
        int startChunkZ = localPageZ * 4;
        for (int chunkZ = startChunkZ; chunkZ < startChunkZ + 4; chunkZ++) {
            for (int chunkX = startChunkX; chunkX < startChunkX + 4; chunkX++) {
                int index = chunkZ * CHUNKS_PER_AXIS + chunkX;
                if (chunks[index] == null
                        || (dirtyChunkMask[index >>> 6]
                        & (1L << (index & 63))) != 0L) return false;
            }
        }
        return true;
    }

    /**
     * Returns the 4x4 exact-subtile mask currently backed by clean retained
     * chunk snapshots for one 64x64 page. Missing or dirty writer slots are not
     * advertised to the renderer. This lets the live minimap consume the writer
     * database without re-entering MapManager/Region.
     */
    public synchronized int leafPresentSubtileMask(int localPageX,
            int localPageZ) {
        if (released || closeRequested || localPageX < 0
                || localPageX >= MapPageLayout.PAGES_PER_REGION
                || localPageZ < 0
                || localPageZ >= MapPageLayout.PAGES_PER_REGION) return 0;
        int startChunkX = localPageX * 4;
        int startChunkZ = localPageZ * 4;
        int mask = 0;
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                int chunkX = startChunkX + x;
                int chunkZ = startChunkZ + z;
                int index = chunkZ * CHUNKS_PER_AXIS + chunkX;
                long bit = 1L << (index & 63);
                if (chunks[index] != null
                        && (dirtyChunkMask[index >>> 6] & bit) == 0L) {
                    mask |= 1 << (z * 4 + x);
                }
            }
        }
        return mask;
    }

    public synchronized boolean commit(ChunkSnapshot snapshot,
            MapMemoryLeaseManager.Lease memoryLease) {
        if (memoryLease == null) return false;
        if (released || closeRequested || snapshot == null) {
            memoryLease.close();
            return false;
        }
        int chunkX = snapshot.localChunkX();
        int chunkZ = snapshot.localChunkZ();
        if (chunkX < 0 || chunkX >= CHUNKS_PER_AXIS
                || chunkZ < 0 || chunkZ >= CHUNKS_PER_AXIS) {
            memoryLease.close();
            return false;
        }
        int index = chunkZ * CHUNKS_PER_AXIS + chunkX;
        ChunkSnapshot previous = chunks[index];
        if (previous != null && previous.sourceRevision() > snapshot.sourceRevision()) {
            memoryLease.close();
            return false;
        }
        MapMemoryLeaseManager.Lease previousLease = chunkLeases[index];
        chunks[index] = snapshot;
        chunkLeases[index] = memoryLease;
        if (previousLease != null) previousLease.close();
        sourceRevision = Math.max(sourceRevision, snapshot.sourceRevision());
        presentChunkMask[index >>> 6] |= 1L << (index & 63);
        dirtyChunkMask[index >>> 6] &= ~(1L << (index & 63));

        int leafX = chunkX >>> 2;
        int leafZ = chunkZ >>> 2;
        boolean leafDirty = false;
        for (int z = leafZ * 4; z < leafZ * 4 + 4 && !leafDirty; z++) {
            for (int x = leafX * 4; x < leafX * 4 + 4; x++) {
                int child = z * CHUNKS_PER_AXIS + x;
                if (chunks[child] == null
                        || (dirtyChunkMask[child >>> 6] & (1L << (child & 63))) != 0L) {
                    leafDirty = true;
                    break;
                }
            }
        }
        int leaf = leafZ * MapPageLayout.PAGES_PER_REGION + leafX;
        if (leafDirty) dirtyLeafMask |= 1L << leaf;
        else dirtyLeafMask &= ~(1L << leaf);
        return true;
    }


    public synchronized int residentChunkCount() {
        int count = 0;
        for (ChunkSnapshot chunk : chunks) if (chunk != null) count++;
        return count;
    }

    int debugResidentChunkCount() {
        return residentChunkCount();
    }

    synchronized int debugDirtyChunkCount() {
        int count = 0;
        for (int i = 0; i < dirtyChunkMask.length; i++) {
            // Absent slots start dirty by design; telemetry must count retained
            // chunks that need refresh, not all never-observed chunks in a region.
            count += Long.bitCount(dirtyChunkMask[i] & presentChunkMask[i]);
        }
        return count;
    }

    public synchronized int activeViewCount() {
        return activeViews;
    }

    public synchronized boolean closeRequested() {
        return closeRequested;
    }

    public synchronized boolean released() {
        return released;
    }

    public synchronized void close() {
        closeRequested = true;
        if (activeViews == 0) releaseStorage();
    }

    private synchronized ProbeMetadata snapshotProbeMetadata() {
        if (released) return null;
        // Palette arrays are immutable publication snapshots; updatePalette swaps
        // the whole reference, so returning these references is safe while the
        // revision pair is validated before a worker acquires a View.
        return new ProbeMetadata(sourceRevision, paletteRevision,
                biomePalette, blockPalette);
    }

    private synchronized View acquireView(long expectedPaletteRevision) {
        if (released || paletteRevision != expectedPaletteRevision) return null;
        activeViews++;
        return new View(this, stamp, regionX, regionZ, sourceRevision, chunks,
                biomePalette, blockPalette, dirtyChunkMask, dirtyLeafMask);
    }

    public synchronized View acquireView() {
        if (released) return null;
        activeViews++;
        return new View(this, stamp, regionX, regionZ, sourceRevision, chunks,
                biomePalette, blockPalette, dirtyChunkMask, dirtyLeafMask);
    }

    public synchronized Probe acquireProbe() {
        if (released) return null;
        activeViews++;
        return new Probe(this);
    }

    /** 0 = missing, 1 = dirty, 2 = ready. */
    private synchronized int chunkReadiness(int localChunkX, int localChunkZ) {
        if (released || localChunkX < 0 || localChunkX >= CHUNKS_PER_AXIS
                || localChunkZ < 0 || localChunkZ >= CHUNKS_PER_AXIS) return 0;
        int index = localChunkZ * CHUNKS_PER_AXIS + localChunkX;
        if (chunks[index] == null) return 0;
        return (dirtyChunkMask[index >>> 6] & (1L << (index & 63))) != 0L
                ? 1 : 2;
    }

    /**
     * Copies the complete readiness bitmap while holding the region lock once.
     * The previous probe path reacquired this monitor for every inspected chunk,
     * which became expensive when several rejected 4x4 batches were retried in
     * the same frame.
     */
    private synchronized void copyReadiness(long[] presentDestination,
            long[] dirtyDestination, int wordOffset,
            long[] revisionDestination, int revisionOffset) {
        if (released || presentDestination == null || dirtyDestination == null
                || revisionDestination == null
                || wordOffset < 0
                || wordOffset + DIRTY_WORDS > presentDestination.length
                || wordOffset + DIRTY_WORDS > dirtyDestination.length
                || revisionOffset < 0
                || revisionOffset + 1 >= revisionDestination.length) {
            return;
        }
        System.arraycopy(presentChunkMask, 0, presentDestination, wordOffset,
                DIRTY_WORDS);
        System.arraycopy(dirtyChunkMask, 0, dirtyDestination, wordOffset,
                DIRTY_WORDS);
        revisionDestination[revisionOffset] = sourceRevision;
        revisionDestination[revisionOffset + 1] = paletteRevision;
    }

    private synchronized void releaseView() {
        if (activeViews > 0) activeViews--;
        if (activeViews == 0 && closeRequested) releaseStorage();
    }

    private void releaseStorage() {
        if (released) return;
        released = true;
        for (int index = 0; index < chunks.length; index++) {
            chunks[index] = null;
            MapMemoryLeaseManager.Lease lease = chunkLeases[index];
            chunkLeases[index] = null;
            if (lease != null) lease.close();
        }
        Arrays.fill(presentChunkMask, 0L);
        Arrays.fill(dirtyChunkMask, -1L);
        dirtyLeafMask = -1L;
    }
}
