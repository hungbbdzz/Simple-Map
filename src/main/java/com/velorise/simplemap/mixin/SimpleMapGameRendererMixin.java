package com.velorise.simplemap.mixin;

import com.velorise.simplemap.client.MapScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The fullscreen map is completely opaque. Rendering the entire 3D level before
 * drawing it wastes the dominant GPU pass and was still visible in telemetry as
 * 84% GPU utilization after all map queues had drained.
 */
@Mixin(GameRenderer.class)
public abstract class SimpleMapGameRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"),
            argsOnly = true, ordinal = 0)
    private boolean simplemap$skipLevelBehindFullscreenMap(boolean renderLevel) {
        Minecraft minecraft = Minecraft.getInstance();
        return renderLevel && !(minecraft.screen instanceof MapScreen);
    }
}
