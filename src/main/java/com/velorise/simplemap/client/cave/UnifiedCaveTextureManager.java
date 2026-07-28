package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapGpuBudgetController;
import com.velorise.simplemap.client.MapResidencyManager;
import com.velorise.simplemap.client.MapPageLayout;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapPipelineStage;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import com.velorise.simplemap.client.ExactPageState;
import com.velorise.simplemap.client.ExactPageStateTracker;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapViewLoadPlanner;
import com.velorise.simplemap.client.MapViewportDemandPolicy;
import com.velorise.simplemap.client.MapWorkScheduler;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified page-only GPU pipeline for Layered and Full Cave.
 *
 * Foreground publication never waits for a 512x512 region consolidation. Every
 * 64x64 page has an explicit source/build/upload revision and stale jobs retry.
 */
public final class UnifiedCaveTextureManager {
    private static final UnifiedCaveTextureManager INSTANCE = new UnifiedCaveTextureManager();
    private static final int MAX_PAGES = Math.max(CaveTextureAtlas.SLOT_COUNT,
            Math.min(3072, CaveTextureAtlas.SLOT_COUNT * 2));
    private static final int MAX_REQUESTS = 4096;
    private static final long INCOMPLETE_RETRY_MS = 80L;
    private static final long FAILED_RETRY_MS = 250L;
    /** Fullscreen cave work may prepare several reusable leaves ahead. Publication
     * remains priority ordered, but no completed visible page waits behind a missing
     * row-major predecessor. */
    /** A corrupt/deferred source must not freeze an entire viewport forever. The
     * skipped page is revisited on the next deterministic repair cycle. */
    /** Any authoritative column is useful. Unknown texels remain masked and the
     * existing screen-space policy hides partial exact leaves at far zoom. Requiring
     * a full 16x16 chunk kept explored cave pages black when archive/source capture
     * arrived incrementally. */
    private static final int FIRST_PUBLICATION_MIN_KNOWN_COLUMNS = 1;
    /** Partial exact pages are useful near the player, but at smaller screen sizes
     * they visually tear against a stable branch backdrop. */
    private static final float PARTIAL_EXACT_MIN_SCREEN_PIXELS = 16.0f;

    private final CaveTileRepository repository = CaveTileRepository.getInstance();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();
    private final MapPipelineTelemetry pipelineTelemetry = MapPipelineTelemetry.getInstance();
    private final CaveTextureAtlas atlas = new CaveTextureAtlas();
    /** Recursive L1-L5 branch tree updated incrementally from leaf-page patches. */
    private final CaveLodTree lodTree = new CaveLodTree();
    /** Render-thread scratch reused across every page publication. */
    private final int[][] uploadScratchLods = allocateLodBuffers();
    private final Map<PageKey, PageInfo> pages = new LinkedHashMap<>(128, 0.75f, true);
    /**
     * O(1) resident-subtree index. The old implementation scanned every resident
     * page for every missing branch node, turning far-zoom cave rendering into
     * O(visibleNodes × residentPages) work.
     */
    private final Map<ResidentNodeKey, Integer> residentNodeCounts = new HashMap<>();
    private final Map<PageKey, PageRequest> requests = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<PageKey, Long> revisions = new HashMap<>();
    private final List<PageInfo> deferredCloses = new ArrayList<>();
    /** Strict publication ordering: minimap completions can never sit behind a
     * large FIFO burst of fullscreen builds. */
    private final PriorityBlockingQueue<CompletedBuild> completedBuilds =
            new PriorityBlockingQueue<>();
    private final AtomicLong completedSequence = new AtomicLong();
    /** Exact-cave render revision independent from unrelated surface/legacy uploads. */
    private final AtomicLong exactContentRevision = new AtomicLong();
    private final PriorityDecodeExecutor workers;
    private final EnumMap<MapRequestLane, VisiblePlanner> visiblePlans =
            new EnumMap<>(MapRequestLane.class);

    private int renderBatchDepth;
    private long lastUploadMs;
    private long observedAtlasGeneration = Long.MIN_VALUE;

    private UnifiedCaveTextureManager() {
        int threads = Math.max(2, Math.min(3,
                Math.max(1, Runtime.getRuntime().availableProcessors() / 4)));
        workers = new PriorityDecodeExecutor(
                com.velorise.simplemap.client.MapWorkScheduler.WorkType.EXACT_BUILD, 8);
        for (MapRequestLane lane : MapRequestLane.values()) {
            visiblePlans.put(lane, new VisiblePlanner());
        }
    }

    public static UnifiedCaveTextureManager getInstance() {
        return INSTANCE;
    }


    /**
     * Revision used by cave render-plan caching. It changes only for cave exact or
     * cave branch residency, so Surface/legacy publication cannot invalidate a
     * stable Cave Map plan every tick.
     */
    public long contentRevision() {
        return exactContentRevision.get() + lodTree.contentRevision();
    }

    public int requestCount() {
        synchronized (pages) {
            return requests.size();
        }
    }

    /** Stops a hidden viewport lane from retaining priority or publication work. */
    public void suspendLane(MapRequestLane lane) {
        if (lane == null) return;
        synchronized (pages) {
            long now = System.currentTimeMillis();
            for (PageRequest request : requests.values()) request.clearLane(lane);
            requests.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            // Exact page outputs are keyed by dimension/view/layer/page and remain
            // reusable after a HUD/fullscreen lane becomes hidden. Cancelling or
            // removing completed work here stranded done futures in PageInfo and
            // produced permanent BUILDING states. Stop new demand, but let bounded
            // in-flight work finish and pass normal revision validation.
            VisiblePlanner planner = visiblePlans.get(lane);
            if (planner != null) planner.clear();
        }
    }

    public int pendingBuildCount() {
        synchronized (pages) {
            int count = 0;
            for (PageInfo info : pages.values()) {
                if (info.pending != null) count++;
            }
            return count;
        }
    }

    private int pendingBuildCount(MapRequestLane lane) {
        synchronized (pages) {
            int count = 0;
            for (PageInfo info : pages.values()) {
                if (info.pending != null && info.pendingLane == lane) count++;
            }
            return count;
        }
    }

    public DebugSnapshot debugSnapshot() {
        synchronized (pages) {
            int pending = 0;
            int initialized = 0;
            int partial = 0;
            int knownEmpty = 0;
            int resident = 0;
            for (PageInfo info : pages.values()) {
                if (info.pending != null) pending++;
                if (info.initialized) initialized++;
                if (info.initialized && info.knownColumns > 0
                        && info.knownColumns < CaveTextureAtlas.PAGE_SIZE
                                * CaveTextureAtlas.PAGE_SIZE) partial++;
                if (info.knownEmpty) knownEmpty++;
                if (info.initialized && info.atlasSlot >= 0) resident++;
            }
            VisiblePlanner fullscreen = visiblePlans.get(MapRequestLane.FULLSCREEN);
            return new DebugSnapshot(pages.size(), requests.size(), pending,
                    completedBuilds.size(), initialized, partial, knownEmpty, resident,
                    fullscreen == null ? 0 : fullscreen.updateSliceIndex,
                    fullscreen == null ? 0 : fullscreen.pagePlan.length);
        }
    }

    private boolean hasActiveRequest(MapRequestLane lane, long now) {
        synchronized (pages) {
            for (PageRequest request : requests.values()) {
                if (request.effectiveLane(now) == lane) return true;
            }
            return false;
        }
    }

    public void requestVisiblePages(CaveView view, int layerY,
            double minX, double maxX, double minZ, double maxZ, float scale) {
        requestVisiblePages(view, layerY, minX, maxX, minZ, maxZ, scale,
                MapRequestLane.FULLSCREEN);
    }

    public void requestVisiblePages(CaveView view, int layerY,
            double minX, double maxX, double minZ, double maxZ, float scale,
            MapRequestLane lane) {
        requestVisiblePages(view, layerY, minX, maxX, minZ, maxZ, scale,
                (minX + maxX) * 0.5, (minZ + maxZ) * 0.5, lane);
    }

