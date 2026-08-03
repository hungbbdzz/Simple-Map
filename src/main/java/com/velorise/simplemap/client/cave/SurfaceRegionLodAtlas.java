package com.velorise.simplemap.client.cave;

import com.mojang.blaze3d.systems.RenderSystem;
import com.velorise.simplemap.client.MapAtlasMemoryTracker;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy;
import com.velorise.simplemap.client.MapRegionLodPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;

/**
 * GPU atlas for the M4 region-centric surface hierarchy.
 *
 * <p>Every node is a fixed 64x64 texture. Level 0 covers one 512x512 source
 * region. Each higher level groups 2x2 direct children, so its world span grows
 * by a factor of two per level. A one-pixel gutter makes linear minification
 * safe and keeps region boundaries stable while panning.</p>
 */
public final class SurfaceRegionLodAtlas {
    public static final int SIZE = 64;
    private static final int PITCH = AtlasGutter.pitch(SIZE);

    private final int level;
    private final int slotColumns;
    private final int slotCount;
    private final boolean[] allocated;
    private final ArrayDeque<Integer> free;
    private final CavePboUploader uploader = new CavePboUploader();
    private final int[] gutteredUpload = new int[PITCH * PITCH];

    private CaveAtlasTexture texture;
    private ResourceLocation location;
    private int textureId;
    private boolean initialized;
    private long storageGeneration;

    public SurfaceRegionLodAtlas(int level) {
        if (level < 0) throw new IllegalArgumentException("level");
        this.level = level;
        this.slotColumns = level == 0
                ? MapMemoryBudgetPolicy.branchLowColumns()
                : level == 1
                ? Math.max(16, MapMemoryBudgetPolicy.branchHighColumns())
                : MapMemoryBudgetPolicy.branchHighColumns();
        this.slotCount = slotColumns * slotColumns;
        this.allocated = new boolean[slotCount];
        this.free = new ArrayDeque<>(slotCount);
        refill();
    }

    public int level() { return level; }
    public int slotCount() { return slotCount; }

    public void ensureInitialized() {
        initialize();
    }

    public long storageGeneration() {
        return storageGeneration;
    }

    public int acquireSlot() {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        Integer slot = free.pollFirst();
        if (slot == null) return -1;
        allocated[slot] = true;
        return slot;
    }

    public void releaseSlot(int slot) {
        if (slot < 0 || slot >= slotCount || !allocated[slot]) return;
        allocated[slot] = false;
        free.addLast(slot);
    }

    public void resetSlots() {
        RenderSystem.assertOnRenderThreadOrInit();
        java.util.Arrays.fill(allocated, false);
        refill();
    }

    public CaveAtlasRegion region(int slot, long knownMask, long completeMask) {
        if (!initialized || slot < 0 || slot >= slotCount || !allocated[slot]) {
            return null;
        }
        int atlasSize = PITCH * slotColumns;
        int sourceX = (slot % slotColumns) * PITCH + AtlasGutter.SIZE;
        int sourceY = (slot / slotColumns) * PITCH + AtlasGutter.SIZE;
        int worldSize = MapRegionLodPolicy.worldSize(level);
        // Level 0 uses a 64-bit leaf mask; factor-2 parents use its low four bits.
        return new CaveAtlasRegion(location, sourceX, sourceY, SIZE, atlasSize,
                level, worldSize, knownMask, completeMask);
    }

    public void upload(int slot, int[] pixels,
            int dirtyMinX, int dirtyMinY, int dirtyMaxX, int dirtyMaxY) {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        if (slot < 0 || slot >= slotCount || !allocated[slot]
                || pixels == null || pixels.length < SIZE * SIZE) return;
        int minX = Math.max(0, Math.min(SIZE - 1, dirtyMinX));
        int minY = Math.max(0, Math.min(SIZE - 1, dirtyMinY));
        int maxX = Math.max(minX, Math.min(SIZE - 1, dirtyMaxX));
        int maxY = Math.max(minY, Math.min(SIZE - 1, dirtyMaxY));

        // Upload the full guttered slot. Region nodes are intentionally sparse and
        // this avoids stale edge texels after partial publication.
        AtlasGutter.copyOnePixelBorder(pixels, SIZE, gutteredUpload);
        int atlasX = (slot % slotColumns) * PITCH;
        int atlasY = (slot / slotColumns) * PITCH;
        uploader.upload(textureId, atlasX, atlasY, PITCH, PITCH,
                gutteredUpload, PITCH, 0, 0);
    }

    private void initialize() {
        if (initialized) return;
        RenderSystem.assertOnRenderThreadOrInit();
        int atlasSize = PITCH * slotColumns;
        location = ResourceLocation.fromNamespaceAndPath(
                "simplemap", "surface_atlas/region_l" + level + "_64");
        texture = new CaveAtlasTexture(atlasSize, true,
                () -> storageGeneration++);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        texture.allocateStorage();
        MapAtlasMemoryTracker.getInstance().register(
                "surface_region_lod_l" + level,
                (long) atlasSize * atlasSize * Integer.BYTES);
        textureId = texture.getId();
        initialized = true;
    }

    private void refill() {
        free.clear();
        for (int slot = 0; slot < slotCount; slot++) free.addLast(slot);
    }
}
