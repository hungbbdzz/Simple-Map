package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MapConfigScreen extends Screen {
    private final Screen parent;
    private boolean isDraggingMinimap = false;
    private double dragOffsetX = 0;
    private double dragOffsetY = 0;

    // Coords dragging & selection state
    private boolean selectedMinimap = false;
    private boolean selectedCoords = false;
    private boolean isDraggingCoords = false;
    private double dragCoordsOffsetX = 0;
    private double dragCoordsOffsetY = 0;

    // Minimap controls reference
    private Button minimapToggleButton;
    private Button minimapRotateButton;
    private SimpleSlider minimapSizeSlider;
    private SimpleSlider minimapZoomSlider;

    // Player marker controls reference
    private Button pointerModeButton;
    private SimpleSlider pointerScaleSlider;
    private SimpleSlider pinScaleSlider;

    // Coords controls reference
    private Button coordsToggleButton;
    private SimpleSlider coordsScaleSlider;
    private Button pointerColorButton;

    // Keep references so slider and editbox can sync each other
    private SimpleSlider scanSlider;
    private EditBox scanInputBox;

    // Require Book toggle button
    private Button requireBookButton;
    private Button autoClearPinButton;
    private Button alwaysRescanButton;

    private static final int SCAN_MIN = 100;
    private static final int SCAN_MAX = 10000;

    public MapConfigScreen(Screen parent) {
        super(Component.literal("Minimap Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!(parent instanceof MapScreen)) {
            // Global config screen from Mod List: only show Require Map Book and Done
            int btnWidth = 200;
            int btnHeight = 20;

            requireBookButton = Button.builder(
                    getRequireBookMessage(),
                    button -> {
                        MapConfig.requireMapBook = !MapConfig.requireMapBook;
                        MapConfig.serverRequireMapBook = MapConfig.requireMapBook; // apply locally immediately
                        button.setMessage(getRequireBookMessage());
                    }).bounds((this.width - btnWidth) / 2, this.height / 2 - 25, btnWidth, btnHeight).build();
            this.addRenderableWidget(requireBookButton);

            this.addRenderableWidget(Button.builder(
                    Component.literal("Done"),
                    button -> {
                        MapConfig.save();
                        if (this.minecraft != null) {
                            this.minecraft.setScreen(this.parent);
                        }
                    }).bounds((this.width - 100) / 2, this.height / 2 + 10, 100, btnHeight).build());
            return;
        }

        int controlWidth = 110;
        int spacing = 6;

        // Row 1: controls
        int row1Y = this.height - 90;
        int row1TotalW = controlWidth * 4 + spacing * 3;
        int row1StartX = (this.width - row1TotalW) / 2;

        // ==================== MINIMAP CONTROLS ====================
        // Toggle Button (Column 1)
        minimapToggleButton = Button.builder(
                getToggleMessage(),
                button -> {
                    MapConfig.minimapEnabled = !MapConfig.minimapEnabled;
                    button.setMessage(getToggleMessage());
                }).bounds(row1StartX, row1Y, controlWidth, 20).build();
        this.addRenderableWidget(minimapToggleButton);

        // Rotation Toggle Button (Column 2)
        minimapRotateButton = Button.builder(
                getRotateMessage(),
                button -> {
                    MapConfig.minimapRotate = !MapConfig.minimapRotate;
                    button.setMessage(getRotateMessage());
                }).bounds(row1StartX + controlWidth + spacing, row1Y, controlWidth, 20).build();
        this.addRenderableWidget(minimapRotateButton);

        // Size Slider (Column 3)
        double initialSizeVal = (double) (MapConfig.minimapSize - 16) / (150 - 16);
        minimapSizeSlider = new SimpleSlider(
                row1StartX + (controlWidth + spacing) * 2, row1Y, controlWidth, 20,
                initialSizeVal,
                val -> String.format("Size: %dpx", 16 + (int) (val * (150 - 16))),
                val -> MapConfig.minimapSize = 16 + (int) (val * (150 - 16)));
        this.addRenderableWidget(minimapSizeSlider);

        // Zoom Slider (Column 4)
        double initialZoomVal = (MapConfig.minimapZoom - 0.05) / (2.0 - 0.05);
        minimapZoomSlider = new SimpleSlider(
                row1StartX + (controlWidth + spacing) * 3, row1Y, controlWidth, 20,
                initialZoomVal,
                val -> String.format("Zoom: %.2fx", 0.05 + val * (2.0 - 0.05)),
                val -> MapConfig.minimapZoom = (float) (0.05 + val * (2.0 - 0.05)));
        this.addRenderableWidget(minimapZoomSlider);


        // ==================== PLAYER & WAYPOINT MARKER CONTROLS (Row 1.5) ====================
        int playerRowY = this.height - 65;

        // Column 1: Pointer Mode
        pointerModeButton = Button.builder(
                getPointerModeMessage(),
                button -> {
                    MapConfig.playerMarkerMode = (MapConfig.playerMarkerMode + 1) % 2;
                    button.setMessage(getPointerModeMessage());
                }).bounds(row1StartX, playerRowY, controlWidth, 20).build();
        this.addRenderableWidget(pointerModeButton);

        // Column 2: Pointer Scale
        double initialPointerScaleVal = (MapConfig.playerMarkerScale - 0.1) / (1.0 - 0.1);
        pointerScaleSlider = new SimpleSlider(
                row1StartX + controlWidth + spacing, playerRowY, controlWidth, 20,
                initialPointerScaleVal,
                val -> String.format("Pointer Scale: %.2fx", 0.1 + val * (1.0 - 0.1)),
                val -> MapConfig.playerMarkerScale = (float) (0.1 + val * (1.0 - 0.1)));
        this.addRenderableWidget(pointerScaleSlider);

        // Column 3: Global Pin Scale (0.1x to 1.0x)
        double initialPinScaleVal = (MapConfig.pinScale - 0.1) / (1.0 - 0.1);
        pinScaleSlider = new SimpleSlider(
                row1StartX + (controlWidth + spacing) * 2, playerRowY, controlWidth, 20,
                initialPinScaleVal,
                val -> String.format("Pin Scale: %.2fx", 0.1 + val * (1.0 - 0.1)),
                val -> {
                    MapConfig.pinScale = (float) (0.1 + val * (1.0 - 0.1));
                    MapConfig.save();
                });
        this.addRenderableWidget(pinScaleSlider);

        // Column 4: Pointer Color Customization
        pointerColorButton = Button.builder(
                getPointerColorMessage(),
                button -> {
                    int currentIndex = 0;
                    for (int i = 0; i < MapConfig.POINTER_COLORS.length; i++) {
                        if (MapConfig.playerPointerColor == MapConfig.POINTER_COLORS[i]) {
                            currentIndex = i;
                            break;
                        }
                    }
                    int nextIndex = (currentIndex + 1) % MapConfig.POINTER_COLORS.length;
                    MapConfig.playerPointerColor = MapConfig.POINTER_COLORS[nextIndex];
                    button.setMessage(getPointerColorMessage());
                }).bounds(row1StartX + (controlWidth + spacing) * 3, playerRowY, controlWidth, 20).build();
        this.addRenderableWidget(pointerColorButton);


        // ==================== COORDS CONTROLS ====================
        int coordsTotalW = controlWidth * 2 + spacing;
        int coordsStartX = (this.width - coordsTotalW) / 2;

        // Toggle Coords
        coordsToggleButton = Button.builder(
                getCoordsToggleMessage(),
                button -> {
                    MapConfig.coordsEnabled = !MapConfig.coordsEnabled;
                    button.setMessage(getCoordsToggleMessage());
                }).bounds(coordsStartX, row1Y, controlWidth, 20).build();
        this.addRenderableWidget(coordsToggleButton);

        // Coords Scale
        double initialCoordsScaleVal = (MapConfig.coordsScale - 0.1) / (2.0 - 0.1);
        coordsScaleSlider = new SimpleSlider(
                coordsStartX + controlWidth + spacing, row1Y, controlWidth, 20,
                initialCoordsScaleVal,
                val -> String.format("Coords Scale: %.2fx", 0.1 + val * (2.0 - 0.1)),
                val -> MapConfig.coordsScale = (float) (0.1 + val * (2.0 - 0.1)));
        this.addRenderableWidget(coordsScaleSlider);


        // Row 2: Scan Points — slider + text input (bidirectional sync)
        int row2Y = this.height - 40;
        int inputBoxW = 70;
        int sliderW = controlWidth * 2 + spacing - inputBoxW - spacing;
        int row2TotalW = sliderW + spacing + inputBoxW;
        int row2StartX = (this.width - row2TotalW) / 2;

        double initialScanVal = (double) (MapConfig.scanPointsPerTick - SCAN_MIN) / (SCAN_MAX - SCAN_MIN);

        scanSlider = new SimpleSlider(
                row2StartX, row2Y, sliderW, 20,
                initialScanVal,
                val -> String.format("Scan Points/Tick: %,d", SCAN_MIN + (int) (val * (SCAN_MAX - SCAN_MIN))),
                val -> {
                    int pts = SCAN_MIN + (int) (val * (SCAN_MAX - SCAN_MIN));
                    MapConfig.scanPointsPerTick = pts;
                    if (scanInputBox != null)
                        scanInputBox.setValue(String.valueOf(pts));
                });
        this.addRenderableWidget(scanSlider);

        // EditBox: allows typing a number directly
        scanInputBox = new EditBox(this.font,
                row2StartX + sliderW + spacing, row2Y,
                inputBoxW, 20,
                Component.literal("Scan points"));
        scanInputBox.setMaxLength(6);
        scanInputBox.setValue(String.valueOf(MapConfig.scanPointsPerTick));
        scanInputBox.setHint(Component.literal("100-100k"));
        scanInputBox.setResponder(text -> {
            try {
                int val = Integer.parseInt(text.trim());
                if (val >= SCAN_MIN && val <= SCAN_MAX) {
                    MapConfig.scanPointsPerTick = val;
                    // Sync text box value -> slider position
                    if (scanSlider != null) {
                        scanSlider.setValue((double) (val - SCAN_MIN) / (SCAN_MAX - SCAN_MIN));
                    }
                    scanInputBox.setTextColor(0xFFFFFF); // white = valid
                } else {
                    scanInputBox.setTextColor(0xFF5555); // red = out of range
                }
            } catch (NumberFormatException ignored) {
                scanInputBox.setTextColor(0xFF5555); // red = not a number
            }
        });
        this.addRenderableWidget(scanInputBox);

        // Done Button
        this.addRenderableWidget(Button.builder(
                Component.literal("Done"),
                button -> {
                    MapConfig.save();
                    if (this.minecraft != null)
                        this.minecraft.setScreen(this.parent);
                }).bounds(10, 10, 60, 20).build());

        // Reset to Default Button
        this.addRenderableWidget(Button.builder(
                Component.literal("Reset to Default"),
                button -> {
                    MapConfig.minimapEnabled = true;
                    MapConfig.minimapXPercent = 0.82f;
                    MapConfig.minimapYPercent = 0.05f;
                    MapConfig.minimapSize = 64;
                    MapConfig.minimapZoom = 1.0f;
                    MapConfig.scanPointsPerTick = MapConfig.calculateDefaultScanPoints();
                    MapConfig.playerMarkerScale = 0.5f;
                    MapConfig.playerMarkerMode = 1;
                    MapConfig.playerPointerColor = 0xFFFF0000;
                    MapConfig.pinScale = 0.5f;
                    MapConfig.waypointsVisible = true;
                    MapConfig.minimapRotate = true;
                    MapConfig.coordsEnabled = true;
                    MapConfig.coordsXPercent = -1.0f;
                    MapConfig.coordsYPercent = -1.0f;
                    MapConfig.coordsScale = 1.0f;
                    MapConfig.requireMapBook = false;
                    MapConfig.serverRequireMapBook = false;
                    MapConfig.autoClearPin = true;
                    MapConfig.alwaysRescanExplored = false;

                    // Update UI elements values
                    selectedMinimap = false;
                    selectedCoords = false;
                    
                    // Reload screen
                    if (this.minecraft != null) {
                        this.init(this.minecraft, this.width, this.height);
                    }
                }).bounds(80, 10, 110, 20).build());

        // Auto Clear Pin Button (Top-Middle)
        autoClearPinButton = Button.builder(
                getAutoClearPinMessage(),
                button -> {
                    MapConfig.autoClearPin = !MapConfig.autoClearPin;
                    MapConfig.save();
                    button.setMessage(getAutoClearPinMessage());
                }).bounds(200, 10, 120, 20).build();
        this.addRenderableWidget(autoClearPinButton);

        // Always Rescan Explored Toggle Button
        alwaysRescanButton = Button.builder(
                getAlwaysRescanMessage(),
                button -> {
                    MapConfig.alwaysRescanExplored = !MapConfig.alwaysRescanExplored;
                    MapConfig.save();
                    button.setMessage(getAlwaysRescanMessage());
                }).bounds(this.width - 150 - 10, 10, 150, 20).build();
        this.addRenderableWidget(alwaysRescanButton);

        // Sync visibility at startup (both selectedMinimap and selectedCoords are false by default)
        updateWidgetVisibility();
    }

    private void updateWidgetVisibility() {
        if (minimapToggleButton != null) minimapToggleButton.visible = selectedMinimap;
        if (minimapRotateButton != null) minimapRotateButton.visible = selectedMinimap;
        if (minimapSizeSlider != null) minimapSizeSlider.visible = selectedMinimap;
        if (minimapZoomSlider != null) minimapZoomSlider.visible = selectedMinimap;
        if (pointerModeButton != null) pointerModeButton.visible = selectedMinimap;
        if (pointerScaleSlider != null) pointerScaleSlider.visible = selectedMinimap;
        if (pinScaleSlider != null) pinScaleSlider.visible = selectedMinimap;
        if (pointerColorButton != null) pointerColorButton.visible = selectedMinimap;

        if (coordsToggleButton != null) coordsToggleButton.visible = selectedCoords;
        if (coordsScaleSlider != null) coordsScaleSlider.visible = selectedCoords;
    }

    private Component getToggleMessage() {
        return Component.literal("Minimap: " + (MapConfig.minimapEnabled ? "ENABLED" : "DISABLED"));
    }

    private Component getRotateMessage() {
        return Component.literal("Map Rotate: " + (MapConfig.minimapRotate ? "ON" : "OFF"));
    }

    private Component getCoordsToggleMessage() {
        return Component.literal("Coords: " + (MapConfig.coordsEnabled ? "ENABLED" : "DISABLED"));
    }

    private Component getRequireBookMessage() {
        return Component.literal("Require Book: " + (MapConfig.requireMapBook ? "ENABLED" : "DISABLED"));
    }

    private Component getAutoClearPinMessage() {
        return Component.literal("Auto Clear Pin: " + (MapConfig.autoClearPin ? "ON" : "OFF"));
    }

    private Component getPointerModeMessage() {
        return Component.literal("Pointer: " + (MapConfig.playerMarkerMode == 1 ? "ARROW ONLY" : "SKIN + ARROW"));
    }

    private Component getPointerColorMessage() {
        String name = "Unknown";
        for (int i = 0; i < MapConfig.POINTER_COLORS.length; i++) {
            if (MapConfig.playerPointerColor == MapConfig.POINTER_COLORS[i]) {
                name = MapConfig.POINTER_COLOR_NAMES[i];
                break;
            }
        }
        return Component.literal("Color: " + name);
    }

    private Component getAlwaysRescanMessage() {
        return Component.literal("Rescan Explored: " + (MapConfig.alwaysRescanExplored ? "ON" : "OFF"));
    }



    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Prevent Minecraft's default background blur and dirt rendering, similar to MapScreen.java
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!(parent instanceof MapScreen)) {
            // Render plain dark background for global config screen
            guiGraphics.fill(0, 0, this.width, this.height, 0xFF0D0D0D);
            guiGraphics.drawCenteredString(this.font, "Simple Map Settings", this.width / 2, this.height / 2 - 50, 0xFFFFFF);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            return;
        }

        // Semi-transparent dark overlay — game view shows through behind
        guiGraphics.fill(0, 0, this.width, this.height, 0xAA000000);

        int size = MapConfig.minimapSize;
        int x = Math.max(2, Math.min((int) (this.width * MapConfig.minimapXPercent), this.width - size - 2));
        int y = Math.max(2, Math.min((int) (this.height * MapConfig.minimapYPercent), this.height - size - 2));

        boolean isHovered = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;

        // 1. Render Minimap
        if (MapConfig.minimapEnabled) {
            int borderColor = isHovered || isDraggingMinimap || selectedMinimap ? 0xFF00C8FF : 0xFF2D3033;
            guiGraphics.fill(x - 1, y - 1, x + size + 1, y, borderColor);
            guiGraphics.fill(x - 1, y + size, x + size + 1, y + size + 1, borderColor);
            guiGraphics.fill(x - 1, y, x, y + size, borderColor);
            guiGraphics.fill(x + size, y, x + size + 1, y + size, borderColor);

            if (this.minecraft != null && this.minecraft.player != null) {
                double interpX = net.minecraft.util.Mth.lerp(partialTick, this.minecraft.player.xo, this.minecraft.player.getX());
                double interpZ = net.minecraft.util.Mth.lerp(partialTick, this.minecraft.player.zo, this.minecraft.player.getZ());
                MapRenderer.getInstance().drawMap(
                        guiGraphics, x, y, size, size,
                        interpX, interpZ,
                        MapConfig.minimapZoom,
                        true, true, true, 0, 0,
                        partialTick);
            }

            if (isHovered || isDraggingMinimap || selectedMinimap) {
                guiGraphics.fill(x, y, x + size, y + size, 0x4400C8FF);
                String label = isDraggingMinimap ? "DRAGGING" : (selectedMinimap ? "SELECTED" : "DRAG ME");
                int labelW = this.font.width(label);
                guiGraphics.drawString(this.font, label, x + (size - labelW) / 2, y + (size - 9) / 2, 0xFFFFFF, true);
            }
        } else {
            int borderColor = isHovered || selectedMinimap ? 0xFF00C8FF : 0xFF666666;
            guiGraphics.fill(x, y, x + size, y + size, 0x55000000);
            guiGraphics.renderOutline(x, y, size, size, borderColor);
            String text = "Minimap Disabled";
            guiGraphics.drawString(this.font, text, x + (size - this.font.width(text)) / 2, y + (size - 9) / 2,
                    0xAAAAAA, false);
        }

        // 2. Render Coordinates (Coords)
        String coords = "0, 80, 0";
        if (this.minecraft != null && this.minecraft.player != null) {
            coords = String.format("%d, %d, %d", 
                    (int) Math.floor(this.minecraft.player.getX()), 
                    (int) Math.floor(this.minecraft.player.getY()), 
                    (int) Math.floor(this.minecraft.player.getZ()));
        }
        
        int cx, cy;
        if (MapConfig.coordsEnabled) {
            int textWidth = (int) (this.font.width(coords) * MapConfig.coordsScale);
            int textHeight = (int) (9 * MapConfig.coordsScale);
            
            if (MapConfig.coordsXPercent < 0 || MapConfig.coordsYPercent < 0) {
                cx = x + (size - textWidth) / 2;
                cy = y + size + 4;
            } else {
                cx = (int) (this.width * MapConfig.coordsXPercent);
                cy = (int) (this.height * MapConfig.coordsYPercent);
            }
            
            cx = Math.max(2, Math.min(cx, this.width - textWidth - 2));
            cy = Math.max(2, Math.min(cy, this.height - textHeight - 2));

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(cx, cy, 0);
            guiGraphics.pose().scale(MapConfig.coordsScale, MapConfig.coordsScale, 1.0f);
            
            int rawWidth = this.font.width(coords);
            guiGraphics.fill(-3, -2, rawWidth + 3, 9, 0x88000000);
            guiGraphics.drawString(this.font, coords, 0, 0, 0xFFFFFF, false);
            guiGraphics.pose().popPose();

            boolean isCoordsHovered = mouseX >= cx - 3 && mouseX < cx + textWidth + 3 && mouseY >= cy - 2 && mouseY < cy + textHeight + 2;
            if (isCoordsHovered || selectedCoords || isDraggingCoords) {
                int borderColor = isCoordsHovered || isDraggingCoords ? 0xFF00C8FF : 0xFF2D3033;
                guiGraphics.renderOutline(cx - 4, cy - 3, textWidth + 8, textHeight + 6, borderColor);
            }
        } else {
            String text = "Coords Disabled";
            int rawWidth = this.font.width(text);
            int placeholderW = (int) (rawWidth * MapConfig.coordsScale);
            int placeholderH = (int) (9 * MapConfig.coordsScale);

            if (MapConfig.coordsXPercent < 0 || MapConfig.coordsYPercent < 0) {
                cx = x + (size - placeholderW) / 2;
                cy = y + size + 4;
            } else {
                cx = (int) (this.width * MapConfig.coordsXPercent);
                cy = (int) (this.height * MapConfig.coordsYPercent);
            }
            
            cx = Math.max(2, Math.min(cx, this.width - placeholderW - 2));
            cy = Math.max(2, Math.min(cy, this.height - placeholderH - 2));

            guiGraphics.fill(cx - 3, cy - 2, cx + placeholderW + 3, cy + placeholderH + 2, 0x55000000);
            guiGraphics.renderOutline(cx - 4, cy - 3, placeholderW + 8, placeholderH + 6, 0xFF666666);

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(cx, cy, 0);
            guiGraphics.pose().scale(MapConfig.coordsScale, MapConfig.coordsScale, 1.0f);
            guiGraphics.drawString(this.font, text, 0, 0, 0xAAAAAA, false);
            guiGraphics.pose().popPose();

            boolean isCoordsHovered = mouseX >= cx - 3 && mouseX < cx + placeholderW + 3 && mouseY >= cy - 2 && mouseY < cy + placeholderH + 2;
            if (isCoordsHovered || selectedCoords || isDraggingCoords) {
                int borderColor = isCoordsHovered || isDraggingCoords ? 0xFF00C8FF : 0xFF2D3033;
                guiGraphics.renderOutline(cx - 4, cy - 3, placeholderW + 8, placeholderH + 6, borderColor);
            }
        }

        // Draw widgets (sliders, buttons, editbox)
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Header hint text
        guiGraphics.drawCenteredString(this.font, "Click & Drag Minimap or Coords to position them. Adjust settings below.",
                this.width / 2, 30, 0xFFFFFF);
        
        if (selectedMinimap) {
            guiGraphics.drawCenteredString(this.font, "Minimap settings visible", this.width / 2, 44, 0x00C8FF);
        } else if (selectedCoords) {
            guiGraphics.drawCenteredString(this.font, "Coordinates settings visible", this.width / 2, 44, 0x00C8FF);
        } else {
            guiGraphics.drawCenteredString(this.font, "Click on Minimap or Coords text to adjust their specific properties.",
                    this.width / 2, 44, 0xAAAAAA);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!(parent instanceof MapScreen)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            // 1. Check Minimap click
            int size = MapConfig.minimapSize;
            int x = Math.max(2, Math.min((int) (this.width * MapConfig.minimapXPercent), this.width - size - 2));
            int y = Math.max(2, Math.min((int) (this.height * MapConfig.minimapYPercent), this.height - size - 2));
            if (mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size) {
                this.selectedMinimap = true;
                this.selectedCoords = false;
                updateWidgetVisibility();
                if (MapConfig.minimapEnabled) {
                    this.isDraggingMinimap = true;
                    this.dragOffsetX = mouseX - x;
                    this.dragOffsetY = mouseY - y;
                }
                return true;
            }

            // 2. Check Coords click
            String coords = MapConfig.coordsEnabled ? "0, 80, 0" : "Coords Disabled";
            if (MapConfig.coordsEnabled && this.minecraft != null && this.minecraft.player != null) {
                coords = String.format("%d, %d, %d", 
                        (int) Math.floor(this.minecraft.player.getX()), 
                        (int) Math.floor(this.minecraft.player.getY()), 
                        (int) Math.floor(this.minecraft.player.getZ()));
            }
            int textWidth = (int) (this.font.width(coords) * MapConfig.coordsScale);
            int textHeight = (int) (9 * MapConfig.coordsScale);
            
            int cx, cy;
            if (MapConfig.coordsXPercent < 0 || MapConfig.coordsYPercent < 0) {
                cx = x + (size - textWidth) / 2;
                cy = y + size + 4;
            } else {
                cx = (int) (this.width * MapConfig.coordsXPercent);
                cy = (int) (this.height * MapConfig.coordsYPercent);
            }
            
            cx = Math.max(2, Math.min(cx, this.width - textWidth - 2));
            cy = Math.max(2, Math.min(cy, this.height - textHeight - 2));

            if (mouseX >= cx - 3 && mouseX < cx + textWidth + 3 && mouseY >= cy - 2 && mouseY < cy + textHeight + 2) {
                this.selectedCoords = true;
                this.selectedMinimap = false;
                updateWidgetVisibility();
                this.isDraggingCoords = true;
                this.dragCoordsOffsetX = mouseX - cx;
                this.dragCoordsOffsetY = mouseY - cy;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!(parent instanceof MapScreen)) {
            return super.mouseReleased(mouseX, mouseY, button);
        }
        if (button == 0) {
            isDraggingMinimap = false;
            isDraggingCoords = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!(parent instanceof MapScreen)) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (isDraggingMinimap) {
            MapConfig.minimapXPercent = Math.max(0f, Math.min(1f, (float) (mouseX - dragOffsetX) / this.width));
            MapConfig.minimapYPercent = Math.max(0f, Math.min(1f, (float) (mouseY - dragOffsetY) / this.height));
            return true;
        }
        if (isDraggingCoords) {
            MapConfig.coordsXPercent = Math.max(0f, Math.min(1f, (float) (mouseX - dragCoordsOffsetX) / this.width));
            MapConfig.coordsYPercent = Math.max(0f, Math.min(1f, (float) (mouseY - dragCoordsOffsetY) / this.height));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Custom slider with public setValue() for external sync from EditBox
    private static class SimpleSlider extends AbstractSliderButton {
        private final java.util.function.Consumer<Double> onChange;
        private final java.util.function.Function<Double, String> labelProvider;

        public SimpleSlider(int x, int y, int width, int height, double defaultValue,
                java.util.function.Function<Double, String> labelProvider,
                java.util.function.Consumer<Double> onChange) {
            super(x, y, width, height, Component.literal(labelProvider.apply(defaultValue)), defaultValue);
            this.labelProvider = labelProvider;
            this.onChange = onChange;
        }

        /**
         * Called from the EditBox responder to push a new normalised value (0..1)
         * into the slider WITHOUT triggering applyValue() to avoid a feedback loop.
         */
        public void setValue(double newValue) {
            this.value = Math.max(0.0, Math.min(1.0, newValue));
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(labelProvider.apply(this.value)));
        }

        @Override
        protected void applyValue() {
            onChange.accept(this.value);
        }
    }
}
