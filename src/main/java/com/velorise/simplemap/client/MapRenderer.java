package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.CaveScreenSpacePolicy;
import com.velorise.simplemap.client.cave.SurfaceLodTree;
import com.velorise.simplemap.client.gpu.TileKey;
import com.velorise.simplemap.client.renderer.LodSelector;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

import java.util.LinkedHashSet;
import java.util.Set;

public class MapRenderer {
    private static final MapRenderer INSTANCE = new MapRenderer();
    private static final float NIGHT_MIN_BRIGHTNESS = 0.22f;

    private int lastSurfaceHierarchyLevel = -1;
    private int lastCaveHierarchyLevel = -1;
    private CachedPlan fullscreenPlan;
    private CachedPlan fullscreenFallbackPlan;
    private CachedPlan minimapPlan;
    private CachedPlan minimapStagingPlan;
    private long minimapStagingSinceNanos;


    public static MapRenderer getInstance() {
        return INSTANCE;
    }

    private MapRenderer() {
    }

    /**
     * Renders the map in the specified viewport on the screen.
     *
     * @param guiGraphics The GUI graphics instance
     * @param viewportX   Left edge of the viewport
     * @param viewportY   Top edge of the viewport
     * @param width       Width of the viewport
     * @param height      Height of the viewport
     * @param centerX     World X coordinate to center the map on
     * @param centerZ     World Z coordinate to center the map on
     * @param scale       Zoom scale factor (pixels per block)
     * @param drawPlayer  Whether to render the player marker at the player's actual
     *                    position
     */
    public void drawMap(GuiGraphics guiGraphics, int viewportX, int viewportY, int width, int height,
            double centerX, double centerZ, float scale, boolean drawPlayer, boolean rotateWithPlayer,
            boolean isMinimap, double mouseWorldX, double mouseWorldZ, float partialTick) {
        drawMap(guiGraphics, viewportX, viewportY, width, height, centerX, centerZ, scale,
                drawPlayer, rotateWithPlayer, isMinimap, mouseWorldX, mouseWorldZ,
                partialTick, false);
    }

    /**
     * The trailing interaction flag is retained for source compatibility only.
     * Visible scan, IO and publication are tick-side and continue during panning.
     */
    public void drawMap(GuiGraphics guiGraphics, int viewportX, int viewportY, int width, int height,
            double centerX, double centerZ, float scale, boolean drawPlayer, boolean rotateWithPlayer,
            boolean isMinimap, double mouseWorldX, double mouseWorldZ, float partialTick,
            boolean cachedOnly) {
        drawMapInternal(guiGraphics, viewportX, viewportY, width, height, centerX, centerZ, scale,
                drawPlayer, rotateWithPlayer, isMinimap, mouseWorldX, mouseWorldZ, partialTick,
                cachedOnly, true, 1.0f, scale);
    }

    /**
     * Off-screen minimap path. The framebuffer already clips to its own extent, so
     * GuiGraphics' window-relative scissor must stay disabled here.
     */
    MapDrawResult drawMapOffscreen(GuiGraphics guiGraphics, int width, int height,
            double centerX, double centerZ, float scale, boolean drawPlayer,
            boolean rotateWithPlayer, float partialTick, float fixedOverlayScale) {
        return drawMapInternal(guiGraphics, 0, 0, width, height, centerX, centerZ, scale,
                drawPlayer, rotateWithPlayer, true, 0.0, 0.0, partialTick, false, false,
                fixedOverlayScale, scale);
    }

    /**
     * Fullscreen surface composition entry used by the pixel-aligned framebuffer.
     * It retains fullscreen scheduling/LOD semantics while disabling only the
     * window-relative scissor because the framebuffer clips to its own extent.
     */
    MapDrawResult drawFullscreenMapOffscreen(GuiGraphics guiGraphics, int width, int height,
            double centerX, double centerZ, float renderPixelsPerBlock,
            float policyPixelsPerBlock, boolean drawPlayer,
            double mouseWorldX, double mouseWorldZ, float partialTick) {
        return drawMapInternal(guiGraphics, 0, 0, width, height, centerX, centerZ,
                renderPixelsPerBlock, drawPlayer, false, false, mouseWorldX, mouseWorldZ,
                partialTick, false, false, 1.0f, policyPixelsPerBlock);
    }

