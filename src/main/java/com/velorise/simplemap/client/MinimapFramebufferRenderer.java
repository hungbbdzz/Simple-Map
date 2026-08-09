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
import com.velorise.simplemap.client.cave.CaveLayerBand;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.minimap.MinimapService;
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
 * <p>The expensive page-table/atlas replay is cached in a north-up physical-pixel
 * texture with a logical 64-pixel guard band around a 256-pixel content window.
 * The target is multiplied by Minecraft GUI scale so one logical HUD pixel never
 * collapses several map texels before composition. Player movement inside that
 * guard band is handled by UV translation,
 * while player yaw is applied only to the final one-quad HUD composition. Camera
 * rotation therefore no longer replays all map pages or performs framebuffer
 * state readbacks every frame.</p>
 */
final class MinimapFramebufferRenderer {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int TARGET_SIZE = 384;
    // 64-pixel guard band around a 256-pixel content window. The target is
    // 384x384, so movement can travel roughly twice as far before a
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
    private TextureTarget frontTarget;
    private TextureTarget backTarget;
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
    private int renderedTargetSize;
    private int renderedContentSize;
    private int renderedOverscan;
    private long renderedCoverageScore;
    /** Exact HUD composition used by live overlays in the current render call. */
    private boolean compositionValid;
    private int compositionX;
    private int compositionY;
    private int compositionSize;
    private float compositionDisplaySpan;
    private float compositionSourceX;
    private float compositionSourceY;
    private int compositionTargetSize;
    private int compositionContentSize;
    private boolean compositionRotated;
    private float compositionYaw;
    private long redrawFrames;
    private long reuseFrames;
    private long coalescedFrames;
    private long fallbackFrames;
    private long reallocations;
    /** Last fully composed front target; never replaced by a cold/empty candidate. */
    private boolean frontFrameValid;
    /** Bounded retry gate while a new projection has no drawable pages yet. */
    private long nextColdRedrawAttemptNanos;

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
        compositionValid = false;
        if (permanentlyDisabled || size <= 0 || pixelsPerBlock <= 0.0f) return false;
        RenderSystem.assertOnRenderThreadOrInit();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return false;

