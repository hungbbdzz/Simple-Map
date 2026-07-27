package com.velorise.simplemap.client.cave;

import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

/**
 * Small mutation-aware cache for automatic cave context probes.
 *
 * <p>Roof/floor probing is expensive in tall caverns. Results are reusable while
 * the player remains in the same chunk/Y band and the surrounding chunk mutation
 * token is unchanged.</p>
 */
public final class CaveContextCache {
    private static final CaveContextCache INSTANCE = new CaveContextCache();
    private static final int MAX_ENTRIES = 2_048;
    private static final long TTL_TICKS = 20L;

    private final LinkedHashMap<Key, Entry> entries =
            new LinkedHashMap<>(128, 0.75f, true);
    private Level observedLevel;

    private CaveContextCache() {
    }

    public static CaveContextCache getInstance() {
        return INSTANCE;
    }

    public synchronized Result resolve(Level level, int chunkX, int chunkZ,
            int playerYBand, long mutationToken, long gameTick,
            Supplier<Result> computer) {
        if (observedLevel != level) {
            entries.clear();
            observedLevel = level;
        }
        String dimension = level.dimension().location().toString();
        Key key = new Key(dimension, chunkX, chunkZ, playerYBand);
        Entry entry = entries.get(key);
        if (entry != null && entry.mutationToken == mutationToken
                && gameTick <= entry.expiresAtTick) {
            return entry.result;
        }
        Result computed = computer.get();
        if (computed == null) return null;
        entries.put(key, new Entry(computed, mutationToken, gameTick + TTL_TICKS));
        trim();
        return computed;
    }

    public synchronized void reset() {
        observedLevel = null;
        entries.clear();
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            var iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private record Key(String dimension, int chunkX, int chunkZ, int playerYBand) {
    }

    private record Entry(Result result, long mutationToken, long expiresAtTick) {
    }

    public record Result(boolean covered, int suggestedTopY, int confidence) {
    }
}
