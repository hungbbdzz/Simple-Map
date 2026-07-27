package com.velorise.simplemap.client.cave;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Registry-indexed cache for cave-scanning BlockState geometry and opacity.
 *
 * Vanilla BlockState instances are immutable and canonical, so their expensive
 * collision/occlusion classification can be resolved once. Unknown modded partial
 * shapes use a conservative position-aware fallback, while still retaining all
 * context-independent fields in the cache.
 */
public final class CaveStateClassifier {
    public static final byte AIR = 0;
    public static final byte WATER = 1;
    public static final byte OTHER_FLUID = 2;
    /** A full collision cube that can be skipped by section-palette fast paths. */
    public static final byte SOLID_FAST = 3;
    /** Empty or partial geometry whose cached/fallback shape must be consulted. */
    public static final byte DYNAMIC = 4;

    private static final CaveStateClassifier INSTANCE = new CaveStateClassifier();
    private static final BlockGetter EMPTY_LEVEL = EmptyBlockGetter.INSTANCE;
    private static final BlockPos ORIGIN = BlockPos.ZERO;

    /* Registered BlockState IDs provide the cheapest hot lookup and are stable for
     * the lifetime of a running registry. The identity map is only a fallback for
     * synthetic/unregistered states supplied by unusual mods. */
    private volatile AtomicReferenceArray<StateInfo> byStateId =
            new AtomicReferenceArray<>(4096);
    private final Map<BlockState, StateInfo> fallbackCache = new IdentityHashMap<>();
    private final CaveTelemetry telemetry = CaveTelemetry.getInstance();

    private CaveStateClassifier() {
    }

    public static CaveStateClassifier getInstance() {
        return INSTANCE;
    }

    public byte classify(BlockState state) {
        return info(state).kind();
    }

    public boolean isCollisionEmpty(BlockGetter level, BlockPos pos, BlockState state) {
        StateInfo info = info(state);
        if (!info.contextDependent()) return info.collisionEmpty();
        telemetry.recordStateDynamicFallback();
        try {
            return state.getCollisionShape(level, pos).isEmpty();
        } catch (Throwable ignored) {
            return info.collisionEmpty();
        }
    }

    public boolean isFullCollisionCube(BlockState state) {
        return info(state).collisionFull();
    }

    public boolean isFullOcclusionCube(BlockState state) {
        return info(state).occlusionFull();
    }

    public boolean isSolidRender(BlockState state) {
        return info(state).solidRender();
    }

    public int lightBlock(BlockState state) {
        return info(state).lightBlock();
    }

    public float collisionTop(BlockGetter level, BlockPos pos, BlockState state) {
        StateInfo info = info(state);
        if (!info.contextDependent()) return info.collisionTop();
        telemetry.recordStateDynamicFallback();
        try {
            VoxelShape shape = state.getCollisionShape(level, pos);
            return shape.isEmpty() ? 0.0f : (float) shape.max(Direction.Axis.Y);
        } catch (Throwable ignored) {
            return info.collisionTop();
        }
    }

    public StateInfo info(BlockState state) {
        int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
        StateInfo cached = null;
        if (stateId >= 0) {
            AtomicReferenceArray<StateInfo> local = byStateId;
            if (stateId < local.length()) cached = local.get(stateId);
        } else {
            synchronized (this) {
                cached = fallbackCache.get(state);
            }
        }
        if (cached != null) return cached;

        /* Live scanning and .mca decoding can miss the same state concurrently.
         * Serialize only this cold path; registry-indexed cache hits remain lock-free. */
        synchronized (this) {
            if (stateId >= 0) {
                ensureStateCapacityLocked(stateId + 1);
                cached = byStateId.get(stateId);
            } else {
                cached = fallbackCache.get(state);
            }
            if (cached != null) return cached;
            telemetry.recordStateCacheMiss();
            StateInfo resolved = resolve(state);
            if (stateId >= 0) byStateId.set(stateId, resolved);
            else fallbackCache.put(state, resolved);
            return resolved;
        }
    }

    public synchronized void clear() {
        AtomicReferenceArray<StateInfo> current = byStateId;
        for (int i = 0; i < current.length(); i++) current.set(i, null);
        fallbackCache.clear();
    }

