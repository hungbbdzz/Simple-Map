package com.velorise.simplemap.client;

/**
 * Single render-thread publication runner for all visible map projections.
 *
 * <p>Viewport/tick code publishes intent and performs no GPU work. Render events
 * drain the same intent against one shared frame ledger. This mirrors Xaero's
 * central render-process pattern: completed CPU work can advance every rendered
 * frame, while a slow frame automatically receives a smaller upload slice. The
 * visible projection always runs first; a cadence-bounded secondary Surface
 * maintenance slice may then use otherwise-unused shared-ledger capacity.</p>
 */
public final class MapPublicationCoordinator {
    private static final MapPublicationCoordinator INSTANCE =
            new MapPublicationCoordinator();

    private boolean surfaceRequested;
    private boolean surfaceFocus;
    private MapRequestLane surfaceLane;
    private boolean layeredCaveRequested;
    private boolean layeredCaveFocus;
    private boolean fullCaveRequested;
    private boolean fullCaveFocus;
    private boolean publicationAllowed;
    private long lastFrameId = Long.MIN_VALUE;
    private long drainCount;
    private long coalescedRequests;

    private MapPublicationCoordinator() {
    }

    public static MapPublicationCoordinator getInstance() {
        return INSTANCE;
    }

    /** Starts a fresh viewport-intent epoch. GPU publication remains frame-owned. */
    public void beginTick() {
        surfaceRequested = false;
        surfaceFocus = false;
        surfaceLane = null;
        layeredCaveRequested = false;
        layeredCaveFocus = false;
        fullCaveRequested = false;
        fullCaveFocus = false;
        coalescedRequests = 0L;
    }

    public void setPublicationAllowed(boolean allowed) {
        publicationAllowed = allowed;
    }

    public void requestSurface(boolean focus) {
        requestSurface(MapRequestLane.BACKGROUND, focus);
    }

    public void requestSurface(MapRequestLane lane, boolean focus) {
        if (surfaceRequested) coalescedRequests++;
        surfaceRequested = true;
        surfaceFocus |= focus;
        MapRequestLane effective = lane == null
                ? MapRequestLane.BACKGROUND : lane;
        if (effective.strongerThan(surfaceLane)) surfaceLane = effective;
    }

    public void requestLayeredCave() {
        requestLayeredCave(false);
    }

    public void requestLayeredCave(boolean focus) {
        if (layeredCaveRequested) coalescedRequests++;
        layeredCaveRequested = true;
        layeredCaveFocus |= focus;
    }

    public void requestFullCave(boolean focus) {
        if (fullCaveRequested) coalescedRequests++;
        fullCaveRequested = true;
        fullCaveFocus |= focus;
    }

    /**
     * Drains one primary projection family for one actual rendered frame. A
     * cadence-bounded Surface maintenance slice may use leftover ledger capacity
     * after Cave publication. The
     * viewport intent deliberately remains live until the next client tick, so a
     * 120/144 Hz client can consume completed work in small slices instead of one
     * 20 TPS burst.
     */
    public void drainFrame(long frameId) {
        if (MapActivityGate.getInstance().blocksForegroundStreaming()) return;
        if (!publicationAllowed) return;
        if (frameId == lastFrameId) return;
        if (!surfaceRequested && !layeredCaveRequested && !fullCaveRequested) return;
        lastFrameId = frameId;

        boolean focused = fullCaveRequested ? fullCaveFocus
                : layeredCaveRequested ? layeredCaveFocus : surfaceFocus;
        // Source capture and GPU upload must share the same physical-frame
        // boundary. A time-window budget can otherwise refill repeatedly inside
        // one already-slow frame and deepen the movement hitch.
        SurfaceRegionSourceDatabase.getInstance().beginPublicationFrame(frameId);
        MapGpuBudgetController.getInstance().beginFrame(focused);
        long started = System.nanoTime();
        boolean cavePrimary = false;
        if (fullCaveRequested) {
            FullCaveTextureManager.getInstance().uploadDirtyTextures(fullCaveFocus);
            cavePrimary = true;
        } else if (layeredCaveRequested) {
            // Focus is now deadline-safe inside UnifiedCaveTextureManager. Preserve
            // the viewport's close-zoom intent so Layered Cave is not artificially
            // throttled while Full Cave receives the same focus signal correctly.
            CaveTextureManager.getInstance().uploadDirtyTextures(layeredCaveFocus);
            cavePrimary = true;
        } else if (surfaceRequested) {
            /*
             * Publish prepared coarse branches before exact leaves consume the
             * shared frame ledger. This gives far-zoom L1 coverage a deterministic
             * path to the screen instead of letting exact uploads starve it forever.
             * Exact pages published below feed the branch source for the next frame;
             * avoiding a second same-frame drain keeps denial counters and render
             * thread planning work bounded.
             */
            MapOverviewTextureManager.getInstance().publishBranches(surfaceFocus);
            MapTextureManager.getInstance().uploadExactTextures(
                    surfaceLane, surfaceFocus);
        }
        /*
         * Cave owns the visible projection, but a completed Surface payload is
         * immutable and needs only a bounded atlas upload. Let one such payload use
         * leftover shared GPU-ledger capacity on non-pressured frames. This does not
         * capture source or submit CPU work, and tryReserve() still protects the
         * primary Cave upload and minimap reserve.
         */
        if (cavePrimary
                && !MapPerformanceGovernor.getInstance().underPressure()) {
            if (surfaceRequested) {
                // A maintenance request exists only on the bounded Cave-background
                // cadence. Let it submit/drain one BACKGROUND Surface slice using
                // the existing 1.5 ms deadline and shared GPU ledger.
                MapOverviewTextureManager.getInstance().publishBranches(false);
                MapTextureManager.getInstance().uploadExactTextures(
                        surfaceLane == null ? MapRequestLane.BACKGROUND : surfaceLane,
                        false);
            } else if (MapTextureManager.getInstance().hasCompletedExactTextures()) {
                MapTextureManager.getInstance().publishCompletedExactTextures(
                        1, 650_000L);
            }
        }
        drainCount++;
        MapObservationTelemetry.getInstance().record(
                MapObservationTelemetry.Lane.PUBLICATION,
                System.nanoTime() - started, 1);
    }

    /** Compatibility entry point for manual/debug paths. */
    public void drainFrame() {
        // Non-render callers cannot identify a physical frame. They receive a
        // unique ledger only for this compatibility path; normal rendering must
        // call the overload with the real client-owned frame id.
        drainFrame(lastFrameId == Long.MAX_VALUE ? Long.MIN_VALUE : lastFrameId + 1L);
    }

    /** Compatibility entry point for manual/debug paths. */
    public void drain(boolean allowed) {
        setPublicationAllowed(allowed);
        drainFrame();
    }

    public Snapshot snapshot() {
        return new Snapshot(drainCount, coalescedRequests,
                surfaceRequested, surfaceLane,
                layeredCaveRequested, fullCaveRequested,
                publicationAllowed);
    }

    public record Snapshot(long drainCount, long coalescedRequests,
            boolean surfaceRequested, MapRequestLane surfaceLane,
            boolean layeredCaveRequested, boolean fullCaveRequested,
            boolean publicationAllowed) {
    }
}
