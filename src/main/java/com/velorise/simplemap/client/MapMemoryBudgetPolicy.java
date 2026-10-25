package com.velorise.simplemap.client;
import com.velorise.simplemap.client.MapViewportCoordinator.MapLodPolicy;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central memory policy for GPU atlas dimensions and resident map content.
 *
 * <p>OpenGL atlases reserve their complete storage when first created, so slot
 * eviction alone cannot lower the fixed VRAM baseline. The atlas profile is
 * therefore selected conservatively before allocation, while a runtime VRAM
 * probe may further reduce the amount of resident page/branch content and legacy
 * fallback data kept warm.</p>
 *
 * <p>The profile can be overridden with {@code -Dsimplemap.gpuBudgetMiB=N}.
 * Supported values are clamped to 64..384 MiB.</p>
 */
public final class MapMemoryBudgetPolicy {
    private static final int MIN_BUDGET_MIB = 64;
    private static final int MAX_BUDGET_MIB = 384;
    private static final int CONFIGURED_BUDGET_MIB = configuredBudgetMiB();
    private static final Profile PROFILE = Profile.forBudget(CONFIGURED_BUDGET_MIB);

    // GL_NVX_gpu_memory_info constants. Kept local so the source remains
    // compatible with LWJGL builds that do not expose the extension constants.
    private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;

    private static volatile long detectedAvailableVramBytes = -1L;
    private static volatile long runtimeResidentBudgetBytes =
            residentBudgetForAvailableBytes(-1L);
    private static volatile long lastProbeNanos;

    private MapMemoryBudgetPolicy() {
    }

    public static int configuredGpuBudgetMiB() {
        return CONFIGURED_BUDGET_MIB;
    }

    public static int surfaceLeafColumns() {
        return PROFILE.surfaceLeafColumns;
    }

    public static int caveExactColumns() {
        return PROFILE.caveExactColumns;
    }

    public static int branchLowColumns() {
        return PROFILE.branchLowColumns;
    }

    public static int branchHighColumns() {
        return PROFILE.branchHighColumns;
    }

    /**
     * Cave L1-L3 are the display levels used by fullscreen zoom.
     * They need enough simultaneous slots for a full 16:9 viewport at the
     * density-correct level; otherwise the renderer is forced to a blurrier
     * parent solely because the atlas is small.  Exact-cave capacity is a good
     * proxy for the selected VRAM profile, capped so high-memory profiles do not
     * reserve several oversized atlases unnecessarily.
     */
    public static int caveBranchColumns(int level) {
        if (level >= 1 && level <= 3) {
            return Math.min(32, Math.max(branchLowColumns(), caveExactColumns()));
        }
        return branchHighColumns();
    }

    public static int legacyRegionLimit() {
        return PROFILE.legacyRegionLimit;
    }

    public static int overviewTextureLimit() {
        return PROFILE.overviewTextureLimit;
    }

    /** Active page/branch payload ceiling, independent of fixed atlas storage. */
    public static long residentContentBudgetBytes() {
        return runtimeResidentBudgetBytes;
    }

    /** Completed CPU upload payloads must not accumulate beyond this limit. */
    public static long pendingUploadBudgetBytes() {
        return PROFILE.pendingUploadBudgetMiB << 20;
    }

    private static final Map<String, Long> ATLAS_ALLOCATIONS = new ConcurrentHashMap<>();

    public static void registerAtlasAllocation(String key, long bytes) {
        if (key == null || key.isBlank()) return;
        ATLAS_ALLOCATIONS.put(key, Math.max(0L, bytes));
        refreshRuntimeVramBudget();
    }

    public static void removeAtlasAllocation(String key) {
        if (key != null) ATLAS_ALLOCATIONS.remove(key);
    }

    public static AtlasSnapshot atlasSnapshot() {
        long bytes = 0L;
        for (long value : ATLAS_ALLOCATIONS.values()) bytes += Math.max(0L, value);
        return new AtlasSnapshot(ATLAS_ALLOCATIONS.size(), bytes,
                plannedAtlasBytes(),
                residentContentBudgetBytes(),
                detectedAvailableVramBytes());
    }

    public record AtlasSnapshot(int allocationCount, long allocatedBytes,
            long plannedAtlasBytes, long residentContentBudgetBytes,
            long detectedAvailableVramBytes) {
    }

    public static long detectedAvailableVramBytes() {
        return detectedAvailableVramBytes;
    }

