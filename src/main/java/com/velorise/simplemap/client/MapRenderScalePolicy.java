package com.velorise.simplemap.client;

/**
 * Keeps screen-density decisions independent from off-screen target resolution.
 *
 * <p>A fullscreen FBO is allocated in physical pixels, so its geometry scale is
 * multiplied by the GUI scale. LOD selection, demand trimming and scheduling must
 * still use the logical screen pixels-per-block seen by the player; otherwise every
 * threshold silently shifts when Minecraft GUI scale changes.</p>
 */
public final class MapRenderScalePolicy {
    private MapRenderScalePolicy() {
    }

    public static Scales fullscreenFbo(float logicalPixelsPerBlock, double guiScale) {
        float logical = Math.max(0.0001f, logicalPixelsPerBlock);
        float physical = (float) (logical * Math.max(1.0, guiScale));
        return new Scales(physical, logical);
    }

    public record Scales(float renderPixelsPerBlock, float policyPixelsPerBlock) {
    }
}
