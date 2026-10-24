package com.velorise.simplemap.client.cave;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.client.MapManager.MapConfig;

import com.velorise.simplemap.client.*;
import com.velorise.simplemap.client.MapManager.DimensionMapProfile;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager.CaveStateClassifier;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager.CaveRegion;
import com.velorise.simplemap.client.cave.CaveMode.CaveLayerBand.CaveView;
import com.velorise.simplemap.client.color.SurfaceColorizer.MapVisualClassifier;
import com.velorise.simplemap.client.renderer.*;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.velorise.simplemap.client.session.MapSessionManager.MapSession;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.PushReaction;

import java.util.*;

public final class CaveMode {
    public enum CaveType {
        OFF,
        LAYERED,
        FULL
    }

    public enum CavePageHoleReason {
        NO_MCA_RECORD,
        MCA_PRESENT_NOT_DECODED,
        SOURCE_DECODE_IN_FLIGHT,
        ARCHIVE_INDEXED_NOT_RESIDENT,
        ARCHIVE_MISSING,
        PROJECTION_NOT_READY,
        PROJECTION_STALE,
        LOADING_GENERATION_NOT_ADMITTED,
        HANDOFF_REJECTED,
        EXACT_GPU_NOT_READY,
        CURRENT_MISSING_PREVIOUS_AVAILABLE,
        CURRENT_AND_PREVIOUS_MISSING,
        GPU_BUDGET_DENIED
    }

    public static final int EXACT_PAGE_WORLD_SIZE = 64;

    public static float exactPagePixels(float scale) {
        return EXACT_PAGE_WORLD_SIZE * Math.max(0.0001f, scale);
    }

    public static boolean branchOnly(float scale, MapRequestLane lane) {
        return lane == MapRequestLane.FULLSCREEN && scale <= 0.25f;
    }

    private static final long ACTIVE_TRANSITION_NANOS = 750_000_000L;
    private static volatile long transitionStartedNanos;

    public static void beginTransition() {
        transitionStartedNanos = System.nanoTime();
    }

    public static void resetTransition() {
        transitionStartedNanos = 0L;
    }

    public static long transitionAgeNanos() {
        long started = transitionStartedNanos;
        return started == 0L ? Long.MAX_VALUE : Math.max(0L, System.nanoTime() - started);
    }

    public static boolean isTransitionActive() {
        return transitionAgeNanos() < ACTIVE_TRANSITION_NANOS;
    }

    public static long getTransitionForegroundBudget(long normal) {
        long safe = Math.max(0L, normal);
        return isTransitionActive() ? Math.min(safe, 300_000L) : safe;
    }

    private static final CaveStateClassifier CAVE_STATE_CLASSIFIER = CaveStateClassifier.getInstance();

    /*
     * Layered cave textures use a retained 16-block identity while preserving the
     * exact selected Top-Y, matching the caveStart/layer split. Old members of
     * a band remain fallback-only until the exact projection has been rebuilt.
     */
    /* Keeps the candidate cave start separate from the displayed cave map.
     * A one-second stable layer window prevents stair/elytra/player-Y jitter from
     * rebasing thousands of retained Layered projection products. */
    private static final int AUTO_LAYER_STABLE_TICKS = 20;
    /** Prevent stair steps and small jumps from oscillating around the active layer Y. */
    private static final int AUTO_LAYER_BOUNDARY_HYSTERESIS = 3;
    private static final int AUTO_LAYER_FAST_SWITCH_DISTANCE = 32;
    private static final int LAYER_DEPTH = 32;