        try {
            double guiScale = Math.max(1.0, minecraft.getWindow().getGuiScale());
            int targetSize = MapRenderScalePolicy.physicalPixels(TARGET_SIZE, guiScale);
            int contentSize = MapRenderScalePolicy.physicalPixels(CONTENT_SIZE, guiScale);
            int overscan = Math.max(1, (targetSize - contentSize) / 2);
            ensureTarget(targetSize);
            if (frontTarget == null || backTarget == null) return false;

            float coverage = rotateWithPlayer ? ROTATED_COVERAGE : 1.0f;
            float displaySpan = size * coverage;
            // CONTENT_SIZE maps to the final logical display span. Raster geometry
            // uses the physical content size, while overlay sizing remains in the
            // same logical ratio as the HUD.
            float targetScale = MapRenderScalePolicy.physicalPixelsPerBlock(
                    pixelsPerBlock, guiScale)
                    * (CONTENT_SIZE / Math.max(1.0f, displaySpan));
            float fixedOverlayScale = contentSize / Math.max(1.0f, displaySpan);
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
                            targetScale, overscan - 1.0f);
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
            boolean redrawRequested =
                    decision == RetainedMinimapFramePolicy.Decision.REDRAW_HARD
                    || decision == RetainedMinimapFramePolicy.Decision.REDRAW_STREAMING;
            boolean lastGoodCompatible = canDisplayLastGood(
                    sessionId, dimension, targetSize);
            if (redrawRequested
                    && !RetainedMinimapHandoffPolicy.shouldAttemptRedraw(
                            lastGoodCompatible, nowNanos,
                            nextColdRedrawAttemptNanos)) {
                // Keep displaying the last complete target while the new projection
                // is still cold. This is the loaded/loading handoff used by Xaero;
                // direct fallback is reserved for the true first frame or GL failure.
                reuseFrames++;
                coalescedFrames++;
            } else if (redrawRequested) {
                boolean retainPrevious = canRetainPreviousFrame(sessionId,
                        dimension, caveProjection, caveLayerY, targetScale,
                        snappedX, snappedZ);
                RedrawResult redraw = redrawTarget(guiGraphics, minecraft,
                        targetSize, contentSize, overscan, snappedX, snappedZ,
                        targetScale, targetScale, fixedOverlayScale, caveActive,
                        caveLayerY, partialTick, retainPrevious);
                if (!redraw.success()) {
                    if (RetainedMinimapHandoffPolicy.retainLastGood(
                            lastGoodCompatible, false)) {
                        nextColdRedrawAttemptNanos =
                                RetainedMinimapHandoffPolicy.nextAttemptNanos(
                                        nowNanos);
                        reuseFrames++;
                        coalescedFrames++;
                    } else {
                        framePolicy.invalidate();
                        fallbackFrames++;
                        return false;
                    }
                } else if (!RetainedMinimapHandoffPolicy
                        .canPublishSuccessfulRedraw(lastGoodCompatible,
                                redraw.retainedUnderlayDrawn(),
                                redraw.coldCoverageReady())) {
                    /*
                     * Only a genuinely cold authority switch (projection/scale/large
                     * teleport) waits for complete hierarchy coverage. For ordinary
                     * same-authority streaming redraws the back target already begins
                     * with the full last-good front frame, then overwrites pages that
                     * advanced. Rejecting that complete composite because one known M4
                     * root/branch was not resident froze PASS120 at 13 redraws while
                     * reuse/coalescing continued for thousands of frames.
                     */
                    nextColdRedrawAttemptNanos =
                            RetainedMinimapHandoffPolicy.nextAttemptNanos(nowNanos);
                    reuseFrames++;
                    coalescedFrames++;
                } else {
                    renderedSnappedX = snappedX;
                    renderedSnappedZ = snappedZ;
                    renderedAnchorPixelX = snappedPixelX;
                    renderedAnchorPixelZ = snappedPixelZ;
                    renderedTargetScale = targetScale;
                    renderedSessionId = sessionId;
                    renderedDimension = dimension;
                    renderedCaveProjection = caveProjection;
                    renderedCaveLayerY = caveLayerY;
                    renderedTargetSize = targetSize;
                    renderedContentSize = contentSize;
                    renderedOverscan = overscan;
                    renderedCoverageScore = redraw.coverageScore();
                    swapTargets();
                    frontFrameValid = true;
                    nextColdRedrawAttemptNanos = 0L;
                    framePolicy.commit(nowNanos);
                    MinimapService.getInstance().markLastGood(
                            Math.max(contentRevision, pixelRevision));
                    redrawFrames++;
                }
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
            float sourceX = clampSource((float) (renderedOverscan + sourceOffsetX),
                    renderedTargetSize, renderedContentSize);
            float sourceY = clampSource((float) (renderedOverscan + sourceOffsetY),
                    renderedTargetSize, renderedContentSize);
            drawTarget(guiGraphics, minecraft, x, y, size, displaySpan,
                    sourceX, sourceY, renderedTargetSize, renderedContentSize,
                    rotateWithPlayer, partialTick);
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

    /**
     * Projects a world point through the exact retained minimap composition that is
     * currently on screen. This is intentionally a read-only transform: navigation
     * never becomes part of the retained texture, but its HUD marker uses the same
     * snapped source anchor, crop, physical/logical scale and final yaw as terrain.
     * That removes the one-pixel relative jitter caused by independently rounding a
     * mathematically equivalent live transform.
     */
    HudPoint projectWorldToHud(double worldX, double worldZ) {
        if (!compositionValid || !frontFrameValid || renderedTargetScale <= 0.0f
                || compositionContentSize <= 0 || compositionTargetSize <= 0) {
            return null;
        }

        double targetX = compositionTargetSize * 0.5
                + (worldX - renderedSnappedX) * renderedTargetScale;
        double targetY = compositionTargetSize * 0.5
                + (worldZ - renderedSnappedZ) * renderedTargetScale;
        double sourceCenterX = compositionSourceX + compositionContentSize * 0.5;
        double sourceCenterY = compositionSourceY + compositionContentSize * 0.5;
        double logicalPerTargetPixel = compositionDisplaySpan
                / Math.max(1.0, compositionContentSize);
        double localX = (targetX - sourceCenterX) * logicalPerTargetPixel;
        double localY = (targetY - sourceCenterY) * logicalPerTargetPixel;

        if (compositionRotated) {
            double angle = Math.toRadians(-compositionYaw - 180.0f);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double rotatedX = localX * cos - localY * sin;
            double rotatedY = localX * sin + localY * cos;
            localX = rotatedX;
            localY = rotatedY;
        }
        return new HudPoint(
                (float) (compositionX + compositionSize * 0.5 + localX),
                (float) (compositionY + compositionSize * 0.5 + localY));
    }

    record HudPoint(float x, float y) { }

    Snapshot snapshot() {
        return new Snapshot(redrawFrames, reuseFrames, coalescedFrames,
                fallbackFrames, reallocations,
                frontTarget == null ? 0 : frontTarget.width,
                frontTarget == null ? 0 : frontTarget.height,
                permanentlyDisabled);
    }

    private RedrawResult redrawTarget(GuiGraphics guiGraphics, Minecraft minecraft,
            int targetSize, int contentSize, int overscan,
            double snappedX, double snappedZ, float targetScale,
            float policyScale, float fixedOverlayScale, boolean caveActive,
            int caveLayerY, float partialTick, boolean retainPrevious) {
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
                    caveActive ? 0.05f : 0.035f,
                    1.0f);
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
                    new Matrix4f().setOrtho(0.0f, targetSize, targetSize, 0.0f,
                            -1000.0f, 1000.0f),
                    VertexSorting.ORTHOGRAPHIC_Z);

