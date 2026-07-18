package com.velorise.simplemap.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persistent vertical cave archive.
 *
 * <p>Each 64x64 tile stores up to {@value #MAX_SURFACES_PER_COLUMN} visible
 * cavity intervals for every X/Z column. Each interval records the top of one
 * contiguous open run and the visible floor beneath it, allowing any bounded
 * Top-Y layer to be rebuilt later without loading the Minecraft chunk again.</p>
 */
public final class VerticalCaveArchiveManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final VerticalCaveArchiveManager INSTANCE = new VerticalCaveArchiveManager();

    public static final int TILE_SHIFT = 6;
    public static final int TILE_SIZE = 1 << TILE_SHIFT;
    private static final int TILE_MASK = TILE_SIZE - 1;
    private static final int COLUMNS = TILE_SIZE * TILE_SIZE;
    public static final int MAX_SURFACES_PER_COLUMN = 63;
    private static final int SLOT_COUNT = COLUMNS * MAX_SURFACES_PER_COLUMN;
    private static final int MAX_LOADED_TILES = 24;
    private static final int FILE_MAGIC = 0x534D5641; // SMVA
    private static final int FILE_VERSION = 7;
    private static final int MAX_FILE_BYTES = 12 * 1024 * 1024;
    private static final int SCANNED_FLAG = 0x80;
    /** Set per column once it has been captured with the current archive format. */
    private static final int CURRENT_FLAG = 0x40;
    private static final int COUNT_MASK = 0x3F;
    private static final String FILE_EXT = ".vca";

    private static final ThreadPoolExecutor LOAD_POOL = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(192), runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-VerticalCaveLoad");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ExecutorService SAVE_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleMap-VerticalCaveSave");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    private final Map<String, ArchiveTile> tiles = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyTiles = new HashSet<>();
    private final Set<String> knownFiles = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> regionRevisions = new ConcurrentHashMap<>();
    private final Map<String, SaveRequest> pendingSaves = new ConcurrentHashMap<>();
    private final Map<String, SaveRequest> inFlightSaves = new ConcurrentHashMap<>();
    private final AtomicBoolean saveDrainScheduled = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong(1L);

    private volatile File cacheDirectory;
    private long lastSaveTime;

    private VerticalCaveArchiveManager() {
    }

    public static VerticalCaveArchiveManager getInstance() {
        return INSTANCE;
    }

    public long getGeneration() {
        return generation.get();
    }

    public synchronized void setCacheDirectory(File directory) {
        if (sameFile(cacheDirectory, directory)) return;
        flushAndClear();
        cacheDirectory = directory;
        if (cacheDirectory != null && !cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            LOGGER.warn("Could not create vertical cave archive directory {}", cacheDirectory);
        }
        indexExistingFiles();
    }

    public boolean isColumnReady(int blockX, int blockZ) {
        ArchiveTile tile = getTile(blockX >> TILE_SHIFT, blockZ >> TILE_SHIFT, true);
        return tile != null && tile.isLoaded();
    }

    public boolean isColumnScanned(int blockX, int blockZ) {
        ArchiveTile tile = getTile(blockX >> TILE_SHIFT, blockZ >> TILE_SHIFT, true);
        return tile != null && tile.isLoaded()
                && tile.isColumnScanned(blockX & TILE_MASK, blockZ & TILE_MASK);
    }

    /**
     * Marks one archived column stale without synchronously rebuilding its complete
     * vertical stack. Block updates can therefore stay cheap; the scanner recaptures
     * the column inside its normal time budget on a later client tick.
     */
    public boolean invalidateColumn(int blockX, int blockZ) {
        int tileX = blockX >> TILE_SHIFT;
        int tileZ = blockZ >> TILE_SHIFT;
        ArchiveTile tile = getTile(tileX, tileZ, true);
        if (tile == null || !tile.isLoaded()) return false;
        if (!tile.invalidateColumn(blockX & TILE_MASK, blockZ & TILE_MASK)) return true;
        String key = key(tileX, tileZ);
        synchronized (dirtyTiles) {
            dirtyTiles.add(key);
        }
        regionRevisions.merge(regionKey(blockX >> 9, blockZ >> 9), 1L, Long::sum);
        return true;
    }

    public Candidate getCandidate(int blockX, int blockZ, int maximumY, int minimumY) {
        ArchiveTile tile = getTile(blockX >> TILE_SHIFT, blockZ >> TILE_SHIFT, true);
        if (tile == null || !tile.isLoaded()) return null;
        return tile.getCandidate(blockX & TILE_MASK, blockZ & TILE_MASK, maximumY, minimumY);
    }

    /** Replaces the complete vertical snapshot for one column. */
    public boolean recordColumn(int blockX, int blockZ, Candidate[] candidates) {
        int tileX = blockX >> TILE_SHIFT;
        int tileZ = blockZ >> TILE_SHIFT;
        ArchiveTile tile = getTile(tileX, tileZ, true);
        if (tile == null || !tile.isLoaded()) return false;
        if (!tile.replaceColumn(blockX & TILE_MASK, blockZ & TILE_MASK, candidates)) return true;
        String key = key(tileX, tileZ);
        synchronized (dirtyTiles) {
            dirtyTiles.add(key);
        }
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        regionRevisions.merge(regionKey(regionX, regionZ), 1L, Long::sum);
        return true;
    }

    public boolean hasRegionData(int regionX, int regionZ) {
        if (regionRevisions.containsKey(regionKey(regionX, regionZ))) return true;
        int firstTileX = regionX << 3;
        int firstTileZ = regionZ << 3;
        for (int dz = 0; dz < 8; dz++) {
            for (int dx = 0; dx < 8; dx++) {
                if (hasTileFile(firstTileX + dx, firstTileZ + dz)) return true;
            }
        }
        return false;
    }

    public long getRegionRevision(int regionX, int regionZ) {
        return regionRevisions.getOrDefault(regionKey(regionX, regionZ), 0L);
    }

    /**
     * Builds a complete 512x512 bounded layer from archived vertical columns.
     * This method performs file IO and must run on a background worker.
     */
    public Projection projectRegion(int layerY, int regionX, int regionZ, boolean fullView) {
        int[] colors = new int[512 * 512];
        short[] heights = new short[512 * 512];
        byte[] scanned = new byte[512 * 512];
        Arrays.fill(heights, FullCaveMapManager.NO_SURFACE);

        int firstTileX = regionX << 3;
        int firstTileZ = regionZ << 3;
        int minimumY = fullView ? -64 : layerY - 31;
        int sourceTiles = 0;
        int scannedColumns = 0;

        for (int tileDz = 0; tileDz < 8; tileDz++) {
            for (int tileDx = 0; tileDx < 8; tileDx++) {
                int tileX = firstTileX + tileDx;
                int tileZ = firstTileZ + tileDz;
                int projected = projectTileInto(tileX, tileZ, tileDx << TILE_SHIFT,
                        tileDz << TILE_SHIFT, layerY, minimumY, colors, heights, scanned);
                if (projected < 0) continue;
                sourceTiles++;
                scannedColumns += projected;
            }
        }

        if (MapConfig.terrainSlopes > 0) applyRelief(colors, heights, scanned);
        return new Projection(colors, heights, scanned, scannedColumns, sourceTiles,
                getRegionRevision(regionX, regionZ));
    }

    public void tickSave() {
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < 10_000L) return;
        lastSaveTime = now;
        saveDirtyTiles();
    }

    public synchronized void flushAndClear() {
        saveDirtyTiles();
        generation.incrementAndGet();
        clearTiles();
        knownFiles.clear();
        regionRevisions.clear();
        cacheDirectory = null;
    }

    private ArchiveTile getTile(int tileX, int tileZ, boolean loadIfMissing) {
        String key = key(tileX, tileZ);
        synchronized (tiles) {
            ArchiveTile existing = tiles.get(key);
            if (existing != null || !loadIfMissing || cacheDirectory == null) return existing;
            long requestedGeneration = generation.get();
            ArchiveTile created = new ArchiveTile(tileX, tileZ, requestedGeneration);
            tiles.put(key, created);
            SaveRequest pending = latestSave(cacheDirectory, tileX, tileZ);
            if (pending != null) {
                created.replace(pending.snapshot());
                created.markLoaded();
            } else if (hasTileFile(tileX, tileZ)) {
                loadTileAsync(created, cacheDirectory, requestedGeneration);
            } else {
                created.markLoaded();
            }
            evictTilesIfNeeded();
            return created;
        }
    }

    private boolean hasTileFile(int tileX, int tileZ) {
        File directory = cacheDirectory;
        if (directory == null) return false;
        if (latestSave(directory, tileX, tileZ) != null) return true;
        String key = key(tileX, tileZ);
        if (knownFiles.contains(key)) return true;
        File file = new File(directory, fileName(tileX, tileZ));
        if (file.isFile()) {
            knownFiles.add(key);
            regionRevisions.putIfAbsent(regionKey(tileX >> 3, tileZ >> 3), 1L);
            return true;
        }
        return false;
    }

    private void loadTileAsync(ArchiveTile tile, File directory, long requestedGeneration) {
        try {
            LOAD_POOL.execute(() -> {
                TileSnapshot snapshot = null;
                File file = new File(directory, fileName(tile.tileX, tile.tileZ));
                try {
                    SaveRequest pending = latestSave(directory, tile.tileX, tile.tileZ);
                    snapshot = pending != null ? pending.snapshot() : read(file);
                } catch (IOException exception) {
                    LOGGER.warn("Failed to load vertical cave tile {},{}", tile.tileX, tile.tileZ, exception);
                    quarantine(file);
                }
                if (requestedGeneration != generation.get() || !sameFile(cacheDirectory, directory)) return;
                synchronized (tiles) {
                    if (tiles.get(key(tile.tileX, tile.tileZ)) != tile || tile.isClosed()) return;
                }
                if (snapshot != null) {
                    tile.replace(snapshot);
                }
                tile.markLoaded();
            });
        } catch (RejectedExecutionException saturated) {
            synchronized (tiles) {
                tiles.remove(key(tile.tileX, tile.tileZ), tile);
            }
        }
    }

    /**
     * Projects one archive tile without materializing a full fixed-size snapshot for
     * every on-disk tile. Overview maps may touch dozens of files; streaming them
     * directly avoids hundreds of megabytes of transient allocations and GC spikes.
     *
     * @return scanned column count, or -1 when no tile source exists
     */
    private int projectTileInto(int tileX, int tileZ, int baseX, int baseZ,
            int maximumY, int minimumY, int[] colors, short[] heights, byte[] scanned) {
        File directory = cacheDirectory;
        if (directory == null) return -1;

        SaveRequest pending = latestSave(directory, tileX, tileZ);
        if (pending != null) {
            return projectSnapshotInto(pending.snapshot(), baseX, baseZ,
                    maximumY, minimumY, colors, heights, scanned);
        }

        synchronized (tiles) {
            ArchiveTile tile = tiles.get(key(tileX, tileZ));
            if (tile != null && tile.isLoaded()) {
                return tile.projectInto(baseX, baseZ, maximumY, minimumY,
                        colors, heights, scanned);
            }
        }

        if (!hasTileFile(tileX, tileZ)) return -1;
        File file = new File(directory, fileName(tileX, tileZ));
        try {
            return projectFileInto(file, baseX, baseZ, maximumY, minimumY,
                    colors, heights, scanned);
        } catch (IOException exception) {
            LOGGER.warn("Failed to project vertical cave tile {},{}", tileX, tileZ, exception);
            quarantine(file);
            knownFiles.remove(key(tileX, tileZ));
            return -1;
        }
    }

    private static int projectSnapshotInto(TileSnapshot snapshot, int baseX, int baseZ,
            int maximumY, int minimumY, int[] colors, short[] heights, byte[] scanned) {
        int scannedColumns = 0;
        for (int localZ = 0; localZ < TILE_SIZE; localZ++) {
            for (int localX = 0; localX < TILE_SIZE; localX++) {
                int column = localZ * TILE_SIZE + localX;
                int flags = snapshot.countFlags()[column] & 0xFF;
                if ((flags & SCANNED_FLAG) == 0) continue;
                int regionIndex = (baseZ + localZ) * 512 + baseX + localX;
                boolean current = (flags & CURRENT_FLAG) != 0;
                scanned[regionIndex] = (byte) (current ? 1 : 2);
                scannedColumns++;
                if (!current) continue;
                Candidate selected = candidate(snapshot, column, maximumY, minimumY);
                if (selected != null) {
                    colors[regionIndex] = selected.color();
                    heights[regionIndex] = selected.bottomY();
                }
            }
        }
        return scannedColumns;
    }

    private static int projectFileInto(File file, int baseX, int baseZ,
            int maximumY, int minimumY, int[] colors, short[] heights, byte[] scanned)
            throws IOException {
        long size = Files.size(file.toPath());
        if (size <= 0L || size > MAX_FILE_BYTES) {
            throw new IOException("Unsafe archive tile size " + size);
        }
        int scannedColumns = 0;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file), 64 * 1024)))) {
            int magic = input.readInt();
            int version = input.readInt();
            input.readInt();
            input.readInt();
            int columns = input.readInt();
            if (magic != FILE_MAGIC || version < 1 || version > FILE_VERSION || columns != COLUMNS) {
                throw new IOException("Unsupported vertical cave archive format");
            }

            for (int column = 0; column < COLUMNS; column++) {
                int flags = input.readUnsignedByte();
                int count = flags & COUNT_MASK;
                if (count > MAX_SURFACES_PER_COLUMN) {
                    throw new IOException("Invalid surface count " + count);
                }
                boolean scannedColumn = (flags & SCANNED_FLAG) != 0;
                boolean current = version >= FILE_VERSION && (flags & CURRENT_FLAG) != 0;
                int localX = column & TILE_MASK;
                int localZ = column >> TILE_SHIFT;
                int regionIndex = (baseZ + localZ) * 512 + baseX + localX;
                if (scannedColumn) {
                    scanned[regionIndex] = (byte) (current ? 1 : 2);
                    scannedColumns++;
                }

                boolean selected = false;
                boolean belowBand = false;
                for (int slot = 0; slot < count; slot++) {
                    short topY = input.readShort();
                    short bottomY = version >= 3 ? input.readShort() : topY;
                    int color = input.readInt();
                    if (!current || selected || belowBand || color == 0) continue;
                    // Select the highest cavity interval intersecting the
                    // requested 32-block band. topY is the top of the contiguous
                    // open run and bottomY is its visible floor. This keeps the
                    // layer bounded while allowing a tall cavern to remain connected
                    // when its floor falls just below the band's lower edge.
                    if (topY < minimumY) {
                        belowBand = true;
                    } else if (bottomY <= maximumY) {
                        colors[regionIndex] = color;
                        heights[regionIndex] = bottomY;
                        selected = true;
                    }
                }
            }
            if (input.read() != -1) throw new IOException("Trailing archive tile data");
        } catch (EOFException exception) {
            throw new IOException("Truncated vertical cave archive", exception);
        }
        return scannedColumns;
    }

    private void saveDirtyTiles() {
        File directory = cacheDirectory;
        if (directory == null) return;
        Set<String> keys;
        synchronized (dirtyTiles) {
            if (dirtyTiles.isEmpty()) return;
            keys = new HashSet<>(dirtyTiles);
            dirtyTiles.clear();
        }
        for (String key : keys) {
            ArchiveTile tile;
            synchronized (tiles) {
                tile = tiles.get(key);
            }
            if (tile != null && tile.isLoaded()) saveTile(tile, directory);
        }
    }

    private void saveTile(ArchiveTile tile, File directory) {
        SaveRequest request = new SaveRequest(directory, tile.tileX, tile.tileZ,
                tile.generation, tile.snapshot());
        pendingSaves.put(request.key(), request);
        scheduleSaveDrain();
    }

    private void scheduleSaveDrain() {
        if (!saveDrainScheduled.compareAndSet(false, true)) return;
        SAVE_POOL.execute(() -> {
            try {
                while (true) {
                    SaveRequest request = pendingSaves.values().stream().findFirst().orElse(null);
                    if (request == null) break;
                    if (!pendingSaves.remove(request.key(), request)) continue;
                    inFlightSaves.put(request.key(), request);
                    try {
                        write(request);
                    } finally {
                        inFlightSaves.remove(request.key(), request);
                    }
                }
            } finally {
                saveDrainScheduled.set(false);
                if (!pendingSaves.isEmpty()) scheduleSaveDrain();
            }
        });
    }

    private void write(SaveRequest request) {
        File directory = request.directory();
        File target = new File(directory, fileName(request.tileX(), request.tileZ()));
        File temporary = null;
        try {
            Files.createDirectories(directory.toPath());
            temporary = Files.createTempFile(directory.toPath(), target.getName() + ".", ".tmp").toFile();
            TileSnapshot snapshot = request.snapshot();
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(new FileOutputStream(temporary), 64 * 1024)))) {
                output.writeInt(FILE_MAGIC);
                output.writeInt(FILE_VERSION);
                output.writeInt(request.tileX());
                output.writeInt(request.tileZ());
                output.writeInt(COLUMNS);
                for (int column = 0; column < COLUMNS; column++) {
                    int flags = snapshot.countFlags()[column] & 0xFF;
                    output.writeByte(flags);
                    if ((flags & SCANNED_FLAG) == 0) continue;
                    int count = flags & COUNT_MASK;
                    int base = column * MAX_SURFACES_PER_COLUMN;
                    for (int slot = 0; slot < count; slot++) {
                        output.writeShort(snapshot.heights()[base + slot]);
                        output.writeShort(snapshot.bottoms()[base + slot]);
                        output.writeInt(snapshot.colors()[base + slot]);
                    }
                }
            }
            atomicReplace(temporary, target);
            if (request.generation() == generation.get() && sameFile(cacheDirectory, directory)) {
                knownFiles.add(key(request.tileX(), request.tileZ()));
                regionRevisions.putIfAbsent(regionKey(request.tileX() >> 3, request.tileZ() >> 3), 1L);
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to save vertical cave tile {},{}", request.tileX(), request.tileZ(), exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
            }
        }
    }

    private static TileSnapshot read(File file) throws IOException {
        long size = Files.size(file.toPath());
        if (size <= 0L || size > MAX_FILE_BYTES) throw new IOException("Unsafe archive tile size " + size);
        byte[] countFlags = new byte[COLUMNS];
        short[] heights = new short[SLOT_COUNT];
        short[] bottoms = new short[SLOT_COUNT];
        int[] colors = new int[SLOT_COUNT];
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file), 64 * 1024)))) {
            int magic = input.readInt();
            int version = input.readInt();
            input.readInt(); // Stored tile X; filename remains canonical authority.
            input.readInt(); // Stored tile Z.
            int columns = input.readInt();
            if (magic != FILE_MAGIC || version < 1 || version > FILE_VERSION || columns != COLUMNS) {
                throw new IOException("Unsupported vertical cave archive format");
            }
            for (int column = 0; column < COLUMNS; column++) {
                int flags = input.readUnsignedByte();
                int count = flags & COUNT_MASK;
                if (count > MAX_SURFACES_PER_COLUMN) throw new IOException("Invalid surface count " + count);
                // V6 is the first archive captured with a reliable client-side
                // surface/seabed cutoff. Older columns may have been recorded empty
                // when OCEAN_FLOOR was absent, so they must be recaptured from loaded chunks.
                if (version < FILE_VERSION) flags &= ~CURRENT_FLAG;
                countFlags[column] = (byte) flags;
                int base = column * MAX_SURFACES_PER_COLUMN;
                short previous = Short.MAX_VALUE;
                for (int slot = 0; slot < count; slot++) {
                    short y = input.readShort();
                    short bottomY = version >= 3 ? input.readShort() : y;
                    int color = input.readInt();
                    if (y > previous) throw new IOException("Unsorted cave surfaces");
                    if (bottomY > y) throw new IOException("Invalid vertical run");
                    previous = y;
                    heights[base + slot] = y;
                    bottoms[base + slot] = bottomY;
                    colors[base + slot] = color;
                }
            }
            if (input.read() != -1) throw new IOException("Trailing vertical cave archive data");
        } catch (EOFException truncated) {
            throw new IOException("Truncated vertical cave archive", truncated);
        }
        return new TileSnapshot(countFlags, heights, bottoms, colors);
    }

    private static Candidate candidate(TileSnapshot snapshot, int column, int maximumY, int minimumY) {
        int flags = snapshot.countFlags()[column] & 0xFF;
        if ((flags & (SCANNED_FLAG | CURRENT_FLAG))
                != (SCANNED_FLAG | CURRENT_FLAG)) return null;
        int count = flags & COUNT_MASK;
        int base = column * MAX_SURFACES_PER_COLUMN;
        for (int slot = 0; slot < count; slot++) {
            short topY = snapshot.heights()[base + slot];
            short bottomY = snapshot.bottoms()[base + slot];
            if (topY < minimumY) break;
            if (bottomY > maximumY) continue;
            int color = snapshot.colors()[base + slot];
            if (color != 0) return new Candidate(topY, bottomY, color);
        }
        return null;
    }

    private static void applyRelief(int[] colors, short[] heights, byte[] scanned) {
        int[] source = Arrays.copyOf(colors, colors.length);
        for (int z = 0; z < 512; z++) {
            for (int x = 0; x < 512; x++) {
                int index = z * 512 + x;
                if (source[index] == 0 || scanned[index] == 0) continue;
                int center = heights[index];
                int north = neighbourHeight(heights, scanned, x, z - 1, center);
                float shade;
                if (MapConfig.terrainSlopes == 1) {
                    shade = clamp(1.0f + (center - north) * 0.030f, 0.86f, 1.14f);
                } else {
                    int south = neighbourHeight(heights, scanned, x, z + 1, center);
                    int west = neighbourHeight(heights, scanned, x - 1, z, center);
                    int east = neighbourHeight(heights, scanned, x + 1, z, center);
                    float gradientX = clamp((west - east) * 0.5f, -8.0f, 8.0f);
                    float gradientZ = clamp((north - south) * 0.5f, -8.0f, 8.0f);
                    float directional = gradientX * 0.045f + gradientZ * 0.062f;
                    int rim = Math.max(Math.max(north, south), Math.max(west, east));
                    float pit = Math.min(0.30f, Math.max(0, rim - center) * 0.050f);
                    float edge = Math.min(0.16f,
                            (Math.abs(west - east) + Math.abs(north - south)) * 0.012f);
                    float depthOcclusion = multiScaleDepthOcclusion(heights, scanned, x, z, center);
                    shade = clamp(1.0f + directional - pit - edge - depthOcclusion, 0.34f, 1.24f);
                }
                colors[index] = shadeAbgr(source[index], shade);
            }
        }
    }

    private static float multiScaleDepthOcclusion(short[] heights, byte[] scanned,
            int x, int z, int center) {
        float shadow = depthAtRadius(heights, scanned, x, z, center, 2, 0.018f)
                + depthAtRadius(heights, scanned, x, z, center, 8, 0.010f)
                + depthAtRadius(heights, scanned, x, z, center, 24, 0.0035f);
        return Math.min(0.34f, shadow);
    }

    private static float depthAtRadius(short[] heights, byte[] scanned,
            int x, int z, int center, int radius, float weight) {
        int sum = Math.max(0, neighbourHeight(heights, scanned, x - radius, z, center) - center)
                + Math.max(0, neighbourHeight(heights, scanned, x + radius, z, center) - center)
                + Math.max(0, neighbourHeight(heights, scanned, x, z - radius, center) - center)
                + Math.max(0, neighbourHeight(heights, scanned, x, z + radius, center) - center)
                + Math.max(0, neighbourHeight(heights, scanned, x - radius, z - radius, center) - center)
                + Math.max(0, neighbourHeight(heights, scanned, x + radius, z - radius, center) - center)
                + Math.max(0, neighbourHeight(heights, scanned, x - radius, z + radius, center) - center)
                + Math.max(0, neighbourHeight(heights, scanned, x + radius, z + radius, center) - center);
        return Math.min(0.16f, (sum * 0.125f) * weight);
    }

    private static int neighbourHeight(short[] heights, byte[] scanned, int x, int z, int fallback) {
        if (x < 0 || z < 0 || x >= 512 || z >= 512) return fallback;
        int index = z * 512 + x;
        return scanned[index] == 0 || heights[index] == FullCaveMapManager.NO_SURFACE
                ? fallback : heights[index];
    }

    private static int shadeAbgr(int abgr, float shade) {
        int alpha = (abgr >>> 24) & 0xFF;
        int red = Math.max(0, Math.min(255, Math.round((abgr & 0xFF) * shade)));
        int green = Math.max(0, Math.min(255, Math.round(((abgr >>> 8) & 0xFF) * shade)));
        int blue = Math.max(0, Math.min(255, Math.round(((abgr >>> 16) & 0xFF) * shade)));
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void indexExistingFiles() {
        File directory = cacheDirectory;
        if (directory == null) return;
        File[] files = directory.listFiles((ignored, name) -> name.startsWith("t.") && name.endsWith(FILE_EXT));
        if (files == null) return;
        for (File file : files) {
            int[] coordinates = parseFileName(file.getName());
            if (coordinates == null) continue;
            knownFiles.add(key(coordinates[0], coordinates[1]));
            regionRevisions.putIfAbsent(regionKey(coordinates[0] >> 3, coordinates[1] >> 3), 1L);
        }
    }

    private void evictTilesIfNeeded() {
        while (tiles.size() > MAX_LOADED_TILES) {
            Iterator<Map.Entry<String, ArchiveTile>> iterator = tiles.entrySet().iterator();
            Map.Entry<String, ArchiveTile> selected = null;
            while (iterator.hasNext()) {
                Map.Entry<String, ArchiveTile> candidate = iterator.next();
                if (candidate.getValue().isLoaded()) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) return;
            String selectedKey = selected.getKey();
            ArchiveTile tile = selected.getValue();
            iterator.remove();
            boolean dirty;
            synchronized (dirtyTiles) {
                dirty = dirtyTiles.remove(selectedKey);
            }
            if (dirty && cacheDirectory != null) saveTile(tile, cacheDirectory);
            tile.close();
        }
    }

    private void clearTiles() {
        synchronized (tiles) {
            for (ArchiveTile tile : tiles.values()) tile.close();
            tiles.clear();
        }
        synchronized (dirtyTiles) {
            dirtyTiles.clear();
        }
    }

    private SaveRequest latestSave(File directory, int tileX, int tileZ) {
        String key = saveKey(directory, tileX, tileZ);
        SaveRequest request = pendingSaves.get(key);
        return request != null ? request : inFlightSaves.get(key);
    }

    private static String saveKey(File directory, int tileX, int tileZ) {
        return new File(directory, fileName(tileX, tileZ)).toPath().toAbsolutePath().normalize().toString();
    }

    private static String fileName(int tileX, int tileZ) {
        return "t." + tileX + "." + tileZ + FILE_EXT;
    }

    private static int[] parseFileName(String name) {
        if (name == null || !name.startsWith("t.") || !name.endsWith(FILE_EXT)) return null;
        String body = name.substring(2, name.length() - FILE_EXT.length());
        int separator = body.indexOf('.');
        if (separator <= 0 || separator >= body.length() - 1) return null;
        try {
            return new int[] { Integer.parseInt(body.substring(0, separator)),
                    Integer.parseInt(body.substring(separator + 1)) };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String key(int tileX, int tileZ) {
        return tileX + "," + tileZ;
    }

    private static String regionKey(int regionX, int regionZ) {
        return regionX + "," + regionZ;
    }

    private static void quarantine(File file) {
        if (file == null || !file.isFile()) return;
        File target = new File(file.getParentFile(), file.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine vertical cave archive {}", file, exception);
        }
    }

    private static void atomicReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean sameFile(File first, File second) {
        if (first == null || second == null) return first == second;
        return first.getAbsoluteFile().equals(second.getAbsoluteFile());
    }

    public record Candidate(short topY, short bottomY, int color) {
        public Candidate(int y, int color) {
            this(y, y, color);
        }

        public Candidate(int topY, int bottomY, int color) {
            this(clampShort(topY), clampShort(Math.min(topY, bottomY)), color);
        }

        private static short clampShort(int value) {
            return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, value));
        }
    }

    public record Projection(int[] colors, short[] heights, byte[] scanned,
            int scannedColumns, int sourceTiles, long revision) {
        public boolean hasData() {
            return scannedColumns > 0;
        }
    }

    private record TileSnapshot(byte[] countFlags, short[] heights, short[] bottoms, int[] colors) {
        private TileSnapshot {
            countFlags = Arrays.copyOf(countFlags, countFlags.length);
            heights = Arrays.copyOf(heights, heights.length);
            bottoms = Arrays.copyOf(bottoms, bottoms.length);
            colors = Arrays.copyOf(colors, colors.length);
        }
    }

    private record SaveRequest(File directory, int tileX, int tileZ, long generation,
            TileSnapshot snapshot) {
        private String key() {
            return saveKey(directory, tileX, tileZ);
        }
    }

    private static final class ArchiveTile {
        private final int tileX;
        private final int tileZ;
        private final long generation;
        private final byte[] countFlags = new byte[COLUMNS];
        private final short[] heights = new short[SLOT_COUNT];
        private final short[] bottoms = new short[SLOT_COUNT];
        private final int[] colors = new int[SLOT_COUNT];
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private volatile boolean loaded;
        private volatile boolean closed;

        private ArchiveTile(int tileX, int tileZ, long generation) {
            this.tileX = tileX;
            this.tileZ = tileZ;
            this.generation = generation;
        }

        private boolean isLoaded() {
            return loaded && !closed;
        }

        private boolean isClosed() {
            return closed;
        }

        private void markLoaded() {
            if (!closed) loaded = true;
        }

        private void close() {
            closed = true;
        }

        private boolean isColumnScanned(int localX, int localZ) {
            lock.lock();
            try {
                int flags = countFlags[localZ * TILE_SIZE + localX] & 0xFF;
                return !closed && (flags & (SCANNED_FLAG | CURRENT_FLAG))
                        == (SCANNED_FLAG | CURRENT_FLAG);
            } finally {
                lock.unlock();
            }
        }

        private boolean invalidateColumn(int localX, int localZ) {
            lock.lock();
            try {
                if (closed) return false;
                int column = localZ * TILE_SIZE + localX;
                int oldFlags = countFlags[column] & 0xFF;
                int base = column * MAX_SURFACES_PER_COLUMN;
                int oldCount = oldFlags & COUNT_MASK;
                boolean changed = oldFlags != SCANNED_FLAG;
                // Preserve SCANNED_FLAG so projections can actively clear stale
                // layer-cache pixels while this column waits to be recaptured.
                countFlags[column] = (byte) SCANNED_FLAG;
                if (oldCount > 0) {
                    Arrays.fill(heights, base, base + oldCount, (short) 0);
                    Arrays.fill(bottoms, base, base + oldCount, (short) 0);
                    Arrays.fill(colors, base, base + oldCount, 0);
                }
                return changed;
            } finally {
                lock.unlock();
            }
        }

        private Candidate getCandidate(int localX, int localZ, int maximumY, int minimumY) {
            lock.lock();
            try {
                if (closed) return null;
                return candidateLocked(localZ * TILE_SIZE + localX, maximumY, minimumY);
            } finally {
                lock.unlock();
            }
        }

        private int projectInto(int baseX, int baseZ, int maximumY, int minimumY,
                int[] outputColors, short[] outputHeights, byte[] outputScanned) {
            lock.lock();
            try {
                if (closed) return -1;
                int scannedColumns = 0;
                for (int localZ = 0; localZ < TILE_SIZE; localZ++) {
                    for (int localX = 0; localX < TILE_SIZE; localX++) {
                        int column = localZ * TILE_SIZE + localX;
                        int flags = countFlags[column] & 0xFF;
                        if ((flags & SCANNED_FLAG) == 0) continue;
                        int regionIndex = (baseZ + localZ) * 512 + baseX + localX;
                        boolean current = (flags & CURRENT_FLAG) != 0;
                        outputScanned[regionIndex] = (byte) (current ? 1 : 2);
                        scannedColumns++;
                        if (!current) continue;
                        Candidate selected = candidateLocked(column, maximumY, minimumY);
                        if (selected != null) {
                            outputColors[regionIndex] = selected.color();
                            outputHeights[regionIndex] = selected.bottomY();
                        }
                    }
                }
                return scannedColumns;
            } finally {
                lock.unlock();
            }
        }

        private Candidate candidateLocked(int column, int maximumY, int minimumY) {
            int flags = countFlags[column] & 0xFF;
            if ((flags & (SCANNED_FLAG | CURRENT_FLAG))
                    != (SCANNED_FLAG | CURRENT_FLAG)) return null;
            int count = flags & COUNT_MASK;
            int base = column * MAX_SURFACES_PER_COLUMN;
            for (int slot = 0; slot < count; slot++) {
                short topY = heights[base + slot];
                short bottomY = bottoms[base + slot];
                if (topY < minimumY) break;
                if (bottomY > maximumY) continue;
                int color = colors[base + slot];
                if (color != 0) return new Candidate(topY, bottomY, color);
            }
            return null;
        }

        private boolean replaceColumn(int localX, int localZ, Candidate[] candidates) {
            Candidate[] normalized = normalizeCandidates(candidates);
            lock.lock();
            try {
                if (closed) return false;
                int column = localZ * TILE_SIZE + localX;
                int base = column * MAX_SURFACES_PER_COLUMN;
                int oldFlags = countFlags[column] & 0xFF;
                int oldCount = oldFlags & COUNT_MASK;
                boolean changed = (oldFlags & (SCANNED_FLAG | CURRENT_FLAG))
                        != (SCANNED_FLAG | CURRENT_FLAG) || oldCount != normalized.length;
                if (!changed) {
                    for (int slot = 0; slot < oldCount; slot++) {
                        Candidate candidate = normalized[slot];
                        if (heights[base + slot] != candidate.topY()
                                || bottoms[base + slot] != candidate.bottomY()
                                || colors[base + slot] != candidate.color()) {
                            changed = true;
                            break;
                        }
                    }
                }
                if (!changed) return false;
                Arrays.fill(heights, base, base + MAX_SURFACES_PER_COLUMN, (short) 0);
                Arrays.fill(bottoms, base, base + MAX_SURFACES_PER_COLUMN, (short) 0);
                Arrays.fill(colors, base, base + MAX_SURFACES_PER_COLUMN, 0);
                for (int slot = 0; slot < normalized.length; slot++) {
                    heights[base + slot] = normalized[slot].topY();
                    bottoms[base + slot] = normalized[slot].bottomY();
                    colors[base + slot] = normalized[slot].color();
                }
                countFlags[column] = (byte) (SCANNED_FLAG | CURRENT_FLAG | normalized.length);
                return true;
            } finally {
                lock.unlock();
            }
        }

        private static Candidate[] normalizeCandidates(Candidate[] candidates) {
            if (candidates == null || candidates.length == 0) return new Candidate[0];
            return Arrays.stream(candidates)
                    .filter(candidate -> candidate != null && candidate.color() != 0)
                    .map(candidate -> new Candidate(candidate.topY(), candidate.bottomY(), candidate.color()))
                    .sorted((first, second) -> Short.compare(second.topY(), first.topY()))
                    .distinct()
                    .limit(MAX_SURFACES_PER_COLUMN)
                    .toArray(Candidate[]::new);
        }

        private TileSnapshot snapshot() {
            lock.lock();
            try {
                return new TileSnapshot(countFlags, heights, bottoms, colors);
            } finally {
                lock.unlock();
            }
        }

        private void replace(TileSnapshot snapshot) {
            lock.lock();
            try {
                if (closed) return;
                System.arraycopy(snapshot.countFlags(), 0, countFlags, 0, COLUMNS);
                System.arraycopy(snapshot.heights(), 0, heights, 0, SLOT_COUNT);
                System.arraycopy(snapshot.bottoms(), 0, bottoms, 0, SLOT_COUNT);
                System.arraycopy(snapshot.colors(), 0, colors, 0, SLOT_COUNT);
            } finally {
                lock.unlock();
            }
        }
    }
}
