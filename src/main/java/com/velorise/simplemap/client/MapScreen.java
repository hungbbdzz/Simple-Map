package com.velorise.simplemap.client;

import com.velorise.simplemap.SimpleMap;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL11;

public class MapScreen extends Screen {
    private double centerX;
    private double centerZ;
    private float scale = 1.0f;
    private boolean isDragging = false;

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

    private Button waypointsToggleButton;

    @Override
    protected void init() {
        // Toggle Waypoint visibility button (Top-Left)
        waypointsToggleButton = Button.builder(
                getWaypointsToggleMessage(),
                button -> {
                    MapConfig.waypointsVisible = !MapConfig.waypointsVisible;
                    MapConfig.save();
                    button.setMessage(getWaypointsToggleMessage());
                }).bounds(10, 10, 120, 20).build();
        this.addRenderableWidget(waypointsToggleButton);

        // Add "Reset View" button to re-center on player and reset zoom
        this.addRenderableWidget(Button.builder(Component.literal("Reset View"), button -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.centerX = this.minecraft.player.getX();
                this.centerZ = this.minecraft.player.getZ();
                this.scale = 1.0f;
            }
        }).bounds(140, 10, 80, 20).build());

        // Add "Refresh Map" button to force a synchronous full rescan and immediate upload
        this.addRenderableWidget(Button.builder(Component.literal("Refresh Map"), button -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                int renderDistance = this.minecraft.options.renderDistance().get();
                int radius = (int) Math.max(16, (renderDistance - 1.5) * 16);
                ChunkScanner.getInstance().scanAroundPlayer(this.minecraft, radius);
                MapTextureManager.getInstance().uploadDirtyTextures(true);
            }
        }).bounds(230, 10, 80, 20).build());



        // Add settings button to open the config screen
        this.addRenderableWidget(Button.builder(Component.literal("Minimap Config"), button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(new MapConfigScreen(this));
            }
        }).bounds(this.width - 110, 10, 100, 20).build());
    }

    private Component getWaypointsToggleMessage() {
        return Component.literal("Waypoints: " + (MapConfig.waypointsVisible ? "SHOW" : "HIDE"));
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Override to draw a plain dark background instead of Minecraft 1.21's
        // built-in gaussian blur post-processing shader (which blurs our map).
        // We draw the background ourselves in render(), so this is intentionally empty.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Fullscreen dark background
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF0D0D0D);

        // Thin 2-pixel border at screen edges (like Xaero's map)
        guiGraphics.fill(0, 0, this.width, 1, 0xFF2D3033); // top
        guiGraphics.fill(0, this.height - 1, this.width, this.height, 0xFF2D3033); // bottom
        guiGraphics.fill(0, 0, 1, this.height, 0xFF2D3033); // left
        guiGraphics.fill(this.width - 1, 0, this.width, this.height, 0xFF2D3033); // right

        // If map is not unlocked, draw a lock message and only render widgets
        if (!com.velorise.simplemap.SimpleMap.isMapUnlocked(this.minecraft.player)) {
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
        final int y;
        BlockInfo(String name, int y) {
            this.name = name;
            this.y = y;
        }
    }

    private void drawCoordsOverlay(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // Calculate world coordinates under the mouse cursor (viewport starts at x=1, y=1)
        double worldX = centerX + (mouseX - 1 - (this.width - 2) / 2.0) / scale;
        double worldZ = centerZ + (mouseY - 1 - (this.height - 2) / 2.0) / scale;

        int bx = (int) Math.floor(worldX);
        int bz = (int) Math.floor(worldZ);

        // Find block name and Y coordinate under cursor if the chunk is loaded on client
        String blockName = "???";
        int yCoord = 0;
        boolean hasY = false;
        if (this.minecraft != null && this.minecraft.level != null && this.minecraft.level.hasChunk(bx >> 4, bz >> 4)) {
            BlockInfo resolved = getBlockInfoAt(this.minecraft.level, bx, bz);
            if (resolved != null && !resolved.name.isEmpty()) {
                blockName = resolved.name;
                yCoord = resolved.y;
                hasY = true;
            }
        }

        String coordText;
        if (hasY) {
            coordText = String.format("Cursor X: %,d | Y: %d | Z: %,d | Block: %s", bx, yCoord, bz, blockName);
        } else {
            coordText = String.format("Cursor X: %,d | Z: %,d | Block: %s", bx, bz, blockName);
        }
        
        // Render at bottom-left corner with a semi-transparent black background
        int textWidth = this.font.width(coordText);
        guiGraphics.fill(5, this.height - 20, 15 + textWidth, this.height - 5, 0x88000000);
        guiGraphics.drawString(this.font, coordText, 10, this.height - 17, 0xFFFFFF, false);

        // Render player coordinates at bottom-right
        if (this.minecraft != null && this.minecraft.player != null) {
            String playerText = String.format("Player X: %,d | Y: %,d | Z: %,d", 
                (int) Math.floor(this.minecraft.player.getX()), 
                (int) Math.floor(this.minecraft.player.getY()), 
                (int) Math.floor(this.minecraft.player.getZ())
            );
            int pTextWidth = this.font.width(playerText);
            guiGraphics.fill(this.width - pTextWidth - 15, this.height - 20, this.width - 5, this.height - 5, 0x88000000);
            guiGraphics.drawString(this.font, playerText, this.width - pTextWidth - 10, this.height - 17, 0x00C8FF, false);
        }
    }

    private BlockInfo getBlockInfoAt(net.minecraft.world.level.Level level, int bx, int bz) {
        boolean isNether = level.dimensionType().hasCeiling();
        int minBuildHeight = level.getMinBuildHeight();
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos(bx, 0, bz);

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
            return new BlockInfo(state.getBlock().getName().getString(), highestY);
        }
        return new BlockInfo("", minBuildHeight);
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

        int menuHeight = clickedWaypoint != null ? 60 : 40;
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

        // Option 3: Follow Waypoint (Only for existing waypoints)
        if (clickedWaypoint != null) {
            boolean isCurrentlyFollowing = MapConfig.pinActive &&
                Math.abs(MapConfig.pinWorldX - clickedWaypoint.x) < 0.01 &&
                Math.abs(MapConfig.pinWorldZ - clickedWaypoint.z) < 0.01;
            boolean hoverOpt3 = mouseX >= popupX && mouseX <= popupX + 100 && mouseY >= popupY + 40 && mouseY <= popupY + 60;
            guiGraphics.fill((int)popupX + 1, (int)popupY + 41, (int)popupX + 99, (int)popupY + 59, hoverOpt3 ? 0xFF2D3033 : 0x00000000);
            String opt3Text = isCurrentlyFollowing ? "Stop Following" : "Follow Waypoint";
            guiGraphics.drawString(this.font, opt3Text, (int)popupX + 6, (int)popupY + 46, 0xFFFFFF, false);
        }

        guiGraphics.pose().popPose();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // If map is not unlocked, only allow clicking screen widgets (buttons)
        if (!SimpleMap.isMapUnlocked(this.minecraft.player)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Click on player coordinates at bottom-right to copy and share in chat
        if (this.minecraft != null && this.minecraft.player != null) {
            String playerText = String.format("Player X: %,d | Y: %,d | Z: %,d", 
                (int) Math.floor(this.minecraft.player.getX()), 
                (int) Math.floor(this.minecraft.player.getY()), 
                (int) Math.floor(this.minecraft.player.getZ())
            );
            int pTextWidth = this.font.width(playerText);
            int pX1 = this.width - pTextWidth - 15;
            int pX2 = this.width - 5;
            int pY1 = this.height - 20;
            int pY2 = this.height - 5;
            if (mouseX >= pX1 && mouseX <= pX2 && mouseY >= pY1 && mouseY <= pY2) {
                net.minecraft.world.entity.player.Player player = this.minecraft.player;
                String shareText = String.format("%s is at [%d, %d, %d]", 
                    player.getName().getString(), 
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
            int menuHeight = clickedWaypoint != null ? 60 : 40;
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
                                    targetY = 64; // Safe middle height in Nether
                                } else {
                                    int surfaceY = this.minecraft.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, tpX, tpZ);
                                    targetY = surfaceY > minBuildHeight ? surfaceY + 1 : 100;
                                }
                            }
                        } else {
                            // Unloaded chunk: Teleport to a reasonable default height (100 in Overworld, 64 in Nether)
                            targetY = isNether ? 64 : 100;
                        }

                        // Execute command using connection.sendCommand (WITHOUT leading slash) in 1.19+
                        this.minecraft.player.connection.sendCommand("tp " + tpX + " " + targetY + " " + tpZ);
                        this.onClose(); // Close map after teleporting
                    }
                } else if (clickedWaypoint != null && mouseY < popupY + 60) {
                    // Option 3: Follow Waypoint
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

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            boolean wasDragging = this.isDragging;
            this.isDragging = false;

            // If mouse barely moved, treat as a click -> place/remove pin
            double moved = Math.sqrt(Math.pow(mouseX - dragStartX, 2) + Math.pow(mouseY - dragStartZ, 2));
            if (moved < 4.0 && !isPopupMenuOpen && MapManager.getInstance().hasLearnedMap()) {
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

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!MapManager.getInstance().hasLearnedMap()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        float oldScale = this.scale;
        
        // Calculate new zoom scale (clamped between 0.05x and 12.0x)
        this.scale = Math.max(0.05f, Math.min(12.0f, this.scale + (float) scrollY * 0.15f * this.scale));

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

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause game in singleplayer
    }
}
