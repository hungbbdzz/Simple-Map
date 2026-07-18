package com.velorise.simplemap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Coordinates for the Simple Map 256x256 GUI atlas. */
public final class MapUiIcons {
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(
            "simplemap", "gui/gui.png");
    private static final int ATLAS_SIZE = 256;

    public enum Icon {
        // Rectangles include the one-pixel down/right shadow drawn into gui.png.
        SETTINGS(0, 0, 16, 15),
        WAYPOINT_OUTLINE(0, 16, 12, 17),
        WAYPOINT_FILLED(16, 16, 12, 17),
        WAYPOINT_LIST(32, 16, 13, 17),
        REFRESH(0, 80, 14, 15),
        SUN(0, 48, 20, 20),
        MOON(32, 48, 16, 16),
        NIGHT_AUTO(48, 48, 19, 19),
        DIMENSION_PORTAL(0, 96, 12, 13),
        CAVE_ON(0, 112, 17, 14),
        CAVE_OFF(32, 112, 17, 14);

        final int u;
        final int v;
        final int width;
        final int height;

        Icon(int u, int v, int width, int height) {
            this.u = u;
            this.v = v;
            this.width = width;
            this.height = height;
        }
    }

    private MapUiIcons() {
    }

    public static void draw(GuiGraphics graphics, Icon icon, int centerX, int centerY,
            boolean enabled) {
        drawScaled(graphics, icon, centerX, centerY, enabled, 1.0f);
    }

    public static void drawScaled(GuiGraphics graphics, Icon icon, int centerX, int centerY,
            boolean enabled, float scale) {
        float safeScale = Math.max(0.25f, Math.min(2.0f, scale));
        int drawWidth = Math.max(1, Math.round(icon.width * safeScale));
        int drawHeight = Math.max(1, Math.round(icon.height * safeScale));
        float alpha = enabled ? 1.0f : 0.42f;
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        graphics.blit(ATLAS,
                centerX - drawWidth / 2, centerY - drawHeight / 2,
                drawWidth, drawHeight,
                (float) icon.u, (float) icon.v,
                icon.width, icon.height, ATLAS_SIZE, ATLAS_SIZE);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
