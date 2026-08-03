package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dedicated create/edit workflow for waypoints. The form is an independent
 * screen rather than a visual layer over WaypointListScreen, so hidden widgets
 * from the manager cannot receive clicks or render above/below the dialog.
 */
public class AddWaypointScreen extends Screen {
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
        super(Component.literal("Add Waypoint"));
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
        super(Component.literal("Edit Waypoint"));
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
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
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
            MapConfig.pinWorldX = replacement.x;
            MapConfig.pinWorldZ = replacement.z;
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
        // The form owns an opaque vanilla-style background; no blurred parent layer.
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
        // A light veil keeps the form readable while preserving exact placement
        // context, terrain scale and nearby saved markers behind it.
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
}
