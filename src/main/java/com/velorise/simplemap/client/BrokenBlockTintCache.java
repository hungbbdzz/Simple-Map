package com.velorise.simplemap.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small runtime cache of block ids that caused exceptions when sampling texture
 * colors. Prevents repeated expensive/unsafe sampling attempts for known-bad
 * mod blocks.
 */
public final class BrokenBlockTintCache {
    private static final BrokenBlockTintCache INSTANCE = new BrokenBlockTintCache();

    public static BrokenBlockTintCache getInstance() { return INSTANCE; }

    private final Set<String> broken = ConcurrentHashMap.newKeySet();

    private BrokenBlockTintCache() {}

    public boolean isBroken(String blockId) {
        return blockId == null || broken.contains(blockId);
    }

    public void markBroken(String blockId) {
        if (blockId == null) return;
        broken.add(blockId);
    }
}