            boolean underlayDrawn = retainPrevious
                    && drawRetainedUnderlay(snappedX, snappedZ, targetScale, targetSize);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().setIdentity();
            try {
                // Keep the retained texture north-up. Yaw is applied to the final
                // composition quad, so looking around never rebuilds this target.
                MapDrawResult result = MapRenderer.getInstance().drawMapOffscreen(
                        guiGraphics, targetSize, targetSize,
                        snappedX, snappedZ, targetScale, policyScale,
                        false, false, partialTick, fixedOverlayScale);
                guiGraphics.flush();
                if (!result.drewAnyMapContent() && !underlayDrawn) {
                    return RedrawResult.FAILED;
                }
                long newCoverage = coverageScore(result);
                if (underlayDrawn) newCoverage = Math.max(newCoverage,
                        renderedCoverageScore);
                // A compatible retained underlay is already complete by
                // construction. Only cold redraws need the expensive hierarchy
                // readiness walk before they are eligible to become the front frame.
                boolean coldCoverageReady = underlayDrawn || (caveActive
                        ? MapRenderer.getInstance()
                                .caveMinimapRetainedCoverageReady(
                                        CaveMode.isFullView(minecraft),
                                        caveLayerY, snappedX, snappedZ,
                                        targetScale, targetSize)
                        : MapRenderer.getInstance()
                                .surfaceMinimapRetainedCoverageReady(
                                        snappedX, snappedZ, targetScale, targetSize));
                return new RedrawResult(true, newCoverage, coldCoverageReady,
                        underlayDrawn);
            } finally {
                guiGraphics.pose().popPose();
            }
            // Return occurs from the content block above after coverage is known.
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
        renderedTargetSize = 0;
        renderedContentSize = 0;
        renderedOverscan = 0;
        renderedCoverageScore = 0L;
        frontFrameValid = false;
        nextColdRedrawAttemptNanos = 0L;
        compositionValid = false;
        compositionX = compositionY = compositionSize = 0;
        compositionDisplaySpan = compositionSourceX = compositionSourceY = 0.0f;
        compositionTargetSize = compositionContentSize = 0;
        compositionRotated = false;
        compositionYaw = 0.0f;
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

    void resetFailureState() {
        permanentlyDisabled = false;
        failureLogged = false;
        nextColdRedrawAttemptNanos = 0L;
        framePolicy.invalidate();
    }

    private void ensureTarget(int targetSize) {
        if (frontTarget != null && backTarget != null
                && frontTarget.width == targetSize
                && frontTarget.height == targetSize
                && backTarget.width == targetSize
                && backTarget.height == targetSize) return;
        destroy();
        frontTarget = createTarget(targetSize);
        backTarget = createTarget(targetSize);
        reallocations += 2L;
        framePolicy.invalidate();
    }

    private static TextureTarget createTarget(int targetSize) {
        TextureTarget created = new TextureTarget(targetSize, targetSize,
                false, Minecraft.ON_OSX);
        // Density reduction happens in the shared LOD tree. Keep both halves of
        // the Xaero-style front/loading pair pixel-exact.
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
        created.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        return created;
    }

    private void swapTargets() {
        TextureTarget previousFront = frontTarget;
        frontTarget = backTarget;
        backTarget = previousFront;
    }

    private boolean canDisplayLastGood(long sessionId, String dimension,
            int targetSize) {
        return frontFrameValid && frontTarget != null
                && renderedSessionId == sessionId
                && renderedDimension != null
                && renderedDimension.equals(dimension)
                && renderedTargetSize == targetSize;
    }

