package com.velorise.simplemap.client;

/** Regression checks for single-owner retained viewport publication. */
public final class ViewportDemandPublicationPolicyCheck {
    private ViewportDemandPublicationPolicyCheck() {
    }

    public static void main(String[] args) {
        require(ViewportDemandPublicationPolicy.rendererOwnsDemand(true),
                "direct renderer stopped refreshing viewport demand");
        require(!ViewportDemandPublicationPolicy.rendererOwnsDemand(false),
                "retained off-screen replay published duplicate viewport demand");

        int planningGenerations = 0;
        for (int retainedRedraws = 0; retainedRedraws < 10_000; retainedRedraws++) {
            // The owner refreshes once; the off-screen renderer must contribute zero.
            planningGenerations++;
            if (ViewportDemandPublicationPolicy.rendererOwnsDemand(false)) {
                planningGenerations++;
            }
        }
        require(planningGenerations == 10_000,
                "retained redraw generated duplicate planning work");
        System.out.println("VIEWPORT_DEMAND_PUBLICATION_POLICY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
