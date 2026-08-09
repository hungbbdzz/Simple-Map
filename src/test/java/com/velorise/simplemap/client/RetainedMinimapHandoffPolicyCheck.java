package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS94 guard for a stable loaded/loading minimap target handoff. */
public final class RetainedMinimapHandoffPolicyCheck {
    private RetainedMinimapHandoffPolicyCheck() { }

    public static void main(String[] args) throws Exception {
        long now = 1_000_000_000L;
        require(RetainedMinimapHandoffPolicy.shouldAttemptRedraw(
                        false, now, Long.MAX_VALUE),
                "first retained frame can never be built");
        require(!RetainedMinimapHandoffPolicy.shouldAttemptRedraw(
                        true, now, now + 1L),
                "cold redraw retry is not throttled");
        require(RetainedMinimapHandoffPolicy.shouldAttemptRedraw(
                        true, now + 1L, now + 1L),
                "cold redraw retry never reopens");
        require(RetainedMinimapHandoffPolicy.retainLastGood(true, false),
                "last complete target is discarded on a cold redraw");
        require(!RetainedMinimapHandoffPolicy.retainLastGood(false, false),
                "blank first frame was treated as a valid retained target");
        require(RetainedMinimapHandoffPolicy.nextAttemptNanos(now) > now,
                "retry deadline did not advance");

        require(RetainedMinimapHandoffPolicy.canPublishSuccessfulRedraw(
                        true, true, false),
                "compatible retained underlay was incorrectly blocked by cold-root readiness");
        require(!RetainedMinimapHandoffPolicy.canPublishSuccessfulRedraw(
                        true, false, false),
                "cold authority switch published without coverage");
        require(RetainedMinimapHandoffPolicy.canPublishSuccessfulRedraw(
                        true, false, true),
                "cold authority switch stayed blocked after coverage became ready");
        String renderer = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/MinimapFramebufferRenderer.java"));
        require(renderer.contains("frontFrameValid")
                        && renderer.contains("nextColdRedrawAttemptNanos")
                        && renderer.contains("retainLastGood")
                        && renderer.contains("shouldAttemptRedraw")
                        && renderer.contains("retainedUnderlayDrawn")
                        && renderer.contains("canPublishSuccessfulRedraw"),
                "retained renderer does not preserve a last-good front target");

        System.out.println("RETAINED_MINIMAP_HANDOFF_POLICY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
