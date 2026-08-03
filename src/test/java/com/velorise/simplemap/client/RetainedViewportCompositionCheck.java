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

        require(Float.isNaN(RetainedViewportComposition.sourceSpan(
                        1600, 0.0f, 0.35f)),
                "invalid render scale was accepted");
        require(Float.isNaN(RetainedViewportComposition.sourceOrigin(
                        64, 1600, Float.NaN, 0.0, 0.0, 0.35f)),
                "invalid source span was accepted");
        System.out.println("RETAINED_VIEWPORT_COMPOSITION_PASS");
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
