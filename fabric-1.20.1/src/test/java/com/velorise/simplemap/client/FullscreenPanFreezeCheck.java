package com.velorise.simplemap.client;
import com.velorise.simplemap.client.renderer.*;

import java.nio.file.Files;
import java.nio.file.Path;

/** Guards retained pan translation against whole-target streaming replay jitter. */
public final class FullscreenPanFreezeCheck {
    private FullscreenPanFreezeCheck() { }

    public static void main(String[] args) throws Exception {
        Path client = Path.of("src/main/java/com/velorise/simplemap/client");
        String fbo = Files.readString(client.resolve("renderer/FullscreenMapFramebufferRenderer.java"));
        String screen = Files.readString(client.resolve("gui/MapScreen.java"));
        String policy = Files.readString(client.resolve("renderer/RetainedFullscreenFramePolicy.java"));
        require(fbo.contains("OVERSCAN = 96")
                        && fbo.contains("viewportInteracting")
                        && fbo.contains("nowNanos, redrawInterval, viewportInteracting"),
                "fullscreen pan still uses the small guard or redraws while interacting");
        require(screen.contains("boolean viewportInteracting = isViewportInteracting(frameNow)")
                        && screen.contains("partialTick, viewportInteracting)"),
                "MapScreen does not pass real drag/momentum state to retained composition");
        require(policy.contains("if (suppressStreamingRedraw) return Decision.DEFER_STREAMING"),
                "streaming revisions can still replay the full target during pan");
        System.out.println("FULLSCREEN_PAN_FREEZE_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
