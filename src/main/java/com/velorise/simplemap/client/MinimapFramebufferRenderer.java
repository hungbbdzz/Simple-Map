package com.velorise.simplemap.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Fixed-resolution retained minimap composition target.
 *
 * <p>The map is rendered once into a 512x512 texture using nearest atlas
 * sampling. A small overscan border lets the final HUD pass apply the player's
 * fractional movement through UV translation instead of moving world texels on
 * a different sub-pixel boundary every frame. The final target is linearly
 * minified into the configured HUD size.</p>
 */
final class MinimapFramebufferRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int TARGET_SIZE = 512;
    private static final int OVERSCAN = 8;
    private static final int CONTENT_SIZE = TARGET_SIZE - OVERSCAN * 2;
    private static final MinimapFramebufferRenderer INSTANCE = new MinimapFramebufferRenderer();

    private TextureTarget target;
    private boolean permanentlyDisabled;
    private boolean failureLogged;

    static MinimapFramebufferRenderer getInstance() {
        return INSTANCE;
    }

    private MinimapFramebufferRenderer() {
    }

    /**
     * @return true only when the framebuffer path emitted actual map texture
     * content; false requests the direct exact-leaf path as a safe fallback.
     */
    boolean render(GuiGraphics guiGraphics, int x, int y, int size,
            double centerX, double centerZ, float pixelsPerBlock,
            boolean rotateWithPlayer, float partialTick) {
        if (permanentlyDisabled || size <= 0 || pixelsPerBlock <= 0.0f) return false;
        RenderSystem.assertOnRenderThreadOrInit();

        Minecraft minecraft = Minecraft.getInstance();
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        int[] oldViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, oldViewport);
        int oldFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        boolean oldStencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean oldDepthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] oldScissor = new int[4];
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, oldScissor);

        try {
            ensureTarget();
            if (target == null) return false;

            // CONTENT_SIZE, rather than the full target, maps to the HUD square.
            // The remaining pixels are a guard band for fractional UV movement.
            float targetScale = pixelsPerBlock * (CONTENT_SIZE / (float) size);
            double worldPerTargetPixel = 1.0 / Math.max(0.0001, targetScale);
            double snappedX = Math.rint(centerX / worldPerTargetPixel) * worldPerTargetPixel;
            double snappedZ = Math.rint(centerZ / worldPerTargetPixel) * worldPerTargetPixel;

            double deltaX = centerX - snappedX;
            double deltaZ = centerZ - snappedZ;
            double sourceOffsetX;
            double sourceOffsetY;
            if (rotateWithPlayer && minecraft.player != null) {
                float yaw = net.minecraft.util.Mth.rotLerp(
                        partialTick, minecraft.player.yRotO, minecraft.player.getYRot());
                double angle = Math.toRadians(-yaw - 180.0f);
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                // R * (actualCenter - snappedCenter), matching MapRenderer's map
                // transform. Sampling around this point keeps the player centered.
                sourceOffsetX = (deltaX * cos - deltaZ * sin) * targetScale;
                sourceOffsetY = (deltaX * sin + deltaZ * cos) * targetScale;
            } else {
                sourceOffsetX = deltaX * targetScale;
                sourceOffsetY = deltaZ * targetScale;
            }

            guiGraphics.flush();
            target.bindWrite(true);
            // Circular minimaps leave a stencil test active on the main target. The
            // off-screen target has its own attachments and must be rendered without
            // inheriting that mask; the mask is restored before final composition.
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            // The minimap target is a pure 2D composition surface. Inheriting the
            // main HUD depth test can reject every map fragment while the retained
            // render statistics still report exact pages as submitted, producing a
            // fully black target that is incorrectly treated as successful.
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            boolean caveActive = CaveMode.isActive(minecraft);
            // An empty cave target must remain an intentional dark map, not a fully
            // transparent hole showing the 3D world through the minimap frame.
            target.setClearColor(caveActive ? 0.03f : 0.025f,
                    caveActive ? 0.04f : 0.03f,
                    caveActive ? 0.05f : 0.035f,
                    1.0f);
            target.clear(Minecraft.ON_OSX);
            RenderSystem.setProjectionMatrix(
                    // Keep GUI vertices at Z=0 inside the clip volume. The normal
                    // Minecraft GUI projection relies on a separate model-view
                    // translation that is not guaranteed for this retained FBO.
                    new Matrix4f().setOrtho(0.0f, TARGET_SIZE, TARGET_SIZE, 0.0f,
                            -1000.0f, 1000.0f),
                    VertexSorting.ORTHOGRAPHIC_Z);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().setIdentity();
            try {
                MapDrawResult drawResult = MapRenderer.getInstance().drawMapOffscreen(guiGraphics,
                        TARGET_SIZE, TARGET_SIZE, snappedX, snappedZ, targetScale,
                        false, rotateWithPlayer, partialTick, CONTENT_SIZE / (float) size);
                guiGraphics.flush();

                if (!drawResult.drewAnyMapContent()) {
                    restoreTarget(oldFramebuffer, oldViewport, oldProjection, oldSorting);
                    restoreCapability(GL11.GL_STENCIL_TEST, oldStencilEnabled);
                    restoreCapability(GL11.GL_DEPTH_TEST, oldDepthEnabled);
                    restoreScissor(oldScissorEnabled, oldScissor);
                    return false;
                }
            } finally {
                guiGraphics.pose().popPose();
            }

            restoreTarget(oldFramebuffer, oldViewport, oldProjection, oldSorting);
            restoreCapability(GL11.GL_STENCIL_TEST, oldStencilEnabled);
            restoreCapability(GL11.GL_DEPTH_TEST, oldDepthEnabled);
            restoreScissor(oldScissorEnabled, oldScissor);

            // The target is upside-down in GUI space. Select a guarded source
            // rectangle and flip V while compositing it to the HUD.
            float sourceX = clampSource((float) (OVERSCAN + sourceOffsetX));
            float sourceY = clampSource((float) (OVERSCAN + sourceOffsetY));
            drawTarget(guiGraphics, x, y, size, sourceX, sourceY);
            return true;
        } catch (Throwable throwable) {
            restoreTarget(oldFramebuffer, oldViewport, oldProjection, oldSorting);
            restoreCapability(GL11.GL_STENCIL_TEST, oldStencilEnabled);
            restoreCapability(GL11.GL_DEPTH_TEST, oldDepthEnabled);
            restoreScissor(oldScissorEnabled, oldScissor);
            permanentlyDisabled = true;
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[SimpleMap] Minimap framebuffer path failed; using direct rendering", throwable);
            }
            destroy();
            return false;
        }
    }

    void destroy() {
        if (target == null) return;
        TextureTarget old = target;
        target = null;
        if (RenderSystem.isOnRenderThreadOrInit()) old.destroyBuffers();
        else RenderSystem.recordRenderCall(old::destroyBuffers);
    }

    void resetFailureState() {
        permanentlyDisabled = false;
        failureLogged = false;
    }

    private void ensureTarget() {
        if (target != null && target.width == TARGET_SIZE && target.height == TARGET_SIZE) return;
        destroy();
        // No depth attachment is needed for a flat map composition target. A
        // color-only FBO also prevents stale depth contents from hiding valid map
        // pages on drivers that preserve more state than Minecraft's GUI expects.
        target = new TextureTarget(TARGET_SIZE, TARGET_SIZE, false, Minecraft.ON_OSX);
        target.setFilterMode(GL11.GL_LINEAR);
        target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    }

    private static void restoreTarget(int framebuffer, int[] viewport,
            Matrix4f projection, VertexSorting sorting) {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        RenderSystem.setProjectionMatrix(projection, sorting);
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) GL11.glEnable(capability);
        else GL11.glDisable(capability);
    }

    private static void restoreScissor(boolean enabled, int[] box) {
        GL11.glScissor(box[0], box[1], box[2], box[3]);
        restoreCapability(GL11.GL_SCISSOR_TEST, enabled);
    }

    private static float clampSource(float value) {
        // Quantization moves by at most half a target pixel, but clamp defensively
        // so numerical drift can never sample beyond the guard band.
        return Math.max(0.0f, Math.min(TARGET_SIZE - CONTENT_SIZE, value));
    }

    private void drawTarget(GuiGraphics guiGraphics, int x, int y, int size,
            float sourceX, float sourceY) {
        float u0 = sourceX / TARGET_SIZE;
        float u1 = (sourceX + CONTENT_SIZE) / TARGET_SIZE;
        float vTop = 1.0f - sourceY / TARGET_SIZE;
        float vBottom = 1.0f - (sourceY + CONTENT_SIZE) / TARGET_SIZE;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        Matrix4f matrix = guiGraphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, x, y, 0.0f).setUv(u0, vTop);
        buffer.addVertex(matrix, x, y + size, 0.0f).setUv(u0, vBottom);
        buffer.addVertex(matrix, x + size, y + size, 0.0f).setUv(u1, vBottom);
        buffer.addVertex(matrix, x + size, y, 0.0f).setUv(u1, vTop);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }
}
