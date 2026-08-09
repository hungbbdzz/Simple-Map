package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveContextCache;
import com.velorise.simplemap.client.cave.CaveLayerBand;
import com.velorise.simplemap.client.cave.CaveModeTransitionPolicy;
import com.velorise.simplemap.client.cave.CavePipeline;
import com.velorise.simplemap.client.cave.CaveView;
import com.velorise.simplemap.client.cave.UnifiedCaveTextureManager;
import com.velorise.simplemap.client.cave.CaveStateClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;

import java.util.HashMap;
import java.util.Map;

/** Resolves the independent cave type and Top-Y selection for each dimension. */
public final class CaveMode {
    public enum CaveType {
        OFF,
        LAYERED,
        FULL
    }

    private static final CaveStateClassifier CAVE_STATE_CLASSIFIER = CaveStateClassifier.getInstance();

    /*
     * Layered cave textures use a retained 16-block identity while preserving the
     * exact selected Top-Y, matching Xaero's caveStart/layer split. Old members of
     * a band remain fallback-only until the exact projection has been rebuilt.
     */
    /* Xaero keeps the candidate cave start separate from the displayed cave map.
     * A one-second stable layer window prevents stair/elytra/player-Y jitter from
     * rebasing thousands of retained Layered projection products. */
    private static final int AUTO_LAYER_STABLE_TICKS = 20;
    /** Prevent stair steps and small jumps from oscillating around a band edge. */
    private static final int AUTO_LAYER_BOUNDARY_HYSTERESIS = 3;
    private static final int AUTO_LAYER_FAST_SWITCH_DISTANCE = 32;
    private static final int LAYER_DEPTH = 32;

    /**
     * Xaero's default cave-mode toggle timer is 1000 ms. Keep the same 20-tick
     * display latch while exposing the covered candidate to the Cave prewarmer
     * immediately. The renderer therefore stays on its stable Surface image while
     * Cave source tiles are prepared instead of flipping after only ~100 ms.
     */
    private static final int AUTO_ENTER_TICKS = 20;
    /** Never leave AUTO stuck on Surface if background GPU warmup is unavailable. */
    private static final int AUTO_ENTER_FORCE_TICKS = 40;
    /** Xaero-style toggle hysteresis: brief openings must not drop the cave layer. */
    private static final int AUTO_EXIT_TICKS = 20;
    private static final int AUTO_CONTEXT_PROBE_INTERVAL = 4;
    /** Xaero's automatic cave check requires a coherent 3x3 roof neighbourhood. */
    private static final int AUTO_ROOF_RADIUS = 1;

    private static int activeLayerY = Integer.MIN_VALUE;
    private static int pendingLayerY = Integer.MIN_VALUE;
    private static int pendingLayerTicks;
    private static long lastLayerEvaluationTick = Long.MIN_VALUE;
    private static long automaticLayerHoldUntilTick = Long.MIN_VALUE;
    private static String activeDimension = "";
    private static long modeRevision = 1L;
    /** Missing entry means Top Y: AUTO. */
    private static final Map<String, Integer> MANUAL_TOP_Y = new HashMap<>();
    private static final Map<String, CaveType> CAVE_TYPES = new HashMap<>();
    private static final Map<String, AutoDetectionState> AUTO_DETECTION = new HashMap<>();

    private CaveMode() {
    }

    public static synchronized long getRevision() {
        return modeRevision;
    }

    /**
     * Holds the current automatic Top-Y briefly while teleport chunk/light packets
     * settle. Manual Top-Y is never modified.
     */
    public static synchronized void holdAutomaticLayer(Minecraft mc, int ticks) {
        if (mc == null || mc.level == null || mc.player == null || ticks <= 0
                || getManualTopY(mc) != null) return;
        String dimension = dimensionKey(mc);
        if (!dimension.equals(activeDimension) || activeLayerY == Integer.MIN_VALUE) return;
        long gameTime = mc.level.getGameTime();
        automaticLayerHoldUntilTick = Math.max(automaticLayerHoldUntilTick,
                gameTime + ticks);
        pendingLayerY = Integer.MIN_VALUE;
        pendingLayerTicks = 0;
    }

