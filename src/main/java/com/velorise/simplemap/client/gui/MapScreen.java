package com.velorise.simplemap.client.gui;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.client.color.SurfaceColorizer.MapVisualClassifier;
import com.velorise.simplemap.client.MapManager.MapConfig;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager.Waypoint;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.renderer.*;
import com.velorise.simplemap.client.color.*;
import com.velorise.simplemap.client.color.SurfaceColorizer.MapColorProfile;
import com.velorise.simplemap.client.*;
import com.velorise.simplemap.client.MinimapRenderer.MinimapPosition;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.velorise.simplemap.ServerConfig;
import com.velorise.simplemap.network.NetworkHandler;
import com.velorise.simplemap.network.payload.SyncConfigPayload;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import java.util.Comparator;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.LinkedHashSet;
import com.velorise.simplemap.client.MapWorkScheduler.MapProcessor;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager.Waypoint;
import com.velorise.simplemap.client.MapDebugRecorder.ExactPageState;
import com.velorise.simplemap.client.MapViewportCoordinator.MapSurfaceDemandPolicy;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.renderer.*;
import com.velorise.simplemap.client.color.*;
import com.velorise.simplemap.SimpleMap;
import com.velorise.simplemap.client.*;
import com.velorise.simplemap.client.MapManager.DimensionMapProfile;

