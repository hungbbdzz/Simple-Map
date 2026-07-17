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

/** Render-thread-owned GPU cache for the active bounded cave layer. */
public final class CaveTextureManager {
    private static final CaveTextureManager INSTANCE = new CaveTextureManager();
    private static final int MAX_TEXTURE_REGIONS = 128;
    private static final int MAX_VISIBLE_HISTORY = MAX_TEXTURE_REGIONS * 4;
    private static final long VISIBLE_TTL_MS = 2_000L;
    private static final long DIRTY_QUIET_NANOS = 45_000_000L;
    private static final long DIRTY_MAX_WAIT_NANOS = 220_000_000L;

    private final Map<String, TextureInfo> textures = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyTextures = new LinkedHashSet<>();
    private final Map<String, Long> visibleTextures = new LinkedHashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final Map<String, Long> firstDirtyNanos = new HashMap<>();
    private final Map<String, Long> lastDirtyNanos = new HashMap<>();
    private final List<TextureInfo> deferredCloses = new ArrayList<>();
    private int renderBatchDepth;
    private int activeTextureLayerY = Integer.MIN_VALUE;
    private long lastUploadTime;

    private CaveTextureManager() {
    }

    public static CaveTextureManager getInstance() {
        return INSTANCE;
    }

    public void markRegionTextureDirty(int layerY, int rx, int rz) {
        String key = key(layerY, rx, rz);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            revisions.merge(key, 1L, Long::sum);
            firstDirtyNanos.putIfAbsent(key, now);
            lastDirtyNanos.put(key, now);
            dirtyTextures.add(key);
        }
    }

    public void onLayerActivated(int layerY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> onLayerActivated(layerY));
            return;
        }
        if (activeTextureLayerY == layerY) return;
        activeTextureLayerY = layerY;
        // Old layer textures may remain cached for fast backtracking, but they are
        // never rendered as a bridge for a different Top-Y selection.
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

    public ResourceLocation getRegionTexture(int layerY, int rx, int rz) {
        CaveMapManager manager = CaveMapManager.getInstance();
        if (manager.getActiveLayerY() != layerY) return null;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            MapProcessor.getInstance().enqueueCaveLoad(layerY, rx, rz, 1);
            return null;
        }

        String key = key(layerY, rx, rz);
        markVisible(key);
        CaveRegion region = manager.getRegion(rx, rz, false);
        if (region == null) {
            if (manager.hasRegionFile(rx, rz)
                    || VerticalCaveArchiveManager.getInstance().hasRegionData(rx, rz)) {
                MapProcessor.getInstance().enqueueCaveLoad(layerY, rx, rz, distancePriority(rx, rz));
            }
            return null;
        }
        if (!region.isLoaded()) return null;

        TextureInfo info;
        synchronized (textures) {
            info = textures.get(key);
            if (info == null) {
                info = createTexture(layerY, rx, rz, manager.getLayerGeneration());
                textures.put(key, info);
                trimCache();
                schedulePreparation(key, info, region);
            }
        }
        return info.initialized ? info.location : null;
    }


    /** Returns only an already uploaded cave tile; performs no IO or CPU rebuild. */
    public ResourceLocation peekRegionTexture(int layerY, int rx, int rz) {
        if (activeTextureLayerY != layerY) return null;
        String key = key(layerY, rx, rz);
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
            String[] parts = key.split(",");
            int layerY = Integer.parseInt(parts[0]);
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);
            CaveMapManager manager = CaveMapManager.getInstance();
            if (manager.getActiveLayerY() != layerY) continue;
            CaveRegion region = manager.getRegion(rx, rz, false);
            if (region == null || !region.isLoaded()) {
                MapProcessor.getInstance().enqueueCaveLoad(layerY, rx, rz, distancePriority(rx, rz));
                requeue(key);
                continue;
            }
            TextureInfo info;
            synchronized (textures) {
                info = textures.get(key);
                if (info == null) {
                    info = createTexture(layerY, rx, rz, manager.getLayerGeneration());
                    textures.put(key, info);
                    trimCache();
                }
            }
            schedulePreparation(key, info, region);
        }
    }

    private void schedulePreparation(String key, TextureInfo info, CaveRegion region) {
        CaveMapManager manager = CaveMapManager.getInstance();
        if (!manager.isLayerGenerationCurrent(info.generation, info.layerY)) return;
        if (info.pending != null) {
            if (!info.pending.isDone()) return;
            try {
                MapTextureBuildWorker.PreparedSingle prepared = info.pending.join();
                if (manager.isLayerGenerationCurrent(info.generation, info.layerY)) {
                    // Publish the newest complete snapshot even when scanning changed
                    // the region while this CPU build was running. Rejecting every
                    // slightly stale build starved a continuously scanned 512x512 cave
                    // region, so the user only saw an old sparse texture for seconds.
                    applyPrepared(info, prepared);

                    synchronized (dirtyTextures) {
                        long currentRevision = revisions.getOrDefault(key, 0L);
                        if (currentRevision > prepared.revision()) {
                            // Newer columns arrived after the snapshot. Keep the region
                            // queued so the next coherent snapshot follows immediately.
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

        long revision;
        synchronized (dirtyTextures) {
            revision = revisions.getOrDefault(key, 0L);
        }
        // Layer pixels are already shaded against their own selected-Y heights
        // during live scan/archive projection. Full-cave heights belong to a
        // different projection and must not influence this texture.
        CompletableFuture<MapTextureBuildWorker.PreparedSingle> future =
                MapTextureBuildWorker.tryBuildCave(region.snapshotPixels(), null,
                        0, MapConfig.mapColorProfile, revision,
                        () -> manager.isLayerGenerationCurrent(info.generation, info.layerY));
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

    private TextureInfo createTexture(int layerY, int rx, int rz, long generation) {
        DynamicTexture texture = new DynamicTexture(512, 512, false);
        texture.setFilter(false, false);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("simplemap",
                "caves/y_" + layerY + "/r_" + rx + '_' + rz);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return new TextureInfo(texture, location, generation, layerY, rx, rz);
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
            // Clear the access-order map before cancelling futures. Cancellation
            // runs completion callbacks synchronously and those callbacks call
            // textures.get(...), which otherwise mutates LinkedHashMap mid-iteration.
            closeNow.addAll(textures.values());
            closeNow.addAll(deferredCloses);
            textures.clear();
            deferredCloses.clear();
            renderBatchDepth = 0;
            activeTextureLayerY = Integer.MIN_VALUE;
        }
        for (TextureInfo info : closeNow) info.close();
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
                    if (dirtyTextures.contains(key) && isReadyForPublication(key, now, force)) {
                        result.add(key);
                    }
                    if (result.size() >= budget) break;
                }
            }
            if (result.size() < budget) {
                for (String key : dirtyTextures) {
                    if (!result.contains(key) && isReadyForPublication(key, now, force)) {
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

    private static String key(int layerY, int rx, int rz) {
        return layerY + "," + rx + "," + rz;
    }

    private static final class TextureInfo {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final long generation;
        private final int layerY;
        private final int rx;
        private final int rz;
        private CompletableFuture<MapTextureBuildWorker.PreparedSingle> pending;
        private long uploadedRevision = Long.MIN_VALUE;
        private boolean initialized;

        private TextureInfo(DynamicTexture texture, ResourceLocation location,
                long generation, int layerY, int rx, int rz) {
            this.texture = texture;
            this.location = location;
            this.generation = generation;
            this.layerY = layerY;
            this.rx = rx;
            this.rz = rz;
        }

        private void close() {
            if (pending != null) pending.cancel(false);
            Minecraft.getInstance().getTextureManager().release(location);
        }
    }
}
