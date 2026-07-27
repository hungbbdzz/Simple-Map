package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapGpuBudgetController;
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
    public static final int MAX_LEVEL = 7;
    private static final long FULL_ROW = -1L;
    /** Clean leaf authority is LRU-bounded; dirty state is never discarded. */
    private static final int MAX_RETAINED_LEAF_STATES = 16_384;

    private final SurfaceBranchAtlas[] atlases = new SurfaceBranchAtlas[MAX_LEVEL + 1];
    private final long[] observedStorageGeneration = new long[MAX_LEVEL + 1];
    private final Map<NodeKey, Node> nodes = new LinkedHashMap<>(128, 0.75f, true);
    private final ArrayDeque<NodeKey> dirtyQueue = new ArrayDeque<>();
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
    private long loadGeneration = 1L;

    public static void invalidatePersistentCache() {
        LodBranchDiskCache.getInstance().invalidateCurrentDimension();
    }

    public SurfaceLodTree() {
        java.util.Arrays.fill(observedStorageGeneration, Long.MIN_VALUE);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            atlases[level] = new SurfaceBranchAtlas(level);
        }
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
                PageUpdateKey key = pendingLeafQueue.pollFirst();
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
        drainDiskLoads();
        int deriveBudget = Math.max(2, budget * 3);
        drainPreparedLeaves(deriveBudget, deadlineNanos);
        scheduleLeafDerivations(deriveBudget, deadlineNanos);
        // Very small pages can complete while this publication slice is still open.
        drainPreparedLeaves(deriveBudget, deadlineNanos);
        int published = 0;
        while (published < budget && System.nanoTime() < deadlineNanos) {
            NodeKey key = dirtyQueue.pollFirst();
            if (key == null) break;
            dirtySet.remove(key);
            Node node = nodes.get(key);
            if (node == null || !node.isDirty() || node.knownMask == 0L) continue;
            if (!ensureSlot(node)) {
                enqueue(key);
                break;
            }
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.BRANCH,
                    MapRequestLane.FULLSCREEN, false)) {
                enqueue(key);
                break;
            }
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
                MapResidencyManager.getInstance().markCoverageChanged();
            }
            saveNode(node);
            published++;
        }
        return published;
    }

    public void clear() {
        for (Node node : nodes.values()) node.close();
        nodes.clear();
        dirtyQueue.clear();
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
        int[] values = {
                pixels[y * 64 + x], pixels[y * 64 + x + 1],
                pixels[(y + 1) * 64 + x], pixels[(y + 1) * 64 + x + 1]
        };
        boolean[] known = {
                (rows[y] & (1L << x)) != 0L,
                (rows[y] & (1L << (x + 1))) != 0L,
                (rows[y + 1] & (1L << x)) != 0L,
                (rows[y + 1] & (1L << (x + 1))) != 0L
        };
        return reduceVisible(values, known);
    }

    private static int reduceVisible(int[] values, boolean[] known) {
        long red = 0L, green = 0L, blue = 0L, weight = 0L;
        int maximumAlpha = 0;
        for (int i = 0; i < values.length; i++) {
            if (!known[i]) continue;
            int value = values[i];
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
        return new LodBranchDiskCache.Key("surface_" + key.dimension,
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
        for (Map.Entry<NodeKey, Node> entry : nodes.entrySet()) {
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

    private void enqueue(NodeKey key) {
        if (dirtySet.add(key)) dirtyQueue.addLast(key);
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
