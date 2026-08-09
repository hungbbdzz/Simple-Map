package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapVisualClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Client-thread-only, single-pass vertical cave scanner. */
public final class CaveTileScanner {
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private final CaveStateClassifier classifier = CaveStateClassifier.getInstance();
    private final MapVisualClassifier visualClassifier = MapVisualClassifier.getInstance();
    private final CaveColorResolver colors = CaveColorResolver.getInstance();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();

    public CaveColumnData scanColumn(Level level, int blockX, int blockZ) {
        CaveTileScanContext context = CaveTileScanContext.create(level, blockX >> 4, blockZ >> 4);
        return scanColumn(level, blockX, blockZ, context);
    }

    /**
     * Scans one X/Z column while reusing section-palette information from the other
     * columns in the same chunk tile.
     */
    public CaveColumnData scanColumn(Level level, int blockX, int blockZ,
            CaveTileScanContext context) {
        if (level == null || context == null
                || !level.hasChunk(blockX >> 4, blockZ >> 4)) return null;

        Scratch scratch = SCRATCH.get();
        scratch.builder.reset();

        int minimumY = level.getMinBuildHeight();
        int startY = findUndergroundStart(level, blockX, blockZ, scratch.probe, context);
        if (startY <= minimumY) {
            return CaveColumnData.emptyScanned(minimumY, startY, true);
        }

        boolean inOpenRun = false;
        int runTopY = startY;
        int waterTopY = Integer.MIN_VALUE;
        int waterDepth = 0;
        boolean runHadWater = false;
        boolean runHadOtherFluid = false;
        boolean runFluidEmissive = false;
        int runFluidColor = 0;

        int y = startY;
        while (y >= minimumY) {
            byte sectionKind = context.sectionKind(level, y, classifier);
            int sectionBottom = context.sectionBottom(level, y);

            if (sectionKind == CaveTileScanContext.ALL_AIR) {
                telemetry.recordAirSectionSkip();
                if (!inOpenRun) {
                    inOpenRun = true;
                    runTopY = y;
                    waterTopY = Integer.MIN_VALUE;
                    waterDepth = 0;
                    runHadWater = false;
                    runHadOtherFluid = false;
                    runFluidEmissive = false;
                    runFluidColor = 0;
                }
                y = sectionBottom - 1;
                continue;
            }

            if (sectionKind == CaveTileScanContext.ALL_SOLID_FAST) {
                telemetry.recordSolidSectionSkip();
                if (inOpenRun) {
                    /*
                     * The current Y is the first solid floor below the open interval.
                     * Resolve exactly this boundary once, then skip the remaining
                     * all-solid section in one step.
                     */
                    scratch.probe.set(blockX, y, blockZ);
                    BlockState floor = readState(level, scratch.probe);
                    int color = colors.resolveDense(level, scratch.probe, floor,
                            waterTopY, waterDepth);
                    byte flags = runHadWater ? CaveColumnData.FLAG_WATER : 0;
                    if (runHadOtherFluid) flags |= CaveColumnData.FLAG_FLUID;
                    if (floor.getLightEmission() > 0 || runFluidEmissive) {
                        flags |= CaveColumnData.FLAG_EMISSIVE;
                    }
                    if (runFluidColor != 0) {
                        color = CaveProjectionSemantics.blendOverlay(
                                color, runFluidColor, 112);
                    }
                    scratch.builder.add(runTopY, y, color, flags);
                    inOpenRun = false;
                    waterTopY = Integer.MIN_VALUE;
                    waterDepth = 0;
                    runHadWater = false;
                    runHadOtherFluid = false;
                    runFluidEmissive = false;
                    runFluidColor = 0;
                }
                y = sectionBottom - 1;
                continue;
            }

            scratch.probe.set(blockX, y, blockZ);
            BlockState state = readState(level, scratch.probe);
            byte kind = classifier.classify(state);

            if (kind == CaveStateClassifier.WATER) {
                if (!inOpenRun) {
                    inOpenRun = true;
                    runTopY = y;
                    waterTopY = y;
                    waterDepth = 0;
                    runHadWater = true;
                    runHadOtherFluid = false;
                    runFluidEmissive = false;
                    runFluidColor = 0;
                }
                if (!runHadWater) {
                    runHadWater = true;
                    waterTopY = y;
                }
                waterDepth++;
                y--;
                continue;
            }

            if (kind == CaveStateClassifier.OTHER_FLUID) {
                /* Xaero treats fluid below the terrain roof as an overlay/open
                 * cavity and continues to the solid floor. PASS109 instead made
                 * the fluid block itself the archived floor, producing lava/water
                 * sheets and authority-dependent Full Cave geometry. */
                if (!inOpenRun) {
                    inOpenRun = true;
                    runTopY = y;
                    waterTopY = Integer.MIN_VALUE;
                    waterDepth = 0;
                    runHadWater = false;
                    runHadOtherFluid = false;
                    runFluidEmissive = false;
                    runFluidColor = 0;
                }
                runHadOtherFluid = true;
                int fluidColor = colors.resolveDenseFluid(
                        level, scratch.probe, state);
                if (fluidColor != 0) runFluidColor = fluidColor;
                if (state.getLightEmission() > 0) runFluidEmissive = true;
                y--;
                continue;
            }

            if (kind == CaveStateClassifier.AIR) {
                if (!inOpenRun) {
                    inOpenRun = true;
                    runTopY = y;
                    waterTopY = Integer.MIN_VALUE;
                    waterDepth = 0;
                    runHadWater = false;
                    runHadOtherFluid = false;
                    runFluidEmissive = false;
                    runFluidColor = 0;
                }
                y--;
                continue;
            }

            /*
             * Xaero's cave writer lets only real air/fluid start an open run.
             * A rail, torch, flower, glass pane or another collision-empty state
             * may live inside an already-open cave, but it must never manufacture
             * a new cave opening while the scan is still inside solid terrain.
             */
            if (inOpenRun) {
                MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
                boolean collisionEmpty = kind == CaveStateClassifier.DYNAMIC
                        && classifier.isCollisionEmpty(level, scratch.probe, state);
                if (CaveProjectionSemantics.isOpenDecoration(
                        state, visual, collisionEmpty)) {
                    y--;
                    continue;
                }
            }

            if (inOpenRun) {
                int color = colors.resolveDense(level, scratch.probe, state,
                        waterTopY, waterDepth);
                byte flags = runHadWater ? CaveColumnData.FLAG_WATER : 0;
                if (runHadOtherFluid) flags |= CaveColumnData.FLAG_FLUID;
                if (state.getLightEmission() > 0 || runFluidEmissive) {
                    flags |= CaveColumnData.FLAG_EMISSIVE;
                }
                if (runFluidColor != 0) {
                    color = CaveProjectionSemantics.blendOverlay(
                            color, runFluidColor, 112);
                }
                scratch.builder.add(runTopY, y, color, flags);
                inOpenRun = false;
                waterTopY = Integer.MIN_VALUE;
                waterDepth = 0;
                runHadWater = false;
                runHadOtherFluid = false;
                runFluidEmissive = false;
                runFluidColor = 0;
            }
            y--;
        }

        return scratch.builder.build(minimumY, startY, y < minimumY);
    }

