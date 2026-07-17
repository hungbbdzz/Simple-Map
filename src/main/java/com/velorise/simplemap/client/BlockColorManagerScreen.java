package com.velorise.simplemap.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Searchable editor for every per-block custom color override. */
public final class BlockColorManagerScreen extends Screen {
    private static final int ROWS = 8;
    private static final int CONTENT_WIDTH = 420;
    private static final int PANEL_PADDING = 8;
    private final Screen parent;
    private final List<Map.Entry<String, Integer>> filtered = new ArrayList<>();
    private EditBox search;
    private int page;
    private boolean confirmClear;

    public BlockColorManagerScreen(Screen parent) {
        super(Component.literal("Custom Block Colors"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = panelLeft();
        int top = panelTop();
        search = new EditBox(font, left, top + 24, 300, 20, Component.literal("Search block ID"));
        search.setHint(Component.literal("Search registry ID..."));
        search.setResponder(value -> {
            page = 0;
            rebuildRows();
        });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> closeToParent())
                .bounds(left + 322, top + 24, 98, 20).build());
        rebuildRows();
    }

    private void rebuildRows() {
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        MapConfig.blockColorOverrides.entrySet().stream()
                .filter(entry -> query.isEmpty() || entry.getKey().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(filtered::add);
        int maxPage = Math.max(0, (filtered.size() - 1) / ROWS);
        page = Math.max(0, Math.min(page, maxPage));

        clearWidgets();
        int left = panelLeft();
        int top = panelTop();
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> closeToParent())
                .bounds(left + 322, top + 24, 98, 20).build());

        int start = page * ROWS;
        for (int row = 0; row < ROWS && start + row < filtered.size(); row++) {
            Map.Entry<String, Integer> entry = filtered.get(start + row);
            String id = entry.getKey();
            int y = top + 52 + row * 23;
            addRenderableWidget(Button.builder(Component.literal("Edit"), b -> edit(id))
                    .bounds(left + 306, y, 52, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
                BlockColorOverrideController.reset(id);
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
                BlockColorOverrideController.clearAll();
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
            Map.Entry<String, Integer> entry = filtered.get(start + row);
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
}