import com.velorise.simplemap.client.MapManager.MapTeleportController;
import com.velorise.simplemap.client.waypoint.*;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager.CaveStateClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
        super(Component.translatable("gui.simplemap.world_map"));
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
                this.minecraft.setScreen(new MapConfigScreen.WaypointListScreen(this));
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
                if (CaveMode.isActive(this.minecraft)) {
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
                FullscreenMapFramebufferRenderer.getInstance().invalidate();
                FullscreenMapFramebufferRenderer.getInstance().resetFailureState();
                MapRenderer.getInstance().clearFullscreenFallback();
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
            // Derives cross-dimension map coordinates from each target
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
        // Keeps the cave-start AUTO/manual control available for both
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

        // Thin 2-pixel border at screen edges (clean 2-pixel margin)
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
                        Component.translatable("gui.simplemap.copy_coords"),
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
        // MapScreen.scale is expressed in logical GUI pixels per block. the 
        // fullscreen label is effectively physical framebuffer pixels per block on
        // a 1080p display. Printing the logical value made 0.262x in Simple Map look
        // comparable to 0.262x in world units even though GUI scale 3 renders it at 0.786
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
            int targetLayer = CaveMode.isFullView(this.minecraft)
                    ? Integer.MIN_VALUE
                    : CaveMode.getLayerY(this.minecraft);
            int cachedY = CaveMapManager.getInstance().getSurfaceY(targetLayer, bx, bz);
            if (cachedY != CaveMapManager.NO_SURFACE) {
                return new BlockInfo("", "", cachedY, Integer.MIN_VALUE, true);
            }
            return null;
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
        boolean scanFromWorldTop = CaveMode.shouldScanFromWorldTop(level);
        int minBuildHeight = level.getMinBuildHeight();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos(bx, 0, bz);

        if (CaveMode.isActive(this.minecraft)) {
            boolean fullView = CaveMode.isFullView(this.minecraft);
            int layerY = fullView ? Integer.MIN_VALUE : CaveMode.getLayerY(this.minecraft);
            int surfaceY = CaveMapManager.getInstance().getSurfaceY(layerY, bx, bz);
            if (surfaceY != CaveMapManager.NO_SURFACE) {
                // Check surfaceY + 1 first in case a thin floor block (carpet/rail) adjusted height by -1
                if (surfaceY + 1 < level.getMaxBuildHeight()) {
                    pos.setY(surfaceY + 1);
                    net.minecraft.world.level.block.state.BlockState aboveState = level.getBlockState(pos);
                    if (!aboveState.isAir()
                            && !CaveMode.isCaveOpenOrInvisible(aboveState, MapConfig.displayFlowers)
                            && CaveMode.isShortFloorBlock(level, pos, aboveState)) {
                        return createBlockInfo(level, pos, surfaceY + 1);
                    }
                }
                pos.setY(surfaceY);
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    return createBlockInfo(level, pos, surfaceY);
                }
                return new BlockInfo("", "", surfaceY, Integer.MIN_VALUE, true);
            }
            if (fullView) {
                return new BlockInfo("", "", minBuildHeight);
            }
            int scanMinimum = CaveMode.getScanMinimum(level, layerY);
            int scanMaximum = CaveMode.getScanMaximum(level, layerY);
            boolean underair = false;
            for (int y = scanMaximum; y >= scanMinimum; y--) {
                pos.setY(y);
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (!underair) {
                    if (CaveMode.isCaveOpenOrInvisible(state, MapConfig.displayFlowers)) {
                        underair = true;
                    }
                    continue;
                }
                if (CaveMode.isCaveOpenOrInvisible(state, MapConfig.displayFlowers)) {
                    continue;
                }
                return createBlockInfo(level, pos, y);
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
        guiGraphics.drawString(this.font, Component.translatable("gui.simplemap.teleport_here"), (int)popupX + 6, (int)popupY + 26, canTeleport ? 0xFFFFFF : 0x777777, false);

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
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F8
                || SimpleMap.ClientModEvents.TOGGLE_DEBUG_OVERLAY_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.TOGGLE_DEBUG_OVERLAY_KEY);
            MapDebugRecorder.getInstance().toggleOverlay();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F9
                || SimpleMap.ClientModEvents.TOGGLE_DEBUG_RECORDING_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.TOGGLE_DEBUG_RECORDING_KEY);
            MapDebugRecorder.getInstance().toggleRecording(this.minecraft);
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_F10
                || SimpleMap.ClientModEvents.MARK_DEBUG_ISSUE_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.MARK_DEBUG_ISSUE_KEY);
            MapDebugRecorder.getInstance().markIssue(this.minecraft);
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
                player.sendSystemMessage(Component.translatable("gui.simplemap.coords_copied"));

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
                            this.minecraft.setScreen(new MapConfigScreen.WaypointListScreen.AddWaypointScreen(this,
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
                    WaypointManager.shareLocation(this.minecraft, null,
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
        double guiScale = this.minecraft == null || this.minecraft.getWindow() == null
                ? 1.0D : Math.max(1.0D, this.minecraft.getWindow().getGuiScale());
        return Math.max(0.015625f, (float) (0.0625D / guiScale));
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
            boolean fullCave = CaveMode.getCaveType(Minecraft.getInstance()) == CaveMode.CaveType.FULL;
            String label;
            if (fullCave) {
                if (isAutoSelection()) label = dragging ? "Full Cave (AUTO)" : "Full Cave: AUTO";
                else label = (dragging ? "Full Cave (Y: " : "Full Cave (Top Y: ") + selectedY() + ")";
            } else {
                if (isAutoSelection()) label = dragging ? "Preview: AUTO" : "Top Y: AUTO";
                else label = (dragging ? "Preview Y: " : "Top Y: ") + selectedY();
            }
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
        protected void onDrag(double mouseX, double mouseY, double dragX, double dragY) {
            MapScreen.this.markViewportInteraction();
            super.onDrag(mouseX, mouseY, dragX, dragY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            boolean shouldCommit = dragging;
            dragging = false;
            if (shouldCommit) commitLayer();
            super.onRelease(mouseX, mouseY);
        }

        private void commitLayer() {
            MapScreen.this.lastViewportInteractionNanos = 0L;
            MapPerformanceGovernor.getInstance().setFullscreenState(true, false);
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
            FullscreenMapFramebufferRenderer.getInstance().invalidate();
            FullscreenMapFramebufferRenderer.getInstance().resetFailureState();
            MapRenderer.getInstance().clearFullscreenFallback();
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

    public static final class MapUiIcons {
        private static final net.minecraft.resources.ResourceLocation ATLAS =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("simplemap", "gui/gui.png");
        private static final int ATLAS_SIZE = 256;

        public enum Icon {
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

            final int u, v, width, height;

            Icon(int u, int v, int width, int height) {
                this.u = u;
                this.v = v;
                this.width = width;
                this.height = height;
            }
        }

        private MapUiIcons() { }

        public static void draw(net.minecraft.client.gui.GuiGraphics graphics, Icon icon,
                int centerX, int centerY, boolean enabled) {
            drawScaled(graphics, icon, centerX, centerY, enabled, 1.0f);
        }

        public static void drawScaled(net.minecraft.client.gui.GuiGraphics graphics, Icon icon,
                int centerX, int centerY, boolean enabled, float scale) {
            float safeScale = Math.max(0.25f, Math.min(2.0f, scale));
            int drawWidth = Math.max(1, Math.round(icon.width * safeScale));
            int drawHeight = Math.max(1, Math.round(icon.height * safeScale));
            float alpha = enabled ? 1.0f : 0.42f;
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
            graphics.blit(ATLAS,
                    centerX - drawWidth / 2, centerY - drawHeight / 2,
                    drawWidth, drawHeight,
                    (float) icon.u, (float) icon.v,
                    icon.width, icon.height, ATLAS_SIZE, ATLAS_SIZE);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    /** Compact live view of the same immutable samples written to metrics.csv. */
    public static final class MapDebugOverlay {
        private MapDebugOverlay() { }

        public static void render(GuiGraphics graphics) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (!recorder.isOverlayVisible()) return;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.level == null || minecraft.player == null) return;
            MapDebugRecorder.DebugSnapshot s = recorder.lastSnapshot();
            if (s.timestampMs() == 0L) return;

            List<Line> lines = new ArrayList<>();
            String state = recorder.isRecording() ? (s.burst() ? "REC BURST" : "REC") : "PAUSED";
            lines.add(new Line("SimpleMap Debug  " + state + "  F8:View F9:Log F10:Mark", 0xFFFFFFFF));
            String tpsText = s.serverTps() < 0.0 ? "n/a" : String.format(Locale.ROOT, "%.1f (%.1f ms)", s.serverTps(), s.serverMspt());
            lines.add(new Line(String.format(Locale.ROOT,
                    "FPS %d  TPS %s  frame %.1f ms  CPU %.0f%%",
                    s.fps(), tpsText, s.frameAverageMs(), s.processCpuPercent()),
                    s.governorPressure() ? 0xFFFFB45C : 0xFFD8D8D8));
            String gpuText = s.gpuUtilizationPercent() < 0.0
                    ? "n/a" : String.format(Locale.ROOT, "%.0f%%",
                    s.gpuUtilizationPercent());
            lines.add(new Line(String.format(Locale.ROOT,
                    "Heap %.0f/%.0f MiB  GC +%d / +%d ms  GPU %s",
                    s.heapUsedMiB(), s.heapMaxMiB(), s.gcCountDelta(),
                    s.gcTimeMsDelta(), gpuText), 0xFFD8D8D8));
            lines.add(new Line(String.format(Locale.ROOT,
                    "Pos [%.0f, %.0f, %.0f]  Dim %s",
                    s.playerX(), s.playerY(), s.playerZ(), s.dimension()),
                    0xFFC9E6FF));
            lines.add(new Line(String.format(Locale.ROOT,
                    "%s %s  center %.0f,%.0f  zoom %.3fx",
                    s.screen(), s.mapMode(), s.mapCenterX(), s.mapCenterZ(), s.mapScale()),
                    0xFFC9E6FF));

            var p = s.pipeline();
            boolean outputBroken = p.exactBuildQueued() > 0L
                    && p.exactGpuReady() == 0L;
            lines.add(new Line("Exact q/c/discard/gpu/draw "
                    + p.exactBuildQueued() + '/' + p.exactBuildCompleted() + '/'
                    + p.exactBuildDiscarded() + '/' + p.exactGpuReady() + '/'
                    + p.exactPagesDrawn(), outputBroken ? 0xFFFF7676 : 0xFFD8D8D8));
            var render = s.render();
            lines.add(new Line("Current " + render.projection() + " L"
                    + render.hierarchyLevel() + " exact/branch/legacy "
                    + render.exactPages() + '/' + render.branchNodes() + '/'
                    + render.legacyFallbacks() + " content " + bool(render.hadContent()),
                    render.hadContent() ? 0xFFD8D8D8 : 0xFFFF7676));
            var gpu = s.gpu();
            var fullscreenFbo = FullscreenMapFramebufferRenderer.getInstance().snapshot();
            lines.add(new Line(String.format(Locale.ROOT,
                    "Branch GPU %.3f ms deny/bootstrap %d/%d  Full FBO draw/reuse/coalesce/fallback %d/%d/%d/%d",
                    gpu.branchPredictionNanos() / 1_000_000.0,
                    gpu.branchDeniedReservations(), gpu.branchBootstrapAdmissions(),
                    fullscreenFbo.redrawFrames(), fullscreenFbo.reuseFrames(),
                    fullscreenFbo.coalescedFrames(), fullscreenFbo.fallbackFrames()),
                    gpu.branchBootstrapAdmissions() > 0L || render.branchNodes() > 0
                            ? 0xFFB9D7B0 : 0xFFFFB45C));
            var minimapFbo = MinimapFramebufferRenderer.getInstance().snapshot();
            lines.add(new Line(String.format(Locale.ROOT,
                    "Minimap FBO redraw/reuse/coalesce/fallback %d/%d/%d/%d  realloc %d",
                    minimapFbo.redrawFrames(), minimapFbo.reuseFrames(),
                    minimapFbo.coalescedFrames(), minimapFbo.fallbackFrames(),
                    minimapFbo.reallocations()),
                    minimapFbo.disabled() || minimapFbo.fallbackFrames() > 0L
                            ? 0xFFFFB45C : 0xFFB9D7B0));
            var demand = MapSurfaceDemandPolicy.snapshot();
            lines.add(new Line(String.format(Locale.ROOT,
                    "Surface demand area %.0f%% trim L/R/V %.0f/%.0f/%.0f%% exactWindow %d",
                    demand.areaRatio() * 100.0, demand.leftFraction() * 100.0,
                    demand.rightFraction() * 100.0, demand.verticalFraction() * 100.0,
                    demand.exactActiveWindow()), demand.trimmed()
                            ? 0xFFB9D7B0 : 0xFFD8D8D8));
            lines.add(new Line("Source present/absent/defer/fail "
                    + p.sourcePresent() + '/' + p.sourceAbsent() + '/'
                    + p.sourceDeferred() + '/' + p.sourceFailed()
                    + "  noContent " + p.noContentRenderPasses(),
                    p.sourceFailed() > 0L ? 0xFFFF7676 : 0xFFD8D8D8));

            var scheduler = s.scheduler();
            lines.add(new Line("Work CPU " + scheduler.cpuActive() + '/'
                    + scheduler.cpuQueued() + " cost " + scheduler.cpuTotalCost()
                    + "  IO " + scheduler.ioActive() + '/' + scheduler.ioQueued()
                    + " cost " + scheduler.ioTotalCost(), 0xFFD8D8D8));

            var texture = s.texture();
            lines.add(new Line("Texture pages " + texture.initializedPages() + '/'
                    + texture.pages() + " running/ready " + texture.pendingPages()
                    + '/' + texture.completedPendingPages() + " dirty "
                    + texture.dirtyPages() + " batches " + texture.pendingBatches(),
                    texture.pendingBatches() == 0 && texture.dirtyPages() > 0
                            ? 0xFFFFB45C : 0xFFD8D8D8));

            var source = s.sourceDb();
            boolean falseReady = source.lastPipelineReady() && !source.lastStrictReady();
            lines.add(new Line("SourceDB regions/chunks/dirty/views " + source.regions()
                    + '/' + source.residentChunks() + '/' + source.dirtyChunks()
                    + '/' + source.pinnedViews() + " plans 1x/expanded "
                    + source.focusedBatchPlans() + '/' + source.expandedBatchPlans(),
                    0xFFD8D8D8));
            lines.add(new Line("Last batch req/present/missing/dirty "
                    + source.lastRequiredChunks() + '/' + source.lastPresentChunks() + '/'
                    + source.lastMissingChunks() + '/' + source.lastDirtyChunks()
                    + " ready " + bool(source.lastPipelineReady()) + '/'
                    + bool(source.lastStrictReady()),
                    falseReady || source.lastMissingChunks() > 0 ? 0xFFFF7676 : 0xFFD8D8D8));

            var cave = s.cave();
            lines.add(new Line("Cave pages/init/partial/resident " + cave.pages() + '/'
                    + cave.initializedPages() + '/' + cave.partialPages() + '/'
                    + cave.residentPages() + " req/pending/done " + cave.requests() + '/'
                    + cave.pendingBuilds() + '/' + cave.completedBuilds(),
                    cave.pendingBuilds() > 0 && cave.completedBuilds() > 0
                            ? 0xFFFFB45C : 0xFFD8D8D8));

            var exact = s.exactStates();
            long requested = exact.pagesByState().getOrDefault(ExactPageState.REQUESTED, 0L);
            long building = exact.pagesByState().getOrDefault(ExactPageState.BUILDING, 0L);
            long upload = exact.pagesByState().getOrDefault(ExactPageState.UPLOAD_QUEUED, 0L);
            long gpuReady = exact.pagesByState().getOrDefault(ExactPageState.GPU_READY, 0L);
            long evicted = exact.pagesByState().getOrDefault(ExactPageState.GPU_EVICTED, 0L);
            long failed = exact.pagesByState().getOrDefault(ExactPageState.FAILED_RETRYABLE, 0L);
            long stale = exact.pagesByState().getOrDefault(ExactPageState.STALE_GENERATION, 0L);
            lines.add(new Line("Page state req/build/upload/gpu/evict/fail/stale "
                    + requested + '/' + building + '/' + upload + '/' + gpuReady + '/'
                    + evicted + '/' + failed + '/' + stale + " old>5s "
                    + exact.requestedOlderThan5s() + '/'
                    + exact.buildingOlderThan5s() + '/'
                    + exact.cpuReadyOlderThan5s(),
                    failed > 0 || stale > 0 || exact.buildingOlderThan5s() > 0
                            ? 0xFFFF7676 : 0xFFD8D8D8));

            var graph = s.workGraph();
            lines.add(new Line("Graph regions D/R/P/ready/pub " + graph.regions() + ' '
                    + graph.dirtyStages() + '/' + graph.runningStages() + '/'
                    + graph.preparedStages() + '/' + graph.readyStages() + '/'
                    + graph.publishedStages(), 0xFFD8D8D8));

            var lod = s.lodGraph();
            lines.add(new Line("M4 graph nodes/L0/coarse D/R/P/pub " + lod.nodes()
                    + '/' + lod.level0Nodes() + '/' + lod.coarseNodes() + ' '
                    + lod.dirty() + '/' + lod.running() + '/' + lod.prepared()
                    + '/' + lod.published(), 0xFFD8D8D8));

            var architecture = s.architecture();
            if (architecture != null) {
                var pageTable = architecture.pageTable();
                var uploads = architecture.uploads();
                lines.add(new Line("M5 page table entries/storage/gen/swap/mismatch "
                        + pageTable.entries() + '/' + pageTable.storages() + '/'
                        + pageTable.generation() + '/' + pageTable.swaps() + '/'
                        + pageTable.generationMismatches(),
                        pageTable.generationMismatches() > 0L
                                ? 0xFFFFB45C : 0xFFD8D8D8));
                lines.add(new Line("M9 upload q/run/commit/reject/stale "
                        + uploads.queued() + '/' + uploads.inFlight() + '/'
                        + uploads.committed() + '/' + uploads.rejected() + '/'
                        + uploads.stale() + " staging "
                        + String.format(Locale.ROOT, "%.1f MiB",
                                uploads.stagingBytes() / 1048576.0),
                        uploads.rejected() > 0L || uploads.stale() > 0L
                                ? 0xFFFFB45C : 0xFFD8D8D8));
                var persistence = architecture.persistence();
                lines.add(new Line("Persist q/ok/fail " + persistence.queued() + '/'
                        + (persistence.surfaceCommitted() + persistence.caveCommitted())
                        + '/' + persistence.failures(),
                        persistence.failures() > 0L ? 0xFFFF7676 : 0xFFD8D8D8));
            }

            var residency = s.residency();
            lines.add(new Line(String.format(Locale.ROOT,
                    "Residency %d pinned %d  %.1f/%.1f MiB  evict/fail %d/%d",
                    residency.residentEntries(), residency.pinnedEntries(),
                    residency.estimatedBytes() / 1048576.0,
                    residency.budgetBytes() / 1048576.0,
                    residency.globalEvictions(), residency.budgetFailures()),
                    residency.budgetFailures() > 0L ? 0xFFFF7676 : 0xFFD8D8D8));

            Path directory = recorder.activeDirectory();
            if (directory != null) {
                lines.add(new Line("File: simplemap-debug/" + directory.getFileName(),
                        0xFFB9D7B0));
            }

            int lineHeight = minecraft.font.lineHeight + 1;
            int maxWidth = 0;
            for (Line line : lines) maxWidth = Math.max(maxWidth, minecraft.font.width(line.text()));
            int right = graphics.guiWidth() - 5;
            int top = 5;
            int left = Math.max(4, right - maxWidth - 8);
            int bottom = top + lines.size() * lineHeight + 6;
            graphics.fill(left, top, right, bottom, 0xC0101010);
            graphics.renderOutline(left, top, right - left, bottom - top, 0x806A6A6A);
            int y = top + 3;
            for (Line line : lines) {
                graphics.drawString(minecraft.font, line.text(), right - 4
                        - minecraft.font.width(line.text()), y, line.color(), false);
                y += lineHeight;
            }
        }

        private static String bool(boolean value) {
            return value ? "Y" : "N";
        }

        private record Line(String text, int color) { }
    }

    public static class MapConfigScreen extends Screen {
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
        super(Component.translatable("gui.simplemap.settings_title"));
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
                            if (MapConfig.localCaveMapMode == 0) {
                                CaveMapManager.getInstance().deactivate();
                            }
                            MapConfig.save();
                            button.setMessage(getServerCaveModeMessage(true, false));
                            return;
                        }
                        ServerConfig.caveMapMode = (ServerConfig.caveMapMode + 1) % 3;
                        MapConfig.serverCaveMapMode = ServerConfig.caveMapMode;
                        if (ServerConfig.caveMapMode == 0) {
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
                    Component.translatable("gui.simplemap.done"),
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
                .tooltip(Tooltip.create(Component.translatable("gui.simplemap.tab.minimap")))
                .build();
        this.addRenderableWidget(tabMapButton);

        tabCoordsButton = Button.builder(
                Component.literal(activeTab == 1 ? "§6§lCoords" : "Coords"),
                button -> {
                    activeTab = 1;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX + tabW + tabSpacing, tabY, tabW, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.simplemap.tab.coords")))
                .build();
        this.addRenderableWidget(tabCoordsButton);

        tabColorsButton = Button.builder(
                Component.literal(activeTab == 2 ? "§6§lColors" : "Colors"),
                button -> {
                    activeTab = 2;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX + (tabW + tabSpacing) * 2, tabY, tabW, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.simplemap.tab.colors")))
                .build();
        this.addRenderableWidget(tabColorsButton);

        tabSystemButton = Button.builder(
                Component.literal(activeTab == 3 ? "§6§lSystem" : "System"),
                button -> {
                    activeTab = 3;
                    updateTabButtons();
                    updateWidgetVisibility();
                }).bounds(startX + (tabW + tabSpacing) * 3, tabY, tabW, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.simplemap.tab.system")))
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

        double initialZoomVal = (MapConfig.minimapZoom - MapConfig.MINIMUM_ZOOM_SCALE)
                / (2.0 - MapConfig.MINIMUM_ZOOM_SCALE);
        minimapZoomSlider = new SimpleSlider(
                col2X, 77, colW, 20,
                initialZoomVal,
                val -> String.format(java.util.Locale.ROOT, "%.2fx",
                        MapConfig.MINIMUM_ZOOM_SCALE + val * (2.0 - MapConfig.MINIMUM_ZOOM_SCALE)),
                val -> MapConfig.minimapZoom = (float) (MapConfig.MINIMUM_ZOOM_SCALE
                        + val * (2.0 - MapConfig.MINIMUM_ZOOM_SCALE)));
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
        sharedColorInput.setValue(MapColorProfile.formatColor(getSelectedTargetColor()));
        sharedColorInput.setHint(Component.literal("#RRGGBB"));
        sharedColorInput.setResponder(this::previewSharedColor);
        sharedColorInput.setTooltip(Tooltip.create(Component.literal(
                "Enter #RRGGBB or #AARRGGBB. Valid input previews immediately.")));
        this.addRenderableWidget(sharedColorInput);

        savePaletteColorButton = Button.builder(
                Component.translatable("gui.simplemap.save"),
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
                    MapSessionManager.getInstance().bumpStyleGeneration();
                    MapTextureManager.getInstance().invalidateStyle();
                    CaveTextureManager.getInstance().invalidateStyle();
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
                Component.translatable("gui.simplemap.restore_defaults"),
                button -> openResetConfirmation())
                .bounds(col1X, 202, gridWidth, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Restore HUD, color, performance and waypoint preferences."
                                + "\nA confirmation screen appears first."
                                + "\nWaypoints, explored map data and block overrides are kept.")))
                .build();
        this.addRenderableWidget(resetToDefaultButton);

        reloadAllRegionsButton = Button.builder(
                Component.translatable("gui.simplemap.reload_regions"),
                button -> {
                    MapManager.getInstance().reloadAllRegions();
                    button.setMessage(Component.literal("Reloading..."));
                }).bounds(col2X, 102, colW, 20)
                .tooltip(Tooltip.create(Component.literal(
                        "Re-color and reload all saved map regions in the background.\n"
                        + "The current image stays visible until each replacement is ready.")))
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
                Component.translatable("gui.simplemap.done"),
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
                Component.literal("Restore"), Component.translatable("gui.simplemap.cancel")));
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
        MapSessionManager.getInstance().bumpStyleGeneration();
        MapTextureManager.getInstance().invalidateStyle();
        CaveTextureManager.getInstance().invalidateStyle();
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
            sharedColorInput.setValue(MapColorProfile.formatColor(getSelectedTargetColor()));
            sharedColorInput.setTextColor(0xFFFFFFFF);
        }
        if (presetColorButton != null) presetColorButton.setMessage(getSelectedPresetMessage());
    }

    private void previewSharedColor(String value) {
        try {
            setSelectedTargetColor(MapColorProfile.parseColor(value));
            sharedColorInput.setTextColor(0xFFFFFFFF);
        } catch (IllegalArgumentException ignored) {
            sharedColorInput.setTextColor(0xFFFF5555);
        }
    }

    private void saveSharedColor() {
        try {
            int color = MapColorProfile.parseColor(sharedColorInput.getValue());
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
        MapSessionManager.getInstance().bumpStyleGeneration();
        ChunkScanner.getInstance().reset();
        if (this.minecraft == null || this.minecraft.player == null) return;
        ChunkScanner.getInstance().requestRefresh(this.minecraft);
        MapTextureManager.getInstance().invalidateStyle();
        CaveTextureManager.getInstance().invalidateStyle();
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
            String formatted = MapColorProfile.formatColor(color);
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

    /** Searchable editor for every per-block custom color override. */
    public static final class BlockColorManagerScreen extends Screen {
        private static final int ROWS = 8;
        private static final int CONTENT_WIDTH = 420;
        private static final int PANEL_PADDING = 8;
        private final Screen parent;
        private final List<java.util.Map.Entry<String, Integer>> filtered = new ArrayList<>();
        private EditBox search;
        private int page;
        private boolean confirmClear;

        public BlockColorManagerScreen(Screen parent) {
            super(Component.translatable("gui.simplemap.block_colors_title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            int left = panelLeft();
            int top = panelTop();
            search = new EditBox(font, left, top + 24, 300, 20, Component.translatable("gui.simplemap.search_blocks"));
            search.setHint(Component.literal("Search registry ID..."));
            search.setResponder(value -> {
                page = 0;
                rebuildRows();
            });
            addRenderableWidget(search);
            addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.done"), b -> closeToParent())
                    .bounds(left + 322, top + 24, 98, 20).build());
            rebuildRows();
        }

        private void rebuildRows() {
            String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
            filtered.clear();
            MapConfig.blockColorOverrides.entrySet().stream()
                    .filter(entry -> query.isEmpty() || entry.getKey().toLowerCase(Locale.ROOT).contains(query))
                    .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                    .forEach(filtered::add);
            int maxPage = Math.max(0, (filtered.size() - 1) / ROWS);
            page = Math.max(0, Math.min(page, maxPage));

            clearWidgets();
            int left = panelLeft();
            int top = panelTop();
            addRenderableWidget(search);
            addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.done"), b -> closeToParent())
                    .bounds(left + 322, top + 24, 98, 20).build());

            int start = page * ROWS;
            for (int row = 0; row < ROWS && start + row < filtered.size(); row++) {
                java.util.Map.Entry<String, Integer> entry = filtered.get(start + row);
                String id = entry.getKey();
                int y = top + 52 + row * 23;
                addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(id))
                        .bounds(left + 306, y, 52, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.reset"), b -> {
                    MapColorProfile.resetBlockOverride(id);
                    rebuildRows();
                }).bounds(left + 364, y, 56, 20).build());
            }

            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                if (page > 0) { page--; rebuildRows(); }
            }).bounds(left, top + 241, 36, 20).build());
            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                if ((page + 1) * ROWS < filtered.size()) { page++; rebuildRows(); }
            }).bounds(left + 42, top + 241, 36, 20).build());
            addRenderableWidget(Button.builder(Component.literal(confirmClear ? "Confirm Clear All" : "Clear All"), b -> {
                if (!confirmClear) {
                    confirmClear = true;
                    rebuildRows();
                } else {
                    confirmClear = false;
                    MapColorProfile.clearAllBlockOverrides();
                    rebuildRows();
                }
            }).bounds(left + 280, top + 241, 140, 20).build());
        }

        private void edit(String blockId) {
            int automatic = MapTextureManager.getInstance().resolveAutomaticBlockColor(blockId);
            if (minecraft != null) minecraft.setScreen(new BlockColorScreen(this, blockId, blockId, 0, 0, automatic));
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(0, 0, width, height, 0xCC080A0C);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int left = panelLeft();
            int top = panelTop();
            graphics.fill(left - PANEL_PADDING, top - PANEL_PADDING, left + CONTENT_WIDTH + PANEL_PADDING, top + 270, 0xF0111215);
            graphics.renderOutline(left - PANEL_PADDING, top - PANEL_PADDING, CONTENT_WIDTH + PANEL_PADDING * 2, 278, 0xFF3B3E44);
            graphics.drawString(font, title, left, top, 0xFFFFFFFF, false);

            int start = page * ROWS;
            for (int row = 0; row < ROWS && start + row < filtered.size(); row++) {
                java.util.Map.Entry<String, Integer> entry = filtered.get(start + row);
                int y = top + 52 + row * 23;
                int color = 0xFF000000 | (entry.getValue() & 0x00FFFFFF);
                graphics.fill(left, y + 2, left + 18, y + 18, color);
                graphics.renderOutline(left, y + 2, 18, 16, 0xFFFFFFFF);
                graphics.drawString(font, entry.getKey(), left + 25, y + 6, 0xFFE0E0E0, false);
            }
            int pages = Math.max(1, (filtered.size() + ROWS - 1) / ROWS);
            graphics.drawString(font, "Overrides: " + filtered.size() + "  Page " + (page + 1) + "/" + pages,
                    left + 88, top + 247, 0xFF9A9A9A, false);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (scrollY > 0 && page > 0) { page--; rebuildRows(); return true; }
            if (scrollY < 0 && (page + 1) * ROWS < filtered.size()) { page++; rebuildRows(); return true; }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        private int panelLeft() {
            return Math.max(10, (width - CONTENT_WIDTH) / 2);
        }

        private int panelTop() {
            return Math.max(10, Math.min(height - 270, height / 2 - 132));
        }

        private void closeToParent() {
            if (minecraft != null) minecraft.setScreen(parent);
        }

        @Override
        public void onClose() { closeToParent(); }

        @Override
        public boolean isPauseScreen() { return false; }

        /** Small editor for a single registry-backed surface-block color override. */
        public static class BlockColorScreen extends Screen {
            private final Screen parent;
            private final String blockId;
            private final String blockName;
            private final int blockX;
            private final int blockZ;
            private final int automaticColor;
            private final List<PaletteSwatch> swatches = new ArrayList<>();
            private EditBox colorInput;

            public BlockColorScreen(Screen parent, String blockId, String blockName,
                    int blockX, int blockZ, int automaticColor) {
                super(Component.literal("Block Color"));
                this.parent = parent;
                this.blockId = blockId;
                this.blockName = blockName;
                this.blockX = blockX;
                this.blockZ = blockZ;
                this.automaticColor = automaticColor;
            }

            @Override
            protected void init() {
                int left = this.width / 2 - 120;
                int top = Math.max(14, this.height / 2 - 90);
                int currentColor = MapConfig.blockColorOverrides.getOrDefault(blockId, automaticColor);

                colorInput = new EditBox(this.font, left, top + 48, 160, 20, Component.literal("Block hex color"));
                colorInput.setMaxLength(9);
                colorInput.setValue(MapColorProfile.formatColor(currentColor));
                colorInput.setHint(Component.literal("#RRGGBB"));
                colorInput.setResponder(value -> {
                    try {
                        MapColorProfile.parseColor(value);
                        colorInput.setTextColor(0xFFFFFFFF);
                    } catch (IllegalArgumentException ignored) {
                        colorInput.setTextColor(0xFFFF5555);
                    }
                });
                addRenderableWidget(colorInput);
                addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.save"), button -> saveOverride())
                        .bounds(left + 166, top + 48, 74, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Automatic"), button -> resetOverride())
                        .bounds(left, top + 128, 116, 20).build());
                addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.cancel"), button -> closeToParent())
                        .bounds(left + 124, top + 128, 116, 20).build());

                swatches.clear();
                for (int i = 0; i < Math.min(8, MapConfig.savedColors.size()); i++) {
                    int x = left + (i % 4) * 60;
                    int y = top + 76 + (i / 4) * 24;
                    PaletteSwatch swatch = new PaletteSwatch(x, y, 56, 18, i, MapConfig.savedColors.get(i));
                    swatches.add(swatch);
                    addRenderableWidget(swatch);
                }
            }

            private void saveOverride() {
                try {
                    int color = MapColorProfile.parseColor(colorInput.getValue());
                    rememberColor(color);
                    MapColorProfile.applyBlockOverride(blockId, color);
                    closeToParent();
                } catch (IllegalArgumentException ignored) {
                    colorInput.setTextColor(0xFFFF5555);
                }
            }

            private void resetOverride() {
                MapColorProfile.resetBlockOverride(blockId);
                closeToParent();
            }

            private void rememberColor(int color) {
                MapConfig.savedColors.remove(Integer.valueOf(color));
                MapConfig.savedColors.add(0, color);
                while (MapConfig.savedColors.size() > 8) {
                    MapConfig.savedColors.remove(MapConfig.savedColors.size() - 1);
                }
            }

            private void closeToParent() {
                if (this.minecraft != null) this.minecraft.setScreen(parent);
            }

            @Override
            public void onClose() {
                closeToParent();
            }

            @Override
            public boolean isPauseScreen() {
                return false;
            }

            @Override
            public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            }

            @Override
            public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                int left = this.width / 2 - 126;
                int top = Math.max(8, this.height / 2 - 96);
                guiGraphics.fill(0, 0, this.width, this.height, 0x88000000);
                guiGraphics.fill(left, top, left + 252, top + 166, 0xF0111215);
                guiGraphics.renderOutline(left, top, 252, 166, 0xFF3B3E44);
                guiGraphics.drawCenteredString(this.font, blockName, this.width / 2, top + 10, 0xFFFFFFFF);
                guiGraphics.drawCenteredString(this.font, blockId, this.width / 2, top + 23, 0xFF9A9A9A);
                guiGraphics.drawString(this.font,
                        MapConfig.savedColors.isEmpty() ? "Enter a color" : "Saved palette",
                        left + 6, top + 63, 0xFF9A9A9A, false);

                int previewColor = automaticColor;
                if (colorInput != null) {
                    try {
                        previewColor = MapColorProfile.parseColor(colorInput.getValue());
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                guiGraphics.fill(left + 218, top + 7, left + 244, top + 33,
                        0xFF000000 | (previewColor & 0x00FFFFFF));
                guiGraphics.renderOutline(left + 217, top + 6, 28, 28, 0xFFFFFFFF);
                super.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            private class PaletteSwatch extends AbstractButton {
                private final int slot;
                private final int color;

                PaletteSwatch(int x, int y, int width, int height, int slot, int color) {
                    super(x, y, width, height, Component.literal("Use " + MapColorProfile.formatColor(color)));
                    this.slot = slot;
                    this.color = color;
                }

                @Override
                public void onPress() {
                    if (Screen.hasShiftDown() && slot < MapConfig.savedColors.size()) {
                        MapConfig.savedColors.remove(slot);
                        MapConfig.save();
                        if (BlockColorScreen.this.minecraft != null) {
                            BlockColorScreen.this.init(BlockColorScreen.this.minecraft,
                                    BlockColorScreen.this.width, BlockColorScreen.this.height);
                        }
                    } else {
                        colorInput.setValue(MapColorProfile.formatColor(color));
                    }
                }

                @Override
                protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
                    int border = isHoveredOrFocused() ? 0xFFFFFFFF : 0xFF55585D;
                    guiGraphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(),
                            0xFF000000 | (color & 0x00FFFFFF));
                    guiGraphics.renderOutline(getX(), getY(), getWidth(), getHeight(), border);
                }

                @Override
                public void updateWidgetNarration(NarrationElementOutput output) {
                    defaultButtonNarrationText(output);
                }
            }
        }
    }


    public static class WaypointListScreen extends Screen {
    private static final String DIM_OVERWORLD = "minecraft:overworld";
    private static final String DIM_NETHER = "minecraft:the_nether";
    private static final String DIM_END = "minecraft:the_end";
    private static final int ROW_HEIGHT = 36;

    private final Screen parent;
    private final List<WaypointManager.Waypoint> filteredWaypoints = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private int selectedTab;
    private double scrollAmount;
    private EditBox searchBox;
    private WaypointManager.Waypoint pendingDelete;
    private long pendingDeleteUntilNanos;

    public WaypointListScreen(Screen parent) {
        super(Component.translatable("gui.simplemap.waypoint_manager_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        tabButtons.clear();
        int centerX = width / 2;

        searchBox = new EditBox(font, centerX - 130, 38, 260, 18,
                Component.translatable("gui.simplemap.search_waypoints"));
        searchBox.setHint(Component.literal("Search name, coordinate or dimension..."));
        searchBox.setResponder(value -> refreshWaypoints());
        addRenderableWidget(searchBox);

        String[] tabs = { "All", "Overworld", "Nether", "End", "Modded" };
        int tabWidth = 72;
        int tabGap = 3;
        int tabStart = centerX - (tabWidth * tabs.length + tabGap * (tabs.length - 1)) / 2;
        for (int index = 0; index < tabs.length; index++) {
            final int tab = index;
            Button button = Button.builder(Component.literal(tabs[index]), b -> setTab(tab))
                    .bounds(tabStart + index * (tabWidth + tabGap), 13, tabWidth, 18).build();
            tabButtons.add(button);
            addRenderableWidget(button);
        }

        int bottomY = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.add_waypoint"), button -> openAddScreen())
                .bounds(centerX - 126, bottomY, 120, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.done"), button -> onClose())
                .bounds(centerX + 6, bottomY, 120, 20).build());

        refreshWaypoints();
        updateTabLabels();
    }

    private void openAddScreen() {
        if (minecraft == null || minecraft.player == null) return;
        minecraft.setScreen(new AddWaypointScreen(this,
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ(),
                MapManager.getInstance().getLiveDimensionResourceId()));
    }

    private void setTab(int tab) {
        selectedTab = tab;
        scrollAmount = 0;
        pendingDelete = null;
        refreshWaypoints();
        updateTabLabels();
    }

    private void updateTabLabels() {
        String[] tabs = { "All", "Overworld", "Nether", "End", "Modded" };
        for (int index = 0; index < tabButtons.size(); index++) {
            tabButtons.get(index).setMessage(Component.literal(
                    index == selectedTab ? "[" + tabs[index] + "]" : tabs[index]));
        }
    }

    private void refreshWaypoints() {
        filteredWaypoints.clear();
        String filter = searchBox == null ? ""
                : searchBox.getValue().trim().toLowerCase(Locale.ROOT);
        for (WaypointManager.Waypoint waypoint : WaypointManager.getInstance().getAllWaypoints()) {
            if (!matchesTab(waypoint)) continue;
            String y = waypoint.hasY ? Integer.toString((int) Math.floor(waypoint.y)) : "";
            String searchable = (waypoint.name + " " + (int) Math.floor(waypoint.x) + " "
                    + y + " " + (int) Math.floor(waypoint.z) + " " + waypoint.dimension)
                    .toLowerCase(Locale.ROOT);
            if (filter.isEmpty() || searchable.contains(filter)) filteredWaypoints.add(waypoint);
        }
        filteredWaypoints.sort(Comparator
                .comparing((WaypointManager.Waypoint waypoint) -> waypoint.dimension)
                .thenComparing(waypoint -> waypoint.name.toLowerCase(Locale.ROOT)));
        clampScroll();
    }

    private boolean matchesTab(WaypointManager.Waypoint waypoint) {
        return switch (selectedTab) {
            case 1 -> isVanillaDimension(waypoint.dimension, "overworld");
            case 2 -> isVanillaDimension(waypoint.dimension, "the_nether");
            case 3 -> isVanillaDimension(waypoint.dimension, "the_end");
            case 4 -> !isVanillaDimension(waypoint.dimension, "overworld")
                    && !isVanillaDimension(waypoint.dimension, "the_nether")
                    && !isVanillaDimension(waypoint.dimension, "the_end");
            default -> true;
        };
    }

    private int listTop() {
        return 63;
    }

    private int listBottom() {
        return height - 35;
    }

    private int listWidth() {
        return Math.min(650, width - 24);
    }

    private int listLeft() {
        return (width - listWidth()) / 2;
    }

    private int maximumScroll() {
        return Math.max(0, filteredWaypoints.size() * ROW_HEIGHT
                - (listBottom() - listTop() - 2));
    }

    private void clampScroll() {
        scrollAmount = Math.max(0, Math.min(maximumScroll(), scrollAmount));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
            double scrollY) {
        if (mouseY >= listTop() && mouseY <= listBottom()) {
            scrollAmount -= scrollY * 22.0;
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || mouseY < listTop() || mouseY > listBottom()) return false;

        int startY = listTop() - (int) scrollAmount;
        for (int index = 0; index < filteredWaypoints.size(); index++) {
            int rowY = startY + index * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < listTop() || rowY > listBottom()) continue;
            if (mouseY < rowY + 7 || mouseY > rowY + 27) continue;

            WaypointManager.Waypoint waypoint = filteredWaypoints.get(index);
            RowActions actions = rowActions();
            if (inside(mouseX, actions.pinX, actions.buttonWidth)) {
                toggleTracked(waypoint);
                return true;
            }
            if (inside(mouseX, actions.editX, actions.buttonWidth)) {
                if (minecraft != null) minecraft.setScreen(new AddWaypointScreen(this, waypoint));
                return true;
            }
            if (inside(mouseX, actions.shareX, actions.shareWidth)) {
                share(waypoint);
                return true;
            }
            if (inside(mouseX, actions.teleportX, actions.smallButtonWidth)) {
                teleport(waypoint);
                return true;
            }
            if (inside(mouseX, actions.deleteX, actions.deleteWidth)) {
                confirmDelete(waypoint);
                return true;
            }
        }
        return false;
    }

    private static boolean inside(double mouseX, int x, int width) {
        return mouseX >= x && mouseX <= x + width;
    }

    private RowActions rowActions() {
        int right = listLeft() + listWidth() - 9;
        int gap = 4;
        int deleteWidth = 48;
        int smallWidth = 30;
        int normalWidth = 44;
        int shareWidth = 42;
        int deleteX = right - deleteWidth;
        int teleportX = deleteX - gap - smallWidth;
        int shareX = teleportX - gap - shareWidth;
        int editX = shareX - gap - normalWidth;
        int pinX = editX - gap - normalWidth;
        return new RowActions(pinX, editX, shareX, teleportX, deleteX,
                normalWidth, shareWidth, smallWidth, deleteWidth);
    }

    private void toggleTracked(WaypointManager.Waypoint waypoint) {
        boolean tracked = isTracked(waypoint);
        if (tracked) {
            PinNavigation.clear();
        } else {
            PinNavigation.activate(waypoint.x, waypoint.z);
        }
        MapManager.getInstance().savePin();
        if (!tracked) openOnMap(waypoint);
    }

    private void openOnMap(WaypointManager.Waypoint waypoint) {
        if (minecraft == null) return;
        MapScreen map = parent instanceof MapScreen parentMap
                ? parentMap : new MapScreen();
        minecraft.setScreen(map);
        map.focusOnWaypoint(waypoint);
    }

    private void share(WaypointManager.Waypoint waypoint) {
        if (parent instanceof MapScreen map) {
            map.onClose();
        } else if (minecraft != null
                && !MapManager.getInstance().isViewingLiveDimension()) {
            MapManager.getInstance().returnToLiveDimension(minecraft);
        }
        WaypointManager.shareLocation(minecraft, waypoint.name,
                waypoint.x, waypoint.y, waypoint.z, waypoint.hasY,
                waypoint.dimension);
    }

    private boolean isTracked(WaypointManager.Waypoint waypoint) {
        return MapConfig.pinActive
                && Math.abs(MapConfig.pinWorldX - waypoint.x) < 0.01
                && Math.abs(MapConfig.pinWorldZ - waypoint.z) < 0.01;
    }

    private void teleport(WaypointManager.Waypoint waypoint) {
        if (minecraft == null) return;
        String targetDimension = MapManager.getInstance()
                .resolveDimensionResourceId(waypoint.dimension);
        int targetY = waypoint.hasY
                ? (int) Math.floor(waypoint.y)
                : MapTeleportController.defaultTargetY(targetDimension);
        if (MapTeleportController.teleport(minecraft, targetDimension,
                (int) Math.floor(waypoint.x), targetY, (int) Math.floor(waypoint.z))) {
            MapManager.getInstance().returnToLiveDimension(minecraft);
            MapViewportCoordinator.getInstance().closeFullscreen();
            minecraft.setScreen(null);
        }
    }

    private void confirmDelete(WaypointManager.Waypoint waypoint) {
        long now = System.nanoTime();
        if (pendingDelete == waypoint && now <= pendingDeleteUntilNanos) {
            WaypointManager.getInstance().removeWaypoint(waypoint);
            pendingDelete = null;
            refreshWaypoints();
            return;
        }
        pendingDelete = waypoint;
        pendingDeleteUntilNanos = now + 3_000_000_000L;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY,
            float partialTick) {
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (pendingDelete != null && System.nanoTime() > pendingDeleteUntilNanos) {
            pendingDelete = null;
        }

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        guiGraphics.fill(0, 0, width, height, 0xF0101010);
        int left = listLeft();
        int top = listTop();
        int bottom = listBottom();
        int listWidth = listWidth();
        guiGraphics.fill(left, top, left + listWidth, bottom, 0xFF191919);
        guiGraphics.renderOutline(left, top, listWidth, bottom - top, 0xFF707070);
        guiGraphics.renderOutline(left + 1, top + 1, listWidth - 2,
                bottom - top - 2, 0xFF080808);

        guiGraphics.enableScissor(left + 2, top + 2, left + listWidth - 2, bottom - 2);
        int startY = top - (int) scrollAmount;
        RowActions actions = rowActions();
        for (int index = 0; index < filteredWaypoints.size(); index++) {
            int rowY = startY + index * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT < top || rowY > bottom) continue;
            WaypointManager.Waypoint waypoint = filteredWaypoints.get(index);
            boolean hover = mouseX >= left && mouseX <= left + listWidth
                    && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT
                    && mouseY >= top && mouseY <= bottom;
            int background = hover ? 0xFF303030 : (index % 2 == 0 ? 0xFF202020 : 0xFF252525);
            guiGraphics.fill(left + 2, rowY + 1, left + listWidth - 2,
                    rowY + ROW_HEIGHT - 1, background);

            renderWaypointIcon(guiGraphics, waypoint, left + 8, rowY + 8);
            String name = waypoint.deathPoint ? waypoint.name + " [Death]" : waypoint.name;
            guiGraphics.drawString(font, name, left + 34, rowY + 6, 0xFFFFFFFF, false);
            String yText = waypoint.hasY ? Integer.toString((int) Math.floor(waypoint.y)) : "—";
            String coordinates = String.format(Locale.ROOT, "X %d  Y %s  Z %d  ·  %s",
                    (int) Math.floor(waypoint.x), yText, (int) Math.floor(waypoint.z),
                    MapManager.displayDimensionName(waypoint.dimension));
            guiGraphics.drawString(font, coordinates, left + 34, rowY + 20,
                    0xFFAAAAAA, false);

            drawCompactButton(guiGraphics, actions.pinX, rowY + 7,
                    actions.buttonWidth, 20, isTracked(waypoint) ? "Stop" : "Follow",
                    mouseX, mouseY);
            drawCompactButton(guiGraphics, actions.editX, rowY + 7,
                    actions.buttonWidth, 20, "Edit", mouseX, mouseY);
            drawCompactButton(guiGraphics, actions.shareX, rowY + 7,
                    actions.shareWidth, 20, "Share", mouseX, mouseY);
            drawCompactButton(guiGraphics, actions.teleportX, rowY + 7,
                    actions.smallButtonWidth, 20, "TP", mouseX, mouseY);
            drawCompactButton(guiGraphics, actions.deleteX, rowY + 7,
                    actions.deleteWidth, 20,
                    pendingDelete == waypoint ? "Confirm" : "Delete", mouseX, mouseY);
        }
        guiGraphics.disableScissor();

        if (filteredWaypoints.isEmpty()) {
            String empty = "No waypoints match this view";
            guiGraphics.drawCenteredString(font, empty, width / 2,
                    top + (bottom - top) / 2 - 4, 0xFFAAAAAA);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderWaypointIcon(GuiGraphics guiGraphics,
            WaypointManager.Waypoint waypoint, int x, int y) {
        guiGraphics.fill(x - 2, y - 2, x + 20, y + 20, 0xFF111111);
        guiGraphics.renderOutline(x - 2, y - 2, 22, 22, 0xFF606060);
        String itemId = WaypointManager.resolveIconItemId(waypoint);
        ResourceLocation location = ResourceLocation.tryParse(itemId);
        Item item = location == null ? Items.COMPASS : BuiltInRegistries.ITEM.get(location);
        if (item == null || item == Items.AIR) item = Items.COMPASS;
        // Every waypoint icon occupies the same 16x16 slot regardless of its map scale.
        guiGraphics.renderFakeItem(new ItemStack(item), x, y);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawCompactButton(GuiGraphics guiGraphics, int x, int y,
            int width, int height, String text, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + width
                && mouseY >= y && mouseY <= y + height;
        int background = hover ? 0xFF555555 : 0xFF343434;
        guiGraphics.fill(x, y, x + width, y + height, background);
        guiGraphics.renderOutline(x, y, width, height,
                hover ? 0xFFFFFFFF : 0xFF707070);
        int textWidth = font.width(text);
        guiGraphics.drawString(font, text, x + (width - textWidth) / 2,
                y + (height - 8) / 2, 0xFFFFFFFF, false);
    }

    private static boolean isVanillaDimension(String dimension, String path) {
        if (dimension == null) return false;
        String normalized = dimension.toLowerCase(Locale.ROOT);
        return normalized.equals(path) || normalized.equals("minecraft:" + path)
                || (path.equals("the_nether") && normalized.equals("nether"))
                || (path.equals("the_end") && normalized.equals("end"));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record RowActions(int pinX, int editX, int shareX,
            int teleportX, int deleteX, int buttonWidth, int shareWidth,
            int smallButtonWidth, int deleteWidth) {
    }

    /**
     * Dedicated create/edit workflow for waypoints.
     */
    public static class AddWaypointScreen extends Screen {
        private static final String[] MINING_ITEMS = {
                "minecraft:iron_pickaxe", "minecraft:iron_axe", "minecraft:minecart",
                "minecraft:raw_iron", "minecraft:diamond", "minecraft:chest"
        };
        private static final String[] COMBAT_ITEMS = {
                "minecraft:iron_sword", "minecraft:bow", "minecraft:shield",
                "minecraft:nether_star", "minecraft:experience_bottle", "minecraft:target"
        };
        private static final String[] TRAVEL_ITEMS = {
                "minecraft:compass", "minecraft:ender_eye", "minecraft:elytra",
                "minecraft:red_bed", "minecraft:recovery_compass", "minecraft:lodestone"
        };
        private static final String[] FOOD_ITEMS = {
                "minecraft:golden_apple", "minecraft:bread", "minecraft:cooked_beef",
                "minecraft:carrot", "minecraft:cake", "minecraft:honey_bottle"
        };

        private final Screen parent;
        private final WaypointManager.Waypoint original;
        private final String previousViewDimension;
        private final boolean previousViewWasLive;
        private final List<String> dimensions = new ArrayList<>();
        private final List<ItemIconButton> iconButtons = new ArrayList<>();
        private final List<ItemIconButton> searchButtons = new ArrayList<>();
        private final List<String> searchResults = new ArrayList<>();
        private final List<Button> tabButtons = new ArrayList<>();

        private double draftX;
        private double draftY;
        private double draftZ;
        private String draftDimension;
        private String draftName;
        private float waypointScale;
        private int selectedIconType = -1;
        private String selectedIconItem;
        private int activeTab;
        private String validationMessage = "";
        private boolean previousViewRestored;

        private EditBox nameInput;
        private EditBox xInput;
        private EditBox yInput;
        private EditBox zInput;
        private EditBox searchBox;
        private Button dimensionButton;
        private SimpleSlider localScaleSlider;

        private int panelLeft;
        private int panelTop;
        private int panelWidth;
        private int panelHeight;

        public AddWaypointScreen(Screen parent, double worldX, double worldZ, String dimension) {
            this(parent, worldX, defaultY(dimension), worldZ, dimension);
        }

        public AddWaypointScreen(Screen parent, double worldX, double worldY, double worldZ,
                String dimension) {
            super(Component.translatable("gui.simplemap.add_waypoint"));
            this.parent = parent;
            this.original = null;
            this.draftX = worldX;
            this.draftY = worldY;
            this.draftZ = worldZ;
            this.draftDimension = normalizeDimension(dimension);
            this.draftName = "";
            this.waypointScale = 1.0f;
            this.selectedIconItem = "minecraft:compass";
            this.previousViewDimension = MapManager.getInstance()
                    .getCurrentDimensionResourceId();
            this.previousViewWasLive = MapManager.getInstance().isViewingLiveDimension();
            rebuildDimensions();
        }

        public AddWaypointScreen(Screen parent, WaypointManager.Waypoint waypoint) {
            super(Component.translatable("gui.simplemap.edit_waypoint"));
            this.parent = parent;
            this.original = waypoint;
            this.draftX = waypoint.x;
            this.draftY = waypoint.hasY ? waypoint.y : defaultY(waypoint.dimension);
            this.draftZ = waypoint.z;
            this.draftDimension = normalizeDimension(waypoint.dimension);
            this.draftName = waypoint.name;
            this.waypointScale = waypoint.scale;
            this.selectedIconType = -1;
            this.selectedIconItem = WaypointManager.resolveIconItemId(waypoint);
            this.previousViewDimension = MapManager.getInstance()
                    .getCurrentDimensionResourceId();
            this.previousViewWasLive = MapManager.getInstance().isViewingLiveDimension();
            rebuildDimensions();
        }

        private static double defaultY(String dimension) {
            Minecraft mc = Minecraft.getInstance();
            String normalized = normalizeDimension(dimension);
            if (mc.player != null && MapManager.getInstance().getLiveDimensionResourceId()
                    .equals(normalized)) {
                return mc.player.getY();
            }
            return MapTeleportController.defaultTargetY(normalized);
        }

        private static String normalizeDimension(String dimension) {
            if (dimension == null || dimension.isBlank()) return "minecraft:overworld";
            return MapManager.getInstance().resolveDimensionResourceId(dimension);
        }

        private void rebuildDimensions() {
            Set<String> ordered = new LinkedHashSet<>();
            ordered.add(draftDimension);
            ordered.add(normalizeDimension(MapManager.getInstance().getCurrentDimensionId()));
            ordered.add(normalizeDimension(MapManager.getInstance().getLiveDimensionResourceId()));
            ordered.add("minecraft:overworld");
            ordered.add("minecraft:the_nether");
            ordered.add("minecraft:the_end");
            for (String dimension : MapManager.getInstance().getSelectableDimensions()) {
                ordered.add(normalizeDimension(dimension));
            }
            for (WaypointManager.Waypoint waypoint : WaypointManager.getInstance().getAllWaypoints()) {
                ordered.add(normalizeDimension(waypoint.dimension));
            }
            dimensions.clear();
            dimensions.addAll(ordered);
        }

        @Override
        protected void init() {
            ensureDraftDimensionViewed();
            iconButtons.clear();
            searchButtons.clear();
            tabButtons.clear();

            panelWidth = Math.max(360, Math.min(570, this.width - 20));
            panelHeight = Math.max(290, Math.min(330, this.height - 20));
            panelLeft = (this.width - panelWidth) / 2;
            panelTop = (this.height - panelHeight) / 2;

            int leftX = panelLeft + 18;
            int rightX = panelLeft + panelWidth / 2 + 6;
            int fieldWidth = panelWidth / 2 - 32;
            int top = panelTop + 38;

            nameInput = new EditBox(this.font, leftX, top + 14, fieldWidth, 20,
                    Component.literal("Waypoint name"));
            nameInput.setMaxLength(64);
            nameInput.setValue(draftName);
            nameInput.setHint(Component.literal(defaultName()));
            addRenderableWidget(nameInput);

            int coordinateY = top + 58;
            int coordinateGap = 5;
            int coordinateWidth = (fieldWidth - coordinateGap * 2) / 3;
            xInput = coordinateBox(leftX, coordinateY, coordinateWidth, draftX, "X coordinate");
            yInput = coordinateBox(leftX + coordinateWidth + coordinateGap, coordinateY,
                    coordinateWidth, draftY, "Y coordinate");
            zInput = coordinateBox(leftX + (coordinateWidth + coordinateGap) * 2,
                    coordinateY, coordinateWidth, draftZ, "Z coordinate");

            dimensionButton = Button.builder(dimensionLabel(), button -> cycleDimension())
                    .bounds(leftX, top + 112, fieldWidth, 20).build();
            addRenderableWidget(dimensionButton);

            int halfButton = (fieldWidth - 5) / 2;
            addRenderableWidget(Button.builder(Component.literal("Use Player Position"), button -> usePlayerPosition())
                    .bounds(leftX, top + 142, halfButton, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Pick on Map"), button -> openMapPicker())
                    .bounds(leftX + halfButton + 5, top + 142, halfButton, 20).build());

            double sliderValue = (waypointScale - 0.5) / 1.5;
            localScaleSlider = new SimpleSlider(leftX, top + 177, fieldWidth, 20,
                    Math.max(0.0, Math.min(1.0, sliderValue)),
                    value -> String.format(Locale.ROOT, "Icon Size: %.1fx", 0.5 + value * 1.5),
                    value -> waypointScale = (float) (0.5 + value * 1.5));
            addRenderableWidget(localScaleSlider);

            String[] tabNames = { "Mining", "Combat", "Travel", "Food", "Search" };
            int tabGap = 2;
            int tabWidth = Math.max(28, (fieldWidth - tabGap * 4) / 5);
            for (int index = 0; index < tabNames.length; index++) {
                final int tab = index;
                Button button = Button.builder(Component.literal(tabNames[index]), b -> setTab(tab))
                        .bounds(rightX + index * (tabWidth + tabGap), top + 14, tabWidth, 18).build();
                tabButtons.add(button);
                addRenderableWidget(button);
            }

            int iconStartX = rightX + 8;
            int iconStartY = top + 48;
            addItemGroupButtons(MINING_ITEMS, 0, iconStartX, iconStartY);
            addItemGroupButtons(COMBAT_ITEMS, 1, iconStartX, iconStartY);
            addItemGroupButtons(TRAVEL_ITEMS, 2, iconStartX, iconStartY);
            addItemGroupButtons(FOOD_ITEMS, 3, iconStartX, iconStartY);

            searchBox = new EditBox(this.font, rightX, iconStartY, fieldWidth, 18,
                    Component.literal("Search waypoint icon"));
            searchBox.setMaxLength(48);
            searchBox.setHint(Component.literal("Search item id..."));
            searchBox.setResponder(value -> updateSearchResults());
            addRenderableWidget(searchBox);

            int searchY = iconStartY + 28;
            for (int index = 0; index < 9; index++) {
                final int resultIndex = index;
                ItemIconButton button = new ItemIconButton(
                        rightX + (index % 5) * 28,
                        searchY + (index / 5) * 28,
                        24, 24, "", b -> {
                            if (resultIndex < searchResults.size()) {
                                selectIcon(searchResults.get(resultIndex));
                            }
                        });
                searchButtons.add(button);
                addRenderableWidget(button);
            }

            int bottomY = panelTop + panelHeight - 30;
            addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.cancel"), button -> onClose())
                    .bounds(panelLeft + panelWidth - 174, bottomY, 76, 20).build());
            addRenderableWidget(Button.builder(Component.literal(original == null ? "Add" : "Save"),
                    button -> saveWaypoint())
                    .bounds(panelLeft + panelWidth - 92, bottomY, 76, 20).build());

            updateTabVisibility();
        }

        private EditBox coordinateBox(int x, int y, int width, double value, String narration) {
            EditBox box = new EditBox(this.font, x, y, width, 20, Component.literal(narration));
            box.setMaxLength(18);
            box.setValue(formatCoordinate(value));
            addRenderableWidget(box);
            return box;
        }

        private static String formatCoordinate(double value) {
            if (Math.abs(value - Math.rint(value)) < 0.0001) {
                return Long.toString(Math.round(value));
            }
            return String.format(Locale.ROOT, "%.2f", value);
        }

        private String defaultName() {
            int count = WaypointManager.getInstance().getWaypointsForDimension(draftDimension).size() + 1;
            return "Waypoint " + count;
        }

        private Component dimensionLabel() {
            return Component.literal("Dimension: " + MapManager.displayDimensionName(draftDimension));
        }

        private void cycleDimension() {
            syncDraftQuietly();
            int index = Math.max(0, dimensions.indexOf(draftDimension));
            draftDimension = dimensions.get((index + 1) % dimensions.size());
            ensureDraftDimensionViewed();
            dimensionButton.setMessage(dimensionLabel());
        }

        private void usePlayerPosition() {
            if (minecraft == null || minecraft.player == null) return;
            draftX = minecraft.player.getX();
            draftY = minecraft.player.getY();
            draftZ = minecraft.player.getZ();
            draftDimension = normalizeDimension(MapManager.getInstance().getLiveDimensionResourceId());
            ensureDraftDimensionViewed();
            ensureDimensionPresent(draftDimension);
            updateCoordinateInputs();
            dimensionButton.setMessage(dimensionLabel());
            validationMessage = "";
        }

        private void openMapPicker() {
            if (!syncDraftFromFields()) return;
            if (minecraft != null) {
                minecraft.setScreen(new WaypointMapPickerScreen(this, draftX, draftZ));
            }
        }

        void acceptPickedLocation(double x, double z, String dimension) {
            draftX = x;
            draftZ = z;
            draftDimension = normalizeDimension(dimension);
            ensureDimensionPresent(draftDimension);
            if (minecraft != null && minecraft.player != null
                    && draftDimension.equals(normalizeDimension(MapManager.getInstance().getLiveDimensionResourceId()))) {
                draftY = minecraft.player.getY();
            }
            validationMessage = "";
        }

        private void ensureDimensionPresent(String dimension) {
            if (!dimensions.contains(dimension)) dimensions.add(0, dimension);
        }

        private void ensureDraftDimensionViewed() {
            if (minecraft == null) return;
            MapManager manager = MapManager.getInstance();
            String target = normalizeDimension(draftDimension);
            if (target.equals(manager.getCurrentDimensionResourceId())) return;
            if (target.equals(manager.getLiveDimensionResourceId())) {
                manager.returnToLiveDimension(minecraft);
            } else {
                manager.switchToDimension(target);
            }
        }

        private void restorePreviousMapView() {
            if (previousViewRestored || minecraft == null) return;
            previousViewRestored = true;
            MapManager manager = MapManager.getInstance();
            if (previousViewWasLive) manager.returnToLiveDimension(minecraft);
            else manager.switchToDimension(previousViewDimension);
        }

        private void updateCoordinateInputs() {
            if (xInput != null) xInput.setValue(formatCoordinate(draftX));
            if (yInput != null) yInput.setValue(formatCoordinate(draftY));
            if (zInput != null) zInput.setValue(formatCoordinate(draftZ));
        }

        private boolean syncDraftFromFields() {
            try {
                draftX = parseCoordinate(xInput.getValue(), "X");
                draftY = parseCoordinate(yInput.getValue(), "Y");
                draftZ = parseCoordinate(zInput.getValue(), "Z");
                draftName = nameInput.getValue().trim();
                validationMessage = "";
                return true;
            } catch (IllegalArgumentException exception) {
                validationMessage = exception.getMessage();
                return false;
            }
        }

        private void syncDraftQuietly() {
            try {
                draftX = Double.parseDouble(xInput.getValue().trim());
                draftY = Double.parseDouble(yInput.getValue().trim());
                draftZ = Double.parseDouble(zInput.getValue().trim());
            } catch (RuntimeException ignored) {
            }
            draftName = nameInput.getValue().trim();
        }

        private static double parseCoordinate(String text, String axis) {
            try {
                double value = Double.parseDouble(text.trim());
                if (!Double.isFinite(value) || Math.abs(value) > 30_000_000.0) {
                    throw new NumberFormatException();
                }
                return value;
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(axis + " must be a valid world coordinate");
            }
        }

        private void saveWaypoint() {
            if (!syncDraftFromFields()) return;
            String name = draftName.isBlank() ? defaultName() : draftName;
            WaypointManager.Waypoint replacement = new WaypointManager.Waypoint(
                    name, draftX, draftY, draftZ, selectedIconType, selectedIconItem,
                    waypointScale, draftDimension, original != null && original.deathPoint);

            boolean trackedOriginal = original != null && MapConfig.pinActive
                    && Math.abs(MapConfig.pinWorldX - original.x) < 0.01
                    && Math.abs(MapConfig.pinWorldZ - original.z) < 0.01;
            if (original == null) {
                WaypointManager.getInstance().addWaypoint(replacement);
            } else if (!WaypointManager.getInstance().updateWaypoint(original, replacement)) {
                validationMessage = "Waypoint no longer exists";
                return;
            }
            if (trackedOriginal) {
                PinNavigation.updateDestination(replacement.x, replacement.z);
                MapManager.getInstance().savePin();
            }
            restorePreviousMapView();
            if (minecraft != null) minecraft.setScreen(parent);
        }

        private void setTab(int tab) {
            activeTab = tab;
            updateTabVisibility();
        }

        private void addItemGroupButtons(String[] items, int tab, int startX, int startY) {
            for (int index = 0; index < items.length; index++) {
                String itemId = items[index];
                ItemIconButton button = new ItemIconButton(
                        startX + (index % 5) * 28,
                        startY + (index / 5) * 28,
                        24, 24, itemId, b -> selectIcon(itemId));
                button.tab = tab;
                iconButtons.add(button);
                addRenderableWidget(button);
            }
        }

        private void selectIcon(String itemId) {
            selectedIconType = -1;
            selectedIconItem = itemId == null || itemId.isBlank() ? "minecraft:compass" : itemId;
        }

        private void updateTabVisibility() {
            for (int index = 0; index < tabButtons.size(); index++) {
                Button button = tabButtons.get(index);
                String plain = switch (index) {
                    case 0 -> "Mining";
                    case 1 -> "Combat";
                    case 2 -> "Travel";
                    case 3 -> "Food";
                    default -> "Search";
                };
                button.setMessage(Component.literal(index == activeTab ? "[" + plain + "]" : plain));
            }
            for (ItemIconButton button : iconButtons) button.visible = button.tab == activeTab;
            boolean searching = activeTab == 4;
            if (searchBox != null) searchBox.visible = searching;
            for (ItemIconButton button : searchButtons) button.visible = false;
            if (searching) updateSearchResults();
        }

        private void updateSearchResults() {
            searchResults.clear();
            if (activeTab != 4 || searchBox == null) return;
            String query = searchBox.getValue().trim().toLowerCase(Locale.ROOT);
            for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
                if (key.getPath().equals("air")) continue;
                String id = key.toString();
                if (query.isEmpty() || id.toLowerCase(Locale.ROOT).contains(query)) {
                    searchResults.add(id);
                    if (searchResults.size() >= searchButtons.size()) break;
                }
            }
            for (int index = 0; index < searchButtons.size(); index++) {
                ItemIconButton button = searchButtons.get(index);
                if (index < searchResults.size()) {
                    button.itemId = searchResults.get(index);
                    button.visible = true;
                } else {
                    button.itemId = "";
                    button.visible = false;
                }
            }
        }

        @Override
        public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY,
                float partialTick) {
        }

        @Override
        public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            syncDraftQuietly();
            float backgroundScale = Math.max(0.28f,
                    Math.min(0.65f, (width - 2) / 1500.0f));
            double markerScreenX = panelLeft >= 90 ? panelLeft / 2.0 : width / 2.0;
            double markerScreenY = height / 2.0;
            double mapCenterX = draftX
                    - (markerScreenX - width / 2.0) / backgroundScale;
            double mapCenterZ = draftZ
                    - (markerScreenY - height / 2.0) / backgroundScale;
            double mouseWorldX = mapCenterX
                    + (mouseX - width / 2.0) / backgroundScale;
            double mouseWorldZ = mapCenterZ
                    + (mouseY - height / 2.0) / backgroundScale;
            MapRenderer.getInstance().drawMap(guiGraphics, 0, 0, width, height,
                    mapCenterX, mapCenterZ, backgroundScale,
                    MapManager.getInstance().isViewingLiveDimension(), false, false,
                    mouseWorldX, mouseWorldZ, partialTick, false);
            guiGraphics.fill(0, 0, width, height, 0x520A0A0A);
            renderScaledItem(guiGraphics, selectedIconItem,
                    (int) Math.round(markerScreenX), (int) Math.round(markerScreenY),
                    0.20f * MapConfig.waypointScale * waypointScale);
            String mapPreview = "Waypoint preview · "
                    + MapManager.displayDimensionName(draftDimension);
            guiGraphics.drawCenteredString(font, mapPreview,
                    (int) Math.round(markerScreenX),
                    (int) Math.round(markerScreenY + 18.0 * waypointScale),
                    0xFFFFFFFF);

            guiGraphics.fill(panelLeft, panelTop, panelLeft + panelWidth,
                    panelTop + panelHeight, 0xF2202020);
            guiGraphics.renderOutline(panelLeft, panelTop, panelWidth, panelHeight, 0xFF707070);
            guiGraphics.renderOutline(panelLeft + 1, panelTop + 1,
                    panelWidth - 2, panelHeight - 2, 0xFF080808);

            int dividerX = panelLeft + panelWidth / 2;
            guiGraphics.fill(dividerX, panelTop + 30, dividerX + 1,
                    panelTop + panelHeight - 38, 0xFF505050);

            guiGraphics.drawCenteredString(font, title, width / 2, panelTop + 11, 0xFFFFFFFF);
            int leftX = panelLeft + 18;
            int rightX = panelLeft + panelWidth / 2 + 6;
            int top = panelTop + 38;
            guiGraphics.drawString(font, "Name", leftX, top + 2, 0xFFBFBFBF, false);
            guiGraphics.drawString(font, "Coordinates", leftX, top + 42, 0xFFBFBFBF, false);
            int fieldWidth = panelWidth / 2 - 32;
            int coordinateGap = 5;
            int coordinateWidth = (fieldWidth - coordinateGap * 2) / 3;
            guiGraphics.drawString(font, "X", leftX + 2, top + 52, 0xFF8F8F8F, false);
            guiGraphics.drawString(font, "Y", leftX + coordinateWidth + coordinateGap + 2,
                    top + 52, 0xFF8F8F8F, false);
            guiGraphics.drawString(font, "Z", leftX + (coordinateWidth + coordinateGap) * 2 + 2,
                    top + 52, 0xFF8F8F8F, false);
            guiGraphics.drawString(font, "Icon", rightX, top + 2, 0xFFBFBFBF, false);

            int previewX = rightX;
            int previewY = top + 146;
            guiGraphics.fill(previewX, previewY, previewX + 40, previewY + 40, 0xFF111111);
            guiGraphics.renderOutline(previewX, previewY, 40, 40, 0xFF909090);
            renderScaledItem(guiGraphics, selectedIconItem,
                    previewX + 20, previewY + 20,
                    0.20f * MapConfig.waypointScale * waypointScale);
            guiGraphics.drawString(font, String.format(Locale.ROOT, "Preview %.1fx", waypointScale),
                    previewX + 47, previewY + 16, 0xFFBFBFBF, false);

            if (!validationMessage.isBlank()) {
                guiGraphics.drawString(font, validationMessage, panelLeft + 18,
                        panelTop + panelHeight - 27, 0xFFFFFFFF, false);
            }

            super.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        private static void renderItem(GuiGraphics guiGraphics, String itemId, int x, int y) {
            ResourceLocation location = ResourceLocation.tryParse(itemId);
            Item item = location == null ? Items.COMPASS : BuiltInRegistries.ITEM.get(location);
            if (item == null || item == Items.AIR) item = Items.COMPASS;
            guiGraphics.renderFakeItem(new ItemStack(item), x, y);
        }

        private static void renderScaledItem(GuiGraphics guiGraphics, String itemId,
                int centerX, int centerY, float scale) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(centerX, centerY, 80.0);
            float safeScale = Math.max(0.35f, Math.min(2.2f, scale));
            guiGraphics.pose().scale(safeScale, safeScale, 1.0f);
            renderItem(guiGraphics, itemId, -8, -8);
            guiGraphics.pose().popPose();
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        }

        @Override
        public void onClose() {
            restorePreviousMapView();
            if (minecraft != null) minecraft.setScreen(parent);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        private static class SimpleSlider extends AbstractSliderButton {
            private final java.util.function.Consumer<Double> onChange;
            private final java.util.function.Function<Double, String> labelProvider;

            SimpleSlider(int x, int y, int width, int height, double defaultValue,
                    java.util.function.Function<Double, String> labelProvider,
                    java.util.function.Consumer<Double> onChange) {
                super(x, y, width, height,
                        Component.literal(labelProvider.apply(defaultValue)), defaultValue);
                this.labelProvider = labelProvider;
                this.onChange = onChange;
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(labelProvider.apply(value)));
            }

            @Override
            protected void applyValue() {
                onChange.accept(value);
            }
        }

        private static class ItemIconButton extends Button {
            private String itemId;
            private int tab = -1;

            ItemIconButton(int x, int y, int width, int height, String itemId, OnPress onPress) {
                super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
                this.itemId = itemId;
            }

            @Override
            public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY,
                    float partialTick) {
                super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
                if (visible && itemId != null && !itemId.isBlank()) {
                    renderItem(guiGraphics, itemId, getX() + (width - 16) / 2,
                            getY() + (height - 16) / 2);
                }
            }
        }

        public static final class WaypointMapPickerScreen extends Screen {
            private static final float MIN_SCALE = 0.015625f;
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
                addRenderableWidget(Button.builder(Component.translatable("gui.simplemap.cancel"), button -> onClose())
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
    }
}

}

}