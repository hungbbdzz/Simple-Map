package com.velorise.simplemap.client;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared provenance and mutation index for surface and cave observations.
 *
 * <p>The index does not own chunk data and never creates chunk tickets. It only
 * records whether a chunk has been observed live or in the local Anvil save, plus
 * a monotonically increasing mutation epoch used to invalidate derived context,
 * surface and cave work.</p>
 */
public final class GeneratedChunkIndex {
    public enum State {
        UNKNOWN,
        LIVE,
        SAVED_PRESENT,
        KNOWN_ABSENT,
        FAILED_RETRY
    }

    private static final GeneratedChunkIndex INSTANCE = new GeneratedChunkIndex();
    private static final int MAX_ENTRIES = 262_144;
    private static final long ABSENT_TTL_MS = 30_000L;
    private static final long FAILURE_TTL_MS = 8_000L;

    private final LinkedHashMap<Key, Entry> entries =
            new LinkedHashMap<>(4096, 0.75f, true);
    private String observedDimension = "";
    private long globalEpoch = 1L;

    private GeneratedChunkIndex() {
    }

    public static GeneratedChunkIndex getInstance() {
        return INSTANCE;
    }

    public synchronized void observeLevel(Level level) {
        String dimension = level == null
                ? "unknown" : level.dimension().location().toString();
        if (dimension.equals(observedDimension)) return;
        observedDimension = dimension;
        entries.clear();
        globalEpoch++;
    }

    public synchronized void reset() {
        observedDimension = "";
        entries.clear();
        globalEpoch++;
    }

    public synchronized long markLive(Level level, int chunkX, int chunkZ) {
        return mutate(level, chunkX, chunkZ, State.LIVE, 0L, false);
    }

    public synchronized long markMutation(Level level, int chunkX, int chunkZ) {
        return mutate(level, chunkX, chunkZ, null, 0L, true);
    }

    /** Chunk unload revokes live residency, not the fact that local save data may exist. */
    public synchronized long markUnavailable(Level level, int chunkX, int chunkZ) {
        Key key = key(level, chunkX, chunkZ);
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (entry.state == State.LIVE) entry.state = State.UNKNOWN;
        entry.epoch = ++globalEpoch;
        entry.updatedAtMs = System.currentTimeMillis();
        trim();
        return entry.epoch;
    }

    public synchronized void markSavedPresent(Level level, int chunkX, int chunkZ) {
        mutate(level, chunkX, chunkZ, State.SAVED_PRESENT, 0L, false);
    }

    public synchronized void markSavedAbsent(Level level, int chunkX, int chunkZ) {
        mutate(level, chunkX, chunkZ, State.KNOWN_ABSENT,
                System.currentTimeMillis() + ABSENT_TTL_MS, false);
    }

    public synchronized void markSavedFailure(Level level, int chunkX, int chunkZ) {
        mutate(level, chunkX, chunkZ, State.FAILED_RETRY,
                System.currentTimeMillis() + FAILURE_TTL_MS, false);
    }

    public synchronized long mutationEpoch(Level level, int chunkX, int chunkZ) {
        Entry entry = entries.get(key(level, chunkX, chunkZ));
        return entry == null ? 0L : entry.epoch;
    }

    /** A compact dependency token for roof/floor and edge-sensitive observations. */
    public synchronized long neighbourhoodEpoch(Level level, int chunkX, int chunkZ,
            int radius) {
        long token = globalEpoch;
        int safe = Math.max(0, radius);
        for (int dz = -safe; dz <= safe; dz++) {
            for (int dx = -safe; dx <= safe; dx++) {
                Entry entry = entries.get(key(level, chunkX + dx, chunkZ + dz));
                if (entry == null) continue;
                token = Long.rotateLeft(token, 7) ^ entry.epoch
                        ^ ChunkPos.asLong(chunkX + dx, chunkZ + dz);
            }
        }
        return token;
    }

    public synchronized State state(Level level, int chunkX, int chunkZ) {
        Entry entry = entries.get(key(level, chunkX, chunkZ));
        if (entry == null) return State.UNKNOWN;
        if ((entry.state == State.KNOWN_ABSENT || entry.state == State.FAILED_RETRY)
                && entry.retryAfterMs > 0L
                && System.currentTimeMillis() >= entry.retryAfterMs) {
            entry.state = State.UNKNOWN;
            entry.retryAfterMs = 0L;
        }
        return entry.state;
    }

    public synchronized Snapshot snapshot() {
        EnumMap<State, Integer> counts = new EnumMap<>(State.class);
        for (State state : State.values()) counts.put(state, 0);
        for (Entry entry : entries.values()) {
            counts.put(entry.state, counts.get(entry.state) + 1);
        }
        return new Snapshot(entries.size(), globalEpoch, Map.copyOf(counts));
    }

    private long mutate(Level level, int chunkX, int chunkZ, State state,
            long retryAfterMs, boolean advanceEpoch) {
        observeLevel(level);
        Key key = key(level, chunkX, chunkZ);
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (state != null) {
            // A background save result must not demote an actively loaded chunk.
            if (entry.state != State.LIVE || state == State.LIVE) entry.state = state;
        }
        if (advanceEpoch) entry.epoch = ++globalEpoch;
        else if (entry.epoch == 0L) entry.epoch = globalEpoch;
        entry.retryAfterMs = retryAfterMs;
        entry.updatedAtMs = System.currentTimeMillis();
        trim();
        return entry.epoch;
    }

    private Key key(Level level, int chunkX, int chunkZ) {
        String dimension = level == null
                ? "unknown" : level.dimension().location().toString();
        return new Key(dimension, ChunkPos.asLong(chunkX, chunkZ));
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            var iterator = entries.entrySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private record Key(String dimension, long chunkPos) {
    }

    private static final class Entry {
        private State state = State.UNKNOWN;
        private long epoch;
        private long retryAfterMs;
        private long updatedAtMs;
    }

    public record Snapshot(int entries, long globalEpoch,
            Map<State, Integer> counts) {
    }
}
