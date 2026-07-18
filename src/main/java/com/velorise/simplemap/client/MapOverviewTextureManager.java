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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Low-detail parent textures for surface/full-cave overview rendering.
 *
 * Keys include the viewed dimension so switching dimensions can retain a small
 * warm GPU cache without ever drawing a tile from another dimension. Layered
 * cave rendering intentionally does not use these parents because downsampling
 * destroys thin cave passages; it streams exact textures instead.
 */
public final class MapOverviewTextureManager {
    public static final int MODE_SURFACE = 0;
    public static final int MODE_LAYER = 1;
    public static final int MODE_FULL = 2;

    private static final MapOverviewTextureManager INSTANCE = new MapOverviewTextureManager();
    private static final int MAX_TEXTURES = 256;

    private final Map<Key, TextureInfo> textures = new LinkedHashMap<>(32, 0.75f, true);
    private final Set<Key> dirty = new LinkedHashSet<>();
    private final Map<Key, Long> revisions = new HashMap<>();
    private final List<TextureInfo> deferredCloses = new ArrayList<>();
    private int renderBatchDepth;
    private long lastUploadMs;

    private MapOverviewTextureManager() {
    }

    public static MapOverviewTextureManager getInstance() {
        return INSTANCE;
    }

    public ResourceLocation getSurface(int rx, int rz, int stride, boolean cachedOnly) {
        return get(new Key(currentDimension(), MODE_SURFACE, 0, rx, rz,
                normalizeStride(stride)), cachedOnly);
    }

    public ResourceLocation getLayer(int layerY, int rx, int rz, int stride, boolean cachedOnly) {
        return get(new Key(currentDimension(), MODE_LAYER, layerY, rx, rz,
                normalizeStride(stride)), cachedOnly);
    }

    public ResourceLocation getFull(int rx, int rz, int stride, boolean cachedOnly) {
        return get(new Key(currentDimension(), MODE_FULL, 0, rx, rz,
                normalizeStride(stride)), cachedOnly);
    }

    /** Queues compact parent textures from client tick, never from rendering. */
    public void requestVisible(int mode, int layerY, double minX, double maxX,
            double minZ, double maxZ, int stride) {
        // Exact layered cave tiles are intentionally preserved at every zoom.
        if (mode == MODE_LAYER) return;
        int safeStride = normalizeStride(stride);
        String dimension = currentDimension();
        int minRx = (int) Math.floor(minX - 1.0) >> 9;
        int maxRx = (int) Math.floor(maxX + 1.0) >> 9;
        int minRz = (int) Math.floor(minZ - 1.0) >> 9;
        int maxRz = (int) Math.floor(maxZ + 1.0) >> 9;
        for (int rz = minRz; rz <= maxRz; rz++) {
            for (int rx = minRx; rx <= maxRx; rx++) {
                Key key = new Key(dimension, mode, 0, rx, rz, safeStride);
                if (ensureSource(key)) requestDirty(key);
            }
        }
    }

    private static int normalizeStride(int stride) {
        return stride >= 8 ? 8 : 4;
    }

