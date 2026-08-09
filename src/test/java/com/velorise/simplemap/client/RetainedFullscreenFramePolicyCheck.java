package com.velorise.simplemap.client;

/** Dependency-free checks for fullscreen retained terrain composition. */
public final class RetainedFullscreenFramePolicyCheck {
    private static final long INTERVAL = 125L;

    private RetainedFullscreenFramePolicyCheck() { }

    public static void main(String[] args) {
        require(RetainedFullscreenFramePolicy.withinTranslationGuard(
                        100.0, 40.0, 0.0, 0.0, 1.0f, 100.0f),
                "inclusive fullscreen guard failed");
        require(!RetainedFullscreenFramePolicy.withinTranslationGuard(
                        100.01, 40.0, 0.0, 0.0, 1.0f, 100.0f),
                "fullscreen guard accepted out-of-range centre");

        RetainedFullscreenFramePolicy policy = new RetainedFullscreenFramePolicy();
        prepare(policy, 10L, 20L, 5);
        require(policy.decision(1_000L, INTERVAL, false)
                        == RetainedFullscreenFramePolicy.Decision.REDRAW_HARD,
                "initial fullscreen target reused");
        policy.commit(1_000L);

        prepare(policy, 10L, 20L, 5);
        require(policy.decision(1_010L, INTERVAL, false)
                        == RetainedFullscreenFramePolicy.Decision.REUSE,
                "unchanged fullscreen target redrew");

        prepare(policy, 10L, 21L, 5);
        require(policy.decision(1_050L, INTERVAL, false)
                        == RetainedFullscreenFramePolicy.Decision.DEFER_STREAMING,
                "fullscreen publication was not coalesced");
        require(policy.decision(1_125L, INTERVAL, true)
                        == RetainedFullscreenFramePolicy.Decision.DEFER_STREAMING,
                "interaction did not freeze whole-target streaming replay");
        require(policy.decision(1_125L, INTERVAL, false)
                        == RetainedFullscreenFramePolicy.Decision.REDRAW_STREAMING,
                "fullscreen publication never became visible after interaction");
        policy.commit(1_125L);

        prepare(policy, 11L, 21L, 5);
        require(policy.decision(1_130L, INTERVAL, false)
                        == RetainedFullscreenFramePolicy.Decision.REDRAW_HARD,
                "anchor handoff was not a hard redraw");
        policy.commit(1_130L);

        prepare(policy, 11L, 21L, 6);
        require(policy.decision(1_140L, INTERVAL, false)
                        == RetainedFullscreenFramePolicy.Decision.REDRAW_HARD,
                "brightness mutation was missed");

        // Waypoint hover, live player motion and pin navigation are intentionally
        // absent from this policy because they are drawn after terrain composition.
        policy.invalidate();
        require(policy.decision(1_160L, INTERVAL, false)
                        == RetainedFullscreenFramePolicy.Decision.REDRAW_HARD,
                "explicit invalidation did not redraw");
        System.out.println("RETAINED_FULLSCREEN_FRAME_POLICY_PASS");
    }

    private static void prepare(RetainedFullscreenFramePolicy policy,
            long anchorPixelX, long pixelRevision, int brightnessBucket) {
        policy.prepare(1L, 2L, 3L, 4L,
                5L, 6L, pixelRevision,
                "minecraft:overworld", 0, Integer.MIN_VALUE,
                anchorPixelX, -9L, 1600, 900, 0.35f,
                brightnessBucket, 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