    public static boolean isActive(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return false;
        // Xaero keeps cave permission, cave type and cave activation separate.
        // OFF disables cave rendering. CAVE/FULL use the same AUTO/manual cave-start
        // decision; the type only decides whether that active cave is layered or full.
        if (MapConfig.getEffectiveCaveMapMode() == 0
                || getCaveType(mc) == CaveType.OFF) return false;
        MapManager manager = MapManager.getInstance();
        boolean acceptsLive = manager.acceptsLiveLevel(mc.level);
        // Portal handoff can expose the new ClientLevel one render frame before
        // MapManager changes its source namespace. Do not let a manual cave setting
        // render the previous dimension under the new live session. An intentional
        // remote map view is different: its explicit override may use manual Cave.
        if (manager.isViewingLiveDimension() && !acceptsLive) return false;
        if (hasManualTopY(mc)) return true;
        // Remote AUTO has no player-local roof context and remains Surface.
        if (!acceptsLive) return false;
        return updateAutomaticDetection(mc);
    }

    /**
     * True while automatic roof detection is a stable Cave candidate even if the
     * one-second display latch has not elapsed yet. Source writers may use this to
     * prewarm a compact near-player Cave set; renderers must continue using
     * {@link #isActive(Minecraft)} so preparation never creates a visible half-Cave
     * transition.
     */
    public static synchronized boolean shouldPrepare(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return false;
        if (MapConfig.getEffectiveCaveMapMode() == 0
                || getCaveType(mc) == CaveType.OFF) return false;
        MapManager manager = MapManager.getInstance();
        boolean acceptsLive = manager.acceptsLiveLevel(mc.level);
        if (manager.isViewingLiveDimension() && !acceptsLive) return false;
        if (hasManualTopY(mc)) return true;
        if (!acceptsLive) return false;
        updateAutomaticDetection(mc);
        AutoDetectionState state = AUTO_DETECTION.get(dimensionKey(mc));
        return state != null && (state.active || state.lastCovered);
    }

    /** Y used by the scanner. Supports manual Top Y across cave views. */
    public static synchronized int getLayerY(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return 0;
        // Full Cave is a column projection and must not acquire a new cache/render
        // revision merely because the player climbed or fell in the Nether.
        if (isFullView(mc)) return Integer.MIN_VALUE;
        Integer manual = getManualTopY(mc);
        if (manual != null && MapConfig.getEffectiveCaveMapMode() != 0) {
            return clampViewedY(mc,
                    CaveLayerBand.projectionTopY(CaveView.LAYERED, manual));
        }

        String dimension = dimensionKey(mc);
        if (!MapManager.getInstance().acceptsLiveLevel(mc.level)) {
            // Remote AUTO has no meaningful player Y. Use one immutable vanilla
            // cave band so viewport revisits do not continuously rebase layers.
            int remoteTopY = 63;
            int stableY = clampViewedY(mc,
                    CaveLayerBand.projectionTopY(CaveView.LAYERED, remoteTopY));
            if (!dimension.equals(activeDimension) || activeLayerY != stableY) {
                activeDimension = dimension;
                activeLayerY = stableY;
                pendingLayerY = Integer.MIN_VALUE;
                pendingLayerTicks = 0;
                modeRevision++;
            }
            return activeLayerY;
        }
        int playerY = clampY(mc.level, mc.player.blockPosition().getY() + 1);
        int anchorY = resolveAutomaticAnchorY(mc, playerY);
        int automaticY = clampY(mc.level,
                CaveLayerBand.projectionTopY(CaveView.LAYERED, anchorY));
        if (dimension.equals(activeDimension) && activeLayerY != Integer.MIN_VALUE) {
            int activeBand = CaveLayerBand.key(CaveView.LAYERED, activeLayerY);
            int lowerSwitchY = CaveLayerBand.lowerY(activeBand)
                    - AUTO_LAYER_BOUNDARY_HYSTERESIS;
            int upperSwitchY = CaveLayerBand.upperY(activeBand)
                    + AUTO_LAYER_BOUNDARY_HYSTERESIS;
            if (anchorY >= lowerSwitchY && anchorY <= upperSwitchY) {
                automaticY = activeLayerY;
            }
        }
        long gameTime = mc.level.getGameTime();
        if (!dimension.equals(activeDimension) || activeLayerY == Integer.MIN_VALUE) {
            activeDimension = dimension;
            activeLayerY = automaticY;
            pendingLayerY = Integer.MIN_VALUE;
            pendingLayerTicks = 0;
            lastLayerEvaluationTick = gameTime;
            automaticLayerHoldUntilTick = Long.MIN_VALUE;
            modeRevision++;
        } else if (gameTime < automaticLayerHoldUntilTick) {
            lastLayerEvaluationTick = gameTime;
            pendingLayerY = Integer.MIN_VALUE;
            pendingLayerTicks = 0;
        } else if (lastLayerEvaluationTick != gameTime) {
            lastLayerEvaluationTick = gameTime;
            if (automaticY == activeLayerY) {
                pendingLayerY = Integer.MIN_VALUE;
                pendingLayerTicks = 0;
            } else {
                if (pendingLayerY == automaticY) pendingLayerTicks++;
                else {
                    pendingLayerY = automaticY;
                    pendingLayerTicks = 1;
                }
                boolean fastSwitch = Math.abs(anchorY - activeLayerY) >= AUTO_LAYER_FAST_SWITCH_DISTANCE;
                if (fastSwitch || pendingLayerTicks >= AUTO_LAYER_STABLE_TICKS) {
                    activeLayerY = automaticY;
                    pendingLayerY = Integer.MIN_VALUE;
                    pendingLayerTicks = 0;
                    modeRevision++;
                }
            }
        }
        return activeLayerY;
    }