    private ResourceLocation get(Key key, boolean cachedOnly) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return null;
        synchronized (textures) {
            TextureInfo existing = textures.get(key);
            if (existing != null) {
                refreshGeneration(existing);
                return existing.initialized ? existing.location : null;
            }
            if (cachedOnly) return null;
        }
        if (!sourceLoaded(key)) return null;
        requestDirty(key);
        return null;
    }

    private TextureInfo createTextureInfo(Key key) {
        Minecraft minecraft = Minecraft.getInstance();
        int size = 512 / key.stride;
        DynamicTexture texture = new DynamicTexture(size, size, false);
        texture.setFilter(false, false);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("simplemap",
                "overview/" + texturePath(key.dimension) + "/m" + key.mode
                        + "_y" + key.layerY + "_s" + key.stride
                        + "_" + key.rx + '_' + key.rz);
        minecraft.getTextureManager().register(location, texture);
        return new TextureInfo(texture, location, key, generationFor(key));
    }

    private boolean sourceLoaded(Key key) {
        if (!isCurrentDimension(key)) return false;
        return switch (key.mode) {
            case MODE_SURFACE -> {
                MapManager.Region region = MapManager.getInstance().getRegion(key.rx, key.rz, false);
                yield region != null && region.isLoaded();
            }
            case MODE_LAYER -> {
                CaveMapManager manager = CaveMapManager.getInstance();
                CaveRegion region = manager.getActiveLayerY() == key.layerY
                        ? manager.getRegion(key.rx, key.rz, false) : null;
                yield region != null && region.isLoaded();
            }
            case MODE_FULL -> {
                FullCaveMapManager.FullRegion region = FullCaveMapManager.getInstance()
                        .getRegion(key.rx, key.rz, false);
                yield region != null && region.isLoaded();
            }
            default -> false;
        };
    }

    private boolean ensureSource(Key key) {
        if (!isCurrentDimension(key)) return false;
        return switch (key.mode) {
            case MODE_SURFACE -> {
                MapManager manager = MapManager.getInstance();
                MapManager.Region region = manager.getRegion(key.rx, key.rz, false);
                if (region == null && manager.hasRegionFile(key.rx, key.rz)) {
                    MapProcessor.getInstance().enqueueSurfaceLoad(key.rx, key.rz,
                            priority(key.rx, key.rz));
                }
                yield region != null && region.isLoaded();
            }
            case MODE_LAYER -> {
                CaveMapManager manager = CaveMapManager.getInstance();
                if (manager.getActiveLayerY() != key.layerY) yield false;
                CaveRegion region = manager.getRegion(key.rx, key.rz, false);
                if (region == null && (manager.hasRegionFile(key.rx, key.rz)
                        || VerticalCaveArchiveManager.getInstance().hasRegionData(key.rx, key.rz))) {
                    MapProcessor.getInstance().enqueueCaveLoad(key.layerY, key.rx, key.rz,
                            priority(key.rx, key.rz));
                }
                yield region != null && region.isLoaded();
            }
            case MODE_FULL -> {
                FullCaveMapManager manager = FullCaveMapManager.getInstance();
                FullCaveMapManager.FullRegion region = manager.getRegion(key.rx, key.rz, false);
                if (region == null && manager.hasRegionFile(key.rx, key.rz)) {
                    MapProcessor.getInstance().enqueueFullCaveLoad(key.rx, key.rz,
                            priority(key.rx, key.rz));
                }
                yield region != null && region.isLoaded();
            }
            default -> false;
        };
    }

    private long generationFor(Key key) {
        return switch (key.mode) {
            case MODE_SURFACE -> MapManager.getInstance().getGeneration();
            case MODE_LAYER -> CaveMapManager.getInstance().getLayerGeneration();
            case MODE_FULL -> FullCaveMapManager.getInstance().getGeneration();
            default -> 0L;
        };
    }

    private boolean generationCurrent(TextureInfo info) {
        if (!isCurrentDimension(info.key)) return false;
        return switch (info.key.mode) {
            case MODE_SURFACE -> MapManager.getInstance().isGenerationCurrent(info.generation);
            case MODE_LAYER -> CaveMapManager.getInstance().isLayerGenerationCurrent(
                    info.generation, info.key.layerY);
            case MODE_FULL -> FullCaveMapManager.getInstance().isGenerationCurrent(info.generation);
            default -> false;
        };
    }

    private void refreshGeneration(TextureInfo info) {
        if (!isCurrentDimension(info.key)) return;
        long generation = generationFor(info.key);
        if (info.generation == generation) return;
        if (info.pending != null) info.pending.cancel(false);
        info.pending = null;
        info.generation = generation;
        markDirty(info.key);
    }

    public void markSurfaceRegionDirty(int rx, int rz) {
        markModeRegionDirty(MODE_SURFACE, 0, rx, rz);
    }

    public void markLayerRegionDirty(int layerY, int rx, int rz) {
        markModeRegionDirty(MODE_LAYER, layerY, rx, rz);
    }

    public void markFullRegionDirty(int rx, int rz) {
        markModeRegionDirty(MODE_FULL, 0, rx, rz);
    }

    private void markModeRegionDirty(int mode, int layerY, int rx, int rz) {
        String dimension = currentDimension();
        synchronized (textures) {
            for (Key key : textures.keySet()) {
                if (key.dimension.equals(dimension) && key.mode == mode
                        && key.layerY == layerY && key.rx == rx && key.rz == rz) {
                    markDirty(key);
                }
            }
        }
    }

    private void requestDirty(Key key) {
        synchronized (dirty) {
            if (dirty.contains(key)) return;
        }
        markDirty(key);
    }

    private void markDirty(Key key) {
        synchronized (dirty) {
            revisions.merge(key, 1L, Long::sum);
            dirty.add(key);
        }
    }

    public void uploadDirtyTextures(boolean focus) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> uploadDirtyTextures(focus));
            return;
        }
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        long now = System.currentTimeMillis();
        if (!focus && !governor.isInteracting() && now - lastUploadMs < 60L) return;
        lastUploadMs = now;
        int budget = (focus || governor.isInteracting()) ? 4 : 2;
        long deadline = System.nanoTime() + Math.max(500_000L,
                governor.textureUploadBudgetNanos(focus) / 2);
        List<Key> selected = selectDirty(budget);
        for (Key key : selected) {
            if (System.nanoTime() >= deadline) {
                markDirty(key);
                continue;
            }
            if (!ensureSource(key)) {
                markDirty(key);
                continue;
            }
            TextureInfo info;
            synchronized (textures) {
                info = textures.get(key);
                if (info == null) {
                    info = createTextureInfo(key);
                    textures.put(key, info);
                    trim();
                } else {
                    refreshGeneration(info);
                }
            }
            schedule(info);
        }
    }

    private List<Key> selectDirty(int budget) {
        String dimension = currentDimension();
        List<Key> selected = new ArrayList<>(budget);
        synchronized (dirty) {
            for (Key key : dirty) {
                if (key.dimension.equals(dimension)) selected.add(key);
                if (selected.size() >= budget) break;
            }
            dirty.removeAll(selected);
        }
        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        selected.sort(java.util.Comparator.comparingDouble(key ->
                governor.focusDistanceSquared(key.rx * 512.0 + 256.0,
                        key.rz * 512.0 + 256.0)));
        return selected;
    }

    private void schedule(TextureInfo info) {
        Key key = info.key;
        if (!generationCurrent(info)) return;
        if (info.pending != null) {
            if (!info.pending.isDone()) return;
            try {
                MapTextureBuildWorker.PreparedSingle prepared = info.pending.join();
                if (generationCurrent(info)) {
                    apply(info, prepared);
                    synchronized (dirty) {
                        if (revisions.getOrDefault(key, 0L) > prepared.revision()) dirty.add(key);
                    }
                }
            } catch (RuntimeException ignored) {
                markDirty(key);
            } finally {
                info.pending = null;
            }
            return;
        }

        long revision;
        synchronized (dirty) {
            revision = revisions.getOrDefault(key, 0L);
        }
        CompletableFuture<MapTextureBuildWorker.PreparedSingle> future = switch (key.mode) {
            case MODE_SURFACE -> buildSurface(info, revision);
            case MODE_LAYER -> buildLayer(info, revision);
            case MODE_FULL -> buildFull(info, revision);
            default -> null;
        };
        if (future == null) {
            markDirty(key);
            return;
        }
        info.pending = future;
        future.whenComplete((ignored, throwable) -> markDirty(key));
    }

    private CompletableFuture<MapTextureBuildWorker.PreparedSingle> buildSurface(
            TextureInfo info, long revision) {
        Key key = info.key;
        MapManager.Region region = MapManager.getInstance().getRegion(key.rx, key.rz, false);
        if (region == null || !region.isLoaded()) return null;
        MapBlockData[] pixels = region.snapshotPixels();
        int outputSize = 512 / key.stride;
        int stride = key.stride;
        int[] colors = new int[outputSize * outputSize];
        for (int z = 0; z < outputSize; z++) {
            for (int x = 0; x < outputSize; x++) {
                MapBlockData data = pixels[z * stride * 512 + x * stride];
                colors[z * outputSize + x] = data != null && !data.isEmpty()
                        ? 0xFF7A7A7A : 0xFF000000;
            }
        }
        return CompletableFuture.completedFuture(
                new MapTextureBuildWorker.PreparedSingle(colors, revision));
    }

    private CompletableFuture<MapTextureBuildWorker.PreparedSingle> buildLayer(
            TextureInfo info, long revision) {
        Key key = info.key;
        CaveRegion region = CaveMapManager.getInstance().getRegion(key.rx, key.rz, false);
        if (region == null || !region.isLoaded()) return null;
        return downsample(region.snapshotPixels(), key.stride, revision);
    }

    private CompletableFuture<MapTextureBuildWorker.PreparedSingle> buildFull(
            TextureInfo info, long revision) {
        Key key = info.key;
        FullCaveMapManager.FullRegion region = FullCaveMapManager.getInstance()
                .getRegion(key.rx, key.rz, false);
        if (region == null || !region.isLoaded()) return null;
        return downsample(region.snapshotPixels(), key.stride, revision);
    }

    private CompletableFuture<MapTextureBuildWorker.PreparedSingle> downsample(
            int[] source, int stride, long revision) {
        int outputSize = 512 / stride;
        int[] colors = new int[outputSize * outputSize];
        for (int z = 0; z < outputSize; z++) {
            for (int x = 0; x < outputSize; x++) {
                colors[z * outputSize + x] = source[z * stride * 512 + x * stride];
            }
        }
        return CompletableFuture.completedFuture(
                new MapTextureBuildWorker.PreparedSingle(colors, revision));
    }

    private void apply(TextureInfo info, MapTextureBuildWorker.PreparedSingle prepared) {
        NativeImage image = info.texture.getPixels();
        if (image == null) return;
        int size = 512 / info.key.stride;
        for (int z = 0; z < size; z++) {
            int row = z * size;
            for (int x = 0; x < size; x++) {
                image.setPixelRGBA(x, z, prepared.styled()[row + x]);
            }
        }
        info.texture.upload();
        info.texture.setFilter(false, false);
        info.initialized = true;
    }

    public void beginRenderBatch() {
        synchronized (textures) {
            renderBatchDepth++;
        }
    }

    public void endRenderBatch() {
        List<TextureInfo> close = null;
        synchronized (textures) {
            if (renderBatchDepth > 0) renderBatchDepth--;
            if (renderBatchDepth == 0 && !deferredCloses.isEmpty()) {
                close = new ArrayList<>(deferredCloses);
                deferredCloses.clear();
            }
        }
        if (close != null) for (TextureInfo info : close) info.close();
    }

    public void invalidateStyle() {
        String dimension = currentDimension();
        synchronized (textures) {
            for (Key key : textures.keySet()) {
                if (key.dimension.equals(dimension)) markDirty(key);
            }
        }
    }

    public void clearCache() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::clearCache);
            return;
        }
        List<TextureInfo> close = new ArrayList<>();
        synchronized (textures) {
            close.addAll(textures.values());
            close.addAll(deferredCloses);
            textures.clear();
            deferredCloses.clear();
            renderBatchDepth = 0;
        }
        for (TextureInfo info : close) info.close();
        synchronized (dirty) {
            dirty.clear();
            revisions.clear();
        }
    }

    private void trim() {
        List<TextureInfo> retired = new ArrayList<>();
        while (textures.size() > MAX_TEXTURES) {
            var iterator = textures.entrySet().iterator();
            if (!iterator.hasNext()) break;
            retired.add(iterator.next().getValue());
            iterator.remove();
        }
        for (TextureInfo info : retired) {
            if (renderBatchDepth > 0) deferredCloses.add(info);
            else info.close();
        }
    }

    private int priority(int rx, int rz) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 1;
        int prx = player.blockPosition().getX() >> 9;
        int prz = player.blockPosition().getZ() >> 9;
        return Math.max(1, 20_000 - (Math.abs(rx - prx) + Math.abs(rz - prz)) * 100);
    }

    private static String currentDimension() {
        return MapManager.getInstance().getDimensionCacheKey();
    }

    private static boolean isCurrentDimension(Key key) {
        return key.dimension.equals(currentDimension());
    }

    private static String texturePath(String dimension) {
        return dimension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    private record Key(String dimension, int mode, int layerY,
            int rx, int rz, int stride) {
    }

    private static final class TextureInfo {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final Key key;
        private long generation;
        private CompletableFuture<MapTextureBuildWorker.PreparedSingle> pending;
        private boolean initialized;

        private TextureInfo(DynamicTexture texture, ResourceLocation location,
                Key key, long generation) {
            this.texture = texture;
            this.location = location;
            this.key = key;
            this.generation = generation;
        }

        private void close() {
            if (pending != null) pending.cancel(false);
            Minecraft.getInstance().getTextureManager().release(location);
        }
    }
}
