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
    private static final int PITCH = SIZE + 2;
    public static final int SLOT_COLUMNS = MapMemoryBudgetPolicy.surfaceLeafColumns();
    public static final int SLOT_COUNT = SLOT_COLUMNS * SLOT_COLUMNS;

    private final boolean[] allocated = new boolean[SLOT_COUNT];
    private final ArrayDeque<Integer> free = new ArrayDeque<>(SLOT_COUNT);
    /** Slots stay quarantined until the old front page table can no longer sample them. */
    private final ArrayDeque<QuarantinedSlot> quarantined = new ArrayDeque<>();
    private static final long SLOT_REUSE_FENCE_NANOS = 50_000_000L;
    private final CavePboUploader colorUploader = new CavePboUploader();
    private final CavePboUploader glowUploader = new CavePboUploader();
    private final int[] gutteredColor = new int[PITCH * PITCH];
    private final int[] gutteredGlow = new int[PITCH * PITCH];

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
        drainQuarantinedSlots(System.nanoTime());
        Integer slot = free.pollFirst();
        if (slot == null) return -1;
        allocated[slot] = true;
        return slot;
    }

    public void releaseSlot(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT || !allocated[slot]) return;
        // Do not make the slot reusable immediately. The page-table remove is
        // staged in the back table; the current front table may still reference
        // this atlas address until the next frame-boundary swap. Immediate reuse
        // is the race that displayed one world's tile at another page coordinate.
        quarantined.addLast(new QuarantinedSlot(slot,
                System.nanoTime() + SLOT_REUSE_FENCE_NANOS));
    }

    private void drainQuarantinedSlots(long nowNanos) {
        while (!quarantined.isEmpty()) {
            QuarantinedSlot head = quarantined.peekFirst();
            if (head.releaseAfterNanos() > nowNanos) break;
            releaseQuarantinedHead();
        }
    }

    /** Called immediately after the back page table becomes the new front table. */
    public void onPageTableFrameBoundary() {
        RenderSystem.assertOnRenderThreadOrInit();
        while (!quarantined.isEmpty()) releaseQuarantinedHead();
    }

    private void releaseQuarantinedHead() {
        QuarantinedSlot head = quarantined.removeFirst();
        int slot = head.slot();
        if (slot < 0 || slot >= SLOT_COUNT || !allocated[slot]) return;
        allocated[slot] = false;
        free.addLast(slot);
    }

    public void resetSlots() {
        RenderSystem.assertOnRenderThreadOrInit();
        java.util.Arrays.fill(allocated, false);
        quarantined.clear();
        refill();
    }

    public CaveAtlasRegion region(int slot, boolean glow) {
        if (!initialized || slot < 0 || slot >= SLOT_COUNT || !allocated[slot]) return null;
        int atlasSize = PITCH * SLOT_COLUMNS;
        int sourceX = (slot % SLOT_COLUMNS) * PITCH + 1;
        int sourceY = (slot / SLOT_COLUMNS) * PITCH + 1;
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
        int atlasX = (slot % SLOT_COLUMNS) * PITCH;
        int atlasY = (slot / SLOT_COLUMNS) * PITCH;
        if (colorPixels != null && colorPixels.length >= SIZE * SIZE) {
            AtlasGutter.copyOnePixelBorder(colorPixels, SIZE, gutteredColor);
            colorUploader.upload(colorTextureId, atlasX, atlasY, PITCH, PITCH,
                    gutteredColor, PITCH, 0, 0);
        }
        if (glowPixels != null && glowPixels.length >= SIZE * SIZE) {
            AtlasGutter.copyOnePixelBorder(glowPixels, SIZE, gutteredGlow);
            glowUploader.upload(glowTextureId, atlasX, atlasY, PITCH, PITCH,
                    gutteredGlow, PITCH, 0, 0);
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
        /*
         * Exact leaves are minified at fractional zoom. Updating only a 16x16
         * interior can leave the replicated atlas edge stale and expose a one-pixel
         * neighbour-slot stripe. Re-upload the compact 66x66 guttered slot; it is
         * still only about 17 KiB per colour layer.
         */
        upload(slot, colorPixels, glowPixels);
    }

    private void initialize() {
        if (initialized) return;
        RenderSystem.assertOnRenderThreadOrInit();
        int atlasSize = PITCH * SLOT_COLUMNS;
        colorLocation = ResourceLocation.fromNamespaceAndPath(
                "simplemap", "surface_atlas/leaves_64");
        glowLocation = ResourceLocation.fromNamespaceAndPath(
                "simplemap", "surface_atlas/leaves_glow_64");
        /*
         * Xaero World Map uses GL_LINEAR for minification and GL_NEAREST for
         * magnification on its exact RegionTexture. Surface pages are often drawn
         * at fractional scales just below 1.0x; nearest minification aliases the
         * 16-block chunk cadence into long water/seabed dash patterns even when the
         * stored Surface pixels are correct. Cave exact atlases remain nearest;
         * Surface exact leaves opt into linear minification only.
         */
        colorTexture = new CaveAtlasTexture(atlasSize, true,
                () -> storageGeneration++);
        glowTexture = new CaveAtlasTexture(atlasSize, true,
                () -> storageGeneration++);
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

    private record QuarantinedSlot(int slot, long releaseAfterNanos) { }
}