    public static synchronized int getSelectedTopY(Minecraft mc) {
        Integer manual = getManualTopY(mc);
        return manual == null ? getLayerY(mc)
                : clampViewedY(mc,
                        CaveLayerBand.projectionTopY(CaveView.LAYERED, manual));
    }

    public static synchronized void setManualLayer(Minecraft mc, int topY) {
        if (MapConfig.getEffectiveCaveMapMode() == 0 || mc == null || mc.level == null) return;
        int clamped = clampViewedY(mc,
                CaveLayerBand.projectionTopY(CaveView.LAYERED, topY));
        Integer previous = MANUAL_TOP_Y.put(dimensionKey(mc), clamped);
        if (previous == null || previous != clamped) modeRevision++;
    }

    public static synchronized void setAutoTopY(Minecraft mc) {
        if (mc == null || mc.level == null) return;
        if (MANUAL_TOP_Y.remove(dimensionKey(mc)) != null) modeRevision++;
    }

    public static synchronized boolean hasManualTopY(Minecraft mc) {
        return getManualTopY(mc) != null;
    }

    public static synchronized CaveType getCaveType(Minecraft mc) {
        if (mc == null || mc.level == null) return CaveType.LAYERED;
        String dimension = dimensionKey(mc);
        CaveType explicit = CAVE_TYPES.get(dimension);
        if (explicit != null) return explicit;
        String stored = MapConfig.caveDimensionModes.get(dimension);
        if (stored != null) {
            try {
                CaveType parsed = CaveType.valueOf(stored);
                CAVE_TYPES.put(dimension, parsed);
                return parsed;
            } catch (IllegalArgumentException ignored) {
                MapConfig.caveDimensionModes.remove(dimension);
            }
        }
        // Xaero stores one independent 0/1/2 cave type per dimension and uses
        // Layered/CAVE as the default type. Dimension classification may optimize
        // the FULL projection internally, but it never rewrites this UI state.
        CAVE_TYPES.put(dimension, CaveType.LAYERED);
        return CaveType.LAYERED;
    }

    public static synchronized void setCaveType(Minecraft mc, CaveType type) {
        if (MapConfig.getEffectiveCaveMapMode() == 0 || mc == null || mc.level == null || type == null) return;
        String dimension = dimensionKey(mc);
        CaveType previous = CAVE_TYPES.put(dimension, type);
        String previousStored = MapConfig.caveDimensionModes.put(dimension, type.name());
        if (previous != type || !type.name().equals(previousStored)) {
            modeRevision++;
            CaveModeTransitionPolicy.begin();
            CaveView nextView = switch (type) {
                case LAYERED -> CaveView.LAYERED;
                case FULL -> CaveView.FULL;
                case OFF -> null;
            };
            UnifiedCaveTextureManager.getInstance().onModeChanged(nextView);
            MapConfig.save();
        }
        if (type == CaveType.OFF) {
            // Cave type and cave-start selection are independent in Xaero. Turning
            // the view OFF must not erase the selected Top Y; switching back to
            // CAVE or FULL restores the previous AUTO/manual cave-start state.
            CaveMapManager.getInstance().deactivate();
        }
    }

