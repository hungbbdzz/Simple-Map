package com.velorise.simplemap.client.cave;

import com.mojang.blaze3d.systems.RenderSystem;
import com.velorise.simplemap.client.MapLodPolicy;
import com.velorise.simplemap.client.MapAtlasMemoryTracker;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayDeque;

/**
 * Shared exact cave-page atlas with compact page mip levels.
 *
 * Every LOD uses the same 48x48 slot index, so switching zoom levels only changes
 * the texture and source rectangle. Every atlas level is represented by a custom
 * GPU-only AbstractTexture, so resource reloads recreate storage without retaining
 * an atlas-sized NativeImage on the Java/native heap.
 */
final class CaveTextureAtlas {
    static final int PAGE_SIZE = 64;
    static final int SLOT_COLUMNS = MapMemoryBudgetPolicy.caveExactColumns();
    static final int SLOT_COUNT = SLOT_COLUMNS * SLOT_COLUMNS;
    static final int LOD_COUNT = 4;

    private static final int[] LOD_SIZES = { 64, 32, 16, 8 };

    private final CaveAtlasTexture[] textures = new CaveAtlasTexture[LOD_COUNT];
    private final ResourceLocation[] locations = new ResourceLocation[LOD_COUNT];
    private final int[] textureIds = new int[LOD_COUNT];
    private final boolean[] allocatedSlots = new boolean[SLOT_COUNT];
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>(SLOT_COUNT);
    private final CavePboUploader uploader = new CavePboUploader();

    private boolean initialized;
    private long storageGeneration;

    CaveTextureAtlas() {
        refillFreeSlots();
    }

    void ensureInitialized() {
        initialize();
    }

    long storageGeneration() {
        return storageGeneration;
    }

    int acquireSlot() {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        Integer slot = freeSlots.pollFirst();
        if (slot == null) return -1;
        allocatedSlots[slot] = true;
        return slot;
    }

    void releaseSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT || !allocatedSlots[slot]) return;
        allocatedSlots[slot] = false;
        freeSlots.addLast(slot);
    }

    void resetSlots() {
        RenderSystem.assertOnRenderThreadOrInit();
        for (int slot = 0; slot < allocatedSlots.length; slot++) {
            allocatedSlots[slot] = false;
        }
        refillFreeSlots();
    }

    CaveAtlasRegion region(int slot, float scale) {
        if (!initialized || slot < 0 || slot >= SLOT_COUNT || !allocatedSlots[slot]) return null;
        int lod = lodForScale(scale);
        int pageSize = LOD_SIZES[lod];
        int atlasSize = pageSize * SLOT_COLUMNS;
        int sourceX = (slot % SLOT_COLUMNS) * pageSize;
        int sourceY = (slot / SLOT_COLUMNS) * pageSize;
        return new CaveAtlasRegion(locations[lod], sourceX, sourceY,
                pageSize, atlasSize);
    }

    void upload(int slot, int lod, int[] pixels, DirtyRect dirty) {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        if (slot < 0 || slot >= SLOT_COUNT || !allocatedSlots[slot]) {
            throw new IllegalStateException("Attempted to upload an unallocated cave atlas slot");
        }
        if (lod < 0 || lod >= LOD_COUNT || dirty == null || dirty.isEmpty()) return;

        int pageSize = LOD_SIZES[lod];
        int atlasX = (slot % SLOT_COLUMNS) * pageSize + dirty.minX();
        int atlasY = (slot / SLOT_COLUMNS) * pageSize + dirty.minY();
        uploader.upload(textureIds[lod], atlasX, atlasY,
                dirty.width(), dirty.height(), pixels, pageSize,
                dirty.minX(), dirty.minY());
    }

    static int lodSize(int lod) {
        return LOD_SIZES[lod];
    }

    static int lodForScale(float scale) {
        return MapLodPolicy.leafMipLevel(scale, LOD_COUNT - 1);
    }

    private void initialize() {
        if (initialized) return;
        RenderSystem.assertOnRenderThreadOrInit();
        Minecraft minecraft = Minecraft.getInstance();
        for (int lod = 0; lod < LOD_COUNT; lod++) {
            int pageSize = LOD_SIZES[lod];
            int atlasSize = pageSize * SLOT_COLUMNS;
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                    "simplemap", "cave_atlas/lod_" + pageSize);
            CaveAtlasTexture texture = new CaveAtlasTexture(atlasSize, this::markStorageAllocated);
            minecraft.getTextureManager().register(location, texture);
            texture.allocateStorage();
            MapAtlasMemoryTracker.getInstance().register(
                    "cave_exact_lod_" + pageSize,
                    (long) atlasSize * atlasSize * Integer.BYTES);

            int textureId = texture.getId();
            textures[lod] = texture;
            locations[lod] = location;
            textureIds[lod] = textureId;
        }
        initialized = true;
    }

    private void markStorageAllocated() {
        storageGeneration++;
    }

    private void refillFreeSlots() {
        freeSlots.clear();
        for (int slot = 0; slot < SLOT_COUNT; slot++) freeSlots.addLast(slot);
    }

    record DirtyRect(int minX, int minY, int maxX, int maxY) {
        static DirtyRect full(int size) {
            return new DirtyRect(0, 0, size - 1, size - 1);
        }

        boolean isEmpty() {
            return maxX < minX || maxY < minY;
        }

        int width() {
            return isEmpty() ? 0 : maxX - minX + 1;
        }

        int height() {
            return isEmpty() ? 0 : maxY - minY + 1;
        }
    }
}
