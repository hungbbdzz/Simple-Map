package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Resolves the independent cave type and Top-Y selection for each dimension. */
public final class CaveMode {
    public enum CaveType {
        OFF,
        LAYERED,
        FULL
    }

    private static final int AUTO_LAYER_STEP = 8;
    private static final int AUTO_LAYER_STABLE_TICKS = 4;
    private static final int AUTO_LAYER_FAST_SWITCH_DISTANCE = 16;
    private static final int LAYER_DEPTH = 32;
    /** Enter on the first confirmed roof tick. */
    private static final int AUTO_ENTER_TICKS = 1;
    /** A tiny clear-sky debounce prevents flicker at roof edges and on stairs. */
    private static final int AUTO_EXIT_TICKS = 3;
    private static int activeLayerY = Integer.MIN_VALUE;
    private static int pendingLayerY = Integer.MIN_VALUE;
    private static int pendingLayerTicks;
    private static long lastLayerEvaluationTick = Long.MIN_VALUE;
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

    public static boolean isActive(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return false;
        int permission = MapConfig.getEffectiveCaveMapMode();
        if (permission == 0) return false;
        if (hasManualTopY(mc)) return true;
        if (permission == 1) {
            return mc.level.dimensionType().hasCeiling() || updateAutomaticDetection(mc);
        }
        CaveType type = getCaveType(mc);
        if (type == CaveType.OFF) return false;
        return mc.level.dimensionType().hasCeiling() || updateAutomaticDetection(mc);
    }

    /** Y used by the scanner. Supports manual Top Y across cave views. */
    public static synchronized int getLayerY(Minecraft mc) {
        if (mc == null || mc.level == null || mc.player == null) return 0;
        Integer manual = getManualTopY(mc);
        if (manual != null && MapConfig.getEffectiveCaveMapMode() == 2) return clampY(mc.level, manual);

        String dimension = dimensionKey(mc);
        int playerY = clampY(mc.level, mc.player.blockPosition().getY() + 1);
        // Automatic layers remain aligned to 8-block bands, but the selected band
        // must be stable for a few ticks before a cache switch. This prevents a
        // staircase or jump at a band boundary from tearing down/rebuilding the
        // visible cave layer several times in quick succession.
        int automaticY = clampY(mc.level,
                Math.floorDiv(playerY + AUTO_LAYER_STEP / 2, AUTO_LAYER_STEP) * AUTO_LAYER_STEP);
        long gameTime = mc.level.getGameTime();
        if (!dimension.equals(activeDimension) || activeLayerY == Integer.MIN_VALUE) {
            activeDimension = dimension;
            activeLayerY = automaticY;
            pendingLayerY = Integer.MIN_VALUE;
            pendingLayerTicks = 0;
            lastLayerEvaluationTick = gameTime;
            modeRevision++;
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
                boolean fastSwitch = Math.abs(playerY - activeLayerY) >= AUTO_LAYER_FAST_SWITCH_DISTANCE;
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
        Integer previous = MANUAL_TOP_Y.put(dimensionKey(mc), clampY(mc.level, topY));
        if (previous == null || previous != clampY(mc.level, topY)) modeRevision++;
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
        activeLayerY = Integer.MIN_VALUE;
        pendingLayerY = Integer.MIN_VALUE;
        pendingLayerTicks = 0;
        lastLayerEvaluationTick = Long.MIN_VALUE;
        activeDimension = "";
        modeRevision++;
    }

    public static synchronized boolean isManualLayerActive(Minecraft mc) {
        return getCaveType(mc) == CaveType.LAYERED && hasManualTopY(mc);
    }

    public static boolean isFullView(Minecraft mc) {
        if (!isActive(mc)) return false;
        // Server AUTO is deliberately the restricted, automatic LAYERED view.
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

    private static synchronized boolean updateAutomaticDetection(Minecraft mc) {
        String dimension = dimensionKey(mc);
        AutoDetectionState state = AUTO_DETECTION.computeIfAbsent(dimension,
                ignored -> new AutoDetectionState());
        long gameTime = mc.level.getGameTime();
        if (state.lastTick == gameTime) return state.active;
        state.lastTick = gameTime;

        /*
         * Direct-roof detection is authoritative. The previous spatial-consensus
         * rule required multiple covered samples six blocks apart, so narrow mines
         * and stair tunnels frequently fell back to the surface map even though a
         * solid ceiling was directly above the player.
         */
        boolean wasActive = state.active;
        boolean covered = hasSolidRoofAbovePlayer(mc);
        if (covered) {
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
     * Returns true as soon as a real, motion-blocking, non-leaf block exists in
     * the vertical column above the player's eyes. This deliberately follows the
     * requested behaviour: roof present = cave layer; open column = surface map.
     *
     * Sky visibility is the fast gate. We still inspect the column so foliage,
     * fluids and non-blocking decoration do not activate cave mode.
     */
    private static boolean hasSolidRoofAbovePlayer(Minecraft mc) {
        Level level = mc.level;
        int x = (int) Math.floor(mc.player.getX());
        int z = (int) Math.floor(mc.player.getZ());
        int firstY = Math.max(level.getMinBuildHeight(), (int) Math.floor(mc.player.getEyeY()) + 1);
        BlockPos eye = new BlockPos(x, Math.max(level.getMinBuildHeight(), firstY - 1), z);
        if (level.canSeeSky(eye)) return false;

        // canSeeSky() is the authoritative fast test. Inspect the whole loaded
        // column only to reject foliage, fluids and decoration that should not be
        // treated as a cave roof. This avoids unreliable heightmap state in deep
        // modded dimensions and switches to the cave layer in the same tick.
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, firstY, z);
        int lastY = level.getMaxBuildHeight() - 1;
        for (int y = firstY; y <= lastY; y++) {
            cursor.setY(y);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir() || state.is(BlockTags.LEAVES) || !state.getFluidState().isEmpty()) continue;
            if (!state.getCollisionShape(level, cursor).isEmpty()) return true;
        }
        return false;
    }

    private static final class AutoDetectionState {
        private boolean active;
        private int coveredTicks;
        private int openTicks;
        private long lastTick = Long.MIN_VALUE;
    }
}
