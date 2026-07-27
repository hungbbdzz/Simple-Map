package com.velorise.simplemap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Field;

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
        long surfaceSide = 64L * surfaceLeafColumns();
        long surfaceExact = surfaceSide * surfaceSide * 4L * 2L;

        int caveColumns = caveExactColumns();
        long caveExact = 0L;
        for (int size : new int[] { 64, 32, 16, 8 }) {
            long side = (long) size * caveColumns;
            caveExact += side * side * 4L;
        }

        long branches = 0L;
        for (int level = 1; level <= MapLodPolicy.MAX_BRANCH_LEVEL; level++) {
            int columns = level <= 2 ? branchLowColumns() : branchHighColumns();
            long side = 66L * columns;
            branches += side * side * 4L * 2L; // surface + cave
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
                return new Profile(20, 24, 14, 10, 6, 48, 12L);
            }
            if (budgetMiB <= 112) {
                return new Profile(24, 32, 16, 12, 12, 96, 20L);
            }
            return new Profile(32, 48, 24, 16, 24, 160, 32L);
        }
    }
}
