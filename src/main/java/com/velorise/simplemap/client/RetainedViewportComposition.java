package com.velorise.simplemap.client;

/**
 * Allocation-free source-window math shared by retained fullscreen composition.
 *
 * <p>The terrain target is rendered at a stable world scale. Presentation zoom
 * (including MapScreen's opening animation) is then applied by sampling a
 * centred sub-rectangle. Keeping this arithmetic outside the OpenGL renderer
 * makes scale alignment independently testable.</p>
 */
final class RetainedViewportComposition {
    private RetainedViewportComposition() { }

    static float sourceSpan(int viewportPixels, float renderPixelsPerBlock,
            float displayPixelsPerBlock) {
        if (viewportPixels <= 0
                || !Float.isFinite(renderPixelsPerBlock)
                || !Float.isFinite(displayPixelsPerBlock)
                || renderPixelsPerBlock <= 0.0f
                || displayPixelsPerBlock <= 0.0f) {
            return Float.NaN;
        }
        float result = viewportPixels
                * (renderPixelsPerBlock / displayPixelsPerBlock);
        return Float.isFinite(result) && result > 0.0f
                ? result : Float.NaN;
    }

    static float sourceOrigin(int overscanPixels, int viewportPixels,
            float sourceSpan, double center, double anchor,
            float renderPixelsPerBlock) {
        if (overscanPixels < 0 || viewportPixels <= 0
                || !Float.isFinite(sourceSpan) || sourceSpan <= 0.0f
                || !Double.isFinite(center) || !Double.isFinite(anchor)
                || !Float.isFinite(renderPixelsPerBlock)
                || renderPixelsPerBlock <= 0.0f) {
            return Float.NaN;
        }
        double result = overscanPixels
                + (viewportPixels - sourceSpan) * 0.5
                + (center - anchor) * renderPixelsPerBlock;
        return Double.isFinite(result) ? (float) result : Float.NaN;
    }
}
