package com.velorise.simplemap.client;

import com.velorise.simplemap.client.persistence.v2.MapPersistenceV2Service;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.velorise.simplemap.client.pipeline.MapWorkGraph;
import com.velorise.simplemap.client.pipeline.MapWorkKey;
import com.velorise.simplemap.client.pipeline.MapWorkStage;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.pipeline.RegionRecord;
import com.velorise.simplemap.client.session.MapSessionManager;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.Writer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Central, lazy-loaded surface map data store. */
public class MapManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapManager INSTANCE = new MapManager();
    private static final int MAX_LOADED_REGIONS = 160;
    private static final int PIXEL_COUNT = 512 * 512;
    /** One corrupt live chunk is enough evidence; isolated legacy fallback pixels are not. */
    private static final int FOREIGN_DIMENSION_COLUMN_THRESHOLD = 64;

    // Saved surface region reads share the global IO control plane.


    /* Region coordinates are queried for every viewport candidate. String keys
     * ("rx,rz") allocated on each lookup and dominated render-thread allocation. */
    private final Long2ObjectLinkedOpenHashMap<Region> loadedRegions =
            new Long2ObjectLinkedOpenHashMap<>(16);
    private final LongOpenHashSet dirtyRegions = new LongOpenHashSet();
    private final Long2BooleanOpenHashMap regionFileExists =
            new Long2BooleanOpenHashMap();
    private final AtomicLong worldGeneration = new AtomicLong(1L);

    private volatile File currentDimensionDir;
    private volatile File currentWorldDir;
    private String currentWorldId = "";
    /** Directory-safe dimension key used by the cache layout. */
    private String currentDimensionId = "";
    /** Canonical registry id, e.g. minecraft:the_nether or modid:moon. */
    private volatile String currentDimensionResourceId = "minecraft:overworld";
    private String liveDimensionId = "overworld";
    private String liveDimensionResourceId = "minecraft:overworld";
    /** Per-ClientLevel world identity cache. This method is called every client
     * tick; resolving/normalising the same server root repeatedly created GiB of
     * Path objects during Cave profiling. */
    private Level observedIdentityLevel;
    private String observedWorldId = "";
    private File observedWorldDirectory;
    private String observedLiveDimensionId = "overworld";
    private String observedLiveDimensionResourceId = "minecraft:overworld";
    /** Non-null while the fullscreen map is browsing a dimension other than the live level. */
    private String viewDimensionOverride;
    private long lastSaveTime;
    private volatile boolean currentDimensionHasSavedData;
    private volatile long lastSavedDataProbeMs;
    /** Immutable filesystem discovery snapshot. Never walk cache directories from
     * minimap/fullscreen rendering or waypoint lookup. */
    private volatile List<String> discoveredDimensionsSnapshot = List.of();

    private boolean hasLearnedMap;
    private String lastLearnedBookId = "";

    private MapManager() {
    }

    public static MapManager getInstance() {
        return INSTANCE;
    }

    public long getGeneration() {
        return worldGeneration.get();
    }

    public boolean isGenerationCurrent(long generation) {
        return generation == worldGeneration.get();
    }

    public boolean hasLearnedMap() {
        return !MapConfig.serverRequireMapBook || hasLearnedMap;
    }

    public String getLastLearnedBookId() {
        return lastLearnedBookId;
    }

    public void setLastLearnedBookId(String bookId) {
        lastLearnedBookId = isCanonicalBookId(bookId) ? bookId.toLowerCase(Locale.ROOT) : "";
        saveLearningState(hasLearnedMap);
    }

    public void openMapScreen() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }

    public void setHasLearnedMap(boolean learned) {
        hasLearnedMap = learned;
        saveLearningState(learned);
    }

    private synchronized void saveLearningState(boolean learned) {
        if (currentWorldDir == null) return;
        Path worldPath = currentWorldDir.toPath();
        Path learnedFile = worldPath.resolve("map_learned.dat");
        Path idFile = worldPath.resolve("last_book_id.dat");
        try {
            Files.createDirectories(worldPath);
            if (!learned) {
                Files.deleteIfExists(learnedFile);
                Files.deleteIfExists(idFile);
                return;
            }

            writeUtf8Atomic(learnedFile, "learned\n");
            if (isCanonicalBookId(lastLearnedBookId)) {
                writeUtf8Atomic(idFile, lastLearnedBookId + "\n");
            } else {
                Files.deleteIfExists(idFile);
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to update map learning state atomically", exception);
        }
    }

    public synchronized void updateWorldAndDimension(Minecraft minecraft) {
        Level level = minecraft.level;
        if (level == null) {
            if (currentDimensionDir != null) flushAndClear();
            else clearObservedWorldIdentity();
            return;
        }

        if (level == observedIdentityLevel && observedWorldDirectory != null
                && !observedWorldId.isEmpty()) {
            liveDimensionId = observedLiveDimensionId;
            liveDimensionResourceId = observedLiveDimensionResourceId;
            if (viewDimensionOverride != null) return;
            if (!liveDimensionId.equals(currentDimensionId)) {
                switchDimensionData(liveDimensionId, liveDimensionResourceId, true);
            }
            return;
        }

        String worldId;
        File worldDirectory;
        if (minecraft.isLocalServer() && minecraft.getSingleplayerServer() != null) {
            try {
                var root = minecraft.getSingleplayerServer().getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT);
                worldId = "sp_" + safePathComponent(root.toAbsolutePath().normalize().toString());
                worldDirectory = root.resolve("simplemap").toFile();
            } catch (Exception exception) {
                worldId = "sp_" + safePathComponent(
                        minecraft.getSingleplayerServer().getWorldData().getLevelName());
                worldDirectory = new File(minecraft.gameDirectory, "simplemap/saves/" + worldId);
            }
        } else if (minecraft.getCurrentServer() != null) {
            String address = minecraft.getCurrentServer().ip;
            worldId = multiplayerWorldId(address);
            worldDirectory = resolveMultiplayerWorldDirectory(minecraft, address, worldId);
        } else {
            worldId = "dim_" + safePathComponent(level.dimension().location().toString());
            worldDirectory = new File(minecraft.gameDirectory, "simplemap/saves/" + worldId);
        }

        String liveResourceId = canonicalDimensionId(level.dimension().location().toString());
        String liveStorageId = dimensionStorageId(liveResourceId);
        liveDimensionResourceId = liveResourceId;
        liveDimensionId = liveStorageId;

        boolean worldChanged = !worldId.equals(currentWorldId)
                || currentWorldDir == null
                || !currentWorldDir.getAbsoluteFile().equals(worldDirectory.getAbsoluteFile());
        if (worldChanged) {
            LOGGER.info("SimpleMap world changed [{}/{}] -> [{}/{}]",
                    currentWorldId, currentDimensionId, worldId, liveStorageId);
            flushAndClear();
            viewDimensionOverride = null;
            currentWorldId = worldId;
            currentWorldDir = worldDirectory;
            if (!worldDirectory.exists() && !worldDirectory.mkdirs()) {
                LOGGER.warn("Could not create SimpleMap world directory {}", worldDirectory);
            }
            loadLearningState(worldDirectory);
            WaypointManager.getInstance().updateWorldDir(worldDirectory);
            configureDimension(liveStorageId, liveResourceId);
            MapSessionManager.getInstance().open(currentWorldId, liveResourceId);
            rememberObservedWorldIdentity(level, worldId, worldDirectory,
                    liveStorageId, liveResourceId);
            return;
        }

        rememberObservedWorldIdentity(level, worldId, worldDirectory,
                liveStorageId, liveResourceId);
        if (viewDimensionOverride != null) return;
        if (!liveStorageId.equals(currentDimensionId)) {
            switchDimensionData(liveStorageId, liveResourceId, true);
        }
    }

    private void rememberObservedWorldIdentity(Level level, String worldId,
            File worldDirectory, String liveStorageId, String liveResourceId) {
        observedIdentityLevel = level;
        observedWorldId = worldId;
        observedWorldDirectory = worldDirectory;
        observedLiveDimensionId = liveStorageId;
        observedLiveDimensionResourceId = liveResourceId;
    }

    private void clearObservedWorldIdentity() {
        observedIdentityLevel = null;
        observedWorldId = "";
        observedWorldDirectory = null;
        observedLiveDimensionId = "overworld";
        observedLiveDimensionResourceId = "minecraft:overworld";
    }

    /** Switches the map data source without changing the player's actual level. */
    public synchronized void switchToDimension(String dimensionId) {
        if (currentWorldDir == null || dimensionId == null || dimensionId.isBlank()) return;
        String canonical = canonicalDimensionId(dimensionId);
        boolean live = canonical.equals(liveDimensionResourceId)
                || "LIVE".equalsIgnoreCase(dimensionId);
        if (live) {
            viewDimensionOverride = null;
            canonical = liveDimensionResourceId;
        } else {
            viewDimensionOverride = canonical;
        }
        String storageId = live ? liveDimensionId : dimensionStorageId(canonical);
        if (storageId.equals(currentDimensionId)
                && canonical.equals(currentDimensionResourceId)) return;
        switchDimensionData(storageId, canonical, true);
    }

    public synchronized void returnToLiveDimension(Minecraft minecraft) {
        viewDimensionOverride = null;
        if (minecraft != null && minecraft.level != null) {
            liveDimensionResourceId = canonicalDimensionId(
                    minecraft.level.dimension().location().toString());
            liveDimensionId = dimensionStorageId(liveDimensionResourceId);
        }
        if (currentWorldDir != null && (!liveDimensionId.equals(currentDimensionId)
                || !liveDimensionResourceId.equals(currentDimensionResourceId))) {
            switchDimensionData(liveDimensionId, liveDimensionResourceId, true);
        }
    }

    /** Switches mutable source ownership while retaining dimension-keyed immutable
     * texture/LOD caches. Old worker commits are rejected by session/generation
     * stamps; inactive GPU pages are reclaimed by the shared residency manager. */
    private void switchDimensionData(String storageId, String resourceId, boolean preserveTextures) {
        // Cancel before clearing managers so no old worker can publish while the
        // dimension-bound stores are in transition.
        MapSessionManager.getInstance().closeActive();
        saveDirtyRegionsAsync();
        MapPersistenceCoordinator.getInstance().reset();
        worldGeneration.incrementAndGet();
        // Packet callbacks target the player's live ClientLevel, not the remote
        // dimension selected by the fullscreen map. Never carry their compact
        // cursors across a map-dimension ownership change.
        MapMutationBus.getInstance().reset();
        MapProcessor.getInstance().clear();
        clearSurfaceDimensionData();
        MapLightManager.getInstance().flushAndClear();
        CaveMapManager.getInstance().flushDataForDimensionSwitch();
        FullCaveMapManager.getInstance().flushDataForDimensionSwitch();
        CaveTextureManager.getInstance().suspendForDimensionSwitch();
        VerticalCaveArchiveManager.getInstance().flushAndClear();
        ChunkScanner.getInstance().reset();
        RegionProcessor.getInstance().stop();
        // Surface exact/legacy keys, overview keys and unified cave keys all include
        // the dimension cache identity. Keep their last-good immutable images so a
        // dimension cycle is a source-context handoff rather than a destructive GPU
        // cold start. A true world change still uses flushAndClear().
        configureDimension(storageId, resourceId);
        MapSessionManager.getInstance().open(currentWorldId, resourceId);
    }

    private void clearSurfaceDimensionData() {
        synchronized (loadedRegions) {
            for (Region region : loadedRegions.values()) region.close();
            loadedRegions.clear();
        }
        synchronized (dirtyRegions) {
            dirtyRegions.clear();
        }
        regionFileExists.clear();
        currentDimensionDir = null;
    }

    private void configureDimension(String storageId, String resourceId) {
        currentDimensionId = storageId;
        currentDimensionResourceId = resourceId;
        currentDimensionDir = new File(currentWorldDir, storageId);
        if (!currentDimensionDir.exists() && !currentDimensionDir.mkdirs()) {
            LOGGER.warn("Could not create SimpleMap dimension directory {}", currentDimensionDir);
        }
        writeDimensionMetadata(currentDimensionDir, resourceId);

        MapLightManager.getInstance().setCacheDirectory(
                new File(currentWorldDir, "light_cache/" + storageId));
        // Cave storage is dimension-scoped, but never dimension-disabled. Xaero
        // keeps the same per-dimension index for Surface, Layered and Full modes;
        // the selected cave-start controls projection without destroying or
        // bypassing the archive. Open dimensions such as The End therefore retain
        // their index while AUTO normally resolves to the surface view.
        CaveMapManager.getInstance().setBaseDirectory(
                new File(currentWorldDir, "caves/" + storageId), currentDimensionDir);
        FullCaveMapManager.getInstance().setCacheDirectory(
                new File(currentWorldDir, "caves/" + storageId + "/full_v3_underground_entry"));
        VerticalCaveArchiveManager.getInstance().setCacheDirectory(
                new File(currentWorldDir, "caves/" + storageId + "/vertical_v3_underground_entry"));
        scanRegionFiles(currentDimensionDir);
        currentDimensionHasSavedData = !regionFileExists.isEmpty()
                || hasAnyMapFiles(currentDimensionDir)
                || hasAnyMapFiles(new File(currentWorldDir, "caves/" + storageId));
        lastSavedDataProbeMs = System.currentTimeMillis();
        discoveredDimensionsSnapshot = List.copyOf(scanDiscoveredDimensions());
        loadPin();
        LOGGER.info("SimpleMap view switched to dimension {} at {}", resourceId, currentDimensionDir);
    }

    /** Canonical ids available to the switcher. Vanilla targets remain safe even
     * before visiting them; modded dimensions are discovered from metadata/cache. */
    public List<String> getSelectableDimensions() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        result.add("minecraft:overworld");
        result.add("minecraft:the_nether");
        result.add("minecraft:the_end");
        result.add(liveDimensionResourceId);
        // Modern servers advertise all currently registered level keys to the
        // client. Reflection keeps this optional across minor mapping/API changes.
        try {
            Object connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                Object levels = connection.getClass().getMethod("levels").invoke(connection);
                if (levels instanceof Iterable<?> iterable) {
                    for (Object levelKey : iterable) {
                        Object location = levelKey.getClass().getMethod("location").invoke(levelKey);
                        if (location != null) result.add(canonicalDimensionId(location.toString()));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Saved metadata/current live level remain a complete safe fallback.
        }
        result.addAll(getDiscoveredDimensions());
        return new ArrayList<>(result);
    }

    public List<String> getDiscoveredDimensions() {
        return new ArrayList<>(discoveredDimensionsSnapshot);
    }

    private List<String> scanDiscoveredDimensions() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (currentWorldDir == null || !currentWorldDir.isDirectory()) return new ArrayList<>();
        File[] subFiles = currentWorldDir.listFiles();
        if (subFiles != null) {
            for (File file : subFiles) {
                if (!file.isDirectory() || file.getName().startsWith(".")
                        || file.getName().equals("light_cache") || file.getName().equals("caves")) continue;
                if (hasAnyMapFiles(file) || new File(file, ".dimension_id").isFile()) {
                    result.add(readDimensionMetadata(file, inferDimensionResourceId(file.getName())));
                }
            }
        }
        File cavesDir = new File(currentWorldDir, "caves");
        File[] caveSub = cavesDir.listFiles();
        if (caveSub != null) {
            for (File file : caveSub) {
                if (!file.isDirectory() || file.getName().startsWith(".")) continue;
                File surfaceDir = new File(currentWorldDir, file.getName());
                if (hasAnyMapFiles(file) || hasAnyMapFiles(surfaceDir)) {
                    result.add(readDimensionMetadata(surfaceDir,
                            inferDimensionResourceId(file.getName())));
                }
            }
        }
        return new ArrayList<>(result);
    }

    public boolean isViewingLiveDimension() {
        return viewDimensionOverride == null;
    }

    /**
     * Dimension fence for data sampled from the live ClientLevel.
     *
     * <p>The fullscreen map can point this manager at a saved remote dimension
     * while packets continue to mutate Minecraft's live level. Callers that own
     * live block/light samples must pass this fence immediately before committing;
     * otherwise Overworld samples can be persisted into an End/Nether region.</p>
     */
    public boolean acceptsLiveLevel(Level level) {
        return level != null
                && viewDimensionOverride == null
                && canonicalDimensionId(level.dimension().location().toString())
                        .equals(currentDimensionResourceId);
    }

    public String getCurrentDimensionResourceId() {
        return currentDimensionResourceId;
    }

    public String getLiveDimensionResourceId() {
        return liveDimensionResourceId;
    }

    /** Stable namespace for texture-cache keys. */
    public String getDimensionCacheKey() {
        return currentDimensionId == null || currentDimensionId.isBlank()
                ? "unknown" : currentDimensionId;
    }

    public boolean hasSavedDataForCurrentDimension() {
        if (!regionFileExists.isEmpty() || currentDimensionHasSavedData) return true;
        long now = System.currentTimeMillis();
        // This method is queried every rendered frame. Probe the filesystem at most
        // once per second so an unvisited dimension remains cheap while newly saved
        // cave-only data is still detected without reopening the screen.
        if (now - lastSavedDataProbeMs < 1_000L) return false;
        lastSavedDataProbeMs = now;
        File cave = currentWorldDir == null ? null
                : new File(currentWorldDir, "caves/" + currentDimensionId);
        currentDimensionHasSavedData = hasAnyMapFiles(currentDimensionDir)
                || hasAnyMapFiles(cave);
        return currentDimensionHasSavedData;
    }

    public static String displayDimensionName(String resourceId) {
        String canonical = canonicalDimensionId(resourceId);
        if (canonical.equals("minecraft:overworld")) return "Overworld";
        if (canonical.equals("minecraft:the_nether")) return "Nether";
        if (canonical.equals("minecraft:the_end")) return "The End";
        int colon = canonical.indexOf(':');
        String namespace = colon >= 0 ? canonical.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? canonical.substring(colon + 1) : canonical;
        String pretty = java.util.Arrays.stream(path.split("[_/.-]+"))
                .filter(part -> !part.isBlank())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
        return namespace.equals("minecraft") ? pretty : pretty + " (" + namespace + ")";
    }

    private boolean hasAnyMapFiles(File dir) {
        return hasAnyMapFiles(dir, 0);
    }

    private boolean hasAnyMapFiles(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 4) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile()) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".smdat") || name.endsWith(".vca")
                        || name.endsWith(".dat") || name.endsWith(".png")) return true;
            } else if (file.isDirectory() && hasAnyMapFiles(file, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private void scanRegionFiles(File directory) {
        regionFileExists.clear();
        if (directory == null || !directory.isDirectory()) return;
        String[] files = directory.list();
        if (files != null) {
            for (String name : files) {
                if (name.startsWith("r.") && name.endsWith(".smdat")) {
                    String[] parts = name.split("\\.");
                    if (parts.length == 4) {
                        try {
                            int rx = Integer.parseInt(parts[1]);
                            int rz = Integer.parseInt(parts[2]);
                            regionFileExists.put(key(rx, rz), true);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
    }

    private void loadLearningState(File worldDirectory) {
        File learnedFile = new File(worldDirectory, "map_learned.dat");
        hasLearnedMap = learnedFile.exists();
        File idFile = new File(worldDirectory, "last_book_id.dat");
        if (!idFile.exists()) {
            lastLearnedBookId = "";
            return;
        }
        try {
            String storedId = Files.readString(idFile.toPath(), StandardCharsets.UTF_8).trim();
            lastLearnedBookId = isCanonicalBookId(storedId) ? storedId : "";
            if (lastLearnedBookId.isEmpty()) Files.deleteIfExists(idFile.toPath());
        } catch (IOException exception) {
            lastLearnedBookId = "";
        }
    }

    public List<String> getLoadedRegionKeysSnapshot() {
        synchronized (loadedRegions) {
            List<String> keys = new ArrayList<>(loadedRegions.size());
            LongIterator iterator = loadedRegions.keySet().iterator();
            while (iterator.hasNext()) {
                long packed = iterator.nextLong();
                keys.add(regionX(packed) + "," + regionZ(packed));
            }
            return keys;
        }
    }

    public MapBlockData getBlockData(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null || !region.isLoaded()) return null;
        return region.getBlockData(blockX & 511, blockZ & 511);
    }

    /**
     * Hot-path counterpart to {@link #getBlockData}. Unknown/empty columns return
     * {@link MapBlockData#EMPTY_PACKED}; callers that only inspect flags/heights do
     * not need to allocate a decoded object.
     */
    public long getPackedBlockData(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null || !region.isLoaded()) return MapBlockData.EMPTY_PACKED;
        return region.getPackedBlockData(blockX & 511, blockZ & 511);
    }

    public int getSurfaceTint(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null || !region.isLoaded()) return SurfaceTintData.UNKNOWN;
        return region.getTint(blockX & 511, blockZ & 511);
    }

    /** O(1) chunk-completion probe used by the chunk-granular live writer. */
    public boolean isChunkSurfaceComplete(int chunkX, int chunkZ) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        return region != null && region.isLoaded()
                && region.isChunkSurfaceComplete((blockX & 511) >>> 4,
                        (blockZ & 511) >>> 4);
    }

    public int getColor(int blockX, int blockZ) {
        long packed = getPackedBlockData(blockX, blockZ);
        if (MapBlockData.isEmpty(packed)) return 0;
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null) return 0;
        List<String> palette = region.snapshotBlockPalette();
        int index = MapBlockData.blockId(packed) & 0xFFFF;
        if (index >= palette.size()) return 0;
        Integer argb = MapTextureManager.getInstance().getBlockColor(palette.get(index));
        if (argb == null) return 0;
        return argbToAbgr(argb);
    }

    public void setBlockData(int blockX, int blockZ, MapBlockData data) {
        setBlockData(blockX, blockZ, data, SurfaceTintData.UNKNOWN);
    }

    public void setBlockData(int blockX, int blockZ, MapBlockData data, int tint) {
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        Region region = getRegion(regionX, regionZ, true);
        if (region == null || !region.isLoaded()) return;
        if (region.setBlockData(blockX & 511, blockZ & 511, data, tint)) {
            markColumnDirty(blockX, blockZ);
            RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
            if (stamp != null) {
                int localChunkX = (blockX & 511) >>> 4;
                int localChunkZ = (blockZ & 511) >>> 4;
                int localChunkIndex = localChunkZ * 32 + localChunkX;
                MapWorkGraph.getInstance().markSourceChunkDirty(stamp,
                        regionX, regionZ, localChunkIndex,
                        region.sourceRevision());
                SurfaceRegionSourceDatabase.getInstance().markChunkDirty(
                        regionX, regionZ, localChunkIndex);
            }
        }
    }

    /**
     * Commits one contiguous row-major slice of a Minecraft 16x16 chunk as a
     * single Surface transaction. Column scanners still resolve the authoritative
     * block/biome/tint payload on the client thread. Region storage is committed
     * once per slice; exact-page invalidation, persistence and retained-source
     * publication happen only when the full 16x16 transaction reaches cursor 256.
     */
    public SurfaceChunkCommit commitSurfaceChunkSlice(int chunkX, int chunkZ,
            int startColumn, long[] packedPixels, int[] surfaceTints,
            boolean[] validColumns, int sourceOffset, int count) {
        if (packedPixels == null || surfaceTints == null || validColumns == null
                || startColumn < 0 || startColumn >= 256 || sourceOffset < 0
                || count <= 0 || sourceOffset + count > packedPixels.length
                || sourceOffset + count > surfaceTints.length
                || sourceOffset + count > validColumns.length) {
            return SurfaceChunkCommit.empty();
        }
        int safeCount = Math.min(count, 256 - startColumn);
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        Region region = getRegion(regionX, regionZ, true);
        if (region == null || !region.isLoaded()) return SurfaceChunkCommit.empty();
        int localChunkX = (blockX & 511) >>> 4;
        int localChunkZ = (blockZ & 511) >>> 4;
        SurfaceChunkCommit result = region.commitSurfaceChunkSlice(
                localChunkX, localChunkZ, startColumn, packedPixels,
                surfaceTints, validColumns, sourceOffset, safeCount);
        // Exact/source/persistence authority remains on the previous complete
        // 16x16 tile until finishSurfaceChunkTransaction() at cursor 256.
        return result;
    }

    /** Publishes one completed 16x16 Surface scan transaction to downstream work. */
    public SurfaceChunkCommit finishSurfaceChunkTransaction(int chunkX, int chunkZ) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        Region region = getRegion(regionX, regionZ, false);
        if (region == null || !region.isLoaded()) return SurfaceChunkCommit.empty();
        int localChunkX = (blockX & 511) >>> 4;
        int localChunkZ = (blockZ & 511) >>> 4;
        long revision = region.finishSurfaceChunkTransaction(localChunkX, localChunkZ);
        if (revision <= 0L) return new SurfaceChunkCommit(0, false, revision);
        markRegionStorageDirty(regionX, regionZ);
        MapTextureManager.getInstance().markPageDirtyForChunk(chunkX, chunkZ);
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp != null) {
            int localChunkIndex = localChunkZ * 32 + localChunkX;
            MapWorkGraph.getInstance().markSourceChunkDirty(stamp,
                    regionX, regionZ, localChunkIndex, revision);
            SurfaceRegionSourceDatabase.getInstance().markChunkDirty(
                    regionX, regionZ, localChunkIndex);
        }
        return new SurfaceChunkCommit(0, true, revision);
    }

    public void markRegionDirty(int regionX, int regionZ) {
        markRegionStorageDirty(regionX, regionZ);
        MapTextureManager.getInstance().markRegionDirty(regionX, regionZ);
        // Far-zoom standalone overview textures are a compatibility/fallback path
        // beside the page-rooted LOD tree. Keep their source revision in sync with
        // live scans so they do not remain stale until MapScreen is opened.
        MapOverviewTextureManager.getInstance().markSurfaceRegionDirty(regionX, regionZ);
    }

    private void markColumnDirty(int blockX, int blockZ) {
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        markRegionStorageDirty(regionX, regionZ);
        MapTextureManager.getInstance().markPageDirtyForBlock(blockX, blockZ);
    }

    private void markRegionStorageDirty(int regionX, int regionZ) {
        long key = key(regionX, regionZ);
        synchronized (dirtyRegions) {
            dirtyRegions.add(key);
        }
        regionFileExists.put(key, true);
    }

    public Region getRegion(int regionX, int regionZ, boolean loadIfMissing) {
        long key = key(regionX, regionZ);
        synchronized (loadedRegions) {
            Region existing = loadedRegions.getAndMoveToLast(key);
            if (existing != null || !loadIfMissing || currentDimensionDir == null) return existing;

            long generation = worldGeneration.get();
            Region created = new Region(regionX, regionZ, generation);
            loadedRegions.putAndMoveToLast(key, created);
            File directory = currentDimensionDir;
            if (hasRegionFile(regionX, regionZ)) {
                loadRegionAsync(created, directory, generation);
            } else {
                created.markLoaded();
            }
            evictOldRegions();
            return created;
        }
    }

    public void requestRegionLoad(int regionX, int regionZ) {
        getRegion(regionX, regionZ, true);
    }

    /**
     * M2 bridge: keep SOURCE_READ running until this region's async disk read
     * has actually committed its CPU data. A queue admission is not a source
     * completion.
     */
    public boolean requestRegionLoad(MapWorkKey workKey, long sourceRevision) {
        if (workKey == null || !isGenerationCurrent(sourceRevision)) return false;
        Region region = getRegion(workKey.regionX(), workKey.regionZ(), true);
        if (region == null) return false;
        region.registerSourceCompletion(workKey, sourceRevision);
        return true;
    }

    public void unloadRegion(int regionX, int regionZ) {
        long key = key(regionX, regionZ);
        Region removed;
        synchronized (loadedRegions) {
            removed = loadedRegions.remove(key);
        }
        synchronized (dirtyRegions) {
            dirtyRegions.remove(key);
        }
        regionFileExists.remove(key);
        if (removed != null) removed.close();
    }

    public void invalidateRegionFile(int regionX, int regionZ) {
        regionFileExists.remove(key(regionX, regionZ));
    }

    public boolean hasRegionFile(int regionX, int regionZ) {
        File directory = currentDimensionDir;
        if (directory == null) return false;
        long key = key(regionX, regionZ);
        synchronized (regionFileExists) {
            if (regionFileExists.containsKey(key)) return regionFileExists.get(key);
        }
        boolean exists = RegionDataStore.hasFile(directory, regionX, regionZ);
        synchronized (regionFileExists) {
            regionFileExists.put(key, exists);
        }
        return exists;
    }

    public boolean isRegionLoadedInCache(int regionX, int regionZ) {
        synchronized (loadedRegions) {
            return loadedRegions.containsKey(key(regionX, regionZ));
        }
    }

    public String getCurrentDimensionId() {
        return currentDimensionId;
    }

    /** Resolves vanilla names, cache folder ids and advertised modded ids to a registry id. */
    public synchronized String resolveDimensionResourceId(String value) {
        if (value == null || value.isBlank() || "LIVE".equalsIgnoreCase(value)) {
            return liveDimensionResourceId;
        }
        if (value.equals(currentDimensionId)) return currentDimensionResourceId;
        if (value.equals(liveDimensionId)) return liveDimensionResourceId;
        // Render overlays pass canonical registry ids. Return those without
        // connection reflection or a cache-directory discovery walk.
        if (value.indexOf(':') >= 0) return canonicalDimensionId(value);
        for (String candidate : getSelectableDimensions()) {
            if (value.equals(candidate) || value.equals(dimensionStorageId(candidate))) {
                return canonicalDimensionId(candidate);
            }
        }
        return canonicalDimensionId(value);
    }

    public static String normalizeDimensionResourceId(String value) {
        return canonicalDimensionId(value);
    }

    public File getCurrentDimensionDir() {
        return currentDimensionDir;
    }

    /**
     * Re-colors and reloads all saved regions without removing the currently
     * displayed image. Existing CPU/GPU pages remain the visual fallback while
     * replacement revisions are built and uploaded region by region.
     */
    public void reloadAllRegions() {
        MapSessionManager.getInstance().bumpStyleGeneration();
        MapTextureManager.getInstance().clearDerivedColorCaches();
        MapVisualClassifier.getInstance().clear();
        MapBlockEntityVisualResolver.getInstance().clear();
        com.velorise.simplemap.client.cave.CaveStateClassifier.getInstance().clear();

        // Do not increment worldGeneration and do not clear any texture/data store.
        // Generation changes are reserved for a real world/dimension replacement.
        MapTextureManager.getInstance().invalidateStyle();
        MapOverviewTextureManager.getInstance().invalidateStyle();
        CaveTextureManager.getInstance().invalidateStyle();
        FullCaveTextureManager.getInstance().invalidateStyle();

        // Do not enqueue every .smdat file in filesystem order. That old path
        // saturated the worker queue and made Reload All reveal random distant
        // islands. The viewport coordinator now re-admits visible regions through
        // the deterministic region sweep; off-screen regions are restyled lazily.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            ChunkScanner.getInstance().requestRefresh(mc);
        }
    }

    /** Includes disk regions plus loaded unsaved regions for map-book snapshots. */
    public List<String> getKnownRegionNamesForBook(int maximum) {
        int limit = Math.max(0, maximum);
        TreeSet<String> names = new TreeSet<>();
        File directory = currentDimensionDir;
        if (directory != null) {
            File[] files = directory.listFiles((ignored, name) ->
                    name != null && name.matches("r\\.-?\\d{1,7}\\.-?\\d{1,7}\\.smdat"));
            if (files != null) {
                for (File file : files) names.add(file.getName());
            }
        }
        synchronized (loadedRegions) {
            for (Region region : loadedRegions.values()) {
                if (region.isLoaded() && region.hasAnyData()) {
                    names.add(RegionDataStore.fileName(region.rx, region.rz));
                }
            }
        }
        List<String> result = new ArrayList<>(Math.min(limit, names.size()));
        for (String name : names) {
            if (result.size() >= limit) break;
            result.add(name);
        }
        return result;
    }

    /** Returns a canonical snapshot of a loaded region, including unsaved edits. */
    public RegionDataStore.StoredRegion snapshotStoredRegion(int rx, int rz) {
        synchronized (loadedRegions) {
            Region region = loadedRegions.get(key(rx, rz));
            if (region == null || !region.isLoaded()) return null;
            RegionSnapshot snapshot = region.snapshot();
            return new RegionDataStore.StoredRegion(snapshot.pixels(), snapshot.tints(),
                    snapshot.biomePalette(), snapshot.blockPalette(),
                    snapshot.completeChunks());
        }
    }

    private void loadRegionAsync(Region region, File directory, long generation) {
        if (!region.beginLoad()) return;
        // Keep the same Region object in the loaded map while IO is saturated.
        // Xaero keeps requested regions in a pull queue; repeatedly removing and
        // recreating them wastes allocations and can leave visible holes near the
        // viewport centre.
        MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                MapRequestLane.FULLSCREEN, MapWorkScheduler.WorkType.DISK_READ,
                MapRequestLane.FULLSCREEN.priorityBase(), 24,
                () -> isLoadTargetCurrent(region, directory, generation), () -> {
            try {
                long[] pixels = new long[PIXEL_COUNT];
                int[] tints = new int[PIXEL_COUNT];
                long[] completeChunks = new long[RegionDataStore.COMPLETE_CHUNK_WORDS];
                Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
                Arrays.fill(tints, SurfaceTintData.UNKNOWN);
                List<String> biomePalette = new ArrayList<>();
                List<String> blockPalette = new ArrayList<>();
                boolean loaded = RegionDataStore.load(directory, region.rx, region.rz,
                        pixels, tints, completeChunks, biomePalette, blockPalette);
                if (!loaded) {
                    quarantineRegion(directory, region.rx, region.rz, generation,
                            "corrupt", "unreadable");
                } else if (isLoadTargetCurrent(region, directory, generation)
                        && hasForeignVanillaBiome(currentDimensionResourceId,
                                pixels, biomePalette)) {
                    MapDebugRecorder.getInstance().event(
                            "SURFACE_REGION_DIMENSION_MISMATCH",
                            "dimension=" + currentDimensionResourceId
                                    + " region=" + region.rx + ',' + region.rz
                                    + " action=quarantine_and_rebuild");
                    quarantineRegion(directory, region.rx, region.rz, generation,
                            "dimension-mismatch", "cross-dimension");
                    Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
                    Arrays.fill(tints, SurfaceTintData.UNKNOWN);
                    Arrays.fill(completeChunks, 0L);
                    biomePalette.clear();
                    blockPalette.clear();
                    loaded = false;
                }
                if (!isLoadTargetCurrent(region, directory, generation)) return;
                region.applyLoadedData(pixels, tints, completeChunks,
                        biomePalette, blockPalette);
                region.markLoaded();
                region.completeSourceCompletions();
                if (loaded) MapTextureManager.getInstance()
                        .markRegionSourceAvailable(region.rx, region.rz);
                synchronized (loadedRegions) {
                    evictOldRegions();
                }
            } finally {
                region.finishLoad();
            }
        });
    }

    private boolean isLoadTargetCurrent(Region region, File directory, long generation) {
        if (!isGenerationCurrent(generation) || currentDimensionDir == null
                || !currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) return false;
        synchronized (loadedRegions) {
            return loadedRegions.get(key(region.rx, region.rz)) == region;
        }
    }

    public void tickSave() {
        MapPersistenceCoordinator.getInstance().tick(this);
    }

    /** Pulls at most {@code maximum} dirty surface regions into persistence. */
    int pumpDirtyRegionSaves(int maximum) {
        File directory = currentDimensionDir;
        if (directory == null || maximum <= 0) return dirtyRegionCount();
        int admitted = 0;
        while (admitted < maximum) {
            long selected;
            synchronized (dirtyRegions) {
                LongIterator iterator = dirtyRegions.iterator();
                if (!iterator.hasNext()) break;
                selected = iterator.nextLong();
                iterator.remove();
            }
            Region region;
            synchronized (loadedRegions) {
                region = loadedRegions.get(selected);
            }
            if (region == null || !region.isLoaded()) continue;
            if (!RegionDataStore.canAcceptSave(directory, region.rx, region.rz)
                    || !saveRegionSnapshot(region, directory, false)) {
                synchronized (dirtyRegions) {
                    dirtyRegions.add(selected);
                }
                break;
            }
            admitted++;
        }
        return dirtyRegionCount();
    }

    int dirtyRegionCount() {
        synchronized (dirtyRegions) {
            return dirtyRegions.size();
        }
    }

    public synchronized void flushAndClear() {
        saveDirtyRegionsAsync();
        MapSessionManager.getInstance().closeActive();
        MapPersistenceCoordinator.getInstance().reset();
        worldGeneration.incrementAndGet();
        MapProcessor.getInstance().clear();
        clearCache();
        MapTextureManager.getInstance().clearCache();
        MapOverviewTextureManager.getInstance().clearCache();
        MapLightManager.getInstance().flushAndClear();
        CaveMapManager.getInstance().flushAndClear();
        FullCaveMapManager.getInstance().flushAndClear();
        CaveTextureManager.getInstance().clearCache();
        FullCaveTextureManager.getInstance().clearCache();
        VerticalCaveArchiveManager.getInstance().flushAndClear();
        ChunkScanner.getInstance().reset();
        RegionProcessor.getInstance().stop();
    }

    private void clearCache() {
        synchronized (loadedRegions) {
            for (Region region : loadedRegions.values()) region.close();
            loadedRegions.clear();
        }
        synchronized (dirtyRegions) {
            dirtyRegions.clear();
        }
        regionFileExists.clear();
        currentDimensionDir = null;
        currentWorldDir = null;
        currentWorldId = "";
        currentDimensionId = "";
        currentDimensionResourceId = "minecraft:overworld";
        liveDimensionId = "overworld";
        liveDimensionResourceId = "minecraft:overworld";
        clearObservedWorldIdentity();
        viewDimensionOverride = null;
        currentDimensionHasSavedData = false;
        lastSavedDataProbeMs = 0L;
        discoveredDimensionsSnapshot = List.of();
        MapConfig.pinActive = false;
        MapConfig.pinWorldX = 0;
        MapConfig.pinWorldZ = 0;
    }

    private void saveDirtyRegionsAsync() {
        File directory = currentDimensionDir;
        if (directory == null) return;
        LongOpenHashSet keys;
        synchronized (dirtyRegions) {
            if (dirtyRegions.isEmpty()) return;
            keys = new LongOpenHashSet(dirtyRegions);
            dirtyRegions.clear();
        }
        // Dimension/world replacement is the rare forced-flush path. Snapshot all
        // remaining dirty regions before their canonical CPU objects are closed.
        LongIterator dirtyIterator = keys.iterator();
        while (dirtyIterator.hasNext()) {
            long key = dirtyIterator.nextLong();
            Region region;
            synchronized (loadedRegions) {
                region = loadedRegions.get(key);
            }
            if (region != null && region.isLoaded()) saveRegionSnapshot(region, directory, true);
        }
    }

    private boolean saveRegionSnapshot(Region region, File directory, boolean force) {
        if (region == null || directory == null || !region.isLoaded()) return false;
        if (!force && !RegionDataStore.canAcceptSave(directory, region.rx, region.rz)) return false;
        RegionSnapshot snapshot = region.snapshot();
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        MapWorkKey workKey = stamp == null ? null : new MapWorkKey(stamp,
                region.rx, region.rz, MapWorkStage.CACHE_COMMIT, Integer.MIN_VALUE);
        MapWorkGraph graph = MapWorkGraph.getInstance();
        if (workKey != null) graph.request(workKey, snapshot.sourceRevision(),
                RegionRecord.ALL_LEAVES);
        final long persistenceWorldIdentity = (currentWorldId == null ? 0L
                : currentWorldId.hashCode() * 0x9E3779B97F4A7C15L)
                ^ directory.getAbsolutePath().hashCode();
        final long persistenceStyleRevision = stamp == null
                ? 0L : stamp.styleGeneration();

        java.util.function.Consumer<RegionDataStore.SaveResult> completion = result -> {
            if (workKey == null) return;
            if (result.success()) {
                graph.markCacheCommitted(workKey, snapshot.sourceRevision(),
                        RegionRecord.ALL_LEAVES);
                MapPersistenceV2Service.getInstance().appendSurface(directory,
                        persistenceWorldIdentity, region.rx, region.rz,
                        snapshot.sourceRevision(), persistenceStyleRevision,
                        new RegionDataStore.StoredRegion(snapshot.pixels(),
                                snapshot.tints(), snapshot.biomePalette(),
                                snapshot.blockPalette(), snapshot.completeChunks()));
            } else {
                graph.markCacheDirty(workKey, snapshot.sourceRevision(),
                        RegionRecord.ALL_LEAVES);
            }
        };

        boolean accepted;
        if (force) {
            RegionDataStore.saveAsync(directory, region.rx, region.rz,
                    snapshot.pixels(), snapshot.tints(), snapshot.biomePalette(),
                    snapshot.blockPalette(), snapshot.completeChunks(), completion);
            accepted = true;
        } else {
            accepted = RegionDataStore.trySaveOwnedAsync(directory, region.rx, region.rz,
                    snapshot.pixels(), snapshot.tints(), snapshot.biomePalette(),
                    snapshot.blockPalette(), snapshot.completeChunks(), completion);
        }
        if (accepted) regionFileExists.put(key(region.rx, region.rz), true);
        else if (workKey != null) graph.markCacheDirty(workKey,
                snapshot.sourceRevision(), RegionRecord.ALL_LEAVES);
        return accepted;
    }

    private void evictOldRegions() {
        while (loadedRegions.size() > MAX_LOADED_REGIONS) {
            long selectedKey = Long.MIN_VALUE;
            Region region = null;
            for (Long2ObjectMap.Entry<Region> candidate
                    : loadedRegions.long2ObjectEntrySet()) {
                if (candidate.getValue().isLoaded()) {
                    selectedKey = candidate.getLongKey();
                    region = candidate.getValue();
                    break;
                }
            }
            if (region == null) return;
            loadedRegions.remove(selectedKey);
            boolean dirty;
            synchronized (dirtyRegions) {
                dirty = dirtyRegions.remove(selectedKey);
            }
            if (dirty && currentDimensionDir != null
                    && !saveRegionSnapshot(region, currentDimensionDir, false)) {
                loadedRegions.putAndMoveToLast(selectedKey, region);
                synchronized (dirtyRegions) {
                    dirtyRegions.add(selectedKey);
                }
                return;
            }
            region.close();
        }
    }

    private static boolean hasForeignVanillaBiome(String dimension,
            long[] pixels, List<String> biomePalette) {
        if (pixels == null || biomePalette == null
                || !("minecraft:the_end".equals(dimension)
                        || "minecraft:the_nether".equals(dimension)
                        || "minecraft:overworld".equals(dimension))) return false;
        int foreignColumns = 0;
        for (long packed : pixels) {
            if (MapBlockData.isEmpty(packed)) continue;
            int index = MapBlockData.biomeId(packed) & 0xFF;
            if (index >= biomePalette.size()) continue;
            String biome = biomePalette.get(index);
            if (!isForeignVanillaBiome(dimension, biome)) continue;
            if (++foreignColumns >= FOREIGN_DIMENSION_COLUMN_THRESHOLD) return true;
        }
        return false;
    }

    private static boolean isForeignVanillaBiome(String dimension, String biome) {
        if (biome == null || !biome.startsWith("minecraft:")
                || "minecraft:plains".equals(biome)) return false;
        boolean end = switch (biome) {
            case "minecraft:the_end", "minecraft:end_highlands",
                    "minecraft:end_midlands", "minecraft:end_barrens",
                    "minecraft:small_end_islands" -> true;
            default -> false;
        };
        boolean nether = switch (biome) {
            case "minecraft:nether_wastes", "minecraft:soul_sand_valley",
                    "minecraft:crimson_forest", "minecraft:warped_forest",
                    "minecraft:basalt_deltas" -> true;
            default -> false;
        };
        if ("minecraft:the_end".equals(dimension)) return !end;
        if ("minecraft:the_nether".equals(dimension)) return !nether;
        return end || nether;
    }

    private void quarantineRegion(File directory, int rx, int rz,
            long requestedGeneration, String suffix, String description) {
        File file = new File(directory, RegionDataStore.fileName(rx, rz));
        if (!file.isFile() || RegionDataStore.hasPending(directory, rx, rz)) return;
        File quarantine = new File(directory,
                file.getName() + "." + suffix + "." + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), quarantine.toPath());
            if (isGenerationCurrent(requestedGeneration)
                    && currentDimensionDir != null
                    && currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) {
                regionFileExists.put(key(rx, rz), false);
            }
            LOGGER.warn("Moved {} SimpleMap region to {}", description,
                    quarantine.getName());
        } catch (IOException exception) {
            if (isGenerationCurrent(requestedGeneration)
                    && currentDimensionDir != null
                    && currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) {
                regionFileExists.remove(key(rx, rz));
            }
            LOGGER.warn("Could not quarantine {} SimpleMap region {}",
                    description, file, exception);
        }
    }


    private static boolean isCanonicalBookId(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void writeUtf8Atomic(Path target, String contents) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Atomic write target has no parent: " + target);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            moveReplacing(temporary, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static String multiplayerWorldId(String address) {
        String normalized = address == null ? "unknown" : address.trim().toLowerCase(Locale.ROOT);
        String readable = safePathComponent(normalized);
        if (readable.length() > 48) readable = readable.substring(0, 48);
        return "mp_" + readable + "_" + shortSha256(normalized);
    }

    private static File resolveMultiplayerWorldDirectory(Minecraft minecraft, String address, String worldId) {
        File savesRoot = new File(minecraft.gameDirectory, "simplemap/saves");
        File target = new File(savesRoot, worldId);
        String legacyId = "mp_" + safePathComponent(address);
        File legacy = new File(savesRoot, legacyId);
        if (!target.exists() && legacy.isDirectory() && !legacy.equals(target)) {
            try {
                Files.createDirectories(savesRoot.toPath());
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                LOGGER.info("Migrated legacy multiplayer map cache {} -> {}",
                        legacy.getName(), target.getName());
            } catch (IOException atomicFailure) {
                try {
                    Files.move(legacy.toPath(), target.toPath());
                    LOGGER.info("Migrated legacy multiplayer map cache {} -> {}",
                            legacy.getName(), target.getName());
                } catch (IOException moveFailure) {
                    LOGGER.warn("Could not migrate legacy multiplayer map cache {}; continuing to use it",
                            legacy, moveFailure);
                    return legacy;
                }
            }
        }
        return target;
    }

    private static String shortSha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                builder.append(String.format("%02x", digest[index] & 0xFF));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    private static String canonicalDimensionId(String value) {
        if (value == null || value.isBlank() || "LIVE".equalsIgnoreCase(value)) {
            return "minecraft:overworld";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("overworld") || normalized.equals("minecraft_overworld")) {
            return "minecraft:overworld";
        }
        if (normalized.equals("nether") || normalized.equals("the_nether")
                || normalized.equals("minecraft_the_nether")) {
            return "minecraft:the_nether";
        }
        if (normalized.equals("end") || normalized.equals("the_end")
                || normalized.equals("minecraft_the_end")) {
            return "minecraft:the_end";
        }
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private static String dimensionStorageId(String resourceId) {
        String canonical = canonicalDimensionId(resourceId);
        int colon = canonical.indexOf(':');
        String namespace = colon >= 0 ? canonical.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? canonical.substring(colon + 1) : canonical;
        return namespace.equals("minecraft") ? safePathComponent(path)
                : safePathComponent(namespace + ":" + path);
    }

    private static String inferDimensionResourceId(String storageId) {
        if (storageId == null) return "minecraft:overworld";
        if (storageId.equals("overworld")) return "minecraft:overworld";
        if (storageId.equals("the_nether") || storageId.equals("nether")) return "minecraft:the_nether";
        if (storageId.equals("the_end") || storageId.equals("end")) return "minecraft:the_end";
        // Legacy custom directories replaced ':' with '_'. Metadata written on the
        // next live visit removes this ambiguity; until then preserve the folder id.
        return storageId.contains(":") ? storageId : "simplemap-cache:" + storageId;
    }

    private static void writeDimensionMetadata(File directory, String resourceId) {
        if (directory == null || resourceId == null || resourceId.isBlank()) return;
        try {
            Files.createDirectories(directory.toPath());
            writeUtf8Atomic(directory.toPath().resolve(".dimension_id"),
                    canonicalDimensionId(resourceId) + "\n");
        } catch (IOException exception) {
            LOGGER.debug("Could not write dimension metadata for {}", directory, exception);
        }
    }

    private static String readDimensionMetadata(File directory, String fallback) {
        if (directory == null) return fallback;
        File metadata = new File(directory, ".dimension_id");
        if (!metadata.isFile()) return fallback;
        try {
            String value = Files.readString(metadata.toPath(), StandardCharsets.UTF_8).trim();
            return value.isBlank() ? fallback : canonicalDimensionId(value);
        } catch (IOException exception) {
            return fallback;
        }
    }

    private static String safePathComponent(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_");
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (cleaned.isBlank()) return "unknown";
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private static int argbToAbgr(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFF_FFFFL);
    }

    private static int regionX(long key) { return (int) (key >> 32); }
    private static int regionZ(long key) { return (int) key; }

    public static final class Region {
        public final int rx;
        public final int rz;
        private final long generation;
        private final long[] pixels = new long[PIXEL_COUNT];
        private final int[] tints = new int[PIXEL_COUNT];
        private final int[] knownColumnsPerPage = new int[MapPageLayout.PAGES_PER_REGION * MapPageLayout.PAGES_PER_REGION];
        /** One exact count per Minecraft chunk; 256 means the tile is publishable. */
        private final short[] knownColumnsPerChunk = new short[32 * 32];
        /** A completed scan is authoritative even when some/all columns are void. */
        private final long[] completeChunks =
                new long[RegionDataStore.COMPLETE_CHUNK_WORDS];
        /** O(1) explored-data test; maintained with page/chunk coverage counters. */
        private int knownColumnCount;
        private final List<String> biomePalette = new ArrayList<>();
        private final List<String> blockPalette = new ArrayList<>();
        private final Map<String, Integer> biomeIndex = new HashMap<>();
        private final Map<String, Integer> blockIndex = new HashMap<>();
        private final List<SourceCompletion> sourceCompletions = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean loaded;
        private volatile boolean closed;
        private final AtomicBoolean loadScheduled = new AtomicBoolean();
        private long sourceRevision = 1L;
        private long paletteRevision = 1L;
        private final long[] chunkRevisions = new long[32 * 32];

        private Region(int rx, int rz, long generation) {
            this.rx = rx;
            this.rz = rz;
            this.generation = generation;
            Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
            Arrays.fill(tints, SurfaceTintData.UNKNOWN);
        }

        public String getCacheKey() { return rx + "," + rz; }
        public boolean isLoaded() { return loaded && !closed; }
        public long getGeneration() { return generation; }
        public void lock() { lock.lock(); }
        public void unlock() { lock.unlock(); }
        public void close() {
            closed = true;
            deferSourceCompletions();
        }
        boolean beginLoad() { return !closed && loadScheduled.compareAndSet(false, true); }
        void finishLoad() { loadScheduled.set(false); }
        void markLoaded() { if (!closed) loaded = true; }
        void registerSourceCompletion(MapWorkKey key, long revision) {
            if (key == null) return;
            boolean completeNow = false;
            boolean deferNow = false;
            synchronized (sourceCompletions) {
                if (closed) deferNow = true;
                else if (loaded) completeNow = true;
                else sourceCompletions.add(new SourceCompletion(key, revision));
            }
            if (completeNow) MapWorkGraph.getInstance().complete(key, revision);
            if (deferNow) MapWorkGraph.getInstance().defer(key);
        }

        void completeSourceCompletions() {
            synchronized (sourceCompletions) {
                if (sourceCompletions.isEmpty()) return;
                for (int i = 0; i < sourceCompletions.size(); i++) {
                    SourceCompletion completion = sourceCompletions.get(i);
                    MapWorkGraph.getInstance().complete(completion.key(), completion.revision());
                }
                sourceCompletions.clear();
            }
        }

        private void deferSourceCompletions() {
            synchronized (sourceCompletions) {
                if (sourceCompletions.isEmpty()) return;
                for (int i = 0; i < sourceCompletions.size(); i++) {
                    SourceCompletion completion = sourceCompletions.get(i);
                    MapWorkGraph.getInstance().defer(completion.key());
                }
                sourceCompletions.clear();
            }
        }

        private record SourceCompletion(MapWorkKey key, long revision) {
        }

        public MapBlockData getBlockData(int pixelX, int pixelZ) {
            lock.lock();
            try {
                long packed = pixels[pixelZ * 512 + pixelX];
                return MapBlockData.isEmpty(packed) ? null : MapBlockData.unpack(packed);
            } finally {
                lock.unlock();
            }
        }

        public long getPackedBlockData(int pixelX, int pixelZ) {
            lock.lock();
            try {
                return pixels[pixelZ * 512 + pixelX];
            } finally {
                lock.unlock();
            }
        }

        public int getTint(int pixelX, int pixelZ) {
            lock.lock();
            try {
                return tints[pixelZ * 512 + pixelX];
            } finally {
                lock.unlock();
            }
        }

        /** O(1) exact-leaf explored-data test used by the fullscreen frontier. */
        public boolean hasAnyDataInPage(int pageX, int pageZ) {
            if (pageX < 0 || pageX >= MapPageLayout.PAGES_PER_REGION
                    || pageZ < 0 || pageZ >= MapPageLayout.PAGES_PER_REGION) {
                return false;
            }
            lock.lock();
            try {
                return knownColumnsPerPage[
                        pageZ * MapPageLayout.PAGES_PER_REGION + pageX] > 0;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns the 4x4 chunk mask whose source columns are complete. This is
         * the authoritative target coverage for progressive 16x16 publication.
         */
        public int completeChunkMaskInPage(int pageX, int pageZ) {
            if (pageX < 0 || pageX >= MapPageLayout.PAGES_PER_REGION
                    || pageZ < 0 || pageZ >= MapPageLayout.PAGES_PER_REGION) {
                return 0;
            }
            lock.lock();
            try {
                int result = 0;
                int startChunkX = pageX * MapPageLayout.SUBTILES_PER_PAGE;
                int startChunkZ = pageZ * MapPageLayout.SUBTILES_PER_PAGE;
                for (int localZ = 0; localZ < MapPageLayout.SUBTILES_PER_PAGE; localZ++) {
                    for (int localX = 0; localX < MapPageLayout.SUBTILES_PER_PAGE; localX++) {
                        int chunkIndex = (startChunkZ + localZ) * 32
                                + startChunkX + localX;
                        if (chunkCompleteUnsafe(chunkIndex)) {
                            result |= 1 << MapPageLayout.subtileIndex(localX, localZ);
                        }
                    }
                }
                return result;
            } finally {
                lock.unlock();
            }
        }

        public boolean isChunkSurfaceComplete(int localChunkX, int localChunkZ) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return false;
            lock.lock();
            try {
                return chunkCompleteUnsafe(localChunkZ * 32 + localChunkX);
            } finally {
                lock.unlock();
            }
        }

        /** Marks the row-major 16x16 transaction complete, including known void. */
        public long finishSurfaceChunkTransaction(int localChunkX, int localChunkZ) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return 0L;
            lock.lock();
            try {
                int chunkIndex = localChunkZ * 32 + localChunkX;
                if (SurfaceChunkCoverage.markComplete(completeChunks, chunkIndex)) {
                    chunkRevisions[chunkIndex] = ++sourceRevision;
                }
                return chunkRevisions[chunkIndex];
            } finally {
                lock.unlock();
            }
        }

        private boolean chunkCompleteUnsafe(int chunkIndex) {
            return SurfaceChunkCoverage.isComplete(completeChunks, chunkIndex);
        }

        public boolean setBlockData(int pixelX, int pixelZ, MapBlockData data) {
            return setBlockData(pixelX, pixelZ, data, SurfaceTintData.UNKNOWN);
        }

        public boolean setBlockData(int pixelX, int pixelZ, MapBlockData data, int tint) {
            long packed = MapBlockData.pack(data);
            lock.lock();
            try {
                int index = pixelZ * 512 + pixelX;
                long previous = pixels[index];
                if (previous == packed && tints[index] == tint) return false;
                boolean wasKnown = !MapBlockData.isEmpty(previous);
                boolean isKnown = !MapBlockData.isEmpty(packed);
                if (wasKnown != isKnown) {
                    knownColumnCount += isKnown ? 1 : -1;
                    int pageX = pixelX / MapPageLayout.PAGE_SIZE;
                    int pageZ = pixelZ / MapPageLayout.PAGE_SIZE;
                    int pageIndex = pageZ * MapPageLayout.PAGES_PER_REGION + pageX;
                    knownColumnsPerPage[pageIndex] += isKnown ? 1 : -1;
                    int chunkIndex = (pixelZ >>> 4) * 32 + (pixelX >>> 4);
                    knownColumnsPerChunk[chunkIndex] += isKnown ? 1 : -1;
                }
                pixels[index] = packed;
                tints[index] = tint;
                long revision = ++sourceRevision;
                int chunkIndex = (pixelZ >>> 4) * 32 + (pixelX >>> 4);
                chunkRevisions[chunkIndex] = revision;
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** Applies one slice of one 16x16 chunk while holding the region lock once. */
        public SurfaceChunkCommit commitSurfaceChunkSlice(int localChunkX,
                int localChunkZ, int startColumn, long[] packedPixels,
                int[] surfaceTints, boolean[] validColumns,
                int sourceOffset, int count) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32
                    || startColumn < 0 || startColumn >= 256
                    || count <= 0) return SurfaceChunkCommit.empty();
            int safeCount = Math.min(count, 256 - startColumn);
            lock.lock();
            try {
                int changed = 0;
                int chunkIndex = localChunkZ * 32 + localChunkX;
                int chunkStartX = localChunkX << 4;
                int chunkStartZ = localChunkZ << 4;
                for (int offset = 0; offset < safeCount; offset++) {
                    int sourceIndex = sourceOffset + offset;
                    if (!validColumns[sourceIndex]) continue;
                    int column = startColumn + offset;
                    int pixelX = chunkStartX + (column & 15);
                    int pixelZ = chunkStartZ + (column >>> 4);
                    int index = pixelZ * 512 + pixelX;
                    long packed = packedPixels[sourceIndex];
                    int tint = surfaceTints[sourceIndex];
                    long previous = pixels[index];
                    // Preserve a durable non-empty observation if a transient live
                    // scan fails to find a surface while the chunk is changing.
                    if (MapBlockData.isEmpty(packed)
                            && !MapBlockData.isEmpty(previous)) continue;
                    if (previous == packed && tints[index] == tint) continue;
                    boolean wasKnown = !MapBlockData.isEmpty(previous);
                    boolean isKnown = !MapBlockData.isEmpty(packed);
                    if (wasKnown != isKnown) {
                        knownColumnCount += isKnown ? 1 : -1;
                        int pageX = pixelX / MapPageLayout.PAGE_SIZE;
                        int pageZ = pixelZ / MapPageLayout.PAGE_SIZE;
                        knownColumnsPerPage[pageZ * MapPageLayout.PAGES_PER_REGION
                                + pageX] += isKnown ? 1 : -1;
                        knownColumnsPerChunk[chunkIndex] += isKnown ? 1 : -1;
                    }
                    pixels[index] = packed;
                    tints[index] = tint;
                    changed++;
                }
                if (changed > 0) {
                    long revision = ++sourceRevision;
                    chunkRevisions[chunkIndex] = revision;
                }
                return new SurfaceChunkCommit(changed,
                        chunkCompleteUnsafe(chunkIndex),
                        chunkRevisions[chunkIndex]);
            } finally {
                lock.unlock();
            }
        }

        /** Resolves both Surface palette indices under one region lock. */
        public long getOrAddSurfacePaletteIndices(String biomeId, String blockId) {
            String safeBiome = biomeId == null || biomeId.isBlank()
                    ? "minecraft:plains" : biomeId;
            String safeBlock = blockId == null || blockId.isBlank()
                    ? "minecraft:air" : blockId;
            lock.lock();
            try {
                Integer biome = biomeIndex.get(safeBiome);
                if (biome == null) {
                    biome = biomePalette.size() >= 255 ? 254 : biomePalette.size();
                    if (biome < 254 || biomePalette.size() < 255) {
                        biomePalette.add(safeBiome);
                        biomeIndex.put(safeBiome, biome);
                        paletteRevision++;
                    }
                }
                Integer block = blockIndex.get(safeBlock);
                if (block == null) {
                    block = blockPalette.size() >= 65_535
                            ? 65_534 : blockPalette.size();
                    if (block < 65_534 || blockPalette.size() < 65_535) {
                        blockPalette.add(safeBlock);
                        blockIndex.put(safeBlock, block);
                        paletteRevision++;
                    }
                }
                return ((long) biome << 32) | (block & 0xFFFF_FFFFL);
            } finally {
                lock.unlock();
            }
        }

        public int getOrAddBiomeIndex(String biomeId) {
            String safeId = biomeId == null || biomeId.isBlank() ? "minecraft:plains" : biomeId;
            lock.lock();
            try {
                Integer existing = biomeIndex.get(safeId);
                if (existing != null) return existing;
                if (biomePalette.size() >= 255) return 254;
                int index = biomePalette.size();
                biomePalette.add(safeId);
                biomeIndex.put(safeId, index);
                paletteRevision++;
                return index;
            } finally {
                lock.unlock();
            }
        }

        public int getOrAddBlockIndex(String blockId) {
            String safeId = blockId == null || blockId.isBlank() ? "minecraft:air" : blockId;
            lock.lock();
            try {
                Integer existing = blockIndex.get(safeId);
                if (existing != null) return existing;
                if (blockPalette.size() >= 65_535) return 65_534;
                int index = blockPalette.size();
                blockPalette.add(safeId);
                blockIndex.put(safeId, index);
                paletteRevision++;
                return index;
            } finally {
                lock.unlock();
            }
        }

        public long sourceRevision() {
            lock.lock();
            try { return sourceRevision; }
            finally { lock.unlock(); }
        }

        public long chunkRevision(int localChunkX, int localChunkZ) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return 0L;
            lock.lock();
            try { return chunkRevisions[localChunkZ * 32 + localChunkX]; }
            finally { lock.unlock(); }
        }

        public RegionSourcePalette snapshotSourcePalette() {
            return snapshotSourcePaletteIfChanged(Long.MIN_VALUE);
        }

        public RegionSourcePalette snapshotSourcePaletteIfChanged(
                long knownPaletteRevision) {
            lock.lock();
            try {
                if (knownPaletteRevision >= paletteRevision) return null;
                return RegionSourcePalette.owned(sourceRevision, paletteRevision,
                        biomePalette.toArray(new String[0]),
                        blockPalette.toArray(new String[0]));
            } finally {
                lock.unlock();
            }
        }

        public RegionChunkSnapshot snapshotChunk(int localChunkX, int localChunkZ,
                byte[] lightLevels) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return null;
            lock.lock();
            try {
                int chunkIndex = localChunkZ * 32 + localChunkX;
                // Xaero exposes a MapTile only after its complete 16x16 payload is
                // available. Completion is observation state, not a count of
                // non-void blocks: a fully scanned End-island edge (or an entirely
                // void chunk) is authoritative even though fewer than 256 columns
                // contain a block. Requiring knownColumns == 256 permanently
                // rejected those chunks and produced the solid End-stone ribbons
                // surrounded by black tiles.
                if (!chunkCompleteUnsafe(chunkIndex)) return null;
                long[] chunkPixels = new long[16 * 16];
                int[] chunkTints = new int[16 * 16];
                int startX = localChunkX * 16;
                int startZ = localChunkZ * 16;
                for (int z = 0; z < 16; z++) {
                    int source = (startZ + z) * 512 + startX;
                    int target = z * 16;
                    System.arraycopy(pixels, source, chunkPixels, target, 16);
                    System.arraycopy(tints, source, chunkTints, target, 16);
                }
                return RegionChunkSnapshot.owned(localChunkX, localChunkZ,
                        chunkRevisions[chunkIndex],
                        chunkPixels, chunkTints, lightLevels);
            } finally {
                lock.unlock();
            }
        }

        /**
         * Captures the density-correct 64x64 level-0 fallback for this 512x512
         * region. One sample represents an 8x8 block cell; a one-cell halo is
         * retained for slope/color shading. This is deliberately about 55 KiB,
         * rather than cloning and colorizing the full multi-megabyte region every
         * time a coarse fallback is refreshed.
         */
        public RegionLodSnapshot snapshotLodLevel0() {
            final int outputSize = 64;
            final int halo = 1;
            final int stride = outputSize + halo * 2;
            final int sampleStep = 8;
            lock.lock();
            try {
                long[] sampledPixels = new long[stride * stride];
                int[] sampledTints = new int[stride * stride];
                byte[] sampledKnown = new byte[stride * stride];
                int knownCells = 0;
                for (int sampleZ = 0; sampleZ < stride; sampleZ++) {
                    int localZ = Math.max(0, Math.min(511,
                            (sampleZ - halo) * sampleStep + sampleStep / 2));
                    int sourceRow = localZ * 512;
                    int targetRow = sampleZ * stride;
                    for (int sampleX = 0; sampleX < stride; sampleX++) {
                        int localX = Math.max(0, Math.min(511,
                                (sampleX - halo) * sampleStep + sampleStep / 2));
                        int target = targetRow + sampleX;
                        sampledPixels[target] = pixels[sourceRow + localX];
                        sampledTints[target] = tints[sourceRow + localX];
                        int chunkIndex = (localZ >>> 4) * 32 + (localX >>> 4);
                        if (chunkCompleteUnsafe(chunkIndex)) {
                            sampledKnown[target] = 1;
                            if (sampleX >= halo && sampleX < stride - halo
                                    && sampleZ >= halo && sampleZ < stride - halo) {
                                knownCells++;
                            }
                        }
                    }
                }
                return RegionLodSnapshot.owned(rx, rz, sourceRevision,
                        stride, halo, knownCells, sampledPixels, sampledTints,
                        sampledKnown, biomePalette.toArray(new String[0]),
                        blockPalette.toArray(new String[0]));
            } finally {
                lock.unlock();
            }
        }

        public boolean hasAnyData() {
            lock.lock();
            try { return knownColumnCount > 0; }
            finally { lock.unlock(); }
        }

        public long[] snapshotPackedPixels() {
            lock.lock();
            try {
                return Arrays.copyOf(pixels, pixels.length);
            } finally {
                lock.unlock();
            }
        }

        public int[] snapshotTints() {
            lock.lock();
            try {
                return Arrays.copyOf(tints, tints.length);
            } finally {
                lock.unlock();
            }
        }

        /**
         * Copies only the requested local region rectangle. Page-rooted texture
         * jobs use this instead of cloning all 512x512 columns for every 64x64
         * leaf update. Palette arrays belong to this immutable window and allow
         * the caller to remap indices when a halo crosses a region boundary.
         */
        public RegionWindow snapshotWindow(int minX, int minZ, int width, int height) {
            if (minX < 0 || minZ < 0 || width <= 0 || height <= 0
                    || minX + width > MapPageLayout.REGION_SIZE
                    || minZ + height > MapPageLayout.REGION_SIZE) {
                throw new IllegalArgumentException("Invalid region window "
                        + minX + "," + minZ + " " + width + "x" + height);
            }
            lock.lock();
            try {
                long[] windowPixels = new long[width * height];
                int[] windowTints = new int[width * height];
                byte[] windowKnown = new byte[width * height];
                for (int z = 0; z < height; z++) {
                    int source = (minZ + z) * MapPageLayout.REGION_SIZE + minX;
                    int target = z * width;
                    System.arraycopy(pixels, source, windowPixels, target, width);
                    System.arraycopy(tints, source, windowTints, target, width);
                    int localZ = minZ + z;
                    for (int x = 0; x < width; x++) {
                        int chunkIndex = (localZ >>> 4) * 32
                                + ((minX + x) >>> 4);
                        if (chunkCompleteUnsafe(chunkIndex)) {
                            windowKnown[target + x] = 1;
                        }
                    }
                }
                return new RegionWindow(windowPixels, windowTints, windowKnown,
                        width, height,
                        biomePalette.toArray(new String[0]),
                        blockPalette.toArray(new String[0]));
            } finally {
                lock.unlock();
            }
        }

        /** Legacy snapshot API retained for compatibility with external code. */
        public MapBlockData[] snapshotPixels() {
            lock.lock();
            try {
                MapBlockData[] result = new MapBlockData[pixels.length];
                for (int i = 0; i < pixels.length; i++) {
                    if (!MapBlockData.isEmpty(pixels[i])) result[i] = MapBlockData.unpack(pixels[i]);
                }
                return result;
            } finally {
                lock.unlock();
            }
        }

        public List<String> snapshotBiomePalette() {
            lock.lock();
            try { return new ArrayList<>(biomePalette); }
            finally { lock.unlock(); }
        }

        public List<String> snapshotBlockPalette() {
            lock.lock();
            try { return new ArrayList<>(blockPalette); }
            finally { lock.unlock(); }
        }

        private RegionSnapshot snapshot() {
            lock.lock();
            try {
                return new RegionSnapshot(Arrays.copyOf(pixels, pixels.length),
                        Arrays.copyOf(tints, tints.length),
                        biomePalette.toArray(new String[0]), blockPalette.toArray(new String[0]),
                        Arrays.copyOf(completeChunks, completeChunks.length),
                        sourceRevision);
            } finally {
                lock.unlock();
            }
        }

        private void applyLoadedData(long[] loadedPixels, int[] loadedTints,
                long[] loadedCompleteChunks, List<String> biomes,
                List<String> blocks) {
            lock.lock();
            try {
                if (closed) return;
                System.arraycopy(loadedPixels, 0, pixels, 0, pixels.length);
                System.arraycopy(loadedTints, 0, tints, 0, tints.length);
                Arrays.fill(knownColumnsPerPage, 0);
                Arrays.fill(knownColumnsPerChunk, (short) 0);
                Arrays.fill(completeChunks, 0L);
                if (loadedCompleteChunks != null
                        && loadedCompleteChunks.length == completeChunks.length) {
                    System.arraycopy(loadedCompleteChunks, 0, completeChunks, 0,
                            completeChunks.length);
                }
                knownColumnCount = 0;
                for (int z = 0; z < 512; z++) {
                    int pageZ = z / MapPageLayout.PAGE_SIZE;
                    int row = z * 512;
                    for (int x = 0; x < 512; x++) {
                        if (MapBlockData.isEmpty(pixels[row + x])) continue;
                        knownColumnCount++;
                        int pageX = x / MapPageLayout.PAGE_SIZE;
                        knownColumnsPerPage[
                                pageZ * MapPageLayout.PAGES_PER_REGION + pageX]++;
                        knownColumnsPerChunk[(z >>> 4) * 32 + (x >>> 4)]++;
                    }
                }
                biomePalette.clear();
                biomePalette.addAll(biomes);
                blockPalette.clear();
                blockPalette.addAll(blocks);
                biomeIndex.clear();
                for (int i = 0; i < biomePalette.size(); i++) biomeIndex.put(biomePalette.get(i), i);
                blockIndex.clear();
                for (int i = 0; i < blockPalette.size(); i++) blockIndex.put(blockPalette.get(i), i);
                paletteRevision++;
                long loadedRevision = ++sourceRevision;
                Arrays.fill(chunkRevisions, loadedRevision);
            } finally {
                lock.unlock();
            }
        }
    }

    public record RegionWindow(long[] pixels, int[] tints, byte[] known,
            int width, int height,
            String[] biomePalette, String[] blockPalette) {
    }

    /** Immutable, ownership-transferred source for a 64x64 region LOD build. */
    public static final class RegionLodSnapshot {
        private final int regionX;
        private final int regionZ;
        private final long sourceRevision;
        private final int stride;
        private final int halo;
        private final int knownCells;
        private final long[] pixels;
        private final int[] tints;
        private final byte[] known;
        private final String[] biomePalette;
        private final String[] blockPalette;

        private RegionLodSnapshot(int regionX, int regionZ,
                long sourceRevision, int stride, int halo, int knownCells,
                long[] pixels, int[] tints, byte[] known,
                String[] biomePalette, String[] blockPalette) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.sourceRevision = Math.max(1L, sourceRevision);
            this.stride = stride;
            this.halo = halo;
            this.knownCells = Math.max(0, knownCells);
            this.pixels = pixels;
            this.tints = tints;
            this.known = known;
            this.biomePalette = biomePalette;
            this.blockPalette = blockPalette;
        }

        static RegionLodSnapshot owned(int regionX, int regionZ,
                long sourceRevision, int stride, int halo, int knownCells,
                long[] pixels, int[] tints, byte[] known,
                String[] biomePalette, String[] blockPalette) {
            return new RegionLodSnapshot(regionX, regionZ, sourceRevision,
                    stride, halo, knownCells, pixels, tints, known,
                    biomePalette, blockPalette);
        }

        public int regionX() { return regionX; }
        public int regionZ() { return regionZ; }
        public long sourceRevision() { return sourceRevision; }
        public int stride() { return stride; }
        public int halo() { return halo; }
        public int knownCells() { return knownCells; }
        public long[] pixelsUnsafe() { return pixels; }
        public int[] tintsUnsafe() { return tints; }
        public byte[] knownUnsafe() { return known; }
        public String[] biomePaletteUnsafe() { return biomePalette; }
        public String[] blockPaletteUnsafe() { return blockPalette; }
    }

    public record SurfaceChunkCommit(int changedColumns,
            boolean chunkComplete, long chunkRevision) {
        private static SurfaceChunkCommit empty() {
            return new SurfaceChunkCommit(0, false, 0L);
        }
    }

    public static final class RegionSourcePalette {
        private final long sourceRevision;
        private final long paletteRevision;
        private final String[] biomePalette;
        private final String[] blockPalette;

        public RegionSourcePalette(long sourceRevision,
                String[] biomePalette, String[] blockPalette) {
            this(sourceRevision, sourceRevision, biomePalette, blockPalette, true);
        }

        public RegionSourcePalette(long sourceRevision, long paletteRevision,
                String[] biomePalette, String[] blockPalette) {
            this(sourceRevision, paletteRevision, biomePalette, blockPalette, true);
        }

        private RegionSourcePalette(long sourceRevision, long paletteRevision,
                String[] biomePalette, String[] blockPalette, boolean copy) {
            this.sourceRevision = Math.max(1L, sourceRevision);
            this.paletteRevision = Math.max(1L, paletteRevision);
            String[] safeBiomes = biomePalette == null ? new String[0] : biomePalette;
            String[] safeBlocks = blockPalette == null ? new String[0] : blockPalette;
            this.biomePalette = copy
                    ? Arrays.copyOf(safeBiomes, safeBiomes.length) : safeBiomes;
            this.blockPalette = copy
                    ? Arrays.copyOf(safeBlocks, safeBlocks.length) : safeBlocks;
        }

        static RegionSourcePalette owned(long sourceRevision,
                long paletteRevision, String[] biomePalette,
                String[] blockPalette) {
            return new RegionSourcePalette(sourceRevision, paletteRevision,
                    biomePalette, blockPalette, false);
        }

        public long sourceRevision() { return sourceRevision; }
        public long paletteRevision() { return paletteRevision; }
        public String[] biomePalette() {
            return Arrays.copyOf(biomePalette, biomePalette.length);
        }
        public String[] blockPalette() {
            return Arrays.copyOf(blockPalette, blockPalette.length);
        }
        String[] biomePaletteUnsafe() { return biomePalette; }
        String[] blockPaletteUnsafe() { return blockPalette; }
    }

    public static final class RegionChunkSnapshot {
        private final int localChunkX;
        private final int localChunkZ;
        private final long sourceRevision;
        private final long[] packedPixels;
        private final int[] tints;
        private final byte[] lightLevels;

        public RegionChunkSnapshot(int localChunkX, int localChunkZ,
                long sourceRevision, long[] packedPixels, int[] tints,
                byte[] lightLevels) {
            this(localChunkX, localChunkZ, sourceRevision,
                    packedPixels, tints, lightLevels, true);
        }

        private RegionChunkSnapshot(int localChunkX, int localChunkZ,
                long sourceRevision, long[] packedPixels, int[] tints,
                byte[] lightLevels, boolean copy) {
            if (packedPixels == null || packedPixels.length != 256
                    || tints == null || tints.length != 256
                    || (lightLevels != null && lightLevels.length != 256)) {
                throw new IllegalArgumentException("Region chunk snapshot requires 256 columns");
            }
            this.localChunkX = localChunkX;
            this.localChunkZ = localChunkZ;
            this.sourceRevision = Math.max(1L, sourceRevision);
            this.packedPixels = copy
                    ? Arrays.copyOf(packedPixels, packedPixels.length) : packedPixels;
            this.tints = copy ? Arrays.copyOf(tints, tints.length) : tints;
            this.lightLevels = lightLevels == null ? null
                    : copy ? Arrays.copyOf(lightLevels, lightLevels.length)
                    : lightLevels;
        }

        static RegionChunkSnapshot owned(int localChunkX, int localChunkZ,
                long sourceRevision, long[] packedPixels, int[] tints,
                byte[] lightLevels) {
            return new RegionChunkSnapshot(localChunkX, localChunkZ,
                    sourceRevision, packedPixels, tints, lightLevels, false);
        }

        public int localChunkX() { return localChunkX; }
        public int localChunkZ() { return localChunkZ; }
        public long sourceRevision() { return sourceRevision; }
        public long[] packedPixels() {
            return Arrays.copyOf(packedPixels, packedPixels.length);
        }
        public int[] tints() { return Arrays.copyOf(tints, tints.length); }
        public byte[] lightLevels() { return lightLevels == null ? null
                : Arrays.copyOf(lightLevels, lightLevels.length); }
        long[] packedPixelsUnsafe() { return packedPixels; }
        int[] tintsUnsafe() { return tints; }
        byte[] lightLevelsUnsafe() { return lightLevels; }
    }

    private record RegionSnapshot(long[] pixels, int[] tints,
            String[] biomePalette, String[] blockPalette, long[] completeChunks,
            long sourceRevision) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class PinData {
        public boolean active;
        public double x;
        public double z;
    }

    private void loadPin() {
        if (currentDimensionDir == null) {
            MapConfig.pinActive = false;
            return;
        }
        File file = new File(currentDimensionDir, "pin.json");
        if (!file.exists()) {
            MapConfig.pinActive = false;
            MapConfig.pinWorldX = 0;
            MapConfig.pinWorldZ = 0;
            return;
        }
        try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            PinData data = GSON.fromJson(reader, PinData.class);
            if (data == null || !Double.isFinite(data.x) || !Double.isFinite(data.z)
                    || Math.abs(data.x) > 30_000_000.0 || Math.abs(data.z) > 30_000_000.0) {
                throw new IOException("Invalid pin coordinates");
            }
            MapConfig.pinActive = data.active;
            MapConfig.pinWorldX = data.x;
            MapConfig.pinWorldZ = data.z;
        } catch (Exception exception) {
            LOGGER.error("Failed to load pin data; preserving unreadable file", exception);
            MapConfig.pinActive = false;
            quarantineSmallJson(file);
        }
    }

    public synchronized void savePin() {
        if (currentDimensionDir == null) return;
        File file = new File(currentDimensionDir, "pin.json");
        Path temporary = null;
        try {
            Files.createDirectories(currentDimensionDir.toPath());
            temporary = Files.createTempFile(currentDimensionDir.toPath(), "pin.", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                PinData data = new PinData();
                boolean validCoordinates = Double.isFinite(MapConfig.pinWorldX)
                        && Double.isFinite(MapConfig.pinWorldZ)
                        && Math.abs(MapConfig.pinWorldX) <= 30_000_000.0
                        && Math.abs(MapConfig.pinWorldZ) <= 30_000_000.0;
                data.active = MapConfig.pinActive && validCoordinates;
                data.x = validCoordinates ? MapConfig.pinWorldX : 0.0;
                data.z = validCoordinates ? MapConfig.pinWorldZ : 0.0;
                GSON.toJson(data, writer);
            }
            moveReplacing(temporary, file.toPath());
            temporary = null;
        } catch (IOException exception) {
            LOGGER.error("Failed to save pin data atomically", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void quarantineSmallJson(File file) {
        if (file == null || !file.isFile()) return;
        Path source = file.toPath();
        Path quarantine = source.resolveSibling(file.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            moveReplacing(source, quarantine);
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine unreadable file {}", file, exception);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
