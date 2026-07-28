package com.velorise.simplemap.client.renderer;

import com.velorise.simplemap.client.gpu.MapGpuInstancePlan;
import com.velorise.simplemap.client.gpu.MapGpuInstanceRenderer;
import net.minecraft.client.gui.GuiGraphics;

/** M11 renderer facade: logical plans no longer know atlas ownership. */
public final class MapGpuRenderer {
    private MapGpuRenderer() { }
    public static void draw(GuiGraphics graphics, MapGpuInstancePlan plan,
            boolean glow) {
        MapGpuInstanceRenderer.draw(graphics, plan, glow);
    }

    public static void drawPhase(GuiGraphics graphics,
            MapGpuInstancePlan plan, int phase) {
        MapGpuInstanceRenderer.drawPhase(graphics, plan, phase);
    }
}
