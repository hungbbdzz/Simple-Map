package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CavePipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Central client-world mutation queue shared by the surface and cave pipelines.
 *
 * <p>Packet hooks only record compact mutation facts. Actual map work is drained on
 * the normal client tick, after Minecraft has applied the packet to the ClientLevel.
 * Duplicate block, light and chunk updates are coalesced before any column scan is
 * performed.</p>
 */
public final class MapMutationBus {
    public static final int BLOCK_STATE = 1;
    public static final int LIGHT = 1 << 1;
    public static final int CHUNK_REPLACE = 1 << 2;
    public static final int CHUNK_UNLOAD = 1 << 3;
    public static final int NEIGHBOUR_DEPENDENCY = 1 << 4;
    public static final int BLOCK_ENTITY = 1 << 5;

    private static final MapMutationBus INSTANCE = new MapMutationBus();
    private static final int MAX_PENDING_COLUMNS = 65_536;
    private static final int MAX_PENDING_CHUNKS = 4_096;
    private static final int COLUMN_WORK_PER_TICK = 64;
    private static final int CHUNK_EXPANSION_PER_TICK = 2;
    private static final int CHUNK_COLUMN_BURST = 64;
    private static final int REGION_CHUNK_BURST = 8;
    private static final int RETRY_DELAY_TICKS = 2;
    private static final int MAX_RETRIES = 40;

    private final Map<Long, ColumnMutation> columns = new HashMap<>();
    private final ArrayDeque<Long> columnOrder = new ArrayDeque<>();
    private final Map<Long, ChunkMutation> chunks = new HashMap<>();
    private final ArrayDeque<Long> chunkOrder = new ArrayDeque<>();
    /** Last-resort durable dirty state when the finer queues are saturated. */
    private final Map<Long, RegionMutation> regions = new HashMap<>();
    private final ArrayDeque<Long> regionOrder = new ArrayDeque<>();

    private Level observedLevel;
    private long receivedMutations;
    private long coalescedMutations;
    private long processedColumns;
    private long escalatedColumns;
    private long escalatedChunks;

    private MapMutationBus() {
    }

    public static MapMutationBus getInstance() {
        return INSTANCE;
    }

