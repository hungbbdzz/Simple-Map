package com.velorise.simplemap.client;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class MapScreen extends Screen {
    private static final int TOOLBAR_BUTTON_SIZE = 22;
    private static final int TOOLBAR_MARGIN = 3;
    private static final int TOOLBAR_GAP = 2;
    private static final float TOOLBAR_ICON_SCALE = 0.80f;
    private static final int PLAYER_PANEL_MARGIN = 4;
    private static final int PLAYER_PANEL_HEIGHT = 15;
    private static final float DEFAULT_MAP_SCALE = 0.5f;
    private static final long OPEN_ANIMATION_NANOS = 1_000_000_000L;
    /** 5x default view opens at 6x and settles back to 5x. */
    private static final float OPEN_ANIMATION_START_MULTIPLIER = 1.2f;
    private static final long CURSOR_CACHE_NANOS = 120_000_000L;
    private static final double MOMENTUM_FRICTION = 7.5;
    private static final double MAX_MOMENTUM_BLOCKS_PER_SECOND = 12_000.0;
    private double centerX;
    private double centerZ;
    private float scale = DEFAULT_MAP_SCALE;
    private float currentRenderScale = DEFAULT_MAP_SCALE;
    private boolean isDragging = false;
    private long lastViewportScanNanos;
    private long openAnimationStartNanos = System.nanoTime();
    private long lastFrameNanos;
    private long lastDragSampleNanos;
    private long interactionHoldUntilNanos;
    private double momentumX;
    private double momentumZ;
    private int cachedCursorX = Integer.MIN_VALUE;
    private int cachedCursorZ = Integer.MIN_VALUE;
    private long cachedCursorRevision = Long.MIN_VALUE;
    private long cachedCursorAtNanos;
    private BlockInfo cachedCursorInfo;
    private boolean cursorCacheValid;

    // Popup context menu for waypoints and teleportation
    private boolean isPopupMenuOpen = false;
    private double popupX = 0;
    private double popupY = 0;
    private double popupWorldX = 0;
    private double popupWorldZ = 0;
    private WaypointManager.Waypoint clickedWaypoint = null;
    private BlockInfo popupBlockInfo = null;

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

    private Button waypointsToggleButton;
    private Button refreshMapButton;
    private Button minimapConfigButton;
    private Button nightModeButton;
    private Button caveLayerModeButton;
    private CaveLayerSlider caveLayerSlider;
    private int toolbarStartX;
    private int toolbarStartY;
    private int toolbarStepY;
    private int toolbarWidth;
    private int toolbarRows;

    @Override
    protected void init() {
        if (this.minecraft != null) {
            MapManager.getInstance().updateWorldAndDimension(this.minecraft);
        }
        if (this.width > 0) {
            this.scale = Math.max(getMinimumStableScale(), (this.width - 2) / 1000.0f);
            this.currentRenderScale = this.scale;
        }

        waypointsToggleButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.WAYPOINT_OUTLINE,
                button -> {
                    MapConfig.waypointsVisible = !MapConfig.waypointsVisible;
                    MapConfig.save();
                    updateToolbarTooltips();
                });
        this.addRenderableWidget(waypointsToggleButton);

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

        minimapConfigButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.SETTINGS, button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new MapConfigScreen(this));
            }
        });
        this.addRenderableWidget(minimapConfigButton);

        nightModeButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.SUN, button -> {
            MapConfig.minimapNightMode = (MapConfig.minimapNightMode + 1) % 3;
            MapConfig.save();
            updateToolbarTooltips();
        });
        this.addRenderableWidget(nightModeButton);

        if (this.minecraft != null && this.minecraft.level != null) {
            caveLayerModeButton = new PixelIconButton(0, 0, TOOLBAR_BUTTON_SIZE, TOOLBAR_BUTTON_SIZE, MapUiIcons.Icon.CAVE_OFF, button -> {
                if (MapConfig.getEffectiveCaveMapMode() != 2) return;
                CaveMode.cycleCaveType(this.minecraft);
                if (CaveMode.getCaveType(this.minecraft) == CaveMode.CaveType.OFF) {
                    CaveMapManager.getInstance().deactivate();
                } else if (CaveMode.getCaveType(this.minecraft) == CaveMode.CaveType.LAYERED) {
                    ChunkScanner.getInstance().requestImmediateCaveLayerRefresh(this.minecraft);
                } else {
                    // FULL uses a separate cache and must immediately rescan loaded
                    // chunks instead of displaying a stale/empty previous composite.
                    ChunkScanner.getInstance().requestRefresh(this.minecraft);
                    FullCaveTextureManager.getInstance().uploadDirtyTextures(true);
                }
                if (caveLayerSlider != null) caveLayerSlider.syncFromMode();
                updateCaveControlLayout();
            });
            this.addRenderableWidget(caveLayerModeButton);

            caveLayerModeButton.active = MapConfig.getEffectiveCaveMapMode() == 2;

            if (MapConfig.getEffectiveCaveMapMode() == 2) {
                int minimumY = this.minecraft.level.getMinBuildHeight();
                int maximumY = this.minecraft.level.getMaxBuildHeight() - 1;
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
        Button[] buttons = { waypointsToggleButton, refreshMapButton,
                nightModeButton, minimapConfigButton };
        // Compact, icon-only controls. The map itself remains visible between buttons.
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
        // Intentionally empty: v10 uses floating icon-only controls instead of a
        // translucent strip that covered map details in the corner.
    }

    private void updateToolbarTooltips() {
        if (waypointsToggleButton != null) waypointsToggleButton.setTooltip(Tooltip.create(Component.literal(
                "Waypoint Visibility: " + (MapConfig.waypointsVisible ? "ON" : "OFF")
                        + "\nClick to show or hide all waypoint markers.")));
        if (refreshMapButton != null) refreshMapButton.setTooltip(Tooltip.create(Component.literal(
                "Refresh Visible Map\nRescan nearby chunks currently loaded by Minecraft."
                        + "\nShift-click: center on player and reset zoom.")));
        if (nightModeButton != null) nightModeButton.setTooltip(Tooltip.create(Component.literal(
                "Map Brightness: " + getNightModeMessage().getString()
                        + "\nCycle DAY / AUTO / NIGHT rendering.")));
        if (minimapConfigButton != null) minimapConfigButton.setTooltip(Tooltip.create(Component.literal(
                "Simple Map Settings\nConfigure minimap, coordinates, colors and scanning.")));
        updateCaveTooltip();
    }

    private void updateCaveTooltip() {
        if (caveLayerModeButton == null) return;
        String detail = MapConfig.getEffectiveCaveMapMode() == 2
                ? switch (CaveMode.getCaveType(this.minecraft)) {
                    case OFF -> "Surface map only";
                    case LAYERED -> "Top-Y cave band";
                    case FULL -> "Automatic full surface / cave cache";
                }
                : "Mode controlled by the server";
        caveLayerModeButton.setTooltip(Tooltip.create(Component.literal(
                getCaveLayerModeMessage().getString() + "\n" + detail
                        + "\nClick to cycle surface, layered cave and full cave views.")));
    }

    private void updateCaveControlLayout() {
        if (caveLayerModeButton == null) return;
        updateCaveTooltip();
        int availableWidth = Math.max(44, this.width - 38);
        // Keep cave controls tight to the bottom-left edge without a panel behind them.
        int controlY = this.height - TOOLBAR_BUTTON_SIZE - TOOLBAR_MARGIN;
        caveLayerModeButton.setWidth(TOOLBAR_BUTTON_SIZE);
        caveLayerModeButton.setX(TOOLBAR_MARGIN);
        caveLayerModeButton.setY(controlY);
        if (caveLayerSlider == null) {
            return;
        }

        CaveMode.CaveType caveType = CaveMode.getCaveType(this.minecraft);
        caveLayerSlider.visible = caveType != CaveMode.CaveType.OFF;
        caveLayerSlider.active = caveType != CaveMode.CaveType.OFF;
        if (!caveLayerSlider.visible) {
            return;
        }
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
        if (MapConfig.getEffectiveCaveMapMode() == 0) return Component.literal("Cave: OFF");
        if (MapConfig.getEffectiveCaveMapMode() == 1) return Component.literal("Cave: AUTO");
        String type = switch (CaveMode.getCaveType(this.minecraft)) {
            case OFF -> "OFF";
            case LAYERED -> "LAYER";
            case FULL -> "FULL";
        };
        return Component.literal("Cave: " + type);
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

    private long viewportScanIntervalNanos(float displayedScale) {
        if (displayedScale >= 0.55f) return 25_000_000L;
        if (displayedScale >= 0.25f) return 55_000_000L;
        return 110_000_000L;
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
        currentRenderScale = scale * openAnimationMultiplier(frameNow);
        boolean cachedOnlyPan = isDragging || Math.hypot(momentumX, momentumZ) > 1.0
                || frameNow < interactionHoldUntilNanos;
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

        // The full-screen map scans every client-loaded chunk in the current
        // viewport. This eliminates the old "only a circle around the player"
        // cave/surface coverage and keeps panned areas coherent.
        long scanInterval = viewportScanIntervalNanos(currentRenderScale);
        if (!cachedOnlyPan && frameNow - lastViewportScanNanos >= scanInterval) {
            lastViewportScanNanos = frameNow;
            double halfW = (vw / 2.0) / Math.max(0.0001f, currentRenderScale);
            double halfH = (vh / 2.0) / Math.max(0.0001f, currentRenderScale);
            ChunkScanner.getInstance().scanVisibleArea(this.minecraft,
                    centerX - halfW, centerX + halfW,
                    centerZ - halfH, centerZ + halfH, currentRenderScale);
        }

        // Draw map fullscreen, North-up, no rotation
        MapRenderer.getInstance().drawMap(
            guiGraphics,
            vx, vy, vw, vh,
            centerX, centerZ, currentRenderScale,
            true,
            false,
            false, mouseWorldX, mouseWorldZ,
            partialTick, cachedOnlyPan
        );

        // Draw GUI widgets (buttons)
        drawToolbarBackground(guiGraphics);
        drawCaveControlBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // Draw Pin distance label if active
        if (MapConfig.pinActive && this.minecraft != null && this.minecraft.player != null) {
            drawPinDistanceLabel(guiGraphics, vx, vy, vw, vh);
        }

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
        boolean chunkLoaded = this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.hasChunk(bx >> 4, bz >> 4);
        BlockInfo resolved = resolveCursorBlockInfo(bx, bz, chunkLoaded, System.nanoTime());
        if (resolved != null && (!resolved.name.isEmpty() || resolved.cached)) {
            yCoord = resolved.y;
            waterSurfaceY = resolved.waterSurfaceY;
            hasY = true;
        }

        String coordText;
        if (hasY) {
            String waterLevel = waterSurfaceY == Integer.MIN_VALUE ? "" : " (" + waterSurfaceY + ")";
            coordText = String.format("X: %,d  Y: %d%s  Z: %,d", bx, yCoord, waterLevel, bz);
        } else {
            coordText = String.format("X: %,d  Z: %,d", bx, bz);
        }

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
            net.minecraft.core.BlockPos biomePos = new net.minecraft.core.BlockPos(bx, yCoord, bz);
            biomeText = this.minecraft.level.getBiome(biomePos).unwrapKey()
                    .map(key -> prettifyId(key.location().getPath()))
                    .orElse("Unknown Biome");
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

        // Render player coordinates at bottom-right. Bounds and text use one shared
        // calculation so the clickable panel cannot drift below the label.
        if (this.minecraft != null && this.minecraft.player != null) {
            String playerText = getPlayerCoordinatesText();
            int[] bounds = getPlayerCoordinatesPanelBounds(playerText);
            boolean hovered = isInside(mouseX, mouseY, bounds);
            guiGraphics.fill(bounds[0], bounds[1], bounds[2], bounds[3],
                    hovered ? 0xAA000000 : 0x78000000);
            if (hovered) {
                guiGraphics.renderOutline(bounds[0], bounds[1], bounds[2] - bounds[0],
                        bounds[3] - bounds[1], 0x88FFFFFF);
            }
            guiGraphics.drawString(this.font, playerText, bounds[0] + 5, bounds[1] + 3,
                    0xFFFFFFFF, false);
            if (hovered) {
                guiGraphics.renderTooltip(this.font,
                        Component.literal("Click to copy these coordinates and open chat."),
                        mouseX, mouseY);
            }
        }
    }

    private String getPlayerCoordinatesText() {
        return String.format("Player X: %,d | Y: %,d | Z: %,d",
                (int) Math.floor(this.minecraft.player.getX()),
                (int) Math.floor(this.minecraft.player.getY()),
                (int) Math.floor(this.minecraft.player.getZ()));
    }

    private int[] getPlayerCoordinatesPanelBounds(String text) {
        int panelWidth = this.font.width(text) + 10;
        int right = this.width - PLAYER_PANEL_MARGIN;
        int bottom = this.height - PLAYER_PANEL_MARGIN;
        return new int[] { right - panelWidth, bottom - PLAYER_PANEL_HEIGHT, right, bottom };
    }

    private static boolean isInside(double mouseX, double mouseY, int[] bounds) {
        return mouseX >= bounds[0] && mouseX <= bounds[2]
                && mouseY >= bounds[1] && mouseY <= bounds[3];
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
        if (chunkLoaded && this.minecraft != null && this.minecraft.level != null) {
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
            int maximumY = CaveMode.getScanMaximum(level, layerY);
            int minimumY = CaveMode.getScanMinimum(level, layerY);
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
        boolean isNether = level.dimensionType().hasCeiling();
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
        if (isNether) {
            highestY = 120;
            boolean foundAir = false;
            for (int y = 120; y >= minBuildHeight; y--) {
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
                        || state.getCollisionShape(level, pos).isEmpty());
        boolean flower = MapConfig.displayFlowers
                && state.is(net.minecraft.tags.BlockTags.FLOWERS);
        if (openEmitter || flower || !state.getFluidState().isEmpty()) return true;
        return !state.isAir()
                && !state.getCollisionShape(level, pos).isEmpty()
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
                        || state.getCollisionShape(level, pos).isEmpty());
        if (openEmitter) {
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).toString();
            return new BlockInfo(state.getBlock().getName().getString(), blockId, openY);
        }
        if (state.getFluidState().isEmpty()) {
            boolean openSpace = state.isAir() || state.getCollisionShape(level, pos).isEmpty();
            if (!openSpace || openY <= level.getMinBuildHeight()) return null;
            pos.setY(openY - 1);
            state = level.getBlockState(pos);
            boolean floorOpen = state.isAir()
                    || (state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty());
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
                        || candidate.getCollisionShape(level, pos).isEmpty()) continue;
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

    /**
     * Draws the pin distance label below the pin icon.
     */
    private void drawPinDistanceLabel(GuiGraphics guiGraphics, int vx, int vy, int vw, int vh) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        // Convert world coords to screen coords
        double vpCX = vx + vw / 2.0;
        double vpCZ = vy + vh / 2.0;

        float pinScrX = (float) (vpCX + (MapConfig.pinWorldX - centerX) * currentRenderScale);
        float pinScrZ = (float) (vpCZ + (MapConfig.pinWorldZ - centerZ) * currentRenderScale);

        // Distance label below the icon (only if on screen)
        boolean onScreen = pinScrX >= vx && pinScrX <= vx + vw && pinScrZ >= vy && pinScrZ <= vy + vh;
        if (onScreen) {
            double dist = Math.sqrt((MapConfig.pinWorldX - playerX) * (MapConfig.pinWorldX - playerX) + (MapConfig.pinWorldZ - playerZ) * (MapConfig.pinWorldZ - playerZ));
            String distText = (int) dist + " blocks";

            float textScale = MapConfig.pinScale * 2.0f; // Scale relative to default 0.5 (where textScale = 1.0f)
            int labelY = (int) pinScrZ + (int) (8 * MapConfig.pinScale) + 2;

            com.mojang.blaze3d.vertex.PoseStack poseStack = guiGraphics.pose();
            poseStack.pushPose();
            poseStack.translate(pinScrX, labelY, 0);
            poseStack.scale(textScale, textScale, 1.0f);

            int rawWidth = this.font.width(distText);
            guiGraphics.fill(-rawWidth / 2 - 2, -1, rawWidth / 2 + 2, 9, 0xAA000000);
            guiGraphics.drawString(this.font, distText, -rawWidth / 2, 0, 0xFFFFFF, false);

            poseStack.popPose();
        }
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
        boolean canTeleport = this.minecraft != null && this.minecraft.player != null &&
            (this.minecraft.player.isCreative() || this.minecraft.player.isSpectator() || this.minecraft.player.hasPermissions(2));
        guiGraphics.fill((int)popupX + 1, (int)popupY + 21, (int)popupX + 99, (int)popupY + 39, hoverOpt2 && canTeleport ? 0xFF2D3033 : 0x00000000);
        guiGraphics.drawString(this.font, "Teleport Here", (int)popupX + 6, (int)popupY + 26, canTeleport ? 0xFFFFFF : 0x777777, false);

        // Option 3: Customize the surface block color.
        boolean canCustomize = popupBlockInfo != null && !popupBlockInfo.id.isEmpty();
        boolean hoverOpt3 = mouseX >= popupX && mouseX <= popupX + 100
                && mouseY >= popupY + 40 && mouseY <= popupY + 60;
        guiGraphics.fill((int) popupX + 1, (int) popupY + 41, (int) popupX + 99, (int) popupY + 59,
                hoverOpt3 && canCustomize ? 0xFF2D3033 : 0x00000000);
        guiGraphics.drawString(this.font, "Customize Color", (int) popupX + 6, (int) popupY + 46,
                canCustomize ? 0xFFFFFF : 0x777777, false);

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

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (SimpleMap.ClientModEvents.OPEN_MAP_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.OPEN_MAP_KEY);
            this.onClose();
            return true;
        }
        if (SimpleMap.ClientModEvents.ZOOM_IN_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.ZOOM_IN_KEY);
            this.scale = Math.min(12.0f, this.scale * 1.15f);
            return true;
        }
        if (SimpleMap.ClientModEvents.ZOOM_OUT_KEY.matches(keyCode, scanCode)) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.ZOOM_OUT_KEY);
            this.scale = Math.max(getMinimumStableScale(), this.scale / 1.15f);
            return true;
        }
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_HOME
                || SimpleMap.ClientModEvents.CENTER_FULL_MAP_KEY.matches(keyCode, scanCode))
                && this.minecraft != null && this.minecraft.player != null) {
            consumeBufferedClicks(SimpleMap.ClientModEvents.CENTER_FULL_MAP_KEY);
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
            int[] bounds = getPlayerCoordinatesPanelBounds(playerText);
            if (isInside(mouseX, mouseY, bounds)) {
                net.minecraft.world.entity.player.Player player = this.minecraft.player;
                String shareText = String.format("I am at coordinates [%d, %d, %d]",
                    (int) Math.floor(player.getX()),
                    (int) Math.floor(player.getY()),
                    (int) Math.floor(player.getZ())
                );
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
                            this.minecraft.setScreen(new AddWaypointScreen(this, popupWorldX, popupWorldZ, MapManager.getInstance().getCurrentDimensionId()));
                        }
                    }
                } else if (mouseY < popupY + 40) {
                    // Option 2: Teleport Here (only blocks survival players without cheats)
                    boolean canTeleport = this.minecraft != null && this.minecraft.player != null &&
                        (this.minecraft.player.isCreative() || this.minecraft.player.isSpectator() || this.minecraft.player.hasPermissions(2));
                    if (canTeleport && this.minecraft.level != null) {
                        isPopupMenuOpen = false;
                        int tpX = (int) popupWorldX;
                        int tpZ = (int) popupWorldZ;
                        int targetY;

                        boolean isNether = this.minecraft.level.dimensionType().hasCeiling();
                        int minBuildHeight = this.minecraft.level.getMinBuildHeight();
                        if (this.minecraft.level.hasChunk(tpX >> 4, tpZ >> 4)) {
                            BlockInfo info = getBlockInfoAt(this.minecraft.level, tpX, tpZ);
                            if (info != null && info.y > minBuildHeight) {
                                int surfaceY = info.waterSurfaceY != Integer.MIN_VALUE ? info.waterSurfaceY : info.y;
                                targetY = surfaceY + 2;
                            } else {
                                BlockInfo cached = getCachedBlockInfoAt(tpX, tpZ);
                                if (cached != null && cached.y > minBuildHeight) {
                                    int surfaceY = cached.waterSurfaceY != Integer.MIN_VALUE ? cached.waterSurfaceY : cached.y;
                                    targetY = surfaceY + 2;
                                } else if (isNether) {
                                    targetY = 65; // Safe middle height in Nether
                                } else {
                                    int surfaceY = this.minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, tpX, tpZ);
                                    targetY = surfaceY > minBuildHeight ? surfaceY + 2 : 100;
                                }
                            }
                        } else {
                            // Unloaded chunk: Try cached map data first before defaulting to safe heights
                            BlockInfo cached = getCachedBlockInfoAt(tpX, tpZ);
                            if (cached != null && cached.y > minBuildHeight) {
                                int surfaceY = cached.waterSurfaceY != Integer.MIN_VALUE ? cached.waterSurfaceY : cached.y;
                                targetY = surfaceY + 2;
                            } else {
                                targetY = isNether ? 65 : 100;
                            }
                        }

                        // Execute command using connection.sendCommand (WITHOUT leading slash) in 1.19+
                        this.minecraft.player.connection.sendCommand("tp " + tpX + " " + targetY + " " + tpZ);
                        this.onClose(); // Close map after teleporting
                    }
                } else if (mouseY < popupY + 60) {
                    // Option 3: Customize block color.
                    if (popupBlockInfo != null && !popupBlockInfo.id.isEmpty() && this.minecraft != null) {
                        int blockX = (int) Math.floor(popupWorldX);
                        int blockZ = (int) Math.floor(popupWorldZ);
                        int mapColor = getDisplayedMapColor(blockX, blockZ);
                        int automaticColor = mapColor == 0 ? 0xFFFFFFFF : abgrToArgb(mapColor);
                        isPopupMenuOpen = false;
                        this.minecraft.setScreen(new BlockColorScreen(this, popupBlockInfo.id, popupBlockInfo.name,
                                blockX, blockZ, automaticColor));
                    }
                } else if (clickedWaypoint != null && mouseY < popupY + 80) {
                    // Option 4: Follow Waypoint
                    boolean isCurrentlyFollowing = MapConfig.pinActive &&
                        Math.abs(MapConfig.pinWorldX - clickedWaypoint.x) < 0.01 &&
                        Math.abs(MapConfig.pinWorldZ - clickedWaypoint.z) < 0.01;
                    if (isCurrentlyFollowing) {
                        MapConfig.pinActive = false;
                    } else {
                        MapConfig.pinWorldX = clickedWaypoint.x;
                        MapConfig.pinWorldZ = clickedWaypoint.z;
                        MapConfig.pinActive = true;
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
            cancelMotionAndOpenAnimation();
            this.isDragging = true;
            dragStartX = mouseX;
            dragStartZ = mouseY;
            lastDragSampleNanos = System.nanoTime();
            interactionHoldUntilNanos = lastDragSampleNanos + 250_000_000L;
            return true;
        }

        // 4. Right click opens the popup context menu
        if (button == 1) {
            popupX = mouseX;
            popupY = mouseY;
            popupWorldX = centerX + (mouseX - this.width / 2.0) / currentRenderScale;
            popupWorldZ = centerZ + (mouseY - this.height / 2.0) / currentRenderScale;

            int popupBlockX = (int) Math.floor(popupWorldX);
            int popupBlockZ = (int) Math.floor(popupWorldZ);
            popupBlockInfo = null;
            if (this.minecraft != null && this.minecraft.level != null
                    && this.minecraft.level.hasChunk(popupBlockX >> 4, popupBlockZ >> 4)) {
                popupBlockInfo = getBlockInfoAt(this.minecraft.level, popupBlockX, popupBlockZ);
            }

            // Check if right-clicking on an existing waypoint
            clickedWaypoint = null;
            java.util.List<WaypointManager.Waypoint> waypoints = WaypointManager.getInstance().getWaypointsForDimension(MapManager.getInstance().getCurrentDimensionId());
            double hoverRadius = 6.0 / currentRenderScale; // 6 pixels hitbox in map world space
            for (WaypointManager.Waypoint wp : waypoints) {
                if (Math.abs(popupWorldX - wp.x) <= hoverRadius && Math.abs(popupWorldZ - wp.z) <= hoverRadius) {
                    clickedWaypoint = wp;
                    break;
                }
            }

            isPopupMenuOpen = true;
            return true;
        }

        return false;
    }

    private int abgrToArgb(int abgr) {
        int alpha = (abgr >>> 24) & 0xFF;
        int blue = (abgr >>> 16) & 0xFF;
        int green = (abgr >>> 8) & 0xFF;
        int red = abgr & 0xFF;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private int getDisplayedMapColor(int blockX, int blockZ) {
        if (CaveMode.isFullView(this.minecraft)) {
            return FullCaveMapManager.getInstance().getColor(blockX, blockZ);
        }
        if (CaveMode.isActive(this.minecraft)) {
            return CaveMapManager.getInstance().getColor(blockX, blockZ);
        }
        return MapManager.getInstance().getColor(blockX, blockZ);
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
                    MapConfig.pinActive = false;
                } else {
                    MapConfig.pinWorldX = snappedPinX;
                    MapConfig.pinWorldZ = snappedPinZ;
                    MapConfig.pinActive = true;
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
            // Drag direction moves the map camera center in the opposite direction.
            double deltaX = -dragX / this.scale;
            double deltaZ = -dragY / this.scale;
            this.centerX += deltaX;
            this.centerZ += deltaZ;
            long now = System.nanoTime();
            interactionHoldUntilNanos = now + 250_000_000L;
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
        double viewportArea = Math.max(1.0, (double) (this.width - 2) * (this.height - 2));
        double regionPixelArea = 512.0 * 512.0;
        double targetVisibleRegions = 88.0;
        return (float) Math.max(0.08,
                Math.sqrt(viewportArea / (targetVisibleRegions * regionPixelArea)));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!MapManager.getInstance().hasLearnedMap()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        cancelMotionAndOpenAnimation();
        interactionHoldUntilNanos = System.nanoTime() + 260_000_000L;
        float oldScale = this.scale;

        // A 512x512 region is one GPU texture. Extremely small scales can place
        // thousands of regions on screen and force unavoidable LRU thrashing.
        // Clamp to a screen-dependent stable overview limit until a hierarchical
        // overview texture system is introduced.
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
        private final int minimumY;
        private final int maximumY;
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
            setMessage(Component.literal(isAutoSelection() ? "Top Y: AUTO" : "Top Y: " + selectedY()));
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
            if (isAutoSelection()) {
                this.value = 0.0;
                CaveMode.setAutoTopY(MapScreen.this.minecraft);
            } else {
                CaveMode.setManualLayer(MapScreen.this.minecraft, selectedY());
            }
            // Commit means scan now: activate the selected cache, synchronously
            // fill the player chunk, and aggressively complete nearby loaded chunks.
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
    public boolean isPauseScreen() {
        return false; // Don't pause game in singleplayer
    }
}
