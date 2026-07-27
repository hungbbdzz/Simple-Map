package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CavePipeline;
import com.velorise.simplemap.client.cave.CaveTileRepository;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import com.velorise.simplemap.client.pipeline.MapWorkKey;

import java.util.ArrayList;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Full Cave compatibility view resolved from the same CaveChunkTile archive. */
public final class FullCaveMapManager {
    public static final short NO_SURFACE = Short.MIN_VALUE;
    private static final FullCaveMapManager INSTANCE = new FullCaveMapManager();
    private static final int PIXELS = 512 * 512;
    private static final int MAX_COMPAT_REGIONS = 12;

    private final CavePipeline pipeline = CavePipeline.getInstance();
    private final AtomicLong generation = new AtomicLong(1L);
    private final Map<Long, RegionHolder> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final List<ProjectionCompletion> projectionCompletions = new ArrayList<>();

    private FullCaveMapManager() {
    }

    public static FullCaveMapManager getInstance() {
        return INSTANCE;
    }

    public long getGeneration() {
        return generation.get();
    }

    public boolean isGenerationCurrent(long value) {
        return generation.get() == value;
    }

    /** Cache ownership moved to CaveMapManager/CavePipeline v4. */
    public synchronized void setCacheDirectory(File directory) {
        deferProjectionCompletions();
        clearCompatRegions();
        generation.incrementAndGet();
    }

    /** Retained for old call sites; live scanning now commits complete column archives. */
    public void mergeCandidate(int blockX, int blockZ, int abgrColor,
            int surfaceY, int observationTopY) {
        FullCaveTextureManager.getInstance().markRegionTextureDirty(blockX >> 9, blockZ >> 9);
    }

    public int getColor(int blockX, int blockZ) {
        return pipeline.getColor(CaveView.FULL, Integer.MIN_VALUE, blockX, blockZ);
    }

    public int getSurfaceY(int blockX, int blockZ) {
        return pipeline.getHeight(CaveView.FULL, Integer.MIN_VALUE, blockX, blockZ);
    }

    public FullRegion getRegion(int rx, int rz, boolean loadIfMissing) {
        return materialize(rx, rz, loadIfMissing);
    }

    /** M2 adapter: completes FULL_CAVE_PROJECTION only after archive readiness. */
    public boolean requestRegionLoad(MapWorkKey key, long revision) {
        if (key == null || !isGenerationCurrent(revision)) return false;
        FullRegion region = materialize(key.regionX(), key.regionZ(), true);
        if (region != null && region.isLoaded()) {
            MapWorkGraph.getInstance().complete(key, revision);
            return true;
        }
        if (!pipeline.hasRegionData(key.regionX(), key.regionZ())) {
            MapWorkGraph.getInstance().complete(key, revision);
            return true;
        }
        synchronized (projectionCompletions) {
            projectionCompletions.add(new ProjectionCompletion(key, revision));
        }
        return true;
    }

    /** Called after CavePipeline advances its asynchronous archive reads. */
    public void tickWorkGraphCompletions() {
        List<ProjectionCompletion> pending;
        synchronized (projectionCompletions) {
            if (projectionCompletions.isEmpty()) return;
            pending = new ArrayList<>(projectionCompletions);
            projectionCompletions.clear();
        }
        for (ProjectionCompletion completion : pending) {
            if (!isGenerationCurrent(completion.revision())) {
                MapWorkGraph.getInstance().defer(completion.key());
                continue;
            }
            int rx = completion.key().regionX();
            int rz = completion.key().regionZ();
            FullRegion region = materialize(rx, rz, false);
            if ((region != null && region.isLoaded()) || !pipeline.hasRegionData(rx, rz)) {
                MapWorkGraph.getInstance().complete(completion.key(), completion.revision());
            } else {
                synchronized (projectionCompletions) {
                    projectionCompletions.add(completion);
                }
            }
        }
    }

    public boolean hasRegionFile(int rx, int rz) {
        return pipeline.hasRegionData(rx, rz);
    }

    public boolean isRegionLoaded(int rx, int rz) {
        return pipeline.isRegionLoaded(rx, rz);
    }

    public FullSnapshot getLoadedSnapshot(int rx, int rz) {
        FullRegion region = materialize(rx, rz, false);
        return region == null ? null : region.snapshot();
    }

    public FullSnapshot readSnapshotFromDisk(int rx, int rz) {
        pipeline.requestRegionLoad(rx, rz);
        FullRegion region = materialize(rx, rz, false);
        return region == null ? null : region.snapshot();
    }

