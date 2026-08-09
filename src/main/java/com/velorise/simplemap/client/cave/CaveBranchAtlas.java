package com.velorise.simplemap.client.cave;

import com.mojang.blaze3d.systems.RenderSystem;
import com.velorise.simplemap.client.MapAtlasMemoryTracker;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;

/** One fixed 64x64 GPU atlas dedicated to a single recursive cave LOD level. */
final class CaveBranchAtlas {
    static final int SIZE = 64;
    private static final int PITCH = AtlasGutter.pitch(SIZE);

    private final int level;
    private final int slotColumns;
    private final int slotCount;
    private final boolean[] allocated;
    private final ArrayDeque<Integer> free;
    /** Raw branch UVs may remain in the current immutable render plan. */
    private final ArrayDeque<Integer> quarantined = new ArrayDeque<>();
    private final CavePboUploader uploader = new CavePboUploader();
    private final int[] gutteredUpload = new int[PITCH * PITCH];

    private CaveAtlasTexture texture;
    private ResourceLocation location;
    private int textureId;
    private boolean initialized;
    private long storageGeneration;

    CaveBranchAtlas(int level) {
        if (level < 1) throw new IllegalArgumentException("Branch level must be positive");
        this.level = level;
        this.slotColumns = slotColumnsForLevel(level);
        this.slotCount = slotColumns * slotColumns;
        this.allocated = new boolean[slotCount];
        this.free = new ArrayDeque<>(slotCount);
        refill();
    }

    static int slotColumnsForLevel(int level) {
        // L1-L3 are density-selected all the way down to the supported 0.1x
        // fullscreen zoom. Keep a complete 16:9 working set resident instead of
        // forcing a coarser parent merely to fit the old shared branch profile.
        return MapMemoryBudgetPolicy.caveBranchColumns(level);
    }

    static int slotCountForLevel(int level) {
        int columns = slotColumnsForLevel(level);
        return columns * columns;
    }

    int slotCount() {
        return slotCount;
    }

    void ensureInitialized() {
        initialize();
    }

    boolean initialized() {
        return initialized;
    }

    long storageGeneration() {
        return storageGeneration;
    }

    boolean hasQuarantinedSlots() {
        return !quarantined.isEmpty();
    }

    int acquireSlot() {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        Integer slot = free.pollFirst();
        if (slot == null) return -1;
        allocated[slot] = true;
        return slot;
    }

    void releaseSlot(int slot) {
        if (slot < 0 || slot >= slotCount || !allocated[slot]) return;
        if (!quarantined.contains(slot)) quarantined.addLast(slot);
    }

    void onPageTableFrameBoundary() {
        RenderSystem.assertOnRenderThreadOrInit();
        while (!quarantined.isEmpty()) {
            int slot = quarantined.removeFirst();
            if (slot < 0 || slot >= slotCount || !allocated[slot]) continue;
            allocated[slot] = false;
            free.addLast(slot);
        }
    }

    void resetSlots() {
        RenderSystem.assertOnRenderThreadOrInit();
        java.util.Arrays.fill(allocated, false);
        quarantined.clear();
        refill();
    }

    CaveAtlasRegion region(int slot, long knownMask, long completeMask) {
        if (!initialized || slot < 0 || slot >= slotCount || !allocated[slot]) return null;
        int atlasSize = PITCH * slotColumns;
        int sourceX = (slot % slotColumns) * PITCH + AtlasGutter.SIZE;
        int sourceY = (slot / slotColumns) * PITCH + AtlasGutter.SIZE;
        int worldSize = 64 << level;
        return new CaveAtlasRegion(location, sourceX, sourceY, SIZE, atlasSize,
                level, worldSize, knownMask, completeMask);
    }

    void upload(int slot, int[] pixels, CaveTextureAtlas.DirtyRect dirty) {
        RenderSystem.assertOnRenderThreadOrInit();
        initialize();
        if (slot < 0 || slot >= slotCount || !allocated[slot]
                || pixels == null || pixels.length < SIZE * SIZE
                || dirty == null || dirty.isEmpty()) return;
        // Branch publication is deliberately sparse (normally one or two nodes
        // per pass). Uploading the complete 66x66 guttered slot costs only about
        // six percent more pixels than a 64x64 full upload and guarantees that
        // linear filtering never observes stale edge texels after a partial update.
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
                "simplemap", "cave_atlas/branch_l" + level + "_64");
        texture = new CaveAtlasTexture(atlasSize, true, () -> storageGeneration++);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        texture.allocateStorage();
        MapAtlasMemoryTracker.getInstance().register(
                "cave_branch_l" + level,
                (long) atlasSize * atlasSize * Integer.BYTES);
        textureId = texture.getId();
        initialized = true;
    }

    private void refill() {
        free.clear();
        for (int slot = 0; slot < slotCount; slot++) free.addLast(slot);
    }
}
