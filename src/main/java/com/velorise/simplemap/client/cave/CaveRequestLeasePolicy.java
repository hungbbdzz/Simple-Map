package com.velorise.simplemap.client.cave;

/**
 * Keeps an exact Cave transaction owned while it is queued, building, or waiting
 * for GPU publication. The lease is deliberately short and renewed only by an
 * attached transaction; viewport handoff still revokes ownership immediately.
 */
final class CaveRequestLeasePolicy {
    static final long ATTACHED_RENEWAL_MS = 5_000L;

    private CaveRequestLeasePolicy() { }

    static long renewedUntil(long now) {
        return now + ATTACHED_RENEWAL_MS;
    }

    static boolean isActive(long lastSeenMs, long leaseUntilMs,
            long requestTtlMs, long now) {
        boolean observed = lastSeenMs != 0L && now - lastSeenMs <= requestTtlMs;
        return observed || leaseUntilMs >= now;
    }
}
