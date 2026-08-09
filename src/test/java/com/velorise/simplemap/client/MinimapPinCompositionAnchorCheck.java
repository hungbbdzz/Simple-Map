package com.velorise.simplemap.client;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS129 guard for live player->pin routing and retained-FBO marker anchoring. */
public final class MinimapPinCompositionAnchorCheck {
    private MinimapPinCompositionAnchorCheck() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("src/main/java/com/velorise/simplemap/client");
        String framebuffer = Files.readString(
                root.resolve("MinimapFramebufferRenderer.java"));
        String renderer = Files.readString(root.resolve("MinimapRenderer.java"));
        String navigation = Files.readString(root.resolve("PinNavigation.java"));
        String mapRenderer = Files.readString(root.resolve("MapRenderer.java"));

        require(framebuffer.contains("HudPoint projectWorldToHud")
                        && framebuffer.contains("renderedSnappedX")
                        && framebuffer.contains("compositionSourceX")
                        && framebuffer.contains("compositionDisplaySpan"),
                "pin is not projected through the exact retained minimap composition");
        require(renderer.contains("framebuffer.projectWorldToHud(")
                        && renderer.contains("PinNavigation.currentRoute(playerX, playerZ)")
                        && renderer.contains("pose().translate(iconX, iconZ")
                        && !renderer.contains("Math.round(iconX)"),
                "minimap pin is not fractionally anchored to the retained terrain");
        require(navigation.contains("currentRoute(double startX, double startZ)")
                        && navigation.contains("targetX() - route.nx()")
                        && navigation.contains("1.0 - interval[1]")
                        && navigation.contains("alignUp(first, stride)"),
                "route is not a live full-segment, destination-phased world lattice");
        require(mapRenderer.contains("PinNavigation.currentRoute(\n                    routePlayerX, routePlayerZ)")
                        && mapRenderer.contains("PinNavigation.visibleDots(route")
                        && !mapRenderer.contains("(end - start) / numSteps"),
                "fullscreen guide line still redistributes dots from clipped endpoints");
        System.out.println("MINIMAP_PIN_LIVE_ROUTE_COMPOSITION_ANCHOR_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
