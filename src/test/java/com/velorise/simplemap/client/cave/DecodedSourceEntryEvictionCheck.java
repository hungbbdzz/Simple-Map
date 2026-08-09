package com.velorise.simplemap.client.cave;

import java.nio.file.Files;
import java.nio.file.Path;

/** PASS94 guard for entry-granular decoded source eviction. */
public final class DecodedSourceEntryEvictionCheck {
    private DecodedSourceEntryEvictionCheck() { }

    public static void main(String[] args) throws Exception {
        require(DecodedSourceEvictionPolicy.evictable(false, 0,
                        DecodedWorldRegionCache.State.PRESENT),
                "unleased decoded source is not evictable");
        require(!DecodedSourceEvictionPolicy.evictable(true, 0,
                        DecodedWorldRegionCache.State.PRESENT),
                "in-flight decoded source can be evicted");
        require(!DecodedSourceEvictionPolicy.evictable(false, 1,
                        DecodedWorldRegionCache.State.PRESENT),
                "leased decoded source can be evicted");
        require(!DecodedSourceEvictionPolicy.evictable(false, 0,
                        DecodedWorldRegionCache.State.ABSENT),
                "zero-byte absence authority should not be discarded by byte trim");

        String cache = Files.readString(Path.of(
                "src/main/java/com/velorise/simplemap/client/cave/DecodedWorldRegionCache.java"));
        require(cache.contains("DecodedSourceEvictionPolicy.evictable")
                        && cache.contains("entry.result = null")
                        && cache.contains("region.presentCount = Math.max(0")
                        && cache.contains("trimLocked();"),
                "decoded source cache still evicts only whole Anvil regions");

        System.out.println("DECODED_SOURCE_ENTRY_EVICTION_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
