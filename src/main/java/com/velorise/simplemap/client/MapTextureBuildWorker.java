package com.velorise.simplemap.client;

import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/**
 * CPU-only texture preparation. Jobs receive immutable primitive snapshots and
 * never touch ClientLevel, NativeImage or OpenGL, so they are safe to run away
 * from the client/render thread.
 */
final class MapTextureBuildWorker {
    private static final int SIZE = 512;
    private static final int PIXELS = SIZE * SIZE;
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(
            4, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), runnable -> {
                Thread thread = new Thread(runnable, "SimpleMap-TextureCPU");
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private MapTextureBuildWorker() {
    }

    /**
     * Build map texture and glow texture on a background thread.
     * Uses SurfaceColorizer to dynamically colorize raw MapBlockData pixels.
     */
    static CompletableFuture<PreparedPair> tryBuildSurface(
            long[] pixels,
            int[] tints,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks,
            int colourMode,
            boolean showFlowers,
            int terrainSlopes,
            byte[] light,
            int profile,
            long revision) {
        return tryBuildSurface(pixels, tints, biomePalette, blockPalette, biomeLookup, blockColors,
                tintPolicies, tintDisabledBlocks, colourMode, showFlowers, terrainSlopes, light, profile, revision, () -> true);
    }

    static CompletableFuture<PreparedPair> tryBuildSurface(
            long[] pixels,
            int[] tints,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks,
            int colourMode,
            boolean showFlowers,
            int terrainSlopes,
            byte[] light,
            int profile,
            long revision,
            BooleanSupplier stillValid) {
        CompletableFuture<PreparedPair> future = new CompletableFuture<>();
        try {
            WORKERS.execute(() -> {
                try {
                    // 1. Colorize pixels from raw block/biome data
                    BooleanSupplier valid = () -> !future.isCancelled() && stillValid.getAsBoolean();
                    if (!valid.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                    int[] styled = SurfaceColorizer.colorize(
                            pixels, tints, biomePalette, blockPalette, biomeLookup,
                            blockColors, tintPolicies, tintDisabledBlocks, colourMode,
                            showFlowers, terrainSlopes, profile, valid);

                    // 2. Generate the glow layer. The old implementation walked a
                    // full 3x3 neighbourhood for every pixel. The same [1 2 1] x
                    // [1 2 1] kernel is now evaluated as two separable passes.
                    byte[] smoothed = buildSmoothedLight(light, valid);
                    int[] glow = new int[PIXELS];
                    for (int z = 0; z < SIZE; z++) {
                        if ((z & 31) == 0 && !valid.getAsBoolean()) {
                            throw new java.util.concurrent.CancellationException();
                        }
                        for (int x = 0; x < SIZE; x++) {
                            int index = z * SIZE + x;
                            int color = styled[index];
                            int level = smoothed == null ? 0 : smoothed[index] & 0xFF;
                            int alpha = color == 0 || level == 0 ? 0
                                    : Math.min(255, Math.round(
                                            (float) Math.pow(level / 15.0f, 1.65f) * 255.0f));
                            int warm = tintTowardWarmLight(color, level);
                            glow[index] = (warm & 0x00FFFFFF) | (alpha << 24);
                        }
                    }
                    future.complete(new PreparedPair(styled, glow, revision));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        } catch (RejectedExecutionException rejected) {
            return null;
        }
    }


    static CompletableFuture<PreparedSingle> tryBuildCave(
            int[] source, short[] heights, int terrainSlopes, int profile,
            long revision, BooleanSupplier stillValid) {
        CompletableFuture<PreparedSingle> future = new CompletableFuture<>();
        try {
            WORKERS.execute(() -> {
                try {
                    BooleanSupplier valid = () -> !future.isCancelled() && stillValid.getAsBoolean();
                    int[] styled = CaveReliefColorizer.colorize(
                            source, heights, terrainSlopes, profile, valid);
                    future.complete(new PreparedSingle(styled, revision));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        } catch (RejectedExecutionException rejected) {
            return null;
        }
    }

    static CompletableFuture<PreparedSingle> tryBuildSingle(
            int[] source, int profile, long revision) {
        return tryBuildSingle(source, profile, revision, () -> true);
    }

    static CompletableFuture<PreparedSingle> tryBuildSingle(
            int[] source, int profile, long revision, BooleanSupplier stillValid) {
        CompletableFuture<PreparedSingle> future = new CompletableFuture<>();
        try {
            WORKERS.execute(() -> {
                try {
                    int[] styled = new int[PIXELS];
                    for (int i = 0; i < styled.length; i++) {
                        if ((i & 8191) == 0 && (future.isCancelled() || !stillValid.getAsBoolean())) {
                            throw new java.util.concurrent.CancellationException();
                        }
                        styled[i] = MapColorProfile.apply(source[i], profile);
                    }
                    future.complete(new PreparedSingle(styled, revision));
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
            return future;
        } catch (RejectedExecutionException rejected) {
            return null;
        }
    }

    private static byte[] buildSmoothedLight(byte[] levels, BooleanSupplier valid) {
        if (levels == null) return null;
        int[] horizontal = new int[PIXELS];
        byte[] result = new byte[PIXELS];
        for (int z = 0; z < SIZE; z++) {
            if ((z & 31) == 0 && !valid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            int row = z * SIZE;
            for (int x = 0; x < SIZE; x++) {
                int sum = (levels[row + x] & 0xFF) * 2;
                if (x > 0) sum += levels[row + x - 1] & 0xFF;
                if (x + 1 < SIZE) sum += levels[row + x + 1] & 0xFF;
                horizontal[row + x] = sum;
            }
        }
        for (int z = 0; z < SIZE; z++) {
            if ((z & 31) == 0 && !valid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            int verticalWeight = (z > 0 ? 1 : 0) + 2 + (z + 1 < SIZE ? 1 : 0);
            int row = z * SIZE;
            for (int x = 0; x < SIZE; x++) {
                int sum = horizontal[row + x] * 2;
                if (z > 0) sum += horizontal[row - SIZE + x];
                if (z + 1 < SIZE) sum += horizontal[row + SIZE + x];
                int horizontalWeight = (x > 0 ? 1 : 0) + 2 + (x + 1 < SIZE ? 1 : 0);
                int filtered = Math.round((float) sum / (horizontalWeight * verticalWeight));
                int original = levels[row + x] & 0xFF;
                result[row + x] = (byte) Math.max(original, filtered);
            }
        }
        return result;
    }

    private static int tintTowardWarmLight(int abgr, int light) {
        if (abgr == 0 || light <= 6) return abgr;
        float strength = Math.min(0.60f, ((light - 6) / 9.0f) * 0.60f);
        int red = abgr & 0xFF;
        int green = (abgr >>> 8) & 0xFF;
        int blue = (abgr >>> 16) & 0xFF;
        red = Math.round(red + (255 - red) * strength);
        green = Math.round(green + (190 - green) * strength);
        blue = Math.round(blue + (88 - blue) * strength);
        return (abgr & 0xFF000000) | (blue << 16) | (green << 8) | red;
    }

    record PreparedPair(int[] styled, int[] glow, long revision) {
    }

    record PreparedSingle(int[] styled, long revision) {
    }
}
