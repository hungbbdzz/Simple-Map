package com.velorise.simplemap.client.cave;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

/**
 * GPU-only atlas texture that owns no atlas-sized NativeImage.
 *
 * Minecraft can call {@link #load(ResourceManager)} again during resource reloads;
 * reallocating the storage there keeps the atlas valid without retaining a second
 * CPU copy of every atlas level.
 */
final class CaveAtlasTexture extends AbstractTexture {
    private final int size;
    private final boolean linearMinification;
    private final Runnable storageAllocatedCallback;

    CaveAtlasTexture(int size, Runnable storageAllocatedCallback) {
        this(size, false, storageAllocatedCallback);
    }

    CaveAtlasTexture(int size, boolean linearMinification,
            Runnable storageAllocatedCallback) {
        if (size <= 0) throw new IllegalArgumentException("Atlas size must be positive");
        this.size = size;
        this.linearMinification = linearMinification;
        this.storageAllocatedCallback = storageAllocatedCallback;
    }

    @Override
    public void load(ResourceManager resourceManager) {
        if (RenderSystem.isOnRenderThreadOrInit()) allocateStorage();
        else RenderSystem.recordRenderCall(this::allocateStorage);
    }

    void allocateStorage() {
        RenderSystem.assertOnRenderThreadOrInit();
        GlStateManager._bindTexture(getId());
        // Exact atlases remain nearest-filtered. Branch atlases opt into linear
        // minification only after allocating replicated one-pixel gutters per slot.
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                linearMinification ? GL11.GL_LINEAR : GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                size, size, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                (ByteBuffer) null);
        if (storageAllocatedCallback != null) storageAllocatedCallback.run();
    }
}
