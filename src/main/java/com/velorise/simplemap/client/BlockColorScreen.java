package com.velorise.simplemap.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Small editor for a single registry-backed surface-block color override. */
public class BlockColorScreen extends Screen {
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
        colorInput.setValue(ColorCode.format(currentColor));
        colorInput.setHint(Component.literal("#RRGGBB"));
        colorInput.setResponder(value -> {
            try {
                ColorCode.parse(value);
                colorInput.setTextColor(0xFFFFFFFF);
            } catch (IllegalArgumentException ignored) {
                colorInput.setTextColor(0xFFFF5555);
            }
        });
        addRenderableWidget(colorInput);

        addRenderableWidget(Button.builder(Component.literal("Save"), button -> saveOverride())
                .bounds(left + 166, top + 48, 74, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Automatic"), button -> resetOverride())
                .bounds(left, top + 128, 116, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> closeToParent())
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
            int color = ColorCode.parse(colorInput.getValue());
            MapConfig.blockColorOverrides.put(blockId, color);
            rememberColor(color);
            MapConfig.save();
            rescanSelectedColumn();
            closeToParent();
        } catch (IllegalArgumentException ignored) {
            colorInput.setTextColor(0xFFFF5555);
        }
    }

    private void resetOverride() {
        MapConfig.blockColorOverrides.remove(blockId);
        MapConfig.save();
        rescanSelectedColumn();
        closeToParent();
    }

    private void rememberColor(int color) {
        MapConfig.savedColors.remove(Integer.valueOf(color));
        MapConfig.savedColors.add(0, color);
        while (MapConfig.savedColors.size() > 8) {
            MapConfig.savedColors.remove(MapConfig.savedColors.size() - 1);
        }
    }

    private void rescanSelectedColumn() {
        if (this.minecraft != null && this.minecraft.level != null) {
            ChunkScanner.getInstance().scanDisplayedColumn(this.minecraft, blockX, blockZ);
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
        // Keep the map visible beneath the compact editor.
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
                previewColor = ColorCode.parse(colorInput.getValue());
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
            super(x, y, width, height, Component.literal("Use " + ColorCode.format(color)));
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
                colorInput.setValue(ColorCode.format(color));
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
