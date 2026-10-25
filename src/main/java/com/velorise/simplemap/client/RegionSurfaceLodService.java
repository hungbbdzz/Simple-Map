package com.velorise.simplemap.client;
import com.velorise.simplemap.client.MapWorkScheduler.MapTextureBuildWorker;

import java.util.Arrays;
import java.util.function.BooleanSupplier;
import com.velorise.simplemap.client.MapWorkScheduler.MapProcessor;
import com.velorise.simplemap.client.gpu.GpuPageTable.TileKey;
import com.velorise.simplemap.client.gpu.GpuPageTable.PageTableEntry;
import com.velorise.simplemap.client.gpu.GpuPageTable.MapUploadEngine.UploadCommand;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy.MapGpuBudgetController;
import com.velorise.simplemap.client.MapMemoryBudgetPolicy.MapResidencyManager;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.cave.CaveTextureAtlas.AtlasGutter;
import com.velorise.simplemap.client.MapWorkScheduler.MapTextureBuildWorker.MapStyleSnapshot;

import com.velorise.simplemap.client.gpu.GpuPageTable.MapUploadEngine;
import com.velorise.simplemap.client.cave.CaveTextureAtlas.CaveAtlasRegion;
import com.velorise.simplemap.client.RegionSurfaceLodService.RegionLodGraph.PreparedBranch;
import com.velorise.simplemap.client.RegionSurfaceLodService.RegionLodGraph.RegionLodDeriver;
import com.velorise.simplemap.client.RegionSurfaceLodService.RegionLodGraph;
import com.velorise.simplemap.client.gpu.GpuPageTable;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp;
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
    private static final int DIRECT_SCHEDULE_FOCUSED = 6;
    private static final int DIRECT_SCHEDULE_FAR_FOCUSED = 8;
    private static final int DIRECT_SCHEDULE_BACKGROUND = 2;
    private static final int EXACT_LEVEL0_SCHEDULE_FOCUSED = 4;
    private static final int EXACT_LEVEL0_SCHEDULE_FAR_FOCUSED = 8;
    private static final int EXACT_LEVEL0_SCHEDULE_BACKGROUND = 1;
    private static final int PARENT_SCHEDULE_FOCUSED = 4;
    private static final int PARENT_SCHEDULE_FAR_FOCUSED = 8;
    private static final int PARENT_SCHEDULE_BACKGROUND = 2;
    private static final int VISIBLE_REGION_LOAD_FOCUSED = 8;
    private static final int VISIBLE_REGION_LOAD_FAR_FOCUSED = 16;
    private static final int VISIBLE_REGION_LOAD_BACKGROUND = 2;
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
         * The LOD system never allows a finer child to become the sole representation:
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
        boolean styleRefresh = MapOverviewTextureManager.getInstance().isStyleRefreshActive();
        long firstPublishDeadline = styleRefresh
                ? deadlineNanos
                : now + Math.max(120_000L, remaining / 3L);
        // Prepared coarse coverage must be allowed onto the GPU before another
        // full-region source probe consumes the complete branch time slice.
        publishPrepared(focused, Math.min(deadlineNanos, firstPublishDeadline));
        if (System.nanoTime() < deadlineNanos) {
            long scheduleDeadline = styleRefresh
                    ? deadlineNanos
                    : now + Math.max(240_000L, remaining * 2L / 3L);
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
            GpuPageTable.MapGpuPageTableService.Resolved resolved =
                    GpuPageTable.MapGpuPageTableService.getInstance().resolve(tileKey);
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

    /** True when the 512x512 M4 level-0 region is GPU-resident at the current style generation. */
    public boolean hasCurrentStyleLevel0(int regionX, int regionZ) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return false;
        RegionLodGraph.NodeKey key = new RegionLodGraph.NodeKey(
                stamp.sessionId(), PROJECTION_SURFACE, 0, regionX, regionZ);
        synchronized (records) {
            NodeRecord record = records.get(key);
            return record != null && record.initialized && record.slot >= 0
                    && record.uploadedKnownMask != 0L
                    && record.uploadedStyleGeneration == stamp.styleGeneration();
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
        if (stamp == null) return;
        lifecycleEpoch++;
        List<CompletableFuture<PreparedBranch>> cancel = new ArrayList<>();
        synchronized (pending) {
            for (PendingTask task : pending.values()) {
                cancel.add(task.future);
                if (task.source != null) task.source.close();
                graph.defer(task.lease);
            }
            pending.clear();
        }
        for (CompletableFuture<PreparedBranch> future : cancel) {
            future.cancel(false);
        }
        completions.clear();
        synchronized (records) {
            for (NodeRecord record : records.values()) {
                record.exactLeaves = null;
                record.lastDirectAttemptNanos = 0L;
                record.lastSourceWatermark = 0L;
                record.unchangedSourceAttempts = 0;
                record.directProbeMisses = 0;
            }
        }
        directScanCursor = 0;
        visibleLoadCursor = 0;
        graph.invalidate(stamp, PROJECTION_SURFACE);
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
                ? (MapRegionLodPolicy.directProjectionEnabled(view.scale)
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
                ? (MapOverviewTextureManager.getInstance().isStyleRefreshActive()
                        ? 16
                        : (MapRegionLodPolicy.directProjectionEnabled(view.scale)
                                ? DIRECT_SCHEDULE_FAR_FOCUSED
                                : DIRECT_SCHEDULE_FOCUSED))
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
                synchronized (records) {
                    NodeRecord record = records.get(key);
                    if (record != null) record.lastDirectAttemptNanos = 0L;
                }
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

        // Build the density-selected visible parent first. This mirrors the
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
                if (!prepared.stamp().equals(lease.stamp())) {
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
            boolean expandsCoverage = !record.initialized
                    || record.slot < 0
                    || (prepared.knownMask() & ~record.uploadedKnownMask) != 0L
                    || (prepared.completeMask() & ~record.uploadedCompleteMask) != 0L;
            if (graphState == null
                    || (prepared.revision() < graphState.targetRevision()
                            && !expandsCoverage)) {
                // Do not spend GPU bandwidth on a worker result that became stale
                // while source/style revisions advanced unless it provides initial
                // or broader region coverage. Durable dirty state will re-admit a
                // fresh refinement build.
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
            MapUploadEngine.getInstance().executeInline(upload);
            long uploadNanos = System.nanoTime() - uploadStart;
            MapGpuBudgetController.getInstance().record(
                    MapGpuBudgetController.UploadKind.BRANCH, uploadNanos);
            record.initialized = prepared.knownMask() != 0L;
            record.uploadedRevision = prepared.revision();
            record.uploadedStyleGeneration = prepared.stamp().styleGeneration();
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
                GpuPageTable.MapGpuPageTableService.getInstance().stage(tileKey(record.key),
                        publishedRegion.texture(), record.slot,
                        atlases[record.key.level()].storageGeneration(),
                        prepared.revision(), flags,
                        publishedRegion.sourceX(), publishedRegion.sourceY(),
                        publishedRegion.sourceSize(), publishedRegion.atlasSize());
                if (record.key.level() == 0) {
                    MapTextureManager.getInstance().onRegionLodStylePublished(
                            record.key.nodeX(), record.key.nodeZ(), prepared.stamp());
                }
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
                        GpuPageTable.MapGpuPageTableService.getInstance().remove(
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
            GpuPageTable.MapGpuPageTableService.getInstance().remove(tileKey(key));
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
            GpuPageTable.MapGpuPageTableService.getInstance().remove(tileKey(record.key));
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
        private long uploadedStyleGeneration = Long.MIN_VALUE;
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

    public static final class SurfaceRegionLodAtlas {
        public static final int SIZE = 64;
        private static final int PITCH = AtlasGutter.pitch(SIZE);

        private final int level;
        private final int slotColumns;
        private final int slotCount;
        private final boolean[] allocated;
        private final java.util.ArrayDeque<Integer> free;
        private final java.util.ArrayDeque<Integer> quarantined = new java.util.ArrayDeque<>();
        private final com.velorise.simplemap.client.gpu.CaveAtlasPboUploader uploader = new com.velorise.simplemap.client.gpu.CaveAtlasPboUploader();
        private final int[] gutteredUpload = new int[PITCH * PITCH];

        private CaveAtlasTexture texture;
        private net.minecraft.resources.ResourceLocation location;
        private int textureId;
        private boolean initialized;
        private long storageGeneration;

        public SurfaceRegionLodAtlas(int level) {
            if (level < 0) throw new IllegalArgumentException("level");
            this.level = level;
            this.slotColumns = level == 0
                    ? MapMemoryBudgetPolicy.branchLowColumns()
                    : level == 1
                    ? Math.max(16, MapMemoryBudgetPolicy.branchHighColumns())
                    : MapMemoryBudgetPolicy.branchHighColumns();
            this.slotCount = slotColumns * slotColumns;
            this.allocated = new boolean[slotCount];
            this.free = new java.util.ArrayDeque<>(slotCount);
            refill();
        }

        public int level() { return level; }
        public int slotCount() { return slotCount; }

        public void ensureInitialized() {
            initialize();
        }

        public long storageGeneration() {
            return storageGeneration;
        }

        public boolean hasQuarantinedSlots() {
            return !quarantined.isEmpty();
        }

        public int acquireSlot() {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThreadOrInit();
            initialize();
            Integer slot = free.pollFirst();
            if (slot == null) return -1;
            allocated[slot] = true;
            return slot;
        }

        public void releaseSlot(int slot) {
            if (slot < 0 || slot >= slotCount || !allocated[slot]) return;
            if (!quarantined.contains(slot)) quarantined.addLast(slot);
        }

        public void onPageTableFrameBoundary() {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThreadOrInit();
            while (!quarantined.isEmpty()) {
                int slot = quarantined.removeFirst();
                if (slot < 0 || slot >= slotCount || !allocated[slot]) continue;
                allocated[slot] = false;
                free.addLast(slot);
            }
        }

        public void resetSlots() {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThreadOrInit();
            java.util.Arrays.fill(allocated, false);
            quarantined.clear();
            refill();
        }

        public CaveAtlasRegion region(int slot, long knownMask, long completeMask) {
            if (!initialized || slot < 0 || slot >= slotCount || !allocated[slot]) {
                return null;
            }
            int atlasSize = PITCH * slotColumns;
            int sourceX = (slot % slotColumns) * PITCH + AtlasGutter.SIZE;
            int sourceY = (slot / slotColumns) * PITCH + AtlasGutter.SIZE;
            int worldSize = MapRegionLodPolicy.worldSize(level);
            return new CaveAtlasRegion(location, sourceX, sourceY, SIZE, atlasSize,
                    level, worldSize, knownMask, completeMask);
        }

        public void upload(int slot, int[] pixels,
                int dirtyMinX, int dirtyMinY, int dirtyMaxX, int dirtyMaxY) {
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThreadOrInit();
            initialize();
            if (slot < 0 || slot >= slotCount || !allocated[slot]
                    || pixels == null || pixels.length < SIZE * SIZE) return;
            int minX = Math.max(0, Math.min(SIZE - 1, dirtyMinX));
            int minY = Math.max(0, Math.min(SIZE - 1, dirtyMinY));
            int maxX = Math.max(minX, Math.min(SIZE - 1, dirtyMaxX));
            int maxY = Math.max(minY, Math.min(SIZE - 1, dirtyMaxY));

            AtlasGutter.copyOnePixelBorder(pixels, SIZE, gutteredUpload);
            int atlasX = (slot % slotColumns) * PITCH;
            int atlasY = (slot / slotColumns) * PITCH;
            uploader.upload(textureId, atlasX, atlasY, PITCH, PITCH,
                    gutteredUpload, PITCH, 0, 0);
        }

        private void initialize() {
            if (initialized) return;
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThreadOrInit();
            int atlasSize = PITCH * slotColumns;
            location = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "simplemap", "surface_atlas/region_l" + level + "_64");
            texture = new CaveAtlasTexture(atlasSize, true,
                    () -> storageGeneration++);
            net.minecraft.client.Minecraft.getInstance().getTextureManager().register(location, texture);
            texture.allocateStorage();
            MapMemoryBudgetPolicy.registerAtlasAllocation(
                    "surface_region_lod_l" + level,
                    (long) atlasSize * atlasSize * Integer.BYTES);
            textureId = texture.getId();
            initialized = true;
        }

        private void refill() {
            free.clear();
            for (int slot = 0; slot < slotCount; slot++) free.addLast(slot);
        }
    }

    /**
     * Shared density policy for the M4 region hierarchy.
     *
     * <p>Level 0 is one 512x512 source region represented by a 64x64 fallback
     * texture (8 blocks/texel). Higher levels follow the same factor-2 hierarchy as
     * The {@code textureLevel}: every level groups 2x2 children and doubles the
     * blocks represented by one texel. The exact path remains chunk-progressive;
     * this hierarchy is only stable coverage while sharper children arrive.</p>
     */
    public static final class MapRegionLodPolicy {
        public static final int MAX_LEVEL = 3;
        public static final float DIRECT_PROJECTION_THRESHOLD = 0.50f;
        /** At this density the level-3 region texel (64 blocks) is screen-correct. */
        public static final float REGION_ONLY_THRESHOLD = 0.015625f;

        private MapRegionLodPolicy() { }

        public static boolean directProjectionEnabled(float logicalScale) {
            return Float.isFinite(logicalScale)
                    && logicalScale > 0.0f
                    && logicalScale < DIRECT_PROJECTION_THRESHOLD;
        }

        /**
         * Coarse region coverage is also required when the visible exact-leaf set is
         * larger than the safe Surface atlas working set. This can happen at a scale
         * slightly above 0.50x on a large fullscreen window: density alone suggests
         * exact L0, but hundreds of leaves cannot remain resident simultaneously.
         * Building a stable 512x512 underlay is cheaper than cycling exact pages.
         */
        public static boolean directProjectionEnabled(float logicalScale,
                int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
            if (directProjectionEnabled(logicalScale)) return true;
            long pagesWide = Math.max(1L, (long) maxPageX - minPageX + 1L);
            long pagesHigh = Math.max(1L, (long) maxPageZ - minPageZ + 1L);
            long exactCapacity = (long) MapMemoryBudgetPolicy.surfaceLeafColumns()
                    * MapMemoryBudgetPolicy.surfaceLeafColumns();
            long safeWorkingSet = Math.max(64L, exactCapacity * 3L / 4L);
            return pagesWide * pagesHigh > safeWorkingSet;
        }


        /**
         * Extreme far zoom uses the level-3 region hierarchy as visual authority.
         * Exact chunk work may still progress in the background; this predicate only
         * prevents invisible fine quads from bloating the render plan.
         */
        public static boolean regionAuthorityOnly(float logicalScale) {
            return Float.isFinite(logicalScale)
                    && logicalScale > 0.0f
                    && logicalScale < REGION_ONLY_THRESHOLD;
        }

        public static int targetLevel(float logicalScale) {
            if (logicalScale >= 0.125f) return 0;
            if (logicalScale >= 0.0625f) return 1;
            if (logicalScale >= 0.03125f) return 2;
            return MAX_LEVEL;
        }

        public static int regionSpan(int level) {
            int clamped = Math.max(0, Math.min(MAX_LEVEL, level));
            return 1 << clamped;
        }

        public static int worldSize(int level) {
            long size = 512L * regionSpan(level);
            return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
        }
    }


    /**
 * Durable region-centric LOD authority introduced for M4.
 *
 * <p>Level 0 represents one 512x512 surface region and owns 64 exact-leaf
 * versions. Every higher level groups 2x2 direct children, matching the
 * screen-space texture-level hierarchy. Queue entries are
 * disposable hints: dirty child masks, version sums and publication state remain
 * in this graph and can reconstruct work after queue reset or admission denial.</p>
 */
public static final class RegionLodGraph {
    /** Exact 64x64 leaves contained by one 512x512 level-0 source region. */
    public static final int LEAF_CHILDREN_PER_AXIS = 8;
    /** Retained array width; parent levels use only their first four entries. */
    public static final int CHILD_COUNT = LEAF_CHILDREN_PER_AXIS
            * LEAF_CHILDREN_PER_AXIS;
    public static final int PARENT_CHILDREN_PER_AXIS = 2;
    public static final int PARENT_CHILD_COUNT = 4;
    public static final long ALL_LEAF_CHILDREN = -1L;
    public static final long ALL_PARENT_CHILDREN = 0xFL;
    /** Compatibility alias for level-0 callers. */
    public static final long ALL_CHILDREN = ALL_LEAF_CHILDREN;
    public static final int DEFAULT_MAX_LEVEL = 3;

    public enum State {
        CLEAN,
        DIRTY,
        RUNNING,
        PREPARED,
        PUBLISHED,
        CANCELLED
    }

    public record NodeKey(long sessionId, int projectionId, int level,
            int nodeX, int nodeZ) {
        public NodeKey {
            if (sessionId <= 0L) throw new IllegalArgumentException("sessionId");
            if (level < 0) throw new IllegalArgumentException("level");
        }
    }

    public record Lease(NodeKey key, RevisionStamp stamp, long revision,
            long dirtyChildMask, long knownMask, long completeMask,
            long[] childVersionSums) {
        public Lease {
            childVersionSums = Arrays.copyOf(childVersionSums,
                    childVersionSums.length);
        }
        @Override public long[] childVersionSums() {
            return Arrays.copyOf(childVersionSums, childVersionSums.length);
        }
    }

    public record NodeSnapshot(NodeKey key, RevisionStamp stamp,
            long targetRevision, long runningRevision,
            long preparedRevision, long publishedRevision,
            long aggregateVersionSum, long dirtyChildMask,
            long knownMask, long completeMask, boolean gpuResident,
            State state, long[] childVersionSums) {
        public NodeSnapshot {
            childVersionSums = Arrays.copyOf(childVersionSums,
                    childVersionSums.length);
        }
        @Override public long[] childVersionSums() {
            return Arrays.copyOf(childVersionSums, childVersionSums.length);
        }
    }


    public record Summary(int nodes, int dirty, int running, int prepared,
            int published, int resident, int level0Nodes, int coarseNodes) {
        public static Summary empty() {
            return new Summary(0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private final int maxLevel;
    private final Map<NodeKey, NodeRecord> nodes = new HashMap<>();

    public RegionLodGraph() {
        this(DEFAULT_MAX_LEVEL);
    }

    public RegionLodGraph(int maxLevel) {
        this.maxLevel = Math.max(0, maxLevel);
    }

    public synchronized void updateLeaf(RevisionStamp stamp,
            int projectionId, int regionX, int regionZ, int leafIndex,
            long leafVersion, boolean known, boolean complete,
            boolean gpuResident) {
        if (stamp == null || leafIndex < 0 || leafIndex >= CHILD_COUNT) return;
        NodeKey key = new NodeKey(stamp.sessionId(), projectionId, 0,
                regionX, regionZ);
        NodeRecord node = node(key, stamp);
        long normalized = Math.max(1L, leafVersion);
        long bit = 1L << leafIndex;
        long previous = node.childVersionSums[leafIndex];
        boolean unpublishedDirectSeed = (node.directSeedMask & bit) != 0L
                && node.publishedRevision == 0L
                && node.preparedRevision == 0L;
        // A direct 512x512 source probe seeds every child with one region-wide
        // watermark before exact 64x64 leaves exist. That watermark and a leaf
        // revision are different authorities, so an exact leaf may replace an
        // unpublished seed even when its local revision number is lower. Once a
        // coarse branch has been prepared/published, the old monotonic fence is
        // retained and a late exact callback cannot roll durable coverage back.
        if (normalized < previous && !unpublishedDirectSeed) return;
        node.directSeedMask &= ~bit;
        node.childVersionSums[leafIndex] = normalized;
        node.aggregateVersionSum = safeAdd(node.aggregateVersionSum,
                normalized - previous);
        node.knownMask = setBit(node.knownMask, bit, known);
        node.completeMask = setBit(node.completeMask, bit, complete);
        node.gpuChildMask = setBit(node.gpuChildMask, bit, gpuResident);
        node.dirtyChildMask |= bit;
        node.targetRevision = revisionFor(node);
        markDirty(node);
        propagate(node);
    }


    /**
     * Seeds or refreshes one level-0 region directly from the source database.
     * All 64 leaf slots share the region source watermark until exact leaf
     * revisions arrive and refine them independently. This allows coarse-first
     * work to exist before any exact texture has been built.
     */
    public synchronized NodeKey requestRegion(RevisionStamp stamp,
            int projectionId, int regionX, int regionZ,
            long sourceWatermark) {
        if (stamp == null) return null;
        NodeKey key = new NodeKey(stamp.sessionId(), projectionId, 0,
                regionX, regionZ);
        NodeRecord node = node(key, stamp);
        long normalized = Math.max(1L, sourceWatermark);
        boolean changed = false;
        for (int child = 0; child < CHILD_COUNT; child++) {
            long bit = 1L << child;
            // Exact-leaf authority must not be overwritten by an unrelated
            // region-wide source watermark. Direct seeding owns only empty or
            // already-direct-seeded child slots; exact publication refines them
            // independently and can then drive the level-0 compositor.
            boolean directOwned = (node.directSeedMask & bit) != 0L;
            if (!directOwned && node.childVersionSums[child] > 0L) continue;
            if (node.childVersionSums[child] >= normalized) {
                node.directSeedMask |= bit;
                continue;
            }
            node.childVersionSums[child] = normalized;
            node.directSeedMask |= bit;
            node.dirtyChildMask |= bit;
            changed = true;
        }
        if (changed || node.publishedRevision == 0L) {
            recomputeAggregate(node);
            if (node.dirtyChildMask == 0L) {
                node.dirtyChildMask = completeMaskForLevel(0);
            }
            node.targetRevision = revisionFor(node);
            markDirty(node);
            propagate(node);
        }
        return key;
    }

    /** Returns a running lease to durable DIRTY state after admission/source wait. */
    public synchronized void defer(Lease lease) {
        if (lease == null) return;
        NodeRecord node = nodes.get(lease.key());
        if (node == null || node.cancelled) return;
        if (node.runningRevision == lease.revision()) node.runningRevision = 0L;
        node.dirtyChildMask |= lease.dirtyChildMask();
        node.state = State.DIRTY;
    }

    public int maxLevel() {
        return maxLevel;
    }

    public synchronized void markLeafDirty(RevisionStamp stamp,
            int projectionId, int regionX, int regionZ, int leafIndex,
            long targetVersion) {
        if (stamp == null || leafIndex < 0 || leafIndex >= CHILD_COUNT) return;
        NodeRecord node = node(new NodeKey(stamp.sessionId(), projectionId, 0,
                regionX, regionZ), stamp);
        long bit = 1L << leafIndex;
        node.childVersionSums[leafIndex] = Math.max(
                node.childVersionSums[leafIndex], Math.max(1L, targetVersion));
        recomputeAggregate(node);
        node.dirtyChildMask |= bit;
        node.targetRevision = revisionFor(node);
        markDirty(node);
        propagate(node);
    }

    public synchronized void markExactLeafEvicted(long sessionId,
            int projectionId, int regionX, int regionZ, int leafIndex) {
        NodeRecord node = nodes.get(new NodeKey(sessionId, projectionId, 0,
                regionX, regionZ));
        if (node == null || leafIndex < 0 || leafIndex >= CHILD_COUNT) return;
        node.gpuChildMask &= ~(1L << leafIndex);
    }

    public synchronized Lease tryBegin(NodeKey key) {
        NodeRecord node = nodes.get(key);
        if (node == null || node.cancelled || node.state != State.DIRTY
                || node.dirtyChildMask == 0L) return null;
        node.state = State.RUNNING;
        node.runningRevision = node.targetRevision;
        return new Lease(key, node.stamp, node.runningRevision,
                node.dirtyChildMask, node.knownMask, node.completeMask,
                node.childVersionSums);
    }

    /**
     * Claims visible level-0 regions in deterministic top-to-bottom order without
     * materializing a snapshot and sorting every graph node on each render frame.
     */
    public synchronized List<Lease> claimLevel0RowMajor(long sessionId,
            int projectionId, int minNodeX, int maxNodeX,
            int minNodeZ, int maxNodeZ, int limit) {
        return claimLevelRowMajor(sessionId, projectionId, 0,
                minNodeX, maxNodeX, minNodeZ, maxNodeZ, limit);
    }

    /**
     * Claims one visible hierarchy level in deterministic screen order. Far zoom
     * uses this for the target region level instead of copying every graph node,
     * sorting snapshots and then discarding almost all of them each frame.
     */
    public synchronized List<Lease> claimLevelRowMajor(long sessionId,
            int projectionId, int level, int minNodeX, int maxNodeX,
            int minNodeZ, int maxNodeZ, int limit) {
        if (level < 0 || level > maxLevel || limit <= 0
                || minNodeX > maxNodeX || minNodeZ > maxNodeZ) {
            return List.of();
        }
        List<Lease> leases = new ArrayList<>(Math.min(limit, 8));
        for (int nodeZ = minNodeZ; nodeZ <= maxNodeZ
                && leases.size() < limit; nodeZ++) {
            for (int nodeX = minNodeX; nodeX <= maxNodeX
                    && leases.size() < limit; nodeX++) {
                NodeKey key = new NodeKey(sessionId, projectionId, level,
                        nodeX, nodeZ);
                NodeRecord node = nodes.get(key);
                if (node == null || node.cancelled
                        || node.state != State.DIRTY
                        || node.dirtyChildMask == 0L
                        || node.knownMask == 0L
                        || (level == 0 && node.directSeedMask != 0L)) continue;
                Lease lease = tryBegin(key);
                if (lease != null) leases.add(lease);
            }
        }
        return List.copyOf(leases);
    }

    public synchronized List<Lease> claimCoarseFirst(long sessionId,
            int projectionId, int limit) {
        if (limit <= 0) return List.of();
        List<NodeRecord> candidates = new ArrayList<>();
        for (NodeRecord node : nodes.values()) {
            if (node.key.sessionId() != sessionId
                    || node.key.projectionId() != projectionId
                    || node.cancelled || node.state != State.DIRTY
                    || node.dirtyChildMask == 0L) continue;
            candidates.add(node);
        }
        candidates.sort(Comparator
                .comparing((NodeRecord node) -> node.gpuResident)
                .thenComparing((NodeRecord node) -> -node.key.level())
                .thenComparingLong(node -> node.publishedRevision)
                .thenComparingInt(node -> node.key.nodeZ())
                .thenComparingInt(node -> node.key.nodeX()));
        List<Lease> leases = new ArrayList<>(Math.min(limit, candidates.size()));
        for (NodeRecord node : candidates) {
            if (leases.size() >= limit) break;
            Lease lease = tryBegin(node.key);
            if (lease != null) leases.add(lease);
        }
        return List.copyOf(leases);
    }

    public synchronized boolean markPrepared(Lease lease,
            PreparedBranch prepared) {
        if (!matches(lease, prepared)) return false;
        NodeRecord node = nodes.get(lease.key());
        if (node == null || node.cancelled) return false;
        node.runningRevision = 0L;
        node.preparedRevision = Math.max(node.preparedRevision,
                prepared.revision());
        boolean current = lease.revision() == node.targetRevision
                && Arrays.equals(lease.childVersionSums(),
                        node.childVersionSums);
        boolean expandsCoverage = (prepared.knownMask() & ~node.knownMask) != 0L
                || (prepared.completeMask() & ~node.completeMask) != 0L;
        if (current) {
            node.knownMask = prepared.knownMask();
            node.completeMask = prepared.completeMask();
            node.dirtyChildMask &= ~lease.dirtyChildMask();
        } else if (expandsCoverage) {
            node.knownMask |= prepared.knownMask();
            node.completeMask |= prepared.completeMask();
        }
        node.state = node.dirtyChildMask == 0L
                && node.preparedRevision >= node.targetRevision
                ? State.PREPARED : State.DIRTY;
        if (current || expandsCoverage) propagate(node);
        return true;
    }

    public synchronized boolean markPublished(PreparedBranch prepared) {
        if (prepared == null) return false;
        NodeRecord node = nodes.get(prepared.key());
        if (node == null || node.cancelled
                || !sameStamp(node.stamp, prepared.stamp())) return false;
        boolean current = prepared.revision() >= node.targetRevision
                && Arrays.equals(prepared.childVersionSums(),
                        node.childVersionSums);
        boolean expandsCoverage = !node.gpuResident
                || (prepared.knownMask() & ~node.knownMask) != 0L
                || (prepared.completeMask() & ~node.completeMask) != 0L;
        if (!current && !expandsCoverage) return false;
        node.preparedRevision = Math.max(node.preparedRevision,
                prepared.revision());
        node.publishedRevision = Math.max(node.publishedRevision,
                prepared.revision());
        if (current) {
            node.knownMask = prepared.knownMask();
            node.completeMask = prepared.completeMask();
            node.dirtyChildMask = 0L;
            node.state = State.PUBLISHED;
        } else {
            node.knownMask |= prepared.knownMask();
            node.completeMask |= prepared.completeMask();
            node.state = State.DIRTY;
        }
        node.gpuResident = node.knownMask != 0L;
        propagate(node);
        return true;
    }

    public synchronized void markBranchEvicted(NodeKey key, long revision) {
        NodeRecord node = nodes.get(key);
        if (node == null || node.cancelled) return;
        if (revision >= node.publishedRevision) node.gpuResident = false;
        node.state = node.preparedRevision >= node.targetRevision
                ? State.PREPARED : State.DIRTY;
    }

    public synchronized boolean hasPublishedReplacement(long sessionId,
            int projectionId, int regionX, int regionZ, long minimumRevision) {
        int childX = regionX;
        int childZ = regionZ;
        for (int level = 0; level <= maxLevel; level++) {
            NodeKey key = new NodeKey(sessionId, projectionId, level,
                    childX, childZ);
            NodeRecord node = nodes.get(key);
            if (node != null && node.gpuResident
                    && node.publishedRevision >= minimumRevision
                    && node.knownMask != 0L) return true;
            childX = Math.floorDiv(childX, PARENT_CHILDREN_PER_AXIS);
            childZ = Math.floorDiv(childZ, PARENT_CHILDREN_PER_AXIS);
        }
        return false;
    }

    public synchronized Summary summary(long sessionId, int projectionId) {
        int dirty = 0;
        int running = 0;
        int prepared = 0;
        int published = 0;
        int resident = 0;
        int level0 = 0;
        int coarse = 0;
        int count = 0;
        for (NodeRecord node : nodes.values()) {
            if (node.key.sessionId() != sessionId
                    || node.key.projectionId() != projectionId) continue;
            count++;
            if (node.key.level() == 0) level0++;
            else coarse++;
            if (node.state == State.DIRTY) dirty++;
            else if (node.state == State.RUNNING) running++;
            else if (node.state == State.PREPARED) prepared++;
            else if (node.state == State.PUBLISHED) published++;
            if (node.gpuResident) resident++;
        }
        return new Summary(count, dirty, running, prepared, published,
                resident, level0, coarse);
    }

    public synchronized void clear() {
        nodes.clear();
    }

    public synchronized NodeSnapshot snapshot(NodeKey key) {
        NodeRecord node = nodes.get(key);
        return node == null ? null : snapshot(node);
    }

    public synchronized List<NodeSnapshot> snapshots(long sessionId,
            int projectionId) {
        List<NodeSnapshot> result = new ArrayList<>();
        for (NodeRecord node : nodes.values()) {
            if (node.key.sessionId() == sessionId
                    && node.key.projectionId() == projectionId) {
                result.add(snapshot(node));
            }
        }
        result.sort(Comparator.comparingInt((NodeSnapshot node) -> node.key().level())
                .thenComparingInt(node -> node.key().nodeZ())
                .thenComparingInt(node -> node.key().nodeX()));
        return List.copyOf(result);
    }

    /** Marks every resident node for a newer style/projection tuple dirty. */
    public synchronized int invalidate(RevisionStamp stamp, int projectionId) {
        if (stamp == null) return 0;
        int invalidated = 0;
        for (NodeRecord node : nodes.values()) {
            if (node.key.sessionId() != stamp.sessionId()
                    || node.key.projectionId() != projectionId
                    || node.cancelled || !isNewer(stamp, node.stamp)) continue;
            node.stamp = stamp;
            long affected = node.knownMask != 0L
                    ? node.knownMask
                    : (node.dirtyChildMask != 0L
                            ? node.dirtyChildMask
                            : completeMaskForLevel(node.key.level()));
            if (affected == 0L) continue;
            if (node.key.level() == 0) {
                node.directSeedMask = completeMaskForLevel(0);
                for (int i = 0; i < CHILD_COUNT; i++) {
                    if (node.childVersionSums[i] > 0L) {
                        node.childVersionSums[i]++;
                    }
                }
                recomputeAggregate(node);
            }
            node.dirtyChildMask |= affected;
            node.targetRevision = Math.max(node.targetRevision + 1L, revisionFor(node));
            markDirty(node);
            if (node.key.level() == 0) {
                propagate(node);
            }
            invalidated++;
        }
        return invalidated;
    }

    public synchronized void cancelSession(long sessionId) {
        var iterator = nodes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<NodeKey, NodeRecord> entry = iterator.next();
            if (entry.getKey().sessionId() != sessionId) continue;
            entry.getValue().cancelled = true;
            entry.getValue().state = State.CANCELLED;
            iterator.remove();
        }
    }

    private NodeRecord node(NodeKey key, RevisionStamp stamp) {
        NodeRecord existing = nodes.get(key);
        if (existing != null) {
            if (isNewer(stamp, existing.stamp)) existing.stamp = stamp;
            return existing;
        }
        NodeRecord created = new NodeRecord(key, stamp);
        nodes.put(key, created);
        return created;
    }

    private void propagate(NodeRecord child) {
        NodeRecord current = child;
        for (int parentLevel = child.key.level() + 1;
                parentLevel <= maxLevel; parentLevel++) {
            int parentX = Math.floorDiv(current.key.nodeX(),
                    PARENT_CHILDREN_PER_AXIS);
            int parentZ = Math.floorDiv(current.key.nodeZ(),
                    PARENT_CHILDREN_PER_AXIS);
            int childIndex = Math.floorMod(current.key.nodeZ(),
                    PARENT_CHILDREN_PER_AXIS) * PARENT_CHILDREN_PER_AXIS
                    + Math.floorMod(current.key.nodeX(),
                            PARENT_CHILDREN_PER_AXIS);
            NodeRecord parent = node(new NodeKey(current.key.sessionId(),
                    current.key.projectionId(), parentLevel, parentX, parentZ),
                    current.stamp);
            long previous = parent.childVersionSums[childIndex];
            long aggregate = current.aggregateVersionSum;
            parent.childVersionSums[childIndex] = aggregate;
            parent.aggregateVersionSum = safeAdd(parent.aggregateVersionSum,
                    aggregate - previous);
            long bit = 1L << childIndex;
            parent.knownMask = setBit(parent.knownMask, bit,
                    current.knownMask != 0L);
            parent.completeMask = setBit(parent.completeMask, bit,
                    current.completeMask == completeMaskForLevel(
                            current.key.level()));
            parent.dirtyChildMask |= bit;
            parent.targetRevision = revisionFor(parent);
            markDirty(parent);
            current = parent;
        }
    }

    /** Number of direct child slots that are meaningful for this node level. */
    public static int childCountForLevel(int level) {
        return level <= 0 ? CHILD_COUNT : PARENT_CHILD_COUNT;
    }

    /** Full coverage mask for this node level. */
    public static long completeMaskForLevel(int level) {
        return level <= 0 ? ALL_LEAF_CHILDREN : ALL_PARENT_CHILDREN;
    }

    private static boolean matches(Lease lease, PreparedBranch prepared) {
        return lease != null && prepared != null
                && lease.key().equals(prepared.key())
                && sameStamp(lease.stamp(), prepared.stamp())
                && lease.revision() == prepared.revision()
                && Arrays.equals(lease.childVersionSums(),
                        prepared.childVersionSums());
    }

    private static boolean sameStamp(RevisionStamp left, RevisionStamp right) {
        return left != null && left.equals(right);
    }

    private static boolean isNewer(RevisionStamp candidate,
            RevisionStamp current) {
        if (current == null) return true;
        boolean nonDecreasing = candidate.sourceGeneration()
                        >= current.sourceGeneration()
                && candidate.styleGeneration() >= current.styleGeneration()
                && candidate.projectionGeneration()
                        >= current.projectionGeneration();
        boolean changed = candidate.sourceGeneration() != current.sourceGeneration()
                || candidate.styleGeneration() != current.styleGeneration()
                || candidate.projectionGeneration()
                        != current.projectionGeneration();
        return nonDecreasing && changed;
    }

    private static long setBit(long mask, long bit, boolean value) {
        return value ? mask | bit : mask & ~bit;
    }

    private static long revisionFor(NodeRecord node) {
        long generation = safeAdd(node.stamp.sourceGeneration(),
                node.stamp.styleGeneration());
        generation = safeAdd(generation,
                node.stamp.projectionGeneration());
        return Math.max(1L, safeAdd(node.aggregateVersionSum, generation));
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static void recomputeAggregate(NodeRecord node) {
        long sum = 0L;
        for (long version : node.childVersionSums) sum = safeAdd(sum, version);
        node.aggregateVersionSum = sum;
    }

    private static void markDirty(NodeRecord node) {
        if (node.state != State.RUNNING) node.state = State.DIRTY;
    }

    private static NodeSnapshot snapshot(NodeRecord node) {
        return new NodeSnapshot(node.key, node.stamp, node.targetRevision,
                node.runningRevision, node.preparedRevision,
                node.publishedRevision, node.aggregateVersionSum,
                node.dirtyChildMask, node.knownMask, node.completeMask,
                node.gpuResident, node.state, node.childVersionSums);
    }

    private static final class NodeRecord {
        private final NodeKey key;
        private RevisionStamp stamp;
        private final long[] childVersionSums = new long[CHILD_COUNT];
        private long targetRevision = 1L;
        private long runningRevision;
        private long preparedRevision;
        private long publishedRevision;
        private long aggregateVersionSum;
        private long dirtyChildMask;
        private long knownMask;
        private long completeMask;
        private long gpuChildMask;
        /** Child versions currently owned by a region-wide direct-source seed. */
        private long directSeedMask;
        private boolean gpuResident;
        private boolean cancelled;
        private State state = State.CLEAN;

        private NodeRecord(NodeKey key, RevisionStamp stamp) {
            this.key = key;
            this.stamp = stamp;
        }
    }

    /**
     * Immutable worker output for one region-centric LOD node. It contains no GPU
     * handle and no mutable source object, so render-thread publication can validate
     * the complete revision tuple before uploading.
     */
    public static final class PreparedBranch {
        private final RegionLodGraph.NodeKey key;
        private final RevisionStamp stamp;
        private final long revision;
        private final int width;
        private final int height;
        private final int[] pixels;
        private final long knownMask;
        private final long completeMask;
        private final long[] knownRows;
        private final long[] completeRows;
        private final long[] childVersionSums;
        private final int dirtyMinX;
        private final int dirtyMinY;
        private final int dirtyMaxX;
        private final int dirtyMaxY;

        public PreparedBranch(RegionLodGraph.NodeKey key, RevisionStamp stamp,
                long revision, int width, int height, int[] pixels,
                long knownMask, long completeMask, long[] childVersionSums,
                int dirtyMinX, int dirtyMinY, int dirtyMaxX, int dirtyMaxY) {
            this(key, stamp, revision, width, height, pixels, knownMask,
                    completeMask, rowsFromMask(knownMask),
                    rowsFromMask(completeMask), childVersionSums,
                    dirtyMinX, dirtyMinY, dirtyMaxX, dirtyMaxY);
        }

        public PreparedBranch(RegionLodGraph.NodeKey key, RevisionStamp stamp,
                long revision, int width, int height, int[] pixels,
                long knownMask, long completeMask, long[] knownRows,
                long[] completeRows, long[] childVersionSums,
                int dirtyMinX, int dirtyMinY, int dirtyMaxX, int dirtyMaxY) {
            if (key == null || stamp == null) {
                throw new IllegalArgumentException("Branch key and stamp are required");
            }
            if (width <= 0 || height <= 0 || pixels == null
                    || pixels.length != width * height) {
                throw new IllegalArgumentException("Invalid branch pixel payload");
            }
            if (childVersionSums == null
                    || childVersionSums.length != RegionLodGraph.CHILD_COUNT) {
                throw new IllegalArgumentException("Branch requires 64 child versions");
            }
            if (knownRows == null || knownRows.length != height
                    || completeRows == null || completeRows.length != height) {
                throw new IllegalArgumentException("Branch requires coverage rows");
            }
            this.key = key;
            this.stamp = stamp;
            this.revision = Math.max(1L, revision);
            this.width = width;
            this.height = height;
            this.pixels = Arrays.copyOf(pixels, pixels.length);
            this.knownMask = knownMask;
            this.completeMask = completeMask;
            this.knownRows = Arrays.copyOf(knownRows, knownRows.length);
            this.completeRows = Arrays.copyOf(completeRows, completeRows.length);
            this.childVersionSums = Arrays.copyOf(childVersionSums,
                    childVersionSums.length);
            this.dirtyMinX = Math.max(0, Math.min(width - 1, dirtyMinX));
            this.dirtyMinY = Math.max(0, Math.min(height - 1, dirtyMinY));
            this.dirtyMaxX = Math.max(this.dirtyMinX,
                    Math.min(width - 1, dirtyMaxX));
            this.dirtyMaxY = Math.max(this.dirtyMinY,
                    Math.min(height - 1, dirtyMaxY));
        }

        public RegionLodGraph.NodeKey key() { return key; }
        public RevisionStamp stamp() { return stamp; }
        public long revision() { return revision; }
        public int width() { return width; }
        public int height() { return height; }
        public int[] pixels() { return Arrays.copyOf(pixels, pixels.length); }
        public long knownMask() { return knownMask; }
        public long completeMask() { return completeMask; }
        public long[] knownRows() { return Arrays.copyOf(knownRows, knownRows.length); }
        public long[] completeRows() {
            return Arrays.copyOf(completeRows, completeRows.length);
        }
        public long[] childVersionSums() {
            return Arrays.copyOf(childVersionSums, childVersionSums.length);
        }
        public int dirtyMinX() { return dirtyMinX; }
        public int dirtyMinY() { return dirtyMinY; }
        public int dirtyMaxX() { return dirtyMaxX; }
        public int dirtyMaxY() { return dirtyMaxY; }

        private static long[] rowsFromMask(long childMask) {
            long[] rows = new long[64];
            for (int child = 0; child < 64; child++) {
                if ((childMask & (1L << child)) == 0L) continue;
                int childX = child & 7;
                int childY = child >>> 3;
                long bits = 0xFFL << (childX * 8);
                int startY = childY * 8;
                for (int y = startY; y < startY + 8; y++) rows[y] |= bits;
            }
            return rows;
        }
    }

    /**
     * Worker-only compositor for the region-centric LOD hierarchy.
     */
    public static final class RegionLodDeriver {
        public static final int TEXTURE_SIZE = 64;
        private static final int LEAF_CHILD_OUTPUT_SIZE = 8;
        private static final int LEAF_SAMPLE_SPAN =
                TEXTURE_SIZE / LEAF_CHILD_OUTPUT_SIZE;

        private RegionLodDeriver() { }

        public static final class ChildSnapshot {
            private final int childIndex;
            private final long revision;
            private final int[] pixels;
            private final long[] knownRows;
            private final long[] completeRows;

            public ChildSnapshot(int childIndex, long revision, int[] pixels,
                    long[] knownRows, long[] completeRows) {
                if (childIndex < 0 || childIndex >= RegionLodGraph.CHILD_COUNT) {
                    throw new IllegalArgumentException("childIndex");
                }
                if (pixels == null || pixels.length != TEXTURE_SIZE * TEXTURE_SIZE
                        || knownRows == null || knownRows.length != TEXTURE_SIZE
                        || completeRows == null
                        || completeRows.length != TEXTURE_SIZE) {
                    throw new IllegalArgumentException(
                            "LOD child requires 64x64 pixels and coverage rows");
                }
                this.childIndex = childIndex;
                this.revision = Math.max(1L, revision);
                this.pixels = Arrays.copyOf(pixels, pixels.length);
                this.knownRows = Arrays.copyOf(knownRows, knownRows.length);
                this.completeRows = Arrays.copyOf(completeRows,
                        completeRows.length);
            }

            public int childIndex() { return childIndex; }
            public long revision() { return revision; }
            public int[] pixels() { return Arrays.copyOf(pixels, pixels.length); }
            public long[] knownRows() {
                return Arrays.copyOf(knownRows, knownRows.length);
            }
            public long[] completeRows() {
                return Arrays.copyOf(completeRows, completeRows.length);
            }
        }

        public static final class ReducedChildSnapshot {
            private final int childIndex;
            private final long revision;
            private final int[] pixels;
            private final long[] knownRows;
            private final long[] completeRows;

            public ReducedChildSnapshot(int childIndex, long revision,
                    int[] pixels, long[] knownRows, long[] completeRows) {
                if (childIndex < 0 || childIndex >= RegionLodGraph.CHILD_COUNT) {
                    throw new IllegalArgumentException("childIndex");
                }
                if (pixels == null || pixels.length
                        != LEAF_CHILD_OUTPUT_SIZE * LEAF_CHILD_OUTPUT_SIZE
                        || knownRows == null
                        || knownRows.length != LEAF_CHILD_OUTPUT_SIZE
                        || completeRows == null
                        || completeRows.length != LEAF_CHILD_OUTPUT_SIZE) {
                    throw new IllegalArgumentException(
                            "Reduced LOD child requires 8x8 pixels and coverage rows");
                }
                this.childIndex = childIndex;
                this.revision = Math.max(1L, revision);
                this.pixels = Arrays.copyOf(pixels, pixels.length);
                this.knownRows = Arrays.copyOf(knownRows, knownRows.length);
                this.completeRows = Arrays.copyOf(completeRows,
                        completeRows.length);
            }

            public int childIndex() { return childIndex; }
            public long revision() { return revision; }
            public int[] pixels() { return Arrays.copyOf(pixels, pixels.length); }
            public long[] knownRows() {
                return Arrays.copyOf(knownRows, knownRows.length);
            }
            public long[] completeRows() {
                return Arrays.copyOf(completeRows, completeRows.length);
            }
        }

        public static ReducedChildSnapshot reduceExactLeaf(int childIndex,
                long revision, int[] pixels, long[] knownRows) {
            if (pixels == null || pixels.length != TEXTURE_SIZE * TEXTURE_SIZE
                    || knownRows == null || knownRows.length != TEXTURE_SIZE) {
                return null;
            }
            int[] reducedPixels = new int[LEAF_CHILD_OUTPUT_SIZE
                    * LEAF_CHILD_OUTPUT_SIZE];
            long[] reducedKnownRows = new long[LEAF_CHILD_OUTPUT_SIZE];
            long[] reducedCompleteRows = new long[LEAF_CHILD_OUTPUT_SIZE];
            for (int outputY = 0; outputY < LEAF_CHILD_OUTPUT_SIZE; outputY++) {
                int sourceStartY = outputY * LEAF_SAMPLE_SPAN;
                for (int outputX = 0; outputX < LEAF_CHILD_OUTPUT_SIZE; outputX++) {
                    int sourceStartX = outputX * LEAF_SAMPLE_SPAN;
                    ReducedColor reduced = reduceBlock(pixels, knownRows,
                            sourceStartX, sourceStartY, LEAF_SAMPLE_SPAN);
                    if (reduced.known) {
                        reducedPixels[outputY * LEAF_CHILD_OUTPUT_SIZE + outputX] =
                                reduced.abgr;
                        reducedKnownRows[outputY] |= 1L << outputX;
                    }
                    if (blockComplete(knownRows, sourceStartX, sourceStartY,
                            LEAF_SAMPLE_SPAN)) {
                        reducedCompleteRows[outputY] |= 1L << outputX;
                    }
                }
            }
            return new ReducedChildSnapshot(childIndex, revision,
                    reducedPixels, reducedKnownRows, reducedCompleteRows);
        }

        public static PreparedBranch deriveLevel0(RegionLodGraph.Lease lease,
                Collection<ReducedChildSnapshot> children,
                BooleanSupplier stillValid) {
            if (lease == null || lease.key().level() != 0) {
                throw new IllegalArgumentException("level-0 lease required");
            }
            BooleanSupplier valid = stillValid == null ? () -> true : stillValid;
            ReducedChildSnapshot[] indexed =
                    new ReducedChildSnapshot[RegionLodGraph.CHILD_COUNT];
            if (children != null) {
                for (ReducedChildSnapshot child : children) {
                    if (child == null) continue;
                    if (indexed[child.childIndex] != null) {
                        throw new IllegalArgumentException(
                                "Duplicate reduced LOD child " + child.childIndex);
                    }
                    indexed[child.childIndex] = child;
                }
            }

            long[] expectedVersions = lease.childVersionSums();
            int[] output = new int[TEXTURE_SIZE * TEXTURE_SIZE];
            long[] outputKnownRows = new long[TEXTURE_SIZE];
            long[] outputCompleteRows = new long[TEXTURE_SIZE];
            long outputKnownMask = 0L;
            long outputCompleteMask = 0L;
            for (int childIndex = 0;
                    childIndex < RegionLodGraph.CHILD_COUNT; childIndex++) {
                check(valid);
                long bit = 1L << childIndex;
                boolean expectedKnown = (lease.knownMask() & bit) != 0L;
                ReducedChildSnapshot child = indexed[childIndex];
                if (child == null) {
                    if (expectedKnown) {
                        throw new IllegalArgumentException(
                                "Missing authoritative reduced child " + childIndex);
                    }
                    continue;
                }
                if (expectedVersions[childIndex] > 0L
                        && child.revision != expectedVersions[childIndex]) {
                    throw new IllegalArgumentException(
                            "Stale reduced child " + childIndex + ": expected "
                                    + expectedVersions[childIndex] + ", got "
                                    + child.revision);
                }
                int childX = childIndex & 7;
                int childZ = childIndex >>> 3;
                int destinationStartX = childX * LEAF_CHILD_OUTPUT_SIZE;
                int destinationStartY = childZ * LEAF_CHILD_OUTPUT_SIZE;
                boolean anyKnown = false;
                boolean allComplete = true;
                for (int row = 0; row < LEAF_CHILD_OUTPUT_SIZE; row++) {
                    long known = child.knownRows[row] & 0xFFL;
                    long complete = child.completeRows[row] & 0xFFL;
                    anyKnown |= known != 0L;
                    allComplete &= complete == 0xFFL;
                    int destinationY = destinationStartY + row;
                    int destinationOffset = destinationY * TEXTURE_SIZE
                            + destinationStartX;
                    System.arraycopy(child.pixels, row * LEAF_CHILD_OUTPUT_SIZE,
                            output, destinationOffset, LEAF_CHILD_OUTPUT_SIZE);
                    outputKnownRows[destinationY] |= known << destinationStartX;
                    outputCompleteRows[destinationY] |=
                            complete << destinationStartX;
                }
                if (anyKnown) outputKnownMask |= bit;
                if (allComplete) outputCompleteMask |= bit;
            }
            int[] dirtyRect = dirtyRect(lease.key().level(),
                    lease.dirtyChildMask());
            return new PreparedBranch(lease.key(), lease.stamp(), lease.revision(),
                    TEXTURE_SIZE, TEXTURE_SIZE, output, outputKnownMask,
                    outputCompleteMask, outputKnownRows, outputCompleteRows,
                    expectedVersions, dirtyRect[0], dirtyRect[1],
                    dirtyRect[2], dirtyRect[3]);
        }

        public static PreparedBranch derive(RegionLodGraph.Lease lease,
                Collection<ChildSnapshot> children, BooleanSupplier stillValid) {
            if (lease == null) throw new IllegalArgumentException("lease");
            BooleanSupplier valid = stillValid == null ? () -> true : stillValid;
            ChildSnapshot[] indexed = new ChildSnapshot[RegionLodGraph.CHILD_COUNT];
            if (children != null) {
                for (ChildSnapshot child : children) {
                    if (child == null) continue;
                    if (indexed[child.childIndex] != null) {
                        throw new IllegalArgumentException(
                                "Duplicate LOD child " + child.childIndex);
                    }
                    indexed[child.childIndex] = child;
                }
            }

            long[] expectedVersions = lease.childVersionSums();
            int[] output = new int[TEXTURE_SIZE * TEXTURE_SIZE];
            long[] outputKnownRows = new long[TEXTURE_SIZE];
            long[] outputCompleteRows = new long[TEXTURE_SIZE];
            long outputKnownMask = 0L;
            long outputCompleteMask = 0L;

            int childCount = RegionLodGraph.childCountForLevel(lease.key().level());
            for (int childIndex = 0; childIndex < childCount; childIndex++) {
                check(valid);
                long bit = 1L << childIndex;
                ChildSnapshot child = indexed[childIndex];
                boolean expectedKnown = (lease.knownMask() & bit) != 0L;
                if (child == null) {
                    if (expectedKnown) {
                        throw new IllegalArgumentException(
                                "Missing authoritative LOD child " + childIndex);
                    }
                    continue;
                }
                long expectedRevision = expectedVersions[childIndex];
                if (expectedRevision > 0L && child.revision != expectedRevision) {
                    throw new IllegalArgumentException(
                            "Stale LOD child " + childIndex + ": expected "
                                    + expectedRevision + ", got " + child.revision);
                }

                ChildReduction reduction = reduceChild(child, output,
                        outputKnownRows, outputCompleteRows, valid,
                        lease.key().level());
                if (reduction.anyKnown()) outputKnownMask |= bit;
                if (reduction.allComplete()) outputCompleteMask |= bit;
            }

            int[] dirtyRect = dirtyRect(lease.key().level(),
                    lease.dirtyChildMask());
            return new PreparedBranch(lease.key(), lease.stamp(), lease.revision(),
                    TEXTURE_SIZE, TEXTURE_SIZE, output, outputKnownMask,
                    outputCompleteMask, outputKnownRows, outputCompleteRows,
                    expectedVersions, dirtyRect[0], dirtyRect[1],
                    dirtyRect[2], dirtyRect[3]);
        }

        private static ChildReduction reduceChild(ChildSnapshot child,
                int[] output, long[] outputKnownRows, long[] outputCompleteRows,
                BooleanSupplier valid, int level) {
            int childrenPerAxis = level <= 0
                    ? RegionLodGraph.LEAF_CHILDREN_PER_AXIS
                    : RegionLodGraph.PARENT_CHILDREN_PER_AXIS;
            int childOutputSize = TEXTURE_SIZE / childrenPerAxis;
            int sampleSpan = TEXTURE_SIZE / childOutputSize;
            int childX = child.childIndex % childrenPerAxis;
            int childZ = child.childIndex / childrenPerAxis;
            int destinationStartX = childX * childOutputSize;
            int destinationStartY = childZ * childOutputSize;
            boolean anyKnown = false;
            boolean allComplete = true;

            for (int outputY = 0; outputY < childOutputSize; outputY++) {
                check(valid);
                int sourceStartY = outputY * sampleSpan;
                int destinationY = destinationStartY + outputY;
                int destinationRow = destinationY * TEXTURE_SIZE;
                for (int outputX = 0; outputX < childOutputSize; outputX++) {
                    int sourceStartX = outputX * sampleSpan;
                    int destinationX = destinationStartX + outputX;
                    ReducedColor reduced = reduceBlock(child, sourceStartX,
                            sourceStartY, sampleSpan);
                    boolean complete = blockComplete(child, sourceStartX,
                            sourceStartY, sampleSpan);
                    if (reduced.known) {
                        anyKnown = true;
                        output[destinationRow + destinationX] = reduced.abgr;
                        outputKnownRows[destinationY] |= 1L << destinationX;
                    }
                    if (complete) {
                        outputCompleteRows[destinationY] |= 1L << destinationX;
                    } else {
                        allComplete = false;
                    }
                }
            }
            return new ChildReduction(anyKnown, allComplete);
        }

        private static boolean blockComplete(ChildSnapshot child,
                int sourceStartX, int sourceStartY, int sampleSpan) {
            long mask = ((1L << sampleSpan) - 1L) << sourceStartX;
            for (int y = 0; y < sampleSpan; y++) {
                if ((child.completeRows[sourceStartY + y] & mask) != mask) {
                    return false;
                }
            }
            return true;
        }

        private static boolean blockComplete(long[] knownRows,
                int sourceStartX, int sourceStartY, int sampleSpan) {
            long mask = ((1L << sampleSpan) - 1L) << sourceStartX;
            for (int y = 0; y < sampleSpan; y++) {
                if ((knownRows[sourceStartY + y] & mask) != mask) return false;
            }
            return true;
        }

        private static ReducedColor reduceBlock(ChildSnapshot child,
                int sourceStartX, int sourceStartY, int sampleSpan) {
            int knownCount = 0;
            int coloredCount = 0;
            long redTotal = 0L;
            long greenTotal = 0L;
            long blueTotal = 0L;
            for (int y = 0; y < sampleSpan; y++) {
                int sourceY = sourceStartY + y;
                long knownRow = child.knownRows[sourceY];
                int sourceRow = sourceY * TEXTURE_SIZE;
                for (int x = 0; x < sampleSpan; x++) {
                    int sourceX = sourceStartX + x;
                    if ((knownRow & (1L << sourceX)) == 0L) continue;
                    knownCount++;
                    int color = child.pixels[sourceRow + sourceX];
                    int a = color >>> 24;
                    if (a == 0) continue;
                    int r = color & 0xFF;
                    int g = (color >>> 8) & 0xFF;
                    int b = (color >>> 16) & 0xFF;
                    int lum = Math.max(r, Math.max(g, b));
                    int w = (r + g + b >= 380 || lum >= 195) ? 6 : 1;
                    coloredCount += w;
                    redTotal += (long) r * w;
                    greenTotal += (long) g * w;
                    blueTotal += (long) b * w;
                }
            }
            if (knownCount == 0) return ReducedColor.UNKNOWN;
            if (coloredCount == 0) return new ReducedColor(true, 0);
            return new ReducedColor(true, packMeanColor(
                    (int) (redTotal / coloredCount),
                    (int) (greenTotal / coloredCount),
                    (int) (blueTotal / coloredCount)));
        }

        private static ReducedColor reduceBlock(int[] pixels, long[] knownRows,
                int sourceStartX, int sourceStartY, int sampleSpan) {
            int knownCount = 0;
            int coloredCount = 0;
            long redTotal = 0L;
            long greenTotal = 0L;
            long blueTotal = 0L;
            for (int y = 0; y < sampleSpan; y++) {
                int sourceY = sourceStartY + y;
                long knownRow = knownRows[sourceY];
                int sourceRow = sourceY * TEXTURE_SIZE;
                for (int x = 0; x < sampleSpan; x++) {
                    int sourceX = sourceStartX + x;
                    if ((knownRow & (1L << sourceX)) == 0L) continue;
                    knownCount++;
                    int color = pixels[sourceRow + sourceX];
                    int a = color >>> 24;
                    if (a == 0) continue;
                    int r = color & 0xFF;
                    int g = (color >>> 8) & 0xFF;
                    int b = (color >>> 16) & 0xFF;
                    int lum = Math.max(r, Math.max(g, b));
                    int w = (r + g + b >= 380 || lum >= 195) ? 6 : 1;
                    coloredCount += w;
                    redTotal += (long) r * w;
                    greenTotal += (long) g * w;
                    blueTotal += (long) b * w;
                }
            }
            if (knownCount == 0) return ReducedColor.UNKNOWN;
            if (coloredCount == 0) return new ReducedColor(true, 0);
            return new ReducedColor(true, packMeanColor(
                    (int) (redTotal / coloredCount),
                    (int) (greenTotal / coloredCount),
                    (int) (blueTotal / coloredCount)));
        }

        private static int packMeanColor(int red, int green, int blue) {
            return 0xFF000000 | (Math.max(0, Math.min(255, blue)) << 16)
                    | (Math.max(0, Math.min(255, green)) << 8)
                    | Math.max(0, Math.min(255, red));
        }

        private static int[] dirtyRect(int level, long dirtyChildMask) {
            if (dirtyChildMask == 0L) return new int[] { 0, 0, 63, 63 };
            int minX = TEXTURE_SIZE;
            int minY = TEXTURE_SIZE;
            int maxX = -1;
            int maxY = -1;
            int childrenPerAxis = level <= 0
                    ? RegionLodGraph.LEAF_CHILDREN_PER_AXIS
                    : RegionLodGraph.PARENT_CHILDREN_PER_AXIS;
            int childCount = RegionLodGraph.childCountForLevel(level);
            int childOutputSize = TEXTURE_SIZE / childrenPerAxis;
            for (int child = 0; child < childCount; child++) {
                if ((dirtyChildMask & (1L << child)) == 0L) continue;
                int x = (child % childrenPerAxis) * childOutputSize;
                int y = (child / childrenPerAxis) * childOutputSize;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x + childOutputSize - 1);
                maxY = Math.max(maxY, y + childOutputSize - 1);
            }
            return new int[] { minX, minY, maxX, maxY };
        }

        private static void check(BooleanSupplier valid) {
            if (!valid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException(
                        "LOD derivation cancelled");
            }
        }

        private record ChildReduction(boolean anyKnown, boolean allComplete) { }

        private record ReducedColor(boolean known, int abgr) {
            private static final ReducedColor UNKNOWN = new ReducedColor(false, 0);
        }
    }
}

}
