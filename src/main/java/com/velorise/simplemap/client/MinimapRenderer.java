package com.velorise.simplemap.client;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;

public class MinimapRenderer {
    private static final MinimapRenderer INSTANCE = new MinimapRenderer();
    public static MinimapRenderer getInstance() {
        return INSTANCE;
    }

    private MinimapRenderer() {}

    /**
     * Renders the minimap HUD overlay on the screen during gameplay.
     */
    public void renderHUD(GuiGraphics guiGraphics, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        
        // Only render if enabled, in-game, HUD is visible, and no screen is open
        if (!MapConfig.minimapEnabled || mc.level == null || mc.player == null || mc.options.hideGui || mc.screen != null) {
            return;
        }

        // Check if map is unlocked (learned map + holding book if requireMapBook is enabled)
        if (!SimpleMap.isMapUnlocked(mc.player)) {
            return;
        }

        Player player = mc.player;
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        int size = MapConfig.minimapSize;
        
        // Calculate position based on percentages
        int x = (int) (screenWidth * MapConfig.minimapXPercent);
        int y = (int) (screenHeight * MapConfig.minimapYPercent);

        // Clamp positions to ensure the minimap is fully inside the screen boundaries
        x = Math.max(2, Math.min(x, screenWidth - size - 2));
        y = Math.max(2, Math.min(y, screenHeight - size - 2));

        // 1. Draw premium sleek borders (thickness scales dynamically with size)
        int borderThickness = Math.max(2, size / 32);
        int tHalf = borderThickness / 2;

        // Outer black shadow (thickness tHalf)
        guiGraphics.fill(x - borderThickness, y - borderThickness, x + size + borderThickness, y - tHalf, 0xFF000000); // Top
        guiGraphics.fill(x - borderThickness, y + size + tHalf, x + size + borderThickness, y + size + borderThickness, 0xFF000000); // Bottom
        guiGraphics.fill(x - borderThickness, y - tHalf, x - tHalf, y + size + tHalf, 0xFF000000); // Left
        guiGraphics.fill(x + size + tHalf, y - tHalf, x + size + borderThickness, y + size + tHalf, 0xFF000000); // Right

        // Inner slate outline (thickness tHalf)
        guiGraphics.fill(x - tHalf, y - tHalf, x + size + tHalf, y, 0xFF2D3033); // Top
        guiGraphics.fill(x - tHalf, y + size, x + size + tHalf, y + size + tHalf, 0xFF2D3033); // Bottom
        guiGraphics.fill(x - tHalf, y, x, y + size, 0xFF2D3033); // Left
        guiGraphics.fill(x + size, y, x + size + tHalf, y + size, 0xFF2D3033); // Right

        // 2. Draw Map inside the viewport (enable rotation with player view, using interpolated center)
        double interpX = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX());
        double interpZ = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ());

        MapRenderer.getInstance().drawMap(
            guiGraphics,
            x, y, size, size,
            interpX, interpZ,
            MapConfig.minimapZoom,
            true,
            MapConfig.minimapRotate,
            true, 0, 0,
            partialTick
        );

        // 3. Draw coordinate overlay text with dynamic scale and position
        if (MapConfig.coordsEnabled) {
            String coords = String.format("%d, %d, %d", (int) Math.floor(player.getX()), (int) Math.floor(player.getY()), (int) Math.floor(player.getZ()));
            int textWidth = (int) (mc.font.width(coords) * MapConfig.coordsScale);
            int textHeight = (int) (9 * MapConfig.coordsScale);

            int cx, cy;
            if (MapConfig.coordsXPercent < 0 || MapConfig.coordsYPercent < 0) {
                // Default: centered perfectly under the minimap (with extra spacing for thicker borders)
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
            guiGraphics.drawString(mc.font, coords, 0, 0, 0xFFFFFF, false);
            guiGraphics.pose().popPose();
        }

        // 4. Draw pin navigation marker on minimap
        if (MapConfig.pinActive) {
            drawPinOnMinimap(guiGraphics, player, x, y, size, partialTick);
        }
    }

    /**
     * Draws the pin navigation marker on the minimap:
     * - Dashed white line from minimap center to pin direction (clipped to circle)
     * - Small RED_X icon at pin position (or edge arrow if off-map)
     */
    private void drawPinOnMinimap(GuiGraphics guiGraphics, Player player, int mx, int my, int size, float partialTick) {
        // Interpolate player coordinates using partialTick to eliminate jitter
        double playerX = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX());
        double playerZ = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ());
        double zoom = MapConfig.minimapZoom;

        // Center of minimap in screen pixels
        float cx = mx + size / 2.0f;
        float cy = my + size / 2.0f;

        // World-to-screen delta for the pin
        double worldDX = MapConfig.pinWorldX - playerX;
        double worldDZ = MapConfig.pinWorldZ - playerZ;

        // If minimap is rotating, transform into player-relative screen space (with interpolated yaw)
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

        // Scale to screen pixels
        float scrDX = (float) (dirX * zoom);
        float scrDZ = (float) (dirZ * zoom);

        // Compute how far pin is from center (in screen px)
        float dist2D = (float) Math.sqrt(scrDX * scrDX + scrDZ * scrDZ);

        // Determine draw position: if within minimap, draw icon there; else draw at edge of the square
        // We calculate limit so the dot center is exactly on the middle line of the inner border
        int borderThickness = Math.max(2, size / 32);
        int tHalf = borderThickness / 2;
        float limit = size / 2.0f + tHalf / 2.0f;
        
        float iconX, iconZ;
        boolean offMap = false;

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

        // Scale pin and dot size dynamically using MapConfig.pinScale (base size is 8x8)
        int markerSize = Math.max(2, (int) (8 * MapConfig.pinScale));
        int halfSize = markerSize / 2;



        // Draw correct icon (only if off-map)
        if (offMap) {
            // Draw off-map dot indicator tinted red (scaled)
            net.minecraft.resources.ResourceLocation offMapTexture =
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "textures/map/decorations/player_off_map.png");
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 0.0f, 0.0f, 1.0f); // Tint red
            guiGraphics.blit(offMapTexture, (int) iconX - halfSize, (int) iconZ - halfSize, markerSize, markerSize, 0.0f, 0.0f, 8, 8, 8, 8);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f); // Reset shader color
        }

        // Distance label (only if close enough to be useful)
        double dist3D = Math.sqrt(worldDX * worldDX + worldDZ * worldDZ);
        String label = dist3D >= 1000
                ? String.format("%.1fk blocks", dist3D / 1000.0)
                : (int) dist3D + "m";
        Minecraft mc = Minecraft.getInstance();
        
        float textScale = 0.6f * (MapConfig.pinScale / 0.5f); // Scale relative to default 0.5 (where textScale = 0.6f)
        int lz = (int) iconZ + halfSize + 2;

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(iconX, lz, 0);
        guiGraphics.pose().scale(textScale, textScale, 1.0f);

        int rawWidth = mc.font.width(label);
        guiGraphics.fill(-rawWidth / 2 - 2, -1, rawWidth / 2 + 2, 8, 0xAA000000);
        guiGraphics.drawString(mc.font, label, -rawWidth / 2, 0, 0xFFFFFF, false);
        guiGraphics.pose().popPose();
    }
}
