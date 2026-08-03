package com.velorise.simplemap.client.cave;

public final class CaveRequestLeasePolicyCheck {
    private CaveRequestLeasePolicyCheck() { }

    public static void main(String[] args) {
        long now = 10_000L;
        require(CaveRequestLeasePolicy.isActive(now - 1_249L, 0L,
                1_250L, now), "fresh viewport observation must stay active");
        require(!CaveRequestLeasePolicy.isActive(now - 1_251L, 0L,
                1_250L, now), "expired unowned request must retire");
        long renewed = CaveRequestLeasePolicy.renewedUntil(now);
        require(CaveRequestLeasePolicy.isActive(now - 5_000L, renewed,
                1_250L, now + 4_999L),
                "attached exact transaction must outlive viewport TTL");
        require(!CaveRequestLeasePolicy.isActive(now - 5_000L, renewed,
                1_250L, renewed + 1L),
                "detached transaction lease must expire without renewal");
        System.out.println("CAVE_REQUEST_LEASE_POLICY_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
