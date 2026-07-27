package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveStateClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The single visual classification policy shared by surface, live cave and
 * world-save cave paths.
 *
 * <p>This class deliberately does not resolve a final colour. It describes how a
 * block participates in a map pixel: whether it is a base, an overlay or invisible,
 * which tint policy applies, and how much opacity an overlay contributes. Geometry
 * remains in {@link CaveStateClassifier}; colour sampling remains in
 * {@link MapTextureManager}. Keeping those concerns separate prevents the old
 * surface/cave classifiers from drifting apart.</p>
 */
public final class MapVisualClassifier {
    public enum Role {
        INVISIBLE,
        OPAQUE_BASE,
        TRANSPARENT_OVERLAY,
        FLUID_OVERLAY,
        EMISSIVE_OVERLAY
    }

    public enum SamplingPolicy {
        NONE,
        MAP_COLOR_FIRST,
        TEXTURE_FIRST
    }

    private static final MapVisualClassifier INSTANCE = new MapVisualClassifier();

    private volatile AtomicReferenceArray<VisualInfo> byStateId =
            new AtomicReferenceArray<>(4096);
    private final Map<BlockState, VisualInfo> fallback = new IdentityHashMap<>();
    private final CaveStateClassifier geometry = CaveStateClassifier.getInstance();

    private MapVisualClassifier() {
    }

    public static MapVisualClassifier getInstance() {
        return INSTANCE;
    }

    public VisualInfo info(BlockState state) {
        int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
        VisualInfo cached = null;
        if (stateId >= 0) {
            AtomicReferenceArray<VisualInfo> local = byStateId;
            if (stateId < local.length()) cached = local.get(stateId);
        } else {
            synchronized (this) {
                cached = fallback.get(state);
            }
        }
        if (cached != null) return cached;

        synchronized (this) {
            if (stateId >= 0) {
                ensureCapacityLocked(stateId + 1);
                cached = byStateId.get(stateId);
            } else {
                cached = fallback.get(state);
            }
            if (cached != null) return cached;
            VisualInfo resolved = resolve(state);
            if (stateId >= 0) byStateId.set(stateId, resolved);
            else fallback.put(state, resolved);
            return resolved;
        }
    }

    public VisualInfo info(String blockId) {
        try {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
            return info(block.defaultBlockState());
        } catch (Throwable ignored) {
            return VisualInfo.unknown(blockId);
        }
    }

    public Role role(BlockState state) {
        return info(state).role();
    }

    /**
     * Returns the authoritative tint policy. Static vanilla/tag rules are available
     * on worker threads; model tint-index inspection is added on the render thread.
     */
    public BlockTintPolicy tintPolicy(BlockState state) {
        VisualInfo info = info(state);
        if (info.fixedTextureColor()) return BlockTintPolicy.NONE;
        if (info.tintPolicy() != BlockTintPolicy.NONE) return info.tintPolicy();
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return BlockTintPolicy.NONE;
        return MapTextureManager.getInstance().resolveTintPolicy(info.blockId());
    }

    public boolean isVisibleSurface(BlockGetter level, BlockPos pos,
            BlockState state, boolean showFlowers) {
        VisualInfo info = info(state);
        if (info.role() == Role.INVISIBLE) return false;
        // The vanilla #flowers tag also contains canopy/substantial blocks such as
        // cherry leaves. Only the classifier's small decorative flower role is
        // controlled by the Flowers option.
        if (info.leaves()) return true;
        if (info.flower()) return showFlowers;
        if (info.fluid() || info.role() == Role.OPAQUE_BASE) return true;

        // A non-empty translucent shape (glass, waterlogged slabs, framed blocks)
        // can still be the visible surface. Empty decorations remain overlays only.
        return !geometry.isCollisionEmpty(level, pos, state);
    }

    public boolean isOverlay(BlockState state) {
        Role role = info(state).role();
        return role == Role.TRANSPARENT_OVERLAY
                || role == Role.FLUID_OVERLAY
                || role == Role.EMISSIVE_OVERLAY;
    }

    public boolean isInvisibleDecoration(BlockState state) {
        return info(state).invisibleDecoration();
    }

    public int overlayOpacity(BlockState state) {
        return info(state).overlayOpacity();
    }

    /** Fluid opacity is independent from the containing waterlogged block role. */
    public int fluidOverlayOpacity(BlockState state) {
        VisualInfo info = info(state);
        if (!info.fluid()) return 0;
        return info.water() ? 176 : (info.emissive() ? 214 : 192);
    }

    public synchronized void clear() {
        AtomicReferenceArray<VisualInfo> current = byStateId;
        for (int i = 0; i < current.length(); i++) current.set(i, null);
        fallback.clear();
    }

    private void ensureCapacityLocked(int required) {
        AtomicReferenceArray<VisualInfo> current = byStateId;
        if (required <= current.length()) return;
        int next = current.length();
        while (next < required) next = Math.max(next + 1, next << 1);
        AtomicReferenceArray<VisualInfo> expanded = new AtomicReferenceArray<>(next);
        for (int i = 0; i < current.length(); i++) expanded.set(i, current.get(i));
        byStateId = expanded;
    }

