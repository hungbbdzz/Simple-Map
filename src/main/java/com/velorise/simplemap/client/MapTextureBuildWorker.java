package com.velorise.simplemap.client;

import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * CPU-only texture preparation routed through the global map work planner.
 * Immutable primitive snapshots remain safe away from the client/render thread,
 * but exact, legacy and cave styling no longer own competing private executors.
 */
final class MapTextureBuildWorker {
    private static final int SIZE = 512;
    private static final int PIXELS = SIZE * SIZE;

    private MapTextureBuildWorker() {
    }

    static CompletableFuture<PreparedPair> tryBuildSurface(
            long[] pixels, int[] tints, List<String> biomePalette,
            List<String> blockPalette, IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision) {
        return tryBuildSurface(pixels, tints, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, light, profile,
                revision, () -> true);
    }

    static CompletableFuture<PreparedPair> tryBuildSurface(
            long[] pixels, int[] tints, List<String> biomePalette,
            List<String> blockPalette, IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid) {
        return submit(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 64, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            check(valid);
            int[] styled = SurfaceColorizer.colorize(
                    pixels, tints, biomePalette, blockPalette, biomeLookup,
                    blockColors, tintPolicies, tintDisabledBlocks, colourMode,
                    showFlowers, terrainSlopes, profile, valid);
            byte[] smoothed = buildSmoothedLight(light, valid);
            int[] glow = new int[PIXELS];
            for (int z = 0; z < SIZE; z++) {
                if ((z & 31) == 0) check(valid);
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
            return new PreparedPair(styled, glow, revision,
                    buildPageKnownRows(pixels));
        });
    }

