package com.velorise.simplemap.client;
import com.velorise.simplemap.client.MapManager.MapConfig;
import com.velorise.simplemap.client.gui.MapScreen.MapConfigScreen;
import com.velorise.simplemap.client.gui.MapScreen.MapConfigScreen.WaypointListScreen;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager.Waypoint;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.renderer.*;

import com.velorise.simplemap.SimpleMap;
import com.velorise.simplemap.client.waypoint.*;
import com.velorise.simplemap.client.gui.*;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

public class MinimapRenderer {
    private static final MinimapRenderer INSTANCE = new MinimapRenderer();
    private static final ResourceLocation PLAYER_OFF_MAP_TEXTURE =
            new ResourceLocation(
                    "simplemap", "textures/gui/player_off_map.png");
    private static final ResourceLocation RED_X_TEXTURE =
            new ResourceLocation(
                    "simplemap", "textures/gui/red_x.png");
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
    private String cachedCoordinateDim = "";
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
                || screen instanceof WaypointListScreen.AddWaypointScreen || screen instanceof MapConfigScreen.BlockColorManagerScreen.BlockColorScreen
                || screen instanceof MapConfigScreen.BlockColorManagerScreen) {
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
        boolean caveActive = CaveMode.isActive(mc) && MapConfig.caveAutoZoom;
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
        double dimScale = MapManager.getInstance().getCrossDimensionCoordinateScale();
        double interpX = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX()) * dimScale;
        double interpZ = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ()) * dimScale;
        MinimapService.getInstance()
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

        // 4. Draw waypoint item markers on minimap (projected and border-clamped like Pin)
        if (MapConfig.waypointsVisible && MapConfig.minimapWaypointsVisible) {
            drawWaypointsOnMinimap(guiGraphics, player, x, y, size, borderThickness,
                    effectiveZoom, partialTick);
        }

        // 5. Draw pin navigation marker on minimap
        if (MapConfig.pinActive) {
            drawPinOnMinimap(guiGraphics, player, x, y, size, borderThickness,
                    effectiveZoom, partialTick);
        }
    }


    private String coordinateText(Player player) {
        int x = (int) Math.floor(player.getX());
        int y = (int) Math.floor(player.getY());
        int z = (int) Math.floor(player.getZ());
        MapManager manager = MapManager.getInstance();
        String viewDim = manager.isViewingLiveDimension() ? "LIVE" : manager.getCurrentDimensionResourceId();
        if (x == cachedCoordinateX && y == cachedCoordinateY
                && z == cachedCoordinateZ && viewDim.equals(cachedCoordinateDim)) {
            return cachedCoordinates;
        }
        cachedCoordinateX = x;
        cachedCoordinateY = y;
        cachedCoordinateZ = z;
        cachedCoordinateDim = viewDim;
        if (manager.isViewingLiveDimension()) {
            cachedCoordinates = x + ", " + y + ", " + z;
        } else {
            double dimScale = manager.getCrossDimensionCoordinateScale();
            int mappedX = (int) Math.floor(player.getX() * dimScale);
            int mappedZ = (int) Math.floor(player.getZ() * dimScale);
            String dimLabel = MapManager.displayDimensionName(viewDim);
            cachedCoordinates = x + ", " + y + ", " + z + " (" + dimLabel + ": " + mappedX + ", " + mappedZ + ")";
        }
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
        float ringWidth = Math.max(1.5f, size / 72.0f);
        float innerRingRadius = radius - 0.5f;
        float outerRingRadius = innerRingRadius + ringWidth;

        // Draw dark circular backdrop first so cold/unexplored areas are cleanly masked
        drawSolidCircle(guiGraphics, cx, cy, radius, CIRCLE_SEGMENTS, 0xFF080A0C);
        guiGraphics.flush();

        // Retained FBO draws a 64-segment circular mesh directly from its texture,
        // avoiding fragile GL_STENCIL_TEST state interactions across FBO switches.
        boolean renderedViaFbo = USE_RETAINED_FRAMEBUFFER
                && MinimapFramebufferRenderer.getInstance().render(
                        guiGraphics, x, y, size, interpX, interpZ, effectiveZoom,
                        MapConfig.minimapRotate, partialTick);
        if (!renderedViaFbo) {
            boolean depthWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(
                    org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            boolean stencilWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(
                    org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
            try {
                java.lang.reflect.Method enableStencil = mc.getMainRenderTarget().getClass().getMethod("enableStencil");
                enableStencil.invoke(mc.getMainRenderTarget());
            } catch (ReflectiveOperationException ignored) {
                // Vanilla/Fabric RenderTarget does not have Forge's enableStencil() patch.
            }
            try {
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
                org.lwjgl.opengl.GL11.glStencilMask(0xFF);
                org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT);
                org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_ALWAYS, 1, 0xFF);
                org.lwjgl.opengl.GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_REPLACE,
                        org.lwjgl.opengl.GL11.GL_REPLACE, org.lwjgl.opengl.GL11.GL_REPLACE);
                org.lwjgl.opengl.GL11.glColorMask(false, false, false, false);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                drawSolidCircle(guiGraphics, cx, cy, radius, CIRCLE_SEGMENTS, 0xFFFFFFFF);
                guiGraphics.flush();
                org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
                org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_EQUAL, 1, 0xFF);
                org.lwjgl.opengl.GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_KEEP,
                        org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_KEEP);
                org.lwjgl.opengl.GL11.glStencilMask(0x00);
                MapRenderer.getInstance().drawMap(
                        guiGraphics, x, y, size, size, interpX, interpZ, effectiveZoom,
                        false, MapConfig.minimapRotate, true, 0, 0, partialTick);
                guiGraphics.flush();
            } finally {
                org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
                org.lwjgl.opengl.GL11.glStencilMask(0xFF);
                if (stencilWasEnabled) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
                else org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
                if (depthWasEnabled) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                else org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            }
        }

        MapRenderer.getInstance().drawMinimapPlayerOverlay(
                guiGraphics, cx, cy, MapConfig.minimapRotate, partialTick);
        // Sleek, thin circular bezel ring
        drawCircleRing(guiGraphics, cx, cy, innerRingRadius - 0.5f, innerRingRadius, CIRCLE_SEGMENTS, 0x77000000);
        drawCircleRing(guiGraphics, cx, cy, innerRingRadius, outerRingRadius, CIRCLE_SEGMENTS, MapConfig.minimapRingColor);
        drawCircleRing(guiGraphics, cx, cy, outerRingRadius, outerRingRadius + 0.75f, CIRCLE_SEGMENTS, 0xE0000000);
        guiGraphics.flush();
        drawCompassDirections(guiGraphics, mc, cx, cy, radius, ringWidth, player, true, partialTick);
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
        drawDirectionLetter(guiGraphics, mc, cx, cy, limit, angleN, net.minecraft.network.chat.Component.translatable("gui.simplemap.compass.n").getString(), letterColor, isCircle);
        drawDirectionLetter(guiGraphics, mc, cx, cy, limit, angleE, net.minecraft.network.chat.Component.translatable("gui.simplemap.compass.e").getString(), letterColor, isCircle);
        drawDirectionLetter(guiGraphics, mc, cx, cy, limit, angleS, net.minecraft.network.chat.Component.translatable("gui.simplemap.compass.s").getString(), letterColor, isCircle);
        drawDirectionLetter(guiGraphics, mc, cx, cy, limit, angleW, net.minecraft.network.chat.Component.translatable("gui.simplemap.compass.w").getString(), letterColor, isCircle);
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
     * Draws waypoint item icons on the minimap HUD (staying upright when rotating
     * and clamping to the minimap frame when off-map, just like the navigation pin).
     */
    private void drawWaypointsOnMinimap(GuiGraphics guiGraphics, Player player, int mx, int my,
            int size, int borderThickness, float effectiveZoom, float partialTick) {
        java.util.List<WaypointManager.Waypoint> waypoints = WaypointManager.getInstance()
                .getWaypointsForDimension(
                        MapManager.getInstance().getCurrentDimensionResourceId());
        if (waypoints == null || waypoints.isEmpty()) return;

        double dimScale = MapManager.getInstance().getCrossDimensionCoordinateScale();
        double playerX = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX()) * dimScale;
        double playerZ = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ()) * dimScale;
        double zoom = effectiveZoom;

        float cx = mx + size / 2.0f;
        float cy = my + size / 2.0f;
        MinimapFramebufferRenderer framebuffer = MinimapFramebufferRenderer.getInstance();

        double cos = 1.0;
        double sin = 0.0;
        if (MapConfig.minimapRotate) {
            float playerYaw = net.minecraft.util.Mth.rotLerp(partialTick,
                    player.yRotO, player.getYRot());
            double angleRad = Math.toRadians(-playerYaw - 180.0f);
            cos = Math.cos(angleRad);
            sin = Math.sin(angleRad);
        }

        float maxCircleRadius = size / 2.0f + borderThickness / 2.0f;
        float squareLimit = size / 2.0f + borderThickness / 2.0f;

        for (WaypointManager.Waypoint wp : waypoints) {
            if (wp == null) continue;
            double worldDX = wp.x - playerX;
            double worldDZ = wp.z - playerZ;

            MinimapFramebufferRenderer.HudPoint wpPoint = framebuffer.projectWorldToHud(wp.x, wp.z);
            float scrDX;
            float scrDZ;
            if (wpPoint != null) {
                scrDX = wpPoint.x() - cx;
                scrDZ = wpPoint.y() - cy;
            } else {
                scrDX = (float) ((worldDX * cos - worldDZ * sin) * zoom);
                scrDZ = (float) ((worldDX * sin + worldDZ * cos) * zoom);
            }
            float dist2D = (float) Math.sqrt(scrDX * scrDX + scrDZ * scrDZ);

            float iconX;
            float iconZ;
            boolean offMap = false;
            if (MapConfig.minimapCircle) {
                if (dist2D <= maxCircleRadius || dist2D <= 0.0001f) {
                    iconX = cx + scrDX;
                    iconZ = cy + scrDZ;
                } else {
                    iconX = cx + (scrDX / dist2D) * maxCircleRadius;
                    iconZ = cy + (scrDZ / dist2D) * maxCircleRadius;
                    offMap = true;
                }
            } else {
                if (Math.abs(scrDX) <= squareLimit && Math.abs(scrDZ) <= squareLimit) {
                    iconX = cx + scrDX;
                    iconZ = cy + scrDZ;
                } else {
                    float maxVal = Math.max(Math.abs(scrDX), Math.abs(scrDZ));
                    float t = maxVal > 0.0001f ? squareLimit / maxVal : 0.0f;
                    iconX = maxVal > 0.0001f ? cx + scrDX * t : cx;
                    iconZ = maxVal > 0.0001f ? cy + scrDZ * t : cy;
                    offMap = true;
                }
            }

            float iconScale = Math.max(0.25f, Math.min(1.25f, 0.10f * MapConfig.waypointScale * wp.scale));
            if (offMap) {
                iconScale *= 0.85f;
            }
            net.minecraft.world.item.ItemStack iconStack =
                    MapRenderer.getInstance().resolveWaypointIconStack(wp);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(iconX, iconZ, 0.0f);
            guiGraphics.pose().scale(iconScale, iconScale, 1.0f);
            guiGraphics.renderFakeItem(iconStack, -8, -8);
            guiGraphics.pose().popPose();
        }
        guiGraphics.flush();
    }

    /**
     * Draws the pin navigation marker on the minimap
     */
    private void drawPinOnMinimap(GuiGraphics guiGraphics, Player player, int mx, int my,
            int size, int borderThickness, float effectiveZoom, float partialTick) {
        double dimScale = MapManager.getInstance().getCrossDimensionCoordinateScale();
        double playerX = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX()) * dimScale;
        double playerZ = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ()) * dimScale;
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
            label = net.minecraft.network.chat.Component.translatable("gui.simplemap.distance.k_blocks",
                    (tenths / 10) + "." + Math.abs(tenths % 10)).getString();
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

            // Degenerate Quad (4 vertices representing a CCW triangle in GUI NDC)
            consumer.vertex(matrix, cx, cy, 0.0f).color(r, g, b, a).endVertex();
            consumer.vertex(matrix, x2, y2, 0.0f).color(r, g, b, a).endVertex();
            consumer.vertex(matrix, x1, y1, 0.0f).color(r, g, b, a).endVertex();
            consumer.vertex(matrix, cx, cy, 0.0f).color(r, g, b, a).endVertex();
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

            float innerR = Math.min(r1, r2);
            float outerR = Math.max(r1, r2);
            consumer.vertex(matrix, cx + innerR * cos1, cy + innerR * sin1, 0.0f).color(r, g, b, a).endVertex();
            consumer.vertex(matrix, cx + innerR * cos2, cy + innerR * sin2, 0.0f).color(r, g, b, a).endVertex();
            consumer.vertex(matrix, cx + outerR * cos2, cy + outerR * sin2, 0.0f).color(r, g, b, a).endVertex();
            consumer.vertex(matrix, cx + outerR * cos1, cy + outerR * sin1, 0.0f).color(r, g, b, a).endVertex();
        }
    }

    public static final class MinimapService {
        public record Summary(long ringGeneration, int diameter,
                long requests, long skipped, long lastGoodRevision,
                boolean lastGoodAvailable) { }

        private static final MinimapService INSTANCE = new MinimapService();
        private static final int RING_DIAMETER = 13;

        private final FixedTileRing ring = new FixedTileRing(RING_DIAMETER);
        private final ColumnSignatureStore signatures =
                new ColumnSignatureStore(RING_DIAMETER * 16 * RING_DIAMETER * 16);
        private long requests;
        private long skipped;
        private long lastGoodRevision;
        private boolean lastGoodAvailable;
        private long lastRequestNanos;
        private long sessionId;

        private MinimapService() { }

        public static MinimapService getInstance() { return INSTANCE; }

        public void update(double centerX, double centerZ, float zoom) {
            RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
            if (stamp == null || !stamp.isCurrent()) return;
            if (sessionId != stamp.sessionId()) resetForSession(stamp.sessionId());
            int chunkX = ((int) Math.floor(centerX)) >> 4;
            int chunkZ = ((int) Math.floor(centerZ)) >> 4;
            boolean moved = ring.recenter(chunkX, chunkZ);
            long now = System.nanoTime();
            if (!moved && now - lastRequestNanos < 50_000_000L) {
                skipped++;
                return;
            }
            lastRequestNanos = now;
            requests++;
        }

        public synchronized void markLastGood(long revision) {
            lastGoodRevision = Math.max(lastGoodRevision, Math.max(1L, revision));
            lastGoodAvailable = true;
        }

        public synchronized boolean hasLastGood() { return lastGoodAvailable; }

        public synchronized Summary summary() {
            return new Summary(ring.generation(), ring.diameter(), requests,
                    skipped, lastGoodRevision, lastGoodAvailable);
        }

        public synchronized void clear() {
            sessionId = 0L;
            signatures.clear();
            lastGoodRevision = 0L;
            lastGoodAvailable = false;
            lastRequestNanos = 0L;
        }

        private synchronized void resetForSession(long newSessionId) {
            sessionId = newSessionId;
            signatures.clear();
            lastGoodRevision = 0L;
            lastGoodAvailable = false;
        }

        public static final class ColumnSignatureStore {
            private final long[] signatures;
            private final int[] colors;
            private long revision;

            public ColumnSignatureStore(int pixels) {
                signatures = new long[Math.max(1, pixels)];
                colors = new int[signatures.length];
                Arrays.fill(signatures, Long.MIN_VALUE);
            }

            public synchronized boolean update(int index, long signature, int color) {
                if (index < 0 || index >= signatures.length) return false;
                if (signatures[index] == signature && colors[index] == color) return false;
                signatures[index] = signature;
                colors[index] = color;
                revision++;
                return true;
            }

            public synchronized int color(int index) {
                return index < 0 || index >= colors.length ? 0 : colors[index];
            }

            public synchronized long signature(int index) {
                return index < 0 || index >= signatures.length
                        ? Long.MIN_VALUE : signatures[index];
            }

            public synchronized long revision() { return revision; }
            public synchronized int[] snapshotColors() {
                return Arrays.copyOf(colors, colors.length);
            }

            public synchronized void clear() {
                Arrays.fill(signatures, Long.MIN_VALUE);
                Arrays.fill(colors, 0);
                revision++;
            }

            public static long signature(int materialId, int biomeId, int topY,
                    int light, int slope, int flags, long styleRevision) {
                long value = materialId & 0xFFFFFL;
                value = value * 0x9E3779B97F4A7C15L + (biomeId & 0xFFFFL);
                value = value * 0x9E3779B97F4A7C15L + (topY & 0xFFFFL);
                value = value * 0x9E3779B97F4A7C15L + (light & 0xFFL);
                value = value * 0x9E3779B97F4A7C15L + (slope & 0xFFL);
                value = value * 0x9E3779B97F4A7C15L + (flags & 0xFFL);
                return value ^ Long.rotateLeft(styleRevision, 17);
            }
        }
    }

    /** Resolves a minimap position relative to a stable screen anchor. */
    public static final class MinimapPosition {
        private static final int EDGE_MARGIN = 2;

        private MinimapPosition() {
        }

        public static int[] resolve(int screenWidth, int screenHeight, int size) {
            if (MapConfig.legacyMinimapPositionPending) {
                int oldX = clamp((int) (screenWidth * MapConfig.minimapXPercent), EDGE_MARGIN,
                        screenWidth - size - EDGE_MARGIN);
                int oldY = clamp((int) (screenHeight * MapConfig.minimapYPercent), EDGE_MARGIN,
                        screenHeight - size - EDGE_MARGIN);
                setFromTopLeft(oldX, oldY, screenWidth, screenHeight, size);
                MapConfig.legacyMinimapPositionPending = false;
                MapConfig.save();
            }

            String anchor = sanitizeAnchor(MapConfig.minimapAnchor);
            int x = baseX(anchor, screenWidth, size) + MapConfig.minimapOffsetX;
            int y = baseY(anchor, screenHeight, size) + MapConfig.minimapOffsetY;
            return new int[] {
                    clamp(x, EDGE_MARGIN, screenWidth - size - EDGE_MARGIN),
                    clamp(y, EDGE_MARGIN, screenHeight - size - EDGE_MARGIN)
            };
        }

        public static void setFromTopLeft(int x, int y, int screenWidth, int screenHeight, int size) {
            String horizontal = nearestAxis(x,
                    EDGE_MARGIN,
                    (screenWidth - size) / 2,
                    screenWidth - size - EDGE_MARGIN,
                    "LEFT", "CENTER", "RIGHT");
            String vertical = nearestAxis(y,
                    EDGE_MARGIN,
                    (screenHeight - size) / 2,
                    screenHeight - size - EDGE_MARGIN,
                    "TOP", "MIDDLE", "BOTTOM");

            MapConfig.minimapAnchor = vertical + "_" + horizontal;
            MapConfig.minimapOffsetX = x - baseX(MapConfig.minimapAnchor, screenWidth, size);
            MapConfig.minimapOffsetY = y - baseY(MapConfig.minimapAnchor, screenHeight, size);
        }

        public static String sanitizeAnchor(String anchor) {
            if (anchor == null) return "TOP_RIGHT";
            return switch (anchor) {
                case "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT",
                        "MIDDLE_LEFT", "MIDDLE_CENTER", "MIDDLE_RIGHT",
                        "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT" -> anchor;
                default -> "TOP_RIGHT";
            };
        }

        private static int baseX(String anchor, int screenWidth, int size) {
            if (anchor.endsWith("_LEFT")) return EDGE_MARGIN;
            if (anchor.endsWith("_CENTER")) return (screenWidth - size) / 2;
            return screenWidth - size - EDGE_MARGIN;
        }

        private static int baseY(String anchor, int screenHeight, int size) {
            if (anchor.startsWith("TOP_")) return EDGE_MARGIN;
            if (anchor.startsWith("MIDDLE_")) return (screenHeight - size) / 2;
            return screenHeight - size - EDGE_MARGIN;
        }

        private static String nearestAxis(int value, int first, int middle, int last,
                String firstName, String middleName, String lastName) {
            int firstDistance = Math.abs(value - first);
            int middleDistance = Math.abs(value - middle);
            int lastDistance = Math.abs(value - last);
            if (firstDistance <= middleDistance && firstDistance <= lastDistance) return firstName;
            if (middleDistance <= lastDistance) return middleName;
            return lastName;
        }

        private static int clamp(int value, int min, int max) {
            if (max < min) return min;
            return Math.max(min, Math.min(value, max));
        }
    }


    public static final class FixedTileRing {
    private final int diameter;
    private final long[] chunkKeys;
    private int centerChunkX = Integer.MIN_VALUE;
    private int centerChunkZ = Integer.MIN_VALUE;
    private long generation;

    public FixedTileRing(int diameter) {
        if (diameter < 3 || (diameter & 1) == 0) {
            throw new IllegalArgumentException("diameter must be odd and >= 3");
        }
        this.diameter = diameter;
        this.chunkKeys = new long[diameter * diameter];
        Arrays.fill(chunkKeys, Long.MIN_VALUE);
    }

    public synchronized boolean recenter(int chunkX, int chunkZ) {
        if (chunkX == centerChunkX && chunkZ == centerChunkZ) return false;
        centerChunkX = chunkX;
        centerChunkZ = chunkZ;
        int radius = diameter / 2;
        int index = 0;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                chunkKeys[index++] = pack(chunkX + x, chunkZ + z);
            }
        }
        generation++;
        return true;
    }

    public synchronized long generation() { return generation; }
    public int diameter() { return diameter; }
    public synchronized int centerChunkX() { return centerChunkX; }
    public synchronized int centerChunkZ() { return centerChunkZ; }
    public synchronized long[] snapshotKeys() {
        return Arrays.copyOf(chunkKeys, chunkKeys.length);
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) { return (int) (key >> 32); }
    public static int unpackZ(long key) { return (int) key; }
}

}
