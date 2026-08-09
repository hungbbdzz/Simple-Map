package com.velorise.simplemap.client.cave;

public final class CaveViewportPublicationPolicyCheck {
    private CaveViewportPublicationPolicyCheck() { }

    public static void main(String[] args) {
        long now = 1_000L;
        if (!CaveViewportPublicationPolicy.windowOpen(false, false, 2_000L, now)) {
            throw new AssertionError("Non-fullscreen work must never be gated");
        }
        if (!CaveViewportPublicationPolicy.windowOpen(true, true, 2_000L, now)) {
            throw new AssertionError("Projection replacement must publish immediately");
        }
        if (CaveViewportPublicationPolicy.windowOpen(true, false, 1_100L, now)) {
            throw new AssertionError("Layered fullscreen must wait for the shared window");
        }
        if (!CaveViewportPublicationPolicy.windowOpen(true, false, 1_000L, now)) {
            throw new AssertionError("Due window must open");
        }
        if (CaveViewportPublicationPolicy.nextWindow(now) - now != 16L) {
            throw new AssertionError("Unexpected viewport cadence");
        }
        System.out.println("CAVE_VIEWPORT_PUBLICATION_POLICY_PASS");
    }
}
