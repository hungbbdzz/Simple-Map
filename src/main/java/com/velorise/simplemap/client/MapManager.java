package com.velorise.simplemap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    private String currentDimensionId = "";
    private long lastSaveTime;

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
        lastLearnedBookId = bookId == null ? "" : bookId;
        saveLearningState(hasLearnedMap);
    }

    public void openMapScreen() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }

    public void setHasLearnedMap(boolean learned) {
        hasLearnedMap = learned;
        saveLearningState(learned);
    }

    private void saveLearningState(boolean learned) {
        if (currentWorldDir == null) return;
        File learnedFile = new File(currentWorldDir, "map_learned.dat");
        File idFile = new File(currentWorldDir, "last_book_id.dat");
        if (learned) {
            try {
                if (!learnedFile.exists()) learnedFile.createNewFile();
                if (!lastLearnedBookId.isEmpty()) Files.writeString(idFile.toPath(), lastLearnedBookId);
                else Files.deleteIfExists(idFile.toPath());
            } catch (IOException exception) {
                LOGGER.error("Failed to write map learning state", exception);
            }
        } else {
            try {
                Files.deleteIfExists(learnedFile.toPath());
                Files.deleteIfExists(idFile.toPath());
            } catch (IOException exception) {
                LOGGER.warn("Failed to clear map learning state", exception);
            }
        }
    }

    public synchronized void updateWorldAndDimension(Minecraft minecraft) {
        if (minecraft.level == null) {
            if (currentDimensionDir != null) flushAndClear();
            return;
        }

        String worldId;
        if (minecraft.isLocalServer() && minecraft.getSingleplayerServer() != null) {
            try {
                var root = minecraft.getSingleplayerServer().getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT);
                worldId = "sp_" + safePathComponent(root.getFileName().toString());
            } catch (Exception exception) {
                worldId = "sp_" + safePathComponent(
                        minecraft.getSingleplayerServer().getWorldData().getLevelName());
            }
        } else if (minecraft.getCurrentServer() != null) {
            worldId = "mp_" + safePathComponent(minecraft.getCurrentServer().ip);
        } else {
            worldId = "dim_" + safePathComponent(
                    minecraft.level.dimension().location().toString());
        }

        var dimensionLocation = minecraft.level.dimension().location();
        String dimensionId = dimensionLocation.getNamespace().equals("minecraft")
                ? safePathComponent(dimensionLocation.getPath())
                : safePathComponent(dimensionLocation.toString());
        if (worldId.equals(currentWorldId) && dimensionId.equals(currentDimensionId)) return;

        LOGGER.info("SimpleMap world changed [{}/{}] -> [{}/{}]",
                currentWorldId, currentDimensionId, worldId, dimensionId);
        flushAndClear();
        currentWorldId = worldId;
        currentDimensionId = dimensionId;

        File worldDirectory;
        if (minecraft.isLocalServer() && minecraft.getSingleplayerServer() != null) {
            try {
                worldDirectory = minecraft.getSingleplayerServer()
                        .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .resolve("simplemap").toFile();
            } catch (Exception exception) {
                worldDirectory = new File(minecraft.gameDirectory, "simplemap/saves/" + worldId);
            }
        } else {
            worldDirectory = new File(minecraft.gameDirectory, "simplemap/saves/" + worldId);
        }
        currentWorldDir = worldDirectory;
        if (!worldDirectory.exists() && !worldDirectory.mkdirs()) {
            LOGGER.warn("Could not create SimpleMap world directory {}", worldDirectory);
        }

        loadLearningState(worldDirectory);
        WaypointManager.getInstance().updateWorldDir(worldDirectory);

        currentDimensionDir = new File(worldDirectory, dimensionId);
        if (!currentDimensionDir.exists() && !currentDimensionDir.mkdirs()) {
            LOGGER.warn("Could not create SimpleMap dimension directory {}", currentDimensionDir);
        }

        MapLightManager.getInstance().setCacheDirectory(new File(worldDirectory, "light_cache/" + dimensionId));
        CaveMapManager.getInstance().setBaseDirectory(
                new File(worldDirectory, "caves/" + dimensionId), currentDimensionDir);
        FullCaveMapManager.getInstance().setCacheDirectory(
                new File(worldDirectory, "caves/" + dimensionId + "/full"));
        loadPin();
        LOGGER.info("SimpleMap switched to lazy region directory: {}", currentDimensionDir);
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
            lastLearnedBookId = Files.readString(idFile.toPath()).trim();
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
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        Region region = getRegion(regionX, regionZ, true);
        if (region == null || !region.isLoaded()) return;
        if (region.setBlockData(blockX & 511, blockZ & 511, data)) {
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
        return regionFileExists.computeIfAbsent(key,
                ignored -> RegionDataStore.hasFile(directory, regionX, regionZ));
    }

    public boolean isRegionLoadedInCache(int regionX, int regionZ) {
        synchronized (loadedRegions) {
            return loadedRegions.containsKey(key(regionX, regionZ));
        }
    }

    public String getCurrentDimensionId() {
        return currentDimensionId;
    }

    public File getCurrentDimensionDir() {
        return currentDimensionDir;
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
            return new RegionDataStore.StoredRegion(snapshot.pixels(),
                    snapshot.biomePalette(), snapshot.blockPalette());
        }
    }

    private void loadRegionAsync(Region region, File directory, long generation) {
        try {
            LOAD_POOL.execute(() -> {
                long[] pixels = new long[PIXEL_COUNT];
                Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
                List<String> biomePalette = new ArrayList<>();
                List<String> blockPalette = new ArrayList<>();
                boolean loaded = RegionDataStore.load(directory, region.rx, region.rz,
                        pixels, biomePalette, blockPalette);
                if (!loaded) quarantineCorruptRegion(directory, region.rx, region.rz, generation);
                if (!isLoadTargetCurrent(region, directory, generation)) return;
                region.applyLoadedData(pixels, biomePalette, blockPalette);
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
        MapLightManager.getInstance().flushAndClear();
        CaveMapManager.getInstance().flushAndClear();
        FullCaveMapManager.getInstance().flushAndClear();
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
                snapshot.pixels(), snapshot.biomePalette(), snapshot.blockPalette());
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

        public boolean setBlockData(int pixelX, int pixelZ, MapBlockData data) {
            long packed = MapBlockData.pack(data);
            lock.lock();
            try {
                int index = pixelZ * 512 + pixelX;
                if (pixels[index] == packed) return false;
                pixels[index] = packed;
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
                        biomePalette.toArray(new String[0]), blockPalette.toArray(new String[0]));
            } finally {
                lock.unlock();
            }
        }

        private void applyLoadedData(long[] loadedPixels, List<String> biomes, List<String> blocks) {
            lock.lock();
            try {
                if (closed) return;
                System.arraycopy(loadedPixels, 0, pixels, 0, pixels.length);
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

    private record RegionSnapshot(long[] pixels, String[] biomePalette, String[] blockPalette) {
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
        try (FileReader reader = new FileReader(file)) {
            PinData data = GSON.fromJson(reader, PinData.class);
            if (data == null) {
                MapConfig.pinActive = false;
                return;
            }
            MapConfig.pinActive = data.active;
            MapConfig.pinWorldX = data.x;
            MapConfig.pinWorldZ = data.z;
        } catch (IOException exception) {
            LOGGER.error("Failed to load pin data", exception);
            MapConfig.pinActive = false;
        }
    }

    public void savePin() {
        if (currentDimensionDir == null) return;
        File file = new File(currentDimensionDir, "pin.json");
        try (FileWriter writer = new FileWriter(file)) {
            PinData data = new PinData();
            data.active = MapConfig.pinActive;
            data.x = MapConfig.pinWorldX;
            data.z = MapConfig.pinWorldZ;
            GSON.toJson(data, writer);
        } catch (IOException exception) {
            LOGGER.error("Failed to save pin data", exception);
        }
    }
}
