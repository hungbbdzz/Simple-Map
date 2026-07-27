package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapGpuBudgetController;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapResidencyManager;

import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Page-rooted recursive cave hierarchy.
 *
 * <p>Exact cave pages remain 64x64 block textures. Branch level 1 groups 2x2
 * pages and covers 128x128 blocks, then each higher level doubles the world
 * span while retaining a 64x64 GPU texture. Unknown coverage is never inferred
 * from transparent colour; it is carried separately by the page known-row mask.</p>
 */
final class CaveLodTree {
    static final int MAX_LEVEL = 7;
    private static final long FULL_ROW = -1L;
    /** Xaero admits only a couple of branch-cache requests per render pass. */
    private static final int MAX_DISK_LOADS_PER_WINDOW = 2;
    private static final long DISK_LOAD_WINDOW_NANOS = 16_000_000L;

    private final CaveBranchAtlas[] atlases = new CaveBranchAtlas[MAX_LEVEL + 1];
    private final long[] observedStorageGeneration = new long[MAX_LEVEL + 1];
    private final Map<NodeKey, Node> nodes = new LinkedHashMap<>(128, 0.75f, true);
    private final ArrayDeque<NodeKey> dirtyQueue = new ArrayDeque<>();
    private final Set<NodeKey> dirtySet = new HashSet<>();
    private final Set<NodeKey> loadingFromDisk = new HashSet<>();
    private final ConcurrentLinkedQueue<LoadedNode> loadedFromDisk =
            new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<PageUpdateKey, PageUpdate> pendingPageUpdates =
            new LinkedHashMap<>(64, 0.75f, true);
    private final MapPipelineTelemetry pipelineTelemetry = MapPipelineTelemetry.getInstance();
    private final LodBranchDiskCache diskCache = LodBranchDiskCache.getInstance();
    private long loadGeneration = 1L;
    private long diskLoadWindowStartedNanos;
    private int diskLoadsInWindow;
    /** Cave-only render revision; surface/background atlas traffic must not rebuild cave plans. */
    private final AtomicLong contentRevision = new AtomicLong();

    long contentRevision() {
        return contentRevision.get();
    }

    CaveLodTree() {
        java.util.Arrays.fill(observedStorageGeneration, Long.MIN_VALUE);
        for (int level = 1; level <= MAX_LEVEL; level++) {
            atlases[level] = new CaveBranchAtlas(level);
        }
    }

