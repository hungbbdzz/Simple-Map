package com.velorise.simplemap.client;

/** Dependency-free checks for retained fullscreen presentation transforms. */
public final class RetainedViewportCompositionCheck {
    private RetainedViewportCompositionCheck() { }

    public static void main(String[] args) {
        float unchanged = RetainedViewportComposition.sourceSpan(
                1600, 0.35f, 0.35f);
        near(unchanged, 1600.0f, 0.001f,
                "stable fullscreen zoom changed source span");
        near(RetainedViewportComposition.sourceOrigin(
                        64, 1600, unchanged, 100.0, 100.0, 0.35f),
                64.0f, 0.001f,
                "stable fullscreen zoom was not centred on the guard");

        float opening = RetainedViewportComposition.sourceSpan(
                1600, 0.35f, 0.42f);
        near(opening, 1333.3334f, 0.01f,
                "1.2x opening zoom source span is wrong");
        near(RetainedViewportComposition.sourceOrigin(
                        64, 1600, opening, 100.0, 100.0, 0.35f),
                197.3333f, 0.01f,
                "opening zoom source rectangle is not centred");

        float panned = RetainedViewportComposition.sourceOrigin(
                64, 1600, unchanged, 110.0, 100.0, 0.35f);
        near(panned, 67.5f, 0.001f,
                "world pan was not converted with stable terrain scale");

        RetainedViewportComposition.PixelAlignedAxis aligned =
                RetainedViewportComposition.pixelAlignedAxis(
                        panned, unchanged, 1600, 1728);
        require(aligned.valid(), "fractional retained source could not be aligned");
        near(aligned.sourceOrigin(), 67.0f, 0.001f,
                "retained source did not start on a whole texel");
        near(aligned.sourceSpan(), 1601.0f, 0.001f,
                "retained source did not end on a whole texel");
        near(aligned.destinationOffsetPixels(), -0.5f, 0.001f,
                "subpixel camera residual was not moved to screen space");
        near(aligned.destinationSpanPixels(), 1601.0f, 0.001f,
                "pixel-aligned destination no longer preserves source slope");
        verifyEquivalentTransform(67.25f, 1333.3334f,
                1600, 1728, 0.0f);
        verifyEquivalentTransform(67.25f, 1333.3334f,
                1600, 1728, 800.0f);
        verifyEquivalentTransform(67.25f, 1333.3334f,
                1600, 1728, 1600.0f);

        require(Float.isNaN(RetainedViewportComposition.sourceSpan(
                        1600, 0.0f, 0.35f)),
                "invalid render scale was accepted");
        require(Float.isNaN(RetainedViewportComposition.sourceOrigin(
                        64, 1600, Float.NaN, 0.0, 0.0, 0.35f)),
                "invalid source span was accepted");
        System.out.println("RETAINED_VIEWPORT_COMPOSITION_PASS");
    }

    private static void verifyEquivalentTransform(float rawOrigin,
            float sourceSpan, int viewportPixels, int texturePixels,
            float destinationPixel) {
        RetainedViewportComposition.PixelAlignedAxis axis =
                RetainedViewportComposition.pixelAlignedAxis(
                        rawOrigin, sourceSpan, viewportPixels, texturePixels);
        require(axis.valid(), "pixel-aligned transform rejected a valid window");
        float originalSource = rawOrigin
                + destinationPixel * sourceSpan / viewportPixels;
        float alignedSource = axis.sourceOrigin()
                + (destinationPixel - axis.destinationOffsetPixels())
                        * axis.sourceSpan() / axis.destinationSpanPixels();
        near(alignedSource, originalSource, 0.001f,
                "pixel alignment changed the camera transform");
    }

    private static void near(float actual, float expected,
            float tolerance, String message) {
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(message + ": actual=" + actual
                    + " expected=" + expected);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
