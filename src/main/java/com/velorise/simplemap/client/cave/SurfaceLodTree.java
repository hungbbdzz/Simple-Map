package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapGpuBudgetController;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapResidencyManager;
import com.velorise.simplemap.client.MapWorkScheduler;

import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Page-rooted surface hierarchy.
 *
 * <p>The exact leaf is a 64x64 block texture at one block per texel. Level 1
 * groups 2x2 leaf pages and covers 128x128 blocks. Every higher level groups
 * four direct children while preserving a fixed 64x64 GPU texture. Coverage is
 * tracked independently from pixel alpha, so an unknown source texel can never
 * become an authoritative transparent hole.</p>
 */
public final class SurfaceLodTree {
    private static final int VISIBLE_QUEUE_SCAN_BUDGET = 512;
    public static final int MAX_LEVEL = 7;
    private static final long FULL_ROW = -1L;
    private static final long GPU_RETRY_MAX_NANOS = 160_000_000L;
    /** Clean leaf authority is LRU-bounded; dirty state is never discarded. */
    private static final int MAX_RETAINED_LEAF_STATES = 16_384;

    private final SurfaceBranchAtlas[] atlases = new SurfaceBranchAtlas[MAX_LEVEL + 1];
    private final long[] observedStorageGeneration = new long[MAX_LEVEL + 1];
    private final Map<NodeKey, Node> nodes = new LinkedHashMap<>(128, 0.75f, true);
    @SuppressWarnings("unchecked")
    private final ArrayDeque<NodeKey>[] dirtyQueues = new ArrayDeque[MAX_LEVEL + 1];
    private final Set<NodeKey> dirtySet = new HashSet<>();
    private final Set<NodeKey> loadingFromDisk = new HashSet<>();
    private final ConcurrentLinkedQueue<LoadedNode> loadedFromDisk =
            new ConcurrentLinkedQueue<>();
    /**
     * Version-backed leaf authority. The queue contains only keys; replacing a
     * page coalesces to the newest immutable source instead of dropping an event.
     */
    private final Object leafLock = new Object();
    private final LinkedHashMap<PageUpdateKey, LeafState> leafStates =
            new LinkedHashMap<>(256, 0.75f, true);
    private final ArrayDeque<PageUpdateKey> pendingLeafQueue = new ArrayDeque<>();
    private final Set<PageUpdateKey> pendingLeafSet = new HashSet<>();
    private final Set<PageUpdateKey> inFlightLeafDerivations = new HashSet<>();
    private final ConcurrentLinkedQueue<PreparedLeaf> preparedLeaves =
            new ConcurrentLinkedQueue<>();
    private final MapPipelineTelemetry pipelineTelemetry = MapPipelineTelemetry.getInstance();
    private final LodBranchDiskCache diskCache = LodBranchDiskCache.getInstance();
    private long gpuAdmissionRetryAfterNanos;
    private int gpuAdmissionFailures;
    private long loadGeneration = 1L;
    /** Latest fullscreen Surface demand used only to prioritize branch work. */
    private volatile VisibleWindow visibleWindow = VisibleWindow.none();

    public static void invalidatePersistentCache() {
        LodBranchDiskCache.getInstance().invalidateCurrentDimension();
    }

