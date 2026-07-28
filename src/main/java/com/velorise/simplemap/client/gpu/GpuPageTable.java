package com.velorise.simplemap.client.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public synchronized long frontGeneration() {
        return frontGeneration;
    }

    public synchronized PageTableEntry resolve(TileKey key) {
        return key == null ? null : front.get(key);
    }

    public synchronized int frontSize() {
        return front.size();
    }

    public synchronized void beginUpdate() {
        if (backPrepared) return;
        back.clear();
        back.putAll(front);
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
    }

    public synchronized void remove(TileKey key) {
        if (key == null) return;
        beginUpdate();
        back.remove(key);
    }

    public synchronized List<RetiredSlot> swapAtFrameBoundary() {
        if (!backPrepared) return List.of();
        List<RetiredSlot> retired = new ArrayList<>();
        for (Map.Entry<TileKey, PageTableEntry> old : front.entrySet()) {
            PageTableEntry replacement = back.get(old.getKey());
            PageTableEntry previous = old.getValue();
            if (replacement == null
                    || replacement.storageId() != previous.storageId()
                    || replacement.slot() != previous.slot()
                    || replacement.storageGeneration() != previous.storageGeneration()) {
                retired.add(new RetiredSlot(previous.storageId(), previous.slot(),
                        previous.storageGeneration()));
            }
        }
        Map<TileKey, PageTableEntry> oldFront = front;
        front = back;
        back = oldFront;
        back.clear();
        backPrepared = false;
        frontGeneration++;
        return List.copyOf(retired);
    }

    public synchronized void clear() {
        front.clear();
        back.clear();
        backPrepared = false;
        frontGeneration++;
    }

    public synchronized Map<TileKey, PageTableEntry> snapshotFront() {
        return Map.copyOf(front);
    }
}