    public void requestVisiblePages(CaveView view, int layerY,
            double minX, double maxX, double minZ, double maxZ, float scale,
            double focusX, double focusZ, MapRequestLane lane) {
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        VisiblePlanner planner = visiblePlans.get(effectiveLane);

        MapViewportDemandPolicy.Bounds admittedViewport =
                MapViewportDemandPolicy.trimEdgeSlivers(
                        minX, maxX, minZ, maxZ, effectiveLane);
        double visibleMinX = admittedViewport.minX();
        double visibleMaxX = admittedViewport.maxX();
        double visibleMinZ = admittedViewport.minZ();
        double visibleMaxZ = admittedViewport.maxZ();
        int minPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(visibleMinX));
        int maxPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(Math.nextDown(visibleMaxX)));
        int minPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(visibleMinZ));
        int maxPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(Math.nextDown(visibleMaxZ)));
        int normalizedLayerY = normalizedLayer(view, layerY);
        String currentDimension = dimension();
        long now = System.currentTimeMillis();
        int rawFocusPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(focusX));
        int rawFocusPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(focusZ));
        if (effectiveLane == MapRequestLane.MINIMAP) {
            minPageX = Math.max(minPageX - MapViewLoadPlanner.MINIMAP_HALO_PAGES,
                    rawFocusPageX - MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES);
            maxPageX = Math.min(maxPageX + MapViewLoadPlanner.MINIMAP_HALO_PAGES,
                    rawFocusPageX + MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES);
            minPageZ = Math.max(minPageZ - MapViewLoadPlanner.MINIMAP_HALO_PAGES,
                    rawFocusPageZ - MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES);
            maxPageZ = Math.min(maxPageZ + MapViewLoadPlanner.MINIMAP_HALO_PAGES,
                    rawFocusPageZ + MapViewLoadPlanner.MINIMAP_MAX_RADIUS_PAGES);
        }
        int centerPageX = clamp(rawFocusPageX, minPageX, maxPageX);
        int centerPageZ = clamp(rawFocusPageZ, minPageZ, maxPageZ);
        boolean focusChanged = centerPageX != planner.focusPageX
                || centerPageZ != planner.focusPageZ;
        boolean changed = !currentDimension.equals(planner.dimension)
                || view != planner.view
                || normalizedLayerY != planner.layerY
                || layerY != planner.projectionTopY
                || minPageX != planner.minPageX || maxPageX != planner.maxPageX
                || minPageZ != planner.minPageZ || maxPageZ != planner.maxPageZ
                || (effectiveLane != MapRequestLane.FULLSCREEN && focusChanged);
        if (!changed && now - planner.lastEnumerationMs
                < CaveScreenSpacePolicy.exactEnumerationRetryMs(scale, effectiveLane)) return;
        planner.lastEnumerationMs = now;

        if (changed) {
            retireLaneOutside(effectiveLane, currentDimension, view,
                    normalizedLayerY, minPageX, maxPageX, minPageZ, maxPageZ);
            planner.reset(currentDimension, view, normalizedLayerY, layerY,
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, effectiveLane);
        } else {
            // Focus changes are retained only for minimap/background helpers;
            // Fullscreen request and publication order remains viewport row-major.
            planner.focusPageX = centerPageX;
            planner.focusPageZ = centerPageZ;
        }
        planner.scale = scale;

        int admissionBudget = CaveScreenSpacePolicy.exactAdmissionBudget(
                scale, effectiveLane, MapPerformanceGovernor.getInstance().underPressure());
        if (admissionBudget <= 0) return;

        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            // Fullscreen is a rolling, bounded frontier. A missing/partial page is
            // allowed to retry, but it must not pin every page after it. This is the
            // same coarse-region principle used by mature map renderers: scheduling
            // order is stable, completion order is independent.
            if (planner.pagePlan.length == 0) return;
            if (planner.pageCursor >= planner.pagePlan.length) {
                if (now < planner.nextRestartMs) return;
                planner.pageCursor = 0;
            }
            int admitted = 0;
            int considered = 0;
            int considerationBudget = Math.max(16, admissionBudget * 8);
            while (planner.pageCursor < planner.pagePlan.length
                    && admitted < admissionBudget
                    && considered < considerationBudget) {
                int ordinal = planner.pageCursor;
                long packedPage = planner.pagePlan[planner.pageCursor++];
                considered++;
                int pageX = CaveLoadHierarchy.x(packedPage);
                int pageZ = CaveLoadHierarchy.z(packedPage);
                PageKey candidateKey = key(view, layerY, pageX, pageZ);
                if (isPageDisplayReady(candidateKey)) continue;
                if (isPageActive(candidateKey, effectiveLane, now)) continue;
                int priority = effectiveLane.priorityBase() + 220_000
                        - Math.min(180_000, ordinal * 250);
                requestPage(view, layerY, pageX, pageZ,
                        priority, effectiveLane, now, ordinal);
                repository.requestDisplayPageLoad(view, layerY, pageX, pageZ);
                pipelineTelemetry.recordPageAdmission(effectiveLane);
                admitted++;
            }
            planner.updateSliceIndex = planner.pageCursor
                    / MapViewLoadPlanner.FULLSCREEN_SLICE_SIZE;
            if (planner.pageCursor >= planner.pagePlan.length) {
                planner.requestCompletedCycles++;
                planner.nextRestartMs = now
                        + CaveScreenSpacePolicy.completedPlanPauseMs(
                                scale, effectiveLane);
            }
            return;
        }

        // Minimap/background retain centre-out ordering and may admit a few exact
        // leaves because the visible footprint is intentionally small.
        if (planner.pageCursor >= planner.pagePlan.length) {
            if (now < planner.nextRestartMs) return;
            planner.pageCursor = 0;
        }
        int admitted = 0;
        while (planner.pageCursor < planner.pagePlan.length
                && admitted < admissionBudget) {
            int ordinal = planner.pageCursor;
            long packedPage = planner.pagePlan[planner.pageCursor++];
            int pageX = CaveLoadHierarchy.x(packedPage);
            int pageZ = CaveLoadHierarchy.z(packedPage);
            PageKey candidateKey = key(view, layerY, pageX, pageZ);
            if (isPageSatisfied(candidateKey)) continue;
            int distancePenalty = Math.min(800_000,
                    squaredDistance(pageX, pageZ, centerPageX, centerPageZ) * 2_000);
            int priority = effectiveLane.priorityBase() + 180_000
                    - distancePenalty - ordinal * 250;
            requestPage(view, layerY, pageX, pageZ, priority,
                    effectiveLane, now);
            repository.requestDisplayPageLoad(view, layerY, pageX, pageZ);
            pipelineTelemetry.recordPageAdmission(effectiveLane);
            admitted++;
        }
        if (planner.pageCursor >= planner.pagePlan.length) {
            planner.nextRestartMs = now
                    + CaveScreenSpacePolicy.completedPlanPauseMs(scale, effectiveLane);
        }
    }

    private static int squaredDistance(int pageX, int pageZ,
            int focusPageX, int focusPageZ) {
        int dx = pageX - focusPageX;
        int dz = pageZ - focusPageZ;
        return dx * dx + dz * dz;
    }

    private void retireLaneOutside(MapRequestLane lane, String dimension,
            CaveView view, int normalizedLayerY, int minPageX, int maxPageX,
            int minPageZ, int maxPageZ) {
        synchronized (pages) {
            long now = System.currentTimeMillis();
            for (PageRequest request : requests.values()) {
                PageKey key = request.key;
                boolean retained = key.dimension().equals(dimension)
                        && key.view() == view && key.layerY() == normalizedLayerY
                        && key.globalPageX() >= minPageX
                        && key.globalPageX() <= maxPageX
                        && key.globalPageZ() >= minPageZ
                        && key.globalPageZ() <= maxPageZ;
                if (!retained) request.clearLane(lane);
            }
            requests.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            // Do not cancel already admitted exact builds merely because the camera
            // crossed a viewport boundary. Their immutable page result is still
            // valid and reusable if the user pans back; session/source revisions
            // below remain the semantic cancellation boundary.
        }
    }

    public void requestRegion(CaveView view, int layerY, int regionX, int regionZ) {
        int firstPageX = regionX << 3;
        int firstPageZ = regionZ << 3;
        long now = System.currentTimeMillis();
        for (int pageX = 0; pageX < 8; pageX++) {
            for (int pageZ = 0; pageZ < 8; pageZ++) {
                int dx = pageX - 3;
                int dz = pageZ - 3;
                requestPage(view, layerY, firstPageX + pageX, firstPageZ + pageZ,
                        MapRequestLane.BACKGROUND.priorityBase()
                                - (dx * dx + dz * dz) * 100,
                        MapRequestLane.BACKGROUND, now);
            }
        }
        repository.requestDisplayPageRangeLoad(view, layerY,
                firstPageX, firstPageX + 7, firstPageZ, firstPageZ + 7);
    }

    public void markRegionDirty(CaveView view, int layerY, int regionX, int regionZ) {
        int firstPageX = regionX << 3;
        int firstPageZ = regionZ << 3;
        synchronized (pages) {
            for (PageKey key : new ArrayList<>(revisions.keySet())) {
                if (key.view() == view && key.layerY() == normalizedLayer(view, layerY)
                        && key.globalPageX() >= firstPageX && key.globalPageX() < firstPageX + 8
                        && key.globalPageZ() >= firstPageZ && key.globalPageZ() < firstPageZ + 8) {
                    revisions.merge(key, 1L, Long::sum);
                }
            }
        }
    }

    public CaveAtlasRegion peekPageRegion(CaveView view, int layerY,
            int regionX, int regionZ, int pageX, int pageZ, float scale) {
        PageKey key = key(view, layerY,
                (regionX << 3) + pageX, (regionZ << 3) + pageZ);
        synchronized (pages) {
            PageInfo info = pages.get(key);
            if (info == null || !info.initialized) return null;
            int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
            if (info.knownColumns < fullColumns
                    && CaveScreenSpacePolicy.exactPagePixels(scale)
                            < PARTIAL_EXACT_MIN_SCREEN_PIXELS) {
                return null;
            }
            MapResidencyManager.getInstance().touch(residencyKey(key));
            return atlas.region(info.atlasSlot, scale);
        }
    }

    /** Compatibility lookup for the first 512x512 branch level. */
    public CaveAtlasRegion peekBranchRegion(CaveView view, int layerY,
            int regionX, int regionZ) {
        return peekBranchRegion(view, layerY, 1, regionX, regionZ);
    }

    /** Returns a partial or complete recursive LOD node. */
    public CaveAtlasRegion peekBranchRegion(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        synchronized (pages) {
            return lodTree.peek(dimension(), view, normalizedLayer(view, layerY),
                    level, nodeX, nodeZ);
        }
    }

    public boolean hasBranchData(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        synchronized (pages) {
            return lodTree.hasData(dimension(), view, normalizedLayer(view, layerY),
                    level, nodeX, nodeZ);
        }
    }

    /** Compatibility view for older call sites; new rendering should use atlas regions. */
    public ResourceLocation peekPage(CaveView view, int layerY,
            int regionX, int regionZ, int pageX, int pageZ) {
        CaveAtlasRegion region = peekPageRegion(view, layerY,
                regionX, regionZ, pageX, pageZ, 1.0f);
        return region == null ? null : region.texture();
    }

    public boolean hasAnyPage(CaveView view, int layerY, int regionX, int regionZ) {
        int firstPageX = regionX << 3;
        int firstPageZ = regionZ << 3;
        int normalized = normalizedLayer(view, layerY);
        String dimension = dimension();
        synchronized (pages) {
            for (PageInfo info : pages.values()) {
                PageKey key = info.key;
                if (info.initialized && key.dimension().equals(dimension)
                        && key.view() == view && key.layerY() == normalized
                        && key.globalPageX() >= firstPageX && key.globalPageX() < firstPageX + 8
                        && key.globalPageZ() >= firstPageZ && key.globalPageZ() < firstPageZ + 8) return true;
            }
        }
        return false;
    }

    /**
     * Returns true when at least one exact GPU page is resident below the given
     * recursive LOD node. A complete ancestor is allowed to cover cold children,
     * but it must not hide a newer exact leaf that is already available.
     */
    public boolean hasResidentPageInNode(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        int normalized = normalizedLayer(view, layerY);
        String currentDimension = dimension();
        synchronized (pages) {
            if (level <= 0) {
                PageInfo info = pages.get(new PageKey(currentDimension, view, normalized,
                        nodeX, nodeZ));
                return info != null && info.initialized && info.atlasSlot >= 0;
            }
            return residentNodeCounts.getOrDefault(new ResidentNodeKey(
                    currentDimension, view, normalized, level, nodeX, nodeZ), 0) > 0;
        }
    }

    /**
     * A resident exact leaf is always legal to render. The fullscreen frontier is
     * an admission/publication ordering policy only; it must never hide a valid
     * cached texture after pan or zoom. When the target branch level is still cold,
     * retained exact leaves therefore remain the last-good visual fallback.
     */
    public boolean allowFullscreenExact(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        return true;
    }

    private void indexResidentPage(PageKey key, int delta) {
        if (key == null || delta == 0) return;
        synchronized (pages) {
            for (int level = 1; level <= CaveLodTree.MAX_LEVEL; level++) {
                int span = 1 << level;
                int nodeX = Math.floorDiv(key.globalPageX(), span);
                int nodeZ = Math.floorDiv(key.globalPageZ(), span);
                ResidentNodeKey node = new ResidentNodeKey(key.dimension(), key.view(),
                        key.layerY(), level, nodeX, nodeZ);
                int updated = residentNodeCounts.getOrDefault(node, 0) + delta;
                if (updated <= 0) residentNodeCounts.remove(node);
                else residentNodeCounts.put(node, updated);
            }
        }
    }

    public void beginRenderBatch() {
        synchronized (pages) {
            renderBatchDepth++;
        }
    }

    public void endRenderBatch() {
        List<PageInfo> close = null;
        synchronized (pages) {
            if (renderBatchDepth > 0) renderBatchDepth--;
            if (renderBatchDepth == 0 && !deferredCloses.isEmpty()) {
                close = new ArrayList<>(deferredCloses);
                deferredCloses.clear();
            }
        }
        if (close != null) close.forEach(PageInfo::close);
    }

    public void upload(boolean force) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> upload(force));
            return;
        }
        synchronizeAtlasStorage();
        long now = System.currentTimeMillis();
        if (!force && now - lastUploadMs < (MapConfig.fastFullscreenLoading ? 12L : 25L)) return;
        lastUploadMs = now;
        pruneRequests(now);

        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        // Force means "do not wait for the normal cadence", not "publish an entire
        // region in one frame". Capping both stages avoids the saw-tooth GPU usage
        // visible on integrated graphics while the fullscreen map is open.
        int publishBudget = force ? 6
                : Math.max(2, Math.min(6, governor.texturePageBudget(false) * 2));
        long deadline = System.nanoTime() + (force ? 6_000_000L
                : Math.max(1_000_000L, Math.min(3_500_000L,
                        governor.textureUploadBudgetNanos(false) * 2)));
        VisiblePlanner fullscreenPlanner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        boolean branchFirst = fullscreenPlanner != null
                && hasActiveRequest(MapRequestLane.FULLSCREEN, now)
                && CaveScreenSpacePolicy.branchFirst(
                        fullscreenPlanner.scale, MapRequestLane.FULLSCREEN);

        // One completed exact transaction may create/refresh a coarse branch, but
        // far-zoom publication spends the remaining frame on branch coverage before
        // starting another expensive leaf. Close zoom retains exact-first latency.
        publishCompleted(branchFirst ? 1 : publishBudget, deadline, now);
        if (branchFirst && System.nanoTime() < deadline) {
            publishBranches(force ? 3 : 2, deadline);
        }
        if (System.nanoTime() < deadline) {
            scheduleBuilds(branchFirst ? 1
                    : (force ? 4 : Math.max(2, publishBudget)), deadline, now);
        }
        if (!branchFirst && System.nanoTime() < deadline) {
            publishBranches(force ? 2 : 1, deadline);
        }
    }

    private void synchronizeAtlasStorage() {
        atlas.ensureInitialized();
        synchronized (pages) {
            lodTree.synchronizeStorage();
        }
        long generation = atlas.storageGeneration();
        boolean pageReload = observedAtlasGeneration != Long.MIN_VALUE
                && generation != observedAtlasGeneration;
        observedAtlasGeneration = generation;
        if (!pageReload) return;
        MapResidencyManager.getInstance().markTopologyChanged();
        exactContentRevision.incrementAndGet();
        synchronized (pages) {
            residentNodeCounts.clear();
            // A resource reload recreates empty GL storage. Keep CPU page buffers
            // and slot ownership, but force every visible page through a full upload.
            for (PageInfo info : pages.values()) {
                info.initialized = false;
                info.uploadedRevision = 0L;
                info.uploadedSourceRevision = Long.MIN_VALUE;
                info.nextRetryMs = 0L;
            }
        }
    }

    public void invalidateStyle() {
        LodBranchDiskCache.getInstance().invalidateCurrentDimension();
        synchronized (pages) {
            for (PageKey key : revisions.keySet()) revisions.merge(key, 1L, Long::sum);
        }
    }

    public void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::clear);
            return;
        }
        List<PageInfo> close;
        synchronized (pages) {
            close = new ArrayList<>(pages.values());
            close.addAll(deferredCloses);
            pages.clear();
            residentNodeCounts.clear();
            requests.clear();
            revisions.clear();
            deferredCloses.clear();
            completedBuilds.clear();
            exactContentRevision.incrementAndGet();
            for (VisiblePlanner planner : visiblePlans.values()) planner.clear();
        }
        close.forEach(PageInfo::close);
        synchronized (pages) {
            lodTree.clear();
        }
        atlas.resetSlots();
        ExactPageStateTracker.getInstance().clearPrefix("cave:");
    }

    private boolean isPageSatisfied(PageKey key) {
        synchronized (pages) {
            return isPageSatisfiedLocked(key);
        }
    }

    /** A fullscreen frontier may advance after a coherent retained page is visible,
     * even while a later source revision is queued for repair. This prevents one
     * partial live tile from freezing every row below it. */
    private boolean isPageDisplayReady(PageKey key) {
        synchronized (pages) {
            return isPageDisplayReadyLocked(key);
        }
    }

    private boolean isPageDisplayReadyLocked(PageKey key) {
        if (isPageSatisfiedLocked(key)) return true;
        PageInfo info = pages.get(key);
        if (info == null || !info.initialized
                || info.knownColumns < FIRST_PUBLICATION_MIN_KNOWN_COLUMNS) {
            return false;
        }
        // A partial page may advance the fullscreen frontier, but only while it
        // still represents the latest retained source. World-save repair can
        // resolve more leaves after the first publication; treating the old GPU
        // page as permanently ready leaves black holes around the player.
        return info.uploadedSourceRevision == repository.getPageRevision(
                key.globalPageX(), key.globalPageZ());
    }

    private boolean isPageActive(PageKey key, MapRequestLane lane, long now) {
        synchronized (pages) {
            return isPageActiveLocked(key, lane, now);
        }
    }

    private boolean isPageActiveLocked(PageKey key, MapRequestLane lane, long now) {
        PageRequest request = requests.get(key);
        if (request != null && request.isLaneActive(lane, now)) return true;
        PageInfo info = pages.get(key);
        return info != null && info.pending != null && info.pendingLane == lane;
    }

    private boolean isPageSatisfiedLocked(PageKey key) {
        PageInfo info = pages.get(key);
        if (info == null || info.pending != null) return false;
        long requestedRevision = revisions.getOrDefault(key, 1L);
        long sourceRevision = repository.getPageRevision(
                key.globalPageX(), key.globalPageZ());
        int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
        return info.uploadedRevision == requestedRevision
                && info.uploadedSourceRevision == sourceRevision
                && (info.knownEmpty
                        || (info.initialized && info.atlasSlot >= 0
                                && info.knownColumns >= fullColumns));
    }

    private void clearSatisfiedRequest(PageKey key, long now) {
        synchronized (pages) {
            PageRequest request = requests.get(key);
            if (request == null) return;
            request.clearAll();
            if (request.isExpired(now)) requests.remove(key);
        }
    }

    private void requestPage(CaveView view, int layerY,
            int globalPageX, int globalPageZ, int priority,
            MapRequestLane lane, long now) {
        requestPage(view, layerY, globalPageX, globalPageZ,
                priority, lane, now, Integer.MAX_VALUE);
    }

    private void requestPage(CaveView view, int layerY,
            int globalPageX, int globalPageZ, int priority,
            MapRequestLane lane, long now, int fullscreenOrdinal) {
        PageKey key = key(view, layerY, globalPageX, globalPageZ);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        synchronized (pages) {
            revisions.putIfAbsent(key, 1L);
            if (isPageSatisfiedLocked(key)) {
                PageRequest satisfied = requests.get(key);
                if (satisfied != null) {
                    satisfied.clearAll();
                    if (satisfied.isExpired(now)) requests.remove(key);
                }
                return;
            }
            PageRequest existing = requests.get(key);
            if (existing == null) {
                existing = new PageRequest(key, layerY);
                requests.put(key, existing);
            } else if (existing.projectionTopY != layerY) {
                existing.retarget(layerY);
                revisions.merge(key, 1L, Long::sum);
                PageInfo info = pages.get(key);
                if (info != null) info.nextRetryMs = 0L;
            }
            existing.observe(effectiveLane, priority, now, fullscreenOrdinal);
            trimLaneShortlist(effectiveLane, now);
            ExactPageStateTracker.getInstance().transition(
                    stateKey(key), ExactPageState.REQUESTED,
                    effectiveLane, revisions.getOrDefault(key, 1L));
            PageInfo pendingInfo = pages.get(key);
            if (pendingInfo != null && pendingInfo.pending != null
                    && effectiveLane.strongerThan(pendingInfo.pendingLane)) {
                if (pendingInfo.pendingToken != null) pendingInfo.pendingToken.cancel();
                if (pendingInfo.pending.cancel(false)) {
                    pendingInfo.pending = null;
                    pendingInfo.pendingToken = null;
                    pendingInfo.pendingLane = null;
                    pendingInfo.nextRetryMs = 0L;
                    pipelineTelemetry.recordTaskCancelledBeforeRun();
                }
            }
            while (requests.size() > MAX_REQUESTS) {
                PageKey retired = weakestRequestKey(now);
                if (retired == null) break;
                requests.remove(retired);
                if (!pages.containsKey(retired)) revisions.remove(retired);
            }
        }
    }

    private PageKey weakestRequestKey(long now) {
        PageKey selected = null;
        int selectedPriority = Integer.MAX_VALUE;
        long selectedSeen = Long.MAX_VALUE;
        for (Map.Entry<PageKey, PageRequest> entry : requests.entrySet()) {
            PageRequest request = entry.getValue();
            int priority = request.effectivePriority(now);
            long seen = request.latestSeenMs();
            if (selected == null || priority < selectedPriority
                    || (priority == selectedPriority && seen < selectedSeen)) {
                selected = entry.getKey();
                selectedPriority = priority;
                selectedSeen = seen;
            }
        }
        return selected;
    }

    private void trimLaneShortlist(MapRequestLane lane, long now) {
        int maximum = switch (lane) {
            case MINIMAP -> 12;
            case FULLSCREEN -> 24;
            case BACKGROUND -> 8;
            case PREFETCH -> 4;
        };
        for (PageRequest request : requests.values()) {
            if (isPageSatisfiedLocked(request.key)) request.clearAll();
        }
        int active = 0;
        for (PageRequest request : requests.values()) {
            if (request.isLaneActive(lane, now)) active++;
        }
        while (active > maximum) {
            PageRequest weakest = null;
            for (PageRequest request : requests.values()) {
                if (!request.isLaneActive(lane, now)) continue;
                if (weakest == null
                        || request.priorityForLane(lane) < weakest.priorityForLane(lane)
                        || (request.priorityForLane(lane) == weakest.priorityForLane(lane)
                                && request.seenForLane(lane) < weakest.seenForLane(lane))) {
                    weakest = request;
                }
            }
            if (weakest == null) break;
            weakest.clearLane(lane);
            active--;
        }
        requests.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private int publishCompleted(int budget, long deadline, long now) {
        int published = 0;
        while (published < budget && System.nanoTime() < deadline) {
            CompletedBuild completed = pollHighestPriorityCompleted(now);
            if (completed == null) break;
            PageInfo info = completed.info();
            CompletableFuture<BuildResult> pending = completed.future();
            if (info.pending != pending) {
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_COMPLETION_REPLACED", 250L)) {
                    recorder.event("CAVE_COMPLETION_REPLACED",
                            "page=" + info.key + " lane=" + completed.lane()
                                    + " ordinal=" + completed.fullscreenOrdinal());
                }
                continue;
            }

            BuildResult result;
            try {
                result = pending.join();
            } catch (Throwable throwable) {
                Throwable cause = throwable;
                while (cause.getCause() != null) cause = cause.getCause();
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = cause instanceof CancellationException
                        ? now : now + FAILED_RETRY_MS;
                pipelineTelemetry.recordExactBuildDiscarded();
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_BUILD_FAILED:" + info.key, 500L)) {
                    recorder.event("CAVE_BUILD_FAILED",
                            "page=" + info.key + " lane=" + completed.lane()
                                    + " failure=" + cause.getClass().getSimpleName()
                                    + ':' + String.valueOf(cause.getMessage()));
                }
                continue;
            }
            MapRequestLane completedLane = completed.lane();
            if (!info.pendingCompletionRecorded) {
                pipelineTelemetry.recordExactBuildCompleted();
                info.pendingCompletionRecorded = true;
            }
            long current;
            synchronized (pages) {
                current = revisions.getOrDefault(info.key, 0L);
            }
            long currentSource = repository.getPageRevision(
                    info.key.globalPageX(), info.key.globalPageZ());
            boolean sourceAdvanced = result.sourceRevision() < currentSource;
            if (result.expectedRevision() != current
                    || result.sourceRevision() > currentSource
                    || !info.key.dimension().equals(dimension())) {
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = now;
                pipelineTelemetry.recordExactBuildDiscarded();
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_RESULT_STALE:" + info.key, 250L)) {
                    recorder.event("CAVE_RESULT_STALE",
                            "page=" + info.key + " lane=" + completedLane
                                    + " expected=" + result.expectedRevision()
                                    + " current=" + current + " source="
                                    + result.sourceRevision() + " current_source="
                                    + currentSource + " current_dimension=" + dimension());
                }
                continue;
            }
            if (sourceAdvanced) {
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_RESULT_SOURCE_ADVANCED:" + info.key,
                        250L)) {
                    recorder.event("CAVE_RESULT_SOURCE_ADVANCED",
                            "page=" + info.key + " lane=" + completedLane
                                    + " built_source=" + result.sourceRevision()
                                    + " current_source=" + currentSource
                                    + " known_columns=" + result.knownColumns());
                }
            }
            if (result.knownColumns() == 0 && !result.complete()) {
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingCompletionRecorded = false;
                info.noSourceRevision = result.sourceRevision();
                // Nothing in this build is authoritative yet. Keep the previous
                // atlas page untouched and retry when source capture advances.
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_PAGE_NO_SOURCE:" + info.key, 500L)) {
                    recorder.event("CAVE_PAGE_NO_SOURCE",
                            "page=" + info.key + " lane=" + completedLane
                                    + " complete=" + result.complete()
                                    + " source_revision=" + result.sourceRevision());
                }
                markIncomplete(info, now);
                continue;
            }
            MapGpuBudgetController gpuBudget = MapGpuBudgetController.getInstance();
            if (!gpuBudget.tryReserve(
                    MapGpuBudgetController.UploadKind.CAVE_EXACT,
                    completedLane, completedLane == MapRequestLane.MINIMAP)) {
                // Keep the completed result/future in the publication queue. The
                // immutable CPU page must not be rebuilt merely because this frame
                // spent its upload budget on a stronger foreground page.
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_GPU_BUDGET_DEFERRED", 500L)) {
                    MapGpuBudgetController.Snapshot gpu = gpuBudget.snapshot();
                    recorder.event("CAVE_GPU_BUDGET_DEFERRED",
                            "page=" + info.key + " lane=" + completedLane
                                    + " predicted_ms="
                                    + (gpu.caveExactPredictionNanos() / 1_000_000.0D)
                                    + " frame_budget_ms="
                                    + (gpu.frameBudgetNanos() / 1_000_000.0D)
                                    + " reserved_ms="
                                    + (gpu.reservedNanos() / 1_000_000.0D)
                                    + " denied=" + gpu.caveExactDeniedReservations());
                }
                completedBuilds.offer(completed);
                break;
            }

            boolean firstGpuPublication = !info.initialized;
            info.pending = null;
            info.pendingToken = null;
            info.pendingCompletionRecorded = false;
            ApplyOutcome outcome = apply(info, result.pixels(),
                    result.knownRows(), result.complete());
            info.pendingLane = null;
            if (!outcome.applied()) {
                markIncomplete(info, now);
                continue;
            }
            info.uploadedRevision = current;
            info.uploadedSourceRevision = result.sourceRevision();
            info.noSourceRevision = Long.MIN_VALUE;
            if (result.complete() && !sourceAdvanced) {
                info.nextRetryMs = 0L;
                clearSatisfiedRequest(info.key, now);
            } else {
                markIncomplete(info, now);
            }
            if (firstGpuPublication && outcome.hasContent()) {
                MapDebugRecorder.getInstance().event("CAVE_PAGE_GPU_READY",
                        "page=" + info.key + " lane=" + completedLane
                                + " known_columns=" + result.knownColumns()
                                + " complete=" + result.complete()
                                + " expected_revision=" + result.expectedRevision()
                                + " source_revision=" + result.sourceRevision());
            }
            published++;
        }
        trimPages();
        return published;
    }


    /**
     * Publication has its own latency ordering. CPU priority alone is not enough:
     * a completed fullscreen burst must not sit in front of a ready minimap page.
     */
    private CompletedBuild pollHighestPriorityCompleted(long now) {
        // PriorityBlockingQueue already orders by lane and fullscreen ordinal. Treat
        // ordinal as a preference, not a hard dependency: one absent or partial page
        // must never block every completed page behind it.
        return completedBuilds.poll();
    }

    private void scheduleBuilds(int budget, long deadline, long now) {
        int pending = pendingBuildCount();
        boolean minimapWaiting = hasActiveRequest(MapRequestLane.MINIMAP, now);
        boolean minimapRunning = pendingBuildCount(MapRequestLane.MINIMAP) > 0;
        // Keep one bounded overflow slot for the HUD hot set. Six fullscreen builds
        // may already be running when the player crosses into a cold minimap page.
        int pendingCap = minimapWaiting && !minimapRunning ? 7 : 6;
        budget = Math.min(budget, Math.max(0, pendingCap - pending));
        if (budget <= 0) return;
        PageRequest[] candidates;
        synchronized (pages) {
            candidates = requests.values().toArray(PageRequest[]::new);
        }

        int scheduled = 0;
        while (scheduled < budget && System.nanoTime() < deadline) {
            int bestIndex = -1;
            for (int index = 0; index < candidates.length; index++) {
                PageRequest candidate = candidates[index];
                if (candidate == null) continue;
                if (candidate.isExpired(now)) continue;
                if (bestIndex < 0 || higherPriority(
                        candidate, candidates[bestIndex], now)) bestIndex = index;
            }
            if (bestIndex < 0) break;

            PageRequest request = candidates[bestIndex];
            candidates[bestIndex] = null;
            PageKey key = request.key;
            MapRequestLane requestLane = request.effectiveLane(now);
            if (requestLane == null || !key.dimension().equals(dimension())) continue;
            int requestOrdinal = requestLane == MapRequestLane.FULLSCREEN
                    ? request.ordinalForLane(requestLane) : Integer.MAX_VALUE;

            long sourceRevision = repository.getPageRevision(
                    key.globalPageX(), key.globalPageZ());
            int projectionTopY = request.projectionTopY;
            PageInfo info;
            long revision;
            synchronized (pages) {
                info = pages.computeIfAbsent(key, PageInfo::new);
                revision = revisions.getOrDefault(key, 1L);
                if (info.pending != null || now < info.nextRetryMs) continue;
                // No-source is a repository state, not a timer. Rebuilding an
                // unchanged empty snapshot only burns CPU and keeps the request
                // lane busy; a repository revision advance re-enables it.
                if (!info.initialized && info.knownColumns == 0
                        && info.noSourceRevision == sourceRevision) continue;
                if (info.uploadedRevision == revision
                        && info.uploadedSourceRevision == sourceRevision) {
                    if (info.knownEmpty) {
                        PageRequest satisfied = requests.get(key);
                        if (satisfied != null) {
                            satisfied.clearAll();
                            if (satisfied.isExpired(now)) requests.remove(key);
                        }
                        continue;
                    }
                    if (info.initialized && info.atlasSlot >= 0) {
                        int fullColumns = CaveTextureAtlas.PAGE_SIZE
                                * CaveTextureAtlas.PAGE_SIZE;
                        if (info.knownColumns >= fullColumns) {
                            PageRequest satisfied = requests.get(key);
                            if (satisfied != null) {
                                satisfied.clearAll();
                                if (satisfied.isExpired(now)) requests.remove(key);
                            }
                        } else {
                            // The current source revision has already been applied.
                            // Wait for a repository revision change instead of
                            // rebuilding the same partial page every 80 ms.
                            info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
                        }
                        continue;
                    }
                    if (info.frontLods != null && info.knownColumns > 0) {
                        if (!MapGpuBudgetController.getInstance().tryReserve(
                                MapGpuBudgetController.UploadKind.CAVE_EXACT,
                                requestLane, requestLane == MapRequestLane.MINIMAP)) {
                            info.nextRetryMs = now + 16L;
                            continue;
                        }
                        if (restoreCavePageResidency(info, requestLane)) {
                            PageRequest satisfied = requests.get(key);
                            if (satisfied != null) {
                                satisfied.clearAll();
                                if (satisfied.isExpired(now)) requests.remove(key);
                            }
                        } else {
                            info.nextRetryMs = now + 16L;
                        }
                        continue;
                    }
                }

                var level = Minecraft.getInstance().level;
                ExactPageStateTracker.getInstance().transition(
                        stateKey(key), ExactPageState.CPU_READY,
                        requestLane, revision);
                ExactPageStateTracker.getInstance().transition(
                        stateKey(key), ExactPageState.BUILDING,
                        requestLane, revision);
                pipelineTelemetry.recordExactBuildQueued();
                long repositoryGeneration = repository.generation();
                MapCancellationToken token = new MapCancellationToken(() ->
                        key.dimension().equals(dimension())
                                && repository.isGenerationCurrent(repositoryGeneration));
                long queuedNanos = System.nanoTime();
                CompletableFuture<BuildResult> future = CompletableFuture.supplyAsync(() -> {
                    long buildStart = System.nanoTime();
                    pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_QUEUE,
                            Math.max(0L, buildStart - queuedNanos));
                    try {
                        token.checkpoint("cave-page-resolve-start");
                        telemetry.recordPageBuild();
                        CaveTileRepository.ResolvedPage resolved = repository.resolvePage(
                                key.view(), projectionTopY, level,
                                key.globalPageX(), key.globalPageZ());
                        token.checkpoint("cave-page-resolve-finished");
                        int[] styled = CavePageStyler.style(
                                resolved.pixels(), resolved.heights(),
                                resolved.topHeights(), resolved.flags(), resolved.light(),
                                resolved.overlayCounts(), resolved.overlayColors(),
                                resolved.overlayAlpha(), resolved.overlayY(),
                                resolved.overlayLight(), resolved.overlayFlags(),
                                key.view(), projectionTopY);
                        token.checkpoint("cave-page-style-finished");
                        return new BuildResult(revision, resolved.revision(), styled,
                                resolved.knownRows(), resolved.knownColumnCount(),
                                resolved.complete());
                    } finally {
                        pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_BUILD,
                                System.nanoTime() - buildStart);
                    }
                }, workers.dynamic(requestLane::executorPriority));
                info.pending = future;
                info.pendingToken = token;
                info.pendingLane = requestLane;
                info.pendingCompletionRecorded = false;
                future.whenComplete((result, throwable) ->
                        completedBuilds.offer(new CompletedBuild(
                                info, future, requestLane, requestOrdinal,
                                completedSequence.getAndIncrement())));
            }
            scheduled++;
        }
    }

    private static boolean higherPriority(PageRequest candidate,
            PageRequest current, long now) {
        int candidatePriority = candidate.effectivePriority(now);
        int currentPriority = current.effectivePriority(now);
        if (candidatePriority != currentPriority) {
            return candidatePriority > currentPriority;
        }
        MapRequestLane candidateLane = candidate.effectiveLane(now);
        MapRequestLane currentLane = current.effectiveLane(now);
        if (candidateLane != currentLane) {
            return candidateLane != null
                    && (currentLane == null || candidateLane.strongerThan(currentLane));
        }
        return candidate.latestSeenMs() > current.latestSeenMs();
    }

    private ApplyOutcome apply(PageInfo info, int[] pixels,
            long[] incomingKnownRows, boolean complete) {
        int pageSize = CaveTextureAtlas.PAGE_SIZE;
        int pixelCount = pageSize * pageSize;
        if (pixels == null || pixels.length < pixelCount
                || incomingKnownRows == null || incomingKnownRows.length < pageSize) {
            return ApplyOutcome.NOT_APPLIED;
        }

        info.ensureBuffers();
        int[] mergedBase = uploadScratchLods[0];
        System.arraycopy(info.frontLods[0], 0, mergedBase, 0, pixelCount);

        int addedKnownColumns = 0;
        if (complete) {
            System.arraycopy(pixels, 0, mergedBase, 0, pixelCount);
        } else {
            for (int y = 0; y < pageSize; y++) {
                long mask = incomingKnownRows[y];
                addedKnownColumns += Long.bitCount(mask & ~info.knownRows[y]);
                while (mask != 0L) {
                    int x = Long.numberOfTrailingZeros(mask);
                    int index = y * pageSize + x;
                    mergedBase[index] = pixels[index];
                    mask &= mask - 1L;
                }
            }
        }

        boolean hasContent = false;
        for (int index = 0; index < pixelCount; index++) {
            if (mergedBase[index] != 0) {
                hasContent = true;
                break;
            }
        }

        for (int lod = 1; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            downsample(uploadScratchLods[lod - 1], CaveTextureAtlas.lodSize(lod - 1),
                    uploadScratchLods[lod], CaveTextureAtlas.lodSize(lod));
        }
        telemetry.recordLodBuild();

        if (!hasContent) {
            commitCoverage(info, incomingKnownRows, complete,
                    addedKnownColumns, pixelCount);
            info.knownEmpty = info.knownColumns >= pixelCount;
            for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
                System.arraycopy(uploadScratchLods[lod], 0, info.frontLods[lod], 0,
                        uploadScratchLods[lod].length);
            }
            updateBranch(info);
            info.releaseAtlasSlot();
            ExactPageStateTracker.getInstance().transition(
                    stateKey(info.key), ExactPageState.KNOWN_EMPTY,
                    info.pendingLane, revisions.getOrDefault(info.key, 1L));
            return new ApplyOutcome(true, false);
        }
        info.knownEmpty = false;
        if (!ensureAtlasSlot(info)) return ApplyOutcome.NOT_APPLIED;

        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), ExactPageState.UPLOAD_QUEUED,
                info.pendingLane, revisions.getOrDefault(info.key, 1L));
        boolean uploaded = false;
        long exactUploadStart = System.nanoTime();
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            info.dirtyPlan.compute(info.initialized ? info.frontLods[lod] : null,
                    uploadScratchLods[lod], size);
            for (int rect = 0; rect < info.dirtyPlan.count(); rect++) {
                CaveTextureAtlas.DirtyRect dirty = info.dirtyPlan.rect(rect);
                atlas.upload(info.atlasSlot, lod, uploadScratchLods[lod], dirty);
                telemetry.recordDirtyRectangleUpload();
                uploaded = true;
            }
        }

        long exactUploadNanos = System.nanoTime() - exactUploadStart;
        pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_UPLOAD,
                exactUploadNanos);
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.CAVE_EXACT,
                exactUploadNanos);
        commitCoverage(info, incomingKnownRows, complete,
                addedKnownColumns, pixelCount);
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            System.arraycopy(uploadScratchLods[lod], 0, info.frontLods[lod], 0,
                    uploadScratchLods[lod].length);
        }
        updateBranch(info);
        if (uploaded) {
            telemetry.recordPageUpload();
            exactContentRevision.incrementAndGet();
        }
        boolean firstGpuPublication = !info.initialized;
        info.initialized = true;
        String residentKey = residencyKey(info.key);
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.CAVE_EXACT,
                4L * (64L * 64L + 32L * 32L + 16L * 16L + 8L * 8L),
                () -> evictExactPageForBudget(info.key));
        MapResidencyManager.getInstance().enforceBudget(
                residentKey, info.pendingLane);
        if (firstGpuPublication) indexResidentPage(info.key, 1);
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), ExactPageState.GPU_READY,
                info.pendingLane, revisions.getOrDefault(info.key, 1L));
        if (firstGpuPublication) pipelineTelemetry.recordExactGpuReady();
        return new ApplyOutcome(true, true);
    }

    private boolean restoreCavePageResidency(PageInfo info,
            MapRequestLane lane) {
        if (info == null || info.frontLods == null || info.knownColumns <= 0
                || info.knownEmpty) return false;
        if (!ensureAtlasSlot(info)) return false;
        long uploadStarted = System.nanoTime();
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            atlas.upload(info.atlasSlot, lod, info.frontLods[lod],
                    new CaveTextureAtlas.DirtyRect(0, 0, size - 1, size - 1));
        }
        long uploadNanos = System.nanoTime() - uploadStarted;
        pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_UPLOAD, uploadNanos);
        MapGpuBudgetController.getInstance().record(
                MapGpuBudgetController.UploadKind.CAVE_EXACT, uploadNanos);
        info.initialized = true;
        info.nextRetryMs = 0L;
        indexResidentPage(info.key, 1);
        String residentKey = residencyKey(info.key);
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.CAVE_EXACT,
                4L * (64L * 64L + 32L * 32L + 16L * 16L + 8L * 8L),
                () -> evictExactPageForBudget(info.key));
        MapResidencyManager.getInstance().enforceBudget(residentKey, lane);
        exactContentRevision.incrementAndGet();
        telemetry.recordPageUpload();
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), ExactPageState.GPU_READY,
                lane, info.uploadedRevision);
        return true;
    }

    private void updateBranch(PageInfo page) {
        // The LOD tree carries per-pixel known/complete coverage and merges each
        // dirty leaf into the existing branch. Publishing resolved leaves now is
        // therefore both stable and essential: one deferred chunk must not keep a
        // whole 4x4-chunk page (and every zoomed-out ancestor) black.
        int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
        if (page.frontLods == null || page.knownColumns <= 0) return;
        synchronized (pages) {
            long pageRevision = Math.max(1L, revisions.getOrDefault(
                    page.key, Math.max(1L, page.uploadedRevision)));
            MapRequestLane lane = page.pendingLane == null
                    ? MapRequestLane.FULLSCREEN : page.pendingLane;
            lodTree.updatePage(page.key.dimension(), page.key.view(), page.key.layerY(),
                    page.key.globalPageX(), page.key.globalPageZ(),
                    page.frontLods[0], page.knownRows,
                    page.knownColumns, page.knownColumns >= fullColumns,
                    pageRevision, lane);
        }
    }

    private boolean hasReplacementCoverage(PageInfo page) {
        if (page == null || page.knownEmpty) return true;
        long revision = Math.max(1L, page.uploadedRevision);
        return lodTree.coversPage(page.key.dimension(), page.key.view(),
                page.key.layerY(), page.key.globalPageX(), page.key.globalPageZ(),
                revision);
    }

    private void publishBranches(int budget, long deadline) {
        synchronized (pages) {
            lodTree.publish(budget, deadline);
        }
    }

    private static void commitCoverage(PageInfo info,
            long[] incomingKnownRows, boolean complete,
            int addedKnownColumns, int pixelCount) {
        if (complete) {
            java.util.Arrays.fill(info.knownRows, -1L);
            info.knownColumns = pixelCount;
            return;
        }
        for (int y = 0; y < info.knownRows.length; y++) {
            info.knownRows[y] |= incomingKnownRows[y];
        }
        info.knownColumns = Math.min(pixelCount,
                info.knownColumns + addedKnownColumns);
    }

    private static void markIncomplete(PageInfo info, long now) {
        // Preserve the last-good CPU/GPU revision. A partial page is visible and
        // reusable; only a newer repository revision should trigger another build.
        info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
    }


    private boolean evictExactPageForBudget(PageKey key) {
        if (key == null || renderBatchDepth > 0) return false;
        PageInfo retired;
        synchronized (pages) {
            retired = pages.get(key);
            if (retired == null || retired.pending != null
                    || retired.atlasSlot < 0 || !retired.initialized
                    || !hasReplacementCoverage(retired)) {
                return false;
            }
            // Preserve CPU data and request/revision state. Only GPU residency is
            // retired so a later visible request can re-upload without rereading
            // or reprojecting the world source.
            retired.releaseAtlasSlot();
        }
        telemetry.recordAtlasEviction();
        return true;
    }

    private boolean ensureAtlasSlot(PageInfo info) {
        if (info.atlasSlot >= 0) return true;
        int slot = atlas.acquireSlot();
        if (slot >= 0) {
            info.atlasSlot = slot;
            return true;
        }

        PageInfo victim = null;
        synchronized (pages) {
            if (renderBatchDepth > 0) return false;
            java.util.List<String> candidates = new java.util.ArrayList<>();
            java.util.Map<String, PageInfo> byResidencyKey = new java.util.HashMap<>();
            for (PageInfo candidate : pages.values()) {
                if (candidate == info || candidate.pending != null
                        || candidate.atlasSlot < 0
                        || !hasReplacementCoverage(candidate)) continue;
                String candidateKey = residencyKey(candidate.key);
                candidates.add(candidateKey);
                byResidencyKey.put(candidateKey, candidate);
            }
            String victimKey = MapResidencyManager.getInstance().chooseVictim(
                    candidates, residencyKey(info.key));
            victim = byResidencyKey.get(victimKey);
        }
        if (victim == null) return false;
        // Atlas pressure retires GPU residency only. Keep the coherent CPU page,
        // revision and source authority so revisiting the area is a cheap re-upload
        // rather than another NBT decode/full-height projection transaction.
        victim.releaseAtlasSlot();
        telemetry.recordAtlasEviction();
        slot = atlas.acquireSlot();
        if (slot < 0) return false;
        info.atlasSlot = slot;
        return true;
    }

    private static void downsample(int[] source, int sourceSize,
            int[] target, int targetSize) {
        for (int z = 0; z < targetSize; z++) {
            int sourceZ = z << 1;
            for (int x = 0; x < targetSize; x++) {
                int sourceX = x << 1;
                int p0 = source[sourceZ * sourceSize + sourceX];
                int p1 = source[sourceZ * sourceSize + sourceX + 1];
                int p2 = source[(sourceZ + 1) * sourceSize + sourceX];
                int p3 = source[(sourceZ + 1) * sourceSize + sourceX + 1];
                target[z * targetSize + x] = averageAbgr(p0, p1, p2, p3);
            }
        }
    }

    private static int averageAbgr(int p0, int p1, int p2, int p3) {
        int a0 = (p0 >>> 24) & 0xFF;
        int a1 = (p1 >>> 24) & 0xFF;
        int a2 = (p2 >>> 24) & 0xFF;
        int a3 = (p3 >>> 24) & 0xFF;
        int weight = a0 + a1 + a2 + a3;
        if (weight == 0) return 0;
        int red = ((p0 & 0xFF) * a0 + (p1 & 0xFF) * a1
                + (p2 & 0xFF) * a2 + (p3 & 0xFF) * a3) / weight;
        int green = (((p0 >>> 8) & 0xFF) * a0 + ((p1 >>> 8) & 0xFF) * a1
                + ((p2 >>> 8) & 0xFF) * a2 + ((p3 >>> 8) & 0xFF) * a3) / weight;
        int blue = (((p0 >>> 16) & 0xFF) * a0 + ((p1 >>> 16) & 0xFF) * a1
                + ((p2 >>> 16) & 0xFF) * a2 + ((p3 >>> 16) & 0xFF) * a3) / weight;
        int alpha = Math.max(Math.max(a0, a1), Math.max(a2, a3));
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private void pruneRequests(long now) {
        synchronized (pages) {
            var iterator = requests.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<PageKey, PageRequest> entry = iterator.next();
                PageRequest request = entry.getValue();
                request.expireLaneObservations(now);
                if (!request.isExpired(now)) continue;
                PageKey key = entry.getKey();
                iterator.remove();
                PageInfo info = pages.get(key);
                if (info != null && info.pending != null) {
                    if (info.pendingToken != null) info.pendingToken.cancel();
                    if (info.pending.cancel(false)) {
                        pipelineTelemetry.recordTaskCancelledBeforeRun();
                    }
                    info.pending = null;
                    info.pendingToken = null;
                    info.pendingLane = null;
                    info.nextRetryMs = 0L;
                }
                if (!pages.containsKey(key)) revisions.remove(key);
            }
        }
    }

    private void trimPages() {
        List<PageInfo> retired = new ArrayList<>();
        synchronized (pages) {
            while (pages.size() > MAX_PAGES) {
                var iterator = pages.entrySet().iterator();
                PageInfo selected = null;
                while (iterator.hasNext()) {
                    PageInfo candidate = iterator.next().getValue();
                    if (candidate.pending == null
                            && (!candidate.initialized
                                    || hasReplacementCoverage(candidate))) {
                        selected = candidate;
                        iterator.remove();
                        if (!requests.containsKey(candidate.key)) revisions.remove(candidate.key);
                        break;
                    }
                }
                if (selected == null) break;
                retired.add(selected);
            }
        }
        for (PageInfo info : retired) {
            synchronized (pages) {
                if (renderBatchDepth > 0) deferredCloses.add(info);
                else info.close();
            }
        }
    }

    private static PageKey key(CaveView view, int layerY, int globalPageX, int globalPageZ) {
        return new PageKey(dimension(), view, normalizedLayer(view, layerY),
                globalPageX, globalPageZ);
    }


    private static String residencyKey(PageKey key) {
        return key == null ? "cave:unknown"
                : "cave:" + key.dimension() + ':' + key.view() + ':'
                        + key.layerY() + ':' + key.globalPageX() + ':'
                        + key.globalPageZ();
    }

    private static String stateKey(PageKey key) {
        return "cave:" + key.dimension() + ':' + key.view() + ':' + key.layerY()
                + ':' + key.globalPageX() + ':' + key.globalPageZ();
    }

    private static int normalizedLayer(CaveView view, int layerY) {
        return DenseCaveTile.normalizeLayer(view, layerY);
    }

    private static String dimension() {
        return MapManager.getInstance().getDimensionCacheKey();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record PageKey(String dimension, CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
    }

    private record ResidentNodeKey(String dimension, CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
    }

    private static final class VisiblePlanner {
        private String dimension = "";
        private CaveView view;
        private int layerY = Integer.MIN_VALUE;
        private int projectionTopY = Integer.MIN_VALUE;
        private int minPageX = Integer.MIN_VALUE;
        private int maxPageX = Integer.MIN_VALUE;
        private int minPageZ = Integer.MIN_VALUE;
        private int maxPageZ = Integer.MIN_VALUE;
        private int focusPageX = Integer.MIN_VALUE;
        private int focusPageZ = Integer.MIN_VALUE;
        private long lastEnumerationMs;
        private long[] pagePlan = new long[0];
        private int pageCursor;
        private int updateSliceIndex;
        private long requestCompletedCycles;
        private float scale = 1.0f;
        private long nextRestartMs;

        private void reset(String dimension, CaveView view, int layerY,
                int projectionTopY, int minPageX, int maxPageX,
                int minPageZ, int maxPageZ, int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            boolean continuousPan = lane == MapRequestLane.FULLSCREEN
                    && this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && rectanglesOverlap(this.minPageX, this.maxPageX,
                            this.minPageZ, this.maxPageZ,
                            minPageX, maxPageX, minPageZ, maxPageZ);
            int previousMinPageX = this.minPageX;
            int previousMaxPageX = this.maxPageX;
            int previousMinPageZ = this.minPageZ;
            int previousMaxPageZ = this.maxPageZ;
            this.dimension = dimension;
            this.view = view;
            this.layerY = layerY;
            this.projectionTopY = projectionTopY;
            this.minPageX = minPageX;
            this.maxPageX = maxPageX;
            this.minPageZ = minPageZ;
            this.maxPageZ = maxPageZ;
            this.focusPageX = centerPageX;
            this.focusPageZ = centerPageZ;
            pagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, lane == MapRequestLane.FULLSCREEN,
                    continuousPan, previousMinPageX, previousMaxPageX,
                    previousMinPageZ, previousMaxPageZ);
            pageCursor = 0;
            updateSliceIndex = 0;
            requestCompletedCycles = 0L;
            nextRestartMs = 0L;
        }

        private static boolean rectanglesOverlap(int firstMinX, int firstMaxX,
                int firstMinZ, int firstMaxZ, int secondMinX, int secondMaxX,
                int secondMinZ, int secondMaxZ) {
            return firstMinX <= secondMaxX && firstMaxX >= secondMinX
                    && firstMinZ <= secondMaxZ && firstMaxZ >= secondMinZ;
        }

        private void clear() {
            dimension = "";
            view = null;
            layerY = Integer.MIN_VALUE;
            projectionTopY = Integer.MIN_VALUE;
            minPageX = maxPageX = minPageZ = maxPageZ = Integer.MIN_VALUE;
            focusPageX = focusPageZ = Integer.MIN_VALUE;
            lastEnumerationMs = 0L;
            pagePlan = new long[0];
            pageCursor = 0;
            updateSliceIndex = 0;
            requestCompletedCycles = 0L;
            scale = 1.0f;
            nextRestartMs = 0L;
        }
    }

    private static final class PageRequest {
        private final PageKey key;
        private final long[] lastSeenByLane = new long[MapRequestLane.values().length];
        private final long[] firstSeenByLane = new long[MapRequestLane.values().length];
        private final int[] priorityByLane = new int[MapRequestLane.values().length];
        private final int[] ordinalByLane = new int[MapRequestLane.values().length];
        private int projectionTopY;

        private PageRequest(PageKey key, int projectionTopY) {
            this.key = key;
            this.projectionTopY = projectionTopY;
            java.util.Arrays.fill(priorityByLane, Integer.MIN_VALUE);
            java.util.Arrays.fill(ordinalByLane, Integer.MAX_VALUE);
        }

        private void retarget(int projectionTopY) {
            this.projectionTopY = projectionTopY;
            java.util.Arrays.fill(lastSeenByLane, 0L);
            java.util.Arrays.fill(firstSeenByLane, 0L);
            java.util.Arrays.fill(priorityByLane, Integer.MIN_VALUE);
            java.util.Arrays.fill(ordinalByLane, Integer.MAX_VALUE);
        }

        private void observe(MapRequestLane lane, int priority, long now,
                int fullscreenOrdinal) {
            int index = lane.ordinal();
            lastSeenByLane[index] = now;
            if (firstSeenByLane[index] == 0L) firstSeenByLane[index] = now;
            priorityByLane[index] = Math.max(priorityByLane[index], priority);
            if (lane == MapRequestLane.FULLSCREEN) {
                ordinalByLane[index] = Math.min(ordinalByLane[index], fullscreenOrdinal);
            }
        }

        private boolean isLaneActive(MapRequestLane lane, long now) {
            long seen = lastSeenByLane[lane.ordinal()];
            return seen != 0L && now - seen <= lane.requestTtlMs();
        }

        private int priorityForLane(MapRequestLane lane) {
            return priorityByLane[lane.ordinal()];
        }

        private long seenForLane(MapRequestLane lane) {
            return lastSeenByLane[lane.ordinal()];
        }

        private void clearLane(MapRequestLane lane) {
            int index = lane.ordinal();
            lastSeenByLane[index] = 0L;
            firstSeenByLane[index] = 0L;
            priorityByLane[index] = Integer.MIN_VALUE;
            ordinalByLane[index] = Integer.MAX_VALUE;
        }

        private void clearAll() {
            java.util.Arrays.fill(lastSeenByLane, 0L);
            java.util.Arrays.fill(firstSeenByLane, 0L);
            java.util.Arrays.fill(priorityByLane, Integer.MIN_VALUE);
            java.util.Arrays.fill(ordinalByLane, Integer.MAX_VALUE);
        }

        private void expireLaneObservations(long now) {
            for (MapRequestLane lane : MapRequestLane.values()) {
                int index = lane.ordinal();
                long seen = lastSeenByLane[index];
                if (seen == 0L || now - seen <= lane.requestTtlMs()) continue;
                lastSeenByLane[index] = 0L;
                firstSeenByLane[index] = 0L;
                priorityByLane[index] = Integer.MIN_VALUE;
                ordinalByLane[index] = Integer.MAX_VALUE;
            }
        }

        private int ordinalForLane(MapRequestLane lane) {
            return ordinalByLane[lane.ordinal()];
        }

        private MapRequestLane effectiveLane(long now) {
            MapRequestLane best = null;
            for (MapRequestLane lane : MapRequestLane.values()) {
                long seen = lastSeenByLane[lane.ordinal()];
                if (seen == 0L || now - seen > lane.requestTtlMs()) continue;
                if (lane.strongerThan(best)) best = lane;
            }
            return best;
        }

        private int effectivePriority(long now) {
            int best = Integer.MIN_VALUE;
            for (MapRequestLane lane : MapRequestLane.values()) {
                int index = lane.ordinal();
                long seen = lastSeenByLane[index];
                if (seen == 0L || now - seen > lane.requestTtlMs()) continue;
                long first = firstSeenByLane[index] == 0L
                        ? seen : firstSeenByLane[index];
                long ageMs = Math.max(0L, now - first);
                // Xaero re-evaluates a small nearest set every frame. Stable
                // scanline work needs equivalent fairness: a valid frontier page
                // gains priority while waiting, but never crosses a stronger lane.
                int ageBonus = (int) Math.min(420_000L, ageMs * 210L);
                best = Math.max(best, priorityByLane[index] + ageBonus);
            }
            return best;
        }

        private long latestSeenMs() {
            long latest = 0L;
            for (long seen : lastSeenByLane) latest = Math.max(latest, seen);
            return latest;
        }

        private boolean isExpired(long now) {
            return effectiveLane(now) == null;
        }
    }

    private final class PageInfo {
        private final PageKey key;
        private final DirtyPlan dirtyPlan = new DirtyPlan();
        private int atlasSlot = -1;
        private int[][] frontLods;
        private CompletableFuture<BuildResult> pending;
        private MapCancellationToken pendingToken;
        private MapRequestLane pendingLane;
        private boolean pendingCompletionRecorded;
        private long uploadedRevision;
        private long uploadedSourceRevision = Long.MIN_VALUE;
        private long noSourceRevision = Long.MIN_VALUE;
        private long nextRetryMs;
        private boolean initialized;
        private boolean knownEmpty;
        /** Authoritative coverage accumulated across partial page builds. */
        private final long[] knownRows = new long[CaveTextureAtlas.PAGE_SIZE];
        private int knownColumns;

        private PageInfo(PageKey key) {
            this.key = key;
        }

        private void ensureBuffers() {
            if (frontLods != null) return;
            frontLods = allocateLodBuffers();
        }

        private void releaseAtlasSlot() {
            if (initialized) indexResidentPage(key, -1);
            if (atlasSlot >= 0) atlas.releaseSlot(atlasSlot);
            MapResidencyManager.getInstance().remove(residencyKey(key));
            exactContentRevision.incrementAndGet();
            atlasSlot = -1;
            initialized = false;
            ExactPageStateTracker.getInstance().transition(
                    stateKey(key), ExactPageState.GPU_EVICTED,
                    pendingLane, revisions.getOrDefault(key, uploadedRevision));
        }

        private void close() {
            if (pendingToken != null) pendingToken.cancel();
            if (pending != null && pending.cancel(false)) {
                pipelineTelemetry.recordTaskCancelledBeforeRun();
            }
            pending = null;
            pendingToken = null;
            pendingLane = null;
            pendingCompletionRecorded = false;
            releaseAtlasSlot();
            frontLods = null;
        }
    }

    private static int[][] allocateLodBuffers() {
        int[][] buffers = new int[CaveTextureAtlas.LOD_COUNT][];
        for (int lod = 0; lod < buffers.length; lod++) {
            int size = CaveTextureAtlas.lodSize(lod);
            buffers[lod] = new int[size * size];
        }
        return buffers;
    }

    /** Reusable row-run dirty plan; no per-update rectangle list allocation. */
    private static final class DirtyPlan {
        private static final int MAX_RECTS = 8;
        private final int[] minX = new int[MAX_RECTS];
        private final int[] minY = new int[MAX_RECTS];
        private final int[] maxX = new int[MAX_RECTS];
        private final int[] maxY = new int[MAX_RECTS];
        private int count;

        private void compute(int[] previous, int[] current, int size) {
            count = 0;
            if (previous == null) {
                add(0, 0, size - 1, size - 1);
                return;
            }

            int boundMinX = size;
            int boundMinY = size;
            int boundMaxX = -1;
            int boundMaxY = -1;
            int changedPixels = 0;
            int activeStartY = -1;
            int activeMinX = size;
            int activeMaxX = -1;

            for (int y = 0; y < size; y++) {
                int rowMinX = size;
                int rowMaxX = -1;
                int row = y * size;
                for (int x = 0; x < size; x++) {
                    if (previous[row + x] == current[row + x]) continue;
                    rowMinX = Math.min(rowMinX, x);
                    rowMaxX = Math.max(rowMaxX, x);
                    changedPixels++;
                }
                if (rowMaxX >= rowMinX) {
                    boundMinX = Math.min(boundMinX, rowMinX);
                    boundMaxX = Math.max(boundMaxX, rowMaxX);
                    boundMinY = Math.min(boundMinY, y);
                    boundMaxY = Math.max(boundMaxY, y);
                    if (activeStartY < 0) activeStartY = y;
                    activeMinX = Math.min(activeMinX, rowMinX);
                    activeMaxX = Math.max(activeMaxX, rowMaxX);
                } else if (activeStartY >= 0) {
                    if (!add(activeMinX, activeStartY, activeMaxX, y - 1)) {
                        setBounding(0, 0, size - 1, size - 1);
                        return;
                    }
                    activeStartY = -1;
                    activeMinX = size;
                    activeMaxX = -1;
                }
            }
            if (activeStartY >= 0
                    && !add(activeMinX, activeStartY, activeMaxX, size - 1)) {
                setBounding(0, 0, size - 1, size - 1);
                return;
            }
            if (changedPixels == 0) return;

            int plannedArea = 0;
            for (int i = 0; i < count; i++) {
                plannedArea += (maxX[i] - minX[i] + 1) * (maxY[i] - minY[i] + 1);
            }
            int fullArea = size * size;
            if (plannedArea * 10 >= fullArea * 7) {
                setBounding(0, 0, size - 1, size - 1);
            }
        }

        private boolean add(int x0, int y0, int x1, int y1) {
            if (count >= MAX_RECTS) return false;
            minX[count] = x0;
            minY[count] = y0;
            maxX[count] = x1;
            maxY[count] = y1;
            count++;
            return true;
        }

        private void setBounding(int x0, int y0, int x1, int y1) {
            count = 0;
            if (x1 >= x0 && y1 >= y0) add(x0, y0, x1, y1);
        }

        private int count() {
            return count;
        }

        private CaveTextureAtlas.DirtyRect rect(int index) {
            return new CaveTextureAtlas.DirtyRect(minX[index], minY[index],
                    maxX[index], maxY[index]);
        }
    }

    public record DebugSnapshot(int pages, int requests, int pendingBuilds,
            int completedBuilds, int initializedPages, int partialPages,
            int knownEmptyPages, int residentPages, int fullscreenSlice,
            int fullscreenPlanPages) {
        public static DebugSnapshot empty() {
            return new DebugSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private record CompletedBuild(PageInfo info,
            CompletableFuture<BuildResult> future,
            MapRequestLane lane,
            int fullscreenOrdinal,
            long sequence) implements Comparable<CompletedBuild> {
        @Override
        public int compareTo(CompletedBuild other) {
            int thisRank = lane == null ? 0 : lane.rank();
            int otherRank = other.lane == null ? 0 : other.lane.rank();
            int byLane = Integer.compare(otherRank, thisRank);
            if (byLane != 0) return byLane;
            if (lane == MapRequestLane.FULLSCREEN
                    && other.lane == MapRequestLane.FULLSCREEN) {
                int byOrdinal = Integer.compare(fullscreenOrdinal,
                        other.fullscreenOrdinal);
                if (byOrdinal != 0) return byOrdinal;
            }
            return Long.compare(sequence, other.sequence);
        }
    }

    private record ApplyOutcome(boolean applied, boolean hasContent) {
        private static final ApplyOutcome NOT_APPLIED =
                new ApplyOutcome(false, false);
    }

    private record BuildResult(long expectedRevision, long sourceRevision,
            int[] pixels, long[] knownRows, int knownColumns, boolean complete) {
    }
}