    /**
     * Queries NVIDIA's available dedicated VRAM when supported. The probe is
     * intentionally best-effort; unsupported drivers keep the conservative
     * configured fallback. Must be called on the render thread.
     */
    public static void refreshRuntimeVramBudget() {
        if (!RenderSystem.isOnRenderThreadOrInit()) return;
        long now = System.nanoTime();
        if (now - lastProbeNanos < 5_000_000_000L) return;
        lastProbeNanos = now;
        long available = queryAvailableVramBytes();
        if (available <= 0L) return;
        detectedAvailableVramBytes = available;
        runtimeResidentBudgetBytes = residentBudgetForAvailableBytes(available);
    }

    /** Estimated fixed RGBA8 atlas storage for the selected profile. */
    public static long plannedAtlasBytes() {
        long surfaceSide = 66L * surfaceLeafColumns();
        long surfaceExact = surfaceSide * surfaceSide * 4L * 2L;

        int caveColumns = caveExactColumns();
        long caveExact = 0L;
        for (int size : new int[] { 64, 32, 16, 8 }) {
            long side = (long) (size + 2) * caveColumns;
            caveExact += side * side * 4L;
        }

        long branches = 0L;
        for (int level = 1; level <= MapLodPolicy.MAX_BRANCH_LEVEL; level++) {
            int surfaceColumns = level <= 2
                    ? branchLowColumns() : branchHighColumns();
            int branchCaveColumns = caveBranchColumns(level);
            long branchSurfaceSide = 66L * surfaceColumns;
            long caveSide = 66L * branchCaveColumns;
            branches += branchSurfaceSide * branchSurfaceSide * 4L;
            branches += caveSide * caveSide * 4L;
        }
        return surfaceExact + caveExact + branches;
    }

    private static int configuredBudgetMiB() {
        Integer requested = Integer.getInteger("simplemap.gpuBudgetMiB");
        if (requested != null && requested > 0) {
            return clamp(requested, MIN_BUDGET_MIB, MAX_BUDGET_MIB);
        }
        long maxHeap = Runtime.getRuntime().maxMemory();
        if (maxHeap <= (3L << 30)) return 72;
        // Balanced is the safe default even on large heaps: Java heap size is not
        // a reliable proxy for dedicated VRAM. High atlas capacity is opt-in via
        // -Dsimplemap.gpuBudgetMiB=128 (or higher).
        return 96;
    }

    private static long residentBudgetForAvailableBytes(long availableBytes) {
        long configured = (long) CONFIGURED_BUDGET_MIB << 20;
        long fixed = plannedAtlasBytes();
        long desired = Math.max(32L << 20, configured - Math.min(configured / 2L, fixed / 3L));
        if (availableBytes <= 0L) return desired;
        // Never reserve more than one quarter of currently available dedicated
        // VRAM for warm map content. Fixed atlas storage is accounted separately.
        long availableShare = Math.max(24L << 20, availableBytes / 4L);
        return Math.max(24L << 20, Math.min(desired, availableShare));
    }

