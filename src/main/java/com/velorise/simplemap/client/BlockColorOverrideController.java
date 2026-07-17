package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;

/** Applies block-color changes atomically and refreshes all cached surface tiles. */
public final class BlockColorOverrideController {
    private BlockColorOverrideController() {
    }

    public static void apply(String blockId, int argb) {
        if (blockId == null || blockId.isBlank()) return;
        MapConfig.blockColorOverrides.put(blockId, 0xFF000000 | (argb & 0x00FFFFFF));
        refresh(blockId);
    }

    public static void reset(String blockId) {
        if (blockId == null || blockId.isBlank()) return;
        MapConfig.blockColorOverrides.remove(blockId);
        refresh(blockId);
    }

    public static void clearAll() {
        if (MapConfig.blockColorOverrides.isEmpty()) return;
        MapConfig.blockColorOverrides.clear();
        MapConfig.save();
        MapTextureManager.getInstance().clearDerivedColorCaches();
        MapTextureManager.getInstance().invalidateStyle();
        MapTextureManager.getInstance().uploadDirtyTextures(true);
        requestLiveCaveRefresh();
    }

    private static void refresh(String blockId) {
        MapConfig.save();
        MapTextureManager.getInstance().invalidateBlockColor(blockId);
        MapTextureManager.getInstance().uploadDirtyTextures(true);
        requestLiveCaveRefresh();
    }

    private static void requestLiveCaveRefresh() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            // Surface .smdat stores block IDs and recolors immediately. Historical
            // cave files store final pixels, so only live-loaded cave columns can be
            // refreshed until the next palette-backed cave format is introduced.
            ChunkScanner.getInstance().requestRefresh(minecraft);
        }
    }
}