    private VisualInfo resolve(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        boolean air = state.isAir();
        boolean water = state.getFluidState().is(FluidTags.WATER);
        boolean fluid = !state.getFluidState().isEmpty();
        boolean leaves = state.is(BlockTags.LEAVES)
                || path.endsWith("_leaves") || path.contains("foliage");
        boolean flowerTag = state.is(BlockTags.FLOWERS);
        boolean grass = state.is(Blocks.GRASS_BLOCK)
                || path.contains("grass") || path.contains("fern");
        boolean wood = state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS);
        boolean fixedTextureColor = state.is(Blocks.CHERRY_LEAVES);
        boolean invisibleDecoration = isTorchLike(state, path);
        boolean emissive = state.getLightEmission() > 0;
        boolean collisionEmpty = geometry.info(state).collisionEmpty();
        boolean solidRender = geometry.isSolidRender(state);
        int lightBlock = geometry.lightBlock(state);
        boolean renderInvisible = !fluid
                && state.getRenderShape() == RenderShape.INVISIBLE;
        boolean explicitTransparency = isExplicitlyTransparent(state, path);
        boolean substantialVisual = leaves || solidRender || !collisionEmpty;
        // Gameplay tags are broader than map semantics. Only small, non-canopy,
        // non-substantial flower-tagged states are hidden by the Flowers option.
        boolean flower = flowerTag && !substantialVisual;

        BlockTintPolicy tintPolicy = BlockTintPolicy.NONE;
        if (!fixedTextureColor) {
            if (state.is(Blocks.SPRUCE_LEAVES)) tintPolicy = BlockTintPolicy.SPRUCE;
            else if (state.is(Blocks.BIRCH_LEAVES)) tintPolicy = BlockTintPolicy.BIRCH;
            else if (leaves || path.contains("vine")) tintPolicy = BlockTintPolicy.FOLIAGE;
            else if (grass) tintPolicy = BlockTintPolicy.GRASS;
        }

        boolean fluidOnly = fluid && collisionEmpty && !solidRender;
        Role role;
        if (air || renderInvisible || invisibleDecoration) role = Role.INVISIBLE;
        else if (fluidOnly) role = Role.FLUID_OVERLAY;
        else if (emissive && collisionEmpty) role = Role.EMISSIVE_OVERLAY;
        /* Xaero's cave writer only treats genuinely translucent/cutout material as
         * an overlay. Light blocking, solidRender and partial collision are not
         * equivalent to visual transparency: slabs, stairs, walls and many modded
         * floors must remain authoritative opaque cave floors. */
        else if (leaves || flower || state.canBeReplaced()
                || explicitTransparency || collisionEmpty) {
            role = Role.TRANSPARENT_OVERLAY;
        } else role = Role.OPAQUE_BASE;

        int opacity = switch (role) {
            case INVISIBLE -> 0;
            case OPAQUE_BASE -> 255;
            case FLUID_OVERLAY -> water ? 176 : (emissive ? 214 : 192);
            case EMISSIVE_OVERLAY -> 164;
            case TRANSPARENT_OVERLAY -> {
                if (fixedTextureColor) yield 176;
                if (leaves) yield 132;
                if (flower) yield 100;
                if (state.canBeReplaced()) yield 84;
                if (collisionEmpty) yield 104;
                yield Math.min(188, 112 + lightBlock * 4);
            }
        };

        SamplingPolicy sampling = role == Role.INVISIBLE
                ? SamplingPolicy.NONE
                : (fixedTextureColor || leaves || grass || fluid
                        ? SamplingPolicy.TEXTURE_FIRST
                        : SamplingPolicy.MAP_COLOR_FIRST);

        return new VisualInfo(blockId, role, tintPolicy, sampling,
                opacity, Math.max(0, Math.min(15, lightBlock)),
                leaves, flower, grass, wood, fluid, water, emissive,
                fixedTextureColor, invisibleDecoration);
    }

    private static boolean isExplicitlyTransparent(BlockState state, String path) {
        if (state.getBlock() instanceof TransparentBlock) return true;
        // Minecraft's pane/ice/web/vine families are not all subclasses of the
        // same transparent base class. Registry-name fallbacks also cover common
        // modded variants without demoting every partial block to an overlay.
        return path.equals("ice") || path.endsWith("_ice")
                || path.contains("glass") || path.endsWith("_pane")
                || path.equals("cobweb") || path.endsWith("_web")
                || path.equals("vine") || path.endsWith("_vine")
                || path.contains("transparent");
    }

    private static boolean isTorchLike(BlockState state, String path) {
        if (state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH)) return true;
        return path.equals("torch") || path.endsWith("_torch")
                || path.endsWith("_wall_torch");
    }

    public record VisualInfo(String blockId, Role role,
            BlockTintPolicy tintPolicy, SamplingPolicy samplingPolicy,
            int overlayOpacity, int lightBlock,
            boolean leaves, boolean flower, boolean grass, boolean wood,
            boolean fluid, boolean water, boolean emissive,
            boolean fixedTextureColor, boolean invisibleDecoration) {
        private static VisualInfo unknown(String blockId) {
            return new VisualInfo(blockId, Role.OPAQUE_BASE,
                    BlockTintPolicy.NONE, SamplingPolicy.TEXTURE_FIRST,
                    255, 15, false, false, false, false,
                    false, false, false, false, false);
        }
    }
}
