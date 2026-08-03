package com.velorise.simplemap.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Small map-only location picker used by the waypoint form. It deliberately has
 * no waypoint-management widgets, preventing the old stacked-screen input and
 * z-order problems.
 */
public final class WaypointMapPickerScreen extends Screen {
    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 12.0f;

    private final AddWaypointScreen parent;
    private double centerX;
    private double centerZ;
    private float scale = 0.5f;
    private boolean dragging;
    private double pressX;
    private double pressY;

    public WaypointMapPickerScreen(AddWaypointScreen parent, double centerX,
            double centerZ) {
        super(Component.literal("Pick Waypoint Location"));
        this.parent = parent;
        this.centerX = centerX;
        this.centerZ = centerZ;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(width / 2 - 45, height - 27, 90, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY,
            float partialTick) {
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, width, height, 0xFF0D0D0D);
        int vx = 1;
        int vy = 1;
        int vw = width - 2;
        int vh = height - 2;
        double mouseWorldX = centerX + (mouseX - vx - vw / 2.0) / scale;
        double mouseWorldZ = centerZ + (mouseY - vy - vh / 2.0) / scale;

        MapRenderer.getInstance().drawMap(guiGraphics, vx, vy, vw, vh,
                centerX, centerZ, scale,
                MapManager.getInstance().isViewingLiveDimension(), false, false,
                mouseWorldX, mouseWorldZ, partialTick, false);

        String instruction = "Click a block to use its X/Z · Drag to pan · Scroll to zoom";
        int instructionWidth = font.width(instruction);
        int boxLeft = (width - instructionWidth) / 2 - 7;
        guiGraphics.fill(boxLeft, 7, boxLeft + instructionWidth + 14, 25, 0xD0101010);
        guiGraphics.renderOutline(boxLeft, 7, instructionWidth + 14, 18, 0xFF707070);
        guiGraphics.drawString(font, instruction, boxLeft + 7, 12, 0xFFFFFFFF, false);

        String cursor = String.format("X: %d  Z: %d",
                (int) Math.floor(mouseWorldX), (int) Math.floor(mouseWorldZ));
        int cursorWidth = font.width(cursor);
        guiGraphics.fill(width - cursorWidth - 13, height - 26,
                width - 5, height - 8, 0xD0101010);
        guiGraphics.drawString(font, cursor, width - cursorWidth - 9,
                height - 21, 0xFFFFFFFF, false);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == 0) {
            dragging = true;
            pressX = mouseX;
            pressY = mouseY;
            return true;
        }
        if (button == 1) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
            double dragX, double dragY) {
        if (dragging && button == 0) {
            centerX -= dragX / scale;
            centerZ -= dragY / scale;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            double moved = Math.hypot(mouseX - pressX, mouseY - pressY);
            if (moved < 4.0) {
                double worldX = centerX + (mouseX - 1 - (width - 2) / 2.0) / scale;
                double worldZ = centerZ + (mouseY - 1 - (height - 2) / 2.0) / scale;
                double selectedX = Math.floor(worldX) + 0.5;
                double selectedZ = Math.floor(worldZ) + 0.5;
                parent.acceptPickedLocation(selectedX, selectedZ,
                        MapManager.getInstance().getCurrentDimensionResourceId());
                MapViewportCoordinator.getInstance().closeFullscreen();
                if (minecraft != null) minecraft.setScreen(parent);
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
            double scrollY) {
        float oldScale = scale;
        float factor = scrollY > 0 ? 1.15f : 1.0f / 1.15f;
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        if (oldScale != scale) {
            double beforeX = centerX + (mouseX - width / 2.0) / oldScale;
            double beforeZ = centerZ + (mouseY - height / 2.0) / oldScale;
            centerX = beforeX - (mouseX - width / 2.0) / scale;
            centerZ = beforeZ - (mouseY - height / 2.0) / scale;
        }
        return true;
    }

    @Override
    public void onClose() {
        MapViewportCoordinator.getInstance().closeFullscreen();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
