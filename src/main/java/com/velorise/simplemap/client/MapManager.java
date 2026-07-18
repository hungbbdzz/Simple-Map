package com.velorise.simplemap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Central, lazy-loaded surface map data store. */
public class MapManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapManager INSTANCE = new MapManager();
    private static final int MAX_LOADED_REGIONS = 160;
    private static final int PIXEL_COUNT = 512 * 512;

    private static final ThreadPoolExecutor LOAD_POOL = new ThreadPoolExecutor(
            3, 3, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(192), runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-RegionLoad");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private final Map<String, Region> loadedRegions = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyRegions = new HashSet<>();
    private final Map<String, Boolean> regionFileExists = new ConcurrentHashMap<>();
    private final AtomicLong worldGeneration = new AtomicLong(1L);

    private volatile File currentDimensionDir;
    private volatile File currentWorldDir;
    private String currentWorldId = "";
    /** Directory-safe dimension key used by the cache layout. */
    private String currentDimensionId = "";
    /** Canonical registry id, e.g. minecraft:the_nether or modid:moon. */
    private String currentDimensionResourceId = "minecraft:overworld";
    private String liveDimensionId = "overworld";
    private String liveDimensionResourceId = "minecraft:overworld";
    /** Non-null while the fullscreen map is browsing a dimension other than the live level. */
    private String viewDimensionOverride;
    private long lastSaveTime;
    private volatile boolean currentDimensionHasSavedData;
    private volatile long lastSavedDataProbeMs;

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
        if (minecraft.level == null) {
            if (currentDimensionDir != null) flushAndClear();
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
            worldId = "dim_" + safePathComponent(
                    minecraft.level.dimension().location().toString());
            worldDirectory = new File(minecraft.gameDirectory, "simplemap/saves/" + worldId);
        }

        String liveResourceId = canonicalDimensionId(
                minecraft.level.dimension().location().toString());
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
            return;
        }

        // A static fullscreen-map view must not be overwritten every client tick by
        // the player's live dimension. It is restored explicitly when the user
        // selects Live or closes the map.
        if (viewDimensionOverride != null) return;
        if (!liveStorageId.equals(currentDimensionId)) {
            switchDimensionData(liveStorageId, liveResourceId, true);
        }
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

    /**
     * Clears only dimension-bound CPU state. GPU texture managers are intentionally
     * retained and are namespace-keyed by dimension, providing a small warm cache
     * when the user cycles back to a map that was already viewed.
     */
    private void switchDimensionData(String storageId, String resourceId, boolean preserveTextures) {
        saveDirtyRegionsAsync();
        worldGeneration.incrementAndGet();
        MapProcessor.getInstance().clear();
        clearSurfaceDimensionData();
        MapLightManager.getInstance().flushAndClear();
        CaveMapManager.getInstance().flushDataForDimensionSwitch();
        FullCaveMapManager.getInstance().flushDataForDimensionSwitch();
        VerticalCaveArchiveManager.getInstance().flushAndClear();
        ChunkScanner.getInstance().reset();
        RegionProcessor.getInstance().stop();
        if (!preserveTextures) {
            MapTextureManager.getInstance().clearCache();
            CaveTextureManager.getInstance().clearCache();
            FullCaveTextureManager.getInstance().clearCache();
            MapOverviewTextureManager.getInstance().clearCache();
        }
        configureDimension(storageId, resourceId);
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
            return new ArrayList<>(loadedRegions.keySet());
        }
    }

    public MapBlockData getBlockData(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null || !region.isLoaded()) return null;
        return region.getBlockData(blockX & 511, blockZ & 511);
    }

    public int getSurfaceTint(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null || !region.isLoaded()) return SurfaceTintData.UNKNOWN;
        return region.getTint(blockX & 511, blockZ & 511);
    }

    public int getColor(int blockX, int blockZ) {
        MapBlockData data = getBlockData(blockX, blockZ);
        if (data == null || data.isEmpty()) return 0;
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null) return 0;
        List<String> palette = region.snapshotBlockPalette();
        int index = data.blockId & 0xFFFF;
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
            markRegionDirty(regionX, regionZ);
        }
    }

    public void markRegionDirty(int regionX, int regionZ) {
        String key = key(regionX, regionZ);
        synchronized (dirtyRegions) {
            dirtyRegions.add(key);
        }
        regionFileExists.put(key, true);
        MapTextureManager.getInstance().markRegionDirty(regionX, regionZ);
    }

    public Region getRegion(int regionX, int regionZ, boolean loadIfMissing) {
        String key = key(regionX, regionZ);
        synchronized (loadedRegions) {
            Region existing = loadedRegions.get(key);
            if (existing != null || !loadIfMissing || currentDimensionDir == null) return existing;

            long generation = worldGeneration.get();
            Region created = new Region(regionX, regionZ, generation);
            loadedRegions.put(key, created);
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

    public void unloadRegion(int regionX, int regionZ) {
        String key = key(regionX, regionZ);
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
        String key = key(regionX, regionZ);
        Boolean cached = regionFileExists.get(key);
        if (cached != null) return cached;
        boolean exists = RegionDataStore.hasFile(directory, regionX, regionZ);
        regionFileExists.put(key, exists);
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

    /** Re-colors and reloads all saved map regions in background threads. */
    public void reloadAllRegions() {
        File directory = currentDimensionDir;
        worldGeneration.incrementAndGet();
        MapTextureManager.getInstance().clearDerivedColorCaches();
        MapTextureManager.getInstance().clearCache();
        CaveMapManager.getInstance().clearCache();
        FullCaveTextureManager.getInstance().clearCache();

        if (directory != null) {
            File[] files = directory.listFiles((dir, name) ->
                    name != null && name.matches("r\\.-?\\d{1,7}\\.-?\\d{1,7}\\.smdat"));
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    String[] parts = name.split("\\.");
                    if (parts.length >= 4) {
                        try {
                            int rx = Integer.parseInt(parts[1]);
                            int rz = Integer.parseInt(parts[2]);
                            MapProcessor.getInstance().enqueueSurfaceLoad(rx, rz, 100);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
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
                    snapshot.biomePalette(), snapshot.blockPalette());
        }
    }

    private void loadRegionAsync(Region region, File directory, long generation) {
        try {
            LOAD_POOL.execute(() -> {
                long[] pixels = new long[PIXEL_COUNT];
                int[] tints = new int[PIXEL_COUNT];
                Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
                Arrays.fill(tints, SurfaceTintData.UNKNOWN);
                List<String> biomePalette = new ArrayList<>();
                List<String> blockPalette = new ArrayList<>();
                boolean loaded = RegionDataStore.load(directory, region.rx, region.rz,
                        pixels, tints, biomePalette, blockPalette);
                if (!loaded) quarantineCorruptRegion(directory, region.rx, region.rz, generation);
                if (!isLoadTargetCurrent(region, directory, generation)) return;
                region.applyLoadedData(pixels, tints, biomePalette, blockPalette);
                region.markLoaded();
                if (loaded) MapTextureManager.getInstance().markRegionDirty(region.rx, region.rz);
                synchronized (loadedRegions) {
                    evictOldRegions();
                }
            });
        } catch (RejectedExecutionException saturated) {
            // Do not let rapid map panning create an unbounded disk-I/O backlog.
            // The renderer will request this region again when queue capacity returns.
            synchronized (loadedRegions) {
                if (loadedRegions.get(key(region.rx, region.rz)) == region) {
                    loadedRegions.remove(key(region.rx, region.rz));
                }
            }
            region.close();
        }
    }

    private boolean isLoadTargetCurrent(Region region, File directory, long generation) {
        if (!isGenerationCurrent(generation) || currentDimensionDir == null
                || !currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) return false;
        synchronized (loadedRegions) {
            return loadedRegions.get(key(region.rx, region.rz)) == region;
        }
    }

    public void tickSave() {
        MapLightManager.getInstance().tickSave();
        CaveMapManager.getInstance().tickSave();
        FullCaveMapManager.getInstance().tickSave();
        VerticalCaveArchiveManager.getInstance().tickSave();
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < 10_000L) return;
        lastSaveTime = now;
        saveDirtyRegionsAsync();
    }

    public synchronized void flushAndClear() {
        saveDirtyRegionsAsync();
        worldGeneration.incrementAndGet();
        MapProcessor.getInstance().clear();
        clearCache();
        MapTextureManager.getInstance().clearCache();
        MapOverviewTextureManager.getInstance().clearCache();
        MapLightManager.getInstance().flushAndClear();
        CaveMapManager.getInstance().flushAndClear();
        FullCaveMapManager.getInstance().flushAndClear();
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
        viewDimensionOverride = null;
        currentDimensionHasSavedData = false;
        lastSavedDataProbeMs = 0L;
        MapConfig.pinActive = false;
        MapConfig.pinWorldX = 0;
        MapConfig.pinWorldZ = 0;
    }

    private void saveDirtyRegionsAsync() {
        File directory = currentDimensionDir;
        if (directory == null) return;
        Set<String> keys;
        synchronized (dirtyRegions) {
            if (dirtyRegions.isEmpty()) return;
            keys = new HashSet<>(dirtyRegions);
            dirtyRegions.clear();
        }
        for (String key : keys) {
            Region region;
            synchronized (loadedRegions) {
                region = loadedRegions.get(key);
            }
            if (region != null && region.isLoaded()) saveRegionSnapshot(region, directory);
        }
    }

    private void saveRegionSnapshot(Region region, File directory) {
        RegionSnapshot snapshot = region.snapshot();
        RegionDataStore.saveAsync(directory, region.rx, region.rz,
                snapshot.pixels(), snapshot.tints(), snapshot.biomePalette(), snapshot.blockPalette());
        regionFileExists.put(key(region.rx, region.rz), true);
    }

    private void evictOldRegions() {
        while (loadedRegions.size() > MAX_LOADED_REGIONS) {
            Iterator<Map.Entry<String, Region>> iterator = loadedRegions.entrySet().iterator();
            Map.Entry<String, Region> selected = null;
            while (iterator.hasNext()) {
                Map.Entry<String, Region> candidate = iterator.next();
                if (candidate.getValue().isLoaded()) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) return;
            String selectedKey = selected.getKey();
            Region region = selected.getValue();
            iterator.remove();
            boolean dirty;
            synchronized (dirtyRegions) {
                dirty = dirtyRegions.remove(selectedKey);
            }
            if (dirty && currentDimensionDir != null) saveRegionSnapshot(region, currentDimensionDir);
            region.close();
        }
    }

    private void quarantineCorruptRegion(File directory, int rx, int rz, long requestedGeneration) {
        File file = new File(directory, RegionDataStore.fileName(rx, rz));
        if (!file.isFile() || RegionDataStore.hasPending(directory, rx, rz)) return;
        File quarantine = new File(directory,
                file.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), quarantine.toPath());
            if (isGenerationCurrent(requestedGeneration)
                    && currentDimensionDir != null
                    && currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) {
                regionFileExists.put(key(rx, rz), false);
            }
            LOGGER.warn("Moved unreadable SimpleMap region to {}", quarantine.getName());
        } catch (IOException exception) {
            if (isGenerationCurrent(requestedGeneration)
                    && currentDimensionDir != null
                    && currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) {
                regionFileExists.remove(key(rx, rz));
            }
            LOGGER.warn("Could not quarantine unreadable SimpleMap region {}", file, exception);
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

    private static String key(int regionX, int regionZ) {
        return regionX + "," + regionZ;
    }

    public static final class Region {
        public final int rx;
        public final int rz;
        private final long generation;
        private final long[] pixels = new long[PIXEL_COUNT];
        private final int[] tints = new int[PIXEL_COUNT];
        private final List<String> biomePalette = new ArrayList<>();
        private final List<String> blockPalette = new ArrayList<>();
        private final Map<String, Integer> biomeIndex = new HashMap<>();
        private final Map<String, Integer> blockIndex = new HashMap<>();
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean loaded;
        private volatile boolean closed;

        private Region(int rx, int rz, long generation) {
            this.rx = rx;
            this.rz = rz;
            this.generation = generation;
            Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
            Arrays.fill(tints, SurfaceTintData.UNKNOWN);
        }

        public String getCacheKey() { return key(rx, rz); }
        public boolean isLoaded() { return loaded && !closed; }
        public long getGeneration() { return generation; }
        public void lock() { lock.lock(); }
        public void unlock() { lock.unlock(); }
        public void close() { closed = true; }
        void markLoaded() { if (!closed) loaded = true; }

        public MapBlockData getBlockData(int pixelX, int pixelZ) {
            lock.lock();
            try {
                long packed = pixels[pixelZ * 512 + pixelX];
                return MapBlockData.isEmpty(packed) ? null : MapBlockData.unpack(packed);
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

        public boolean setBlockData(int pixelX, int pixelZ, MapBlockData data) {
            return setBlockData(pixelX, pixelZ, data, SurfaceTintData.UNKNOWN);
        }

        public boolean setBlockData(int pixelX, int pixelZ, MapBlockData data, int tint) {
            long packed = MapBlockData.pack(data);
            lock.lock();
            try {
                int index = pixelZ * 512 + pixelX;
                if (pixels[index] == packed && tints[index] == tint) return false;
                pixels[index] = packed;
                tints[index] = tint;
                return true;
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
                return index;
            } finally {
                lock.unlock();
            }
        }

        public boolean hasAnyData() {
            lock.lock();
            try {
                for (long pixel : pixels) {
                    if (!MapBlockData.isEmpty(pixel)) return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
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
                        biomePalette.toArray(new String[0]), blockPalette.toArray(new String[0]));
            } finally {
                lock.unlock();
            }
        }

        private void applyLoadedData(long[] loadedPixels, int[] loadedTints,
                List<String> biomes, List<String> blocks) {
            lock.lock();
            try {
                if (closed) return;
                System.arraycopy(loadedPixels, 0, pixels, 0, pixels.length);
                System.arraycopy(loadedTints, 0, tints, 0, tints.length);
                biomePalette.clear();
                biomePalette.addAll(biomes);
                blockPalette.clear();
                blockPalette.addAll(blocks);
                biomeIndex.clear();
                for (int i = 0; i < biomePalette.size(); i++) biomeIndex.put(biomePalette.get(i), i);
                blockIndex.clear();
                for (int i = 0; i < blockPalette.size(); i++) blockIndex.put(blockPalette.get(i), i);
            } finally {
                lock.unlock();
            }
        }
    }

    private record RegionSnapshot(long[] pixels, int[] tints, String[] biomePalette, String[] blockPalette) {
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
