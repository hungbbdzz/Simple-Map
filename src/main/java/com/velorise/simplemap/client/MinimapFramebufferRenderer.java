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
import com.mojang.math.Axis;
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
 * Fixed-resolution retained minimap composition target.
 *
 * <p>The expensive page-table/atlas replay is cached in a north-up 384x384
 * texture with a 64-pixel guard band around a 256-pixel content window. Player
 * movement inside that guard band is handled by UV translation,
 * while player yaw is applied only to the final one-quad HUD composition. Camera
 * rotation therefore no longer replays all map pages or performs framebuffer
 * state readbacks every frame.</p>
 */
final class MinimapFramebufferRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int TARGET_SIZE = 384;
    // 64-pixel guard band around a 256-pixel content window. The target
    // remains 320x320, but movement can travel roughly twice as far before a
    // recenter/redraw is required.
    private static final int OVERSCAN = 64;
    private static final int CONTENT_SIZE = 256;
    /** Leave one target pixel as a filtering/rounding safety margin. */
    private static final float TRANSLATION_REUSE_LIMIT = OVERSCAN - 1.0f;
    /** Matches the direct renderer's rotated viewport expansion. */
    private static final float ROTATED_COVERAGE = 1.415f;
    private static final long SURFACE_STREAMING_REDRAW_NANOS = 125_000_000L;
    private static final long CAVE_STREAMING_REDRAW_NANOS = 200_000_000L;
    private static final long MOVING_STREAMING_REDRAW_NANOS = 250_000_000L;
    private static final long PRESSURE_STREAMING_REDRAW_NANOS = 250_000_000L;
    private static final MinimapFramebufferRenderer INSTANCE =
            new MinimapFramebufferRenderer();

    private final RetainedMinimapFramePolicy framePolicy =
            new RetainedMinimapFramePolicy();
    private TextureTarget target;
    private boolean permanentlyDisabled;
    private boolean failureLogged;
    private double renderedSnappedX;
    private double renderedSnappedZ;
    private long renderedAnchorPixelX;
    private long renderedAnchorPixelZ;
    private float renderedTargetScale;
    private long renderedSessionId;
    private String renderedDimension;
    private int renderedCaveProjection;
    private int renderedCaveLayerY = Integer.MIN_VALUE;
    private long redrawFrames;
    private long reuseFrames;
    private long coalescedFrames;
    private long fallbackFrames;
    private long reallocations;

    static MinimapFramebufferRenderer getInstance() {
        return INSTANCE;
    }

    static float demandCoverageFactor(boolean rotateWithPlayer) {
        float compositionCoverage = rotateWithPlayer ? ROTATED_COVERAGE : 1.0f;
        // The full target, including its guard band, is rendered from atlas pages.
        return compositionCoverage * (TARGET_SIZE / (float) CONTENT_SIZE);
    }


    private MinimapFramebufferRenderer() {
    }

    /**
     * @return true when the retained target was composed successfully. Sparse or
     * empty source coverage remains a valid retained frame while tick-side loading
     * continues; only framebuffer/render failures use the direct fallback.
     */
    boolean render(GuiGraphics guiGraphics, int x, int y, int size,
            double centerX, double centerZ, float pixelsPerBlock,
            boolean rotateWithPlayer, float partialTick) {
        if (permanentlyDisabled || size <= 0 || pixelsPerBlock <= 0.0f) return false;
        RenderSystem.assertOnRenderThreadOrInit();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return false;

        try {
            ensureTarget();
            if (target == null) return false;

            float coverage = rotateWithPlayer ? ROTATED_COVERAGE : 1.0f;
            float displaySpan = size * coverage;
            // CONTENT_SIZE maps to the final pre-rotation display span. For a
            // rotating square this is its diagonal, exactly matching the direct
            // renderer's sqrt(2)-expanded world bounds.
            float targetScale = pixelsPerBlock
                    * (CONTENT_SIZE / Math.max(1.0f, displaySpan));
            float fixedOverlayScale = CONTENT_SIZE / Math.max(1.0f, displaySpan);
            double worldPerTargetPixel = 1.0 / Math.max(0.0001, targetScale);
            long candidatePixelX = Math.round(centerX / worldPerTargetPixel);
            long candidatePixelZ = Math.round(centerZ / worldPerTargetPixel);
            double candidateX = candidatePixelX * worldPerTargetPixel;
            double candidateZ = candidatePixelZ * worldPerTargetPixel;

            boolean caveActive = CaveMode.isActive(minecraft);
            boolean fullCaveView = caveActive && CaveMode.isFullView(minecraft);
            int caveLayerY = caveActive && !fullCaveView
                    ? CaveMode.getLayerY(minecraft)
                    : Integer.MIN_VALUE;
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

            boolean reuseAnchor = renderedTargetScale > 0.0f
                    && renderedSessionId == sessionId
                    && renderedDimension != null
                    && renderedDimension.equals(dimension)
                    && renderedCaveProjection == caveProjection
                    && renderedCaveLayerY == caveLayerY
                    && Float.floatToIntBits(renderedTargetScale)
                            == Float.floatToIntBits(targetScale)
                    && RetainedMinimapFramePolicy.withinTranslationGuard(
                            centerX, centerZ, renderedSnappedX, renderedSnappedZ,
                            targetScale, TRANSLATION_REUSE_LIMIT);
            long snappedPixelX = reuseAnchor
                    ? renderedAnchorPixelX : candidatePixelX;
            long snappedPixelZ = reuseAnchor
                    ? renderedAnchorPixelZ : candidatePixelZ;
            double snappedX = reuseAnchor ? renderedSnappedX : candidateX;
            double snappedZ = reuseAnchor ? renderedSnappedZ : candidateZ;

            framePolicy.prepare(sessionId, sourceGeneration,
                    styleGeneration, projectionGeneration,
                    residency.topologyRevision(), contentRevision, pixelRevision,
                    WaypointManager.getInstance().visualRevision(),
                    dimension,
                    caveProjection, caveLayerY,
                    snappedPixelX, snappedPixelZ,
                    targetScale, fixedOverlayScale,
                    MapRenderer.getInstance().minimapBrightnessBucket(
                            minecraft, partialTick, caveActive),
                    MapConfig.minimapNightMode, MapConfig.waypointScale,
                    MapConfig.playerPointerColor);

            long nowNanos = System.nanoTime();
            double liveOffsetX = reuseAnchor
                    ? Math.abs((centerX - renderedSnappedX) * targetScale) : 0.0;
            double liveOffsetZ = reuseAnchor
                    ? Math.abs((centerZ - renderedSnappedZ) * targetScale) : 0.0;
            boolean movingInsideGuard = Math.max(liveOffsetX, liveOffsetZ) >= 1.0;
            long streamingInterval = MapPerformanceGovernor.getInstance().underPressure()
                    ? PRESSURE_STREAMING_REDRAW_NANOS
                    : movingInsideGuard ? MOVING_STREAMING_REDRAW_NANOS
                    : caveActive ? CAVE_STREAMING_REDRAW_NANOS
                            : SURFACE_STREAMING_REDRAW_NANOS;
            RetainedMinimapFramePolicy.Decision decision = framePolicy.decision(
                    nowNanos, streamingInterval);
            if (decision == RetainedMinimapFramePolicy.Decision.REDRAW_HARD
                    || decision == RetainedMinimapFramePolicy.Decision.REDRAW_STREAMING) {
                if (!redrawTarget(guiGraphics, minecraft, snappedX, snappedZ,
                        targetScale, fixedOverlayScale, caveActive, partialTick)) {
                    framePolicy.invalidate();
                    fallbackFrames++;
                    return false;
                }
                renderedSnappedX = snappedX;
                renderedSnappedZ = snappedZ;
                renderedAnchorPixelX = snappedPixelX;
                renderedAnchorPixelZ = snappedPixelZ;
                renderedTargetScale = targetScale;
                renderedSessionId = sessionId;
                renderedDimension = dimension;
                renderedCaveProjection = caveProjection;
                renderedCaveLayerY = caveLayerY;
                framePolicy.commit(nowNanos);
                redrawFrames++;
            } else {
                reuseFrames++;
                if (decision == RetainedMinimapFramePolicy.Decision.DEFER_STREAMING) {
                    coalescedFrames++;
                }
            }

            double sourceOffsetX = (centerX - renderedSnappedX)
                    * renderedTargetScale;
            double sourceOffsetY = (centerZ - renderedSnappedZ)
                    * renderedTargetScale;
            float sourceX = clampSource((float) (OVERSCAN + sourceOffsetX));
            float sourceY = clampSource((float) (OVERSCAN + sourceOffsetY));
            drawTarget(guiGraphics, minecraft, x, y, size, displaySpan,
                    sourceX, sourceY, rotateWithPlayer, partialTick);
            return true;
        } catch (Throwable throwable) {
            permanentlyDisabled = true;
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[SimpleMap] Retained minimap framebuffer failed; "
                        + "using direct rendering", throwable);
            }
            fallbackFrames++;
            destroy();
            return false;
        }
    }

    Snapshot snapshot() {
        return new Snapshot(redrawFrames, reuseFrames, coalescedFrames,
                fallbackFrames, reallocations, target == null ? 0 : target.width,
                target == null ? 0 : target.height, permanentlyDisabled);
    }

    private boolean redrawTarget(GuiGraphics guiGraphics, Minecraft minecraft,
            double snappedX, double snappedZ, float targetScale,
            float fixedOverlayScale, boolean caveActive, float partialTick) {
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
                    caveActive ? 0.05f : 0.035f,
                    1.0f);
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
                    new Matrix4f().setOrtho(0.0f, TARGET_SIZE, TARGET_SIZE, 0.0f,
                            -1000.0f, 1000.0f),
                    VertexSorting.ORTHOGRAPHIC_Z);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().setIdentity();
            try {
                // Keep the retained texture north-up. Yaw is applied to the final
                // composition quad, so looking around never rebuilds this target.
                MapDrawResult result = MapRenderer.getInstance().drawMapOffscreen(
                        guiGraphics, TARGET_SIZE, TARGET_SIZE,
                        snappedX, snappedZ, targetScale,
                        false, false, partialTick, fixedOverlayScale);
                guiGraphics.flush();
                if (!result.drewAnyMapContent()) {
                    return false;
                }
            } finally {
                guiGraphics.pose().popPose();
            }
            // A bound framebuffer is not proof of terrain coverage. Until at
            // least one exact/branch/legacy page is present, let the caller use
            // the direct cache-only renderer so overlays never sit over a
            // permanently committed black target.
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

    void destroy() {
        framePolicy.invalidate();
        renderedSnappedX = 0.0;
        renderedSnappedZ = 0.0;
        renderedAnchorPixelX = 0L;
        renderedAnchorPixelZ = 0L;
        renderedTargetScale = 0.0f;
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

    void resetFailureState() {
        permanentlyDisabled = false;
        failureLogged = false;
        framePolicy.invalidate();
    }

    private void ensureTarget() {
        if (target != null && target.width == TARGET_SIZE
                && target.height == TARGET_SIZE) return;
        destroy();
        target = new TextureTarget(TARGET_SIZE, TARGET_SIZE,
                false, Minecraft.ON_OSX);
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
        target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
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

    private static float clampSource(float value) {
        return Math.max(0.0f, Math.min(TARGET_SIZE - CONTENT_SIZE, value));
    }

    record Snapshot(long redrawFrames, long reuseFrames,
            long coalescedFrames, long fallbackFrames, long reallocations,
            int width, int height, boolean disabled) { }

    private void drawTarget(GuiGraphics guiGraphics, Minecraft minecraft,
            int x, int y, int size, float displaySpan,
            float sourceX, float sourceY,
            boolean rotateWithPlayer, float partialTick) {
        float u0 = sourceX / TARGET_SIZE;
        float u1 = (sourceX + CONTENT_SIZE) / TARGET_SIZE;
        float vTop = 1.0f - sourceY / TARGET_SIZE;
        float vBottom = 1.0f - (sourceY + CONTENT_SIZE) / TARGET_SIZE;

        // Preserve the already-buffered minimap frame/border before direct upload.
        guiGraphics.flush();
        guiGraphics.enableScissor(x, y, x + size, y + size);
        guiGraphics.pose().pushPose();
        try {
            guiGraphics.pose().translate(x + size * 0.5f,
                    y + size * 0.5f, 0.0f);
            if (rotateWithPlayer && minecraft.player != null) {
                float yaw = net.minecraft.util.Mth.rotLerp(partialTick,
                        minecraft.player.yRotO, minecraft.player.getYRot());
                guiGraphics.pose().mulPose(
                        Axis.ZP.rotationDegrees(-yaw - 180.0f));
            }
            float half = displaySpan * 0.5f;
            Matrix4f matrix = guiGraphics.pose().last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, target.getColorTextureId());
            BufferBuilder buffer = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION_TEX);
            buffer.addVertex(matrix, -half, -half, 0.0f).setUv(u0, vTop);
            buffer.addVertex(matrix, -half, half, 0.0f).setUv(u0, vBottom);
            buffer.addVertex(matrix, half, half, 0.0f).setUv(u1, vBottom);
            buffer.addVertex(matrix, half, -half, 0.0f).setUv(u1, vTop);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        } finally {
            guiGraphics.pose().popPose();
            guiGraphics.disableScissor();
        }
    }
}
