package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS95 guard: fullscreen uses the same loaded/loading isolation as minimap. */
public final class FullscreenXaeroHandoffCheck {
    private FullscreenXaeroHandoffCheck() { }

    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/FullscreenMapFramebufferRenderer.java"));
        require(source.contains("frontTarget") && source.contains("backTarget")
                        && source.contains("frontFrameValid")
                        && source.contains("nextColdRedrawAttemptNanos")
                        && source.contains("drawRetainedUnderlay")
                        && source.contains("swapTargets()")
                        && source.contains("RetainedMinimapHandoffPolicy.shouldAttemptRedraw")
                        && source.contains("RetainedMinimapHandoffPolicy.retainLastGood"),
                "fullscreen can still expose a cold/empty loading framebuffer");
        System.out.println("FULLSCREEN_XAERO_HANDOFF_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
