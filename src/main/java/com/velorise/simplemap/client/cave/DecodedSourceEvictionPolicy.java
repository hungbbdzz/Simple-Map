package com.velorise.simplemap.client.cave;

/** Dependency-free invariants for evicting decoded NBT/source entries. */
final class DecodedSourceEvictionPolicy {
    private DecodedSourceEvictionPolicy() { }

    static boolean evictable(boolean hasFuture, int totalLeases,
            DecodedWorldRegionCache.State state) {
        return !hasFuture && totalLeases == 0
                && state == DecodedWorldRegionCache.State.PRESENT;
    }
}
