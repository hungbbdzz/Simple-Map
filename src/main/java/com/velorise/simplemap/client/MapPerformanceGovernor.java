package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;

/**
 * Adaptive time-budget controller. Visible map work continues while dragging;
 * only measured frame pressure reduces its slice. Background work still yields
 * when Minecraft is busy streaming chunks.
 */
public final class MapPerformanceGovernor {
    private static final MapPerformanceGovernor INSTANCE = new MapPerformanceGovernor();
    private static final double DEFAULT_TARGET_FRAME_NANOS = 16_666_667.0;
    private static final double MIN_TARGET_FRAME_NANOS = 6_500_000.0;
    private static final double MAX_TARGET_FRAME_NANOS = 33_333_333.0;
    private static final long DUPLICATE_FRAME_EVENT_NANOS = 1_500_000L;

    private volatile boolean fullscreenOpen;
    private volatile boolean interacting;
    private volatile long lastFrameNanos;
    private volatile long lastFrameEventNanos;
    private volatile double smoothedFrameNanos = DEFAULT_TARGET_FRAME_NANOS;
    /** Slowly rising recent best frame time estimates the actual cap/vsync target. */
    private volatile double baselineFrameNanos = DEFAULT_TARGET_FRAME_NANOS;
    private volatile int pressureFrames;
    private volatile double focusWorldX;
    private volatile double focusWorldZ;

    private MapPerformanceGovernor() {
    }

    public static MapPerformanceGovernor getInstance() {
        return INSTANCE;
    }