    static CompletableFuture<PreparedSingle> tryBuildCave(
            int[] source, short[] heights, int terrainSlopes, int profile,
            long revision, BooleanSupplier stillValid) {
        return submit(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 48, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            return new PreparedSingle(CaveReliefColorizer.colorize(
                    source, heights, terrainSlopes, profile, valid), revision);
        });
    }

    static CompletableFuture<PreparedPair> tryBuildSurfacePage(
            long[] pixels, int[] tints, int stride, int halo,
            int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid) {
        return tryBuildSurfacePage(pixels, tints, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, light, profile,
                revision, stillValid, MapRequestLane.FULLSCREEN.executorPriority());
    }

    static CompletableFuture<PreparedPair> tryBuildSurfacePage(
            long[] pixels, int[] tints, int stride, int halo,
            int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid,
            int executorPriority) {
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(executorPriority);
        MapWorkScheduler.WorkType type = lane == MapRequestLane.MINIMAP
                ? MapWorkScheduler.WorkType.MINIMAP_EXACT
                : MapWorkScheduler.WorkType.EXACT_BUILD;
        return submit(lane, type, lane.priorityBase(), 8, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            check(valid);
            int[] pageStyled = SurfaceColorizer.colorizePageWindow(
                    pixels, tints, stride, halo, worldPageStartX, worldPageStartZ,
                    biomePalette, blockPalette, biomeLookup, blockColors,
                    tintPolicies, tintDisabledBlocks, colourMode, showFlowers,
                    terrainSlopes, profile, valid);
            byte[] pageLight = buildSmoothedLightPageWindow(light, stride, halo, valid);
            int[] pageGlow = new int[pageStyled.length];
            for (int i = 0; i < pageStyled.length; i++) {
                if ((i & 1023) == 0) check(valid);
                int color = pageStyled[i];
                int level = pageLight == null ? 0 : pageLight[i] & 0xFF;
                int alpha = color == 0 || level == 0 ? 0
                        : Math.min(255, Math.round(
                                (float) Math.pow(level / 15.0f, 1.65f) * 255.0f));
                int warm = tintTowardWarmLight(color, level);
                pageGlow[i] = (warm & 0x00FFFFFF) | (alpha << 24);
            }
            return new PreparedPair(pageStyled, pageGlow, revision,
                    new long[][] { buildKnownRowsForPageWindow(pixels, stride, halo) });
        });
    }

    static CompletableFuture<PreparedSingle> tryBuildCavePage(
            int[] source, short[] heights, int pageX, int pageZ,
            int terrainSlopes, int profile, long revision,
            BooleanSupplier stillValid) {
        return submit(MapRequestLane.FULLSCREEN,
                MapWorkScheduler.WorkType.EXACT_BUILD, 0, 24, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            int[] fullStyled = CaveReliefColorizer.colorize(
                    source, heights, terrainSlopes, profile, valid);
            int pagePixels = MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE;
            int[] pageStyled = new int[pagePixels];
            int startX = pageX * MapPageLayout.PAGE_SIZE;
            int startZ = pageZ * MapPageLayout.PAGE_SIZE;
            for (int pz = 0; pz < MapPageLayout.PAGE_SIZE; pz++) {
                if ((pz & 15) == 0) check(valid);
                int z = startZ + pz;
                System.arraycopy(fullStyled, z * SIZE + startX,
                        pageStyled, pz * MapPageLayout.PAGE_SIZE,
                        MapPageLayout.PAGE_SIZE);
            }
            return new PreparedSingle(pageStyled, revision);
        });
    }

    static CompletableFuture<PreparedSingle> tryBuildSingle(
            int[] source, int profile, long revision) {
        return tryBuildSingle(source, profile, revision, () -> true);
    }

    static CompletableFuture<PreparedSingle> tryBuildSingle(
            int[] source, int profile, long revision,
            BooleanSupplier stillValid) {
        return submit(MapRequestLane.BACKGROUND,
                MapWorkScheduler.WorkType.LEGACY_BUILD, 0, 48, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            int[] styled = new int[PIXELS];
            for (int i = 0; i < styled.length; i++) {
                if ((i & 8191) == 0) check(valid);
                styled[i] = MapColorProfile.apply(source[i], profile);
            }
            return new PreparedSingle(styled, revision);
        });
    }

    private static <T> CompletableFuture<T> submit(MapRequestLane lane,
            MapWorkScheduler.WorkType type, int priority, int cost,
            BooleanSupplier stillValid, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        BooleanSupplier valid = () -> !future.isCancelled()
                && (stillValid == null || stillValid.getAsBoolean());
        boolean accepted = MapWorkScheduler.tryCpu(lane, type, priority, cost,
                valid, () -> {
                    try {
                        check(valid);
                        future.complete(supplier.get());
                    } catch (Throwable throwable) {
                        future.completeExceptionally(throwable);
                    }
                });
        return accepted ? future : null;
    }

    private static BooleanSupplier guarded(BooleanSupplier valid) {
        return valid == null ? () -> true : valid;
    }

    private static void check(BooleanSupplier valid) {
        if (!valid.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException();
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

    private static byte[] buildSmoothedLightPageWindow(byte[] levels, int stride,
            int halo, BooleanSupplier valid) {
        if (levels == null) return null;
        int pageSize = MapPageLayout.PAGE_SIZE;
        if (levels.length != stride * stride || stride < pageSize + halo * 2) {
            return null;
        }
        byte[] result = new byte[pageSize * pageSize];
        for (int localZ = 0; localZ < pageSize; localZ++) {
            if ((localZ & 15) == 0 && !valid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException();
            }
            int z = halo + localZ;
            for (int localX = 0; localX < pageSize; localX++) {
                int x = halo + localX;
                int weighted = 0;
                int weight = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    int sampleZ = z + dz;
                    if (sampleZ < 0 || sampleZ >= stride) continue;
                    int wz = dz == 0 ? 2 : 1;
                    int row = sampleZ * stride;
                    for (int dx = -1; dx <= 1; dx++) {
                        int sampleX = x + dx;
                        if (sampleX < 0 || sampleX >= stride) continue;
                        int wx = dx == 0 ? 2 : 1;
                        int sampleWeight = wx * wz;
                        weighted += (levels[row + sampleX] & 0xFF) * sampleWeight;
                        weight += sampleWeight;
                    }
                }
                int original = levels[z * stride + x] & 0xFF;
                int filtered = weight == 0 ? original : Math.round((float) weighted / weight);
                result[localZ * pageSize + localX] = (byte) Math.max(original, filtered);
            }
        }
        return result;
    }

    private static long[] buildKnownRowsForPageWindow(long[] packedPixels,
            int stride, int halo) {
        long[] rows = new long[MapPageLayout.PAGE_SIZE];
        int pageSize = MapPageLayout.PAGE_SIZE;
        if (packedPixels == null || packedPixels.length != stride * stride
                || stride < pageSize + halo * 2) return rows;
        for (int localZ = 0; localZ < pageSize; localZ++) {
            int row = (halo + localZ) * stride + halo;
            long mask = 0L;
            for (int localX = 0; localX < pageSize; localX++) {
                if (!MapBlockData.isEmpty(packedPixels[row + localX])) {
                    mask |= 1L << localX;
                }
            }
            rows[localZ] = mask;
        }
        return rows;
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

    private static long[][] buildPageKnownRows(long[] packedPixels) {
        long[][] pages = new long[MapPageLayout.PAGES_PER_REGION * MapPageLayout.PAGES_PER_REGION]
                [MapPageLayout.PAGE_SIZE];
        if (packedPixels == null || packedPixels.length < PIXELS) return pages;
        for (int z = 0; z < SIZE; z++) {
            int pageZ = z / MapPageLayout.PAGE_SIZE;
            int localZ = z % MapPageLayout.PAGE_SIZE;
            int fullRow = z * SIZE;
            for (int pageX = 0; pageX < MapPageLayout.PAGES_PER_REGION; pageX++) {
                long mask = 0L;
                int startX = pageX * MapPageLayout.PAGE_SIZE;
                for (int x = 0; x < MapPageLayout.PAGE_SIZE; x++) {
                    if (!MapBlockData.isEmpty(packedPixels[fullRow + startX + x])) {
                        mask |= 1L << x;
                    }
                }
                pages[pageZ * MapPageLayout.PAGES_PER_REGION + pageX][localZ] = mask;
            }
        }
        return pages;
    }

    record PreparedPair(int[] styled, int[] glow, long revision, long[][] pageKnownRows) {
    }

    record PreparedSingle(int[] styled, long revision) {
    }

}
