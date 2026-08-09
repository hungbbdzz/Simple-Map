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
    /** Extra bounded ledger used only by a CPU-complete fullscreen cave row. */
    private static final long FULLSCREEN_CAVE_WAVEFRONT_EXTRA_NANOS = 3_000_000L;
    private static final long FULLSCREEN_CAVE_WAVEFRONT_EXTRA_BYTES = 2L << 20;
    /**
     * One exact upload is an atomic operation: it cannot be split merely because
     * the adaptive frame slice became a few microseconds smaller than the current
     * EWMA prediction. Without this escape hatch a cold prediction can be larger
     * than every future frame budget, so no first upload ever runs and the EWMA can
     * never converge.
     */
    private static final long ATOMIC_FOREGROUND_SOFT_MIN_NANOS = 850_000L;
    private static final long ATOMIC_FOREGROUND_SOFT_EXTRA_NANOS = 500_000L;
    private static final long ATOMIC_FOREGROUND_SOFT_MAX_NANOS = 1_500_000L;
    private static final long ATOMIC_FOREGROUND_HARD_CAP_NANOS = 6_000_000L;
    private static final long ATOMIC_FOREGROUND_MIN_INTERVAL_NANOS = 50_000_000L;
    private static final long ATOMIC_FOREGROUND_PRESSURE_INTERVAL_NANOS = 100_000_000L;
    private static final long ATOMIC_FOREGROUND_BYTE_CAP = 1L << 20;
    /**
     * Branch publication receives one small bootstrap admission before exact work.
     * On high-refresh clients the adaptive frame budget can be smaller than the
     * protected minimap reserve, which previously left BRANCH with zero usable time
     * forever. This paced escape hatch is deliberately narrower than exact upload
     * admission and is limited to one fullscreen branch every few frames.
     */
    private static final long BRANCH_BOOTSTRAP_HARD_CAP_NANOS = 4_000_000L;
    private static final long BRANCH_BOOTSTRAP_BYTE_CAP = 256L << 10;
    private static final long BRANCH_BOOTSTRAP_MIN_INTERVAL_NANOS = 24_000_000L;
    private static final long BRANCH_BOOTSTRAP_PRESSURE_INTERVAL_NANOS = 48_000_000L;

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
    private final EnumMap<UploadKind, Long> deniedReservations =
            new EnumMap<>(UploadKind.class);
    private long windowStartNanos;
    private long frameBudgetNanos = NORMAL_BUDGET_NANOS;
    private long frameByteBudget = NORMAL_BYTE_BUDGET;
    private boolean explicitFrame;
    private long reservedNanos;
    private long minimapReservedNanos;
    private long reservedBytes;
    private long minimapReservedBytes;
    private long oversizedForegroundAdmissions;
    private long lastOversizedForegroundAdmissionNanos;
    private long branchBootstrapAdmissions;
    private long lastBranchBootstrapAdmissionNanos;
    private long fullscreenCaveWavefrontExtraNanos;
    private long fullscreenCaveWavefrontExtraBytes;
    private long fullscreenCaveWavefrontAdmissions;
    /** One primary-branch escape hatch is available in every explicit render frame. */
    private boolean branchBootstrapUsedThisFrame;

    private MapGpuBudgetController() {
        for (UploadKind kind : UploadKind.values()) {
            ewmaNanos.put(kind, kind.initialNanos);
            deniedReservations.put(kind, 0L);
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
        branchBootstrapUsedThisFrame = false;
        fullscreenCaveWavefrontExtraNanos = 0L;
        fullscreenCaveWavefrontExtraBytes = 0L;
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
                    || reservedBytes + predictedBytes > availableBytes) {
                if (tryAdmitAtomicForeground(effectiveKind, effectiveLane,
                        prediction, predictedBytes, availableNanos, availableBytes,
                        System.nanoTime(), pressure)) return true;
                recordDenied(effectiveKind);
                return false;
            }
            reserve(prediction, predictedBytes, true);
            return true;
        }

        long protectedNanos = Math.max(0L,
                MINIMAP_RESERVE_NANOS - minimapReservedNanos);
        long protectedBytes = Math.max(0L,
                MINIMAP_RESERVE_BYTES - minimapReservedBytes);
        /*
         * A FULLSCREEN branch is the primary representation at far zoom, not weak
         * maintenance work. Reserving the minimap slice against it forced one tiny
         * bootstrap admission and produced tens of thousands of denials while the
         * actual branch upload cost stayed around a few hundred microseconds. Xaero
         * advances the visible region texture before leaf refinements; give visible
         * branches the normal foreground ledger and keep only background/prefetch
         * branches weak.
         */
        boolean weak = effectiveKind == UploadKind.LEGACY
                || effectiveLane == MapRequestLane.BACKGROUND
                || effectiveLane == MapRequestLane.PREFETCH
                || (effectiveKind == UploadKind.BRANCH
                        && effectiveLane != MapRequestLane.FULLSCREEN
                        && effectiveLane != MapRequestLane.MINIMAP);
        long usableNanos = weak ? Math.max(0L, budget - protectedNanos) : budget;
        long usableBytes = weak ? Math.max(0L, byteBudget - protectedBytes) : byteBudget;
        if (reservedNanos + prediction > usableNanos
                || reservedBytes + predictedBytes > usableBytes) {
            long now = System.nanoTime();
            if (tryAdmitFullscreenCaveWavefront(effectiveKind, effectiveLane,
                    prediction, predictedBytes, usableNanos, usableBytes,
                    pressure)) return true;
            if (tryAdmitBranchBootstrap(effectiveKind, effectiveLane,
                    prediction, predictedBytes, now, pressure)) return true;
            if (tryAdmitAtomicForeground(effectiveKind, effectiveLane,
                    prediction, predictedBytes, usableNanos, usableBytes,
                    now, pressure)) return true;
            recordDenied(effectiveKind);
            return false;
        }
        reserve(prediction, predictedBytes, false);
        return true;
    }


    private void reserve(long nanos, long bytes, boolean minimap) {
        reservedNanos += Math.max(0L, nanos);
        reservedBytes += Math.max(0L, bytes);
        if (minimap) {
            minimapReservedNanos += Math.max(0L, nanos);
            minimapReservedBytes += Math.max(0L, bytes);
        }
    }


    /**
     * Fullscreen cave rows are already CPU-complete and ordered before they reach
     * this ledger. Give that wavefront a small extra render-frame allowance instead
     * of denying the same immutable page for hundreds of frames. The allowance is
     * disabled under pressure and remains below one normal 60 Hz frame half-slice.
     */
    private boolean tryAdmitFullscreenCaveWavefront(UploadKind kind,
            MapRequestLane lane, long prediction, long bytes,
            long budget, long byteBudget, boolean pressure) {
        if (pressure || kind != UploadKind.CAVE_EXACT
                || lane != MapRequestLane.FULLSCREEN || !explicitFrame) {
            return false;
        }
        long projectedNanos = reservedNanos + prediction;
        long projectedBytes = reservedBytes + bytes;
        long extraNanos = Math.max(0L, projectedNanos - Math.max(0L, budget));
        long extraBytes = Math.max(0L, projectedBytes - Math.max(0L, byteBudget));
        if (extraNanos > FULLSCREEN_CAVE_WAVEFRONT_EXTRA_NANOS
                || extraBytes > FULLSCREEN_CAVE_WAVEFRONT_EXTRA_BYTES) {
            return false;
        }
        long newlyConsumedNanos = Math.max(0L,
                extraNanos - fullscreenCaveWavefrontExtraNanos);
        long newlyConsumedBytes = Math.max(0L,
                extraBytes - fullscreenCaveWavefrontExtraBytes);
        if (fullscreenCaveWavefrontExtraNanos + newlyConsumedNanos
                        > FULLSCREEN_CAVE_WAVEFRONT_EXTRA_NANOS
                || fullscreenCaveWavefrontExtraBytes + newlyConsumedBytes
                        > FULLSCREEN_CAVE_WAVEFRONT_EXTRA_BYTES) {
            return false;
        }
        reserve(prediction, bytes, false);
        fullscreenCaveWavefrontExtraNanos = extraNanos;
        fullscreenCaveWavefrontExtraBytes = extraBytes;
        fullscreenCaveWavefrontAdmissions++;
        return true;
    }

    private boolean tryAdmitBranchBootstrap(UploadKind kind,
            MapRequestLane lane, long prediction, long bytes,
            long now, boolean pressure) {
        if (kind != UploadKind.BRANCH || lane != MapRequestLane.FULLSCREEN) {
            return false;
        }
        if (reservedNanos != 0L || reservedBytes != 0L) return false;
        if (prediction > BRANCH_BOOTSTRAP_HARD_CAP_NANOS
                || bytes > BRANCH_BOOTSTRAP_BYTE_CAP) return false;
        if (explicitFrame) {
            // Branches are the primary far-zoom representation. Guarantee one
            // small branch admission before exact work in every render frame;
            // otherwise the protected minimap reserve can starve L1 indefinitely.
            if (branchBootstrapUsedThisFrame) return false;
            branchBootstrapUsedThisFrame = true;
        } else {
            long interval = pressure ? BRANCH_BOOTSTRAP_PRESSURE_INTERVAL_NANOS
                    : BRANCH_BOOTSTRAP_MIN_INTERVAL_NANOS;
            if (now - lastBranchBootstrapAdmissionNanos < interval) return false;
        }
        reserve(prediction, bytes, false);
        branchBootstrapAdmissions++;
        lastBranchBootstrapAdmissionNanos = now;
        return true;
    }

    private boolean tryAdmitAtomicForeground(UploadKind kind,
            MapRequestLane lane, long prediction, long bytes,
            long budget, long byteBudget, long now, boolean pressure) {
        if (reservedNanos != 0L || reservedBytes != 0L) return false;
        if (lane != MapRequestLane.MINIMAP && lane != MapRequestLane.FULLSCREEN) {
            return false;
        }
        if (kind != UploadKind.SURFACE_EXACT && kind != UploadKind.CAVE_EXACT) {
            return false;
        }

        long timeSoftCap = Math.max(ATOMIC_FOREGROUND_SOFT_MIN_NANOS,
                Math.min(ATOMIC_FOREGROUND_SOFT_MAX_NANOS,
                        Math.max(1L, budget) + ATOMIC_FOREGROUND_SOFT_EXTRA_NANOS));
        long byteSoftCap = Math.max(ATOMIC_FOREGROUND_BYTE_CAP,
                Math.max(1L, byteBudget) * 2L);
        if (bytes > byteSoftCap || prediction > ATOMIC_FOREGROUND_HARD_CAP_NANOS) {
            return false;
        }

        long interval = pressure ? ATOMIC_FOREGROUND_PRESSURE_INTERVAL_NANOS
                : ATOMIC_FOREGROUND_MIN_INTERVAL_NANOS;
        boolean smallAtomicOverrun = prediction <= timeSoftCap;
        boolean pacedLargeAtomicOverrun = now - lastOversizedForegroundAdmissionNanos
                >= interval;
        if (!smallAtomicOverrun && !pacedLargeAtomicOverrun) return false;

        reserve(prediction, bytes, lane == MapRequestLane.MINIMAP);
        oversizedForegroundAdmissions++;
        lastOversizedForegroundAdmissionNanos = now;
        return true;
    }

    private void recordDenied(UploadKind kind) {
        deniedReservations.merge(kind, 1L, Long::sum);
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
                predictedNanos(UploadKind.LEGACY),
                deniedReservations.getOrDefault(UploadKind.SURFACE_EXACT, 0L),
                deniedReservations.getOrDefault(UploadKind.CAVE_EXACT, 0L),
                deniedReservations.getOrDefault(UploadKind.BRANCH, 0L),
                deniedReservations.getOrDefault(UploadKind.LEGACY, 0L),
                oversizedForegroundAdmissions, branchBootstrapAdmissions);
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
            branchBootstrapUsedThisFrame = false;
            fullscreenCaveWavefrontExtraNanos = 0L;
            fullscreenCaveWavefrontExtraBytes = 0L;
        }
    }

    public record Snapshot(long reservedNanos, long minimapReservedNanos,
            long reservedBytes, long minimapReservedBytes,
            long frameBudgetNanos, long frameByteBudget,
            long surfaceExactPredictionNanos, long caveExactPredictionNanos,
            long branchPredictionNanos, long legacyPredictionNanos,
            long surfaceExactDeniedReservations, long caveExactDeniedReservations,
            long branchDeniedReservations, long legacyDeniedReservations,
            long oversizedForegroundAdmissions, long branchBootstrapAdmissions) {
    }
}
