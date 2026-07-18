package com.velorise.simplemap.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Render-thread-owned GPU cache for the stable full-cave composite. */
public final class FullCaveTextureManager {
    private static final FullCaveTextureManager INSTANCE = new FullCaveTextureManager();
    private static final int MAX_TEXTURE_REGIONS = 128;
    private static final int MAX_TEXTURE_PAGES = 768;
    private static final int MAX_VISIBLE_HISTORY = MAX_TEXTURE_REGIONS * 4;
    private static final long VISIBLE_TTL_MS = 2_000L;
    private static final long DIRTY_QUIET_NANOS = 45_000_000L;
    private static final long DIRTY_MAX_WAIT_NANOS = 220_000_000L;

    private final Map<String, TextureInfo> textures = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, PageTextureInfo> pageCache = new LinkedHashMap<>(128, 0.75f, true);
    private final Set<String> dirtyTextures = new LinkedHashSet<>();
    private final Map<String, Long> visibleTextures = new LinkedHashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final Map<String, Long> firstDirtyNanos = new HashMap<>();
    private final Map<String, Long> lastDirtyNanos = new HashMap<>();
    private final List<TextureInfo> deferredCloses = new ArrayList<>();
    private final List<PageTextureInfo> deferredPageCloses = new ArrayList<>();
    private int renderBatchDepth;
    private long lastUploadTime;

    private FullCaveTextureManager() {
    }

    public static FullCaveTextureManager getInstance() {
        return INSTANCE;
    }

