package com.velorise.simplemap.client.gpu;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime bridge between logical page-table entries and Minecraft textures. */
public final class MapGpuPageTableService {
    public record Resolved(PageTableEntry entry, ResourceLocation texture) { }
    public record Summary(long generation, int entries, int storages,
            long staged, long swaps, long generationMismatches) { }

    private static final MapGpuPageTableService INSTANCE =
            new MapGpuPageTableService();

    private final GpuPageTable table = new GpuPageTable();
    private final AtomicInteger nextStorageId = new AtomicInteger(1);
    private final Map<ResourceLocation, Integer> storageIds = new HashMap<>();
    private final Map<Integer, Storage> storages = new HashMap<>();
    private long staged;
    private long swaps;
    private long generationMismatches;

    private MapGpuPageTableService() { }

    public static MapGpuPageTableService getInstance() {
        return INSTANCE;
    }

    public synchronized int storageId(ResourceLocation texture,
            long storageGeneration) {
        if (texture == null) return -1;
        Integer existing = storageIds.get(texture);
        if (existing != null) {
            Storage storage = storages.get(existing);
            if (storage != null && storage.generation != storageGeneration) {
                storages.put(existing, new Storage(texture, storageGeneration));
            }
            return existing;
        }
        int id = nextStorageId.getAndIncrement();
        storageIds.put(texture, id);
        storages.put(id, new Storage(texture, storageGeneration));
        return id;
    }

    public synchronized void stage(TileKey key, ResourceLocation texture,
            int slot, long storageGeneration, long contentRevision,
            int flags, float sourceX, float sourceY,
            int sourceSize, int atlasSize) {
        if (key == null || texture == null || slot < 0) return;
        int storageId = storageId(texture, storageGeneration);
        table.stage(key, new PageTableEntry(storageId, slot,
                storageGeneration, Math.max(1L, contentRevision), key.level(),
                flags | PageTableEntry.FLAG_RESIDENT,
                sourceX, sourceY, sourceSize, atlasSize));
        staged++;
    }

    public synchronized void remove(TileKey key) {
        table.remove(key);
    }

    public synchronized Resolved resolve(TileKey key) {
        PageTableEntry entry = table.resolve(key);
        if (entry == null || !entry.resident()) return null;
        Storage storage = storages.get(entry.storageId());
        if (storage == null || storage.generation != entry.storageGeneration()) {
            generationMismatches++;
            return null;
        }
        return new Resolved(entry, storage.texture);
    }

    public synchronized List<GpuPageTable.RetiredSlot> swapAtFrameBoundary() {
        long before = table.frontGeneration();
        List<GpuPageTable.RetiredSlot> retired = table.swapAtFrameBoundary();
        if (table.frontGeneration() != before) swaps++;
        return retired;
    }

    public synchronized Summary summary() {
        return new Summary(table.frontGeneration(), table.frontSize(),
                storages.size(), staged, swaps, generationMismatches);
    }

    public synchronized void clear() {
        table.clear();
        storageIds.clear();
        storages.clear();
        nextStorageId.set(1);
    }

    private record Storage(ResourceLocation texture, long generation) { }
}
