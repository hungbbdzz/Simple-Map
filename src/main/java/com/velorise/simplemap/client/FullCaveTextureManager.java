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
    private static final int MAX_VISIBLE_HISTORY = MAX_TEXTURE_REGIONS * 4;
    private static final long VISIBLE_TTL_MS = 2_000L;

    private final Map<String, TextureInfo> textures = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyTextures = new LinkedHashSet<>();
    private final Map<String, Long> visibleTextures = new LinkedHashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final List<TextureInfo> deferredCloses = new ArrayList<>();
    private int renderBatchDepth;
    private long lastUploadTime;

    private FullCaveTextureManager() {
    }

    public static FullCaveTextureManager getInstance() {
        return INSTANCE;
    }

    public void markRegionTextureDirty(int rx, int rz) {
        String key = key(rx, rz);
        synchronized (dirtyTextures) {
            // One revision per queued rebuild is enough. Thousands of changed
            // columns in the same region now collapse into a single texture job.
            if (dirtyTextures.add(key)) revisions.merge(key, 1L, Long::sum);
        }
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
        synchronized (textures) {
            if (renderBatchDepth > 0) renderBatchDepth--;
            if (renderBatchDepth == 0 && !deferredCloses.isEmpty()) {
                closeNow = new ArrayList<>(deferredCloses);
                deferredCloses.clear();
            }
        }
        if (closeNow != null) for (TextureInfo info : closeNow) info.close();
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
        for (String key : selectDirty(budget)) {
            if (System.nanoTime() > deadline && !force) {
                requeue(key);
                continue;
            }
            int comma = key.indexOf(',');
            int rx = Integer.parseInt(key.substring(0, comma));
            int rz = Integer.parseInt(key.substring(comma + 1));
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
                if (manager.isGenerationCurrent(info.generation)) applyPrepared(info, prepared);
            } catch (RuntimeException ignored) {
            } finally {
                info.pending = null;
            }
            synchronized (dirtyTextures) {
                if (revisions.getOrDefault(key, 0L) > info.uploadedRevision) dirtyTextures.add(key);
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
                "caves/full/r_" + rx + '_' + rz);
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
                for (String key : textures.keySet()) {
                    revisions.merge(key, 1L, Long::sum);
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
        synchronized (dirtyTextures) {
            dirtyTextures.clear();
            revisions.clear();
        }
        synchronized (visibleTextures) {
            visibleTextures.clear();
        }
    }

    private List<String> selectDirty(int budget) {
        List<String> result = new ArrayList<>(budget);
        synchronized (dirtyTextures) {
            synchronized (visibleTextures) {
                for (String key : visibleTextures.keySet()) {
                    if (dirtyTextures.contains(key)) result.add(key);
                    if (result.size() >= budget) break;
                }
            }
            if (result.size() < budget) {
                for (String key : dirtyTextures) {
                    if (!result.contains(key)) result.add(key);
                    if (result.size() >= budget) break;
                }
            }
            dirtyTextures.removeAll(result);
        }
        return result;
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
        return rx + "," + rz;
    }

    private static final class TextureInfo {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final long generation;
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
}
