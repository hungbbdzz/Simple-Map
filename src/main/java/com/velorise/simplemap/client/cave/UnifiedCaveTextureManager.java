package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapActivityGate;
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
import com.velorise.simplemap.client.gpu.MapGpuPageTableService;
import com.velorise.simplemap.client.gpu.TileKey;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.session.MapSessionManager;
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
    private static final int MAX_PAGES = Math.max(CaveTextureAtlas.SLOT_COUNT,
            Math.min(3072, CaveTextureAtlas.SLOT_COUNT * 2));
    private static final int MAX_REQUESTS = 4096;
    private static final long INCOMPLETE_RETRY_MS = 80L;
    private static final long FAILED_RETRY_MS = 250L;
    /**
     * A 64x64 cave page is fed by sixteen central chunks plus a one-chunk halo.
     * World-save/live capture commits those leaves independently, so the summed
     * page revision may advance dozens of times while one coherent page is being
     * assembled. Once a usable fallback exists, wait for a short quiet window
     * instead of rebuilding after every individual leaf commit. The hard burst
     * limit guarantees that a permanently noisy page still receives progressive
     * updates.
     */
    private static final long SOURCE_QUIET_MS = 90L;
    private static final long SOURCE_BURST_MAX_MS = 400L;
    /** Two 16x16 leaves worth of newly authoritative pixels justify publishing a
     * stale-in-flight result immediately; smaller deltas are coalesced. */
    private static final int SOURCE_PROGRESS_MIN_COLUMNS = 512;
    /**
     * Fullscreen workers may prepare a short centre-out runway, but render-visible
     * publication advances through one deterministic frontier. This preserves CPU
     * parallelism without exposing scattered centre/lower islands to the player.
     */
    private static final int FULLSCREEN_BUILD_AHEAD_PAGES = 24;
    /** A ready run is revealed as one compact visual burst. */
    private static final int FULLSCREEN_PUBLICATION_BURST = 12;
    /**
     * A CPU-ready page that lost the atlas race is retried from the immutable
     * completed payload. Rebuilding the projection would multiply IO/CPU work while
     * the atlas is saturated.
     */
    private static final long ATLAS_PUBLICATION_RETRY_MS = 40L;
    /**
     * Pages outside every live viewport may surrender GPU residency without first
     * waiting for branch replacement. Their coherent CPU LODs remain attached and
     * can be restored cheaply when revisited.
     */
    private static final long OFFSCREEN_EVICTION_GRACE_MS = 120L;
    /** Keep two exact-page rings owned while the fullscreen camera pans. */
    private static final int FULLSCREEN_STICKY_HALO_PAGES = 2;
    private static final int FULLSCREEN_RECENTER_THRESHOLD_PAGES = 2;
    private static final long ACTIVE_PLANNER_GRACE_MS = 1_000L;
    /** Adaptive visible restyle sweep after palette/profile changes. */
    private static final long STYLE_REFRESH_WINDOW_MS = 2_500L;
    /** Any authoritative column is useful. Unknown texels remain masked and the
     * existing screen-space policy hides partial exact leaves at far zoom. Requiring
     * a full 16x16 chunk kept explored cave pages black when archive/source capture
     * arrived incrementally. */
    private static final int FIRST_PUBLICATION_MIN_KNOWN_COLUMNS = 256;
    /** Partial exact pages are useful near the player, but at smaller screen sizes
     * they visually tear against a stable branch backdrop. */
    private static final float PARTIAL_EXACT_MIN_SCREEN_PIXELS = 16.0f;
    /** Layered continuity is committed at Minecraft chunk granularity. */
    private static final int PROJECTION_TILE_SIZE = 16;
    private static final int PROJECTION_TILES_PER_PAGE =
            CaveTextureAtlas.PAGE_SIZE / PROJECTION_TILE_SIZE;
    private static final int PROJECTION_TILE_COUNT =
            PROJECTION_TILES_PER_PAGE * PROJECTION_TILES_PER_PAGE;
    private static final long PROJECTION_TILE_ROW_MASK = 0xFFFFL;
    private static final MapRequestLane[] REQUEST_LANES = MapRequestLane.values();
    private static final UnifiedCaveTextureManager INSTANCE = new UnifiedCaveTextureManager();

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
    private final java.util.concurrent.ConcurrentHashMap<ResidentNodeKey, Integer> residentNodeCounts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<PageKey, PageRequest> requests = new LinkedHashMap<>(128, 0.75f, true);
    private final Map<PageKey, Long> revisions = new HashMap<>();
    /** Exact Top-Y currently owning each retained Layered 16-block band. */
    private final Map<ProjectionBandKey, Integer> activeLayerProjections = new HashMap<>();
    private final List<PageInfo> deferredCloses = new ArrayList<>();
    /** Strict publication ordering: minimap completions can never sit behind a
     * large FIFO burst of fullscreen builds. */
    private final PriorityBlockingQueue<CompletedBuild> completedBuilds =
            new PriorityBlockingQueue<>();
    /** Render-thread scratch used when a later fullscreen completion is already
     * ready but must wait behind the centre-out publication frontier. */
    private final ArrayList<CompletedBuild> completedPollScratch =
            new ArrayList<>(FULLSCREEN_BUILD_AHEAD_PAGES + 4);
    private final List<PageRequest> candidateBuffer = new ArrayList<>(128);
    private final AtomicLong completedSequence = new AtomicLong();
    /** Exact-cave render revision independent from unrelated surface/legacy uploads. */
    private final AtomicLong exactTopologyRevision = new AtomicLong();
    private final EnumMap<MapRequestLane, VisiblePlanner> visiblePlans =
            new EnumMap<>(MapRequestLane.class);

    private int renderBatchDepth;
    /** Monotonic renderer epoch; current/previous-frame leaves are never victims. */
    private long renderEpoch;
    /** Monotonic first-residency order used to distinguish old cached leaves from
     * newly revealed leaves in the active fullscreen centre-out generation. */
    private long gpuPublicationSequence;
    private long lastUploadMs;
    private volatile long styleRefreshUntilMs;
    private long observedAtlasGeneration = Long.MIN_VALUE;
    /** Global cadence aligns Layered fullscreen page commits into one viewport batch. */
    private long nextFullscreenLayeredPublicationMs;
    private int observedFullscreenLayeredProjectionTopY = Integer.MIN_VALUE;
    private boolean fullscreenLayeredPublicationWindowOpen = true;
    /** Working-set authority used to evict historical mode residency first. */
    private volatile CaveView preferredView;
    private volatile String preferredDimension = "";

    private UnifiedCaveTextureManager() {
        for (MapRequestLane lane : REQUEST_LANES) {
            visiblePlans.put(lane, new VisiblePlanner());
        }
    }

    public static UnifiedCaveTextureManager getInstance() {
        return INSTANCE;
    }

    /**
     * Transfers visible request ownership to the newly selected cave view. Warm
     * FULL/LAYERED atlas pages remain resident as last-good frames; ordinary atlas
     * pressure evicts the inactive view first when the selected view needs space.
     */
    public void onModeChanged(CaveView nextView) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> onModeChanged(nextView));
            return;
        }
        synchronized (pages) {
            preferredDimension = dimension();
            preferredView = nextView;
            for (VisiblePlanner planner : visiblePlans.values()) planner.clear();
            for (PageRequest request : requests.values()) {
                if (!preferredDimension.equals(request.key.dimension())) continue;
                if (nextView != null && request.key.view() == nextView) continue;
                request.clearLane(MapRequestLane.MINIMAP);
                request.clearLane(MapRequestLane.FULLSCREEN);
            }
            long now = System.currentTimeMillis();
            requests.entrySet().removeIf(entry -> entry.getValue().isExpired(now));

            for (PageInfo info : pages.values()) {
                if (!preferredDimension.equals(info.key.dimension())) continue;
                if (nextView != null && info.key.view() == nextView) continue;
                if (info.pending != null) detachPendingLocked(info, true);
            }
            nextFullscreenLayeredPublicationMs = 0L;
            observedFullscreenLayeredProjectionTopY = Integer.MIN_VALUE;
            fullscreenLayeredPublicationWindowOpen = true;
        }
        exactTopologyRevision.incrementAndGet();
    }


    /**
     * Revision used by cave render-plan caching. It changes only for cave exact or
     * cave branch residency, so Surface/legacy publication cannot invalidate a
     * stable Cave Map plan every tick.
     */
    public long contentRevision() {
        return exactTopologyRevision.get() + lodTree.contentRevision();
    }

    /** Branch-only far zoom must not rebuild for unrelated exact-page uploads. */
    public long branchContentRevision() {
        return lodTree.contentRevision();
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
            // Future-aware scheduling now gives cancellation a terminal callback.
            // Once this lane is hidden, detach only builds that no other live lane
            // still owns. Retaining them until completion caused hundreds of stale
            // results after repeated Surface/Layered/Full transitions and let the
            // diagnostic BUILDING state grow while every worker queue was empty.
            for (PageInfo info : pages.values()) {
                if (info.pending == null || info.pendingLane != lane) continue;
                if (isPendingStillWantedLocked(info, now)) continue;
                detachPendingLocked(info, true);
            }
            VisiblePlanner planner = visiblePlans.get(lane);
            if (planner != null) planner.clear();
        }
    }

    public int pendingBuildCount() {
        return (int) (pendingBuildCounts() >>> 32);
    }

    private int pendingBuildCount(MapRequestLane lane) {
        synchronized (pages) {
            int count = 0;
            // Every attached build owns a live PageRequest lease. The request map
            // is bounded to the lane shortlists (normally <= 48), while the page
            // cache can contain thousands of historical entries at far zoom.
            for (PageRequest request : requests.values()) {
                PageInfo info = pages.get(request.key);
                if (info != null && info.pending != null
                        && info.pendingLane == lane) count++;
            }
            return count;
        }
    }

    /** Packs total pending builds in the high word and minimap builds in the low. */
    private long pendingBuildCounts() {
        synchronized (pages) {
            int total = 0;
            int minimap = 0;
            for (PageRequest request : requests.values()) {
                PageInfo info = pages.get(request.key);
                if (info == null || info.pending == null) continue;
                total++;
                if (info.pendingLane == MapRequestLane.MINIMAP) minimap++;
            }
            return ((long) total << 32) | (minimap & 0xFFFF_FFFFL);
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
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        layerY = projectionTopY(view, layerY);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        String currentDimension = dimension();
        if (preferredView != view || !currentDimension.equals(preferredDimension)) {
            onModeChanged(view);
        }
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
        int rawMinPageX = minPageX;
        int rawMaxPageX = maxPageX;
        int rawMinPageZ = minPageZ;
        int rawMaxPageZ = maxPageZ;
        int normalizedLayerY = normalizedLayer(view, layerY);
        long now = System.currentTimeMillis();
        planner.lastDemandMs = now;
        int rawFocusPageX = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(focusX));
        int rawFocusPageZ = MapPageLayout.globalPageFromBlock(
                (int) Math.floor(focusZ));
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            if (planner.containsVisibleViewport(currentDimension, view,
                    normalizedLayerY, layerY,
                    rawMinPageX, rawMaxPageX, rawMinPageZ, rawMaxPageZ)) {
                // Keep the existing halo window while the real screen remains
                // inside it. Previously the halo shifted by one page whenever the
                // camera crossed a 64-block boundary, rebuilding a plan and handing
                // off hundreds of source/build owners despite almost total overlap.
                minPageX = planner.minPageX;
                maxPageX = planner.maxPageX;
                minPageZ = planner.minPageZ;
                maxPageZ = planner.maxPageZ;
            } else {
                minPageX -= FULLSCREEN_STICKY_HALO_PAGES;
                maxPageX += FULLSCREEN_STICKY_HALO_PAGES;
                minPageZ -= FULLSCREEN_STICKY_HALO_PAGES;
                maxPageZ += FULLSCREEN_STICKY_HALO_PAGES;
            }
        }
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
        if (view == CaveView.LAYERED) {
            activateLayerProjection(currentDimension, normalizedLayerY, layerY);
        }
        boolean focusChanged = centerPageX != planner.focusPageX
                || centerPageZ != planner.focusPageZ;
        boolean changed = !currentDimension.equals(planner.dimension)
                || view != planner.view
                || normalizedLayerY != planner.layerY
                || layerY != planner.projectionTopY
                || minPageX != planner.minPageX || maxPageX != planner.maxPageX
                || minPageZ != planner.minPageZ || maxPageZ != planner.maxPageZ
                || (effectiveLane != MapRequestLane.FULLSCREEN && focusChanged);
        boolean recentered = !changed && effectiveLane == MapRequestLane.FULLSCREEN
                && planner.shouldRecenter(centerPageX, centerPageZ,
                        FULLSCREEN_RECENTER_THRESHOLD_PAGES);
        if (!changed && !recentered && now - planner.lastEnumerationMs
                < CaveScreenSpacePolicy.exactEnumerationRetryMs(scale, effectiveLane)) return;
        planner.lastEnumerationMs = now;

        if (changed) {
            boolean continuousFullscreenPan = effectiveLane == MapRequestLane.FULLSCREEN
                    && planner.sameProjectionOverlap(currentDimension, view,
                            normalizedLayerY, layerY,
                            minPageX, maxPageX, minPageZ, maxPageZ);
            planner.reset(currentDimension, view, normalizedLayerY, layerY,
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, effectiveLane);
            if (effectiveLane == MapRequestLane.FULLSCREEN) {
                planner.baselineGpuPublicationSequence = gpuPublicationSequence;
                lodTree.prioritizeViewport(currentDimension, view,
                        normalizedLayerY, minPageX, maxPageX,
                        minPageZ, maxPageZ);
            }
            if (continuousFullscreenPan) {
                handoffFullscreenViewport(planner);
            } else {
                retireLaneOutside(effectiveLane, currentDimension, view,
                        normalizedLayerY, minPageX, maxPageX, minPageZ, maxPageZ);
            }
        } else if (recentered) {
            planner.recenter(centerPageX, centerPageZ, effectiveLane);
            planner.baselineGpuPublicationSequence = gpuPublicationSequence;
            lodTree.prioritizeViewport(currentDimension, view,
                    normalizedLayerY, minPageX, maxPageX,
                    minPageZ, maxPageZ);
            handoffFullscreenViewport(planner);
        } else {
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

    /** Rebase every overlapping transaction. Source-waiting, queued, in-flight and
     * CPU-ready pages all represent reusable work; restricting retention to
     * {@code info.pending != null} repeatedly revoked pages before they acquired a
     * build slot and was the primary cause of black cave viewports while panning. */
    private void handoffFullscreenViewport(VisiblePlanner planner) {
        synchronized (pages) {
            long now = System.currentTimeMillis();
            int retained = 0;
            int retired = 0;
            int detached = 0;
            for (PageRequest request : requests.values()) {
                int ordinal = planner.ordinalOf(
                        request.key.globalPageX(), request.key.globalPageZ());
                boolean overlapping = ordinal >= 0
                        && request.projectionTopY == planner.projectionTopY;
                if (overlapping) {
                    int priority = MapRequestLane.FULLSCREEN.priorityBase() + 220_000
                            - Math.min(180_000, ordinal * 250);
                    request.rebase(MapRequestLane.FULLSCREEN,
                            priority, now, ordinal);
                    retained++;
                } else {
                    request.clearLane(MapRequestLane.FULLSCREEN);
                    retired++;
                }
            }
            requests.entrySet().removeIf(entry -> entry.getValue().isExpired(now));

            // pendingLane records where a task started, not who owns it now. Check
            // current request ownership for every pending page so a historical lane
            // cannot retain an exact-build slot after viewport handoff.
            for (PageInfo info : pages.values()) {
                if (info.pending == null || isPendingStillWantedLocked(info, now)) continue;
                detachPendingLocked(info, true);
                detached++;
            }

            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_VIEWPORT_HANDOFF", 100L)) {
                recorder.event("CAVE_VIEWPORT_HANDOFF",
                        "retained=" + retained + " retired=" + retired
                                + " detached=" + detached
                                + " pages=" + planner.pagePlan.length);
            }
        }
    }

    private void retireLaneOutside(MapRequestLane lane, String dimension,
            CaveView view, int normalizedLayerY, int minPageX, int maxPageX,
            int minPageZ, int maxPageZ) {
        synchronized (pages) {
            long now = System.currentTimeMillis();
            for (PageRequest request : requests.values()) {
                PageKey key = request.key;
                boolean retained = lane != MapRequestLane.FULLSCREEN
                        && key.dimension().equals(dimension)
                        && key.view() == view && key.layerY() == normalizedLayerY
                        && key.globalPageX() >= minPageX
                        && key.globalPageX() <= maxPageX
                        && key.globalPageZ() >= minPageZ
                        && key.globalPageZ() <= maxPageZ;
                // Fullscreen pan/zoom starts a fresh centre-out generation. Retaining
                // the old ordinal/age for overlapping pages lets a former centre or
                // lower page outrank the new centre-out frontier and recreates the
                // scattered-island pattern the deterministic plan is meant to avoid.
                if (!retained) request.clearLane(lane);
            }
            requests.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
            // A viewport-scoped build that no longer has any live lane owner must
            // not retain one of the six exact-build slots. Its source/style result
            // is reusable only after being requested again; cancellation here is
            // cheaper than completing and discarding it after several mode/pan
            // transitions.
            for (PageInfo info : pages.values()) {
                if (info.pending == null || info.pendingLane != lane) continue;
                if (isPendingStillWantedLocked(info, now)) continue;
                detachPendingLocked(info, true);
            }
        }
    }

    private void activateLayerProjection(String dimension, int normalizedLayerY,
            int projectionTopY) {
        projectionTopY = CaveLayerBand.projectionTopY(CaveView.LAYERED, projectionTopY);
        ProjectionBandKey band = new ProjectionBandKey(
                dimension, CaveView.LAYERED, normalizedLayerY);
        synchronized (pages) {
            Integer previous = activeLayerProjections.put(band, projectionTopY);
            if (previous == null || previous == projectionTopY) return;

            // Xaero keys cave storage by caveStart >> 4 and stores the exact
            // caveStart separately. Inside one band, old tiles remain visible until
            // their 16x16 replacement is complete. Do not invalidate the atlas/LOD
            // hierarchy and do not globally cancel the previous working set.
            for (PageInfo info : pages.values()) {
                if (!info.key.dimension().equals(dimension)
                        || info.key.view() != CaveView.LAYERED
                        || info.key.layerY() != normalizedLayerY) continue;
                info.beginProjectionTransition(projectionTopY);
                info.nextRetryMs = 0L;
            }
            exactTopologyRevision.incrementAndGet();
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_LAYER_PROJECTION_RETARGET:" + band, 100L)) {
                recorder.event("CAVE_LAYER_PROJECTION_RETARGET",
                        "band=" + band + " previous_top_y=" + previous
                                + " current_top_y=" + projectionTopY);
            }
        }
    }

    public void requestRegion(CaveView view, int layerY, int regionX, int regionZ) {
        layerY = projectionTopY(view, layerY);
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
        layerY = projectionTopY(view, layerY);
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
        layerY = projectionTopY(view, layerY);
        PageKey key = key(view, layerY,
                (regionX << 3) + pageX, (regionZ << 3) + pageZ);
        synchronized (pages) {
            PageInfo info = pages.get(key);
            if (info == null) return null;
            if (view == CaveView.LAYERED
                    && !info.canRenderProjection(layerY)) return null;
            info.lastVisibleRenderEpoch = renderEpoch;
            info.lastVisibleMs = System.currentTimeMillis();
            if (!info.initialized || info.atlasSlot < 0) return null;
            int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
            if (info.knownColumns < fullColumns
                    && CaveScreenSpacePolicy.exactPagePixels(scale)
                            < PARTIAL_EXACT_MIN_SCREEN_PIXELS) {
                return null;
            }
            MapResidencyManager.getInstance().touch(residencyKey(key));
            publishPageTable(info, false);
            return atlas.region(info.atlasSlot, scale);
        }
    }

    /** Logical exact-page identity selected for the same atlas LOD as scale. */
    public TileKey pageTileKey(CaveView view, int layerY,
            int globalPageX, int globalPageZ, float scale) {
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return null;
        int normalized = normalizedLayer(view, layerY);
        int lod = CaveTextureAtlas.lodForScale(scale);
        return caveTileKey(stamp.sessionId(), view, normalized,
                globalPageX, globalPageZ, lod);
    }

    /** Compatibility lookup for the first 512x512 branch level. */
    public CaveAtlasRegion peekBranchRegion(CaveView view, int layerY,
            int regionX, int regionZ) {
        return peekBranchRegion(view, layerY, 1, regionX, regionZ);
    }

    /** Returns a partial or complete recursive LOD node. */
    public CaveAtlasRegion peekBranchRegion(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        layerY = projectionTopY(view, layerY);
        // Render-plan traversal reads only the last GPU-published branch snapshot.
        // Disk loading, tree derivation and dirty-queue mutation are bridged back to
        // the owner thread by CaveLodTree, so zooming cannot block on the pages lock.
        return lodTree.peekPublished(dimension(), view,
                normalizedLayer(view, layerY), level, nodeX, nodeZ);
    }

    public boolean hasBranchData(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        layerY = projectionTopY(view, layerY);
        return lodTree.hasDataSnapshot(dimension(), view,
                normalizedLayer(view, layerY), level, nodeX, nodeZ);
    }

    /** Compatibility view for older call sites; new rendering should use atlas regions. */
    public ResourceLocation peekPage(CaveView view, int layerY,
            int regionX, int regionZ, int pageX, int pageZ) {
        CaveAtlasRegion region = peekPageRegion(view, layerY,
                regionX, regionZ, pageX, pageZ, 1.0f);
        return region == null ? null : region.texture();
    }

    public boolean hasAnyPage(CaveView view, int layerY, int regionX, int regionZ) {
        layerY = projectionTopY(view, layerY);
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
     * Pure tick-side readiness probe used for atomic Layered cave handoff. A page
     * counts only when the requested projection owns every 16x16 tile (or the
     * source transaction proved the page empty). Partial pages remain useful after
     * handoff, but cannot be the signal that replaces the previous visible layer.
     */
    public boolean isPageProjectionResolved(CaveView view, int layerY,
            int globalPageX, int globalPageZ) {
        layerY = projectionTopY(view, layerY);
        PageKey key = key(view, layerY, globalPageX, globalPageZ);
        synchronized (pages) {
            PageInfo info = pages.get(key);
            if (info == null) return false;
            if (info.knownEmpty) return true;
            if (!info.initialized || info.atlasSlot < 0 || info.knownColumns <= 0) {
                return false;
            }
            return view == CaveView.FULL
                    || info.isProjectionAuthoritative(layerY);
        }
    }

    /**
     * Returns true when at least one exact GPU page is resident below the given
     * recursive LOD node. A complete ancestor is allowed to cover cold children,
     * but it must not hide a newer exact leaf that is already available.
     */
    public boolean hasResidentPageInNode(CaveView view, int layerY,
            int level, int nodeX, int nodeZ) {
        layerY = projectionTopY(view, layerY);
        int normalized = normalizedLayer(view, layerY);
        String currentDimension = dimension();
        if (level > 0) {
            return residentNodeCounts.getOrDefault(new ResidentNodeKey(
                    currentDimension, view, normalized, level, nodeX, nodeZ), 0) > 0;
        }
        synchronized (pages) {
            PageInfo info = pages.get(new PageKey(currentDimension, view, normalized,
                    nodeX, nodeZ));
            return info != null && info.initialized && info.atlasSlot >= 0;
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
        layerY = projectionTopY(view, layerY);
        VisiblePlanner planner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        if (planner == null || planner.pagePlan.length == 0) return true;
        PageKey key = key(view, layerY, globalPageX, globalPageZ);
        synchronized (pages) {
            if (!planner.matches(key)) return true;
            PageInfo info = pages.get(key);
            if (info == null || !info.initialized || info.atlasSlot < 0) return false;
            // Exact leaves that predate this viewport generation are stable cache,
            // not newly scattered work. Keep them visible across small pans.
            if (info.firstGpuPublicationSequence > 0L
                    && info.firstGpuPublicationSequence
                            <= planner.baselineGpuPublicationSequence) {
                return true;
            }
            int ordinal = planner.ordinalOf(globalPageX, globalPageZ);
            int visibleFrontierEnd = Math.min(planner.pagePlan.length,
                    planner.publicationCursor + FULLSCREEN_PUBLICATION_BURST);
            return ordinal >= 0 && ordinal < visibleFrontierEnd;
        }
    }

    private void indexResidentPage(PageKey key, int delta) {
        if (key == null || delta == 0) return;
        for (int level = 1; level <= CaveLodTree.MAX_LEVEL; level++) {
            int span = 1 << level;
            int nodeX = Math.floorDiv(key.globalPageX(), span);
            int nodeZ = Math.floorDiv(key.globalPageZ(), span);
            ResidentNodeKey node = new ResidentNodeKey(key.dimension(), key.view(),
                    key.layerY(), level, nodeX, nodeZ);
            residentNodeCounts.compute(node, (ignored, previous) -> {
                int updated = (previous == null ? 0 : previous) + delta;
                return updated <= 0 ? null : updated;
            });
        }
    }

    public void beginRenderBatch() {
        synchronized (pages) {
            if (renderBatchDepth == 0) renderEpoch++;
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
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> upload(force));
            return;
        }
        synchronizeAtlasStorage();
        long now = System.currentTimeMillis();
        if (!force && now - lastUploadMs < (MapConfig.fastFullscreenLoading ? 5L : 12L)) return;
        lastUploadMs = now;
        pruneRequests(now);

        MapPerformanceGovernor governor = MapPerformanceGovernor.getInstance();
        boolean pressured = governor.underPressure();
        boolean idleHeadroom = governor.hasStreamingHeadroom();
        boolean styleRefresh = now < styleRefreshUntilMs && !pressured;
        // Force bypasses cadence, never the 125-FPS frame deadline. Exact upload,
        // branch publication and scheduling all spend from one shared slice.
        int governorBudget = governor.texturePageBudget(true);
        int publishBudget = styleRefresh
                ? Math.min(idleHeadroom ? 10 : 6,
                        governorBudget + (idleHeadroom ? 4 : 2))
                : pressured ? 1 : Math.min(idleHeadroom ? 10 : 6,
                        governorBudget + (idleHeadroom ? 4 : 1));
        long uploadBudgetNanos = styleRefresh
                ? Math.min(1_500_000L, governor.textureUploadBudgetNanos(true))
                : governor.textureUploadBudgetNanos(force
                        || hasActiveRequest(MapRequestLane.FULLSCREEN, now)
                        || hasActiveRequest(MapRequestLane.MINIMAP, now));
        long deadline = System.nanoTime() + uploadBudgetNanos;
        VisiblePlanner fullscreenPlanner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        boolean branchFirst = fullscreenPlanner != null
                && hasActiveRequest(MapRequestLane.FULLSCREEN, now)
                && CaveScreenSpacePolicy.branchFirst(
                        fullscreenPlanner.scale, MapRequestLane.FULLSCREEN);
        boolean fullscreenActive = fullscreenPlanner != null
                && hasActiveRequest(MapRequestLane.FULLSCREEN, now);

        // One completed exact transaction may create/refresh a coarse branch, but
        // far-zoom publication spends the remaining frame on branch coverage before
        // starting another expensive leaf. Close zoom publishes at most one compact
        // run of four centre-out leaves so the visual reveal remains coherent.
        int exactPublishBudget = branchFirst ? (idleHeadroom ? 2 : 1)
                : (fullscreenActive
                        ? Math.min(FULLSCREEN_PUBLICATION_BURST, publishBudget)
                        : publishBudget);
        boolean layeredFullscreenActive = fullscreenActive
                && fullscreenPlanner.view == CaveView.LAYERED;
        boolean layeredProjectionChanged = layeredFullscreenActive
                && observedFullscreenLayeredProjectionTopY
                        != fullscreenPlanner.projectionTopY;
        if (layeredProjectionChanged) {
            observedFullscreenLayeredProjectionTopY = fullscreenPlanner.projectionTopY;
            nextFullscreenLayeredPublicationMs = 0L;
        }
        fullscreenLayeredPublicationWindowOpen =
                CaveViewportPublicationPolicy.windowOpen(layeredFullscreenActive,
                        layeredProjectionChanged,
                        nextFullscreenLayeredPublicationMs, now);
        publishCompleted(exactPublishBudget, deadline, now);
        if (layeredFullscreenActive && fullscreenLayeredPublicationWindowOpen) {
            nextFullscreenLayeredPublicationMs =
                    CaveViewportPublicationPolicy.nextWindow(now);
        }
        // The flag is meaningful only inside this upload transaction. Other callers
        // must never inherit a closed fullscreen window.
        fullscreenLayeredPublicationWindowOpen = true;
        if (branchFirst && System.nanoTime() < deadline) {
            publishBranches(pressured ? 1 : (idleHeadroom ? 4 : 2), deadline);
        }
        if (System.nanoTime() < deadline) {
            int scheduleBudget = branchFirst ? (idleHeadroom ? 2 : 1)
                    : (styleRefresh ? (idleHeadroom ? 12 : 8)
                            : force ? Math.min(idleHeadroom ? 4 : 2, publishBudget)
                                    : Math.max(1, publishBudget
                                            + (idleHeadroom ? 2 : 0)));
            scheduleBuilds(scheduleBudget, deadline, now);
        }
        if (!branchFirst && System.nanoTime() < deadline) {
            publishBranches(pressured ? 1
                    : Math.min(idleHeadroom ? 4 : 2, publishBudget), deadline);
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
        exactTopologyRevision.incrementAndGet();
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
        styleRefreshUntilMs = System.currentTimeMillis() + STYLE_REFRESH_WINDOW_MS;
        lastUploadMs = 0L;
        synchronized (pages) {
            for (PageKey key : pages.keySet()) revisions.merge(key, 1L, Long::sum);
            // Re-enumerate the visible plans immediately. Existing atlas pages stay
            // visible until replacements publish, so this is a revision sweep rather
            // than a destructive cache clear.
            for (VisiblePlanner planner : visiblePlans.values()) {
                planner.pageCursor = 0;
                planner.nextRestartMs = 0L;
                planner.lastEnumerationMs = Long.MIN_VALUE;
            }
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
            activeLayerProjections.clear();
            deferredCloses.clear();
            completedBuilds.clear();
            completedPollScratch.clear();
            gpuPublicationSequence = 0L;
            exactTopologyRevision.incrementAndGet();
            for (VisiblePlanner planner : visiblePlans.values()) planner.clear();
        }
        close.forEach(PageInfo::close);
        synchronized (pages) {
            lodTree.clear();
        }
        atlas.resetSlots();
        ExactPageStateTracker.getInstance().clearPrefix("cave:");
    }

    /**
     * Cancels dimension-owned work while retaining immutable CPU/GPU pages and LOD
     * branches under their dimension-qualified keys. The atlas is shared and its
     * normal residency policy may evict inactive dimensions when space is needed.
     * Returning to a previously viewed dimension can therefore draw the last-good
     * cave map immediately instead of rebuilding every page from disk.
     */
    public void suspendForDimensionSwitch() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(this::suspendForDimensionSwitch);
            return;
        }
        synchronized (pages) {
            for (PageInfo info : pages.values()) {
                if (info.pending != null) detachPendingLocked(info, false);
            }
            requests.clear();
            completedBuilds.clear();
            completedPollScratch.clear();
            for (VisiblePlanner planner : visiblePlans.values()) planner.clear();
            gpuPublicationSequence = 0L;
            exactTopologyRevision.incrementAndGet();
        }
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
        if (info == null) return false;

        if (info.knownEmpty && matchesAuthoritativeProjectionLocked(info)) return true;
        return info.initialized && info.atlasSlot >= 0
                && info.knownColumns >= FIRST_PUBLICATION_MIN_KNOWN_COLUMNS
                && matchesActiveProjectionLocked(info);
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
        if (!matchesAuthoritativeProjectionLocked(info)) return false;
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
        layerY = projectionTopY(view, layerY);
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
                if (info != null) {
                    // Legacy caches may still contain a pre-Pass-30 projection for
                    // this band. Keep that complete page visible while the canonical
                    // replacement is staged, then swap the whole 64x64 page once.
                    info.beginProjectionTransition(layerY);
                    info.nextRetryMs = 0L;
                }
            }
            existing.observe(effectiveLane, priority, now, fullscreenOrdinal);
            trimLaneShortlist(effectiveLane, now);
            ExactPageStateTracker.getInstance().transition(
                    stateKey(key), ExactPageState.REQUESTED,
                    effectiveLane, revisions.getOrDefault(key, 1L));
            PageInfo pendingInfo = pages.get(key);
            if (pendingInfo != null && pendingInfo.pending != null
                    && effectiveLane.strongerThan(pendingInfo.pendingLane)) {
                detachPendingLocked(pendingInfo, true);
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
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = cause instanceof CancellationException
                        ? now + 16L : now + FAILED_RETRY_MS;
                pipelineTelemetry.recordExactBuildDiscarded();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), cause instanceof CancellationException
                                ? ExactPageState.STALE_GENERATION
                                : ExactPageState.FAILED_RETRYABLE,
                        completed.lane(), revisions.getOrDefault(info.key, 1L));
                if (!(cause instanceof CancellationException)) {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent("CAVE_BUILD_FAILED:" + info.key, 500L)) {
                        recorder.event("CAVE_BUILD_FAILED",
                                "page=" + info.key + " lane=" + completed.lane()
                                        + " failure=" + cause.getClass().getSimpleName()
                                        + ':' + String.valueOf(cause.getMessage()));
                    }
                }
                continue;
            }
            MapRequestLane completedLane = completed.lane();
            if (result.projectionTopY() != info.pendingProjectionTopY
                    || !isProjectionStillRequested(
                            info.key, result.projectionTopY())) {
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = now;
                pipelineTelemetry.recordExactBuildDiscarded();
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.STALE_GENERATION,
                        completedLane, revisions.getOrDefault(info.key, 1L));
                continue;
            }
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
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = now;
                pipelineTelemetry.recordExactBuildDiscarded();
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.STALE_GENERATION,
                        completedLane, current);
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
            info.observeSourceRevision(currentSource, now);
            if (result.superseded()) {
                info.restartSourceSettleWindow(currentSource, now);
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = Math.max(now + 16L,
                        info.sourceSettleDeadlineMs());
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), info.knownColumns > 0
                                ? ExactPageState.CPU_PARTIAL
                                : ExactPageState.REQUESTED,
                        completedLane, current);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_BUILD_SOURCE_COALESCED:" + info.key,
                        500L)) {
                    recorder.event("CAVE_BUILD_SOURCE_COALESCED",
                            "page=" + info.key + " lane=" + completedLane
                                    + " current_source=" + currentSource
                                    + " known_columns=" + info.knownColumns);
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
                if (sourceAdvanced) info.restartSourceSettleWindow(currentSource, now);
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
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
                info.nextRetryMs = Math.max(now + INCOMPLETE_RETRY_MS,
                        info.sourceSettleDeadlineMs());
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.ABSENT,
                        completedLane, current);
                continue;
            }
            int addedKnownColumns = info.countAddedProjectionColumns(
                    result.projectionTopY(), result.knownRows());
            int newlyReadyTiles = info.countNewReadyProjectionTiles(
                    result.projectionTopY(), result.knownRows(), result.complete());
            int fullColumns = CaveTextureAtlas.PAGE_SIZE * CaveTextureAtlas.PAGE_SIZE;
            boolean firstUsefulPublication = info.currentProjectionKnownColumns(
                    result.projectionTopY()) == 0;
            boolean authoritativeUpgrade = result.complete()
                    && !info.isProjectionAuthoritative(result.projectionTopY());
            boolean meaningfulProgress = newlyReadyTiles > 0
                    || addedKnownColumns >= SOURCE_PROGRESS_MIN_COLUMNS;
            boolean periodicRefreshDue = info.lastPublicationMs == 0L
                    || now - info.lastPublicationMs >= SOURCE_BURST_MAX_MS;
            if (sourceAdvanced && !firstUsefulPublication
                    && !authoritativeUpgrade && !meaningfulProgress
                    && !periodicRefreshDue) {
                info.restartSourceSettleWindow(currentSource, now);
                info.pending = null;
                info.pendingToken = null;
                info.pendingLane = null;
                info.pendingProjectionTopY = Integer.MIN_VALUE;
                info.pendingCompletionRecorded = false;
                info.nextRetryMs = Math.max(now + 16L,
                        info.sourceSettleDeadlineMs());
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.CPU_PARTIAL,
                        completedLane, current);
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                if (recorder.shouldEmitEvent("CAVE_RESULT_PROGRESS_COALESCED:" + info.key,
                        500L)) {
                    recorder.event("CAVE_RESULT_PROGRESS_COALESCED",
                            "page=" + info.key + " lane=" + completedLane
                                    + " added_columns=" + addedKnownColumns
                                    + " known_columns=" + info.knownColumns
                                    + " built_source=" + result.sourceRevision()
                                    + " current_source=" + currentSource);
                }
                continue;
            }
            boolean firstGpuPublication = !info.initialized;
            ApplyOutcome outcome = apply(info, result.projectionTopY(), result.pixels(),
                    result.knownRows(), result.complete(), now);
            if (outcome.retryablePublication()) {
                if (outcome.deferral() == PublicationDeferral.GPU_BUDGET) {
                    deferCaveGpuRetry(info, completedLane, now);
                } else if (outcome.deferral() == PublicationDeferral.COALESCE) {
                    info.nextPublicationAttemptMs = now + CaveTilePublicationPolicy.RETRY_MS;
                } else {
                    info.nextPublicationAttemptMs = now + ATLAS_PUBLICATION_RETRY_MS;
                }
                ExactPageStateTracker.getInstance().transition(
                        stateKey(info.key), ExactPageState.CPU_READY,
                        completedLane, current);
                completedBuilds.offer(completed);
                if (outcome.deferral() == PublicationDeferral.COALESCE) {
                    // This page is waiting for more 16x16 tiles. Keep scanning the
                    // completed queue so one sparse page cannot waste the whole
                    // viewport publication window.
                    continue;
                }
                MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                String event = outcome.deferral() == PublicationDeferral.ATLAS_SLOT
                        ? "CAVE_ATLAS_SLOT_DEFERRED"
                        : "CAVE_GPU_BUDGET_DEFERRED";
                if (recorder.shouldEmitEvent(event, 500L)) {
                    recorder.event(event,
                            "page=" + info.key + " lane=" + completedLane
                                    + " atlas_capacity=" + CaveTextureAtlas.SLOT_COUNT
                                    + " retained_pages=" + pages.size());
                }
                break;
            }
            info.pending = null;
            info.pendingToken = null;
            info.pendingCompletionRecorded = false;
            info.pendingProjectionTopY = Integer.MIN_VALUE;
            info.pendingLane = null;
            resetCaveGpuRetry(info, completedLane);
            if (!outcome.applied()) {
                markIncomplete(info, now);
                continue;
            }
            info.uploadedRevision = current;
            info.uploadedSourceRevision = result.sourceRevision();
            info.noSourceRevision = Long.MIN_VALUE;
            info.lastPublicationMs = now;
            if (result.complete() && !sourceAdvanced) {
                info.markSourceSettled(currentSource);
                info.nextRetryMs = 0L;
                clearSatisfiedRequest(info.key, now);
            } else {
                if (sourceAdvanced) {
                    info.restartSourceSettleWindow(currentSource, now);
                }
                info.nextRetryMs = sourceAdvanced
                        ? Math.max(now + INCOMPLETE_RETRY_MS,
                                info.sourceSettleDeadlineMs())
                        : now + INCOMPLETE_RETRY_MS;
            }
            if (firstGpuPublication && outcome.hasContent()) {
                MapDebugRecorder.getInstance().event("CAVE_PAGE_GPU_READY",
                        "page=" + info.key + " lane=" + completedLane
                                + " known_columns=" + result.knownColumns()
                                + " complete=" + result.complete()
                                + " expected_revision=" + result.expectedRevision()
                                + " source_revision=" + result.sourceRevision());
            }
            markFullscreenPublicationAdvanced(completed, now);
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
        VisiblePlanner fullscreen = visiblePlans.get(MapRequestLane.FULLSCREEN);
        synchronized (pages) {
            advanceFullscreenPublicationFrontierLocked(fullscreen, now);
        }

        completedPollScratch.clear();
        CompletedBuild selected = null;
        int scanBudget = Math.min(completedBuilds.size(),
                FULLSCREEN_BUILD_AHEAD_PAGES + 8);
        for (int scanned = 0; scanned < scanBudget; scanned++) {
            CompletedBuild candidate = completedBuilds.poll();
            if (candidate == null) break;
            if (!isCompletionStillAttached(candidate)) {
                pipelineTelemetry.recordTaskCompletedButDiscarded();
                continue;
            }
            if (candidate.info().nextPublicationAttemptMs <= now
                    && caveGpuLaneEligible(candidate.lane(), now)
                    && isCompletionPublicationEligible(candidate, fullscreen)) {
                selected = candidate;
                break;
            }
            completedPollScratch.add(candidate);
        }
        for (CompletedBuild deferred : completedPollScratch) {
            completedBuilds.offer(deferred);
        }
        completedPollScratch.clear();
        return selected;
    }

    private boolean isCompletionStillAttached(CompletedBuild completed) {
        if (completed == null) return false;
        synchronized (pages) {
            return completed.info().pending == completed.future();
        }
    }

    /**
     * Minimap/background completions retain lane ordering. A fullscreen first
     * publication is eligible only at the current centre-out frontier; refreshes for
     * pages already behind that frontier remain legal and cannot create a new island.
     */
    private boolean isCompletionPublicationEligible(CompletedBuild completed,
            VisiblePlanner planner) {
        if (completed == null) return false;
        if (completed.lane() != MapRequestLane.FULLSCREEN) return true;
        if (completed.info().key.view() == CaveView.LAYERED
                && !fullscreenLayeredPublicationWindowOpen) {
            return false;
        }
        if (planner == null || planner.pagePlan.length == 0
                || !planner.matches(completed.info().key)) {
            // Never spend a first atlas slot on an obsolete viewport. An already
            // resident page may still receive a cheap refresh while its retained
            // frame is being handed off.
            return completed.info().initialized;
        }
        int ordinal = completed.fullscreenOrdinal();
        if (ordinal < 0 || ordinal == Integer.MAX_VALUE) {
            ordinal = planner.ordinalOf(completed.info().key.globalPageX(),
                    completed.info().key.globalPageZ());
        }
        int frontierEnd = Math.min(planner.pagePlan.length,
                planner.publicationCursor + FULLSCREEN_PUBLICATION_BURST);
        // Pages behind the cursor are hole repairs. Pages at/just ahead of it form
        // one compact reveal burst. Everything farther away remains CPU-ready and
        // consumes no atlas slot until the visual frontier reaches it.
        return ordinal >= 0 && ordinal < frontierEnd;
    }

    private void advanceFullscreenPublicationFrontierLocked(
            VisiblePlanner planner, long now) {
        if (planner == null || planner.pagePlan.length == 0) return;
        while (planner.publicationCursor < planner.pagePlan.length) {
            int ordinal = planner.publicationCursor;
            planner.ensureFrontierRingGrace(ordinal, now);
            long packed = planner.pagePlan[ordinal];
            PageKey key = new PageKey(planner.dimension, planner.view,
                    planner.layerY, CaveLoadHierarchy.x(packed),
                    CaveLoadHierarchy.z(packed));
            if (isPageDisplayReadyLocked(key)) {
                planner.advancePublicationCursor(now);
                continue;
            }
            PageInfo info = pages.get(key);
            long sourceRevision = repository.getPageRevision(
                    key.globalPageX(), key.globalPageZ());
            boolean confirmedNoSource = (info != null && info.pending == null
                    && info.knownColumns == 0
                    && info.noSourceRevision == sourceRevision)
                    || (info == null && sourceRevision == 0L);
            if (confirmedNoSource
                    && now - planner.frontierWaitStartedMs
                            >= planner.frontierNoSourceGraceMs()) {
                planner.advancePublicationCursor(now);
                continue;
            }
            break;
        }
    }

    private void markFullscreenPublicationAdvanced(CompletedBuild completed,
            long now) {
        if (completed == null || completed.lane() != MapRequestLane.FULLSCREEN) return;
        VisiblePlanner planner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        synchronized (pages) {
            if (planner == null || !planner.matches(completed.info().key)) return;
            int ordinal = completed.fullscreenOrdinal();
            if (ordinal == planner.publicationCursor) {
                planner.advancePublicationCursor(now);
                advanceFullscreenPublicationFrontierLocked(planner, now);
            }
        }
    }



    private boolean isBuildAheadEligible(PageRequest request, long now) {
        MapRequestLane lane = request.effectiveLane(now);
        if (lane != MapRequestLane.FULLSCREEN) return true;
        VisiblePlanner planner = visiblePlans.get(MapRequestLane.FULLSCREEN);
        if (planner == null || planner.pagePlan.length == 0) return true;
        return planner.matches(request.key);
    }



    private void scheduleBuilds(int budget, long deadline, long now) {
        long pendingCounts = pendingBuildCounts();
        int pending = (int) (pendingCounts >>> 32);
        boolean minimapWaiting = hasActiveRequest(MapRequestLane.MINIMAP, now);
        boolean minimapRunning = (int) pendingCounts > 0;
        // Reserve one overflow slot for minimap demand without shrinking the
        // fullscreen centre-out build-ahead runway.
        int pendingCap = minimapWaiting && !minimapRunning
                ? FULLSCREEN_BUILD_AHEAD_PAGES + 1
                : FULLSCREEN_BUILD_AHEAD_PAGES;
        budget = Math.min(budget, Math.max(0, pendingCap - pending));
        if (budget <= 0) return;
        synchronized (pages) {
            candidateBuffer.clear();
            candidateBuffer.addAll(requests.values());
        }

        int scheduled = 0;
        while (scheduled < budget && System.nanoTime() < deadline) {
            int bestIndex = -1;
            for (int index = 0; index < candidateBuffer.size(); index++) {
                PageRequest candidate = candidateBuffer.get(index);
                if (candidate == null) continue;
                if (candidate.isExpired(now)) continue;
                if (!isBuildAheadEligible(candidate, now)) continue;
                if (bestIndex < 0 || higherPriority(
                        candidate, candidateBuffer.get(bestIndex), now)) bestIndex = index;
            }
            if (bestIndex < 0) break;

            PageRequest request = candidateBuffer.get(bestIndex);
            candidateBuffer.set(bestIndex, null);
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
                if (info.pending != null) continue;
                info.beginProjectionTransition(projectionTopY);
                info.observeSourceRevision(sourceRevision, now);
                if (now < info.nextRetryMs) continue;
                // No-source is a repository state, not a timer. Rebuilding an
                // unchanged empty snapshot only burns CPU and keeps the request
                // lane busy; a repository revision advance re-enables it.
                if (!info.initialized && info.knownColumns == 0
                        && info.noSourceRevision == sourceRevision) continue;
                // A usable exact/CPU fallback already exists. Coalesce the burst of
                // per-leaf source revisions into one coherent refresh instead of
                // resolving and styling the same 64x64 page after every chunk commit.
                if (info.knownColumns > 0
                        && info.uploadedSourceRevision != sourceRevision
                        && !info.isSourceSettled(now)) {
                    info.nextRetryMs = Math.max(info.nextRetryMs,
                            info.sourceSettleDeadlineMs());
                    continue;
                }
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
                        if (info.isProjectionAuthoritative(projectionTopY)) {
                            PageRequest satisfied = requests.get(key);
                            if (satisfied != null) {
                                satisfied.clearAll();
                                if (satisfied.isExpired(now)) requests.remove(key);
                            }
                        } else {
                            // The current source revision has been staged or only
                            // some 16x16 replacements are visible. Keep the old-band
                            // fallback, but continue until every tile represents the
                            // requested exact Top-Y.
                            info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
                        }
                        continue;
                    }
                    if (info.frontLods != null && info.knownColumns > 0
                            && info.canRenderProjection(projectionTopY)) {
                        if (restoreCavePageResidency(info, requestLane)) {
                            if (info.isProjectionAuthoritative(projectionTopY)) {
                                PageRequest satisfied = requests.get(key);
                                if (satisfied != null) {
                                    satisfied.clearAll();
                                    if (satisfied.isExpired(now)) requests.remove(key);
                                }
                            } else {
                                info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
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
                long repositoryGeneration = repository.generation();
                long scheduledSourceRevision = sourceRevision;
                boolean hadFallbackAtSchedule = info.knownColumns > 0
                        && info.canRenderProjection(projectionTopY);
                MapCancellationToken token = new MapCancellationToken(() ->
                        key.dimension().equals(dimension())
                                && repository.isGenerationCurrent(repositoryGeneration)
                                && isProjectionStillRequested(
                                        key, projectionTopY));
                long queuedNanos = System.nanoTime();
                CompletableFuture<BuildResult> future = MapWorkScheduler.tryCpuFuture(
                        requestLane, MapWorkScheduler.WorkType.EXACT_BUILD,
                        request.effectivePriority(now), 8, token, () -> {
                    long buildStart = System.nanoTime();
                    pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_QUEUE,
                            Math.max(0L, buildStart - queuedNanos));
                    try {
                        token.checkpoint("cave-page-resolve-start");
                        long sourceBeforeResolve = repository.getPageRevision(
                                key.globalPageX(), key.globalPageZ());
                        if (hadFallbackAtSchedule
                                && sourceBeforeResolve != scheduledSourceRevision) {
                            return BuildResult.superseded(revision, sourceBeforeResolve,
                                    projectionTopY);
                        }
                        telemetry.recordPageBuild();
                        CaveTileRepository.ResolvedPage resolved = repository.resolvePage(
                                key.view(), projectionTopY, level,
                                key.globalPageX(), key.globalPageZ());
                        token.checkpoint("cave-page-resolve-finished");
                        long sourceAfterResolve = repository.getPageRevision(
                                key.globalPageX(), key.globalPageZ());
                        if (hadFallbackAtSchedule
                                && resolved.revision() != sourceAfterResolve) {
                            return BuildResult.superseded(revision, sourceAfterResolve,
                                    projectionTopY);
                        }
                        int[] styled = CavePageStyler.style(
                                resolved.pixels(), resolved.heights(),
                                resolved.topHeights(), resolved.flags(), resolved.light(),
                                resolved.overlayCounts(), resolved.overlayColors(),
                                resolved.overlayAlpha(), resolved.overlayY(),
                                resolved.overlayLight(), resolved.overlayFlags(),
                                key.view(), projectionTopY);
                        token.checkpoint("cave-page-style-finished");
                        return new BuildResult(revision, resolved.revision(), projectionTopY,
                                styled,
                                resolved.knownRows(), resolved.knownColumnCount(),
                                resolved.complete(), false);
                    } finally {
                        pipelineTelemetry.recordStageNanos(MapPipelineStage.EXACT_BUILD,
                                System.nanoTime() - buildStart);
                    }
                });
                if (future == null) {
                    // Admission failure is not an in-flight build. The previous
                    // CompletableFuture/Executor adapter could place the command in
                    // an invisible delayed retry queue while PageInfo retained one
                    // of the six exact-build slots indefinitely. A cold viewport
                    // then had demand but could not schedule any current pages.
                    info.nextRetryMs = now + 16L;
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    if (recorder.shouldEmitEvent("CAVE_BUILD_ADMISSION_DEFERRED", 500L)) {
                        recorder.event("CAVE_BUILD_ADMISSION_DEFERRED",
                                "page=" + key + " lane=" + requestLane
                                        + " pending=" + pendingBuildCount()
                                        + " requests=" + requestCount());
                    }
                    continue;
                }
                pipelineTelemetry.recordExactBuildQueued();
                ExactPageStateTracker.getInstance().transition(
                        stateKey(key), ExactPageState.BUILDING,
                        requestLane, revision);
                info.pending = future;
                info.pendingToken = token;
                info.pendingLane = requestLane;
                info.pendingProjectionTopY = projectionTopY;
                info.pendingCompletionRecorded = false;
                future.whenComplete((result, throwable) -> {
                    synchronized (pages) {
                        // Stronger-lane retargeting or page retirement may have
                        // already detached this future. Do not enqueue a terminal
                        // completion that can only be discarded later. The detach
                        // path normally terminalizes the tracker; cover the narrow
                        // race where completion observes a cleared owner first.
                        if (info.pending != future) {
                            if (info.pending == null) {
                                PageRequest live = requests.get(info.key);
                                long completedAt = System.currentTimeMillis();
                                boolean requested = live != null
                                        && !live.isExpired(completedAt)
                                        && live.projectionTopY == projectionTopY;
                                ExactPageStateTracker.getInstance().transition(
                                        stateKey(info.key), requested
                                                ? ExactPageState.REQUESTED
                                                : ExactPageState.STALE_GENERATION,
                                        requested ? live.effectiveLane(completedAt)
                                                : requestLane,
                                        revisions.getOrDefault(info.key, revision));
                            }
                            return;
                        }
                    }
                    completedBuilds.offer(new CompletedBuild(
                            info, future, requestLane, requestOrdinal,
                            completedSequence.getAndIncrement()));
                });
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

    private ApplyOutcome apply(PageInfo info, int projectionTopY, int[] pixels,
            long[] incomingKnownRows, boolean complete, long now) {
        int pageSize = CaveTextureAtlas.PAGE_SIZE;
        int pixelCount = pageSize * pageSize;
        boolean wasCompleteBeforeApply = info.knownColumns >= pixelCount;
        if (pixels == null || pixels.length < pixelCount
                || incomingKnownRows == null || incomingKnownRows.length < pageSize) {
            return ApplyOutcome.NOT_APPLIED;
        }

        info.beginProjectionTransition(projectionTopY);
        int[] mergedBase = uploadScratchLods[0];
        // A far-zoom page may wait several frames for an atlas/GPU reservation.
        // Do not allocate its four retained mip arrays until publication can
        // actually proceed. Existing resident pages still merge from last-good
        // pixels; first publications begin from a clean reusable scratch page.
        if (info.frontLods != null) {
            System.arraycopy(info.frontLods[0], 0, mergedBase, 0, pixelCount);
        } else {
            java.util.Arrays.fill(mergedBase, 0);
        }

        int addedKnownColumns = info.stageProjection(
                projectionTopY, pixels, incomingKnownRows, complete);
        boolean replacingDifferentProjection =
                info.hasVisibleProjectionDifferentFrom(projectionTopY);
        if (replacingDifferentProjection
                && !info.stagingProjectionComplete(projectionTopY)) {
            // Never expose a checkerboard of two vertical projections. Keep the
            // last coherent page as the authority until its replacement is complete.
            return ApplyOutcome.NOT_APPLIED;
        }
        int readyTileMask = replacingDifferentProjection
                ? (1 << PROJECTION_TILE_COUNT) - 1
                : info.readyStagedTileMask(projectionTopY, mergedBase);
        if (readyTileMask == 0) {
            info.stagedReadySinceMs = 0L;
            return ApplyOutcome.NOT_APPLIED;
        }
        if (info.stagedReadySinceMs == 0L) info.stagedReadySinceMs = now;
        int readyTileCount = Integer.bitCount(readyTileMask);
        if (!CaveTilePublicationPolicy.shouldPublish(info.pendingLane,
                info.initialized, replacingDifferentProjection, readyTileCount,
                info.stagedReadySinceMs, now)) {
            return ApplyOutcome.COALESCED;
        }
        info.copyStagedTiles(projectionTopY, readyTileMask, mergedBase);

        boolean hasContent = false;
        for (int index = 0; index < pixelCount; index++) {
            if (mergedBase[index] != 0) {
                hasContent = true;
                break;
            }
        }
        boolean authoritative = info.wouldBeProjectionAuthoritative(
                projectionTopY, readyTileMask);
        if (hasContent) {
            boolean hadAtlasSlot = info.atlasSlot >= 0;
            if (!ensureAtlasSlot(info)) return ApplyOutcome.ATLAS_DEFERRED;
            MapRequestLane uploadLane = info.pendingLane == null
                    ? MapRequestLane.BACKGROUND : info.pendingLane;
            if (!MapGpuBudgetController.getInstance().tryReserve(
                    MapGpuBudgetController.UploadKind.CAVE_EXACT,
                    uploadLane, uploadLane == MapRequestLane.MINIMAP
                            || uploadLane == MapRequestLane.FULLSCREEN)) {
                if (!hadAtlasSlot && !info.initialized && info.atlasSlot >= 0) {
                    atlas.releaseSlot(info.atlasSlot);
                    info.atlasSlot = -1;
                }
                return ApplyOutcome.GPU_DEFERRED;
            }
        }

        // Visible authority changes only after a content page owns both atlas
        // residency and render-frame upload budget. A denial leaves the staged
        // projection immutable and ready to retry without tile swaps or LOD work.
        info.commitStagedTiles(projectionTopY, readyTileMask, mergedBase);
        MapDebugRecorder recorder = MapDebugRecorder.getInstance();
        String event = replacingDifferentProjection
                ? "CAVE_LAYER_PAGE_SWAP"
                : info.key.view() == CaveView.FULL
                        ? "CAVE_FULL_TILE_SWAP" : "CAVE_LAYER_TILE_SWAP";
        if (recorder.shouldEmitEvent(event + ':' + info.key, 250L)) {
            recorder.event(event,
                    "page=" + info.key + " top_y=" + projectionTopY
                            + " tiles=" + Integer.bitCount(readyTileMask)
                            + " tile_mask=" + Integer.toHexString(readyTileMask));
        }

        for (int lod = 1; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            downsample(uploadScratchLods[lod - 1], CaveTextureAtlas.lodSize(lod - 1),
                    uploadScratchLods[lod], CaveTextureAtlas.lodSize(lod));
        }
        telemetry.recordLodBuild();

        // Reservation succeeded; retained CPU mips now have a consumer and are
        // worth materializing. Deferred pages avoid this allocation entirely.
        info.ensureBuffers();
        if (!hasContent) {
            info.knownEmpty = authoritative;
            for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
                System.arraycopy(uploadScratchLods[lod], 0, info.frontLods[lod], 0,
                        uploadScratchLods[lod].length);
            }
            if (authoritative) info.publishedProjectionTopY = projectionTopY;
            info.stagedReadySinceMs = 0L;
            updateBranch(info);
            info.releaseAtlasSlot();
            ExactPageStateTracker.getInstance().transition(
                    stateKey(info.key), authoritative
                            ? ExactPageState.KNOWN_EMPTY
                            : ExactPageState.CPU_PARTIAL,
                    info.pendingLane, revisions.getOrDefault(info.key, 1L));
            return ApplyOutcome.APPLIED_EMPTY;
        }
        info.knownEmpty = false;
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
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            System.arraycopy(uploadScratchLods[lod], 0, info.frontLods[lod], 0,
                    uploadScratchLods[lod].length);
        }
        if (authoritative) info.publishedProjectionTopY = projectionTopY;
        info.stagedReadySinceMs = 0L;
        updateBranch(info);
        boolean firstGpuPublication = !info.initialized;
        boolean completenessChanged = wasCompleteBeforeApply
                != (info.knownColumns >= pixelCount);
        if (uploaded) {
            telemetry.recordPageUpload();
            MapResidencyManager.getInstance().markPixelsChanged(
                    MapResidencyManager.Kind.CAVE_EXACT);
        }
        info.initialized = true;
        publishPageTable(info, true);
        // Atlas pixel changes are visible without rebuilding world geometry. Only
        // residency or coverage transitions alter which quads the hierarchy emits.
        if (firstGpuPublication || completenessChanged) {
            exactTopologyRevision.incrementAndGet();
        }
        if (firstGpuPublication && info.firstGpuPublicationSequence == 0L) {
            info.firstGpuPublicationSequence = ++gpuPublicationSequence;
        }
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
        return ApplyOutcome.APPLIED_CONTENT;
    }

    private static void copyKnownPixels(int[] source, long[] knownRows,
            int[] destination, int pageSize) {
        for (int y = 0; y < pageSize; y++) {
            long mask = knownRows[y];
            while (mask != 0L) {
                int x = Long.numberOfTrailingZeros(mask);
                destination[y * pageSize + x] = source[y * pageSize + x];
                mask &= mask - 1L;
            }
        }
    }

    private static long caveGpuRetryDelayMs(MapRequestLane lane) {
        MapRequestLane effective = normalizedGpuLane(lane);
        boolean pressure = MapPerformanceGovernor.getInstance().underPressure();
        return switch (effective) {
            case MINIMAP -> pressure ? 8L : 4L;
            case FULLSCREEN -> pressure ? 16L : 8L;
            default -> pressure ? 40L : 24L;
        };
    }

    private static MapRequestLane normalizedGpuLane(MapRequestLane lane) {
        return lane == null ? MapRequestLane.BACKGROUND : lane;
    }

    private boolean caveGpuLaneEligible(MapRequestLane lane, long nowMs) {
        return true;
    }

    private void deferCaveGpuRetry(PageInfo info, MapRequestLane lane, long nowMs) {
        info.gpuReservationFailures = Math.min(8, info.gpuReservationFailures + 1);
        info.nextPublicationAttemptMs = nowMs + caveGpuRetryDelayMs(lane);
    }

    private void resetCaveGpuRetry(PageInfo info, MapRequestLane lane) {
        if (info != null) {
            info.gpuReservationFailures = 0;
            info.nextPublicationAttemptMs = 0L;
        }
    }

    private boolean restoreCavePageResidency(PageInfo info,
            MapRequestLane lane) {
        if (info == null || info.frontLods == null || info.knownColumns <= 0
                || info.knownEmpty) return false;
        long now = System.currentTimeMillis();
        MapRequestLane uploadLane = normalizedGpuLane(lane);
        if (info.nextPublicationAttemptMs > now
                || !caveGpuLaneEligible(uploadLane, now)) return false;
        boolean hadAtlasSlot = info.atlasSlot >= 0;
        if (!ensureAtlasSlot(info)) return false;
        if (!MapGpuBudgetController.getInstance().tryReserve(
                MapGpuBudgetController.UploadKind.CAVE_EXACT,
                uploadLane, uploadLane == MapRequestLane.MINIMAP)) {
            if (!hadAtlasSlot && !info.initialized && info.atlasSlot >= 0) {
                atlas.releaseSlot(info.atlasSlot);
                info.atlasSlot = -1;
            }
            deferCaveGpuRetry(info, uploadLane, now);
            return false;
        }
        resetCaveGpuRetry(info, uploadLane);
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
        publishPageTable(info, true);
        if (info.firstGpuPublicationSequence == 0L) {
            info.firstGpuPublicationSequence = ++gpuPublicationSequence;
        }
        info.nextRetryMs = 0L;
        indexResidentPage(info.key, 1);
        String residentKey = residencyKey(info.key);
        MapResidencyManager.getInstance().register(
                residentKey, MapResidencyManager.Kind.CAVE_EXACT,
                4L * (64L * 64L + 32L * 32L + 16L * 16L + 8L * 8L),
                () -> evictExactPageForBudget(info.key));
        MapResidencyManager.getInstance().enforceBudget(residentKey, lane);
        exactTopologyRevision.incrementAndGet();
        telemetry.recordPageUpload();
        MapResidencyManager.getInstance().markPixelsChanged(
                MapResidencyManager.Kind.CAVE_EXACT);
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), ExactPageState.GPU_READY,
                lane, info.uploadedRevision);
        return true;
    }

    private void publishPageTable(PageInfo info, boolean force) {
        if (info == null || !info.initialized || info.atlasSlot < 0) return;
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp == null) return;
        long storageGeneration = atlas.storageGeneration();
        long revision = Math.max(1L, revisions.getOrDefault(
                info.key, info.uploadedRevision));
        if (!force && info.pageTableSessionId == stamp.sessionId()
                && info.pageTableStorageGeneration == storageGeneration
                && info.pageTableSlot == info.atlasSlot
                && info.pageTableRevision == revision) return;

        MapGpuPageTableService pageTable = MapGpuPageTableService.getInstance();
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            CaveAtlasRegion region = atlas.regionForLod(info.atlasSlot, lod);
            if (region == null) continue;
            TileKey tileKey = caveTileKey(stamp.sessionId(), info.key.view(),
                    info.key.layerY(), info.key.globalPageX(),
                    info.key.globalPageZ(), lod);
            pageTable.stage(tileKey, region.texture(), info.atlasSlot,
                    storageGeneration, revision, 0,
                    region.sourceX(), region.sourceY(),
                    region.sourceSize(), region.atlasSize());
        }
        info.pageTableSessionId = stamp.sessionId();
        info.pageTableStorageGeneration = storageGeneration;
        info.pageTableSlot = info.atlasSlot;
        info.pageTableRevision = revision;
    }

    private void removePageTable(PageInfo info) {
        if (info == null || info.pageTableSessionId <= 0L) return;
        MapGpuPageTableService pageTable = MapGpuPageTableService.getInstance();
        for (int lod = 0; lod < CaveTextureAtlas.LOD_COUNT; lod++) {
            pageTable.remove(caveTileKey(info.pageTableSessionId,
                    info.key.view(), info.key.layerY(),
                    info.key.globalPageX(), info.key.globalPageZ(), lod));
        }
        info.pageTableSessionId = 0L;
        info.pageTableStorageGeneration = Long.MIN_VALUE;
        info.pageTableSlot = -1;
        info.pageTableRevision = Long.MIN_VALUE;
    }

    private static TileKey caveTileKey(long sessionId, CaveView view,
            int normalizedLayer, int globalPageX, int globalPageZ, int lod) {
        int variant = view == CaveView.FULL
                ? TileKey.VARIANT_CAVE_FULL : TileKey.VARIANT_CAVE_LAYERED;
        int projectionId = view == CaveView.FULL ? 0 : normalizedLayer;
        return new TileKey(sessionId, projectionId, lod,
                globalPageX, globalPageZ, variant);
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

    private static int countAddedKnownColumns(long[] existingRows,
            long[] incomingRows) {
        if (incomingRows == null) return 0;
        int rowCount = Math.min(existingRows.length, incomingRows.length);
        int added = 0;
        for (int row = 0; row < rowCount; row++) {
            added += Long.bitCount(incomingRows[row] & ~existingRows[row]);
        }
        return added;
    }

    private boolean isRecentlyRenderVisible(PageInfo info) {
        return info != null && info.lastVisibleRenderEpoch > 0L
                && renderEpoch - info.lastVisibleRenderEpoch <= 1L;
    }

    private static void markIncomplete(PageInfo info, long now) {
        // Preserve the last-good CPU/GPU revision. A partial page is visible and
        // reusable; only a newer repository revision should trigger another build.
        info.nextRetryMs = now + INCOMPLETE_RETRY_MS;
    }


    private boolean evictExactPageForBudget(PageKey key) {
        if (key == null || renderBatchDepth > 0) return false;
        PageInfo retired;
        long now = System.currentTimeMillis();
        synchronized (pages) {
            retired = pages.get(key);
            if (retired == null || retired.pending != null
                    || retired.atlasSlot < 0 || !retired.initialized
                    || isRecentlyRenderVisible(retired)) {
                return false;
            }
            boolean safelyOffscreen = isOffscreenEvictionCandidateLocked(retired, now);
            if (!safelyOffscreen && !hasReplacementCoverage(retired)) return false;
            // Preserve CPU data and request/revision state. Only GPU residency is
            // retired so a later visible request can re-upload without rereading
            // or reprojecting the world source.
            retired.releaseAtlasSlot();
        }
        telemetry.recordAtlasEviction();
        return true;
    }

    /**
     * Acquires an exact atlas slot without allocating candidate collections.
     *
     * <p>Offscreen coherent pages are the first eviction class and do not require a
     * branch replacement: they cannot create a visible hole and their CPU copy is
     * retained for a cheap restore. A visible page is considered only when a branch
     * already covers it. This prevents a full 1024-slot atlas from deadlocking a
     * newly visible close-zoom page merely because branch publication is behind.</p>
     */
    private boolean ensureAtlasSlot(PageInfo info) {
        if (info.atlasSlot >= 0) return true;
        int slot = atlas.acquireSlot();
        if (slot >= 0) {
            info.atlasSlot = slot;
            return true;
        }

        PageInfo victim;
        long now = System.currentTimeMillis();
        synchronized (pages) {
            if (renderBatchDepth > 0) return false;
            victim = selectAtlasVictimLocked(info, now, true, true);
            if (victim == null) {
                // Local atlas liveness is more important than a stale global pin.
                // The page is outside all active viewports and retains its CPU copy.
                victim = selectAtlasVictimLocked(info, now, true, false);
            }
            if (victim == null) {
                victim = selectAtlasVictimLocked(info, now, false, true);
            }
        }
        if (victim == null) return false;

        boolean offscreen;
        synchronized (pages) {
            offscreen = isOffscreenEvictionCandidateLocked(victim, now);
            victim.releaseAtlasSlot();
        }
        telemetry.recordAtlasEviction();
        if (offscreen) {
            MapDebugRecorder recorder = MapDebugRecorder.getInstance();
            if (recorder.shouldEmitEvent("CAVE_ATLAS_OFFSCREEN_EVICTED", 250L)) {
                recorder.event("CAVE_ATLAS_OFFSCREEN_EVICTED",
                        "victim=" + victim.key + " requester=" + info.key);
            }
        }
        slot = atlas.acquireSlot();
        if (slot < 0) return false;
        info.atlasSlot = slot;
        return true;
    }

    private PageInfo selectAtlasVictimLocked(PageInfo protectedInfo, long now,
            boolean requireOffscreen, boolean respectPin) {
        PageInfo best = null;
        long bestScore = Long.MAX_VALUE;
        MapResidencyManager residency = MapResidencyManager.getInstance();
        for (PageInfo candidate : pages.values()) {
            if (candidate == protectedInfo || candidate.pending != null
                    || candidate.atlasSlot < 0 || !candidate.initialized
                    || isRecentlyRenderVisible(candidate)
                    || hasActiveRequestLocked(candidate.key, now)) {
                continue;
            }
            boolean offscreen = isOffscreenEvictionCandidateLocked(candidate, now);
            if (requireOffscreen) {
                if (!offscreen) continue;
            } else if (!hasReplacementCoverage(candidate)) {
                continue;
            }
            String key = residencyKey(candidate.key);
            if (respectPin && residency.isPinned(key)) continue;
            long score = residency.evictionScore(key);
            if (preferredView != null
                    && preferredDimension.equals(candidate.key.dimension())
                    && candidate.key.view() != preferredView) {
                score -= 1_000_000_000L;
            }
            if (best == null || score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean hasActiveRequestLocked(PageKey key, long now) {
        PageRequest request = requests.get(key);
        return request != null && request.effectiveLane(now) != null;
    }

    private boolean isOffscreenEvictionCandidateLocked(PageInfo info, long now) {
        if (info == null || now - info.lastVisibleMs <= OFFSCREEN_EVICTION_GRACE_MS) {
            return false;
        }
        for (VisiblePlanner planner : visiblePlans.values()) {
            if (planner == null || planner.pagePlan.length == 0
                    || now - planner.lastEnumerationMs > ACTIVE_PLANNER_GRACE_MS) {
                continue;
            }
            if (planner.matches(info.key)) return false;
        }
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
                ExactPageStateTracker.getInstance().removeIfState(
                        stateKey(key), ExactPageState.REQUESTED);
                PageInfo info = pages.get(key);
                if (info != null && info.pending != null) {
                    detachPendingLocked(info, true);
                }
                if (!pages.containsKey(key)) revisions.remove(key);
            }
        }
    }

    private boolean isProjectionStillRequested(PageKey key, int projectionTopY) {
        synchronized (pages) {
            PageRequest request = requests.get(key);
            long now = System.currentTimeMillis();
            return request != null && !request.isExpired(now)
                    && request.projectionTopY == projectionTopY;
        }
    }

    private boolean isPendingStillWantedLocked(PageInfo info, long now) {
        if (info == null || info.pending == null) return false;
        PageRequest request = requests.get(info.key);
        return request != null && !request.isExpired(now)
                && request.projectionTopY == info.pendingProjectionTopY;
    }

    private void detachPendingLocked(PageInfo info, boolean countCancellation) {
        if (info == null || info.pending == null) return;
        CompletableFuture<BuildResult> detached = info.pending;
        MapRequestLane detachedLane = info.pendingLane;
        int detachedProjectionTopY = info.pendingProjectionTopY;
        long revision = revisions.getOrDefault(info.key, 1L);
        if (info.pendingToken != null) info.pendingToken.cancel();
        boolean cancelled = detached.cancel(false);
        if (countCancellation && cancelled) {
            pipelineTelemetry.recordTaskCancelledBeforeRun();
        }
        // A completion may already have reached the publication queue just before
        // the viewport/lane was retired. Remove that exact ownership record now so
        // it cannot consume a future frontier scan only to be discarded there.
        completedBuilds.removeIf(completed -> completed.info() == info
                && completed.future() == detached);
        info.pending = null;
        info.pendingToken = null;
        info.pendingLane = null;
        info.pendingProjectionTopY = Integer.MIN_VALUE;
        info.pendingCompletionRecorded = false;
        info.nextRetryMs = 0L;

        long now = System.currentTimeMillis();
        PageRequest request = requests.get(info.key);
        boolean requested = request != null && !request.isExpired(now)
                && request.projectionTopY == detachedProjectionTopY;
        MapRequestLane terminalLane = requested
                ? request.effectiveLane(now) : detachedLane;
        ExactPageStateTracker.getInstance().transition(
                stateKey(info.key), requested
                        ? ExactPageState.REQUESTED
                        : ExactPageState.STALE_GENERATION,
                terminalLane, revision);
    }

    private boolean matchesActiveProjectionLocked(PageInfo info) {
        if (info == null || info.key.view() == CaveView.FULL) return true;
        ProjectionBandKey band = new ProjectionBandKey(info.key.dimension(),
                info.key.view(), info.key.layerY());
        int active = activeLayerProjections.getOrDefault(
                band, info.publishedProjectionTopY);
        return info.canRenderProjection(active);
    }

    private boolean matchesAuthoritativeProjectionLocked(PageInfo info) {
        if (info == null || info.key.view() == CaveView.FULL) return true;
        ProjectionBandKey band = new ProjectionBandKey(info.key.dimension(),
                info.key.view(), info.key.layerY());
        int active = activeLayerProjections.getOrDefault(
                band, info.publishedProjectionTopY);
        return info.isProjectionAuthoritative(active);
    }

    private void trimPages() {
        synchronized (pages) {
            if (pages.size() <= MAX_PAGES) return;
        }
        List<PageInfo> retired = new ArrayList<>();
        long now = System.currentTimeMillis();
        synchronized (pages) {
            while (pages.size() > MAX_PAGES) {
                var iterator = pages.entrySet().iterator();
                PageInfo selected = null;
                while (iterator.hasNext()) {
                    PageInfo candidate = iterator.next().getValue();
                    if (candidate.pending != null
                            || hasActiveRequestLocked(candidate.key, now)
                            || isRecentlyRenderVisible(candidate)) {
                        continue;
                    }
                    if (!candidate.initialized
                            || isOffscreenEvictionCandidateLocked(candidate, now)
                            || hasReplacementCoverage(candidate)) {
                        selected = candidate;
                        iterator.remove();
                        if (!requests.containsKey(candidate.key)) {
                            revisions.remove(candidate.key);
                        }
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
            ExactPageStateTracker.getInstance().remove(stateKey(info.key));
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
        return DenseCaveTile.normalizeLayer(view, projectionTopY(view, layerY));
    }

    private static int projectionTopY(CaveView view, int layerY) {
        return CaveLayerBand.projectionTopY(view, layerY);
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

    private record ProjectionBandKey(String dimension, CaveView view, int layerY) {
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
        /** Last live viewport demand, independent from enumeration cadence. */
        private long lastDemandMs;
        private long[] pagePlan = new long[0];
        private int pageCursor;
        /** First centre-out page that has not yet crossed the visual frontier. */
        private int publicationCursor;
        private int frontierWaitOrdinal = -1;
        private long frontierWaitStartedMs;
        private long baselineGpuPublicationSequence;
        private int updateSliceIndex;
        private long requestCompletedCycles;
        private float scale = 1.0f;
        private long nextRestartMs;

        private boolean shouldRecenter(int centerPageX, int centerPageZ,
                int thresholdPages) {
            if (focusPageX == Integer.MIN_VALUE) return false;
            return Math.max(Math.abs(centerPageX - focusPageX),
                    Math.abs(centerPageZ - focusPageZ)) >= Math.max(1, thresholdPages);
        }

        private void recenter(int centerPageX, int centerPageZ,
                MapRequestLane lane) {
            focusPageX = centerPageX;
            focusPageZ = centerPageZ;
            pagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    centerPageX, centerPageZ, lane == MapRequestLane.FULLSCREEN,
                    false, minPageX, maxPageX, minPageZ, maxPageZ);
            pageCursor = 0;
            publicationCursor = 0;
            frontierWaitOrdinal = -1;
            frontierWaitStartedMs = 0L;
            updateSliceIndex = 0;
            requestCompletedCycles = 0L;
            nextRestartMs = 0L;
        }

        private boolean sameProjectionOverlap(String dimension, CaveView view,
                int layerY, int projectionTopY,
                int minPageX, int maxPageX, int minPageZ, int maxPageZ) {
            return this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && rectanglesOverlap(this.minPageX, this.maxPageX,
                            this.minPageZ, this.maxPageZ,
                            minPageX, maxPageX, minPageZ, maxPageZ);
        }

        private boolean containsVisibleViewport(String dimension, CaveView view,
                int layerY, int projectionTopY,
                int visibleMinPageX, int visibleMaxPageX,
                int visibleMinPageZ, int visibleMaxPageZ) {
            return this.dimension.equals(dimension) && this.view == view
                    && this.layerY == layerY
                    && this.projectionTopY == projectionTopY
                    && visibleMinPageX >= minPageX
                    && visibleMaxPageX <= maxPageX
                    && visibleMinPageZ >= minPageZ
                    && visibleMaxPageZ <= maxPageZ;
        }

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
            publicationCursor = 0;
            frontierWaitOrdinal = -1;
            frontierWaitStartedMs = 0L;
            updateSliceIndex = 0;
            requestCompletedCycles = 0L;
            nextRestartMs = 0L;
        }

        private boolean matches(PageKey key) {
            return key != null && dimension.equals(key.dimension())
                    && view == key.view() && layerY == key.layerY()
                    && key.globalPageX() >= minPageX
                    && key.globalPageX() <= maxPageX
                    && key.globalPageZ() >= minPageZ
                    && key.globalPageZ() <= maxPageZ;
        }

        private int ordinalOf(int pageX, int pageZ) {
            return CaveLoadHierarchy.centerOutOrdinal(
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    focusPageX, focusPageZ, pageX, pageZ);
        }

        private void advancePublicationCursor(long now) {
            if (publicationCursor < pagePlan.length) publicationCursor++;
            ensureFrontierRingGrace(publicationCursor, now);
        }

        private long frontierNoSourceGraceMs() {
            if (publicationCursor < 0 || publicationCursor >= pagePlan.length) {
                return 120L;
            }
            long packed = pagePlan[publicationCursor];
            int ring = Math.max(
                    Math.abs(CaveLoadHierarchy.x(packed) - focusPageX),
                    Math.abs(CaveLoadHierarchy.z(packed) - focusPageZ));
            if (ring <= 1) return 650L;
            if (ring <= 4) return 250L;
            return 120L;
        }

        private void ensureFrontierRingGrace(int ordinal, long now) {
            if (ordinal < 0 || ordinal >= pagePlan.length) {
                frontierWaitOrdinal = -1;
                frontierWaitStartedMs = 0L;
                return;
            }
            long packed = pagePlan[ordinal];
            int ringStart = CaveLoadHierarchy.centerOutRingStart(
                    minPageX, maxPageX, minPageZ, maxPageZ,
                    focusPageX, focusPageZ,
                    CaveLoadHierarchy.x(packed), CaveLoadHierarchy.z(packed));
            if (frontierWaitOrdinal == ringStart) return;
            frontierWaitOrdinal = ringStart;
            frontierWaitStartedMs = now;
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
            lastDemandMs = 0L;
            pagePlan = new long[0];
            pageCursor = 0;
            publicationCursor = 0;
            frontierWaitOrdinal = -1;
            frontierWaitStartedMs = 0L;
            baselineGpuPublicationSequence = 0L;
            updateSliceIndex = 0;
            requestCompletedCycles = 0L;
            scale = 1.0f;
            nextRestartMs = 0L;
        }
    }

    private static final class PageRequest {
        private final PageKey key;
        private final long[] lastSeenByLane = new long[REQUEST_LANES.length];
        private final long[] firstSeenByLane = new long[REQUEST_LANES.length];
        private final int[] priorityByLane = new int[REQUEST_LANES.length];
        private final int[] ordinalByLane = new int[REQUEST_LANES.length];
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

        private void rebase(MapRequestLane lane, int priority, long now,
                int fullscreenOrdinal) {
            int index = lane.ordinal();
            lastSeenByLane[index] = now;
            firstSeenByLane[index] = now;
            priorityByLane[index] = priority;
            ordinalByLane[index] = lane == MapRequestLane.FULLSCREEN
                    ? fullscreenOrdinal : Integer.MAX_VALUE;
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
            for (MapRequestLane lane : REQUEST_LANES) {
                int index = lane.ordinal();
                long seen = lastSeenByLane[index];
                if (seen != 0L && now - seen <= lane.requestTtlMs()) continue;
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
            for (MapRequestLane lane : REQUEST_LANES) {
                long seen = lastSeenByLane[lane.ordinal()];
                if (seen == 0L || now - seen > lane.requestTtlMs()) continue;
                if (lane.strongerThan(best)) best = lane;
            }
            return best;
        }

        private int effectivePriority(long now) {
            int best = Integer.MIN_VALUE;
            for (MapRequestLane lane : REQUEST_LANES) {
                int index = lane.ordinal();
                long seen = lastSeenByLane[index];
                if (seen == 0L || now - seen > lane.requestTtlMs()) continue;
                long first = firstSeenByLane[index] == 0L
                        ? seen : firstSeenByLane[index];
                long ageMs = Math.max(0L, now - first);
                // Xaero re-evaluates a small nearest set every frame. Stable
                // centre-out work needs equivalent fairness: a valid frontier page
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
        /** Last logical page-table publication; independent from atlas residency. */
        private long pageTableSessionId;
        private long pageTableStorageGeneration = Long.MIN_VALUE;
        private int pageTableSlot = -1;
        private long pageTableRevision = Long.MIN_VALUE;
        private int[][] frontLods;
        private long firstGpuPublicationSequence;
        private CompletableFuture<BuildResult> pending;
        private MapCancellationToken pendingToken;
        private MapRequestLane pendingLane;
        private int pendingProjectionTopY = Integer.MIN_VALUE;
        private boolean pendingCompletionRecorded;
        /** Exact Layered Top-Y represented by the whole visible page when uniform. */
        private int publishedProjectionTopY = Integer.MIN_VALUE;
        /** Exact Top-Y currently requested inside this retained 16-block band. */
        private int activeProjectionTopY = Integer.MIN_VALUE;
        /** Per 16x16 visible tile identity; mixed values are valid during transition. */
        private final int[] visibleTileProjectionTopY = new int[PROJECTION_TILE_COUNT];
        /** Bit set only after the corresponding 16x16 tile is atomically committed. */
        private int visibleTileMask;
        /** CPU-only replacement assembled for activeProjectionTopY. */
        private int stagingProjectionTopY = Integer.MIN_VALUE;
        private int[] stagingPixels;
        private final long[] stagingKnownRows = new long[CaveTextureAtlas.PAGE_SIZE];
        private int stagingKnownColumns;
        private long uploadedRevision;
        private long uploadedSourceRevision = Long.MIN_VALUE;
        private long noSourceRevision = Long.MIN_VALUE;
        private long observedSourceRevision = Long.MIN_VALUE;
        private long sourceRevisionChangedMs;
        private long sourceBurstStartedMs;
        private long lastPublicationMs;
        private long nextRetryMs;
        /** Earliest retry for an immutable CPU-ready completion awaiting an atlas slot. */
        private long nextPublicationAttemptMs;
        private long stagedReadySinceMs;
        private int gpuReservationFailures;
        private long lastVisibleRenderEpoch;
        private long lastVisibleMs;
        private boolean initialized;
        private boolean knownEmpty;
        /** Authoritative coverage accumulated across partial page builds. */
        private final long[] knownRows = new long[CaveTextureAtlas.PAGE_SIZE];
        private int knownColumns;

        private PageInfo(PageKey key) {
            this.key = key;
            java.util.Arrays.fill(visibleTileProjectionTopY, Integer.MIN_VALUE);
        }

        private boolean matchesProjection(int projectionTopY) {
            return canRenderProjection(projectionTopY);
        }

        /**
         * A Layered cache key is the 16-block band. The exact Top-Y is transition
         * state inside that band. Retargeting must preserve the visible page and
         * prepare atomic 16x16 replacements rather than clearing the atlas.
         */
        private void beginProjectionTransition(int projectionTopY) {
            if (key.view() == CaveView.FULL) {
                activeProjectionTopY = Integer.MIN_VALUE;
                publishedProjectionTopY = Integer.MIN_VALUE;
                return;
            }
            if (activeProjectionTopY == projectionTopY) return;

            activeProjectionTopY = projectionTopY;
            resetProjectionStaging(projectionTopY);
            uploadedSourceRevision = Long.MIN_VALUE;
            noSourceRevision = Long.MIN_VALUE;
            observedSourceRevision = Long.MIN_VALUE;
            sourceRevisionChangedMs = 0L;
            sourceBurstStartedMs = 0L;
            lastPublicationMs = 0L;
            nextRetryMs = 0L;
            stagedReadySinceMs = 0L;
            knownEmpty = false;
        }

        private boolean canRenderProjection(int projectionTopY) {
            if (key.view() == CaveView.FULL) return true;
            return activeProjectionTopY == projectionTopY
                    && frontLods != null && knownColumns > 0;
        }

        private boolean isProjectionAuthoritative(int projectionTopY) {
            int fullMask = (1 << PROJECTION_TILE_COUNT) - 1;
            if (visibleTileMask != fullMask) return false;
            if (key.view() == CaveView.FULL) return true;
            if (activeProjectionTopY != projectionTopY) return false;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if (visibleTileProjectionTopY[tile] != projectionTopY) return false;
            }
            return true;
        }

        private boolean hasVisibleProjectionDifferentFrom(int projectionTopY) {
            if (!initialized || visibleTileMask == 0) return false;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((visibleTileMask & (1 << tile)) == 0) continue;
                if (visibleTileProjectionTopY[tile] != projectionTopY) return true;
            }
            return false;
        }

        private boolean stagingProjectionComplete(int projectionTopY) {
            if (stagingProjectionTopY != projectionTopY) return false;
            for (long row : stagingKnownRows) {
                if (row != -1L) return false;
            }
            return true;
        }

        private int currentProjectionKnownColumns(int projectionTopY) {
            int count = 0;
            for (int row = 0; row < CaveTextureAtlas.PAGE_SIZE; row++) {
                count += Long.bitCount(projectionKnownRow(projectionTopY, row));
            }
            return count;
        }

        private int countAddedProjectionColumns(int projectionTopY,
                long[] incomingRows) {
            if (incomingRows == null) return 0;
            int added = 0;
            int rows = Math.min(CaveTextureAtlas.PAGE_SIZE, incomingRows.length);
            for (int row = 0; row < rows; row++) {
                added += Long.bitCount(incomingRows[row]
                        & ~projectionKnownRow(projectionTopY, row));
            }
            return added;
        }

        private int countNewReadyProjectionTiles(int projectionTopY,
                long[] incomingRows, boolean complete) {
            long[] candidateRows = stagingProjectionTopY == projectionTopY
                    ? stagingKnownRows : null;
            int ready = 0;
            for (int tileZ = 0; tileZ < PROJECTION_TILES_PER_PAGE; tileZ++) {
                for (int tileX = 0; tileX < PROJECTION_TILES_PER_PAGE; tileX++) {
                    int tile = tileZ * PROJECTION_TILES_PER_PAGE + tileX;
                    if ((visibleTileMask & (1 << tile)) != 0
                            && visibleTileProjectionTopY[tile] == projectionTopY) continue;
                    boolean tileReady = true;
                    long mask = PROJECTION_TILE_ROW_MASK
                            << (tileX * PROJECTION_TILE_SIZE);
                    int firstRow = tileZ * PROJECTION_TILE_SIZE;
                    for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                        int row = firstRow + localZ;
                        long known = complete ? -1L : 0L;
                        if (candidateRows != null) known |= candidateRows[row];
                        if (incomingRows != null && row < incomingRows.length) {
                            known |= incomingRows[row];
                        }
                        if ((known & mask) != mask) {
                            tileReady = false;
                            break;
                        }
                    }
                    if (tileReady) ready++;
                }
            }
            return ready;
        }

        private long projectionKnownRow(int projectionTopY, int row) {
            long known = stagingProjectionTopY == projectionTopY
                    ? stagingKnownRows[row] : 0L;
            int tileZ = row / PROJECTION_TILE_SIZE;
            for (int tileX = 0; tileX < PROJECTION_TILES_PER_PAGE; tileX++) {
                int tile = tileZ * PROJECTION_TILES_PER_PAGE + tileX;
                if ((visibleTileMask & (1 << tile)) != 0
                        && visibleTileProjectionTopY[tile] == projectionTopY) {
                    known |= PROJECTION_TILE_ROW_MASK
                            << (tileX * PROJECTION_TILE_SIZE);
                }
            }
            return known;
        }

        private int stageProjection(int projectionTopY, int[] pixels,
                long[] incomingRows, boolean complete) {
            if (stagingProjectionTopY != projectionTopY) {
                resetProjectionStaging(projectionTopY);
            }
            if (stagingPixels == null) {
                stagingPixels = new int[CaveTextureAtlas.PAGE_SIZE
                        * CaveTextureAtlas.PAGE_SIZE];
            }
            int before = stagingKnownColumns;
            if (complete) {
                System.arraycopy(pixels, 0, stagingPixels, 0, stagingPixels.length);
                java.util.Arrays.fill(stagingKnownRows, -1L);
                stagingKnownColumns = stagingPixels.length;
                return Math.max(0, stagingKnownColumns - before);
            }
            for (int row = 0; row < CaveTextureAtlas.PAGE_SIZE; row++) {
                long mask = incomingRows[row];
                stagingKnownColumns += Long.bitCount(mask & ~stagingKnownRows[row]);
                long copy = mask;
                while (copy != 0L) {
                    int x = Long.numberOfTrailingZeros(copy);
                    stagingPixels[row * CaveTextureAtlas.PAGE_SIZE + x] =
                            pixels[row * CaveTextureAtlas.PAGE_SIZE + x];
                    copy &= copy - 1L;
                }
                stagingKnownRows[row] |= mask;
            }
            return Math.max(0, stagingKnownColumns - before);
        }

        private int readyStagedTileMask(int projectionTopY,
                int[] visiblePixels) {
            if (stagingProjectionTopY != projectionTopY
                    || visiblePixels == null) return 0;
            int maskResult = 0;
            for (int tileZ = 0; tileZ < PROJECTION_TILES_PER_PAGE; tileZ++) {
                for (int tileX = 0; tileX < PROJECTION_TILES_PER_PAGE; tileX++) {
                    long mask = PROJECTION_TILE_ROW_MASK
                            << (tileX * PROJECTION_TILE_SIZE);
                    int firstRow = tileZ * PROJECTION_TILE_SIZE;
                    boolean ready = true;
                    for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                        if ((stagingKnownRows[firstRow + localZ] & mask) != mask) {
                            ready = false;
                            break;
                        }
                    }
                    if (ready) {
                        int tile = tileZ * PROJECTION_TILES_PER_PAGE + tileX;
                        boolean projectionChanged =
                                (visibleTileMask & (1 << tile)) == 0
                                        || visibleTileProjectionTopY[tile] != projectionTopY;
                        boolean pixelsChanged = projectionChanged;
                        if (!pixelsChanged) {
                            int firstX = tileX * PROJECTION_TILE_SIZE;
                            for (int localZ = 0; localZ < PROJECTION_TILE_SIZE
                                    && !pixelsChanged; localZ++) {
                                int row = firstRow + localZ;
                                int offset = row * CaveTextureAtlas.PAGE_SIZE + firstX;
                                for (int localX = 0;
                                        localX < PROJECTION_TILE_SIZE; localX++) {
                                    if (visiblePixels[offset + localX]
                                            != stagingPixels[offset + localX]) {
                                        pixelsChanged = true;
                                        break;
                                    }
                                }
                            }
                        }
                        if (pixelsChanged) maskResult |= 1 << tile;
                    }
                }
            }
            return maskResult;
        }

        private void copyStagedTiles(int projectionTopY, int tileMask,
                int[] destination) {
            if (stagingProjectionTopY != projectionTopY || stagingPixels == null) return;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((tileMask & (1 << tile)) == 0) continue;
                int tileX = tile % PROJECTION_TILES_PER_PAGE;
                int tileZ = tile / PROJECTION_TILES_PER_PAGE;
                int firstX = tileX * PROJECTION_TILE_SIZE;
                int firstZ = tileZ * PROJECTION_TILE_SIZE;
                for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                    int offset = (firstZ + localZ) * CaveTextureAtlas.PAGE_SIZE
                            + firstX;
                    System.arraycopy(stagingPixels, offset, destination, offset,
                            PROJECTION_TILE_SIZE);
                }
            }
        }

        private boolean wouldBeProjectionAuthoritative(int projectionTopY,
                int tileMask) {
            int projectedMask = visibleTileMask | tileMask;
            if (projectedMask != (1 << PROJECTION_TILE_COUNT) - 1) return false;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((tileMask & (1 << tile)) != 0) continue;
                if (visibleTileProjectionTopY[tile] != projectionTopY) return false;
            }
            return true;
        }

        private void commitStagedTiles(int projectionTopY, int tileMask,
                int[] destination) {
            if (stagingProjectionTopY != projectionTopY || stagingPixels == null) return;
            for (int tile = 0; tile < PROJECTION_TILE_COUNT; tile++) {
                if ((tileMask & (1 << tile)) == 0) continue;
                int tileX = tile % PROJECTION_TILES_PER_PAGE;
                int tileZ = tile / PROJECTION_TILES_PER_PAGE;
                int firstX = tileX * PROJECTION_TILE_SIZE;
                int firstZ = tileZ * PROJECTION_TILE_SIZE;
                long rowMask = PROJECTION_TILE_ROW_MASK << firstX;
                for (int localZ = 0; localZ < PROJECTION_TILE_SIZE; localZ++) {
                    int row = firstZ + localZ;
                    int offset = row * CaveTextureAtlas.PAGE_SIZE + firstX;
                    System.arraycopy(stagingPixels, offset, destination, offset,
                            PROJECTION_TILE_SIZE);
                    knownRows[row] |= rowMask;
                }
                visibleTileProjectionTopY[tile] = projectionTopY;
                visibleTileMask |= 1 << tile;
            }
            knownColumns = countKnownColumns(knownRows);
            if (isProjectionAuthoritative(projectionTopY)) {
                publishedProjectionTopY = projectionTopY;
            }
        }

        private void resetProjectionStaging(int projectionTopY) {
            stagingProjectionTopY = projectionTopY;
            java.util.Arrays.fill(stagingKnownRows, 0L);
            stagingKnownColumns = 0;
            stagedReadySinceMs = 0L;
            if (stagingPixels != null) java.util.Arrays.fill(stagingPixels, 0);
        }

        private int countKnownColumns(long[] rows) {
            int count = 0;
            for (long row : rows) count += Long.bitCount(row);
            return count;
        }

        private void observeSourceRevision(long sourceRevision, long now) {
            if (observedSourceRevision == sourceRevision) return;
            if (sourceRevisionChangedMs == 0L || sourceBurstStartedMs == 0L
                    || now - sourceBurstStartedMs >= SOURCE_BURST_MAX_MS
                    || now - sourceRevisionChangedMs > SOURCE_QUIET_MS * 2L) {
                sourceBurstStartedMs = now;
            }
            observedSourceRevision = sourceRevision;
            sourceRevisionChangedMs = now;
        }

        private void restartSourceSettleWindow(long sourceRevision, long now) {
            observedSourceRevision = sourceRevision;
            sourceRevisionChangedMs = now;
            sourceBurstStartedMs = now;
        }

        private long sourceSettleDeadlineMs() {
            if (sourceRevisionChangedMs == 0L) return 0L;
            long quietDeadline = sourceRevisionChangedMs + SOURCE_QUIET_MS;
            long burstDeadline = sourceBurstStartedMs == 0L
                    ? quietDeadline : sourceBurstStartedMs + SOURCE_BURST_MAX_MS;
            return Math.min(quietDeadline, burstDeadline);
        }

        private boolean isSourceSettled(long now) {
            return now >= sourceSettleDeadlineMs();
        }

        private void markSourceSettled(long sourceRevision) {
            observedSourceRevision = sourceRevision;
            sourceRevisionChangedMs = 0L;
            sourceBurstStartedMs = 0L;
        }

        private void ensureBuffers() {
            if (frontLods != null) return;
            frontLods = allocateLodBuffers();
        }

        private void releaseAtlasSlot() {
            boolean wasResident = initialized && atlasSlot >= 0;
            if (wasResident) indexResidentPage(key, -1);
            removePageTable(this);
            if (atlasSlot >= 0) atlas.releaseSlot(atlasSlot);
            if (wasResident) {
                MapResidencyManager.getInstance().remove(residencyKey(key));
                exactTopologyRevision.incrementAndGet();
            }
            atlasSlot = -1;
            initialized = false;
            if (wasResident) {
                ExactPageStateTracker.getInstance().transition(
                        stateKey(key), ExactPageState.GPU_EVICTED,
                        pendingLane, revisions.getOrDefault(key, uploadedRevision));
            }
        }

        private void close() {
            if (pendingToken != null) pendingToken.cancel();
            if (pending != null && pending.cancel(false)) {
                pipelineTelemetry.recordTaskCancelledBeforeRun();
            }
            pending = null;
            pendingToken = null;
            pendingLane = null;
            pendingProjectionTopY = Integer.MIN_VALUE;
            pendingCompletionRecorded = false;
            nextPublicationAttemptMs = 0L;
            stagedReadySinceMs = 0L;
            gpuReservationFailures = 0;
            releaseAtlasSlot();
            frontLods = null;
            stagingPixels = null;
            java.util.Arrays.fill(stagingKnownRows, 0L);
            java.util.Arrays.fill(visibleTileProjectionTopY, Integer.MIN_VALUE);
            visibleTileMask = 0;
            stagingKnownColumns = 0;
            stagingProjectionTopY = Integer.MIN_VALUE;
            activeProjectionTopY = Integer.MIN_VALUE;
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

    private enum PublicationDeferral {
        NONE,
        ATLAS_SLOT,
        GPU_BUDGET,
        COALESCE
    }

    private record ApplyOutcome(boolean applied, boolean hasContent,
            PublicationDeferral deferral) {
        private static final ApplyOutcome NOT_APPLIED =
                new ApplyOutcome(false, false, PublicationDeferral.NONE);
        private static final ApplyOutcome ATLAS_DEFERRED =
                new ApplyOutcome(false, true, PublicationDeferral.ATLAS_SLOT);
        private static final ApplyOutcome GPU_DEFERRED =
                new ApplyOutcome(false, true, PublicationDeferral.GPU_BUDGET);
        private static final ApplyOutcome COALESCED =
                new ApplyOutcome(false, true, PublicationDeferral.COALESCE);
        private static final ApplyOutcome APPLIED_EMPTY =
                new ApplyOutcome(true, false, PublicationDeferral.NONE);
        private static final ApplyOutcome APPLIED_CONTENT =
                new ApplyOutcome(true, true, PublicationDeferral.NONE);

        private boolean retryablePublication() {
            return deferral != PublicationDeferral.NONE;
        }
    }

    private record BuildResult(long expectedRevision, long sourceRevision,
            int projectionTopY, int[] pixels, long[] knownRows,
            int knownColumns, boolean complete, boolean superseded) {
        private static BuildResult superseded(long expectedRevision,
                long sourceRevision, int projectionTopY) {
            return new BuildResult(expectedRevision, sourceRevision,
                    projectionTopY, null, null, 0, false, true);
        }
    }
}
