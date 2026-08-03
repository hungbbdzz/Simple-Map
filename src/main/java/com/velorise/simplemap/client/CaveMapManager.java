package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CavePipeline;
import com.velorise.simplemap.client.cave.CaveTileRepository;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import com.velorise.simplemap.client.pipeline.MapWorkKey;

import java.util.ArrayList;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Selected Top-Y facade backed by the shared CaveChunkTile repository.
 * Pixel regions exist only as compatibility snapshots for overview/inspection code.
 */
public final class CaveMapManager {
    private static final CaveMapManager INSTANCE = new CaveMapManager();
    private static final int MAX_COMPAT_REGIONS = 12;

    private final CavePipeline pipeline = CavePipeline.getInstance();
    private final AtomicLong layerGeneration = new AtomicLong(1L);
    private final Map<String, RegionHolder> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final List<ProjectionCompletion> projectionCompletions = new ArrayList<>();
    private volatile int activeLayerY = Integer.MIN_VALUE;

    private CaveMapManager() {
    }

    public static CaveMapManager getInstance() {
        return INSTANCE;
    }

    public long getLayerGeneration() {
        return layerGeneration.get();
    }

    public boolean isLayerGenerationCurrent(long generation, int layerY) {
        return generation == layerGeneration.get()
                && com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                        CaveView.LAYERED, layerY)
                == com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                        CaveView.LAYERED, activeLayerY);
    }

    public synchronized void setBaseDirectory(File directory) {
        setBaseDirectory(directory, null);
    }

    public synchronized void setBaseDirectory(File directory, File surfaceDirectory) {
        deferProjectionCompletions();
        pipeline.setCacheDirectory(directory == null ? null : new File(directory, "v4_tiles"));
        clearCompatRegions();
        layerGeneration.incrementAndGet();
    }

    public synchronized void setActiveLayer(int layerY) {
        if (activeLayerY == layerY) return;
        int previousBand = com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                CaveView.LAYERED, activeLayerY);
        int nextBand = com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                CaveView.LAYERED, layerY);
        activeLayerY = layerY;
        if (previousBand != nextBand) {
            deferProjectionCompletions();
            // A different band has its own retained texture hierarchy. Compatibility
            // snapshots are switched, never used to clear the real page atlas.
            clearCompatRegions();
            layerGeneration.incrementAndGet();
        }
        CaveTextureManager.getInstance().onLayerActivated(layerY);
    }

    public int getActiveLayerY() {
        return activeLayerY;
    }

    public void requestVisibleRegion(int layerY, int rx, int rz) {
        setActiveLayer(layerY);
        pipeline.requestRegionLoad(rx, rz);
        CaveTextureManager.getInstance().requestVisibleRegion(layerY, rx, rz);
        materialize(layerY, rx, rz, true);
    }

    /**
     * M2 manager adapter. The work graph remains RUNNING until the archive
     * backing this compatibility view becomes usable; requesting a disk read is
     * not treated as completion.
     */
    public boolean requestVisibleRegion(MapWorkKey key, long revision, int layerY) {
        if (key == null || !isLayerGenerationCurrent(revision, layerY)) return false;
        requestVisibleRegion(layerY, key.regionX(), key.regionZ());
        CaveRegion region = getRegion(key.regionX(), key.regionZ(), false);
        if (region != null && region.isLoaded()) {
            MapWorkGraph.getInstance().complete(key, revision);
            return true;
        }
        if (!pipeline.hasRegionData(key.regionX(), key.regionZ())) {
            // No archive entry means this is a known-empty compatibility view,
            // not a request that should spin forever waiting for pixels.
            MapWorkGraph.getInstance().complete(key, revision);
            return true;
        }
        synchronized (projectionCompletions) {
            projectionCompletions.add(new ProjectionCompletion(key, revision, layerY));
        }
        return true;
    }

    /** Called once per client tick after the cave pipeline has advanced its IO. */
    public void tickWorkGraphCompletions() {
        List<ProjectionCompletion> pending;
        synchronized (projectionCompletions) {
            if (projectionCompletions.isEmpty()) return;
            pending = new ArrayList<>(projectionCompletions);
            projectionCompletions.clear();
        }
        for (ProjectionCompletion completion : pending) {
            if (!isLayerGenerationCurrent(completion.revision(), completion.layerY())) {
                MapWorkGraph.getInstance().defer(completion.key());
                continue;
            }
            int rx = completion.key().regionX();
            int rz = completion.key().regionZ();
            CaveRegion region = materialize(completion.layerY(), rx, rz, false);
            if ((region != null && region.isLoaded()) || !pipeline.hasRegionData(rx, rz)) {
                MapWorkGraph.getInstance().complete(completion.key(), completion.revision());
            } else {
                synchronized (projectionCompletions) {
                    projectionCompletions.add(completion);
                }
            }
        }
    }

    /** Legacy direct-pixel writes are intentionally retired by the tile pipeline. */
    public void setColor(int blockX, int blockZ, int abgrColor) {
        markRegionDirty(blockX >> 9, blockZ >> 9);
    }

    public void markRegionDirty(int rx, int rz) {
        CaveTextureManager.getInstance().markRegionTextureDirty(activeLayerY, rx, rz);
    }

    public int getColor(int blockX, int blockZ) {
        if (activeLayerY == Integer.MIN_VALUE) return 0;
        return pipeline.getColor(CaveView.LAYERED, activeLayerY, blockX, blockZ);
    }

    public CaveRegion getRegion(int rx, int rz, boolean loadIfMissing) {
        return materialize(activeLayerY, rx, rz, loadIfMissing);
    }

    public boolean hasRegionFile(int rx, int rz) {
        return pipeline.hasRegionData(rx, rz);
    }

    public boolean isRegionLoaded(int rx, int rz) {
        return pipeline.isRegionLoaded(rx, rz);
    }

    public void tickSave() {
        pipeline.tickSave();
    }

    public synchronized void flushAndClear() {
        deferProjectionCompletions();
        pipeline.flushAndClear();
        clearCompatRegions();
        layerGeneration.incrementAndGet();
    }

    public synchronized void flushDataForDimensionSwitch() {
        deferProjectionCompletions();
        pipeline.flushForDimensionSwitch();
        clearCompatRegions();
        layerGeneration.incrementAndGet();
    }

    public synchronized void clearCache() {
        deferProjectionCompletions();
        pipeline.clearRuntime(true);
        clearCompatRegions();
        layerGeneration.incrementAndGet();
    }

    public synchronized void deactivate() {
        deferProjectionCompletions();
        activeLayerY = Integer.MIN_VALUE;
        clearCompatRegions();
        layerGeneration.incrementAndGet();
    }

    private CaveRegion materialize(int layerY, int rx, int rz, boolean requestLoad) {
        if (layerY == Integer.MIN_VALUE) return null;
        if (requestLoad) pipeline.requestRegionLoad(rx, rz);
        String key = com.velorise.simplemap.client.cave.DenseCaveTile.normalizeLayer(
                CaveView.LAYERED, layerY) + ":" + rx + ',' + rz;
        long sourceRevision = pipeline.getRegionRevision(rx, rz);
        synchronized (regions) {
            RegionHolder holder = regions.get(key);
            if (holder != null && holder.sourceRevision == sourceRevision) return holder.region;
            if (!pipeline.isRegionLoaded(rx, rz) && sourceRevision == 0L) return holder == null ? null : holder.region;
            CaveTileRepository.ResolvedRegion resolved = pipeline.resolveRegion(
                    CaveView.LAYERED, layerY, rx, rz);
            CaveRegion region = new CaveRegion(rx, rz, layerGeneration.get());
            region.replacePixels(resolved.pixels());
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

    private record RegionHolder(CaveRegion region, long sourceRevision) {
    }

    private record ProjectionCompletion(MapWorkKey key, long revision, int layerY) {
    }
}