    void synchronizeStorage() {
        drainDiskLoads();
        boolean reallocated = false;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            CaveBranchAtlas atlas = atlases[level];
            atlas.ensureInitialized();
            long generation = atlas.storageGeneration();
            if (observedStorageGeneration[level] != Long.MIN_VALUE
                    && observedStorageGeneration[level] != generation) reallocated = true;
            observedStorageGeneration[level] = generation;
        }
        if (!reallocated) return;
        MapResidencyManager.getInstance().markTopologyChanged();
        contentRevision.incrementAndGet();
        for (Node node : nodes.values()) {
            node.initialized = false;
            node.markFullDirty();
            enqueue(node.key);
        }
    }

    CaveAtlasRegion peek(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        drainDiskLoads();
        if (level < 1 || level > MAX_LEVEL) return null;
        NodeKey key = new NodeKey(dimension, view, layerY, level, nodeX, nodeZ);
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

    boolean hasData(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        drainDiskLoads();
        if (level < 1 || level > MAX_LEVEL) return false;
        NodeKey key = new NodeKey(dimension, view, layerY, level, nodeX, nodeZ);
        Node node = nodes.get(key);
        if (node == null) requestDiskLoad(key);
        if (node != null && node.knownMask != 0L) return true;
        LodBranchDiskCache.Metadata metadata = diskCache.metadata(diskKey(key));
        return metadata != null && metadata.knownMask() != 0L;
    }

    /**
     * Exact-to-branch publication fence. An exact cave page is safe to retire only
     * after its level-1 quadrant is resident on the GPU and was derived from at
     * least the exact page revision being retired.
     */
    boolean coversPage(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ, long sourceRevision) {
        drainDiskLoads();
        int nodeX = Math.floorDiv(globalPageX, 2);
        int nodeZ = Math.floorDiv(globalPageZ, 2);
        NodeKey key = new NodeKey(dimension, view, layerY, 1, nodeX, nodeZ);
        Node node = nodes.get(key);
        if (node == null || !node.initialized || node.atlasSlot < 0) return false;
        int childIndex = Math.floorMod(globalPageZ, 2) * 2
                + Math.floorMod(globalPageX, 2);
        boolean covered = (node.uploadedKnownMask & (1L << childIndex)) != 0L
                && node.uploadedChildRevisions[childIndex]
                        >= Math.max(1L, sourceRevision);
        if (covered) MapResidencyManager.getInstance().touch(residencyKey(key));
        return covered;
    }

    void updatePage(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ, int[] pagePixels64,
            long[] knownRows, int knownColumns, boolean complete) {
        updatePage(dimension, view, layerY, globalPageX, globalPageZ,
                pagePixels64, knownRows, knownColumns, complete,
                System.nanoTime(), MapRequestLane.BACKGROUND);
    }

    void updatePage(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ, int[] pagePixels64,
            long[] knownRows, int knownColumns, boolean complete,
            long sourceRevision, MapRequestLane lane) {
        if (pagePixels64 == null || pagePixels64.length < 64 * 64
                || knownRows == null || knownRows.length < 64 || knownColumns <= 0) return;
        PageUpdateKey key = new PageUpdateKey(dimension, view, layerY,
                globalPageX, globalPageZ);
        // Version-backed coalescing: a slow branch builder retains the newest
        // semantic page state instead of dropping an unrelated older key.
        PageUpdate update = new PageUpdate(key,
                java.util.Arrays.copyOf(pagePixels64, 64 * 64),
                java.util.Arrays.copyOf(knownRows, 64), knownColumns, complete,
                Math.max(1L, sourceRevision),
                lane == null ? MapRequestLane.BACKGROUND : lane,
                System.nanoTime());
        synchronized (pendingPageUpdates) {
            PageUpdate previous = pendingPageUpdates.get(key);
            if (previous == null || update.sourceRevision() >= previous.sourceRevision()) {
                pendingPageUpdates.put(key, update);
            }
        }
        pipelineTelemetry.recordBranchUpdateQueued();
    }

    private void applyPageUpdate(PageUpdate update) {
        drainDiskLoads();
        PageUpdateKey key = update.key();
        int[] pagePixels64 = update.pagePixels64();
        long[] knownRows = update.knownRows();
        int globalPageX = key.globalPageX();
        int globalPageZ = key.globalPageZ();

        int nodeX = Math.floorDiv(globalPageX, 2);
        int nodeZ = Math.floorDiv(globalPageZ, 2);
        Node node = node(key.dimension(), key.view(), key.layerY(), 1, nodeX, nodeZ);
        int localPageX = Math.floorMod(globalPageX, 2);
        int localPageZ = Math.floorMod(globalPageZ, 2);
        int destinationBaseX = localPageX * 32;
        int destinationBaseY = localPageZ * 32;
        int changedMinX = 64;
        int changedMinY = 64;
        int changedMaxX = -1;
        int changedMaxY = -1;

        long previousKnownMask = node.knownMask;
        long previousCompleteMask = node.completeMask;
        for (int y = 0; y < 32; y++) {
            int sourceY = y << 1;
            int targetRow = (destinationBaseY + y) * 64;
            for (int x = 0; x < 32; x++) {
                int sourceX = x << 1;
                int known = knownCount(knownRows, sourceX, sourceY);
                int targetX = destinationBaseX + x;
                int targetY = destinationBaseY + y;
                int targetIndex = targetRow + targetX;
                boolean nowKnown = known > 0;
                int reduced = nowKnown
                        ? reduceCave(pagePixels64, knownRows, sourceX, sourceY) : 0;
                boolean wasKnown = (node.knownRows[targetY] & (1L << targetX)) != 0L;
                boolean wasComplete = (node.completeRows[targetY] & (1L << targetX)) != 0L;
                boolean nowComplete = nowKnown && known == 4;
                if (wasKnown == nowKnown && node.pixels[targetIndex] == reduced
                        && wasComplete == nowComplete) continue;
                node.pixels[targetIndex] = reduced;
                setBit(node.knownRows, targetX, targetY, nowKnown);
                setBit(node.completeRows, targetX, targetY, nowComplete);
                changedMinX = Math.min(changedMinX, targetX);
                changedMinY = Math.min(changedMinY, targetY);
                changedMaxX = Math.max(changedMaxX, targetX);
                changedMaxY = Math.max(changedMaxY, targetY);
            }
        }

        int childIndex = localPageZ * 2 + localPageX;
        node.childRevisions[childIndex] = Math.max(
                node.childRevisions[childIndex], update.sourceRevision());
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

    private int drainPendingPageUpdates(int budget, long deadlineNanos) {
        int derived = 0;
        while (derived < budget && System.nanoTime() < deadlineNanos) {
            PageUpdate update;
            synchronized (pendingPageUpdates) {
                Iterator<PageUpdate> iterator = pendingPageUpdates.values().iterator();
                if (!iterator.hasNext()) break;
                update = iterator.next();
                iterator.remove();
            }
            long started = System.nanoTime();
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_QUEUE,
                    Math.max(0L, started - update.queuedNanos()));
            applyPageUpdate(update);
            pipelineTelemetry.recordStageNanos(MapPipelineStage.BRANCH_DERIVE,
                    System.nanoTime() - started);
            derived++;
        }
        return derived;
    }

    int publish(int budget, long deadlineNanos) {
        drainDiskLoads();
        drainPendingPageUpdates(Math.max(1, Math.min(2, budget)), deadlineNanos);
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
                    residentKey, MapResidencyManager.Kind.CAVE_BRANCH,
                    66L * 66L * Integer.BYTES,
                    () -> evictResidentForBudget(node.key));
            MapResidencyManager.getInstance().enforceBudget(
                    residentKey, MapRequestLane.FULLSCREEN);
            node.uploadedRevision = node.revision;
            node.uploadedKnownMask = node.knownMask;
            node.uploadedCompleteMask = node.completeMask;
            System.arraycopy(node.childRevisions, 0,
                    node.uploadedChildRevisions, 0, node.childRevisions.length);
            contentRevision.incrementAndGet();
            saveNode(node);
            published++;
        }
        return published;
    }

    void clear() {
        if (!nodes.isEmpty()) contentRevision.incrementAndGet();
        for (Node node : nodes.values()) node.close();
        nodes.clear();
        dirtyQueue.clear();
        dirtySet.clear();
        loadGeneration++;
        loadingFromDisk.clear();
        loadedFromDisk.clear();
        diskLoadWindowStartedNanos = 0L;
        diskLoadsInWindow = 0;
        synchronized (pendingPageUpdates) {
            pendingPageUpdates.clear();
        }
        for (int level = 1; level <= MAX_LEVEL; level++) atlases[level].resetSlots();
    }

    private void propagate(Node child) {
        if (child.key.level() >= MAX_LEVEL || !child.isDirty()) return;
        int parentLevel = child.key.level() + 1;
        int parentX = Math.floorDiv(child.key.nodeX(), 2);
        int parentZ = Math.floorDiv(child.key.nodeZ(), 2);
        Node parent = node(child.key.dimension(), child.key.view(), child.key.layerY(),
                parentLevel, parentX, parentZ);

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
            int targetRow = (destinationBaseY + y) * 64;
            for (int x = minX; x <= maxX; x++) {
                int sourceX = x << 1;
                int known = knownCount(child.knownRows, sourceX, sourceY);
                int targetX = destinationBaseX + x;
                int targetY = destinationBaseY + y;
                int targetIndex = targetRow + targetX;
                boolean nowKnown = known > 0;
                int reduced = nowKnown
                        ? reduceCave(child.pixels, child.knownRows, sourceX, sourceY) : 0;
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

    private static int reduceCave(int[] pixels, long[] rows, int x, int y) {
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
        return reduceCaveValues(values, known);
    }

    /** Occupancy-preserving reduction: any visible child leaves a visible parent. */
    private static int reduceCaveValues(int[] values, boolean[] known) {
        long red = 0L, green = 0L, blue = 0L, weight = 0L;
        int best = 0;
        int bestScore = -1;
        int maximumAlpha = 0;
        for (int i = 0; i < values.length; i++) {
            if (!known[i]) continue;
            int value = values[i];
            int alpha = (value >>> 24) & 0xFF;
            if (value == 0 || alpha == 0) continue;
            int r = value & 0xFF;
            int g = (value >>> 8) & 0xFF;
            int b = (value >>> 16) & 0xFF;
            int luminance = r * 3 + g * 6 + b;
            int score = alpha * 2048 + luminance;
            if (score > bestScore) {
                bestScore = score;
                best = value;
            }
            red += (long) r * alpha;
            green += (long) g * alpha;
            blue += (long) b * alpha;
            weight += alpha;
            maximumAlpha = Math.max(maximumAlpha, alpha);
        }
        if (weight == 0L) return 0;
        int avgR = (int) (red / weight);
        int avgG = (int) (green / weight);
        int avgB = (int) (blue / weight);
        int bestR = best & 0xFF;
        int bestG = (best >>> 8) & 0xFF;
        int bestB = (best >>> 16) & 0xFF;
        int r = (bestR * 3 + avgR) >> 2;
        int g = (bestG * 3 + avgG) >> 2;
        int b = (bestB * 3 + avgB) >> 2;
        return (Math.max(1, maximumAlpha) << 24) | (b << 16) | (g << 8) | r;
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

    private Node node(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        NodeKey key = new NodeKey(dimension, view, layerY, level, nodeX, nodeZ);
        Node node = nodes.computeIfAbsent(key, Node::new);
        requestDiskLoad(key);
        return node;
    }

    private void requestDiskLoad(NodeKey key) {
        if (nodes.containsKey(key) && nodes.get(key).knownMask != 0L) return;
        if (!diskCache.mayContain(diskKey(key))) return;
        if (loadingFromDisk.contains(key)) return;
        if (!consumeDiskLoadSlot()) return;
        if (!loadingFromDisk.add(key)) return;
        long generation = loadGeneration;
        diskCache.loadAsync(diskKey(key)).whenComplete((snapshot, throwable) ->
                loadedFromDisk.add(new LoadedNode(key, snapshot, generation)));
    }

    private boolean consumeDiskLoadSlot() {
        long now = System.nanoTime();
        if (diskLoadWindowStartedNanos == 0L
                || now - diskLoadWindowStartedNanos >= DISK_LOAD_WINDOW_NANOS) {
            diskLoadWindowStartedNanos = now;
            diskLoadsInWindow = 0;
        }
        if (diskLoadsInWindow >= MAX_DISK_LOADS_PER_WINDOW) return false;
        diskLoadsInWindow++;
        return true;
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
        return key == null ? "cave_branch:unknown" : "cave_branch:" + key;
    }

    private static LodBranchDiskCache.Key diskKey(NodeKey key) {
        String kind = "cave_" + key.dimension + '_' + key.view.name().toLowerCase()
                + '_' + key.layerY;
        return new LodBranchDiskCache.Key(kind,
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
        contentRevision.incrementAndGet();
        return true;
    }

    /** Keeps one continuous cave LOD underlay while exact/branch residency churns. */
    private boolean hasPublishedParentCoverage(Node node) {
        if (node == null || node.key.level() >= MAX_LEVEL) return false;
        int parentLevel = node.key.level() + 1;
        int parentX = Math.floorDiv(node.key.nodeX(), 2);
        int parentZ = Math.floorDiv(node.key.nodeZ(), 2);
        Node parent = nodes.get(new NodeKey(node.key.dimension(), node.key.view(),
                node.key.layerY(), parentLevel, parentX, parentZ));
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
        int atlasSlots = CaveBranchAtlas.slotCountForLevel(level);
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
        contentRevision.incrementAndGet();
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

    private record PageUpdateKey(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
    }

    private record PageUpdate(PageUpdateKey key, int[] pagePixels64, long[] knownRows,
            int knownColumns, boolean complete, long sourceRevision,
            MapRequestLane lane, long queuedNanos) {
    }

    private record NodeKey(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
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

        private void markDirty(int minX, int minY, int maxX, int maxY) {
            dirtyMinX = Math.min(dirtyMinX, minX);
            dirtyMinY = Math.min(dirtyMinY, minY);
            dirtyMaxX = Math.max(dirtyMaxX, maxX);
            dirtyMaxY = Math.max(dirtyMaxY, maxY);
        }

        private void markFullDirty() {
            markDirty(0, 0, 63, 63);
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