    private static long queryAvailableVramBytes() {
        try {
            Object capabilities = GL.getCapabilities();
            Field field = capabilities.getClass().getField("GL_NVX_gpu_memory_info");
            if (!field.getBoolean(capabilities)) return -1L;
            int availableKiB = GL11.glGetInteger(
                    GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);
            return availableKiB > 0 ? availableKiB * 1024L : -1L;
        } catch (Throwable unsupported) {
            return -1L;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record Profile(int surfaceLeafColumns, int caveExactColumns,
            int branchLowColumns, int branchHighColumns,
            int legacyRegionLimit, int overviewTextureLimit,
            long pendingUploadBudgetMiB) {
        private static Profile forBudget(int budgetMiB) {
            if (budgetMiB <= 72) {
                return new Profile(24, 24, 14, 10, 6, 48, 12L);
            }
            if (budgetMiB <= 112) {
                return new Profile(32, 36, 16, 12, 12, 96, 20L);
            }
            return new Profile(36, 48, 24, 16, 24, 160, 32L);
        }
    }


public static final class MapGpuBudgetController {
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
         * actual branch upload cost stayed around a few hundred microseconds. The engine
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

/**
 * Global residency authority shared by surface exact, cave exact, branch and
 * legacy textures.
 *
 * <p>V16.4 shared only an importance score while each atlas still evicted
 * locally. V16.5 adds a hard warm-content ceiling and explicit render-thread
 * eviction callbacks. Fixed atlas storage is controlled by
 * {@link MapMemoryBudgetPolicy}; this manager controls which page/branch payloads
 * remain resident inside that storage and which CPU-side copies stay attached to
 * active GPU entries.</p>
 */
public static final class MapResidencyManager {
    private static final MapResidencyManager INSTANCE = new MapResidencyManager();
    private static final ThreadLocal<MapRequestLane> RENDER_LANE = new ThreadLocal<>();
    private static final long MINIMAP_PIN_NANOS = 4_000_000_000L;
    private static final long FULLSCREEN_PIN_NANOS = 3_000_000_000L;

    public enum Kind {
        SURFACE_EXACT(5),
        CAVE_EXACT(5),
        SURFACE_BRANCH(3),
        CAVE_BRANCH(3),
        SURFACE_LEGACY(1),
        CAVE_LEGACY(1),
        LEGACY(1);

        private final int baseRank;

        Kind(int baseRank) {
            this.baseRank = baseRank;
        }
    }

    @FunctionalInterface
    public interface EvictionHandler {
        /** Returns true only when the resident object was actually retired. */
        boolean evict();
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong globalEvictions = new AtomicLong();
    private final AtomicLong budgetFailures = new AtomicLong();
    /** O(1) resident byte accounting; never rescan the whole residency table in hot paths. */
    private final AtomicLong residentBytes = new AtomicLong();
    /** Changes only when existing atlas UVs can become unsafe. */
    private final AtomicLong topologyRevision = new AtomicLong();
    /** Changes whenever visible resident coverage is added, updated or removed. */
    private final AtomicLong contentRevision = new AtomicLong();
    /** Family-local coverage revisions prevent unrelated surface/cave churn rebuilding plans. */
    private final AtomicLong surfaceContentRevision = new AtomicLong();
    private final AtomicLong caveContentRevision = new AtomicLong();
    /**
     * GPU pixel publication revisions are intentionally separate from coverage.
     * A page can upload changed texels into an already-resident atlas slot without
     * altering render-plan geometry. Retained composition targets still need to
     * know that their copied pixels are stale, so every exact atlas write advances
     * the corresponding family revision.
     */
    private final AtomicLong surfacePixelRevision = new AtomicLong();
    private final AtomicLong cavePixelRevision = new AtomicLong();

    private MapResidencyManager() {
    }

    public static MapResidencyManager getInstance() {
        return INSTANCE;
    }

    public static void beginRender(MapRequestLane lane) {
        RENDER_LANE.set(lane == null ? MapRequestLane.FULLSCREEN : lane);
    }

    public static void endRender() {
        RENDER_LANE.remove();
    }

    public static MapRequestLane currentRenderLane() {
        MapRequestLane lane = RENDER_LANE.get();
        return lane == null ? MapRequestLane.FULLSCREEN : lane;
    }

    public void register(String key, Kind kind, long estimatedBytes) {
        register(key, kind, estimatedBytes, null);
    }

    public void register(String key, Kind kind, long estimatedBytes,
            EvictionHandler evictionHandler) {
        if (key == null) return;
        long now = System.nanoTime();
        Kind effectiveKind = kind == null ? Kind.LEGACY : kind;
        long effectiveBytes = Math.max(0L, estimatedBytes);

        while (true) {
            Entry entry = entries.get(key);
            boolean created = false;
            if (entry == null) {
                Entry candidate = new Entry();
                Entry raced = entries.putIfAbsent(key, candidate);
                entry = raced == null ? candidate : raced;
                created = raced == null;
            }

            Kind oldKind;
            long oldBytes;
            synchronized (entry) {
                // A concurrent eviction may have detached this entry after get().
                // Retry rather than updating an orphan and corrupting byte accounting.
                if (entries.get(key) != entry) continue;
                oldKind = entry.kind;
                oldBytes = entry.estimatedBytes;
                entry.kind = effectiveKind;
                entry.estimatedBytes = effectiveBytes;
                if (evictionHandler != null) entry.evictionHandler = evictionHandler;
                if (entry.createdNanos == 0L) entry.createdNanos = now;
                entry.lastResidentNanos = now;
                if (entry.lastTouchNanos == 0L) entry.lastTouchNanos = now;
            }

            long delta = effectiveBytes - oldBytes;
            if (delta != 0L) residentBytes.addAndGet(delta);
            if (created || oldKind != effectiveKind) {
                contentRevision.incrementAndGet();
                if (!created) incrementFamilyRevision(oldKind);
                incrementFamilyRevision(effectiveKind);
            }
            return;
        }
    }

    public long topologyRevision() {
        return topologyRevision.get();
    }

    public long contentRevision() {
        return contentRevision.get();
    }

    public long surfaceContentRevision() {
        return surfaceContentRevision.get();
    }

    public long caveContentRevision() {
        return caveContentRevision.get();
    }

    public long surfacePixelRevision() {
        return surfacePixelRevision.get();
    }

    public long cavePixelRevision() {
        return cavePixelRevision.get();
    }

    /** Records an atlas texel mutation without forcing render-plan reconstruction. */
    public void markPixelsChanged(Kind kind) {
        Kind effective = kind == null ? Kind.LEGACY : kind;
        switch (effective) {
            case SURFACE_EXACT, SURFACE_BRANCH, SURFACE_LEGACY ->
                    surfacePixelRevision.incrementAndGet();
            case CAVE_EXACT, CAVE_BRANCH, CAVE_LEGACY ->
                    cavePixelRevision.incrementAndGet();
            case LEGACY -> {
                surfacePixelRevision.incrementAndGet();
                cavePixelRevision.incrementAndGet();
            }
        }
    }

    /** Call when an atlas object/storage is recreated and all cached UV plans are unsafe. */
    public void markTopologyChanged() {
        topologyRevision.incrementAndGet();
        contentRevision.incrementAndGet();
        surfaceContentRevision.incrementAndGet();
        caveContentRevision.incrementAndGet();
        surfacePixelRevision.incrementAndGet();
        cavePixelRevision.incrementAndGet();
    }

    /**
     * Marks a visible coverage/mask transition without invalidating atlas UVs.
     * Pixel-only updates inside an already visible slot deliberately do not call
     * this method.
     */
    public void markCoverageChanged() {
        contentRevision.incrementAndGet();
        surfaceContentRevision.incrementAndGet();
        caveContentRevision.incrementAndGet();
    }

    public void markCoverageChanged(Kind kind) {
        contentRevision.incrementAndGet();
        incrementFamilyRevision(kind);
    }

    private void incrementFamilyRevision(Kind kind) {
        if (kind == Kind.SURFACE_EXACT || kind == Kind.SURFACE_BRANCH
                || kind == Kind.SURFACE_LEGACY) {
            surfaceContentRevision.incrementAndGet();
        } else if (kind == Kind.CAVE_EXACT || kind == Kind.CAVE_BRANCH
                || kind == Kind.CAVE_LEGACY) {
            caveContentRevision.incrementAndGet();
        } else {
            // Legacy entries can participate in either projection.
            surfaceContentRevision.incrementAndGet();
            caveContentRevision.incrementAndGet();
        }
    }

    public void touch(String key) {
        touch(key, currentRenderLane());
    }

    public void touch(String key, MapRequestLane lane) {
        if (key == null) return;
        Entry entry = entries.get(key);
        if (entry == null) return;
        long now = System.nanoTime();
        MapRequestLane effective = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        entry.lastTouchNanos = now;
        if (effective.rank() >= entry.strongestLaneRank
                || now - entry.lastLaneNanos > 3_000_000_000L) {
            entry.strongestLaneRank = effective.rank();
            entry.lastLaneNanos = now;
        }
    }

    public void remove(String key) {
        if (key == null) return;
        Entry entry = entries.get(key);
        if (entry == null) return;
        synchronized (entry) {
            if (!entries.remove(key, entry)) return;
            residentBytes.addAndGet(-Math.max(0L, entry.estimatedBytes));
        }
        contentRevision.incrementAndGet();
        incrementFamilyRevision(entry.kind);
    }

    /** Lower values are better eviction victims. */
    public long evictionScore(String key) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null) return Long.MIN_VALUE / 4;
        long now = System.nanoTime();
        long laneAge = now - entry.lastLaneNanos;
        int laneRank = laneAge > 3_000_000_000L ? 0 : entry.strongestLaneRank;
        long recentBucket = Math.max(0L,
                10_000_000_000L - Math.min(10_000_000_000L,
                        now - entry.lastTouchNanos));
        // Large weak residents are slightly preferred as victims because they
        // recover more budget per eviction without overriding lane/kind priority.
        long sizePenalty = Math.min((1L << 48) - 1L,
                Math.max(0L, entry.estimatedBytes) << 8);
        return ((long) laneRank << 56)
                + ((long) entry.kind.baseRank << 52)
                + recentBucket - sizePenalty;
    }

    public boolean isPinned(String key) {
        Entry entry = key == null ? null : entries.get(key);
        if (entry == null) return false;
        long now = System.nanoTime();
        long age = now - entry.lastLaneNanos;
        if (entry.strongestLaneRank >= MapRequestLane.MINIMAP.rank()) {
            return age <= MINIMAP_PIN_NANOS;
        }
        if (entry.strongestLaneRank >= MapRequestLane.FULLSCREEN.rank()) {
            return age <= FULLSCREEN_PIN_NANOS;
        }
        return false;
    }

    public String chooseVictim(Collection<String> candidates, String protectedKey) {
        if (candidates == null || candidates.isEmpty()) return null;
        String best = null;
        long bestScore = Long.MAX_VALUE;
        for (String candidate : candidates) {
            if (candidate == null || candidate.equals(protectedKey)
                    || isPinned(candidate)) continue;
            long score = evictionScore(candidate);
            if (best == null || score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best != null) return best;
        // Local atlas exhaustion must not deadlock publication. If every local
        // entry is pinned, pick the least-important non-protected fallback.
        for (String candidate : candidates) {
            if (candidate == null || candidate.equals(protectedKey)) continue;
            long score = evictionScore(candidate);
            if (best == null || score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Enforces the global resident-content ceiling. Call on the render thread
     * after publishing or registering a new resident. Visible minimap entries are
     * never selected; fullscreen pins are relaxed only when the hard ceiling is
     * exceeded by more than ten percent.
     */
    public boolean enforceBudget(String protectedKey, MapRequestLane requesterLane) {
        long budget = MapMemoryBudgetPolicy.residentContentBudgetBytes();
        if (budget <= 0L) return true;
        int guard = 0;
        while (residentBytes.get() > budget && guard++ < 128) {
            long current = residentBytes.get();
            boolean emergency = current > budget + budget / 10L;
            EntryCandidate victim = chooseGlobalVictim(protectedKey, emergency);
            if (victim == null) {
                budgetFailures.incrementAndGet();
                return false;
            }
            Entry entry = entries.get(victim.key);
            EvictionHandler handler = entry == null ? null : entry.evictionHandler;
            boolean evicted = false;
            try {
                evicted = handler != null && handler.evict();
            } catch (RuntimeException ignored) {
                evicted = false;
            }
            if (evicted) {
                // Handlers normally call remove() themselves. If one does not,
                // retire it here exactly once and keep byte accounting coherent.
                if (entry != null) {
                    synchronized (entry) {
                        if (entries.remove(victim.key, entry)) {
                            residentBytes.addAndGet(-Math.max(0L, entry.estimatedBytes));
                            contentRevision.incrementAndGet();
                            incrementFamilyRevision(entry.kind);
                        }
                    }
                }
                globalEvictions.incrementAndGet();
            } else if (entry != null) {
                // Do not spin forever on a manager that cannot evict during an
                // active render batch. Mark it unavailable for this enforcement.
                entry.blockedUntilNanos = System.nanoTime() + 100_000_000L;
            }
        }
        return residentBytes.get() <= budget;
    }

    private EntryCandidate chooseGlobalVictim(String protectedKey,
            boolean emergency) {
        long now = System.nanoTime();
        String bestKey = null;
        long bestScore = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            String key = mapEntry.getKey();
            Entry entry = mapEntry.getValue();
            if (key == null || key.equals(protectedKey)
                    || entry.evictionHandler == null
                    || entry.blockedUntilNanos > now) continue;
            boolean pinned = isPinned(key);
            if (pinned) {
                if (entry.strongestLaneRank >= MapRequestLane.MINIMAP.rank()) continue;
                if (!emergency) continue;
            }
            long score = evictionScore(key);
            if (bestKey == null || score < bestScore) {
                bestKey = key;
                bestScore = score;
            }
        }
        return bestKey == null ? null : new EntryCandidate(bestKey, bestScore);
    }

    private long estimatedResidentBytes() {
        return Math.max(0L, residentBytes.get());
    }

    public Snapshot snapshot() {
        int pinned = 0;
        for (Map.Entry<String, Entry> mapEntry : entries.entrySet()) {
            if (isPinned(mapEntry.getKey())) pinned++;
        }
        return new Snapshot(entries.size(), pinned, estimatedResidentBytes(),
                MapMemoryBudgetPolicy.residentContentBudgetBytes(),
                globalEvictions.get(), budgetFailures.get());
    }

    private static final class Entry {
        private volatile Kind kind = Kind.LEGACY;
        private volatile long estimatedBytes;
        private volatile long createdNanos;
        private volatile long lastResidentNanos;
        private volatile long lastTouchNanos;
        private volatile long lastLaneNanos;
        private volatile long blockedUntilNanos;
        private volatile int strongestLaneRank;
        private volatile EvictionHandler evictionHandler;
    }

    private record EntryCandidate(String key, long score) {
    }

    public record Snapshot(int residentEntries, int pinnedEntries,
            long estimatedBytes, long budgetBytes, long globalEvictions,
            long budgetFailures) {
    }
}

/**
 * Hard byte admission for map work that retains source, projection, LOD, upload
 * or IO buffers. Queue cost remains useful for scheduling pressure, but it is not
 * a memory bound. A task must own a lease before it is allowed to allocate its
 * estimated retained payload.
 */
public static final class MapMemoryLeaseManager {
    public enum Category {
        PENDING_SOURCE,
        PENDING_PROJECTION,
        PENDING_LOD,
        PENDING_UPLOAD,
        IO_BUFFER
    }

    public record CategorySnapshot(long usedBytes, long foregroundLimitBytes,
            long hardLimitBytes) { }

    public record Snapshot(EnumMap<Category, CategorySnapshot> categories,
            long deniedCount) { }

    public static final class Lease implements AutoCloseable {
        private final Category category;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(Category category, long bytes) {
            this.category = category;
            this.bytes = bytes;
        }

        public Category category() { return category; }
        public long bytes() { return bytes; }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            USED.get(category).addAndGet(-bytes);
        }
    }

    private record Budget(long foregroundLimitBytes, long hardLimitBytes) { }

    private static final EnumMap<Category, AtomicLong> USED =
            new EnumMap<>(Category.class);
    private static final EnumMap<Category, Budget> BUDGETS =
            new EnumMap<>(Category.class);
    private static final AtomicLong DENIED = new AtomicLong();

    static {
        long heap = Runtime.getRuntime().maxMemory();
        put(Category.PENDING_SOURCE,
                bounded(heap / 24L, 64L << 20, 256L << 20));
        put(Category.PENDING_PROJECTION,
                bounded(heap / 40L, 24L << 20, 160L << 20));
        put(Category.PENDING_LOD,
                bounded(heap / 96L, 12L << 20, 80L << 20));
        put(Category.PENDING_UPLOAD,
                Math.max(8L << 20, MapMemoryBudgetPolicy.pendingUploadBudgetBytes()));
        put(Category.IO_BUFFER,
                bounded(heap / 128L, 8L << 20, 64L << 20));
        for (Category category : Category.values()) {
            USED.put(category, new AtomicLong());
        }
    }

    private MapMemoryLeaseManager() { }

    /**
     * Attempts to reserve bytes. The last 15% of each category is protected for
     * minimap work so fullscreen reconstruction and Full Cave cannot starve it.
     */
    public static Lease tryAcquire(Category category, long requestedBytes,
            MapRequestLane lane) {
        if (category == null) return null;
        long bytes = Math.max(1L, requestedBytes);
        Budget budget = BUDGETS.get(category);
        long limit = lane == MapRequestLane.MINIMAP
                ? budget.hardLimitBytes : budget.foregroundLimitBytes;
        AtomicLong used = USED.get(category);
        while (true) {
            long current = used.get();
            if (bytes > limit || current > limit - bytes) {
                DENIED.incrementAndGet();
                return null;
            }
            if (used.compareAndSet(current, current + bytes)) {
                return new Lease(category, bytes);
            }
        }
    }

    public static Snapshot snapshot() {
        EnumMap<Category, CategorySnapshot> categories =
                new EnumMap<>(Category.class);
        for (Category category : Category.values()) {
            Budget budget = BUDGETS.get(category);
            categories.put(category, new CategorySnapshot(USED.get(category).get(),
                    budget.foregroundLimitBytes, budget.hardLimitBytes));
        }
        return new Snapshot(categories, DENIED.get());
    }

    private static void put(Category category, long hardLimitBytes) {
        long reserve = Math.max(1L << 20, hardLimitBytes * 15L / 100L);
        BUDGETS.put(category,
                new Budget(Math.max(1L, hardLimitBytes - reserve), hardLimitBytes));
    }

    private static long bounded(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

}
