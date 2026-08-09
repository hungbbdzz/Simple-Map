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

    /**
     * Splits a fractional retained-texture window into integer texel bounds plus a
     * subpixel destination translation. This is the same stability principle used
     * by Xaero's primary/secondary map offsets: atlas/FBO sampling stays on whole
     * texels while the residual camera motion remains smooth in screen space.
     */
    static PixelAlignedAxis pixelAlignedAxis(float rawOrigin, float sourceSpan,
            int viewportPixels, int texturePixels) {
        if (!Float.isFinite(rawOrigin) || !Float.isFinite(sourceSpan)
                || sourceSpan <= 0.0f || viewportPixels <= 0
                || texturePixels <= 0) return PixelAlignedAxis.invalid();
        double rawEnd = (double) rawOrigin + sourceSpan;
        int sourceStart = Math.max(0, (int) Math.floor(rawOrigin));
        int sourceEnd = Math.min(texturePixels, (int) Math.ceil(rawEnd));
        if (sourceEnd <= sourceStart) return PixelAlignedAxis.invalid();
        float destinationPixelsPerSource = viewportPixels / sourceSpan;
        float destinationOffset = (sourceStart - rawOrigin)
                * destinationPixelsPerSource;
        float destinationSpan = (sourceEnd - sourceStart)
                * destinationPixelsPerSource;
        if (!Float.isFinite(destinationOffset)
                || !Float.isFinite(destinationSpan)
                || destinationSpan <= 0.0f) return PixelAlignedAxis.invalid();
        return new PixelAlignedAxis(sourceStart, sourceEnd - sourceStart,
                destinationOffset, destinationSpan, true);
    }

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

    record PixelAlignedAxis(float sourceOrigin, float sourceSpan,
            float destinationOffsetPixels, float destinationSpanPixels,
            boolean valid) {
        private static PixelAlignedAxis invalid() {
            return new PixelAlignedAxis(Float.NaN, Float.NaN,
                    Float.NaN, Float.NaN, false);
        }
    }
}
