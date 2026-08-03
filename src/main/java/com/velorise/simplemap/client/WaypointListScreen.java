package com.velorise.simplemap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Vanilla-grey waypoint manager with a dedicated create/edit workflow. */
public class WaypointListScreen extends Screen {
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
        super(Component.literal("Waypoint Manager"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        tabButtons.clear();
        int centerX = width / 2;

        searchBox = new EditBox(font, centerX - 130, 38, 260, 18,
                Component.literal("Search waypoints"));
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
        addRenderableWidget(Button.builder(Component.literal("Add Waypoint"), button -> openAddScreen())
                .bounds(centerX - 126, bottomY, 120, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
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
            MapConfig.pinActive = false;
        } else {
            MapConfig.pinWorldX = waypoint.x;
            MapConfig.pinWorldZ = waypoint.z;
            MapConfig.pinActive = true;
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
        WaypointChatShare.shareLocation(minecraft, waypoint.name,
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
}