    public SurfaceLodTree() {
        java.util.Arrays.fill(observedStorageGeneration, Long.MIN_VALUE);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            atlases[level] = new SurfaceBranchAtlas(level);
            dirtyQueues[level] = new ArrayDeque<>();
        }
    }

    /**
     * Updates the visible Surface page window. This does not create work and does
     * not discard background dirty state; it only changes which already-dirty LOD
     * node/leaf is selected first during the next bounded publication slices.
     */
    public void setVisibleWindow(String dimension, int preferredLevel,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int focusPageX, int focusPageZ) {
        int safeMinX = Math.min(minPageX, maxPageX);
        int safeMaxX = Math.max(minPageX, maxPageX);
        int safeMinZ = Math.min(minPageZ, maxPageZ);
        int safeMaxZ = Math.max(minPageZ, maxPageZ);
        visibleWindow = new VisibleWindow(
                dimension == null ? "" : dimension,
                Math.max(1, Math.min(MAX_LEVEL, preferredLevel)),
                safeMinX, safeMaxX, safeMinZ, safeMaxZ,
                focusPageX, focusPageZ);
    }

    public void synchronizeStorage() {
        drainDiskLoads();
        boolean reallocated = false;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            if (countLevel(level) == 0) continue;
            SurfaceBranchAtlas atlas = atlases[level];
            atlas.ensureInitialized();
            long generation = atlas.storageGeneration();
            if (observedStorageGeneration[level] != Long.MIN_VALUE
                    && observedStorageGeneration[level] != generation) reallocated = true;
            observedStorageGeneration[level] = generation;
        }
        if (!reallocated) return;
        MapResidencyManager.getInstance().markTopologyChanged();
        for (Node node : nodes.values()) {
            node.initialized = false;
            node.markFullDirty();
            enqueue(node.key);
        }
    }

    public CaveAtlasRegion peek(String dimension, int level, int nodeX, int nodeZ) {
        drainDiskLoads();
        if (level < 1 || level > MAX_LEVEL) return null;
        NodeKey key = new NodeKey(dimension, level, nodeX, nodeZ);
        Node node = nodes.get(key);
        if (node == null) {
            requestDiskLoad(key);
            return null;
        }
        if (node.initialized && node.atlasSlot >= 0
                && node.uploadedKnownMask != 0L) {
            MapResidencyManager.getInstance().touch(residencyKey(key));
            return atlases[level].region(node.atlasSlot,
                    node.uploadedKnownMask, node.uploadedCompleteMask);
        }
        if (node.knownMask == 0L) return null;
        node.markFullDirty();
        enqueue(node.key);
        return null;
    }

    public boolean hasData(String dimension, int level, int nodeX, int nodeZ) {
        drainDiskLoads();
        if (level < 1 || level > MAX_LEVEL) return false;
        NodeKey key = new NodeKey(dimension, level, nodeX, nodeZ);
        Node node = nodes.get(key);
        if (node == null) requestDiskLoad(key);
        if (node != null && node.knownMask != 0L) return true;
        LodBranchDiskCache.Metadata metadata = diskCache.metadata(diskKey(key));
        return metadata != null && metadata.knownMask() != 0L;
    }

    /**
     * Updates one exact 64x64 leaf page. {@code knownRows} contains one bit per
     * authoritative source column and is deliberately independent from ARGB alpha.
     *
     * <p>The pixel array is treated as immutable after this call. Exact texture
     * pages already retain the same array as their CPU restore snapshot, so this
     * avoids another 16 KiB copy for every branch update.</p>
     */
    public void updatePage(String dimension, int globalPageX, int globalPageZ,
            int[] pixels64, long[] knownRows, boolean complete) {
        updatePage(dimension, globalPageX, globalPageZ, pixels64, knownRows,
                complete, System.nanoTime(), MapRequestLane.BACKGROUND);
    }

    public void updatePage(String dimension, int globalPageX, int globalPageZ,
            int[] pixels64, long[] knownRows, boolean complete,
            long sourceRevision, MapRequestLane lane) {
        if (pixels64 == null || pixels64.length < 64 * 64
                || knownRows == null || knownRows.length < 64) return;
        PageUpdateKey key = new PageUpdateKey(dimension, globalPageX, globalPageZ);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        long revision = Math.max(1L, sourceRevision);
        synchronized (leafLock) {
            LeafState state = leafStates.computeIfAbsent(key, LeafState::new);
            if (revision < state.sourceRevision) return;
            state.pixels64 = pixels64;
            state.knownRows = java.util.Arrays.copyOf(knownRows, 64);
            state.complete = complete;
            state.sourceRevision = revision;
            state.queuedNanos = state.queuedNanos == 0L
                    ? System.nanoTime() : state.queuedNanos;
            if (effectiveLane.strongerThan(state.lane)) state.lane = effectiveLane;
            enqueueLeafLocked(key);
            trimLeafStatesLocked();
        }
        pipelineTelemetry.recordBranchUpdateQueued();
    }

    /** Returns true only when a GPU-resident level-1 branch covers this revision. */
    public boolean coversPage(String dimension, int globalPageX, int globalPageZ,
            long sourceRevision) {
        drainDiskLoads();
        int nodeX = Math.floorDiv(globalPageX, 2);
        int nodeZ = Math.floorDiv(globalPageZ, 2);
        Node node = nodes.get(new NodeKey(dimension, 1, nodeX, nodeZ));
        if (node == null || !node.initialized || node.atlasSlot < 0) return false;
        int childIndex = Math.floorMod(globalPageZ, 2) * 2
                + Math.floorMod(globalPageX, 2);
        boolean covered = (node.uploadedKnownMask & (1L << childIndex)) != 0L
                && node.uploadedChildRevisions[childIndex] >= sourceRevision;
        if (covered) MapResidencyManager.getInstance().touch(residencyKey(node.key));
        return covered;
    }

    private int scheduleLeafDerivations(int budget, long deadlineNanos) {
        int scheduled = 0;
        while (scheduled < budget && System.nanoTime() < deadlineNanos) {
            LeafInput input;
            synchronized (leafLock) {
                PageUpdateKey key = pollVisibleLeafLocked();
                if (key == null) break;
                pendingLeafSet.remove(key);
                LeafState state = leafStates.get(key);
                if (state == null || state.pixels64 == null || state.knownRows == null
                        || state.derivedRevision >= state.sourceRevision) continue;
                if (!inFlightLeafDerivations.add(key)) continue;
                input = new LeafInput(key, state.pixels64, state.knownRows,
                        state.complete, state.sourceRevision,
                        state.lane == null ? MapRequestLane.BACKGROUND : state.lane,
                        state.queuedNanos, loadGeneration);
            }
            CompletableFuture<PreparedLeaf> future = MapWorkScheduler.tryCpuFuture(
                    input.lane(), MapWorkScheduler.WorkType.BRANCH_DERIVE,
                    input.lane().priorityBase(), 4,
                    () -> input.generation() == loadGeneration,
                    () -> prepareLeaf(input));
            if (future == null) {
                synchronized (leafLock) {
                    inFlightLeafDerivations.remove(input.key());
                    enqueueLeafLocked(input.key());
                }
                break;
            }
            future.whenComplete((prepared, throwable) -> {
                synchronized (leafLock) {
                    inFlightLeafDerivations.remove(input.key());
                    if (prepared == null || throwable != null
                            || input.generation() != loadGeneration) {
                        LeafState state = leafStates.get(input.key());
                        if (state != null && state.derivedRevision < state.sourceRevision) {
                            enqueueLeafLocked(input.key());
                        }
                        return;
                    }
                }
                preparedLeaves.add(prepared);
            });
            scheduled++;
        }
        return scheduled;
    }

    private int drainPreparedLeaves(int budget, long deadlineNanos) {
        int applied = 0;
        while (applied < budget && System.nanoTime() < deadlineNanos) {
            PreparedLeaf prepared = preparedLeaves.poll();
            if (prepared == null) break;
            LeafState state;
            synchronized (leafLock) {
                state = leafStates.get(prepared.key());
                if (state == null) continue;
                if (prepared.revision() < state.sourceRevision) {
                    enqueueLeafLocked(prepared.key());
                    continue;
                }
            }
            long started = System.nanoTime();
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_QUEUE,
                    Math.max(0L, started - prepared.queuedNanos()));
            applyPreparedLeaf(prepared);
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_DERIVE,
                    System.nanoTime() - started);
            synchronized (leafLock) {
                state = leafStates.get(prepared.key());
                if (state != null) {
                    state.derivedRevision = Math.max(state.derivedRevision,
                            prepared.revision());
                    state.queuedNanos = 0L;
                    if (state.derivedRevision < state.sourceRevision) {
                        state.queuedNanos = System.nanoTime();
                        enqueueLeafLocked(prepared.key());
                    }
                    trimLeafStatesLocked();
                }
            }
            applied++;
        }
        return applied;
    }

    private static PreparedLeaf prepareLeaf(LeafInput input) {
        int[] reducedPixels = new int[32 * 32];
        long[] reducedKnownRows = new long[32];
        long[] reducedCompleteRows = new long[32];
        for (int y = 0; y < 32; y++) {
            int sourceY = y << 1;
            long knownRow = 0L;
            long completeRow = 0L;
            for (int x = 0; x < 32; x++) {
                int sourceX = x << 1;
                int known = knownCount(input.knownRows(), sourceX, sourceY);
                if (known == 0) continue;
                reducedPixels[y * 32 + x] = reduceSurface(
                        input.pixels64(), input.knownRows(), sourceX, sourceY);
                knownRow |= 1L << x;
                if (known == 4) completeRow |= 1L << x;
            }
            reducedKnownRows[y] = knownRow;
            reducedCompleteRows[y] = completeRow;
        }
        return new PreparedLeaf(input.key(), reducedPixels, reducedKnownRows,
                reducedCompleteRows, input.revision(), input.queuedNanos());
    }

    private void applyPreparedLeaf(PreparedLeaf prepared) {
        drainDiskLoads();
        String dimension = prepared.key().dimension();
        int globalPageX = prepared.key().globalPageX();
        int globalPageZ = prepared.key().globalPageZ();
        int nodeX = Math.floorDiv(globalPageX, 2);
        int nodeZ = Math.floorDiv(globalPageZ, 2);
        Node node = node(dimension, 1, nodeX, nodeZ);
        int localPageX = Math.floorMod(globalPageX, 2);
        int localPageZ = Math.floorMod(globalPageZ, 2);
        int childIndex = localPageZ * 2 + localPageX;
        int destinationBaseX = localPageX * 32;
        int destinationBaseY = localPageZ * 32;
        int changedMinX = 64;
        int changedMinY = 64;
        int changedMaxX = -1;
        int changedMaxY = -1;
        long previousKnownMask = node.knownMask;
        long previousCompleteMask = node.completeMask;

        for (int y = 0; y < 32; y++) {
            int targetY = destinationBaseY + y;
            int destinationRow = targetY * SurfaceBranchAtlas.SIZE;
            long sourceKnown = prepared.knownRows32()[y];
            long sourceComplete = prepared.completeRows32()[y];
            for (int x = 0; x < 32; x++) {
                int targetX = destinationBaseX + x;
                int targetIndex = destinationRow + targetX;
                boolean nowKnown = (sourceKnown & (1L << x)) != 0L;
                boolean nowComplete = (sourceComplete & (1L << x)) != 0L;
                boolean wasKnown = (node.knownRows[targetY] & (1L << targetX)) != 0L;
                boolean wasComplete = (node.completeRows[targetY] & (1L << targetX)) != 0L;
                int nextPixel = nowKnown ? prepared.pixels32()[y * 32 + x] : 0;
                if (wasKnown == nowKnown && wasComplete == nowComplete
                        && node.pixels[targetIndex] == nextPixel) continue;
                node.pixels[targetIndex] = nextPixel;
                setBit(node.knownRows, targetX, targetY, nowKnown);
                setBit(node.completeRows, targetX, targetY, nowComplete);
                changedMinX = Math.min(changedMinX, targetX);
                changedMinY = Math.min(changedMinY, targetY);
                changedMaxX = Math.max(changedMaxX, targetX);
                changedMaxY = Math.max(changedMaxY, targetY);
            }
        }

        node.childRevisions[childIndex] = Math.max(
                node.childRevisions[childIndex], prepared.revision());
        refreshQuadrantCoverage(node, localPageX, localPageZ);
        boolean coverageChanged = previousKnownMask != node.knownMask
                || previousCompleteMask != node.completeMask;
        if (changedMaxX < changedMinX && !coverageChanged) return;
        if (changedMaxX < changedMinX) {
            changedMinX = destinationBaseX;
            changedMinY = destinationBaseY;
            changedMaxX = destinationBaseX + 31;
            changedMaxY = destinationBaseY + 31;
        }
        node.revision++;
        node.markDirty(changedMinX, changedMinY, changedMaxX, changedMaxY);
        enqueue(node.key);
        propagate(node);
        trimLevel(1);
    }

    private PageUpdateKey pollVisibleLeafLocked() {
        if (pendingLeafQueue.isEmpty()) return null;
        VisibleWindow window = visibleWindow;
        PageUpdateKey best = null;
        long bestDistance = Long.MAX_VALUE;
        int scanCount = Math.min(pendingLeafQueue.size(),
                VISIBLE_QUEUE_SCAN_BUDGET);
        for (int scanned = 0; scanned < scanCount; scanned++) {
            PageUpdateKey candidate = pendingLeafQueue.pollFirst();
            if (candidate == null) break;
            boolean visible = window.matches(candidate.dimension())
                    && candidate.globalPageX() >= window.minPageX()
                    && candidate.globalPageX() <= window.maxPageX()
                    && candidate.globalPageZ() >= window.minPageZ()
                    && candidate.globalPageZ() <= window.maxPageZ();
            if (!visible) {
                pendingLeafQueue.addLast(candidate);
                continue;
            }
            long dx = (long) candidate.globalPageX() - window.focusPageX();
            long dz = (long) candidate.globalPageZ() - window.focusPageZ();
            long distance = dx * dx + dz * dz;
            if (best == null || distance < bestDistance) {
                if (best != null) pendingLeafQueue.addLast(best);
                best = candidate;
                bestDistance = distance;
            } else {
                pendingLeafQueue.addLast(candidate);
            }
        }
        if (best != null) return best;
        return pendingLeafQueue.pollFirst();
    }

    private boolean isVisibleNode(NodeKey candidate, VisibleWindow window,
            int minNodeX, int maxNodeX, int minNodeZ, int maxNodeZ) {
        return window.matches(candidate.dimension())
                && candidate.nodeX() >= minNodeX && candidate.nodeX() <= maxNodeX
                && candidate.nodeZ() >= minNodeZ && candidate.nodeZ() <= maxNodeZ;
    }

    private void enqueueLeafLocked(PageUpdateKey key) {
        if (key != null && pendingLeafSet.add(key)) pendingLeafQueue.addLast(key);
    }

    private void trimLeafStatesLocked() {
        if (leafStates.size() <= MAX_RETAINED_LEAF_STATES) return;
        Iterator<Map.Entry<PageUpdateKey, LeafState>> iterator =
                leafStates.entrySet().iterator();
        while (leafStates.size() > MAX_RETAINED_LEAF_STATES && iterator.hasNext()) {
            Map.Entry<PageUpdateKey, LeafState> entry = iterator.next();
            LeafState state = entry.getValue();
            if (inFlightLeafDerivations.contains(entry.getKey())
                    || pendingLeafSet.contains(entry.getKey())
                    || state.derivedRevision < state.sourceRevision) continue;
            iterator.remove();
        }
        // If every retained leaf is dirty/in-flight, allow temporary growth. Losing
        // semantic map state is never an acceptable memory-pressure response.
    }

    public int publish(int budget, long deadlineNanos) {
        return publish(budget, deadlineNanos, 1);
    }

    public int publish(int budget, long deadlineNanos, int preferredLevel) {
        drainDiskLoads();
        int safeBudget = Math.max(1, budget);
        int safePreferredLevel = Math.max(1, Math.min(MAX_LEVEL, preferredLevel));
        int deriveBudget = Math.max(2, safeBudget * 3);

        /*
         * Commit already-prepared leaves and upload already-dirty L1/L2 nodes before
         * spending this short frame slice on more CPU derivation. Previously a busy
         * derivation queue could consume the entire deadline on every frame, leaving
         * branch publication permanently behind exact work even when GPU admission
         * was available.
         */
        drainPreparedLeaves(deriveBudget, deadlineNanos);
        int published = publishDirtyNodes(safeBudget, deadlineNanos,
                safePreferredLevel);
        if (published >= safeBudget || System.nanoTime() >= deadlineNanos) {
            return published;
        }

        scheduleLeafDerivations(deriveBudget, deadlineNanos);
        // Very small L1 reductions can complete while the same publication slice is
        // still open. Commit them and use any remaining upload slots immediately.
        drainPreparedLeaves(deriveBudget, deadlineNanos);
        published += publishDirtyNodes(safeBudget - published, deadlineNanos,
                safePreferredLevel);
        return published;
    }

    private static long branchGpuRetryDelayNanos(int failures) {
        long base = MapPerformanceGovernor.getInstance().underPressure()
                ? 48_000_000L : 24_000_000L;
        int shift = Math.max(0, Math.min(3, failures - 1));
        return Math.min(GPU_RETRY_MAX_NANOS, base << shift);
    }

    private int publishDirtyNodes(int budget, long deadlineNanos,
            int preferredLevel) {
        int published = 0;
        int considered = 0;
        int scanLimit = Math.max(8, budget * 4);
        while (published < budget && considered++ < scanLimit
                && System.nanoTime() < deadlineNanos) {
            NodeKey key = pollDirty(preferredLevel);
            if (key == null) break;
            dirtySet.remove(key);
            Node node = nodes.get(key);
            if (node == null || !node.isDirty() || node.knownMask == 0L) continue;
            long now = System.nanoTime();
            if (gpuAdmissionRetryAfterNanos > now) {
                enqueue(key);
                break;
            }
            if (node.nextUploadAttemptNanos > now) {
                enqueue(key);
                continue;
            }
            if (!ensureSlot(node)) {
                enqueue(key);
                continue;
            }
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.BRANCH,
                    MapRequestLane.FULLSCREEN, false)) {
                node.uploadReservationFailures++;
                node.nextUploadAttemptNanos = now + branchGpuRetryDelayNanos(
                        node.uploadReservationFailures);
                gpuAdmissionFailures = Math.min(8, gpuAdmissionFailures + 1);
                gpuAdmissionRetryAfterNanos = now + branchGpuRetryDelayNanos(
                        gpuAdmissionFailures);
                enqueue(key);
                break;
            }
            node.uploadReservationFailures = 0;
            node.nextUploadAttemptNanos = 0L;
            gpuAdmissionFailures = 0;
            gpuAdmissionRetryAfterNanos = 0L;
            boolean wasInitialized = node.initialized;
            long oldUploadedKnownMask = node.uploadedKnownMask;
            long oldUploadedCompleteMask = node.uploadedCompleteMask;
            long uploadStart = System.nanoTime();
            atlases[node.key.level()].upload(node.atlasSlot, node.pixels, node.dirtyRect());
            long uploadNanos = System.nanoTime() - uploadStart;
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_UPLOAD,
                    uploadNanos);
            MapGpuBudgetController.getInstance().record(
                    MapGpuBudgetController.UploadKind.BRANCH, uploadNanos);
            node.clearDirty();
            node.initialized = true;
            String residentKey = residencyKey(node.key);
            MapResidencyManager.getInstance().register(
                    residentKey, MapResidencyManager.Kind.SURFACE_BRANCH,
                    66L * 66L * Integer.BYTES,
                    () -> evictResidentForBudget(node.key));
            MapResidencyManager.getInstance().enforceBudget(
                    residentKey, MapRequestLane.FULLSCREEN);
            node.uploadedRevision = node.revision;
            node.uploadedKnownMask = node.knownMask;
            node.uploadedCompleteMask = node.completeMask;
            System.arraycopy(node.childRevisions, 0,
                    node.uploadedChildRevisions, 0, node.childRevisions.length);
            if (wasInitialized && (oldUploadedKnownMask != node.uploadedKnownMask
                    || oldUploadedCompleteMask != node.uploadedCompleteMask)) {
                MapResidencyManager.getInstance().markCoverageChanged(
                        MapResidencyManager.Kind.SURFACE_BRANCH);
            }
            saveNode(node);
            published++;
        }
        return published;
    }

    public void clear() {
        for (Node node : nodes.values()) node.close();
        nodes.clear();
        for (int level = 1; level <= MAX_LEVEL; level++) dirtyQueues[level].clear();
        dirtySet.clear();
        loadGeneration++;
        loadingFromDisk.clear();
        loadedFromDisk.clear();
        synchronized (leafLock) {
            leafStates.clear();
            pendingLeafQueue.clear();
            pendingLeafSet.clear();
            inFlightLeafDerivations.clear();
        }
        preparedLeaves.clear();
        visibleWindow = VisibleWindow.none();
        for (int level = 1; level <= MAX_LEVEL; level++) atlases[level].resetSlots();
    }

    private void propagate(Node child) {
        if (child.key.level() >= MAX_LEVEL || !child.isDirty()) return;
        int parentLevel = child.key.level() + 1;
        int parentX = Math.floorDiv(child.key.nodeX(), 2);
        int parentZ = Math.floorDiv(child.key.nodeZ(), 2);
        Node parent = node(child.key.dimension(), parentLevel, parentX, parentZ);

        int quadrantX = Math.floorMod(child.key.nodeX(), 2);
        int quadrantZ = Math.floorMod(child.key.nodeZ(), 2);
        int destinationBaseX = quadrantX * 32;
        int destinationBaseY = quadrantZ * 32;
        int minX = Math.max(0, child.dirtyMinX >> 1);
        int minY = Math.max(0, child.dirtyMinY >> 1);
        int maxX = Math.min(31, child.dirtyMaxX >> 1);
        int maxY = Math.min(31, child.dirtyMaxY >> 1);
        int changedMinX = 64;
        int changedMinY = 64;
        int changedMaxX = -1;
        int changedMaxY = -1;
        long previousKnownMask = parent.knownMask;
        long previousCompleteMask = parent.completeMask;
        for (int y = minY; y <= maxY; y++) {
            int sourceY = y << 1;
            int targetRow = (destinationBaseY + y) * SurfaceBranchAtlas.SIZE;
            for (int x = minX; x <= maxX; x++) {
                int sourceX = x << 1;
                int known = knownCount(child.knownRows, sourceX, sourceY);
                int targetX = destinationBaseX + x;
                int targetY = destinationBaseY + y;
                int targetIndex = targetRow + targetX;
                boolean nowKnown = known > 0;
                int reduced = nowKnown
                        ? reduceSurface(child.pixels, child.knownRows, sourceX, sourceY)
                        : 0;
                boolean wasKnown = (parent.knownRows[targetY] & (1L << targetX)) != 0L;
                boolean wasComplete = (parent.completeRows[targetY] & (1L << targetX)) != 0L;
                boolean nowComplete = nowKnown
                        && knownCount(child.completeRows, sourceX, sourceY) == 4;
                if (wasKnown == nowKnown && parent.pixels[targetIndex] == reduced
                        && wasComplete == nowComplete) continue;
                parent.pixels[targetIndex] = reduced;
                setBit(parent.knownRows, targetX, targetY, nowKnown);
                setBit(parent.completeRows, targetX, targetY, nowComplete);
                changedMinX = Math.min(changedMinX, targetX);
                changedMinY = Math.min(changedMinY, targetY);
                changedMaxX = Math.max(changedMaxX, targetX);
                changedMaxY = Math.max(changedMaxY, targetY);
            }
        }

        int childIndex = quadrantZ * 2 + quadrantX;
        parent.childRevisions[childIndex] = child.revision;
        refreshQuadrantCoverage(parent, quadrantX, quadrantZ);
        boolean coverageChanged = previousKnownMask != parent.knownMask
                || previousCompleteMask != parent.completeMask;
        if (changedMaxX < changedMinX && !coverageChanged) return;
        if (changedMaxX < changedMinX) {
            changedMinX = destinationBaseX + minX;
            changedMinY = destinationBaseY + minY;
            changedMaxX = destinationBaseX + maxX;
            changedMaxY = destinationBaseY + maxY;
        }
        parent.revision++;
        parent.markDirty(changedMinX, changedMinY, changedMaxX, changedMaxY);
        enqueue(parent.key);
        trimLevel(parentLevel);
        propagate(parent);
    }

    private static int knownCount(long[] rows, int x, int y) {
        int count = 0;
        if ((rows[y] & (1L << x)) != 0L) count++;
        if ((rows[y] & (1L << (x + 1))) != 0L) count++;
        if ((rows[y + 1] & (1L << x)) != 0L) count++;
        if ((rows[y + 1] & (1L << (x + 1))) != 0L) count++;
        return count;
    }

    private static int reduceSurface(int[] pixels, long[] rows, int x, int y) {
        long red = 0L, green = 0L, blue = 0L, weight = 0L;
        int maximumAlpha = 0;
        // Four scalar samples; avoids two short-lived arrays for every LOD texel.
        for (int child = 0; child < 4; child++) {
            int sourceX = x + (child & 1);
            int sourceY = y + (child >>> 1);
            if ((rows[sourceY] & (1L << sourceX)) == 0L) continue;
            int value = pixels[sourceY * 64 + sourceX];
            int alpha = (value >>> 24) & 0xFF;
            if (value == 0 || alpha == 0) continue;
            red += (long) (value & 0xFF) * alpha;
            green += (long) ((value >>> 8) & 0xFF) * alpha;
            blue += (long) ((value >>> 16) & 0xFF) * alpha;
            weight += alpha;
            maximumAlpha = Math.max(maximumAlpha, alpha);
        }
        if (weight == 0L) return 0;
        return (Math.max(1, maximumAlpha) << 24)
                | ((int) (blue / weight) << 16)
                | ((int) (green / weight) << 8)
                | (int) (red / weight);
    }

    private static void setBit(long[] rows, int x, int y, boolean value) {
        long bit = 1L << x;
        if (value) rows[y] |= bit;
        else rows[y] &= ~bit;
    }

    private static void refreshQuadrantCoverage(Node node, int quadrantX, int quadrantZ) {
        int startX = quadrantX * 32;
        int startY = quadrantZ * 32;
        boolean anyKnown = false;
        boolean allComplete = true;
        long rangeMask = 0xFFFFFFFFL << startX;
        for (int y = startY; y < startY + 32; y++) {
            anyKnown |= (node.knownRows[y] & rangeMask) != 0L;
            if ((node.completeRows[y] & rangeMask) != rangeMask) allComplete = false;
        }
        int childIndex = quadrantZ * 2 + quadrantX;
        if (anyKnown) node.knownMask |= 1L << childIndex;
        else node.knownMask &= ~(1L << childIndex);
        if (allComplete) node.completeMask |= 1L << childIndex;
        else node.completeMask &= ~(1L << childIndex);
    }

    private static boolean allRowsComplete(long[] rows) {
        for (int y = 0; y < 64; y++) if (rows[y] != FULL_ROW) return false;
        return true;
    }

    private Node node(String dimension, int level, int nodeX, int nodeZ) {
        NodeKey key = new NodeKey(dimension, level, nodeX, nodeZ);
        Node node = nodes.computeIfAbsent(key, Node::new);
        requestDiskLoad(key);
        return node;
    }

    private void requestDiskLoad(NodeKey key) {
        if (nodes.containsKey(key) && nodes.get(key).knownMask != 0L) return;
        if (!diskCache.mayContain(diskKey(key))) return;
        if (!loadingFromDisk.add(key)) return;
        long generation = loadGeneration;
        diskCache.loadAsync(diskKey(key)).whenComplete((snapshot, throwable) ->
                loadedFromDisk.add(new LoadedNode(key, snapshot, generation)));
    }

    private void drainDiskLoads() {
        LoadedNode loaded;
        while ((loaded = loadedFromDisk.poll()) != null) {
            loadingFromDisk.remove(loaded.key);
            if (loaded.generation != loadGeneration) continue;
            LodBranchDiskCache.Snapshot snapshot = loaded.snapshot;
            if (snapshot == null || snapshot.knownMask() == 0L) continue;
            Node node = nodes.computeIfAbsent(loaded.key, Node::new);
            for (int y = 0; y < 64; y++) {
                long incomingKnown = snapshot.knownRows()[y];
                long missing = incomingKnown & ~node.knownRows[y];
                while (missing != 0L) {
                    int x = Long.numberOfTrailingZeros(missing);
                    int index = y * 64 + x;
                    node.pixels[index] = snapshot.pixels()[index];
                    missing &= missing - 1L;
                }
                node.knownRows[y] |= incomingKnown;
                node.completeRows[y] |= snapshot.completeRows()[y];
            }
            node.knownMask |= snapshot.knownMask();
            node.completeMask |= snapshot.completeMask();
            node.revision = Math.max(node.revision, snapshot.revision());
            node.markFullDirty();
            enqueue(node.key);
        }
    }

    private void saveNode(Node node) {
        diskCache.saveAsync(diskKey(node.key), new LodBranchDiskCache.Snapshot(
                node.pixels, node.knownRows, node.completeRows,
                node.knownMask, node.completeMask, node.revision));
    }

    private static String residencyKey(NodeKey key) {
        return key == null ? "surface_branch:unknown" : "surface_branch:" + key;
    }

    private static LodBranchDiskCache.Key diskKey(NodeKey key) {
        return new LodBranchDiskCache.Key("surface_chunk16_" + key.dimension,
                key.level, key.nodeX, key.nodeZ);
    }


    private boolean evictResidentForBudget(NodeKey key) {
        if (key == null) return false;
        Node node = nodes.get(key);
        if (node == null || node.atlasSlot < 0 || node.isDirty()
                || !hasPublishedParentCoverage(node)) return false;
        atlases[key.level()].releaseSlot(node.atlasSlot);
        MapResidencyManager.getInstance().remove(residencyKey(key));
        node.atlasSlot = -1;
        node.initialized = false;
        return true;
    }

    /**
     * Publish-before-retire applies between every LOD level, not only exact to
     * level 1. A resident branch is released only after its parent has uploaded a
     * child revision at least as new, preserving one continuous GPU underlay.
     */
    private boolean hasPublishedParentCoverage(Node node) {
        if (node == null || node.key.level() >= MAX_LEVEL) return false;
        int parentLevel = node.key.level() + 1;
        int parentX = Math.floorDiv(node.key.nodeX(), 2);
        int parentZ = Math.floorDiv(node.key.nodeZ(), 2);
        Node parent = nodes.get(new NodeKey(node.key.dimension(),
                parentLevel, parentX, parentZ));
        if (parent == null || !parent.initialized || parent.atlasSlot < 0) return false;
        int childIndex = Math.floorMod(node.key.nodeZ(), 2) * 2
                + Math.floorMod(node.key.nodeX(), 2);
        return (parent.uploadedKnownMask & (1L << childIndex)) != 0L
                && parent.uploadedChildRevisions[childIndex] >= node.uploadedRevision;
    }

    private boolean ensureSlot(Node node) {
        if (node.atlasSlot >= 0) return true;
        int slot = atlases[node.key.level()].acquireSlot();
        if (slot < 0) {
            if (!retireOldestResident(node.key.level(), node.key)) return false;
            slot = atlases[node.key.level()].acquireSlot();
        }
        if (slot < 0) return false;
        node.atlasSlot = slot;
        node.markFullDirty();
        return true;
    }

    private void trimLevel(int level) {
        int atlasSlots = SurfaceBranchAtlas.slotCountForLevel(level);
        // Keep at most two atlas generations of CPU branch pixels warm. The old
        // fixed 2048/1024 limits could retain well over one hundred MiB across
        // surface and cave trees even after GPU entries were evicted.
        int maximumCpuNodes = Math.max(96, atlasSlots * 2);
        while (countLevel(level) > maximumCpuNodes) {
            if (!removeOldestNonResident(level)) break;
        }
    }

    private int countLevel(int level) {
        int count = 0;
        for (NodeKey key : nodes.keySet()) if (key.level() == level) count++;
        return count;
    }

    /** Releases only GPU residency; CPU pixels and coverage remain reusable. */
    private boolean retireOldestResident(int level, NodeKey protectedKey) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        java.util.Map<String, Node> byKey = new java.util.HashMap<>();
        // nodes is an access-order LinkedHashMap. Parent-coverage lookups call
        // nodes.get(), which reorder the map. Iterate a stable snapshot so LRU
        // observation cannot invalidate the active iterator.
        java.util.List<Map.Entry<NodeKey, Node>> snapshot =
                new java.util.ArrayList<>(nodes.entrySet());
        for (Map.Entry<NodeKey, Node> entry : snapshot) {
            if (entry.getKey().level() != level
                    || entry.getKey().equals(protectedKey)) continue;
            Node node = entry.getValue();
            if (node.atlasSlot < 0 || node.isDirty()
                    || !hasPublishedParentCoverage(node)) continue;
            String residency = residencyKey(entry.getKey());
            candidates.add(residency);
            byKey.put(residency, node);
        }
        String victimKey = MapResidencyManager.getInstance().chooseVictim(
                candidates, residencyKey(protectedKey));
        Node retired = byKey.get(victimKey);
        if (retired == null) return false;
        atlases[level].releaseSlot(retired.atlasSlot);
        MapResidencyManager.getInstance().remove(victimKey);
        retired.atlasSlot = -1;
        retired.initialized = false;
        return true;
    }

    private boolean removeOldestNonResident(int level) {
        Iterator<Map.Entry<NodeKey, Node>> iterator = nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<NodeKey, Node> entry = iterator.next();
            if (entry.getKey().level() != level) continue;
            Node node = entry.getValue();
            if (node.atlasSlot >= 0 || node.isDirty()) continue;
            iterator.remove();
            dirtySet.remove(node.key);
            return true;
        }
        return false;
    }

    private NodeKey pollDirty(int preferredLevel) {
        int preferred = Math.max(1, Math.min(MAX_LEVEL, preferredLevel));
        NodeKey key = pollVisibleDirty(preferred);
        if (key != null) return key;
        key = dirtyQueues[preferred].pollFirst();
        if (key != null) return key;
        for (int distance = 1; distance < MAX_LEVEL; distance++) {
            int finer = preferred - distance;
            if (finer >= 1) {
                key = pollVisibleDirty(finer);
                if (key == null) key = dirtyQueues[finer].pollFirst();
                if (key != null) return key;
            }
            int coarser = preferred + distance;
            if (coarser <= MAX_LEVEL) {
                key = pollVisibleDirty(coarser);
                if (key == null) key = dirtyQueues[coarser].pollFirst();
                if (key != null) return key;
            }
        }
        return null;
    }

    private NodeKey pollVisibleDirty(int level) {
        ArrayDeque<NodeKey> queue = dirtyQueues[level];
        if (queue == null || queue.isEmpty()) return null;
        VisibleWindow window = visibleWindow;
        int span = 1 << level;
        int minNodeX = Math.floorDiv(window.minPageX(), span);
        int maxNodeX = Math.floorDiv(window.maxPageX(), span);
        int minNodeZ = Math.floorDiv(window.minPageZ(), span);
        int maxNodeZ = Math.floorDiv(window.maxPageZ(), span);
        int focusNodeX = Math.floorDiv(window.focusPageX(), span);
        int focusNodeZ = Math.floorDiv(window.focusPageZ(), span);
        NodeKey best = null;
        long bestDistance = Long.MAX_VALUE;
        int scanCount = Math.min(queue.size(), VISIBLE_QUEUE_SCAN_BUDGET);
        for (int scanned = 0; scanned < scanCount; scanned++) {
            NodeKey candidate = queue.pollFirst();
            if (candidate == null) break;
            if (!isVisibleNode(candidate, window, minNodeX, maxNodeX,
                    minNodeZ, maxNodeZ)) {
                queue.addLast(candidate);
                continue;
            }
            long dx = (long) candidate.nodeX() - focusNodeX;
            long dz = (long) candidate.nodeZ() - focusNodeZ;
            long distance = dx * dx + dz * dz;
            if (best == null || distance < bestDistance) {
                if (best != null) queue.addLast(best);
                best = candidate;
                bestDistance = distance;
            } else {
                queue.addLast(candidate);
            }
        }
        return best;
    }

    private void enqueue(NodeKey key) {
        if (key == null || key.level() < 1 || key.level() > MAX_LEVEL) return;
        if (dirtySet.add(key)) dirtyQueues[key.level()].addLast(key);
    }

    private record VisibleWindow(String dimension, int preferredLevel,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int focusPageX, int focusPageZ) {
        private static VisibleWindow none() {
            return new VisibleWindow("", 1, Integer.MIN_VALUE, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0);
        }

        private boolean matches(String candidateDimension) {
            return !dimension.isEmpty() && dimension.equals(candidateDimension);
        }
    }

    private record PageUpdateKey(String dimension, int globalPageX, int globalPageZ) {
    }

    private static final class LeafState {
        private final PageUpdateKey key;
        private int[] pixels64;
        private long[] knownRows;
        private boolean complete;
        private long sourceRevision;
        private long derivedRevision;
        private long queuedNanos;
        private MapRequestLane lane = MapRequestLane.BACKGROUND;

        private LeafState(PageUpdateKey key) {
            this.key = key;
        }
    }

    private record LeafInput(PageUpdateKey key, int[] pixels64, long[] knownRows,
            boolean complete, long revision, MapRequestLane lane,
            long queuedNanos, long generation) {
    }

    private record PreparedLeaf(PageUpdateKey key, int[] pixels32,
            long[] knownRows32, long[] completeRows32,
            long revision, long queuedNanos) {
    }

    private record NodeKey(String dimension, int level, int nodeX, int nodeZ) {
    }

    private record LoadedNode(NodeKey key, LodBranchDiskCache.Snapshot snapshot,
            long generation) {
    }

    private final class Node {
        private final NodeKey key;
        private final int[] pixels = new int[64 * 64];
        private final long[] knownRows = new long[64];
        private final long[] completeRows = new long[64];
        private long knownMask;
        private long completeMask;
        /** GPU-published masks remain authoritative while newer CPU state is dirty. */
        private long uploadedKnownMask;
        private long uploadedCompleteMask;
        private long revision = 1L;
        private long uploadedRevision;
        private final long[] childRevisions = new long[4];
        private final long[] uploadedChildRevisions = new long[4];
        private int atlasSlot = -1;
        private int dirtyMinX = 64;
        private int dirtyMinY = 64;
        private int dirtyMaxX = -1;
        private int dirtyMaxY = -1;
        private long nextUploadAttemptNanos;
        private int uploadReservationFailures;
        private boolean initialized;

        private Node(NodeKey key) {
            this.key = key;
        }

        private boolean isComplete() {
            return completeMask == 0xFL;
        }

        private void markFullDirty() {
            markDirty(0, 0, 63, 63);
        }

        private void markDirty(int minX, int minY, int maxX, int maxY) {
            dirtyMinX = Math.min(dirtyMinX, minX);
            dirtyMinY = Math.min(dirtyMinY, minY);
            dirtyMaxX = Math.max(dirtyMaxX, maxX);
            dirtyMaxY = Math.max(dirtyMaxY, maxY);
        }

        private boolean isDirty() {
            return dirtyMaxX >= dirtyMinX && dirtyMaxY >= dirtyMinY;
        }

        private CaveTextureAtlas.DirtyRect dirtyRect() {
            return new CaveTextureAtlas.DirtyRect(
                    dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY);
        }

        private void clearDirty() {
            dirtyMinX = dirtyMinY = 64;
            dirtyMaxX = dirtyMaxY = -1;
        }

        private void close() {
            if (atlasSlot >= 0) atlases[key.level()].releaseSlot(atlasSlot);
            MapResidencyManager.getInstance().remove(residencyKey(key));
            atlasSlot = -1;
            initialized = false;
        }
    }
}
