package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.CaveMode;
import com.velorise.simplemap.client.FullCaveMapManager;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapWorkScheduler;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Shared persistent source of truth for Layered and Full Cave views. */
public final class CaveTileRepository {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final CaveTileRepository INSTANCE = new CaveTileRepository();
    private static final int MAX_LOADED_TILES = 8192;
    private static final int MAX_DISPLAY_TILES = 8192;
    private static final int DISPLAY_SAVE_BATCH_SIZE = 16;
    private static final int PAGE_SIZE = 64;
    private static final int PROJECTION_SIZE = PAGE_SIZE + 2;
    /** Four central chunks plus a one-chunk border on every side. */
    private static final int DISPLAY_TILE_WINDOW = 6;
    private static final int MAX_RESOLVED_PAGE_CACHE = 128;
    private static final int SAVE_BATCH_SIZE = 8;
    private static final int REGION_COMPACTION_SAVE_INTERVAL = 64;
    /** Disk writes are pull-driven and bounded. Saturation keeps data dirty and
     * retries later instead of throwing through Minecraft's client tick. */
    private static final int MAX_PENDING_RAW_SAVE_BATCHES = 2;
    private static final int MAX_PENDING_DISPLAY_SAVE_BATCHES = 1;
    private static final int MAX_PENDING_COMPACTIONS = 1;
    private static final long MIN_SAVE_RETRY_MS = 10L;
    private static final long MAX_SAVE_RETRY_MS = 500L;
    /** Small direct-projection workspace reused by each page-build worker. */
    private static final ThreadLocal<ProjectionWorkspace> PROJECTION_WORKSPACE =
            ThreadLocal.withInitial(ProjectionWorkspace::new);

    private final Map<Long, CaveChunkTile> tiles = new LinkedHashMap<>(256, 0.75f, true);
    /** Exact 2D output used by Layered/Full rendering; no run graph is involved. */
    private final Map<DenseCaveTileKey, DenseCaveTile> displayTiles =
            new LinkedHashMap<>(256, 0.75f, true);
    private final Set<DenseCaveTileKey> indexedDisplayTiles = new HashSet<>();
    private final Set<DenseCaveTileKey> dirtyDisplayTiles = new HashSet<>();
    /** Exact Top-Y for a known-empty projection inside a retained band. */
    private final Map<DenseCaveTileKey, Integer> absentDisplayTiles = new HashMap<>();
    /**
     * Soft-invalidated dense tiles remain renderable until a complete replacement
     * is committed. This mirrors Xaero's incremental map updates: refresh never
     * turns already known terrain into a temporary black square.
     */
    private final Set<DenseCaveTileKey> staleDisplayTiles = new HashSet<>();
    private final Map<DenseCaveTileKey, CaveDisplayRegionStore.RecordPointer>
            displayRecords = new HashMap<>();
    private final Map<DenseCaveTileKey, CompletableFuture<?>>
            pendingDisplayLoads = new HashMap<>();
    private final Map<DenseCaveTileKey, CompletableFuture<?>>
            pendingDisplaySaves = new HashMap<>();
    private final Set<Long> indexedTiles = new HashSet<>();
    private final Map<Long, Integer> indexedRegionCounts = new HashMap<>();
    /** Latest random-access record for each tile stored in a packed .cvr region file. */
    private final Map<Long, CaveRegionStore.RecordPointer> regionRecords = new HashMap<>();
    private final Map<Long, Long> pageRevisions = new HashMap<>();
    private final Map<Long, Long> regionRevisions = new HashMap<>();
    private final Map<Long, CompletableFuture<?>> pendingLoads = new HashMap<>();
    private final Map<Long, CompletableFuture<?>> pendingSaves = new HashMap<>();
    private final Map<Long, CompletableFuture<?>> pendingCompactions = new HashMap<>();
    private final Map<Long, Integer> regionSaveCounts = new HashMap<>();
    private final Map<PageCacheKey, ResolvedPage> resolvedPageCache =
            new LinkedHashMap<>(32, 0.75f, true);
    private final List<TileListener> listeners = new CopyOnWriteArrayList<>();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();
    private final AtomicLong generation = new AtomicLong(1L);
    private long nextSaveAdmissionNanos;
    private long saveRetryDelayMs = MIN_SAVE_RETRY_MS;

    private volatile File directory;

    private CaveTileRepository() {
    }

    public static CaveTileRepository getInstance() {
        return INSTANCE;
    }

    public long generation() {
        return generation.get();
    }

    public boolean isGenerationCurrent(long value) {
        return generation.get() == value;
    }

