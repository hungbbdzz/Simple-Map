package com.velorise.simplemap.client;

import java.util.EnumMap;

/**
 * Shared render-thread upload governor for every Simple Map GPU producer.
 *
 * <p>The controller budgets both measured driver time and uploaded bytes. Time
 * protects frame pacing; bytes prevent a burst of many cheap-looking partial
 * updates from saturating PCIe/PBO bandwidth or retaining too many completed CPU
 * payloads in one publication window.</p>
 */
public final class MapGpuBudgetController {
    private static final MapGpuBudgetController INSTANCE = new MapGpuBudgetController();
    /** Fallback only when publication is invoked outside the render-frame runner. */
    private static final long FALLBACK_WINDOW_NANOS = 50_000_000L;
    private static final long NORMAL_BUDGET_NANOS = 3_500_000L;
    private static final long PRESSURE_BUDGET_NANOS = 1_800_000L;
    private static final long FOCUSED_BUDGET_NANOS = 6_000_000L;
    private static final long MINIMAP_RESERVE_NANOS = 1_000_000L;
    private static final long NORMAL_BYTE_BUDGET = 2L << 20;
    private static final long PRESSURE_BYTE_BUDGET = 768L << 10;
    private static final long FOCUSED_BYTE_BUDGET = 4L << 20;
    private static final long MINIMAP_RESERVE_BYTES = 384L << 10;

    public enum UploadKind {
        SURFACE_EXACT(450_000L, 2L * 64L * 64L * 4L),
        CAVE_EXACT(750_000L, 4L * (64L * 64L + 32L * 32L + 16L * 16L + 8L * 8L)),
        BRANCH(220_000L, 66L * 66L * 4L),
        LEGACY(1_500_000L, 2L * 512L * 512L * 4L);

        private final long initialNanos;
        private final long estimatedBytes;

        UploadKind(long initialNanos, long estimatedBytes) {
            this.initialNanos = initialNanos;
            this.estimatedBytes = estimatedBytes;
        }
    }

    private final EnumMap<UploadKind, Long> ewmaNanos =
            new EnumMap<>(UploadKind.class);
    private long windowStartNanos;
    private long frameBudgetNanos = NORMAL_BUDGET_NANOS;
    private long frameByteBudget = NORMAL_BYTE_BUDGET;
    private boolean explicitFrame;
    private long reservedNanos;
    private long minimapReservedNanos;
    private long reservedBytes;
    private long minimapReservedBytes;

    private MapGpuBudgetController() {
        for (UploadKind kind : UploadKind.values()) {
            ewmaNanos.put(kind, kind.initialNanos);
        }
    }

    public static MapGpuBudgetController getInstance() {
        return INSTANCE;
    }

    /**
     * Starts one render-frame upload ledger. Unlike the previous fixed 16 ms
     * window, a 120/144 Hz client receives a small independent slice every frame.
     */
    public synchronized void beginFrame(boolean focused) {
        long now = System.nanoTime();
        explicitFrame = true;
        windowStartNanos = now;
        reservedNanos = 0L;
        minimapReservedNanos = 0L;
        reservedBytes = 0L;
        minimapReservedBytes = 0L;
        boolean pressure = MapPerformanceGovernor.getInstance().underPressure();
        long configured = MapPerformanceGovernor.getInstance()
                .textureUploadBudgetNanos(focused);
        long fixedMaximum = focused ? FOCUSED_BUDGET_NANOS
                : pressure ? PRESSURE_BUDGET_NANOS : NORMAL_BUDGET_NANOS;
        frameBudgetNanos = Math.max(250_000L, Math.min(fixedMaximum, configured));
        long fixedBytes = focused ? FOCUSED_BYTE_BUDGET
                : pressure ? PRESSURE_BYTE_BUDGET : NORMAL_BYTE_BUDGET;
        double ratio = frameBudgetNanos / (double) Math.max(1L, fixedMaximum);
        frameByteBudget = Math.max(256L << 10,
                Math.min(fixedBytes, (long) (fixedBytes * Math.max(0.25D, ratio))));
    }

    public synchronized boolean tryReserve(UploadKind kind,
            MapRequestLane lane, boolean focused) {
        UploadKind effective = kind == null ? UploadKind.BRANCH : kind;
        return tryReserve(effective, lane, focused, effective.estimatedBytes);
    }

