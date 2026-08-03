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
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

/**
 * Retained screen-space composition for the fullscreen map.
 *
 * <p>The previous implementation allocated in physical window pixels, multiplied
 * the world scale by GUI scale and replayed every atlas batch on every visual
 * frame. It was consequently quarantined after producing clear-only output below
 * 0.5 px/block. This implementation renders in the same logical GUI coordinate
 * system as {@link MapScreen}, retains a wide guard band, translates pan motion by
 * source UV and coalesces atlas publications to a bounded cadence.</p>
 */
final class FullscreenMapFramebufferRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final FullscreenMapFramebufferRenderer INSTANCE =
            new FullscreenMapFramebufferRenderer();
    /**
     * Logical GUI pixels retained around every viewport edge. Sixty-four pixels
     * cover normal drag motion until the next 5 Hz streaming refresh without
     * inflating a 0.35x fullscreen load by the ~50% area cost of the old 128px
     * halo.
     */
    private static final int OVERSCAN = 64;
    /** Only the near halo is admitted as visible demand; the outer guard is reuse. */
    private static final int DEMAND_OVERSCAN = 32;
    private static final float TRANSLATION_REUSE_LIMIT = OVERSCAN - 1.0f;
    private static final long SURFACE_STREAMING_REDRAW_NANOS = 250_000_000L;
    private static final long CAVE_STREAMING_REDRAW_NANOS = 250_000_000L;
    private static final long PANNING_STREAMING_REDRAW_NANOS = 250_000_000L;
    private static final long PRESSURE_STREAMING_REDRAW_NANOS = 400_000_000L;

    private final RetainedFullscreenFramePolicy framePolicy =
            new RetainedFullscreenFramePolicy();
    private TextureTarget target;
    private boolean permanentlyDisabled;
    private boolean failureLogged;
    private double renderedAnchorX;
    private double renderedAnchorZ;
    private long renderedAnchorPixelX;
    private long renderedAnchorPixelZ;
    private float renderedScale;
    private long renderedSessionId;
    private String renderedDimension;
    private int renderedCaveProjection;
    private int renderedCaveLayerY = Integer.MIN_VALUE;
    private long renderedFrames;
    private long redrawFrames;
    private long reuseFrames;
    private long coalescedFrames;
    private long fallbackFrames;
    private long reallocations;
    private int maximumTextureSize;

    static FullscreenMapFramebufferRenderer getInstance() {
        return INSTANCE;
    }

    private FullscreenMapFramebufferRenderer() {
    }

    static boolean shouldUse(Minecraft minecraft, float pixelsPerBlock) {
        return minecraft != null && minecraft.level != null
                && pixelsPerBlock > 0.0f && Float.isFinite(pixelsPerBlock);
    }

    static int demandOverscanPixels() {
        return DEMAND_OVERSCAN;
    }

    /**
     * @return true when a retained target was composited; false asks the caller to
     *         use the direct cache-only renderer for this frame.
     */
    boolean render(GuiGraphics guiGraphics, int viewportX, int viewportY,
            int width, int height, double centerX, double centerZ,
            float renderPixelsPerBlock, float displayPixelsPerBlock,
            float partialTick) {
        if (permanentlyDisabled || width <= 0 || height <= 0
                || renderPixelsPerBlock <= 0.0f
                || displayPixelsPerBlock <= 0.0f
                || !Float.isFinite(renderPixelsPerBlock)
                || !Float.isFinite(displayPixelsPerBlock)) {
            fallbackFrames++;
            return false;
        }
        RenderSystem.assertOnRenderThreadOrInit();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            fallbackFrames++;
            return false;
        }

        int targetWidth = width + OVERSCAN * 2;
        int targetHeight = height + OVERSCAN * 2;
        if (maximumTextureSize <= 0) {
            maximumTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        }
        if (targetWidth > maximumTextureSize || targetHeight > maximumTextureSize) {
            fallbackFrames++;
            return false;
        }

        try {
            ensureTarget(targetWidth, targetHeight);
            if (target == null) {
                fallbackFrames++;
                return false;
            }

            boolean caveActive = CaveMode.isActive(minecraft);
            boolean fullCaveView = caveActive && CaveMode.isFullView(minecraft);
            int caveLayerY = caveActive && !fullCaveView
                    ? CaveMode.getLayerY(minecraft) : Integer.MIN_VALUE;
            int caveProjection = !caveActive ? 0 : fullCaveView ? 2 : 1;
            MapResidencyManager residency = MapResidencyManager.getInstance();
            long contentRevision = caveActive
                    ? (fullCaveView
                            ? FullCaveTextureManager.getInstance().contentRevision()
                            : CaveTextureManager.getInstance().contentRevision())
                    : residency.surfaceContentRevision();
            long pixelRevision = caveActive
                    ? residency.cavePixelRevision()
                    : residency.surfacePixelRevision();
            RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
            long sessionId = stamp == null ? 0L : stamp.sessionId();
            long sourceGeneration = stamp == null ? 0L : stamp.sourceGeneration();
            long styleGeneration = stamp == null ? 0L : stamp.styleGeneration();
            long projectionGeneration = stamp == null
                    ? 0L : stamp.projectionGeneration();
            String dimension = MapManager.getInstance().getDimensionCacheKey();

            double worldPerPixel = 1.0 / Math.max(0.0001, renderPixelsPerBlock);
            long candidatePixelX = Math.round(centerX / worldPerPixel);
            long candidatePixelZ = Math.round(centerZ / worldPerPixel);
            double candidateX = candidatePixelX * worldPerPixel;
            double candidateZ = candidatePixelZ * worldPerPixel;

            boolean reuseAnchor = renderedScale > 0.0f
                    && renderedSessionId == sessionId
                    && renderedDimension != null
                    && renderedDimension.equals(dimension)
                    && renderedCaveProjection == caveProjection
                    && renderedCaveLayerY == caveLayerY
                    && Float.floatToIntBits(renderedScale)
                            == Float.floatToIntBits(renderPixelsPerBlock)
                    && RetainedFullscreenFramePolicy.withinTranslationGuard(
                            centerX, centerZ, renderedAnchorX, renderedAnchorZ,
                            renderPixelsPerBlock, TRANSLATION_REUSE_LIMIT);
            long anchorPixelX = reuseAnchor
                    ? renderedAnchorPixelX : candidatePixelX;
            long anchorPixelZ = reuseAnchor
                    ? renderedAnchorPixelZ : candidatePixelZ;
            double anchorX = reuseAnchor ? renderedAnchorX : candidateX;
            double anchorZ = reuseAnchor ? renderedAnchorZ : candidateZ;

            framePolicy.prepare(sessionId, sourceGeneration,
                    styleGeneration, projectionGeneration,
                    residency.topologyRevision(), contentRevision, pixelRevision,
                    dimension, caveProjection, caveLayerY,
                    anchorPixelX, anchorPixelZ,
                    targetWidth, targetHeight, renderPixelsPerBlock,
                    MapRenderer.getInstance().minimapBrightnessBucket(
                            minecraft, partialTick, caveActive),
                    MapConfig.minimapNightMode);

            double offsetX = reuseAnchor
                    ? Math.abs((centerX - renderedAnchorX)
                            * renderPixelsPerBlock) : 0.0;
            double offsetZ = reuseAnchor
                    ? Math.abs((centerZ - renderedAnchorZ)
                            * renderPixelsPerBlock) : 0.0;
            boolean panningInsideGuard = Math.max(offsetX, offsetZ) >= 1.0;
            long redrawInterval = MapPerformanceGovernor.getInstance().underPressure()
                    ? PRESSURE_STREAMING_REDRAW_NANOS
                    : panningInsideGuard ? PANNING_STREAMING_REDRAW_NANOS
                    : caveActive ? CAVE_STREAMING_REDRAW_NANOS
                            : SURFACE_STREAMING_REDRAW_NANOS;
            long nowNanos = System.nanoTime();
            RetainedFullscreenFramePolicy.Decision decision = framePolicy.decision(
                    nowNanos, redrawInterval);

            if (decision == RetainedFullscreenFramePolicy.Decision.REDRAW_HARD
                    || decision == RetainedFullscreenFramePolicy.Decision.REDRAW_STREAMING) {
                if (!redrawTarget(guiGraphics, minecraft, targetWidth, targetHeight,
                        anchorX, anchorZ, renderPixelsPerBlock,
                        partialTick, caveActive)) {
                    framePolicy.invalidate();
                    fallbackFrames++;
                    return false;
                }
                renderedAnchorX = anchorX;
                renderedAnchorZ = anchorZ;
                renderedAnchorPixelX = anchorPixelX;
                renderedAnchorPixelZ = anchorPixelZ;
                renderedScale = renderPixelsPerBlock;
                renderedSessionId = sessionId;
                renderedDimension = dimension;
                renderedCaveProjection = caveProjection;
                renderedCaveLayerY = caveLayerY;
                framePolicy.commit(nowNanos);
                redrawFrames++;
            } else {
                reuseFrames++;
                if (decision == RetainedFullscreenFramePolicy.Decision.DEFER_STREAMING) {
                    coalescedFrames++;
                }
            }

            // Opening/zoom presentation is applied by sampling a centred sub-rect
            // from the terrain target. MapScreen can therefore animate 1.2x -> 1.0x
            // without changing the terrain scale, rebuilding the plan or reallocating
            // the FBO on every visual frame.
            float sourceWidth = RetainedViewportComposition.sourceSpan(
                    width, renderPixelsPerBlock, displayPixelsPerBlock);
            float sourceHeight = RetainedViewportComposition.sourceSpan(
                    height, renderPixelsPerBlock, displayPixelsPerBlock);
            float rawSourceX = RetainedViewportComposition.sourceOrigin(
                    OVERSCAN, width, sourceWidth,
                    centerX, renderedAnchorX, renderedScale);
            float rawSourceY = RetainedViewportComposition.sourceOrigin(
                    OVERSCAN, height, sourceHeight,
                    centerZ, renderedAnchorZ, renderedScale);
            if (!Float.isFinite(sourceWidth) || !Float.isFinite(sourceHeight)
                    || !Float.isFinite(rawSourceX) || !Float.isFinite(rawSourceY)
                    || sourceWidth <= 0.0f || sourceHeight <= 0.0f
                    || sourceWidth > targetWidth || sourceHeight > targetHeight) {
                fallbackFrames++;
                return false;
            }
            float sourceX = clampSource(rawSourceX, targetWidth - sourceWidth);
            float sourceY = clampSource(rawSourceY, targetHeight - sourceHeight);
            drawTarget(guiGraphics, viewportX, viewportY, width, height,
                    sourceX, sourceY, sourceWidth, sourceHeight,
                    targetWidth, targetHeight);
            renderedFrames++;
            return true;
        } catch (Throwable throwable) {
            permanentlyDisabled = true;
            fallbackFrames++;
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[SimpleMap] Retained fullscreen framebuffer failed; "
                        + "using direct rendering", throwable);
            }
            destroy();
            return false;
        }
    }

    private boolean redrawTarget(GuiGraphics guiGraphics, Minecraft minecraft,
            int targetWidth, int targetHeight, double anchorX, double anchorZ,
            float pixelsPerBlock, float partialTick, boolean caveActive) {
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        int[] oldViewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, oldViewport);
        int oldFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        org.joml.Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix();
        boolean oldStencilEnabled = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        boolean oldDepthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldScissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int[] oldScissor = oldScissorEnabled ? new int[4] : null;
        if (oldScissorEnabled) {
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, oldScissor);
        }

        try {
            guiGraphics.flush();
            target.bindWrite(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            target.setClearColor(caveActive ? 0.03f : 0.025f,
                    caveActive ? 0.04f : 0.03f,
                    caveActive ? 0.05f : 0.035f, 1.0f);
            target.clear(Minecraft.ON_OSX);
            // RenderTarget.clear() binds and then unbinds its framebuffer in
            // Minecraft 1.21.1. Rebind before replaying atlas quads; otherwise
            // telemetry reports content while the retained texture remains only
            // the near-black clear colour.
            target.bindWrite(true);
            // The GUI render pass owns a non-trivial global model-view
            // matrix. Off-screen vertices are already transformed by GuiGraphics'
            // pose, so retaining the window model-view moves them outside this
            // framebuffer. Use identity for the retained pass only.
            modelViewStack.identity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0.0f, targetWidth, targetHeight, 0.0f,
                            -1000.0f, 1000.0f),
                    VertexSorting.ORTHOGRAPHIC_Z);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().setIdentity();
            try {
                MapDrawResult result = MapRenderer.getInstance().drawFullscreenMapOffscreen(
                        guiGraphics, targetWidth, targetHeight,
                        anchorX, anchorZ, pixelsPerBlock, pixelsPerBlock,
                        partialTick);
                guiGraphics.flush();
                if (!result.drewAnyMapContent()) {
                    return false;
                }
            } finally {
                guiGraphics.pose().popPose();
            }
            // Commit only a framebuffer that contains terrain. The caller's
            // direct cache-only fallback remains visible while publication fills
            // a cold viewport; the next content revision retries retained replay.
            return true;
        } finally {
            modelViewStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            restoreTarget(oldFramebuffer, oldViewport, oldProjection, oldSorting);
            restoreCapability(GL11.GL_STENCIL_TEST, oldStencilEnabled);
            restoreCapability(GL11.GL_DEPTH_TEST, oldDepthEnabled);
            restoreScissor(oldScissorEnabled, oldScissor);
        }
    }

    void resetFailureState() {
        permanentlyDisabled = false;
        failureLogged = false;
        framePolicy.invalidate();
    }

    void destroy() {
        framePolicy.invalidate();
        renderedAnchorX = 0.0;
        renderedAnchorZ = 0.0;
        renderedAnchorPixelX = 0L;
        renderedAnchorPixelZ = 0L;
        renderedScale = 0.0f;
        renderedSessionId = 0L;
        renderedDimension = null;
        renderedCaveProjection = 0;
        renderedCaveLayerY = Integer.MIN_VALUE;
        if (target == null) return;
        TextureTarget old = target;
        target = null;
        if (RenderSystem.isOnRenderThreadOrInit()) old.destroyBuffers();
        else RenderSystem.recordRenderCall(old::destroyBuffers);
    }

    Snapshot snapshot() {
        return new Snapshot(renderedFrames, redrawFrames, reuseFrames,
                coalescedFrames, fallbackFrames, reallocations,
                target == null ? 0 : target.width,
                target == null ? 0 : target.height, permanentlyDisabled);
    }

    private void ensureTarget(int width, int height) {
        if (target != null && target.width == width && target.height == height) return;
        destroy();
        target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        // Match Xaero's terrain sampling: crisp nearest-neighbour magnification,
        // but linear minification when many world pixels collapse into one screen
        // pixel. A single GL_LINEAR mode blurred both close and medium zoom.
        target.setFilterMode(GL11.GL_NEAREST);
        GlStateManager._bindTexture(target.getColorTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                GL12.GL_CLAMP_TO_EDGE);
        target.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        reallocations++;
        framePolicy.invalidate();
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
        if (enabled && box != null) {
            GL11.glScissor(box[0], box[1], box[2], box[3]);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    private static float clampSource(float value, float maximum) {
        return Math.max(0.0f, Math.min(Math.max(0.0f, maximum), value));
    }

    private void drawTarget(GuiGraphics guiGraphics, int x, int y,
            int width, int height, float sourceX, float sourceY,
            float sourceWidth, float sourceHeight,
            int textureWidth, int textureHeight) {
        float u0 = sourceX / textureWidth;
        float u1 = (sourceX + sourceWidth) / textureWidth;
        float vTop = 1.0f - sourceY / textureHeight;
        float vBottom = 1.0f - (sourceY + sourceHeight) / textureHeight;

        guiGraphics.flush();
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

    record Snapshot(long renderedFrames, long redrawFrames, long reuseFrames,
            long coalescedFrames, long fallbackFrames, long reallocations,
            int width, int height, boolean disabled) {
    }
}
