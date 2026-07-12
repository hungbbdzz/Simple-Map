package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;

public class AddWaypointScreen extends Screen {
    private final Screen parent;
    private final double worldX;
    private final double worldZ;
    private final String dimension;

    private EditBox nameInput;
    private SimpleSlider globalScaleSlider;
    private SimpleSlider localScaleSlider;
    private float waypointScale = 1.0f; // local waypoint scale

    // Icon Selection State (Defaulting to Compass item)
    private int selectedIconType = -1; // -1 for custom items
    private String selectedIconItem = "minecraft:compass";

    // Category Tab State (0: Mining, 1: Combat, 2: Travel, 3: Food, 4: Search)
    private int activeTab = 0;

    // Search Box & Results
    private EditBox searchBox;
    private final List<String> searchResults = new ArrayList<>();
    private final List<Button> searchResultButtons = new ArrayList<>();

    // UI elements lists to update dynamically
    private final List<Button> iconSelectorButtons = new ArrayList<>();

    private static final String[] MINING_ITEMS = {
            "minecraft:iron_pickaxe", "minecraft:iron_axe", "minecraft:minecart",
            "minecraft:raw_iron"
    };
    private static final String[] COMBAT_ITEMS = {
            "minecraft:iron_sword", "minecraft:bow", "minecraft:nether_star",
            "minecraft:experience_bottle"
    };
    private static final String[] TRAVEL_ITEMS = {
            "minecraft:compass", "minecraft:ender_eye", "minecraft:elytra", "minecraft:red_bed"
    };
    private static final String[] FOOD_ITEMS = {
            "minecraft:golden_apple", "minecraft:bread", "minecraft:cooked_beef"
    };

