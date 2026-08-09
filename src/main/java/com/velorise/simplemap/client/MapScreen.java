package com.velorise.simplemap.client;

import com.velorise.simplemap.SimpleMap;
import com.velorise.simplemap.client.cave.CaveStateClassifier;
import com.velorise.simplemap.client.cave.CaveDimensionProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MapScreen extends Screen {
    private static final int TOOLBAR_BUTTON_SIZE = 22;
    private static final int TOOLBAR_MARGIN = 3;
    private static final int TOOLBAR_GAP = 2;
    private static final float TOOLBAR_ICON_SCALE = 1.0f;
    private static final int PLAYER_PANEL_MARGIN = 4;
    private static final int PLAYER_PANEL_HEIGHT = 15;
    private static final int ZOOM_PANEL_MARGIN = 4;
    private static final int ZOOM_PANEL_HEIGHT = 15;
    private static final float DEFAULT_MAP_SCALE = 0.5f;
    private static final long OPEN_ANIMATION_NANOS = 1_000_000_000L;
    /** 5x default view opens at 6x and settles back to 5x. */
    private static final float OPEN_ANIMATION_START_MULTIPLIER = 1.2f;
    private static final long CURSOR_CACHE_NANOS = 120_000_000L;
    private static final long VIEWPORT_INTERACTION_SETTLE_NANOS = 750_000_000L;
    private static final double MOMENTUM_FRICTION = 7.5;
    private static final double MAX_MOMENTUM_BLOCKS_PER_SECOND = 12_000.0;
    private double centerX;
    private double centerZ;
    private float scale = DEFAULT_MAP_SCALE;
    private float currentRenderScale = DEFAULT_MAP_SCALE;
    private boolean isDragging = false;
    private long openAnimationStartNanos = System.nanoTime();
    private long lastFrameNanos;
    private long lastDragSampleNanos;
    private long lastViewportInteractionNanos;
    private double momentumX;
    private double momentumZ;
    private int cachedCursorX = Integer.MIN_VALUE;
    private int cachedCursorZ = Integer.MIN_VALUE;
    private long cachedCursorRevision = Long.MIN_VALUE;
    private long cachedCursorAtNanos;
    private BlockInfo cachedCursorInfo;
    private boolean cursorCacheValid;
    private boolean temporaryVsyncDisabled;
    private final StringBuilder overlayTextBuilder = new StringBuilder(64);
    private int cachedPlayerTextX = Integer.MIN_VALUE;
    private int cachedPlayerTextY = Integer.MIN_VALUE;
    private int cachedPlayerTextZ = Integer.MIN_VALUE;
    private String cachedPlayerText = "";
    private int cachedZoomTextKey = Integer.MIN_VALUE;
    private String cachedZoomText = "";
    private int cachedCoordTextX = Integer.MIN_VALUE;
    private int cachedCoordTextY = Integer.MIN_VALUE;
    private int cachedCoordTextZ = Integer.MIN_VALUE;
    private int cachedCoordWaterY = Integer.MIN_VALUE;
    private boolean cachedCoordHasY;
    private String cachedCoordText = "";
    private int cachedBiomeX = Integer.MIN_VALUE;
    private int cachedBiomeY = Integer.MIN_VALUE;
    private int cachedBiomeZ = Integer.MIN_VALUE;
    private String cachedBiomeText;
    private final CaveStateClassifier caveStateClassifier = CaveStateClassifier.getInstance();
    private final MapVisualClassifier visualClassifier = MapVisualClassifier.getInstance();

    // Popup context menu for waypoints and teleportation
    private boolean isPopupMenuOpen = false;
    private double popupX = 0;
    private double popupY = 0;
    private double popupWorldX = 0;
    private double popupWorldZ = 0;
    private WaypointManager.Waypoint clickedWaypoint = null;

    // Track drag vs click
    private double dragStartX = 0;
    private double dragStartZ = 0;

    public double getCenterX() { return this.centerX; }
    public double getCenterZ() { return this.centerZ; }
    public float getScale() { return this.scale; }

    public MapScreen() {
        super(Component.literal("World Map"));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            this.centerX = mc.player.getX();
            this.centerZ = mc.player.getZ();
        }
    }

    /** Opens the requested waypoint's dimension and places it at map centre. */
    public void focusOnWaypoint(WaypointManager.Waypoint waypoint) {
        if (waypoint == null || this.minecraft == null) return;
        MapManager manager = MapManager.getInstance();
        String target = manager.resolveDimensionResourceId(waypoint.dimension);
        if (target.equals(manager.getLiveDimensionResourceId())) {
            selectedDimension = "LIVE";
            manager.returnToLiveDimension(this.minecraft);
        } else {
            selectedDimension = target;
            manager.switchToDimension(target);
        }
        centerX = waypoint.x;
        centerZ = waypoint.z;
        scale = Math.max(getMinimumStableScale(), 0.75f);
        currentRenderScale = scale;
        cancelMotionAndOpenAnimation();
        cursorCacheValid = false;
        isPopupMenuOpen = false;
        updateToolbarTooltips();
    }

    private Button waypointsToggleButton;
    private Button waypointListButton;
    private Button refreshMapButton;
    private Button minimapConfigButton;
    private Button nightModeButton;
    private Button caveLayerModeButton;
    private Button dimensionSwitchButton;
    private CaveLayerSlider caveLayerSlider;
    private String selectedDimension = "LIVE";
    private final Map<String, double[]> dimensionCameras = new HashMap<>();
    private int toolbarStartX;
    private int toolbarStartY;
    private int toolbarStepY;
    private int toolbarWidth;
    private int toolbarRows;

    @Override
    protected void init() {
        markViewportInteraction();
        FullscreenMapFramebufferRenderer.getInstance().resetFailureState();
        if (this.minecraft != null) {
            /*
             * The fullscreen map has its own configured FPS budget and does not
             * render the 3D level behind this opaque screen.  On a 120/130 Hz
             * display, GLFW VSync otherwise drops the whole screen to the next
             * divisor (~60 FPS) whenever a single frame misses the refresh slot.
             * Bypass VSync only for this screen, without changing/saving the
             * player's option, and restore it from removed().
             */
            if (!temporaryVsyncDisabled
                    && this.minecraft.options.enableVsync().get()
                    && this.minecraft.options.framerateLimit().get() > 60) {
                this.minecraft.getWindow().updateVsync(false);
                temporaryVsyncDisabled = true;
            }
            MapManager.getInstance().updateWorldAndDimension(this.minecraft);
        }
        if (this.width > 0) {
            this.scale = Math.max(getMinimumStableScale(), (this.width - 2) / 1000.0f);
            this.currentRenderScale = this.scale;
        }

        // Pos 1: Settings
        minimapConfigButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.SETTINGS, button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new MapConfigScreen(this));
            }
        });
        this.addRenderableWidget(minimapConfigButton);

        // Pos 2: Waypoint Toggle Visibility
        waypointsToggleButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.WAYPOINT_OUTLINE,
                button -> {
                    MapConfig.waypointsVisible = !MapConfig.waypointsVisible;
                    MapConfig.save();
                    updateToolbarTooltips();
                });
        this.addRenderableWidget(waypointsToggleButton);

        // Pos 3: Waypoint List Manager (Flag directly below Waypoint Toggle)
        waypointListButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.WAYPOINT_LIST, button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new WaypointListScreen(this));
            }
        });
        this.addRenderableWidget(waypointListButton);

        // Pos 4: Night Mode
        nightModeButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.SUN, button -> {
            MapConfig.minimapNightMode = (MapConfig.minimapNightMode + 1) % 3;
            MapConfig.save();
            updateToolbarTooltips();
        });
        this.addRenderableWidget(nightModeButton);

        // Pos 5: Refresh
        refreshMapButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.REFRESH, button -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                if (Screen.hasShiftDown()) {
                    this.centerX = this.minecraft.player.getX();
                    this.centerZ = this.minecraft.player.getZ();
                    this.scale = DEFAULT_MAP_SCALE;
                    cancelMotionAndOpenAnimation();
                    return;
                }
                ChunkScanner.getInstance().requestRefresh(this.minecraft);
                if (CaveMode.isFullView(this.minecraft)) {
                    FullCaveTextureManager.getInstance().uploadDirtyTextures(true);
                } else if (CaveMode.isActive(this.minecraft)) {
                    CaveTextureManager.getInstance().uploadDirtyTextures(true);
                } else {
                    MapTextureManager.getInstance().uploadDirtyTextures(true);
                }
            }
        });
        this.addRenderableWidget(refreshMapButton);

        // Dimension Switcher (above Cave controls)
        dimensionSwitchButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE,
                MapUiIcons.Icon.DIMENSION_PORTAL, button -> cycleViewedDimension());
        this.addRenderableWidget(dimensionSwitchButton);

        if (this.minecraft != null && this.minecraft.level != null) {
            caveLayerModeButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.CAVE_OFF, button -> {
                if (MapConfig.getEffectiveCaveMapMode() == 0) return;
                markViewportInteraction();
                CaveMode.cycleCaveType(this.minecraft);
                clampScaleForCurrentMode();
                if (CaveMode.getCaveType(this.minecraft) == CaveMode.CaveType.OFF) {
                    CaveMapManager.getInstance().deactivate();
                } else if (CaveMode.getCaveType(this.minecraft) == CaveMode.CaveType.LAYERED) {
                    ChunkScanner.getInstance().requestImmediateCaveLayerRefresh(this.minecraft);
                } else {
                    ChunkScanner.getInstance().requestImmediateCaveLayerRefresh(
                            this.minecraft);
                }
                if (caveLayerSlider != null) caveLayerSlider.syncFromMode();
                updateCaveControlLayout();
            });
            this.addRenderableWidget(caveLayerModeButton);

            caveLayerModeButton.active = MapConfig.getEffectiveCaveMapMode() != 0;

            if (MapConfig.getEffectiveCaveMapMode() != 0) {
                DimensionMapProfile viewedProfile = MapManager.getInstance()
                        .getCurrentDimensionProfile();
                int minimumY = viewedProfile == null
                        ? this.minecraft.level.getMinBuildHeight() : viewedProfile.minY();
                int maximumY = viewedProfile == null
                        ? this.minecraft.level.getMaxBuildHeight() - 1 : viewedProfile.maxY();
                double initialValue = CaveMode.hasManualTopY(this.minecraft)
                        ? CaveLayerSlider.normalizeNumeric(CaveMode.getSelectedTopY(this.minecraft), minimumY, maximumY)
                        : 0.0;
                caveLayerSlider = new CaveLayerSlider(0, 0, 156, 18,
                        minimumY, maximumY, initialValue);
                caveLayerSlider.setTooltip(Tooltip.create(Component.literal(
                        "Cave Top Y\nAUTO follows the player's current underground band."
                                + "\nDrag right to select a fixed cave layer.")));
                this.addRenderableWidget(caveLayerSlider);
            }
            updateCaveControlLayout();
        }

        layoutToolbar();
        updateToolbarTooltips();
    }

    private void layoutToolbar() {
        Button[] buttons = { minimapConfigButton, waypointsToggleButton, waypointListButton,
                nightModeButton, refreshMapButton };
        toolbarStartX = TOOLBAR_MARGIN;
        toolbarStartY = TOOLBAR_MARGIN;
        toolbarStepY = TOOLBAR_BUTTON_SIZE + TOOLBAR_GAP;
        toolbarWidth = TOOLBAR_BUTTON_SIZE;
        toolbarRows = buttons.length;
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setX(toolbarStartX);
            buttons[i].setY(toolbarStartY + i * toolbarStepY);
            buttons[i].setWidth(TOOLBAR_BUTTON_SIZE);
        }
    }

    private void drawToolbarBackground(GuiGraphics guiGraphics) {
        // Intentionally empty
    }

    private void updateToolbarTooltips() {
        if (minimapConfigButton != null) minimapConfigButton.setTooltip(Tooltip.create(Component.literal(
                "Simple Map Settings\nConfigure minimap, coordinates, colors and scanning.")));
        if (waypointListButton != null) waypointListButton.setTooltip(Tooltip.create(Component.literal(
                "Waypoint Manager (Key: U)\nManage, edit, teleport and track waypoints.")));
        if (waypointsToggleButton != null) waypointsToggleButton.setTooltip(Tooltip.create(Component.literal(
                "Waypoint Visibility: " + (MapConfig.waypointsVisible ? "ON" : "OFF")
                        + "\nClick to show or hide all waypoint markers.")));
        if (refreshMapButton != null) refreshMapButton.setTooltip(Tooltip.create(Component.literal(
                "Refresh Visible Map\nRescan nearby chunks currently loaded by Minecraft."
                        + "\nShift-click: center on player and reset zoom.")));
        if (nightModeButton != null) nightModeButton.setTooltip(Tooltip.create(Component.literal(
                "Map Brightness: " + getNightModeMessage().getString()
                        + "\nCycle DAY / AUTO / NIGHT rendering.")));
        if (dimensionSwitchButton != null) dimensionSwitchButton.setTooltip(Tooltip.create(Component.literal(
                "Dimension: " + getDimensionName(selectedDimension))));
        updateCaveTooltip();
    }

    private String getDimensionName(String dim) {
        if ("LIVE".equals(dim)) {
            String live = MapManager.getInstance().getLiveDimensionResourceId();
            return "Current (" + MapManager.displayDimensionName(live) + ")";
        }
        return MapManager.displayDimensionName(dim);
    }

    private void cycleViewedDimension() {
        if (this.minecraft == null) return;
        MapManager manager = MapManager.getInstance();
        String liveDimension = manager.getLiveDimensionResourceId();

        // "Current" already represents the player's live dimension. Excluding that
        // same resource id removes redundant cycles such as Overworld -> Current
        // while the player is already in the Overworld.
        if (!"LIVE".equals(selectedDimension) && selectedDimension.equals(liveDimension)) {
            selectedDimension = "LIVE";
        }
        saveCurrentDimensionCamera();

        List<String> options = new ArrayList<>();
        options.add("LIVE");
        for (String dimension : manager.getSelectableDimensions()) {
            if (dimension == null || dimension.isBlank()
                    || dimension.equals(liveDimension) || options.contains(dimension)) {
                continue;
            }
            options.add(dimension);
        }
        int currentIndex = options.indexOf(selectedDimension);
        String previous = selectedDimension;
        selectedDimension = options.get((currentIndex + 1 + options.size()) % options.size());

        if ("LIVE".equals(selectedDimension)) manager.returnToLiveDimension(this.minecraft);
        else manager.switchToDimension(selectedDimension);
        restoreDimensionCamera(previous, selectedDimension);
        if (caveLayerSlider != null) caveLayerSlider.syncBoundsFromDimension();
        cursorCacheValid = false;
        isPopupMenuOpen = false;
        updateToolbarTooltips();
    }

    private void saveCurrentDimensionCamera() {
        dimensionCameras.put(selectedDimension, new double[] { centerX, centerZ, scale });
    }

    private void restoreDimensionCamera(String previous, String target) {
        double[] cached = dimensionCameras.get(target);
        if (cached != null) {
            centerX = cached[0];
            centerZ = cached[1];
            scale = (float) cached[2];
            currentRenderScale = scale;
            cancelMotionAndOpenAnimation();
            return;
        }
        if ("LIVE".equals(target) && minecraft != null && minecraft.player != null) {
            centerX = minecraft.player.getX();
            centerZ = minecraft.player.getZ();
        } else {
            String from = "LIVE".equals(previous)
                    ? MapManager.getInstance().getLiveDimensionResourceId() : previous;
            String to = "LIVE".equals(target)
                    ? MapManager.getInstance().getLiveDimensionResourceId() : target;
            // Xaero derives cross-dimension map coordinates from each target
            // DimensionType.coordinateScale(), not hard-coded Overworld/Nether ids.
            // Persisted profiles extend the same behavior to modded dimensions.
            MapManager mapManager = MapManager.getInstance();
            DimensionMapProfile fromProfile = mapManager.getDimensionProfile(from);
            DimensionMapProfile toProfile = mapManager.getDimensionProfile(to);
            double ratio = fromProfile.coordinateScale() / toProfile.coordinateScale();
            if (Double.isFinite(ratio) && ratio > 0.0) {
                centerX *= ratio;
                centerZ *= ratio;
            }
        }
        cancelMotionAndOpenAnimation();
    }

    private void updateCaveTooltip() {
        if (caveLayerModeButton == null) return;
        String detail = switch (CaveMode.getCaveType(this.minecraft)) {
            case OFF -> "Surface map only";
            case LAYERED -> "Cave map at the selected Top Y";
            case FULL -> "Full cave projection using the same AUTO/manual cave start";
        };
        String permission = MapConfig.getEffectiveCaveMapMode() == 0
                ? "\nCave maps are disabled by the server or local setting."
                : "\nClick to cycle OFF, CAVE and FULL.";
        caveLayerModeButton.setTooltip(Tooltip.create(Component.literal(
                getCaveLayerModeMessage().getString() + "\n" + detail + permission)));
    }

    private void updateCaveControlLayout() {
        int controlY = this.height - TOOLBAR_BUTTON_SIZE - TOOLBAR_MARGIN;
        if (caveLayerModeButton != null) {
            updateCaveTooltip();
            caveLayerModeButton.setWidth(TOOLBAR_BUTTON_SIZE);
            caveLayerModeButton.setX(TOOLBAR_MARGIN);
            caveLayerModeButton.setY(controlY);
        }
        if (dimensionSwitchButton != null) {
            dimensionSwitchButton.setWidth(TOOLBAR_BUTTON_SIZE);
            dimensionSwitchButton.setX(TOOLBAR_MARGIN);
            dimensionSwitchButton.setY(controlY - TOOLBAR_BUTTON_SIZE - TOOLBAR_GAP);
        }
        if (caveLayerSlider == null || caveLayerModeButton == null) return;

        int availableWidth = Math.max(44, this.width - 38);
        CaveMode.CaveType caveType = CaveMode.getCaveType(this.minecraft);
        // Xaero keeps the cave-start AUTO/manual control available for both
        // layered CAVE and FULL. FULL ignores the exact Top Y for projection, but
        // the value still decides whether cave mode is manually active.
        caveLayerSlider.visible = caveType != CaveMode.CaveType.OFF;
        caveLayerSlider.active = caveType != CaveMode.CaveType.OFF;
        if (!caveLayerSlider.visible) return;

        caveLayerSlider.setWidth(Math.min(190, availableWidth));
        caveLayerSlider.setX(TOOLBAR_MARGIN + TOOLBAR_BUTTON_SIZE + TOOLBAR_GAP + 2);
        caveLayerSlider.setY(controlY + 2);
    }

    private void drawCaveControlBackground(GuiGraphics guiGraphics) {
        // Intentionally empty. The cave icon and vanilla slider remain individually
        // readable without covering the underlying map with another dark rectangle.
    }

    private Component getWaypointsToggleMessage() {
        return Component.literal("WP: " + (MapConfig.waypointsVisible ? "ON" : "OFF"));
    }

    private Component getNightModeMessage() {
        String mode = switch (MapConfig.minimapNightMode) {
            case 1 -> "AUTO";
            case 2 -> "ON";
            default -> "OFF";
        };
        String label = CaveMode.isActive(this.minecraft) ? "Cave light: " : "Night: ";
        return Component.literal(label + mode);
    }

    private Component getCaveLayerModeMessage() {
        String type = switch (CaveMode.getCaveType(this.minecraft)) {
            case OFF -> "OFF";
            case LAYERED -> "CAVE";
            case FULL -> "FULL";
        };
        return Component.literal(type);
    }

    private void cancelMotionAndOpenAnimation() {
        momentumX = 0.0;
        momentumZ = 0.0;
        openAnimationStartNanos = 0L;
    }

    private float openAnimationMultiplier(long now) {
        if (openAnimationStartNanos == 0L) return 1.0f;
        float progress = Math.min(1.0f,
                (now - openAnimationStartNanos) / (float) OPEN_ANIMATION_NANOS);
        if (progress >= 1.0f) {
            openAnimationStartNanos = 0L;
            return 1.0f;
        }
        // Smoothstep keeps the 3x-to-2x opening motion visible through the full second.
        float eased = progress * progress * (3.0f - 2.0f * progress);
        return OPEN_ANIMATION_START_MULTIPLIER
                + (1.0f - OPEN_ANIMATION_START_MULTIPLIER) * eased;
    }

    private void updateMomentum(long now) {
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return;
        }
        double seconds = Math.min(0.050, Math.max(0.0, (now - lastFrameNanos) / 1_000_000_000.0));
        lastFrameNanos = now;
        if (isDragging || seconds <= 0.0) return;
        if (Math.abs(momentumX) < 0.5 && Math.abs(momentumZ) < 0.5) {
            momentumX = 0.0;
            momentumZ = 0.0;
            return;
        }
        centerX += momentumX * seconds;
        centerZ += momentumZ * seconds;
        double damping = Math.exp(-MOMENTUM_FRICTION * seconds);
        momentumX *= damping;
        momentumZ *= damping;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Override to draw a plain dark background instead of Minecraft 1.21's
        // built-in gaussian blur post-processing shader (which blurs our map).
        // We draw the background ourselves in render(), so this is intentionally empty.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        long frameNow = System.nanoTime();
        updateMomentum(frameNow);
        clampScaleForCurrentMode();
        currentRenderScale = scale * openAnimationMultiplier(frameNow);
        // AUTO is a live view: keep the compact Y readout synchronized with the
        // player's stable scan band without turning the slider into manual mode.
        if (caveLayerSlider != null && !caveLayerSlider.isDragging()
                && !CaveMode.hasManualTopY(this.minecraft)) {
            caveLayerSlider.syncFromMode();
        }
        // Fullscreen dark background
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF0D0D0D);

        // Thin 2-pixel border at screen edges (like Xaero's map)
        guiGraphics.fill(0, 0, this.width, 1, 0xFF2D3033); // top
        guiGraphics.fill(0, this.height - 1, this.width, this.height, 0xFF2D3033); // bottom
        guiGraphics.fill(0, 0, 1, this.height, 0xFF2D3033); // left
        guiGraphics.fill(this.width - 1, 0, this.width, this.height, 0xFF2D3033); // right

        // If map is not unlocked, draw a lock message and only render widgets
        if (!com.velorise.simplemap.SimpleMap.isMapUnlocked(this.minecraft.player)) {
            drawToolbarBackground(guiGraphics);
            drawCaveControlBackground(guiGraphics);
            super.render(guiGraphics, mouseX, mouseY, partialTick);
            String title = "MAP LOCKED";
            String subtitle = MapManager.getInstance().hasLearnedMap()
                ? "You must hold a Learned Map Book in your inventory to view the map."
                : "You must craft and learn a Map Book to unlock the world map.";
            int tw1 = this.font.width(title);
            int tw2 = this.font.width(subtitle);
            guiGraphics.drawString(this.font, title, (this.width - tw1) / 2, this.height / 2 - 20, 0xFF5555, false);
            guiGraphics.drawString(this.font, subtitle, (this.width - tw2) / 2, this.height / 2, 0xAAAAAA, false);
            return;
        }

        // Viewport occupies the full screen (minus 1px border)
        int vx = 1, vy = 1, vw = this.width - 2, vh = this.height - 2;

        // Mouse world coordinates relative to fullscreen viewport centre
        double mouseWorldX = centerX + (mouseX - vx - vw / 2.0) / currentRenderScale;
        double mouseWorldZ = centerZ + (mouseY - vy - vh / 2.0) / currentRenderScale;

        // Scanning, IO and texture publication are handled by MapViewportCoordinator
        // from client tick. Retained composition may reuse one texture for many
        // visual frames, so refresh the lightweight fullscreen demand independently
        // from atlas replay. Otherwise demand expires while the FBO is doing exactly
        // what it should: not calling MapRenderer every frame.
        double demandGuard = FullscreenMapFramebufferRenderer.demandOverscanPixels();
        // Use the stable post-animation zoom for loading. Otherwise the one-second
        // opening animation changes page bounds every frame and repeatedly rebases
        // the centre-out planner while the final viewport is still cold.
        float demandScale = scale;
        double demandHalfW = (vw * 0.5 + demandGuard)
                / Math.max(0.0001f, demandScale);
        double demandHalfH = (vh * 0.5 + demandGuard)
                / Math.max(0.0001f, demandScale);
        boolean viewportInteracting = isViewportInteracting(frameNow);
        MapViewportCoordinator.getInstance().submitFullscreen(
                centerX - demandHalfW, centerX + demandHalfW,
                centerZ - demandHalfH, centerZ + demandHalfH,
                demandScale, centerX, centerZ, viewportInteracting);

        // Draw map fullscreen, North-up, no rotation. The optional pixel-aligned
        // framebuffer retains the atlas replay and applies bounded pan motion as
        // source UV translation. Direct rendering remains the correctness fallback.
        boolean framebufferRendered = false;
        if (FullscreenMapFramebufferRenderer.shouldUse(this.minecraft,
                currentRenderScale)) {
            framebufferRendered = FullscreenMapFramebufferRenderer.getInstance().render(
                    guiGraphics, vx, vy, vw, vh, centerX, centerZ,
                    scale, currentRenderScale, partialTick, viewportInteracting);
        }
        if (!framebufferRendered) {
            MapRenderer.getInstance().drawMap(
                guiGraphics,
                vx, vy, vw, vh,
                centerX, centerZ, currentRenderScale,
                MapManager.getInstance().isViewingLiveDimension(),
                false,
                false, mouseWorldX, mouseWorldZ,
                partialTick, false
            );
        } else {
            MapRenderer.getInstance().drawFullscreenOverlays(
                    guiGraphics, vx, vy, vw, vh,
                    centerX, centerZ, currentRenderScale,
                    MapManager.getInstance().isViewingLiveDimension(),
                    mouseWorldX, mouseWorldZ, partialTick);
        }

        if (!MapManager.getInstance().isViewingLiveDimension()
                && !MapManager.getInstance().hasSavedDataForCurrentDimension()) {
            String message = "No saved map data — visit "
                    + MapManager.displayDimensionName(
                            MapManager.getInstance().getCurrentDimensionResourceId())
                    + " first";
            int textWidth = this.font.width(message);
            guiGraphics.fill((this.width - textWidth) / 2 - 6, this.height / 2 - 8,
                    (this.width + textWidth) / 2 + 6, this.height / 2 + 8, 0xB0000000);
            guiGraphics.drawString(this.font, message,
                    (this.width - textWidth) / 2, this.height / 2 - 4,
                    0xFFB8B8B8, false);
        }

        // Draw GUI widgets (buttons)
        drawToolbarBackground(guiGraphics);
        drawCaveControlBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Draw coordinates overlay
        drawCoordsOverlay(guiGraphics, mouseX, mouseY);

        // Draw the custom context menu if open
        if (isPopupMenuOpen) {
            drawPopupMenu(guiGraphics, mouseX, mouseY);
        }
    }

    private static class BlockInfo {
        final String name;
        final String id;
        final int y;
        final int waterSurfaceY;
        final boolean cached;

        BlockInfo(String name, String id, int y) {
            this(name, id, y, Integer.MIN_VALUE, false);
        }

        BlockInfo(String name, String id, int y, int waterSurfaceY) {
            this(name, id, y, waterSurfaceY, false);
        }

        BlockInfo(String name, String id, int y, int waterSurfaceY, boolean cached) {
            this.name = name;
            this.id = id;
            this.y = y;
            this.waterSurfaceY = waterSurfaceY;
            this.cached = cached;
        }

        boolean hasWaterSurface() {
            return waterSurfaceY != Integer.MIN_VALUE;
        }
    }

    /** Small vanilla-backed button whose foreground comes from our shared icon atlas. */
    private static final class PixelIconButton extends Button {
        private final MapUiIcons.Icon icon;

        private PixelIconButton(int x, int y, int width, int height,
                MapUiIcons.Icon icon, OnPress onPress) {
            super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
            this.icon = icon;
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (!visible) {
                return;
            }
            MapUiIcons.Icon displayedIcon = icon;
            if (icon == MapUiIcons.Icon.WAYPOINT_OUTLINE) {
                displayedIcon = MapConfig.waypointsVisible
                        ? MapUiIcons.Icon.WAYPOINT_FILLED
                        : MapUiIcons.Icon.WAYPOINT_OUTLINE;
            } else if (icon == MapUiIcons.Icon.SUN) {
                displayedIcon = switch (MapConfig.minimapNightMode) {
                    case 1 -> MapUiIcons.Icon.NIGHT_AUTO;
                    case 2 -> MapUiIcons.Icon.MOON;
                    default -> MapUiIcons.Icon.SUN;
                };
            } else if (icon == MapUiIcons.Icon.CAVE_ON || icon == MapUiIcons.Icon.CAVE_OFF) {
                // The hollow cave icon means cave view is active; the solid icon means OFF.
                displayedIcon = CaveMode.getCaveType(Minecraft.getInstance()) == CaveMode.CaveType.OFF
                        ? MapUiIcons.Icon.CAVE_OFF
                        : MapUiIcons.Icon.CAVE_ON;
            }
            if (isHoveredOrFocused()) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x12FFFFFF);
            }
            MapUiIcons.drawScaled(graphics, displayedIcon,
                    getX() + getWidth() / 2, getY() + getHeight() / 2,
                    active, TOOLBAR_ICON_SCALE);
        }
    }

    private void drawCoordsOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Calculate world coordinates under the mouse cursor (viewport starts at x=1, y=1)
        double worldX = centerX + (mouseX - 1 - (this.width - 2) / 2.0) / currentRenderScale;
        double worldZ = centerZ + (mouseY - 1 - (this.height - 2) / 2.0) / currentRenderScale;

        int bx = (int) Math.floor(worldX);
        int bz = (int) Math.floor(worldZ);

        // Resolve live world data first. Outside the client's chunk radius, fall back
        // to the persistent surface/vertical archive instead of dropping the Y value.
        int yCoord = 0;
        int waterSurfaceY = Integer.MIN_VALUE;
        boolean hasY = false;
        boolean chunkLoaded = MapManager.getInstance().isViewingLiveDimension()
                && this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.hasChunk(bx >> 4, bz >> 4);
        BlockInfo resolved = resolveCursorBlockInfo(bx, bz, chunkLoaded, System.nanoTime());
        if (resolved != null && (!resolved.name.isEmpty() || resolved.cached)) {
            yCoord = resolved.y;
            waterSurfaceY = resolved.waterSurfaceY;
            hasY = true;
        }

        String coordText = buildCoordinateText(bx, bz, hasY,
                yCoord, waterSurfaceY);

        String blockText = null;
        if (MapConfig.cursorBlockEnabled && resolved != null && resolved.name != null && !resolved.name.isBlank()) {
            blockText = resolved.name;
        }

        String biomeText = null;
        // ClientLevel biome access is only authoritative while the chunk is live.
        // Cached surface data currently stores a compact biome palette internally,
        // but the cursor does not force-load world chunks merely to display it.
        if (MapConfig.cursorBiomeEnabled && hasY && chunkLoaded
                && this.minecraft != null && this.minecraft.level != null) {
            biomeText = resolveCursorBiomeText(bx, yCoord, bz);
        }

        int coordWidth = this.font.width(coordText);
        int blockWidth = blockText == null ? 0 : this.font.width(blockText);
        int biomeWidth = biomeText == null ? 0 : this.font.width(biomeText);
        int panelWidth = Math.max(coordWidth, Math.max(blockWidth, biomeWidth)) + 10;
        int lines = 1 + (blockText == null ? 0 : 1) + (biomeText == null ? 0 : 1);
        int panelHeight = 3 + lines * 10;
        int panelX = (this.width - panelWidth) / 2;
        // Nudge coordinate readout closer to the top edge
        int panelY = 4;
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0x88000000);
        int lineY = panelY + 3;
        guiGraphics.drawString(this.font, coordText,
                panelX + (panelWidth - coordWidth) / 2, lineY, 0xFFFFFF, false);
        lineY += 10;
        if (blockText != null) {
            guiGraphics.drawString(this.font, blockText,
                    panelX + (panelWidth - blockWidth) / 2, lineY, 0xD8D8D8, false);
            lineY += 10;
        }
        if (biomeText != null) {
            guiGraphics.drawString(this.font, biomeText,
                    panelX + (panelWidth - biomeWidth) / 2, lineY, 0xA8D69C, false);
        }

        drawZoomPanel(guiGraphics);

        // Render player coordinates at bottom-right. Bounds and text use one shared
        // calculation so the clickable panel cannot drift below the label.
        if (this.minecraft != null && this.minecraft.player != null) {
            String playerText = getPlayerCoordinatesText();
            int playerPanelWidth = this.font.width(playerText) + 10;
            int panelRight = this.width - PLAYER_PANEL_MARGIN;
            int panelBottom = this.height - PLAYER_PANEL_MARGIN;
            int panelLeft = panelRight - playerPanelWidth;
            int panelTop = panelBottom - PLAYER_PANEL_HEIGHT;
            boolean hovered = isInside(mouseX, mouseY,
                    panelLeft, panelTop, panelRight, panelBottom);
            guiGraphics.fill(panelLeft, panelTop, panelRight, panelBottom,
                    hovered ? 0xAA000000 : 0x78000000);
            if (hovered) {
                guiGraphics.renderOutline(panelLeft, panelTop, playerPanelWidth,
                        PLAYER_PANEL_HEIGHT, 0x88FFFFFF);
            }
            guiGraphics.drawString(this.font, playerText, panelLeft + 5, panelTop + 3,
                    0xFFFFFFFF, false);
            if (hovered) {
                guiGraphics.renderTooltip(this.font,
                        Component.literal("Click to copy these coordinates and open chat."),
                        mouseX, mouseY);
            }
        }
    }

    private void drawZoomPanel(GuiGraphics guiGraphics) {
        String zoomText = getZoomText();
        int panelWidth = this.font.width(zoomText) + 10;
        int left = (this.width - panelWidth) / 2;
        int bottom = this.height - ZOOM_PANEL_MARGIN;
        int top = bottom - ZOOM_PANEL_HEIGHT;
        guiGraphics.fill(left, top, left + panelWidth, bottom, 0x78000000);
        guiGraphics.drawString(this.font, zoomText, left + 5, top + 3,
                0xFFE6E6E6, false);
    }

    private String getZoomText() {
        // MapScreen.scale is expressed in logical GUI pixels per block. Xaero's
        // fullscreen label is effectively physical framebuffer pixels per block on
        // a 1080p display. Printing the logical value made 0.262x in Simple Map look
        // comparable to 0.262x in Xaero even though GUI scale 3 renders it at 0.786
        // physical pixels/block. Report the actual screen density so screenshots and
        // performance tests use the same unit.
        double guiScale = this.minecraft == null || this.minecraft.getWindow() == null
                ? 1.0D : Math.max(1.0D, this.minecraft.getWindow().getGuiScale());
        float displayedScale = (float) (Math.max(getMinimumStableScale(), this.scale)
                * guiScale);
        int decimals = displayedScale < 1.0f ? 3 : displayedScale < 10.0f ? 2 : 1;
        int divisor = decimals == 3 ? 1000 : decimals == 2 ? 100 : 10;
        int quantized = Math.round(displayedScale * divisor);
        int key = quantized * 31 + divisor;
        if (key == cachedZoomTextKey) return cachedZoomText;
        int fraction = Math.abs(quantized % divisor);
        overlayTextBuilder.setLength(0);
        overlayTextBuilder.append(quantized / divisor).append('.');
        if (decimals >= 2 && fraction < divisor / 10) overlayTextBuilder.append('0');
        if (decimals == 3 && fraction < 10) overlayTextBuilder.append('0');
        overlayTextBuilder.append(fraction).append('x');
        cachedZoomTextKey = key;
        cachedZoomText = overlayTextBuilder.toString();
        return cachedZoomText;
    }

    private String getPlayerCoordinatesText() {
        int x = (int) Math.floor(this.minecraft.player.getX());
        int y = (int) Math.floor(this.minecraft.player.getY());
        int z = (int) Math.floor(this.minecraft.player.getZ());
        if (x == cachedPlayerTextX && y == cachedPlayerTextY
                && z == cachedPlayerTextZ) return cachedPlayerText;
        overlayTextBuilder.setLength(0);
        overlayTextBuilder.append("Player X: ");
        appendGroupedInteger(overlayTextBuilder, x);
        overlayTextBuilder.append(" | Y: ");
        appendGroupedInteger(overlayTextBuilder, y);
        overlayTextBuilder.append(" | Z: ");
        appendGroupedInteger(overlayTextBuilder, z);
        cachedPlayerTextX = x;
        cachedPlayerTextY = y;
        cachedPlayerTextZ = z;
        cachedPlayerText = overlayTextBuilder.toString();
        return cachedPlayerText;
    }

    private String buildCoordinateText(int x, int z, boolean hasY,
            int y, int waterSurfaceY) {
        if (x == cachedCoordTextX && z == cachedCoordTextZ
                && hasY == cachedCoordHasY
                && (!hasY || (y == cachedCoordTextY
                        && waterSurfaceY == cachedCoordWaterY))) {
            return cachedCoordText;
        }
        overlayTextBuilder.setLength(0);
        overlayTextBuilder.append("X: ");
        appendGroupedInteger(overlayTextBuilder, x);
        if (hasY) {
            overlayTextBuilder.append("  Y: ").append(y);
            if (waterSurfaceY != Integer.MIN_VALUE) {
                overlayTextBuilder.append(" (").append(waterSurfaceY).append(')');
            }
        }
        overlayTextBuilder.append("  Z: ");
        appendGroupedInteger(overlayTextBuilder, z);
        cachedCoordTextX = x;
        cachedCoordTextY = y;
        cachedCoordTextZ = z;
        cachedCoordWaterY = waterSurfaceY;
        cachedCoordHasY = hasY;
        cachedCoordText = overlayTextBuilder.toString();
        return cachedCoordText;
    }

    private String resolveCursorBiomeText(int x, int y, int z) {
        if (x == cachedBiomeX && y == cachedBiomeY && z == cachedBiomeZ
                && cachedBiomeText != null) return cachedBiomeText;
        net.minecraft.core.BlockPos biomePos = new net.minecraft.core.BlockPos(x, y, z);
        cachedBiomeText = this.minecraft.level.getBiome(biomePos).unwrapKey()
                .map(key -> prettifyId(key.location().getPath()))
                .orElse("Unknown Biome");
        cachedBiomeX = x;
        cachedBiomeY = y;
        cachedBiomeZ = z;
        return cachedBiomeText;
    }

    private static void appendGroupedInteger(StringBuilder builder, int value) {
        long absolute = Math.abs((long) value);
        if (value < 0) builder.append('-');
        long divisor = 1L;
        int digits = 1;
        while (divisor <= absolute / 10L) {
            divisor *= 10L;
            digits++;
        }
        while (divisor > 0L) {
            builder.append((char) ('0' + (absolute / divisor) % 10L));
            divisor /= 10L;
            digits--;
            if (digits > 0 && digits % 3 == 0) builder.append(',');
        }
    }

    private static boolean isInside(double mouseX, double mouseY,
            int left, int top, int right, int bottom) {
        return mouseX >= left && mouseX <= right
                && mouseY >= top && mouseY <= bottom;
    }

    /**
     * Looks up cursor height without requiring the Minecraft chunk to remain loaded.
     * Surface mode uses the persisted .smdat column, while layered/full cave modes
     * use the v3 vertical-run archive (with the older full-cave snapshot as a fallback).
     */
    private BlockInfo resolveCursorBlockInfo(int bx, int bz, boolean chunkLoaded, long now) {
        long revision = CaveMode.getRevision();
        if (cursorCacheValid && cachedCursorX == bx && cachedCursorZ == bz
                && cachedCursorRevision == revision
                && now - cachedCursorAtNanos < CURSOR_CACHE_NANOS) {
            return cachedCursorInfo;
        }
        BlockInfo resolved = null;
        if (MapManager.getInstance().isViewingLiveDimension()
                && chunkLoaded && this.minecraft != null && this.minecraft.level != null) {
            resolved = getBlockInfoAt(this.minecraft.level, bx, bz);
        }
        if (resolved == null || resolved.name.isEmpty()) {
            BlockInfo cached = getCachedBlockInfoAt(bx, bz);
            if (cached != null) resolved = cached;
        }
        cachedCursorX = bx;
        cachedCursorZ = bz;
        cachedCursorRevision = revision;
        cachedCursorAtNanos = now;
        cachedCursorInfo = resolved;
        cursorCacheValid = true;
        return resolved;
    }

    private BlockInfo getCachedBlockInfoAt(int bx, int bz) {
        if (this.minecraft == null || this.minecraft.level == null) return null;
        net.minecraft.world.level.Level level = this.minecraft.level;

        if (CaveMode.isActive(this.minecraft)) {
            if (CaveMode.isFullView(this.minecraft)) {
                int fullY = FullCaveMapManager.getInstance().getSurfaceY(bx, bz);
                if (fullY != FullCaveMapManager.NO_SURFACE) {
                    return new BlockInfo("", "", fullY, Integer.MIN_VALUE, true);
                }
                VerticalCaveArchiveManager.Candidate candidate =
                        VerticalCaveArchiveManager.getInstance().getCandidate(
                                bx, bz, level.getMaxBuildHeight() - 1, level.getMinBuildHeight());
                return candidate == null ? null
                        : new BlockInfo("", "", candidate.bottomY(), Integer.MIN_VALUE, true);
            }

            int layerY = CaveMode.getLayerY(this.minecraft);
            int maximumY = CaveMode.getViewedScanMaximum(this.minecraft, layerY);
            int minimumY = CaveMode.getViewedScanMinimum(this.minecraft, layerY);
            VerticalCaveArchiveManager.Candidate candidate =
                    VerticalCaveArchiveManager.getInstance().getCandidate(
                            bx, bz, maximumY, minimumY);
            return candidate == null ? null
                    : new BlockInfo("", "", candidate.bottomY(), Integer.MIN_VALUE, true);
        }

        MapManager manager = MapManager.getInstance();
        MapBlockData data = manager.getBlockData(bx, bz);
        if (data == null) {
            int regionX = bx >> 9;
            int regionZ = bz >> 9;
            if (manager.hasRegionFile(regionX, regionZ)
                    && !manager.isRegionLoadedInCache(regionX, regionZ)) {
                MapProcessor.getInstance().enqueueSurfaceLoad(regionX, regionZ, 120_000);
            }
            return null;
        }
        if (data.isEmpty()) return null;
        int waterSurface = data.isFluid() && !data.isGlowing()
                ? data.topY : Integer.MIN_VALUE;
        return new BlockInfo("", "", data.getReliefY(), waterSurface, true);
    }

    private BlockInfo getBlockInfoAt(net.minecraft.world.level.Level level, int bx, int bz) {
        boolean scanFromWorldTop = CaveDimensionProfile.shouldScanFromWorldTop(level);
        int minBuildHeight = level.getMinBuildHeight();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos(bx, 0, bz);

        if (CaveMode.isActive(this.minecraft)) {
            if (CaveMode.isFullView(this.minecraft)) {
                int surfaceY = FullCaveMapManager.getInstance().getSurfaceY(bx, bz);
                if (surfaceY != FullCaveMapManager.NO_SURFACE) {
                    pos.setY(surfaceY);
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(state.getBlock()).toString();
                        return createBlockInfo(level, pos, surfaceY);
                    }
                }
                return new BlockInfo("", "", minBuildHeight);
            }
            int layerY = CaveMode.getLayerY(this.minecraft);
            int scanMinimum = CaveMode.getScanMinimum(level, layerY);
            int scanMaximum = CaveMode.getScanMaximum(level, layerY);
            VerticalCaveArchiveManager archive = VerticalCaveArchiveManager.getInstance();
            if (archive.isColumnReady(bx, bz) && archive.isColumnScanned(bx, bz)) {
                VerticalCaveArchiveManager.Candidate candidate =
                        archive.getCandidate(bx, bz, scanMaximum, scanMinimum);
                if (candidate == null) return null;
                pos.setY(candidate.bottomY());
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (!state.isAir()) return createBlockInfo(level, pos, candidate.bottomY());
                return new BlockInfo("", "", candidate.bottomY(), Integer.MIN_VALUE, true);
            }

            // Match the renderer: exact material at Top-Y first, then search only
            // downward through the bounded band. Alternating above/below caused the
            // cursor Y to jump between unrelated cave floors every frame.
            pos.setY(scanMaximum);
            net.minecraft.world.level.block.state.BlockState selected = level.getBlockState(pos);
            if (isRenderableSliceState(level, pos, selected)) {
                return createBlockInfo(level, pos, scanMaximum);
            }
            for (int y = scanMaximum; y >= scanMinimum; y--) {
                BlockInfo info = getCaveBlockInfoAtY(level, pos, y);
                if (info != null) return info;
            }
            return null;
        }

        int highestY;
        if (scanFromWorldTop) {
            int dimensionTopY = level.getMaxBuildHeight() - 1;
            highestY = dimensionTopY;
            boolean foundAir = false;
            for (int y = dimensionTopY; y >= minBuildHeight; y--) {
                pos.setY(y);
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (!foundAir) {
                    if (state.isAir()) foundAir = true;
                } else {
                    if (!state.isAir() && state.getMapColor(level, pos) != net.minecraft.world.level.material.MapColor.NONE) {
                        highestY = y;
                        break;
                    }
                }
            }
        } else {
            int surfaceY = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, bx, bz);
            highestY = minBuildHeight;
            for (int y = surfaceY; y >= minBuildHeight; y--) {
                pos.setY(y);
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (!state.isAir() && state.getMapColor(level, pos) != net.minecraft.world.level.material.MapColor.NONE) {
                    highestY = y;
                    break;
                }
            }
        }

        if (highestY > minBuildHeight) {
            pos.setY(highestY);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return createBlockInfo(level, pos, highestY);
        }
        return new BlockInfo("", "", minBuildHeight);
    }

    private boolean isRenderableSliceState(net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos.MutableBlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        boolean openEmitter = state.getLightEmission() > 0
                && state.getFluidState().isEmpty()
                && (state.is(net.minecraft.world.level.block.Blocks.FIRE)
                        || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                        || caveStateClassifier.isCollisionEmpty(level, pos, state));
        boolean flower = MapConfig.displayFlowers
                && visualClassifier.info(state).flower();
        if (openEmitter || flower || !state.getFluidState().isEmpty()) return true;
        return !state.isAir()
                && !caveStateClassifier.isCollisionEmpty(level, pos, state)
                && state.getMapColor(level, pos) != net.minecraft.world.level.material.MapColor.NONE;
    }

    private BlockInfo getCaveBlockInfoAtY(net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos.MutableBlockPos pos, int openY) {
        pos.setY(openY);
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        boolean openEmitter = state.getLightEmission() > 0
                && state.getFluidState().isEmpty()
                && (state.is(net.minecraft.world.level.block.Blocks.FIRE)
                        || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                        || caveStateClassifier.isCollisionEmpty(level, pos, state));
        if (openEmitter) {
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).toString();
            return new BlockInfo(state.getBlock().getName().getString(), blockId, openY);
        }
        if (state.getFluidState().isEmpty()) {
            boolean openSpace = state.isAir() || caveStateClassifier.isCollisionEmpty(level, pos, state);
            if (!openSpace || openY <= level.getMinBuildHeight()) return null;
            pos.setY(openY - 1);
            state = level.getBlockState(pos);
            boolean floorOpen = state.isAir()
                    || (caveStateClassifier.isCollisionEmpty(level, pos, state) && state.getFluidState().isEmpty());
            if (floorOpen) return null;
        }
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString();
        return createBlockInfo(level, pos, pos.getY());
    }

    private BlockInfo createBlockInfo(net.minecraft.world.level.Level level,
            net.minecraft.core.BlockPos.MutableBlockPos pos, int visibleY) {
        pos.setY(visibleY);
        net.minecraft.world.level.block.state.BlockState visibleState = level.getBlockState(pos);
        if (visibleState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            int waterSurfaceY = visibleY;
            int bottomY = visibleY;
            net.minecraft.world.level.block.state.BlockState bottomState = visibleState;
            for (int y = visibleY - 1; y >= level.getMinBuildHeight(); y--) {
                pos.setY(y);
                net.minecraft.world.level.block.state.BlockState candidate = level.getBlockState(pos);
                if (candidate.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                        || candidate.isAir()
                        || caveStateClassifier.isCollisionEmpty(level, pos, candidate)) continue;
                bottomY = y;
                bottomState = candidate;
                break;
            }
            String bottomId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(bottomState.getBlock()).toString();
            return new BlockInfo(bottomState.getBlock().getName().getString(), bottomId,
                    bottomY, waterSurfaceY);
        }
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(visibleState.getBlock()).toString();
        return new BlockInfo(visibleState.getBlock().getName().getString(), blockId, visibleY);
    }

    private static String prettifyId(String id) {
        String[] parts = id.split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private void drawPopupMenu(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0, 0, 100); // Draw on top of everything

        int menuHeight = clickedWaypoint != null ? 80 : 60;
        // Background box
        guiGraphics.fill((int)popupX, (int)popupY, (int)popupX + 100, (int)popupY + menuHeight, 0xFF121212);
        guiGraphics.renderOutline((int)popupX, (int)popupY, 100, menuHeight, 0xFF3C3C3C);

        // Option 1: Delete Waypoint or Add Waypoint
        boolean hoverOpt1 = mouseX >= popupX && mouseX <= popupX + 100 && mouseY >= popupY && mouseY <= popupY + 20;
        guiGraphics.fill((int)popupX + 1, (int)popupY + 1, (int)popupX + 99, (int)popupY + 19, hoverOpt1 ? 0xFF2D3033 : 0x00000000);
        String opt1Text = clickedWaypoint != null ? "Delete Waypoint" : "Add Waypoint";
        guiGraphics.drawString(this.font, opt1Text, (int)popupX + 6, (int)popupY + 6, 0xFFFFFF, false);

        // Option 2: Teleport Here
        boolean hoverOpt2 = mouseX >= popupX && mouseX <= popupX + 100 && mouseY >= popupY + 20 && mouseY <= popupY + 40;
        boolean canTeleport = MapTeleportController.canTeleport(this.minecraft);
        guiGraphics.fill((int)popupX + 1, (int)popupY + 21, (int)popupX + 99, (int)popupY + 39, hoverOpt2 && canTeleport ? 0xFF2D3033 : 0x00000000);
        guiGraphics.drawString(this.font, "Teleport Here", (int)popupX + 6, (int)popupY + 26, canTeleport ? 0xFFFFFF : 0x777777, false);

        // Option 3: share the inspected location through a pre-filled chat message.
        boolean hoverOpt3 = mouseX >= popupX && mouseX <= popupX + 100
                && mouseY >= popupY + 40 && mouseY <= popupY + 60;
        guiGraphics.fill((int) popupX + 1, (int) popupY + 41, (int) popupX + 99, (int) popupY + 59,
                hoverOpt3 ? 0xFF2D3033 : 0x00000000);
        guiGraphics.drawString(this.font, "Share Location", (int) popupX + 6, (int) popupY + 46,
                0xFFFFFF, false);

        // Option 4: Follow Waypoint (Only for existing waypoints)
        if (clickedWaypoint != null) {
            boolean isCurrentlyFollowing = MapConfig.pinActive &&
                Math.abs(MapConfig.pinWorldX - clickedWaypoint.x) < 0.01 &&
                Math.abs(MapConfig.pinWorldZ - clickedWaypoint.z) < 0.01;
            boolean hoverOpt4 = mouseX >= popupX && mouseX <= popupX + 100
                    && mouseY >= popupY + 60 && mouseY <= popupY + 80;
            guiGraphics.fill((int)popupX + 1, (int)popupY + 61, (int)popupX + 99, (int)popupY + 79,
                    hoverOpt4 ? 0xFF2D3033 : 0x00000000);
            String opt4Text = isCurrentlyFollowing ? "Stop Following" : "Follow Waypoint";
            guiGraphics.drawString(this.font, opt4Text, (int)popupX + 6, (int)popupY + 66, 0xFFFFFF, false);
        }

        guiGraphics.pose().popPose();
    }

    private void markViewportInteraction() {
        long now = System.nanoTime();
        if (lastViewportInteractionNanos == 0L
                || now - lastViewportInteractionNanos
                        >= VIEWPORT_INTERACTION_SETTLE_NANOS) {
            MapViewportCoordinator.getInstance().beginFullscreenInteraction();
        }
        lastViewportInteractionNanos = now;
    }

    private boolean isViewportInteracting(long nowNanos) {
        return isDragging
                || (caveLayerSlider != null && caveLayerSlider.isDragging())
                || Math.abs(momentumX) > 0.5 || Math.abs(momentumZ) > 0.5
                || nowNanos - lastViewportInteractionNanos
                        < VIEWPORT_INTERACTION_SETTLE_NANOS;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (SimpleMap.ClientModEvents.OPEN_MAP_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.OPEN_MAP_KEY);
            this.onClose();
            return true;
        }
        if (SimpleMap.ClientModEvents.ZOOM_IN_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.ZOOM_IN_KEY);
            markViewportInteraction();
            this.scale = Math.min(12.0f, this.scale * 1.15f);
            return true;
        }
        if (SimpleMap.ClientModEvents.ZOOM_OUT_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.ZOOM_OUT_KEY);
            markViewportInteraction();
            this.scale = Math.max(getMinimumStableScale(), this.scale / 1.15f);
            return true;
        }
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME
                || SimpleMap.ClientModEvents.CENTER_FULL_MAP_KEY.matches(keyCode, scanCode))
                && this.minecraft != null && this.minecraft.player != null) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.CENTER_FULL_MAP_KEY);
            markViewportInteraction();
            this.centerX = this.minecraft.player.getX();
            this.centerZ = this.minecraft.player.getZ();
            this.scale = DEFAULT_MAP_SCALE;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static void consumeBufferedClicks(net.minecraft.client.KeyMapping mapping) {
        while (mapping.consumeClick()) {
            // Prevent Screen.keyPressed and the next client tick from handling the same key.
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // If map is not unlocked, only allow clicking screen widgets (buttons)
        if (!SimpleMap.isMapUnlocked(this.minecraft.player)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Click the visible bottom-right player-coordinate panel to share in chat.
        if (this.minecraft != null && this.minecraft.player != null) {
            String playerText = getPlayerCoordinatesText();
            int panelWidth = this.font.width(playerText) + 10;
            int panelRight = this.width - PLAYER_PANEL_MARGIN;
            int panelBottom = this.height - PLAYER_PANEL_MARGIN;
            int panelLeft = panelRight - panelWidth;
            int panelTop = panelBottom - PLAYER_PANEL_HEIGHT;
            if (isInside(mouseX, mouseY,
                    panelLeft, panelTop, panelRight, panelBottom)) {
                net.minecraft.world.entity.player.Player player = this.minecraft.player;
                String shareText = "I am at coordinates ["
                        + (int) Math.floor(player.getX()) + ", "
                        + (int) Math.floor(player.getY()) + ", "
                        + (int) Math.floor(player.getZ()) + "]";
                // Copy to Clipboard
                this.minecraft.keyboardHandler.setClipboard(shareText);
                player.sendSystemMessage(Component.literal("§aCoordinates copied to clipboard!"));

                // Open Chat Screen with the pre-filled text
                this.onClose();
                this.minecraft.setScreen(new net.minecraft.client.gui.screens.ChatScreen(shareText));
                return true;
            }
        }

        // 1. If context menu is open, handle its options first
        if (isPopupMenuOpen) {
            int menuHeight = clickedWaypoint != null ? 80 : 60;
            if (mouseX >= popupX && mouseX <= popupX + 100 && mouseY >= popupY && mouseY <= popupY + menuHeight) {
                // Determine option clicked
                if (mouseY < popupY + 20) {
                    if (clickedWaypoint != null) {
                        // Option 1 (Waypoint exists): Delete Waypoint
                        WaypointManager.getInstance().removeWaypoint(clickedWaypoint);
                        clickedWaypoint = null;
                        isPopupMenuOpen = false;
                    } else {
                        // Option 1 (No waypoint): Add Waypoint
                        isPopupMenuOpen = false;
                        if (this.minecraft != null) {
                            String waypointDimension = MapManager.getInstance()
                                    .getCurrentDimensionResourceId();
                            int waypointY = resolveTeleportTargetY(
                                    (int) Math.floor(popupWorldX),
                                    (int) Math.floor(popupWorldZ), waypointDimension);
                            this.minecraft.setScreen(new AddWaypointScreen(this,
                                    popupWorldX, waypointY, popupWorldZ,
                                    waypointDimension));
                        }
                    }
                } else if (mouseY < popupY + 40) {
                    // Option 2: Teleport to the dimension currently displayed by the map.
                    if (MapTeleportController.canTeleport(this.minecraft)) {
                        isPopupMenuOpen = false;
                        int tpX = (int) Math.floor(popupWorldX);
                        int tpZ = (int) Math.floor(popupWorldZ);
                        String targetDimension = MapManager.getInstance()
                                .getCurrentDimensionResourceId();
                        int targetY = resolveTeleportTargetY(tpX, tpZ, targetDimension);
                        if (MapTeleportController.teleport(this.minecraft, targetDimension,
                                tpX, targetY, tpZ)) {
                            this.onClose();
                        }
                    }
                } else if (mouseY < popupY + 60) {
                    // Option 3: keep sharing intentional by opening editable chat.
                    String dimension = MapManager.getInstance()
                            .getCurrentDimensionResourceId();
                    int targetY = resolveTeleportTargetY(
                            (int) Math.floor(popupWorldX),
                            (int) Math.floor(popupWorldZ), dimension);
                    isPopupMenuOpen = false;
                    this.onClose();
                    WaypointChatShare.shareLocation(this.minecraft, null,
                            popupWorldX, targetY, popupWorldZ, true, dimension);
                } else if (clickedWaypoint != null && mouseY < popupY + 80) {
                    // Option 4: Follow Waypoint
                    boolean isCurrentlyFollowing = MapConfig.pinActive &&
                        Math.abs(MapConfig.pinWorldX - clickedWaypoint.x) < 0.01 &&
                        Math.abs(MapConfig.pinWorldZ - clickedWaypoint.z) < 0.01;
                    if (isCurrentlyFollowing) {
                        PinNavigation.clear();
                    } else {
                        PinNavigation.activate(clickedWaypoint.x, clickedWaypoint.z);
                    }
                    MapManager.getInstance().savePin();
                    isPopupMenuOpen = false;
                    clickedWaypoint = null;
                }
                return true;
            }
            isPopupMenuOpen = false; // Clicked outside the menu, close it
            clickedWaypoint = null;
        }

        // 2. Standard button clicks
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        // 3. Left click: start drag tracking; determine if it's a click vs drag on mouseReleased
        if (button == 0) {
            markViewportInteraction();
            cancelMotionAndOpenAnimation();
            this.isDragging = true;
            dragStartX = mouseX;
            dragStartZ = mouseY;
            lastDragSampleNanos = System.nanoTime();
            return true;
        }

        // 4. Right click opens the popup context menu
        if (button == 1) {
            popupX = mouseX;
            popupY = mouseY;
            popupWorldX = centerX + (mouseX - this.width / 2.0) / currentRenderScale;
            popupWorldZ = centerZ + (mouseY - this.height / 2.0) / currentRenderScale;

            // Check if right-clicking on an existing waypoint
            clickedWaypoint = null;
            java.util.List<WaypointManager.Waypoint> waypoints = WaypointManager.getInstance()
                    .getWaypointsForDimension(MapManager.getInstance()
                            .getCurrentDimensionResourceId());
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (WaypointManager.Waypoint wp : waypoints) {
                double hoverRadius = MapRenderer.waypointHitRadiusWorld(
                        wp, currentRenderScale);
                if (Math.abs(popupWorldX - wp.x) <= hoverRadius && Math.abs(popupWorldZ - wp.z) <= hoverRadius) {
                    double distance = Math.hypot(popupWorldX - wp.x,
                            popupWorldZ - wp.z);
                    if (distance < nearestDistance) {
                        clickedWaypoint = wp;
                        nearestDistance = distance;
                    }
                }
            }

            isPopupMenuOpen = true;
            return true;
        }

        return false;
    }

    private int resolveTeleportTargetY(int blockX, int blockZ, String targetDimension) {
        MapManager manager = MapManager.getInstance();
        int minimumY = this.minecraft != null && this.minecraft.level != null
                ? this.minecraft.level.getMinBuildHeight() : -64;

        // Only query ClientLevel blocks when the map is displaying that live level.
        // A remotely viewed Nether/End/modded map must never sample Overworld chunks.
        if (manager.isViewingLiveDimension() && this.minecraft != null
                && this.minecraft.level != null) {
            net.minecraft.world.level.Level level = this.minecraft.level;
            if (level.hasChunk(blockX >> 4, blockZ >> 4)) {
                BlockInfo live = getBlockInfoAt(level, blockX, blockZ);
                if (live != null && live.y > level.getMinBuildHeight()) {
                    int surfaceY = live.waterSurfaceY != Integer.MIN_VALUE
                            ? live.waterSurfaceY : live.y;
                    return surfaceY + 2;
                }
            }
        }

        BlockInfo cached = getCachedBlockInfoAt(blockX, blockZ);
        if (cached != null && cached.y > minimumY) {
            int surfaceY = cached.waterSurfaceY != Integer.MIN_VALUE
                    ? cached.waterSurfaceY : cached.y;
            return surfaceY + 2;
        }
        return MapTeleportController.defaultTargetY(targetDimension);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            boolean wasDragging = this.isDragging;
            this.isDragging = false;

            // If mouse barely moved, treat as a click -> place/remove pin
            double moved = Math.sqrt(Math.pow(mouseX - dragStartX, 2) + Math.pow(mouseY - dragStartZ, 2));
            if (wasDragging && moved < 4.0 && !isPopupMenuOpen && MapManager.getInstance().hasLearnedMap()) {
                momentumX = 0.0;
                momentumZ = 0.0;
                double clickWorldX = centerX + (mouseX - 1 - (this.width - 2) / 2.0) / currentRenderScale;
                double clickWorldZ = centerZ + (mouseY - 1 - (this.height - 2) / 2.0) / currentRenderScale;

                double snappedPinX = Math.floor(clickWorldX) + 0.5;
                double snappedPinZ = Math.floor(clickWorldZ) + 0.5;

                // Click same pin again within 4 screen-pixels -> remove
                if (MapConfig.pinActive && Math.abs(snappedPinX - MapConfig.pinWorldX) < 4.0 / currentRenderScale && Math.abs(snappedPinZ - MapConfig.pinWorldZ) < 4.0 / currentRenderScale) {
                    PinNavigation.clear();
                } else {
                    PinNavigation.activate(snappedPinX, snappedPinZ);
                }
                MapManager.getInstance().savePin();
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!MapManager.getInstance().hasLearnedMap()) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        if (this.isDragging) {
            markViewportInteraction();
            // Drag direction moves the map camera center in the opposite direction.
            double deltaX = -dragX / this.scale;
            double deltaZ = -dragY / this.scale;
            this.centerX += deltaX;
            this.centerZ += deltaZ;
            long now = System.nanoTime();
            double seconds = lastDragSampleNanos == 0L ? 0.0
                    : Math.max(0.001, Math.min(0.050,
                            (now - lastDragSampleNanos) / 1_000_000_000.0));
            lastDragSampleNanos = now;
            if (seconds > 0.0) {
                double sampleX = deltaX / seconds;
                double sampleZ = deltaZ / seconds;
                momentumX = clampMomentum(momentumX * 0.35 + sampleX * 0.65);
                momentumZ = clampMomentum(momentumZ * 0.35 + sampleZ * 0.65);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private static double clampMomentum(double value) {
        return Math.max(-MAX_MOMENTUM_BLOCKS_PER_SECOND,
                Math.min(MAX_MOMENTUM_BLOCKS_PER_SECOND, value));
    }

    private float getMinimumStableScale() {
        return MapConfig.MINIMUM_ZOOM_SCALE;
    }

    private void clampScaleForCurrentMode() {
        float minimum = getMinimumStableScale();
        if (this.scale >= minimum) return;
        this.scale = minimum;
        this.currentRenderScale = Math.max(this.currentRenderScale, minimum);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!MapManager.getInstance().hasLearnedMap()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        markViewportInteraction();
        cancelMotionAndOpenAnimation();
        float oldScale = this.scale;

        float minimumScale = getMinimumStableScale();
        this.scale = Math.max(minimumScale,
                Math.min(12.0f, this.scale + (float) scrollY * 0.15f * this.scale));

        // Viewport centre (viewport starts at x=1, y=1)
        double vpCentreX = 1 + (this.width - 2) / 2.0;
        double vpCentreZ = 1 + (this.height - 2) / 2.0;

        // Adjust camera center so that zoom focuses on the mouse cursor location
        double mouseWorldX = this.centerX + (mouseX - vpCentreX) / oldScale;
        double mouseWorldZ = this.centerZ + (mouseY - vpCentreZ) / oldScale;

        this.centerX = mouseWorldX - (mouseX - vpCentreX) / this.scale;
        this.centerZ = mouseWorldZ - (mouseY - vpCentreZ) / this.scale;

        return true;
    }

    private final class CaveLayerSlider extends AbstractSliderButton {
        private static final double AUTO_ZONE = 0.045;
        private int minimumY;
        private int maximumY;
        private boolean dragging;

        private CaveLayerSlider(int x, int y, int width, int height,
                int minimumY, int maximumY, double initialValue) {
            super(x, y, width, height, Component.empty(), initialValue);
            this.minimumY = minimumY;
            this.maximumY = maximumY;
            updateMessage();
        }

        private void syncFromMode() {
            this.value = CaveMode.hasManualTopY(MapScreen.this.minecraft)
                    ? normalizeNumeric(CaveMode.getSelectedTopY(MapScreen.this.minecraft), minimumY, maximumY)
                    : 0.0;
            updateMessage();
        }

        private void syncBoundsFromDimension() {
            DimensionMapProfile profile = MapManager.getInstance()
                    .getCurrentDimensionProfile();
            if (profile != null) {
                this.minimumY = profile.minY();
                this.maximumY = profile.maxY();
            } else if (MapScreen.this.minecraft != null
                    && MapScreen.this.minecraft.level != null) {
                this.minimumY = MapScreen.this.minecraft.level.getMinBuildHeight();
                this.maximumY = MapScreen.this.minecraft.level.getMaxBuildHeight() - 1;
            }
            syncFromMode();
        }

        private boolean isDragging() {
            return dragging;
        }

        private int selectedY() {
            double numericValue = Math.max(0.0, Math.min(1.0,
                    (this.value - AUTO_ZONE) / (1.0 - AUTO_ZONE)));
            return minimumY + (int) Math.round(numericValue * (maximumY - minimumY));
        }

        private boolean isAutoSelection() {
            return this.value <= AUTO_ZONE * 0.55;
        }

        @Override
        protected void updateMessage() {
            String label;
            if (isAutoSelection()) label = dragging ? "Preview: AUTO" : "Top Y: AUTO";
            else label = (dragging ? "Preview Y: " : "Top Y: ") + selectedY();
            setMessage(Component.literal(label));
        }

        @Override
        protected void applyValue() {
            // Mouse dragging only previews the selected Y range. Committing here
            // would swap caches and start a new vertical scan for every mouse pixel.
            if (dragging) return;
            commitLayer();
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            MapScreen.this.markViewportInteraction();
            dragging = true;
            super.onClick(mouseX, mouseY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            boolean shouldCommit = dragging;
            dragging = false;
            if (shouldCommit) commitLayer();
            super.onRelease(mouseX, mouseY);
        }

        private void commitLayer() {
            MapScreen.this.markViewportInteraction();
            if (isAutoSelection()) {
                this.value = 0.0;
                CaveMode.setAutoTopY(MapScreen.this.minecraft);
            } else {
                CaveMode.setManualLayer(MapScreen.this.minecraft, selectedY());
            }
            // Activate and stream the selected cache. Existing exact-layer data is
            // preserved; only a genuinely uncached player region receives a small
            // live prime, avoiding a circular rescan that overwrote warm pixels.
            ChunkScanner.getInstance().requestImmediateCaveLayerRefresh(MapScreen.this.minecraft);
            updateMessage();
            if (MapScreen.this.caveLayerModeButton != null) {
                MapScreen.this.updateCaveTooltip();
            }
        }

        private static double normalizeNumeric(int layerY, int minimumY, int maximumY) {
            if (maximumY <= minimumY) return 0.0;
            int clamped = Math.max(minimumY, Math.min(maximumY, layerY));
            double numeric = (double) (clamped - minimumY) / (maximumY - minimumY);
            return AUTO_ZONE + numeric * (1.0 - AUTO_ZONE);
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && !"LIVE".equals(selectedDimension)) {
            MapManager.getInstance().returnToLiveDimension(this.minecraft);
            selectedDimension = "LIVE";
        }
        MapViewportCoordinator.getInstance().closeFullscreen();
        FullscreenMapFramebufferRenderer.getInstance().destroy();
        super.onClose();
    }

    @Override
    public void removed() {
        if (temporaryVsyncDisabled && this.minecraft != null) {
            this.minecraft.getWindow().updateVsync(true);
            temporaryVsyncDisabled = false;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause game in singleplayer
    }
}
