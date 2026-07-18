package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;

/**
 * Adaptive time-budget controller. Map work yields aggressively whenever frame
 * pacing degrades or Minecraft is busy streaming chunks.
 */
public final class MapPerformanceGovernor {
    private static final MapPerformanceGovernor INSTANCE = new MapPerformanceGovernor();
    private static final double TARGET_FRAME_NANOS = 16_666_667.0;

    private volatile boolean fullscreenOpen;
    private volatile boolean interacting;
    private volatile long lastFrameNanos;
    private volatile double smoothedFrameNanos = TARGET_FRAME_NANOS;
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
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0L) return;
        long elapsed = Math.min(250_000_000L, Math.max(1_000_000L, now - previous));
        smoothedFrameNanos = smoothedFrameNanos * 0.92 + elapsed * 0.08;
        if (elapsed > 28_000_000L) pressureFrames = Math.min(120, pressureFrames + 4);
        else if (elapsed > 21_000_000L) pressureFrames = Math.min(120, pressureFrames + 1);
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
        return pressureFrames > 8 || smoothedFrameNanos > 23_000_000.0;
    }

    private double headroomFactor() {
        double factor = TARGET_FRAME_NANOS / Math.max(TARGET_FRAME_NANOS, smoothedFrameNanos);
        if (pressureFrames > 20) factor *= 0.55;
        else if (pressureFrames > 8) factor *= 0.75;
        return Math.max(0.25, Math.min(1.0, factor));
    }

    public long gameplayScanBudgetNanos(boolean cave) {
        if (interacting) return 0L;
        long base = cave ? 800_000L : 600_000L;
        return Math.max(180_000L, (long) (base * headroomFactor()));
    }

    public long verticalArchiveBudgetNanos() {
        if (interacting || underPressure()) return 0L;
        return Math.max(100_000L, (long) (350_000L * headroomFactor()));
    }

    public long fullscreenScanBudgetNanos(float scale, boolean fastLoading) {
        if (!fullscreenOpen || interacting) return 0L;
        long base;
        if (scale < 0.20f) base = fastLoading ? 1_800_000L : 900_000L;
        else if (scale < 0.55f) base = fastLoading ? 3_200_000L : 1_600_000L;
        else base = fastLoading ? 5_000_000L : 2_500_000L;
        return Math.max(650_000L, (long) (base * headroomFactor()));
    }

    public long textureUploadBudgetNanos(boolean focus) {
        if (interacting) return 0L;
        long base = focus ? 3_000_000L : 1_000_000L;
        return Math.max(500_000L, (long) (base * headroomFactor()));
    }

    public int texturePageBudget(boolean focus) {
        if (interacting) return 0;
        int base = focus ? 12 : 4;
        return Math.max(1, (int) Math.round(base * headroomFactor()));
    }

    public boolean allowBackgroundWork(Minecraft minecraft) {
        if (interacting || underPressure()) return false;
        if (minecraft == null || minecraft.player == null) return false;
        double speedSq = minecraft.player.getDeltaMovement().horizontalDistanceSqr();
        return speedSq < 0.18;
    }
}
