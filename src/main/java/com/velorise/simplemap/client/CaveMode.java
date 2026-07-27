package com.velorise.simplemap.client;

import com.velorise.simplemap.client.cave.CaveContextCache;
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
     * Layered cave textures use retained 16-block bands. AUTO still moves in
     * 8-block steps so it can follow a floor smoothly inside a tall cavern, but an
     * exact Top-Y change inside the same band patches the existing page/LOD tree
     * instead of abandoning it.
     */
    private static final int AUTO_LAYER_STEP = 8;
    private static final int AUTO_LAYER_STABLE_TICKS = 8;
    private static final int AUTO_LAYER_FAST_SWITCH_DISTANCE = 32;
    private static final int LAYER_DEPTH = 32;

    /** Enter quickly after a coherent roof/floor context is observed. */
    private static final int AUTO_ENTER_TICKS = 2;
    /** Xaero-style toggle hysteresis: brief openings must not drop the cave layer. */
    private static final int AUTO_EXIT_TICKS = 20;
    private static final int AUTO_CONTEXT_PROBE_INTERVAL = 4;
    private static final int AUTO_ROOF_NEARBY_RADIUS = 2;
    private static final int AUTO_ROOF_NEARBY_REQUIRED = 2;
    private static final int AUTO_FLOOR_HEADROOM = 4;

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
        int permission = MapConfig.getEffectiveCaveMapMode();
        if (permission == 0) return false;
        if (hasManualTopY(mc)) return true;

        boolean persistentCaveDimension = isPersistentCaveDimension(mc.level);
        if (permission == 1) {
            return persistentCaveDimension || updateAutomaticDetection(mc);
        }
        CaveType type = getCaveType(mc);
        if (type == CaveType.OFF) return false;
        return persistentCaveDimension || updateAutomaticDetection(mc);
    }

    /** Y used by the scanner. Supports manual Top Y across cave views. */
    public static synchronized int getLayerY(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return 0;
        Integer manual = getManualTopY(mc);
        if (manual != null && MapConfig.getEffectiveCaveMapMode() == 2) return clampY(mc.level, manual);

        String dimension = dimensionKey(mc);
        int playerY = clampY(mc.level, mc.player.blockPosition().getY() + 1);
        int anchorY = resolveAutomaticAnchorY(mc, playerY);
        int automaticY = clampY(mc.level,
                Math.floorDiv(anchorY + AUTO_LAYER_STEP / 2, AUTO_LAYER_STEP) * AUTO_LAYER_STEP);
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
        return manual == null ? getLayerY(mc) : clampY(mc.level, manual);
    }

    public static synchronized void setManualLayer(Minecraft mc, int topY) {
        if (MapConfig.getEffectiveCaveMapMode() != 2 || mc == null || mc.level == null) return;
        int clamped = clampY(mc.level, topY);
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
        if (mc == null || mc.level == null) return CaveType.FULL;
        return CAVE_TYPES.getOrDefault(dimensionKey(mc), CaveType.LAYERED);
    }

    public static synchronized void setCaveType(Minecraft mc, CaveType type) {
        if (MapConfig.getEffectiveCaveMapMode() != 2 || mc == null || mc.level == null || type == null) return;
        CaveType previous = CAVE_TYPES.put(dimensionKey(mc), type);
        if (previous != type) modeRevision++;
        if (type == CaveType.OFF) {
            MANUAL_TOP_Y.remove(dimensionKey(mc));
            CaveMapManager.getInstance().deactivate();
        }
    }

    public static synchronized void cycleCaveType(Minecraft mc) {
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
        modeRevision++;
    }

    public static synchronized boolean isManualLayerActive(Minecraft mc) {
        return getCaveType(mc) == CaveType.LAYERED && hasManualTopY(mc);
    }

    public static boolean isFullView(Minecraft mc) {
        if (!isActive(mc)) return false;
        if (MapConfig.getEffectiveCaveMapMode() != 2) return false;
        return getCaveType(mc) == CaveType.FULL;
    }

    private static synchronized Integer getManualTopY(Minecraft mc) {
        if (mc == null || mc.level == null) return null;
        return MANUAL_TOP_Y.get(dimensionKey(mc));
    }

    private static String dimensionKey(Minecraft mc) {
        return mc.level.dimension().location().toString();
    }

    /**
     * Nether-style and no-skylight dimensions are cave contexts even when a modded
     * DimensionType does not set hasCeiling(). This is the common failure mode for
     * dedicated cave dimensions while the player is floating in a large chamber.
     */
    private static boolean isPersistentCaveDimension(Level level) {
        return level.dimensionType().hasCeiling() || !level.dimensionType().hasSkyLight();
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
                        int confidence = isPersistentCaveDimension(mc.level)
                                ? 3 : (context.covered() ? 2 : 1);
                        return new CaveContextCache.Result(context.covered(),
                                context.suggestedTopY(), confidence);
                    });
            if (cached != null) {
                state.lastCovered = cached.covered();
                state.suggestedTopY = cached.suggestedTopY();
            }
        }

        boolean wasActive = state.active;
        if (isPersistentCaveDimension(mc.level)) {
            state.active = true;
            state.coveredTicks = AUTO_ENTER_TICKS;
            state.openTicks = 0;
        } else if (state.lastCovered) {
            state.openTicks = 0;
            state.coveredTicks++;
            if (state.coveredTicks >= AUTO_ENTER_TICKS) state.active = true;
        } else {
            state.coveredTicks = 0;
            state.openTicks++;
            if (state.openTicks >= AUTO_EXIT_TICKS) state.active = false;
        }
        if (wasActive != state.active) modeRevision++;
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
        int eyeY = clampY(level, (int) Math.floor(mc.player.getEyeY()));
        int firstRoofY = Math.min(level.getMaxBuildHeight() - 1, eyeY + 1);
        boolean persistent = isPersistentCaveDimension(level);

        BlockPos eye = new BlockPos(x, eyeY, z);
        if (!persistent && level.dimensionType().hasSkyLight()
                && level.getBrightness(LightLayer.SKY, eye) >= 15
                && level.canSeeSky(eye)) {
            return new CaveContext(false, eyeY + 1);
        }

        int[][] offsets = {
                { AUTO_ROOF_NEARBY_RADIUS, 0 },
                { -AUTO_ROOF_NEARBY_RADIUS, 0 },
                { 0, AUTO_ROOF_NEARBY_RADIUS },
                { 0, -AUTO_ROOF_NEARBY_RADIUS }
        };
        int directRoof = Integer.MIN_VALUE;
        int nearbyRoofCount = 0;
        if (!persistent) {
            directRoof = findBlockingRoofY(level, x, z, firstRoofY);
            for (int[] offset : offsets) {
                if (findBlockingRoofY(level, x + offset[0], z + offset[1], firstRoofY)
                        != Integer.MIN_VALUE) {
                    nearbyRoofCount++;
                }
            }
        }

        boolean covered = persistent || directRoof != Integer.MIN_VALUE
                || nearbyRoofCount >= AUTO_ROOF_NEARBY_REQUIRED;

        /*
         * A player hovering high above the local cave floor should not select an empty
         * 32-block band. Anchor the band to the nearest substantial floor in the same
         * small neighbourhood while retaining several blocks of headroom.
         */
        int nearestFloor = findNearestFloorY(level, x, z, eyeY - 1);
        for (int[] offset : offsets) {
            nearestFloor = Math.max(nearestFloor,
                    findNearestFloorY(level, x + offset[0], z + offset[1], eyeY - 1));
        }
        int suggestedTop = eyeY + 1;
        if (nearestFloor != Integer.MIN_VALUE
                && suggestedTop - nearestFloor >= LAYER_DEPTH) {
            suggestedTop = nearestFloor + LAYER_DEPTH - AUTO_FLOOR_HEADROOM;
        }
        return new CaveContext(covered, clampY(level, suggestedTop));
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

    private static int findNearestFloorY(Level level, int x, int z, int startY) {
        if (startY < level.getMinBuildHeight()) return Integer.MIN_VALUE;
        LevelChunk chunk = fullChunk(level, x >> 4, z >> 4);
        if (chunk == null) return Integer.MIN_VALUE;

        int lowerBound = level.getMinBuildHeight();
        int top = Math.min(startY, level.getMaxBuildHeight() - 1);
        LevelChunkSection[] sections = chunk.getSections();
        int firstSection = Math.min(sections.length - 1, level.getSectionIndex(top));
        int lastSection = Math.max(0, level.getSectionIndex(lowerBound));
        int minimumSectionY = Math.floorDiv(level.getMinBuildHeight(), 16);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, top, z);
        for (int sectionIndex = firstSection; sectionIndex >= lastSection; sectionIndex--) {
            LevelChunkSection section = sections[sectionIndex];
            if (section == null || section.hasOnlyAir()) continue;
            int sectionBottom = (minimumSectionY + sectionIndex) << 4;
            int fromY = Math.min(top, sectionBottom + 15);
            int toY = Math.max(lowerBound, sectionBottom);
            for (int y = fromY; y >= toY; y--) {
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
                || !state.getFluidState().isEmpty()) return false;
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

    private record CaveContext(boolean covered, int suggestedTopY) {
    }

    private static final class AutoDetectionState {
        private boolean active;
        private boolean lastCovered;
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
