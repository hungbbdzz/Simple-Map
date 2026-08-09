package com.velorise.simplemap.client;

import net.minecraft.world.level.biome.Biome;
import com.velorise.simplemap.client.pipeline.RevisionStamp;
import com.velorise.simplemap.client.lod.PreparedBranch;
import com.velorise.simplemap.client.lod.RegionLodGraph;

import java.util.List;
import java.util.Arrays;
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
        return tryBuildSurfacePage(pixels, tints, null, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, light, profile, revision,
                stillValid, executorPriority, stamp);
    }

    static CompletableFuture<PreparedPair> tryBuildSurfacePage(
            long[] pixels, int[] tints, byte[] known, int stride, int halo,
            int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid,
            int executorPriority, RevisionStamp stamp) {
        return tryBuildSurfacePage(pixels, tints, known, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, light, profile, revision,
                stillValid, executorPriority, stamp,
                MapPageLayout.FULL_SUBTILE_MASK);
    }

    static CompletableFuture<PreparedPair> tryBuildSurfacePage(
            long[] pixels, int[] tints, byte[] known, int stride, int halo,
            int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            java.util.Map<String, Integer> blockColors,
            java.util.Map<String, BlockTintPolicy> tintPolicies,
            java.util.Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, byte[] light,
            int profile, long revision, BooleanSupplier stillValid,
            int executorPriority, RevisionStamp stamp,
            int requestedSubtileMask) {
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(executorPriority);
        MapWorkScheduler.WorkType type = lane == MapRequestLane.MINIMAP
                ? MapWorkScheduler.WorkType.MINIMAP_EXACT
                : MapWorkScheduler.WorkType.EXACT_BUILD;
        return submit(lane, type, lane.priorityBase(), 8, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            check(valid);
            long[] knownRows = known == null
                    ? buildKnownRowsForPageWindow(pixels, stride, halo)
                    : buildKnownRowsForPageWindow(known, stride, halo);
            int updateSubtiles = requestedSubtileMask
                    & MapPageLayout.completeSubtileMask(knownRows);
            int[] pageStyled = SurfaceColorizer.colorizePageWindow(
                    pixels, tints, known, stride, halo, worldPageStartX, worldPageStartZ,
                    biomePalette, blockPalette, biomeLookup, blockColors,
                    tintPolicies, tintDisabledBlocks, colourMode, showFlowers,
                    terrainSlopes, profile, updateSubtiles, valid);
            byte[] pageLight = buildSmoothedLightPageWindow(light, stride, halo,
                    updateSubtiles, valid);
            int[] pageGlow = new int[pageStyled.length];
            for (int i = 0; i < pageStyled.length; i++) {
                if ((i & 1023) == 0) check(valid);
                int localX = i % MapPageLayout.PAGE_SIZE;
                int localZ = i / MapPageLayout.PAGE_SIZE;
                int subtile = MapPageLayout.subtileIndex(
                        localX / MapPageLayout.SUBTILE_SIZE,
                        localZ / MapPageLayout.SUBTILE_SIZE);
                if ((updateSubtiles & (1 << subtile)) == 0) continue;
                int color = pageStyled[i];
                int level = pageLight == null ? 0 : pageLight[i] & 0xFF;
                int alpha = color == 0 || level == 0 ? 0
                        : Math.min(255, Math.round(
                                (float) Math.pow(level / 15.0f, 1.65f) * 255.0f));
                int warm = tintTowardWarmLight(color, level);
                pageGlow[i] = (warm & 0x00FFFFFF) | (alpha << 24);
            }
            return new PreparedPair(pageStyled, pageGlow, revision,
                    new long[][] { knownRows }, stamp, updateSubtiles);
        });
    }

    static CompletableFuture<PreparedSurfaceRegionBatch> tryBuildSurfaceBatch(
            SurfaceRegionSourceDatabase.BatchSourcePlan sourcePlan,
            MapStyleSnapshot style, long[] pageRevisions,
            boolean[] activePages, int[] requestedSubtileMasks,
            BooleanSupplier stillValid,
            int executorPriority) {
        if (sourcePlan == null || style == null || pageRevisions == null
                || activePages == null || pageRevisions.length
                        != sourcePlan.pagesWide() * sourcePlan.pagesHigh()
                || activePages.length != pageRevisions.length
                || requestedSubtileMasks == null
                || requestedSubtileMasks.length != pageRevisions.length) {
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

            /*
             * A 1x1 transaction is already exactly one PAGE_SNAPSHOT_SIZE window.
             * PASS102 nevertheless allocated a second long/int/byte/byte 66x66
             * scratch set and copied the assembled source into it. Minimap is now
             * deliberately 1x1, so that duplicate accounted for four avoidable
             * primitive arrays on every live page build. Borrow the immutable
             * worker-local assembly directly for 1x1 and keep reusable compact
             * scratch only for multi-page fullscreen batches.
             */
            boolean directSinglePage = source.pagesWide() == 1
                    && source.pagesHigh() == 1
                    && source.stride() == compactStride
                    && source.height() == compactStride;
            long[] pixels = directSinglePage ? source.pixelsUnsafe()
                    : new long[compactPixels];
            int[] tints = directSinglePage ? source.tintsUnsafe()
                    : new int[compactPixels];
            byte[] lights = directSinglePage ? source.lightUnsafe()
                    : new byte[compactPixels];
            byte[] known = directSinglePage ? source.knownUnsafe()
                    : new byte[compactPixels];
            byte[] smoothedLights = new byte[MapPageLayout.PAGE_SIZE
                    * MapPageLayout.PAGE_SIZE];
            List<String> biomePalette = source.biomePalette();
            List<String> blockPalette = source.blockPalette();
            IntFunction<Biome> biomeLookup = style.biomeLookup(biomePalette);

            for (int pageZ = 0; pageZ < source.pagesHigh(); pageZ++) {
                for (int pageX = 0; pageX < source.pagesWide(); pageX++) {
                    int pageIndex = pageZ * source.pagesWide() + pageX;
                    if (!activePages[pageIndex]) continue;
                    check(valid);
                    int sourceX = pageX * MapPageLayout.PAGE_SIZE;
                    int sourceZ = pageZ * MapPageLayout.PAGE_SIZE;
                    if (!directSinglePage) {
                        for (int z = 0; z < compactStride; z++) {
                            int from = (sourceZ + z) * source.stride() + sourceX;
                            int to = z * compactStride;
                            System.arraycopy(source.pixelsUnsafe(), from,
                                    pixels, to, compactStride);
                            System.arraycopy(source.tintsUnsafe(), from,
                                    tints, to, compactStride);
                            System.arraycopy(source.lightUnsafe(), from,
                                    lights, to, compactStride);
                            System.arraycopy(source.knownUnsafe(), from,
                                    known, to, compactStride);
                        }
                    }
                    int worldPageStartX = source.worldPageStartX()
                            + pageX * MapPageLayout.PAGE_SIZE;
                    int worldPageStartZ = source.worldPageStartZ()
                            + pageZ * MapPageLayout.PAGE_SIZE;
                    long[] knownRows = buildKnownRowsForPageWindow(
                            known, compactStride, halo);
                    int updateSubtiles = requestedSubtileMasks[pageIndex]
                            & MapPageLayout.completeSubtileMask(knownRows);
                    int[] styled = SurfaceColorizer.colorizePageWindow(
                            pixels, tints, known, compactStride, halo,
                            worldPageStartX, worldPageStartZ,
                            biomePalette, blockPalette, biomeLookup,
                            style.blockColors(), style.tintPolicies(),
                            style.tintDisabledBlocks(), style.colourMode(),
                            style.showFlowers(), style.terrainSlopes(),
                            style.profile(), updateSubtiles, valid);
                    byte[] smoothed = buildSmoothedLightPageWindowInto(
                            lights, compactStride, halo, valid, smoothedLights,
                            updateSubtiles);
                    int[] glow = new int[styled.length];
                    for (int index = 0; index < styled.length; index++) {
                        int localX = index % MapPageLayout.PAGE_SIZE;
                        int localZ = index / MapPageLayout.PAGE_SIZE;
                        int subtile = MapPageLayout.subtileIndex(
                                localX / MapPageLayout.SUBTILE_SIZE,
                                localZ / MapPageLayout.SUBTILE_SIZE);
                        if ((updateSubtiles & (1 << subtile)) == 0) continue;
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
                                    knownRows }, source.stamp(), updateSubtiles);
                }
            }
            return new PreparedSurfaceRegionBatch(source.stamp(),
                    source.regionX(), source.regionZ(), source.batchPageX(),
                    source.batchPageZ(), source.pagesWide(), source.pagesHigh(),
                    source.sourceRevision(), prepared);
        });
    }

    /**
     * Lightweight direct projection from the already resident region. PASS118
     * uses a 4-block supersample grid and linearly reduces 2x2 styled samples into
     * each final level-0 texel. This removes the point-sampled 8x8 alias pattern
     * without cloning/colorizing the full 512x512 region.
     */
    static CompletableFuture<PreparedBranch> tryBuildRegionLodLevel0(
            RegionLodGraph.Lease lease, MapManager.RegionLodSnapshot source,
            MapStyleSnapshot style, BooleanSupplier stillValid,
            int executorPriority) {
        int sampledOutputSize = source == null ? 0
                : source.stride() - source.halo() * 2;
        if (lease == null || source == null || style == null
                || lease.key().level() != 0 || source.halo() != 1
                || source.sampleStep() != 4 || sampledOutputSize != 128
                || source.pixelsUnsafe().length
                        != source.stride() * source.stride()
                || source.tintsUnsafe().length
                        != source.pixelsUnsafe().length
                || source.knownUnsafe().length
                        != source.pixelsUnsafe().length) {
            return null;
        }
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                executorPriority);
        return submit(lane, MapWorkScheduler.WorkType.SOURCE_PROJECTION,
                lane.priorityBase(), 12, stillValid, () -> {
            BooleanSupplier valid = guarded(stillValid);
            check(valid);
            int[] supersampled = SurfaceColorizer.colorizeSampleWindow(
                    source.pixelsUnsafe(), source.tintsUnsafe(), source.knownUnsafe(),
                    source.stride(),
                    source.halo(), sampledOutputSize,
                    source.regionX() * MapPageLayout.REGION_SIZE
                            + source.sampleStep() / 2,
                    source.regionZ() * MapPageLayout.REGION_SIZE
                            + source.sampleStep() / 2,
                    source.sampleStep(),
                    Arrays.asList(source.biomePaletteUnsafe()),
                    Arrays.asList(source.blockPaletteUnsafe()),
                    style.biomeLookup(), style.blockColors(),
                    style.tintPolicies(), style.tintDisabledBlocks(),
                    style.colourMode(), style.showFlowers(),
                    style.terrainSlopes(), style.profile(), valid);
            int[] output = new int[64 * 64];
            long[] knownRows = new long[64];
            byte[] known = source.knownUnsafe();
            int stride = source.stride();
            int halo = source.halo();
            for (int y = 0; y < 64; y++) {
                if ((y & 15) == 0) check(valid);
                int sampleY = y << 1;
                for (int x = 0; x < 64; x++) {
                    int sampleX = x << 1;
                    int knownCount = 0;
                    long red = 0L;
                    long green = 0L;
                    long blue = 0L;
                    int colored = 0;
                    for (int dz = 0; dz < 2; dz++) {
                        int sourceY = sampleY + dz;
                        int knownRowOffset = (sourceY + halo) * stride + halo;
                        int colorRowOffset = sourceY * sampledOutputSize;
                        for (int dx = 0; dx < 2; dx++) {
                            int sourceX = sampleX + dx;
                            if (known[knownRowOffset + sourceX] == 0) continue;
                            knownCount++;
                            int color = supersampled[colorRowOffset + sourceX];
                            if ((color >>> 24) == 0) continue;
                            colored++;
                            red += color & 0xFF;
                            green += (color >>> 8) & 0xFF;
                            blue += (color >>> 16) & 0xFF;
                        }
                    }
                    if (knownCount > 0) knownRows[y] |= 1L << x;
                    if (colored > 0) {
                        output[y * 64 + x] = 0xFF000000
                                | ((int) (blue / colored) << 16)
                                | ((int) (green / colored) << 8)
                                | (int) (red / colored);
                    }
                }
            }

            long knownMask = 0L;
            long completeMask = 0L;
            for (int childZ = 0; childZ < 8; childZ++) {
                for (int childX = 0; childX < 8; childX++) {
                    long segment = 0xFFL << (childX * 8);
                    boolean any = false;
                    boolean all = true;
                    int firstY = childZ * 8;
                    for (int y = firstY; y < firstY + 8; y++) {
                        long rowSegment = knownRows[y] & segment;
                        any |= rowSegment != 0L;
                        all &= rowSegment == segment;
                    }
                    long bit = 1L << (childZ * 8 + childX);
                    if (any) knownMask |= bit;
                    if (all) completeMask |= bit;
                }
            }
            int[] dirty = regionLodDirtyRect(lease.dirtyChildMask());
            return new PreparedBranch(lease.key(), lease.stamp(),
                    lease.revision(), 64, 64, output, knownMask,
                    completeMask, knownRows, Arrays.copyOf(knownRows, 64),
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

    static CompletableFuture<PreparedBranch> tryDeriveRegionLodLevel0(
            RegionLodGraph.Lease lease,
            java.util.Collection<com.velorise.simplemap.client.lod.RegionLodDeriver.ReducedChildSnapshot> children,
            BooleanSupplier stillValid, int executorPriority) {
        if (lease == null || lease.key().level() != 0) return null;
        MapRequestLane lane = MapWorkScheduler.laneForExecutorPriority(
                executorPriority);
        return submit(lane, MapWorkScheduler.WorkType.BRANCH_DERIVE,
                lane.priorityBase(), 16, stillValid,
                () -> com.velorise.simplemap.client.lod.RegionLodDeriver
                        .deriveLevel0(lease, children, guarded(stillValid)));
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
        if (supplier == null) return null;
        BooleanSupplier valid = stillValid == null ? () -> true : stillValid;
        /*
         * Viewport-scoped tasks may be purged before a worker starts them. The old
         * tryCpu wrapper left its manually-created future permanently incomplete in
         * that case, so pendingSurfaceBatches/page.pending accumulated while the
         * scheduler itself reported zero queued work. tryCpuFuture deliberately runs
         * a tiny terminal command for invalid tasks and cancels the future, allowing
         * all ownership/requeue callbacks to release their state.
         */
        return MapWorkScheduler.tryCpuFuture(lane, type, priority, cost, valid, () -> {
            check(valid);
            return supplier.get();
        });
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
        return buildSmoothedLightPageWindow(levels, stride, halo,
                MapPageLayout.FULL_SUBTILE_MASK, valid);
    }

    private static byte[] buildSmoothedLightPageWindow(byte[] levels, int stride,
            int halo, int requestedSubtiles, BooleanSupplier valid) {
        if (levels == null) return null;
        return buildSmoothedLightPageWindowInto(levels, stride, halo, valid,
                new byte[MapPageLayout.PAGE_SIZE * MapPageLayout.PAGE_SIZE],
                requestedSubtiles);
    }

    private static byte[] buildSmoothedLightPageWindowInto(byte[] levels,
            int stride, int halo, BooleanSupplier valid, byte[] result) {
        return buildSmoothedLightPageWindowInto(levels, stride, halo, valid,
                result, MapPageLayout.FULL_SUBTILE_MASK);
    }

    private static byte[] buildSmoothedLightPageWindowInto(byte[] levels,
            int stride, int halo, BooleanSupplier valid, byte[] result,
            int requestedSubtiles) {
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
            int subtileRow = (localZ / MapPageLayout.SUBTILE_SIZE)
                    * MapPageLayout.SUBTILES_PER_PAGE;
            int z = halo + localZ;
            for (int localX = 0; localX < pageSize; localX++) {
                int subtile = subtileRow
                        + localX / MapPageLayout.SUBTILE_SIZE;
                if ((requestedSubtiles & (1 << subtile)) == 0) continue;
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

    /** Coverage is independent of material: a scanned void column is still known. */
    private static long[] buildKnownRowsForPageWindow(byte[] knownPixels,
            int stride, int halo) {
        long[] rows = new long[MapPageLayout.PAGE_SIZE];
        int pageSize = MapPageLayout.PAGE_SIZE;
        if (knownPixels == null || knownPixels.length != stride * stride
                || stride < pageSize + halo * 2) return rows;
        for (int localZ = 0; localZ < pageSize; localZ++) {
            int row = (halo + localZ) * stride + halo;
            long mask = 0L;
            for (int localX = 0; localX < pageSize; localX++) {
                if (knownPixels[row + localX] != 0) mask |= 1L << localX;
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
            long[][] pageKnownRows, RevisionStamp stamp,
            int updateSubtileMask) {
        PreparedPair(int[] styled, int[] glow, long revision,
                long[][] pageKnownRows) {
            this(styled, glow, revision, pageKnownRows, null,
                    MapPageLayout.FULL_SUBTILE_MASK);
        }

        PreparedPair(int[] styled, int[] glow, long revision,
                long[][] pageKnownRows, RevisionStamp stamp) {
            this(styled, glow, revision, pageKnownRows, stamp,
                    MapPageLayout.FULL_SUBTILE_MASK);
        }
    }

    record PreparedSingle(int[] styled, long revision) {
    }

}
