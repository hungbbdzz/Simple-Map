package com.velorise.simplemap.client.cave;

/** Dependency-free checks for adaptive Surface source loading. */
public final class AdaptiveDimensionLoadPolicyCheck {
    private AdaptiveDimensionLoadPolicyCheck() {
    }

    public static void main(String[] args) {
        require(AdaptiveDimensionLoadPolicy.topology(true, false)
                        == AdaptiveDimensionLoadPolicy.Topology.SKYLIT_OPEN,
                "skylit open dimension was misclassified");
        require(AdaptiveDimensionLoadPolicy.topology(false, false)
                        == AdaptiveDimensionLoadPolicy.Topology.DARK_OPEN,
                "The End topology was misclassified as a cave world");
        require(AdaptiveDimensionLoadPolicy.topology(false, true)
                        == AdaptiveDimensionLoadPolicy.Topology.HARD_CEILING,
                "hard-ceiling dimension was not recognized");
        require(AdaptiveDimensionLoadPolicy.surfaceHaloChunks() == 1,
                "surface leaf lost its one-chunk edge halo");
        require(AdaptiveDimensionLoadPolicy.surfacePageBudget(
                        AdaptiveDimensionLoadPolicy.Topology.DARK_OPEN,
                        true, false, false, 0.08f) == 8,
                "dark/open far zoom did not retain its reconstruction frontier");
        require(AdaptiveDimensionLoadPolicy.surfacePageBudget(
                        AdaptiveDimensionLoadPolicy.Topology.SKYLIT_OPEN,
                        true, false, false, 0.18f) == 8,
                "healthy overworld fullscreen did not use available source headroom");
        require(AdaptiveDimensionLoadPolicy.surfacePageBudget(
                        AdaptiveDimensionLoadPolicy.Topology.DARK_OPEN,
                        true, true, false, 0.5f) == 2,
                "pressure did not collapse source admission");
        require(!AdaptiveDimensionLoadPolicy.shouldRetarget(
                        0, 8, 0, 8, 4, 4,
                        1, 9, 0, 8, 5, 4),
                "one-page continuous pan reset the source frontier");
        require(AdaptiveDimensionLoadPolicy.shouldRetarget(
                        0, 8, 0, 8, 4, 4,
                        2, 10, 0, 8, 6, 4),
                "meaningful pan failed to retarget source priority");
        System.out.println("Simple Map adaptive-dimension load checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
