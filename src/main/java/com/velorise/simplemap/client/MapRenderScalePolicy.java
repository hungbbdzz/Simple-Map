package com.velorise.simplemap.client;

/**
 * Converts Minecraft GUI-space map zoom into real framebuffer density.
 *
 * <p>Map widgets are laid out in logical GUI pixels, while texture sampling and
 * LOD selection happen on the physical framebuffer. At GUI scale 3, a logical
 * zoom of 0.42 already covers roughly 1.26 physical pixels per block and must use
 * exact L0 data. Treating 0.42 as the screen density incorrectly selects L1, then
 * upscales it three times, producing the visible "blur first, sharpen later"
 * transition that Xaero avoids.</p>
 */
public final class MapRenderScalePolicy {
    private MapRenderScalePolicy() {
    }

    public static float physicalPixelsPerBlock(float logicalPixelsPerBlock,
            double guiScale) {
        float logical = Math.max(0.0001f, logicalPixelsPerBlock);
        return (float) (logical * Math.max(1.0, guiScale));
    }

    public static int physicalPixels(int logicalPixels, double guiScale) {
        return Math.max(1, (int) Math.ceil(Math.max(1, logicalPixels)
                * Math.max(1.0, guiScale)));
    }

    public static Scales fullscreenFbo(float logicalPixelsPerBlock,
            double guiScale) {
        float physical = physicalPixelsPerBlock(logicalPixelsPerBlock, guiScale);
        // The off-screen target and the final framebuffer have the same physical
        // texel density. LOD policy therefore uses physical density too.
        return new Scales(physical, physical);
    }

    public record Scales(float renderPixelsPerBlock, float policyPixelsPerBlock) {
    }
}