    public static synchronized void cycleCaveType(Minecraft mc) {
        if (mc == null || mc.level == null) return;
        CaveType next = switch (getCaveType(mc)) {
            case OFF -> CaveType.LAYERED;
            case LAYERED -> CaveType.FULL;
            case FULL -> CaveType.OFF;
        };
        setCaveType(mc, next);
    }

    public static int clampY(Level level, int y) {
        return Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight() - 1, y));
    }

    public static int getScanMinimum(Level level, int topY) {
        return Math.max(level.getMinBuildHeight(), clampY(level, topY) - LAYER_DEPTH + 1);
    }

    public static int getScanMaximum(Level level, int topY) {
        return clampY(level, topY);
    }

    /** Build limits of the map dimension currently being viewed, not necessarily live. */
    public static int getViewedScanMinimum(Minecraft mc, int topY) {
        DimensionMapProfile profile = MapManager.getInstance().getCurrentDimensionProfile();
        int maximum = profile == null ? clampY(mc.level, topY) : profile.clampY(topY);
        int minimumBound = profile == null ? mc.level.getMinBuildHeight() : profile.minY();
        return Math.max(minimumBound, maximum - LAYER_DEPTH + 1);
    }

    public static int getViewedScanMaximum(Minecraft mc, int topY) {
        DimensionMapProfile profile = MapManager.getInstance().getCurrentDimensionProfile();
        return profile == null ? clampY(mc.level, topY) : profile.clampY(topY);
    }

    public static synchronized void clearManualLayer() {
        MANUAL_TOP_Y.clear();
        CAVE_TYPES.clear();
        AUTO_DETECTION.clear();
        CaveContextCache.getInstance().reset();
        activeLayerY = Integer.MIN_VALUE;
        pendingLayerY = Integer.MIN_VALUE;
        pendingLayerTicks = 0;
        lastLayerEvaluationTick = Long.MIN_VALUE;
        automaticLayerHoldUntilTick = Long.MIN_VALUE;
        activeDimension = "";
        CaveModeTransitionPolicy.reset();
        modeRevision++;
    }

    public static synchronized boolean isManualLayerActive(Minecraft mc) {
        return getCaveType(mc) == CaveType.LAYERED && hasManualTopY(mc);
    }

    public static boolean isFullView(Minecraft mc) {
        return isActive(mc) && getCaveType(mc) == CaveType.FULL;
    }

    private static synchronized Integer getManualTopY(Minecraft mc) {
        if (mc == null || mc.level == null) return null;
        return MANUAL_TOP_Y.get(dimensionKey(mc));
    }

    private static String dimensionKey(Minecraft mc) {
        String viewed = MapManager.getInstance().getCurrentDimensionResourceId();
        return viewed == null || viewed.isBlank()
                ? mc.level.dimension().location().toString() : viewed;
    }

    private static int clampViewedY(Minecraft mc, int y) {
        String dimension = dimensionKey(mc);
        DimensionMapProfile profile = MapManager.getInstance()
                .getCurrentDimensionProfile();
        if (profile != null && dimension.equals(profile.resourceId())) {
            return profile.clampY(y);
        }
        return clampY(mc.level, y);
    }

    private static synchronized int resolveAutomaticAnchorY(Minecraft mc, int fallbackY) {
        updateAutomaticDetection(mc);
        AutoDetectionState state = AUTO_DETECTION.get(dimensionKey(mc));
        if (state == null || state.suggestedTopY == Integer.MIN_VALUE) return fallbackY;
        return clampY(mc.level, state.suggestedTopY);
    }

    private static synchronized boolean updateAutomaticDetection(Minecraft mc) {
        String dimension = dimensionKey(mc);
        AutoDetectionState state = AUTO_DETECTION.computeIfAbsent(dimension,
                ignored -> new AutoDetectionState());
        long gameTime = mc.level.getGameTime();
        if (state.lastTick == gameTime) return state.active;
        state.lastTick = gameTime;

        int playerX = (int) Math.floor(mc.player.getX());
        int playerZ = (int) Math.floor(mc.player.getZ());
        int playerY = (int) Math.floor(mc.player.getEyeY());

        boolean movedColumn = state.lastPlayerX != playerX || state.lastPlayerZ != playerZ;
        boolean movedBand = Math.abs(state.lastPlayerY - playerY) >= 3;
        boolean cacheExpired = gameTime - state.lastProbeTick >= AUTO_CONTEXT_PROBE_INTERVAL;
        if (movedColumn || movedBand || cacheExpired) {
            state.lastPlayerX = playerX;
            state.lastPlayerY = playerY;
            state.lastPlayerZ = playerZ;
            state.lastProbeTick = gameTime;
            int chunkX = playerX >> 4;
            int chunkZ = playerZ >> 4;
            int playerYBand = Math.floorDiv(playerY, 8);
            long mutationToken = GeneratedChunkIndex.getInstance()
                    .neighbourhoodEpoch(mc.level, chunkX, chunkZ, 1);
            CaveContextCache.Result cached = CaveContextCache.getInstance().resolve(
                    mc.level, chunkX, chunkZ, playerYBand, mutationToken, gameTime,
                    () -> {
                        CaveContext context = detectCaveContext(mc);
                        return new CaveContextCache.Result(context.covered(),
                                context.suggestedTopY(), context.confidence());
                    });
            if (cached != null) {
                state.lastCovered = cached.covered();
                state.lastConfidence = cached.confidence();
                state.suggestedTopY = cached.suggestedTopY();
            }
        }

        boolean wasActive = state.active;
        if (state.lastCovered) {
            state.openTicks = 0;
            state.coveredTicks++;
            if (!state.active && state.coveredTicks >= AUTO_ENTER_TICKS) {
                /*
                 * Time hysteresis alone is not a loading barrier. Keep Surface as
                 * loadedCaving until the centre Cave source set is actually warm;
                 * otherwise the exact/branch/GPU pipeline starts from cold on the
                 * same frame the AUTO detector flips presentation.
                 */
                boolean warm = CavePipeline.getInstance().autoWarmupReady(mc);
                if (warm || state.coveredTicks >= AUTO_ENTER_FORCE_TICKS) {
                    state.active = true;
                } else {
                    MapDebugRecorder recorder = MapDebugRecorder.getInstance();
                    String eventKey = "CAVE_AUTO_WAITING_WARM_SOURCE:" + dimension;
                    if (recorder.shouldEmitEvent(eventKey, 250L)) {
                        recorder.event("CAVE_AUTO_WAITING_WARM_SOURCE",
                                "covered_ticks=" + state.coveredTicks
                                        + " suggested_top_y=" + state.suggestedTopY);
                    }
                }
            }
        } else {
            state.coveredTicks = 0;
            state.openTicks++;
            if (state.openTicks >= AUTO_EXIT_TICKS) state.active = false;
        }
        if (wasActive != state.active) {
            modeRevision++;
            CaveModeTransitionPolicy.begin();
            MapDebugRecorder.getInstance().event("CAVE_AUTO_DISPLAY_LATCH",
                    "active=" + state.active
                            + " covered_ticks=" + state.coveredTicks
                            + " open_ticks=" + state.openTicks
                            + " confidence=" + state.lastConfidence
                            + " suggested_top_y=" + state.suggestedTopY);
        }
        return state.active;
    }

    /**
     * Xaero-like cave context resolution. It uses skylight as the cheap outdoor
     * rejection, then scans only non-empty chunk sections all the way to the real
     * heightmap/section ceiling. There is no fixed 96-block cap, so enormous caverns
     * and tall cave dimensions remain detectable while the player is airborne.
     */
    private static CaveContext detectCaveContext(Minecraft mc) {
        Level level = mc.level;
        int x = (int) Math.floor(mc.player.getX());
        int z = (int) Math.floor(mc.player.getZ());
        int scanY = clampY(level, (int) Math.floor(mc.player.getY()) + 1);
        boolean hardCeiling = level.dimensionType().hasCeiling();

        BlockPos eye = new BlockPos(x, scanY, z);
        if (!hardCeiling && level.dimensionType().hasSkyLight()
                && level.getBrightness(LightLayer.SKY, eye) >= 15
                && level.canSeeSky(eye)) {
            return new CaveContext(false, scanY + 3, 0);
        }

        int centerRoof = Integer.MIN_VALUE;
        int coveredColumns = 0;
        int diameter = AUTO_ROOF_RADIUS * 2 + 1;
        for (int dz = -AUTO_ROOF_RADIUS; dz <= AUTO_ROOF_RADIUS; dz++) {
            for (int dx = -AUTO_ROOF_RADIUS; dx <= AUTO_ROOF_RADIUS; dx++) {
                int roof = findBlockingRoofY(level, x + dx, z + dz, scanY);
                if (roof == Integer.MIN_VALUE) {
                    return new CaveContext(false, scanY + 3,
                            Math.min(3, coveredColumns));
                }
                coveredColumns++;
                if (dx == 0 && dz == 0) centerRoof = roof;
            }
        }

        // Xaero caps caveStart at playerY + 4 even when the physical roof is higher.
        // The exact roof is used only when it lies closer to the player.
        int suggestedTop = Math.min(centerRoof, scanY + 3);
        return new CaveContext(coveredColumns == diameter * diameter,
                clampY(level, suggestedTop), 3);
    }

    private static int findBlockingRoofY(Level level, int x, int z, int firstY) {
        LevelChunk chunk = fullChunk(level, x >> 4, z >> 4);
        if (chunk == null) return Integer.MIN_VALUE;

        int upperBound = level.getMaxBuildHeight() - 1;
        if (level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()) {
            try {
                upperBound = Math.min(upperBound,
                        Math.max(firstY, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)));
            } catch (Throwable ignored) {
                // Section traversal below remains authoritative.
            }
        }
        if (upperBound < firstY) return Integer.MIN_VALUE;

        LevelChunkSection[] sections = chunk.getSections();
        int firstSection = Math.max(0, level.getSectionIndex(firstY));
        int lastSection = Math.min(sections.length - 1, level.getSectionIndex(upperBound));
        int minimumSectionY = Math.floorDiv(level.getMinBuildHeight(), 16);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, firstY, z);
        for (int sectionIndex = firstSection; sectionIndex <= lastSection; sectionIndex++) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            int sectionBottom = (minimumSectionY + sectionIndex) << 4;
            int fromY = Math.max(firstY, sectionBottom);
            int toY = Math.min(upperBound, sectionBottom + 15);
            for (int y = fromY; y <= toY; y++) {
                cursor.setY(y);
                BlockState state = level.getBlockState(cursor);
                if (isSubstantialBarrier(level, cursor, state)) return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isSubstantialBarrier(Level level,
            BlockPos.MutableBlockPos pos, BlockState state) {
        if (state == null || state.isAir() || state.is(BlockTags.LEAVES)
                || !state.getFluidState().isEmpty() || state.canBeReplaced()
                || state.getPistonPushReaction() == PushReaction.DESTROY) return false;
        MapVisualClassifier.VisualInfo visual =
                MapVisualClassifier.getInstance().info(state);
        if (visual.leaves() || visual.flower()
                || visual.role() != MapVisualClassifier.Role.OPAQUE_BASE) return false;
        if (state.blocksMotion()) return true;
        return !CAVE_STATE_CLASSIFIER.isCollisionEmpty(level, pos, state);
    }

    private static LevelChunk fullChunk(Level level, int chunkX, int chunkZ) {
        try {
            ChunkAccess access = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return access instanceof LevelChunk chunk ? chunk : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record CaveContext(boolean covered, int suggestedTopY, int confidence) {
    }

    private static final class AutoDetectionState {
        private boolean active;
        private boolean lastCovered;
        private int lastConfidence;
        private int coveredTicks;
        private int openTicks;
        private int suggestedTopY = Integer.MIN_VALUE;
        private int lastPlayerX = Integer.MIN_VALUE;
        private int lastPlayerY = Integer.MIN_VALUE;
        private int lastPlayerZ = Integer.MIN_VALUE;
        private long lastTick = Long.MIN_VALUE;
        private long lastProbeTick = Long.MIN_VALUE;
    }
}