    private void ensureStateCapacityLocked(int required) {
        AtomicReferenceArray<StateInfo> current = byStateId;
        if (required <= current.length()) return;
        int next = current.length();
        while (next < required) next = Math.max(next + 1, next << 1);
        AtomicReferenceArray<StateInfo> expanded = new AtomicReferenceArray<>(next);
        for (int i = 0; i < current.length(); i++) expanded.set(i, current.get(i));
        byStateId = expanded;
    }

    private StateInfo resolve(BlockState state) {
        if (state.isAir()) return StateInfo.air();
        if (state.getFluidState().is(FluidTags.WATER)) return StateInfo.water();
        if (!state.getFluidState().isEmpty()) return StateInfo.otherFluid();

        boolean vanilla = "minecraft".equals(
                BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
        boolean collisionEmpty = false;
        boolean collisionFull = false;
        boolean occlusionEmpty = false;
        boolean occlusionFull = false;
        VoxelShape collisionShape = null;
        VoxelShape occlusionShape = null;
        boolean solidRender = false;
        boolean canOcclude = state.canOcclude();
        boolean skylightPasses = false;
        boolean usesShapeForLightOcclusion = state.useShapeForLightOcclusion();
        boolean largeCollisionShape = state.hasLargeCollisionShape();
        int lightBlock = 0;
        float collisionTop = 1.0f;
        boolean failed = false;

        try {
            collisionShape = state.getCollisionShape(EMPTY_LEVEL, ORIGIN);
            collisionEmpty = collisionShape.isEmpty();
            collisionFull = !collisionEmpty && Block.isShapeFullBlock(collisionShape);
            collisionTop = collisionEmpty ? 0.0f
                    : (float) collisionShape.max(Direction.Axis.Y);

            occlusionShape = state.getOcclusionShape(EMPTY_LEVEL, ORIGIN);
            occlusionEmpty = occlusionShape.isEmpty();
            occlusionFull = !occlusionEmpty && Block.isShapeFullBlock(occlusionShape);
            solidRender = state.isSolidRender(EMPTY_LEVEL, ORIGIN);
            skylightPasses = state.propagatesSkylightDown(EMPTY_LEVEL, ORIGIN);
            lightBlock = state.getLightBlock(EMPTY_LEVEL, ORIGIN);
        } catch (Throwable ignored) {
            failed = true;
            collisionEmpty = !state.blocksMotion();
            collisionFull = state.blocksMotion();
            occlusionEmpty = !canOcclude;
            occlusionFull = canOcclude && state.blocksMotion();
            solidRender = occlusionFull;
            lightBlock = occlusionFull ? 15 : 0;
            collisionTop = collisionEmpty ? 0.0f : 1.0f;
        }

        /* Full/empty shapes are safe invariants. Partial shapes from vanilla are
         * encoded by immutable state properties; unknown modded blocks may query
         * neighbours or block entities, so they retain a position-aware fallback. */
        boolean contextDependent = failed || (!vanilla && !collisionEmpty && !collisionFull);
        byte kind = collisionFull && state.blocksMotion()
                ? SOLID_FAST : DYNAMIC;
        return new StateInfo(kind, collisionEmpty, collisionFull,
                occlusionEmpty, occlusionFull, solidRender, canOcclude,
                skylightPasses, usesShapeForLightOcclusion, largeCollisionShape,
                Math.max(0, Math.min(15, lightBlock)), collisionTop,
                contextDependent, collisionShape, occlusionShape);
    }

    public record StateInfo(byte kind, boolean collisionEmpty,
            boolean collisionFull, boolean occlusionEmpty,
            boolean occlusionFull, boolean solidRender, boolean canOcclude,
            boolean skylightPasses, boolean usesShapeForLightOcclusion,
            boolean largeCollisionShape, int lightBlock, float collisionTop,
            boolean contextDependent, VoxelShape collisionShape,
            VoxelShape occlusionShape) {
        private static StateInfo air() {
            return new StateInfo(AIR, true, false, true, false,
                    false, false, true, false, false,
                    0, 0.0f, false, null, null);
        }

        private static StateInfo water() {
            return new StateInfo(WATER, true, false, true, false,
                    false, false, true, false, false,
                    0, 0.0f, false, null, null);
        }

        private static StateInfo otherFluid() {
            return new StateInfo(OTHER_FLUID, true, false, true, false,
                    false, false, true, false, false,
                    0, 0.0f, false, null, null);
        }
    }
}
