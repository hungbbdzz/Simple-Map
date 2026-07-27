package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapCancellationToken;
import com.velorise.simplemap.client.MapVisualClassifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dense cave projector shared by live chunks and world-save NBT.
 *
 * <p>Geometry comes from {@link CaveStateClassifier}; visual participation comes
 * from the same {@link MapVisualClassifier} used by the surface map. Base material
 * and up to three ordered overlays remain separate until page styling.</p>
 */
final class CaveDisplayProjector {
    static final int LAYER_DEPTH = 32;

    private final CaveStateClassifier geometry = CaveStateClassifier.getInstance();
    private final MapVisualClassifier visuals = MapVisualClassifier.getInstance();

    DenseCaveTile project(ChunkSource source, CaveView view, int layerY,
            long revision, DenseCaveTile.Source tileSource) {
        return project(source, view, layerY, revision, tileSource,
                new MapCancellationToken(null));
    }

    DenseCaveTile project(ChunkSource source, CaveView view, int layerY,
            long revision, DenseCaveTile.Source tileSource,
            MapCancellationToken token) {
        MapCancellationToken effectiveToken = token == null
                ? new MapCancellationToken(null) : token;
        DenseCaveTile.Builder builder = new DenseCaveTile.Builder();
        for (int localZ = 0; localZ < 16; localZ++) {
            effectiveToken.checkpoint("cave-project-row-" + localZ);
            for (int localX = 0; localX < 16; localX++) {
                projectColumn(source, view, layerY, localX, localZ, builder);
            }
        }
        effectiveToken.checkpoint("cave-project-tile-ready");
        return builder.build(source.chunkX(), source.chunkZ(), view,
                layerY, layerY, revision, tileSource);
    }

