package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;

public class MapRenderer {
    private static final MapRenderer INSTANCE = new MapRenderer();
    private static final float NIGHT_MIN_BRIGHTNESS = 0.22f;
    private static final long REGION_REQUEST_INTERVAL_NANOS = 100_000_000L;
    private static final long MINIMAP_UPLOAD_INTERVAL_NANOS = 100_000_000L;

    private long lastRegionRequestNanos;
    private long lastMinimapUploadNanos;
    private int lastRequestMinRx = Integer.MIN_VALUE;
    private int lastRequestMaxRx = Integer.MIN_VALUE;
    private int lastRequestMinRz = Integer.MIN_VALUE;
    private int lastRequestMaxRz = Integer.MIN_VALUE;
    private int lastRequestMode = Integer.MIN_VALUE;
    private int lastRequestLayerY = Integer.MIN_VALUE;

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
     * cachedOnly keeps interaction frames strictly render-only: uploaded region
     * textures are translated/scaled like one printed image while IO, scans and GPU
     * publication wait until the camera settles.
     */
    public void drawMap(GuiGraphics guiGraphics, int viewportX, int viewportY, int width, int height,
            double centerX, double centerZ, float scale, boolean drawPlayer, boolean rotateWithPlayer,
            boolean isMinimap, double mouseWorldX, double mouseWorldZ, float partialTick,
            boolean cachedOnly) {

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        boolean caveMode = CaveMode.isActive(mc);
        boolean fullCaveView = caveMode && CaveMode.isFullView(mc);
        int caveLayerY = caveMode ? CaveMode.getLayerY(mc) : Integer.MIN_VALUE;
        // GPU publication stays time-bounded even on the fullscreen map. The old
        // fast-refresh path bypassed throttling every rendered frame and could
        // upload several 512x512 textures at once, causing visible frame spikes.
        boolean focusUpload = !isMinimap && MapConfig.fastFullscreenLoading && scale >= 0.35f;
        // At overview zoom, forcing up to eight 512x512 uploads every rendered
        // frame is more expensive than drawing the map itself. Let the normal
        // 50 ms publication throttle stream those regions progressively.
        long renderNow = System.nanoTime();
        boolean allowPublication = !cachedOnly
                && (!isMinimap || renderNow - lastMinimapUploadNanos >= MINIMAP_UPLOAD_INTERVAL_NANOS);
        if (allowPublication) {
            if (isMinimap) lastMinimapUploadNanos = renderNow;
            if (fullCaveView) {
                FullCaveTextureManager.getInstance().uploadDirtyTextures(focusUpload);
            } else if (caveMode) {
                CaveMapManager.getInstance().setActiveLayer(caveLayerY);
                CaveTextureManager.getInstance().uploadDirtyTextures(focusUpload);
            } else {
                MapTextureManager.getInstance().uploadDirtyTextures(focusUpload);
            }
        } else if (caveMode && !fullCaveView) {
            CaveMapManager.getInstance().setActiveLayer(caveLayerY);
        }

        // Reset shader color to prevent other GUI elements from tinting the map
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        // 2. Enable scissor test to clip rendering to the viewport
        guiGraphics.enableScissor(viewportX, viewportY, viewportX + width, viewportY + height);

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

        // Prioritize the map viewport itself, but do not rebuild the same request
        // set every rendered frame. Bounds changes trigger immediately; stationary
        // views refresh at 10 Hz and individual texture lookups still self-enqueue.
        int requestMode = caveMode ? (fullCaveView ? 2 : 1) : 0;
        long requestNow = System.nanoTime();
        boolean requestChanged = minRegionX != lastRequestMinRx || maxRegionX != lastRequestMaxRx
                || minRegionZ != lastRequestMinRz || maxRegionZ != lastRequestMaxRz
                || requestMode != lastRequestMode || caveLayerY != lastRequestLayerY;
        if (!cachedOnly && (requestChanged
                || requestNow - lastRegionRequestNanos >= REGION_REQUEST_INTERVAL_NANOS)) {
            lastRegionRequestNanos = requestNow;
            lastRequestMinRx = minRegionX;
            lastRequestMaxRx = maxRegionX;
            lastRequestMinRz = minRegionZ;
            lastRequestMaxRz = maxRegionZ;
            lastRequestMode = requestMode;
            lastRequestLayerY = caveLayerY;

            int centerRegionX = ((int) Math.floor(centerX)) >> 9;
            int centerRegionZ = ((int) Math.floor(centerZ)) >> 9;
            MapManager surfaceManager = MapManager.getInstance();
            FullCaveMapManager fullManager = FullCaveMapManager.getInstance();
            CaveMapManager layerManager = CaveMapManager.getInstance();
            VerticalCaveArchiveManager verticalArchive = VerticalCaveArchiveManager.getInstance();
            for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                    int priority = 100_000
                            - (Math.abs(rx - centerRegionX) + Math.abs(rz - centerRegionZ)) * 100;
                    boolean hasSurfaceSource = surfaceManager.hasRegionFile(rx, rz)
                            || surfaceManager.isRegionLoadedInCache(rx, rz);
                    if (!caveMode && hasSurfaceSource) {
                        MapProcessor.getInstance().enqueueSurfaceLoad(rx, rz, priority);
                    }
                    if (fullCaveView) {
                        if (fullManager.hasRegionFile(rx, rz) || fullManager.isRegionLoaded(rx, rz)) {
                            MapProcessor.getInstance().enqueueFullCaveLoad(rx, rz, priority + 20);
                        }
                    } else if (caveMode) {
                        boolean hasVerticalSource = verticalArchive.hasRegionData(rx, rz);
                        if (hasVerticalSource || layerManager.hasRegionFile(rx, rz)
                                || layerManager.isRegionLoaded(rx, rz)) {
                            MapProcessor.getInstance().enqueueCaveLoad(caveLayerY, rx, rz, priority + 20);
                        }
                    }
                }
            }
        }

        // 6. Draw the visible tiles in one retained render batch. Evicted GPU
        // textures are released only after GuiGraphics.flush(), so changing the
        // bound texture no longer forces one flush per region.
        float mapBrightness = getMapBrightness(mc, partialTick);
        float caveBrightness = getCaveBrightness(mc, partialTick);
        MapTextureManager surfaceTextures = MapTextureManager.getInstance();
        FullCaveTextureManager fullCaveTextures = FullCaveTextureManager.getInstance();
        CaveTextureManager caveTextures = CaveTextureManager.getInstance();
        surfaceTextures.beginRenderBatch();
        fullCaveTextures.beginRenderBatch();
        caveTextures.beginRenderBatch();
        try {
            boolean blendWasEnabledForMap = GL11.glIsEnabled(GL11.GL_BLEND);
            if (caveMode) {
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
            }
            try {
                if (caveMode) {
                    // Strict layer rendering: unavailable or empty columns stay black.
                    // Never blend a selected Top-Y layer with surface, full-cave, or a
                    // previously selected layer, since those pixels describe different Y.
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    guiGraphics.fill(minRegionX * 512, minRegionZ * 512,
                            (maxRegionX + 1) * 512, (maxRegionZ + 1) * 512, 0xFF080A0C);

                    RenderSystem.setShaderColor(caveBrightness, caveBrightness, caveBrightness, 1.0F);
                    if (fullCaveView) {
                        for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                            for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                                drawRegion(guiGraphics, (cachedOnly ? fullCaveTextures.peekRegionTexture(rx, rz) : fullCaveTextures.getRegionTexture(rx, rz)), rx, rz);
                            }
                        }
                    } else {
                        for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                            for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                                drawRegion(guiGraphics, (cachedOnly ? caveTextures.peekRegionTexture(caveLayerY, rx, rz) : caveTextures.getRegionTexture(caveLayerY, rx, rz)), rx, rz);
                            }
                        }
                    }
                } else {
                    RenderSystem.setShaderColor(mapBrightness, mapBrightness, mapBrightness, 1.0F);
                    for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                            drawRegion(guiGraphics, (cachedOnly ? surfaceTextures.peekRegionTexture(rx, rz) : surfaceTextures.getRegionTexture(rx, rz)), rx, rz);
                        }
                    }
                }
            } finally {
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                if (caveMode && !blendWasEnabledForMap) RenderSystem.disableBlend();
            }

            // Restore lit surface colours over the dark ambient base. Night mode
            // is shader-only and therefore no longer rebuilds the full map cache.
            // Keep emissive/light sources visible at every zoom level. Earlier builds
            // disabled this pass below 0.32 px/block on the fullscreen map, which made
            // torches, lava and other lights appear to switch off while zooming out.
            // The pass reuses already prepared per-region glow textures, so zoom only
            // changes composition; it does not rebuild light data.
            if (!caveMode && MapConfig.minimapNightMode != 0) {
                boolean blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                float glowStrength = MapConfig.minimapNightMode == 2
                        ? 1.0f
                        : Math.max(0.0f, Math.min(1.0f,
                                (1.0f - mapBrightness) / (1.0f - NIGHT_MIN_BRIGHTNESS)));
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, glowStrength);
                try {
                    for (int rz = minRegionZ; rz <= maxRegionZ; rz++) {
                        for (int rx = minRegionX; rx <= maxRegionX; rx++) {
                            ResourceLocation glowTexture = cachedOnly ? surfaceTextures.peekGlowRegionTexture(rx, rz)
                                    : surfaceTextures.getGlowRegionTexture(rx, rz);
                            if (glowTexture == null) continue;
                            RenderSystem.setShaderTexture(0, glowTexture);
                            guiGraphics.blit(glowTexture, rx * 512, rz * 512, 512, 512,
                                    0f, 0f, 512, 512, 512, 512);
                        }
                    }
                } finally {
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    if (!blendWasEnabled) RenderSystem.disableBlend();
                }
            }
        } finally {
            guiGraphics.flush();
            caveTextures.endRenderBatch();
            fullCaveTextures.endRenderBatch();
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
                float pinScaleFactor = (baseFactor / scale) * MapConfig.pinScale;
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

            drawPlayerMarker(guiGraphics, playerX, playerZ, playerYaw, scale);
        }

        // 8. Disable scissor and pop pose
        guiGraphics.disableScissor();
        poseStack.popPose();
    }

    private void drawRegion(GuiGraphics guiGraphics, ResourceLocation texture, int regionX, int regionZ) {
        if (texture == null) return;
        RenderSystem.setShaderTexture(0, texture);
        // DynamicTexture filtering is configured when the texture is created.
        // Re-applying glTexParameter for every visible region caused hundreds of
        // redundant driver calls in heavily zoomed-out fullscreen views.
        guiGraphics.blit(texture, regionX * 512, regionZ * 512, 512, 512,
                0f, 0f, 512, 512, 512, 512);
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
    private void drawPlayerMarker(GuiGraphics guiGraphics, double worldX, double worldZ, float yaw, float mapScale) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();

        // Translate to the player's position in world space
        poseStack.translate(worldX, worldZ, 10);

        // Compensate map scale and apply playerMarkerScale config
        float markerScale = (1.0f / mapScale) * MapConfig.playerMarkerScale;
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

}
