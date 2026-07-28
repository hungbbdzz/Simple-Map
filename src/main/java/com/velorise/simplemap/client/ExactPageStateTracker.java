package com.velorise.simplemap.client;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded alpha diagnostics for exact-page lifecycle transitions.
 *
 * This tracker is deliberately not an authority for rendering. It records the
 * authority owned by the surface/cave managers so pipeline stalls can be located
 * without turning every missing texture into an ambiguous null state.
 */
public final class ExactPageStateTracker {
    private static final ExactPageStateTracker INSTANCE = new ExactPageStateTracker();
    private static final int MAX_ENTRIES = 16_384;

    private final Map<String, Entry> entries = new LinkedHashMap<>(512, 0.75f, true);
    private final EnumMap<ExactPageState, AtomicLong> transitions =
            new EnumMap<>(ExactPageState.class);

    private ExactPageStateTracker() {
        for (ExactPageState state : ExactPageState.values()) {
            transitions.put(state, new AtomicLong());
        }
    }

    public static ExactPageStateTracker getInstance() {
        return INSTANCE;
    }

    public synchronized void transition(String key, ExactPageState state,
            MapRequestLane lane, long revision) {
        if (key == null || state == null) return;
        Entry previous = entries.get(key);
        long now = System.currentTimeMillis();
        MapRequestLane effectiveLane = lane == null ? MapRequestLane.BACKGROUND : lane;
        if (previous != null && previous.revision > revision
                && state != ExactPageState.STALE_GENERATION) {
            return;
        }
        if (previous != null && previous.state == state
                && previous.lane == effectiveLane
                && previous.revision == revision) {
            return;
        }
        entries.put(key, new Entry(state, effectiveLane, revision, now));
        transitions.get(state).incrementAndGet();
        trim();
    }

    public synchronized ExactPageState state(String key) {
        Entry entry = entries.get(key);
        return entry == null ? ExactPageState.ABSENT : entry.state;
    }

    public synchronized Snapshot snapshot() {
        EnumMap<ExactPageState, Long> counts = new EnumMap<>(ExactPageState.class);
        for (ExactPageState state : ExactPageState.values()) counts.put(state, 0L);
        long now = System.currentTimeMillis();
        long oldestAgeMs = 0L;
        long requestedOlderThan5s = 0L;
        long buildingOlderThan5s = 0L;
        long cpuReadyOlderThan5s = 0L;
        for (Entry entry : entries.values()) {
            counts.put(entry.state, counts.get(entry.state) + 1L);
            long age = Math.max(0L, now - entry.updatedAtMs);
            oldestAgeMs = Math.max(oldestAgeMs, age);
            if (age < 5_000L) continue;
            if (entry.state == ExactPageState.REQUESTED) requestedOlderThan5s++;
            else if (entry.state == ExactPageState.BUILDING) buildingOlderThan5s++;
            else if (entry.state == ExactPageState.CPU_READY) cpuReadyOlderThan5s++;
        }
        EnumMap<ExactPageState, Long> transitionCounts = new EnumMap<>(ExactPageState.class);
        for (ExactPageState state : ExactPageState.values()) {
            transitionCounts.put(state, transitions.get(state).get());
        }
        return new Snapshot(entries.size(), counts, transitionCounts,
                oldestAgeMs, requestedOlderThan5s, buildingOlderThan5s,
                cpuReadyOlderThan5s);
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Resets diagnostic state at the start of a new map session. */
    public synchronized void reset() {
        entries.clear();
        for (AtomicLong counter : transitions.values()) counter.set(0L);
    }

    public synchronized void clearPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            clear();
            return;
        }
        entries.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            String eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
    }

    private record Entry(ExactPageState state, MapRequestLane lane,
            long revision, long updatedAtMs) {
    }

    public record Snapshot(int trackedPages,
            Map<ExactPageState, Long> pagesByState,
            Map<ExactPageState, Long> transitionsByState,
            long oldestStateAgeMs, long requestedOlderThan5s,
            long buildingOlderThan5s, long cpuReadyOlderThan5s) {
    }
}
