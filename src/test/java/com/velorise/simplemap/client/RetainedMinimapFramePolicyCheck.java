package com.velorise.simplemap.client;

/** Dependency-free invalidation/coalescing checks for retained minimap composition. */
public final class RetainedMinimapFramePolicyCheck {
    private static final long INTERVAL = 100L;

    private RetainedMinimapFramePolicyCheck() { }

    public static void main(String[] args) {
        require(RetainedMinimapFramePolicy.withinTranslationGuard(
                        12.0, -4.0, 10.0, -2.0, 2.0f, 4.0f),
                "guard rejected its inclusive edge");
        require(!RetainedMinimapFramePolicy.withinTranslationGuard(
                        12.01, -4.0, 10.0, -2.0, 2.0f, 4.0f),
                "guard accepted an out-of-range translation");
        require(!RetainedMinimapFramePolicy.withinTranslationGuard(
                        Double.NaN, 0.0, 0.0, 0.0, 1.0f, 4.0f),
                "guard accepted a non-finite centre");

        RetainedMinimapFramePolicy policy = new RetainedMinimapFramePolicy();
        prepare(policy, 10L, 20L, 3L, 4);
        require(policy.decision(1_000L, INTERVAL)
                        == RetainedMinimapFramePolicy.Decision.REDRAW_HARD,
                "initial target was treated as reusable");
        policy.commit(1_000L);
        require(policy.decision(1_010L, INTERVAL)
                        == RetainedMinimapFramePolicy.Decision.REUSE,
                "unchanged target did not reuse");

        // Fractional player movement is handled by UV translation and must not
        // invalidate the expensive atlas replay while no route is active.
        prepare(policy, 10L, 20L, 3L, 4);
        require(policy.decision(1_020L, INTERVAL)
                        == RetainedMinimapFramePolicy.Decision.REUSE,
                "fractional movement rebuilt map content");

        prepare(policy, 11L, 20L, 3L, 4);
        require(policy.decision(1_030L, INTERVAL)
                        == RetainedMinimapFramePolicy.Decision.REDRAW_HARD,
                "snapped centre change was missed");
        policy.commit(1_030L);

        // Atlas publication is streaming work. It is retained immediately but
        // replay is bounded by the content cadence.
        prepare(policy, 11L, 21L, 3L, 4);
        require(policy.decision(1_050L, INTERVAL)
                        == RetainedMinimapFramePolicy.Decision.DEFER_STREAMING,
                "pixel publication was not coalesced");
        require(policy.decision(1_130L, INTERVAL)
                        == RetainedMinimapFramePolicy.Decision.REDRAW_STREAMING,
                "coalesced publication never became drawable");
        policy.commit(1_130L);

        prepare(policy, 11L, 21L, 4L, 4);
        require(policy.decision(1_140L, INTERVAL)
                        == RetainedMinimapFramePolicy.Decision.REDRAW_HARD,
                "waypoint mutation was missed");
        policy.commit(1_140L);

        // Pin navigation is no longer baked into the retained target. Activating a
        // pin or moving the route origin therefore cannot invalidate atlas replay.
        // The dynamic route/marker is emitted after framebuffer composition.
        prepare(policy, 11L, 21L, 4L, 4);
        require(policy.decision(1_150L, INTERVAL)
                        == RetainedMinimapFramePolicy.Decision.REUSE,
                "detached pin overlay invalidated retained map content");

        policy.invalidate();
        require(policy.needsRedraw(), "explicit invalidation did not rebuild");
        System.out.println("RETAINED_MINIMAP_FRAME_POLICY_PASS");
    }

    private static void prepare(RetainedMinimapFramePolicy policy,
            long snappedX, long pixelRevision, long waypointRevision,
            int brightnessBucket) {
        policy.prepare(1L, 2L, 3L, 4,
                5L, 6L, pixelRevision, waypointRevision,
                "minecraft:overworld", 0, Integer.MIN_VALUE,
                snappedX, -9L, 1.5f, 7.75f,
                brightnessBucket, 1, 5.0f, 0xFFFFFFFF);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