    public void onFrame() {
        long now = System.nanoTime();
        // RenderGuiEvent and ScreenEvent can both fire for one visual frame.
        // Treat near-adjacent callbacks as the same frame instead of reporting a
        // fictitious sub-millisecond frame and resetting upload pressure twice.
        if (lastFrameEventNanos != 0L
                && now - lastFrameEventNanos < DUPLICATE_FRAME_EVENT_NANOS) return;
        lastFrameEventNanos = now;
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0L) return;
        long elapsed = Math.min(250_000_000L, Math.max(1_000_000L, now - previous));
        smoothedFrameNanos = smoothedFrameNanos * 0.92 + elapsed * 0.08;
        double risingBaseline = Math.min(MAX_TARGET_FRAME_NANOS,
                baselineFrameNanos * 1.0025D);
        baselineFrameNanos = Math.max(MIN_TARGET_FRAME_NANOS,
                Math.min(risingBaseline, elapsed));
        double target = targetFrameNanos();
        if (elapsed > target * 1.75D) pressureFrames = Math.min(120, pressureFrames + 4);
        else if (elapsed > target * 1.35D) pressureFrames = Math.min(120, pressureFrames + 1);
        else pressureFrames = Math.max(0, pressureFrames - 1);
    }

    public void setFullscreenState(boolean open, boolean interacting) {
        this.fullscreenOpen = open;
        this.interacting = interacting;
    }

    public void setFocus(double worldX, double worldZ) {
        focusWorldX = worldX;
        focusWorldZ = worldZ;
    }

    public double focusDistanceSquared(double worldX, double worldZ) {
        double dx = worldX - focusWorldX;
        double dz = worldZ - focusWorldZ;
        return dx * dx + dz * dz;
    }

    public boolean isFullscreenOpen() {
        return fullscreenOpen;
    }

    public boolean isInteracting() {
        return interacting;
    }

    public boolean underPressure() {
        MapWorkScheduler.Snapshot work = MapWorkScheduler.snapshot();
        double target = targetFrameNanos();
        return pressureFrames > 8 || smoothedFrameNanos > target * 1.40D
                || work.cpuTotalCost() > 800L || work.ioTotalCost() > 440L;
    }

    private double targetFrameNanos() {
        return Math.max(MIN_TARGET_FRAME_NANOS,
                Math.min(MAX_TARGET_FRAME_NANOS, baselineFrameNanos * 1.06D));
    }

    private double headroomFactor() {
        double target = targetFrameNanos();
        double factor = target / Math.max(target, smoothedFrameNanos);
        if (pressureFrames > 20) factor *= 0.55;
        else if (pressureFrames > 8) factor *= 0.75;
        MapWorkScheduler.Snapshot work = MapWorkScheduler.snapshot();
        if (work.cpuTotalCost() > 800L) factor *= 0.55;
        else if (work.cpuTotalCost() > 560L) factor *= 0.75;
        if (work.ioTotalCost() > 400L) factor *= 0.80;
        return Math.max(0.20, Math.min(1.0, factor));
    }

    public long gameplayScanBudgetNanos(boolean cave) {
        // Cave projection is substantially more expensive per useful page than
        // surface scanning. Keep a larger but still adaptive slice so the minimap
        // does not wait seconds for the first 16x16 tiles.
        long base = cave ? 1_500_000L : 700_000L;
        return Math.max(120_000L, (long) (base * headroomFactor()));
    }

    public long verticalArchiveBudgetNanos() {
        if (underPressure()) return 0L;
        return Math.max(120_000L, (long) (450_000L * headroomFactor()));
    }

    public long fullscreenScanBudgetNanos(float scale, boolean fastLoading) {
        if (!fullscreenOpen) return 0L;
        long base;
        if (scale < 0.20f) base = fastLoading ? 1_800_000L : 900_000L;
        else if (scale < 0.55f) base = fastLoading ? 3_200_000L : 1_600_000L;
        else base = fastLoading ? 5_000_000L : 2_500_000L;
        return Math.max(650_000L, (long) (base * headroomFactor()));
    }

    public long fullscreenCaveBudgetNanos(float scale, boolean fastLoading) {
        if (!fullscreenOpen) return 0L;
        long base;
        if (scale < 0.20f) base = fastLoading ? 900_000L : 450_000L;
        else if (scale < 0.55f) base = fastLoading ? 1_500_000L : 800_000L;
        else base = fastLoading ? 2_000_000L : 1_100_000L;
        return Math.max(250_000L, (long) (base * headroomFactor()));
    }

    public long textureUploadBudgetNanos(boolean focus) {
        double target = targetFrameNanos();
        double current = Math.max(target, smoothedFrameNanos);
        double measuredSlack = Math.max(0.0D, target - Math.min(target, current * 0.82D));
        long base = focus ? 3_250_000L : 1_250_000L;
        long adaptive = (long) (base * headroomFactor() + measuredSlack * 0.22D);
        long maximum = focus
                ? (long) Math.max(1_000_000L, target * 0.24D)
                : (long) Math.max(450_000L, target * 0.10D);
        return Math.max(300_000L, Math.min(maximum, adaptive));
    }

    public long targetFrameBudgetNanos() {
        return (long) targetFrameNanos();
    }

    public double smoothedFrameNanos() {
        return smoothedFrameNanos;
    }

    public int texturePageBudget(boolean focus) {
        int base = focus ? 12 : 4;
        return Math.max(1, (int) Math.round(base * headroomFactor()));
    }


    /**
     * One immutable admission profile consumed by the unified observation scheduler.
     * Existing subsystem-specific budget methods remain the low-level time slices;
     * this profile decides which lanes may run and how much mutation/viewport work
     * may be admitted during the current frame-pressure regime.
     */
    public ObservationProfile observationProfile(Minecraft minecraft) {
        boolean pressured = underPressure();
        boolean movingFast = minecraft != null && minecraft.player != null
                && minecraft.player.getDeltaMovement().horizontalDistanceSqr() >= 0.18;
        if (pressured) {
            return new ObservationProfile(24, 1, 0.65,
                    80_000_000L, 140_000_000L,
                    true, true, false, true, false);
        }
        if (fullscreenOpen) {
            return new ObservationProfile(48, 2, 1.0,
                    20_000_000L, 50_000_000L,
                    true, true, !movingFast, true, !movingFast);
        }
        return new ObservationProfile(64, 2, movingFast ? 0.80 : 1.0,
                40_000_000L, 50_000_000L,
                true, true, !movingFast, true, !movingFast);
    }

    public record ObservationProfile(int mutationColumnBudget,
            int mutationChunkBudget, double liveRadiusFactor,
            long fullscreenIntervalNanos, long minimapIntervalNanos,
            boolean allowVisibleScan, boolean allowSavedVisible,
            boolean allowLayerWarmup, boolean allowPublication,
            boolean allowArchiveBackground) {
    }

    public boolean allowBackgroundWork(Minecraft minecraft) {
        if (underPressure()) return false;
        if (minecraft == null || minecraft.player == null) return false;
        double speedSq = minecraft.player.getDeltaMovement().horizontalDistanceSqr();
        return speedSq < 0.18;
    }
}
