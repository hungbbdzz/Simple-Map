package com.velorise.simplemap.client;

import com.velorise.simplemap.client.surface.SurfacePublicationService;
import com.velorise.simplemap.client.surface.SurfaceResidencyService;
import com.velorise.simplemap.client.cave.CaveAtlasRegion;
import com.velorise.simplemap.client.cave.SurfaceRegionLodAtlas;
import com.velorise.simplemap.client.lod.PreparedBranch;
import com.velorise.simplemap.client.lod.RegionLodDeriver;
import com.velorise.simplemap.client.lod.RegionLodGraph;
import com.velorise.simplemap.client.gpu.MapGpuPageTableService;
import com.velorise.simplemap.client.gpu.PageTableEntry;
import com.velorise.simplemap.client.gpu.TileKey;
import com.velorise.simplemap.client.gpu.UploadCommand;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * M4 region-centric Surface LOD authority.
 *
 * <p>The old factor-2 {@code SurfaceLodTree} remains a visual-quality adapter,
 * but this service owns durable region-level dirty state, direct source
 * projection, 8x8 parent derivation, CPU prepared revisions and GPU publication.
 * A level-0 node can therefore cover a 512x512 region before any of its 64 exact
 * pages has completed.</p>
 */
public final class RegionSurfaceLodService {
    public static final int PROJECTION_SURFACE = 0;
    private static final RegionSurfaceLodService INSTANCE =
            new RegionSurfaceLodService();
    private static final int MAX_LEVEL = MapRegionLodPolicy.MAX_LEVEL;
    private static final int MAX_CPU_RECORDS = 2_048;
    private static final int DIRECT_SCHEDULE_FOCUSED = 2;
    private static final int DIRECT_SCHEDULE_BACKGROUND = 1;
    private static final int PARENT_SCHEDULE_FOCUSED = 2;
    private static final int PARENT_SCHEDULE_BACKGROUND = 1;

    private final RegionLodGraph graph = new RegionLodGraph(MAX_LEVEL);
    private final SurfaceRegionLodAtlas[] atlases =
            new SurfaceRegionLodAtlas[MAX_LEVEL + 1];
    private final long[] observedStorageGeneration =
            new long[MAX_LEVEL + 1];
    private final LinkedHashMap<RegionLodGraph.NodeKey, NodeRecord> records =
            new LinkedHashMap<>(128, 0.75f, true);
    private final Map<RegionLodGraph.NodeKey, PendingTask> pending =
            new HashMap<>();
    private final ConcurrentLinkedQueue<CompletedTask> completions =
            new ConcurrentLinkedQueue<>();

    private volatile VisibleView visibleView = VisibleView.none();
    private long lifecycleEpoch = 1L;

    private RegionSurfaceLodService() {
        java.util.Arrays.fill(observedStorageGeneration, Long.MIN_VALUE);
        for (int level = 0; level <= MAX_LEVEL; level++) {
            atlases[level] = new SurfaceRegionLodAtlas(level);
        }
    }

    public static RegionSurfaceLodService getInstance() {
        return INSTANCE;
    }

    public RegionLodGraph graph() {
        return graph;
    }

    /** Updates only priority metadata; no durable dirty state is discarded. */
    public void setVisibleView(RevisionStamp stamp, float logicalScale,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int focusPageX, int focusPageZ) {
        if (stamp == null || !stamp.isCurrent()) {
            visibleView = VisibleView.none();
            return;
        }
        int minRegionX = Math.floorDiv(Math.min(minPageX, maxPageX),
                MapPageLayout.PAGES_PER_REGION);
        int maxRegionX = Math.floorDiv(Math.max(minPageX, maxPageX),
                MapPageLayout.PAGES_PER_REGION);
        int minRegionZ = Math.floorDiv(Math.min(minPageZ, maxPageZ),
                MapPageLayout.PAGES_PER_REGION);
        int maxRegionZ = Math.floorDiv(Math.max(minPageZ, maxPageZ),
                MapPageLayout.PAGES_PER_REGION);
        int focusRegionX = Math.floorDiv(focusPageX,
                MapPageLayout.PAGES_PER_REGION);
        int focusRegionZ = Math.floorDiv(focusPageZ,
                MapPageLayout.PAGES_PER_REGION);
        visibleView = new VisibleView(stamp, logicalScale,
                minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                focusRegionX, focusRegionZ, focusPageX, focusPageZ);
    }