    /** Reserves predicted time and bytes for one upload. */
    public synchronized boolean tryReserve(UploadKind kind,
            MapRequestLane lane, boolean focused, long bytes) {
        rotateWindow(System.nanoTime());
        UploadKind effectiveKind = kind == null ? UploadKind.BRANCH : kind;
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        long prediction = Math.max(80_000L,
                ewmaNanos.getOrDefault(effectiveKind, effectiveKind.initialNanos));
        long predictedBytes = Math.max(0L, bytes);
        boolean pressure = MapPerformanceGovernor.getInstance().underPressure();
        long fallbackBudget = focused ? FOCUSED_BUDGET_NANOS
                : pressure ? PRESSURE_BUDGET_NANOS : NORMAL_BUDGET_NANOS;
        long fallbackBytes = focused ? FOCUSED_BYTE_BUDGET
                : pressure ? PRESSURE_BYTE_BUDGET : NORMAL_BYTE_BUDGET;
        long budget = explicitFrame ? frameBudgetNanos : fallbackBudget;
        long byteBudget = explicitFrame ? frameByteBudget : fallbackBytes;

        if (effectiveLane == MapRequestLane.MINIMAP) {
            long availableNanos = budget + Math.max(0L,
                    MINIMAP_RESERVE_NANOS - minimapReservedNanos);
            long availableBytes = byteBudget + Math.max(0L,
                    MINIMAP_RESERVE_BYTES - minimapReservedBytes);
            if (reservedNanos + prediction > availableNanos
                    || reservedBytes + predictedBytes > availableBytes) return false;
            reservedNanos += prediction;
            minimapReservedNanos += prediction;
            reservedBytes += predictedBytes;
            minimapReservedBytes += predictedBytes;
            return true;
        }

        long protectedNanos = Math.max(0L,
                MINIMAP_RESERVE_NANOS - minimapReservedNanos);
        long protectedBytes = Math.max(0L,
                MINIMAP_RESERVE_BYTES - minimapReservedBytes);
        boolean weak = effectiveKind == UploadKind.BRANCH
                || effectiveKind == UploadKind.LEGACY
                || effectiveLane == MapRequestLane.BACKGROUND
                || effectiveLane == MapRequestLane.PREFETCH;
        long usableNanos = weak ? Math.max(0L, budget - protectedNanos) : budget;
        long usableBytes = weak ? Math.max(0L, byteBudget - protectedBytes) : byteBudget;
        if (reservedNanos + prediction > usableNanos
                || reservedBytes + predictedBytes > usableBytes) return false;
        reservedNanos += prediction;
        reservedBytes += predictedBytes;
        return true;
    }

    /** Records an upload that was not pre-reserved. */
    public synchronized void consumeActual(UploadKind kind,
            MapRequestLane lane, long nanos) {
        UploadKind effective = kind == null ? UploadKind.BRANCH : kind;
        consumeActual(effective, lane, nanos, effective.estimatedBytes);
    }

    public synchronized void consumeActual(UploadKind kind,
            MapRequestLane lane, long nanos, long bytes) {
        rotateWindow(System.nanoTime());
        reservedNanos += Math.max(0L, nanos);
        reservedBytes += Math.max(0L, bytes);
        if (lane == MapRequestLane.MINIMAP) {
            minimapReservedNanos += Math.max(0L, nanos);
            minimapReservedBytes += Math.max(0L, bytes);
        }
        record(kind, nanos);
    }

    /** Updates the driver-specific EWMA after a completed upload. */
    public synchronized void record(UploadKind kind, long nanos) {
        if (kind == null || nanos <= 0L) return;
        long clamped = Math.max(20_000L, Math.min(20_000_000L, nanos));
        long previous = ewmaNanos.getOrDefault(kind, kind.initialNanos);
        long next = previous + ((clamped - previous) >> 3);
        ewmaNanos.put(kind, Math.max(20_000L, next));
    }

    public synchronized long predictedNanos(UploadKind kind) {
        UploadKind effective = kind == null ? UploadKind.BRANCH : kind;
        return ewmaNanos.getOrDefault(effective, effective.initialNanos);
    }

    public synchronized Snapshot snapshot() {
        rotateWindow(System.nanoTime());
        return new Snapshot(reservedNanos, minimapReservedNanos,
                reservedBytes, minimapReservedBytes,
                frameBudgetNanos, frameByteBudget,
                predictedNanos(UploadKind.SURFACE_EXACT),
                predictedNanos(UploadKind.CAVE_EXACT),
                predictedNanos(UploadKind.BRANCH),
                predictedNanos(UploadKind.LEGACY));
    }

    private void rotateWindow(long now) {
        if (windowStartNanos == 0L
                || now - windowStartNanos >= FALLBACK_WINDOW_NANOS) {
            windowStartNanos = now;
            explicitFrame = false;
            frameBudgetNanos = NORMAL_BUDGET_NANOS;
            frameByteBudget = NORMAL_BYTE_BUDGET;
            reservedNanos = 0L;
            minimapReservedNanos = 0L;
            reservedBytes = 0L;
            minimapReservedBytes = 0L;
        }
    }

    public record Snapshot(long reservedNanos, long minimapReservedNanos,
            long reservedBytes, long minimapReservedBytes,
            long frameBudgetNanos, long frameByteBudget,
            long surfaceExactPredictionNanos, long caveExactPredictionNanos,
            long branchPredictionNanos, long legacyPredictionNanos) {
    }
}
