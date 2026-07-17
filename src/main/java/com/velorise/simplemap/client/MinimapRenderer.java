package com.velorise.simplemap.client;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

public class MinimapRenderer {
    private static final MinimapRenderer INSTANCE = new MinimapRenderer();
    private static final long CAVE_ZOOM_ANIMATION_NANOS = 1_000_000_000L;
    private static final float CAVE_ZOOM_MULTIPLIER = 1.55f;

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

            // Draw Map
            MapRenderer.getInstance().drawMap(
                    guiGraphics,
                    x, y, size, size,
                    interpX, interpZ,
                    effectiveZoom,
                    true,
                    MapConfig.minimapRotate,
                    true, 0, 0,
                    partialTick);

            // Draw Compass Directions on the square borders
            float cx = x + size / 2.0f;
            float cy = y + size / 2.0f;
            float radius = size / 2.0f;
            drawCompassDirections(guiGraphics, mc, cx, cy, radius, borderThickness, player, false, partialTick);
        }

        // 3. Draw coordinate overlay text with dynamic scale and position
        if (MapConfig.coordsEnabled) {
            String coords = String.format("%d, %d, %d", (int) Math.floor(player.getX()),
                    (int) Math.floor(player.getY()), (int) Math.floor(player.getZ()));
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

    private void renderCircularMinimap(GuiGraphics guiGraphics, Minecraft mc, Player player,
            int x, int y, int size, int borderThickness, int tHalf,
            double interpX, double interpZ, float effectiveZoom, float partialTick) {
        float radius = size / 2.0f;
        float cx = x + radius;
        float cy = y + radius;
        float clipRadius = radius - tHalf;
        boolean depthWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        boolean stencilWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
        int previousStencilFunc = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_FUNC);
        int previousStencilRef = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_REF);
        int previousStencilValueMask = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_VALUE_MASK);
        int previousStencilWriteMask = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_WRITEMASK);
        int previousStencilFail = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_FAIL);
        int previousStencilDepthFail = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        int previousStencilDepthPass = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_STENCIL_PASS_DEPTH_PASS);

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
            for (int dy = -(int) Math.ceil(maskRadius); dy <= (int) Math.ceil(maskRadius); dy++) {
                float squared = maskRadius * maskRadius - dy * dy;
                if (squared < 0.0f) continue;
                float dx = (float) Math.sqrt(squared);
                fillFloat(guiGraphics, cx - dx, cy + dy, cx + dx, cy + dy + 1.0f, 0xFFFFFFFF);
            }
            guiGraphics.flush();

            org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);
            org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_EQUAL, 1, 0xFF);
            org.lwjgl.opengl.GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_KEEP,
                    org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_KEEP);
            org.lwjgl.opengl.GL11.glStencilMask(0x00);

            MapRenderer.getInstance().drawMap(
                    guiGraphics,
                    x, y, size, size,
                    interpX, interpZ,
                    effectiveZoom,
                    true,
                    MapConfig.minimapRotate,
                    true, 0, 0,
                    partialTick);
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

        drawCircleRing(guiGraphics, cx, cy, clipRadius, radius, 64, MapConfig.minimapRingColor);
        drawCircleRing(guiGraphics, cx, cy, radius, radius + borderThickness, 64, MapConfig.minimapRingColor);
        drawCircleRing(guiGraphics, cx, cy, radius + borderThickness, radius + borderThickness + 1, 64,
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

        // Center of minimap
        float cx = mx + size / 2.0f;
        float cy = my + size / 2.0f;

        double worldDX = MapConfig.pinWorldX - playerX;
        double worldDZ = MapConfig.pinWorldZ - playerZ;

        float dirX, dirZ;
        if (MapConfig.minimapRotate) {
            float playerYaw = net.minecraft.util.Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
            double angleRad = Math.toRadians(180.0f - playerYaw);
            double cos = Math.cos(angleRad);
            double sin = Math.sin(angleRad);
            dirX = (float) (worldDX * cos - worldDZ * sin);
            dirZ = (float) (worldDX * sin + worldDZ * cos);
        } else {
            dirX = (float) worldDX;
            dirZ = (float) worldDZ;
        }

        float scrDX = (float) (dirX * zoom);
        float scrDZ = (float) (dirZ * zoom);
        float dist2D = (float) Math.sqrt(scrDX * scrDX + scrDZ * scrDZ);

        float iconX, iconZ;
        boolean offMap = false;

        if (MapConfig.minimapCircle) {
            // Circular clamping boundary
            float maxCircleRadius = size / 2.0f + borderThickness / 2.0f;
            if (dist2D <= maxCircleRadius) {
                iconX = cx + scrDX;
                iconZ = cy + scrDZ;
            } else {
                iconX = cx + (scrDX / dist2D) * maxCircleRadius;
                iconZ = cy + (scrDZ / dist2D) * maxCircleRadius;
                offMap = true;
            }
        } else {
            // Square clamping boundary
            float limit = size / 2.0f + (borderThickness / 2.0f);
            if (Math.abs(scrDX) <= limit && Math.abs(scrDZ) <= limit) {
                iconX = cx + scrDX;
                iconZ = cy + scrDZ;
            } else {
                float maxVal = Math.max(Math.abs(scrDX), Math.abs(scrDZ));
                if (maxVal > 0) {
                    float t = limit / maxVal;
                    iconX = cx + scrDX * t;
                    iconZ = cy + scrDZ * t;
                } else {
                    iconX = cx;
                    iconZ = cy;
                }
                offMap = true;
            }
        }

        int markerSize = Math.max(2, (int) (8 * MapConfig.pinScale));
        int halfSize = markerSize / 2;

        if (offMap) {
            net.minecraft.resources.ResourceLocation offMapTexture = net.minecraft.resources.ResourceLocation
                    .fromNamespaceAndPath("minecraft", "textures/map/decorations/player_off_map.png");
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 0.0f, 0.0f, 1.0f);
            guiGraphics.blit(offMapTexture, (int) iconX - halfSize, (int) iconZ - halfSize, markerSize, markerSize,
                    0.0f, 0.0f, 8, 8, 8, 8);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        double dist3D = Math.sqrt(worldDX * worldDX + worldDZ * worldDZ);
        String label = dist3D >= 1000
                ? String.format("%.1fk blocks", dist3D / 1000.0)
                : (int) dist3D + "m";
        Minecraft mc = Minecraft.getInstance();

        float textScale = 0.6f * (MapConfig.pinScale / 0.5f);
        int lz = (int) iconZ + halfSize + 2;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(iconX, lz, 0);
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
            double angle1 = i * angleStep;
            double angle2 = (i + 1) * angleStep;

            float x1 = (float) (cx + radius * Math.cos(angle1));
            float y1 = (float) (cy + radius * Math.sin(angle1));
            float x2 = (float) (cx + radius * Math.cos(angle2));
            float y2 = (float) (cy + radius * Math.sin(angle2));

            // Degenerate Quad (4 vertices representing a triangle)
            consumer.addVertex(matrix, cx, cy, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, x1, y1, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, x2, y2, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx, cy, 0.0f).setColor(r, g, b, a);
        }
    }

    private void fillFloat(GuiGraphics guiGraphics, float minX, float minY, float maxX, float maxY, int color) {
        float a = ((color >> 24) & 0xFF) / 255.0f;
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;

        org.joml.Matrix4f matrix = guiGraphics.pose().last().pose();
        com.mojang.blaze3d.vertex.VertexConsumer consumer = guiGraphics.bufferSource()
                .getBuffer(net.minecraft.client.renderer.RenderType.gui());

        consumer.addVertex(matrix, minX, minY, 0.0f).setColor(r, g, b, a);
        consumer.addVertex(matrix, minX, maxY, 0.0f).setColor(r, g, b, a);
        consumer.addVertex(matrix, maxX, maxY, 0.0f).setColor(r, g, b, a);
        consumer.addVertex(matrix, maxX, minY, 0.0f).setColor(r, g, b, a);
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
            double angle1 = i * angleStep;
            double angle2 = (i + 1) * angleStep;

            float cos1 = (float) Math.cos(angle1);
            float sin1 = (float) Math.sin(angle1);
            float cos2 = (float) Math.cos(angle2);
            float sin2 = (float) Math.sin(angle2);

            consumer.addVertex(matrix, cx + r1 * cos1, cy + r1 * sin1, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx + r1 * cos2, cy + r1 * sin2, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx + r2 * cos2, cy + r2 * sin2, 0.0f).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx + r2 * cos1, cy + r2 * sin1, 0.0f).setColor(r, g, b, a);
        }
    }
}