    public void addListener(TileListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void setDirectory(File requested) {
        File previousDirectory;
        List<CaveChunkTile.Snapshot> snapshots;
        List<DenseCaveTile> displaySnapshots;
        List<CompletableFuture<?>> inFlight;
        List<CompletableFuture<?>> displayInFlight;
        synchronized (this) {
            if (requested != null && requested.equals(directory)) return;
            previousDirectory = directory;
            snapshots = collectDirtySnapshotsLocked();
            displaySnapshots = collectDirtyDisplayTilesLocked();
            inFlight = new ArrayList<>(pendingSaves.values());
            displayInFlight = new ArrayList<>(pendingDisplaySaves.values());

            generation.incrementAndGet();
            tiles.clear();
            displayTiles.clear();
            indexedDisplayTiles.clear();
            dirtyDisplayTiles.clear();
            absentDisplayTiles.clear();
            staleDisplayTiles.clear();
            displayRecords.clear();
            pendingDisplayLoads.clear();
            pendingDisplaySaves.clear();
            pageRevisions.clear();
            regionRevisions.clear();
            pendingLoads.clear();
            pendingSaves.clear();
            pendingCompactions.clear();
            regionSaveCounts.clear();
            nextSaveAdmissionNanos = 0L;
            saveRetryDelayMs = MIN_SAVE_RETRY_MS;
            resolvedPageCache.clear();
            indexedTiles.clear();
            indexedRegionCounts.clear();
            regionRecords.clear();

            directory = requested;
            if (directory != null && !directory.exists() && !directory.mkdirs()) {
                LOGGER.warn("Could not create cave tile directory {}", directory);
            }
            rebuildIndex();
            generation.incrementAndGet();
        }
        flushSnapshotsAfter(previousDirectory, snapshots, inFlight);
        flushDisplayTilesAfter(previousDirectory, displaySnapshots, displayInFlight);
    }

    public synchronized File directory() {
        return directory;
    }

    public CaveChunkTile getOrCreateLiveTile(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        synchronized (this) {
            CaveChunkTile existing = tiles.get(key);
            if (existing != null) return existing;
            CaveChunkTile created = new CaveChunkTile(chunkX, chunkZ, true);
            tiles.put(key, created);
            trimLoadedTiles();
            if (indexedTiles.contains(key)) requestTileLoadLocked(chunkX, chunkZ, key);
            return created;
        }
    }

    public synchronized CaveChunkTile getLoadedTile(int chunkX, int chunkZ) {
        return tiles.get(pack(chunkX, chunkZ));
    }

    public synchronized int loadedTileCount() {
        return tiles.size();
    }

    public synchronized int loadedDisplayTileCount() {
        return displayTiles.size();
    }

    /**
     * Returns true when a tile of at least the requested authority is resident, or
     * when the request may be satisfied by the persistent dense cache.
     */
    public synchronized boolean hasDisplayTileSource(CaveView view, int layerY,
            int chunkX, int chunkZ, DenseCaveTile.Source minimumSource) {
        DenseCaveTileKey key = new DenseCaveTileKey(chunkX, chunkZ, view, layerY);
        return hasDisplayTileSourceLocked(key, minimumSource);
    }

    /**
     * Scheduler-facing authority check. A stale tile is still a valid render
     * fallback, but it must not suppress a replacement scan/read.
     */
    public synchronized boolean hasFreshDisplayTileSource(CaveView view, int layerY,
            int chunkX, int chunkZ, DenseCaveTile.Source minimumSource) {
        DenseCaveTileKey key = new DenseCaveTileKey(chunkX, chunkZ, view, layerY);
        if (staleDisplayTiles.contains(key)) return false;
        DenseCaveTile loaded = displayTiles.get(key);
        if (loaded != null) {
            return loaded.source().rank() >= minimumSource.rank()
                    && (view == CaveView.FULL || loaded.projectionTopY() == layerY);
        }
        // A layered record index identifies only the retained 16-block band. The
        // exact Top-Y is stored in the payload, so it must be loaded before it can
        // suppress a replacement projection.
        if (view != CaveView.FULL) return false;
        return minimumSource.rank() <= DenseCaveTile.Source.WORLD_SAVE.rank()
                && (indexedDisplayTiles.contains(key)
                        || pendingDisplayLoads.containsKey(key));
    }

    /**
     * Returns true only when every central 16x16 tile of a 64x64 page is already
     * authoritative (or explicitly known empty). World-save scheduling uses this
     * page-level check so publication is coherent instead of exposing isolated
     * Minecraft chunks in worker completion order.
     */
    public synchronized boolean hasFreshDisplayPageSource(CaveView view, int layerY,
            int globalPageX, int globalPageZ, DenseCaveTile.Source minimumSource) {
        int normalized = DenseCaveTile.normalizeLayer(view, layerY);
        int firstChunkX = globalPageX << 2;
        int firstChunkZ = globalPageZ << 2;
        for (int localX = 0; localX < 4; localX++) {
            for (int localZ = 0; localZ < 4; localZ++) {
                DenseCaveTileKey key = new DenseCaveTileKey(
                        firstChunkX + localX, firstChunkZ + localZ, view, normalized);
                if (staleDisplayTiles.contains(key)) return false;
                if (absentDisplayTiles.getOrDefault(key, Integer.MAX_VALUE) == layerY) continue;
                DenseCaveTile loaded = displayTiles.get(key);
                if (loaded == null || loaded.source().rank() < minimumSource.rank()) return false;
                if (view != CaveView.FULL && loaded.projectionTopY() != layerY) return false;
            }
        }
        return true;
    }

    private boolean hasDisplayTileSourceLocked(DenseCaveTileKey key,
            DenseCaveTile.Source minimumSource) {
        DenseCaveTile loaded = displayTiles.get(key);
        if (loaded != null && loaded.source().rank() >= minimumSource.rank()) return true;
        return minimumSource.rank() <= DenseCaveTile.Source.WORLD_SAVE.rank()
                && (indexedDisplayTiles.contains(key)
                        || pendingDisplayLoads.containsKey(key));
    }

    /**
     * Marks one mode/layer tile for a non-destructive rebuild. The existing dense
     * tile and its uploaded page stay visible until a complete replacement arrives.
     */
    public synchronized void markDisplayTileStale(CaveView view, int layerY,
            int chunkX, int chunkZ) {
        DenseCaveTileKey key = new DenseCaveTileKey(chunkX, chunkZ, view, layerY);
        staleDisplayTiles.add(key);
        absentDisplayTiles.remove(key);
    }

    public synchronized boolean isDisplayTileStale(CaveView view, int layerY,
            int chunkX, int chunkZ) {
        return staleDisplayTiles.contains(
                new DenseCaveTileKey(chunkX, chunkZ, view, layerY));
    }

    /**
     * Marks every already-known cave projection in a chunk range stale, regardless
     * of cave view or Layered Top-Y. This is used after teleport/world transitions,
     * where the automatic layer may intentionally remain frozen for a few ticks.
     * Existing pixels remain resident as fallback until a stable live transaction
     * replaces the currently requested projection.
     */
    public synchronized int markDisplayRangeStaleAllLayers(
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            int maximumTiles) {
        int limit = Math.max(0, maximumTiles);
        int marked = 0;
        for (DenseCaveTileKey key : displayTiles.keySet()) {
            if (marked >= limit) break;
            if (key.chunkX() < minChunkX || key.chunkX() > maxChunkX
                    || key.chunkZ() < minChunkZ || key.chunkZ() > maxChunkZ) continue;
            if (staleDisplayTiles.add(key)) marked++;
        }
        if (marked >= limit) return marked;
        for (DenseCaveTileKey key : indexedDisplayTiles) {
            if (marked >= limit) break;
            if (key.chunkX() < minChunkX || key.chunkX() > maxChunkX
                    || key.chunkZ() < minChunkZ || key.chunkZ() > maxChunkZ) continue;
            if (staleDisplayTiles.add(key)) marked++;
        }
        return marked;
    }

    /** Marks only already known tiles in a viewport; unknown world areas are not
     * added to the stale set. The old pixels remain available as visual fallback. */
    public synchronized int markDisplayRangeStale(CaveView view, int layerY,
            int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
            int maximumTiles) {
        int normalized = DenseCaveTile.normalizeLayer(view, layerY);
        int limit = Math.max(0, maximumTiles);
        int marked = 0;
        for (DenseCaveTileKey key : displayTiles.keySet()) {
            if (marked >= limit) break;
            if (key.view() != view || key.layerY() != normalized
                    || key.chunkX() < minChunkX || key.chunkX() > maxChunkX
                    || key.chunkZ() < minChunkZ || key.chunkZ() > maxChunkZ) continue;
            if (staleDisplayTiles.add(key)) marked++;
        }
        if (marked >= limit) return marked;
        for (DenseCaveTileKey key : indexedDisplayTiles) {
            if (marked >= limit) break;
            if (key.view() != view || key.layerY() != normalized
                    || key.chunkX() < minChunkX || key.chunkX() > maxChunkX
                    || key.chunkZ() < minChunkZ || key.chunkZ() > maxChunkZ) continue;
            if (staleDisplayTiles.add(key)) marked++;
        }
        return marked;
    }

    public boolean commitDisplayTile(DenseCaveTile tile, long expectedGeneration) {
        if (tile == null) return false;
        synchronized (this) {
            if (!isGenerationCurrent(expectedGeneration)) return false;
            DenseCaveTileKey key = DenseCaveTileKey.of(tile);
            DenseCaveTile current = displayTiles.get(key);
            if (current != null && current.source().rank() > tile.source().rank()) return false;
            if (current != null && current.source() == tile.source()
                    && current.revision() >= tile.revision()) return false;
            displayTiles.put(key, tile);
            absentDisplayTiles.remove(key, tile.projectionTopY());
            staleDisplayTiles.remove(key);
            if (tile.source() != DenseCaveTile.Source.DISK) dirtyDisplayTiles.add(key);
            touchLocked(tile.chunkX(), tile.chunkZ(), tile.revision());
            trimDisplayTilesLocked();
            return true;
        }
    }

    /**
     * Atomically publishes one 64x64 page reconstructed from up to sixteen Anvil
     * chunks. Existing higher-authority LIVE tiles are retained. Missing chunks are
     * marked known-empty in the same transaction, allowing the page builder to
     * distinguish EMPTY from UNKNOWN without displaying a checkerboard of partial
     * worker results.
     */
    public synchronized boolean commitDisplayPage(List<DenseCaveTile> replacements,
            CaveView view, int layerY, int firstChunkX, int firstChunkZ,
            boolean[] knownAbsent, long expectedGeneration) {
        if (!isGenerationCurrent(expectedGeneration)) return false;
        int normalized = DenseCaveTile.normalizeLayer(view, layerY);
        boolean changed = false;

        if (replacements != null) {
            for (DenseCaveTile tile : replacements) {
                if (tile == null || tile.view() != view || tile.layerY() != normalized) continue;
                DenseCaveTileKey key = DenseCaveTileKey.of(tile);
                DenseCaveTile current = displayTiles.get(key);
                if (current != null && current.source().rank() > tile.source().rank()) continue;
                if (current != null && current.source() == tile.source()
                        && current.revision() >= tile.revision()) continue;
                displayTiles.put(key, tile);
                absentDisplayTiles.remove(key, tile.projectionTopY());
                staleDisplayTiles.remove(key);
                if (tile.source() != DenseCaveTile.Source.DISK) dirtyDisplayTiles.add(key);
                touchLocked(tile.chunkX(), tile.chunkZ(), tile.revision());
                changed = true;
            }
        }

        if (knownAbsent != null) {
            int count = Math.min(16, knownAbsent.length);
            for (int order = 0; order < count; order++) {
                if (!knownAbsent[order]) continue;
                int localX = order / 4;
                int localZ = order % 4;
                int chunkX = firstChunkX + localX;
                int chunkZ = firstChunkZ + localZ;
                DenseCaveTileKey key = new DenseCaveTileKey(
                        chunkX, chunkZ, view, normalized);
                DenseCaveTile current = displayTiles.get(key);
                if (current != null && (view == CaveView.FULL
                        || current.projectionTopY() == layerY)
                        && current.source().rank() >= DenseCaveTile.Source.WORLD_SAVE.rank()) continue;
                Integer previousAbsent = absentDisplayTiles.put(key, layerY);
                if (previousAbsent == null || previousAbsent != layerY) {
                    staleDisplayTiles.remove(key);
                    touchLocked(chunkX, chunkZ, System.nanoTime());
                    changed = true;
                }
            }
        }
        trimDisplayTilesLocked();
        return changed;
    }

    public synchronized void invalidateDisplayTile(int chunkX, int chunkZ) {
        displayTiles.entrySet().removeIf(entry ->
                entry.getKey().chunkX() == chunkX && entry.getKey().chunkZ() == chunkZ);
        dirtyDisplayTiles.removeIf(key -> key.chunkX() == chunkX && key.chunkZ() == chunkZ);
        absentDisplayTiles.keySet().removeIf(key -> key.chunkX() == chunkX && key.chunkZ() == chunkZ);
        staleDisplayTiles.removeIf(key -> key.chunkX() == chunkX && key.chunkZ() == chunkZ);
        indexedDisplayTiles.removeIf(key -> key.chunkX() == chunkX && key.chunkZ() == chunkZ);
        displayRecords.entrySet().removeIf(entry ->
                entry.getKey().chunkX() == chunkX && entry.getKey().chunkZ() == chunkZ);
        touchLocked(chunkX, chunkZ, System.nanoTime());
    }

    public synchronized void markDisplayTileAbsent(CaveView view, int layerY,
            int chunkX, int chunkZ, long expectedGeneration) {
        if (!isGenerationCurrent(expectedGeneration)) return;
        DenseCaveTileKey key = new DenseCaveTileKey(chunkX, chunkZ, view, layerY);
        DenseCaveTile current = displayTiles.get(key);
        if (current != null && (view == CaveView.FULL
                || current.projectionTopY() == layerY)) return;
        absentDisplayTiles.put(key, layerY);
        touchLocked(chunkX, chunkZ, System.nanoTime());
    }

    /**
     * True when a complete live/cache tile or a pending .cvr read already owns this
     * chunk. A partially populated runtime tile is deliberately not authoritative:
     * the world-save reader may still fill its missing columns.
     */
    public synchronized boolean hasTileSource(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        CaveChunkTile tile = tiles.get(key);
        return (tile != null && tile.isComplete())
                || indexedTiles.contains(key) || pendingLoads.containsKey(key);
    }

    /**
     * Publishes a complete read-only world-save reconstruction. Missing columns are
     * filled, but live-scanned columns are never replaced. Imported data remains
     * dirty and is therefore batched into the normal .cvr save path.
     */
    public boolean mergeWorldSaveTile(CaveChunkTile.Snapshot snapshot,
            long expectedGeneration) {
        if (snapshot == null) return false;
        long key = pack(snapshot.chunkX(), snapshot.chunkZ());

        /* Build and populate a new tile before exposing it through the repository.
         * This prevents trimLoadedTiles() from retiring a momentarily empty tile. */
        CaveChunkTile candidate = new CaveChunkTile(
                snapshot.chunkX(), snapshot.chunkZ(), false);
        if (!candidate.mergeWorldSave(snapshot)) return false;

        while (true) {
            CaveChunkTile tile;
            synchronized (this) {
                if (!isGenerationCurrent(expectedGeneration)) return false;
                tile = tiles.get(key);
                if (tile == null) {
                    tiles.put(key, candidate);
                    touchLocked(candidate.chunkX(), candidate.chunkZ(),
                            candidate.revision());
                    trimLoadedTiles();
                    return true;
                }
            }

            boolean changed = tile.mergeWorldSave(snapshot);
            if (!changed) return false;
            synchronized (this) {
                if (!isGenerationCurrent(expectedGeneration)) return false;
                // A concurrent trim/reload may have replaced the tile. Retry against
                // the currently published instance instead of touching an orphan.
                if (tiles.get(key) != tile) continue;
                touchLocked(tile.chunkX(), tile.chunkZ(), tile.revision());
                return true;
            }
        }
    }

    public synchronized boolean isColumnScanned(int blockX, int blockZ) {
        CaveChunkTile tile = tiles.get(pack(blockX >> 4, blockZ >> 4));
        return tile != null && tile.isColumnScanned(blockX & 15, blockZ & 15);
    }

    public void invalidateTile(int chunkX, int chunkZ) {
        CaveChunkTile tile = getOrCreateLiveTile(chunkX, chunkZ);
        tile.invalidateAll();
        touch(chunkX, chunkZ, tile.revision());
    }

    /** Invalidates an archive tile only when it is already resident. */
    public boolean invalidateLoadedTile(int chunkX, int chunkZ) {
        CaveChunkTile tile;
        synchronized (this) {
            tile = tiles.get(pack(chunkX, chunkZ));
        }
        if (tile == null) return false;
        tile.invalidateAll();
        touch(chunkX, chunkZ, tile.revision());
        return true;
    }

    public boolean invalidateColumn(int blockX, int blockZ) {
        CaveChunkTile tile = getOrCreateLiveTile(blockX >> 4, blockZ >> 4);
        boolean changed = tile.invalidateColumn(blockX & 15, blockZ & 15);
        if (changed) touch(tile.chunkX(), tile.chunkZ(), tile.revision());
        return changed;
    }

    /** Queue a non-destructive live-world recheck while retaining current pixels. */
    public boolean requestColumnRecheck(int blockX, int blockZ) {
        CaveChunkTile tile = getLoadedTile(blockX >> 4, blockZ >> 4);
        if (tile == null || !tile.isComplete()) return false;
        return tile.requestRecheckColumn(blockX & 15, blockZ & 15);
    }

    public boolean commitColumn(CaveChunkTile tile, int columnIndex, CaveColumnData data) {
        boolean changed = tile.commitColumn(columnIndex, data);
        if (changed) touch(tile.chunkX(), tile.chunkZ(), tile.revision());
        return changed;
    }

    /**
     * Commits one column without immediately invalidating page/region projections.
     *
     * The scheduler uses this inside a coherent tile burst and publishes one combined
     * tile change after the burst. This avoids hundreds of page revision increments,
     * listener walks and stale texture builds while a 16x16 tile is being completed.
     */
    public boolean commitColumnDeferred(CaveChunkTile tile, int columnIndex,
            CaveColumnData data) {
        return tile.commitColumn(columnIndex, data);
    }

    /** Publishes all deferred column commits from one scheduler burst. */
    public void publishTileChanges(CaveChunkTile tile) {
        if (tile != null) touch(tile.chunkX(), tile.chunkZ(), tile.revision());
    }

    public CaveColumnData.Candidate getCandidate(int blockX, int blockZ,
            int maximumY, int minimumY) {
        CaveChunkTile tile;
        synchronized (this) {
            tile = tiles.get(pack(blockX >> 4, blockZ >> 4));
            if (tile == null) {
                requestTileLoadLocked(blockX >> 4, blockZ >> 4,
                        pack(blockX >> 4, blockZ >> 4));
                return null;
            }
        }
        CaveColumnData column = tile.getColumn(blockX & 15, blockZ & 15);
        return column == null ? null : column.firstVisibleLayeredCandidate(maximumY, minimumY);
    }

    public CaveColumnData.Candidate getFullCandidate(int blockX, int blockZ) {
        CaveChunkTile tile;
        synchronized (this) {
            tile = tiles.get(pack(blockX >> 4, blockZ >> 4));
            if (tile == null) {
                requestTileLoadLocked(blockX >> 4, blockZ >> 4,
                        pack(blockX >> 4, blockZ >> 4));
                return null;
            }
        }
        CaveColumnData column = tile.getColumn(blockX & 15, blockZ & 15);
        return column == null ? null : column.firstVisibleFullCandidate();
    }

    public int getColor(CaveView view, int layerY, Level level, int blockX, int blockZ) {
        DenseCaveTile dense = getDisplayTile(view, layerY, blockX >> 4, blockZ >> 4);
        if (dense != null) return dense.color(blockX & 15, blockZ & 15);
        CaveColumnData.Candidate candidate = resolveCandidate(view, layerY, level, blockX, blockZ);
        return candidate == null ? 0 : candidate.color();
    }

    public int getHeight(CaveView view, int layerY, Level level, int blockX, int blockZ) {
        DenseCaveTile dense = getDisplayTile(view, layerY, blockX >> 4, blockZ >> 4);
        if (dense != null) return dense.floorY(blockX & 15, blockZ & 15);
        CaveColumnData.Candidate candidate = resolveCandidate(view, layerY, level, blockX, blockZ);
        return candidate == null ? FullCaveMapManager.NO_SURFACE : candidate.bottomY();
    }

    synchronized DenseCaveTile getLoadedDisplayTile(CaveView view, int layerY,
            int chunkX, int chunkZ) {
        return displayTiles.get(new DenseCaveTileKey(chunkX, chunkZ, view, layerY));
    }

    synchronized boolean isCurrentDisplayTileRevision(CaveView view, int layerY,
            int chunkX, int chunkZ, long revision, DenseCaveTile.Source source) {
        DenseCaveTile current = displayTiles.get(
                new DenseCaveTileKey(chunkX, chunkZ, view, layerY));
        return current != null && current.revision() == revision
                && current.source() == source;
    }

    private DenseCaveTile getDisplayTile(CaveView view, int layerY,
            int chunkX, int chunkZ) {
        DenseCaveTileKey key = new DenseCaveTileKey(chunkX, chunkZ, view, layerY);
        synchronized (this) {
            DenseCaveTile tile = displayTiles.get(key);
            if (tile == null && indexedDisplayTiles.contains(key)) {
                requestDisplayTileLoadLocked(key);
            }
            return tile;
        }
    }

    private CaveColumnData.Candidate resolveCandidate(CaveView view, int layerY,
            Level level, int blockX, int blockZ) {
        if (view == CaveView.FULL) return getFullCandidate(blockX, blockZ);
        int maximum = level == null ? layerY : CaveMode.getScanMaximum(level, layerY);
        int minimum = level == null ? layerY - 31 : CaveMode.getScanMinimum(level, layerY);
        return getCandidate(blockX, blockZ, maximum, minimum);
    }

    public void requestPageLoad(int globalPageX, int globalPageZ) {
        int firstChunkX = (globalPageX << 2) - 1;
        int firstChunkZ = (globalPageZ << 2) - 1;
        synchronized (this) {
            for (int dz = 0; dz < 6; dz++) {
                for (int dx = 0; dx < 6; dx++) {
                    int chunkX = firstChunkX + dx;
                    int chunkZ = firstChunkZ + dz;
                    long key = pack(chunkX, chunkZ);
                    if (!tiles.containsKey(key) && indexedTiles.contains(key)) {
                        requestTileLoadLocked(chunkX, chunkZ, key);
                    }
                }
            }
        }
    }

    /**
     * Requests the exact 64x64 page admitted by the visible scheduler plus the
     * one-chunk halo needed by cave projection. Unlike the range method, this does
     * not turn sparse admitted pages into a large rectangular IO request.
     */
    public void requestDisplayPageLoad(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        int firstChunkX = (globalPageX << 2) - 1;
        int lastChunkX = (globalPageX << 2) + 4;
        int firstChunkZ = (globalPageZ << 2) - 1;
        int lastChunkZ = (globalPageZ << 2) + 4;
        synchronized (this) {
            for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
                for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
                    DenseCaveTileKey key = new DenseCaveTileKey(
                            chunkX, chunkZ, view, normalizedLayer);
                    if (!displayTiles.containsKey(key)
                            && indexedDisplayTiles.contains(key)) {
                        requestDisplayTileLoadLocked(key);
                    }
                }
            }
        }
        requestPageRangeLoad(globalPageX, globalPageX, globalPageZ, globalPageZ);
    }

    public void requestDisplayPageRangeLoad(CaveView view, int layerY,
            int minGlobalPageX, int maxGlobalPageX,
            int minGlobalPageZ, int maxGlobalPageZ) {
        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        int firstChunkX = (Math.min(minGlobalPageX, maxGlobalPageX) << 2) - 1;
        int lastChunkX = (Math.max(minGlobalPageX, maxGlobalPageX) << 2) + 4;
        int firstChunkZ = (Math.min(minGlobalPageZ, maxGlobalPageZ) << 2) - 1;
        int lastChunkZ = (Math.max(minGlobalPageZ, maxGlobalPageZ) << 2) + 4;
        synchronized (this) {
            for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
                for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
                    DenseCaveTileKey key = new DenseCaveTileKey(
                            chunkX, chunkZ, view, normalizedLayer);
                    if (!displayTiles.containsKey(key)
                            && indexedDisplayTiles.contains(key)) {
                        requestDisplayTileLoadLocked(key);
                    }
                }
            }
        }
        // Keep old .cvr history available as a multiplayer/migration fallback.
        requestPageRangeLoad(minGlobalPageX, maxGlobalPageX,
                minGlobalPageZ, maxGlobalPageZ);
    }

    /**
     * Requests the union of all tile borders needed by a rectangle of 64x64 pages.
     * Adjacent pages overlap by five of their six chunk rows/columns, so checking the
     * rectangle once is substantially cheaper than issuing one 6x6 request per page.
     */
    public void requestPageRangeLoad(int minGlobalPageX, int maxGlobalPageX,
            int minGlobalPageZ, int maxGlobalPageZ) {
        int firstChunkX = (Math.min(minGlobalPageX, maxGlobalPageX) << 2) - 1;
        int lastChunkX = (Math.max(minGlobalPageX, maxGlobalPageX) << 2) + 4;
        int firstChunkZ = (Math.min(minGlobalPageZ, maxGlobalPageZ) << 2) - 1;
        int lastChunkZ = (Math.max(minGlobalPageZ, maxGlobalPageZ) << 2) + 4;
        synchronized (this) {
            for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
                for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
                    long key = pack(chunkX, chunkZ);
                    if (!tiles.containsKey(key) && indexedTiles.contains(key)) {
                        requestTileLoadLocked(chunkX, chunkZ, key);
                    }
                }
            }
        }
    }

    public void requestRegionLoad(int regionX, int regionZ) {
        int firstChunkX = regionX << 5;
        int firstChunkZ = regionZ << 5;
        synchronized (this) {
            for (int dz = 0; dz < 32; dz++) {
                for (int dx = 0; dx < 32; dx++) {
                    int chunkX = firstChunkX + dx;
                    int chunkZ = firstChunkZ + dz;
                    long key = pack(chunkX, chunkZ);
                    if (!tiles.containsKey(key) && indexedTiles.contains(key)) {
                        requestTileLoadLocked(chunkX, chunkZ, key);
                    }
                }
            }
        }
    }

    public synchronized boolean hasRegionData(int regionX, int regionZ) {
        long regionKey = pack(regionX, regionZ);
        if (indexedRegionCounts.getOrDefault(regionKey, 0) > 0) return true;
        for (DenseCaveTileKey key : indexedDisplayTiles) {
            if ((key.chunkX() >> 5) == regionX && (key.chunkZ() >> 5) == regionZ) return true;
        }
        for (CaveChunkTile tile : tiles.values()) {
            if ((tile.chunkX() >> 5) == regionX && (tile.chunkZ() >> 5) == regionZ
                    && tile.hasAnyScannedColumn()) return true;
        }
        for (DenseCaveTile tile : displayTiles.values()) {
            if ((tile.chunkX() >> 5) == regionX && (tile.chunkZ() >> 5) == regionZ) return true;
        }
        return false;
    }

    public synchronized boolean isRegionLoaded(int regionX, int regionZ) {
        for (CaveChunkTile tile : tiles.values()) {
            if ((tile.chunkX() >> 5) == regionX && (tile.chunkZ() >> 5) == regionZ
                    && tile.hasAnyScannedColumn()) return true;
        }
        for (DenseCaveTile tile : displayTiles.values()) {
            if ((tile.chunkX() >> 5) == regionX && (tile.chunkZ() >> 5) == regionZ) return true;
        }
        return false;
    }

    public synchronized long getRegionRevision(int regionX, int regionZ) {
        return regionRevisions.getOrDefault(pack(regionX, regionZ), 0L);
    }

    /**
     * Source revision includes the eight neighbouring pages because relief shading
     * samples a one-block border. A border change must rebuild the adjacent page too.
     */
    public synchronized long getPageRevision(int globalPageX, int globalPageZ) {
        long revision = 0L;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                revision += pageRevisions.getOrDefault(
                        pack(globalPageX + dx, globalPageZ + dz), 0L);
            }
        }
        return revision;
    }

    public ResolvedPage resolvePage(CaveView view, int layerY, Level level,
            int globalPageX, int globalPageZ) {
        ProjectionWorkspace workspace = PROJECTION_WORKSPACE.get();
        CaveChunkTile[] archiveTiles = workspace.pageTiles;
        DenseCaveTile[] denseTiles = workspace.displayTiles;
        boolean[] knownEmptyTiles = workspace.knownEmptyTiles;
        java.util.Arrays.fill(archiveTiles, null);
        java.util.Arrays.fill(denseTiles, null);
        java.util.Arrays.fill(knownEmptyTiles, false);

        int normalizedLayer = DenseCaveTile.normalizeLayer(view, layerY);
        int firstChunkX = (globalPageX << 2) - 1;
        int firstChunkZ = (globalPageZ << 2) - 1;
        boolean complete = true;
        long revision;
        synchronized (this) {
            for (int dz = 0; dz < DISPLAY_TILE_WINDOW; dz++) {
                for (int dx = 0; dx < DISPLAY_TILE_WINDOW; dx++) {
                    int chunkX = firstChunkX + dx;
                    int chunkZ = firstChunkZ + dz;
                    int tileIndex = dz * DISPLAY_TILE_WINDOW + dx;
                    DenseCaveTileKey displayKey = new DenseCaveTileKey(
                            chunkX, chunkZ, view, normalizedLayer);
                    DenseCaveTile dense = displayTiles.get(displayKey);
                    CaveChunkTile archive = tiles.get(pack(chunkX, chunkZ));
                    if (dense != null && view != CaveView.FULL
                            && dense.projectionTopY() != layerY) {
                        // Keep the previous band texture as visual fallback, but do
                        // not declare this page authoritative for the new exact Top-Y.
                        complete = false;
                    }
                    denseTiles[tileIndex] = dense;
                    archiveTiles[tileIndex] = archive;
                    boolean knownEmpty = absentDisplayTiles.getOrDefault(
                            displayKey, Integer.MAX_VALUE) == layerY;
                    knownEmptyTiles[tileIndex] = knownEmpty;

                    boolean central = dx >= 1 && dx <= 4 && dz >= 1 && dz <= 4;
                    if (!central) continue;
                    boolean denseMatches = dense != null && (view == CaveView.FULL
                            || dense.projectionTopY() == layerY);
                    if (denseMatches || knownEmpty) continue;
                    boolean archiveReady = archive != null && archive.isComplete();
                    if (!archiveReady) complete = false;
                }
            }
            revision = getPageRevision(globalPageX, globalPageZ);
        }

        PageCacheKey cacheKey = new PageCacheKey(generation.get(), view,
                normalizedLayer, layerY, globalPageX, globalPageZ, revision);
        if (complete) {
            synchronized (this) {
                ResolvedPage cached = resolvedPageCache.get(cacheKey);
                if (cached != null) {
                    telemetry.recordResolvedPageCacheHit();
                    return cached;
                }
            }
        }
        telemetry.recordResolvedPageCacheMiss();
        long resolveStarted = System.nanoTime();

        int[] pixels = new int[PAGE_SIZE * PAGE_SIZE];
        long[] knownRows = new long[PAGE_SIZE];
        short[] heights = new short[PROJECTION_SIZE * PROJECTION_SIZE];
        short[] topHeights = new short[PAGE_SIZE * PAGE_SIZE];
        byte[] pixelFlags = new byte[PAGE_SIZE * PAGE_SIZE];
        byte[] pixelLight = new byte[PAGE_SIZE * PAGE_SIZE];
        byte[] overlayCounts = new byte[PAGE_SIZE * PAGE_SIZE];
        int[] overlayColors = new int[PAGE_SIZE * PAGE_SIZE * DenseCaveTile.MAX_OVERLAYS];
        byte[] overlayAlpha = new byte[overlayColors.length];
        short[] overlayY = new short[overlayColors.length];
        byte[] overlayLight = new byte[overlayColors.length];
        byte[] overlayFlags = new byte[overlayColors.length];
        java.util.Arrays.fill(heights, FullCaveMapManager.NO_SURFACE);
        java.util.Arrays.fill(topHeights, FullCaveMapManager.NO_SURFACE);
        java.util.Arrays.fill(overlayY, FullCaveMapManager.NO_SURFACE);

        int borderedOriginX = (globalPageX << 6) - 1;
        int borderedOriginZ = (globalPageZ << 6) - 1;
        int maximum = view == CaveView.FULL ? Integer.MAX_VALUE
                : level == null ? layerY : CaveMode.getScanMaximum(level, layerY);
        int minimum = view == CaveView.FULL ? Integer.MIN_VALUE
                : level == null ? layerY - CaveDisplayProjector.LAYER_DEPTH + 1
                        : CaveMode.getScanMinimum(level, layerY);

        boolean hasContent = false;
        for (int targetZ = 0; targetZ < PROJECTION_SIZE; targetZ++) {
            int blockZ = borderedOriginZ + targetZ;
            int chunkZ = blockZ >> 4;
            int tileZ = chunkZ - firstChunkZ;
            int localZ = blockZ & 15;
            for (int targetX = 0; targetX < PROJECTION_SIZE; targetX++) {
                int blockX = borderedOriginX + targetX;
                int chunkX = blockX >> 4;
                int tileX = chunkX - firstChunkX;
                int localX = blockX & 15;
                int tileIndex = tileZ * DISPLAY_TILE_WINDOW + tileX;
                int color = 0;
                short floor = FullCaveMapManager.NO_SURFACE;
                short openTop = FullCaveMapManager.NO_SURFACE;
                byte flags = 0;
                byte light = 0;
                int denseOverlayCount = 0;
                boolean known = false;

                DenseCaveTile dense = denseTiles[tileIndex];
                boolean denseMatches = dense != null && (view == CaveView.FULL
                        || dense.projectionTopY() == layerY);
                if (denseMatches) {
                    known = true;
                    color = dense.baseColor(localX, localZ);
                    floor = dense.floorY(localX, localZ);
                    openTop = dense.topY(localX, localZ);
                    flags = dense.flags(localX, localZ);
                    light = dense.light(localX, localZ);
                    denseOverlayCount = dense.overlayCount(localX, localZ);
                } else if (knownEmptyTiles[tileIndex]) {
                    // Exact Top-Y was authoritatively read and contains no chunk.
                    // This known zero may clear the retained page pixel.
                    known = true;
                } else {
                    // Compatibility fallback for old .cvr and multiplayer history.
                    // A scanned empty column is still known and may intentionally
                    // clear an older pixel. Missing columns remain unknown.
                    CaveChunkTile archive = archiveTiles[tileIndex];
                    CaveColumnData column = archive == null
                            ? null : archive.getColumn(localX, localZ);
                    if (column != null) {
                        known = true;
                        int run = view == CaveView.FULL
                                ? column.firstVisibleFullIndex()
                                : column.firstVisibleLayeredIndex(maximum, minimum);
                        if (run >= 0) {
                            color = column.color(run);
                            floor = column.bottomY(run);
                            openTop = column.topY(run);
                            flags = (byte) (column.flags(run)
                                    | DenseCaveTile.FLAG_PRELIT_LEGACY);
                            light = 15;
                        }
                    }
                }

                heights[targetZ * PROJECTION_SIZE + targetX] = floor;
                if (targetX > 0 && targetX <= PAGE_SIZE
                        && targetZ > 0 && targetZ <= PAGE_SIZE) {
                    int pageX = targetX - 1;
                    int pageZ = targetZ - 1;
                    int pageIndex = pageZ * PAGE_SIZE + pageX;
                    pixels[pageIndex] = color;
                    topHeights[pageIndex] = openTop;
                    pixelFlags[pageIndex] = flags;
                    pixelLight[pageIndex] = light;
                    if (denseMatches && denseOverlayCount > 0) {
                        int count = Math.min(DenseCaveTile.MAX_OVERLAYS,
                                denseOverlayCount);
                        overlayCounts[pageIndex] = (byte) count;
                        int first = pageIndex * DenseCaveTile.MAX_OVERLAYS;
                        for (int layerIndex = 0; layerIndex < count; layerIndex++) {
                            int entry = first + layerIndex;
                            overlayColors[entry] = dense.overlayColor(
                                    localX, localZ, layerIndex);
                            overlayAlpha[entry] = dense.overlayAlpha(
                                    localX, localZ, layerIndex);
                            overlayY[entry] = dense.overlayY(
                                    localX, localZ, layerIndex);
                            overlayLight[entry] = dense.overlayLight(
                                    localX, localZ, layerIndex);
                            overlayFlags[entry] = dense.overlayFlags(
                                    localX, localZ, layerIndex);
                        }
                    }
                    if (known) knownRows[pageZ] |= 1L << pageX;
                    hasContent |= color != 0;
                }
            }
        }

        if (complete) {
            for (long row : knownRows) {
                if (row != -1L) {
                    complete = false;
                    break;
                }
            }
        }
        ResolvedPage resolved = new ResolvedPage(
                pixels, heights, topHeights, pixelFlags, pixelLight,
                overlayCounts, overlayColors, overlayAlpha, overlayY,
                overlayLight, overlayFlags, knownRows,
                revision, hasContent, complete);
        telemetry.recordGraphResolve(System.nanoTime() - resolveStarted);
        if (complete) {
            synchronized (this) {
                resolvedPageCache.put(cacheKey, resolved);
                while (resolvedPageCache.size() > MAX_RESOLVED_PAGE_CACHE) {
                    var iterator = resolvedPageCache.entrySet().iterator();
                    if (!iterator.hasNext()) break;
                    iterator.next();
                    iterator.remove();
                }
            }
        }
        return resolved;
    }

    public ResolvedRegion resolveRegion(CaveView view, int layerY, Level level,
            int regionX, int regionZ) {
        int[] pixels = new int[512 * 512];
        short[] heights = new short[512 * 512];
        java.util.Arrays.fill(heights, FullCaveMapManager.NO_SURFACE);
        boolean content = false;
        long revision = getRegionRevision(regionX, regionZ);
        int firstPageX = regionX << 3;
        int firstPageZ = regionZ << 3;
        for (int pz = 0; pz < 8; pz++) {
            for (int px = 0; px < 8; px++) {
                ResolvedPage page = resolvePage(view, layerY, level,
                        firstPageX + px, firstPageZ + pz);
                content |= page.hasContent();
                int[] styled = CavePageStyler.style(
                        page.pixels(), page.heights(), page.topHeights(),
                        page.flags(), page.light(), page.overlayCounts(),
                        page.overlayColors(), page.overlayAlpha(), page.overlayY(),
                        page.overlayLight(), page.overlayFlags(), view, layerY);
                for (int z = 0; z < 64; z++) {
                    int target = (pz * 64 + z) * 512 + px * 64;
                    int pixelSource = z * 64;
                    int heightSource = (z + 1) * 66 + 1;
                    System.arraycopy(styled, pixelSource, pixels, target, 64);
                    System.arraycopy(page.heights(), heightSource, heights, target, 64);
                }
            }
        }
        return new ResolvedRegion(pixels, heights, revision, content);
    }

    public void tickSave() {
        synchronized (this) {
            long now = System.nanoTime();
            if (now < nextSaveAdmissionNanos) return;

            SaveAdmission display = scheduleDisplaySaveLocked();
            SaveAdmission raw = scheduleNextRawSaveLocked();
            updateSaveBackoffLocked(display, raw, now);
        }
    }

    private SaveAdmission scheduleNextRawSaveLocked() {
        if (distinctFutureCount(pendingSaves) >= MAX_PENDING_RAW_SAVE_BATCHES) {
            return SaveAdmission.NONE;
        }
        CaveChunkTile first = null;
        int regionX = 0;
        int regionZ = 0;
        for (Map.Entry<Long, CaveChunkTile> entry : tiles.entrySet()) {
            CaveChunkTile candidate = entry.getValue();
            int candidateRegionX = candidate.chunkX() >> 5;
            int candidateRegionZ = candidate.chunkZ() >> 5;
            if (!candidate.isDirtyForSave()
                    || pendingSaves.containsKey(entry.getKey())
                    || pendingCompactions.containsKey(pack(
                            candidateRegionX, candidateRegionZ))) continue;
            first = candidate;
            regionX = candidateRegionX;
            regionZ = candidateRegionZ;
            break;
        }
        if (first == null) return SaveAdmission.NONE;

        List<CaveChunkTile> batch = new ArrayList<>(SAVE_BATCH_SIZE);
        batch.add(first);
        for (Map.Entry<Long, CaveChunkTile> entry : tiles.entrySet()) {
            if (batch.size() >= SAVE_BATCH_SIZE) break;
            CaveChunkTile tile = entry.getValue();
            if (tile == first || !tile.isDirtyForSave()
                    || pendingSaves.containsKey(entry.getKey())) continue;
            if ((tile.chunkX() >> 5) == regionX && (tile.chunkZ() >> 5) == regionZ) {
                batch.add(tile);
            }
        }
        return scheduleSaveBatchLocked(batch);
    }

    private void updateSaveBackoffLocked(SaveAdmission display,
            SaveAdmission raw, long now) {
        boolean saturated = display == SaveAdmission.SATURATED
                || raw == SaveAdmission.SATURATED;
        boolean accepted = display == SaveAdmission.ACCEPTED
                || raw == SaveAdmission.ACCEPTED;
        if (saturated) {
            nextSaveAdmissionNanos = now + TimeUnit.MILLISECONDS.toNanos(
                    saveRetryDelayMs);
            saveRetryDelayMs = Math.min(MAX_SAVE_RETRY_MS,
                    Math.max(MIN_SAVE_RETRY_MS, saveRetryDelayMs << 1));
        } else if (accepted) {
            nextSaveAdmissionNanos = 0L;
            saveRetryDelayMs = MIN_SAVE_RETRY_MS;
        }
    }

    public synchronized void clearRuntime(boolean preserveDiskIndex) {
        generation.incrementAndGet();
        tiles.clear();
        displayTiles.clear();
        dirtyDisplayTiles.clear();
        absentDisplayTiles.clear();
        staleDisplayTiles.clear();
        pendingDisplayLoads.clear();
        pendingDisplaySaves.clear();
        pageRevisions.clear();
        regionRevisions.clear();
        resolvedPageCache.clear();
        pendingLoads.clear();
        pendingSaves.clear();
        pendingCompactions.clear();
        regionSaveCounts.clear();
        nextSaveAdmissionNanos = 0L;
        saveRetryDelayMs = MIN_SAVE_RETRY_MS;
        if (!preserveDiskIndex) {
            indexedTiles.clear();
            indexedRegionCounts.clear();
            rebuildIndex();
        }
    }

    public void flushAndClear() {
        File targetDirectory;
        List<CaveChunkTile.Snapshot> snapshots;
        List<DenseCaveTile> displaySnapshots;
        List<CompletableFuture<?>> inFlight;
        List<CompletableFuture<?>> displayInFlight;
        synchronized (this) {
            targetDirectory = directory;
            snapshots = collectDirtySnapshotsLocked();
            displaySnapshots = collectDirtyDisplayTilesLocked();
            inFlight = new ArrayList<>(pendingSaves.values());
            displayInFlight = new ArrayList<>(pendingDisplaySaves.values());

            generation.incrementAndGet();
            tiles.clear();
            displayTiles.clear();
            dirtyDisplayTiles.clear();
            absentDisplayTiles.clear();
            staleDisplayTiles.clear();
            pendingDisplayLoads.clear();
            pendingDisplaySaves.clear();
            pageRevisions.clear();
            regionRevisions.clear();
            resolvedPageCache.clear();
            pendingLoads.clear();
            pendingSaves.clear();
            pendingCompactions.clear();
            regionSaveCounts.clear();
            nextSaveAdmissionNanos = 0L;
            saveRetryDelayMs = MIN_SAVE_RETRY_MS;
        }
        // Never wait for disk IO on Minecraft's render thread during a portal or
        // teleport. Old saves finish first, then the newest detached snapshots are
        // written to the directory that belonged to the old dimension.
        flushSnapshotsAfter(targetDirectory, snapshots, inFlight);
        flushDisplayTilesAfter(targetDirectory, displaySnapshots, displayInFlight);
    }

    private List<CaveChunkTile.Snapshot> collectDirtySnapshotsLocked() {
        List<CaveChunkTile.Snapshot> snapshots = new ArrayList<>();
        for (CaveChunkTile tile : tiles.values()) {
            if (tile.isDirtyForSave()) snapshots.add(tile.snapshot());
        }
        return snapshots;
    }

    private List<DenseCaveTile> collectDirtyDisplayTilesLocked() {
        List<DenseCaveTile> result = new ArrayList<>();
        for (DenseCaveTileKey key : dirtyDisplayTiles) {
            DenseCaveTile tile = displayTiles.get(key);
            if (tile != null) result.add(tile);
        }
        return result;
    }

    private void flushSnapshotsAfter(File targetDirectory,
            List<CaveChunkTile.Snapshot> snapshots,
            List<CompletableFuture<?>> inFlight) {
        if ((snapshots == null || snapshots.isEmpty())
                && (inFlight == null || inFlight.isEmpty())) return;
        CompletableFuture<?> barrier = inFlight == null || inFlight.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new));
        barrier.handle((ignored, failure) -> null).thenRun(() ->
                MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                        MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.DISK_WRITE, 0,
                        Math.max(12, snapshots == null ? 1 : snapshots.size()),
                        () -> true, () -> {
                            if (snapshots == null || snapshots.isEmpty()) return;
                            try {
                                CaveRegionStore.appendSnapshots(targetDirectory, snapshots);
                            } catch (IOException exception) {
                                LOGGER.warn("Could not asynchronously flush {} cave tiles",
                                        snapshots.size(), exception);
                            }
                        }));
    }

    private void flushDisplayTilesAfter(File targetDirectory,
            List<DenseCaveTile> tilesToSave, List<CompletableFuture<?>> inFlight) {
        if ((tilesToSave == null || tilesToSave.isEmpty())
                && (inFlight == null || inFlight.isEmpty())) return;
        CompletableFuture<?> barrier = inFlight == null || inFlight.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new));
        barrier.handle((ignored, failure) -> null).thenRun(() ->
                MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                        MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.DISK_WRITE, 0,
                        Math.max(12, tilesToSave == null ? 1 : tilesToSave.size()),
                        () -> true, () -> {
                            if (tilesToSave == null || tilesToSave.isEmpty()) return;
                            try {
                                CaveDisplayRegionStore.append(targetDirectory, tilesToSave);
                            } catch (IOException exception) {
                                LOGGER.warn("Could not asynchronously flush {} dense cave tiles",
                                        tilesToSave.size(), exception);
                            }
                        }));
    }

    private synchronized void requestDisplayTileLoadLocked(DenseCaveTileKey key) {
        if (!indexedDisplayTiles.contains(key)
                || pendingDisplayLoads.containsKey(key)) return;
        File sourceDirectory = directory;
        CaveDisplayRegionStore.RecordPointer pointer = displayRecords.get(key);
        long expectedGeneration = generation.get();
        CompletableFuture<DenseCaveTile> future = MapWorkScheduler.tryIoFuture(
                MapRequestLane.FULLSCREEN, MapWorkScheduler.WorkType.DISK_READ,
                MapRequestLane.FULLSCREEN.priorityBase(), 8,
                () -> generation.get() == expectedGeneration
                        && sourceDirectory == directory,
                () -> {
                    try {
                        return CaveDisplayRegionStore.read(sourceDirectory, pointer);
                    } catch (IOException exception) {
                        LOGGER.warn("Could not read dense cave tile {}", key, exception);
                        return null;
                    }
                });
        if (future == null) return;
        pendingDisplayLoads.put(key, future);
        future.whenComplete((tile, throwable) -> {
            synchronized (CaveTileRepository.this) {
                pendingDisplayLoads.remove(key, future);
                if (generation.get() != expectedGeneration
                        || sourceDirectory != directory
                        || !indexedDisplayTiles.contains(key)) return;
                if (throwable != null || tile == null) {
                    indexedDisplayTiles.remove(key);
                    displayRecords.remove(key);
                    return;
                }
                DenseCaveTile current = displayTiles.get(key);
                if (current == null || current.source().rank() <= tile.source().rank()) {
                    displayTiles.put(key, tile);
                    absentDisplayTiles.remove(key, tile.projectionTopY());
                    touchLocked(tile.chunkX(), tile.chunkZ(), tile.revision());
                    trimDisplayTilesLocked();
                }
            }
        });
    }

    private synchronized void requestTileLoadLocked(int chunkX, int chunkZ, long key) {
        if (!indexedTiles.contains(key) || pendingLoads.containsKey(key)) return;
        File sourceDirectory = directory;
        CaveRegionStore.RecordPointer regionPointer = regionRecords.get(key);
        long expectedGeneration = generation.get();
        CompletableFuture<CaveChunkTile.Snapshot> future =
                MapWorkScheduler.tryIoFuture(MapRequestLane.FULLSCREEN,
                        MapWorkScheduler.WorkType.DISK_READ,
                        MapRequestLane.FULLSCREEN.priorityBase(), 8,
                        () -> generation.get() == expectedGeneration
                                && sourceDirectory == directory,
                        () -> {
                            try {
                                return readSnapshot(sourceDirectory, chunkX, chunkZ,
                                        regionPointer);
                            } catch (IOException exception) {
                                LOGGER.warn("Could not read cave tile {},{}",
                                        chunkX, chunkZ, exception);
                                return null;
                            }
                        });
        if (future == null) return;
        pendingLoads.put(key, future);
        future.whenComplete((snapshot, throwable) -> {
            synchronized (CaveTileRepository.this) {
                pendingLoads.remove(key, future);
                if (generation.get() != expectedGeneration
                        || sourceDirectory != directory) return;
                if (throwable != null || snapshot == null) {
                    /*
                     * Old cache versions and corrupt files must not remain indexed:
                     * otherwise every visible-page request reopens the same unusable
                     * file forever. A live tile, when present, remains available for
                     * a fresh world scan and will overwrite the old path on save.
                     */
                    removeIndexedTileLocked(chunkX, chunkZ);
                    return;
                }
                telemetry.recordTileLoad();
                CaveChunkTile existing = tiles.get(key);
                boolean changed;
                if (existing == null) {
                    existing = CaveChunkTile.fromSnapshot(snapshot);
                    tiles.put(key, existing);
                    changed = true;
                } else {
                    changed = existing.mergeMissing(snapshot);
                }
                trimLoadedTiles();
                if (changed) touchLocked(chunkX, chunkZ, existing.revision());
            }
        });
    }

    private SaveAdmission scheduleDisplaySaveLocked() {
        if (distinctFutureCount(pendingDisplaySaves)
                >= MAX_PENDING_DISPLAY_SAVE_BATCHES) {
            return SaveAdmission.NONE;
        }
        DenseCaveTileKey firstKey = null;
        DenseCaveTile firstTile = null;
        for (DenseCaveTileKey key : dirtyDisplayTiles) {
            if (pendingDisplaySaves.containsKey(key)) continue;
            DenseCaveTile tile = displayTiles.get(key);
            if (tile == null) continue;
            firstKey = key;
            firstTile = tile;
            break;
        }
        if (firstTile == null) return SaveAdmission.NONE;

        int regionX = firstTile.chunkX() >> 5;
        int regionZ = firstTile.chunkZ() >> 5;
        List<DenseCaveTile> batch = new ArrayList<>(DISPLAY_SAVE_BATCH_SIZE);
        List<DenseCaveTileKey> keys = new ArrayList<>(DISPLAY_SAVE_BATCH_SIZE);
        batch.add(firstTile);
        keys.add(firstKey);
        for (DenseCaveTileKey key : dirtyDisplayTiles) {
            if (batch.size() >= DISPLAY_SAVE_BATCH_SIZE || key.equals(firstKey)
                    || pendingDisplaySaves.containsKey(key)) continue;
            DenseCaveTile tile = displayTiles.get(key);
            if (tile == null || (tile.chunkX() >> 5) != regionX
                    || (tile.chunkZ() >> 5) != regionZ) continue;
            batch.add(tile);
            keys.add(key);
        }

        File targetDirectory = directory;
        long expectedGeneration = generation.get();
        CompletableFuture<Map<DenseCaveTileKey,
                CaveDisplayRegionStore.RecordPointer>> future =
                MapWorkScheduler.tryIoFuture(MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.DISK_WRITE, 0,
                        Math.max(12, batch.size()),
                        () -> generation.get() == expectedGeneration
                                && targetDirectory == directory,
                        () -> {
                            try {
                                return CaveDisplayRegionStore.append(
                                        targetDirectory, batch);
                            } catch (IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        if (future == null) return SaveAdmission.SATURATED;
        for (DenseCaveTileKey key : keys) pendingDisplaySaves.put(key, future);
        future.whenComplete((records, throwable) -> {
            synchronized (CaveTileRepository.this) {
                for (DenseCaveTileKey key : keys) {
                    pendingDisplaySaves.remove(key, future);
                }
                if (throwable != null) {
                    if (!(throwable instanceof java.util.concurrent.CancellationException)) {
                        LOGGER.warn("Could not save dense cave tile batch of {}",
                                batch.size(), throwable);
                    }
                    return;
                }
                if (generation.get() != expectedGeneration
                        || targetDirectory != directory) return;
                for (int i = 0; i < keys.size(); i++) {
                    DenseCaveTileKey key = keys.get(i);
                    DenseCaveTile saved = batch.get(i);
                    DenseCaveTile current = displayTiles.get(key);
                    boolean stillCurrent = current != null
                            && current.revision() == saved.revision();
                    if (stillCurrent) dirtyDisplayTiles.remove(key);
                    CaveDisplayRegionStore.RecordPointer pointer = records == null
                            ? null : records.get(key);
                    if (stillCurrent && pointer != null) {
                        indexedDisplayTiles.add(key);
                        displayRecords.put(key, pointer);
                    }
                }
                trimDisplayTilesLocked();
            }
        });
        return SaveAdmission.ACCEPTED;
    }

    private SaveAdmission scheduleSaveLocked(CaveChunkTile tile) {
        return scheduleSaveBatchLocked(List.of(tile));
    }

    private SaveAdmission scheduleSaveBatchLocked(List<CaveChunkTile> batch) {
        if (batch == null || batch.isEmpty()) return SaveAdmission.NONE;
        if (distinctFutureCount(pendingSaves) >= MAX_PENDING_RAW_SAVE_BATCHES) {
            return SaveAdmission.NONE;
        }
        int batchRegionX = batch.get(0).chunkX() >> 5;
        int batchRegionZ = batch.get(0).chunkZ() >> 5;
        if (pendingCompactions.containsKey(pack(batchRegionX, batchRegionZ))) {
            return SaveAdmission.NONE;
        }
        List<CaveChunkTile.Snapshot> snapshots = new ArrayList<>(batch.size());
        List<Long> keys = new ArrayList<>(batch.size());
        for (CaveChunkTile tile : batch) {
            long key = pack(tile.chunkX(), tile.chunkZ());
            if (pendingSaves.containsKey(key)) continue;
            snapshots.add(tile.snapshot());
            keys.add(key);
        }
        if (snapshots.isEmpty()) return SaveAdmission.NONE;

        File targetDirectory = directory;
        long expectedGeneration = generation.get();
        CompletableFuture<Map<Long, CaveRegionStore.RecordPointer>> future =
                MapWorkScheduler.tryIoFuture(MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.DISK_WRITE, 0,
                        Math.max(12, snapshots.size()),
                        () -> generation.get() == expectedGeneration
                                && targetDirectory == directory,
                        () -> {
                            try {
                                return CaveRegionStore.appendSnapshots(
                                        targetDirectory, snapshots);
                            } catch (IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        if (future == null) return SaveAdmission.SATURATED;
        for (Long key : keys) pendingSaves.put(key, future);

        future.whenComplete((recordPointers, throwable) -> {
            synchronized (CaveTileRepository.this) {
                for (Long key : keys) pendingSaves.remove(key, future);
                if (throwable != null) {
                    if (!(throwable instanceof java.util.concurrent.CancellationException)) {
                        LOGGER.warn("Could not save cave tile batch of {}",
                                snapshots.size(), throwable);
                    }
                    return;
                }
                if (generation.get() != expectedGeneration
                        || targetDirectory != directory) return;

                int regionX = snapshots.get(0).chunkX() >> 5;
                int regionZ = snapshots.get(0).chunkZ() >> 5;
                for (CaveChunkTile.Snapshot snapshot : snapshots) {
                    long key = pack(snapshot.chunkX(), snapshot.chunkZ());
                    CaveChunkTile current = tiles.get(key);
                    if (current != null) current.markSaved(snapshot.revision());
                    CaveRegionStore.RecordPointer pointer = recordPointers == null
                            ? null : recordPointers.get(key);
                    if (pointer != null) regionRecords.put(key, pointer);
                    indexTileLocked(snapshot.chunkX(), snapshot.chunkZ());
                }
                noteRegionSaveLocked(regionX, regionZ, snapshots.size(),
                        targetDirectory, expectedGeneration);
                trimLoadedTiles();
            }
        });
        return SaveAdmission.ACCEPTED;
    }

    private void noteRegionSaveLocked(int regionX, int regionZ, int savedCount,
            File targetDirectory, long expectedGeneration) {
        long regionKey = pack(regionX, regionZ);
        int count = regionSaveCounts.getOrDefault(regionKey, 0)
                + Math.max(1, savedCount);
        if (count < REGION_COMPACTION_SAVE_INTERVAL
                || pendingCompactions.containsKey(regionKey)
                || hasPendingSavesInRegionLocked(regionX, regionZ)
                || pendingCompactions.size() >= MAX_PENDING_COMPACTIONS) {
            regionSaveCounts.put(regionKey, count);
            return;
        }

        CompletableFuture<CaveRegionStore.CompactionResult> future =
                MapWorkScheduler.tryIoFuture(MapRequestLane.BACKGROUND,
                        MapWorkScheduler.WorkType.CACHE_MAINTENANCE, -100,
                        24, () -> generation.get() == expectedGeneration
                                && targetDirectory == directory,
                        () -> {
                            try {
                                return CaveRegionStore.compactRegionIfNeeded(
                                        targetDirectory, regionX, regionZ);
                            } catch (IOException exception) {
                                throw new RuntimeException(exception);
                            }
                        });
        if (future == null) {
            // Compaction is optional maintenance. Preserve the accumulated count
            // and try again only after foreground reads/writes have drained.
            regionSaveCounts.put(regionKey, count);
            return;
        }
        regionSaveCounts.put(regionKey, 0);
        pendingCompactions.put(regionKey, future);
        future.whenComplete((result, throwable) -> {
            synchronized (CaveTileRepository.this) {
                pendingCompactions.remove(regionKey, future);
                if (throwable != null) {
                    if (!(throwable instanceof java.util.concurrent.CancellationException)) {
                        LOGGER.warn("Could not compact cave region {},{}",
                                regionX, regionZ, throwable);
                    }
                    regionSaveCounts.merge(regionKey,
                            REGION_COMPACTION_SAVE_INTERVAL, Math::max);
                    return;
                }
                if (result == null || generation.get() != expectedGeneration
                        || targetDirectory != directory) return;

                regionRecords.entrySet().removeIf(entry -> {
                    CaveRegionStore.RecordPointer pointer = entry.getValue();
                    return pointer.regionX() == regionX && pointer.regionZ() == regionZ;
                });
                regionRecords.putAll(result.records());
                for (CaveRegionStore.RecordPointer pointer : result.records().values()) {
                    indexTileLocked(pointer.chunkX(), pointer.chunkZ());
                }
                telemetry.recordRegionCompaction(result.reclaimedBytes());
            }
        });
    }

    private boolean hasPendingSavesInRegionLocked(int regionX, int regionZ) {
        for (Long key : pendingSaves.keySet()) {
            int chunkX = (int) (key >> 32);
            int chunkZ = (int) (long) key;
            if ((chunkX >> 5) == regionX && (chunkZ >> 5) == regionZ) return true;
        }
        return false;
    }

    private void touch(int chunkX, int chunkZ, long tileRevision) {
        synchronized (this) {
            touchLocked(chunkX, chunkZ, tileRevision);
        }
    }

    private void touchLocked(int chunkX, int chunkZ, long tileRevision) {
        int globalPageX = chunkX >> 2;
        int globalPageZ = chunkZ >> 2;
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        pageRevisions.merge(pack(globalPageX, globalPageZ), 1L, Long::sum);
        regionRevisions.merge(pack(regionX, regionZ), 1L, Long::sum);
        for (TileListener listener : listeners) {
            try {
                listener.onTileChanged(chunkX, chunkZ, tileRevision);
            } catch (Throwable throwable) {
                LOGGER.warn("Cave tile listener failed", throwable);
            }
        }
    }

    private void trimDisplayTilesLocked() {
        while (displayTiles.size() > MAX_DISPLAY_TILES) {
            DenseCaveTileKey removable = null;
            for (Map.Entry<DenseCaveTileKey, DenseCaveTile> entry : displayTiles.entrySet()) {
                DenseCaveTileKey key = entry.getKey();
                if (!dirtyDisplayTiles.contains(key)
                        && !pendingDisplaySaves.containsKey(key)
                        && !pendingDisplayLoads.containsKey(key)) {
                    removable = key;
                    break;
                }
            }
            if (removable == null) {
                scheduleDisplaySaveLocked();
                break;
            }
            displayTiles.remove(removable);
        }
    }

    private void trimLoadedTiles() {
        while (tiles.size() > MAX_LOADED_TILES) {
            Long removableKey = null;
            CaveChunkTile dirtyToSave = null;
            long dirtyKey = 0L;

            for (Map.Entry<Long, CaveChunkTile> entry : tiles.entrySet()) {
                CaveChunkTile tile = entry.getValue();
                if (!tile.needsScanWork() && !tile.isDirtyForSave()
                        && !pendingSaves.containsKey(entry.getKey())) {
                    removableKey = entry.getKey();
                    break;
                }
                if (dirtyToSave == null && tile.isDirtyForSave()
                        && !pendingSaves.containsKey(entry.getKey())
                        && !pendingCompactions.containsKey(pack(
                                tile.chunkX() >> 5, tile.chunkZ() >> 5))) {
                    dirtyToSave = tile;
                    dirtyKey = entry.getKey();
                }
            }

            if (removableKey != null) {
                tiles.remove(removableKey);
                continue;
            }
            if (dirtyToSave != null && !pendingSaves.containsKey(dirtyKey)) {
                scheduleSaveLocked(dirtyToSave);
            }
            // Keep dirty tiles resident until their snapshot is safely persisted.
            break;
        }
    }

    private void rebuildIndex() {
        indexedTiles.clear();
        indexedRegionCounts.clear();
        regionRecords.clear();
        indexedDisplayTiles.clear();
        displayRecords.clear();
        regionSaveCounts.clear();
        File source = directory;
        if (source == null || !source.isDirectory()) return;

        // Prefer the packed random-access region container. The latest complete
        // record for every tile is indexed directly by byte offset.
        regionRecords.putAll(CaveRegionStore.rebuildIndex(source));
        for (CaveRegionStore.RecordPointer pointer : regionRecords.values()) {
            indexTileLocked(pointer.chunkX(), pointer.chunkZ());
        }

        displayRecords.putAll(CaveDisplayRegionStore.rebuildIndex(source));
        indexedDisplayTiles.addAll(displayRecords.keySet());

        // Version-3 per-chunk files remain a migration fallback. A successful save
        // appends the tile to its .cvr region and removes the legacy small file.
        File[] files = source.listFiles((dir, name) -> name != null
                && name.matches("c\\.-?\\d+\\.-?\\d+\\.cvt"));
        if (files == null) return;
        for (File file : files) {
            String[] parts = file.getName().split("\\.");
            if (parts.length != 4) continue;
            try {
                indexTileLocked(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void indexTileLocked(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        if (!indexedTiles.add(key)) return;
        indexedRegionCounts.merge(pack(chunkX >> 5, chunkZ >> 5), 1, Integer::sum);
    }

    private void removeIndexedTileLocked(int chunkX, int chunkZ) {
        long key = pack(chunkX, chunkZ);
        regionRecords.remove(key);
        if (!indexedTiles.remove(key)) return;
        long regionKey = pack(chunkX >> 5, chunkZ >> 5);
        int remaining = indexedRegionCounts.getOrDefault(regionKey, 0) - 1;
        if (remaining <= 0) indexedRegionCounts.remove(regionKey);
        else indexedRegionCounts.put(regionKey, remaining);
    }

    private static CaveChunkTile.Snapshot readSnapshot(File directory,
            int chunkX, int chunkZ, CaveRegionStore.RecordPointer regionPointer)
            throws IOException {
        CaveChunkTile.Snapshot packed = CaveRegionStore.readSnapshot(directory, regionPointer);
        if (packed != null) return packed;
        return CaveRegionStore.readLegacySnapshot(directory, chunkX, chunkZ);
    }

    private static int distinctFutureCount(Map<?, CompletableFuture<?>> futures) {
        if (futures == null || futures.isEmpty()) return 0;
        return new HashSet<>(futures.values()).size();
    }

    private enum SaveAdmission {
        NONE,
        ACCEPTED,
        SATURATED
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public record ResolvedPage(int[] pixels, short[] heights,
            short[] topHeights, byte[] flags, byte[] light,
            byte[] overlayCounts, int[] overlayColors, byte[] overlayAlpha,
            short[] overlayY, byte[] overlayLight, byte[] overlayFlags,
            long[] knownRows, long revision, boolean hasContent,
            boolean complete) {
        public int knownColumnCount() {
            int count = 0;
            for (long row : knownRows) count += Long.bitCount(row);
            return count;
        }
    }

    public record ResolvedRegion(int[] pixels, short[] heights,
            long revision, boolean hasContent) {
    }

    private static final class ProjectionWorkspace {
        private final CaveChunkTile[] pageTiles =
                new CaveChunkTile[DISPLAY_TILE_WINDOW * DISPLAY_TILE_WINDOW];
        private final DenseCaveTile[] displayTiles =
                new DenseCaveTile[DISPLAY_TILE_WINDOW * DISPLAY_TILE_WINDOW];
        private final boolean[] knownEmptyTiles =
                new boolean[DISPLAY_TILE_WINDOW * DISPLAY_TILE_WINDOW];
    }

    private record PageCacheKey(long generation, CaveView view, int layerY,
            int projectionTopY, int globalPageX, int globalPageZ, long revision) {
    }

    @FunctionalInterface
    public interface TileListener {
        void onTileChanged(int chunkX, int chunkZ, long tileRevision);
    }
}
