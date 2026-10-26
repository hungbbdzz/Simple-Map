package com.velorise.simplemap.client;

/** Dependency-free checks for physical-frame source-capture accounting. */
public final class SurfaceCaptureFrameAllowanceCheck {
    private SurfaceCaptureFrameAllowanceCheck() { }

    public static void main(String[] args) {
        SurfaceCaptureFrameAllowance allowance =
                new SurfaceCaptureFrameAllowance();
        allowance.beginFrame(10L);
        require(allowance.claim(true, false, 1L) == 32,
                "first minimap slice incorrect");
        require(allowance.claim(true, false, 20_000_000L) == 32,
                "second minimap slice incorrect");
        require(allowance.claim(true, false, 200_000_000L) == 0,
                "slow physical frame refilled by wall clock");

        allowance.beginFrame(11L);
        require(allowance.claim(true, false, 200_000_001L) == 32,
                "new physical frame did not refill");

        allowance.beginFrame(12L);
        require(allowance.claim(false, true, 300_000_000L) == 4,
                "pressured fullscreen slice incorrect");
        require(allowance.claim(false, true, 301_000_000L) == 4,
                "pressured fullscreen remainder incorrect");
        require(allowance.claim(false, true, 400_000_000L) == 0,
                "pressured frame exceeded allowance");

        SurfaceCaptureFrameAllowance fallback =
                new SurfaceCaptureFrameAllowance();
        require(fallback.claim(false, false, 1L) == 24,
                "fallback first slice incorrect");
        require(fallback.claim(false, false, 10_000_000L) == 24,
                "fallback second slice incorrect");
        require(fallback.claim(false, false, 49_000_000L) == 0,
                "fallback refilled too early");
        require(fallback.claim(false, false, 50_000_001L) == 24,
                "fallback did not refill at 50 ms");

        fallback.reset();
        require(fallback.claim(true, true, 60_000_000L) == 8,
                "reset did not restore fallback accounting");
        System.out.println("SURFACE_CAPTURE_FRAME_ALLOWANCE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
