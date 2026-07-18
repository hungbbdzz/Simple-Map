package com.velorise.simplemap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Coordinates for the original Simple Map 256x256 GUI atlas. */
public final class MapUiIcons {
    private static final ResourceLocation ATLAS = ResourceLocation.fromNamespaceAndPath(
            "simplemap", "gui/gui.png");
    private static final int ATLAS_SIZE = 256;

    public enum Icon {
        WAYPOINT_OUTLINE(0, 0, 15, 22),
        WAYPOINT_FILLED(16, 0, 15, 22),
        REFRESH(0, 32, 13, 15),
        SUN(0, 48, 19, 19),
        MOON(32, 48, 15, 15),
        NIGHT_AUTO(48, 48, 18, 18),
        SETTINGS(0, 80, 15, 14),
        CAVE(0, 97, 16, 13);

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
        float alpha = enabled ? 1.0f : 0.42f;
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        graphics.blit(ATLAS,
                centerX - icon.width / 2, centerY - icon.height / 2,
                icon.width, icon.height,
                (float) icon.u, (float) icon.v,
                icon.width, icon.height, ATLAS_SIZE, ATLAS_SIZE);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
