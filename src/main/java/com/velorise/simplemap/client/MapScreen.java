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
    private double centerX;
    private double centerZ;
    private float scale = 1.0f;
    private boolean isDragging = false;
    private long lastViewportScanNanos;

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

        waypointsToggleButton = new PixelIconButton(0, 0, 26, 26, MapUiIcons.Icon.WAYPOINT_OUTLINE,
                button -> {
                    MapConfig.waypointsVisible = !MapConfig.waypointsVisible;
                    MapConfig.save();
                    updateToolbarTooltips();
                });
        this.addRenderableWidget(waypointsToggleButton);

        refreshMapButton = new PixelIconButton(0, 0, 26, 26, MapUiIcons.Icon.REFRESH, button -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                if (Screen.hasShiftDown()) {
                    this.centerX = this.minecraft.player.getX();
                    this.centerZ = this.minecraft.player.getZ();
                    this.scale = 1.0f;
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

        minimapConfigButton = new PixelIconButton(0, 0, 26, 26, MapUiIcons.Icon.SETTINGS, button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new MapConfigScreen(this));
            }
        });
        this.addRenderableWidget(minimapConfigButton);

        nightModeButton = new PixelIconButton(0, 0, 26, 26, MapUiIcons.Icon.SUN, button -> {
            MapConfig.minimapNightMode = (MapConfig.minimapNightMode + 1) % 3;
            MapConfig.save();
            updateToolbarTooltips();
        });
        this.addRenderableWidget(nightModeButton);

        if (this.minecraft != null && this.minecraft.level != null) {
            caveLayerModeButton = new PixelIconButton(0, 0, 26, 26, MapUiIcons.Icon.CAVE, button -> {
                if (MapConfig.serverCaveMapMode != 2) return;
                CaveMode.cycleCaveType(this.minecraft);
                if (CaveMode.getCaveType(this.minecraft) == CaveMode.CaveType.OFF) {
                    CaveMapManager.getInstance().deactivate();
                }
                if (caveLayerSlider != null) caveLayerSlider.syncFromMode();
                updateCaveControlLayout();
            });
            this.addRenderableWidget(caveLayerModeButton);

            caveLayerModeButton.active = MapConfig.serverCaveMapMode == 2;

            if (MapConfig.serverCaveMapMode == 2) {
                int minimumY = this.minecraft.level.getMinBuildHeight();
                int maximumY = this.minecraft.level.getMaxBuildHeight() - 1;
                double initialValue = CaveMode.hasManualTopY(this.minecraft)
                        ? CaveLayerSlider.normalizeNumeric(CaveMode.getSelectedTopY(this.minecraft), minimumY, maximumY)
                        : 0.0;
                caveLayerSlider = new CaveLayerSlider(0, 0, 156, 18,
                        minimumY, maximumY, initialValue);
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
        // Move toolbar into the top-left corner for quicker access
        toolbarStartX = 8;
        toolbarStartY = 8;
        toolbarStepY = 30;
        toolbarWidth = 26;
        toolbarRows = buttons.length;
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setX(toolbarStartX);
            buttons[i].setY(toolbarStartY + i * toolbarStepY);
            buttons[i].setWidth(26);
        }
    }

    private void drawToolbarBackground(GuiGraphics guiGraphics) {
        int top = toolbarStartY - 6;
        int bottom = toolbarStartY + toolbarRows * toolbarStepY - 1;
        guiGraphics.fill(toolbarStartX - 8, top, toolbarStartX + toolbarWidth + 8, bottom, 0x22090B0D);
    }

    private void updateToolbarTooltips() {
        if (waypointsToggleButton != null) waypointsToggleButton.setTooltip(Tooltip.create(Component.literal(
                "Waypoints\n" + (MapConfig.waypointsVisible ? "Enabled" : "Hidden"))));
        if (refreshMapButton != null) refreshMapButton.setTooltip(Tooltip.create(Component.literal(
                "Refresh Map\nReload nearby loaded chunks\nShift-click: reset view")));
        if (nightModeButton != null) nightModeButton.setTooltip(Tooltip.create(Component.literal(
                getNightModeMessage().getString())));
        if (minimapConfigButton != null) minimapConfigButton.setTooltip(Tooltip.create(Component.literal(
                "Settings\nOpen map configuration")));
        updateCaveTooltip();
    }

    private void updateCaveTooltip() {
        if (caveLayerModeButton == null) return;
        String detail = MapConfig.serverCaveMapMode == 2
                ? switch (CaveMode.getCaveType(this.minecraft)) {
                    case OFF -> "Surface map only";
                    case LAYERED -> "Top-Y cave band";
                    case FULL -> "Automatic full surface / cave cache";
                }
                : "Mode controlled by the server";
        caveLayerModeButton.setTooltip(Tooltip.create(Component.literal(
                getCaveLayerModeMessage().getString() + "\n" + detail)));
    }

    private void updateCaveControlLayout() {
        if (caveLayerModeButton == null) return;
        updateCaveTooltip();
        int availableWidth = Math.max(44, this.width - 48);
        // Push cave controls closer to the bottom-left corner for faster access
        int controlY = this.height - 34;
        caveLayerModeButton.setWidth(26);
        caveLayerModeButton.setX(8);
        caveLayerModeButton.setY(controlY);
        if (caveLayerSlider == null) {
            return;
        }

        CaveMode.CaveType caveType = CaveMode.getCaveType(this.minecraft);
        caveLayerSlider.visible = caveType != CaveMode.CaveType.OFF;
        caveLayerSlider.active = caveType == CaveMode.CaveType.LAYERED;
        if (!caveLayerSlider.visible) {
            return;
        }
        caveLayerSlider.setWidth(Math.min(190, availableWidth));
        caveLayerSlider.setX(38);
        caveLayerSlider.setY(controlY + 2);
    }

    private void drawCaveControlBackground(GuiGraphics guiGraphics) {
        if (caveLayerModeButton == null || !caveLayerModeButton.visible) return;
        int left = caveLayerModeButton.getX();
        int top = caveLayerModeButton.getY();
        int right = caveLayerModeButton.getX() + caveLayerModeButton.getWidth();
        if (caveLayerSlider != null && caveLayerSlider.visible) {
            left = Math.min(left, caveLayerSlider.getX());
            top = Math.min(top, caveLayerSlider.getY());
            right = Math.max(right, caveLayerSlider.getX() + caveLayerSlider.getWidth());
        }
        left -= 4;
        top -= 4;
        right += 4;
        int bottom = caveLayerSlider != null && caveLayerSlider.visible
                ? Math.max(caveLayerModeButton.getY() + 26,
                        caveLayerSlider.getY() + 18) + 4
                : caveLayerModeButton.getY() + 26 + 4;
        guiGraphics.fill(left, top, right, bottom, 0x22090B0D);
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
        if (MapConfig.serverCaveMapMode == 0) return Component.literal("Cave: OFF");
        if (MapConfig.serverCaveMapMode == 1) return Component.literal("Cave: AUTO");
        String type = switch (CaveMode.getCaveType(this.minecraft)) {
            case OFF -> "OFF";
            case LAYERED -> "LAYER";
            case FULL -> "FULL";
        };
        return Component.literal("Cave: " + type);
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Override to draw a plain dark background instead of Minecraft 1.21's
        // built-in gaussian blur post-processing shader (which blurs our map).
        // We draw the background ourselves in render(), so this is intentionally empty.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
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
        double mouseWorldX = centerX + (mouseX - vx - vw / 2.0) / scale;
        double mouseWorldZ = centerZ + (mouseY - vy - vh / 2.0) / scale;

        // The full-screen map scans every client-loaded chunk in the current
        // viewport. This eliminates the old "only a circle around the player"
        // cave/surface coverage and keeps panned areas coherent.
        long now = System.nanoTime();
        if (now - lastViewportScanNanos >= 50_000_000L) {
            lastViewportScanNanos = now;
            double halfW = (vw / 2.0) / Math.max(0.0001f, scale);
            double halfH = (vh / 2.0) / Math.max(0.0001f, scale);
            ChunkScanner.getInstance().scanVisibleArea(this.minecraft,
                    centerX - halfW, centerX + halfW,
                    centerZ - halfH, centerZ + halfH);
        }

        // Draw map fullscreen, North-up, no rotation
        MapRenderer.getInstance().drawMap(
            guiGraphics,
            vx, vy, vw, vh,
            centerX, centerZ, scale,
            true,
            false,
            false, mouseWorldX, mouseWorldZ,
            partialTick
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
        BlockInfo(String name, String id, int y) {
            this(name, id, y, Integer.MIN_VALUE);
        }
        BlockInfo(String name, String id, int y, int waterSurfaceY) {
            this.name = name;
            this.id = id;
            this.y = y;
            this.waterSurfaceY = waterSurfaceY;
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
            }
            if (isHoveredOrFocused()) {
                graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x12FFFFFF);
            }
            MapUiIcons.draw(graphics, displayedIcon,
                    getX() + getWidth() / 2, getY() + getHeight() / 2,
                    active);
        }
    }

    private void drawCoordsOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Calculate world coordinates under the mouse cursor (viewport starts at x=1, y=1)
        double worldX = centerX + (mouseX - 1 - (this.width - 2) / 2.0) / scale;
        double worldZ = centerZ + (mouseY - 1 - (this.height - 2) / 2.0) / scale;

        int bx = (int) Math.floor(worldX);
        int bz = (int) Math.floor(worldZ);

        // Find block name and Y coordinate under cursor if the chunk is loaded on client
        int yCoord = 0;
        int waterSurfaceY = Integer.MIN_VALUE;
        boolean hasY = false;
        if (this.minecraft != null && this.minecraft.level != null && this.minecraft.level.hasChunk(bx >> 4, bz >> 4)) {
            BlockInfo resolved = getBlockInfoAt(this.minecraft.level, bx, bz);
            if (resolved != null && !resolved.name.isEmpty()) {
                yCoord = resolved.y;
                waterSurfaceY = resolved.waterSurfaceY;
                hasY = true;
            }
        }

        String coordText;
        if (hasY) {
            String waterLevel = waterSurfaceY == Integer.MIN_VALUE ? "" : " (" + waterSurfaceY + ")";
            coordText = String.format("X: %,d  Y: %d%s  Z: %,d", bx, yCoord, waterLevel, bz);
        } else {
            coordText = String.format("X: %,d  Z: %,d", bx, bz);
        }

        String biomeText = null;
        if (MapConfig.cursorBiomeEnabled && hasY && this.minecraft != null && this.minecraft.level != null) {
            net.minecraft.core.BlockPos biomePos = new net.minecraft.core.BlockPos(bx, yCoord, bz);
            biomeText = this.minecraft.level.getBiome(biomePos).unwrapKey()
                    .map(key -> prettifyId(key.location().getPath()))
                    .orElse("Unknown Biome");
        }

        int coordWidth = this.font.width(coordText);
        int biomeWidth = biomeText == null ? 0 : this.font.width(biomeText);
        int panelWidth = Math.max(coordWidth, biomeWidth) + 10;
        int panelHeight = biomeText == null ? 13 : 23;
        int panelX = (this.width - panelWidth) / 2;
        // Nudge coordinate readout closer to the top edge
        int panelY = 4;
        guiGraphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0x88000000);
        guiGraphics.drawString(this.font, coordText,
                panelX + (panelWidth - coordWidth) / 2, panelY + 3, 0xFFFFFF, false);
        if (biomeText != null) {
            guiGraphics.drawString(this.font, biomeText,
                    panelX + (panelWidth - biomeWidth) / 2, panelY + 13, 0xA8D69C, false);
        }

        // Render player coordinates at bottom-right
        if (this.minecraft != null && this.minecraft.player != null) {
            String playerText = String.format("Player X: %,d | Y: %,d | Z: %,d",
                (int) Math.floor(this.minecraft.player.getX()),
                (int) Math.floor(this.minecraft.player.getY()),
                (int) Math.floor(this.minecraft.player.getZ())
            );
            int pTextWidth = this.font.width(playerText);
            int pPanelX = this.width - pTextWidth - 10;
            int pPanelY = this.height - 16;
            boolean hovered = mouseX >= pPanelX - 5 && mouseX <= pPanelX + pTextWidth + 5
                    && mouseY >= pPanelY - 1 && mouseY <= pPanelY + 15;
            guiGraphics.fill(pPanelX - 5, pPanelY - 1, pPanelX + pTextWidth + 5, pPanelY + 15,
                    hovered ? 0xBB10232A : 0x88000000);
            guiGraphics.drawString(this.font, playerText, pPanelX, pPanelY + 1, 0x00C8FF, false);
        }
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
            int maximumOffset = Math.max(layerY - scanMinimum, scanMaximum - layerY);
            for (int offset = 0; offset <= maximumOffset; offset++) {
                int above = layerY + offset;
                if (above <= scanMaximum) {
                    BlockInfo info = getCaveBlockInfoAtY(level, pos, above);
                    if (info != null) return info;
                }
                int below = layerY - offset;
                if (offset != 0 && below >= scanMinimum) {
                    BlockInfo info = getCaveBlockInfoAtY(level, pos, below);
                    if (info != null) return info;
                }
            }
            return new BlockInfo("", "", layerY);
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

        float pinScrX = (float) (vpCX + (MapConfig.pinWorldX - centerX) * scale);
        float pinScrZ = (float) (vpCZ + (MapConfig.pinWorldZ - centerZ) * scale);

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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // If map is not unlocked, only allow clicking screen widgets (buttons)
        if (!SimpleMap.isMapUnlocked(this.minecraft.player)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Click the visible top-right player-coordinate panel to share in chat
        if (this.minecraft != null && this.minecraft.player != null) {
            String playerText = String.format("Player X: %,d | Y: %,d | Z: %,d",
                (int) Math.floor(this.minecraft.player.getX()),
                (int) Math.floor(this.minecraft.player.getY()),
                (int) Math.floor(this.minecraft.player.getZ())
            );
            int pTextWidth = this.font.width(playerText);
            int pPanelX = this.width - pTextWidth - 10;
            int pPanelY = this.height - 16;
            int pX1 = pPanelX - 5;
            int pX2 = pPanelX + pTextWidth + 5;
            int pY1 = pPanelY - 1;
            int pY2 = pPanelY + 15;
            if (mouseX >= pX1 && mouseX <= pX2 && mouseY >= pY1 && mouseY <= pY2) {
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
                                targetY = info.y + 1;
                            } else {
                                if (isNether) {
                                    targetY = 65; // Safe middle height in Nether
                                } else {
                                    int surfaceY = this.minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, tpX, tpZ);
                                    targetY = surfaceY > minBuildHeight ? surfaceY + 1 : 100;
                                }
                            }
                        } else {
                            // Unloaded chunk: Teleport to a reasonable default height (100 in Overworld, 65 in Nether)
                            targetY = isNether ? 65 : 100;
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
            this.isDragging = true;
            dragStartX = mouseX;
            dragStartZ = mouseY;
            return true;
        }

        // 4. Right click opens the popup context menu
        if (button == 1) {
            popupX = mouseX;
            popupY = mouseY;
            popupWorldX = centerX + (mouseX - this.width / 2.0) / scale;
            popupWorldZ = centerZ + (mouseY - this.height / 2.0) / scale;

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
            double hoverRadius = 6.0 / scale; // 6 pixels hitbox in map world space
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
                double clickWorldX = centerX + (mouseX - 1 - (this.width - 2) / 2.0) / scale;
                double clickWorldZ = centerZ + (mouseY - 1 - (this.height - 2) / 2.0) / scale;

                double snappedPinX = Math.floor(clickWorldX) + 0.5;
                double snappedPinZ = Math.floor(clickWorldZ) + 0.5;

                // Click same pin again within 4 screen-pixels -> remove
                if (MapConfig.pinActive && Math.abs(snappedPinX - MapConfig.pinWorldX) < 4.0 / scale && Math.abs(snappedPinZ - MapConfig.pinWorldZ) < 4.0 / scale) {
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
            // Drag direction moves the map camera center in the opposite direction
            this.centerX -= dragX / this.scale;
            this.centerZ -= dragY / this.scale;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private float getMinimumStableScale() {
        double viewportArea = Math.max(1.0, (double) (this.width - 2) * (this.height - 2));
        double regionPixelArea = 512.0 * 512.0;
        double targetVisibleRegions = 112.0;
        return (float) Math.max(0.08,
                Math.sqrt(viewportArea / (targetVisibleRegions * regionPixelArea)));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!MapManager.getInstance().hasLearnedMap()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

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
