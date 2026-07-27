package com.velorise.simplemap.client;

/**
 * Immutable summary of map texture content emitted by one render pass.
 *
 * <p>HUD composition must not treat a valid framebuffer bind as proof that map
 * data was rendered. This result separates GPU-path success from actual map
 * coverage, allowing the minimap to fall back safely when the exact/LOD caches
 * have not produced any visible texture yet.</p>
 */
public record MapDrawResult(
        int exactPagesDrawn,
        int branchNodesDrawn,
        int legacyFallbacksDrawn) {

    public static final MapDrawResult EMPTY = new MapDrawResult(0, 0, 0);

    public boolean drewAnyMapContent() {
        return exactPagesDrawn > 0 || branchNodesDrawn > 0 || legacyFallbacksDrawn > 0;
    }
}
