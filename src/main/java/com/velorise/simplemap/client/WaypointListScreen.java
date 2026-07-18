package com.velorise.simplemap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WaypointListScreen extends Screen {
    private final Screen parent;
    private int selectedTab = 0; // 0: All, 1: Overworld, 2: Nether, 3: End, 4: Modded
    private double scrollAmount = 0;
    private final List<WaypointManager.Waypoint> filteredWaypoints = new ArrayList<>();
    private EditBox searchBox;

    private static final String DIM_OVERWORLD = "minecraft:overworld";
    private static final String DIM_NETHER = "minecraft:the_nether";
    private static final String DIM_END = "minecraft:the_end";

    public WaypointListScreen(Screen parent) {
        super(Component.literal("Waypoint Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        // Search box at top
        this.searchBox = new EditBox(this.font, centerX - 120, 36, 240, 18, Component.literal("Search..."));
        this.searchBox.setHint(Component.literal("Search waypoints..."));
        this.searchBox.setResponder(text -> refreshWaypoints());
        this.addRenderableWidget(this.searchBox);

        // Dimension Tabs
        int tabW = 70;
        int tabY = 12;
        int tabStartX = centerX - (tabW * 5 + 16) / 2;

        this.addRenderableWidget(Button.builder(Component.literal("All"), b -> setTab(0))
                .bounds(tabStartX, tabY, tabW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Overworld"), b -> setTab(1))
                .bounds(tabStartX + tabW + 4, tabY, tabW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Nether"), b -> setTab(2))
                .bounds(tabStartX + (tabW + 4) * 2, tabY, tabW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("End"), b -> setTab(3))
                .bounds(tabStartX + (tabW + 4) * 3, tabY, tabW, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("Modded"), b -> setTab(4))
                .bounds(tabStartX + (tabW + 4) * 4, tabY, tabW, 18).build());

        // Bottom action buttons
        int bottomY = this.height - 28;
        this.addRenderableWidget(Button.builder(Component.literal(" Add Waypoint"), b -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                double px = this.minecraft.player.getX();
                double pz = this.minecraft.player.getZ();
                String dim = MapManager.getInstance().getCurrentDimensionId();
                this.minecraft.setScreen(new AddWaypointScreen(this, px, pz, dim));
            }
        }).bounds(centerX - 130, bottomY, 120, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
            if (this.minecraft != null) this.minecraft.setScreen(this.parent);
        }).bounds(centerX + 10, bottomY, 120, 20).build());

        refreshWaypoints();
    }

    private void setTab(int tab) {
        this.selectedTab = tab;
        this.scrollAmount = 0;
        refreshWaypoints();
    }

    private void refreshWaypoints() {
        filteredWaypoints.clear();
        List<WaypointManager.Waypoint> all = new ArrayList<>();
        for (WaypointManager.Waypoint waypoint : WaypointManager.getInstance().getAllWaypoints()) {
            boolean include = switch (selectedTab) {
                case 1 -> isVanillaDimension(waypoint.dimension, "overworld");
                case 2 -> isVanillaDimension(waypoint.dimension, "the_nether");
                case 3 -> isVanillaDimension(waypoint.dimension, "the_end");
                case 4 -> !isVanillaDimension(waypoint.dimension, "overworld")
                        && !isVanillaDimension(waypoint.dimension, "the_nether")
                        && !isVanillaDimension(waypoint.dimension, "the_end");
                default -> true;
            };
            if (include) all.add(waypoint);
        }

        String filter = searchBox != null ? searchBox.getValue().trim().toLowerCase(Locale.ROOT) : "";
        for (WaypointManager.Waypoint wp : all) {
            if (filter.isEmpty() || wp.name.toLowerCase(Locale.ROOT).contains(filter)
                    || String.valueOf((int) wp.x).contains(filter)
                    || String.valueOf((int) wp.z).contains(filter)) {
                filteredWaypoints.add(wp);
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int listTop = 60;
        int listBottom = this.height - 34;
        if (mouseY >= listTop && mouseY <= listBottom) {
            int rowHeight = 32;
            int maxScroll = Math.max(0, filteredWaypoints.size() * rowHeight - (listBottom - listTop));
            this.scrollAmount = Math.max(0, Math.min(maxScroll, this.scrollAmount - scrollY * 20));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        int listTop = 60;
        int listBottom = this.height - 34;
        if (mouseY >= listTop && mouseY <= listBottom && button == 0) {
            int centerX = this.width / 2;
            int rowHeight = 32;
            int startY = listTop - (int) scrollAmount;

            for (int i = 0; i < filteredWaypoints.size(); i++) {
                int rowY = startY + i * rowHeight;
                if (rowY + rowHeight < listTop || rowY > listBottom) continue;

                WaypointManager.Waypoint wp = filteredWaypoints.get(i);
                int rightX = centerX + 180;

                // Button hitboxes: [Track] [TP] [Delete]
                int btnW = 40;
                int delX = rightX - btnW - 4;
                int tpX = delX - btnW - 4;
                int trackX = tpX - btnW - 4;

                if (mouseY >= rowY + 6 && mouseY <= rowY + 26) {
                    if (mouseX >= trackX && mouseX <= trackX + btnW) {
                        // Track / Pin waypoint
                        boolean isCurrentlyTracked = MapConfig.pinActive
                                && Math.abs(MapConfig.pinWorldX - wp.x) < 0.01
                                && Math.abs(MapConfig.pinWorldZ - wp.z) < 0.01;
                        if (isCurrentlyTracked) {
                            MapConfig.pinActive = false;
                        } else {
                            MapConfig.pinWorldX = wp.x;
                            MapConfig.pinWorldZ = wp.z;
                            MapConfig.pinActive = true;
                        }
                        MapManager.getInstance().savePin();
                        return true;
                    }
                    if (mouseX >= tpX && mouseX <= tpX + btnW) {
                        String targetDimension = MapManager.getInstance()
                                .resolveDimensionResourceId(wp.dimension);
                        int targetY = MapTeleportController.defaultTargetY(targetDimension);
                        if (MapTeleportController.teleport(this.minecraft, targetDimension,
                                (int) Math.floor(wp.x), targetY, (int) Math.floor(wp.z))) {
                            MapManager.getInstance().returnToLiveDimension(this.minecraft);
                            MapViewportCoordinator.getInstance().closeFullscreen();
                            this.minecraft.setScreen(null);
                        }
                        return true;
                    }
                    if (mouseX >= delX && mouseX <= delX + btnW) {
                        // Delete waypoint
                        WaypointManager.getInstance().removeWaypoint(wp);
                        refreshWaypoints();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Minecraft 1.21 applies a blur shader to the parent screen by default.
        // This screen draws an opaque UI background itself, so suppress that pass.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        // Dark background overlay
        guiGraphics.fill(0, 0, this.width, this.height, 0xF0101014);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 100.0F);

        int centerX = this.width / 2;
        int listTop = 60;
        int listBottom = this.height - 34;
        int listWidth = 370;
        int listLeft = centerX - listWidth / 2;

        // Render List Background
        guiGraphics.fill(listLeft, listTop, listLeft + listWidth, listBottom, 0xFF18181F);
        guiGraphics.renderOutline(listLeft, listTop, listWidth, listBottom - listTop, 0xFF353545);

        // Render Waypoint Rows
        guiGraphics.enableScissor(listLeft + 1, listTop + 1, listLeft + listWidth - 1, listBottom - 1);
        int rowHeight = 32;
        int startY = listTop - (int) scrollAmount;

        for (int i = 0; i < filteredWaypoints.size(); i++) {
            int rowY = startY + i * rowHeight;
            if (rowY + rowHeight < listTop || rowY > listBottom) continue;

            WaypointManager.Waypoint wp = filteredWaypoints.get(i);
            boolean hover = mouseX >= listLeft && mouseX <= listLeft + listWidth
                    && mouseY >= rowY && mouseY < rowY + rowHeight
                    && mouseY >= listTop && mouseY <= listBottom;

            guiGraphics.fill(listLeft + 2, rowY + 1, listLeft + listWidth - 2, rowY + rowHeight - 1,
                    hover ? 0xFF2A2A38 : (i % 2 == 0 ? 0xFF1D1D26 : 0xFF22222E));

            // Render Item/Icon
            renderWaypointIcon(guiGraphics, wp, listLeft + 8, rowY + 8);

            // Render Name & Coordinates
            guiGraphics.drawString(this.font, wp.name, listLeft + 30, rowY + 6, wp.deathPoint ? 0xFFFF5555 : 0xFFFFFFFF, false);
            String coords = String.format("X: %d  Z: %d (%s)", (int) wp.x, (int) wp.z, simplifyDimension(wp.dimension));
            guiGraphics.drawString(this.font, coords, listLeft + 30, rowY + 18, 0x88AAAAAA, false);

            // Render Action Buttons
            int rightX = listLeft + listWidth - 10;
            int btnW = 40;
            int delX = rightX - btnW - 4;
            int tpX = delX - btnW - 4;
            int trackX = tpX - btnW - 4;

            boolean isTracked = MapConfig.pinActive
                    && Math.abs(MapConfig.pinWorldX - wp.x) < 0.01
                    && Math.abs(MapConfig.pinWorldZ - wp.z) < 0.01;

            drawCompactButton(guiGraphics, trackX, rowY + 6, btnW, 18, isTracked ? "Tracked" : "Track",
                    isTracked ? 0xFF2A8040 : 0xFF353545, mouseX, mouseY);
            drawCompactButton(guiGraphics, tpX, rowY + 6, btnW, 18, "TP", 0xFF353545, mouseX, mouseY);
            drawCompactButton(guiGraphics, delX, rowY + 6, btnW, 18, "Delete", 0xFF802A2A, mouseX, mouseY);
        }
        guiGraphics.disableScissor();
        guiGraphics.pose().popPose();

        // Render widgets on the same unblurred UI plane, above item icons and rows.
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 200.0F);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.pose().popPose();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void renderWaypointIcon(GuiGraphics guiGraphics, WaypointManager.Waypoint wp, int x, int y) {
        if (wp.iconItem != null && !wp.iconItem.isEmpty()) {
            ResourceLocation loc = ResourceLocation.tryParse(wp.iconItem);
            if (loc != null) {
                var item = BuiltInRegistries.ITEM.get(loc);
                if (item != null && item != Items.AIR) {
                    guiGraphics.renderItem(new ItemStack(item), x, y - 2);
                    RenderSystem.disableDepthTest();
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    return;
                }
            }
        }
        // Fallback icon
        guiGraphics.renderItem(new ItemStack(Items.COMPASS), x, y - 2);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private void drawCompactButton(GuiGraphics guiGraphics, int x, int y, int width, int height,
            String text, int bgColor, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        int color = hover ? 0xFF555566 : bgColor;
        guiGraphics.fill(x, y, x + width, y + height, color);
        guiGraphics.renderOutline(x, y, width, height, 0xFF666677);
        int textW = this.font.width(text);
        guiGraphics.drawString(this.font, text, x + (width - textW) / 2, y + (height - 8) / 2, 0xFFFFFFFF, false);
    }

    private static boolean isVanillaDimension(String dimension, String path) {
        if (dimension == null) return false;
        String normalized = dimension.toLowerCase(Locale.ROOT);
        return normalized.equals(path) || normalized.equals("minecraft:" + path)
                || (path.equals("the_nether") && normalized.equals("nether"))
                || (path.equals("the_end") && normalized.equals("end"));
    }

    private String simplifyDimension(String dim) {
        if (dim == null || dim.isBlank()) return "Unknown";
        return MapManager.displayDimensionName(dim);
    }
}
