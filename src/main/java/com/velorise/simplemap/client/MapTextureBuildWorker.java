package com.velorise.simplemap.client;

import net.minecraft.world.level.biome.Biome;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.lod.PreparedBranch;
import com.velorise.simplemap.client.lod.RegionLodGraph;

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
                revision, stillValid, MapRequestLane.FULLSCREEN.executorPriority(), null);
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
            int executorPriority, RevisionStamp stamp) {
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
                    new long[][] { buildKnownRowsForPageWindow(pixels, stride, halo) },
                    stamp);
        });
    }

    static CompletableFuture<PreparedSurfaceRegionBatch> tryBuildSurfaceBatch(
            SurfaceRegionSourceDatabase.BatchSourcePlan sourcePlan,
            MapStyleSnapshot style, long[] pageRevisions,
            boolean[] activePages, BooleanSupplier stillValid,
            int executorPriority) {
        if (sourcePlan == null || style == null || pageRevisions == null
                || activePages == null || pageRevisions.length
                        != sourcePlan.pagesWide() * sourcePlan.pagesHigh()
                || activePages.length != pageRevisions.length) {
            return null;
        }
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(executorPriority);
        MapWorkScheduler.WorkType type = lane == MapRequestLane.MINIMAP
                ? MapWorkScheduler.WorkType.MINIMAP_EXACT
                : MapWorkScheduler.WorkType.EXACT_BUILD;
        int pageCount = sourcePlan.pagesWide() * sourcePlan.pagesHigh();
        int activeCount = 0;
        for (boolean active : activePages) if (active) activeCount++;
        if (activeCount == 0) return null;
        final int admittedPages = activeCount;
        return submit(lane, type, lane.priorityBase(),
                Math.max(8, admittedPages * 6),
                stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            SurfaceRegionSourceDatabase.AssembledBatchWindow source =
                    sourcePlan.assemble(valid);
            MapTextureBuildWorker.PreparedPair[] prepared =
                    new MapTextureBuildWorker.PreparedPair[pageCount];
            int compactStride = MapPageLayout.PAGE_SNAPSHOT_SIZE;
            int compactPixels = compactStride * compactStride;
            int halo = source.halo();

            // One worker-local scratch window is reused for every leaf in the
            // transaction. Only styled/glow/known output survives publication.
            long[] pixels = new long[compactPixels];
            int[] tints = new int[compactPixels];
            byte[] lights = new byte[compactPixels];
            byte[] smoothedLights = new byte[MapPageLayout.PAGE_SIZE
                    * MapPageLayout.PAGE_SIZE];
            IntFunction<Biome> biomeLookup = style.biomeLookup();
            List<String> biomePalette = source.biomePalette();
            List<String> blockPalette = source.blockPalette();

            for (int pageZ = 0; pageZ < source.pagesHigh(); pageZ++) {
                for (int pageX = 0; pageX < source.pagesWide(); pageX++) {
                    int pageIndex = pageZ * source.pagesWide() + pageX;
                    if (!activePages[pageIndex]) continue;
                    check(valid);
                    int sourceX = pageX * MapPageLayout.PAGE_SIZE;
                    int sourceZ = pageZ * MapPageLayout.PAGE_SIZE;
                    for (int z = 0; z < compactStride; z++) {
                        int from = (sourceZ + z) * source.stride() + sourceX;
                        int to = z * compactStride;
                        System.arraycopy(source.pixelsUnsafe(), from,
                                pixels, to, compactStride);
                        System.arraycopy(source.tintsUnsafe(), from,
                                tints, to, compactStride);
                        System.arraycopy(source.lightUnsafe(), from,
                                lights, to, compactStride);
                    }
                    int worldPageStartX = source.worldPageStartX()
                            + pageX * MapPageLayout.PAGE_SIZE;
                    int worldPageStartZ = source.worldPageStartZ()
                            + pageZ * MapPageLayout.PAGE_SIZE;
                    int[] styled = SurfaceColorizer.colorizePageWindow(
                            pixels, tints, compactStride, halo,
                            worldPageStartX, worldPageStartZ,
                            biomePalette, blockPalette, biomeLookup,
                            style.blockColors(), style.tintPolicies(),
                            style.tintDisabledBlocks(), style.colourMode(),
                            style.showFlowers(), style.terrainSlopes(),
                            style.profile(), valid);
                    byte[] smoothed = buildSmoothedLightPageWindowInto(
                            lights, compactStride, halo, valid, smoothedLights);
                    int[] glow = new int[styled.length];
                    for (int index = 0; index < styled.length; index++) {
                        int color = styled[index];
                        int level = smoothed == null ? 0 : smoothed[index] & 0xFF;
                        int alpha = color == 0 || level == 0 ? 0
                                : Math.min(255, Math.round((float) Math.pow(
                                        level / 15.0f, 1.65f) * 255.0f));
                        int warm = tintTowardWarmLight(color, level);
                        glow[index] = (warm & 0x00FFFFFF) | (alpha << 24);
                    }
                    prepared[pageIndex] = new PreparedPair(styled, glow,
                            pageRevisions[pageIndex], new long[][] {
                                    buildKnownRowsForPageWindow(
                                            pixels, compactStride, halo) },
                            source.stamp());
                }
            }
            return new PreparedSurfaceRegionBatch(source.stamp(),
                    source.regionX(), source.regionZ(), source.batchPageX(),
                    source.batchPageZ(), source.pagesWide(), source.pagesHigh(),
                    source.sourceRevision(), prepared);
        });
    }

    /**
     * Direct M4 projection for one 512x512 source region. It produces the
     * level-0 region branch without waiting for 64 exact pages to be built or
     * uploaded first. All colorization and reduction happens on the shared CPU
     * scheduler; the render thread only validates and publishes the result.
     */
    static CompletableFuture<PreparedBranch> tryBuildRegionLodLevel0(
            RegionLodGraph.Lease lease,
            SurfaceRegionSourceDatabase.BatchSourcePlan sourcePlan,
            MapStyleSnapshot style, BooleanSupplier stillValid,
            int executorPriority) {
        if (lease == null || sourcePlan == null || style == null
                || lease.key().level() != 0
                || sourcePlan.pagesWide() != MapPageLayout.PAGES_PER_REGION
                || sourcePlan.pagesHigh() != MapPageLayout.PAGES_PER_REGION) {
            return null;
        }
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                executorPriority);
        return submit(lane, MapWorkScheduler.WorkType.SOURCE_PROJECTION,
                lane.priorityBase(), 96, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            SurfaceRegionSourceDatabase.AssembledBatchWindow source =
                    sourcePlan.assemble(valid);
            int[] output = new int[64 * 64];
            long[] outputKnownRows = new long[64];
            long[] outputCompleteRows = new long[64];
            long knownMask = 0L;
            long completeMask = 0L;
            int compactStride = MapPageLayout.PAGE_SNAPSHOT_SIZE;
            int compactPixels = compactStride * compactStride;
            int halo = source.halo();
            long[] pixels = new long[compactPixels];
            int[] tints = new int[compactPixels];
            byte[] lights = new byte[compactPixels];

            for (int pageZ = 0; pageZ < MapPageLayout.PAGES_PER_REGION; pageZ++) {
                for (int pageX = 0; pageX < MapPageLayout.PAGES_PER_REGION; pageX++) {
                    check(valid);
                    int pageIndex = pageZ * MapPageLayout.PAGES_PER_REGION + pageX;
                    int sourceX = pageX * MapPageLayout.PAGE_SIZE;
                    int sourceZ = pageZ * MapPageLayout.PAGE_SIZE;
                    for (int z = 0; z < compactStride; z++) {
                        int from = (sourceZ + z) * source.stride() + sourceX;
                        int to = z * compactStride;
                        System.arraycopy(source.pixelsUnsafe(), from,
                                pixels, to, compactStride);
                        System.arraycopy(source.tintsUnsafe(), from,
                                tints, to, compactStride);
                        System.arraycopy(source.lightUnsafe(), from,
                                lights, to, compactStride);
                    }
                    int worldPageStartX = source.worldPageStartX()
                            + pageX * MapPageLayout.PAGE_SIZE;
                    int worldPageStartZ = source.worldPageStartZ()
                            + pageZ * MapPageLayout.PAGE_SIZE;
                    int[] styled = SurfaceColorizer.colorizePageWindow(
                            pixels, tints, compactStride, halo,
                            worldPageStartX, worldPageStartZ,
                            source.biomePalette(), source.blockPalette(),
                            style.biomeLookup(), style.blockColors(),
                            style.tintPolicies(), style.tintDisabledBlocks(),
                            style.colourMode(), style.showFlowers(),
                            style.terrainSlopes(), style.profile(), valid);
                    long[] knownRows = buildKnownRowsForPageWindow(
                            pixels, compactStride, halo);
                    boolean leafKnown = false;
                    boolean leafComplete = true;
                    for (int row = 0; row < 64; row++) {
                        leafKnown |= knownRows[row] != 0L;
                        leafComplete &= knownRows[row] == -1L;
                    }
                    long leafBit = 1L << pageIndex;
                    if (leafKnown) knownMask |= leafBit;
                    if (leafComplete) completeMask |= leafBit;

                    int destinationBaseX = pageX * 8;
                    int destinationBaseY = pageZ * 8;
                    for (int outY = 0; outY < 8; outY++) {
                        int destinationY = destinationBaseY + outY;
                        for (int outX = 0; outX < 8; outX++) {
                            int destinationX = destinationBaseX + outX;
                            ReducedSurfaceCell reduced = reduceSurfaceCell(
                                    styled, knownRows, outX * 8, outY * 8);
                            if (reduced.known()) {
                                output[destinationY * 64 + destinationX] =
                                        reduced.color();
                                outputKnownRows[destinationY] |=
                                        1L << destinationX;
                            }
                            if (reduced.complete()) {
                                outputCompleteRows[destinationY] |=
                                        1L << destinationX;
                            }
                        }
                    }
                }
            }
            int[] dirty = regionLodDirtyRect(lease.dirtyChildMask());
            return new PreparedBranch(lease.key(), lease.stamp(),
                    lease.revision(), 64, 64, output, knownMask,
                    completeMask, outputKnownRows, outputCompleteRows,
                    lease.childVersionSums(), dirty[0], dirty[1],
                    dirty[2], dirty[3]);
        });
    }

    static CompletableFuture<PreparedBranch> tryDeriveRegionLodParent(
            RegionLodGraph.Lease lease,
            java.util.Collection<com.velorise.simplemap.client.lod.RegionLodDeriver.ChildSnapshot> children,
            BooleanSupplier stillValid, int executorPriority) {
        if (lease == null || lease.key().level() <= 0) return null;
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                executorPriority);
        return submit(lane, MapWorkScheduler.WorkType.BRANCH_DERIVE,
                lane.priorityBase(), 48, stillValid,
                () -> com.velorise.simplemap.client.lod.RegionLodDeriver.derive(
                        lease, children, guarded(stillValid)));
    }

    private static ReducedSurfaceCell reduceSurfaceCell(int[] styled,
            long[] knownRows, int startX, int startY) {
        long alpha = 0L;
        long red = 0L;
        long green = 0L;
        long blue = 0L;
        int colored = 0;
        boolean known = false;
        boolean complete = true;
        for (int y = 0; y < 8; y++) {
            long knownRow = knownRows[startY + y];
            int row = (startY + y) * 64;
            for (int x = 0; x < 8; x++) {
                boolean cellKnown = (knownRow & (1L << (startX + x))) != 0L;
                known |= cellKnown;
                complete &= cellKnown;
                if (!cellKnown) continue;
                int color = styled[row + startX + x];
                int a = color >>> 24;
                if (a == 0) continue;
                colored++;
                alpha += a;
                blue += (color >>> 16) & 0xFF;
                green += (color >>> 8) & 0xFF;
                red += color & 0xFF;
            }
        }
        if (!known) return new ReducedSurfaceCell(false, false, 0);
        if (colored == 0) return new ReducedSurfaceCell(true, complete, 0);
        int a = (int) (alpha / colored);
        int b = (int) (blue / colored);
        int g = (int) (green / colored);
        int r = (int) (red / colored);
        return new ReducedSurfaceCell(true, complete,
                (a << 24) | (b << 16) | (g << 8) | r);
    }

    private static int[] regionLodDirtyRect(long dirtyChildMask) {
        if (dirtyChildMask == 0L || dirtyChildMask == -1L) {
            return new int[] { 0, 0, 63, 63 };
        }
        int minX = 64;
        int minY = 64;
        int maxX = -1;
        int maxY = -1;
        for (int child = 0; child < 64; child++) {
            if ((dirtyChildMask & (1L << child)) == 0L) continue;
            int x = (child & 7) * 8;
            int y = (child >>> 3) * 8;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x + 7);
            maxY = Math.max(maxY, y + 7);
        }
        return maxX < minX ? new int[] { 0, 0, 63, 63 }
                : new int[] { minX, minY, maxX, maxY };
    }

    private record ReducedSurfaceCell(boolean known, boolean complete,
            int color) { }

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
        return buildSmoothedLightPageWindowInto(levels, stride, halo, valid,
                new byte[MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE]);
    }

    private static byte[] buildSmoothedLightPageWindowInto(byte[] levels,
            int stride, int halo, BooleanSupplier valid, byte[] result) {
        if (levels == null) return null;
        int pageSize = MapPageLayout.PAGE_SIZE;
        if (levels.length != stride * stride || stride < pageSize + halo * 2
                || result == null || result.length < pageSize * pageSize) {
            return null;
        }
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
                int filtered = weight == 0 ? original
                        : Math.round((float) weighted / weight);
                result[localZ * pageSize + localX] =
                        (byte) Math.max(original, filtered);
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

    record PreparedPair(int[] styled, int[] glow, long revision,
            long[][] pageKnownRows, RevisionStamp stamp) {
        PreparedPair(int[] styled, int[] glow, long revision,
                long[][] pageKnownRows) {
            this(styled, glow, revision, pageKnownRows, null);
        }
    }

    record PreparedSingle(int[] styled, long revision) {
    }

}
