package com.velorise.simplemap.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

public class MinimapMaskTexture {
    private static final ResourceLocation MASK_LOCATION = ResourceLocation.fromNamespaceAndPath("simplemap",
            "gui/minimap_mask");
    private static DynamicTexture maskTexture = null;

    public static ResourceLocation getMaskLocation() {
        if (maskTexture == null) {
            register();
        }
        return MASK_LOCATION;
    }

    private static void register() {
        int width = 256;
        int height = 256;
        maskTexture = new DynamicTexture(width, height, false);
        maskTexture.setFilter(true, false); // Bilinear filtering for smooth circle edges

        NativeImage nativeImage = maskTexture.getPixels();
        if (nativeImage != null) {
            float cx = 127.5f;
            float cy = 127.5f;

            for (int y = 0; y < 256; y++) {
                for (int x = 0; x < 256; x++) {
                    float dx = x - cx;
                    float dy = y - cy;
                    double dist = Math.sqrt(dx * dx + dy * dy);

                    // ABGR pixel color values
                    int abgrColor;
                    if (dist < 114.0) {
                        abgrColor = 0x00000000; // Transparent center
                    } else if (dist < 116.0) {
                        abgrColor = 0xFF33302D; // Slate grey inner border (r=2D, g=30, b=33)
                    } else if (dist < 121.0) {
                        abgrColor = 0xFF000000; // Black outer border (r=00, g=00, b=00)
                    } else {
                        abgrColor = 0x00000000; // Transparent corners! (r=00, g=00, b=00, alpha=0)
                    }

                    nativeImage.setPixelRGBA(x, y, abgrColor);
                }
            }
            maskTexture.upload();
            Minecraft.getInstance().getTextureManager().register(MASK_LOCATION, maskTexture);
        }
    }
}
