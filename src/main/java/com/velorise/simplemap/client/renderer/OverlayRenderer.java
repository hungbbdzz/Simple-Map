package com.velorise.simplemap.client.renderer;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Explicit overlay boundary reserved for waypoints, player markers and debug UI.
 * It intentionally owns no terrain source, projection, upload or persistence work.
 */
public interface OverlayRenderer {
    void render(GuiGraphics graphics);
}