    void projectColumn(ChunkSource source, CaveView view, int layerY,
            int localX, int localZ, DenseCaveTile.Builder output) {
        output.beginColumn();
        boolean full = view == CaveView.FULL;
        int minimumY = source.minimumY();
        int maximumY = source.maximumY() - 1;
        int highY = full
                ? clamp(source.surfaceY(localX, localZ) + 3, minimumY, maximumY)
                : clamp(layerY, minimumY, maximumY);
        int lowY = full
                ? minimumY
                : Math.max(minimumY, highY + 1 - LAYER_DEPTH);

        boolean underAir = full;
        boolean shouldEnterGround = full;
        int openTopY = lowY;
        int waterDepth = 0;
        int otherFluidDepth = 0;
        boolean sawOverlay = false;

        for (int y = highY; y >= lowY; y--) {
            BlockState state = source.stateAt(localX, y, localZ);
            BlockState visualState = source.visualStateAt(localX, y, localZ);
            CaveStateClassifier.StateInfo geometryInfo = geometry.info(state);
            MapVisualClassifier.VisualInfo visual = visuals.info(visualState);
            byte kind = geometryInfo.kind();
            int stateLight = Math.max(Math.max(state.getLightEmission(),
                    visualState.getLightEmission()), source.lightAt(localX, y, localZ));
            int skyLight = source.skyLightAt(localX, y, localZ);
            int visibleLayerLight = Math.max(stateLight, Math.min(12, skyLight));

            // Match Xaero's cave-entry semantics: only real air (and fluids below)
            // opens a cavity. A torch, flower, rail, leaf or glass block embedded in
            // terrain must not manufacture an artificial one-pixel cave opening.
            if (state.isAir()) {
                if (!underAir) openTopY = y;
                underAir = true;
                continue;
            }

            if (visual.fluid()) {
                // A waterlogged state can contribute both its block material and a
                // fluid overlay. Pure fluid states stop here; waterlogged geometry
                // continues through the normal base/overlay classification below.
                if (!shouldEnterGround) {
                    if (!underAir) openTopY = y;
                    underAir = true;
                    int layerColor = source.resolveFluidColor(
                            visualState, localX, y, localZ);
                    byte layerFlags = DenseCaveTile.OVERLAY_FLUID;
                    if (visual.emissive()) layerFlags |= DenseCaveTile.OVERLAY_EMISSIVE;
                    output.addOverlay(layerColor, visuals.fluidOverlayOpacity(visualState), y,
                            visibleLayerLight, layerFlags);
                    sawOverlay |= layerColor != 0;
                    if (kind == CaveStateClassifier.WATER || visual.water()) waterDepth++;
                    else otherFluidDepth++;
                }
                if (visual.role() == MapVisualClassifier.Role.FLUID_OVERLAY) continue;
            }

            // Torches and equivalent modded decorations contribute light but never
            // replace the floor material or open a cavity by themselves.
            if (visual.role() == MapVisualClassifier.Role.INVISIBLE) continue;

            boolean collisionEmpty = kind == CaveStateClassifier.AIR
                    || (kind == CaveStateClassifier.DYNAMIC
                            && geometry.isCollisionEmpty(source.blockGetter(),
                                    source.position(localX, y, localZ), state));
            boolean visualOverlay = visual.role() == MapVisualClassifier.Role.TRANSPARENT_OVERLAY
                    || visual.role() == MapVisualClassifier.Role.EMISSIVE_OVERLAY;

            if (visualOverlay || collisionEmpty) {
                // Like Xaero's loadPixelHelp(), transparent material is collected
                // only after the scan is already under real air/fluid. It is not an
                // air substitute while traversing solid terrain.
                if (shouldEnterGround || !underAir) continue;
                int layerColor = source.resolveBlockColor(
                        visualState, localX, y, localZ);
                byte layerFlags = visual.emissive()
                        ? DenseCaveTile.OVERLAY_EMISSIVE : 0;
                output.addOverlay(layerColor, visual.overlayOpacity(), y,
                        visibleLayerLight, layerFlags);
                sawOverlay |= layerColor != 0;
                continue;
            }

            if (shouldEnterGround) {
                // Surface trees/decor do not count as the terrain roof. The first
                // opaque terrain block begins the underground search transaction.
                if (state.is(BlockTags.LOGS) || visual.leaves()
                        || visual.flower() || state.canBeReplaced()) {
                    continue;
                }
                shouldEnterGround = false;
                underAir = false;
                waterDepth = 0;
                otherFluidDepth = 0;
                sawOverlay = false;
                output.beginColumn();
                continue;
            }

            if (!underAir) continue;

            int color = source.resolveBlockColor(visualState, localX, y, localZ);
            if (color == 0) {
                sawOverlay = true;
                continue;
            }

            byte flags = 0;
            if (waterDepth > 0) flags |= DenseCaveTile.FLAG_WATER;
            if (otherFluidDepth > 0) flags |= DenseCaveTile.FLAG_FLUID;
            if (visual.emissive()) flags |= DenseCaveTile.FLAG_EMISSIVE;
            if (sawOverlay) flags |= DenseCaveTile.FLAG_OVERLAY;
            output.set(localX, localZ, color, y,
                    Math.max(y, openTopY), flags, stateLight);
            return;
        }

        output.set(localX, localZ, 0, 0, 0, (byte) 0, 0);
    }

    interface ChunkSource {
        int chunkX();
        int chunkZ();
        int minimumY();
        /** Exclusive maximum build height. */
        int maximumY();
        int surfaceY(int localX, int localZ);
        BlockState stateAt(int localX, int y, int localZ);
        default BlockState visualStateAt(int localX, int y, int localZ) {
            return stateAt(localX, y, localZ);
        }
        net.minecraft.world.level.BlockGetter blockGetter();
        net.minecraft.core.BlockPos position(int localX, int y, int localZ);
        int lightAt(int localX, int y, int localZ);
        int skyLightAt(int localX, int y, int localZ);
        int resolveBlockColor(BlockState state, int localX, int y, int localZ);
        int resolveFluidColor(BlockState state, int localX, int y, int localZ);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
