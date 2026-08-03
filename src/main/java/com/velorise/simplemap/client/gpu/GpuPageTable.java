package com.velorise.simplemap.client.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Double-buffered logical page table.
 *
 * <p>Writers mutate only the back table. At a frame boundary the two maps are
 * swapped atomically and slots replaced by the new front generation become
 * eligible for retirement. The renderer never observes a half-published entry.</p>
 */
public final class GpuPageTable {
    public record RetiredSlot(int storageId, int slot, long generation) { }

    private Map<TileKey, PageTableEntry> front = new HashMap<>();
    private Map<TileKey, PageTableEntry> back = new HashMap<>();
    private long frontGeneration;
    private boolean backPrepared;
    /** Keys changed since the last frame boundary. Both maps are kept identical
     * after every swap, so staging only records deltas instead of cloning every
     * resident page-table entry into the back map. */
    private final Set<TileKey> changedKeys = new HashSet<>();

    public synchronized long frontGeneration() {
        return frontGeneration;
    }

    public synchronized PageTableEntry resolve(TileKey key) {
        return key == null ? null : front.get(key);
    }

    public synchronized int frontSize() {
        return front.size();
    }

    /**
     * Render-thread snapshot view. The front map is never mutated between frame
     * boundary swaps; writers touch only {@code back}. Callers must not retain the
     * returned reference across a later swap and must never mutate it.
     */
    synchronized Map<TileKey, PageTableEntry> frontView() {
        return front;
    }

    public synchronized void beginUpdate() {
        if (backPrepared) return;
        // After swapAtFrameBoundary both buffers contain the same entries. The
        // back buffer can therefore accept incremental mutations immediately.
        backPrepared = true;
    }

    public synchronized void stage(TileKey key, PageTableEntry entry) {
        if (key == null || entry == null) return;
        beginUpdate();
        PageTableEntry previous = back.get(key);
        if (previous != null && previous.contentRevision() > entry.contentRevision()) {
            return;
        }
        back.put(key, entry);
        changedKeys.add(key);
    }

    public synchronized void remove(TileKey key) {
        if (key == null) return;
        beginUpdate();
        back.remove(key);
        changedKeys.add(key);
    }

    public synchronized List<RetiredSlot> swapAtFrameBoundary() {
        if (!backPrepared) return List.of();
        List<RetiredSlot> retired = null;
        // Only changed keys can retire a slot. The old implementation scanned the
        // entire front map and rebuilt the entire back HashMap every publication
        // frame, even when one page changed.
        for (TileKey key : changedKeys) {
            PageTableEntry previous = front.get(key);
            if (previous == null) continue;
            PageTableEntry replacement = back.get(key);
            if (replacement == null
                    || replacement.storageId() != previous.storageId()
                    || replacement.slot() != previous.slot()
                    || replacement.storageGeneration()
                            != previous.storageGeneration()) {
                if (retired == null) retired = new ArrayList<>();
                retired.add(new RetiredSlot(previous.storageId(), previous.slot(),
                        previous.storageGeneration()));
            }
        }
        Map<TileKey, PageTableEntry> oldFront = front;
        front = back;
        back = oldFront;

        // Bring the old front (now back) to the new authoritative state using the
        // same small delta. This preserves the invariant required by beginUpdate
        // without allocating one HashMap node per resident tile every frame.
        for (TileKey key : changedKeys) {
            PageTableEntry replacement = front.get(key);
            if (replacement == null) back.remove(key);
            else back.put(key, replacement);
        }
        changedKeys.clear();
        backPrepared = false;
        frontGeneration++;
        return retired == null ? List.of() : retired;
    }

    public synchronized void clear() {
        front.clear();
        back.clear();
        changedKeys.clear();
        backPrepared = false;
        frontGeneration++;
    }

    public synchronized Map<TileKey, PageTableEntry> snapshotFront() {
        return Map.copyOf(front);
    }
}
