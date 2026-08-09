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
 * projection, factor-2 parent derivation, CPU prepared revisions and GPU publication.
 * A level-0 node can therefore cover a 512x512 region before any of its 64 exact
 * pages has completed.</p>
 */
public final class RegionSurfaceLodService {
    public static final int PROJECTION_SURFACE = 0;
    private static final RegionSurfaceLodService INSTANCE =
            new RegionSurfaceLodService();
    private static final int MAX_LEVEL = MapRegionLodPolicy.MAX_LEVEL;
    private static final int MAX_CPU_RECORDS = 2_048;
    private static final int DIRECT_SCHEDULE_FOCUSED = 1;
    private static final int DIRECT_SCHEDULE_FAR_FOCUSED = 2;
    private static final int DIRECT_SCHEDULE_BACKGROUND = 1;
    private static final int EXACT_LEVEL0_SCHEDULE_FOCUSED = 4;
    private static final int EXACT_LEVEL0_SCHEDULE_FAR_FOCUSED = 8;
    private static final int EXACT_LEVEL0_SCHEDULE_BACKGROUND = 1;
    private static final int PARENT_SCHEDULE_FOCUSED = 2;
    private static final int PARENT_SCHEDULE_FAR_FOCUSED = 8;
    private static final int PARENT_SCHEDULE_BACKGROUND = 1;
    private static final int VISIBLE_REGION_LOAD_FOCUSED = 2;
    private static final int VISIBLE_REGION_LOAD_FAR_FOCUSED = 4;
    private static final int VISIBLE_REGION_LOAD_BACKGROUND = 1;
    private static final long DIRECT_BASE_RETRY_NANOS = 350_000_000L;
    private static final long DIRECT_NULL_RETRY_NANOS = 750_000_000L;
    private static final long DIRECT_UNCHANGED_RETRY_NANOS = 1_500_000_000L;

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
    private long visibleLoadSignature = Long.MIN_VALUE;
    private long[] visibleRegionPlan = new long[0];
    private int visibleLoadCursor;
    private int directScanCursor;

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

    /** Flushes region-LOD atlas slot quarantine after the page-table swap. */
    public void onPageTableFrameBoundary() {
        for (int level = 0; level <= MAX_LEVEL; level++) {
            atlases[level].onPageTableFrameBoundary();
        }
    }

    /**
     * Returns a bounded compatibility-LOD publication budget for the current
     * Surface viewport. The legacy factor-2 tree remains a bootstrap fallback,
     * but once region authority covers most target nodes it must stop competing
     * for the same branch GPU ledger every render frame.
     */
    public int legacyPublishBudget(boolean focused) {
        VisibleView view = visibleView;
        if (!view.current()) return focused ? 2 : 1;
        if (!view.coarseRequired) return focused ? 2 : 1;
        if (MapRegionLodPolicy.regionAuthorityOnly(view.scale)) return 0;
        int level = MapRegionLodPolicy.targetLevel(view.scale);
        int span = MapRegionLodPolicy.regionSpan(level);
        int minNodeX = Math.floorDiv(view.minRegionX, span);
        int maxNodeX = Math.floorDiv(view.maxRegionX, span);
        int minNodeZ = Math.floorDiv(view.minRegionZ, span);
        int maxNodeZ = Math.floorDiv(view.maxRegionZ, span);
        int total = Math.max(0, maxNodeX - minNodeX + 1)
                * Math.max(0, maxNodeZ - minNodeZ + 1);
        if (total == 0) return focused ? 2 : 1;
        int covered = 0;
        int complete = 0;
        synchronized (records) {
            for (int nodeZ = minNodeZ; nodeZ <= maxNodeZ; nodeZ++) {
                for (int nodeX = minNodeX; nodeX <= maxNodeX; nodeX++) {
                    RegionLodGraph.NodeKey key = new RegionLodGraph.NodeKey(
                            view.stamp.sessionId(), PROJECTION_SURFACE,
                            level, nodeX, nodeZ);
                    NodeRecord record = records.get(key);
                    if (record == null || !record.initialized
                            || record.uploadedKnownMask == 0L) continue;
                    covered++;
                    if (record.uploadedCompleteMask == -1L) complete++;
                }
            }
        }
        if (complete == total) return 0;
        if (covered * 4 >= total * 3) return 1;
        if (covered * 2 >= total) return focused ? 2 : 1;
        return focused ? 4 : 1;
    }