    private MapDrawResult drawMapInternal(GuiGraphics guiGraphics, int viewportX, int viewportY,
            int width, int height, double centerX, double centerZ, float scale,
            boolean drawPlayer, boolean rotateWithPlayer, boolean isMinimap,
            double mouseWorldX, double mouseWorldZ, float partialTick,
            boolean cachedOnly, boolean manageScissor, float fixedOverlayScale,
            float policyScale) {

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return MapDrawResult.EMPTY;

        RenderStats renderStats = new RenderStats();
        MapResidencyManager.beginRender(isMinimap
                ? MapRequestLane.MINIMAP : MapRequestLane.FULLSCREEN);
        try {

        boolean caveMode = CaveMode.isActive(mc);
        boolean fullCaveView = caveMode && CaveMode.isFullView(mc);
        int caveLayerY = caveMode ? CaveMode.getLayerY(mc) : Integer.MIN_VALUE;
        // Renderer is strictly cache-only. Scans, IO, CPU builds and GPU
        // publication are scheduled from MapViewportCoordinator on client tick.
        if (caveMode && !fullCaveView) {
            CaveMapManager.getInstance().setActiveLayer(caveLayerY);
        }

        // Reset shader color to prevent other GUI elements from tinting the map
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        // 2. Enable scissor test only for direct window rendering. Off-screen
        // targets use the framebuffer bounds; window-relative GUI scissor coordinates
        // would otherwise clip the 512x512 target incorrectly.
        if (manageScissor) {
            guiGraphics.enableScissor(viewportX, viewportY, viewportX + width, viewportY + height);
        }

        // 3. Set up the camera transformation:
        // - Translate to the center of the viewport
        poseStack.translate(viewportX + width / 2.0, viewportY + height / 2.0, 0);

        // - Rotate the map coordinate space if requested (so player yaw points UP)
        if (rotateWithPlayer) {
            float playerYaw = net.minecraft.util.Mth.rotLerp(partialTick, mc.player.yRotO, mc.player.getYRot());
            poseStack.mulPose(Axis.ZP.rotationDegrees(-playerYaw - 180.0f));
        }

        // - Scale according to zoom level
        poseStack.scale(scale, scale, 1.0f);
        // - Translate by the negative center coordinates in world space
        poseStack.translate(-centerX, -centerZ, 0);

        // 4. Calculate bounds of visible world coordinates in the viewport
        // Expand bounds by sqrt(2) (1.415x) when rotated to prevent region clipping at
        // corners
        double searchFactor = rotateWithPlayer ? 1.415 : 1.0;
        double halfW = ((width / 2.0) / scale) * searchFactor;
        double halfH = ((height / 2.0) / scale) * searchFactor;
        double minX = centerX - halfW;
        double maxX = centerX + halfW;
        double minZ = centerZ - halfH;
        double maxZ = centerZ + halfH;

        // 5. Determine which 512x512 regions are visible
        // One-block epsilon prevents a region at the exact viewport edge from
        // alternating in/out because of floating-point zoom rounding.
        int minRegionX = (int) Math.floor(minX - 1.0) >> 9;
        int maxRegionX = (int) Math.floor(maxX + 1.0) >> 9;
        int minRegionZ = (int) Math.floor(minZ - 1.0) >> 9;
        int maxRegionZ = (int) Math.floor(maxZ + 1.0) >> 9;
        int minVisiblePageX = Math.floorDiv((int) Math.floor(minX - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxVisiblePageX = Math.floorDiv((int) Math.floor(maxX + 1.0),
                MapPageLayout.PAGE_SIZE);
        int minVisiblePageZ = Math.floorDiv((int) Math.floor(minZ - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxVisiblePageZ = Math.floorDiv((int) Math.floor(maxZ + 1.0),
                MapPageLayout.PAGE_SIZE);
        // The minimap is a small, player-centred hot set. It must always read the
        // exact 64x64 leaves directly; branch availability is never allowed to
        // decide whether the minimap has content.
        int visibleNodeTarget = caveMode ? 96 : 560;
        int hierarchyLevel;
        if (isMinimap) {
            hierarchyLevel = 0;
        } else {
            /*
             * Surface quality follows texel density first, matching Xaero's useful
             * L0/L1/L2/L3 selection. The old viewport-node budget could force a
             * 0.29x view from density-correct L1 to L2/L3 and made the map look soft.
             * Work volume is controlled by sliced demand/admission, not by silently
             * lowering the visible texture density.
             */
            int candidateLevel = caveMode
                    ? MapLodPolicy.branchLevel(policyScale, width, height,
                            searchFactor, visibleNodeTarget)
                    : LodSelector.surfaceLevel(policyScale);
            if (caveMode) {
                hierarchyLevel = MapLodPolicy.stabilizeBranchLevel(
                        candidateLevel, lastCaveHierarchyLevel, policyScale);
                lastCaveHierarchyLevel = hierarchyLevel;
            } else {
                hierarchyLevel = MapLodPolicy.stabilizeBranchLevel(
                        candidateLevel, lastSurfaceHierarchyLevel, policyScale);
                lastSurfaceHierarchyLevel = hierarchyLevel;
            }
        }
        boolean caveBranchOnly = caveMode && !isMinimap
                && CaveScreenSpacePolicy.branchOnly(policyScale, MapRequestLane.FULLSCREEN);
        /*
         * Both visible-map pipelines are anchored to their viewport. The cursor is
         * reserved for coordinate inspection, waypoint interaction and tooltips;
         * it never changes source, exact or branch loading order.
         */
        double attentionX = centerX;
        double attentionZ = centerZ;
        int attentionPageX = Math.floorDiv((int) Math.floor(attentionX),
                MapPageLayout.PAGE_SIZE);
        int attentionPageZ = Math.floorDiv((int) Math.floor(attentionZ),
                MapPageLayout.PAGE_SIZE);

        // Publish viewport to coordinator (tick-side scan/upload, not render-side).
        if (isMinimap) {
            MapViewportCoordinator.getInstance().submitMinimap(minX, maxX, minZ, maxZ, policyScale);
        } else if (!caveMode) {
            // Submit the complete logical viewport. SurfaceDemandController owns
            // far-zoom trimming so render and demand policy cannot trim twice.
            MapViewportCoordinator.getInstance().submitFullscreen(
                    minX, maxX, minZ, maxZ,
                    policyScale, centerX, centerZ, false);
        } else {
            MapViewportCoordinator.getInstance().submitFullscreen(
                    minX, maxX, minZ, maxZ, policyScale,
                    centerX, centerZ, false);
        }

        // Visible data requests are handled by MapViewportCoordinator. Keeping
        // this path free of enqueue loops is what makes dragging/zooming stable.

        // 6. Build or reuse an immutable cache-only render plan. Recursive
        // hierarchy traversal is repeated only when the quantized viewport or
        // global atlas topology changes.
        float mapBrightness = getMapBrightness(mc, partialTick);
        float caveBrightness = getCaveBrightness(mc, partialTick);
        MapTextureManager surfaceTextures = MapTextureManager.getInstance();
        MapOverviewTextureManager overviewTextures = MapOverviewTextureManager.getInstance();
        if (!isMinimap && !caveMode) {
            overviewTextures.setPreferredSurfaceView(policyScale, hierarchyLevel,
                    minVisiblePageX, maxVisiblePageX,
                    minVisiblePageZ, maxVisiblePageZ,
                    attentionPageX, attentionPageZ);
        }
        FullCaveTextureManager fullCaveTextures = FullCaveTextureManager.getInstance();
        CaveTextureManager caveTextures = CaveTextureManager.getInstance();
        surfaceTextures.beginRenderBatch();
        overviewTextures.beginRenderBatch();
        fullCaveTextures.beginRenderBatch();
        caveTextures.beginRenderBatch();
        try {
            MapResidencyManager residency = MapResidencyManager.getInstance();
            long topologyRevision = residency.topologyRevision();
            // Cave plans should react to cave exact/branch publication only. The old
            // global residency revision rebuilt a large cave hierarchy whenever an
            // unrelated Surface, minimap halo or legacy texture changed.
            long contentRevision = caveMode
                    ? (fullCaveView ? fullCaveTextures.contentRevision()
                            : caveTextures.contentRevision())
                    : residency.contentRevision();
            int renderScaleClass = renderScaleClass(caveMode, policyScale);
            // Rendering and loading are both anchored to the viewport.
            int renderAttentionPageX = isMinimap ? attentionPageX
                    : Math.floorDiv((int) Math.floor(centerX), MapPageLayout.PAGE_SIZE);
            int renderAttentionPageZ = isMinimap ? attentionPageZ
                    : Math.floorDiv((int) Math.floor(centerZ), MapPageLayout.PAGE_SIZE);
            PlanKey planKey = new PlanKey(
                    MapManager.getInstance().getDimensionCacheKey(),
                    caveMode, fullCaveView, caveLayerY,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    hierarchyLevel, caveBranchOnly,
                    renderScaleClass, renderAttentionPageX, renderAttentionPageZ,
                    MapConfig.minimapNightMode);
            long nowNanos = System.nanoTime();
            CachedPlan cachedPlan;
            CachedPlan fallbackPlan = null;
            if (isMinimap) {
                cachedPlan = selectMinimapPlan(planKey, caveMode, fullCaveView,
                        caveLayerY, hierarchyLevel, caveBranchOnly, policyScale,
                        minX, maxX, minZ, maxZ,
                        minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                        minVisiblePageX, maxVisiblePageX,
                        minVisiblePageZ, maxVisiblePageZ,
                        renderAttentionPageX, renderAttentionPageZ,
                        surfaceTextures, overviewTextures,
                        fullCaveTextures, caveTextures,
                        topologyRevision, contentRevision, nowNanos);
            } else {
                fallbackPlan = fullscreenFallbackPlan;
                if (fallbackPlan != null
                        && (!compatibleFullscreenFallback(fallbackPlan.key(), planKey)
                                || !fallbackPlan.plan().topologyValid(topologyRevision))) {
                    fallbackPlan = null;
                    fullscreenFallbackPlan = null;
                }
                CachedPlan previousPlan = fullscreenPlan;
                cachedPlan = previousPlan;
                boolean cachedHasPending = cachedPlan != null
                        && cachedPlan.plan().pendingRegions().length > 0;
                if (cachedPlan == null || !cachedPlan.key().equals(planKey)
                        || cachedPlan.contentRevision() != contentRevision
                        || !cachedPlan.plan().valid(topologyRevision, nowNanos,
                                cachedHasPending)) {
                    MapRenderPlan plan = buildRenderPlan(caveMode, fullCaveView,
                            caveLayerY, hierarchyLevel, caveBranchOnly, policyScale,
                            true, false,
                            minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                            minVisiblePageX, maxVisiblePageX,
                            minVisiblePageZ, maxVisiblePageZ,
                            renderAttentionPageX, renderAttentionPageZ,
                            surfaceTextures, overviewTextures,
                            fullCaveTextures, caveTextures);
                    MapPipelineTelemetry.getInstance().recordRenderPlanBuild(
                            plan.quadCount(), plan.batchCount());
                    if (previousPlan != null
                            && compatibleFullscreenFallback(previousPlan.key(), planKey)
                            && previousPlan.plan().topologyValid(topologyRevision)
                            && previousPlan.plan().quadCount() > 0
                            && (plan.quadCount() == 0
                                    || plan.pendingRegions().length > 0)) {
                        // Keep the previous viewport/LOD as a visual underlay while
                        // the target hierarchy warms. World-space vertices remain
                        // correct under the new pose, so overlap survives pan/zoom
                        // instead of flashing to black.
                        fallbackPlan = previousPlan;
                        fullscreenFallbackPlan = previousPlan;
                    }
                    cachedPlan = new CachedPlan(planKey, plan, contentRevision);
                    fullscreenPlan = cachedPlan;
                    if (fullscreenFallbackPlan != null
                            && cachedPlan.plan().quadCount() > 0
                            && cachedPlan.plan().pendingRegions().length == 0) {
                        fallbackPlan = null;
                        fullscreenFallbackPlan = null;
                    }
                } else {
                    MapPipelineTelemetry.getInstance().recordRenderPlanReuse();
                }
            }

            boolean blendWasEnabledForMap = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean mapBlendRequired = caveMode || hierarchyLevel > 0;
            if (mapBlendRequired) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
            }
            if (caveMode) {
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                guiGraphics.fill(minRegionX * 512, minRegionZ * 512,
                        (maxRegionX + 1) * 512, (maxRegionZ + 1) * 512,
                        0xFF080A0C);
                RenderSystem.setShaderColor(caveBrightness, caveBrightness,
                        caveBrightness, 1.0F);
            } else {
                RenderSystem.setShaderColor(mapBrightness, mapBrightness,
                        mapBrightness, 1.0F);
            }
            if (fallbackPlan != null) fallbackPlan.plan().drawBase(guiGraphics);
            cachedPlan.plan().drawBase(guiGraphics);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (mapBlendRequired && !blendWasEnabledForMap) {
                RenderSystem.disableBlend();
            }

            if (!caveMode && MapConfig.minimapNightMode != 0
                    && hierarchyLevel == 0) {
                boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                float glowStrength = MapConfig.minimapNightMode == 2
                        ? 1.0f
                        : Math.max(0.0f, Math.min(1.0f,
                                (1.0f - mapBrightness)
                                        / (1.0f - NIGHT_MIN_BRIGHTNESS)));
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, glowStrength);
                if (fallbackPlan != null) fallbackPlan.plan().drawGlow(guiGraphics);
                cachedPlan.plan().drawGlow(guiGraphics);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                if (!blendWasEnabled) RenderSystem.disableBlend();
            }
            if (!isMinimap) {
                drawLoadingIndicators(guiGraphics,
                        cachedPlan.plan().pendingRegions(), policyScale);
            }
            renderStats.accept(cachedPlan.plan().result());
        } finally {
            guiGraphics.flush();
            caveTextures.endRenderBatch();
            fullCaveTextures.endRenderBatch();
            overviewTextures.endRenderBatch();
            surfaceTextures.endRenderBatch();
        }

        // 6.5. Draw waypoints for the current dimension if enabled
        if (MapConfig.waypointsVisible) {
            java.util.List<WaypointManager.Waypoint> waypoints = WaypointManager.getInstance()
                    .getWaypointsForDimension(MapManager.getInstance().getCurrentDimensionId());
            for (WaypointManager.Waypoint wp : waypoints) {
                boolean isHovered = false;
                if (!isMinimap) {
                    // Waypoint width in world coordinates is ~1.28 * waypointScale. So hover radius
                    // is ~0.64 * waypointScale (increased slightly to 0.8 for easier hovering)
                    double hoverRadius = 0.8 * MapConfig.waypointScale;
                    isHovered = Math.abs(mouseWorldX - wp.x) <= hoverRadius
                            && Math.abs(mouseWorldZ - wp.z) <= hoverRadius;
                }
                drawWaypointMarker(guiGraphics, wp, scale, isMinimap, isHovered);
            }
        }

        // 6.6. Render pin navigation dotted line in world coordinates
        if (MapConfig.pinActive) {
            double playerX = net.minecraft.util.Mth.lerp(partialTick, mc.player.xo, mc.player.getX());
            double playerZ = net.minecraft.util.Mth.lerp(partialTick, mc.player.zo, mc.player.getZ());

            double dx = MapConfig.pinWorldX - playerX;
            double dz = MapConfig.pinWorldZ - playerZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0) {
                double nx = dx / len;
                double nz = dz / len;

                int pointerColor = getActualPointerColor(MapConfig.playerPointerColor);
                int lineColor = isMinimap ? ((pointerColor & 0x00FFFFFF) | 0xCC000000) : pointerColor;

                double approxStep = Math.max(8.0, 6.0 / scale);
                double start = 4.0;
                double end = len - 4.0;

                if (end > start) {
                    int numSteps = (int) Math.round((end - start) / approxStep);
                    double step = numSteps > 0 ? (end - start) / numSteps : 0.0;

                    for (int k = 0; k <= numSteps; k++) {
                        double traveled = start + k * step;
                        double wx = playerX + nx * traveled;
                        double wz = playerZ + nz * traveled;

                        int bx = (int) Math.floor(wx);
                        int bz = (int) Math.floor(wz);

                        guiGraphics.fill(bx, bz, bx + 1, bz + 1, lineColor);
                    }
                }
            }
        }

        // 6.5. Render pin marker if active and within visible bounds
        if (MapConfig.pinActive) {
            if (MapConfig.pinWorldX >= minX && MapConfig.pinWorldX <= maxX &&
                    MapConfig.pinWorldZ >= minZ && MapConfig.pinWorldZ <= maxZ) {

                poseStack.pushPose();
                poseStack.translate(MapConfig.pinWorldX, MapConfig.pinWorldZ, 5);

                float baseFactor = isMinimap ? 1.0f : 2.0f;
                float pinScaleFactor = (baseFactor / scale) * MapConfig.pinScale * fixedOverlayScale;
                poseStack.scale(pinScaleFactor, pinScaleFactor, 1.0f);

                net.minecraft.resources.ResourceLocation redX = net.minecraft.resources.ResourceLocation
                        .fromNamespaceAndPath("minecraft", "textures/map/decorations/red_x.png");
                guiGraphics.blit(redX, -4, -4, 8, 8, 0.0f, 0.0f, 8, 8, 8, 8);

                poseStack.popPose();
            }
        }

        // 7. Render player marker if enabled
        if (drawPlayer) {
            double playerX = net.minecraft.util.Mth.lerp(partialTick, mc.player.xo, mc.player.getX());
            double playerZ = net.minecraft.util.Mth.lerp(partialTick, mc.player.zo, mc.player.getZ());
            float playerYaw = net.minecraft.util.Mth.rotLerp(partialTick, mc.player.yRotO, mc.player.getYRot())
                    + 180.0f;

            drawPlayerMarker(guiGraphics, playerX, playerZ, playerYaw, scale, fixedOverlayScale);
        }

        // 8. Disable scissor and pop pose
        if (manageScissor) guiGraphics.disableScissor();
        poseStack.popPose();
        MapDrawResult result = renderStats.snapshot();
        String renderProjection = isMinimap
                ? (caveMode ? (fullCaveView ? "MINIMAP_CAVE_FULL"
                        : "MINIMAP_CAVE_LAYERED") : "MINIMAP_SURFACE")
                : (!caveMode ? "SURFACE"
                        : (fullCaveView ? "CAVE_FULL" : "CAVE_LAYERED"));
        MapPipelineTelemetry telemetry = MapPipelineTelemetry.getInstance();
        telemetry.recordRenderContext(renderProjection, hierarchyLevel);
        telemetry.recordRenderResult(result);
        return result;
        } finally {
            MapResidencyManager.endRender();
        }
    }

    private CachedPlan selectMinimapPlan(PlanKey planKey,
            boolean caveMode, boolean fullCaveView, int caveLayerY,
            int hierarchyLevel, boolean caveBranchOnly, float scale,
            double minX, double maxX, double minZ, double maxZ,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minVisiblePageX, int maxVisiblePageX,
            int minVisiblePageZ, int maxVisiblePageZ,
            int attentionPageX, int attentionPageZ,
            MapTextureManager surfaceTextures,
            MapOverviewTextureManager overviewTextures,
            FullCaveTextureManager fullCaveTextures,
            CaveTextureManager caveTextures,
            long topologyRevision, long contentRevision, long nowNanos) {
        CachedPlan current = minimapPlan;
        boolean currentSafe = current != null
                && current.plan().topologyValid(topologyRevision)
                && sameMinimapAuthority(current.key(), planKey);
        boolean sameStagingWindow = minimapStagingPlan != null
                && minimapStagingPlan.key().equals(planKey)
                && minimapStagingPlan.plan().topologyValid(topologyRevision);
        boolean stagingMatches = sameStagingWindow
                && minimapStagingPlan.contentRevision() == contentRevision;
        if (!stagingMatches) {
            MapRenderPlan candidate = buildRenderPlan(caveMode, fullCaveView,
                    caveLayerY, hierarchyLevel, caveBranchOnly, scale,
                    false, true,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    minVisiblePageX, maxVisiblePageX,
                    minVisiblePageZ, maxVisiblePageZ,
                    attentionPageX, attentionPageZ,
                    surfaceTextures, overviewTextures,
                    fullCaveTextures, caveTextures);
            minimapStagingPlan = new CachedPlan(planKey, candidate, contentRevision);
            if (!sameStagingWindow) minimapStagingSinceNanos = nowNanos;
            MapPipelineTelemetry.getInstance().recordRenderPlanBuild(
                    candidate.quadCount(), candidate.batchCount());
        }

        CachedPlan staging = minimapStagingPlan;
        int expectedPages = visiblePageCount(minX, maxX, minZ, maxZ);
        int requiredPages = Math.max(1,
                Math.min(expectedPages, (int) Math.ceil(expectedPages * 0.70)));
        int stagedExact = staging.plan().result().exactPagesDrawn();
        boolean authorityChanged = current == null
                || !sameMinimapAuthority(current.key(), planKey);
        boolean ready = stagedExact >= requiredPages
                || (!currentSafe && stagedExact > 0)
                || (stagedExact > 0
                        && nowNanos - minimapStagingSinceNanos >= 350_000_000L);
        if (authorityChanged || !currentSafe || ready) {
            minimapPlan = staging;
            minimapStagingPlan = null;
            minimapStagingSinceNanos = 0L;
            return minimapPlan;
        }
        MapPipelineTelemetry.getInstance().recordRenderPlanReuse();
        return current;
    }

    private static boolean sameMinimapAuthority(PlanKey left, PlanKey right) {
        if (left == null || right == null) return false;
        return left.dimension().equals(right.dimension())
                && left.caveMode() == right.caveMode()
                && left.fullCaveView() == right.fullCaveView()
                && (!left.caveMode() || left.fullCaveView()
                        || left.caveLayerY() == right.caveLayerY());
    }

    private static int visiblePageCount(double minX, double maxX,
            double minZ, double maxZ) {
        int minPageX = Math.floorDiv((int) Math.floor(Math.min(minX, maxX) - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxPageX = Math.floorDiv((int) Math.floor(Math.max(minX, maxX) + 1.0),
                MapPageLayout.PAGE_SIZE);
        int minPageZ = Math.floorDiv((int) Math.floor(Math.min(minZ, maxZ) - 1.0),
                MapPageLayout.PAGE_SIZE);
        int maxPageZ = Math.floorDiv((int) Math.floor(Math.max(minZ, maxZ) + 1.0),
                MapPageLayout.PAGE_SIZE);
        long total = (long) (maxPageX - minPageX + 1)
                * (maxPageZ - minPageZ + 1);
        return (int) Math.max(1L, Math.min(256L, total));
    }

    private static int renderScaleClass(boolean caveMode, float scale) {
        if (!caveMode) return 0;
        int mip = MapLodPolicy.leafMipLevel(scale, 3);
        int partialExactClass = CaveScreenSpacePolicy.exactPagePixels(scale) >= 16.0f
                ? 1 : 0;
        return (mip << 1) | partialExactClass;
    }

    private MapRenderPlan buildRenderPlan(boolean caveMode, boolean fullCaveView,
            int caveLayerY, int hierarchyLevel, boolean caveBranchOnly, float scale,
            boolean collectPending, boolean centerOutTraversal,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minVisiblePageX, int maxVisiblePageX,
            int minVisiblePageZ, int maxVisiblePageZ,
            int attentionPageX, int attentionPageZ,
            MapTextureManager surfaceTextures,
            MapOverviewTextureManager overviewTextures,
            FullCaveTextureManager fullCaveTextures,
            CaveTextureManager caveTextures) {
        MapRenderPlan.Builder builder = new MapRenderPlan.Builder();
        if (caveMode) {
            Set<Long> drawnRegions = new LinkedHashSet<>();
            CaveHierarchySource source;
            if (fullCaveView) {
                source = new CaveHierarchySource() {
                    @Override
                    public CaveAtlasRegion branch(int level, int nodeX, int nodeZ) {
                        return fullCaveTextures.peekBranchRegion(level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean hasBranchData(int level, int nodeX, int nodeZ) {
                        return fullCaveTextures.hasBranchData(level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean hasResidentPageInNode(int level, int nodeX, int nodeZ) {
                        return fullCaveTextures.hasResidentPageInNode(level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean allowExact(int globalPageX, int globalPageZ) {
                        return centerOutTraversal
                                || fullCaveTextures.allowFullscreenExact(
                                        globalPageX, globalPageZ);
                    }

                    @Override
                    public CaveAtlasRegion page(int rx, int rz, int px, int pz, float drawScale) {
                        return fullCaveTextures.peekPageRegion(rx, rz, px, pz, drawScale);
                    }
                };
            } else {
                source = new CaveHierarchySource() {
                    @Override
                    public CaveAtlasRegion branch(int level, int nodeX, int nodeZ) {
                        return caveTextures.peekBranchRegion(caveLayerY, level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean hasBranchData(int level, int nodeX, int nodeZ) {
                        return caveTextures.hasBranchData(caveLayerY, level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean hasResidentPageInNode(int level, int nodeX, int nodeZ) {
                        return caveTextures.hasResidentPageInNode(caveLayerY, level, nodeX, nodeZ);
                    }

                    @Override
                    public boolean allowExact(int globalPageX, int globalPageZ) {
                        return centerOutTraversal
                                || caveTextures.allowFullscreenExact(caveLayerY,
                                        globalPageX, globalPageZ);
                    }

                    @Override
                    public CaveAtlasRegion page(int rx, int rz, int px, int pz, float drawScale) {
                        return caveTextures.peekPageRegion(caveLayerY, rx, rz, px, pz, drawScale);
                    }
                };
            }
            collectCaveHierarchy(builder, source,
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    minVisiblePageX, maxVisiblePageX,
                    minVisiblePageZ, maxVisiblePageZ,
                    hierarchyLevel, scale, caveBranchOnly,
                    attentionPageX, attentionPageZ, centerOutTraversal,
                    drawnRegions);
            if (collectPending && fullCaveView) {
                FullCaveMapManager manager = FullCaveMapManager.getInstance();
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                        if (!drawnRegions.contains(packRegion(rx, rz))
                                && (manager.hasRegionFile(rx, rz)
                                        || manager.isRegionLoaded(rx, rz))) {
                            builder.pending(rx, rz);
                        }
                    }
                }
            } else if (collectPending) {
                CaveMapManager manager = CaveMapManager.getInstance();
                VerticalCaveArchiveManager archive = VerticalCaveArchiveManager.getInstance();
                for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                    for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                        if (!drawnRegions.contains(packRegion(rx, rz))
                                && (manager.hasRegionFile(rx, rz)
                                        || manager.isRegionLoaded(rx, rz)
                                        || archive.hasRegionData(rx, rz))) {
                            builder.pending(rx, rz);
                        }
                    }
                }
            }
        } else {
            MapManager manager = MapManager.getInstance();
            if (hierarchyLevel > 0) {
                // M4 region-centric coverage is an underlay authority. It is
                // intentionally collected before the old factor-2 refinement tree
                // so a 512x512 coarse region can appear without 64 exact leaves.
                collectRegionSurfaceCoverage(builder, overviewTextures,
                        minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                        scale, attentionPageX, attentionPageZ);
                collectSurfaceHierarchy(builder, overviewTextures, surfaceTextures,
                        minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                        minVisiblePageX, maxVisiblePageX,
                        minVisiblePageZ, maxVisiblePageZ,
                        hierarchyLevel, scale, attentionPageX, attentionPageZ,
                        manager, collectPending, centerOutTraversal);
            } else {
                if (centerOutTraversal) {
                    int centerPageX = clamp(attentionPageX,
                            minVisiblePageX, maxVisiblePageX);
                    int centerPageZ = clamp(attentionPageZ,
                            minVisiblePageZ, maxVisiblePageZ);
                    int maximumRadius = gridRadius(minVisiblePageX, maxVisiblePageX,
                            minVisiblePageZ, maxVisiblePageZ,
                            centerPageX, centerPageZ);
                    for (int radius = 0; radius <= maximumRadius; radius++) {
                        for (int pageZ = centerPageZ - radius;
                                pageZ <= centerPageZ + radius; pageZ++) {
                            for (int pageX = centerPageX - radius;
                                    pageX <= centerPageX + radius; pageX++) {
                                if (!onRing(pageX, pageZ, centerPageX, centerPageZ,
                                        radius)
                                        || pageX < minVisiblePageX
                                        || pageX > maxVisiblePageX
                                        || pageZ < minVisiblePageZ
                                        || pageZ > maxVisiblePageZ) continue;
                                collectSurfaceLeaf(builder, surfaceTextures,
                                        pageX, pageZ, manager, collectPending);
                            }
                        }
                    }
                } else {
                    for (int pageX = minVisiblePageX;
                            pageX <= maxVisiblePageX; pageX++) {
                        for (int pageZ = minVisiblePageZ;
                                pageZ <= maxVisiblePageZ; pageZ++) {
                            collectSurfaceLeaf(builder, surfaceTextures,
                                    pageX, pageZ, manager, collectPending);
                        }
                    }
                }
                if (MapConfig.minimapNightMode != 0) {
                    for (int pageX = minVisiblePageX;
                            pageX <= maxVisiblePageX; pageX++) {
                        for (int pageZ = minVisiblePageZ;
                                pageZ <= maxVisiblePageZ; pageZ++) {
                            collectSurfaceGlowLeaf(builder, surfaceTextures,
                                    pageX, pageZ);
                        }
                    }
                }
            }
        }
        return builder.build(MapResidencyManager.getInstance().topologyRevision());
    }

    private void collectCaveHierarchy(MapRenderPlan.Builder builder,
            CaveHierarchySource source,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minVisiblePageX, int maxVisiblePageX,
            int minVisiblePageZ, int maxVisiblePageZ,
            int level, float scale, boolean branchOnly,
            int focusPageX, int focusPageZ, boolean centerOutTraversal,
            Set<Long> drawnRegions) {
        int minPageX = minVisiblePageX;
        int maxPageX = maxVisiblePageX;
        int minPageZ = minVisiblePageZ;
        int maxPageZ = maxVisiblePageZ;
        if (level <= 0) {
            if (branchOnly) return;
            if (!centerOutTraversal) {
                for (int pageX = minPageX; pageX <= maxPageX; pageX++) {
                    for (int pageZ = minPageZ; pageZ <= maxPageZ; pageZ++) {
                        collectCaveLeaf(builder, source, pageX, pageZ, scale,
                                drawnRegions);
                    }
                }
                return;
            }
            int centerX = clamp(focusPageX, minPageX, maxPageX);
            int centerZ = clamp(focusPageZ, minPageZ, maxPageZ);
            int maximumRadius = gridRadius(minPageX, maxPageX, minPageZ, maxPageZ,
                    centerX, centerZ);
            for (int radius = 0; radius <= maximumRadius; radius++) {
                for (int pageZ = centerZ - radius; pageZ <= centerZ + radius; pageZ++) {
                    for (int pageX = centerX - radius; pageX <= centerX + radius; pageX++) {
                        if (!onRing(pageX, pageZ, centerX, centerZ, radius)
                                || pageX < minPageX || pageX > maxPageX
                                || pageZ < minPageZ || pageZ > maxPageZ) continue;
                        collectCaveLeaf(builder, source, pageX, pageZ, scale,
                                drawnRegions);
                    }
                }
            }
            return;
        }
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        if (!centerOutTraversal) {
            for (int nodeX = minNodeX; nodeX <= maxNodeX; nodeX++) {
                for (int nodeZ = minNodeZ; nodeZ <= maxNodeZ; nodeZ++) {
                    collectCaveNode(builder, source, level, nodeX, nodeZ, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions);
                }
            }
            return;
        }
        int centerX = clamp(Math.floorDiv(focusPageX, pageSpan), minNodeX, maxNodeX);
        int centerZ = clamp(Math.floorDiv(focusPageZ, pageSpan), minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                centerX, centerZ);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            for (int nodeZ = centerZ - radius; nodeZ <= centerZ + radius; nodeZ++) {
                for (int nodeX = centerX - radius; nodeX <= centerX + radius; nodeX++) {
                    if (!onRing(nodeX, nodeZ, centerX, centerZ, radius)
                            || nodeX < minNodeX || nodeX > maxNodeX
                            || nodeZ < minNodeZ || nodeZ > maxNodeZ) continue;
                    collectCaveNode(builder, source, level, nodeX, nodeZ, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions);
                }
            }
        }
    }

    private void collectCaveNode(MapRenderPlan.Builder builder,
            CaveHierarchySource source, int level, int nodeX, int nodeZ,
            float scale, boolean branchOnly,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            Set<Long> drawnRegions) {
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;
        if (lastPageX < minPageX || firstPageX > maxPageX
                || lastPageZ < minPageZ || firstPageZ > maxPageZ) return;

        CaveAtlasRegion branch = source.branch(level, nodeX, nodeZ);
        CaveAtlasRegion ancestor = null;
        if (branch == null) {
            ancestor = findCaveAncestor(source, level, nodeX, nodeZ);
            if (ancestor != null) {
                addAncestorQuad(builder, ancestor, level, nodeX, nodeZ);
                markDrawnPageRange(drawnRegions,
                        Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                        Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
            }
        }
        if (branchOnly) {
            if (branch != null) {
                addNodeQuad(builder, branch, nodeX, nodeZ, level);
                markDrawnPageRange(drawnRegions,
                        Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                        Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
                return;
            }
            if (source.hasBranchData(level, nodeX, nodeZ) || level <= 1) return;
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    collectCaveNode(builder, source, level - 1,
                            nodeX * 2 + childX, nodeZ * 2 + childZ, scale, true,
                            minPageX, maxPageX, minPageZ, maxPageZ, drawnRegions);
                }
            }
            return;
        }
        if (branch == null && ancestor == null
                && !source.hasResidentPageInNode(level, nodeX, nodeZ)) return;
        if (branch != null) addNodeQuad(builder, branch, nodeX, nodeZ, level);
        if (level == 1) {
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = childZ * 2 + childX;
                    int pageX = firstPageX + childX;
                    int pageZ = firstPageZ + childZ;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    if (collectCaveLeaf(builder, source, pageX, pageZ, scale,
                            drawnRegions)) continue;
                    if (branch != null && branch.childComplete(childIndex)) {
                        markPageDrawn(drawnRegions, pageX, pageZ);
                    }
                }
            }
            return;
        }
        for (int childX = 0; childX < 2; childX++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                int childIndex = childZ * 2 + childX;
                int childNodeX = nodeX * 2 + childX;
                int childNodeZ = nodeZ * 2 + childZ;
                if (branch != null && branch.childComplete(childIndex)
                        && !source.hasResidentPageInNode(level - 1,
                                childNodeX, childNodeZ)) continue;
                collectCaveNode(builder, source, level - 1,
                        childNodeX, childNodeZ, scale, false,
                        minPageX, maxPageX, minPageZ, maxPageZ, drawnRegions);
            }
        }
    }

    private boolean collectCaveLeaf(MapRenderPlan.Builder builder,
            CaveHierarchySource source, int globalPageX, int globalPageZ,
            float scale, Set<Long> drawnRegions) {
        if (!source.allowExact(globalPageX, globalPageZ)) return false;
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = source.page(rx, rz, px, pz, scale);
        if (page == null) return false;
        addCavePageQuad(builder, page, rx, rz, px, pz);
        drawnRegions.add(packRegion(rx, rz));
        return true;
    }

    private void collectRegionSurfaceCoverage(MapRenderPlan.Builder builder,
            MapOverviewTextureManager overviewTextures,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            float scale, int focusPageX, int focusPageZ) {
        int level = MapRegionLodPolicy.targetLevel(scale);
        int span = MapRegionLodPolicy.regionSpan(level);
        int minNodeX = Math.floorDiv(minRegionX, span);
        int maxNodeX = Math.floorDiv(maxRegionX, span);
        int minNodeZ = Math.floorDiv(minRegionZ, span);
        int maxNodeZ = Math.floorDiv(maxRegionZ, span);
        int focusRegionX = Math.floorDiv(focusPageX,
                MapPageLayout.PAGES_PER_REGION);
        int focusRegionZ = Math.floorDiv(focusPageZ,
                MapPageLayout.PAGES_PER_REGION);
        int focusNodeX = clamp(Math.floorDiv(focusRegionX, span),
                minNodeX, maxNodeX);
        int focusNodeZ = clamp(Math.floorDiv(focusRegionZ, span),
                minNodeZ, maxNodeZ);
        int radiusMax = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                focusNodeX, focusNodeZ);
        for (int radius = 0; radius <= radiusMax; radius++) {
            for (int nodeZ = focusNodeZ - radius;
                    nodeZ <= focusNodeZ + radius; nodeZ++) {
                for (int nodeX = focusNodeX - radius;
                        nodeX <= focusNodeX + radius; nodeX++) {
                    if (!onRing(nodeX, nodeZ, focusNodeX, focusNodeZ, radius)
                            || nodeX < minNodeX || nodeX > maxNodeX
                            || nodeZ < minNodeZ || nodeZ > maxNodeZ) continue;
                    CaveAtlasRegion branch = overviewTextures
                            .peekRegionSurfaceBranch(level, nodeX, nodeZ);
                    if (branch != null) {
                        addRegionLodQuad(builder, branch, nodeX, nodeZ);
                        continue;
                    }
                    CaveAtlasRegion ancestor = findRegionSurfaceAncestor(
                            overviewTextures, level, nodeX, nodeZ);
                    if (ancestor != null) {
                        addRegionLodAncestorQuad(builder, ancestor,
                                level, nodeX, nodeZ);
                    }
                }
            }
        }
    }

    private CaveAtlasRegion findRegionSurfaceAncestor(
            MapOverviewTextureManager overviewTextures, int targetLevel,
            int targetNodeX, int targetNodeZ) {
        int divisor = 1;
        for (int level = targetLevel + 1; level <= 3; level++) {
            divisor *= 8;
            CaveAtlasRegion ancestor = overviewTextures.peekRegionSurfaceBranch(
                    level, Math.floorDiv(targetNodeX, divisor),
                    Math.floorDiv(targetNodeZ, divisor));
            if (ancestor != null) return ancestor;
        }
        return null;
    }

    private void collectSurfaceHierarchy(MapRenderPlan.Builder builder,
            MapOverviewTextureManager overviewTextures,
            MapTextureManager surfaceTextures,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int minVisiblePageX, int maxVisiblePageX,
            int minVisiblePageZ, int maxVisiblePageZ,
            int level, float scale, int focusPageX, int focusPageZ,
            MapManager manager, boolean collectPending,
            boolean centerOutTraversal) {
        int minPageX = minVisiblePageX;
        int maxPageX = maxVisiblePageX;
        int minPageZ = minVisiblePageZ;
        int maxPageZ = maxVisiblePageZ;
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        if (!centerOutTraversal) {
            for (int nodeX = minNodeX; nodeX <= maxNodeX; nodeX++) {
                for (int nodeZ = minNodeZ; nodeZ <= maxNodeZ; nodeZ++) {
                    collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                            level, nodeX, nodeZ, minPageX, maxPageX,
                            minPageZ, maxPageZ, scale, manager, collectPending, false);
                }
            }
            return;
        }
        int centerX = clamp(Math.floorDiv(focusPageX, pageSpan), minNodeX, maxNodeX);
        int centerZ = clamp(Math.floorDiv(focusPageZ, pageSpan), minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                centerX, centerZ);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            for (int nodeZ = centerZ - radius; nodeZ <= centerZ + radius; nodeZ++) {
                for (int nodeX = centerX - radius; nodeX <= centerX + radius; nodeX++) {
                    if (!onRing(nodeX, nodeZ, centerX, centerZ, radius)
                            || nodeX < minNodeX || nodeX > maxNodeX
                            || nodeZ < minNodeZ || nodeZ > maxNodeZ) continue;
                    collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                            level, nodeX, nodeZ, minPageX, maxPageX,
                            minPageZ, maxPageZ, scale, manager, collectPending, false);
                }
            }
        }
    }

    private void collectSurfaceNode(MapRenderPlan.Builder builder,
            MapOverviewTextureManager overviewTextures,
            MapTextureManager surfaceTextures,
            int level, int nodeX, int nodeZ,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            float scale, MapManager manager, boolean collectPending,
            boolean inheritedCoverage) {
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;
        if (lastPageX < minPageX || firstPageX > maxPageX
                || lastPageZ < minPageZ || firstPageZ > maxPageZ) return;

        CaveAtlasRegion branch = overviewTextures.peekSurfaceBranch(level, nodeX, nodeZ);
        boolean nodeCovered = inheritedCoverage;
        if (branch != null) {
            addNodeQuad(builder, branch, nodeX, nodeZ, level);
            nodeCovered = true;
        } else if (!nodeCovered) {
            // Never expose a black hole while the requested LOD/exact leaf is
            // pending. A coarser GPU-resident ancestor remains as an underlay and
            // is cropped to this node until finer data is published.
            CaveAtlasRegion ancestor = findSurfaceAncestor(
                    overviewTextures, level, nodeX, nodeZ);
            if (ancestor != null) {
                addAncestorQuad(builder, ancestor, level, nodeX, nodeZ);
                nodeCovered = true;
            }
        }

        if (level == 1) {
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = childZ * 2 + childX;
                    int pageX = firstPageX + childX;
                    int pageZ = firstPageZ + childZ;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    /*
                     * At far zoom the density-correct L1 texture is the primary
                     * representation, not merely an underlay beneath a minified
                     * nearest-filtered L0 page. Keep exact leaves only as a
                     * progressive fallback for quadrants that the branch has not
                     * captured yet. Once the L1 child is complete, drawing exact on
                     * top would reintroduce the aliasing/softness this path exists
                     * to remove.
                     */
                    boolean branchPrimary = branch != null
                            && MapSurfaceRenderPolicy.useBranchInsteadOfExact(
                                    scale, level, branch.childKnown(childIndex));
                    if (branchPrimary && branch.childComplete(childIndex)) continue;
                    /*
                     * A partial L1 texture is transparent outside its known texels.
                     * Keep the exact leaf underneath it instead of drawing exact on
                     * top. The branch therefore owns every known pixel while exact
                     * fills only the still-unknown part, avoiding both black holes
                     * and the old nearest-filtered L0 override.
                     */
                    int phase = branchPrimary
                            ? MapRenderPlan.PHASE_L1_EXACT_UNDERLAY
                            : MapRenderPlan.PHASE_EXACT;
                    collectSurfaceLeaf(builder, surfaceTextures, pageX, pageZ,
                            manager, collectPending, phase);
                }
            }
            return;
        }
        for (int childX = 0; childX < 2; childX++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                int childIndex = childZ * 2 + childX;
                int childNodeX = nodeX * 2 + childX;
                int childNodeZ = nodeZ * 2 + childZ;
                if (branch != null && branch.childComplete(childIndex)
                        && !surfaceTextures.hasResidentPageInNode(level - 1,
                                childNodeX, childNodeZ)) continue;
                collectSurfaceNode(builder, overviewTextures, surfaceTextures,
                        level - 1, childNodeX, childNodeZ,
                        minPageX, maxPageX, minPageZ, maxPageZ,
                        scale, manager, collectPending, nodeCovered);
            }
        }
    }

    private boolean collectSurfaceLeaf(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ,
            MapManager manager, boolean collectPending) {
        return collectSurfaceLeaf(builder, surfaceTextures, globalPageX, globalPageZ,
                manager, collectPending, MapRenderPlan.PHASE_EXACT);
    }

    private boolean collectSurfaceLeaf(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ,
            MapManager manager, boolean collectPending, int phase) {
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = surfaceTextures.peekPageRegion(rx, rz, px, pz);
        if (page != null) {
            addPageQuad(builder, page, rx, rz, px, pz, phase);
            return true;
        }
        if (collectPending && (manager.hasRegionFile(rx, rz)
                || manager.isRegionLoadedInCache(rx, rz))) {
            builder.pending(rx, rz);
        }
        return false;
    }

    private boolean collectSurfaceRegionPages(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int rx, int rz) {
        boolean drew = false;
        for (int pz = 0; pz < MapPageLayout.PAGES_PER_REGION; pz++) {
            for (int px = 0; px < MapPageLayout.PAGES_PER_REGION; px++) {
                CaveAtlasRegion page = surfaceTextures.peekPageRegion(rx, rz, px, pz);
                if (page == null) continue;
                addPageQuad(builder, page, rx, rz, px, pz,
                        MapRenderPlan.PHASE_EXACT);
                drew = true;
            }
        }
        return drew;
    }

    private void collectSurfaceGlowLeaf(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ) {
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = surfaceTextures.peekGlowPageRegion(rx, rz, px, pz);
        if (page != null) addPageQuad(builder, page, rx, rz, px, pz,
                MapRenderPlan.PHASE_GLOW);
    }

    private void collectSurfaceGlowPages(MapRenderPlan.Builder builder,
            MapTextureManager surfaceTextures, int rx, int rz) {
        for (int pz = 0; pz < MapPageLayout.PAGES_PER_REGION; pz++) {
            for (int px = 0; px < MapPageLayout.PAGES_PER_REGION; px++) {
                CaveAtlasRegion page = surfaceTextures.peekGlowPageRegion(rx, rz, px, pz);
                if (page != null) addPageQuad(builder, page, rx, rz, px, pz,
                        MapRenderPlan.PHASE_GLOW);
            }
        }
    }

    /** Cave pages are not published into the M5 Surface page table yet.
     * Replaying them as Surface TileKeys can resolve an unrelated Surface atlas
     * entry at the same page coordinates, producing the mixed green/cave mosaic. */
    private void addCavePageQuad(MapRenderPlan.Builder builder,
            CaveAtlasRegion region, int regionX, int regionZ,
            int pageX, int pageZ) {
        if (builder.add(region.texture(), MapRenderPlan.PHASE_EXACT,
                regionX * 512 + pageX * 64,
                regionZ * 512 + pageZ * 64,
                64, 64, region.sourceX(), region.sourceY(),
                region.sourceSize(), region.sourceSize(),
                region.atlasSize(), region.atlasSize())) {
            builder.exact();
        }
    }

    private void addPageQuad(MapRenderPlan.Builder builder, CaveAtlasRegion region,
            int regionX, int regionZ, int pageX, int pageZ, int phase) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        int globalPageX = regionX * MapPageLayout.PAGES_PER_REGION + pageX;
        int globalPageZ = regionZ * MapPageLayout.PAGES_PER_REGION + pageZ;
        int variant = phase >= MapRenderPlan.PHASE_GLOW
                ? TileKey.VARIANT_SURFACE_GLOW
                : TileKey.VARIANT_SURFACE_EXACT;
        boolean added;
        if (stamp != null) {
            added = builder.addTile(new TileKey(stamp.sessionId(), 0, 0,
                            globalPageX, globalPageZ, variant),
                    region.texture(), phase,
                    regionX * 512 + pageX * 64,
                    regionZ * 512 + pageZ * 64,
                    64, 64, region.sourceX(), region.sourceY(),
                    region.sourceSize(), region.sourceSize(),
                    region.atlasSize(), region.atlasSize());
        } else {
            added = builder.add(region.texture(), phase,
                    regionX * 512 + pageX * 64,
                    regionZ * 512 + pageZ * 64,
                    64, 64, region.sourceX(), region.sourceY(),
                    region.sourceSize(), region.sourceSize(),
                    region.atlasSize(), region.atlasSize());
        }
        if (added && (phase == MapRenderPlan.PHASE_EXACT
                || phase == MapRenderPlan.PHASE_L1_EXACT_UNDERLAY)) builder.exact();
    }

    private void addNodeQuad(MapRenderPlan.Builder builder, CaveAtlasRegion region,
            int nodeX, int nodeZ, int level) {
        int worldSize = region.worldSize();
        if (builder.add(region.texture(), MapRenderPlan.branchPhase(level),
                nodeX * worldSize, nodeZ * worldSize,
                worldSize, worldSize, region.sourceX(), region.sourceY(),
                region.sourceSize(), region.sourceSize(),
                region.atlasSize(), region.atlasSize())) {
            builder.branch();
        }
    }

    private void addRegionLodQuad(MapRenderPlan.Builder builder,
            CaveAtlasRegion region, int nodeX, int nodeZ) {
        int worldSize = region.worldSize();
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        boolean added;
        if (stamp != null) {
            added = builder.addTile(new TileKey(stamp.sessionId(),
                            RegionSurfaceLodService.PROJECTION_SURFACE,
                            region.level(), nodeX, nodeZ,
                            TileKey.VARIANT_SURFACE_BRANCH),
                    region.texture(), MapRenderPlan.PHASE_REGION_COARSE,
                    nodeX * worldSize, nodeZ * worldSize,
                    worldSize, worldSize, region.sourceX(), region.sourceY(),
                    region.sourceSize(), region.sourceSize(),
                    region.atlasSize(), region.atlasSize());
        } else {
            added = builder.add(region.texture(),
                    MapRenderPlan.PHASE_REGION_COARSE,
                    nodeX * worldSize, nodeZ * worldSize,
                    worldSize, worldSize, region.sourceX(), region.sourceY(),
                    region.sourceSize(), region.sourceSize(),
                    region.atlasSize(), region.atlasSize());
        }
        if (added) builder.branch();
    }

    private void addRegionLodAncestorQuad(MapRenderPlan.Builder builder,
            CaveAtlasRegion ancestor, int targetLevel,
            int nodeX, int nodeZ) {
        int difference = ancestor.level() - targetLevel;
        if (difference <= 0) {
            addRegionLodQuad(builder, ancestor, nodeX, nodeZ);
            return;
        }
        int subdivision = 1;
        for (int index = 0; index < difference; index++) subdivision *= 8;
        int sourceSize = Math.max(1, ancestor.sourceSize() / subdivision);
        int localX = Math.floorMod(nodeX, subdivision);
        int localZ = Math.floorMod(nodeZ, subdivision);
        int worldSize = MapRegionLodPolicy.worldSize(targetLevel);
        if (builder.add(ancestor.texture(), MapRenderPlan.PHASE_REGION_COARSE,
                nodeX * worldSize, nodeZ * worldSize,
                worldSize, worldSize,
                ancestor.sourceX() + localX * sourceSize,
                ancestor.sourceY() + localZ * sourceSize,
                sourceSize, sourceSize,
                ancestor.atlasSize(), ancestor.atlasSize())) {
            builder.branch();
        }
    }

    private void addAncestorQuad(MapRenderPlan.Builder builder,
            CaveAtlasRegion ancestor, int targetLevel, int nodeX, int nodeZ) {
        int difference = ancestor.level() - targetLevel;
        if (difference <= 0) {
            addNodeQuad(builder, ancestor, nodeX, nodeZ, targetLevel);
            return;
        }
        int subdivision = 1 << difference;
        int sourceSize = Math.max(1, ancestor.sourceSize() / subdivision);
        int localX = Math.floorMod(nodeX, subdivision);
        int localZ = Math.floorMod(nodeZ, subdivision);
        int worldSize = MapLodPolicy.worldSizeForBranch(targetLevel);
        if (builder.add(ancestor.texture(), MapRenderPlan.branchPhase(targetLevel),
                nodeX * worldSize, nodeZ * worldSize, worldSize, worldSize,
                ancestor.sourceX() + localX * sourceSize,
                ancestor.sourceY() + localZ * sourceSize,
                sourceSize, sourceSize, ancestor.atlasSize(), ancestor.atlasSize())) {
            builder.branch();
        }
    }

    private void drawRegion(GuiGraphics guiGraphics, ResourceLocation texture, int regionX, int regionZ) {
        if (texture == null) return;
        RenderSystem.setShaderTexture(0, texture);
        guiGraphics.blit(texture, regionX * 512, regionZ * 512, 512, 512,
                0f, 0f, 512, 512, 512, 512);
    }

    private void drawPage(GuiGraphics guiGraphics, ResourceLocation texture, int regionX, int regionZ, int pageX, int pageZ) {
        if (texture == null) return;
        RenderSystem.setShaderTexture(0, texture);
        int x = regionX * 512 + pageX * 64;
        int z = regionZ * 512 + pageZ * 64;
        guiGraphics.blit(texture, x, z, 64, 64, 0f, 0f, 64, 64, 64, 64);
    }


    private void drawAtlasPage(GuiGraphics guiGraphics, CaveAtlasRegion region,
            int regionX, int regionZ, int pageX, int pageZ) {
        if (region == null) return;
        RenderSystem.setShaderTexture(0, region.texture());
        int x = regionX * 512 + pageX * 64;
        int z = regionZ * 512 + pageZ * 64;
        guiGraphics.blit(region.texture(), x, z, 64, 64,
                region.sourceX(), region.sourceY(),
                region.sourceSize(), region.sourceSize(),
                region.atlasSize(), region.atlasSize());
    }

    private void drawAtlasRegion(GuiGraphics guiGraphics, CaveAtlasRegion region,
            int regionX, int regionZ) {
        if (region == null) return;
        RenderSystem.setShaderTexture(0, region.texture());
        guiGraphics.blit(region.texture(), regionX * 512, regionZ * 512, 512, 512,
                region.sourceX(), region.sourceY(),
                region.sourceSize(), region.sourceSize(),
                region.atlasSize(), region.atlasSize());
    }

    private void drawCaveHierarchy(GuiGraphics guiGraphics,
            CaveHierarchySource source,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int level, float scale, boolean branchOnly,
            int focusPageX, int focusPageZ,
            Set<Long> drawnRegions, RenderStats stats) {
        int minPageX = minRegionX * MapPageLayout.PAGES_PER_REGION;
        int maxPageX = (maxRegionX + 1) * MapPageLayout.PAGES_PER_REGION - 1;
        int minPageZ = minRegionZ * MapPageLayout.PAGES_PER_REGION;
        int maxPageZ = (maxRegionZ + 1) * MapPageLayout.PAGES_PER_REGION - 1;

        if (level <= 0) {
            if (branchOnly) return;
            int clampedFocusPageX = clamp(focusPageX, minPageX, maxPageX);
            int clampedFocusPageZ = clamp(focusPageZ, minPageZ, maxPageZ);
            int maximumRadius = gridRadius(minPageX, maxPageX, minPageZ, maxPageZ,
                    clampedFocusPageX, clampedFocusPageZ);
            for (int radius = 0; radius <= maximumRadius; radius++) {
                for (int pageZ = clampedFocusPageZ - radius;
                        pageZ <= clampedFocusPageZ + radius; pageZ++) {
                    for (int pageX = clampedFocusPageX - radius;
                            pageX <= clampedFocusPageX + radius; pageX++) {
                        if (!onRing(pageX, pageZ, clampedFocusPageX,
                                clampedFocusPageZ, radius)) continue;
                        if (pageX < minPageX || pageX > maxPageX
                                || pageZ < minPageZ || pageZ > maxPageZ) continue;
                        drawCaveLeafPage(guiGraphics, source, pageX, pageZ, scale,
                                drawnRegions, stats);
                    }
                }
            }
            return;
        }

        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        int focusNodeX = clamp(Math.floorDiv(focusPageX, pageSpan), minNodeX, maxNodeX);
        int focusNodeZ = clamp(Math.floorDiv(focusPageZ, pageSpan), minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                focusNodeX, focusNodeZ);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            for (int nodeZ = focusNodeZ - radius; nodeZ <= focusNodeZ + radius; nodeZ++) {
                for (int nodeX = focusNodeX - radius; nodeX <= focusNodeX + radius; nodeX++) {
                    if (!onRing(nodeX, nodeZ, focusNodeX, focusNodeZ, radius)) continue;
                    if (nodeX < minNodeX || nodeX > maxNodeX
                            || nodeZ < minNodeZ || nodeZ > maxNodeZ) continue;
                    drawCaveNode(guiGraphics, source, level, nodeX, nodeZ, scale,
                            branchOnly, minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, stats);
                }
            }
        }
    }

    private void drawCaveNode(GuiGraphics guiGraphics, CaveHierarchySource source,
            int level, int nodeX, int nodeZ, float scale, boolean branchOnly,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            Set<Long> drawnRegions, RenderStats stats) {
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;
        if (lastPageX < minPageX || firstPageX > maxPageX
                || lastPageZ < minPageZ || firstPageZ > maxPageZ) return;

        CaveAtlasRegion branch = source.branch(level, nodeX, nodeZ);
        CaveAtlasRegion ancestorFallback = null;
        if (branch == null) {
            ancestorFallback = findCaveAncestor(source, level, nodeX, nodeZ);
            if (ancestorFallback != null) {
                drawCaveAncestorSubRect(guiGraphics, ancestorFallback,
                        level, nodeX, nodeZ);
                stats.branchNodes++;
                markDrawnPageRange(drawnRegions,
                        Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                        Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
            }
        }
        if (branchOnly) {
            if (branch != null) {
                drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
                stats.branchNodes++;
                markDrawnPageRange(drawnRegions,
                        Math.max(firstPageX, minPageX), Math.min(lastPageX, maxPageX),
                        Math.max(firstPageZ, minPageZ), Math.min(lastPageZ, maxPageZ));
                return;
            }
            // If metadata says this branch exists on disk, wait for that coherent
            // representation instead of recursively probing many lower levels in
            // the same frame. Descend only when the current level is genuinely
            // absent and a smaller cached branch may provide coverage.
            if (source.hasBranchData(level, nodeX, nodeZ)) return;
            // Xaero keeps a coarse root/ancestor sub-rectangle visible while lower
            // branch levels load. Continue downward only to refine that stable base;
            // never fall through to exact leaves at branch-only zoom.
            if (level <= 1) return;
            int childPageSpan = MapLodPolicy.pageSpanForBranch(level - 1);
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childNodeX = nodeX * 2 + childX;
                    int childNodeZ = nodeZ * 2 + childZ;
                    int childFirstPageX = childNodeX * childPageSpan;
                    int childFirstPageZ = childNodeZ * childPageSpan;
                    if (childFirstPageX > maxPageX
                            || childFirstPageX + childPageSpan - 1 < minPageX
                            || childFirstPageZ > maxPageZ
                            || childFirstPageZ + childPageSpan - 1 < minPageZ) continue;
                    drawCaveNode(guiGraphics, source, level - 1,
                            childNodeX, childNodeZ, scale, true,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            drawnRegions, stats);
                }
            }
            return;
        }

        // A missing branch is not allowed to hide resident exact pages at close and
        // medium zoom. Query the exact-page authority once and stop only when neither
        // branch nor resident child data exists.
        if (branch == null && ancestorFallback == null
                && !source.hasResidentPageInNode(level, nodeX, nodeZ)) {
            return;
        }
        if (branch != null) {
            drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
            stats.branchNodes++;
        }

        if (level == 1) {
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = childZ * 2 + childX;
                    int pageX = firstPageX + childX;
                    int pageZ = firstPageZ + childZ;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    boolean exactDrawn = drawCaveLeafPage(guiGraphics, source,
                            pageX, pageZ, scale, drawnRegions, stats);
                    if (exactDrawn) continue;
                    if (branch != null && branch.childComplete(childIndex)) {
                        markPageDrawn(drawnRegions, pageX, pageZ);
                        continue;
                    }
                }
            }
            return;
        }

        int childPageSpan = MapLodPolicy.pageSpanForBranch(level - 1);
        for (int childX = 0; childX < 2; childX++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                int childIndex = childZ * 2 + childX;
                int childNodeX = nodeX * 2 + childX;
                int childNodeZ = nodeZ * 2 + childZ;
                int childFirstPageX = childNodeX * childPageSpan;
                int childFirstPageZ = childNodeZ * childPageSpan;
                if (branch != null && branch.childComplete(childIndex)
                        && !source.hasResidentPageInNode(
                                level - 1, childNodeX, childNodeZ)) {
                    markDrawnPageRange(drawnRegions,
                            Math.max(childFirstPageX, minPageX),
                            Math.min(childFirstPageX + childPageSpan - 1, maxPageX),
                            Math.max(childFirstPageZ, minPageZ),
                            Math.min(childFirstPageZ + childPageSpan - 1, maxPageZ));
                    continue;
                }
                drawCaveNode(guiGraphics, source, level - 1,
                        childNodeX, childNodeZ, scale, false,
                        minPageX, maxPageX, minPageZ, maxPageZ, drawnRegions, stats);
            }
        }
    }

    private boolean drawCaveLeafPage(GuiGraphics guiGraphics, CaveHierarchySource source,
            int globalPageX, int globalPageZ, float scale, Set<Long> drawnRegions,
            RenderStats stats) {
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = source.page(rx, rz, px, pz, scale);
        if (page == null) return false;
        drawAtlasPage(guiGraphics, page, rx, rz, px, pz);
        drawnRegions.add(packRegion(rx, rz));
        stats.exactPages++;
        return true;
    }

    private CaveAtlasRegion findSurfaceAncestor(
            MapOverviewTextureManager source, int targetLevel,
            int targetNodeX, int targetNodeZ) {
        for (int ancestorLevel = targetLevel + 1;
                ancestorLevel <= SurfaceLodTree.MAX_LEVEL; ancestorLevel++) {
            int shift = ancestorLevel - targetLevel;
            int ancestorX = Math.floorDiv(targetNodeX, 1 << shift);
            int ancestorZ = Math.floorDiv(targetNodeZ, 1 << shift);
            CaveAtlasRegion ancestor = source.peekSurfaceBranch(
                    ancestorLevel, ancestorX, ancestorZ);
            if (ancestor != null) return ancestor;
        }
        return null;
    }

    private CaveAtlasRegion findCaveAncestor(CaveHierarchySource source,
            int level, int nodeX, int nodeZ) {
        for (int ancestorLevel = level + 1;
                ancestorLevel <= MapLodPolicy.MAX_BRANCH_LEVEL; ancestorLevel++) {
            int scale = 1 << (ancestorLevel - level);
            CaveAtlasRegion ancestor = source.branch(ancestorLevel,
                    Math.floorDiv(nodeX, scale), Math.floorDiv(nodeZ, scale));
            if (ancestor != null) return ancestor;
        }
        return null;
    }

    /** Draws the descendant node's sub-rectangle from a coarser resident ancestor. */
    private void drawCaveAncestorSubRect(GuiGraphics guiGraphics,
            CaveAtlasRegion ancestor, int targetLevel, int nodeX, int nodeZ) {
        int levelDifference = ancestor.level() - targetLevel;
        if (levelDifference <= 0) {
            drawAtlasNode(guiGraphics, ancestor, nodeX, nodeZ);
            return;
        }
        int subdivision = 1 << levelDifference;
        int sourceSize = Math.max(1, ancestor.sourceSize() / subdivision);
        int localX = Math.floorMod(nodeX, subdivision);
        int localZ = Math.floorMod(nodeZ, subdivision);
        float sourceX = ancestor.sourceX() + localX * sourceSize;
        float sourceY = ancestor.sourceY() + localZ * sourceSize;
        int worldSize = MapLodPolicy.worldSizeForBranch(targetLevel);
        RenderSystem.setShaderTexture(0, ancestor.texture());
        guiGraphics.blit(ancestor.texture(), nodeX * worldSize, nodeZ * worldSize,
                worldSize, worldSize, sourceX, sourceY, sourceSize, sourceSize,
                ancestor.atlasSize(), ancestor.atlasSize());
    }

    private void drawAtlasNode(GuiGraphics guiGraphics, CaveAtlasRegion region,
            int nodeX, int nodeZ) {
        RenderSystem.setShaderTexture(0, region.texture());
        int worldSize = region.worldSize();
        guiGraphics.blit(region.texture(), nodeX * worldSize, nodeZ * worldSize,
                worldSize, worldSize,
                region.sourceX(), region.sourceY(),
                region.sourceSize(), region.sourceSize(),
                region.atlasSize(), region.atlasSize());
    }

    private static void markPageDrawn(Set<Long> output, int pageX, int pageZ) {
        output.add(packRegion(
                Math.floorDiv(pageX, MapPageLayout.PAGES_PER_REGION),
                Math.floorDiv(pageZ, MapPageLayout.PAGES_PER_REGION)));
    }

    private static void markDrawnPageRange(Set<Long> output,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
        if (maxPageX < minPageX || maxPageZ < minPageZ) return;
        int minRegionX = Math.floorDiv(minPageX, MapPageLayout.PAGES_PER_REGION);
        int maxRegionX = Math.floorDiv(maxPageX, MapPageLayout.PAGES_PER_REGION);
        int minRegionZ = Math.floorDiv(minPageZ, MapPageLayout.PAGES_PER_REGION);
        int maxRegionZ = Math.floorDiv(maxPageZ, MapPageLayout.PAGES_PER_REGION);
        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                output.add(packRegion(rx, rz));
            }
        }
    }

    private interface CaveHierarchySource {
        CaveAtlasRegion branch(int level, int nodeX, int nodeZ);

        boolean hasBranchData(int level, int nodeX, int nodeZ);

        boolean hasResidentPageInNode(int level, int nodeX, int nodeZ);

        boolean allowExact(int globalPageX, int globalPageZ);

        CaveAtlasRegion page(int regionX, int regionZ,
                int pageX, int pageZ, float scale);
    }

    private void drawSurfaceHierarchy(GuiGraphics guiGraphics,
            MapOverviewTextureManager overviewTextures,
            MapTextureManager surfaceTextures,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int level, float scale, int focusPageX, int focusPageZ,
            Set<Long> pendingRegions, MapManager manager, RenderStats stats) {
        if (level <= 0) return;
        int minPageX = minRegionX * MapPageLayout.PAGES_PER_REGION;
        int maxPageX = (maxRegionX + 1) * MapPageLayout.PAGES_PER_REGION - 1;
        int minPageZ = minRegionZ * MapPageLayout.PAGES_PER_REGION;
        int maxPageZ = (maxRegionZ + 1) * MapPageLayout.PAGES_PER_REGION - 1;
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int minNodeX = Math.floorDiv(minPageX, pageSpan);
        int maxNodeX = Math.floorDiv(maxPageX, pageSpan);
        int minNodeZ = Math.floorDiv(minPageZ, pageSpan);
        int maxNodeZ = Math.floorDiv(maxPageZ, pageSpan);
        int focusNodeX = clamp(Math.floorDiv(focusPageX, pageSpan), minNodeX, maxNodeX);
        int focusNodeZ = clamp(Math.floorDiv(focusPageZ, pageSpan), minNodeZ, maxNodeZ);
        int maximumRadius = gridRadius(minNodeX, maxNodeX, minNodeZ, maxNodeZ,
                focusNodeX, focusNodeZ);
        for (int radius = 0; radius <= maximumRadius; radius++) {
            for (int nodeZ = focusNodeZ - radius; nodeZ <= focusNodeZ + radius; nodeZ++) {
                for (int nodeX = focusNodeX - radius; nodeX <= focusNodeX + radius; nodeX++) {
                    if (!onRing(nodeX, nodeZ, focusNodeX, focusNodeZ, radius)) continue;
                    if (nodeX < minNodeX || nodeX > maxNodeX
                            || nodeZ < minNodeZ || nodeZ > maxNodeZ) continue;
                    drawSurfaceNode(guiGraphics, overviewTextures, surfaceTextures,
                            level, nodeX, nodeZ, scale,
                            minPageX, maxPageX, minPageZ, maxPageZ,
                            pendingRegions, manager, stats);
                }
            }
        }
    }

    private void drawSurfaceNode(GuiGraphics guiGraphics,
            MapOverviewTextureManager overviewTextures,
            MapTextureManager surfaceTextures,
            int level, int nodeX, int nodeZ, float scale,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            Set<Long> pendingRegions, MapManager manager, RenderStats stats) {
        int pageSpan = MapLodPolicy.pageSpanForBranch(level);
        int firstPageX = nodeX * pageSpan;
        int firstPageZ = nodeZ * pageSpan;
        int lastPageX = firstPageX + pageSpan - 1;
        int lastPageZ = firstPageZ + pageSpan - 1;
        if (lastPageX < minPageX || firstPageX > maxPageX
                || lastPageZ < minPageZ || firstPageZ > maxPageZ) return;

        CaveAtlasRegion branch = overviewTextures.peekSurfaceBranch(level, nodeX, nodeZ);
        boolean l1BranchOverlay = level == 1 && branch != null && scale < 0.50f;
        // Direct compatibility rendering has no phase sorter. At far L1 zoom, draw
        // exact fallback first and the partially transparent branch afterwards so
        // known L1 texels are authoritative while unknown texels keep exact data.
        if (branch != null && !l1BranchOverlay) {
            drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
            stats.branchNodes++;
        }

        if (level == 1) {
            for (int childX = 0; childX < 2; childX++) {
                for (int childZ = 0; childZ < 2; childZ++) {
                    int childIndex = childZ * 2 + childX;
                    int pageX = firstPageX + childX;
                    int pageZ = firstPageZ + childZ;
                    if (pageX < minPageX || pageX > maxPageX
                            || pageZ < minPageZ || pageZ > maxPageZ) continue;
                    boolean branchPrimary = branch != null
                            && MapSurfaceRenderPolicy.useBranchInsteadOfExact(
                                    scale, level, branch.childKnown(childIndex));
                    if (branchPrimary && branch.childComplete(childIndex)) continue;
                    drawSurfaceLeafPage(guiGraphics, surfaceTextures, pageX, pageZ,
                            pendingRegions, manager, stats);
                }
            }
            if (l1BranchOverlay) {
                drawAtlasNode(guiGraphics, branch, nodeX, nodeZ);
                stats.branchNodes++;
            }
            return;
        }

        for (int childX = 0; childX < 2; childX++) {
            for (int childZ = 0; childZ < 2; childZ++) {
                int childIndex = childZ * 2 + childX;
                int childNodeX = nodeX * 2 + childX;
                int childNodeZ = nodeZ * 2 + childZ;
                if (branch != null && branch.childComplete(childIndex)
                        && !surfaceTextures.hasResidentPageInNode(
                                level - 1, childNodeX, childNodeZ)) continue;
                drawSurfaceNode(guiGraphics, overviewTextures, surfaceTextures,
                        level - 1, childNodeX, childNodeZ, scale,
                        minPageX, maxPageX, minPageZ, maxPageZ,
                        pendingRegions, manager, stats);
            }
        }
    }

    private boolean drawSurfaceLeafPage(GuiGraphics guiGraphics,
            MapTextureManager surfaceTextures, int globalPageX, int globalPageZ,
            Set<Long> pendingRegions, MapManager manager, RenderStats stats) {
        int rx = Math.floorDiv(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int rz = Math.floorDiv(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        int px = Math.floorMod(globalPageX, MapPageLayout.PAGES_PER_REGION);
        int pz = Math.floorMod(globalPageZ, MapPageLayout.PAGES_PER_REGION);
        CaveAtlasRegion page = surfaceTextures.peekPageRegion(rx, rz, px, pz);
        if (page != null) {
            drawAtlasPage(guiGraphics, page, rx, rz, px, pz);
            stats.exactPages++;
            return true;
        }
        // Legacy 512x512 region textures are no longer a foreground authority.
        // Missing exact leaves remain covered by stable LOD branches or a loading
        // indicator until the exact page is published.
        if (manager.hasRegionFile(rx, rz) || manager.isRegionLoadedInCache(rx, rz)) {
            collectPendingRegion(pendingRegions, rx, rz);
        }
        return false;
    }

    private boolean drawSurfaceRegionWithPages(GuiGraphics guiGraphics,
            MapTextureManager surfaceTextures, int rx, int rz, float scale,
            RenderStats stats) {
        boolean drewAny = false;
        for (int pz = 0; pz < MapPageLayout.PAGES_PER_REGION; pz++) {
            for (int px = 0; px < MapPageLayout.PAGES_PER_REGION; px++) {
                CaveAtlasRegion pageTex = surfaceTextures.peekPageRegion(rx, rz, px, pz);
                if (pageTex == null) continue;
                drawAtlasPage(guiGraphics, pageTex, rx, rz, px, pz);
                drewAny = true;
                stats.exactPages++;
            }
        }
        return drewAny;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(Math.min(minimum, maximum),
                Math.min(Math.max(minimum, maximum), value));
    }

    private static int gridRadius(int minX, int maxX, int minZ, int maxZ,
            int focusX, int focusZ) {
        return Math.max(
                Math.max(Math.abs(focusX - minX), Math.abs(maxX - focusX)),
                Math.max(Math.abs(focusZ - minZ), Math.abs(maxZ - focusZ)));
    }

    private static boolean onRing(int x, int z, int focusX, int focusZ, int radius) {
        return Math.max(Math.abs(x - focusX), Math.abs(z - focusZ)) == radius;
    }

    private static long packRegion(int regionX, int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }

    private static void collectPendingRegion(Set<Long> pending, int regionX, int regionZ) {
        if (pending == null || pending.size() >= 256) return;
        pending.add(packRegion(regionX, regionZ));
    }

    /**
     * Loading granularity follows zoom: close views use 128/256-block cells,
     * normal views use one region, and far views group 2x2 or 4x4 regions.
     */
    private void drawLoadingIndicators(GuiGraphics guiGraphics,
            long[] pendingRegions, float mapScale) {
        if (pendingRegions == null || pendingRegions.length == 0) return;
        Set<Long> pending = new LinkedHashSet<>();
        for (long packed : pendingRegions) {
            if (pending.size() >= 256) break;
            pending.add(packed);
        }
        drawLoadingIndicators(guiGraphics, pending, mapScale);
    }

    private void drawLoadingIndicators(GuiGraphics guiGraphics,
            Set<Long> pendingRegions, float mapScale) {
        if (pendingRegions == null || pendingRegions.isEmpty() || mapScale <= 0.0f) return;
        int cellBlocks = loadingCellBlocks(mapScale);
        Set<Long> cells = new LinkedHashSet<>();
        for (long packed : pendingRegions) {
            int rx = (int) (packed >> 32);
            int rz = (int) packed;
            int regionX = rx * 512;
            int regionZ = rz * 512;
            if (cellBlocks < 512) {
                for (int z = regionZ; z < regionZ + 512 && cells.size() < 24; z += cellBlocks) {
                    for (int x = regionX; x < regionX + 512 && cells.size() < 24; x += cellBlocks) {
                        cells.add(packCell(x, z));
                    }
                }
            } else {
                int cellX = Math.floorDiv(regionX, cellBlocks) * cellBlocks;
                int cellZ = Math.floorDiv(regionZ, cellBlocks) * cellBlocks;
                cells.add(packCell(cellX, cellZ));
            }
            if (cells.size() >= 24) break;
        }

        float screenCell = Math.max(1.0f, cellBlocks * mapScale);
        int radius = Math.max(4, Math.min(9, Math.round(screenCell * 0.08f)));
        for (long packed : cells) {
            int cellX = (int) (packed >> 32);
            int cellZ = (int) packed;
            drawLoadingSpinner(guiGraphics,
                    cellX + cellBlocks * 0.5,
                    cellZ + cellBlocks * 0.5,
                    mapScale, radius);
        }
    }

    private static int loadingCellBlocks(float scale) {
        if (scale >= 1.0f) return 128;
        if (scale >= 0.45f) return 256;
        if (scale >= 0.18f) return 512;
        if (scale >= 0.10f) return 1024;
        return 2048;
    }

    private static long packCell(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private void drawLoadingSpinner(GuiGraphics guiGraphics,
            double worldX, double worldZ, float mapScale, int radius) {
        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(worldX, worldZ, 20.0);
        float inverseScale = 1.0f / mapScale;
        pose.scale(inverseScale, inverseScale, 1.0f);
        int diagonal = Math.max(3, Math.round(radius * 0.70f));
        int[][] points = {
                { 0, -radius }, { diagonal, -diagonal }, { radius, 0 },
                { diagonal, diagonal }, { 0, radius }, { -diagonal, diagonal },
                { -radius, 0 }, { -diagonal, -diagonal }
        };
        int phase = (int) ((System.currentTimeMillis() / 90L) & 7L);
        for (int i = 0; i < points.length; i++) {
            int distance = (i - phase + 8) & 7;
            int alpha = distance == 0 ? 0xFF : distance <= 2 ? 0xA0 : 0x48;
            int color = (alpha << 24) | 0x00D8D8D8;
            int x = points[i][0];
            int y = points[i][1];
            guiGraphics.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
        pose.popPose();
    }

    private void drawSurfaceGlowRegionWithPages(GuiGraphics guiGraphics,
            MapTextureManager surfaceTextures, int rx, int rz) {
        for (int pz = 0; pz < MapPageLayout.PAGES_PER_REGION; pz++) {
            for (int px = 0; px < MapPageLayout.PAGES_PER_REGION; px++) {
                CaveAtlasRegion pageGlow = surfaceTextures.peekGlowPageRegion(rx, rz, px, pz);
                if (pageGlow != null) drawAtlasPage(guiGraphics, pageGlow, rx, rz, px, pz);
            }
        }
    }


    private float getMapBrightness(Minecraft mc, float partialTick) {
        if (MapConfig.minimapNightMode == 0) return 1.0f;
        if (MapConfig.minimapNightMode == 2) return NIGHT_MIN_BRIGHTNESS;
        if (!mc.level.dimensionType().hasSkyLight()) return NIGHT_MIN_BRIGHTNESS;

        // ClientLevel returns a sky-light factor near 1 in daylight and near 0.2 at
        // night. Rain and thunder are already included in this value.
        float skyLight = Math.max(0.0f, Math.min(1.0f, mc.level.getSkyDarken(partialTick)));
        // Vanilla's night floor is roughly 0.2 rather than zero. Normalize that
        // range so AUTO can still reach the same deep-night contrast as ON.
        float darkness = Math.max(0.0f, Math.min(1.0f, (1.0f - skyLight) / 0.8f));
        return 1.0f - darkness * (1.0f - NIGHT_MIN_BRIGHTNESS);
    }


    private float getCaveBrightness(Minecraft mc, float partialTick) {
        if (MapConfig.minimapNightMode == 0) return 1.0f;
        if (MapConfig.minimapNightMode == 2) return 0.62f;
        // AUTO remains readable in permanent-ceiling dimensions such as the
        // Nether, while still being visibly darker than OFF and lighter than ON.
        if (!mc.level.dimensionType().hasSkyLight()) return 0.80f;
        float surfaceBrightness = getMapBrightness(mc, partialTick);
        return 0.70f + surfaceBrightness * 0.30f;
    }

    /**
     * Draws a waypoint marker at the specified world coordinates.
     */
    private void drawWaypointMarker(GuiGraphics guiGraphics, WaypointManager.Waypoint wp, float mapScale,
            boolean isMinimap, boolean isHovered) {
        Minecraft mc = Minecraft.getInstance();
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        // Translate to the waypoint's position in world space
        poseStack.translate(wp.x, wp.z, 5);

        // Do not compensate map scale so waypoints scale with map zoom.
        // Base scale is 0.08f (fits perfectly in ~1.28 blocks in world space).
        float markerScale = 0.08f * MapConfig.waypointScale * wp.scale;
        poseStack.scale(markerScale, markerScale, 1.0f);

        // Determine item ID (map old preset iconTypes to item textures for backward
        // compatibility)
        String itemID = wp.iconItem;
        if (wp.iconType >= 0) {
            if (wp.iconType == 0)
                itemID = "minecraft:red_dye";
            else if (wp.iconType == 1)
                itemID = "minecraft:red_bed";
            else if (wp.iconType == 2)
                itemID = "minecraft:target";
            else
                itemID = "minecraft:nether_star";
        }

        // Draw the item texture
        if (itemID.isEmpty()) {
            itemID = "minecraft:compass";
        }

        try {
            net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                    ResourceLocation.parse(itemID));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                // Draw item centered (ItemStack is 16x16px, so we offset by -8)
                guiGraphics.renderFakeItem(new net.minecraft.world.item.ItemStack(item), -8, -8);
            } else {
                guiGraphics.renderFakeItem(
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS), -8, -8);
            }
        } catch (Exception e) {
            guiGraphics.renderFakeItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS),
                    -8, -8);
        }

        // Draw name text above the icon ONLY if it is not the minimap and it is hovered
        if (!isMinimap && isHovered) {
            poseStack.scale(0.8f, 0.8f, 1.0f);
            int textWidth = mc.font.width(wp.name);
            int textX = -textWidth / 2;
            int textY = -16;

            guiGraphics.fill(textX - 2, textY - 2, textX + textWidth + 2, textY + 9, 0x88000000);
            guiGraphics.drawString(mc.font, wp.name, textX, textY, 0xFFFFFF, false);
        }

        poseStack.popPose();
    }

    /**
     * Draws the player marker at the specified world coordinates.
     */
    private void drawPlayerMarker(GuiGraphics guiGraphics, double worldX, double worldZ, float yaw,
            float mapScale, float fixedOverlayScale) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        // Translate to the player's position in world space
        poseStack.translate(worldX, worldZ, 10);

        // Compensate map scale and apply playerMarkerScale config
        float markerScale = (1.0f / mapScale) * MapConfig.playerMarkerScale * fixedOverlayScale;
        poseStack.scale(markerScale, markerScale, 1.0f);

        // Rotate the entire marker to face player direction
        poseStack.mulPose(Axis.ZP.rotationDegrees(yaw));

        int pointerColor = getActualPointerColor(MapConfig.playerPointerColor);

        if (MapConfig.playerMarkerMode == 1) {
            // Mode 1: ARROW_ONLY (Only show triangle/chevron pointer pointing UP/forward)
            drawDirectionalPointer(poseStack, pointerColor, 1.0f);
        } else {
            // Mode 0: DEFAULT (Player skin head only, no pointer)
            // 1. Draw black background border for player head
            guiGraphics.fill(-5, -5, 5, 5, 0xFF121212);

            // 2. Draw Player Head Skin (Face + Hat Layer)
            net.minecraft.client.resources.PlayerSkin skin = mc.player.getSkin();
            ResourceLocation skinLocation = skin.texture();

            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            // Base Face layer
            guiGraphics.blit(skinLocation, -4, -4, 8, 8, 8.0f, 8.0f, 8, 8, 64, 64);
            // Outer Hat/Hair layer
            guiGraphics.blit(skinLocation, -4, -4, 8, 8, 40.0f, 8.0f, 8, 8, 64, 64);
        }

        poseStack.popPose();
    }

    /**
     * Draws the minimap player marker as a final HUD overlay. It must not live in
     * the retained map FBO: a cold map page or failed composition would otherwise
     * make the player arrow disappear together with map content.
     */
    public void drawMinimapPlayerOverlay(GuiGraphics guiGraphics, float centerX,
            float centerY, boolean rotateWithPlayer, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        try {
            poseStack.translate(centerX, centerY, 400.0f);
            poseStack.scale(MapConfig.playerMarkerScale,
                    MapConfig.playerMarkerScale, 1.0f);
            if (!rotateWithPlayer) {
                float yaw = net.minecraft.util.Mth.rotLerp(
                        partialTick, mc.player.yRotO, mc.player.getYRot()) + 180.0f;
                poseStack.mulPose(Axis.ZP.rotationDegrees(yaw));
            }

            int pointerColor = getActualPointerColor(MapConfig.playerPointerColor);
            if (MapConfig.playerMarkerMode == 1) {
                drawDirectionalPointer(poseStack, pointerColor, 1.0f);
            } else {
                guiGraphics.fill(-5, -5, 5, 5, 0xFF121212);
                net.minecraft.client.resources.PlayerSkin skin = mc.player.getSkin();
                ResourceLocation skinLocation = skin.texture();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                guiGraphics.blit(skinLocation, -4, -4, 8, 8,
                        8.0f, 8.0f, 8, 8, 64, 64);
                guiGraphics.blit(skinLocation, -4, -4, 8, 8,
                        40.0f, 8.0f, 8, 8, 64, 64);
            }
            guiGraphics.flush();
        } finally {
            poseStack.popPose();
            if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
    }

    public int getActualPointerColor(int configColor) {
        if (configColor == 0xFF000001) {
            float hue = (System.currentTimeMillis() % 4000) / 4000.0f; // cycle every 4 seconds
            int r = 0, g = 0, b = 0;
            float h = hue * 6;
            float f = h - (int) h;
            float q = 1.0f - f;
            float t = f;
            switch ((int) h) {
                case 0:
                    r = 255;
                    g = (int) (t * 255);
                    b = 0;
                    break;
                case 1:
                    r = (int) (q * 255);
                    g = 255;
                    b = 0;
                    break;
                case 2:
                    r = 0;
                    g = 255;
                    b = (int) (t * 255);
                    break;
                case 3:
                    r = 0;
                    g = (int) (q * 255);
                    b = 255;
                    break;
                case 4:
                    r = (int) (t * 255);
                    g = 0;
                    b = 255;
                    break;
                case 5:
                    r = 255;
                    g = 0;
                    b = (int) (q * 255);
                    break;
            }
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return configColor;
    }

    private void drawDirectionalPointer(PoseStack poseStack, int color, float sizeScale) {
        org.joml.Matrix4f matrix = poseStack.last().pose();

        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;

        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem
                .setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);

        com.mojang.blaze3d.vertex.Tesselator tesselator = com.mojang.blaze3d.vertex.Tesselator.getInstance();
        com.mojang.blaze3d.vertex.BufferBuilder buffer = tesselator.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);

        // Chevron coordinates:
        float tipY = -6.0f * sizeScale;
        float blX = -4.5f * sizeScale;
        float blY = 4.5f * sizeScale;
        float indY = 2.0f * sizeScale;
        float brX = 4.5f * sizeScale;
        float brY = 4.5f * sizeScale;

        // Outline coordinates:
        float oTipY = -7.5f * sizeScale;
        float oBlX = -5.5f * sizeScale;
        float oBlY = 5.5f * sizeScale;
        float oIndY = 3.0f * sizeScale;
        float oBrX = 5.5f * sizeScale;
        float oBrY = 5.5f * sizeScale;

        // 1. Draw Outline (2 triangles for chevron)
        // Left half: tip -> bl -> ind
        buffer.addVertex(matrix, 0.0f, oTipY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        buffer.addVertex(matrix, oBlX, oBlY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        buffer.addVertex(matrix, 0.0f, oIndY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        // Right half: tip -> ind -> br
        buffer.addVertex(matrix, 0.0f, oTipY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        buffer.addVertex(matrix, 0.0f, oIndY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);
        buffer.addVertex(matrix, oBrX, oBrY, 0.0f).setColor(0.07f, 0.07f, 0.07f, 1.0f);

        // 2. Draw Inner Fill (2 triangles for chevron)
        // Left half: tip -> bl -> ind
        buffer.addVertex(matrix, 0.0f, tipY, 0.1f).setColor(r, g, b, a);
        buffer.addVertex(matrix, blX, blY, 0.1f).setColor(r, g, b, a);
        buffer.addVertex(matrix, 0.0f, indY, 0.1f).setColor(r, g, b, a);
        // Right half: tip -> ind -> br
        buffer.addVertex(matrix, 0.0f, tipY, 0.1f).setColor(r, g, b, a);
        buffer.addVertex(matrix, 0.0f, indY, 0.1f).setColor(r, g, b, a);
        buffer.addVertex(matrix, brX, brY, 0.1f).setColor(r, g, b, a);

        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static final class RenderStats {
        private int exactPages;
        private int branchNodes;
        private int legacyFallbacks;

        private void accept(MapDrawResult result) {
            if (result == null) return;
            exactPages += result.exactPagesDrawn();
            branchNodes += result.branchNodesDrawn();
            legacyFallbacks += result.legacyFallbacksDrawn();
        }

        private MapDrawResult snapshot() {
            return new MapDrawResult(exactPages, branchNodes, legacyFallbacks);
        }
    }

    private static boolean compatibleFullscreenFallback(
            PlanKey previous, PlanKey target) {
        if (previous == null || target == null
                || !previous.dimension().equals(target.dimension())
                || previous.caveMode() != target.caveMode()
                || previous.fullCaveView() != target.fullCaveView()
                || previous.caveLayerY() != target.caveLayerY()) return false;
        return previous.maxRegionX() >= target.minRegionX()
                && previous.minRegionX() <= target.maxRegionX()
                && previous.maxRegionZ() >= target.minRegionZ()
                && previous.minRegionZ() <= target.maxRegionZ();
    }

    private record PlanKey(String dimension, boolean caveMode,
            boolean fullCaveView, int caveLayerY,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int hierarchyLevel, boolean caveBranchOnly, int renderScaleClass,
            int attentionPageX, int attentionPageZ, int nightMode) {
    }

    private record CachedPlan(PlanKey key, MapRenderPlan plan,
            long contentRevision) {
    }

}
