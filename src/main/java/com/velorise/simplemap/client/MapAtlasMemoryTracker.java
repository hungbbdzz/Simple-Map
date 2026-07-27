package com.velorise.simplemap.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks actual allocated RGBA8 atlas storage, without counting slot contents twice. */
public final class MapAtlasMemoryTracker {
    private static final MapAtlasMemoryTracker INSTANCE = new MapAtlasMemoryTracker();
    private final Map<String, Long> allocations = new ConcurrentHashMap<>();

    private MapAtlasMemoryTracker() {
    }

    public static MapAtlasMemoryTracker getInstance() {
        return INSTANCE;
    }

    public void register(String key, long bytes) {
        if (key == null || key.isBlank()) return;
        allocations.put(key, Math.max(0L, bytes));
        MapMemoryBudgetPolicy.refreshRuntimeVramBudget();
    }

    public void remove(String key) {
        if (key != null) allocations.remove(key);
    }

    public Snapshot snapshot() {
        long bytes = 0L;
        for (long value : allocations.values()) bytes += Math.max(0L, value);
        return new Snapshot(allocations.size(), bytes,
                MapMemoryBudgetPolicy.plannedAtlasBytes(),
                MapMemoryBudgetPolicy.residentContentBudgetBytes(),
                MapMemoryBudgetPolicy.detectedAvailableVramBytes());
    }

    public record Snapshot(int allocationCount, long allocatedBytes,
            long plannedAtlasBytes, long residentContentBudgetBytes,
            long detectedAvailableVramBytes) {
    }
}
