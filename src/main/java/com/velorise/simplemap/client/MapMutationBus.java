package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CavePipeline;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

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
    private static final int MAX_PENDING_COLUMNS = 8_192;
    private static final int MAX_PENDING_CHUNKS = 4_096;
    private static final int COLUMN_WORK_PER_TICK = 64;
    private static final int CHUNK_EXPANSION_PER_TICK = 2;
    private static final int CHUNK_COLUMN_BURST = 32;
    private static final int REGION_CHUNK_BURST = 8;
    private static final int RETRY_DELAY_TICKS = 2;
    private static final int MAX_RETRIES = 40;

    /*
     * These queues are fed directly by packet handlers. Using boxed Long keys in
     * both HashMap and ArrayDeque allocated two (and sometimes more) wrapper
     * objects per mutation. A fresh chunk/light burst could therefore create
     * hundreds of MiB/s of short-lived garbage before any map work began. Keep
     * the same coalescing/order semantics with fastutil's primitive collections.
     */
    private final Long2ObjectOpenHashMap<ColumnMutation> columns =
            new Long2ObjectOpenHashMap<>(2_048);
    private final LongArrayFIFOQueue columnOrder = new LongArrayFIFOQueue(2_048);
    private final Long2ObjectOpenHashMap<ChunkMutation> chunks =
            new Long2ObjectOpenHashMap<>(512);
    private final LongArrayFIFOQueue chunkOrder = new LongArrayFIFOQueue(512);
    /** Last-resort durable dirty state when the finer queues are saturated. */
    private final Long2ObjectOpenHashMap<RegionMutation> regions =
            new Long2ObjectOpenHashMap<>(64);
    private final LongArrayFIFOQueue regionOrder = new LongArrayFIFOQueue(64);

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
        enqueueChunkWithDependentBorders(chunkX, chunkZ, CHUNK_REPLACE, true);
    }

    /** Light packets restyle the changed chunk plus directly dependent edge pixels. */
    public synchronized void onLightUpdate(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(level, chunkX, chunkZ);
        enqueueChunkWithDependentBorders(chunkX, chunkZ, LIGHT, true);
    }

    /** Keep old pixels, revoke live authority and wait for a later load packet. */
    public synchronized void onChunkUnload(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markUnavailable(level, chunkX, chunkZ);
        enqueueChunk(chunkX, chunkZ, CHUNK_UNLOAD | NEIGHBOUR_DEPENDENCY,
                false, true);
    }

    /** Section-wide block packets are cheaper to represent as one chunk transaction. */
    public synchronized void onSectionBlocksUpdate(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(level, chunkX, chunkZ);
        enqueueChunkWithDependentBorders(chunkX, chunkZ,
                BLOCK_STATE | NEIGHBOUR_DEPENDENCY, true);
    }

    /** Runs after CavePipeline observes world/teleport state and before viewport scans. */
    public void tick(Minecraft minecraft) {
        tick(minecraft, COLUMN_WORK_PER_TICK, CHUNK_EXPANSION_PER_TICK,
                MapPerformanceGovernor.getInstance().mutationRepairBudgetNanos());
    }

    /** Dynamic admission used by MapObservationScheduler under frame pressure. */
    public void tick(Minecraft minecraft, int columnBudget, int chunkBudget,
            long timeBudgetNanos) {
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
        long started = System.nanoTime();
        long deadline = started + Math.max(100_000L, timeBudgetNanos);
        if (deadline < started) deadline = Long.MAX_VALUE;
        expandRegions(Math.max(1, chunkBudget / 2), deadline);
        int safeColumnBudget = Math.max(0, columnBudget);
        boolean chunkWorkWaiting;
        synchronized (this) {
            chunkWorkWaiting = !chunks.isEmpty();
        }
        // Preserve a small low-latency lane for individual block edits, then spend
        // the rest directly on compact chunk cursors. The old path materialized up
        // to 2,304 ColumnMutation objects for one packet before doing any scan.
        int urgentColumnBudget = chunkWorkWaiting
                ? Math.min(safeColumnBudget, 1)
                : safeColumnBudget;
        int processed = processColumns(level, urgentColumnBudget, deadline);
        // Even when the one urgent column consumed the deadline, allow one compact
        // chunk cursor to publish its cave invalidation. Surface pixels remain
        // pre-emptible and continue from the same primitive cursor next tick.
        processed += expandChunks(level, Math.max(0, chunkBudget),
                Math.max(0, safeColumnBudget - processed), deadline);
        if (processed < safeColumnBudget && System.nanoTime() < deadline) {
            processed += processColumns(level, safeColumnBudget - processed, deadline);
        }
    }

    private int processColumns(Level level, int budget, long deadline) {
        int processed = 0;
        int examined = 0;
        int maximumExamined = Math.max(1, budget << 1);
        while (processed < budget && examined < maximumExamined
                && (examined == 0 || System.nanoTime() < deadline)) {
            ColumnMutation mutation = pollColumn();
            if (mutation == null) break;
            examined++;
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
        return processed;
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
    private void expandRegions(int regionBudget, long deadline) {
        int expanded = 0;
        while (expanded < regionBudget
                && (expanded == 0 || System.nanoTime() < deadline)) {
            RegionMutation mutation = pollRegion();
            if (mutation == null) return;
            boolean deferred = false;
            for (int i = 0; i < REGION_CHUNK_BURST && mutation.hasDirtyChunks(); i++) {
                if (!enqueueChunkFromRegion(mutation)) {
                    deferred = true;
                    break;
                }
            }
            if (mutation.hasDirtyChunks() || deferred) requeueRegion(mutation);
            expanded++;
        }
    }

    private int expandChunks(Level level, int expansionBudget, int columnBudget,
            long deadline) {
        int expanded = 0;
        int processed = 0;
        long gameTick = level.getGameTime();
        while (expanded < expansionBudget
                && (expanded == 0 || System.nanoTime() < deadline)) {
            ChunkMutation mutation = pollChunk(gameTick);
            if (mutation == null) break;

            if ((mutation.reasons & CHUNK_UNLOAD) != 0) {
                CavePipeline.getInstance().onChunkUnavailable(
                        mutation.chunkX, mutation.chunkZ, mutation.reasons);
                processed += scanDependentBorders(level, mutation,
                        Math.max(0, columnBudget - processed), deadline);
                if (mutation.hasRemainingSurfaceWork()) requeueChunk(mutation);
                expanded++;
                if (processed >= columnBudget) break;
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
            if (!mutation.hasRemainingSurfaceWork()) {
                expanded++;
                continue;
            }

            int available = Math.max(0, columnBudget - processed);
            int centralBudget = mutation.rescanSurface
                    ? Math.min(CHUNK_COLUMN_BURST, available) : 0;
            int end = Math.min(256, mutation.nextColumn + centralBudget);
            while (mutation.rescanSurface && mutation.nextColumn < end
                    && System.nanoTime() < deadline) {
                int column = mutation.nextColumn;
                int blockX = (mutation.chunkX << 4) + (column & 15);
                int blockZ = (mutation.chunkZ << 4) + (column >>> 4);
                scanSurfaceMutationColumn(level, blockX, blockZ, mutation.reasons);
                mutation.nextColumn++;
                processed++;
                processedColumns++;
            }
            processed += scanDependentBorders(level, mutation,
                    Math.max(0, columnBudget - processed), deadline);
            if (mutation.hasRemainingSurfaceWork()) requeueChunk(mutation);
            expanded++;
            if (processed >= columnBudget) break;
        }
        return processed;
    }

    private synchronized void enqueueChunkWithDependentBorders(int chunkX,
            int chunkZ, int reasons, boolean rescanSurface) {
        enqueueChunk(chunkX, chunkZ, reasons | NEIGHBOUR_DEPENDENCY,
                rescanSurface, rescanSurface);
    }

    /**
     * Only pixels immediately outside a changed chunk depend on its height/light
     * for slope and edge styling. Re-scanning all eight neighbouring chunks was a
     * 9x amplification and the dominant allocation spike on chunk packets.
     */
    private int scanDependentBorders(Level level, ChunkMutation mutation, int budget,
            long deadline) {
        if (!mutation.repairDependentBorders || budget <= 0) return 0;
        int processed = 0;
        int minX = mutation.chunkX << 4;
        int minZ = mutation.chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int end = Math.min(68, mutation.nextBorderColumn
                + Math.min(CHUNK_COLUMN_BURST, budget));
        while (mutation.nextBorderColumn < end && System.nanoTime() < deadline) {
            int cursor = mutation.nextBorderColumn++;
            int blockX;
            int blockZ;
            if (cursor < 16) {
                blockX = minX - 1;
                blockZ = minZ + cursor;
            } else if (cursor < 32) {
                blockX = maxX + 1;
                blockZ = minZ + cursor - 16;
            } else if (cursor < 48) {
                blockX = minX + cursor - 32;
                blockZ = minZ - 1;
            } else if (cursor < 64) {
                blockX = minX + cursor - 48;
                blockZ = maxZ + 1;
            } else {
                int corner = cursor - 64;
                blockX = (corner & 1) == 0 ? minX - 1 : maxX + 1;
                blockZ = (corner & 2) == 0 ? minZ - 1 : maxZ + 1;
            }
            // Missing neighbour chunks retain their durable pixels. Their own load
            // packet will repair the seam later, without keeping this task alive.
            if (level.hasChunk(blockX >> 4, blockZ >> 4)) {
                scanSurfaceMutationColumn(level, blockX, blockZ, mutation.reasons);
                processedColumns++;
            }
            processed++;
        }
        return processed;
    }

    private static void scanSurfaceMutationColumn(Level level, int blockX,
            int blockZ, int reasons) {
        if ((reasons & LIGHT) != 0
                && (reasons & (BLOCK_STATE | CHUNK_REPLACE)) == 0) {
            ChunkScanner.getInstance().scanSurfaceLightColumn(level, blockX, blockZ);
        } else {
            ChunkScanner.getInstance().scanSurfaceColumn(level, blockX, blockZ);
        }
    }

    private synchronized void enqueueChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface) {
        enqueueChunk(chunkX, chunkZ, reasons, rescanSurface, false);
    }

    private synchronized void enqueueChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface, boolean repairDependentBorders) {
        receivedMutations++;
        long key = ChunkPos.asLong(chunkX, chunkZ);
        ChunkMutation existing = chunks.get(key);
        if (existing != null) {
            if ((reasons & CHUNK_UNLOAD) != 0) {
                existing.reasons = CHUNK_UNLOAD;
                existing.rescanSurface = false;
                existing.nextColumn = 0;
                existing.nextBorderColumn = 0;
                existing.caveHandled = false;
            } else {
                // A later load/update supersedes an earlier unload for the same key.
                existing.reasons = (existing.reasons & ~CHUNK_UNLOAD) | reasons;
                existing.rescanSurface |= rescanSurface;
                if ((existing.reasons & CHUNK_UNLOAD) == 0) existing.retries = 0;
            }
            existing.repairDependentBorders |= repairDependentBorders;
            coalescedMutations++;
            return;
        }
        if (chunks.size() >= MAX_PENDING_CHUNKS) {
            escalatedChunks++;
            enqueueRegionChunk(chunkX, chunkZ, reasons, rescanSurface,
                    repairDependentBorders);
            return;
        }
        ChunkMutation created = new ChunkMutation(chunkX, chunkZ, reasons,
                rescanSurface, repairDependentBorders);
        chunks.put(key, created);
        chunkOrder.enqueue(key);
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
        if (urgent) columnOrder.enqueueFirst(key);
        else columnOrder.enqueue(key);
        return true;
    }

    private synchronized ColumnMutation pollColumn() {
        while (!columnOrder.isEmpty()) {
            long key = columnOrder.dequeueLong();
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
        if (urgent) columnOrder.enqueueFirst(key);
        else columnOrder.enqueue(key);
    }

    private synchronized ChunkMutation pollChunk(long gameTick) {
        int checks = chunkOrder.size();
        while (checks-- > 0 && !chunkOrder.isEmpty()) {
            long key = chunkOrder.dequeueLong();
            ChunkMutation mutation = chunks.remove(key);
            if (mutation == null) continue;
            if (mutation.retryAfterTick > gameTick) {
                chunks.put(key, mutation);
                chunkOrder.enqueue(key);
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
            existing.repairDependentBorders |= mutation.repairDependentBorders;
            existing.nextBorderColumn = Math.min(existing.nextBorderColumn,
                    mutation.nextBorderColumn);
            existing.caveHandled &= mutation.caveHandled;
            return;
        }
        // A chunk can be polled, discover that its client data is unavailable,
        // and find the queue full by the time it is requeued. Preserve its dirty
        // meaning at the coarser durable level rather than breaking the cap.
        if (chunks.size() >= MAX_PENDING_CHUNKS) {
            escalatedChunks++;
            enqueueRegionChunk(mutation.chunkX, mutation.chunkZ,
                    mutation.reasons, mutation.rescanSurface,
                    mutation.repairDependentBorders);
            return;
        }
        chunks.put(key, mutation);
        chunkOrder.enqueue(key);
    }

    private synchronized boolean enqueueChunkFromRegion(RegionMutation region) {
        if (chunks.size() >= MAX_PENDING_CHUNKS) return false;
        int offset = region.takeNextDirtyChunk();
        if (offset < 0) return true;
        int chunkX = (region.regionX << 5) + (offset & 31);
        int chunkZ = (region.regionZ << 5) + (offset >>> 5);
        enqueueChunk(chunkX, chunkZ, region.reasons, region.rescanSurface,
                region.repairDependentBorders);
        return true;
    }

    private synchronized void enqueueRegionChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface, boolean repairDependentBorders) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = ChunkPos.asLong(regionX, regionZ);
        RegionMutation existing = regions.get(key);
        if (existing != null) {
            existing.reasons |= reasons;
            existing.rescanSurface |= rescanSurface;
            existing.repairDependentBorders |= repairDependentBorders;
            existing.markDirty(chunkX, chunkZ);
            coalescedMutations++;
            return;
        }
        RegionMutation created = new RegionMutation(regionX, regionZ, reasons,
                rescanSurface, repairDependentBorders);
        created.markDirty(chunkX, chunkZ);
        regions.put(key, created);
        regionOrder.enqueue(key);
    }

    private synchronized RegionMutation pollRegion() {
        while (!regionOrder.isEmpty()) {
            long key = regionOrder.dequeueLong();
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
            existing.repairDependentBorders |= mutation.repairDependentBorders;
            existing.mergeDirty(mutation);
            return;
        }
        regions.put(key, mutation);
        regionOrder.enqueue(key);
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
        private boolean repairDependentBorders;
        private int nextBorderColumn;
        private int retries;
        private long retryAfterTick;

        private ChunkMutation(int chunkX, int chunkZ, int reasons,
                boolean rescanSurface, boolean repairDependentBorders) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.reasons = reasons;
            this.rescanSurface = rescanSurface;
            this.repairDependentBorders = repairDependentBorders;
        }

        private boolean hasRemainingSurfaceWork() {
            return (rescanSurface && nextColumn < 256)
                    || (repairDependentBorders && nextBorderColumn < 68);
        }
    }

    private static final class RegionMutation {
        private final int regionX;
        private final int regionZ;
        private int reasons;
        private boolean rescanSurface;
        private boolean repairDependentBorders;
        private final long[] dirtyChunks = new long[16];
        private int dirtyCount;
        private int cursor;

        private RegionMutation(int regionX, int regionZ, int reasons,
                boolean rescanSurface, boolean repairDependentBorders) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.reasons = reasons;
            this.rescanSurface = rescanSurface;
            this.repairDependentBorders = repairDependentBorders;
        }

        private void markDirty(int chunkX, int chunkZ) {
            int localX = chunkX & 31;
            int localZ = chunkZ & 31;
            int offset = (localZ << 5) | localX;
            int word = offset >>> 6;
            long bit = 1L << (offset & 63);
            if ((dirtyChunks[word] & bit) != 0L) return;
            dirtyChunks[word] |= bit;
            dirtyCount++;
            cursor = Math.min(cursor, offset);
        }

        private boolean hasDirtyChunks() {
            return dirtyCount > 0;
        }

        private int takeNextDirtyChunk() {
            if (dirtyCount <= 0) return -1;
            for (int checked = 0; checked < 1024; checked++) {
                int offset = (cursor + checked) & 1023;
                int word = offset >>> 6;
                long bit = 1L << (offset & 63);
                if ((dirtyChunks[word] & bit) == 0L) continue;
                dirtyChunks[word] &= ~bit;
                dirtyCount--;
                cursor = (offset + 1) & 1023;
                return offset;
            }
            dirtyCount = 0;
            return -1;
        }

        private void mergeDirty(RegionMutation other) {
            for (int i = 0; i < dirtyChunks.length; i++) {
                long added = other.dirtyChunks[i] & ~dirtyChunks[i];
                if (added == 0L) continue;
                dirtyChunks[i] |= added;
                dirtyCount += Long.bitCount(added);
            }
        }
    }

    public record Snapshot(int pendingColumns, int pendingChunks, int pendingRegions,
            long receivedMutations, long coalescedMutations, long processedColumns,
            long escalatedColumns, long escalatedChunks) {
    }
}
