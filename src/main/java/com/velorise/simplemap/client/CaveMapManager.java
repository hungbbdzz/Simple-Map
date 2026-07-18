package com.velorise.simplemap.client;

import com.mojang.blaze3d.platform.NativeImage;
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

/** Lazy binary cache for bounded Top-Y cave projections. */
public final class CaveMapManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final CaveMapManager INSTANCE = new CaveMapManager();
    private static final int MAX_REGIONS = 144;
    private static final int PIXELS = 512 * 512;
    private static final int LAYER_DEPTH = 32;
    private static final int FILE_MAGIC = 0x43415645; // CAVE
    private static final int FILE_VERSION = 1;
    private static final int MAX_BINARY_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LEGACY_PNG_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LEGACY_IMAGE_DIMENSION = 4096;
    private static final String FILE_EXT = ".cave";

    private static final ThreadPoolExecutor LOAD_POOL = new ThreadPoolExecutor(
            3, 3, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(160), runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-CaveLoad");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ExecutorService SAVE_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleMap-CaveSave");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    private final Map<String, CaveRegion> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyRegions = new HashSet<>();
    private final Set<String> seedJobs = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> fileExists = new ConcurrentHashMap<>();
    private final Map<String, CaveSaveRequest> pendingSaves = new ConcurrentHashMap<>();
    private final Map<String, CaveSaveRequest> inFlightSaves = new ConcurrentHashMap<>();
    private final AtomicBoolean saveDrainScheduled = new AtomicBoolean();
    private final AtomicLong layerGeneration = new AtomicLong(1L);

    private volatile File baseDirectory;
    private volatile File surfaceDirectory;
    private volatile File activeLayerDirectory;
    private volatile File legacyLayerDirectory;
    private volatile int activeLayerY = Integer.MIN_VALUE;
    private long lastSaveTime;

    private CaveMapManager() {
    }

    public static CaveMapManager getInstance() {
        return INSTANCE;
    }

    public long getLayerGeneration() {
        return layerGeneration.get();
    }

    public boolean isLayerGenerationCurrent(long generation, int layerY) {
        return generation == layerGeneration.get() && layerY == activeLayerY;
    }

    public synchronized void setBaseDirectory(File directory) {
        setBaseDirectory(directory, null);
    }

    public synchronized void setBaseDirectory(File directory, File surfaceDirectory) {
        if (sameFile(baseDirectory, directory) && sameFile(this.surfaceDirectory, surfaceDirectory)) return;
        flushAndClear();
        baseDirectory = directory;
        this.surfaceDirectory = surfaceDirectory;
        if (baseDirectory != null && !baseDirectory.exists() && !baseDirectory.mkdirs()) {
            LOGGER.warn("Could not create cave cache directory {}", baseDirectory);
        }
    }

    public synchronized void setActiveLayer(int layerY) {
        if (activeLayerY == layerY && activeLayerDirectory != null) return;
        saveDirtyRegions();
        layerGeneration.incrementAndGet();
        clearRegions();
        dirtyRegions.clear();
        fileExists.clear();
        seedJobs.clear();
        activeLayerY = layerY;
        activeLayerDirectory = baseDirectory == null ? null
                : new File(baseDirectory, "layer_v4_band32_" + layerY);
        legacyLayerDirectory = baseDirectory == null ? null
                : new File(baseDirectory, "layer_v3_band32_" + layerY);
        if (activeLayerDirectory != null && !activeLayerDirectory.exists()
                && !activeLayerDirectory.mkdirs()) {
            LOGGER.warn("Could not create cave layer directory {}", activeLayerDirectory);
        }
        CaveTextureManager.getInstance().onLayerActivated(layerY);
    }

    public int getActiveLayerY() {
        return activeLayerY;
    }

    public void requestVisibleRegion(int layerY, int rx, int rz) {
        if (layerY != activeLayerY) return;
        CaveRegion region = getRegion(rx, rz, true);
        if (region == null) return;
        // Always attempt a Full-Cave merge for visible regions. Existing layer
        // files can be sparse, so seeding only when the file was absent left large
        // black holes that never filled while panning an explored map.
        if (region.isLoaded()) scheduleVisibleSeed(layerY, rx, rz, region);
    }

    public void setColor(int blockX, int blockZ, int abgrColor) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        CaveRegion region = getRegion(rx, rz, true);
        if (region == null || !region.isLoaded()) return;
        if (region.setColor(blockX & 511, blockZ & 511, abgrColor)) markRegionDirty(rx, rz);
    }

    public void markRegionDirty(int rx, int rz) {
        int layerY = activeLayerY;
        if (layerY == Integer.MIN_VALUE) return;
        synchronized (dirtyRegions) {
            dirtyRegions.add(key(rx, rz));
        }
        CaveTextureManager.getInstance().markRegionTextureDirty(layerY, rx, rz);
    }

    public int getColor(int blockX, int blockZ) {
        CaveRegion region = getRegion(blockX >> 9, blockZ >> 9, true);
        return region == null || !region.isLoaded() ? 0
                : region.getColor(blockX & 511, blockZ & 511);
    }

    public CaveRegion getRegion(int rx, int rz, boolean loadIfMissing) {
        String key = key(rx, rz);
        synchronized (regions) {
            CaveRegion existing = regions.get(key);
            if (existing != null || !loadIfMissing || activeLayerDirectory == null) return existing;
            long generation = layerGeneration.get();
            CaveRegion created = new CaveRegion(rx, rz, generation);
            regions.put(key, created);
            CaveSaveRequest pending = latestSave(activeLayerDirectory, rx, rz);
            if (pending != null) {
                created.replacePixels(pending.pixels());
                created.markLoaded();
            } else if (hasAnyStoredRegion(rx, rz)) {
                loadRegionAsync(created, activeLayerDirectory, legacyLayerDirectory,
                        generation, activeLayerY);
            } else created.markLoaded();
            evictRegionsIfNeeded();
            return created;
        }
    }

    public boolean hasRegionFile(int rx, int rz) {
        return hasAnyStoredRegion(rx, rz);
    }

    public boolean isRegionLoaded(int rx, int rz) {
        synchronized (regions) {
            CaveRegion region = regions.get(key(rx, rz));
            return region != null && region.isLoaded();
        }
    }

    public void tickSave() {
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < 10_000L) return;
        lastSaveTime = now;
        saveDirtyRegions();
    }

    public synchronized void flushAndClear() {
        deactivate();
        baseDirectory = null;
        surfaceDirectory = null;
    }

    public synchronized void deactivate() {
        saveDirtyRegions();
        layerGeneration.incrementAndGet();
        clearRegions();
        dirtyRegions.clear();
        fileExists.clear();
        seedJobs.clear();
        CaveTextureManager.getInstance().clearCache();
        activeLayerDirectory = null;
        legacyLayerDirectory = null;
        activeLayerY = Integer.MIN_VALUE;
    }

    private void loadRegionAsync(CaveRegion region, File directory, File requestedLegacyDirectory,
            long requestedGeneration, int requestedLayerY) {
        try {
            LOAD_POOL.execute(() -> {
                int[] pixels = null;
                boolean legacy = false;
                File attemptedFile = null;
                CaveSaveRequest pending = latestSave(directory, region.rx, region.rz);
                try {
                    if (pending != null) {
                        pixels = java.util.Arrays.copyOf(pending.pixels(), pending.pixels().length);
                    } else {
                        File binary = new File(directory, fileName(region.rx, region.rz));
                        if (binary.isFile()) {
                            attemptedFile = binary;
                            pixels = readBinary(binary);
                        } else {
                            File legacyFile = new File(directory, legacyFileName(region.rx, region.rz));
                            if (!legacyFile.isFile() && requestedLegacyDirectory != null) {
                                legacyFile = new File(requestedLegacyDirectory,
                                        legacyFileName(region.rx, region.rz));
                            }
                            if (legacyFile.isFile()) {
                                attemptedFile = legacyFile;
                                pixels = readLegacyPng(legacyFile);
                                legacy = true;
                            }
                        }
                    }
                } catch (IOException exception) {
                    LOGGER.error("Failed to load cave region {},{}", region.rx, region.rz, exception);
                    quarantineCorruptFile(attemptedFile);
                    if (isLayerGenerationCurrent(requestedGeneration, requestedLayerY)
                            && sameFile(activeLayerDirectory, directory)) {
                        fileExists.remove(key(region.rx, region.rz));
                    }
                }
                if (!isLoadTargetCurrent(region, directory, requestedGeneration, requestedLayerY)) return;
                if (pixels != null) region.replacePixels(pixels);
                region.markLoaded();
                CaveTextureManager.getInstance().markRegionTextureDirty(
                        requestedLayerY, region.rx, region.rz);
                if (legacy) markRegionDirty(region.rx, region.rz);
            });
        } catch (RejectedExecutionException saturated) {
            // Visible requests are retried by the renderer. Removing the placeholder
            // avoids retaining thousands of unloaded regions during rapid panning.
            synchronized (regions) {
                if (regions.get(key(region.rx, region.rz)) == region) {
                    regions.remove(key(region.rx, region.rz));
                }
            }
            region.close();
        }
    }

    private boolean isLoadTargetCurrent(CaveRegion region, File directory,
            long requestedGeneration, int requestedLayerY) {
        if (!isLayerGenerationCurrent(requestedGeneration, requestedLayerY)
                || activeLayerDirectory == null
                || !activeLayerDirectory.getAbsoluteFile().equals(directory.getAbsoluteFile())) return false;
        synchronized (regions) {
            return regions.get(key(region.rx, region.rz)) == region;
        }
    }

    private int[] readBinary(File file) throws IOException {
        long fileSize = Files.size(file.toPath());
        if (fileSize <= 0L || fileSize > MAX_BINARY_FILE_BYTES) {
            throw new IOException("Invalid cave cache size " + fileSize);
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file), 64 * 1024)))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != FILE_MAGIC || version != FILE_VERSION) {
                throw new IOException("Unsupported cave cache format");
            }
            int count = input.readInt();
            if (count != PIXELS) throw new IOException("Invalid cave pixel count " + count);
            byte[] raw = input.readNBytes(PIXELS * 4);
            if (raw.length != PIXELS * 4) throw new EOFException("Truncated cave payload");
            int[] pixels = new int[PIXELS];
            int pointer = 0;
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = ((raw[pointer++] & 0xFF) << 24)
                        | ((raw[pointer++] & 0xFF) << 16)
                        | ((raw[pointer++] & 0xFF) << 8)
                        | (raw[pointer++] & 0xFF);
            }
            return pixels;
        }
    }

    private int[] readLegacyPng(File file) throws IOException {
        validateLegacyPng(file);
        try (FileInputStream stream = new FileInputStream(file);
                NativeImage image = NativeImage.read(stream)) {
            int[] pixels = new int[PIXELS];
            int width = Math.min(512, image.getWidth());
            int height = Math.min(512, image.getHeight());
            for (int z = 0; z < height; z++) {
                for (int x = 0; x < width; x++) pixels[z * 512 + x] = image.getPixelRGBA(x, z);
            }
            return pixels;
        }
    }



    private static void validateLegacyPng(File file) throws IOException {
        long size = Files.size(file.toPath());
        if (size <= 0L || size > MAX_LEGACY_PNG_BYTES) {
            throw new IOException("Invalid legacy cave PNG size " + size);
        }
        byte[] header = new byte[24];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.readNBytes(header, 0, header.length);
            if (read != header.length
                    || (header[0] & 0xFF) != 0x89 || header[1] != 0x50
                    || header[2] != 0x4E || header[3] != 0x47
                    || header[12] != 'I' || header[13] != 'H'
                    || header[14] != 'D' || header[15] != 'R') {
                throw new IOException("Invalid legacy cave PNG header");
            }
        }
        int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
        if (width <= 0 || height <= 0 || width > MAX_LEGACY_IMAGE_DIMENSION
                || height > MAX_LEGACY_IMAGE_DIMENSION) {
            throw new IOException("Unsafe legacy cave PNG dimensions " + width + "x" + height);
        }
    }

    private static void quarantineCorruptFile(File file) {
        if (file == null || !file.isFile()) return;
        File quarantine = new File(file.getParentFile(),
                file.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Moved unreadable cave cache to {}", quarantine.getName());
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine unreadable cave cache {}", file, exception);
        }
    }

    private void scheduleVisibleSeed(int layerY, int rx, int rz, CaveRegion targetRegion) {
        long generation = layerGeneration.get();
        String jobKey = generation + ":" + rx + "," + rz;
        if (!seedJobs.add(jobKey)) return;
        try {
            LOAD_POOL.execute(() -> {
                try {
                    FullCaveMapManager.FullSnapshot full = FullCaveMapManager.getInstance()
                            .getLoadedSnapshot(rx, rz);
                    if (full == null) {
                        full = FullCaveMapManager.getInstance().readSnapshotFromDisk(rx, rz);
                    }
                    if (full == null || !isLayerGenerationCurrent(generation, layerY)) return;
                    int minimumY = layerY - LAYER_DEPTH + 1;
                    int[] seeded = new int[PIXELS];
                    for (int i = 0; i < PIXELS; i++) {
                        int height = full.heights()[i];
                        if (height >= minimumY && height <= layerY) seeded[i] = full.pixels()[i];
                    }
                    applyVisibleSeed(generation, layerY, rx, rz, targetRegion, seeded);
                } finally {
                    seedJobs.remove(jobKey);
                }
            });
        } catch (RejectedExecutionException saturated) {
            seedJobs.remove(jobKey);
        }
    }

    private void applyVisibleSeed(long generation, int layerY, int rx, int rz,
            CaveRegion targetRegion, int[] seeded) {
        if (!isLayerGenerationCurrent(generation, layerY)) return;
        synchronized (regions) {
            if (regions.get(key(rx, rz)) != targetRegion || !targetRegion.isLoaded()) return;
        }
        boolean changed = false;
        targetRegion.lock();
        try {
            int[] current = targetRegion.getPixelsDirect();
            for (int i = 0; i < current.length; i++) {
                if (current[i] == 0 && seeded[i] != 0) {
                    current[i] = seeded[i];
                    changed = true;
                }
            }
        } finally {
            targetRegion.unlock();
        }
        if (changed) markRegionDirty(rx, rz);
    }

    private void saveDirtyRegions() {
        File directory = activeLayerDirectory;
        if (directory == null) return;
        Set<String> keys;
        synchronized (dirtyRegions) {
            if (dirtyRegions.isEmpty()) return;
            keys = new HashSet<>(dirtyRegions);
            dirtyRegions.clear();
        }
        for (String key : keys) {
            CaveRegion region;
            synchronized (regions) {
                region = regions.get(key);
            }
            if (region != null && region.isLoaded()) saveRegion(region, directory);
        }
    }

    private void saveRegion(CaveRegion region, File directory) {
        if (directory == null || region == null || !region.isLoaded()) return;
        CaveSaveRequest request = new CaveSaveRequest(directory, region.rx, region.rz,
                region.getGeneration(), region.snapshotPixels());
        pendingSaves.put(request.key(), request);
        scheduleSaveDrain();
    }

    private void scheduleSaveDrain() {
        if (!saveDrainScheduled.compareAndSet(false, true)) return;
        SAVE_POOL.execute(() -> {
            try {
                while (true) {
                    CaveSaveRequest request = pendingSaves.values().stream().findFirst().orElse(null);
                    if (request == null) break;
                    if (!pendingSaves.remove(request.key(), request)) continue;
                    inFlightSaves.put(request.key(), request);
                    try {
                        writeSaveRequest(request);
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

    private void writeSaveRequest(CaveSaveRequest request) {
        File directory = request.directory();
        File file = new File(directory, fileName(request.rx(), request.rz()));
        File temporary = null;
        try {
            Files.createDirectories(directory.toPath());
            temporary = Files.createTempFile(directory.toPath(), file.getName() + ".", ".tmp").toFile();
            byte[] raw = new byte[PIXELS * 4];
            int pointer = 0;
            for (int pixel : request.pixels()) {
                raw[pointer++] = (byte) (pixel >>> 24);
                raw[pointer++] = (byte) (pixel >>> 16);
                raw[pointer++] = (byte) (pixel >>> 8);
                raw[pointer++] = (byte) pixel;
            }
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(new FileOutputStream(temporary), 64 * 1024)))) {
                output.writeInt(FILE_MAGIC);
                output.writeInt(FILE_VERSION);
                output.writeInt(PIXELS);
                output.write(raw);
            }
            atomicReplace(temporary, file);
            if (request.generation() == layerGeneration.get() && sameFile(activeLayerDirectory, directory)) {
                fileExists.put(key(request.rx(), request.rz()), true);
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to save cave region {},{}", request.rx(), request.rz(), exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
            }
        }
    }

    private CaveSaveRequest latestSave(File directory, int rx, int rz) {
        if (directory == null) return null;
        String saveKey = saveKey(directory, rx, rz);
        CaveSaveRequest request = pendingSaves.get(saveKey);
        return request != null ? request : inFlightSaves.get(saveKey);
    }

    private boolean hasAnyStoredRegion(int rx, int rz) {
        File directory = activeLayerDirectory;
        if (directory == null) return false;
        if (latestSave(directory, rx, rz) != null) return true;
        String key = key(rx, rz);
        return fileExists.computeIfAbsent(key, ignored -> {
            if (new File(directory, fileName(rx, rz)).isFile()) return true;
            File legacy = legacyFile(rx, rz);
            return legacy != null && legacy.isFile();
        });
    }

    private File legacyFile(int rx, int rz) {
        File inActive = activeLayerDirectory == null ? null
                : new File(activeLayerDirectory, legacyFileName(rx, rz));
        if (inActive != null && inActive.isFile()) return inActive;
        return legacyLayerDirectory == null ? null
                : new File(legacyLayerDirectory, legacyFileName(rx, rz));
    }

    private void evictRegionsIfNeeded() {
        while (regions.size() > MAX_REGIONS) {
            Iterator<Map.Entry<String, CaveRegion>> iterator = regions.entrySet().iterator();
            Map.Entry<String, CaveRegion> selected = null;
            while (iterator.hasNext()) {
                Map.Entry<String, CaveRegion> candidate = iterator.next();
                if (candidate.getValue().isLoaded()) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) return;
            String selectedKey = selected.getKey();
            CaveRegion region = selected.getValue();
            iterator.remove();
            boolean dirty;
            synchronized (dirtyRegions) {
                dirty = dirtyRegions.remove(selectedKey);
            }
            if (dirty && activeLayerDirectory != null) saveRegion(region, activeLayerDirectory);
            region.close();
        }
    }

    private void clearRegions() {
        synchronized (regions) {
            for (CaveRegion region : regions.values()) region.close();
            regions.clear();
        }
    }

    private static String saveKey(File directory, int rx, int rz) {
        return new File(directory, fileName(rx, rz)).toPath().toAbsolutePath().normalize().toString();
    }

    private record CaveSaveRequest(File directory, int rx, int rz, long generation, int[] pixels) {
        private CaveSaveRequest {
            pixels = java.util.Arrays.copyOf(pixels, pixels.length);
        }

        private String key() {
            return saveKey(directory, rx, rz);
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

    private static String key(int rx, int rz) {
        return rx + "," + rz;
    }

    private static String fileName(int rx, int rz) {
        return "r." + rx + "." + rz + FILE_EXT;
    }

    private static String legacyFileName(int rx, int rz) {
        return "r." + rx + "." + rz + ".png";
    }
}