    /**
     * The default cave-mode toggle timer is 1000 ms. Keep the same 20-tick
     * display latch while exposing the covered candidate to the Cave prewarmer
     * immediately. The renderer therefore stays on its stable Surface image while
     * Cave source tiles are prepared instead of flipping after only ~100 ms.
     */
    private static final int AUTO_ENTER_TICKS = 20;
    /** Never leave AUTO stuck on Surface if background GPU warmup is unavailable. */
    private static final int AUTO_ENTER_FORCE_TICKS = 40;
    /** Toggle hysteresis: brief openings must not drop the cave layer. */
    private static final int AUTO_EXIT_TICKS = 20;
    private static final int AUTO_CONTEXT_PROBE_INTERVAL = 4;
    /** The automatic cave check requires a coherent 3x3 roof neighbourhood. */
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
        // Cave permission, cave type and cave activation are kept separate.
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
        DimensionMapProfile viewedProfile = manager.getCurrentDimensionProfile();
        boolean viewedHasCeiling = viewedProfile != null ? viewedProfile.hasCeiling() : mc.level.dimensionType().hasCeiling();
        boolean viewedHasSkyLight = viewedProfile != null ? viewedProfile.hasSkyLight() : mc.level.dimensionType().hasSkyLight();
        if (viewedHasCeiling || !viewedHasSkyLight) {
            return true;
        }
        // Remote AUTO in an open-sky dimension (like Overworld):
        // if the user explicitly chose FULL cave mode (or LAYERED cave mode while viewing remotely),
        // honor that selection so remote Cave/Full Cave views work seamlessly!
        if (!acceptsLive) {
            CaveType explicit = CAVE_TYPES.get(dimensionKey(mc));
            if (explicit == null) {
                String stored = MapConfig.caveDimensionModes.get(dimensionKey(mc));
                if (stored != null) {
                    try { explicit = CaveType.valueOf(stored); } catch (IllegalArgumentException ignored) {}
                }
            }
            return explicit == CaveType.FULL || explicit == CaveType.LAYERED;
        }
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
        DimensionMapProfile viewedProfile = manager.getCurrentDimensionProfile();
        boolean viewedHasCeiling = viewedProfile != null ? viewedProfile.hasCeiling() : mc.level.dimensionType().hasCeiling();
        boolean viewedHasSkyLight = viewedProfile != null ? viewedProfile.hasSkyLight() : mc.level.dimensionType().hasSkyLight();
        if (viewedHasCeiling || !viewedHasSkyLight) {
            return true;
        }
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
            if (Math.abs(anchorY - activeLayerY) <= AUTO_LAYER_BOUNDARY_HYSTERESIS) {
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
                int dist = Math.abs(automaticY - activeLayerY);
                if (dist >= AUTO_LAYER_FAST_SWITCH_DISTANCE) {
                    activeLayerY = automaticY;
                    pendingLayerY = Integer.MIN_VALUE;
                    pendingLayerTicks = 0;
                    modeRevision++;
                } else if (pendingLayerY == automaticY) {
                    pendingLayerTicks++;
                    if (pendingLayerTicks >= AUTO_LAYER_STABLE_TICKS) {
                        activeLayerY = automaticY;
                        pendingLayerY = Integer.MIN_VALUE;
                        pendingLayerTicks = 0;
                        modeRevision++;
                    }
                } else {
                    pendingLayerY = automaticY;
                    pendingLayerTicks = 1;
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
        // The system stores one independent 0/1/2 cave type per dimension and uses
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
            beginTransition();
            if (type == CaveType.FULL) {
                CaveMapManager.getInstance().setActiveLayer(Integer.MIN_VALUE);
                CaveTextureManager.getInstance().onLayerActivated(Integer.MIN_VALUE);
            } else if (type == CaveType.LAYERED) {
                int layerY = getLayerY(mc);
                CaveMapManager.getInstance().setActiveLayer(layerY);
                CaveTextureManager.getInstance().onLayerActivated(layerY);
            }
            MapConfig.save();
        }
        if (type == CaveType.OFF) {
            // Cave type and cave-start selection are independent. Turning
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
        int max = level.dimensionType().hasCeiling() ? 122 : (level.getMaxBuildHeight() - 1);
        return Math.max(level.getMinBuildHeight(), Math.min(max, y));
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
        activeLayerY = Integer.MIN_VALUE;
        pendingLayerY = Integer.MIN_VALUE;
        pendingLayerTicks = 0;
        lastLayerEvaluationTick = Long.MIN_VALUE;
        automaticLayerHoldUntilTick = Long.MIN_VALUE;
        activeDimension = "";
        resetTransition();
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

            int caving = getCaving(
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.level);
            if (caving != CAVE_NOT_ACTIVE) {
                state.lastCovered = true;
                state.lastConfidence = 3;
                state.suggestedTopY = caving;
            } else {
                state.lastCovered = false;
                state.lastConfidence = 0;
                state.suggestedTopY = Integer.MIN_VALUE;
            }
        }

        boolean wasActive = state.active;
        if (state.lastCovered) {
            state.openTicks = 0;
            state.coveredTicks++;
            if (!state.active && state.coveredTicks >= AUTO_ENTER_TICKS) {
                state.active = true;
            }
        } else {
            state.coveredTicks = 0;
            state.openTicks++;
            if (state.openTicks >= AUTO_EXIT_TICKS) state.active = false;
        }
        if (wasActive != state.active) {
            modeRevision++;
            beginTransition();
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
     * Contextual cave resolution. It uses skylight as the cheap outdoor
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

        // The calculation caps caveStart at playerY + 4 even when the physical roof is higher.
        // The exact roof is used only when it lies closer to the player.
        int suggestedTop = Math.min(centerRoof, scanY + 3);
        return new CaveContext(coveredColumns == diameter * diameter,
                clampY(level, suggestedTop), 3);
    }

    private static int findBlockingRoofY(Level level, int x, int z, int firstY) {
        LevelChunk chunk = fullChunk(level, x >> 4, z >> 4);
        if (chunk == null) return Integer.MIN_VALUE;

        int upperBound = level.dimensionType().hasCeiling() ? 122 : (level.getMaxBuildHeight() - 1);
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
        return !isPassThrough(state)
                && (state.blocksMotion() || !CAVE_STATE_CLASSIFIER.isCollisionEmpty(level, pos, state));
    }

    private static LevelChunk fullChunk(Level level, int chunkX, int chunkZ) {
        try {
            ChunkAccess access = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            return access instanceof LevelChunk chunk ? chunk : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean shouldScanFromWorldTop(Level level) {
        return level != null && (level.dimensionType().hasCeiling() || !level.dimensionType().hasSkyLight());
    }

    public static final int CAVE_NOT_ACTIVE = Integer.MAX_VALUE;

    public static int getCaving(double playerX, double playerY, double playerZ, Level world) {
        if (world == null) return CAVE_NOT_ACTIVE;

        int worldBottomY = world.getMinBuildHeight();
        int worldTopY = world.dimensionType().hasCeiling() ? 122 : (world.getMaxBuildHeight() - 1);
        int y = (int) Math.floor(playerY) + 1;
        int defaultCaveStart = y + 3;

        if (y > worldTopY || y < worldBottomY) {
            return CAVE_NOT_ACTIVE;
        }

        int x = net.minecraft.util.Mth.floor(playerX);
        int z = net.minecraft.util.Mth.floor(playerZ);
        int roofRadius = 1;
        int roofDiameter = 1 + roofRadius * 2;
        int startX = x - roofRadius;
        int startZ = z - roofRadius;

        boolean ignoringHeightmaps = world.dimensionType().hasCeiling() || !world.dimensionType().hasSkyLight();
        int bottom = y;
        int top = Integer.MAX_VALUE;
        int potentialResult = defaultCaveStart;
        LevelChunk prevChunk = null;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int o = 0; o < roofDiameter; ++o) {
            columnLoop:
            for (int p = 0; p < roofDiameter; ++p) {
                int currentX = startX + o;
                int currentZ = startZ + p;
                mutablePos.set(currentX, y, currentZ);

                LevelChunk bchunk = fullChunk(world, currentX >> 4, currentZ >> 4);
                if (bchunk == null) {
                    return CAVE_NOT_ACTIVE;
                }

                if (!ignoringHeightmaps) {
                    int skyLight = world.getBrightness(LightLayer.SKY, mutablePos);
                    if (skyLight >= 15) {
                        return CAVE_NOT_ACTIVE;
                    }
                    int insideX = currentX & 15;
                    int insideZ = currentZ & 15;
                    bottom = y;
                    top = bchunk.getHeight(Heightmap.Types.WORLD_SURFACE, insideX, insideZ);
                } else if (bchunk != prevChunk) {
                    LevelChunkSection[] sections = bchunk.getSections();
                    if (sections.length == 0) return CAVE_NOT_ACTIVE;

                    int playerSection = (y - worldBottomY) >> 4;
                    if (playerSection < 0) playerSection = 0;
                    boolean foundSomething = false;
                    bottom = y;
                    for (int i = playerSection; i < sections.length; ++i) {
                        LevelChunkSection sec = sections[i];
                        if (sec == null || sec.hasOnlyAir()) continue;
                        if (!foundSomething) {
                            bottom = Math.max(bottom, worldBottomY + (i << 4));
                            foundSomething = true;
                        }
                        top = worldBottomY + (i << 4) + 15;
                    }
                    if (!foundSomething) return CAVE_NOT_ACTIVE;
                    prevChunk = bchunk;
                }

                if (top < worldBottomY) return CAVE_NOT_ACTIVE;
                if (top > worldTopY) top = worldTopY;

                for (int currentY = bottom; currentY <= top; ++currentY) {
                    mutablePos.setY(currentY);
                    BlockState state = bchunk.getBlockState(mutablePos);
                    if (isPassThrough(state)) continue;

                    if (o == roofRadius && p == roofRadius) {
                        potentialResult = Math.min(currentY, defaultCaveStart);
                    }
                    continue columnLoop;
                }

                return CAVE_NOT_ACTIVE;
            }
        }

        return potentialResult;
    }

    /**
     * Blocks that do NOT count as an opaque cave ceiling when detecting whether the
     * player is inside a cave (transparentCache).
     */
    public static boolean isPassThrough(BlockState state) {
        if (state == null || state.isAir()) return true;
        if (state.getPistonPushReaction() == PushReaction.DESTROY) return true;
        if (!state.getFluidState().isEmpty() || state.getBlock() instanceof LiquidBlock) return true;
        if (state.is(BlockTags.LEAVES)) return true;
        if (state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT)) return true;
        if (state.canBeReplaced()) return true;
        if (state.getBlock() instanceof TransparentBlock) return true;

        MapVisualClassifier.VisualInfo visual = MapVisualClassifier.getInstance().info(state);
        if (visual.leaves() || visual.flower()) return true;
        return visual.role() != MapVisualClassifier.Role.OPAQUE_BASE;
    }

    /**
     * Unified predicate for whether a block counts as solid underground terrain
     * that transitions Full Cave's {@code shouldEnterGround} from {@code true} to {@code false}.
     * Used by both live chunk scanning ({@link ChunkScanner}) and Anvil {@code .mca}
     * slicing ({@link CaveTextureManager}) so both pipelines produce identical results.
     */
    private static final byte STATE_FLAG_SOLID_ROOF = 1;
    private static final byte STATE_FLAG_OPEN_HIDE_FLOWERS = 2;
    private static final byte STATE_FLAG_OPEN_SHOW_FLOWERS = 4;
    private static final byte STATE_FLAG_SHORT_FLOOR = 8;
    private static final java.util.concurrent.ConcurrentHashMap<BlockState, Byte> CAVE_STATE_FLAGS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>(2048);

    private static byte resolveCaveStateFlags(BlockState state) {
        Byte cached = CAVE_STATE_FLAGS_CACHE.get(state);
        if (cached != null) return cached;
        byte flags = 0;
        if (computeUndergroundSolidRoof(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state)) {
            flags |= STATE_FLAG_SOLID_ROOF;
        }
        if (computeCaveOpenOrInvisible(state, false)) {
            flags |= STATE_FLAG_OPEN_HIDE_FLOWERS;
        }
        if (computeCaveOpenOrInvisible(state, true)) {
            flags |= STATE_FLAG_OPEN_SHOW_FLOWERS;
        }
        if (computeShortFloorBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state)) {
            flags |= STATE_FLAG_SHORT_FLOOR;
        }
        CAVE_STATE_FLAGS_CACHE.put(state, flags);
        return flags;
    }

