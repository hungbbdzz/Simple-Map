package com.velorise.simplemap.client;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public class MinimapRenderer {
    private static final MinimapRenderer INSTANCE = new MinimapRenderer();
    private static final ResourceLocation PLAYER_OFF_MAP_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    "minecraft", "textures/map/decorations/player_off_map.png");
    private static final ResourceLocation RED_X_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    "minecraft", "textures/map/decorations/red_x.png");
    private static final int CIRCLE_SEGMENTS = 64;
    private static final float[] CIRCLE_COS = new float[CIRCLE_SEGMENTS + 1];
    private static final float[] CIRCLE_SIN = new float[CIRCLE_SEGMENTS + 1];
    static {
        for (int index = 0; index <= CIRCLE_SEGMENTS; index++) {
            double angle = index * (Math.PI * 2.0 / CIRCLE_SEGMENTS);
            CIRCLE_COS[index] = (float) Math.cos(angle);
            CIRCLE_SIN[index] = (float) Math.sin(angle);
        }
    }
    private int cachedCoordinateX = Integer.MIN_VALUE;
    private int cachedCoordinateY = Integer.MIN_VALUE;
    private int cachedCoordinateZ = Integer.MIN_VALUE;
    private String cachedCoordinates = "";
    private static final long CAVE_ZOOM_ANIMATION_NANOS = 1_000_000_000L;
    private static final float CAVE_ZOOM_MULTIPLIER = 1.55f;
    /** Both square and circular minimaps use the retained north-up composition. */
    private static final boolean USE_RETAINED_FRAMEBUFFER = true;

    private boolean caveAnimationInitialized;
    private boolean lastCaveActive;
    private long caveAnimationStartNanos;
    private float caveZoomFrom = 1.0f;
    private float caveZoomTo = 1.0f;
    private float caveZoomMultiplier = 1.0f;

    private long worldJoinTimeNanos;

    public static MinimapRenderer getInstance() {
        return INSTANCE;
    }

    private MinimapRenderer() {
    }

    public void onWorldJoin() {
        this.worldJoinTimeNanos = System.nanoTime();
        MinimapFramebufferRenderer.getInstance().resetFailureState();
    }

    public void onWorldLeave() {
        MinimapFramebufferRenderer.getInstance().destroy();
    }

    public static boolean isAllowedScreenForMinimap(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null) return false;
        // Hide minimap on the mod's own full-screen views
        if (screen instanceof MapScreen || screen instanceof MapConfigScreen
                || screen instanceof AddWaypointScreen || screen instanceof BlockColorScreen
                || screen instanceof BlockColorManagerScreen) {
            return false;
        }
        // Hide minimap on Minecraft system screens (loading, saving, title, connecting...)
        String className = screen.getClass().getSimpleName();
        return !className.contains("Loading") && !className.contains("Saving")
                && !className.contains("Progress") && !className.contains("Receiving")
                && !className.contains("Connect") && !className.contains("Title")
                && !className.contains("WorldSelection") && !className.contains("DirtMessage")
                && !className.contains("Win");
    }

    private float getAnimatedZoom(Minecraft mc) {
        boolean caveActive = CaveMode.isActive(mc);
        long now = System.nanoTime();
        if (!caveAnimationInitialized) {
            caveAnimationInitialized = true;
            lastCaveActive = caveActive;
            caveZoomMultiplier = caveActive ? CAVE_ZOOM_MULTIPLIER : 1.0f;
            caveZoomFrom = caveZoomMultiplier;
            caveZoomTo = caveZoomMultiplier;
        } else if (caveActive != lastCaveActive) {
            caveZoomMultiplier = evaluateCaveZoom(now);
            caveZoomFrom = caveZoomMultiplier;
            caveZoomTo = caveActive ? CAVE_ZOOM_MULTIPLIER : 1.0f;
            caveAnimationStartNanos = now;
            lastCaveActive = caveActive;
        }
        caveZoomMultiplier = evaluateCaveZoom(now);
        return MapConfig.minimapZoom * caveZoomMultiplier;
    }

    private float evaluateCaveZoom(long now) {
        if (caveAnimationStartNanos == 0L || caveZoomFrom == caveZoomTo) return caveZoomTo;
        float progress = Math.min(1.0f,
                (now - caveAnimationStartNanos) / (float) CAVE_ZOOM_ANIMATION_NANOS);
        float eased = progress * progress * (3.0f - 2.0f * progress);
        if (progress >= 1.0f) caveAnimationStartNanos = 0L;
        return caveZoomFrom + (caveZoomTo - caveZoomFrom) * eased;
    }

    /**
     * Renders the minimap HUD overlay on the screen during gameplay.
     */
    public void renderHUD(GuiGraphics guiGraphics, float partialTick) {
        renderHUD(guiGraphics, partialTick, false);
    }

    public void renderHUD(GuiGraphics guiGraphics, float partialTick, boolean screenOverlay) {
        Minecraft mc = Minecraft.getInstance();

        long now = System.nanoTime();
        if (worldJoinTimeNanos != 0L && now - worldJoinTimeNanos < 1_500_000_000L) {
            return;
        }

        // Normal HUD rendering and screen-overlay rendering are dispatched separately to
        // avoid drawing the minimap twice while a menu is open.
        if (!MapConfig.minimapEnabled || mc.level == null || mc.player == null || mc.options.hideGui
                || mc.getOverlay() != null || mc.player.tickCount < 20
                || (!screenOverlay && mc.screen != null)
                || (screenOverlay && (mc.screen == null || !MapConfig.showMinimapInScreens || !isAllowedScreenForMinimap(mc.screen)))) {
            return;
        }

        // Check if map is unlocked (learned map + holding book if requireMapBook is
        // enabled)
        if (!SimpleMap.isMapUnlocked(mc.player)) {
            return;
        }

        Player player = mc.player;
        float effectiveZoom = getAnimatedZoom(mc);
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int size = MapConfig.minimapSize;

        int[] position = MinimapPosition.resolve(screenWidth, screenHeight, size);
        int x = position[0];
        int y = position[1];

        int borderThickness = Math.max(2, size / 32);
        int tHalf = Math.max(1, borderThickness / 2);
        double interpX = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX());
        double interpZ = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ());
        com.velorise.simplemap.client.minimap.MinimapService.getInstance()
                .update(interpX, interpZ, effectiveZoom);
        // Retained rendering can reuse the same texture for many visual frames,
        // but tick-side visible demand expires after 500 ms. Refresh only the
        // lightweight viewport intent here so stable FBO reuse never suspends
        // scanning/IO/publication around a stationary player.
        double demandFactor = MinimapFramebufferRenderer.demandCoverageFactor(
                MapConfig.minimapRotate);
        double demandHalf = (size * 0.5 / Math.max(0.0001f, effectiveZoom))
                * demandFactor;
        MapViewportCoordinator.getInstance().submitMinimap(
                interpX - demandHalf, interpX + demandHalf,
                interpZ - demandHalf, interpZ + demandHalf, effectiveZoom);

        if (MapConfig.minimapCircle) {
            renderCircularMinimap(guiGraphics, mc, player, x, y, size, borderThickness, tHalf,
                    interpX, interpZ, effectiveZoom, partialTick);
        } else {
            // ==========================================
            // SQUARE MINIMAP (Original Sleek Borders)
            // ==========================================
            guiGraphics.renderOutline(x - borderThickness - 1, y - borderThickness - 1,
                    size + borderThickness * 2 + 2, size + borderThickness * 2 + 2, 0xFF000000);
            // Main colored border
            guiGraphics.fill(x - borderThickness, y - borderThickness, x + size + borderThickness, y - tHalf,
                    MapConfig.minimapRingColor); // Top
            guiGraphics.fill(x - borderThickness, y + size + tHalf, x + size + borderThickness,
                    y + size + borderThickness, MapConfig.minimapRingColor); // Bottom
            guiGraphics.fill(x - borderThickness, y - tHalf, x - tHalf, y + size + tHalf,
                    MapConfig.minimapRingColor); // Left
            guiGraphics.fill(x + size + tHalf, y - tHalf, x + size + borderThickness, y + size + tHalf,
                    MapConfig.minimapRingColor); // Right

            // Inner slate outline (thickness tHalf)
            guiGraphics.fill(x - tHalf, y - tHalf, x + size + tHalf, y, MapConfig.minimapRingColor); // Top
            guiGraphics.fill(x - tHalf, y + size, x + size + tHalf, y + size + tHalf, MapConfig.minimapRingColor); // Bottom
            guiGraphics.fill(x - tHalf, y, x, y + size, MapConfig.minimapRingColor); // Left
            guiGraphics.fill(x + size, y, x + size + tHalf, y + size, MapConfig.minimapRingColor); // Right

            // Draw map through the fixed-resolution minimap target. If the GPU or
            // another renderer rejects the FBO path, direct rendering remains a
            // session-safe fallback.
            renderMapContent(guiGraphics, x, y, size, interpX, interpZ,
                    effectiveZoom, partialTick);
            // The player marker is a HUD overlay, not map-texture content. Keeping it
            // outside the FBO makes it survive empty/cold pages and framebuffer fallback.
            MapRenderer.getInstance().drawMinimapPlayerOverlay(
                    guiGraphics, x + size / 2.0f, y + size / 2.0f,
                    MapConfig.minimapRotate, partialTick);

            // Draw Compass Directions on the square borders
            float cx = x + size / 2.0f;
            float cy = y + size / 2.0f;
            float radius = size / 2.0f;
            drawCompassDirections(guiGraphics, mc, cx, cy, radius, borderThickness, player, false, partialTick);
        }

        // 3. Draw coordinate overlay text with dynamic scale and position
        if (MapConfig.coordsEnabled) {
            String coords = coordinateText(player);
            int textWidth = (int) (mc.font.width(coords) * MapConfig.coordsScale);
            int textHeight = (int) (9 * MapConfig.coordsScale);

            int cx, cy;
            if (MapConfig.coordsXPercent < 0 || MapConfig.coordsYPercent < 0) {
                // Default: snapped underneath the minimap
                cx = x + (size - textWidth) / 2;
                cy = y + size + borderThickness + 2;
            } else {
                cx = (int) (screenWidth * MapConfig.coordsXPercent);
                cy = (int) (screenHeight * MapConfig.coordsYPercent);
            }

            // Clamp coordinates boundaries
            cx = Math.max(2, Math.min(cx, screenWidth - textWidth - 2));
            cy = Math.max(2, Math.min(cy, screenHeight - textHeight - 2));

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(cx, cy, 0);
            guiGraphics.pose().scale(MapConfig.coordsScale, MapConfig.coordsScale, 1.0f);

            int rawWidth = mc.font.width(coords);
            guiGraphics.fill(-3, -2, rawWidth + 3, 9, 0x88000000);
            int coordsColor = MapRenderer.getInstance().getActualPointerColor(MapConfig.coordsTextColor);
            guiGraphics.drawString(mc.font, coords, 0, 0, coordsColor, false);
            guiGraphics.pose().popPose();
        }

        // 4. Draw pin navigation marker on minimap
        if (MapConfig.pinActive) {
            drawPinOnMinimap(guiGraphics, player, x, y, size, borderThickness,
                    effectiveZoom, partialTick);
        }
    }


    private String coordinateText(Player player) {
        int x = (int) Math.floor(player.getX());
        int y = (int) Math.floor(player.getY());
        int z = (int) Math.floor(player.getZ());
        if (x == cachedCoordinateX && y == cachedCoordinateY
                && z == cachedCoordinateZ) return cachedCoordinates;
        cachedCoordinateX = x;
        cachedCoordinateY = y;
        cachedCoordinateZ = z;
        cachedCoordinates = x + ", " + y + ", " + z;
        return cachedCoordinates;
    }

    private void renderMapContent(GuiGraphics guiGraphics, int x, int y, int size,
            double centerX, double centerZ, float effectiveZoom, float partialTick) {
        if (USE_RETAINED_FRAMEBUFFER) {
            boolean rendered = MinimapFramebufferRenderer.getInstance().render(
                    guiGraphics, x, y, size, centerX, centerZ, effectiveZoom,
                    MapConfig.minimapRotate, partialTick);
            if (rendered) return;
        }

        // This fallback is reached only after a framebuffer/render failure. The
        // circular path keeps its active stencil test, so direct terrain replay is
        // still clipped correctly when retained composition is unavailable.
        // MapRenderer owns the single depth/blend/flush scope for direct atlas
        // replay. Wrapping it again here added another synchronous GL state query and
        // flush to every HUD frame without changing visibility.
        guiGraphics.fill(x, y, x + size, y + size, 0xFF080A0C);
        MapRenderer.getInstance().drawMap(
                guiGraphics, x, y, size, size, centerX, centerZ, effectiveZoom,
                false, MapConfig.minimapRotate, true, 0, 0, partialTick);
    }

    private void renderCircularMinimap(GuiGraphics guiGraphics, Minecraft mc, Player player,
            int x, int y, int size, int borderThickness, int tHalf,
            double interpX, double interpZ, float effectiveZoom, float partialTick) {
        float radius = size / 2.0f;
        float cx = x + radius;
        float cy = y + radius;
        float clipRadius = radius - tHalf;
        boolean depthWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(
                org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        boolean stencilWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(
                org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
        // Stencil is disabled in the normal GUI path. Querying seven additional GL
        // values every minimap frame forced driver synchronization even though
        // there was no state to preserve. Pay that compatibility cost only when a
        // previous renderer actually left stencil enabled.
        int previousStencilFunc = org.lwjgl.opengl.GL11.GL_ALWAYS;
        int previousStencilRef = 0;
        int previousStencilValueMask = 0xFF;
        int previousStencilWriteMask = 0xFF;
        int previousStencilFail = org.lwjgl.opengl.GL11.GL_KEEP;
        int previousStencilDepthFail = org.lwjgl.opengl.GL11.GL_KEEP;
        int previousStencilDepthPass = org.lwjgl.opengl.GL11.GL_KEEP;
        if (stencilWasEnabled) {
            previousStencilFunc = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_FUNC);
            previousStencilRef = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_REF);
            previousStencilValueMask = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_VALUE_MASK);
            previousStencilWriteMask = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_WRITEMASK);
            previousStencilFail = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_FAIL);
            previousStencilDepthFail = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_PASS_DEPTH_FAIL);
            previousStencilDepthPass = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_STENCIL_PASS_DEPTH_PASS);
        }

        mc.getMainRenderTarget().enableStencil();
        try {
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
            org.lwjgl.opengl.GL11.glStencilMask(0xFF);
            org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT);
            org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_ALWAYS, 1, 0xFF);
            org.lwjgl.opengl.GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_REPLACE,
                    org.lwjgl.opengl.GL11.GL_REPLACE, org.lwjgl.opengl.GL11.GL_REPLACE);
            org.lwjgl.opengl.GL11.glColorMask(false, false, false, false);
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);

            float maskRadius = clipRadius + 1.5f;
            // One buffered 64-segment fan replaces ~2*radius individual GUI fill
            // commands and square-root calculations on every HUD frame.
            drawSolidCircle(guiGraphics, cx, cy, maskRadius,
                    CIRCLE_SEGMENTS, 0xFFFFFFFF);
            guiGraphics.flush();

            org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
            org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_EQUAL, 1, 0xFF);
            org.lwjgl.opengl.GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_KEEP,
                    org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_KEEP);
            org.lwjgl.opengl.GL11.glStencilMask(0x00);

            renderMapContent(guiGraphics, x, y, size, interpX, interpZ,
                    effectiveZoom, partialTick);
            guiGraphics.flush();
        } finally {
            // A render exception must never leave Minecraft with color writes, depth or
            // stencil state disabled for the rest of the HUD frame.
            org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
            org.lwjgl.opengl.GL11.glStencilFunc(previousStencilFunc, previousStencilRef, previousStencilValueMask);
            org.lwjgl.opengl.GL11.glStencilOp(previousStencilFail, previousStencilDepthFail, previousStencilDepthPass);
            org.lwjgl.opengl.GL11.glStencilMask(previousStencilWriteMask);
            if (stencilWasEnabled) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
            else org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
            if (depthWasEnabled) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            else org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        }

        MapRenderer.getInstance().drawMinimapPlayerOverlay(
                guiGraphics, cx, cy, MapConfig.minimapRotate, partialTick);
        drawCircleRing(guiGraphics, cx, cy, clipRadius, radius, CIRCLE_SEGMENTS, MapConfig.minimapRingColor);
        drawCircleRing(guiGraphics, cx, cy, radius, radius + borderThickness, CIRCLE_SEGMENTS, MapConfig.minimapRingColor);
        drawCircleRing(guiGraphics, cx, cy, radius + borderThickness, radius + borderThickness + 1, CIRCLE_SEGMENTS,
                0xFF000000);
        guiGraphics.flush();
        drawCompassDirections(guiGraphics, mc, cx, cy, radius, borderThickness, player, true, partialTick);
    }

    private void drawCompassDirections(GuiGraphics guiGraphics, Minecraft mc, float cx, float cy, float radius,
            float borderThickness, Player player, boolean isCircle, float partialTick) {
        if (!MapConfig.compassLettersVisible) {
            return;
        }

        float playerYaw;
        if (MapConfig.minimapRotate) {
            playerYaw = net.minecraft.util.Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
        } else {
            playerYaw = 180.0f; // North is fixed at top
        }

        // Screen angles in degrees (0 is East/Right, 90 is South/Bottom, 180 is
        // West/Left, 270/-90 is North/Top)
        float angleN = -playerYaw + 90.0f;
        float angleE = -playerYaw + 180.0f;
        float angleS = -playerYaw + 270.0f;
        float angleW = -playerYaw;

        // Render direction labels along the middle of the border
        float limit;
        if (isCircle) {
            limit = radius * 0.915f;
        } else {
            limit = radius + borderThickness / 2.0f;
        }

        int letterColor = MapRenderer.getInstance().getActualPointerColor(MapConfig.compassLetterColor);
        drawDirectionLetter(guiGraphics, mc, cx, cy, limit, angleN, "N", letterColor, isCircle);
        drawDirectionLetter(guiGraphics, mc, cx, cy, limit, angleE, "E", letterColor, isCircle);
        drawDirectionLetter(guiGraphics, mc, cx, cy, limit, angleS, "S", letterColor, isCircle);
        drawDirectionLetter(guiGraphics, mc, cx, cy, limit, angleW, "W", letterColor, isCircle);
    }

    private void drawDirectionLetter(GuiGraphics guiGraphics, Minecraft mc, float cx, float cy, float limit,
            float angleDegrees, String letter, int color, boolean isCircle) {
        float rad = (float) Math.toRadians(angleDegrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float x, y;
        if (isCircle) {
            x = cx + limit * cos;
            y = cy + limit * sin;
        } else {
            // Project onto the square border line
            float scale = 1.0f / Math.max(Math.abs(cos), Math.abs(sin));
            x = cx + limit * cos * scale;
            y = cy + limit * sin * scale;
        }

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0);
        guiGraphics.pose().scale(0.7f, 0.7f, 1.0f); // Scale down slightly to fit on the border nicely

        int textWidth = mc.font.width(letter);
        guiGraphics.drawString(mc.font, letter, -textWidth / 2, -4, color, true);
        guiGraphics.pose().popPose();
    }

    /**
     * Draws the pin navigation marker on the minimap
     */
    private void drawPinOnMinimap(GuiGraphics guiGraphics, Player player, int mx, int my,
            int size, int borderThickness, float effectiveZoom, float partialTick) {
        double playerX = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX());
        double playerZ = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ());
        double zoom = effectiveZoom;

        float cx = mx + size / 2.0f;
        float cy = my + size / 2.0f;
        double worldDX = MapConfig.pinWorldX - playerX;
        double worldDZ = MapConfig.pinWorldZ - playerZ;

        // Prefer the exact retained-FBO composition transform. Terrain can reuse a
        // snapped physical-pixel anchor for many HUD frames; projecting the pin from
        // an independently rounded live formula is mathematically close but can
        // oscillate by one logical pixel relative to the texture. Reading the final
        // crop/scale/yaw transform pins the marker to the same map pixels.
        MinimapFramebufferRenderer framebuffer = MinimapFramebufferRenderer.getInstance();
        MinimapFramebufferRenderer.HudPoint pinPoint = framebuffer.projectWorldToHud(
                MapConfig.pinWorldX, MapConfig.pinWorldZ);

        double cos = 1.0;
        double sin = 0.0;
        if (MapConfig.minimapRotate) {
            float playerYaw = net.minecraft.util.Mth.rotLerp(partialTick,
                    player.yRotO, player.getYRot());
            double angleRad = Math.toRadians(-playerYaw - 180.0f);
            cos = Math.cos(angleRad);
            sin = Math.sin(angleRad);
        }
        float scrDX;
        float scrDZ;
        if (pinPoint != null) {
            scrDX = pinPoint.x() - cx;
            scrDZ = pinPoint.y() - cy;
        } else {
            scrDX = (float) ((worldDX * cos - worldDZ * sin) * zoom);
            scrDZ = (float) ((worldDX * sin + worldDZ * cos) * zoom);
        }
        float dist2D = (float) Math.sqrt(scrDX * scrDX + scrDZ * scrDZ);

        float iconX;
        float iconZ;
        boolean offMap = false;
        if (MapConfig.minimapCircle) {
            float maxCircleRadius = size / 2.0f + borderThickness / 2.0f;
            if (dist2D <= maxCircleRadius || dist2D <= 0.0001f) {
                iconX = cx + scrDX;
                iconZ = cy + scrDZ;
            } else {
                iconX = cx + (scrDX / dist2D) * maxCircleRadius;
                iconZ = cy + (scrDZ / dist2D) * maxCircleRadius;
                offMap = true;
            }
        } else {
            float limit = size / 2.0f + borderThickness / 2.0f;
            if (Math.abs(scrDX) <= limit && Math.abs(scrDZ) <= limit) {
                iconX = cx + scrDX;
                iconZ = cy + scrDZ;
            } else {
                float maxVal = Math.max(Math.abs(scrDX), Math.abs(scrDZ));
                float t = maxVal > 0.0001f ? limit / maxVal : 0.0f;
                iconX = maxVal > 0.0001f ? cx + scrDX * t : cx;
                iconZ = maxVal > 0.0001f ? cy + scrDZ * t : cy;
                offMap = true;
            }
        }

        int markerSize = Math.max(2, (int) (8 * MapConfig.pinScale));
        int halfSize = markerSize / 2;

        // Navigation geometry is always live player -> destination. Dot phase is
        // destination-anchored and selected only after the complete world segment is
        // known, so offscreen clipping cannot re-space the guide line.
        PinNavigation.Route route = PinNavigation.currentRoute(playerX, playerZ);
        if (route != null && zoom > 0.0001) {
            double halfWorld = (size * 0.5 + borderThickness + 2.0)
                    * 1.5 / zoom;
            PinNavigation.DotRange dots = PinNavigation.visibleDots(route,
                    playerX - halfWorld, playerX + halfWorld,
                    playerZ - halfWorld, playerZ + halfWorld,
                    5.0 / zoom, PinNavigation.MAX_VISIBLE_ROUTE_DOTS);
            if (!dots.isEmpty()) {
                int pointerColor = MapRenderer.getInstance().getActualPointerColor(
                        MapConfig.playerPointerColor);
                int routeColor = (pointerColor & 0x00FFFFFF) | 0xCC000000;
                float routeLimit = Math.max(1.0f, size * 0.5f - 1.0f);
                for (int index = dots.firstIndex();
                        index <= dots.lastIndex(); index += dots.stride()) {
                    double dotWorldX = PinNavigation.dotWorldX(route, index);
                    double dotWorldZ = PinNavigation.dotWorldZ(route, index);
                    MinimapFramebufferRenderer.HudPoint dotPoint =
                            framebuffer.projectWorldToHud(dotWorldX, dotWorldZ);
                    float dotScreenX;
                    float dotScreenZ;
                    if (dotPoint != null) {
                        dotScreenX = dotPoint.x() - cx;
                        dotScreenZ = dotPoint.y() - cy;
                    } else {
                        double dotDX = dotWorldX - playerX;
                        double dotDZ = dotWorldZ - playerZ;
                        dotScreenX = (float) ((dotDX * cos - dotDZ * sin) * zoom);
                        dotScreenZ = (float) ((dotDX * sin + dotDZ * cos) * zoom);
                    }
                    boolean inside = MapConfig.minimapCircle
                            ? dotScreenX * dotScreenX + dotScreenZ * dotScreenZ
                                    <= routeLimit * routeLimit
                            : Math.abs(dotScreenX) <= routeLimit
                                    && Math.abs(dotScreenZ) <= routeLimit;
                    if (!inside) continue;
                    guiGraphics.pose().pushPose();
                    guiGraphics.pose().translate(cx + dotScreenX,
                            cy + dotScreenZ, 0.0f);
                    guiGraphics.fill(0, 0, 1, 1, routeColor);
                    guiGraphics.pose().popPose();
                }
            }
        }

        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        // Keep the icon on the fractional HUD coordinate. Rounding the marker while
        // the retained texture scrolls subpixel was the remaining visible jitter.
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(iconX, iconZ, 0.0f);
        if (offMap) {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 0.0f, 0.0f, 1.0f);
            guiGraphics.blit(PLAYER_OFF_MAP_TEXTURE,
                    -halfSize, -halfSize, markerSize, markerSize,
                    0.0f, 0.0f, 8, 8, 8, 8);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        } else {
            guiGraphics.blit(RED_X_TEXTURE,
                    -halfSize, -halfSize, markerSize, markerSize,
                    0.0f, 0.0f, 8, 8, 8, 8);
        }
        guiGraphics.pose().popPose();

        double dist3D = Math.sqrt(worldDX * worldDX + worldDZ * worldDZ);
        String label;
        if (dist3D >= 1000) {
            long tenths = Math.round(dist3D / 100.0);
            label = (tenths / 10) + "." + Math.abs(tenths % 10) + "k blocks";
        } else {
            label = (int) dist3D + "m";
        }
        Minecraft mc = Minecraft.getInstance();
        float textScale = 0.6f * (MapConfig.pinScale / 0.5f);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(iconX, iconZ + halfSize + 2.0f, 0.0f);
        guiGraphics.pose().scale(textScale, textScale, 1.0f);
        int rawWidth = mc.font.width(label);
        guiGraphics.fill(-rawWidth / 2 - 2, -1, rawWidth / 2 + 2, 8, 0xAA000000);
        guiGraphics.drawString(mc.font, label, -rawWidth / 2, 0, 0xFFFFFF, false);
        guiGraphics.pose().popPose();
    }

    private void drawSolidCircle(GuiGraphics guiGraphics, float cx, float cy, float radius, int numSegments,
            int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        org.joml.Matrix4f matrix = guiGraphics.pose().last().pose();
        net.minecraft.client.renderer.MultiBufferSource bufferSource = guiGraphics.bufferSource();
        com.mojang.blaze3d.vertex.VertexConsumer consumer = bufferSource
                .getBuffer(net.minecraft.client.renderer.RenderType.gui());

        double angleStep = 2.0 * Math.PI / numSegments;
        for (int i = 0; i < numSegments; i++) {
            float cos1 = numSegments == CIRCLE_SEGMENTS
                    ? CIRCLE_COS[i] : (float) Math.cos(i * angleStep);
            float sin1 = numSegments == CIRCLE_SEGMENTS
                    ? CIRCLE_SIN[i] : (float) Math.sin(i * angleStep);
            float cos2 = numSegments == CIRCLE_SEGMENTS
                    ? CIRCLE_COS[i + 1] : (float) Math.cos((i + 1) * angleStep);
            float sin2 = numSegments == CIRCLE_SEGMENTS
                    ? CIRCLE_SIN[i + 1] : (float) Math.sin((i + 1) * angleStep);

            float x1 = cx + radius * cos1;
            float y1 = cy + radius * sin1;
            float x2 = cx + radius * cos2;
            float y2 = cy + radius * sin2;

            // Degenerate Quad (4 vertices representing a triangle)
            consumer.addVertex(matrix, cx, cy, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, x1, y1, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, x2, y2, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx, cy, 0.0f).setColor(r, g, b, a);
        }
    }

    private void drawCircleRing(GuiGraphics guiGraphics, float cx, float cy, float r1, float r2, int numSegments,
            int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        org.joml.Matrix4f matrix = guiGraphics.pose().last().pose();
        net.minecraft.client.renderer.MultiBufferSource bufferSource = guiGraphics.bufferSource();
        com.mojang.blaze3d.vertex.VertexConsumer consumer = bufferSource
                .getBuffer(net.minecraft.client.renderer.RenderType.gui());

        double angleStep = 2.0 * Math.PI / numSegments;
        for (int i = 0; i < numSegments; i++) {
            float cos1 = numSegments == CIRCLE_SEGMENTS
                    ? CIRCLE_COS[i] : (float) Math.cos(i * angleStep);
            float sin1 = numSegments == CIRCLE_SEGMENTS
                    ? CIRCLE_SIN[i] : (float) Math.sin(i * angleStep);
            float cos2 = numSegments == CIRCLE_SEGMENTS
                    ? CIRCLE_COS[i + 1] : (float) Math.cos((i + 1) * angleStep);
            float sin2 = numSegments == CIRCLE_SEGMENTS
                    ? CIRCLE_SIN[i + 1] : (float) Math.sin((i + 1) * angleStep);

            consumer.addVertex(matrix, cx + r1 * cos1, cy + r1 * sin1, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx + r1 * cos2, cy + r1 * sin2, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx + r2 * cos2, cy + r2 * sin2, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx + r2 * cos1, cy + r2 * sin1, 0.0f).setColor(r, g, b, a);
        }
    }
}
