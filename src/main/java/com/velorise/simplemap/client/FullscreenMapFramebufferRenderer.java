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
import com.velorise.simplemap.client.cave.CaveLayerBand;
import com.velorise.simplemap.client.cave.CaveScreenSpacePolicy;
import com.velorise.simplemap.client.cave.CaveView;
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
 * <p>The target is allocated in physical framebuffer pixels and reuses a guarded
 * screen-space snapshot. World bounds and demand remain in logical GUI space, but
 * Surface raster density/LOD uses real pixels per block, while Cave LOD follows
 * logical user zoom so it matches source admission. This keeps the retained target
 * sharp without making GUI scale change Cave hierarchy semantics.</p>
 */
final class FullscreenMapFramebufferRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final FullscreenMapFramebufferRenderer INSTANCE =
            new FullscreenMapFramebufferRenderer();
    /**
     * Logical GUI pixels retained around every viewport edge. Ninety-six pixels
     * cover normal drag/momentum while streaming revisions are frozen, reducing
     * anchor handoffs without returning to the area cost of the old 128px halo.
     */
    private static final int OVERSCAN = 96;
    /** Only the near halo is admitted as visible demand; the outer guard is reuse. */
    private static final int DEMAND_OVERSCAN = 32;
    private static final float TRANSLATION_REUSE_LIMIT = OVERSCAN - 1.0f;
    /** Exact page-table replay is cheap and should expose new publications quickly. */
    private static final long EXACT_STREAMING_REDRAW_NANOS = 125_000_000L;
    /** Coarse raw-atlas plans remain more expensive to rebuild. */
    private static final long COARSE_STREAMING_REDRAW_NANOS = 200_000_000L;
    private static final long PRESSURE_STREAMING_REDRAW_NANOS = 320_000_000L;

    private final RetainedFullscreenFramePolicy framePolicy =
            new RetainedFullscreenFramePolicy();
    /** Last complete framebuffer currently visible to the user. */
    private TextureTarget frontTarget;
    /** Loading framebuffer. It is never exposed until terrain replay succeeds. */
    private TextureTarget backTarget;
    private boolean frontFrameValid;
    private long nextColdRedrawAttemptNanos;
    private int renderedTargetWidth;
    private int renderedTargetHeight;
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
    private int compositionFilter = -1;

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
            float partialTick, boolean viewportInteracting) {
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

        double guiScale = Math.max(1.0, minecraft.getWindow().getGuiScale());
        MapRenderScalePolicy.Scales density =
                MapRenderScalePolicy.fullscreenFbo(renderPixelsPerBlock, guiScale);
        float physicalRenderScale = density.renderPixelsPerBlock();
        float physicalDisplayScale = MapRenderScalePolicy.physicalPixelsPerBlock(
                displayPixelsPerBlock, guiScale);
        int physicalViewportWidth = MapRenderScalePolicy.physicalPixels(width, guiScale);
        int physicalViewportHeight = MapRenderScalePolicy.physicalPixels(height, guiScale);
        int physicalOverscan = MapRenderScalePolicy.physicalPixels(OVERSCAN, guiScale);
        int targetWidth = physicalViewportWidth + physicalOverscan * 2;
        int targetHeight = physicalViewportHeight + physicalOverscan * 2;
        if (maximumTextureSize <= 0) {
            maximumTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        }
        if (targetWidth > maximumTextureSize || targetHeight > maximumTextureSize) {
            fallbackFrames++;
            return false;
        }

        try {
            ensureTargets(targetWidth, targetHeight);
            if (frontTarget == null || backTarget == null) {
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

            double worldPerPixel = 1.0 / Math.max(0.0001, physicalRenderScale);
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
                            == Float.floatToIntBits(physicalRenderScale)
                    && RetainedFullscreenFramePolicy.withinTranslationGuard(
                            centerX, centerZ, renderedAnchorX, renderedAnchorZ,
                            physicalRenderScale, physicalOverscan - 1.0f);
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
                    targetWidth, targetHeight, physicalRenderScale,
                    MapRenderer.getInstance().minimapBrightnessBucket(
                            minecraft, partialTick, caveActive),
                    MapConfig.minimapNightMode);

            float policyScale = density.policyPixelsPerBlock();
            // Cave LOD is selected from stable logical user zoom. Physical density is
            // still used to rasterize a sharp retained target and for Surface policy.
            float cavePolicyScale = renderPixelsPerBlock;
            boolean coarseAuthority = caveActive
                    ? CaveScreenSpacePolicy.branchOnly(
                            cavePolicyScale, MapRequestLane.FULLSCREEN)
                    : MapRegionLodPolicy.targetLevel(policyScale) > 0;
            long redrawInterval = MapPerformanceGovernor.getInstance().underPressure()
                    ? PRESSURE_STREAMING_REDRAW_NANOS
                    : (coarseAuthority ? COARSE_STREAMING_REDRAW_NANOS
                            : EXACT_STREAMING_REDRAW_NANOS);
            long nowNanos = System.nanoTime();
            RetainedFullscreenFramePolicy.Decision decision = framePolicy.decision(
                    nowNanos, redrawInterval, viewportInteracting);

            boolean redrawRequested =
                    decision == RetainedFullscreenFramePolicy.Decision.REDRAW_HARD
                    || decision == RetainedFullscreenFramePolicy.Decision.REDRAW_STREAMING;
            boolean lastGoodCompatible = canDisplayLastGood(
                    sessionId, dimension, targetWidth, targetHeight);
            if (redrawRequested
                    && !RetainedMinimapHandoffPolicy.shouldAttemptRedraw(
                            lastGoodCompatible, nowNanos,
                            nextColdRedrawAttemptNanos)) {
                // Xaero-style loaded/loading isolation: a cold projection never
                // replaces the complete framebuffer currently on screen.
                reuseFrames++;
                coalescedFrames++;
            } else if (redrawRequested) {
                boolean retainPrevious = canRetainPreviousFrame(sessionId,
                        dimension, caveProjection, caveLayerY, physicalRenderScale,
                        anchorX, anchorZ, targetWidth, targetHeight);
                if (!redrawTarget(guiGraphics, minecraft, targetWidth, targetHeight,
                        anchorX, anchorZ, physicalRenderScale,
                        policyScale, cavePolicyScale, partialTick, caveActive,
                        retainPrevious)) {
                    if (RetainedMinimapHandoffPolicy.retainLastGood(
                            lastGoodCompatible, false)) {
                        nextColdRedrawAttemptNanos =
                                RetainedMinimapHandoffPolicy.nextAttemptNanos(nowNanos);
                        reuseFrames++;
                        coalescedFrames++;
                    } else {
                        framePolicy.invalidate();
                        fallbackFrames++;
                        return false;
                    }
                } else {
                    renderedAnchorX = anchorX;
                    renderedAnchorZ = anchorZ;
                    renderedAnchorPixelX = anchorPixelX;
                    renderedAnchorPixelZ = anchorPixelZ;
                    renderedScale = physicalRenderScale;
                    renderedSessionId = sessionId;
                    renderedDimension = dimension;
                    renderedCaveProjection = caveProjection;
                    renderedCaveLayerY = caveLayerY;
                    renderedTargetWidth = targetWidth;
                    renderedTargetHeight = targetHeight;
                    swapTargets();
                    frontFrameValid = true;
                    nextColdRedrawAttemptNanos = 0L;
                    framePolicy.commit(nowNanos);
                    redrawFrames++;
                }
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
                    physicalViewportWidth, physicalRenderScale, physicalDisplayScale);
            float sourceHeight = RetainedViewportComposition.sourceSpan(
                    physicalViewportHeight, physicalRenderScale, physicalDisplayScale);
            float rawSourceX = RetainedViewportComposition.sourceOrigin(
                    physicalOverscan, physicalViewportWidth, sourceWidth,
                    centerX, renderedAnchorX, renderedScale);
            float rawSourceY = RetainedViewportComposition.sourceOrigin(
                    physicalOverscan, physicalViewportHeight, sourceHeight,
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
            RetainedViewportComposition.PixelAlignedAxis alignedX =
                    RetainedViewportComposition.pixelAlignedAxis(
                            sourceX, sourceWidth, physicalViewportWidth, targetWidth);
            RetainedViewportComposition.PixelAlignedAxis alignedY =
                    RetainedViewportComposition.pixelAlignedAxis(
                            sourceY, sourceHeight, physicalViewportHeight, targetHeight);
            if (!alignedX.valid() || !alignedY.valid()) {
                fallbackFrames++;
                return false;
            }
            configureCompositionFilter(physicalDisplayScale,
                    alignedX.sourceSpan(), alignedY.sourceSpan(),
                    physicalViewportWidth, physicalViewportHeight);
            float destinationX = viewportX
                    + alignedX.destinationOffsetPixels() / (float) guiScale;
            float destinationY = viewportY
                    + alignedY.destinationOffsetPixels() / (float) guiScale;
            float destinationWidth = alignedX.destinationSpanPixels()
                    / (float) guiScale;
            float destinationHeight = alignedY.destinationSpanPixels()
                    / (float) guiScale;
            drawTarget(guiGraphics, viewportX, viewportY, width, height,
                    destinationX, destinationY, destinationWidth, destinationHeight,
                    alignedX.sourceOrigin(), alignedY.sourceOrigin(),
                    alignedX.sourceSpan(), alignedY.sourceSpan(),
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
            float renderPixelsPerBlock, float policyPixelsPerBlock,
            float cavePolicyPixelsPerBlock, float partialTick,
            boolean caveActive, boolean retainPrevious) {
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
            backTarget.bindWrite(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            backTarget.setClearColor(caveActive ? 0.03f : 0.025f,
                    caveActive ? 0.04f : 0.03f,
                    caveActive ? 0.05f : 0.035f, 1.0f);
            backTarget.clear(Minecraft.ON_OSX);
            // RenderTarget.clear() binds and then unbinds its framebuffer in
            // Minecraft 1.21.1. Rebind before replaying atlas quads; otherwise
            // telemetry reports content while the retained texture remains only
            // the near-black clear colour.
            backTarget.bindWrite(true);
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

            boolean underlayDrawn = retainPrevious
                    && drawRetainedUnderlay(anchorX, anchorZ, renderPixelsPerBlock,
                            targetWidth, targetHeight);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().setIdentity();
            try {
                MapDrawResult result = MapRenderer.getInstance().drawFullscreenMapOffscreen(
                        guiGraphics, targetWidth, targetHeight,
                        anchorX, anchorZ, renderPixelsPerBlock, policyPixelsPerBlock,
                        cavePolicyPixelsPerBlock, partialTick);
                guiGraphics.flush();
                if (!result.drewAnyMapContent() && !underlayDrawn) {
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
        nextColdRedrawAttemptNanos = 0L;
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
        renderedTargetWidth = 0;
        renderedTargetHeight = 0;
        frontFrameValid = false;
        nextColdRedrawAttemptNanos = 0L;
        compositionFilter = -1;
        TextureTarget oldFront = frontTarget;
        TextureTarget oldBack = backTarget;
        frontTarget = null;
        backTarget = null;
        if (oldFront == null && oldBack == null) return;
        if (RenderSystem.isOnRenderThreadOrInit()) {
            if (oldFront != null) oldFront.destroyBuffers();
            if (oldBack != null && oldBack != oldFront) oldBack.destroyBuffers();
        } else {
            RenderSystem.recordRenderCall(() -> {
                if (oldFront != null) oldFront.destroyBuffers();
                if (oldBack != null && oldBack != oldFront) oldBack.destroyBuffers();
            });
        }
    }

    Snapshot snapshot() {
        return new Snapshot(renderedFrames, redrawFrames, reuseFrames,
                coalescedFrames, fallbackFrames, reallocations,
                frontTarget == null ? 0 : frontTarget.width,
                frontTarget == null ? 0 : frontTarget.height, permanentlyDisabled);
    }

    private void ensureTargets(int width, int height) {
        if (frontTarget != null && backTarget != null
                && frontTarget.width == width && frontTarget.height == height
                && backTarget.width == width && backTarget.height == height) return;
        destroy();
        frontTarget = createTarget(width, height);
        backTarget = createTarget(width, height);
        reallocations += 2L;
        framePolicy.invalidate();
    }

    private static TextureTarget createTarget(int width, int height) {
        TextureTarget created = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        created.setFilterMode(GL11.GL_NEAREST);
        GlStateManager._bindTexture(created.getColorTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S,
                GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T,
                GL12.GL_CLAMP_TO_EDGE);
        created.setClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        return created;
    }

    private void swapTargets() {
        TextureTarget previousFront = frontTarget;
        frontTarget = backTarget;
        backTarget = previousFront;
    }

    private boolean canDisplayLastGood(long sessionId, String dimension,
            int width, int height) {
        return frontFrameValid && frontTarget != null
                && renderedSessionId == sessionId
                && renderedDimension != null && renderedDimension.equals(dimension)
                && renderedTargetWidth == width && renderedTargetHeight == height;
    }

    private boolean canRetainPreviousFrame(long sessionId, String dimension,
            int caveProjection, int caveLayerY, float renderScale,
            double anchorX, double anchorZ, int width, int height) {
        if (!canDisplayLastGood(sessionId, dimension, width, height)
                || renderedScale <= 0.0f) return false;
        if (renderedCaveProjection != caveProjection
                || Float.floatToIntBits(renderedScale)
                        != Float.floatToIntBits(renderScale)) return false;
        if (caveProjection == 1
                && !CaveLayerBand.same(CaveView.LAYERED,
                        renderedCaveLayerY, caveLayerY)) return false;
        double shiftX = (renderedAnchorX - anchorX) * renderScale;
        double shiftZ = (renderedAnchorZ - anchorZ) * renderScale;
        return Math.abs(shiftX) < width && Math.abs(shiftZ) < height;
    }

    /**
     * Copies the last complete fullscreen composition into the loading target.
     * Newly available exact/branch pages overwrite it; unresolved pages therefore
     * cannot punch black holes into a frame that was already complete.
     */
    private boolean drawRetainedUnderlay(double anchorX, double anchorZ,
            float renderScale, int targetWidth, int targetHeight) {
        if (frontTarget == null || !frontFrameValid) return false;
        float shiftX = (float) ((renderedAnchorX - anchorX) * renderScale);
        float shiftZ = (float) ((renderedAnchorZ - anchorZ) * renderScale);
        float left = shiftX;
        float top = shiftZ;
        float right = left + renderedTargetWidth;
        float bottom = top + renderedTargetHeight;

        RenderSystem.disableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, frontTarget.getColorTextureId());
        Matrix4f identity = new Matrix4f();
        BufferBuilder buffer = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.addVertex(identity, left, top, 0.0f).setUv(0.0f, 1.0f);
        buffer.addVertex(identity, left, bottom, 0.0f).setUv(0.0f, 0.0f);
        buffer.addVertex(identity, right, bottom, 0.0f).setUv(1.0f, 0.0f);
        buffer.addVertex(identity, right, top, 0.0f).setUv(1.0f, 1.0f);
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        return true;
    }

    /**
     * The map renderer has already selected a density-matched exact/branch/root LOD
     * before drawing into this target. Sampling that completed target linearly a
     * second time blends real cave/terrain pixels with neighbouring black unknown
     * pixels, creating the visible grey veil and erasing one-pixel passages. Keep the
     * final composition nearest-filtered at every zoom. Camera snapping and the LOD
     * hierarchy handle far-zoom stability; this stage must not blur their result.
     */
    private void configureCompositionFilter(float displayPixelsPerBlock,
            float sourceWidth, float sourceHeight, int width, int height) {
        if (frontTarget == null || compositionFilter == GL11.GL_NEAREST) return;
        frontTarget.setFilterMode(GL11.GL_NEAREST);
        if (backTarget != null) backTarget.setFilterMode(GL11.GL_NEAREST);
        GlStateManager._bindTexture(frontTarget.getColorTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
                GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER,
                GL11.GL_NEAREST);
        compositionFilter = GL11.GL_NEAREST;
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

    private void drawTarget(GuiGraphics guiGraphics,
            int clipX, int clipY, int clipWidth, int clipHeight,
            float x, float y, float width, float height,
            float sourceX, float sourceY,
            float sourceWidth, float sourceHeight,
            int textureWidth, int textureHeight) {
        float u0 = sourceX / textureWidth;
        float u1 = (sourceX + sourceWidth) / textureWidth;
        float vTop = 1.0f - sourceY / textureHeight;
        float vBottom = 1.0f - (sourceY + sourceHeight) / textureHeight;

        guiGraphics.flush();
        guiGraphics.enableScissor(clipX, clipY,
                clipX + clipWidth, clipY + clipHeight);
        try {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, frontTarget.getColorTextureId());
            Matrix4f matrix = guiGraphics.pose().last().pose();
            BufferBuilder buffer = Tesselator.getInstance().begin(
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buffer.addVertex(matrix, x, y, 0.0f).setUv(u0, vTop);
            buffer.addVertex(matrix, x, y + height, 0.0f).setUv(u0, vBottom);
            buffer.addVertex(matrix, x + width, y + height, 0.0f)
                    .setUv(u1, vBottom);
            buffer.addVertex(matrix, x + width, y, 0.0f).setUv(u1, vTop);
            BufferUploader.drawWithShader(buffer.buildOrThrow());
        } finally {
            guiGraphics.disableScissor();
        }
    }

    record Snapshot(long renderedFrames, long redrawFrames, long reuseFrames,
            long coalescedFrames, long fallbackFrames, long reallocations,
            int width, int height, boolean disabled) {
    }
}