    private static boolean computeUndergroundSolidRoof(BlockGetter level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        if (state.ignitedByLava() || state.canBeReplaced()
                || state.getPistonPushReaction() == PushReaction.DESTROY
                || state.getBlock() instanceof TransparentBlock) {
            return false;
        }
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)
                || state.is(BlockTags.PLANKS) || state.is(BlockTags.FENCES)
                || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.RAILS)
                || state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)
                || state.is(BlockTags.BANNERS) || state.is(BlockTags.SIGNS)
                || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)
                || state.is(BlockTags.WART_BLOCKS) || state.is(BlockTags.ICE)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.MUSHROOM_STEM)
                || state.is(Blocks.MANGROVE_ROOTS)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.SHROOMLIGHT)
                || state.is(Blocks.MELON)
                || state.is(Blocks.PUMPKIN)
                || state.is(Blocks.CARVED_PUMPKIN)
                || state.is(Blocks.JACK_O_LANTERN)
                || state.is(Blocks.HAY_BLOCK)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.SCAFFOLDING)
                || state.is(Blocks.BARRIER)
                || state.is(Blocks.LIGHT)) {
            return false;
        }
        MapVisualClassifier.VisualInfo visual = MapVisualClassifier.getInstance().info(state);
        if (visual.leaves() || visual.flower()
                || visual.role() != MapVisualClassifier.Role.OPAQUE_BASE) {
            return false;
        }
        return state.blocksMotion()
                || !CAVE_STATE_CLASSIFIER.isCollisionEmpty(level, pos, state);
    }

    public static boolean isUndergroundSolidRoof(BlockGetter level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) return false;
        return (resolveCaveStateFlags(state) & STATE_FLAG_SOLID_ROOF) != 0;
    }

    public static boolean isUndergroundSolidRoof(BlockState state) {
        if (state == null || state.isAir()) return false;
        return (resolveCaveStateFlags(state) & STATE_FLAG_SOLID_ROOF) != 0;
    }

    private static boolean computeCaveOpenOrInvisible(BlockState state, boolean showFlowers) {
        if (state == null || state.isAir()) return true;
        if (!state.getFluidState().isEmpty()) return false;
        if (state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT)
                || state.is(Blocks.STRUCTURE_VOID)) return true;

        MapVisualClassifier.VisualInfo visual = MapVisualClassifier.getInstance().info(state);
        if (visual.role() == MapVisualClassifier.Role.INVISIBLE
                || visual.invisibleDecoration()) {
            return true;
        }
        if (visual.flower()) {
            return !showFlowers;
        }

        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (path.equals("short_grass") || path.equals("grass")
                || path.equals("tall_grass") || path.equals("fern")
                || path.equals("large_fern") || path.equals("dead_bush")
                || path.equals("vine") || path.endsWith("_vines")
                || path.endsWith("_vines_plant") || path.equals("hanging_roots")
                || path.equals("spore_blossom") || path.equals("cobweb")
                || path.endsWith("_web") || path.equals("sugar_cane")
                || path.equals("bamboo_sapling") || path.equals("kelp")
                || path.equals("kelp_plant") || path.equals("seagrass")
                || path.equals("tall_seagrass") || path.equals("tripwire")) {
            return true;
        }
        return false;
    }

    /**
     * Returns true if {@code state} should be treated as open air / invisible decoration
     * during cave column traversal (setting {@code underair = true} and passing through
     * to expose the floor beneath). Cached in O(1) per BlockState.
     */
    public static boolean isCaveOpenOrInvisible(BlockState state, boolean showFlowers) {
        if (state == null || state.isAir()) return true;
        byte mask = showFlowers ? STATE_FLAG_OPEN_SHOW_FLOWERS : STATE_FLAG_OPEN_HIDE_FLOWERS;
        return (resolveCaveStateFlags(state) & mask) != 0;
    }

    private static boolean computeShortFloorBlock(BlockGetter level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) return false;
        if (CAVE_STATE_CLASSIFIER.isCollisionEmpty(level, pos, state)) return true;
        return CAVE_STATE_CLASSIFIER.collisionTop(level, pos, state) < 0.25f;
    }

    /**
     * Returns true if {@code state} is a low-profile/thin floor block (carpet, rail,
     * redstone wire, snow layer, pressure plate, repeater, glow lichen, etc.) whose
     * effective height for slope shading should be adjusted by -1 (short-block logic).
     */
    public static boolean isShortFloorBlock(BlockGetter level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) return false;
        return (resolveCaveStateFlags(state) & STATE_FLAG_SHORT_FLOOR) != 0;
    }

    public static boolean isShortFloorBlock(BlockState state) {
        if (state == null || state.isAir()) return false;
        return (resolveCaveStateFlags(state) & STATE_FLAG_SHORT_FLOOR) != 0;
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

    /** Pure guard proving that local roof samples cannot classify a whole dimension. */
    public static final class CaveDimensionEvidence {
        private CaveDimensionEvidence() {
        }

        public static boolean classifyEnclosed(int sampleCount, int coveredCount,
                int strongCoveredCount, int openCount) {
            return false;
        }
    }

    /**
     * Stable identity and projection for layered cave textures.
     */
    public static final class CaveLayerBand {
        public static final int HEIGHT = 16;

        /** Logical projection rendered from the shared vertical cave archive. */
        public enum CaveView {
            LAYERED,
            FULL
        }

        private CaveLayerBand() {
        }

        public static int key(CaveView view, int topY) {
            if (view == CaveView.FULL) return Integer.MIN_VALUE;
            return Math.floorDiv(topY, HEIGHT) * HEIGHT;
        }

        public static int projectionTopY(CaveView view, int requestedTopY) {
            if (view == CaveView.FULL) return Integer.MIN_VALUE;
            return requestedTopY;
        }

        public static int lowerY(int bandKey) {
            return bandKey;
        }

        public static int upperY(int bandKey) {
            return bandKey + HEIGHT - 1;
        }

        public static boolean same(CaveView view, int firstTopY, int secondTopY) {
            return key(view, firstTopY) == key(view, secondTopY);
        }
    }
}