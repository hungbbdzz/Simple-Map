package com.velorise.simplemap.client;

/**
 * Single render-thread publication runner for all visible map projections.
 *
 * <p>Viewport/tick code publishes intent and performs no GPU work. Render events
 * drain the same intent against one shared frame ledger. This mirrors Xaero's
 * central render-process pattern: completed CPU work can advance every rendered
 * frame, while a slow frame automatically receives a smaller upload slice.</p>
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
     * Drains at most one projection family for one actual rendered frame. The
     * viewport intent deliberately remains live until the next client tick, so a
     * 120/144 Hz client can consume completed work in small slices instead of one
     * 20 TPS burst.
     */
    public void drainFrame(long frameId) {
        if (!publicationAllowed) return;
        if (frameId == lastFrameId) return;
        if (!surfaceRequested && !layeredCaveRequested && !fullCaveRequested) return;
        lastFrameId = frameId;

        boolean focused = fullCaveRequested ? fullCaveFocus
                : layeredCaveRequested ? layeredCaveFocus : surfaceFocus;
        MapGpuBudgetController.getInstance().beginFrame(focused);
        long started = System.nanoTime();
        if (fullCaveRequested) {
            FullCaveTextureManager.getInstance().uploadDirtyTextures(fullCaveFocus);
        } else if (layeredCaveRequested) {
            CaveTextureManager.getInstance().uploadDirtyTextures(false);
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
