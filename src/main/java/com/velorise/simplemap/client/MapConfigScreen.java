package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MapConfigScreen extends Screen {
    private final Screen parent;
    private static com.mojang.blaze3d.pipeline.RenderTarget minimapTarget = null;
    private boolean isDraggingMinimap = false;
    private double dragOffsetX = 0;
    private double dragOffsetY = 0;

    // Coords dragging & selection state
    private boolean selectedMinimap = false;
    private boolean selectedCoords = false;
    private boolean isDraggingCoords = false;
    private double dragCoordsOffsetX = 0;
    private double dragCoordsOffsetY = 0;

    // UI Tab & Side Panel State
    private int activeTab = 0; // 0 = Minimap, 1 = Coords, 2 = System/Performance
    private Button tabMapButton;
    private Button tabCoordsButton;
    private Button tabSystemButton;
    private Button doneButton;
    private Button resetToDefaultButton;

    // Minimap controls reference
    private Button minimapToggleButton;
    private Button minimapRotateButton;
    private Button minimapShapeButton;
    private SimpleSlider minimapSizeSlider;
    private SimpleSlider minimapZoomSlider;

    // Player marker controls reference
    private Button pointerModeButton;
    private SimpleSlider pointerScaleSlider;
    private SimpleSlider pinScaleSlider;

    // Coords controls reference
    private Button coordsToggleButton;
    private SimpleSlider coordsScaleSlider;
    private Button coordsColorButton;
    private Button pointerColorButton;
    private Button compassLettersToggleButton;
    private Button compassColorButton;

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

        // ==================== TABS (Top of Left Panel) ====================
        // Tab buttons are at y = 20, width = 74, spacing = 4
        int tabY = 20;
        int tabW = 74;
        int tabSpacing = 4;
        int startX = 14;

        tabMapButton = Button.builder(
                Component.literal(activeTab == 0 ? "§6§lMinimap" : "Minimap"),
                button -> {
                    activeTab = 0;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX, tabY, tabW, 20).build();
        this.addRenderableWidget(tabMapButton);

        tabCoordsButton = Button.builder(
                Component.literal(activeTab == 1 ? "§6§lCoords" : "Coords"),
                button -> {
                    activeTab = 1;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX + tabW + tabSpacing, tabY, tabW, 20).build();
        this.addRenderableWidget(tabCoordsButton);

        tabSystemButton = Button.builder(
                Component.literal(activeTab == 2 ? "§6§lSystem" : "System"),
                button -> {
                    activeTab = 2;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX + (tabW + tabSpacing) * 2, tabY, tabW, 20).build();
        this.addRenderableWidget(tabSystemButton);

        // ==================== CONTROLS GRID ====================
        int col1X = 16;
        int col2X = 132;
        int colW = 108;

        // Row 1 (y = 52)
        minimapToggleButton = Button.builder(
                getToggleMessage(),
                button -> {
                    MapConfig.minimapEnabled = !MapConfig.minimapEnabled;
                    button.setMessage(getToggleMessage());
                }).bounds(col1X, 52, colW, 20).build();
        this.addRenderableWidget(minimapToggleButton);

        double initialSizeVal = (double) (MapConfig.minimapSize - 16) / (150 - 16);
        minimapSizeSlider = new SimpleSlider(
                col2X, 52, colW, 20,
                initialSizeVal,
                val -> String.format("Size: %dpx", 16 + (int) (val * (150 - 16))),
                val -> MapConfig.minimapSize = 16 + (int) (val * (150 - 16)));
        this.addRenderableWidget(minimapSizeSlider);

        // Row 2 (y = 77)
        minimapShapeButton = Button.builder(
                getShapeMessage(),
                button -> {
                    MapConfig.minimapCircle = !MapConfig.minimapCircle;
                    button.setMessage(getShapeMessage());
                }).bounds(col1X, 77, colW, 20).build();
        this.addRenderableWidget(minimapShapeButton);

        double initialZoomVal = (MapConfig.minimapZoom - 0.05) / (2.0 - 0.05);
        minimapZoomSlider = new SimpleSlider(
                col2X, 77, colW, 20,
                initialZoomVal,
                val -> String.format("Zoom: %.2fx", 0.05 + val * (2.0 - 0.05)),
                val -> MapConfig.minimapZoom = (float) (0.05 + val * (2.0 - 0.05)));
        this.addRenderableWidget(minimapZoomSlider);

        // Row 3 (y = 102)
        minimapRotateButton = Button.builder(
                getRotateMessage(),
                button -> {
                    MapConfig.minimapRotate = !MapConfig.minimapRotate;
                    button.setMessage(getRotateMessage());
                }).bounds(col1X, 102, colW, 20).build();
        this.addRenderableWidget(minimapRotateButton);

        double initialPointerScaleVal = (MapConfig.playerMarkerScale - 0.1) / (1.0 - 0.1);
        pointerScaleSlider = new SimpleSlider(
                col2X, 102, colW, 20,
                initialPointerScaleVal,
                val -> String.format("Pointer: %.2fx", 0.1 + val * (1.0 - 0.1)),
                val -> MapConfig.playerMarkerScale = (float) (0.1 + val * (1.0 - 0.1)));
        this.addRenderableWidget(pointerScaleSlider);

        // Row 4 (y = 127)
        pointerModeButton = Button.builder(
                getPointerModeMessage(),
                button -> {
                    MapConfig.playerMarkerMode = (MapConfig.playerMarkerMode + 1) % 2;
                    button.setMessage(getPointerModeMessage());
                }).bounds(col1X, 127, colW, 20).build();
        this.addRenderableWidget(pointerModeButton);

        double initialPinScaleVal = (MapConfig.pinScale - 0.1) / (1.0 - 0.1);
        pinScaleSlider = new SimpleSlider(
                col2X, 127, colW, 20,
                initialPinScaleVal,
                val -> String.format("Pin: %.2fx", 0.1 + val * (1.0 - 0.1)),
                val -> {
                    MapConfig.pinScale = (float) (0.1 + val * (1.0 - 0.1));
                    MapConfig.save();
                });
        this.addRenderableWidget(pinScaleSlider);

        // Row 5 (y = 152)
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
                }).bounds(col1X, 152, colW, 20).build();
        this.addRenderableWidget(pointerColorButton);

        // Row 5, col 2 (y = 152)
        compassLettersToggleButton = Button.builder(
                getCompassLettersMessage(),
                button -> {
                    MapConfig.compassLettersVisible = !MapConfig.compassLettersVisible;
                    button.setMessage(getCompassLettersMessage());
                }).bounds(col2X, 152, colW, 20).build();
        this.addRenderableWidget(compassLettersToggleButton);

        // Row 6 (y = 177)
        compassColorButton = Button.builder(
                getCompassColorMessage(),
                button -> {
                    int currentIndex = 0;
                    for (int i = 0; i < MapConfig.POINTER_COLORS.length; i++) {
                        if (MapConfig.compassLetterColor == MapConfig.POINTER_COLORS[i]) {
                            currentIndex = i;
                            break;
                        }
                    }
                    int nextIndex = (currentIndex + 1) % MapConfig.POINTER_COLORS.length;
                    MapConfig.compassLetterColor = MapConfig.POINTER_COLORS[nextIndex];
                    button.setMessage(getCompassColorMessage());
                }).bounds(col1X, 177, colW, 20).build();
        this.addRenderableWidget(compassColorButton);

        // ==================== COORDS CONTROLS ====================
        coordsToggleButton = Button.builder(
                getCoordsToggleMessage(),
                button -> {
                    MapConfig.coordsEnabled = !MapConfig.coordsEnabled;
                    button.setMessage(getCoordsToggleMessage());
                }).bounds(col1X, 52, colW, 20).build();
        this.addRenderableWidget(coordsToggleButton);

        double initialCoordsScaleVal = (MapConfig.coordsScale - 0.1) / (2.0 - 0.1);
        coordsScaleSlider = new SimpleSlider(
                col2X, 52, colW, 20,
                initialCoordsScaleVal,
                val -> String.format("Coords: %.2fx", 0.1 + val * (2.0 - 0.1)),
                val -> MapConfig.coordsScale = (float) (0.1 + val * (2.0 - 0.1)));
        this.addRenderableWidget(coordsScaleSlider);

        // Row 2 (y = 77)
        coordsColorButton = Button.builder(
                getCoordsColorMessage(),
                button -> {
                    int currentIndex = 0;
                    for (int i = 0; i < MapConfig.POINTER_COLORS.length; i++) {
                        if (MapConfig.coordsTextColor == MapConfig.POINTER_COLORS[i]) {
                            currentIndex = i;
                            break;
                        }
                    }
                    int nextIndex = (currentIndex + 1) % MapConfig.POINTER_COLORS.length;
                    MapConfig.coordsTextColor = MapConfig.POINTER_COLORS[nextIndex];
                    button.setMessage(getCoordsColorMessage());
                }).bounds(col1X, 77, colW, 20).build();
        this.addRenderableWidget(coordsColorButton);

        // ==================== SYSTEM CONTROLS ====================
        autoClearPinButton = Button.builder(
                getAutoClearPinMessage(),
                button -> {
                    MapConfig.autoClearPin = !MapConfig.autoClearPin;
                    MapConfig.save();
                    button.setMessage(getAutoClearPinMessage());
                }).bounds(col1X, 52, colW, 20).build();
        this.addRenderableWidget(autoClearPinButton);

        double initialScanVal = (double) (MapConfig.scanPointsPerTick - SCAN_MIN) / (SCAN_MAX - SCAN_MIN);
        scanSlider = new SimpleSlider(
                col2X, 52, colW, 20,
                initialScanVal,
                val -> String.format("Scan: %,d", SCAN_MIN + (int) (val * (SCAN_MAX - SCAN_MIN))),
                val -> {
                    int pts = SCAN_MIN + (int) (val * (SCAN_MAX - SCAN_MIN));
                    MapConfig.scanPointsPerTick = pts;
                    if (scanInputBox != null)
                        scanInputBox.setValue(String.valueOf(pts));
                });
        this.addRenderableWidget(scanSlider);

        alwaysRescanButton = Button.builder(
                getAlwaysRescanMessage(),
                button -> {
                    MapConfig.alwaysRescanExplored = !MapConfig.alwaysRescanExplored;
                    MapConfig.save();
                    button.setMessage(getAlwaysRescanMessage());
                }).bounds(col1X, 77, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Always refresh explored chunks.\nWarning: Can impact performance.")))
                .build();
        this.addRenderableWidget(alwaysRescanButton);

        scanInputBox = new EditBox(this.font,
                col2X, 77, colW - 1, 20,
                Component.literal("Scan points"));
        scanInputBox.setMaxLength(6);
        scanInputBox.setValue(String.valueOf(MapConfig.scanPointsPerTick));
        scanInputBox.setHint(Component.literal("100-10k"));
        scanInputBox.setResponder(text -> {
            try {
                int val = Integer.parseInt(text.trim());
                if (val >= SCAN_MIN && val <= SCAN_MAX) {
                    MapConfig.scanPointsPerTick = val;
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

        resetToDefaultButton = Button.builder(
                Component.literal("Reset Default"),
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
                    MapConfig.minimapCircle = false;
                    MapConfig.coordsEnabled = true;
                    MapConfig.coordsXPercent = -1.0f;
                    MapConfig.coordsYPercent = -1.0f;
                    MapConfig.coordsScale = 0.8f;
                    MapConfig.coordsTextColor = 0xFFFFFFFF;
                    MapConfig.compassLettersVisible = true;
                    MapConfig.compassLetterColor = 0xFFFFFFFF;
                    MapConfig.requireMapBook = false;
                    MapConfig.serverRequireMapBook = false;
                    MapConfig.autoClearPin = true;
                    MapConfig.alwaysRescanExplored = false;

                    selectedMinimap = false;
                    selectedCoords = false;

                    if (this.minecraft != null) {
                        this.init(this.minecraft, this.width, this.height);
                    }
                }).bounds(col1X, 102, colW, 20).build();
        this.addRenderableWidget(resetToDefaultButton);

        // ==================== DONE BUTTON ====================
        doneButton = Button.builder(
                Component.literal("Done"),
                button -> {
                    MapConfig.save();
                    if (this.minecraft != null)
                        this.minecraft.setScreen(this.parent);
                }).bounds(col1X, this.height - 30, colW * 2 + 8, 20).build();
        this.addRenderableWidget(doneButton);

        // Sync visibility at startup
        updateWidgetVisibility();
    }

    private void updateTabButtons() {
        if (tabMapButton != null)
            tabMapButton.setMessage(Component.literal(activeTab == 0 ? "§6§lMinimap" : "Minimap"));
        if (tabCoordsButton != null)
            tabCoordsButton.setMessage(Component.literal(activeTab == 1 ? "§6§lCoords" : "Coords"));
        if (tabSystemButton != null)
            tabSystemButton.setMessage(Component.literal(activeTab == 2 ? "§6§lSystem" : "System"));
    }

    private void updateWidgetVisibility() {
        boolean showMap = activeTab == 0;
        boolean showCoords = activeTab == 1;
        boolean showSystem = activeTab == 2;

        if (minimapToggleButton != null)
            minimapToggleButton.visible = showMap;
        if (minimapRotateButton != null)
            minimapRotateButton.visible = showMap;
        if (minimapShapeButton != null)
            minimapShapeButton.visible = showMap;
        if (minimapSizeSlider != null)
            minimapSizeSlider.visible = showMap;
        if (minimapZoomSlider != null)
            minimapZoomSlider.visible = showMap;
        if (pointerModeButton != null)
            pointerModeButton.visible = showMap;
        if (pointerScaleSlider != null)
            pointerScaleSlider.visible = showMap;
        if (pinScaleSlider != null)
            pinScaleSlider.visible = showMap;
        if (pointerColorButton != null)
            pointerColorButton.visible = showMap;
        if (compassLettersToggleButton != null)
            compassLettersToggleButton.visible = showMap;
        if (compassColorButton != null)
            compassColorButton.visible = showMap;

        if (coordsToggleButton != null)
            coordsToggleButton.visible = showCoords;
        if (coordsScaleSlider != null)
            coordsScaleSlider.visible = showCoords;
        if (coordsColorButton != null)
            coordsColorButton.visible = showCoords;

        if (autoClearPinButton != null)
            autoClearPinButton.visible = showSystem;
        if (alwaysRescanButton != null)
            alwaysRescanButton.visible = showSystem;
        if (resetToDefaultButton != null)
            resetToDefaultButton.visible = showSystem;
        if (scanSlider != null)
            scanSlider.visible = showSystem;
        if (scanInputBox != null)
            scanInputBox.visible = showSystem;
    }

    private Component getToggleMessage() {
        return Component.literal("Minimap: " + (MapConfig.minimapEnabled ? "ENABLED" : "DISABLED"));
    }

    private Component getRotateMessage() {
        return Component.literal("Map Rotate: " + (MapConfig.minimapRotate ? "ON" : "OFF"));
    }

    private Component getShapeMessage() {
        return Component.literal("Shape: " + (MapConfig.minimapCircle ? "CIRCLE" : "SQUARE"));
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

    private Component getCoordsColorMessage() {
        String name = "Unknown";
        for (int i = 0; i < MapConfig.POINTER_COLORS.length; i++) {
            if (MapConfig.coordsTextColor == MapConfig.POINTER_COLORS[i]) {
                name = MapConfig.POINTER_COLOR_NAMES[i];
                break;
            }
        }
        return Component.literal("Coords Color: " + name);
    }

    private Component getCompassLettersMessage() {
        return Component.literal("Compass Letters: " + (MapConfig.compassLettersVisible ? "ON" : "OFF"));
    }

    private Component getCompassColorMessage() {
        String name = "Unknown";
        for (int i = 0; i < MapConfig.POINTER_COLORS.length; i++) {
            if (MapConfig.compassLetterColor == MapConfig.POINTER_COLORS[i]) {
                name = MapConfig.POINTER_COLOR_NAMES[i];
                break;
            }
        }
        return Component.literal("Compass Color: " + name);
    }

    private Component getAlwaysRescanMessage() {
        return Component.literal("Always Rescan: " + (MapConfig.alwaysRescanExplored ? "ON" : "OFF"));
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Prevent Minecraft's default background blur and dirt rendering, similar to
        // MapScreen.java
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!(parent instanceof MapScreen)) {
            // Render plain dark background for global config screen
            guiGraphics.fill(0, 0, this.width, this.height, 0xFF0D0D0D);
            guiGraphics.drawCenteredString(this.font, "Simple Map Settings", this.width / 2, this.height / 2 - 50,
                    0xFFFFFF);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            return;
        }

        // Semi-transparent dark overlay — game view shows through behind
        guiGraphics.fill(0, 0, this.width, this.height, 0xAA000000);

        // Draw Left Settings Panel Card Background & Borders
        guiGraphics.fill(10, 15, 250, this.height - 10, 0xDD111215); // Dark slate card body
        guiGraphics.renderOutline(9, 14, 242, this.height - 23, 0xFF2D3033); // Card border outline
        guiGraphics.fill(10, 58, 250, 59, 0xFF2D3033); // Divider line under tabs

        int size = MapConfig.minimapSize;
        int x = Math.max(2, Math.min((int) (this.width * MapConfig.minimapXPercent), this.width - size - 2));
        int y = Math.max(2, Math.min((int) (this.height * MapConfig.minimapYPercent), this.height - size - 2));

        boolean isHovered = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;

        // 1. Render Minimap
        if (MapConfig.minimapEnabled) {
            int borderColor = isHovered || isDraggingMinimap || selectedMinimap ? 0xFF00C8FF : 0xFF2D3033;
            float cx = x + size / 2.0f;
            float cy = y + size / 2.0f;
            float radius = size / 2.0f;
            int borderThickness = Math.max(2, size / 32);

            if (MapConfig.minimapCircle) {
                // CIRCULAR PREVIEW (OpenGL Stencil Masking)
                int tHalf = Math.max(1, borderThickness / 2);
                cx = x + radius;
                cy = y + radius;
                float clipRadius = radius - tHalf;
                int circleBorderColor = isHovered || isDraggingMinimap || selectedMinimap ? 0xFF00C8FF : 0xFF000000;

                // Ensure stencil buffer is enabled on the main framebuffer
                if (this.minecraft != null) {
                    this.minecraft.getMainRenderTarget().enableStencil();
                }

                // 1. Enable stencil test and clear stencil buffer
                org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
                org.lwjgl.opengl.GL11.glStencilMask(0xFF); // Enable writes for clearing!
                org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_STENCIL_BUFFER_BIT);

                // 2. Configure stencil to write 1 on circle area
                org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_ALWAYS, 1, 0xFF);
                org.lwjgl.opengl.GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_REPLACE, org.lwjgl.opengl.GL11.GL_REPLACE,
                        org.lwjgl.opengl.GL11.GL_REPLACE);

                // Disable color writes and depth test so the clipping circle writes purely to
                // stencil
                org.lwjgl.opengl.GL11.glColorMask(false, false, false, false);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);

                // Draw circular mask using scanlines of fillFloat (precise float coordinates)
                float maskRadius = clipRadius + 0.5f; // Overlap slightly under the border
                for (int dy = -(int) maskRadius; dy <= (int) maskRadius; dy++) {
                    float dx = (float) Math.sqrt(maskRadius * maskRadius - dy * dy);
                    fillFloat(guiGraphics, cx - dx, cy + dy, cx + dx, cy + dy + 1.0f, 0xFFFFFFFF);
                }

                // Flush the stencil write while color mask and depth test are disabled
                guiGraphics.flush();

                // Restore color mask
                org.lwjgl.opengl.GL11.glColorMask(true, true, true, true);

                // 3. Configure stencil to only draw inside the circle (where stencil is 1)
                org.lwjgl.opengl.GL11.glStencilFunc(org.lwjgl.opengl.GL11.GL_EQUAL, 1, 0xFF);
                org.lwjgl.opengl.GL11.glStencilOp(org.lwjgl.opengl.GL11.GL_KEEP, org.lwjgl.opengl.GL11.GL_KEEP,
                        org.lwjgl.opengl.GL11.GL_KEEP);
                org.lwjgl.opengl.GL11.glStencilMask(0x00);

                // Draw the map preview directly (it will be clipped to the circle!)
                if (this.minecraft != null && this.minecraft.player != null) {
                    double interpX = net.minecraft.util.Mth.lerp(partialTick, this.minecraft.player.xo,
                            this.minecraft.player.getX());
                    double interpZ = net.minecraft.util.Mth.lerp(partialTick, this.minecraft.player.zo,
                            this.minecraft.player.getZ());
                    MapRenderer.getInstance().drawMap(
                            guiGraphics, x, y, size, size,
                            interpX, interpZ,
                            MapConfig.minimapZoom,
                            true, true, true, 0, 0,
                            partialTick);
                }

                // Flush map preview draw call
                guiGraphics.flush();

                // 4. Disable stencil test
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);

                // 5. Draw the bezel overlay (borders) always on top using vector rings
                drawCircleRing(guiGraphics, cx, cy, clipRadius, radius, 64, 0xFF33302D); // Inner slate grey ring
                drawCircleRing(guiGraphics, cx, cy, radius, radius + borderThickness, 64, circleBorderColor); // Outer
                                                                                                              // black /
                                                                                                              // highlight
                                                                                                              // ring

                // Dragging highlight overlay
                if (isHovered || isDraggingMinimap || selectedMinimap) {
                    // Draw a semi-transparent circular overlay on top using the batched circle
                    // helper
                    drawSolidCircleHelper(guiGraphics, cx, cy, clipRadius, 64, 0x4400C8FF, 0.3f);
                }

                // Flush bezel mask and highlight overlay
                guiGraphics.flush();

                // 4. Draw directions
                if (this.minecraft != null && this.minecraft.player != null) {
                    drawCompassDirectionsHelper(guiGraphics, this.minecraft, cx, cy, radius, borderThickness,
                            this.minecraft.player, true, partialTick);
                }
            } else {
                // SQUARE PREVIEW
                guiGraphics.fill(x - 1, y - 1, x + size + 1, y, borderColor);
                guiGraphics.fill(x - 1, y + size, x + size + 1, y + size + 1, borderColor);
                guiGraphics.fill(x - 1, y, x, y + size, borderColor);
                guiGraphics.fill(x + size, y, x + size + 1, y + size, borderColor);

                if (this.minecraft != null && this.minecraft.player != null) {
                    double interpX = net.minecraft.util.Mth.lerp(partialTick, this.minecraft.player.xo,
                            this.minecraft.player.getX());
                    double interpZ = net.minecraft.util.Mth.lerp(partialTick, this.minecraft.player.zo,
                            this.minecraft.player.getZ());
                    MapRenderer.getInstance().drawMap(
                            guiGraphics, x, y, size, size,
                            interpX, interpZ,
                            MapConfig.minimapZoom,
                            true, true, true, 0, 0,
                            partialTick);
                }

                // Draw directions (square)
                if (this.minecraft != null && this.minecraft.player != null) {
                    drawCompassDirectionsHelper(guiGraphics, this.minecraft, cx, cy, radius, borderThickness,
                            this.minecraft.player, false, partialTick);
                }

                if (isHovered || isDraggingMinimap || selectedMinimap) {
                    guiGraphics.fill(x, y, x + size, y + size, 0x4400C8FF);
                }
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
            int coordsColor = MapRenderer.getInstance().getActualPointerColor(MapConfig.coordsTextColor);
            guiGraphics.drawString(this.font, coords, 0, 0, coordsColor, false);
            guiGraphics.pose().popPose();

            boolean isCoordsHovered = mouseX >= cx - 3 && mouseX < cx + textWidth + 3 && mouseY >= cy - 2
                    && mouseY < cy + textHeight + 2;
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

            boolean isCoordsHovered = mouseX >= cx - 3 && mouseX < cx + placeholderW + 3 && mouseY >= cy - 2
                    && mouseY < cy + placeholderH + 2;
            if (isCoordsHovered || selectedCoords || isDraggingCoords) {
                int borderColor = isCoordsHovered || isDraggingCoords ? 0xFF00C8FF : 0xFF2D3033;
                guiGraphics.renderOutline(cx - 4, cy - 3, placeholderW + 8, placeholderH + 6, borderColor);
            }
        }

        // Draw widgets (sliders, buttons, editbox)
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Simple hint above Done button
        int panelCenterX = doneButton.getX() + doneButton.getWidth() / 2;
        int hintY = doneButton.getY() - 18;

        guiGraphics.drawCenteredString(
                this.font,
                "Click & Drag",
                panelCenterX,
                hintY,
                0x808080);
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

    private void drawSolidCircleHelper(GuiGraphics guiGraphics, float cx, float cy, float radius, int numSegments,
            int color, float z) {
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

            // Degenerate Quad (4 vertices representing a triangle) at Z = z
            consumer.addVertex(matrix, cx, cy, z).setColor(r, g, b, a);
            consumer.addVertex(matrix, x1, y1, z).setColor(r, g, b, a);
            consumer.addVertex(matrix, x2, y2, z).setColor(r, g, b, a);
            consumer.addVertex(matrix, cx, cy, z).setColor(r, g, b, a);
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

    private void drawCompassDirectionsHelper(GuiGraphics guiGraphics, Minecraft mc, float cx, float cy, float radius,
            float borderThickness, net.minecraft.world.entity.player.Player player, boolean isCircle,
            float partialTick) {
        if (!MapConfig.compassLettersVisible) {
            return;
        }

        float playerYaw;
        if (MapConfig.minimapRotate) {
            playerYaw = net.minecraft.util.Mth.rotLerp(partialTick, player.yRotO, player.getYRot());
        } else {
            playerYaw = 180.0f;
        }

        float angleN = -playerYaw + 90.0f;
        float angleE = -playerYaw + 180.0f;
        float angleS = -playerYaw + 270.0f;
        float angleW = -playerYaw;

        float limit;
        if (isCircle) {
            limit = radius * 0.915f;
        } else {
            limit = radius + borderThickness / 2.0f;
        }

        int letterColor = MapRenderer.getInstance().getActualPointerColor(MapConfig.compassLetterColor);
        drawDirectionLetterHelper(guiGraphics, mc, cx, cy, limit, angleN, "N", letterColor, isCircle);
        drawDirectionLetterHelper(guiGraphics, mc, cx, cy, limit, angleE, "E", letterColor, isCircle);
        drawDirectionLetterHelper(guiGraphics, mc, cx, cy, limit, angleS, "S", letterColor, isCircle);
        drawDirectionLetterHelper(guiGraphics, mc, cx, cy, limit, angleW, "W", letterColor, isCircle);
    }

    private void drawDirectionLetterHelper(GuiGraphics guiGraphics, Minecraft mc, float cx, float cy, float limit,
            float angleDegrees, String letter, int color, boolean isCircle) {
        float rad = (float) Math.toRadians(angleDegrees);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        float x, y;
        if (isCircle) {
            x = cx + limit * cos;
            y = cy + limit * sin;
        } else {
            float scale = 1.0f / Math.max(Math.abs(cos), Math.abs(sin));
            x = cx + limit * cos * scale;
            y = cy + limit * sin * scale;
        }

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(x, y, 0);
        guiGraphics.pose().scale(0.7f, 0.7f, 1.0f);

        int textWidth = mc.font.width(letter);
        guiGraphics.drawString(mc.font, letter, -textWidth / 2, -4, color, true);
        guiGraphics.pose().popPose();
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