    public AddWaypointScreen(Screen parent, double worldX, double worldZ, String dimension) {
        super(Component.literal("Add Waypoint"));
        this.parent = parent;
        this.worldX = worldX;
        this.worldZ = worldZ;
        this.dimension = dimension;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int count = WaypointManager.getInstance().getWaypointsForDimension(dimension).size() + 1;
        String defaultName = "Waypoint " + count;

        // ==================== LEFT COLUMN: Name, Done, Cancel, Global & Local Scale
        // ====================
        // Name Input Field
        this.nameInput = new EditBox(this.font, centerX - 140, centerY - 45, 90, 20,
                Component.literal("Waypoint Name"));
        this.nameInput.setValue("");
        this.nameInput.setHint(Component.literal(defaultName));
        this.addRenderableWidget(this.nameInput);

        // Save Button
        this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> {
            String name = this.nameInput.getValue().trim();
            if (name.isEmpty()) {
                int currentCount = WaypointManager.getInstance().getWaypointsForDimension(dimension).size() + 1;
                name = "Waypoint " + currentCount;
            }
            WaypointManager.getInstance().addWaypoint(new WaypointManager.Waypoint(
                    name, worldX, worldZ, selectedIconType, selectedIconItem, waypointScale, dimension));
            if (this.minecraft != null) {
                this.minecraft.setScreen(this.parent);
            }
        }).bounds(centerX - 140, centerY - 15, 43, 20).build());

        // Cancel Button
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(this.parent);
            }
        }).bounds(centerX - 92, centerY - 15, 42, 20).build());

        // Global Waypoint Scale Slider
        double initialGlobalVal = (MapConfig.waypointScale - 1.0) / (10.0 - 1.0);
        this.globalScaleSlider = new SimpleSlider(
                centerX - 140, centerY + 10, 90, 20,
                initialGlobalVal,
                val -> String.format("Global: %.1fx", 1.0 + val * (10.0 - 1.0)),
                val -> {
                    MapConfig.waypointScale = (float) (1.0 + val * (10.0 - 1.0));
                    MapConfig.save();
                });
        this.addRenderableWidget(this.globalScaleSlider);

        // Local Waypoint Scale Slider
        double initialLocalVal = (waypointScale - 1.0) / (5.0 - 1.0);
        this.localScaleSlider = new SimpleSlider(
                centerX - 140, centerY + 35, 90, 20,
                initialLocalVal,
                val -> String.format("Local: %.1fx", 1.0 + val * (5.0 - 1.0)),
                val -> this.waypointScale = (float) (1.0 + val * (5.0 - 1.0)));
        this.addRenderableWidget(this.localScaleSlider);

        // ==================== MIDDLE COLUMN: Tabs & Icon selection
        // ====================
        // Category Tabs (Row 1) - 5 tabs drawn on 1 single row
        String[] tabNames = { "Mining", "Combat", "Travel", "Food", "Search" };
        int tabW = 38;
        int tabSpacing = 2;
        int tabStartX = centerX - 40;

        for (int i = 0; i < 5; i++) {
            int tabIndex = i;
            this.addRenderableWidget(Button.builder(Component.literal(tabNames[i]), button -> {
                this.activeTab = tabIndex;
                updateIconSelectorVisibility();
            }).bounds(tabStartX + i * (tabW + tabSpacing), centerY - 45, tabW, 16).build());
        }

        // Initialize Custom Item Buttons (Mining, Combat, Travel, Food)
        int iconsStartX = centerX - 20;
        int iconsY = centerY - 15;
        addItemGroupButtons(MINING_ITEMS, iconsStartX, iconsY);
        addItemGroupButtons(COMBAT_ITEMS, iconsStartX, iconsY);
        addItemGroupButtons(TRAVEL_ITEMS, iconsStartX, iconsY);
        addItemGroupButtons(FOOD_ITEMS, iconsStartX, iconsY);

        // Initialize Search Box (displays on tab 4)
        this.searchBox = new EditBox(this.font, centerX - 40, centerY - 15, 198, 16, Component.literal("Search Items"));
        this.searchBox.setMaxLength(20);
        this.searchBox.setValue("");
        this.searchBox.setHint(Component.literal("Search item..."));
        this.searchBox.setResponder(text -> updateSearchResults());
        this.addRenderableWidget(this.searchBox);

        // Initialize 9 search result buttons placeholders
        int searchResultsStartX = centerX - 40;
        int searchResultsY = centerY + 10;
        for (int i = 0; i < 9; i++) {
            final int index = i;
            ItemIconButton btn = new ItemIconButton(searchResultsStartX + i * 22, searchResultsY, 20, 20, -1, "", b -> {
                if (index < searchResults.size()) {
                    this.selectedIconType = -1;
                    this.selectedIconItem = searchResults.get(index);
                }
            });
            btn.visible = false;
            this.searchResultButtons.add(btn);
            this.addRenderableWidget(btn);
        }

        // Apply tab visibility sync
        updateIconSelectorVisibility();
    }

    private void addItemGroupButtons(String[] itemsList, int startX, int y) {
        for (int i = 0; i < itemsList.length; i++) {
            String itemID = itemsList[i];
            ItemIconButton btn = new ItemIconButton(startX + i * 26, y, 22, 22, -1, itemID, b -> {
                this.selectedIconType = -1;
                this.selectedIconItem = itemID;
            });
            this.iconSelectorButtons.add(btn);
            this.addRenderableWidget(btn);
        }
    }

    private void updateIconSelectorVisibility() {
        // Hide all buttons first
        for (Button btn : iconSelectorButtons) {
            btn.visible = false;
        }
        if (searchBox != null)
            searchBox.visible = false;
        for (Button btn : searchResultButtons) {
            btn.visible = false;
        }

        // Show active tab elements
        if (activeTab == 0) { // Mining
            for (Button btn : iconSelectorButtons) {
                if (btn instanceof ItemIconButton ib && ib.iconType == -1 && isItemInGroup(ib.itemID, MINING_ITEMS)) {
                    btn.visible = true;
                }
            }
        } else if (activeTab == 1) { // Combat
            for (Button btn : iconSelectorButtons) {
                if (btn instanceof ItemIconButton ib && ib.iconType == -1 && isItemInGroup(ib.itemID, COMBAT_ITEMS)) {
                    btn.visible = true;
                }
            }
        } else if (activeTab == 2) { // Travel
            for (Button btn : iconSelectorButtons) {
                if (btn instanceof ItemIconButton ib && ib.iconType == -1 && isItemInGroup(ib.itemID, TRAVEL_ITEMS)) {
                    btn.visible = true;
                }
            }
        } else if (activeTab == 3) { // Food
            for (Button btn : iconSelectorButtons) {
                if (btn instanceof ItemIconButton ib && ib.iconType == -1 && isItemInGroup(ib.itemID, FOOD_ITEMS)) {
                    btn.visible = true;
                }
            }
        } else if (activeTab == 4) { // Search
            if (searchBox != null) {
                searchBox.visible = true;
                updateSearchResults();
            }
        }
    }

    private boolean isItemInGroup(String id, String[] group) {
        for (String item : group) {
            if (item.equals(id))
                return true;
        }
        return false;
    }

    private void updateSearchResults() {
        searchResults.clear();
        if (activeTab != 4 || searchBox == null)
            return;

        String query = searchBox.getValue().trim().toLowerCase();
        int count = 0;
        if (query.isEmpty()) {
            // Show first 9 items from A to Z as default suggestion list
            for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
                if (key.getPath().equals("air"))
                    continue;
                searchResults.add(key.toString());
                count++;
                if (count >= 9)
                    break; // Limit to 9 results
            }
        } else {
            // Filter matching query
            for (ResourceLocation key : BuiltInRegistries.ITEM.keySet()) {
                String idStr = key.toString();
                String path = key.getPath();
                if (path.contains(query) || idStr.contains(query)) {
                    searchResults.add(idStr);
                    count++;
                    if (count >= 9)
                        break;
                }
            }
        }

        // Update placeholder buttons
        for (int i = 0; i < 9; i++) {
            ItemIconButton btn = (ItemIconButton) searchResultButtons.get(i);
            if (i < searchResults.size()) {
                btn.itemID = searchResults.get(i);
                btn.visible = true;
            } else {
                btn.itemID = "";
                btn.visible = false;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Override to prevent background blur or dirt rendering.
        // We draw the full dark overlay in render() instead.
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 1. Render MapScreen in the background cleanly if parent exists
        if (this.parent != null) {
            this.parent.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        // 2. Overlay a semi-transparent dark shade to focus on the dialog
        guiGraphics.fill(0, 0, this.width, this.height, 0x88000000);

        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Draw Titles
        guiGraphics.drawString(this.font, "Waypoint Creator", centerX - 140, centerY - 65, 0xFFFFFF, false);
        guiGraphics.drawString(this.font, "Select Icon", centerX - 40, centerY - 60, 0x00C8FF, false);

        // 3. Render Live Waypoint Preview directly on the Map background (at exact
        // world coords)
        if (this.parent instanceof MapScreen mapScreen) {
            double mapCenterX = mapScreen.getCenterX();
            double mapCenterZ = mapScreen.getCenterZ();
            float mapScale = mapScreen.getScale();

            // Calculate exact screen coordinates matching map viewport
            double screenX = this.width / 2.0 + (worldX - mapCenterX) * mapScale;
            double screenY = this.height / 2.0 + (worldZ - mapCenterZ) * mapScale;

            String itemID = selectedIconItem;
            if (itemID.isEmpty()) {
                itemID = "minecraft:compass";
            }
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemID));
            if (item != Items.AIR) {
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(screenX, screenY, 15); // render on top of overlay shade

                // Scale factor includes both global setting, local setting and the map's
                // current zoom scale
                float previewScale = 0.08f * MapConfig.waypointScale * waypointScale * mapScale;
                guiGraphics.pose().scale(previewScale, previewScale, 1.0f);

                // Draw item centered
                guiGraphics.renderFakeItem(new ItemStack(item), -8, -8);
                guiGraphics.pose().popPose();
            }
        }

        // Render normal buttons/widgets
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    // Custom slider for scale adjustment
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

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(labelProvider.apply(this.value)));
        }

        @Override
        protected void applyValue() {
            onChange.accept(this.value);
        }
    }

    // Custom Button that renders a 2D Item Texture
    private static class ItemIconButton extends Button {
        public final int iconType;
        public String itemID;

        public ItemIconButton(int x, int y, int width, int height, int iconType, String itemID, OnPress onPress) {
            super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
            this.iconType = iconType;
            this.itemID = itemID;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);

            if (visible) {
                if (!itemID.isEmpty()) {
                    Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemID));
                    if (item != Items.AIR) {
                        guiGraphics.renderFakeItem(new ItemStack(item), getX() + (width - 16) / 2,
                                getY() + (height - 16) / 2);
                    }
                }
            }
        }
    }
}
