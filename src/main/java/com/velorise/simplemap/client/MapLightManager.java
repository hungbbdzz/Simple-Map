package com.velorise.simplemap.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.BitSet;
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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local surface block-light cache. Disk work is asynchronous and every result
 * is guarded by a world-generation token so old dimensions cannot publish into
 * the newly active map.
 */
public final class MapLightManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapLightManager INSTANCE = new MapLightManager();
    private static final int PIXEL_COUNT = 512 * 512;
    private static final int MAX_REGIONS = 96;

    private static final ThreadPoolExecutor LOAD_POOL = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(96), runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-LightLoad");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ExecutorService SAVE_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SimpleMap-LightSave");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    private final Map<String, LightRegion> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyRegions = new HashSet<>();
    private final Map<String, Boolean> fileExists = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, LightSaveRequest> pendingSaves = new ConcurrentHashMap<>();
    private final Map<String, LightSaveRequest> inFlightSaves = new ConcurrentHashMap<>();
    private final AtomicBoolean saveDrainScheduled = new AtomicBoolean();
    private volatile File cacheDirectory;
    private long lastSaveTime;

    public static MapLightManager getInstance() {
        return INSTANCE;
    }

    private MapLightManager() {
    }

    public void setLight(int blockX, int blockZ, int light) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        LightRegion region = getRegion(rx, rz, true);
        if (region == null) return;
        int px = blockX & 511;
        int pz = blockZ & 511;
        if (region.setLight(px, pz, light)) {
            synchronized (dirtyRegions) {
                dirtyRegions.add(key(rx, rz));
            }
            MapTextureManager.getInstance().markRegionDirty(rx, rz);
        }
    }

    public LightRegion getRegion(int rx, int rz, boolean create) {
        String key = key(rx, rz);
        synchronized (regions) {
            LightRegion existing = regions.get(key);
            if (existing != null || !create || cacheDirectory == null) return existing;

            long token = generation.get();
            LightRegion created = new LightRegion(rx, rz, token);
            regions.put(key, created);
            File directory = cacheDirectory;
            File file = new File(directory, fileName(rx, rz));
            LightSaveRequest pending = latestSave(directory, rx, rz);
            boolean exists = pending != null || fileExists.computeIfAbsent(key, ignored -> file.isFile());
            if (pending != null) {
                created.applyLoaded(pending.levels());
                created.markLoaded();
            } else if (exists) loadRegionAsync(created, directory, token);
            else created.markLoaded();
            evictOldRegions(directory);
            return created;
        }
    }

    public synchronized void setCacheDirectory(File directory) {
        File normalized = directory == null ? null : directory.getAbsoluteFile();
        if (sameFile(cacheDirectory, normalized)) return;
        flushCurrentDirectory();
        generation.incrementAndGet();
        closeAndClearRegions();
        fileExists.clear();
        cacheDirectory = normalized;
        if (normalized != null) {
            try {
                Files.createDirectories(normalized.toPath());
            } catch (IOException exception) {
                LOGGER.warn("Could not create SimpleMap light-cache directory {}", normalized, exception);
            }
        }
    }

    public void tickSave() {
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < 10_000L) return;
        lastSaveTime = now;
        saveDirtyRegions(cacheDirectory);
    }

    public synchronized void flushAndClear() {
        flushCurrentDirectory();
        generation.incrementAndGet();
        closeAndClearRegions();
        fileExists.clear();
        cacheDirectory = null;
    }

    private void loadRegionAsync(LightRegion region, File directory, long token) {
        File file = new File(directory, fileName(region.rx, region.rz));
        try {
            LOAD_POOL.execute(() -> {
                byte[] bytes = null;
                LightSaveRequest pending = latestSave(directory, region.rx, region.rz);
                if (pending != null) {
                    bytes = Arrays.copyOf(pending.levels(), pending.levels().length);
                } else {
                    try {
                        long length = Files.size(file.toPath());
                        if (length == PIXEL_COUNT) {
                            bytes = Files.readAllBytes(file.toPath());
                        } else {
                            throw new IOException("Invalid light-cache size " + length);
                        }
                    } catch (IOException exception) {
                        LOGGER.warn("Failed to read light cache {}", file.getName(), exception);
                        quarantineCorruptFile(file);
                        if (token == generation.get() && sameFile(cacheDirectory, directory)) {
                            fileExists.remove(key(region.rx, region.rz));
                        }
                    }
                }
                if (!isCurrent(region, directory, token)) return;
                if (bytes != null) region.applyLoaded(bytes);
                region.markLoaded();
                MapTextureManager.getInstance().markRegionDirty(region.rx, region.rz);
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

    private boolean isCurrent(LightRegion region, File directory, long token) {
        if (token != generation.get() || !sameFile(cacheDirectory, directory) || region.closed) return false;
        synchronized (regions) {
            return regions.get(key(region.rx, region.rz)) == region;
        }
    }

    private void flushCurrentDirectory() {
        saveDirtyRegions(cacheDirectory);
    }

    private void saveDirtyRegions(File directory) {
        if (directory == null) return;
        Set<String> keys;
        synchronized (dirtyRegions) {
            if (dirtyRegions.isEmpty()) return;
            keys = new HashSet<>(dirtyRegions);
            dirtyRegions.clear();
        }
        synchronized (regions) {
            for (String key : keys) {
                LightRegion region = regions.get(key);
                if (region != null && !region.closed) saveRegionAsync(region, directory);
            }
        }
    }

    private void saveRegionAsync(LightRegion region, File directory) {
        if (directory == null || region == null || region.closed) return;
        LightSaveRequest request = new LightSaveRequest(directory, region.rx, region.rz,
                region.getGeneration(), region.snapshot());
        pendingSaves.put(request.key(), request);
        scheduleSaveDrain();
    }

    private void scheduleSaveDrain() {
        if (!saveDrainScheduled.compareAndSet(false, true)) return;
        SAVE_POOL.execute(() -> {
            try {
                while (true) {
                    LightSaveRequest request = pendingSaves.values().stream().findFirst().orElse(null);
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

    private void writeSaveRequest(LightSaveRequest request) {
        File directory = request.directory();
        File file = new File(directory, fileName(request.rx(), request.rz()));
        File temporary = null;
        try {
            Files.createDirectories(directory.toPath());
            temporary = Files.createTempFile(directory.toPath(), file.getName() + ".", ".tmp").toFile();
            Files.write(temporary.toPath(), request.levels());
            atomicReplace(temporary, file);
            if (request.generation() == generation.get() && sameFile(cacheDirectory, directory)) {
                fileExists.put(key(request.rx(), request.rz()), true);
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to write light cache {}", file.getName(), exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private LightSaveRequest latestSave(File directory, int rx, int rz) {
        String saveKey = saveKey(directory, rx, rz);
        LightSaveRequest request = pendingSaves.get(saveKey);
        return request != null ? request : inFlightSaves.get(saveKey);
    }

    private void evictOldRegions(File directory) {
        while (regions.size() > MAX_REGIONS) {
            Iterator<Map.Entry<String, LightRegion>> iterator = regions.entrySet().iterator();
            if (!iterator.hasNext()) return;
            Map.Entry<String, LightRegion> eldest = iterator.next();
            iterator.remove();
            boolean dirty;
            synchronized (dirtyRegions) {
                dirty = dirtyRegions.remove(eldest.getKey());
            }
            if (dirty && directory != null) saveRegionAsync(eldest.getValue(), directory);
            eldest.getValue().close();
        }
    }

    private void closeAndClearRegions() {
        synchronized (regions) {
            for (LightRegion region : regions.values()) region.close();
            regions.clear();
        }
        synchronized (dirtyRegions) {
            dirtyRegions.clear();
        }
    }



    private static void quarantineCorruptFile(File file) {
        if (file == null || !file.isFile()) return;
        File quarantine = new File(file.getParentFile(),
                file.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Moved unreadable light cache to {}", quarantine.getName());
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine unreadable light cache {}", file, exception);
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
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.toPath().toAbsolutePath().normalize()
                .equals(second.toPath().toAbsolutePath().normalize());
    }

    private static String key(int rx, int rz) {
        return rx + "," + rz;
    }

    private static String fileName(int rx, int rz) {
        return "l." + rx + "." + rz + ".bin";
    }

    private static String saveKey(File directory, int rx, int rz) {
        return new File(directory, fileName(rx, rz)).toPath().toAbsolutePath().normalize().toString();
    }

    private record LightSaveRequest(File directory, int rx, int rz, long generation, byte[] levels) {
        private LightSaveRequest {
            levels = Arrays.copyOf(levels, levels.length);
        }

        private String key() {
            return saveKey(directory, rx, rz);
        }
    }

    public static final class LightRegion {
        private final int rx;
        private final int rz;
        private final long generation;
        private final byte[] levels = new byte[PIXEL_COUNT];
        private final BitSet modifiedBeforeLoad = new BitSet(PIXEL_COUNT);
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean loaded;
        private volatile boolean closed;

        private LightRegion(int rx, int rz, long generation) {
            this.rx = rx;
            this.rz = rz;
            this.generation = generation;
        }

        public void lock() {
            lock.lock();
        }

        public void unlock() {
            lock.unlock();
        }

        public byte[] getLevelsDirect() {
            return levels;
        }

        public boolean isLoaded() {
            return loaded && !closed;
        }

        public long getGeneration() {
            return generation;
        }

        private void markLoaded() {
            if (!closed) loaded = true;
        }

        private void close() {
            closed = true;
        }

        private void applyLoaded(byte[] source) {
            lock.lock();
            try {
                if (closed) return;
                for (int i = 0; i < levels.length; i++) {
                    if (!modifiedBeforeLoad.get(i)) levels[i] = source[i];
                }
                modifiedBeforeLoad.clear();
            } finally {
                lock.unlock();
            }
        }

        private byte[] snapshot() {
            lock.lock();
            try {
                return Arrays.copyOf(levels, levels.length);
            } finally {
                lock.unlock();
            }
        }

        private boolean setLight(int px, int pz, int light) {
            int clamped = Math.max(0, Math.min(15, light));
            int index = pz * 512 + px;
            lock.lock();
            try {
                if (closed || (levels[index] & 0xFF) == clamped) return false;
                levels[index] = (byte) clamped;
                if (!loaded) modifiedBeforeLoad.set(index);
                return true;
            } finally {
                lock.unlock();
            }
        }
    }
}
