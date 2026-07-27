package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.MapBlockEntityVisualResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Client-thread source adapter used by the dense display projector. */
final class LiveCaveChunkSource implements CaveDisplayProjector.ChunkSource {
    private final Level level;
    private final LevelChunk chunk;
    private final int chunkX;
    private final int chunkZ;
    private final CaveColorResolver colors = CaveColorResolver.getInstance();
    private final MapBlockEntityVisualResolver blockEntityVisuals =
            MapBlockEntityVisualResolver.getInstance();
    private final BlockPos.MutableBlockPos localProbe = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos globalProbe = new BlockPos.MutableBlockPos();

    LiveCaveChunkSource(CaveChunkReadinessTracker.Snapshot snapshot) {
        this.level = snapshot.level();
        this.chunkX = snapshot.chunkX();
        this.chunkZ = snapshot.chunkZ();
        this.chunk = snapshot.centerChunk();
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public int minimumY() {
        return level.getMinBuildHeight();
    }

    @Override
    public int maximumY() {
        return level.getMaxBuildHeight();
    }

    @Override
    public int surfaceY(int localX, int localZ) {
        int globalX = (chunkX << 4) + localX;
        int globalZ = (chunkZ << 4) + localZ;
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, globalX, globalZ);
    }

    @Override
    public BlockState stateAt(int localX, int y, int localZ) {
        return chunk.getBlockState(localProbe.set(localX, y, localZ));
    }

    @Override
    public BlockState visualStateAt(int localX, int y, int localZ) {
        BlockState actual = stateAt(localX, y, localZ);
        globalProbe.set((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
        return blockEntityVisuals.resolveLive(level, globalProbe, actual);
    }

    @Override
    public BlockGetter blockGetter() {
        return level;
    }

    @Override
    public BlockPos position(int localX, int y, int localZ) {
        return globalProbe.set((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }

    @Override
    public int lightAt(int localX, int y, int localZ) {
        globalProbe.set((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
        return level.getBrightness(LightLayer.BLOCK, globalProbe);
    }

    @Override
    public int skyLightAt(int localX, int y, int localZ) {
        globalProbe.set((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
        return level.getBrightness(LightLayer.SKY, globalProbe);
    }

    @Override
    public int resolveBlockColor(BlockState state, int localX, int y, int localZ) {
        globalProbe.set((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
        return colors.resolveDense(level, globalProbe, state, Integer.MIN_VALUE, 0);
    }

    @Override
    public int resolveFluidColor(BlockState state, int localX, int y, int localZ) {
        globalProbe.set((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
        BlockState fluidState = state.getFluidState().isEmpty()
                ? state : state.getFluidState().createLegacyBlock();
        return colors.resolveDenseFluid(level, globalProbe, fluidState);
    }

}