    /**
     * Bounded client/render-thread runner. Source capture is admitted here, while
     * all projection and parent reduction executes on shared workers.
     */
    public void publish(boolean focused, long deadlineNanos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> publish(focused, deadlineNanos));
            return;
        }
        synchronizeStorage();
        drainCompletions();
        scheduleDirectLevel0(focused, deadlineNanos);
        scheduleParents(focused, deadlineNanos);
        drainCompletions();
        publishPrepared(focused, deadlineNanos);
        trimCpuRecords();
    }

    public CaveAtlasRegion peek(int level, int nodeX, int nodeZ) {
        if (level < 0 || level > MAX_LEVEL) return null;
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return null;
        RegionLodGraph.NodeKey key = new RegionLodGraph.NodeKey(
                stamp.sessionId(), PROJECTION_SURFACE, level, nodeX, nodeZ);
        synchronized (records) {
            NodeRecord record = records.get(key);
            if (record == null || !record.initialized || record.slot < 0
                    || record.uploadedKnownMask == 0L) return null;
            MapResidencyManager.getInstance().touch(residencyKey(key));
            TileKey tileKey = tileKey(key);
            MapGpuPageTableService.Resolved resolved =
                    SurfaceResidencyService.getInstance().resolve(tileKey);
            if (resolved != null) {
                PageTableEntry entry = resolved.entry();
                return new CaveAtlasRegion(resolved.texture(), entry.sourceX(),
                        entry.sourceY(), entry.sourceSize(), entry.atlasSize(),
                        level, MapRegionLodPolicy.worldSize(level),
                        record.uploadedKnownMask, record.uploadedCompleteMask);
            }
            return atlases[level].region(record.slot,
                    record.uploadedKnownMask, record.uploadedCompleteMask);
        }
    }

    public boolean hasData(int level, int nodeX, int nodeZ) {
        if (level < 0 || level > MAX_LEVEL) return false;
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return false;
        RegionLodGraph.NodeKey key = new RegionLodGraph.NodeKey(
                stamp.sessionId(), PROJECTION_SURFACE, level, nodeX, nodeZ);
        synchronized (records) {
            NodeRecord record = records.get(key);
            return record != null && record.prepared != null
                    && record.prepared.knownMask() != 0L;
        }
    }

    /** Published level-0 replacement fence for exact page eviction. */
    public boolean coversExactPage(int globalPageX, int globalPageZ,
            long sourceRevision) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return false;
        int regionX = Math.floorDiv(globalPageX,
                MapPageLayout.PAGES_PER_REGION);
        int regionZ = Math.floorDiv(globalPageZ,
                MapPageLayout.PAGES_PER_REGION);
        int localX = Math.floorMod(globalPageX,
                MapPageLayout.PAGES_PER_REGION);
        int localZ = Math.floorMod(globalPageZ,
                MapPageLayout.PAGES_PER_REGION);
        int child = MapPageLayout.pageIndex(localX, localZ);
        RegionLodGraph.NodeKey key = new RegionLodGraph.NodeKey(
                stamp.sessionId(), PROJECTION_SURFACE, 0, regionX, regionZ);
        synchronized (records) {
            NodeRecord record = records.get(key);
            if (record == null || !record.initialized
                    || (record.uploadedKnownMask & (1L << child)) == 0L
                    || record.uploadedChildVersions == null) return false;
            return record.uploadedChildVersions[child]
                    >= Math.max(1L, sourceRevision);
        }
    }

    public void updateExactLeaf(RevisionStamp stamp, int regionX, int regionZ,
            int leafIndex, long leafVersion, boolean known,
            boolean complete, boolean resident) {
        graph.updateLeaf(stamp, PROJECTION_SURFACE, regionX, regionZ,
                leafIndex, leafVersion, known, complete, resident);
    }

    public void markExactLeafEvicted(RevisionStamp stamp, int regionX,
            int regionZ, int leafIndex) {
        if (stamp == null) return;
        graph.markExactLeafEvicted(stamp.sessionId(), PROJECTION_SURFACE,
                regionX, regionZ, leafIndex);
    }

    public RegionLodGraph.Summary summary() {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        return stamp == null ? RegionLodGraph.Summary.empty()
                : graph.summary(stamp.sessionId(), PROJECTION_SURFACE);
    }

    public void invalidate(RevisionStamp stamp) {
        if (stamp != null) graph.invalidate(stamp, PROJECTION_SURFACE);
    }

    public void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::clear);
            return;
        }
        lifecycleEpoch++;
        List<CompletableFuture<PreparedBranch>> cancel = new ArrayList<>();
        synchronized (pending) {
            for (PendingTask task : pending.values()) cancel.add(task.future);
            pending.clear();
        }
        for (CompletableFuture<PreparedBranch> future : cancel) {
            future.cancel(false);
        }
        completions.clear();
        synchronized (records) {
            for (NodeRecord record : records.values()) releaseRecord(record);
            records.clear();
        }
        graph.clear();
        visibleView = VisibleView.none();
        for (SurfaceRegionLodAtlas atlas : atlases) atlas.resetSlots();
    }

    private void scheduleDirectLevel0(boolean focused, long deadlineNanos) {
        VisibleView view = visibleView;
        // Direct region projection is the far-zoom coarse-first path. At close
        // zoom the exact pipeline and the legacy refinement adapter already own
        // visible quality, so capturing whole 512x512 regions here would only
        // recreate the M3 foreground spike that M4 is intended to remove.
        if (!view.current()
                || !MapRegionLodPolicy.directProjectionEnabled(view.scale)
                || System.nanoTime() >= deadlineNanos) return;
        int budget = focused ? DIRECT_SCHEDULE_FOCUSED
                : DIRECT_SCHEDULE_BACKGROUND;
        List<RegionCoordinate> candidates = visibleRegions(view);
        int scheduled = 0;
        for (RegionCoordinate candidate : candidates) {
            if (scheduled >= budget || System.nanoTime() >= deadlineNanos) break;
            RegionLodGraph.NodeKey key = new RegionLodGraph.NodeKey(
                    view.stamp.sessionId(), PROJECTION_SURFACE, 0,
                    candidate.x, candidate.z);
            if (isPending(key) || !shouldAttemptDirect(key)) continue;

            int focusLocalPageX = clamp(view.focusPageX
                    - candidate.x * MapPageLayout.PAGES_PER_REGION,
                    0, MapPageLayout.PAGES_PER_REGION - 1);
            int focusLocalPageZ = clamp(view.focusPageZ
                    - candidate.z * MapPageLayout.PAGES_PER_REGION,
                    0, MapPageLayout.PAGES_PER_REGION - 1);
            MapRequestLane lane = focused
                    ? MapRequestLane.FULLSCREEN : MapRequestLane.BACKGROUND;
            SurfaceRegionSourceDatabase.BatchSourcePlan source =
                    SurfaceRegionSourceDatabase.getInstance().captureBatchPlan(
                            view.stamp, candidate.x, candidate.z, 0, 0,
                            focusLocalPageX, focusLocalPageZ,
                            MapPageLayout.PAGES_PER_REGION,
                            MapPageLayout.PAGES_PER_REGION,
                            false, lane);
            if (source == null) continue;
            noteDirectSourceAttempt(key, source.sourceRevision());
            RegionLodGraph.NodeKey seeded = graph.requestRegion(view.stamp,
                    PROJECTION_SURFACE, candidate.x, candidate.z,
                    source.sourceRevision());
            RegionLodGraph.Lease lease = graph.tryBegin(seeded);
            if (lease == null) {
                source.close();
                continue;
            }
            MapStyleSnapshot style = MapTextureManager.getInstance()
                    .captureStyleSnapshot(source, view.stamp);
            long epoch = lifecycleEpoch;
            CompletableFuture<PreparedBranch> future =
                    MapTextureBuildWorker.tryBuildRegionLodLevel0(
                            lease, source, style,
                            () -> epoch == lifecycleEpoch
                                    && view.stamp.isCurrent(),
                            lane.executorPriority());
            if (future == null) {
                graph.defer(lease);
                source.close();
                continue;
            }
            registerPending(new PendingTask(lease, future, source, epoch));
            scheduled++;
        }
    }

    private void scheduleParents(boolean focused, long deadlineNanos) {
        VisibleView view = visibleView;
        if (!view.current() || System.nanoTime() >= deadlineNanos) return;
        int budget = focused ? PARENT_SCHEDULE_FOCUSED
                : PARENT_SCHEDULE_BACKGROUND;
        List<RegionLodGraph.NodeSnapshot> candidates = graph.snapshots(
                view.stamp.sessionId(), PROJECTION_SURFACE).stream()
                .filter(node -> node.key().level() > 0
                        && node.state() == RegionLodGraph.State.DIRTY
                        && node.knownMask() != 0L)
                .sorted(Comparator
                        .comparingInt((RegionLodGraph.NodeSnapshot node) ->
                                -node.key().level())
                        .thenComparingLong(node -> distanceToView(node.key(), view)))
                .toList();
        int scheduled = 0;
        for (RegionLodGraph.NodeSnapshot candidate : candidates) {
            if (scheduled >= budget || System.nanoTime() >= deadlineNanos) break;
            if (isPending(candidate.key())) continue;
            RegionLodGraph.Lease lease = graph.tryBegin(candidate.key());
            if (lease == null) continue;
            Collection<RegionLodDeriver.ChildSnapshot> children =
                    childSnapshots(lease);
            if (children == null) {
                graph.defer(lease);
                continue;
            }
            MapRequestLane lane = focused
                    ? MapRequestLane.FULLSCREEN : MapRequestLane.BACKGROUND;
            long epoch = lifecycleEpoch;
            CompletableFuture<PreparedBranch> future =
                    MapTextureBuildWorker.tryDeriveRegionLodParent(
                            lease, children,
                            () -> epoch == lifecycleEpoch
                                    && view.stamp.isCurrent(),
                            lane.executorPriority());
            if (future == null) {
                graph.defer(lease);
                continue;
            }
            registerPending(new PendingTask(lease, future, null, epoch));
            scheduled++;
        }
    }

    private Collection<RegionLodDeriver.ChildSnapshot> childSnapshots(
            RegionLodGraph.Lease lease) {
        List<RegionLodDeriver.ChildSnapshot> children = new ArrayList<>();
        long[] expected = lease.childVersionSums();
        int childLevel = lease.key().level() - 1;
        synchronized (records) {
            for (int child = 0; child < RegionLodGraph.CHILD_COUNT; child++) {
                if ((lease.knownMask() & (1L << child)) == 0L) continue;
                int childX = lease.key().nodeX() * 8 + (child & 7);
                int childZ = lease.key().nodeZ() * 8 + (child >>> 3);
                RegionLodGraph.NodeKey childKey = new RegionLodGraph.NodeKey(
                        lease.key().sessionId(), lease.key().projectionId(),
                        childLevel, childX, childZ);
                NodeRecord record = records.get(childKey);
                if (record == null || record.prepared == null
                        || record.prepared.knownMask() == 0L) return null;
                PreparedBranch prepared = record.prepared;
                if (!prepared.stamp().equals(lease.stamp())
                        || versionSum(prepared.childVersionSums())
                                != expected[child]) {
                    return null;
                }
                children.add(new RegionLodDeriver.ChildSnapshot(child,
                        expected[child], prepared.pixels(),
                        prepared.knownRows(), prepared.completeRows()));
            }
        }
        return children;
    }

    private void registerPending(PendingTask task) {
        synchronized (pending) {
            if (pending.putIfAbsent(task.lease.key(), task) != null) {
                task.future.cancel(false);
                if (task.source != null) task.source.close();
                graph.defer(task.lease);
                return;
            }
        }
        task.future.whenComplete((prepared, failure) -> {
            if (task.source != null) task.source.close();
            completions.add(new CompletedTask(task, prepared, failure));
        });
    }

    private void drainCompletions() {
        CompletedTask completed;
        while ((completed = completions.poll()) != null) {
            PendingTask task = completed.task;
            synchronized (pending) {
                pending.remove(task.lease.key(), task);
            }
            if (task.epoch != lifecycleEpoch
                    || !task.lease.stamp().isCurrent()) continue;
            if (completed.failure != null || completed.prepared == null
                    || !graph.markPrepared(task.lease, completed.prepared)) {
                graph.defer(task.lease);
                continue;
            }
            synchronized (records) {
                NodeRecord record = records.computeIfAbsent(
                        task.lease.key(), NodeRecord::new);
                record.prepared = completed.prepared;
                record.readyRevision = completed.prepared.revision();
            }
        }
    }

    private void publishPrepared(boolean focused, long deadlineNanos) {
        VisibleView view = visibleView;
        while (System.nanoTime() < deadlineNanos) {
            NodeRecord record = bestPreparedRecord(view);
            if (record == null) break;
            PreparedBranch prepared = record.prepared;
            if (prepared == null || !prepared.stamp().isCurrent()) {
                // Retire this CPU result before selecting again. Leaving its
                // ready revision active would make the selector pick the same
                // stale branch repeatedly until the per-frame deadline expires.
                record.readyRevision = record.uploadedRevision;
                continue;
            }
            RegionLodGraph.NodeSnapshot graphState = graph.snapshot(record.key);
            if (graphState == null
                    || prepared.revision() < graphState.targetRevision()) {
                // Do not spend GPU bandwidth on a worker result that became stale
                // while source/style revisions advanced. Durable dirty state will
                // re-admit a fresh build.
                record.readyRevision = record.uploadedRevision;
                continue;
            }
            long bytes = 66L * 66L * Integer.BYTES;
            MapRequestLane lane = isVisible(record.key, view)
                    ? MapRequestLane.FULLSCREEN : MapRequestLane.BACKGROUND;
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.BRANCH,
                    lane, focused, bytes)) break;
            if (record.slot < 0) {
                record.slot = atlases[record.key.level()].acquireSlot();
                if (record.slot < 0) {
                    evictOne(record.key.level());
                    record.slot = atlases[record.key.level()].acquireSlot();
                }
                if (record.slot < 0) break;
            }
            int[] pixels = prepared.pixels();
            long uploadStart = System.nanoTime();
            UploadCommand upload = new UploadCommand(tileKey(record.key),
                    prepared.stamp(), lane, (int) bytes, prepared.revision(),
                    null,
                    () -> atlases[record.key.level()].upload(record.slot, pixels,
                            prepared.dirtyMinX(), prepared.dirtyMinY(),
                            prepared.dirtyMaxX(), prepared.dirtyMaxY()),
                    null, null);
            SurfacePublicationService.getInstance().executeInline(upload);
            long uploadNanos = System.nanoTime() - uploadStart;
            MapGpuBudgetController.getInstance().record(
                    MapGpuBudgetController.UploadKind.BRANCH, uploadNanos);
            record.initialized = prepared.knownMask() != 0L;
            record.uploadedRevision = prepared.revision();
            record.uploadedKnownMask = prepared.knownMask();
            record.uploadedCompleteMask = prepared.completeMask();
            record.uploadedChildVersions = prepared.childVersionSums();
            if (!graph.markPublished(prepared)) {
                atlases[record.key.level()].releaseSlot(record.slot);
                record.slot = -1;
                record.initialized = false;
                record.readyRevision = record.uploadedRevision;
                continue;
            }
            CaveAtlasRegion publishedRegion = atlases[record.key.level()].region(
                    record.slot, record.uploadedKnownMask,
                    record.uploadedCompleteMask);
            if (publishedRegion != null) {
                int flags = PageTableEntry.FLAG_LINEAR;
                if (prepared.completeMask() == -1L) {
                    flags |= PageTableEntry.FLAG_COMPLETE;
                }
                SurfacePublicationService.getInstance().stage(tileKey(record.key),
                        publishedRegion.texture(), record.slot,
                        atlases[record.key.level()].storageGeneration(),
                        prepared.revision(), flags,
                        publishedRegion.sourceX(), publishedRegion.sourceY(),
                        publishedRegion.sourceSize(), publishedRegion.atlasSize());
            }
            String residentKey = residencyKey(record.key);
            MapResidencyManager.getInstance().register(residentKey,
                    MapResidencyManager.Kind.SURFACE_BRANCH, bytes,
                    () -> evictRecord(record.key));
            MapResidencyManager.getInstance().enforceBudget(
                    residentKey, lane);
            MapResidencyManager.getInstance().markCoverageChanged();
        }
    }

    /**
     * Select one upload candidate without allocating and sorting every prepared
     * region on every render frame. Far zoom can retain thousands of ready nodes
     * while the GPU ledger deliberately publishes only a few of them.
     */
    private NodeRecord bestPreparedRecord(VisibleView view) {
        NodeRecord best = null;
        synchronized (records) {
            for (NodeRecord candidate : records.values()) {
                if (candidate.prepared == null
                        || candidate.readyRevision <= candidate.uploadedRevision) {
                    continue;
                }
                if (best == null || comparePrepared(candidate, best, view) < 0) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static int comparePrepared(NodeRecord left, NodeRecord right,
            VisibleView view) {
        boolean leftVisible = isVisible(left.key, view);
        boolean rightVisible = isVisible(right.key, view);
        if (leftVisible != rightVisible) return leftVisible ? -1 : 1;
        int byLevel = Integer.compare(right.key.level(), left.key.level());
        if (byLevel != 0) return byLevel;
        return Long.compare(distanceToView(left.key, view),
                distanceToView(right.key, view));
    }

    private void synchronizeStorage() {
        for (int level = 0; level <= MAX_LEVEL; level++) {
            SurfaceRegionLodAtlas atlas = atlases[level];
            atlas.ensureInitialized();
            long generation = atlas.storageGeneration();
            if (observedStorageGeneration[level] != Long.MIN_VALUE
                    && observedStorageGeneration[level] != generation) {
                atlas.resetSlots();
                synchronized (records) {
                    for (NodeRecord record : records.values()) {
                        if (record.key.level() != level) continue;
                        record.initialized = false;
                        SurfacePublicationService.getInstance().remove(
                                tileKey(record.key));
                        record.slot = -1;
                        graph.markBranchEvicted(record.key,
                                record.uploadedRevision);
                    }
                }
                MapResidencyManager.getInstance().markTopologyChanged();
            }
            observedStorageGeneration[level] = generation;
        }
    }

    private boolean evictRecord(RegionLodGraph.NodeKey key) {
        synchronized (records) {
            NodeRecord record = records.get(key);
            if (record == null || !record.initialized || record.slot < 0) {
                return false;
            }
            atlases[key.level()].releaseSlot(record.slot);
            SurfacePublicationService.getInstance().remove(tileKey(key));
            record.slot = -1;
            record.initialized = false;
            graph.markBranchEvicted(key, record.uploadedRevision);
            MapResidencyManager.getInstance().markCoverageChanged();
            return true;
        }
    }

    private void evictOne(int level) {
        synchronized (records) {
            for (NodeRecord candidate : records.values()) {
                if (candidate.key.level() != level || !candidate.initialized
                        || candidate.slot < 0 || isVisible(candidate.key, visibleView)) {
                    continue;
                }
                evictRecord(candidate.key);
                return;
            }
        }
    }

    private void releaseRecord(NodeRecord record) {
        if (record.slot >= 0) {
            atlases[record.key.level()].releaseSlot(record.slot);
            SurfacePublicationService.getInstance().remove(tileKey(record.key));
            record.slot = -1;
        }
        MapResidencyManager.getInstance().remove(residencyKey(record.key));
    }

    private void trimCpuRecords() {
        synchronized (records) {
            if (records.size() <= MAX_CPU_RECORDS) return;
            var iterator = records.entrySet().iterator();
            while (records.size() > MAX_CPU_RECORDS && iterator.hasNext()) {
                NodeRecord record = iterator.next().getValue();
                if (record.initialized || isPending(record.key)
                        || isVisible(record.key, visibleView)) continue;
                releaseRecord(record);
                iterator.remove();
            }
        }
    }

    private boolean isPending(RegionLodGraph.NodeKey key) {
        synchronized (pending) {
            return pending.containsKey(key);
        }
    }

    private boolean shouldAttemptDirect(RegionLodGraph.NodeKey key) {
        long now = System.nanoTime();
        RegionLodGraph.NodeSnapshot snapshot = graph.snapshot(key);
        synchronized (records) {
            NodeRecord record = records.get(key);
            if (record == null) return true;
            boolean preparedCurrent = snapshot != null
                    && record.prepared != null
                    && record.readyRevision >= snapshot.targetRevision();
            boolean residentCurrent = snapshot != null && record.initialized
                    && record.uploadedRevision >= snapshot.targetRevision();
            long completeMask = residentCurrent ? record.uploadedCompleteMask
                    : preparedCurrent ? record.prepared.completeMask() : 0L;
            if ((preparedCurrent || residentCurrent) && completeMask == -1L) {
                return false;
            }
            long delay = record.unchangedSourceAttempts >= 2
                    ? 1_000_000_000L : 120_000_000L;
            return now - record.lastDirectAttemptNanos >= delay;
        }
    }

    private void noteDirectSourceAttempt(RegionLodGraph.NodeKey key,
            long sourceWatermark) {
        synchronized (records) {
            NodeRecord record = records.computeIfAbsent(key, NodeRecord::new);
            record.lastDirectAttemptNanos = System.nanoTime();
            if (record.lastSourceWatermark == sourceWatermark) {
                record.unchangedSourceAttempts++;
            } else {
                record.lastSourceWatermark = sourceWatermark;
                record.unchangedSourceAttempts = 0;
            }
        }
    }

    private static List<RegionCoordinate> visibleRegions(VisibleView view) {
        List<RegionCoordinate> result = new ArrayList<>();
        for (int z = view.minRegionZ; z <= view.maxRegionZ; z++) {
            for (int x = view.minRegionX; x <= view.maxRegionX; x++) {
                result.add(new RegionCoordinate(x, z));
            }
        }
        result.sort(Comparator.comparingLong(region -> {
            long dx = (long) region.x - view.focusRegionX;
            long dz = (long) region.z - view.focusRegionZ;
            return dx * dx + dz * dz;
        }));
        return result;
    }

    private static long distanceToView(RegionLodGraph.NodeKey key,
            VisibleView view) {
        if (view == null || !view.current()) return Long.MAX_VALUE;
        long span = pow8(key.level());
        long centerX = (long) key.nodeX() * span + span / 2L;
        long centerZ = (long) key.nodeZ() * span + span / 2L;
        long dx = centerX - view.focusRegionX;
        long dz = centerZ - view.focusRegionZ;
        return dx * dx + dz * dz;
    }

    private static boolean isVisible(RegionLodGraph.NodeKey key,
            VisibleView view) {
        if (view == null || !view.current()
                || key.sessionId() != view.stamp.sessionId()) return false;
        long span = pow8(key.level());
        long minX = (long) key.nodeX() * span;
        long minZ = (long) key.nodeZ() * span;
        long maxX = minX + span - 1L;
        long maxZ = minZ + span - 1L;
        return maxX >= view.minRegionX && minX <= view.maxRegionX
                && maxZ >= view.minRegionZ && minZ <= view.maxRegionZ;
    }


    private static long versionSum(long[] versions) {
        long sum = 0L;
        if (versions == null) return sum;
        for (long version : versions) {
            if (version > 0L && sum > Long.MAX_VALUE - version) {
                return Long.MAX_VALUE;
            }
            sum += version;
        }
        return sum;
    }

    private static long pow8(int level) {
        long value = 1L;
        for (int index = 0; index < level; index++) value *= 8L;
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static TileKey tileKey(RegionLodGraph.NodeKey key) {
        return new TileKey(key.sessionId(), key.projectionId(), key.level(),
                key.nodeX(), key.nodeZ(), TileKey.VARIANT_SURFACE_BRANCH);
    }

    private static String residencyKey(RegionLodGraph.NodeKey key) {
        return "surface-region-lod:" + key.sessionId() + ':'
                + key.level() + ':' + key.nodeX() + ':' + key.nodeZ();
    }

    private record RegionCoordinate(int x, int z) { }

    private record PendingTask(RegionLodGraph.Lease lease,
            CompletableFuture<PreparedBranch> future,
            SurfaceRegionSourceDatabase.BatchSourcePlan source,
            long epoch) { }

    private record CompletedTask(PendingTask task, PreparedBranch prepared,
            Throwable failure) { }

    private static final class NodeRecord {
        private final RegionLodGraph.NodeKey key;
        private PreparedBranch prepared;
        private long readyRevision;
        private long uploadedRevision;
        private long uploadedKnownMask;
        private long uploadedCompleteMask;
        private long[] uploadedChildVersions;
        private int slot = -1;
        private boolean initialized;
        private long lastDirectAttemptNanos;
        private long lastSourceWatermark;
        private int unchangedSourceAttempts;

        private NodeRecord(RegionLodGraph.NodeKey key) {
            this.key = key;
        }
    }

    private record VisibleView(RevisionStamp stamp, float scale,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int focusRegionX, int focusRegionZ,
            int focusPageX, int focusPageZ) {
        private static VisibleView none() {
            return new VisibleView(null, 1.0f, 0, -1, 0, -1,
                    0, 0, 0, 0);
        }

        private boolean current() {
            return stamp != null && stamp.isCurrent()
                    && minRegionX <= maxRegionX && minRegionZ <= maxRegionZ;
        }
    }
}
