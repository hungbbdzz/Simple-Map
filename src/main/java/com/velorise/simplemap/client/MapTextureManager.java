package com.velorise.simplemap.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.MapColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/** Render-thread-owned GPU cache for surface region textures. */
public class MapTextureManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapTextureManager INSTANCE = new MapTextureManager();
    private static final int MAX_TEXTURE_REGIONS = 128;
    private static final int MAX_VISIBLE_HISTORY = MAX_TEXTURE_REGIONS * 4;
    private static final long VISIBLE_TTL_MS = 2_000L;
    /** Wait for a brief quiet period before rebuilding a 512x512 region. */
    private static final long DIRTY_QUIET_NANOS = 60_000_000L;
    /** Publish progressive snapshots frequently while a region is still scanning. */
    private static final long DIRTY_MAX_WAIT_NANOS = 350_000_000L;

    private final Map<String, RegionTextureInfo> textureCache = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyTextures = new LinkedHashSet<>();
    private final Map<String, Long> visibleTextures = new LinkedHashMap<>();
    private final Map<String, Long> revisions = new HashMap<>();
    private final Map<String, Long> firstDirtyNanos = new HashMap<>();
    private final Map<String, Long> lastDirtyNanos = new HashMap<>();
    private final List<RegionTextureInfo> deferredCloses = new ArrayList<>();
    private int renderBatchDepth;
    private final Map<String, Integer> blockColorsCache = new ConcurrentHashMap<>();
    private final Map<String, Integer> vanillaBlockColorsCache = new ConcurrentHashMap<>();
    private final Map<String, BlockTintPolicy> tintPolicyCache = new ConcurrentHashMap<>();
    private long lastUploadTime;

    private MapTextureManager() {
    }

    public static MapTextureManager getInstance() {
        return INSTANCE;
    }

    public void registerBlockColor(String blockId, int argb) {
        blockColorsCache.put(blockId, argb);
        vanillaBlockColorsCache.put(blockId, argb);
    }

    public Integer getBlockColor(String blockId) {
        return (MapConfig.blockColourMode == 1 ? vanillaBlockColorsCache : blockColorsCache).get(blockId);
    }

    /** Must resolve uncached texture resources on the client thread. */
    public int resolveBlockColor(String blockId) {
        return resolveBlockColor(blockId, MapConfig.blockColourMode);
    }

    public BlockTintPolicy resolveTintPolicy(String blockIdText) {
        BlockTintPolicy cached = tintPolicyCache.get(blockIdText);
        if (cached != null) return cached;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return BlockTintPolicy.NONE;
        BlockTintPolicy resolved = BlockTintPolicy.NONE;
        try {
            ResourceLocation blockId = ResourceLocation.parse(blockIdText);
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockId);
            var state = block.defaultBlockState();
            String path = blockId.getPath();
            if (BlockTextureColorSampler.modelUsesTint(blockId)) {
                boolean leaves = state.is(net.minecraft.tags.BlockTags.LEAVES)
                        || path.endsWith("_leaves") || path.contains("foliage");
                if (leaves || path.contains("vine")) resolved = BlockTintPolicy.FOLIAGE;
                else if (path.contains("grass") || path.contains("fern")) resolved = BlockTintPolicy.GRASS;
            }
        } catch (Throwable ignored) {
        }
        tintPolicyCache.put(blockIdText, resolved);
        return resolved;
    }

    public int resolveBlockColor(String blockId, int colourMode) {
        Map<String, Integer> cache = colourMode == 1 ? vanillaBlockColorsCache : blockColorsCache;
        Integer override = MapConfig.blockColorOverrides.get(blockId);
        if (override != null) {
            int opaque = 0xFF000000 | (override & 0x00FFFFFF);
            cache.put(blockId, opaque);
            return opaque;
        }
        Integer cached = cache.get(blockId);
        if (cached != null) return cached;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return 0xFFFFFFFF;
        int resolved = resolveDefaultBlockColor(blockId, colourMode);
        cache.put(blockId, resolved);
        return resolved;
    }

    /** Resolves the automatic color while deliberately ignoring a saved override. */
    public int resolveAutomaticBlockColor(String blockId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return 0xFFFFFFFF;
        return resolveDefaultBlockColor(blockId, MapConfig.blockColourMode);
    }

    /** Invalidates one block's derived color, then rebuilds every cached surface tile. */
    public void invalidateBlockColor(String blockId) {
        if (blockId != null) {
            blockColorsCache.remove(blockId);
            vanillaBlockColorsCache.remove(blockId);
            tintPolicyCache.remove(blockId);
            try {
                BlockTextureColorSampler.invalidate(ResourceLocation.parse(blockId));
            } catch (RuntimeException ignored) {
                BlockTextureColorSampler.clearCache();
            }
        }
        invalidateStyle();
    }

    public void markRegionDirty(int regionX, int regionZ) {
        String key = key(regionX, regionZ);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            // Every changed column advances the revision. Prepared images are complete
            // immutable region snapshots; a slightly older snapshot may still be
            // published, then immediately followed by the newest queued revision.
            revisions.merge(key, 1L, Long::sum);
            firstDirtyNanos.putIfAbsent(key, now);
            lastDirtyNanos.put(key, now);
            dirtyTextures.add(key);
        }
    }

    public ResourceLocation getRegionTexture(int regionX, int regionZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            MapProcessor.getInstance().enqueueSurfaceLoad(regionX, regionZ, 1);
            return null;
        }

        String key = key(regionX, regionZ);
        markRegionVisible(key);
        MapManager manager = MapManager.getInstance();
        MapManager.Region region = manager.getRegion(regionX, regionZ, false);
        if (region == null) {
            if (manager.hasRegionFile(regionX, regionZ)) {
                int priority = distancePriority(regionX, regionZ);
                MapProcessor.getInstance().enqueueSurfaceLoad(regionX, regionZ, priority);
            }
            return null;
        }
        if (!region.isLoaded()) return null;

        RegionTextureInfo info;
        synchronized (textureCache) {
            info = textureCache.get(key);
            if (info == null) {
                info = createTextureInfo(regionX, regionZ, manager.getGeneration());
                textureCache.put(key, info);
                trimTextureCache();
                schedulePreparation(key, info, region, regionX, regionZ);
            }
        }
        return info.initialized ? info.location : null;
    }

    public ResourceLocation getGlowRegionTexture(int regionX, int regionZ) {
        getRegionTexture(regionX, regionZ);
        synchronized (textureCache) {
            RegionTextureInfo info = textureCache.get(key(regionX, regionZ));
            return info != null && info.initialized ? info.glowLocation : null;
        }
    }


    /** Returns only an already uploaded GPU tile; never loads, rebuilds or allocates. */
    public ResourceLocation peekRegionTexture(int regionX, int regionZ) {
        String key = key(regionX, regionZ);
        synchronized (textureCache) {
            RegionTextureInfo info = textureCache.get(key);
            if (info == null || !info.initialized) return null;
            markRegionVisible(key);
            return info.location;
        }
    }

    /** Cached-only counterpart for the emissive overlay. */
    public ResourceLocation peekGlowRegionTexture(int regionX, int regionZ) {
        String key = key(regionX, regionZ);
        synchronized (textureCache) {
            RegionTextureInfo info = textureCache.get(key);
            if (info == null || !info.initialized) return null;
            markRegionVisible(key);
            return info.glowLocation;
        }
    }


    public void beginRenderBatch() {
        synchronized (textureCache) {
            renderBatchDepth++;
        }
    }

    public void endRenderBatch() {
        List<RegionTextureInfo> closeNow = null;
        synchronized (textureCache) {
            if (renderBatchDepth > 0) renderBatchDepth--;
            if (renderBatchDepth == 0 && !deferredCloses.isEmpty()) {
                closeNow = new ArrayList<>(deferredCloses);
                deferredCloses.clear();
            }
        }
        if (closeNow != null) for (RegionTextureInfo info : closeNow) info.close();
    }

    public void uploadDirtyTextures() {
        uploadDirtyTextures(false);
    }

    /** Performs CPU result publication and DynamicTexture upload on the client thread only. */
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

        int countBudget = force ? 6 : 2;
        long deadline = System.nanoTime() + (force ? 8_000_000L : 4_000_000L);
        List<String> work = selectDirty(countBudget, force);
        for (String key : work) {
            if (System.nanoTime() > deadline && !force) {
                requeue(key);
                continue;
            }
            int comma = key.indexOf(',');
            int regionX = Integer.parseInt(key.substring(0, comma));
            int regionZ = Integer.parseInt(key.substring(comma + 1));
            MapManager.Region region = MapManager.getInstance().getRegion(regionX, regionZ, false);
            if (region == null || !region.isLoaded()) {
                MapProcessor.getInstance().enqueueSurfaceLoad(regionX, regionZ, distancePriority(regionX, regionZ));
                requeue(key);
                continue;
            }

            RegionTextureInfo info;
            synchronized (textureCache) {
                info = textureCache.get(key);
                if (info == null) {
                    info = createTextureInfo(regionX, regionZ, MapManager.getInstance().getGeneration());
                    textureCache.put(key, info);
                    trimTextureCache();
                }
            }
            schedulePreparation(key, info, region, regionX, regionZ);
        }
    }

    private List<String> selectDirty(int budget, boolean force) {
        List<String> selected = new ArrayList<>(budget);
        long now = System.nanoTime();
        synchronized (dirtyTextures) {
            if (dirtyTextures.isEmpty()) return selected;
            synchronized (visibleTextures) {
                for (String key : visibleTextures.keySet()) {
                    if (dirtyTextures.contains(key) && isReadyForPublication(key, now, force)) {
                        selected.add(key);
                        if (selected.size() >= budget) break;
                    }
                }
            }
            if (selected.size() < budget) {
                for (String key : dirtyTextures) {
                    if (!selected.contains(key) && isReadyForPublication(key, now, force)) {
                        selected.add(key);
                    }
                    if (selected.size() >= budget) break;
                }
            }
            dirtyTextures.removeAll(selected);
        }
        return selected;
    }

    private boolean isReadyForPublication(String key, long now, boolean force) {
        if (force) return true;
        long first = firstDirtyNanos.getOrDefault(key, now);
        long last = lastDirtyNanos.getOrDefault(key, first);
        return now - last >= DIRTY_QUIET_NANOS || now - first >= DIRTY_MAX_WAIT_NANOS;
    }

    private void schedulePreparation(String key, RegionTextureInfo info,
            MapManager.Region region, int regionX, int regionZ) {
        if (!MapManager.getInstance().isGenerationCurrent(info.generation)) return;
        if (info.pending != null) {
            if (!info.pending.isDone()) return;
            try {
                MapTextureBuildWorker.PreparedPair prepared = info.pending.join();
                if (MapManager.getInstance().isGenerationCurrent(info.generation)) {
                    // The worker always builds from one immutable 512x512 snapshot, so
                    // the result is internally coherent even if newer columns arrived
                    // while it was being colorized. Publishing that complete snapshot
                    // prevents a continuously scanned region from remaining black.
                    applyPrepared(info, prepared);

                    synchronized (dirtyTextures) {
                        long currentRevision = revisions.getOrDefault(key, 0L);
                        if (currentRevision > prepared.revision()) {
                            // A newer coherent snapshot is required; keep it queued.
                            dirtyTextures.add(key);
                        } else {
                            dirtyTextures.remove(key);
                            firstDirtyNanos.remove(key);
                            lastDirtyNanos.remove(key);
                        }
                    }
                }
            } catch (RuntimeException exception) {
                LOGGER.debug("Discarded failed/stale surface texture job {}", key, exception);
                requeue(key);
            } finally {
                info.pending = null;
            }
            return;
        }

        long[] pixels = region.snapshotPackedPixels();
        int[] tints = region.snapshotTints();
        List<String> biomePalette = region.snapshotBiomePalette();
        List<String> blockPalette = region.snapshotBlockPalette();
        long revision;
        synchronized (dirtyTextures) {
            revision = revisions.getOrDefault(key, 0L);
        }

        var level = Minecraft.getInstance().level;
        Registry<Biome> biomeRegistry = level == null ? null
                : level.registryAccess().registryOrThrow(Registries.BIOME);
        Biome[] resolvedBiomes = new Biome[biomePalette.size()];
        for (int i = 0; i < biomePalette.size(); i++) {
            try {
                if (biomeRegistry != null) {
                    resolvedBiomes[i] = biomeRegistry.get(ResourceLocation.parse(biomePalette.get(i)));
                }
            } catch (RuntimeException ignored) {
            }
        }
        IntFunction<Biome> biomeLookup = index -> index >= 0 && index < resolvedBiomes.length
                ? resolvedBiomes[index] : null;

        Map<String, Integer> selectedColorCache = MapConfig.blockColourMode == 1
                ? vanillaBlockColorsCache : blockColorsCache;
        for (String blockId : blockPalette) {
            resolveBlockColor(blockId, MapConfig.blockColourMode);
            if (MapConfig.blockColourMode == 0) resolveTintPolicy(blockId);
        }
        Map<String, Integer> blockColors = new HashMap<>(selectedColorCache);
        Map<String, BlockTintPolicy> tintPolicies = new HashMap<>(tintPolicyCache);
        Set<String> tintDisabledBlocks = Set.copyOf(MapConfig.blockColorOverrides.keySet());

        byte[] light = null;
        MapLightManager.LightRegion lightRegion = MapLightManager.getInstance().getRegion(
                regionX, regionZ, MapConfig.minimapNightMode != 0);
        if (lightRegion != null) {
            light = new byte[512 * 512];
            lightRegion.lock();
            try {
                System.arraycopy(lightRegion.getLevelsDirect(), 0, light, 0, light.length);
            } finally {
                lightRegion.unlock();
            }
        }

        CompletableFuture<MapTextureBuildWorker.PreparedPair> future =
                MapTextureBuildWorker.tryBuildSurface(pixels, tints, biomePalette, blockPalette,
                        biomeLookup, blockColors, tintPolicies, tintDisabledBlocks, MapConfig.blockColourMode,
                        MapConfig.displayFlowers, MapConfig.terrainSlopes, light,
                        MapConfig.mapColorProfile, revision,
                        () -> MapManager.getInstance().isGenerationCurrent(info.generation));
        if (future == null) {
            requeue(key);
            return;
        }
        info.pending = future;
        future.whenComplete((ignored, throwable) -> {
            synchronized (textureCache) {
                if (textureCache.get(key) != info) return;
            }
            requeue(key);
        });
    }

    private RegionTextureInfo createTextureInfo(int regionX, int regionZ, long generation) {
        DynamicTexture texture = new DynamicTexture(512, 512, false);
        texture.setFilter(false, false);
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("simplemap",
                "regions/r_" + regionX + '_' + regionZ);
        Minecraft.getInstance().getTextureManager().register(location, texture);

        DynamicTexture glowTexture = new DynamicTexture(512, 512, false);
        glowTexture.setFilter(false, false);
        ResourceLocation glowLocation = ResourceLocation.fromNamespaceAndPath("simplemap",
                "regions/glow_" + regionX + '_' + regionZ);
        Minecraft.getInstance().getTextureManager().register(glowLocation, glowTexture);
        return new RegionTextureInfo(texture, location, glowTexture, glowLocation, generation);
    }

    private void applyPrepared(RegionTextureInfo info, MapTextureBuildWorker.PreparedPair prepared) {
        NativeImage image = info.texture.getPixels();
        NativeImage glowImage = info.glowTexture.getPixels();
        if (image == null || glowImage == null) return;
        int[] styled = prepared.styled();
        int[] glow = prepared.glow();
        for (int z = 0; z < 512; z++) {
            int row = z * 512;
            for (int x = 0; x < 512; x++) {
                image.setPixelRGBA(x, z, styled[row + x]);
                glowImage.setPixelRGBA(x, z, glow[row + x]);
            }
        }
        info.texture.upload();
        info.glowTexture.upload();
        info.texture.setFilter(false, false);
        info.glowTexture.setFilter(false, false);
        info.uploadedRevision = prepared.revision();
        info.initialized = true;
    }

    private int resolveDefaultBlockColor(String blockIdText, int colourMode) {
        try {
            ResourceLocation blockId = ResourceLocation.parse(blockIdText);
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(blockId);
            if (block == null) return 0xFFFFFFFF;
            var state = block.defaultBlockState();
            var minecraft = Minecraft.getInstance();
            var level = minecraft.level;
            BlockPos position = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();

            // VANILLA intentionally uses the same small MapColor palette as a
            // vanilla filled map. ACCURATE resolves the actual block model texture.
            if (colourMode == 1) {
                try {
                    MapColor mapColor = state.getMapColor(level, position);
                    return 0xFF000000 | (mapColor.col & 0x00FFFFFF);
                } catch (Throwable ignored) {
                    return 0xFFFFFFFF;
                }
            }

            Integer sampled = BlockTextureColorSampler.sampleArgb(blockId);
            if (sampled != null) return sampled;

            int rgb = -1;
            if (level != null && !BrokenBlockTintCache.getInstance().isBroken(blockIdText)) {
                try {
                    rgb = minecraft.getBlockColors().getColor(state, level, position, 0);
                } catch (Throwable throwable) {
                    BrokenBlockTintCache.getInstance().markBroken(blockIdText);
                }
            }
            if (rgb == -1) {
                try {
                    MapColor mapColor = state.getMapColor(level, position);
                    rgb = mapColor.col;
                } catch (Throwable ignored) {
                    rgb = 0xFFFFFF;
                }
            }
            return 0xFF000000 | (rgb & 0x00FFFFFF);
        } catch (RuntimeException exception) {
            return 0xFFFFFFFF;
        }
    }

    public void clearDerivedColorCaches() {
        blockColorsCache.clear();
        vanillaBlockColorsCache.clear();
        tintPolicyCache.clear();
        BlockTextureColorSampler.clearCache();
    }

    public void invalidateStyle() {
        synchronized (textureCache) {
            synchronized (dirtyTextures) {
                long now = System.nanoTime();
                for (String key : textureCache.keySet()) {
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
        List<RegionTextureInfo> closeNow = new ArrayList<>();
        synchronized (textureCache) {
            closeNow.addAll(textureCache.values());
            closeNow.addAll(deferredCloses);
            textureCache.clear();
            deferredCloses.clear();
            renderBatchDepth = 0;
        }
        for (RegionTextureInfo info : closeNow) info.close();
        synchronized (dirtyTextures) {
            dirtyTextures.clear();
            revisions.clear();
            firstDirtyNanos.clear();
            lastDirtyNanos.clear();
        }
        synchronized (visibleTextures) {
            visibleTextures.clear();
        }
        blockColorsCache.clear();
        vanillaBlockColorsCache.clear();
        tintPolicyCache.clear();
        BlockTextureColorSampler.clearCache();
    }

    private void trimTextureCache() {
        List<RegionTextureInfo> retired = new ArrayList<>();
        while (textureCache.size() > MAX_TEXTURE_REGIONS) {
            var iterator = textureCache.entrySet().iterator();
            if (!iterator.hasNext()) break;
            RegionTextureInfo eldest = iterator.next().getValue();
            iterator.remove();
            retired.add(eldest);
        }
        for (RegionTextureInfo info : retired) retire(info);
    }

    private void retire(RegionTextureInfo info) {
        if (renderBatchDepth > 0) deferredCloses.add(info);
        else info.close();
    }

    private void markRegionVisible(String key) {
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

    private void requeue(String key) {
        synchronized (dirtyTextures) {
            long now = System.nanoTime();
            firstDirtyNanos.putIfAbsent(key, now);
            lastDirtyNanos.putIfAbsent(key, now);
            dirtyTextures.add(key);
        }
    }

    private int distancePriority(int regionX, int regionZ) {
        var player = Minecraft.getInstance().player;
        if (player == null) return 1;
        int playerRegionX = player.blockPosition().getX() >> 9;
        int playerRegionZ = player.blockPosition().getZ() >> 9;
        int distance = Math.abs(regionX - playerRegionX) + Math.abs(regionZ - playerRegionZ);
        return Math.max(1, 10_000 - distance * 100);
    }

    private static String key(int regionX, int regionZ) {
        return regionX + "," + regionZ;
    }

    private static final class RegionTextureInfo {
        private final DynamicTexture texture;
        private final ResourceLocation location;
        private final DynamicTexture glowTexture;
        private final ResourceLocation glowLocation;
        private final long generation;
        private CompletableFuture<MapTextureBuildWorker.PreparedPair> pending;
        private long uploadedRevision = Long.MIN_VALUE;
        private boolean initialized;

        private RegionTextureInfo(DynamicTexture texture, ResourceLocation location,
                DynamicTexture glowTexture, ResourceLocation glowLocation, long generation) {
            this.texture = texture;
            this.location = location;
            this.glowTexture = glowTexture;
            this.glowLocation = glowLocation;
            this.generation = generation;
        }

        private void close() {
            if (pending != null) pending.cancel(false);
            Minecraft.getInstance().getTextureManager().release(location);
            Minecraft.getInstance().getTextureManager().release(glowLocation);
        }
    }
}
