package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CavePipeline;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
    private static final int MAX_PENDING_CHUNKS = 2_048;
    private static final int HOT_COLUMN_PRECISE_LIMIT = 512;
    private static final int COLUMN_COMPACTION_THRESHOLD = 1_024;
    private static final int COLUMN_COMPACTION_TARGET = 512;
    /* Keep precise chunk transactions for the current render frontier, but do
     * not retain an entire multi-minute travel trail as 2,048 mutable objects.
     * Cold, not-yet-started work above this watermark is folded into durable
     * region bitsets; the newest authoritative packet remains precise. */
    private static final int CHUNK_REGION_ESCALATION_THRESHOLD =
            MovementMutationPolicy.PRECISE_CHUNK_WORKING_SET;
    private static final int COLUMN_COMPACTION_PER_TICK = 128;
    private static final int HOT_RADIUS_CHUNKS = 4;
    private static final int COLUMN_WORK_PER_TICK = 64;
    private static final int CHUNK_EXPANSION_PER_TICK = 2;
    /* The scanner checks the wall-clock deadline every eight columns. Let one
     * active transaction use the remaining slice instead of imposing a second
     * 64-column breadth-first cap that leaves thousands of chunks 25% complete. */
    private static final int CHUNK_COLUMN_BURST = 256;
    private static final int REGION_CHUNK_BURST = 8;
    private static final int RETRY_DELAY_TICKS = 2;
    private static final int MAX_COLUMN_RETRIES = 8;
    private static final int CAVE_FANOUT_PER_TICK = 64;
    private static final long NO_ACTIVE_CHUNK = Long.MIN_VALUE;
    /* Chunk packets are normally observable immediately. Retrying an unloaded
     * chunk for several seconds lets stale travel history monopolize the tiny
     * chunk budget and delay the newly loaded chunk in front of the player. */
    private static final int MAX_CHUNK_RETRIES = 8;

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
    /** Player-adjacent chunk/light packets bypass stale travel backlog. */
    private final LongArrayFIFOQueue urgentChunkOrder =
            new LongArrayFIFOQueue(128);
    /**
     * Chunk packets are one authoritative observation, but Surface and Cave have
     * different downstream costs. Keep the Cave invalidation/fanout in its own
     * compact lane so a partially scanned Surface chunk cannot delay every cave
     * projection behind thousands of 16x16 source transactions.
     */
    private final Long2IntOpenHashMap caveFanoutReasons =
            new Long2IntOpenHashMap(256);
    private final LongArrayFIFOQueue caveFanoutOrder =
            new LongArrayFIFOQueue(256);
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
    /** A started 16x16 Surface transaction is resumed before opening another. */
    private long activeSurfaceChunkKey = NO_ACTIVE_CHUNK;

    private MapMutationBus() {
    }

    public static MapMutationBus getInstance() {
        return INSTANCE;
    }

    /** A single block packet can change tint, shape, slope and light around it. */
    public synchronized void onBlockUpdate(BlockPos position) {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        if (position == null) return;
        int centerX = position.getX();
        int centerZ = position.getZ();
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(
                level, centerX >> 4, centerZ >> 4);
        int reasons = BLOCK_STATE | NEIGHBOUR_DEPENDENCY;
        boolean urgent = isHotChunk(centerX >> 4, centerZ >> 4);
        enqueueNeighbourhoodColumns(centerX, centerZ, reasons, urgent);
    }

    /** Block-entity visual packets can change camouflage without a BlockState packet. */
    public synchronized void onBlockEntityUpdate(BlockPos position) {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        if (position == null) return;
        int centerX = position.getX();
        int centerZ = position.getZ();
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(
                level, centerX >> 4, centerZ >> 4);
        int reasons = BLOCK_ENTITY | NEIGHBOUR_DEPENDENCY;
        boolean urgent = isHotChunk(centerX >> 4, centerZ >> 4);
        enqueueNeighbourhoodColumns(centerX, centerZ, reasons, urgent);
    }

    /** Chunk data replacement affects the chunk and every border-dependent neighbour. */
    public synchronized void onChunkData(int chunkX, int chunkZ) {
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            // Packet ingress remains O(1): record authority and append one primitive
            // key. ChunkScanner owns all expensive 16x16 capture later under its
            // frame deadline, matching Xaero's writer queue rather than scanning in
            // the packet callback.
            GeneratedChunkIndex.getInstance().markLive(level, chunkX, chunkZ);
            /*
             * PASS108: every chunk-data packet is an O(1) authoritative Surface
             * ingress event. The old fixed radius=6 gate meant chunks that Minecraft
             * had already loaded inside a larger render distance were invisible to
             * the writer and depended on a slow fallback sweep. ChunkScanner owns
             * queue bounds, stale-distance rejection and frame-budgeted capture.
             */
            ChunkScanner.getInstance().enqueueLoadedSurfaceChunk(chunkX, chunkZ);
            // Cave projection is substantially more expensive than Surface capture.
            // Only packets inside the player-hot 9x9 chunk window enter the travel
            // frontier; render-distance edge packets remain available to the normal
            // centre-out viewport scheduler after movement. This mirrors Xaero's
            // closest-region shortlist and prevents cold packet order from delaying
            // the cave tile under the player.
            if (isHotChunk(chunkX, chunkZ)) {
                CavePipeline.getInstance().enqueueLoadedChunk(chunkX, chunkZ);
            }
        }
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        if (level != null) {
            GeneratedChunkIndex.getInstance().markMutation(level, chunkX, chunkZ);
        }
        // Exact/Region LOD capture is awakened only after the complete 16x16
        // Surface transaction is published. Waking it at packet admission makes
        // every viewport probe the same 25/50/75%-complete source repeatedly.
        enqueueCaveFanout(chunkX, chunkZ,
                CHUNK_REPLACE | NEIGHBOUR_DEPENDENCY);
        // Surface acquisition is owned by the viewport scanner. Scheduling a
        // second 256-column transaction for every chunk packet duplicated source
        // work across the entire render distance and left travel-history backlog
        // after movement stopped. Cave authority still observes the packet above.
    }

    /** Light packets restyle the changed chunk plus directly dependent edge pixels. */
    public synchronized void onLightUpdate(int chunkX, int chunkZ) {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(level, chunkX, chunkZ);
        enqueueCaveFanout(chunkX, chunkZ, LIGHT);
        // Surface lighting is an independent byte cache. It does not affect slope
        // or neighbouring geometry, so a light packet must not fan out into the 68
        // border columns used by topology changes. When night shading is disabled,
        // Cave owns this observation and no empty Surface queue entry is required.
        if (MapConfig.minimapNightMode != 0 && level != null
                && level.hasChunk(chunkX, chunkZ)
                && isHotChunk(chunkX, chunkZ)) {
            enqueueChunk(chunkX, chunkZ, LIGHT, true,
                    false, true, true);
        }
    }

    /** Keep old pixels, revoke live authority and wait for a later load packet. */
    public synchronized void onChunkUnload(int chunkX, int chunkZ) {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markUnavailable(level, chunkX, chunkZ);
        enqueueCaveFanout(chunkX, chunkZ,
                CHUNK_UNLOAD | NEIGHBOUR_DEPENDENCY);
        // Unloading revokes live authority but intentionally keeps the last exact
        // Surface pixels. Enqueuing a Surface transaction here cannot publish a
        // new image; it only creates travel-history work and previously scanned up
        // to 68 dependent border columns for every chunk leaving render distance.
        if (MovementMutationPolicy.schedulesSurfaceWorkForUnload()) {
            enqueueChunk(chunkX, chunkZ,
                    CHUNK_UNLOAD | NEIGHBOUR_DEPENDENCY,
                    false, true, isHotChunk(chunkX, chunkZ), true);
        }
    }

    /** Section-wide block packets are cheaper to represent as one chunk transaction. */
    public synchronized void onSectionBlocksUpdate(int chunkX, int chunkZ) {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        Level level = Minecraft.getInstance().level;
        if (level != null) GeneratedChunkIndex.getInstance().markMutation(level, chunkX, chunkZ);
        enqueueCaveFanout(chunkX, chunkZ,
                BLOCK_STATE | NEIGHBOUR_DEPENDENCY);
        enqueueChunk(chunkX, chunkZ,
                BLOCK_STATE | NEIGHBOUR_DEPENDENCY, true, false,
                isHotChunk(chunkX, chunkZ), true);
    }

    private synchronized void enqueueCaveFanout(int chunkX, int chunkZ,
            int reasons) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        boolean existed = caveFanoutReasons.containsKey(key);
        int merged = caveFanoutReasons.get(key);
        if ((reasons & CHUNK_UNLOAD) != 0) {
            merged = CHUNK_UNLOAD | (reasons & NEIGHBOUR_DEPENDENCY);
        } else {
            merged = (merged & ~CHUNK_UNLOAD) | reasons;
        }
        caveFanoutReasons.put(key, merged);
        if (!existed) {
            if ((reasons & CHUNK_REPLACE) != 0) caveFanoutOrder.enqueueFirst(key);
            else caveFanoutOrder.enqueue(key);
        }
    }

    /** Runs after CavePipeline observes world/teleport state and before viewport scans. */
    public void tick(Minecraft minecraft) {
        tick(minecraft, COLUMN_WORK_PER_TICK, CHUNK_EXPANSION_PER_TICK,
                MapPerformanceGovernor.getInstance().mutationRepairBudgetNanos());
    }

    /** Dynamic admission used by MapObservationScheduler under frame pressure. */
    public void tick(Minecraft minecraft, int columnBudget, int chunkBudget,
            long timeBudgetNanos) {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            reset();
            return;
        }
        Level level = minecraft.level;
        if (!MapManager.getInstance().acceptsLiveLevel(level)) {
            // Packets keep arriving while the fullscreen map browses another
            // dimension. Those samples belong to the live ClientLevel and must
            // never drain into the remotely selected surface/cave stores.
            reset();
            return;
        }
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
        long fanoutDeadline = Math.min(deadline, started + Math.max(80_000L,
                Math.min(250_000L, Math.max(1L, timeBudgetNanos / 4L))));
        drainCaveFanout(CAVE_FANOUT_PER_TICK, fanoutDeadline);
        compactColumnsToChunks(COLUMN_COMPACTION_PER_TICK, deadline);
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

    private void drainCaveFanout(int budget, long deadline) {
        int drained = 0;
        while (drained < budget && (drained == 0 || System.nanoTime() < deadline)) {
            long key;
            int reasons;
            synchronized (this) {
                if (caveFanoutOrder.isEmpty()) return;
                key = caveFanoutOrder.dequeueLong();
                if (!caveFanoutReasons.containsKey(key)) continue;
                reasons = caveFanoutReasons.remove(key);
            }
            int chunkX = ChunkPos.getX(key);
            int chunkZ = ChunkPos.getZ(key);
            if ((reasons & CHUNK_UNLOAD) != 0) {
                CavePipeline.getInstance().onChunkUnavailable(chunkX, chunkZ, reasons);
            } else {
                CavePipeline.getInstance().onChunkMutation(chunkX, chunkZ, reasons);
            }
            synchronized (this) {
                // A packet that arrived while the callback above was running owns
                // a newer fanout. Only mark the Surface transaction synchronized
                // when no newer cave observation remains queued for this chunk.
                if (!caveFanoutReasons.containsKey(key)) {
                    ChunkMutation mutation = chunks.get(key);
                    if (mutation != null) mutation.caveHandled = true;
                }
            }
            drained++;
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
                if (++mutation.retries <= MAX_COLUMN_RETRIES) {
                    requeueColumn(mutation, mutation.urgent);
                }
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

    /**
     * Converts the oldest fine-grained column entries into one compact 256-bit
     * chunk cursor before the column queue reaches its hard cap. This is metadata
     * compaction only: no world access occurs here, and exact dirty columns remain
     * exact unless the chunk lane is itself saturated and must escalate to region.
     */
    private void compactColumnsToChunks(int budget, long deadline) {
        int compacted = 0;
        while (compacted < budget && System.nanoTime() < deadline) {
            synchronized (this) {
                if (columns.size() <= COLUMN_COMPACTION_TARGET) return;
            }
            ColumnMutation mutation = pollColumn();
            if (mutation == null) return;
            enqueueCompactedColumn(mutation.blockX, mutation.blockZ,
                    mutation.reasons, mutation.caveHandled, mutation.urgent);
            escalatedColumns++;
            compacted++;
        }
    }

    public synchronized void reset() {
        clearQueuesLocked();
        observedLevel = null;
    }

    /** Drops travel-time repair objects once per movement epoch. */
    public synchronized void dropQueuedWorkForMovement() {
        clearQueuesLocked();
    }

    /** Allocation-free queue depth probes for hot admission paths. */
    public synchronized int pendingColumns() {
        return columns.size();
    }

    public synchronized int pendingChunks() {
        return chunks.size();
    }

    public synchronized int pendingRegions() {
        return regions.size();
    }

    /** One monitor acquisition for admission decisions that need all three queues. */
    public synchronized boolean hasBacklog(int columnThreshold,
            int chunkThreshold) {
        return columns.size() >= Math.max(0, columnThreshold)
                || chunks.size() >= Math.max(0, chunkThreshold)
                || !regions.isEmpty();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(columns.size(), chunks.size(), regions.size(), receivedMutations,
                coalescedMutations, processedColumns, escalatedColumns, escalatedChunks);
    }

    /** Materializes a bounded amount of region dirtiness only when finer queues have room. */
    private void expandRegions(int regionBudget, long deadline) {
        synchronized (this) {
            if (chunks.size() >= CHUNK_REGION_ESCALATION_THRESHOLD) return;
        }
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
                if (!mutation.caveHandled) {
                    CavePipeline.getInstance().onChunkUnavailable(
                            mutation.chunkX, mutation.chunkZ, mutation.reasons);
                    mutation.caveHandled = true;
                }
                processed += scanDependentBorders(level, mutation,
                        Math.max(0, columnBudget - processed), deadline);
                if (mutation.hasRemainingSurfaceWork()) {
                    requeueChunk(mutation);
                } else {
                    releaseActiveSurfaceChunk(mutation);
                }
                expanded++;
                if (processed >= columnBudget) break;
                continue;
            }

            if (!level.hasChunk(mutation.chunkX, mutation.chunkZ)) {
                // Light-only observations have no stable source after unload. A
                // later viewport scan or light packet will refresh the chunk when
                // it is actually visible again; retrying it only pins backlog.
                if ((mutation.reasons & LIGHT) != 0
                        && (mutation.reasons & (BLOCK_STATE | CHUNK_REPLACE)) == 0) {
                    releaseActiveSurfaceChunk(mutation);
                    expanded++;
                    continue;
                }
                // Chunk-replace work represents a live packet snapshot. Once the
                // client has already unloaded that chunk, retrying it consumes the
                // scarce writer slots while providing no usable source. A future
                // load packet will enqueue a fresh authoritative transaction.
                if ((mutation.reasons & CHUNK_REPLACE) != 0) {
                    releaseActiveSurfaceChunk(mutation);
                    expanded++;
                    continue;
                }
                if (++mutation.retries <= MAX_CHUNK_RETRIES) {
                    mutation.retryAfterTick = gameTick + RETRY_DELAY_TICKS;
                    requeueChunk(mutation);
                    // Do not let a temporarily unavailable transaction reserve the
                    // single sticky slot while newer loaded chunks can make progress.
                    releaseActiveSurfaceChunk(mutation);
                } else {
                    releaseActiveSurfaceChunk(mutation);
                }
                expanded++;
                continue;
            }

            if (!mutation.caveHandled) {
                CavePipeline.getInstance().onChunkMutation(
                        mutation.chunkX, mutation.chunkZ, mutation.reasons);
                mutation.caveHandled = true;
            }

            int available = Math.max(0, columnBudget - processed);
            while (mutation.hasDirtyColumns() && available > 0
                    && System.nanoTime() < deadline) {
                int column = mutation.takeNextDirtyColumn();
                if (column < 0) break;
                int blockX = (mutation.chunkX << 4) + (column & 15);
                int blockZ = (mutation.chunkZ << 4) + (column >>> 4);
                scanSurfaceMutationColumn(level, blockX, blockZ, mutation.reasons);
                processed++;
                available--;
                processedColumns++;
            }
            if (!mutation.hasRemainingSurfaceWork()) {
                releaseActiveSurfaceChunk(mutation);
                expanded++;
                continue;
            }

            available = Math.max(0, columnBudget - processed);
            boolean lightOnly = (mutation.reasons & LIGHT) != 0
                    && (mutation.reasons & (BLOCK_STATE | CHUNK_REPLACE)) == 0;
            // Chunk transactions are wall-clock bounded inside ChunkScanner. Charge
            // one compact admission token per eight scanned columns instead of
            // treating 64 columns as 64 queue operations; the old count gate let
            // only a few full chunks per second through while the deadline was idle.
            int centralBudget = mutation.rescanSurface && available > 0
                    ? CHUNK_COLUMN_BURST : 0;
            int end = Math.min(256, mutation.nextColumn + centralBudget);
            if (mutation.nextColumn < end && System.nanoTime() < deadline) {
                int previous = mutation.nextColumn;
                if (lightOnly) {
                    mutation.nextColumn = ChunkScanner.getInstance()
                            .scanSurfaceLightChunkSlice(level, mutation.chunkX,
                                    mutation.chunkZ, previous, end - previous);
                } else {
                    mutation.nextColumn = ChunkScanner.getInstance()
                            .scanSurfaceChunkSlice(level, mutation.chunkX,
                                    mutation.chunkZ, previous, end - previous,
                                    deadline);
                }
                int advanced = Math.max(0, mutation.nextColumn - previous);
                int admissionCharge = advanced <= 0 ? 0
                        : Math.max(1, (advanced + 7) >>> 3);
                processed += admissionCharge;
                processedColumns += advanced;
            }
            processed += scanDependentBorders(level, mutation,
                    Math.max(0, columnBudget - processed), deadline);
            if (mutation.hasRemainingSurfaceWork()) {
                requeueChunk(mutation);
            } else {
                releaseActiveSurfaceChunk(mutation);
            }
            expanded++;
            if (processed >= columnBudget) break;
        }
        return processed;
    }

    private synchronized void enqueueChunkWithDependentBorders(int chunkX,
            int chunkZ, int reasons, boolean rescanSurface, boolean urgent) {
        enqueueChunk(chunkX, chunkZ, reasons | NEIGHBOUR_DEPENDENCY,
                rescanSurface, rescanSurface, urgent);
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
        enqueueChunk(chunkX, chunkZ, reasons, rescanSurface, false, false);
    }

    private synchronized void enqueueChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface, boolean repairDependentBorders) {
        enqueueChunk(chunkX, chunkZ, reasons, rescanSurface,
                repairDependentBorders, false);
    }

    private synchronized void enqueueChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface, boolean repairDependentBorders,
            boolean urgent) {
        enqueueChunk(chunkX, chunkZ, reasons, rescanSurface,
                repairDependentBorders, urgent, false);
    }

    private synchronized void enqueueChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface, boolean repairDependentBorders,
            boolean urgent, boolean caveHandled) {
        receivedMutations++;
        long key = ChunkPos.asLong(chunkX, chunkZ);
        ChunkMutation existing = chunks.get(key);
        if (existing != null) {
            if ((reasons & CHUNK_UNLOAD) != 0) {
                existing.reasons = CHUNK_UNLOAD;
                existing.rescanSurface = false;
                existing.nextColumn = 0;
                existing.nextBorderColumn = 0;
                existing.caveHandled = caveHandled;
            } else {
                // A later load/update supersedes an earlier unload for the same key.
                existing.reasons = (existing.reasons & ~CHUNK_UNLOAD) | reasons;
                if (rescanSurface && (reasons & (CHUNK_REPLACE | BLOCK_STATE)) != 0) {
                    existing.nextColumn = 0;
                    existing.clearDirtyColumns();
                }
                existing.rescanSurface |= rescanSurface;
                existing.caveHandled &= caveHandled;
                if ((existing.reasons & CHUNK_UNLOAD) == 0) existing.retries = 0;
            }
            existing.repairDependentBorders |= repairDependentBorders;
            if ((reasons & CHUNK_REPLACE) != 0) {
                // The latest load packet is the newest authoritative frontier.
                // Move it ahead of stale travel history even when the key was
                // already classified urgent; duplicate primitive keys are harmless
                // because only the current map entry can be removed once.
                existing.urgent = true;
                urgentChunkOrder.enqueueFirst(key);
            } else if (urgent && !existing.urgent) {
                existing.urgent = true;
                urgentChunkOrder.enqueue(key);
            }
            coalescedMutations++;
            return;
        }
        boolean authoritativeFrontier = urgent && (reasons & CHUNK_REPLACE) != 0;
        // Compact one cold, untouched transaction before admitting each new
        // authoritative frontier chunk once the precise lane reaches its working
        // set watermark. This holds moving-world memory near the useful viewport
        // instead of allowing the queue to grow to the 2,048 hard cap.
        if (authoritativeFrontier
                && MovementMutationPolicy.shouldCompactForAuthoritativeFrontier(
                        chunks.size())) {
            makeRoomForAuthoritativeChunk();
        }
        if (chunks.size() >= MAX_PENDING_CHUNKS) {
            if (!authoritativeFrontier || !makeRoomForAuthoritativeChunk()) {
                escalatedChunks++;
                enqueueRegionChunk(chunkX, chunkZ, reasons, rescanSurface,
                        repairDependentBorders, caveHandled);
                return;
            }
        } else if (!urgent && chunks.size() >= CHUNK_REGION_ESCALATION_THRESHOLD) {
            escalatedChunks++;
            enqueueRegionChunk(chunkX, chunkZ, reasons, rescanSurface,
                    repairDependentBorders, caveHandled);
            return;
        }
        ChunkMutation created = new ChunkMutation(chunkX, chunkZ, reasons,
                rescanSurface, repairDependentBorders, urgent);
        created.caveHandled = caveHandled;
        chunks.put(key, created);
        if (urgent) {
            if ((reasons & CHUNK_REPLACE) != 0) urgentChunkOrder.enqueueFirst(key);
            else urgentChunkOrder.enqueue(key);
        }
        else chunkOrder.enqueue(key);
    }

    /**
     * A block packet changes at most a 3x3 column neighbourhood. Keep only the
     * centre column in the low-latency precise lane; fold the eight dependency
     * columns directly into per-chunk 256-bit masks. This preserves exact surface
     * repair while avoiding nine short-lived ColumnMutation objects per packet.
     * Cold edits are compacted completely because their display latency is already
     * hidden by cached exact/region coverage.
     */
    private void enqueueNeighbourhoodColumns(int centerX, int centerZ,
            int reasons, boolean urgent) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int blockX = centerX + dx;
                int blockZ = centerZ + dz;
                boolean preciseCentre = urgent && dx == 0 && dz == 0
                        && columns.size() < HOT_COLUMN_PRECISE_LIMIT;
                if (preciseCentre) {
                    enqueueColumn(blockX, blockZ, reasons, false, true);
                } else {
                    receivedMutations++;
                    escalatedColumns++;
                    enqueueCompactedColumn(blockX, blockZ, reasons, false, urgent);
                }
            }
        }
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
            if (urgent && !existing.urgent) {
                existing.urgent = true;
                columnOrder.enqueueFirst(key);
            }
            coalescedMutations++;
            return true;
        }
        if (columns.size() >= COLUMN_COMPACTION_THRESHOLD) {
            escalatedColumns++;
            enqueueCompactedColumn(blockX, blockZ, reasons, caveHandled, urgent);
            return false;
        }
        if (columns.size() >= MAX_PENDING_COLUMNS) {
            escalatedColumns++;
            enqueueChunk(blockX >> 4, blockZ >> 4, reasons, true);
            return false;
        }
        ColumnMutation created = new ColumnMutation(blockX, blockZ, reasons,
                caveHandled, urgent);
        columns.put(key, created);
        if (urgent) columnOrder.enqueueFirst(key);
        else columnOrder.enqueue(key);
        return true;
    }

    private synchronized void enqueueCompactedColumn(int blockX, int blockZ,
            int reasons, boolean caveHandled, boolean urgent) {
        int chunkX = blockX >> 4;
        int chunkZ = blockZ >> 4;
        long key = ChunkPos.asLong(chunkX, chunkZ);
        ChunkMutation existing = chunks.get(key);
        if (existing != null) {
            existing.reasons |= reasons;
            existing.caveHandled &= caveHandled;
            existing.markDirtyColumn(((blockZ & 15) << 4) | (blockX & 15));
            if (urgent && !existing.urgent) {
                existing.urgent = true;
                urgentChunkOrder.enqueue(key);
            }
            coalescedMutations++;
            return;
        }
        if ((!urgent && chunks.size() >= CHUNK_REGION_ESCALATION_THRESHOLD)
                || chunks.size() >= MAX_PENDING_CHUNKS) {
            escalatedChunks++;
            enqueueRegionChunk(chunkX, chunkZ, reasons, true, false,
                    caveHandled);
            return;
        }
        ChunkMutation created = new ChunkMutation(chunkX, chunkZ, reasons,
                false, false, urgent);
        created.caveHandled = caveHandled;
        created.markDirtyColumn(((blockZ & 15) << 4) | (blockX & 15));
        chunks.put(key, created);
        if (urgent) urgentChunkOrder.enqueue(key);
        else chunkOrder.enqueue(key);
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
            existing.urgent |= mutation.urgent;
            return;
        }
        // The column was temporarily removed for processing, therefore the
        // queue may have filled while its chunk was unavailable. Escalate
        // instead of silently exceeding the fine-grained cap or losing it.
        if (columns.size() >= MAX_PENDING_COLUMNS) {
            escalatedColumns++;
            enqueueChunk(mutation.blockX >> 4, mutation.blockZ >> 4,
                    mutation.reasons, true, false, mutation.urgent,
                    mutation.caveHandled);
            return;
        }
        columns.put(key, mutation);
        if (urgent || mutation.urgent) columnOrder.enqueueFirst(key);
        else columnOrder.enqueue(key);
    }

    private synchronized ChunkMutation pollChunk(long gameTick) {
        if (activeSurfaceChunkKey != NO_ACTIVE_CHUNK) {
            long key = activeSurfaceChunkKey;
            ChunkMutation active = chunks.remove(key);
            if (active == null) {
                activeSurfaceChunkKey = NO_ACTIVE_CHUNK;
            } else if (active.retryAfterTick <= gameTick) {
                // Atomic publication still resumes a hot transaction first. Once
                // movement leaves it behind, however, a newly delivered urgent
                // chunk must be allowed to start rather than waiting behind the
                // old 16x16 transaction for several frames.
                if (MovementMutationPolicy.shouldYieldActiveToUrgent(
                        isHotChunk(active.chunkX, active.chunkZ),
                        !urgentChunkOrder.isEmpty())) {
                    active.urgent = false;
                    chunks.put(key, active);
                    chunkOrder.enqueue(key);
                    activeSurfaceChunkKey = NO_ACTIVE_CHUNK;
                } else {
                    return active;
                }
            } else {
                chunks.put(key, active);
            }
        }
        ChunkMutation urgent = pollChunkQueue(urgentChunkOrder, gameTick, true);
        if (urgent != null) return urgent;
        return pollChunkQueue(chunkOrder, gameTick, false);
    }

    private ChunkMutation pollChunkQueue(LongArrayFIFOQueue queue,
            long gameTick, boolean urgentQueue) {
        int checks = queue.size();
        while (checks-- > 0 && !queue.isEmpty()) {
            long key = queue.dequeueLong();
            ChunkMutation mutation = chunks.remove(key);
            if (mutation == null) continue;
            if (urgentQueue && !mutation.urgent) {
                chunks.put(key, mutation);
                continue;
            }
            if (urgentQueue && !isHotChunk(mutation.chunkX, mutation.chunkZ)
                    && (mutation.reasons & CHUNK_REPLACE) == 0) {
                mutation.urgent = false;
                chunks.put(key, mutation);
                chunkOrder.enqueue(key);
                continue;
            }
            if (mutation.retryAfterTick > gameTick) {
                chunks.put(key, mutation);
                queue.enqueue(key);
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
            existing.mergeDirtyColumns(mutation);
            if (mutation.urgent && !existing.urgent) {
                existing.urgent = true;
                if ((existing.reasons & CHUNK_REPLACE) != 0) {
                    urgentChunkOrder.enqueueFirst(key);
                } else {
                    urgentChunkOrder.enqueue(key);
                }
            }
            if (existing.hasInProgressSurfaceTransaction()) {
                activeSurfaceChunkKey = key;
            }
            return;
        }
        if (mutation.hasInProgressSurfaceTransaction()) {
            chunks.put(key, mutation);
            activeSurfaceChunkKey = key;
            return;
        }
        // A chunk can be polled, discover that its client data is unavailable,
        // and find the queue full by the time it is requeued. Preserve its dirty
        // meaning at the coarser durable level rather than breaking the cap.
        if ((!mutation.urgent && chunks.size() >= CHUNK_REGION_ESCALATION_THRESHOLD)
                || chunks.size() >= MAX_PENDING_CHUNKS) {
            escalatedChunks++;
            enqueueRegionChunk(mutation.chunkX, mutation.chunkZ,
                    mutation.reasons, mutation.rescanSurface,
                    mutation.repairDependentBorders, mutation.caveHandled);
            return;
        }
        chunks.put(key, mutation);
        if (mutation.urgent) {
            if ((mutation.reasons & CHUNK_REPLACE) != 0) {
                urgentChunkOrder.enqueueFirst(key);
            } else {
                urgentChunkOrder.enqueue(key);
            }
        }
        else chunkOrder.enqueue(key);
    }

    /**
     * Keeps newly delivered Minecraft chunks actionable when the Surface queue is
     * saturated. One cold, not-yet-started transaction is moved to the durable
     * region bitset; no dirty meaning is discarded. This is intentionally bounded
     * so packet admission remains O(1)-ish even during fast elytra travel.
     */
    private boolean makeRoomForAuthoritativeChunk() {
        ChunkMutation candidate = null;
        int examined = 0;
        for (ChunkMutation queued : chunks.values()) {
            if (++examined > 512) break;
            long key = ChunkPos.asLong(queued.chunkX, queued.chunkZ);
            if (key == activeSurfaceChunkKey
                    || queued.hasInProgressSurfaceTransaction()
                    || isHotChunk(queued.chunkX, queued.chunkZ)) continue;
            candidate = queued;
            if (!queued.urgent) break;
        }
        if (candidate == null) return false;

        long candidateKey = ChunkPos.asLong(candidate.chunkX, candidate.chunkZ);
        if (chunks.remove(candidateKey) == null) return false;
        if ((candidate.reasons & CHUNK_UNLOAD) == 0
                && candidate.hasRemainingSurfaceWork()) {
            enqueueRegionChunk(candidate.chunkX, candidate.chunkZ,
                    candidate.reasons, candidate.rescanSurface,
                    candidate.repairDependentBorders, candidate.caveHandled);
        }
        escalatedChunks++;
        return true;
    }

    private synchronized boolean enqueueChunkFromRegion(RegionMutation region) {
        if (chunks.size() >= CHUNK_REGION_ESCALATION_THRESHOLD) return false;
        int offset = region.takeNextDirtyChunk();
        if (offset < 0) return true;
        int chunkX = (region.regionX << 5) + (offset & 31);
        int chunkZ = (region.regionZ << 5) + (offset >>> 5);
        enqueueChunk(chunkX, chunkZ, region.reasons, region.rescanSurface,
                region.repairDependentBorders, false, region.caveHandled);
        return true;
    }

    private synchronized void enqueueRegionChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface, boolean repairDependentBorders) {
        enqueueRegionChunk(chunkX, chunkZ, reasons, rescanSurface,
                repairDependentBorders, false);
    }

    private synchronized void enqueueRegionChunk(int chunkX, int chunkZ, int reasons,
            boolean rescanSurface, boolean repairDependentBorders,
            boolean caveHandled) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long key = ChunkPos.asLong(regionX, regionZ);
        RegionMutation existing = regions.get(key);
        if (existing != null) {
            existing.reasons |= reasons;
            existing.rescanSurface |= rescanSurface;
            existing.repairDependentBorders |= repairDependentBorders;
            existing.caveHandled &= caveHandled;
            existing.markDirty(chunkX, chunkZ);
            coalescedMutations++;
            return;
        }
        RegionMutation created = new RegionMutation(regionX, regionZ, reasons,
                rescanSurface, repairDependentBorders, caveHandled);
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
            existing.caveHandled &= mutation.caveHandled;
            existing.mergeDirty(mutation);
            return;
        }
        regions.put(key, mutation);
        regionOrder.enqueue(key);
    }

    private synchronized void releaseActiveSurfaceChunk(ChunkMutation mutation) {
        if (mutation == null) return;
        long key = ChunkPos.asLong(mutation.chunkX, mutation.chunkZ);
        if (activeSurfaceChunkKey == key) activeSurfaceChunkKey = NO_ACTIVE_CHUNK;
    }

    private void clearQueuesLocked() {
        columns.clear();
        columnOrder.clear();
        chunks.clear();
        chunkOrder.clear();
        urgentChunkOrder.clear();
        caveFanoutReasons.clear();
        caveFanoutOrder.clear();
        activeSurfaceChunkKey = NO_ACTIVE_CHUNK;
        regions.clear();
        regionOrder.clear();
    }

    private static long packBlock(int blockX, int blockZ) {
        return ((long) blockX << 32) ^ (blockZ & 0xFFFFFFFFL);
    }

    private static boolean isHotChunk(int chunkX, int chunkZ) {
        return isTravelChunk(chunkX, chunkZ, HOT_RADIUS_CHUNKS);
    }

    private static boolean isTravelChunk(int chunkX, int chunkZ, int radius) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) return false;
        int playerChunkX = minecraft.player.getBlockX() >> 4;
        int playerChunkZ = minecraft.player.getBlockZ() >> 4;
        return Math.abs(chunkX - playerChunkX) <= radius
                && Math.abs(chunkZ - playerChunkZ) <= radius;
    }

    private static final class ColumnMutation {
        private final int blockX;
        private final int blockZ;
        private int reasons;
        private boolean caveHandled;
        private int retries;
        private boolean urgent;

        private ColumnMutation(int blockX, int blockZ, int reasons,
                boolean caveHandled, boolean urgent) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.reasons = reasons;
            this.caveHandled = caveHandled;
            this.urgent = urgent;
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
        private boolean urgent;
        private long[] dirtyColumns;
        private int dirtyColumnCount;
        private int dirtyColumnCursor;

        private ChunkMutation(int chunkX, int chunkZ, int reasons,
                boolean rescanSurface, boolean repairDependentBorders,
                boolean urgent) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.reasons = reasons;
            this.rescanSurface = rescanSurface;
            this.repairDependentBorders = repairDependentBorders;
            this.urgent = urgent;
        }

        private boolean hasInProgressSurfaceTransaction() {
            return rescanSurface && nextColumn > 0 && nextColumn < 256
                    && (reasons & CHUNK_UNLOAD) == 0;
        }

        private boolean hasRemainingSurfaceWork() {
            return hasDirtyColumns()
                    || (rescanSurface && nextColumn < 256)
                    || (repairDependentBorders && nextBorderColumn < 68);
        }

        private void markDirtyColumn(int localColumn) {
            if (localColumn < 0 || localColumn >= 256) return;
            if (rescanSurface && localColumn >= nextColumn) return;
            if (dirtyColumns == null) dirtyColumns = new long[4];
            int word = localColumn >>> 6;
            long bit = 1L << (localColumn & 63);
            if ((dirtyColumns[word] & bit) != 0L) return;
            dirtyColumns[word] |= bit;
            if (dirtyColumnCount == 0) dirtyColumnCursor = localColumn;
            else dirtyColumnCursor = Math.min(dirtyColumnCursor, localColumn);
            dirtyColumnCount++;
        }

        private boolean hasDirtyColumns() {
            return dirtyColumnCount > 0;
        }

        private int takeNextDirtyColumn() {
            if (dirtyColumnCount <= 0 || dirtyColumns == null) return -1;
            for (int checked = 0; checked < 256; checked++) {
                int column = (dirtyColumnCursor + checked) & 255;
                int word = column >>> 6;
                long bit = 1L << (column & 63);
                if ((dirtyColumns[word] & bit) == 0L) continue;
                dirtyColumns[word] &= ~bit;
                dirtyColumnCount--;
                dirtyColumnCursor = (column + 1) & 255;
                return column;
            }
            dirtyColumnCount = 0;
            return -1;
        }

        private void mergeDirtyColumns(ChunkMutation other) {
            if (other.dirtyColumnCount <= 0 || other.dirtyColumns == null) return;
            if (dirtyColumns == null) dirtyColumns = new long[4];
            for (int word = 0; word < dirtyColumns.length; word++) {
                long added = other.dirtyColumns[word] & ~dirtyColumns[word];
                if (added == 0L) continue;
                dirtyColumns[word] |= added;
                dirtyColumnCount += Long.bitCount(added);
            }
        }

        private void clearDirtyColumns() {
            if (dirtyColumns != null) {
                for (int word = 0; word < dirtyColumns.length; word++) {
                    dirtyColumns[word] = 0L;
                }
            }
            dirtyColumnCount = 0;
            dirtyColumnCursor = 0;
        }
    }

    private static final class RegionMutation {
        private final int regionX;
        private final int regionZ;
        private int reasons;
        private boolean rescanSurface;
        private boolean repairDependentBorders;
        private boolean caveHandled;
        private final long[] dirtyChunks = new long[16];
        private int dirtyCount;
        private int cursor;

        private RegionMutation(int regionX, int regionZ, int reasons,
                boolean rescanSurface, boolean repairDependentBorders,
                boolean caveHandled) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.reasons = reasons;
            this.rescanSurface = rescanSurface;
            this.repairDependentBorders = repairDependentBorders;
            this.caveHandled = caveHandled;
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
