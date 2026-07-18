package com.velorise.simplemap.client;

import com.velorise.simplemap.ServerConfig;
import com.velorise.simplemap.network.NetworkHandler;
import com.velorise.simplemap.network.payload.SyncConfigPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

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
    private int activeTab = 0; // 0 = Map, 1 = Coords, 2 = Colors, 3 = System
    private Button tabMapButton;
    private Button tabCoordsButton;
    private Button tabColorsButton;
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
    private Button compassLettersToggleButton;
    private Button showInScreensButton;
    private Button mapStyleButton;
    private Button displayFlowersButton;
    private Button cursorBiomeButton;
    private Button cursorBlockButton;

    // Shared color editor: one input and a small reusable saved-color palette.
    private static final String[] COLOR_TARGET_NAMES = { "Arrow", "Ring", "Compass", "Coords" };
    private int selectedColorTarget = 0;
    private Button colorTargetButton;
    private Button presetColorButton;
    private Button savePaletteColorButton;
    private Button customBlockColorsButton;
    private EditBox sharedColorInput;
    private final List<ColorSwatchButton> paletteButtons = new ArrayList<>();

    // Keep references so slider and editbox can sync each other
    private SimpleSlider scanSlider;
    private EditBox scanInputBox;

    // Require Book toggle button
    private Button requireBookButton;
    private Button autoClearPinButton;
    private Button deathWaypointButton;
    private Button deathWaypointLimitButton;
    private Button alwaysRescanButton;
    private Button reloadAllRegionsButton;
    private Button mapRevealModeButton;
    private Button blockColourModeButton;
    private Button terrainSlopesButton;
    private Button fastFullscreenButton;
    private Button caveScanButton;

    // Shared responsive panel geometry. Init and render use the same numbers so
    // controls cannot drift outside the card at different GUI scales.
    private int panelLeft = 10;
    private int panelTop = 15;
    private int panelWidth = 240;
    private int panelRight = 250;
    private int panelBottom = 0;

    private static final int SCAN_CHUNK_MIN = 4;
    private static final int SCAN_CHUNK_MAX = 390; // 390 represents AUTO/MAX
    private static final int PALETTE_LABEL_Y = 104;
    private static final int PALETTE_SWATCH_Y = 116;

    public MapConfigScreen(Screen parent) {
        super(Component.literal("Minimap Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!(parent instanceof MapScreen)) {
            // Global/server config screen opened from the Mods list.
            int btnWidth = 176;
            int btnHeight = 20;
            boolean remoteServer = isRemoteServerSession();
            boolean remoteExtension = remoteServer && MapConfig.serverExtensionAvailable;

            requireBookButton = Button.builder(
                    getServerRequireBookMessage(remoteServer, remoteExtension),
                    button -> {
                        ServerConfig.requireMapBook = !ServerConfig.requireMapBook;
                        MapConfig.serverRequireMapBook = ServerConfig.requireMapBook;
                        ServerConfig.save();
                        syncIntegratedServerConfig();
                        button.setMessage(getServerRequireBookMessage(false, false));
                    }).bounds((this.width - btnWidth) / 2, this.height / 2 - 30, btnWidth, btnHeight)
                    .tooltip(Tooltip.create(Component.literal(
                            remoteServer && !remoteExtension
                                    ? "Map Books require the Simple Map server extension."
                                    : "Require a learned Map Book before the map can open.")))
                    .build();
            requireBookButton.active = !remoteServer;
            this.addRenderableWidget(requireBookButton);

            caveScanButton = Button.builder(
                    getServerCaveModeMessage(remoteServer, remoteExtension),
                    button -> {
                        if (remoteServer && !remoteExtension) {
                            MapConfig.localCaveMapMode = (MapConfig.localCaveMapMode + 1) % 3;
                            if (MapConfig.localCaveMapMode != 2) {
                                CaveMode.clearManualLayer();
                                CaveMapManager.getInstance().deactivate();
                            }
                            MapConfig.save();
                            button.setMessage(getServerCaveModeMessage(true, false));
                            return;
                        }
                        ServerConfig.caveMapMode = (ServerConfig.caveMapMode + 1) % 3;
                        MapConfig.serverCaveMapMode = ServerConfig.caveMapMode;
                        if (ServerConfig.caveMapMode != 2) {
                            CaveMode.clearManualLayer();
                            CaveMapManager.getInstance().deactivate();
                        }
                        ServerConfig.save();
                        syncIntegratedServerConfig();
                        button.setMessage(getServerCaveModeMessage(false, false));
                    }).bounds((this.width - btnWidth) / 2, this.height / 2, btnWidth, btnHeight)
                    .tooltip(Tooltip.create(Component.literal(
                            "Cave visibility; may feel like cheating.\n"
                                    + "OFF: surface only · AUTO: underground\n"
                                    + "ON: auto plus manual layers")))
                    .build();
            caveScanButton.active = !remoteExtension;
            this.addRenderableWidget(caveScanButton);

            this.addRenderableWidget(Button.builder(
                    Component.literal("Done"),
                    button -> {
                        ServerConfig.save();
                        if (this.minecraft != null) {
                            this.minecraft.setScreen(this.parent);
                        }
                    }).bounds((this.width - 88) / 2, this.height / 2 + 38, 88, btnHeight).build());
            return;
        }

        // ==================== RESPONSIVE LEFT PANEL ====================
        panelLeft = Math.max(4, Math.min(10, this.width / 24));
        panelTop = 15;
        panelWidth = Math.min(240, Math.max(180, this.width - panelLeft - 6));
        panelRight = panelLeft + panelWidth;
        panelBottom = Math.max(panelTop + 80, this.height - 10);

        // ==================== TABS (Top of Left Panel) ====================
        int tabY = 20;
        int tabSpacing = 4;
        int startX = panelLeft + 4;
        int tabW = Math.max(38, (panelWidth - 8 - tabSpacing * 3) / 4);

        tabMapButton = Button.builder(
                Component.literal(activeTab == 0 ? "§6§lMap" : "Map"),
                button -> {
                    activeTab = 0;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX, tabY, tabW, 20)
                .tooltip(Tooltip.create(Component.literal("Minimap visibility, size, orientation and markers.")))
                .build();
        this.addRenderableWidget(tabMapButton);

        tabCoordsButton = Button.builder(
                Component.literal(activeTab == 1 ? "§6§lCoords" : "Coords"),
                button -> {
                    activeTab = 1;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX + tabW + tabSpacing, tabY, tabW, 20)
                .tooltip(Tooltip.create(Component.literal("Coordinate overlay and cursor information.")))
                .build();
        this.addRenderableWidget(tabCoordsButton);

        tabColorsButton = Button.builder(
                Component.literal(activeTab == 2 ? "§6§lColors" : "Colors"),
                button -> {
                    activeTab = 2;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX + (tabW + tabSpacing) * 2, tabY, tabW, 20)
                .tooltip(Tooltip.create(Component.literal("HUD colors, saved palette and block overrides.")))
                .build();
        this.addRenderableWidget(tabColorsButton);

        tabSystemButton = Button.builder(
                Component.literal(activeTab == 3 ? "§6§lSystem" : "System"),
                button -> {
                    activeTab = 3;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX + (tabW + tabSpacing) * 3, tabY, tabW, 20)
                .tooltip(Tooltip.create(Component.literal("Scanning, performance, pins and recovery settings.")))
                .build();
        this.addRenderableWidget(tabSystemButton);

        // ==================== CONTROLS GRID ====================
        int col1X = panelLeft + 6;
        int columnGap = 8;
        int colW = Math.max(78, (panelWidth - 12 - columnGap) / 2);
        int col2X = col1X + colW + columnGap;
        int gridWidth = colW * 2 + columnGap;

        // Row 1 (y = 52)
        minimapToggleButton = Button.builder(
                getToggleMessage(),
                button -> {
                    MapConfig.minimapEnabled = !MapConfig.minimapEnabled;
                    button.setMessage(getToggleMessage());
                }).bounds(col1X, 52, colW, 20)
                .tooltip(Tooltip.create(Component.literal("Show or hide the minimap HUD without disabling map recording.")))
                .build();
        this.addRenderableWidget(minimapToggleButton);

        double initialSizeVal = (double) (MapConfig.minimapSize - 16) / (150 - 16);
        minimapSizeSlider = new SimpleSlider(
                col2X, 52, colW, 20,
                initialSizeVal,
                val -> String.format("Size: %dpx", 16 + (int) (val * (150 - 16))),
                val -> MapConfig.minimapSize = 16 + (int) (val * (150 - 16)));
        minimapSizeSlider.setTooltip(Tooltip.create(Component.literal(
                "Changes the minimap's on-screen size. Drag the preview to reposition it.")));
        this.addRenderableWidget(minimapSizeSlider);

        // Row 2 (y = 77)
        minimapShapeButton = Button.builder(
                getShapeMessage(),
                button -> {
                    MapConfig.minimapCircle = !MapConfig.minimapCircle;
                    button.setMessage(getShapeMessage());
                }).bounds(col1X, 77, colW, 20)
                .tooltip(Tooltip.create(Component.literal("Switch between a square and circular minimap frame.")))
                .build();
        this.addRenderableWidget(minimapShapeButton);

        double initialZoomVal = (MapConfig.minimapZoom - 0.05) / (2.0 - 0.05);
        minimapZoomSlider = new SimpleSlider(
                col2X, 77, colW, 20,
                initialZoomVal,
                val -> String.format("Zoom: %.2fx", 0.05 + val * (2.0 - 0.05)),
                val -> MapConfig.minimapZoom = (float) (0.05 + val * (2.0 - 0.05)));
        minimapZoomSlider.setTooltip(Tooltip.create(Component.literal(
                "Controls how much world area fits inside the minimap.")));
        this.addRenderableWidget(minimapZoomSlider);

        // Row 3 (y = 102)
        minimapRotateButton = Button.builder(
                getRotateMessage(),
                button -> {
                    MapConfig.minimapRotate = !MapConfig.minimapRotate;
                    button.setMessage(getRotateMessage());
                }).bounds(col1X, 102, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "ROTATING follows player direction. NORTH UP keeps north fixed at the top.")))
                .build();
        this.addRenderableWidget(minimapRotateButton);

        double initialPointerScaleVal = (MapConfig.playerMarkerScale - 0.1) / (1.0 - 0.1);
        pointerScaleSlider = new SimpleSlider(
                col2X, 102, colW, 20,
                initialPointerScaleVal,
                val -> String.format("Pointer: %.2fx", 0.1 + val * (1.0 - 0.1)),
                val -> MapConfig.playerMarkerScale = (float) (0.1 + val * (1.0 - 0.1)));
        pointerScaleSlider.setTooltip(Tooltip.create(Component.literal(
                "Changes the size of the player marker at the center of the minimap.")));
        this.addRenderableWidget(pointerScaleSlider);

        // Row 4 (y = 127)
        pointerModeButton = Button.builder(
                getPointerModeMessage(),
                button -> {
                    MapConfig.playerMarkerMode = (MapConfig.playerMarkerMode + 1) % 2;
                    button.setMessage(getPointerModeMessage());
                }).bounds(col1X, 127, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Choose either a compact direction arrow or the player head skin.")))
                .build();
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
        pinScaleSlider.setTooltip(Tooltip.create(Component.literal(
                "Changes the size of the temporary navigation pin and its map marker.")));
        this.addRenderableWidget(pinScaleSlider);

        // ==================== SHARED COLOR EDITOR ====================
        colorTargetButton = Button.builder(
                getColorTargetMessage(),
                button -> {
                    selectedColorTarget = (selectedColorTarget + 1) % COLOR_TARGET_NAMES.length;
                    button.setMessage(getColorTargetMessage());
                    syncSharedColorInput();
                }).bounds(col1X, 52, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Select which HUD element receives the edited color.")))
                .build();
        this.addRenderableWidget(colorTargetButton);

        presetColorButton = Button.builder(
                getSelectedPresetMessage(),
                button -> {
                    setSelectedTargetColor(nextPresetColor(getSelectedTargetColor()));
                    syncSharedColorInput();
                }).bounds(col2X, 52, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Cycle through built-in readable colors for the selected target.")))
                .build();
        this.addRenderableWidget(presetColorButton);

        int saveButtonWidth = Math.max(44, Math.min(56, colW / 2));
        int colorInputWidth = Math.max(72, gridWidth - saveButtonWidth - columnGap);
        int saveButtonX = col1X + colorInputWidth + columnGap;
        sharedColorInput = new EditBox(this.font, col1X, 77, colorInputWidth, 20,
                Component.literal("Shared hex color"));
        sharedColorInput.setMaxLength(9);
        sharedColorInput.setValue(ColorCode.format(getSelectedTargetColor()));
        sharedColorInput.setHint(Component.literal("#RRGGBB"));
        sharedColorInput.setResponder(this::previewSharedColor);
        sharedColorInput.setTooltip(Tooltip.create(Component.literal(
                "Enter #RRGGBB or #AARRGGBB. Valid input previews immediately.")));
        this.addRenderableWidget(sharedColorInput);

        savePaletteColorButton = Button.builder(
                Component.literal("Save"),
                button -> saveSharedColor())
                .bounds(saveButtonX, 77, saveButtonWidth, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Save the current color to the reusable palette below.")))
                .build();
        this.addRenderableWidget(savePaletteColorButton);

        paletteButtons.clear();
        int swatchGap = 4;
        int swatchWidth = Math.max(28, (gridWidth - swatchGap * 3) / 4);
        for (int i = 0; i < 8; i++) {
            int swatchX = col1X + (i % 4) * (swatchWidth + swatchGap);
            int swatchY = PALETTE_SWATCH_Y + (i / 4) * 25;
            ColorSwatchButton swatch = new ColorSwatchButton(swatchX, swatchY, swatchWidth, 20, i);
            paletteButtons.add(swatch);
            this.addRenderableWidget(swatch);
        }

        // Row 5, col 2 (y = 152)
        customBlockColorsButton = Button.builder(
                Component.literal("Block Overrides: " + MapConfig.blockColorOverrides.size()),
                b -> {
                    if (this.minecraft != null) this.minecraft.setScreen(new BlockColorManagerScreen(this));
                }).bounds(col1X, 166, gridWidth, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Search, edit, reset or remove per-block map colors.")))
                .build();
        this.addRenderableWidget(customBlockColorsButton);

        compassLettersToggleButton = Button.builder(
                getCompassLettersMessage(),
                button -> {
                    MapConfig.compassLettersVisible = !MapConfig.compassLettersVisible;
                    button.setMessage(getCompassLettersMessage());
                }).bounds(col2X, 152, colW, 20)
                .tooltip(Tooltip.create(Component.literal("Show N, E, S and W around the minimap frame.")))
                .build();
        this.addRenderableWidget(compassLettersToggleButton);

        mapStyleButton = Button.builder(
                getMapStyleMessage(),
                button -> {
                    MapConfig.mapColorProfile = (MapConfig.mapColorProfile + 1) % MapColorProfile.NAMES.length;
                    button.setMessage(getMapStyleMessage());
                    MapTextureManager.getInstance().invalidateStyle();
                    CaveTextureManager.getInstance().invalidateStyle();
                    FullCaveTextureManager.getInstance().invalidateStyle();
                }).bounds(col1X, 152, colW, 20)
                .tooltip(Tooltip.create(Component.literal("Cycle the overall map color profile.")))
                .build();
        this.addRenderableWidget(mapStyleButton);

        showInScreensButton = Button.builder(
                getShowInScreensMessage(),
                button -> {
                    MapConfig.showMinimapInScreens = !MapConfig.showMinimapInScreens;
                    button.setMessage(getShowInScreensMessage());
                }).bounds(col1X, 177, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Keep the minimap visible while inventory, containers, chat or pause screens are open.")))
                .build();
        this.addRenderableWidget(showInScreensButton);

        // ==================== COORDS CONTROLS ====================
        coordsToggleButton = Button.builder(
                getCoordsToggleMessage(),
                button -> {
                    MapConfig.coordsEnabled = !MapConfig.coordsEnabled;
                    button.setMessage(getCoordsToggleMessage());
                }).bounds(col1X, 52, colW, 20)
                .tooltip(Tooltip.create(Component.literal("Show the movable player coordinate overlay.")))
                .build();
        this.addRenderableWidget(coordsToggleButton);

        double initialCoordsScaleVal = (MapConfig.coordsScale - 0.1) / (2.0 - 0.1);
        coordsScaleSlider = new SimpleSlider(
                col2X, 52, colW, 20,
                initialCoordsScaleVal,
                val -> String.format("Coords: %.2fx", 0.1 + val * (2.0 - 0.1)),
                val -> MapConfig.coordsScale = (float) (0.1 + val * (2.0 - 0.1)));
        coordsScaleSlider.setTooltip(Tooltip.create(Component.literal(
                "Changes coordinate text size. Drag the preview text to reposition it.")));
        this.addRenderableWidget(coordsScaleSlider);

        cursorBiomeButton = Button.builder(
                getCursorBiomeMessage(),
                button -> {
                    MapConfig.cursorBiomeEnabled = !MapConfig.cursorBiomeEnabled;
                    MapConfig.save();
                    button.setMessage(getCursorBiomeMessage());
                }).bounds(col1X, 77, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Show the live biome name below cursor coordinates when the chunk is loaded.")))
                .build();
        this.addRenderableWidget(cursorBiomeButton);

        cursorBlockButton = Button.builder(
                getCursorBlockMessage(),
                button -> {
                    MapConfig.cursorBlockEnabled = !MapConfig.cursorBlockEnabled;
                    MapConfig.save();
                    button.setMessage(getCursorBlockMessage());
                }).bounds(col2X, 77, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Show the block under the cursor between the coordinate and biome readouts.")))
                .build();
        this.addRenderableWidget(cursorBlockButton);

        // ==================== SYSTEM CONTROLS ====================
        mapRevealModeButton = Button.builder(
                getMapRevealModeMessage(),
                button -> {
                    MapConfig.mapRevealMode = 1;
                    ChunkScanner.getInstance().requestRefresh(this.minecraft);
                    MapConfig.save();
                    button.setMessage(getMapRevealModeMessage());
                }).bounds(col1X, 52, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Chunk-stream mapping completes loaded chunks by priority.\n"
                                + "The legacy random DOTS pipeline has been removed.")))
                .build();
        this.addRenderableWidget(mapRevealModeButton);

        autoClearPinButton = Button.builder(
                getAutoClearPinMessage(),
                button -> {
                    MapConfig.autoClearPin = !MapConfig.autoClearPin;
                    MapConfig.save();
                    button.setMessage(getAutoClearPinMessage());
                }).bounds(col1X, 102, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Automatically remove the temporary navigation pin within 5 blocks of it.")))
                .build();
        this.addRenderableWidget(autoClearPinButton);

        int initialChunks = pointsToChunkBudget(MapConfig.scanPointsPerTick);
        double initialScanVal = (double) (initialChunks - SCAN_CHUNK_MIN)
                / (SCAN_CHUNK_MAX - SCAN_CHUNK_MIN);
        scanSlider = new SimpleSlider(
                col2X, 52, colW, 20,
                initialScanVal,
                val -> formatScanBudget(sliderToChunkBudget(val)),
                val -> {
                    int chunks = sliderToChunkBudget(val);
                    MapConfig.scanPointsPerTick = chunkBudgetToPoints(chunks);
                    if (scanInputBox != null) {
                        scanInputBox.setValue(chunks >= SCAN_CHUNK_MAX
                                ? "AUTO" : String.valueOf(chunks));
                    }
                });
        scanSlider.setTooltip(Tooltip.create(Component.literal(
                "Loaded chunk-column scan budget per client tick.\n"
                        + "Higher values fill surface and cave layers faster but use more CPU.\n"
                        + "AUTO uses the frame-time deadline and scans as fast as the client can safely sustain.")));
        this.addRenderableWidget(scanSlider);

        alwaysRescanButton = Button.builder(
                getAlwaysRescanMessage(),
                button -> {
                    MapConfig.alwaysRescanExplored = !MapConfig.alwaysRescanExplored;
                    MapConfig.save();
                    button.setMessage(getAlwaysRescanMessage());
                }).bounds(col1X, 77, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Continuously rescan loaded client chunks.\n"
                        + "OFF (Default): Scans explored blocks once for max performance.\n"
                        + "ON: Keeps re-scanning live loaded chunks to pick up world changes.")))
                .build();
        this.addRenderableWidget(alwaysRescanButton);

        scanInputBox = new EditBox(this.font,
                col2X, 77, colW - 1, 20,
                Component.literal("Scan points"));
        scanInputBox.setMaxLength(6);
        scanInputBox.setValue(initialChunks >= SCAN_CHUNK_MAX
                ? "AUTO" : String.valueOf(initialChunks));
        scanInputBox.setHint(Component.literal("4-389/AUTO"));
        scanInputBox.setTooltip(Tooltip.create(Component.literal(
                "Approximate 16x16 chunks processed per tick.\n"
                        + "AUTO/MAX is recommended for modern systems.")));
        scanInputBox.setResponder(text -> {
            String normalized = text.trim();
            if (normalized.equalsIgnoreCase("AUTO") || normalized.equalsIgnoreCase("MAX")) {
                MapConfig.scanPointsPerTick = 100000;
                if (scanSlider != null) scanSlider.setValue(1.0);
                scanInputBox.setTextColor(0xFFFFFF);
                return;
            }
            try {
                int chunks = Integer.parseInt(normalized);
                if (chunks >= SCAN_CHUNK_MIN && chunks < SCAN_CHUNK_MAX) {
                    MapConfig.scanPointsPerTick = chunkBudgetToPoints(chunks);
                    if (scanSlider != null) {
                        scanSlider.setValue((double) (chunks - SCAN_CHUNK_MIN)
                                / (SCAN_CHUNK_MAX - SCAN_CHUNK_MIN));
                    }
                    scanInputBox.setTextColor(0xFFFFFF);
                } else {
                    scanInputBox.setTextColor(0xFF5555);
                }
            } catch (NumberFormatException ignored) {
                scanInputBox.setTextColor(0xFF5555);
            }
        });
        this.addRenderableWidget(scanInputBox);

        fastFullscreenButton = Button.builder(
                getFastFullscreenMessage(),
                b -> {
                    MapConfig.fastFullscreenLoading = !MapConfig.fastFullscreenLoading;
                    b.setMessage(getFastFullscreenMessage());
                    MapConfig.save();
                }).bounds(col2X, 152, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Temporarily gives the open full map a larger bounded scan/upload budget.")))
                .build();
        this.addRenderableWidget(fastFullscreenButton);

        deathWaypointButton = Button.builder(
                getDeathWaypointMessage(),
                b -> {
                    MapConfig.createDeathWaypoint = !MapConfig.createDeathWaypoint;
                    b.setMessage(getDeathWaypointMessage());
                    if (deathWaypointLimitButton != null) deathWaypointLimitButton.active = MapConfig.createDeathWaypoint;
                    MapConfig.save();
                }).bounds(col1X, 177, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Create a local waypoint at the player's position on death.")))
                .build();
        this.addRenderableWidget(deathWaypointButton);

        deathWaypointLimitButton = Button.builder(
                getDeathWaypointLimitMessage(),
                b -> {
                    MapConfig.maxDeathWaypoints = switch (MapConfig.maxDeathWaypoints) {
                        case 0 -> 1;
                        case 1 -> 3;
                        case 3 -> 5;
                        case 5 -> 10;
                        default -> 0;
                    };
                    b.setMessage(getDeathWaypointLimitMessage());
                    MapConfig.save();
                }).bounds(col2X, 177, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Maximum retained death waypoints per dimension.")))
                .build();
        deathWaypointLimitButton.active = MapConfig.createDeathWaypoint;
        this.addRenderableWidget(deathWaypointLimitButton);

        resetToDefaultButton = Button.builder(
                Component.literal("Restore Defaults..."),
                button -> openResetConfirmation())
                .bounds(col1X, 202, gridWidth, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Restore HUD, color, performance and waypoint preferences."
                                + "\nA confirmation screen appears first."
                                + "\nWaypoints, explored map data and block overrides are kept.")))
                .build();
        this.addRenderableWidget(resetToDefaultButton);

        reloadAllRegionsButton = Button.builder(
                Component.literal("Reload All Regions"),
                button -> {
                    MapManager.getInstance().reloadAllRegions();
                    button.setMessage(Component.literal("Reloading..."));
                }).bounds(col2X, 102, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Re-color and reload all saved map regions in the background.\n"
                        + "Runs in the background and updates textures automatically when finished.")))
                .build();
        this.addRenderableWidget(reloadAllRegionsButton);

        displayFlowersButton = Button.builder(
                getDisplayFlowersMessage(),
                button -> {
                    MapConfig.displayFlowers = !MapConfig.displayFlowers;
                    button.setMessage(getDisplayFlowersMessage());
                    refreshLoadedMapData();
                }).bounds(col1X, 127, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Draw small flower blocks on the map. Disabling can reduce visual noise.")))
                .build();
        this.addRenderableWidget(displayFlowersButton);

        blockColourModeButton = Button.builder(
                getBlockColourModeMessage(),
                button -> {
                    MapConfig.blockColourMode = (MapConfig.blockColourMode + 1) % 2;
                    button.setMessage(getBlockColourModeMessage());
                    refreshLoadedMapData();
                }).bounds(col2X, 127, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "ACCURATE uses registered block and biome colours.\n"
                                + "VANILLA uses Minecraft map colours.")))
                .build();
        this.addRenderableWidget(blockColourModeButton);

        terrainSlopesButton = Button.builder(
                getTerrainSlopesMessage(),
                button -> {
                    MapConfig.terrainSlopes = (MapConfig.terrainSlopes + 1) % 3;
                    button.setMessage(getTerrainSlopesMessage());
                    refreshLoadedMapData();
                }).bounds(col1X, 152, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "OFF is flat · 2D uses gentle relief.\n"
                                + "3D uses stronger multi-direction slope shading.")))
                .build();
        this.addRenderableWidget(terrainSlopesButton);

        // ==================== DONE BUTTON ====================
        doneButton = Button.builder(
                Component.literal("Done"),
                button -> {
                    MapConfig.save();
                    if (this.minecraft != null)
                        this.minecraft.setScreen(this.parent);
                }).bounds(col1X, this.height - 30, gridWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Save settings and return to the full map.")))
                .build();
        this.addRenderableWidget(doneButton);

        // Sync visibility at startup
        updateWidgetVisibility();
    }

    private void openResetConfirmation() {
        if (this.minecraft == null) return;
        this.minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                applyDefaultPreferences();
            }
            if (this.minecraft != null) {
                this.minecraft.setScreen(this);
            }
        }, Component.literal("Restore Simple Map Defaults?"),
                Component.literal("This restores HUD, colors, scanning and waypoint preferences. "
                        + "Saved palette colors, block overrides, waypoints and explored map data are preserved."),
                Component.literal("Restore"), Component.literal("Cancel")));
    }

    private void applyDefaultPreferences() {
        MapConfig.resetPreferencesToDefaults();
        selectedMinimap = false;
        selectedCoords = false;
        isDraggingMinimap = false;
        isDraggingCoords = false;
        CaveMode.clearManualLayer();
        CaveMapManager.getInstance().deactivate();
        ChunkScanner.getInstance().reset();
        MapTextureManager.getInstance().invalidateStyle();
        CaveTextureManager.getInstance().invalidateStyle();
        FullCaveTextureManager.getInstance().invalidateStyle();
    }

    private void updateTabButtons() {
        if (tabMapButton != null)
            tabMapButton.setMessage(Component.literal(activeTab == 0 ? "§6§lMap" : "Map"));
        if (tabCoordsButton != null)
            tabCoordsButton.setMessage(Component.literal(activeTab == 1 ? "§6§lCoords" : "Coords"));
        if (tabColorsButton != null)
            tabColorsButton.setMessage(Component.literal(activeTab == 2 ? "§6§lColors" : "Colors"));
        if (tabSystemButton != null)
            tabSystemButton.setMessage(Component.literal(activeTab == 3 ? "§6§lSystem" : "System"));
    }

    private void updateWidgetVisibility() {
        boolean hideAll = selectedMinimap || selectedCoords;
        boolean showMap = activeTab == 0 && !hideAll;
        boolean showCoords = activeTab == 1 && !hideAll;
        boolean showColors = activeTab == 2 && !hideAll;
        boolean showSystem = activeTab == 3 && !hideAll;

        if (tabMapButton != null) tabMapButton.visible = !hideAll;
        if (tabCoordsButton != null) tabCoordsButton.visible = !hideAll;
        if (tabColorsButton != null) tabColorsButton.visible = !hideAll;
        if (tabSystemButton != null) tabSystemButton.visible = !hideAll;
        if (doneButton != null) doneButton.visible = !hideAll;

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
        if (colorTargetButton != null)
            colorTargetButton.visible = showColors;
        if (presetColorButton != null)
            presetColorButton.visible = showColors;
        if (sharedColorInput != null)
            sharedColorInput.visible = showColors;
        if (savePaletteColorButton != null)
            savePaletteColorButton.visible = showColors;
        if (customBlockColorsButton != null) {
            customBlockColorsButton.visible = showColors;
            customBlockColorsButton.setMessage(Component.literal(
                    "Block Overrides: " + MapConfig.blockColorOverrides.size()));
        }
        refreshPaletteButtons();
        if (compassLettersToggleButton != null)
            compassLettersToggleButton.visible = showMap;
        if (showInScreensButton != null)
            showInScreensButton.visible = showMap;
        if (mapStyleButton != null)
            mapStyleButton.visible = showMap;

        if (coordsToggleButton != null)
            coordsToggleButton.visible = showCoords;
        if (coordsScaleSlider != null)
            coordsScaleSlider.visible = showCoords;
        if (cursorBiomeButton != null)
            cursorBiomeButton.visible = showCoords;
        if (cursorBlockButton != null)
            cursorBlockButton.visible = showCoords;

        if (autoClearPinButton != null)
            autoClearPinButton.visible = showSystem;
        if (alwaysRescanButton != null)
            alwaysRescanButton.visible = showSystem;
        if (reloadAllRegionsButton != null)
            reloadAllRegionsButton.visible = showSystem;
        if (mapRevealModeButton != null)
            mapRevealModeButton.visible = showSystem;
        if (resetToDefaultButton != null)
            resetToDefaultButton.visible = showSystem;
        if (scanSlider != null)
            scanSlider.visible = showSystem;
        if (scanInputBox != null)
            scanInputBox.visible = showSystem;
        if (displayFlowersButton != null)
            displayFlowersButton.visible = showSystem;
        if (blockColourModeButton != null)
            blockColourModeButton.visible = showSystem;
        if (terrainSlopesButton != null)
            terrainSlopesButton.visible = showSystem;
        if (fastFullscreenButton != null)
            fastFullscreenButton.visible = showSystem;
        if (deathWaypointButton != null)
            deathWaypointButton.visible = showSystem;
        if (deathWaypointLimitButton != null) {
            deathWaypointLimitButton.visible = showSystem;
            deathWaypointLimitButton.active = showSystem && MapConfig.createDeathWaypoint;
        }
    }

    private Component getToggleMessage() {
        return Component.literal("Minimap: " + (MapConfig.minimapEnabled ? "ENABLED" : "DISABLED"));
    }

    private Component getRotateMessage() {
        return Component.literal("North Lock: " + (MapConfig.minimapRotate ? "OFF" : "ON"));
    }

    private Component getShapeMessage() {
        return Component.literal("Shape: " + (MapConfig.minimapCircle ? "CIRCLE" : "SQUARE"));
    }

    private Component getCoordsToggleMessage() {
        return Component.literal("Coordinates: " + (MapConfig.coordsEnabled ? "ON" : "OFF"));
    }

    private Component getDeathWaypointMessage() {
        return Component.literal("Death Points: " + (MapConfig.createDeathWaypoint ? "ON" : "OFF"));
    }

    private Component getDeathWaypointLimitMessage() {
        return Component.literal("Death Limit: " + MapConfig.maxDeathWaypoints);
    }

    private Component getAutoClearPinMessage() {
        return Component.literal("Auto-Clear Pin: " + (MapConfig.autoClearPin ? "ON" : "OFF"));
    }

    private Component getPointerModeMessage() {
        return Component.literal("Marker: " + (MapConfig.playerMarkerMode == 1 ? "ARROW" : "SKIN"));
    }

    private Component getCompassLettersMessage() {
        return Component.literal("Compass Labels: " + (MapConfig.compassLettersVisible ? "ON" : "OFF"));
    }

    private Component getShowInScreensMessage() {
        return Component.literal("Show in Screens: " + (MapConfig.showMinimapInScreens ? "ON" : "OFF"));
    }

    private Component getMapStyleMessage() {
        int index = Math.max(0, Math.min(MapColorProfile.NAMES.length - 1, MapConfig.mapColorProfile));
        return Component.literal("Style: " + MapColorProfile.NAMES[index]);
    }

    private Component getColorTargetMessage() {
        return Component.literal("Edit Color: " + COLOR_TARGET_NAMES[selectedColorTarget]);
    }

    private Component getSelectedPresetMessage() {
        return Component.literal("Color: " + getPresetColorName(getSelectedTargetColor()));
    }

    private int getSelectedTargetColor() {
        return switch (selectedColorTarget) {
            case 1 -> MapConfig.minimapRingColor;
            case 2 -> MapConfig.compassLetterColor;
            case 3 -> MapConfig.coordsTextColor;
            default -> MapConfig.playerPointerColor;
        };
    }

    private void setSelectedTargetColor(int color) {
        switch (selectedColorTarget) {
            case 1 -> MapConfig.minimapRingColor = color;
            case 2 -> MapConfig.compassLetterColor = color;
            case 3 -> MapConfig.coordsTextColor = color;
            default -> MapConfig.playerPointerColor = color;
        }
        if (presetColorButton != null) presetColorButton.setMessage(getSelectedPresetMessage());
    }

    private void syncSharedColorInput() {
        if (sharedColorInput != null) {
            sharedColorInput.setValue(ColorCode.format(getSelectedTargetColor()));
            sharedColorInput.setTextColor(0xFFFFFFFF);
        }
        if (presetColorButton != null) presetColorButton.setMessage(getSelectedPresetMessage());
    }

    private void previewSharedColor(String value) {
        try {
            setSelectedTargetColor(ColorCode.parse(value));
            sharedColorInput.setTextColor(0xFFFFFFFF);
        } catch (IllegalArgumentException ignored) {
            sharedColorInput.setTextColor(0xFFFF5555);
        }
    }

    private void saveSharedColor() {
        try {
            int color = ColorCode.parse(sharedColorInput.getValue());
            setSelectedTargetColor(color);
            MapConfig.savedColors.remove(Integer.valueOf(color));
            MapConfig.savedColors.add(0, color);
            while (MapConfig.savedColors.size() > 8) {
                MapConfig.savedColors.remove(MapConfig.savedColors.size() - 1);
            }
            MapConfig.save();
            refreshPaletteButtons();
        } catch (IllegalArgumentException ignored) {
            sharedColorInput.setTextColor(0xFFFF5555);
        }
    }

    private void applySavedColor(int slot) {
        if (slot < 0 || slot >= MapConfig.savedColors.size()) return;
        setSelectedTargetColor(MapConfig.savedColors.get(slot));
        syncSharedColorInput();
    }

    private void refreshPaletteButtons() {
        for (int i = 0; i < paletteButtons.size(); i++) {
            ColorSwatchButton swatch = paletteButtons.get(i);
            boolean hasColor = i < MapConfig.savedColors.size();
            if (hasColor) swatch.setColor(MapConfig.savedColors.get(i));
            swatch.visible = activeTab == 2 && hasColor;
        }
    }

    private int nextPresetColor(int currentColor) {
        for (int i = 0; i < MapConfig.POINTER_COLORS.length; i++) {
            if (currentColor == MapConfig.POINTER_COLORS[i]) {
                return MapConfig.POINTER_COLORS[(i + 1) % MapConfig.POINTER_COLORS.length];
            }
        }
        return MapConfig.POINTER_COLORS[0];
    }

    private String getPresetColorName(int color) {
        for (int i = 0; i < MapConfig.POINTER_COLORS.length; i++) {
            if (color == MapConfig.POINTER_COLORS[i]) return MapConfig.POINTER_COLOR_NAMES[i];
        }
        return "Custom";
    }

    private Component getFastFullscreenMessage() {
        return Component.literal("Loading: "
                + (MapConfig.fastFullscreenLoading ? "FAST" : "BALANCED"));
    }

    private Component getAlwaysRescanMessage() {
        return Component.literal("Rescan Loaded: " + (MapConfig.alwaysRescanExplored ? "ON" : "OFF"));
    }

    private static int pointsToChunkBudget(int points) {
        if (points >= 100000) return SCAN_CHUNK_MAX;
        return Math.max(SCAN_CHUNK_MIN,
                Math.min(SCAN_CHUNK_MAX - 1, (points + 255) / 256));
    }

    private static int chunkBudgetToPoints(int chunks) {
        if (chunks >= SCAN_CHUNK_MAX) return 100000;
        return Math.max(1000, Math.min(99999, chunks * 256));
    }

    private static int sliderToChunkBudget(double value) {
        return SCAN_CHUNK_MIN + (int) Math.round(value
                * (SCAN_CHUNK_MAX - SCAN_CHUNK_MIN));
    }

    private static String formatScanBudget(int chunks) {
        return chunks >= SCAN_CHUNK_MAX ? "Scan: AUTO" : "Scan: " + chunks + " chunks/t";
    }

    private Component getMapRevealModeMessage() {
        return Component.literal("Mapping: STREAM");
    }

    private Component getDisplayFlowersMessage() {
        return Component.literal("Map Flowers: " + (MapConfig.displayFlowers ? "ON" : "OFF"));
    }

    private Component getBlockColourModeMessage() {
        return Component.literal("Block Colors: " + (MapConfig.blockColourMode == 1 ? "VANILLA" : "ACCURATE"));
    }

    private Component getTerrainSlopesMessage() {
        String mode = switch (MapConfig.terrainSlopes) {
            case 0 -> "OFF";
            case 1 -> "2D";
            default -> "3D";
        };
        return Component.literal("Terrain Shading: " + mode);
    }

    private Component getCursorBiomeMessage() {
        return Component.literal("Cursor Biome: " + (MapConfig.cursorBiomeEnabled ? "ON" : "OFF"));
    }

    private Component getCursorBlockMessage() {
        return Component.literal("Cursor Block: " + (MapConfig.cursorBlockEnabled ? "ON" : "OFF"));
    }

    private void refreshLoadedMapData() {
        MapConfig.save();
        ChunkScanner.getInstance().reset();
        if (this.minecraft == null || this.minecraft.player == null) return;
        ChunkScanner.getInstance().requestRefresh(this.minecraft);
        MapTextureManager.getInstance().invalidateStyle();
        CaveTextureManager.getInstance().invalidateStyle();
        FullCaveTextureManager.getInstance().invalidateStyle();
    }

    private boolean isRemoteServerSession() {
        return this.minecraft != null && this.minecraft.getConnection() != null && !this.minecraft.isLocalServer();
    }

    private void syncIntegratedServerConfig() {
        if (this.minecraft == null || this.minecraft.getSingleplayerServer() == null) return;
        net.minecraft.server.MinecraftServer server = this.minecraft.getSingleplayerServer();
        SyncConfigPayload payload = new SyncConfigPayload(ServerConfig.requireMapBook, ServerConfig.caveMapMode);
        server.execute(() -> {
            for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
                NetworkHandler.sendToPlayer(player, payload);
            }
        });
    }

    private Component getServerRequireBookMessage(boolean remoteServer, boolean remoteExtension) {
        if (remoteServer && !remoteExtension) {
            return Component.literal("Book: SERVER MOD REQUIRED");
        }
        boolean enabled = remoteServer ? MapConfig.serverRequireMapBook : ServerConfig.requireMapBook;
        return Component.literal("Book: " + (enabled ? "REQUIRED" : "OPTIONAL")
                + (remoteServer ? " · SERVER" : ""));
    }

    private Component getServerCaveModeMessage(boolean remoteServer, boolean remoteExtension) {
        int mode = remoteServer
                ? (remoteExtension ? MapConfig.serverCaveMapMode : MapConfig.localCaveMapMode)
                : ServerConfig.caveMapMode;
        String name = switch (mode) {
            case 1 -> "AUTO";
            case 2 -> "ON";
            default -> "OFF";
        };
        String authority = remoteServer ? (remoteExtension ? " · SERVER" : " · LOCAL") : "";
        return Component.literal("Cave Scan: " + name + authority);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // The in-map editor suppresses Minecraft's default blur. The global Mod
        // Config branch invokes the superclass explicitly to get the normal blurred
        // game backdrop.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (!(parent instanceof MapScreen)) {
            // Global Mod Config uses Minecraft's blurred in-game background and a
            // compact translucent card. The map's own settings screen deliberately
            // keeps its existing custom preview background below.
            super.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
            int panelWidth = 216;
            int panelHeight = 140;
            int panelLeft = (this.width - panelWidth) / 2;
            int panelTop = (this.height - panelHeight) / 2 - 10;
            guiGraphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xA818191C);
            guiGraphics.renderOutline(panelLeft, panelTop, panelWidth, panelHeight, 0xB8666A70);
            guiGraphics.drawCenteredString(this.font, "Simple Map · Server", this.width / 2, panelTop + 16,
                    0xFFF2F2F2);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            return;
        }

        if (!selectedMinimap && !selectedCoords) {
            // Semi-transparent dark overlay — game view shows through behind
            guiGraphics.fill(0, 0, this.width, this.height, 0xAA000000);

            // Draw the same responsive card geometry used by init().
            panelBottom = Math.max(panelTop + 80, this.height - 10);
            guiGraphics.fill(panelLeft, panelTop, panelRight, panelBottom, 0xDD111215);
            guiGraphics.renderOutline(panelLeft - 1, panelTop - 1,
                    panelWidth + 2, panelBottom - panelTop + 2, 0xFF2D3033);
            guiGraphics.fill(panelLeft, 58, panelRight, 59, 0xFF2D3033);
        } else {
            guiGraphics.fill(0, 0, this.width, this.height, 0x30000000);
        }

        int size = MapConfig.minimapSize;
        int[] minimapPosition = MinimapPosition.resolve(this.width, this.height, size);
        int x = minimapPosition[0];
        int y = minimapPosition[1];

        boolean isHovered = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;

        // 1. Render Minimap
        if (MapConfig.minimapEnabled) {
            int borderColor = isHovered || isDraggingMinimap || selectedMinimap
                    ? 0xFF00C8FF : MapConfig.minimapRingColor;
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
                int circleBorderColor = isHovered || isDraggingMinimap || selectedMinimap
                        ? 0xFF00C8FF : MapConfig.minimapRingColor;

                // Ensure stencil buffer is enabled on the main framebuffer
                if (this.minecraft != null) {
                    this.minecraft.getMainRenderTarget().enableStencil();
                }
                boolean depthTestWasEnabled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);

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
                float maskRadius = clipRadius + 1.5f; // Overlap slightly under the border
                for (int dy = -(int) Math.ceil(maskRadius); dy <= (int) Math.ceil(maskRadius); dy++) {
                    float squared = maskRadius * maskRadius - dy * dy;
                    if (squared < 0.0f) continue;
                    float dx = (float) Math.sqrt(squared);
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
                org.lwjgl.opengl.GL11.glStencilMask(0xFF);
                if (depthTestWasEnabled) {
                    org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                }

                // 5. Draw the bezel overlay (borders) always on top using vector rings
                drawCircleRing(guiGraphics, cx, cy, clipRadius, radius, 64, MapConfig.minimapRingColor);
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

        if (activeTab == 2) {
            String paletteLabel = MapConfig.savedColors.isEmpty()
                    ? "Save a color to reuse it"
                    : "Saved colors (Shift-click removes)";
            guiGraphics.drawString(this.font, paletteLabel, panelLeft + 6,
                    PALETTE_LABEL_Y, 0xFFB0B0B0, false);
        }

        // Draw widgets (sliders, buttons, editbox)
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Simple hint above Done button
        int panelCenterX = doneButton.getX() + doneButton.getWidth() / 2;
        int hintY = doneButton.getY() - 18;

        String hint = activeTab == 2 ? "Choose target, then apply a saved color"
                : ((selectedMinimap || selectedCoords)
                        ? "Drag to move, click outside to save"
                        : (activeTab <= 1 ? "Click & Drag" : ""));
        if (!hint.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, hint, panelCenterX, hintY, 0x808080);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!(parent instanceof MapScreen)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            int size = MapConfig.minimapSize;
            int[] minimapPosition = MinimapPosition.resolve(this.width, this.height, size);
            int x = minimapPosition[0];
            int y = minimapPosition[1];

            String coords = MapConfig.coordsEnabled ? "0, 80, 0" : "Coords Disabled";
            if (MapConfig.coordsEnabled && this.minecraft != null && this.minecraft.player != null) {
                coords = String.format("%d, %d, %d",
                        (int) Math.floor(this.minecraft.player.getX()),
                        (int) Math.floor(this.minecraft.player.getY()),
                        (int) Math.floor(this.minecraft.player.getZ()));
            }
            int textWidth = (int) (this.font.width(coords) * MapConfig.coordsScale);
            int textHeight = (int) (9 * MapConfig.coordsScale);
            int cx;
            int cy;
            if (MapConfig.coordsXPercent < 0 || MapConfig.coordsYPercent < 0) {
                cx = x + (size - textWidth) / 2;
                cy = y + size + 4;
            } else {
                cx = (int) (this.width * MapConfig.coordsXPercent);
                cy = (int) (this.height * MapConfig.coordsYPercent);
            }
            cx = Math.max(2, Math.min(cx, this.width - textWidth - 2));
            cy = Math.max(2, Math.min(cy, this.height - textHeight - 2));

            boolean insideMinimap = mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
            boolean insideCoords = mouseX >= cx - 3 && mouseX < cx + textWidth + 3
                    && mouseY >= cy - 2 && mouseY < cy + textHeight + 2;

            if ((selectedMinimap && !insideMinimap) || (selectedCoords && !insideCoords)) {
                selectedMinimap = false;
                selectedCoords = false;
                isDraggingMinimap = false;
                isDraggingCoords = false;
                MapConfig.save();
                updateWidgetVisibility();
                return true;
            }

            // 1. Check Minimap click
            if (insideMinimap) {
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
            if (insideCoords) {
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
            int requestedX = (int) Math.round(mouseX - dragOffsetX);
            int requestedY = (int) Math.round(mouseY - dragOffsetY);
            MinimapPosition.setFromTopLeft(requestedX, requestedY, this.width, this.height, MapConfig.minimapSize);
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
        MapConfig.save();
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

    private class ColorSwatchButton extends AbstractButton {
        private final int slot;
        private int color;

        ColorSwatchButton(int x, int y, int width, int height, int slot) {
            super(x, y, width, height, Component.literal("Saved color"));
            this.slot = slot;
        }

        void setColor(int color) {
            this.color = color;
            String formatted = ColorCode.format(color);
            setMessage(Component.literal("Use " + formatted));
            setTooltip(Tooltip.create(Component.literal(
                    formatted + "\nClick: apply to selected target\nShift-click: remove from saved colors")));
        }

        @Override
        public void onPress() {
            if (Screen.hasShiftDown() && slot < MapConfig.savedColors.size()) {
                MapConfig.savedColors.remove(slot);
                MapConfig.save();
                refreshPaletteButtons();
            } else {
                applySavedColor(slot);
            }
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int border = isHoveredOrFocused() || color == getSelectedTargetColor() ? 0xFFFFFFFF : 0xFF55585D;
            guiGraphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF161719);
            guiGraphics.fill(getX() + 3, getY() + 3, getX() + getWidth() - 3, getY() + getHeight() - 3,
                    0xFF000000 | (color & 0x00FFFFFF));
            guiGraphics.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
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
