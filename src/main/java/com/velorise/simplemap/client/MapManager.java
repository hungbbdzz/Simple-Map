package com.velorise.simplemap.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final ExecutorService FILE_IO_POOL = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SimpleMap-FileIO");
        thread.setDaemon(true);
        return thread;
    });

    private static final MapManager INSTANCE = new MapManager();
    public static MapManager getInstance() {
        return INSTANCE;
    }

    private final Map<String, Region> loadedRegions = new java.util.LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyRegions = new HashSet<>();
    private File currentDimensionDir;
    private File currentWorldDir;
    private String currentWorldId = "";
    private String currentDimensionId = "";
    private long lastSaveTime = 0;
    
    // Learning state for requireMapBook config
    private boolean hasLearnedMap = false;
    private String lastLearnedBookId = "";

    private MapManager() {}

    public boolean hasLearnedMap() {
        // If server does not require book map, player has learned it by default
        if (!MapConfig.serverRequireMapBook) {
            return true;
        }
        return hasLearnedMap;
    }

    public String getLastLearnedBookId() {
        return lastLearnedBookId;
    }

    public void setLastLearnedBookId(String bookId) {
        this.lastLearnedBookId = bookId;
        saveLearningState(this.hasLearnedMap);
    }

    public void openMapScreen() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }

    public void setHasLearnedMap(boolean learned) {
        this.hasLearnedMap = learned;
        saveLearningState(learned);
    }

    private void saveLearningState(boolean learned) {
        if (currentWorldDir == null) return;
        File learnedFile = new File(currentWorldDir, "map_learned.dat");
        File idFile = new File(currentWorldDir, "last_book_id.dat");
        if (learned) {
            try {
                if (!learnedFile.exists()) {
                    learnedFile.createNewFile();
                }
                if (lastLearnedBookId != null && !lastLearnedBookId.isEmpty()) {
                    Files.writeString(idFile.toPath(), lastLearnedBookId);
                } else if (idFile.exists()) {
                    idFile.delete();
                }
            } catch (IOException e) {
                LOGGER.error("Failed to write map learning state files", e);
            }
        } else {
            if (learnedFile.exists()) {
                learnedFile.delete();
            }
            if (idFile.exists()) {
                idFile.delete();
            }
        }
    }

    /**
     * Updates the active directory based on the current world and dimension.
     */
    public synchronized void updateWorldAndDimension(Minecraft mc) {
        if (mc.level == null) {
            clearCache();
            return;
        }

        String worldId;
        if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
            try {
                java.nio.file.Path worldRoot = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                worldId = "sp_" + worldRoot.getFileName().toString();
            } catch (Exception e) {
                // Fallback to human-readable level name if that fails
                worldId = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
            }
        } else if (mc.getCurrentServer() != null) {
            // Multiplayer: use server IP
            worldId = "mp_" + mc.getCurrentServer().ip.replace(':', '_');
        } else {
            // Fallback: just use the dimension key as a temporary stable key.
            // This happens briefly during singleplayer load before getSingleplayerServer() is ready.
            worldId = "dim_" + mc.level.dimension().location().toString().replace(':', '_');
        }

        // Standardize dimension folder names (e.g. minecraft:overworld -> overworld, twilightforest:twilight_forest -> twilightforest_twilight_forest)
        net.minecraft.resources.ResourceLocation dimLoc = mc.level.dimension().location();
        String dimId = dimLoc.getNamespace().equals("minecraft") 
            ? dimLoc.getPath() 
            : dimLoc.toString().replace(':', '_');

        // If world or dimension changed, flush cache and swap folders
        if (!worldId.equals(currentWorldId) || !dimId.equals(currentDimensionId)) {
            LOGGER.info("SimpleMap: world changed [{}/{}] -> [{}/{}]", currentWorldId, currentDimensionId, worldId, dimId);
            flushAndClear();
            currentWorldId = worldId;
            currentDimensionId = dimId;

            // Resolve the world directory (stored inside local singleplayer save folder if local)
            File worldDir;
            if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
                try {
                    worldDir = mc.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("simplemap").toFile();
                } catch (Exception e) {
                    worldDir = new File(mc.gameDirectory, "simplemap/saves/" + worldId);
                }
            } else {
                worldDir = new File(mc.gameDirectory, "simplemap/saves/" + worldId);
            }
            currentWorldDir = worldDir;

            // Load learning state for the new world
            File learnedFile = new File(worldDir, "map_learned.dat");
            this.hasLearnedMap = learnedFile.exists();
            File idFile = new File(worldDir, "last_book_id.dat");
            if (idFile.exists()) {
                try {
                    this.lastLearnedBookId = Files.readString(idFile.toPath()).trim();
                } catch (IOException e) {
                    this.lastLearnedBookId = "";
                }
            } else {
                this.lastLearnedBookId = "";
            }

            // Update WaypointManager world directory path
            if (!worldDir.exists()) {
                worldDir.mkdirs();
            }
            WaypointManager.getInstance().updateWorldDir(worldDir);

            // Target path: worldDir/[dimension]/
            File baseDir = new File(worldDir, dimId);
            if (!baseDir.exists()) {
                baseDir.mkdirs();
            }
            currentDimensionDir = baseDir;
            LOGGER.info("SimpleMap switched to directory: {}", currentDimensionDir.getAbsolutePath());
            loadPin();
        }
    }

    public String getCurrentDimensionId() {
        return currentDimensionId;
    }

    public File getCurrentDimensionDir() {
        return currentDimensionDir;
    }

    /**
     * Gets the color at world block coordinates (x, z). Returns 0 (transparent) if unexplored.
     */
    public int getColor(int blockX, int blockZ) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        Region region = getRegion(rx, rz, true);
        if (region == null) return 0;
        
        int px = blockX & 511;
        int pz = blockZ & 511;
        return region.getColor(px, pz);
    }

    /**
     * Sets the color at world block coordinates (x, z).
     */
    public void setColor(int blockX, int blockZ, int abgrColor) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        Region region = getRegion(rx, rz, true);
        if (region != null && region.isLoaded()) {
            int px = blockX & 511;
            int pz = blockZ & 511;
            if (region.setColor(px, pz, abgrColor)) {
                synchronized (dirtyRegions) {
                    dirtyRegions.add(region.getCacheKey());
                }
                // Notify the texture manager to upload new pixels to OpenGL
                MapTextureManager.getInstance().markRegionTextureDirty(rx, rz);
            }
        }
    }

    /**
     * Gets a region, loading it from disk if required.
     */
    public Region getRegion(int rx, int rz, boolean loadIfMissing) {
        String key = rx + "," + rz;
        synchronized (loadedRegions) {
            Region region = loadedRegions.get(key);
            if (region == null && loadIfMissing && currentDimensionDir != null) {
                region = new Region(rx, rz);
                loadedRegions.put(key, region);
                loadRegionFromDiskAsync(region);

                // LRU eviction (max 256 cached regions to limit JVM heap memory)
                while (loadedRegions.size() > 256) {
                    java.util.Iterator<Map.Entry<String, Region>> it = loadedRegions.entrySet().iterator();
                    if (it.hasNext()) {
                        Map.Entry<String, Region> eldestEntry = it.next();
                        Region eldestRegion = eldestEntry.getValue();
                        String eldestKey = eldestEntry.getKey();
                        
                        it.remove();
                        
                        boolean wasDirty;
                        synchronized (dirtyRegions) {
                            wasDirty = dirtyRegions.remove(eldestKey);
                        }
                        if (wasDirty) {
                            saveRegionImmediately(eldestRegion);
                        }
                        eldestRegion.close();
                    } else {
                        break;
                    }
                }
            }
            return region;
        }
    }

    /**
     * Evicts a region from the memory cache so that it is reloaded from disk on next access.
     */
    public void unloadRegion(int rx, int rz) {
        String key = rx + "," + rz;
        Region region;
        synchronized (loadedRegions) {
            region = loadedRegions.remove(key);
        }
        synchronized (dirtyRegions) {
            dirtyRegions.remove(key);
        }
        if (region != null) {
            region.close();
        }
    }

    public boolean hasRegionFile(int rx, int rz) {
        if (currentDimensionDir == null) return false;
        File file = new File(currentDimensionDir, "r." + rx + "." + rz + ".png");
        return file.exists();
    }

    public boolean isRegionLoadedInCache(int rx, int rz) {
        String key = rx + "," + rz;
        synchronized (loadedRegions) {
            return loadedRegions.containsKey(key);
        }
    }

    private void loadRegionFromDiskAsync(Region region) {
        if (currentDimensionDir == null) return;
        File file = new File(currentDimensionDir, "r." + region.rx + "." + region.rz + ".png");

        CompletableFuture.runAsync(() -> {
            if (!file.exists()) {
                region.markLoaded();
                return;
            }
            try (FileInputStream is = new FileInputStream(file)) {
                NativeImage image = NativeImage.read(is);
                int width = Math.min(512, image.getWidth());
                int height = Math.min(512, image.getHeight());
                
                region.lock();
                try {
                    for (int z = 0; z < height; z++) {
                        for (int x = 0; x < width; x++) {
                            // NativeImage reads ABGR
                            region.setColorDirect(x, z, image.getPixelRGBA(x, z));
                        }
                    }
                } finally {
                    region.unlock();
                }
                image.close();
                region.markLoaded();
                // Mark texture as dirty so renderer re-uploads it to GPU
                MapTextureManager.getInstance().markRegionTextureDirty(region.rx, region.rz);
            } catch (IOException e) {
                LOGGER.error("Failed to load map region PNG: " + file.getName(), e);
                region.markLoaded();
            }
        }, FILE_IO_POOL);
    }

    /**
     * Periodically called to save dirty regions to disk.
     */
    public void tickSave() {
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < 10000) return; // Save every 10 seconds
        lastSaveTime = now;

        saveDirtyRegionsAsync();
    }

    public synchronized void flushAndClear() {
        saveDirtyRegionsAsync();
        clearCache();
        // Clear OpenGL texture cache and scanner state when swapping worlds or dimensions
        MapTextureManager.getInstance().clearCache();
        ChunkScanner.getInstance().reset();
    }

    private synchronized void clearCache() {
        synchronized (loadedRegions) {
            for (Region r : loadedRegions.values()) {
                r.close();
            }
            loadedRegions.clear();
        }
        synchronized (dirtyRegions) {
            dirtyRegions.clear();
        }
        currentDimensionDir = null;
        currentWorldDir = null;
        currentWorldId = "";
        currentDimensionId = "";
        
        // Reset pin state
        MapConfig.pinActive = false;
        MapConfig.pinWorldX = 0;
        MapConfig.pinWorldZ = 0;
    }

    private void saveDirtyRegionsAsync() {
        if (currentDimensionDir == null) return;

        Set<String> toSave;
        synchronized (dirtyRegions) {
            if (dirtyRegions.isEmpty()) return;
            toSave = new HashSet<>(dirtyRegions);
            dirtyRegions.clear();
        }

        for (String key : toSave) {
            Region region;
            synchronized (loadedRegions) {
                region = loadedRegions.get(key);
            }
            if (region != null && region.isLoaded()) {
                File file = new File(currentDimensionDir, "r." + region.rx + "." + region.rz + ".png");
                
                // Copy pixel data safely before sending to IO pool
                int[] pixelsCopy = new int[512 * 512];
                region.lock();
                try {
                    System.arraycopy(region.getPixelsDirect(), 0, pixelsCopy, 0, pixelsCopy.length);
                } finally {
                    region.unlock();
                }

                CompletableFuture.runAsync(() -> {
                    try (NativeImage image = new NativeImage(512, 512, true)) {
                        for (int z = 0; z < 512; z++) {
                            for (int x = 0; x < 512; x++) {
                                image.setPixelRGBA(x, z, pixelsCopy[z * 512 + x]);
                            }
                        }
                        Path tempFile = file.getParentFile().toPath().resolve(file.getName() + ".tmp");
                        image.writeToFile(tempFile);
                        Files.move(tempFile, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        LOGGER.error("Failed to write map region PNG: " + file.getName(), e);
                    }
                }, FILE_IO_POOL);
            }
        }
    }

    private void saveRegionImmediately(Region region) {
        if (currentDimensionDir == null || !region.isLoaded()) return;

        File file = new File(currentDimensionDir, "r." + region.rx + "." + region.rz + ".png");
        int[] pixelsCopy = new int[512 * 512];
        region.lock();
        try {
            System.arraycopy(region.getPixelsDirect(), 0, pixelsCopy, 0, pixelsCopy.length);
        } finally {
            region.unlock();
        }

        CompletableFuture.runAsync(() -> {
            try (NativeImage image = new NativeImage(512, 512, true)) {
                for (int z = 0; z < 512; z++) {
                    for (int x = 0; x < 512; x++) {
                        image.setPixelRGBA(x, z, pixelsCopy[z * 512 + x]);
                    }
                }
                Path tempFile = file.getParentFile().toPath().resolve(file.getName() + ".tmp");
                image.writeToFile(tempFile);
                Files.move(tempFile, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOGGER.error("Failed to write map region PNG: " + file.getName(), e);
            }
        }, FILE_IO_POOL);
    }

    public static class Region {
        public final int rx;
        public final int rz;
        private final int[] pixels = new int[512 * 512];
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private boolean loaded = false;

        public Region(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }

        public String getCacheKey() {
            return rx + "," + rz;
        }

        public void lock() {
            lock.lock();
        }

        public void unlock() {
            lock.unlock();
        }

        public boolean isLoaded() {
            return loaded;
        }

        public void markLoaded() {
            this.loaded = true;
        }

        public int getColor(int px, int pz) {
            lock.lock();
            try {
                return pixels[pz * 512 + px];
            } finally {
                lock.unlock();
            }
        }

        public boolean setColor(int px, int pz, int abgrColor) {
            lock.lock();
            try {
                int index = pz * 512 + px;
                if (pixels[index] != abgrColor) {
                    pixels[index] = abgrColor;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        public void setColorDirect(int px, int pz, int abgrColor) {
            pixels[pz * 512 + px] = abgrColor;
        }

        public int[] getPixelsDirect() {
            return pixels;
        }

        public void close() {
            // Clean resources if any
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class PinData {
        public boolean active = false;
        public double x = 0;
        public double z = 0;
    }

    private void loadPin() {
        if (currentDimensionDir == null) {
            MapConfig.pinActive = false;
            return;
        }
        File pinFile = new File(currentDimensionDir, "pin.json");
        if (!pinFile.exists()) {
            MapConfig.pinActive = false;
            MapConfig.pinWorldX = 0;
            MapConfig.pinWorldZ = 0;
            return;
        }
        try (FileReader reader = new FileReader(pinFile)) {
            PinData data = GSON.fromJson(reader, PinData.class);
            if (data != null) {
                MapConfig.pinActive = data.active;
                MapConfig.pinWorldX = data.x;
                MapConfig.pinWorldZ = data.z;
            } else {
                MapConfig.pinActive = false;
            }
        } catch (IOException e) {
            LOGGER.error("Failed to load pin data", e);
            MapConfig.pinActive = false;
        }
    }

    public void savePin() {
        if (currentDimensionDir == null) return;
        File pinFile = new File(currentDimensionDir, "pin.json");
        try (FileWriter writer = new FileWriter(pinFile)) {
            PinData data = new PinData();
            data.active = MapConfig.pinActive;
            data.x = MapConfig.pinWorldX;
            data.z = MapConfig.pinWorldZ;
            GSON.toJson(data, writer);
        } catch (IOException e) {
            LOGGER.error("Failed to save pin data", e);
        }
    }
}
