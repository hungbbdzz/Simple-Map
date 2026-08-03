package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapVisualClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
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
                    int color = colors.resolve(level, scratch.probe, floor,
                            y, false, waterTopY, waterDepth);
                    byte flags = runHadWater ? CaveColumnData.FLAG_WATER : 0;
                    if (floor.getLightEmission() > 0) flags |= CaveColumnData.FLAG_EMISSIVE;
                    scratch.builder.add(runTopY, y, color, flags);
                    inOpenRun = false;
                    waterTopY = Integer.MIN_VALUE;
                    waterDepth = 0;
                    runHadWater = false;
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
                int color = colors.resolveFluid(level, scratch.probe, state, y, false);
                int top = inOpenRun ? runTopY : y;
                scratch.builder.add(top, y, color, CaveColumnData.FLAG_FLUID);
                inOpenRun = false;
                waterTopY = Integer.MIN_VALUE;
                waterDepth = 0;
                runHadWater = false;

                var fluidType = state.getFluidState().getType();
                y--;
                while (y >= minimumY) {
                    byte nextSectionKind = context.sectionKind(level, y, classifier);
                    if (nextSectionKind == CaveTileScanContext.ALL_SOLID_FAST) break;
                    scratch.probe.set(blockX, y, blockZ);
                    BlockState next = readState(level, scratch.probe);
                    if (next.getFluidState().isEmpty()
                            || next.getFluidState().getType() != fluidType) break;
                    y--;
                }
                continue;
            }

            boolean open = kind == CaveStateClassifier.AIR;
            if (kind == CaveStateClassifier.DYNAMIC) {
                open = classifier.isCollisionEmpty(level, scratch.probe, state);
            }

            if (open) {
                if (!inOpenRun) {
                    inOpenRun = true;
                    runTopY = y;
                    waterTopY = Integer.MIN_VALUE;
                    waterDepth = 0;
                    runHadWater = false;
                }
                y--;
                continue;
            }

            if (inOpenRun) {
                int color = colors.resolve(level, scratch.probe, state,
                        y, false, waterTopY, waterDepth);
                byte flags = runHadWater ? CaveColumnData.FLAG_WATER : 0;
                if (state.getLightEmission() > 0) flags |= CaveColumnData.FLAG_EMISSIVE;
                scratch.builder.add(runTopY, y, color, flags);
                inOpenRun = false;
                waterTopY = Integer.MIN_VALUE;
                waterDepth = 0;
                runHadWater = false;
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
                        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                blockX, blockZ) - 1));

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
            if (isUndergroundMass(level, probe, state)) return y - 1;
            y--;
        }
        return minimumY;
    }

    private boolean isUndergroundMass(Level level, BlockPos pos, BlockState state) {
        byte kind = classifier.classify(state);
        if (kind == CaveStateClassifier.AIR
                || kind == CaveStateClassifier.WATER
                || kind == CaveStateClassifier.OTHER_FLUID) return false;
        MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
        if (visual.leaves() || state.is(BlockTags.LOGS)
                || visual.flower() || state.canBeReplaced()) return false;
        if (kind == CaveStateClassifier.SOLID_FAST) return true;
        return !classifier.isCollisionEmpty(level, pos, state);
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