    public void markRegionTextureDirty(int rx, int rz) {
        String key = key(rx, rz);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            revisions.merge(key, 1L, Long::sum);
            firstDirtyNanos.putIfAbsent(key, now);
            lastDirtyNanos.put(key, now);
            dirtyTextures.add(key);
        }
    }

    /**
     * Marks regions in the given world-coordinate viewport as visible for
     * upload priority. Called from client tick via MapViewportCoordinator.
     */
    public void requestVisiblePages(double minX, double maxX, double minZ, double maxZ) {
        int minRx = (int) Math.floor(minX - 1.0) >> 9;
        int maxRx = (int) Math.floor(maxX + 1.0) >> 9;
        int minRz = (int) Math.floor(minZ - 1.0) >> 9;
        int maxRz = (int) Math.floor(maxZ + 1.0) >> 9;
        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                markVisible(key(rx, rz));
            }
        }
    }

    public void requestVisibleRegion(int rx, int rz) {
        markVisible(key(rx, rz));
    }

    public ResourceLocation getRegionTexture(int rx, int rz) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            MapProcessor.getInstance().enqueueFullCaveLoad(rx, rz, 1);
            return null;
        }
        String key = key(rx, rz);
        markVisible(key);
        FullCaveMapManager manager = FullCaveMapManager.getInstance();
        FullCaveMapManager.FullRegion region = manager.getRegion(rx, rz, false);
        if (region == null) {
            if (manager.hasRegionFile(rx, rz)) {
                MapProcessor.getInstance().enqueueFullCaveLoad(rx, rz, distancePriority(rx, rz));
            }
            return null;
        }
        if (!region.isLoaded()) return null;

        TextureInfo info;
        synchronized (textures) {
            info = textures.get(key);
            if (info == null) {
                info = createTexture(rx, rz, manager.getGeneration());
                textures.put(key, info);
                trimCache();
                schedulePreparation(key, info, region);
            } else if (info.generation != manager.getGeneration()) {
                if (info.pending != null) info.pending.cancel(false);
                info.pending = null;
                info.generation = manager.getGeneration();
            }
        }
        return info.initialized ? info.location : null;
    }


    public void beginRenderBatch() {
        synchronized (textures) {
            renderBatchDepth++;
        }
    }

    public void endRenderBatch() {
        List<TextureInfo> closeNow = null;
        List<PageTextureInfo> closePagesNow = null;
        synchronized (textures) {
            if (renderBatchDepth > 0) renderBatchDepth--;
            if (renderBatchDepth == 0) {
                if (!deferredCloses.isEmpty()) {
                    closeNow = new ArrayList<>(deferredCloses);
                    deferredCloses.clear();
                }
                if (!deferredPageCloses.isEmpty()) {
                    closePagesNow = new ArrayList<>(deferredPageCloses);
                    deferredPageCloses.clear();
                }
            }
        }
        if (closeNow != null) for (TextureInfo info : closeNow) info.close();
        if (closePagesNow != null) for (PageTextureInfo info : closePagesNow) info.close();
    }

    /** Returns only an already uploaded 64x64 sub-page full-cave tile. */
    public ResourceLocation peekPageTexture(int rx, int rz, int pageX, int pageZ) {
        String key = pageKey(rx, rz, pageX, pageZ);
        synchronized (pageCache) {
            PageTextureInfo info = pageCache.get(key);
            if (info == null || !info.initialized) return null;
            markVisible(key(rx, rz));
            return info.location;
        }
    }

    public boolean hasAnyPageTexture(int rx, int rz) {
        String prefix = key(rx, rz) + ":";
        synchronized (pageCache) {
            for (String key : pageCache.keySet()) {
                if (key.startsWith(prefix)) return true;
            }
        }
        return false;
    }

    /** Returns only an already uploaded full-cave tile; performs no IO or rebuild. */
    public ResourceLocation peekRegionTexture(int rx, int rz) {
        String key = key(rx, rz);
        synchronized (textures) {
            TextureInfo info = textures.get(key);
            if (info == null || !info.initialized) return null;
            markVisible(key);
            return info.location;
        }
    }

    public void uploadDirtyTextures() {
        uploadDirtyTextures(false);
    }

    public void uploadDirtyTextures(boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> uploadDirtyTextures(force));
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastUploadTime < 50L) return;
        lastUploadTime = now;
        pruneVisibility(now);

        int budget = force ? 8 : 3;
        long deadline = System.nanoTime() + (force ? 8_000_000L : 4_000_000L);
        for (String key : selectDirty(budget, force)) {
            if (System.nanoTime() > deadline && !force) {
                requeue(key);
                continue;
            }
            int separator = key.lastIndexOf('|');
            String coordinates = key.substring(separator + 1);
            int comma = coordinates.indexOf(',');
            int rx = Integer.parseInt(coordinates.substring(0, comma));
            int rz = Integer.parseInt(coordinates.substring(comma + 1));
            FullCaveMapManager manager = FullCaveMapManager.getInstance();
            FullCaveMapManager.FullRegion region = manager.getRegion(rx, rz, false);
            if (region == null || !region.isLoaded()) {
                MapProcessor.getInstance().enqueueFullCaveLoad(rx, rz, distancePriority(rx, rz));
                requeue(key);
                continue;
            }
            TextureInfo info;
            synchronized (textures) {
                info = textures.get(key);
                if (info == null) {
                    info = createTexture(rx, rz, manager.getGeneration());
                    textures.put(key, info);
                    trimCache();
                } else if (info.generation != manager.getGeneration()) {
                    if (info.pending != null) info.pending.cancel(false);
                    info.pending = null;
                    info.generation = manager.getGeneration();
                }
            }
            schedulePreparation(key, info, region);
        }
    }

    private void schedulePreparation(String key, TextureInfo info,
            FullCaveMapManager.FullRegion region) {
        FullCaveMapManager manager = FullCaveMapManager.getInstance();
        if (!manager.isGenerationCurrent(info.generation)) return;
        if (info.pending != null) {
            if (!info.pending.isDone()) return;
            try {
                MapTextureBuildWorker.PreparedSingle prepared = info.pending.join();
                if (manager.isGenerationCurrent(info.generation)) {
                    // Full-cave regions are also filled continuously. Publish a
                    // complete prepared snapshot even when a few newer columns exist,
                    // then queue the next revision instead of starving the GPU image.
                    applyPrepared(info, prepared);

                    synchronized (dirtyTextures) {
                        long currentRevision = revisions.getOrDefault(key, 0L);
                        if (currentRevision > prepared.revision()) {
                            dirtyTextures.add(key);
                        } else {
                            dirtyTextures.remove(key);
                            firstDirtyNanos.remove(key);
                            lastDirtyNanos.remove(key);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                requeue(key);
            } finally {
                info.pending = null;
            }
            return;
        }

        int[] pixels = new int[512 * 512];
        short[] heights = new short[512 * 512];
        region.lock();
        try {
            System.arraycopy(region.getPixelsDirect(), 0, pixels, 0, pixels.length);
            System.arraycopy(region.getHeightsDirect(), 0, heights, 0, heights.length);
        } finally {
            region.unlock();
        }
        long revision;
        synchronized (dirtyTextures) {
            revision = revisions.getOrDefault(key, 0L);
        }
        CompletableFuture<MapTextureBuildWorker.PreparedSingle> future =
                MapTextureBuildWorker.tryBuildCave(pixels, heights, MapConfig.terrainSlopes,
                        MapConfig.mapColorProfile, revision,
                        () -> manager.isGenerationCurrent(info.generation));
        if (future == null) {
            requeue(key);
            return;
        }
        info.pending = future;
        future.whenComplete((ignored, throwable) -> {
            synchronized (textures) {
                if (textures.get(key) != info) return;
            }
            requeue(key);
        });
    }

    private TextureInfo createTexture(int rx, int rz, long generation) {
        DynamicTexture texture = new DynamicTexture(512, 512, false);
        texture.setFilter(false, false);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("simplemap",
                "caves/" + texturePathDimension() + "/full/r_" + rx + '_' + rz);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return new TextureInfo(texture, location, generation);
    }

    private void applyPrepared(TextureInfo info, MapTextureBuildWorker.PreparedSingle prepared) {
        NativeImage image = info.texture.getPixels();
        if (image == null) return;
        int[] pixels = prepared.styled();
        for (int z = 0; z < 512; z++) {
            int row = z * 512;
            for (int x = 0; x < 512; x++) image.setPixelRGBA(x, z, pixels[row + x]);
        }
        info.texture.upload();
        info.texture.setFilter(false, false);
        info.uploadedRevision = prepared.revision();
        info.initialized = true;
    }

    public void invalidateStyle() {
        synchronized (textures) {
            synchronized (dirtyTextures) {
                long now = System.nanoTime();
                for (String key : textures.keySet()) {
                    revisions.merge(key, 1L, Long::sum);
                    firstDirtyNanos.putIfAbsent(key, now);
                    lastDirtyNanos.put(key, now);
                    dirtyTextures.add(key);
                }
            }
        }
    }

    public void clearCache() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::clearCache);
            return;
        }
        List<TextureInfo> closeNow = new ArrayList<>();
        synchronized (textures) {
            closeNow.addAll(textures.values());
            closeNow.addAll(deferredCloses);
            textures.clear();
            deferredCloses.clear();
            renderBatchDepth = 0;
        }
        for (TextureInfo info : closeNow) info.close();

        List<PageTextureInfo> closePagesNow = new ArrayList<>();
        synchronized (pageCache) {
            closePagesNow.addAll(pageCache.values());
            closePagesNow.addAll(deferredPageCloses);
            pageCache.clear();
            deferredPageCloses.clear();
        }
        for (PageTextureInfo info : closePagesNow) info.close();
        synchronized (dirtyTextures) {
            dirtyTextures.clear();
            revisions.clear();
            firstDirtyNanos.clear();
            lastDirtyNanos.clear();
        }
        synchronized (visibleTextures) {
            visibleTextures.clear();
        }
    }

    private List<String> selectDirty(int budget, boolean force) {
        List<String> result = new ArrayList<>(budget);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            synchronized (visibleTextures) {
                for (String key : visibleTextures.keySet()) {
                    if (isCurrentDimensionKey(key) && dirtyTextures.contains(key)
                            && isReadyForPublication(key, now, force)) {
                        result.add(key);
                    }
                    if (result.size() >= budget) break;
                }
            }
            if (result.size() < budget) {
                for (String key : dirtyTextures) {
                    if (isCurrentDimensionKey(key) && !result.contains(key)
                            && isReadyForPublication(key, now, force)) {
                        result.add(key);
                    }
                    if (result.size() >= budget) break;
                }
            }
            dirtyTextures.removeAll(result);
        }
        return result;
    }

    private boolean isReadyForPublication(String key, long now, boolean force) {
        if (force) return true;
        long first = firstDirtyNanos.getOrDefault(key, now);
        long last = lastDirtyNanos.getOrDefault(key, first);
        return now - last >= DIRTY_QUIET_NANOS || now - first >= DIRTY_MAX_WAIT_NANOS;
    }

    private void markVisible(String key) {
        synchronized (visibleTextures) {
            visibleTextures.remove(key);
            visibleTextures.put(key, System.currentTimeMillis());
            while (visibleTextures.size() > MAX_VISIBLE_HISTORY) {
                var iterator = visibleTextures.entrySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    private void pruneVisibility(long now) {
        synchronized (visibleTextures) {
            visibleTextures.entrySet().removeIf(entry -> now - entry.getValue() > VISIBLE_TTL_MS);
        }
    }

    private void trimCache() {
        List<TextureInfo> retired = new ArrayList<>();
        while (textures.size() > MAX_TEXTURE_REGIONS) {
            var iterator = textures.entrySet().iterator();
            if (!iterator.hasNext()) break;
            TextureInfo info = iterator.next().getValue();
            iterator.remove();
            retired.add(info);
        }
        for (TextureInfo info : retired) retire(info);
    }

    private void retire(TextureInfo info) {
        if (renderBatchDepth > 0) deferredCloses.add(info);
        else info.close();
    }

    private void requeue(String key) {
        synchronized (dirtyTextures) {
            long now = System.nanoTime();
            firstDirtyNanos.putIfAbsent(key, now);
            lastDirtyNanos.putIfAbsent(key, now);
            dirtyTextures.add(key);
        }
    }

    private int distancePriority(int rx, int rz) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 1;
        int prx = player.blockPosition().getX() >> 9;
        int prz = player.blockPosition().getZ() >> 9;
        return Math.max(1, 10_000 - (Math.abs(rx - prx) + Math.abs(rz - prz)) * 100);
    }

    private static String key(int rx, int rz) {
        return MapManager.getInstance().getDimensionCacheKey() + "|" + rx + "," + rz;
    }

    private static String pageKey(int rx, int rz, int pageX, int pageZ) {
        return key(rx, rz) + ":" + pageX + "," + pageZ;
    }

    private static boolean isCurrentDimensionKey(String key) {
        return key != null && key.startsWith(
                MapManager.getInstance().getDimensionCacheKey() + "|");
    }

    private static String texturePathDimension() {
        return MapManager.getInstance().getDimensionCacheKey()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9/._-]", "_");
    }

    private static final class TextureInfo {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private long generation;
        private CompletableFuture<MapTextureBuildWorker.PreparedSingle> pending;
        private long uploadedRevision = Long.MIN_VALUE;
        private boolean initialized;

        private TextureInfo(DynamicTexture texture, ResourceLocation location, long generation) {
            this.texture = texture;
            this.location = location;
            this.generation = generation;
        }

        private void close() {
            if (pending != null) pending.cancel(false);
            Minecraft.getInstance().getTextureManager().release(location);
        }
    }

    private static final class PageTextureInfo {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private long generation;
        private CompletableFuture<MapTextureBuildWorker.PreparedSingle> pending;
        private boolean initialized;

        private PageTextureInfo(DynamicTexture texture, ResourceLocation location, long generation) {
            this.texture = texture;
            this.location = location;
            this.generation = generation;
        }

        private void close() {
            if (pending != null) pending.cancel(false);
            Minecraft.getInstance().getTextureManager().release(location);
        }
    }
}
