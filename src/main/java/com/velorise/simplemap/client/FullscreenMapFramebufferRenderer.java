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
 * Screen-space retained composition for far-zoom fullscreen surface rendering.
 *
 * <p>The map is rendered into a framebuffer whose content area maps one-to-one to
 * GUI pixels. The world centre is snapped to the framebuffer texel grid and the
 * remaining fractional movement is applied as a tiny UV offset during the final
 * composite. This prevents exact/branch atlas texels from landing on a different
 * fractional screen boundary every frame while panning.</p>
 *
 * <p>The target is allocated in physical framebuffer pixels rather than logical
 * GUI pixels. After Minecraft applies GUI scaling, one source texel therefore
 * lands on one display pixel instead of being magnified by the GUI scale. A small
 * guard band keeps fractional UV correction away from unrendered edges. Cave
 * views remain on the direct path because their thin lines
 * and alpha composition have different sampling requirements.</p>
 */
final class FullscreenMapFramebufferRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final FullscreenMapFramebufferRenderer INSTANCE =
            new FullscreenMapFramebufferRenderer();
    private static final int OVERSCAN = 12;
    private static final float FAR_ZOOM_THRESHOLD = 0.50f;
    private static final boolean QUARANTINED = true;

    private TextureTarget target;
    private boolean permanentlyDisabled;
    private boolean failureLogged;
    private long renderedFrames;
    private long fallbackFrames;
    private long reallocations;

    static FullscreenMapFramebufferRenderer getInstance() {
        return INSTANCE;
    }

    private FullscreenMapFramebufferRenderer() {
    }

    static boolean shouldUse(Minecraft minecraft, float pixelsPerBlock) {
        // Quarantined for correctness: the off-screen target can report successful
        // draw calls while compositing only its clear colour below 0.5 px/block.
        // The direct renderer is already cache-only and supports the same exact/LOD
        // plans, so use it at every zoom until the FBO path can validate actual
        // colour output instead of relying on draw-call presence.
        return !QUARANTINED && minecraft != null && minecraft.level != null
                && pixelsPerBlock > 0.0f
                && pixelsPerBlock < FAR_ZOOM_THRESHOLD
                && !CaveMode.isActive(minecraft);
    }

    /**
     * @return true when the off-screen target produced visible map content. A false
     *         result asks the caller to use direct rendering for this frame.
     */
    boolean render(GuiGraphics guiGraphics, int viewportX, int viewportY,
            int width, int height, double centerX, double centerZ,
            float pixelsPerBlock, boolean drawPlayer,
            double mouseWorldX, double mouseWorldZ, float partialTick) {
        if (permanentlyDisabled || width <= 0 || height <= 0
                || pixelsPerBlock <= 0.0f) {
            fallbackFrames++;
            return false;
        }
        RenderSystem.assertOnRenderThreadOrInit();

        Minecraft minecraft = Minecraft.getInstance();
        double guiScale = Math.max(1.0, minecraft.getWindow().getGuiScale());
        int contentWidth = Math.max(1, (int) Math.ceil(width * guiScale));
        int contentHeight = Math.max(1, (int) Math.ceil(height * guiScale));
        int targetWidth = contentWidth + OVERSCAN * 2;
        int targetHeight = contentHeight + OVERSCAN * 2;
        MapRenderScalePolicy.Scales scales = MapRenderScalePolicy.fullscreenFbo(
                pixelsPerBlock, guiScale);
        float targetPixelsPerBlock = scales.renderPixelsPerBlock();
        int maximumTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        if (targetWidth > maximumTextureSize || targetHeight > maximumTextureSize) {
            fallbackFrames++;
            return false;
        }

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
            ensureTarget(targetWidth, targetHeight);
            if (target == null) {
                fallbackFrames++;
                return false;
            }

            double worldPerTargetPixel = 1.0 / Math.max(0.0001,
                    targetPixelsPerBlock);
            double snappedX = Math.rint(centerX / worldPerTargetPixel)
                    * worldPerTargetPixel;
            double snappedZ = Math.rint(centerZ / worldPerTargetPixel)
                    * worldPerTargetPixel;
            double sourceOffsetX = (centerX - snappedX) * targetPixelsPerBlock;
            double sourceOffsetY = (centerZ - snappedZ) * targetPixelsPerBlock;

            guiGraphics.flush();
            target.bindWrite(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            target.setClearColor(0.025f, 0.03f, 0.035f, 1.0f);
            target.clear(Minecraft.ON_OSX);
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0f, targetWidth, targetHeight, 0.0f,
                            -1000.0f, 1000.0f),
                    VertexSorting.ORTHOGRAPHIC_Z);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().setIdentity();
            MapDrawResult drawResult;
            try {
                drawResult = MapRenderer.getInstance().drawFullscreenMapOffscreen(
                        guiGraphics, targetWidth, targetHeight,
                        snappedX, snappedZ, targetPixelsPerBlock,
                        scales.policyPixelsPerBlock(), drawPlayer,
                        mouseWorldX, mouseWorldZ, partialTick);
                guiGraphics.flush();
            } finally {
                guiGraphics.pose().popPose();
            }

            restoreTarget(oldFramebuffer, oldViewport, oldProjection, oldSorting);
            restoreCapability(GL11.GL_STENCIL_TEST, oldStencilEnabled);
            restoreCapability(GL11.GL_DEPTH_TEST, oldDepthEnabled);
            restoreScissor(oldScissorEnabled, oldScissor);

            if (!drawResult.drewAnyMapContent()) {
                fallbackFrames++;
                return false;
            }

            float sourceX = clampSource((float) (OVERSCAN + sourceOffsetX),
                    targetWidth - contentWidth);
            float sourceY = clampSource((float) (OVERSCAN + sourceOffsetY),
                    targetHeight - contentHeight);
            drawTarget(guiGraphics, viewportX, viewportY, width, height,
                    sourceX, sourceY, contentWidth, contentHeight,
                    targetWidth, targetHeight);
            renderedFrames++;
            return true;
        } catch (Throwable throwable) {
            restoreTarget(oldFramebuffer, oldViewport, oldProjection, oldSorting);
            restoreCapability(GL11.GL_STENCIL_TEST, oldStencilEnabled);
            restoreCapability(GL11.GL_DEPTH_TEST, oldDepthEnabled);
            restoreScissor(oldScissorEnabled, oldScissor);
            permanentlyDisabled = true;
            fallbackFrames++;
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[SimpleMap] Fullscreen framebuffer path failed; using direct rendering",
                        throwable);
            }
            destroy();
            return false;
        }
    }

    void resetFailureState() {
        permanentlyDisabled = false;
        failureLogged = false;
    }

    void destroy() {
        if (target == null) return;
        TextureTarget old = target;
        target = null;
        if (RenderSystem.isOnRenderThreadOrInit()) old.destroyBuffers();
        else RenderSystem.recordRenderCall(old::destroyBuffers);
    }

    Snapshot snapshot() {
        return new Snapshot(renderedFrames, fallbackFrames, reallocations,
                target == null ? 0 : target.width,
                target == null ? 0 : target.height,
                permanentlyDisabled || QUARANTINED);
    }

    private void ensureTarget(int width, int height) {
        if (target != null && target.width == width && target.height == height) return;
        destroy();
        target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        target.setFilterMode(GL11.GL_LINEAR);
        target.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        reallocations++;
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

    private static float clampSource(float value, int maximum) {
        return Math.max(0.0f, Math.min(Math.max(0, maximum), value));
    }

    private void drawTarget(GuiGraphics guiGraphics, int x, int y,
            int width, int height, float sourceX, float sourceY,
            int sourceWidth, int sourceHeight,
            int textureWidth, int textureHeight) {
        float u0 = sourceX / textureWidth;
        float u1 = (sourceX + sourceWidth) / textureWidth;
        float vTop = 1.0f - sourceY / textureHeight;
        float vBottom = 1.0f - (sourceY + sourceHeight) / textureHeight;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        Matrix4f matrix = guiGraphics.pose().last().pose();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(matrix, x, y, 0.0f).setUv(u0, vTop);
        buffer.addVertex(matrix, x, y + height, 0.0f).setUv(u0, vBottom);
        buffer.addVertex(matrix, x + width, y + height, 0.0f).setUv(u1, vBottom);
        buffer.addVertex(matrix, x + width, y, 0.0f).setUv(u1, vTop);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    record Snapshot(long renderedFrames, long fallbackFrames, long reallocations,
            int width, int height, boolean disabled) {
    }
}