    private int findUndergroundStart(Level level, int blockX, int blockZ,
            BlockPos.MutableBlockPos probe, CaveTileScanContext context) {
        int minimumY = level.getMinBuildHeight();
        int maximumY = level.getMaxBuildHeight() - 1;
        int topY = CaveDimensionProfile.shouldScanFromWorldTop(level)
                ? maximumY
                : Math.max(minimumY, Math.min(maximumY,
                        level.getHeight(Heightmap.Types.WORLD_SURFACE,
                                blockX, blockZ)));

        /*
         * Enter the first real terrain/roof block, then begin immediately below it.
         * Requiring several consecutive solid blocks misses tunnels with thin roofs.
         * The shared section context lets us jump over all-air palette sections and
         * enter an all-solid section without inspecting every Y.
         */
        int y = topY;
        while (y >= minimumY) {
            byte sectionKind = context.sectionKind(level, y, classifier);
            int sectionBottom = context.sectionBottom(level, y);
            if (sectionKind == CaveTileScanContext.ALL_AIR) {
                telemetry.recordAirSectionSkip();
                y = sectionBottom - 1;
                continue;
            }
            if (sectionKind == CaveTileScanContext.ALL_SOLID_FAST) return y - 1;

            probe.set(blockX, y, blockZ);
            BlockState state = readState(level, probe);
            MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
            boolean collisionEmpty = classifier.classify(state) == CaveStateClassifier.DYNAMIC
                    && classifier.isCollisionEmpty(level, probe, state);
            if (CaveProjectionSemantics.isTerrainEntry(
                    state, visual, collisionEmpty)) return y - 1;
            y--;
        }
        return minimumY;
    }

    private BlockState readState(Level level, BlockPos pos) {
        telemetry.recordBlockStateRead();
        return level.getBlockState(pos);
    }

    private static final class Scratch {
        private final CaveColumnData.Builder builder = new CaveColumnData.Builder();
        private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    }
}
