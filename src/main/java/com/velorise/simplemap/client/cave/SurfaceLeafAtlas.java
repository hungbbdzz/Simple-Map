package com.velorise.simplemap.client.cave;

import com.mojang.blaze3d.systems.RenderSystem;
import com.velorise.simplemap.client.MapAtlasMemoryTracker;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy;
import com.velorise.simplemap.client.MapPageLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;

/** Shared retained GPU atlas for exact 64x64 surface leaves and their glow layer. */
public final class SurfaceLeafAtlas {
    public static final int SIZE = 64;
    public static final int SLOT_COLUMNS = MapMemoryBudgetPolicy.surfaceLeafColumns();
    public static final int SLOT_COUNT = SLOT_COLUMNS * SLOT_COLUMNS;

    private final boolean[] allocated = new boolean[SLOT_COUNT];
    private final ArrayDeque<Integer> free = new ArrayDeque<>(SLOT_COUNT);
    private final CavePboUploader colorUploader = new CavePboUploader();
    private final CavePboUploader glowUploader = new CavePboUploader();

    private CaveAtlasTexture colorTexture;
    private CaveAtlasTexture glowTexture;
    private ResourceLocation colorLocation;
    private ResourceLocation glowLocation;
    private int colorTextureId;
    private int glowTextureId;
    private boolean initialized;
    private long storageGeneration;

    public SurfaceLeafAtlas() {
        refill();
    }

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
        if (slot < 0 || slot >= SLOT_COUNT || !allocated[slot]) return;
        allocated[slot] = false;
        free.addLast(slot);
    }

    public void resetSlots() {
        RenderSystem.assertOnRenderThreadOrInit();
        java.util.Arrays.fill(allocated, false);
        refill();
    }

    public CaveAtlasRegion region(int slot, boolean glow) {
        if (!initialized || slot < 0 || slot >= SLOT_COUNT || !allocated[slot]) return null;
        int atlasSize = SIZE * SLOT_COLUMNS;
        int sourceX = (slot % SLOT_COLUMNS) * SIZE;
        int sourceY = (slot / SLOT_COLUMNS) * SIZE;
        return new CaveAtlasRegion(glow ? glowLocation : colorLocation,
                sourceX, sourceY, SIZE, atlasSize,
                0, SIZE, -1L, -1L);
    }

    public void upload(int slot, int[] colorPixels, int[] glowPixels) {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        if (slot < 0 || slot >= SLOT_COUNT || !allocated[slot]) {
            throw new IllegalStateException("Attempted to upload an unallocated surface leaf slot");
        }
        int atlasX = (slot % SLOT_COLUMNS) * SIZE;
        int atlasY = (slot / SLOT_COLUMNS) * SIZE;
        if (colorPixels != null && colorPixels.length >= SIZE * SIZE) {
            colorUploader.upload(colorTextureId, atlasX, atlasY, SIZE, SIZE,
                    colorPixels, SIZE, 0, 0);
        }
        if (glowPixels != null && glowPixels.length >= SIZE * SIZE) {
            glowUploader.upload(glowTextureId, atlasX, atlasY, SIZE, SIZE,
                    glowPixels, SIZE, 0, 0);
        }
    }

    /** Uploads only the changed 16x16 chunk-sized parts of one 64x64 leaf. */
    public void uploadSubtiles(int slot, int[] colorPixels, int[] glowPixels,
            int subtileMask) {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        if (slot < 0 || slot >= SLOT_COUNT || !allocated[slot]) {
            throw new IllegalStateException(
                    "Attempted to upload an unallocated surface leaf slot");
        }
        if (subtileMask == 0) return;
        int atlasBaseX = (slot % SLOT_COLUMNS) * SIZE;
        int atlasBaseY = (slot / SLOT_COLUMNS) * SIZE;
        for (int subtileZ = 0; subtileZ < MapPageLayout.SUBTILES_PER_PAGE;
                subtileZ++) {
            for (int subtileX = 0; subtileX < MapPageLayout.SUBTILES_PER_PAGE;
                    subtileX++) {
                int bit = 1 << MapPageLayout.subtileIndex(subtileX, subtileZ);
                if ((subtileMask & bit) == 0) continue;
                int sourceX = subtileX * MapPageLayout.SUBTILE_SIZE;
                int sourceY = subtileZ * MapPageLayout.SUBTILE_SIZE;
                int atlasX = atlasBaseX + sourceX;
                int atlasY = atlasBaseY + sourceY;
                if (colorPixels != null && colorPixels.length >= SIZE * SIZE) {
                    colorUploader.upload(colorTextureId, atlasX, atlasY,
                            MapPageLayout.SUBTILE_SIZE, MapPageLayout.SUBTILE_SIZE,
                            colorPixels, SIZE, sourceX, sourceY);
                }
                if (glowPixels != null && glowPixels.length >= SIZE * SIZE) {
                    glowUploader.upload(glowTextureId, atlasX, atlasY,
                            MapPageLayout.SUBTILE_SIZE, MapPageLayout.SUBTILE_SIZE,
                            glowPixels, SIZE, sourceX, sourceY);
                }
            }
        }
    }

    private void initialize() {
        if (initialized) return;
        RenderSystem.assertOnRenderThreadOrInit();
        int atlasSize = SIZE * SLOT_COLUMNS;
        colorLocation = ResourceLocation.fromNamespaceAndPath(
                "simplemap", "surface_atlas/leaves_64");
        glowLocation = ResourceLocation.fromNamespaceAndPath(
                "simplemap", "surface_atlas/leaves_glow_64");
        colorTexture = new CaveAtlasTexture(atlasSize, () -> storageGeneration++);
        glowTexture = new CaveAtlasTexture(atlasSize, () -> storageGeneration++);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.getTextureManager().register(colorLocation, colorTexture);
        minecraft.getTextureManager().register(glowLocation, glowTexture);
        colorTexture.allocateStorage();
        glowTexture.allocateStorage();
        long bytesPerAtlas = (long) atlasSize * atlasSize * Integer.BYTES;
        MapAtlasMemoryTracker.getInstance().register(
                "surface_exact_color", bytesPerAtlas);
        MapAtlasMemoryTracker.getInstance().register(
                "surface_exact_glow", bytesPerAtlas);
        colorTextureId = colorTexture.getId();
        glowTextureId = glowTexture.getId();
        initialized = true;
    }

    private void refill() {
        free.clear();
        for (int slot = 0; slot < SLOT_COUNT; slot++) free.addLast(slot);
    }
}
