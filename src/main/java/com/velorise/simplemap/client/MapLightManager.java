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
import java.util.concurrent.ConcurrentHashMap;
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
    private static final int MAX_PENDING_SAVES = 12;
    private static final long SAVE_RETRY_DELAY_MS = 500L;

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
            MapTextureManager.getInstance().markPageDirtyForBlock(blockX, blockZ);
            int localChunkIndex = ((blockZ & 511) >>> 4) * 32
                    + ((blockX & 511) >>> 4);
            SurfaceRegionSourceDatabase.getInstance().markChunkDirty(
                    rx, rz, localChunkIndex);
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
        pumpDirtyRegionSaves(2);
    }

    /** Pulls only a small number of dirty light regions into the IO queue. */
    int pumpDirtyRegionSaves(int maximum) {
        File directory = cacheDirectory;
        if (directory == null || maximum <= 0) return dirtyRegionCount();
        int admitted = 0;
        while (admitted < maximum) {
            String selected;
            synchronized (dirtyRegions) {
                Iterator<String> iterator = dirtyRegions.iterator();
                if (!iterator.hasNext()) break;
                selected = iterator.next();
                iterator.remove();
            }
            LightRegion region;
            synchronized (regions) {
                region = regions.get(selected);
            }
            if (region == null || region.closed) continue;
            if (!saveRegionAsync(region, directory, false)) {
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

    int pendingSaveCount() {
        return pendingSaves.size();
    }

    int inFlightSaveCount() {
        return inFlightSaves.size();
    }

    public synchronized void flushAndClear() {
        flushCurrentDirectory();
        generation.incrementAndGet();
        closeAndClearRegions();
        fileExists.clear();
        cacheDirectory = null;
    }

    private void loadRegionAsync(LightRegion region, File directory, long token) {
        if (!region.beginLoad()) return;
        File file = new File(directory, fileName(region.rx, region.rz));
        MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                MapRequestLane.FULLSCREEN, MapWorkScheduler.WorkType.DISK_READ,
                MapRequestLane.FULLSCREEN.priorityBase(), 10,
                () -> isCurrent(region, directory, token), () -> {
            try {
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
                MapTextureManager.getInstance()
                        .markRegionSourceAvailable(region.rx, region.rz);
            } finally {
                region.finishLoad();
            }
        });
    }

    private boolean isCurrent(LightRegion region, File directory, long token) {
        if (token != generation.get() || !sameFile(cacheDirectory, directory) || region.closed) return false;
        synchronized (regions) {
            return regions.get(key(region.rx, region.rz)) == region;
        }
    }

    private void flushCurrentDirectory() {
        File directory = cacheDirectory;
        if (directory == null) return;
        Set<String> keys;
        synchronized (dirtyRegions) {
            keys = new HashSet<>(dirtyRegions);
            dirtyRegions.clear();
        }
        synchronized (regions) {
            for (String key : keys) {
                LightRegion region = regions.get(key);
                if (region != null && !region.closed) saveRegionAsync(region, directory, true);
            }
        }
    }

    private boolean saveRegionAsync(LightRegion region, File directory, boolean force) {
        if (directory == null || region == null || region.closed) return false;
        String saveKey = saveKey(directory, region.rx, region.rz);
        if (!force && !pendingSaves.containsKey(saveKey)
                && !inFlightSaves.containsKey(saveKey)
                && pendingSaves.size() >= MAX_PENDING_SAVES) return false;
        LightSaveRequest request = new LightSaveRequest(directory, region.rx, region.rz,
                region.getGeneration(), region.snapshot());
        pendingSaves.put(request.key(), request);
        scheduleSaveDrain(0L);
        return true;
    }

    private void scheduleSaveDrain(long delayMs) {
        if (!saveDrainScheduled.compareAndSet(false, true)) return;
        MapWorkScheduler.scheduleIo(Math.max(0L, delayMs), TimeUnit.MILLISECONDS,
                MapRequestLane.BACKGROUND, MapWorkScheduler.WorkType.DISK_WRITE,
                0, 12, () -> true, () -> {
            boolean failed = false;
            LightSaveRequest request = null;
            try {
                Iterator<LightSaveRequest> iterator = pendingSaves.values().iterator();
                if (iterator.hasNext()) request = iterator.next();
                if (request == null || !pendingSaves.remove(request.key(), request)) return;
                inFlightSaves.put(request.key(), request);
                try {
                    if (!writeSaveRequest(request)) {
                        failed = true;
                        pendingSaves.putIfAbsent(request.key(), request);
                    }
                } finally {
                    inFlightSaves.remove(request.key(), request);
                }
            } finally {
                saveDrainScheduled.set(false);
                if (!pendingSaves.isEmpty()) {
                    scheduleSaveDrain(failed ? SAVE_RETRY_DELAY_MS : 2L);
                }
            }
        });
    }

    private boolean writeSaveRequest(LightSaveRequest request) {
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
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to write light cache {}", file.getName(), exception);
            return false;
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
            if (dirty && directory != null
                    && !saveRegionAsync(eldest.getValue(), directory, false)) {
                // Do not evict the only authoritative dirty copy merely because
                // the bounded save queue is full. It will be reconsidered later.
                regions.put(eldest.getKey(), eldest.getValue());
                synchronized (dirtyRegions) {
                    dirtyRegions.add(eldest.getKey());
                }
                return;
            }
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
        private final AtomicBoolean loadScheduled = new AtomicBoolean();

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

        /** Copies a small immutable light rectangle for a page + halo job. */
        public byte[] snapshotWindow(int minX, int minZ, int width, int height) {
            if (minX < 0 || minZ < 0 || width <= 0 || height <= 0
                    || minX + width > MapPageLayout.REGION_SIZE
                    || minZ + height > MapPageLayout.REGION_SIZE) {
                throw new IllegalArgumentException("Invalid light window "
                        + minX + "," + minZ + " " + width + "x" + height);
            }
            lock.lock();
            try {
                byte[] result = new byte[width * height];
                for (int z = 0; z < height; z++) {
                    System.arraycopy(levels,
                            (minZ + z) * MapPageLayout.REGION_SIZE + minX,
                            result, z * width, width);
                }
                return result;
            } finally {
                lock.unlock();
            }
        }

        public boolean isLoaded() {
            return loaded && !closed;
        }

        public long getGeneration() {
            return generation;
        }

        private boolean beginLoad() {
            return !closed && loadScheduled.compareAndSet(false, true);
        }

        private void finishLoad() {
            loadScheduled.set(false);
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
