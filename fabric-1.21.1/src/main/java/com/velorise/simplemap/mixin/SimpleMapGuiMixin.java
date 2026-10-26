package com.velorise.simplemap.mixin;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renders the minimap HUD and drains the map publication frame at the end of Gui.render(). */
@Mixin(Gui.class)
public abstract class SimpleMapGuiMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void simplemap$onRenderGui(GuiGraphics guiGraphics, DeltaTracker deltaTracker,
            CallbackInfo callback) {
        SimpleMap.ClientGameEvents.onRenderGui(guiGraphics);
    }
}
