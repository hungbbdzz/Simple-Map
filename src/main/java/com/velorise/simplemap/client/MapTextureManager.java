package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.platform.NativeImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MapTextureManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapTextureManager INSTANCE = new MapTextureManager();
    public static MapTextureManager getInstance() {
        return INSTANCE;
    }

    private final Map<String, RegionTextureInfo> textureCache = new java.util.LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyTextures = new HashSet<>();
    private long lastUploadTime = 0;

    private MapTextureManager() {}

    /**
     * Marks a region texture as needing an upload to the GPU.
     */
    public void markRegionTextureDirty(int rx, int rz) {
        synchronized (dirtyTextures) {
            dirtyTextures.add(rx + "," + rz);
        }
    }

    /**
     * Returns the ResourceLocation of the region's DynamicTexture, or null if the region is not loaded yet.
     */
    public ResourceLocation getRegionTexture(int rx, int rz) {
        String key = rx + "," + rz;
        synchronized (textureCache) {
            RegionTextureInfo info = textureCache.get(key);
            if (info == null) {
                // Only load if the region actually has a file on disk or is already in the cache (prevents unexplored regions from bloating cache)
                if (MapManager.getInstance().hasRegionFile(rx, rz) || MapManager.getInstance().isRegionLoadedInCache(rx, rz)) {
                    // Check if the underlying region data is loaded
                    MapManager.Region region = MapManager.getInstance().getRegion(rx, rz, true);
                    if (region != null && region.isLoaded()) {
                        // Create the DynamicTexture
                        DynamicTexture texture = new DynamicTexture(512, 512, false);
                        texture.setFilter(false, false); // Disable bilinear filtering to keep map sharp/pixelated in GUI screen
                        ResourceLocation location = ResourceLocation.fromNamespaceAndPath("simplemap", "regions/r_" + rx + "_" + rz);
                        Minecraft.getInstance().getTextureManager().register(location, texture);
                        
                        info = new RegionTextureInfo(texture, location);
                        textureCache.put(key, info);
                        markRegionTextureDirty(rx, rz); // Force immediate initial upload
                        
                        // LRU Cache eviction (max 256 cached textures to limit native/GPU memory)
                        while (textureCache.size() > 256) {
                            java.util.Iterator<Map.Entry<String, RegionTextureInfo>> it = textureCache.entrySet().iterator();
                            if (it.hasNext()) {
                                Map.Entry<String, RegionTextureInfo> eldestEntry = it.next();
                                RegionTextureInfo eldest = eldestEntry.getValue();
                                it.remove();
                                eldest.texture.close();
                                Minecraft.getInstance().getTextureManager().release(eldest.location);
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
            return info != null ? info.location : null;
        }
    }

    /**
     * Uploads any dirty region textures to OpenGL. MUST be called on the client main render thread.
     * Throttled to 5 updates/sec by default unless force is true.
     */
    public void uploadDirtyTextures() {
        uploadDirtyTextures(false);
    }

    public void uploadDirtyTextures(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (now - lastUploadTime < 200)) {
            return;
        }
        lastUploadTime = now;

        Set<String> toUpload;
        synchronized (dirtyTextures) {
            if (dirtyTextures.isEmpty()) return;
            toUpload = new HashSet<>(dirtyTextures);
            dirtyTextures.clear();
        }

        for (String key : toUpload) {
            String[] parts = key.split(",");
            int rx = Integer.parseInt(parts[0]);
            int rz = Integer.parseInt(parts[1]);

            RegionTextureInfo info;
            synchronized (textureCache) {
                info = textureCache.get(key);
            }

            if (info != null) {
                MapManager.Region region = MapManager.getInstance().getRegion(rx, rz, false);
                if (region != null && region.isLoaded()) {
                    NativeImage nativeImage = info.texture.getPixels();
                    if (nativeImage != null) {
                        region.lock();
                        try {
                            int[] pixels = region.getPixelsDirect();
                            for (int z = 0; z < 512; z++) {
                                for (int x = 0; x < 512; x++) {
                                    nativeImage.setPixelRGBA(x, z, pixels[z * 512 + x]);
                                }
                            }
                        } finally {
                            region.unlock();
                        }
                        // Upload texture to OpenGL
                        info.texture.upload();

                        // Set nearest-neighbor filtering to make the map pixel-art crisp
                        info.texture.setFilter(false, false);
                    }
                }
            }
        }
    }

    /**
     * Clears all cached textures, releasing OpenGL resources.
     */
    public void clearCache() {
        synchronized (textureCache) {
            for (RegionTextureInfo info : textureCache.values()) {
                info.texture.close();
                Minecraft.getInstance().getTextureManager().release(info.location);
            }
            textureCache.clear();
        }
        synchronized (dirtyTextures) {
            dirtyTextures.clear();
        }
        LOGGER.info("SimpleMap cleared OpenGL texture cache.");
    }

    private static class RegionTextureInfo {
        public final DynamicTexture texture;
        public final ResourceLocation location;

        public RegionTextureInfo(DynamicTexture texture, ResourceLocation location) {
            this.texture = texture;
            this.location = location;
        }
    }
}