    /** Saving is performed once by CaveMapManager.tickSave(). */
    public void tickSave() {
    }

    public synchronized void flushAndClear() {
        deferProjectionCompletions();
        clearCompatRegions();
        generation.incrementAndGet();
    }

    public synchronized void flushDataForDimensionSwitch() {
        flushAndClear();
    }

    public synchronized void deactivate() {
        deferProjectionCompletions();
        clearCompatRegions();
        generation.incrementAndGet();
    }

    private FullRegion materialize(int rx, int rz, boolean requestLoad) {
        if (requestLoad) pipeline.requestRegionLoad(rx, rz);
        long key = CaveTileRepository.pack(rx, rz);
        long sourceRevision = pipeline.getRegionRevision(rx, rz);
        synchronized (regions) {
            RegionHolder holder = regions.get(key);
            if (holder != null && holder.sourceRevision == sourceRevision) return holder.region;
            if (!pipeline.isRegionLoaded(rx, rz) && sourceRevision == 0L) return holder == null ? null : holder.region;
            CaveTileRepository.ResolvedRegion resolved = pipeline.resolveRegion(
                    CaveView.FULL, Integer.MIN_VALUE, rx, rz);
            FullRegion region = new FullRegion(rx, rz, generation.get());
            region.replace(new FullSnapshot(resolved.pixels(), resolved.heights()));
            region.markLoaded();
            regions.put(key, new RegionHolder(region, sourceRevision));
            trimCompatRegions();
            return region;
        }
    }

    private void trimCompatRegions() {
        while (regions.size() > MAX_COMPAT_REGIONS) {
            var iterator = regions.entrySet().iterator();
            if (!iterator.hasNext()) break;
            RegionHolder holder = iterator.next().getValue();
            iterator.remove();
            holder.region.close();
        }
    }

    private void clearCompatRegions() {
        synchronized (regions) {
            for (RegionHolder holder : regions.values()) holder.region.close();
            regions.clear();
        }
    }

    private void deferProjectionCompletions() {
        List<ProjectionCompletion> pending;
        synchronized (projectionCompletions) {
            if (projectionCompletions.isEmpty()) return;
            pending = new ArrayList<>(projectionCompletions);
            projectionCompletions.clear();
        }
        for (ProjectionCompletion completion : pending) {
            MapWorkGraph.getInstance().defer(completion.key());
        }
    }

    public record FullSnapshot(int[] pixels, short[] heights) {
    }

    private record ProjectionCompletion(MapWorkKey key, long revision) {
    }

    public static final class FullRegion {
        public final int rx;
        public final int rz;
        private final long generation;
        private final int[] pixels = new int[PIXELS];
        private final short[] heights = new short[PIXELS];
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean loaded;
        private volatile boolean closed;

        private FullRegion(int rx, int rz, long generation) {
            this.rx = rx;
            this.rz = rz;
            this.generation = generation;
            Arrays.fill(heights, NO_SURFACE);
        }

        public boolean isLoaded() { return loaded && !closed; }
        public void markLoaded() { if (!closed) loaded = true; }
        public long getGeneration() { return generation; }
        public void lock() { lock.lock(); }
        public void unlock() { lock.unlock(); }
        public void close() { closed = true; }
        public int[] getPixelsDirect() { return pixels; }
        public short[] getHeightsDirect() { return heights; }

        public int getColor(int px, int pz) {
            lock.lock();
            try { return pixels[pz * 512 + px]; }
            finally { lock.unlock(); }
        }

        public int getSurfaceY(int px, int pz) {
            lock.lock();
            try { return heights[pz * 512 + px]; }
            finally { lock.unlock(); }
        }

        public int[] snapshotPixels() {
            lock.lock();
            try { return Arrays.copyOf(pixels, pixels.length); }
            finally { lock.unlock(); }
        }

        private FullSnapshot snapshot() {
            lock.lock();
            try {
                return new FullSnapshot(Arrays.copyOf(pixels, pixels.length),
                        Arrays.copyOf(heights, heights.length));
            } finally {
                lock.unlock();
            }
        }

        private void replace(FullSnapshot snapshot) {
            lock.lock();
            try {
                if (closed) return;
                System.arraycopy(snapshot.pixels(), 0, pixels, 0,
                        Math.min(PIXELS, snapshot.pixels().length));
                System.arraycopy(snapshot.heights(), 0, heights, 0,
                        Math.min(PIXELS, snapshot.heights().length));
            } finally {
                lock.unlock();
            }
        }
    }

    private record RegionHolder(FullRegion region, long sourceRevision) {
    }
}