    private boolean canRetainPreviousFrame(long sessionId, String dimension,
            int caveProjection, int caveLayerY, float targetScale,
            double snappedX, double snappedZ) {
        if (frontTarget == null || renderedTargetScale <= 0.0f) return false;
        if (renderedSessionId != sessionId || renderedDimension == null
                || !renderedDimension.equals(dimension)) return false;
        if (renderedCaveProjection != caveProjection
                || Float.floatToIntBits(renderedTargetScale)
                        != Float.floatToIntBits(targetScale)) return false;
        if (caveProjection == 1
                && !CaveLayerBand.same(CaveView.LAYERED,
                        renderedCaveLayerY, caveLayerY)) return false;
        double shiftX = (renderedSnappedX - snappedX) * targetScale;
        double shiftZ = (renderedSnappedZ - snappedZ) * targetScale;
        return renderedTargetSize > 0
                && Math.abs(shiftX) < renderedTargetSize
                && Math.abs(shiftZ) < renderedTargetSize;
    }

    /**
     * Copies the previous complete minimap into the loading framebuffer before
     * replaying newly available pages. This mirrors Xaero MinimapWriter's
     * loadedBlocks/loadingBlocks handoff: missing pages never punch holes in the
     * visible frame, while exact current pages overwrite the retained underlay.
     */
    private boolean drawRetainedUnderlay(double snappedX, double snappedZ,
            float targetScale, int targetSize) {
        if (frontTarget == null) return false;
        float shiftX = (float) ((renderedSnappedX - snappedX) * targetScale);
        float shiftZ = (float) ((renderedSnappedZ - snappedZ) * targetScale);
        float left = shiftX;
        float top = shiftZ;
        float right = left + renderedTargetSize;
        float bottom = top + renderedTargetSize;

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

    private static float clampSource(float value, int targetSize, int contentSize) {
        return Math.max(0.0f, Math.min(targetSize - contentSize, value));
    }


    private static long coverageScore(MapDrawResult result) {
        if (result == null) return 0L;
        return result.exactPagesDrawn() * 16L
                + result.branchNodesDrawn() * 4L
                + result.legacyFallbacksDrawn();
    }

    private record RedrawResult(boolean success, long coverageScore,
            boolean coldCoverageReady, boolean retainedUnderlayDrawn) {
        private static final RedrawResult FAILED =
                new RedrawResult(false, 0L, false, false);
    }

    record Snapshot(long redrawFrames, long reuseFrames,
            long coalescedFrames, long fallbackFrames, long reallocations,
            int width, int height, boolean disabled) { }

    private void drawTarget(GuiGraphics guiGraphics, Minecraft minecraft,
            int x, int y, int size, float displaySpan,
            float sourceX, float sourceY, int targetSize, int contentSize,
            boolean rotateWithPlayer, float partialTick) {
        float u0 = sourceX / targetSize;
        float u1 = (sourceX + contentSize) / targetSize;
        float vTop = 1.0f - sourceY / targetSize;
        float vBottom = 1.0f - (sourceY + contentSize) / targetSize;

        // Preserve the already-buffered minimap frame/border before direct upload.
        guiGraphics.flush();
        guiGraphics.enableScissor(x, y, x + size, y + size);
        float yaw = 0.0f;
        if (rotateWithPlayer && minecraft.player != null) {
            yaw = net.minecraft.util.Mth.rotLerp(partialTick,
                    minecraft.player.yRotO, minecraft.player.getYRot());
        }
        compositionX = x;
        compositionY = y;
        compositionSize = size;
        compositionDisplaySpan = displaySpan;
        compositionSourceX = sourceX;
        compositionSourceY = sourceY;
        compositionTargetSize = targetSize;
        compositionContentSize = contentSize;
        compositionRotated = rotateWithPlayer;
        compositionYaw = yaw;
        compositionValid = true;

        guiGraphics.pose().pushPose();
        try {
            guiGraphics.pose().translate(x + size * 0.5f,
                    y + size * 0.5f, 0.0f);
            if (rotateWithPlayer && minecraft.player != null) {
                guiGraphics.pose().mulPose(
                        Axis.ZP.rotationDegrees(-yaw - 180.0f));
            }
            float half = displaySpan * 0.5f;
            Matrix4f matrix = guiGraphics.pose().last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, frontTarget.getColorTextureId());
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
