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

/** Stable per-dimension cave composite stored in a compact binary cache. */
public final class FullCaveMapManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final FullCaveMapManager INSTANCE = new FullCaveMapManager();
    private static final int MAX_REGIONS = 144;
    private static final int PIXELS = 512 * 512;
    private static final int FILE_MAGIC = 0x534D4643; // SMFC
    private static final int FILE_VERSION = 1;
    private static final int LEGACY_HEIGHT_MAGIC = 0x534D4659;
    private static final int LEGACY_HEIGHT_VERSION = 1;
    private static final int MAX_BINARY_FILE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LEGACY_PNG_BYTES = 32 * 1024 * 1024;
    private static final int MAX_LEGACY_HEIGHT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LEGACY_IMAGE_DIMENSION = 4096;
    private static final String FILE_EXT = ".fcave";
    public static final short NO_SURFACE = Short.MIN_VALUE;

    private static final ThreadPoolExecutor LOAD_POOL = new ThreadPoolExecutor(
            2, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-FullCaveLoad");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ExecutorService SAVE_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleMap-FullCaveSave");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    private final Map<String, FullRegion> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyRegions = new HashSet<>();
    private final Map<String, Boolean> fileExists = new ConcurrentHashMap<>();
    private final Map<String, FullSaveRequest> pendingSaves = new ConcurrentHashMap<>();
    private final Map<String, FullSaveRequest> inFlightSaves = new ConcurrentHashMap<>();
    private final AtomicBoolean saveDrainScheduled = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong(1L);
    private volatile File cacheDirectory;
    private long lastSaveTime;

    private FullCaveMapManager() {
    }

    public static FullCaveMapManager getInstance() {
        return INSTANCE;
    }

    public long getGeneration() {
        return generation.get();
    }

    public boolean isGenerationCurrent(long value) {
        return value == generation.get();
    }

    public synchronized void setCacheDirectory(File directory) {
        if (sameFile(cacheDirectory, directory)) return;
        flushAndClear();
        cacheDirectory = directory;
        if (cacheDirectory != null && !cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            LOGGER.warn("Could not create full cave cache directory {}", cacheDirectory);
        }
    }

    public void mergeCandidate(int blockX, int blockZ, int abgrColor, int surfaceY, int observationTopY) {
        if (abgrColor == 0 || surfaceY <= NO_SURFACE) return;
        FullRegion region = getRegion(blockX >> 9, blockZ >> 9, true);
        if (region == null || !region.isLoaded()) return;
        if (region.mergeCandidate(blockX & 511, blockZ & 511, abgrColor, surfaceY, observationTopY)) {
            markDirty(region.rx, region.rz);
        }
    }

    public int getColor(int blockX, int blockZ) {
        FullRegion region = getRegion(blockX >> 9, blockZ >> 9, true);
        return region == null || !region.isLoaded() ? 0 : region.getColor(blockX & 511, blockZ & 511);
    }

    public int getSurfaceY(int blockX, int blockZ) {
        FullRegion region = getRegion(blockX >> 9, blockZ >> 9, true);
        return region == null || !region.isLoaded() ? NO_SURFACE
                : region.getSurfaceY(blockX & 511, blockZ & 511);
    }

    public FullRegion getRegion(int rx, int rz, boolean loadIfMissing) {
        String key = key(rx, rz);
        synchronized (regions) {
            FullRegion existing = regions.get(key);
            if (existing != null || !loadIfMissing || cacheDirectory == null) return existing;
            long currentGeneration = generation.get();
            FullRegion created = new FullRegion(rx, rz, currentGeneration);
            regions.put(key, created);
            FullSaveRequest pending = latestSave(cacheDirectory, rx, rz);
            if (pending != null) {
                created.replace(pending.copySnapshot());
                created.markLoaded();
            } else if (hasRegionFile(rx, rz)) {
                loadRegionAsync(created, cacheDirectory, currentGeneration);
            } else created.markLoaded();
            evictRegionsIfNeeded();
            return created;
        }
    }

    public boolean hasRegionFile(int rx, int rz) {
        File directory = cacheDirectory;
        if (directory == null) return false;
        if (latestSave(directory, rx, rz) != null) return true;
        String key = key(rx, rz);
        return fileExists.computeIfAbsent(key, ignored ->
                new File(directory, fileName(rx, rz)).isFile()
                        || new File(directory, legacyColorFileName(rx, rz)).isFile()
                        || new File(directory, legacyHeightFileName(rx, rz)).isFile());
    }

    public boolean isRegionLoaded(int rx, int rz) {
        synchronized (regions) {
            FullRegion region = regions.get(key(rx, rz));
            return region != null && region.isLoaded();
        }
    }

    public FullSnapshot getLoadedSnapshot(int rx, int rz) {
        synchronized (regions) {
            FullRegion region = regions.get(key(rx, rz));
            return region == null || !region.isLoaded() ? null : region.snapshot();
        }
    }

    /** Synchronous disk read intended for the cave IO worker, never the render thread. */
    public FullSnapshot readSnapshotFromDisk(int rx, int rz) {
        File directory = cacheDirectory;
        if (directory == null) return null;
        FullSaveRequest pending = latestSave(directory, rx, rz);
        if (pending != null) return pending.copySnapshot();

        File binary = new File(directory, fileName(rx, rz));
        if (binary.isFile()) {
            try {
                return readBinary(binary);
            } catch (IOException exception) {
                LOGGER.debug("Failed to read full cave snapshot {},{}", rx, rz, exception);
                quarantineCorruptFile(binary);
                if (sameFile(cacheDirectory, directory)) fileExists.remove(key(rx, rz));
                return null;
            }
        }

        File color = new File(directory, legacyColorFileName(rx, rz));
        File height = new File(directory, legacyHeightFileName(rx, rz));
        if (color.isFile() || height.isFile()) {
            try {
                return readLegacy(color, height);
            } catch (IOException exception) {
                LOGGER.debug("Failed to read legacy full cave snapshot {},{}", rx, rz, exception);
            }
        }
        return null;
    }

    public void tickSave() {
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < 10_000L) return;
        lastSaveTime = now;
        saveDirtyRegions();
    }

    public synchronized void flushAndClear() {
        saveDirtyRegions();
        generation.incrementAndGet();
        clearRegions();
        fileExists.clear();
        FullCaveTextureManager.getInstance().clearCache();
        cacheDirectory = null;
    }

    public synchronized void deactivate() {
        saveDirtyRegions();
        generation.incrementAndGet();
        clearRegions();
        FullCaveTextureManager.getInstance().clearCache();
    }

    private void loadRegionAsync(FullRegion region, File directory, long requestedGeneration) {
        try {
            LOAD_POOL.execute(() -> {
                FullSnapshot snapshot = null;
                boolean legacy = false;
                File attemptedFile = null;
                try {
                    FullSaveRequest pending = latestSave(directory, region.rx, region.rz);
                    if (pending != null) {
                        snapshot = pending.copySnapshot();
                    } else {
                        File binary = new File(directory, fileName(region.rx, region.rz));
                        if (binary.isFile()) {
                            attemptedFile = binary;
                            snapshot = readBinary(binary);
                        } else {
                            File color = new File(directory, legacyColorFileName(region.rx, region.rz));
                            File height = new File(directory, legacyHeightFileName(region.rx, region.rz));
                            snapshot = readLegacy(color, height);
                            legacy = snapshot != null;
                        }
                    }
                } catch (IOException exception) {
                    LOGGER.error("Failed to load full cave region {},{}", region.rx, region.rz, exception);
                    quarantineCorruptFile(attemptedFile);
                    if (isGenerationCurrent(requestedGeneration)
                            && sameFile(cacheDirectory, directory)) {
                        fileExists.remove(key(region.rx, region.rz));
                    }
                }
                if (!isLoadTargetCurrent(region, directory, requestedGeneration)) return;
                if (snapshot != null) region.replace(snapshot);
                region.markLoaded();
                FullCaveTextureManager.getInstance().markRegionTextureDirty(region.rx, region.rz);
                int activeLayer = CaveMapManager.getInstance().getActiveLayerY();
                if (activeLayer != Integer.MIN_VALUE) {
                    CaveTextureManager.getInstance().markRegionTextureDirty(
                            activeLayer, region.rx, region.rz);
                }
                if (legacy) markDirty(region.rx, region.rz); // migrate lazily to .fcave
            });
        } catch (RejectedExecutionException saturated) {
            synchronized (regions) {
                if (regions.get(key(region.rx, region.rz)) == region) {
                    regions.remove(key(region.rx, region.rz));
                }
            }
            region.close();
        }
    }

    private boolean isLoadTargetCurrent(FullRegion region, File directory, long requestedGeneration) {
        if (!isGenerationCurrent(requestedGeneration) || cacheDirectory == null
                || !cacheDirectory.getAbsoluteFile().equals(directory.getAbsoluteFile())) return false;
        synchronized (regions) {
            return regions.get(key(region.rx, region.rz)) == region;
        }
    }

    private FullSnapshot readBinary(File file) throws IOException {
        long fileSize = Files.size(file.toPath());
        if (fileSize <= 0L || fileSize > MAX_BINARY_FILE_BYTES) {
            throw new IOException("Invalid full cave cache size " + fileSize);
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new FileInputStream(file), 64 * 1024)))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != FILE_MAGIC || version != FILE_VERSION) {
                throw new IOException("Unsupported full cave cache format");
            }
            int count = input.readInt();
            if (count != PIXELS) throw new IOException("Invalid full cave pixel count " + count);
            byte[] raw = input.readNBytes(PIXELS * 6);
            if (raw.length != PIXELS * 6) throw new EOFException("Truncated full cave payload");
            int[] pixels = new int[PIXELS];
            short[] heights = new short[PIXELS];
            int pointer = 0;
            for (int i = 0; i < PIXELS; i++) {
                pixels[i] = ((raw[pointer++] & 0xFF) << 24)
                        | ((raw[pointer++] & 0xFF) << 16)
                        | ((raw[pointer++] & 0xFF) << 8)
                        | (raw[pointer++] & 0xFF);
                heights[i] = (short) (((raw[pointer++] & 0xFF) << 8) | (raw[pointer++] & 0xFF));
            }
            return new FullSnapshot(pixels, heights);
        }
    }

    private FullSnapshot readLegacy(File colorFile, File heightFile) throws IOException {
        if (!colorFile.isFile() && !heightFile.isFile()) return null;
        int[] pixels = new int[PIXELS];
        short[] heights = new short[PIXELS];
        Arrays.fill(heights, NO_SURFACE);
        if (colorFile.isFile()) {
            try {
                validateLegacyPng(colorFile);
                try (FileInputStream stream = new FileInputStream(colorFile);
                        NativeImage image = NativeImage.read(stream)) {
                    int width = Math.min(512, image.getWidth());
                    int height = Math.min(512, image.getHeight());
                    for (int z = 0; z < height; z++) {
                        for (int x = 0; x < width; x++) pixels[z * 512 + x] = image.getPixelRGBA(x, z);
                    }
                }
            } catch (IOException exception) {
                quarantineCorruptFile(colorFile);
                LOGGER.warn("Ignored unreadable legacy full-cave color cache {}", colorFile, exception);
            }
        }
        if (heightFile.isFile()) {
            try {
                long size = Files.size(heightFile.toPath());
                if (size <= 0L || size > MAX_LEGACY_HEIGHT_BYTES) {
                    throw new IOException("Invalid legacy full-cave height size " + size);
                }
                try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                        new GZIPInputStream(new FileInputStream(heightFile), 64 * 1024)))) {
                    int magic = input.readInt();
                    int version = input.readInt();
                    if (magic != LEGACY_HEIGHT_MAGIC || version != LEGACY_HEIGHT_VERSION) {
                        throw new IOException("Unsupported legacy full-cave height format");
                    }
                    for (int i = 0; i < heights.length; i++) heights[i] = input.readShort();
                }
            } catch (IOException exception) {
                quarantineCorruptFile(heightFile);
                LOGGER.warn("Ignored unreadable legacy full-cave height cache {}", heightFile, exception);
            }
        }
        return new FullSnapshot(pixels, heights);
    }



    private static void validateLegacyPng(File file) throws IOException {
        long size = Files.size(file.toPath());
        if (size <= 0L || size > MAX_LEGACY_PNG_BYTES) {
            throw new IOException("Invalid legacy full-cave PNG size " + size);
        }
        byte[] header = new byte[24];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.readNBytes(header, 0, header.length);
            if (read != header.length
                    || (header[0] & 0xFF) != 0x89 || header[1] != 0x50
                    || header[2] != 0x4E || header[3] != 0x47
                    || header[12] != 'I' || header[13] != 'H'
                    || header[14] != 'D' || header[15] != 'R') {
                throw new IOException("Invalid legacy full-cave PNG header");
            }
        }
        int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);
        if (width <= 0 || height <= 0 || width > MAX_LEGACY_IMAGE_DIMENSION
                || height > MAX_LEGACY_IMAGE_DIMENSION) {
            throw new IOException("Unsafe legacy full-cave PNG dimensions " + width + "x" + height);
        }
    }

    private static void quarantineCorruptFile(File file) {
        if (file == null || !file.isFile()) return;
        File quarantine = new File(file.getParentFile(),
                file.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Moved unreadable full-cave cache to {}", quarantine.getName());
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine unreadable full-cave cache {}", file, exception);
        }
    }

    private void markDirty(int rx, int rz) {
        synchronized (dirtyRegions) {
            dirtyRegions.add(key(rx, rz));
        }
        FullCaveTextureManager.getInstance().markRegionTextureDirty(rx, rz);
        int activeLayer = CaveMapManager.getInstance().getActiveLayerY();
        if (activeLayer != Integer.MIN_VALUE) {
            CaveTextureManager.getInstance().markRegionTextureDirty(activeLayer, rx, rz);
        }
    }

    private void saveDirtyRegions() {
        File directory = cacheDirectory;
        if (directory == null) return;
        Set<String> keys;
        synchronized (dirtyRegions) {
            if (dirtyRegions.isEmpty()) return;
            keys = new HashSet<>(dirtyRegions);
            dirtyRegions.clear();
        }
        for (String key : keys) {
            FullRegion region;
            synchronized (regions) {
                region = regions.get(key);
            }
            if (region != null && region.isLoaded()) saveRegion(region, directory);
        }
    }

    private void saveRegion(FullRegion region, File directory) {
        if (directory == null || region == null || !region.isLoaded()) return;
        FullSaveRequest request = new FullSaveRequest(directory, region.rx, region.rz,
                region.getGeneration(), region.snapshot());
        pendingSaves.put(request.key(), request);
        scheduleSaveDrain();
    }

    private void scheduleSaveDrain() {
        if (!saveDrainScheduled.compareAndSet(false, true)) return;
        SAVE_POOL.execute(() -> {
            try {
                while (true) {
                    FullSaveRequest request = pendingSaves.values().stream().findFirst().orElse(null);
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

    private void writeSaveRequest(FullSaveRequest request) {
        File directory = request.directory();
        File file = new File(directory, fileName(request.rx(), request.rz()));
        File temporary = null;
        try {
            Files.createDirectories(directory.toPath());
            temporary = Files.createTempFile(directory.toPath(), file.getName() + ".", ".tmp").toFile();
            FullSnapshot snapshot = request.copySnapshot();
            byte[] raw = new byte[PIXELS * 6];
            int pointer = 0;
            for (int i = 0; i < PIXELS; i++) {
                int pixel = snapshot.pixels()[i];
                short height = snapshot.heights()[i];
                raw[pointer++] = (byte) (pixel >>> 24);
                raw[pointer++] = (byte) (pixel >>> 16);
                raw[pointer++] = (byte) (pixel >>> 8);
                raw[pointer++] = (byte) pixel;
                raw[pointer++] = (byte) (height >>> 8);
                raw[pointer++] = (byte) height;
            }
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    new GZIPOutputStream(new FileOutputStream(temporary), 64 * 1024)))) {
                output.writeInt(FILE_MAGIC);
                output.writeInt(FILE_VERSION);
                output.writeInt(PIXELS);
                output.write(raw);
            }
            atomicReplace(temporary, file);
            if (request.generation() == generation.get() && sameFile(cacheDirectory, directory)) {
                fileExists.put(key(request.rx(), request.rz()), true);
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to save full cave region {},{}", request.rx(), request.rz(), exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary.toPath()); } catch (IOException ignored) { }
            }
        }
    }

    private FullSaveRequest latestSave(File directory, int rx, int rz) {
        if (directory == null) return null;
        String saveKey = saveKey(directory, rx, rz);
        FullSaveRequest request = pendingSaves.get(saveKey);
        return request != null ? request : inFlightSaves.get(saveKey);
    }

    private void evictRegionsIfNeeded() {
        while (regions.size() > MAX_REGIONS) {
            Iterator<Map.Entry<String, FullRegion>> iterator = regions.entrySet().iterator();
            Map.Entry<String, FullRegion> selected = null;
            while (iterator.hasNext()) {
                Map.Entry<String, FullRegion> candidate = iterator.next();
                if (candidate.getValue().isLoaded()) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) return;
            String selectedKey = selected.getKey();
            FullRegion region = selected.getValue();
            iterator.remove();
            boolean dirty;
            synchronized (dirtyRegions) {
                dirty = dirtyRegions.remove(selectedKey);
            }
            if (dirty && cacheDirectory != null) saveRegion(region, cacheDirectory);
            region.close();
        }
    }

    private void clearRegions() {
        synchronized (regions) {
            for (FullRegion region : regions.values()) region.close();
            regions.clear();
        }
        synchronized (dirtyRegions) {
            dirtyRegions.clear();
        }
    }

    private static String saveKey(File directory, int rx, int rz) {
        return new File(directory, fileName(rx, rz)).toPath().toAbsolutePath().normalize().toString();
    }

    private record FullSaveRequest(File directory, int rx, int rz, long generation,
            FullSnapshot snapshot) {
        private FullSaveRequest {
            snapshot = new FullSnapshot(Arrays.copyOf(snapshot.pixels(), snapshot.pixels().length),
                    Arrays.copyOf(snapshot.heights(), snapshot.heights().length));
        }

        private String key() {
            return saveKey(directory, rx, rz);
        }

        private FullSnapshot copySnapshot() {
            return new FullSnapshot(Arrays.copyOf(snapshot.pixels(), snapshot.pixels().length),
                    Arrays.copyOf(snapshot.heights(), snapshot.heights().length));
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

    private static String legacyColorFileName(int rx, int rz) {
        return "r." + rx + "." + rz + ".png";
    }

    private static String legacyHeightFileName(int rx, int rz) {
        return "r." + rx + "." + rz + ".y.gz";
    }

    public record FullSnapshot(int[] pixels, short[] heights) {
    }

    public static final class FullRegion {
        public final int rx;
        public final int rz;
        private final long generation;
        private final int[] pixels = new int[PIXELS];
        private final short[] heights = new short[PIXELS];
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
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

        private FullSnapshot snapshot() {
            lock.lock();
            try { return new FullSnapshot(Arrays.copyOf(pixels, pixels.length),
                    Arrays.copyOf(heights, heights.length)); }
            finally { lock.unlock(); }
        }

        private void replace(FullSnapshot snapshot) {
            lock.lock();
            try {
                if (closed) return;
                System.arraycopy(snapshot.pixels(), 0, pixels, 0, PIXELS);
                System.arraycopy(snapshot.heights(), 0, heights, 0, PIXELS);
            } finally {
                lock.unlock();
            }
        }

        private boolean mergeCandidate(int px, int pz, int color, int surfaceY, int observationTopY) {
            lock.lock();
            try {
                if (closed) return false;
                int index = pz * 512 + px;
                int clampedY = Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, surfaceY));
                if (heights[index] == NO_SURFACE || clampedY >= heights[index]
                        || observationTopY >= heights[index]) {
                    boolean changed = heights[index] != (short) clampedY || pixels[index] != color;
                    heights[index] = (short) clampedY;
                    pixels[index] = color;
                    return changed;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }
    }
}