    /** A single block packet can change tint, shape, slope and light around it. */
    public synchronized void onBlockUpdate(BlockPos position) {
        if (position == null) return;
        int centerX = position.getX();
        int centerZ = position.getZ();
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(
                level, centerX >> 4, centerZ >> 4);
        int reasons = BLOCK_STATE | NEIGHBOUR_DEPENDENCY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                enqueueColumn(centerX + dx, centerZ + dz, reasons, false, true);
            }
        }
    }

    /** Block-entity visual packets can change camouflage without a BlockState packet. */
    public synchronized void onBlockEntityUpdate(BlockPos position) {
        if (position == null) return;
        int centerX = position.getX();
        int centerZ = position.getZ();
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(
                level, centerX >> 4, centerZ >> 4);
        int reasons = BLOCK_ENTITY | NEIGHBOUR_DEPENDENCY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                enqueueColumn(centerX + dx, centerZ + dz, reasons, false, true);
            }
        }
    }

    /** Chunk data replacement affects the chunk and every border-dependent neighbour. */
    public synchronized void onChunkData(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            GeneratedChunkIndex.getInstance().markMutation(level, chunkX, chunkZ);
            GeneratedChunkIndex.getInstance().markLive(level, chunkX, chunkZ);
        }
        enqueueChunkNeighbourhood(chunkX, chunkZ, CHUNK_REPLACE, true);
    }

    /** Light packets can restyle every pixel and all edge slopes in the 3x3 area. */
    public synchronized void onLightUpdate(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(level, chunkX, chunkZ);
        enqueueChunkNeighbourhood(chunkX, chunkZ, LIGHT, true);
    }

    /** Keep old pixels, revoke live authority and wait for a later load packet. */
    public synchronized void onChunkUnload(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markUnavailable(level, chunkX, chunkZ);
        enqueueChunk(chunkX, chunkZ, CHUNK_UNLOAD, false);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                enqueueChunk(chunkX + dx, chunkZ + dz,
                        NEIGHBOUR_DEPENDENCY, true);
            }
        }
    }

    /** Section-wide block packets are cheaper to represent as one chunk transaction. */
    public synchronized void onSectionBlocksUpdate(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(level, chunkX, chunkZ);
        enqueueChunkNeighbourhood(chunkX, chunkZ,
                BLOCK_STATE | NEIGHBOUR_DEPENDENCY, true);
    }

    /** Runs after CavePipeline observes world/teleport state and before viewport scans. */
    public void tick(Minecraft minecraft) {
        tick(minecraft, COLUMN_WORK_PER_TICK, CHUNK_EXPANSION_PER_TICK);
    }

    /** Dynamic admission used by MapObservationScheduler under frame pressure. */
    public void tick(Minecraft minecraft, int columnBudget, int chunkBudget) {
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            reset();
            return;
        }
        Level level = minecraft.level;
        synchronized (this) {
            if (observedLevel != level) {
                clearQueuesLocked();
                observedLevel = level;
            }
        }

        GeneratedChunkIndex.getInstance().observeLevel(level);
        expandRegions(Math.max(1, chunkBudget / 2));
        expandChunks(level, Math.max(0, chunkBudget));
        int processed = 0;
        int safeColumnBudget = Math.max(0, columnBudget);
        while (processed < safeColumnBudget) {
            ColumnMutation mutation = pollColumn();
            if (mutation == null) break;
            if (!level.hasChunk(mutation.blockX >> 4, mutation.blockZ >> 4)) {
                if (++mutation.retries <= MAX_RETRIES) requeueColumn(mutation, false);
                continue;
            }

            // Surface and cave consume the same authoritative column event. Surface
            // rescans immediately; cave uses a transactionally committed column patch.
            if ((mutation.reasons & LIGHT) != 0
                    && (mutation.reasons & (BLOCK_STATE | CHUNK_REPLACE)) == 0) {
                ChunkScanner.getInstance().scanSurfaceLightColumn(level,
                        mutation.blockX, mutation.blockZ);
            } else {
                ChunkScanner.getInstance().scanSurfaceColumn(level,
                        mutation.blockX, mutation.blockZ);
            }
            if (!mutation.caveHandled) {
                CavePipeline.getInstance().onColumnMutation(
                        mutation.blockX, mutation.blockZ, mutation.reasons);
            }
            processed++;
            processedColumns++;
        }
    }

    public synchronized void reset() {
        clearQueuesLocked();
        observedLevel = null;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(columns.size(), chunks.size(), regions.size(), receivedMutations,
                coalescedMutations, processedColumns, escalatedColumns, escalatedChunks);
    }

    /** Materializes a bounded amount of region dirtiness only when finer queues have room. */
    private void expandRegions(int regionBudget) {
        int expanded = 0;
        while (expanded < regionBudget) {
            RegionMutation mutation = pollRegion();
            if (mutation == null) return;
            boolean deferred = false;
            for (int i = 0; i < REGION_CHUNK_BURST && mutation.nextChunk < 1024; i++) {
                if (!enqueueChunkFromRegion(mutation)) {
                    deferred = true;
                    break;
                }
                mutation.nextChunk++;
            }
            if (mutation.nextChunk < 1024 || deferred) requeueRegion(mutation);
            expanded++;
        }
    }

    private void expandChunks(Level level, int expansionBudget) {
        int expanded = 0;
        long gameTick = level.getGameTime();
        while (expanded < expansionBudget) {
            ChunkMutation mutation = pollChunk(gameTick);
            if (mutation == null) break;

            if ((mutation.reasons & CHUNK_UNLOAD) != 0) {
                CavePipeline.getInstance().onChunkUnavailable(
                        mutation.chunkX, mutation.chunkZ, mutation.reasons);
                expanded++;
                continue;
            }

            if (!level.hasChunk(mutation.chunkX, mutation.chunkZ)) {
                if (++mutation.retries <= MAX_RETRIES) {
                    mutation.retryAfterTick = gameTick + RETRY_DELAY_TICKS;
                    requeueChunk(mutation);
                }
                expanded++;
                continue;
            }

            if (!mutation.caveHandled) {
                CavePipeline.getInstance().onChunkMutation(
                        mutation.chunkX, mutation.chunkZ, mutation.reasons);
                mutation.caveHandled = true;
            }
            if (!mutation.rescanSurface) {
                expanded++;
                continue;
            }

            int end = Math.min(256, mutation.nextColumn + CHUNK_COLUMN_BURST);
            boolean deferred = false;
            while (mutation.nextColumn < end) {
                int column = mutation.nextColumn;
                int blockX = (mutation.chunkX << 4) + (column & 15);
                int blockZ = (mutation.chunkZ << 4) + (column >>> 4);
                if (!enqueueColumn(blockX, blockZ, mutation.reasons, true, false)) {
                    deferred = true;
                    break;
                }
                mutation.nextColumn++;
            }
            if (mutation.nextColumn < 256 || deferred) requeueChunk(mutation);
            expanded++;
        }
    }

    private synchronized void enqueueChunkNeighbourhood(int centerChunkX,
            int centerChunkZ, int reasons, boolean rescanSurface) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                enqueueChunk(centerChunkX + dx, centerChunkZ + dz,
                        reasons, rescanSurface);
            }
        }
    }

    private synchronized void enqueueChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface) {
        receivedMutations++;
        long key = ChunkPos.asLong(chunkX, chunkZ);
        ChunkMutation existing = chunks.get(key);
        if (existing != null) {
            if ((reasons & CHUNK_UNLOAD) != 0) {
                existing.reasons = CHUNK_UNLOAD;
                existing.rescanSurface = false;
                existing.nextColumn = 0;
                existing.caveHandled = false;
            } else {
                // A later load/update supersedes an earlier unload for the same key.
                existing.reasons = (existing.reasons & ~CHUNK_UNLOAD) | reasons;
                existing.rescanSurface |= rescanSurface;
                if ((existing.reasons & CHUNK_UNLOAD) == 0) existing.retries = 0;
            }
            coalescedMutations++;
            return;
        }
        if (chunks.size() >= MAX_PENDING_CHUNKS) {
            escalatedChunks++;
            enqueueRegion(chunkX >> 5, chunkZ >> 5, reasons, rescanSurface);
            return;
        }
        ChunkMutation created = new ChunkMutation(chunkX, chunkZ, reasons,
                rescanSurface);
        chunks.put(key, created);
        chunkOrder.addLast(key);
    }

    private synchronized boolean enqueueColumn(int blockX, int blockZ, int reasons,
            boolean caveHandled, boolean urgent) {
        receivedMutations++;
        long key = packBlock(blockX, blockZ);
        ColumnMutation existing = columns.get(key);
        if (existing != null) {
            existing.reasons |= reasons;
            // If any producer still needs cave work, the merged event needs it too.
            existing.caveHandled &= caveHandled;
            coalescedMutations++;
            return true;
        }
        if (columns.size() >= MAX_PENDING_COLUMNS) {
            escalatedColumns++;
            enqueueChunk(blockX >> 4, blockZ >> 4, reasons, true);
            return false;
        }
        ColumnMutation created = new ColumnMutation(blockX, blockZ, reasons,
                caveHandled);
        columns.put(key, created);
        if (urgent) columnOrder.addFirst(key);
        else columnOrder.addLast(key);
        return true;
    }

    private synchronized ColumnMutation pollColumn() {
        while (!columnOrder.isEmpty()) {
            long key = columnOrder.removeFirst();
            ColumnMutation mutation = columns.remove(key);
            if (mutation != null) return mutation;
        }
        return null;
    }

    private synchronized void requeueColumn(ColumnMutation mutation, boolean urgent) {
        long key = packBlock(mutation.blockX, mutation.blockZ);
        ColumnMutation existing = columns.get(key);
        if (existing != null) {
            existing.reasons |= mutation.reasons;
            existing.caveHandled &= mutation.caveHandled;
            return;
        }
        // The column was temporarily removed for processing, therefore the
        // queue may have filled while its chunk was unavailable. Escalate
        // instead of silently exceeding the fine-grained cap or losing it.
        if (columns.size() >= MAX_PENDING_COLUMNS) {
            escalatedColumns++;
            enqueueChunk(mutation.blockX >> 4, mutation.blockZ >> 4,
                    mutation.reasons, true);
            return;
        }
        columns.put(key, mutation);
        if (urgent) columnOrder.addFirst(key);
        else columnOrder.addLast(key);
    }

    private synchronized ChunkMutation pollChunk(long gameTick) {
        int checks = chunkOrder.size();
        while (checks-- > 0 && !chunkOrder.isEmpty()) {
            long key = chunkOrder.removeFirst();
            ChunkMutation mutation = chunks.remove(key);
            if (mutation == null) continue;
            if (mutation.retryAfterTick > gameTick) {
                chunks.put(key, mutation);
                chunkOrder.addLast(key);
                continue;
            }
            return mutation;
        }
        return null;
    }

    private synchronized void requeueChunk(ChunkMutation mutation) {
        long key = ChunkPos.asLong(mutation.chunkX, mutation.chunkZ);
        ChunkMutation existing = chunks.get(key);
        if (existing != null) {
            if ((existing.reasons & CHUNK_UNLOAD) != 0
                    && (mutation.reasons & CHUNK_UNLOAD) == 0) {
                return;
            }
            existing.reasons |= mutation.reasons;
            existing.rescanSurface |= mutation.rescanSurface;
            existing.nextColumn = Math.min(existing.nextColumn, mutation.nextColumn);
            existing.caveHandled &= mutation.caveHandled;
            return;
        }
        // A chunk can be polled, discover that its client data is unavailable,
        // and find the queue full by the time it is requeued. Preserve its dirty
        // meaning at the coarser durable level rather than breaking the cap.
        if (chunks.size() >= MAX_PENDING_CHUNKS) {
            escalatedChunks++;
            enqueueRegion(mutation.chunkX >> 5, mutation.chunkZ >> 5,
                    mutation.reasons, mutation.rescanSurface);
            return;
        }
        chunks.put(key, mutation);
        chunkOrder.addLast(key);
    }

    private synchronized boolean enqueueChunkFromRegion(RegionMutation region) {
        if (chunks.size() >= MAX_PENDING_CHUNKS) return false;
        int offset = region.nextChunk;
        int chunkX = (region.regionX << 5) + (offset & 31);
        int chunkZ = (region.regionZ << 5) + (offset >>> 5);
        enqueueChunk(chunkX, chunkZ, region.reasons, region.rescanSurface);
        return true;
    }

    private synchronized void enqueueRegion(int regionX, int regionZ, int reasons,
            boolean rescanSurface) {
        long key = ChunkPos.asLong(regionX, regionZ);
        RegionMutation existing = regions.get(key);
        if (existing != null) {
            existing.reasons |= reasons;
            existing.rescanSurface |= rescanSurface;
            coalescedMutations++;
            return;
        }
        regions.put(key, new RegionMutation(regionX, regionZ, reasons, rescanSurface));
        regionOrder.addLast(key);
    }

    private synchronized RegionMutation pollRegion() {
        while (!regionOrder.isEmpty()) {
            long key = regionOrder.removeFirst();
            RegionMutation mutation = regions.remove(key);
            if (mutation != null) return mutation;
        }
        return null;
    }

    private synchronized void requeueRegion(RegionMutation mutation) {
        long key = ChunkPos.asLong(mutation.regionX, mutation.regionZ);
        RegionMutation existing = regions.get(key);
        if (existing != null) {
            existing.reasons |= mutation.reasons;
            existing.rescanSurface |= mutation.rescanSurface;
            existing.nextChunk = Math.min(existing.nextChunk, mutation.nextChunk);
            return;
        }
        regions.put(key, mutation);
        regionOrder.addLast(key);
    }

    private void clearQueuesLocked() {
        columns.clear();
        columnOrder.clear();
        chunks.clear();
        chunkOrder.clear();
        regions.clear();
        regionOrder.clear();
    }

    private static long packBlock(int blockX, int blockZ) {
        return ((long) blockX << 32) ^ (blockZ & 0xFFFFFFFFL);
    }

    private static final class ColumnMutation {
        private final int blockX;
        private final int blockZ;
        private int reasons;
        private boolean caveHandled;
        private int retries;

        private ColumnMutation(int blockX, int blockZ, int reasons,
                boolean caveHandled) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.reasons = reasons;
            this.caveHandled = caveHandled;
        }
    }

    private static final class ChunkMutation {
        private final int chunkX;
        private final int chunkZ;
        private int reasons;
        private boolean rescanSurface;
        private boolean caveHandled;
        private int nextColumn;
        private int retries;
        private long retryAfterTick;

        private ChunkMutation(int chunkX, int chunkZ, int reasons,
                boolean rescanSurface) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.reasons = reasons;
            this.rescanSurface = rescanSurface;
        }
    }

    private static final class RegionMutation {
        private final int regionX;
        private final int regionZ;
        private int reasons;
        private boolean rescanSurface;
        private int nextChunk;

        private RegionMutation(int regionX, int regionZ, int reasons,
                boolean rescanSurface) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.reasons = reasons;
            this.rescanSurface = rescanSurface;
        }
    }

    public record Snapshot(int pendingColumns, int pendingChunks, int pendingRegions,
            long receivedMutations, long coalescedMutations, long processedColumns,
            long escalatedColumns, long escalatedChunks) {
    }
}