    /** Updates only priority metadata; no durable dirty state is discarded. */
    public void setVisibleView(RevisionStamp stamp, float logicalScale,
            int minPageX, int maxPageX, int minPageZ, int maxPageZ,
            int focusPageX, int focusPageZ, MapRequestLane lane) {
        if (stamp == null || !stamp.isCurrent()) {
            visibleView = VisibleView.none();
            visibleLoadSignature = Long.MIN_VALUE;
            visibleRegionPlan = new long[0];
            visibleLoadCursor = 0;
            directScanCursor = 0;
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
        /*
         * Both visible Surface owners feed this service. Keep one
         * cheap M4 level-0/root authority for every visible region at every zoom.
         * Xaero never allows a finer child to become the sole representation:
         * when a child is cold, the retained root sub-rectangle remains visible.
         * The old density gate disabled exactly that invariant at close zoom and
         * left teleport/atlas-eviction holes with no renderable underlay.
         */
        boolean coarseRequired = true;
        MapRequestLane visibleLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        VisibleView next = new VisibleView(stamp, logicalScale,
                minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                focusRegionX, focusRegionZ, focusPageX, focusPageZ,
                coarseRequired, visibleLane);
        long signature = visibleLoadSignature(next);
        if (signature != visibleLoadSignature) {
            visibleLoadSignature = signature;
            visibleRegionPlan = buildCenterOutRegionPlan(
                    minRegionX, maxRegionX, minRegionZ, maxRegionZ,
                    focusRegionX, focusRegionZ);
            visibleLoadCursor = 0;
            directScanCursor = 0;
        }
        visibleView = next;
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
        long now = System.nanoTime();
        long remaining = Math.max(0L, deadlineNanos - now);
        long firstPublishDeadline = now + Math.max(120_000L, remaining / 3L);
        // Prepared coarse coverage must be allowed onto the GPU before another
        // full-region source probe consumes the complete branch time slice.
        publishPrepared(focused, Math.min(deadlineNanos, firstPublishDeadline));
        if (System.nanoTime() < deadlineNanos) {
            long scheduleDeadline = now + Math.max(240_000L, remaining * 2L / 3L);
            admitVisibleRegionLoads(focused,
                    Math.min(deadlineNanos, scheduleDeadline));
            // Coarse source coverage is the first visible result. Exact-derived
            // replacement must not exhaust the tiny admission slice before this
            // fallback gets a chance to start.
            scheduleDirectLevel0(focused, Math.min(deadlineNanos, scheduleDeadline));
            scheduleExactLevel0(focused, Math.min(deadlineNanos, scheduleDeadline));
            scheduleParents(focused, Math.min(deadlineNanos, scheduleDeadline));
        }
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
                    || (record.uploadedCompleteMask & (1L << child)) == 0L
                    || record.uploadedChildVersions == null) return false;
            return record.uploadedChildVersions[child]
                    >= Math.max(1L, sourceRevision);
        }
    }

    public void updateExactLeaf(RevisionStamp stamp, int regionX, int regionZ,
            int leafIndex, long leafVersion, boolean known,
            boolean complete, boolean resident, int[] pixels,
            long[] knownRows) {
        RegionLodDeriver.ReducedChildSnapshot reduced = known
                ? RegionLodDeriver.reduceExactLeaf(leafIndex, leafVersion,
                        pixels, knownRows)
                : null;
        RegionLodGraph.NodeKey key = stamp == null ? null
                : new RegionLodGraph.NodeKey(stamp.sessionId(),
                        PROJECTION_SURFACE, 0, regionX, regionZ);
        if (key != null) {
            synchronized (records) {
                NodeRecord record = records.computeIfAbsent(key, NodeRecord::new);
                if (record.exactLeaves == null) {
                    record.exactLeaves = new RegionLodDeriver.ReducedChildSnapshot[
                            RegionLodGraph.CHILD_COUNT];
                }
                RegionLodDeriver.ReducedChildSnapshot previous =
                        record.exactLeaves[leafIndex];
                if (reduced != null
                        && (previous == null
                                || reduced.revision() >= previous.revision())) {
                    record.exactLeaves[leafIndex] = reduced;
                } else if (!known) {
                    record.exactLeaves[leafIndex] = null;
                }
            }
        }
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

    /**
     * Wakes the coarse hierarchy after a saved 512x512 Surface region has reached
     * authoritative CPU memory. Exact pages are still demand-driven; this signal
     * exists so a far-zoom viewport can obtain a stable underlay without first
     * building all sixty-four exact leaves.
     */
    public void onRegionSourceAvailable(int regionX, int regionZ) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null || !stamp.isCurrent()) return;
        MapManager.Region region = MapManager.getInstance().getRegion(
                regionX, regionZ, false);
        if (region == null || !region.isLoaded() || !region.hasAnyData()) return;
        onRegionSourceWarmed(regionX, regionZ);
        SurfaceRegionSourceDatabase.getInstance().warmLoadedRegion(
                stamp, regionX, regionZ, MapRequestLane.FULLSCREEN);
    }

    /** Wakes a direct level-0 retry after asynchronous source warming progressed. */
    public void onRegionSourceWarmed(int regionX, int regionZ) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null || !stamp.isCurrent()) return;
        MapManager.Region region = MapManager.getInstance().getRegion(
                regionX, regionZ, false);
        if (region == null || !region.isLoaded() || !region.hasAnyData()) return;
        RegionLodGraph.NodeKey key = new RegionLodGraph.NodeKey(
                stamp.sessionId(), PROJECTION_SURFACE, 0, regionX, regionZ);
        synchronized (records) {
            NodeRecord record = records.computeIfAbsent(key, NodeRecord::new);
            record.lastDirectAttemptNanos = 0L;
            record.directProbeMisses = 0;
            record.unchangedSourceAttempts = 0;
        }
        graph.requestRegion(stamp, PROJECTION_SURFACE,
                regionX, regionZ, region.sourceRevision());
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
        visibleLoadSignature = Long.MIN_VALUE;
        visibleRegionPlan = new long[0];
        visibleLoadCursor = 0;
        directScanCursor = 0;
        for (SurfaceRegionLodAtlas atlas : atlases) atlas.resetSlots();
    }

    /**
     * Admits saved Surface regions in deterministic screen order. Pass 12 could
     * render a region branch once its source happened to be resident, but neither
     * the exact demand path nor the region LOD path actually requested remote
     * .smdat files. The result was a small ribbon of already-live chunks surrounded
     * by permanent black space while workers and the GPU stayed idle.
     */
    private void admitVisibleRegionLoads(boolean focused, long deadlineNanos) {
        VisibleView view = visibleView;
        if (!view.current() || !view.coarseRequired
                || System.nanoTime() >= deadlineNanos) return;
        int total = visibleRegionPlan.length;
        if (total == 0) return;
        boolean visibleForeground = view.lane == MapRequestLane.MINIMAP
                || view.lane == MapRequestLane.FULLSCREEN;
        int budget = visibleForeground
                ? (MapRegionLodPolicy.regionAuthorityOnly(view.scale)
                        ? VISIBLE_REGION_LOAD_FAR_FOCUSED
                        : VISIBLE_REGION_LOAD_FOCUSED)
                : VISIBLE_REGION_LOAD_BACKGROUND;
        MapManager manager = MapManager.getInstance();
        int admitted = 0;
        int considered = 0;
        /*
         * This is a retrying visibility ring, not a one-shot discovery pass.
         * Source-read hints can be coalesced, deferred, or invalidated when the
         * exact viewport is rebased.  The previous monotonic cursor reached the
         * end once and then permanently stopped asking for any region which had
         * missed that first admission, leaving the region LOD underlay at one or
         * two islands while exact leaves appeared across the screen.
         */
        if (visibleLoadCursor >= total) visibleLoadCursor = 0;
        while (considered < total && admitted < budget
                && System.nanoTime() < deadlineNanos) {
            int ordinal = visibleLoadCursor++;
            if (visibleLoadCursor >= total) visibleLoadCursor = 0;
            considered++;
            long packed = visibleRegionPlan[ordinal];
            int regionX = unpackX(packed);
            int regionZ = unpackZ(packed);
            MapManager.Region resident = manager.getRegion(regionX, regionZ, false);
            if (resident != null && resident.isLoaded()) continue;
            if (!manager.hasRegionFile(regionX, regionZ)) continue;
            int priority = view.lane.priorityBase() + 220_000
                    - Math.min(200_000, ordinal * 128);
            MapProcessor.getInstance().enqueueSurfaceLoad(
                    regionX, regionZ, priority);
            admitted++;
        }
    }

    private void scheduleDirectLevel0(boolean focused, long deadlineNanos) {
        VisibleView view = visibleView;
        // Direct level-0 is the retained fullscreen root at every zoom. It is a
        // 64x64 coarse representation of one 512x512 region, not a 512x512 GPU
        // upload. Exact/factor-2 textures refine it without ever making already
        // known coverage disappear.
        if (!view.current() || !view.coarseRequired
                || System.nanoTime() >= deadlineNanos) return;
        boolean visibleForeground = view.lane == MapRequestLane.MINIMAP
                || view.lane == MapRequestLane.FULLSCREEN;
        int budget = visibleForeground
                ? (MapRegionLodPolicy.regionAuthorityOnly(view.scale)
                        ? DIRECT_SCHEDULE_FAR_FOCUSED
                        : DIRECT_SCHEDULE_FOCUSED)
                : DIRECT_SCHEDULE_BACKGROUND;
        int total = visibleRegionPlan.length;
        if (total == 0) return;
        if (directScanCursor >= total) directScanCursor = 0;
        int scheduled = 0;
        int considered = 0;
        while (considered < total && scheduled < budget
                && System.nanoTime() < deadlineNanos) {
            int ordinal = directScanCursor++;
            if (directScanCursor >= total) directScanCursor = 0;
            considered++;
            long packed = visibleRegionPlan[ordinal];
            int candidateX = unpackX(packed);
            int candidateZ = unpackZ(packed);
            RegionLodGraph.NodeKey key = new RegionLodGraph.NodeKey(
                    view.stamp.sessionId(), PROJECTION_SURFACE, 0,
                    candidateX, candidateZ);
            if (isPending(key) || !shouldAttemptDirect(key)) continue;
            MapManager.Region region = MapManager.getInstance().getRegion(
                    candidateX, candidateZ, false);
            if (region == null || !region.isLoaded()) continue;
            noteDirectProbe(key);
            MapRequestLane lane = view.lane;
            MapManager.RegionLodSnapshot source = region.snapshotLodLevel0();
            if (source == null || source.knownCells() == 0) {
                noteDirectProbeMiss(key);
                continue;
            }
            noteDirectSourceWatermark(key, source.sourceRevision());
            RegionLodGraph.NodeKey seeded = graph.requestRegion(view.stamp,
                    PROJECTION_SURFACE, candidateX, candidateZ,
                    source.sourceRevision());
            RegionLodGraph.Lease lease = graph.tryBegin(seeded);
            if (lease == null) continue;
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
                continue;
            }
            registerPending(new PendingTask(lease, future, null, epoch));
            scheduled++;
        }
    }

    private void scheduleExactLevel0(boolean focused, long deadlineNanos) {
        VisibleView view = visibleView;
        if (!view.current() || !view.coarseRequired
                || System.nanoTime() >= deadlineNanos) return;
        boolean visibleForeground = view.lane == MapRequestLane.MINIMAP
                || view.lane == MapRequestLane.FULLSCREEN;
        int budget = visibleForeground
                ? (MapRegionLodPolicy.regionAuthorityOnly(view.scale)
                        ? EXACT_LEVEL0_SCHEDULE_FAR_FOCUSED
                        : EXACT_LEVEL0_SCHEDULE_FOCUSED)
                : EXACT_LEVEL0_SCHEDULE_BACKGROUND;
        List<RegionLodGraph.Lease> leases = graph.claimLevel0RowMajor(
                view.stamp.sessionId(), PROJECTION_SURFACE,
                view.minRegionX, view.maxRegionX,
                view.minRegionZ, view.maxRegionZ, budget);
        for (RegionLodGraph.Lease lease : leases) {
            if (System.nanoTime() >= deadlineNanos) {
                graph.defer(lease);
                continue;
            }
            Collection<RegionLodDeriver.ReducedChildSnapshot> children =
                    exactLevel0Snapshots(lease);
            if (children == null) {
                graph.defer(lease);
                continue;
            }
            MapRequestLane lane = view.lane;
            long epoch = lifecycleEpoch;
            CompletableFuture<PreparedBranch> future =
                    MapTextureBuildWorker.tryDeriveRegionLodLevel0(
                            lease, children,
                            () -> epoch == lifecycleEpoch
                                    && view.stamp.isCurrent(),
                            lane.executorPriority());
            if (future == null) {
                graph.defer(lease);
                continue;
            }
            registerPending(new PendingTask(lease, future, null, epoch));
        }
    }

    private Collection<RegionLodDeriver.ReducedChildSnapshot>
            exactLevel0Snapshots(RegionLodGraph.Lease lease) {
        List<RegionLodDeriver.ReducedChildSnapshot> children = new ArrayList<>();
        long[] expected = lease.childVersionSums();
        synchronized (records) {
            NodeRecord record = records.get(lease.key());
            if (record == null || record.exactLeaves == null) return null;
            for (int child = 0; child < RegionLodGraph.CHILD_COUNT; child++) {
                if ((lease.knownMask() & (1L << child)) == 0L) continue;
                RegionLodDeriver.ReducedChildSnapshot summary =
                        record.exactLeaves[child];
                if (summary == null || summary.revision() != expected[child]) {
                    return null;
                }
                children.add(summary);
            }
        }
        return children;
    }

    private void scheduleParents(boolean focused, long deadlineNanos) {
        VisibleView view = visibleView;
        if (!view.current() || System.nanoTime() >= deadlineNanos) return;
        boolean regionOnly = MapRegionLodPolicy.regionAuthorityOnly(view.scale);
        int budget = focused
                ? (regionOnly ? PARENT_SCHEDULE_FAR_FOCUSED
                        : PARENT_SCHEDULE_FOCUSED)
                : PARENT_SCHEDULE_BACKGROUND;
        int scheduled = 0;

        // Build the density-selected visible parent first. This mirrors Xaero's
        // selected-level update slice and avoids a full graph snapshot + stream +
        // sort on every far-zoom frame.
        int targetLevel = MapRegionLodPolicy.targetLevel(view.scale);
        if (targetLevel > 0) {
            int span = MapRegionLodPolicy.regionSpan(targetLevel);
            List<RegionLodGraph.Lease> visible = graph.claimLevelRowMajor(
                    view.stamp.sessionId(), PROJECTION_SURFACE, targetLevel,
                    Math.floorDiv(view.minRegionX, span),
                    Math.floorDiv(view.maxRegionX, span),
                    Math.floorDiv(view.minRegionZ, span),
                    Math.floorDiv(view.maxRegionZ, span), budget);
            scheduled += scheduleParentLeases(visible, focused, deadlineNanos);
        }
        if (scheduled >= budget || System.nanoTime() >= deadlineNanos) return;

        // Remaining capacity maintains coarser ancestors and off-target durable
        // nodes. This path is intentionally small; the selected level above owns
        // foreground throughput.
        List<RegionLodGraph.NodeSnapshot> candidates = graph.snapshots(
                view.stamp.sessionId(), PROJECTION_SURFACE).stream()
                .filter(node -> node.key().level() > 0
                        && node.key().level() != targetLevel
                        && node.state() == RegionLodGraph.State.DIRTY
                        && node.knownMask() != 0L)
                .sorted(Comparator
                        .comparingInt((RegionLodGraph.NodeSnapshot node) ->
                                -node.key().level())
                        .thenComparingLong(node -> distanceToView(node.key(), view)))
                .limit(Math.max(0, budget - scheduled))
                .toList();
        List<RegionLodGraph.Lease> leases = new ArrayList<>(candidates.size());
        for (RegionLodGraph.NodeSnapshot candidate : candidates) {
            if (System.nanoTime() >= deadlineNanos) break;
            if (isPending(candidate.key())) continue;
            RegionLodGraph.Lease lease = graph.tryBegin(candidate.key());
            if (lease != null) leases.add(lease);
        }
        scheduleParentLeases(leases, focused, deadlineNanos);
    }

    private int scheduleParentLeases(Collection<RegionLodGraph.Lease> leases,
            boolean focused, long deadlineNanos) {
        if (leases == null || leases.isEmpty()) return 0;
        int scheduled = 0;
        for (RegionLodGraph.Lease lease : leases) {
            if (System.nanoTime() >= deadlineNanos) {
                graph.defer(lease);
                continue;
            }
            if (isPending(lease.key())) {
                graph.defer(lease);
                continue;
            }
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
                                    && lease.stamp().isCurrent(),
                            lane.executorPriority());
            if (future == null) {
                graph.defer(lease);
                continue;
            }
            registerPending(new PendingTask(lease, future, null, epoch));
            scheduled++;
        }
        return scheduled;
    }

    private Collection<RegionLodDeriver.ChildSnapshot> childSnapshots(
            RegionLodGraph.Lease lease) {
        List<RegionLodDeriver.ChildSnapshot> children = new ArrayList<>();
        long[] expected = lease.childVersionSums();
        int childLevel = lease.key().level() - 1;
        synchronized (records) {
            int childCount = RegionLodGraph.childCountForLevel(
                    lease.key().level());
            for (int child = 0; child < childCount; child++) {
                if ((lease.knownMask() & (1L << child)) == 0L) continue;
                int childX = lease.key().nodeX()
                        * RegionLodGraph.PARENT_CHILDREN_PER_AXIS
                        + child % RegionLodGraph.PARENT_CHILDREN_PER_AXIS;
                int childZ = lease.key().nodeZ()
                        * RegionLodGraph.PARENT_CHILDREN_PER_AXIS
                        + child / RegionLodGraph.PARENT_CHILDREN_PER_AXIS;
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
                    ? view.lane : MapRequestLane.BACKGROUND;
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.BRANCH,
                    lane, focused, bytes)) break;
            boolean wasInitialized = record.initialized && record.slot >= 0;
            long previousKnownMask = record.uploadedKnownMask;
            long previousCompleteMask = record.uploadedCompleteMask;
            if (record.slot < 0) {
                SurfaceRegionLodAtlas atlas = atlases[record.key.level()];
                record.slot = atlas.acquireSlot();
                if (record.slot < 0) {
                    if (atlas.hasQuarantinedSlots()) break;
                    evictOne(record.key.level());
                    // Fenced reuse intentionally waits until the next frame; do
                    // not evict another prepared region in the same publish pass.
                    break;
                }
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
                int flags = 0;
                if (prepared.completeMask()
                        == RegionLodGraph.completeMaskForLevel(
                                prepared.key().level())) {
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
            if (wasInitialized
                    && (previousKnownMask != record.uploadedKnownMask
                            || previousCompleteMask
                                    != record.uploadedCompleteMask)) {
                MapResidencyManager.getInstance().markCoverageChanged(
                        MapResidencyManager.Kind.SURFACE_BRANCH);
            }
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
        if (leftVisible && rightVisible) {
            int byZ = Integer.compare(left.key.nodeZ(), right.key.nodeZ());
            if (byZ != 0) return byZ;
            return Integer.compare(left.key.nodeX(), right.key.nodeX());
        }
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
            // Local atlas eviction and global-budget eviction share this handler.
            // Removing the residency entry keeps O(1) byte accounting and the
            // surface coverage revision coherent in both cases.
            MapResidencyManager.getInstance().remove(residencyKey(key));
            // Ancestor fallback quads can carry raw atlas UVs. Invalidate the
            // immutable render-plan topology before this slot is ever recycled.
            MapResidencyManager.getInstance().markTopologyChanged();
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
        boolean retiredGpuSlot = record.slot >= 0;
        if (retiredGpuSlot) {
            atlases[record.key.level()].releaseSlot(record.slot);
            SurfacePublicationService.getInstance().remove(tileKey(record.key));
            record.slot = -1;
        }
        MapResidencyManager.getInstance().remove(residencyKey(record.key));
        if (retiredGpuSlot) MapResidencyManager.getInstance().markTopologyChanged();
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
            if ((preparedCurrent || residentCurrent)
                    && completeMask == RegionLodGraph.completeMaskForLevel(
                            key.level())) {
                return false;
            }
            long delay = record.directProbeMisses > 0
                    ? DIRECT_NULL_RETRY_NANOS
                    : record.unchangedSourceAttempts >= 2
                            ? DIRECT_UNCHANGED_RETRY_NANOS
                            : DIRECT_BASE_RETRY_NANOS;
            return now - record.lastDirectAttemptNanos >= delay;
        }
    }

    private void noteDirectProbe(RegionLodGraph.NodeKey key) {
        synchronized (records) {
            NodeRecord record = records.computeIfAbsent(key, NodeRecord::new);
            record.lastDirectAttemptNanos = System.nanoTime();
        }
    }

    private void noteDirectProbeMiss(RegionLodGraph.NodeKey key) {
        synchronized (records) {
            NodeRecord record = records.computeIfAbsent(key, NodeRecord::new);
            record.directProbeMisses = Math.min(8, record.directProbeMisses + 1);
        }
    }

    private void noteDirectSourceWatermark(RegionLodGraph.NodeKey key,
            long sourceWatermark) {
        synchronized (records) {
            NodeRecord record = records.computeIfAbsent(key, NodeRecord::new);
            record.directProbeMisses = 0;
            if (record.lastSourceWatermark == sourceWatermark) {
                record.unchangedSourceAttempts++;
            } else {
                record.lastSourceWatermark = sourceWatermark;
                record.unchangedSourceAttempts = 0;
            }
        }
    }

    private static long[] buildCenterOutRegionPlan(int minX, int maxX,
            int minZ, int maxZ, int focusX, int focusZ) {
        int width = Math.max(0, maxX - minX + 1);
        int height = Math.max(0, maxZ - minZ + 1);
        int total = width * height;
        if (total == 0) return new long[0];
        int centerX = clamp(focusX, minX, maxX);
        int centerZ = clamp(focusZ, minZ, maxZ);
        int maximumRadius = Math.max(
                Math.max(centerX - minX, maxX - centerX),
                Math.max(centerZ - minZ, maxZ - centerZ));
        long[] result = new long[total];
        int ordinal = 0;
        for (int radius = 0; radius <= maximumRadius; radius++) {
            int left = centerX - radius;
            int right = centerX + radius;
            int top = centerZ - radius;
            int bottom = centerZ + radius;
            if (top >= minZ && top <= maxZ) {
                for (int x = Math.max(minX, left);
                        x <= Math.min(maxX, right); x++) {
                    result[ordinal++] = pack(x, top);
                }
            }
            if (radius == 0) continue;
            for (int z = Math.max(minZ, top + 1);
                    z <= Math.min(maxZ, bottom - 1); z++) {
                if (left >= minX && left <= maxX) {
                    result[ordinal++] = pack(left, z);
                }
                if (right != left && right >= minX && right <= maxX) {
                    result[ordinal++] = pack(right, z);
                }
            }
            if (bottom >= minZ && bottom <= maxZ) {
                for (int x = Math.max(minX, left);
                        x <= Math.min(maxX, right); x++) {
                    result[ordinal++] = pack(x, bottom);
                }
            }
        }
        return result;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }

    private static long distanceToView(RegionLodGraph.NodeKey key,
            VisibleView view) {
        if (view == null || !view.current()) return Long.MAX_VALUE;
        long span = pow2(key.level());
        long centerX = (long) key.nodeX() * span + span / 2L;
        long centerZ = (long) key.nodeZ() * span + span / 2L;
        long dx = centerX - view.focusRegionX;
        long dz = centerZ - view.focusRegionZ;
        return dx * dx + dz * dz;
    }

    private static long visibleLoadSignature(VisibleView view) {
        if (view == null || !view.current()) return Long.MIN_VALUE;
        long hash = view.stamp.sessionId();
        hash = hash * 31L + view.minRegionX;
        hash = hash * 31L + view.maxRegionX;
        hash = hash * 31L + view.minRegionZ;
        hash = hash * 31L + view.maxRegionZ;
        return hash;
    }

    private static boolean isVisible(RegionLodGraph.NodeKey key,
            VisibleView view) {
        if (view == null || !view.current()
                || key.sessionId() != view.stamp.sessionId()) return false;
        long span = pow2(key.level());
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

    private static long pow2(int level) {
        return 1L << Math.max(0, Math.min(30, level));
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
        private int directProbeMisses;
        private RegionLodDeriver.ReducedChildSnapshot[] exactLeaves;

        private NodeRecord(RegionLodGraph.NodeKey key) {
            this.key = key;
        }
    }

    private record VisibleView(RevisionStamp stamp, float scale,
            int minRegionX, int maxRegionX, int minRegionZ, int maxRegionZ,
            int focusRegionX, int focusRegionZ,
            int focusPageX, int focusPageZ, boolean coarseRequired,
            MapRequestLane lane) {
        private static VisibleView none() {
            return new VisibleView(null, 1.0f, 0, -1, 0, -1,
                    0, 0, 0, 0, false, MapRequestLane.BACKGROUND);
        }

        private boolean current() {
            return stamp != null && stamp.isCurrent()
                    && minRegionX <= maxRegionX && minRegionZ <= maxRegionZ;
        }
    }
}
