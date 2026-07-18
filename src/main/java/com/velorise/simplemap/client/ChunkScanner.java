package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ChunkScanner {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int IMMEDIATE_RADIUS = 8;
    private static final int FULL_CAVE_IMMEDIATE_RADIUS = 6;
    private static final int REGION_CHUNK_SIZE = 32;
    private static final int UNKNOWN_SURFACE_SCAN_ATTEMPTS = 12;
    // Do not restart an in-progress chunk every time the player walks a few blocks.
    private static final int NORMAL_REANCHOR_DISTANCE = 24;
    private static final int BASE_VERTICAL_COST = 24;
    // Increase per-tick nano budgets to allow broader scanning for larger explored areas
    private static final long SURFACE_SCAN_BUDGET_NANOS = 5_000_000L;
    private static final long CAVE_SCAN_BUDGET_NANOS = 7_000_000L;
    private static final long MAP_SCREEN_SCAN_BUDGET_NANOS = 8_000_000L;
    private static final int IMMEDIATE_COLUMNS_PER_TICK = 64;

    private static final ChunkScanner INSTANCE = new ChunkScanner();

    public static ChunkScanner getInstance() {
        return INSTANCE;
    }

    private final Random random = new Random();
    /** NORMAL completes loaded chunks from nearest to farthest. */
    private final Map<String, NormalScanState> normalScans = new HashMap<>();
    private final Map<Integer, int[]> chunkOrders = new HashMap<>();
    /** Each view keeps an adaptive reveal radius while the player is moving. */
    private final Map<String, MovementState> movementStates = new HashMap<>();
    private final Map<String, Integer> immediateCursors = new HashMap<>();
    private final Map<String, Integer> urgentChunkCursors = new HashMap<>();
    private final Map<String, ViewportScanState> viewportScans = new HashMap<>();
    private volatile long forcedRescanUntilNanos;
    private long observedCaveModeRevision = Long.MIN_VALUE;

    private ChunkScanner() {
    }

    /** Reset scan state so a fresh world starts with a clean slate */
    public void reset() {
        normalScans.clear();
        movementStates.clear();
        immediateCursors.clear();
        urgentChunkCursors.clear();
        viewportScans.clear();
        forcedRescanUntilNanos = 0L;
        observedCaveModeRevision = Long.MIN_VALUE;
    }

    /**
     * Scans a time-bounded amount of work per tick. Loaded chunks are completed
     * from the player outward; the legacy random-dot reveal path is no longer used.
     */
    public void scanAroundPlayerUniform(Minecraft mc, int maxRadius) {
        if (mc.level == null || mc.player == null)
            return;

        boolean caveActive = CaveMode.isActive(mc);
        synchronizeCaveModeRevision();
        if (caveActive) {
            scanCaveAroundPlayerUniform(mc, maxRadius);
            return;
        }

        int centerBlockX = (int) Math.floor(mc.player.getX());
        int centerBlockZ = (int) Math.floor(mc.player.getZ());
        int effectiveRadius = getAdaptiveRadius(mc, maxRadius, viewKey(mc, false, false, 0));
        int radiusSq = effectiveRadius * effectiveRadius;
        long deadline = System.nanoTime() + scanBudget(mc, false);

        // 1. Scan immediate small 13x13 circular region (radius = 6) around the player
        // instantly to capture direct player interactions (building/mining)
        scanImmediate(mc, centerBlockX, centerBlockZ, IMMEDIATE_RADIUS,
                0, false, false, deadline);
        scanUrgentLoadedChunks(mc, 0, false, false, deadline);

        int samplesTarget = resolveSampleTarget(false);
        scanNearestChunks(mc, effectiveRadius, samplesTarget, 0, false, false, deadline);

        // Light changes more often than terrain color. Refresh a small independent
        // sample of already explored, loaded positions without enabling full rescans.
        int lightSamples = Math.min(128, Math.max(16, samplesTarget / 8));
        for (int i = 0; i < lightSamples && System.nanoTime() < deadline; i++) {
            int dx = random.nextInt(2 * effectiveRadius + 1) - effectiveRadius;
            int dz = random.nextInt(2 * effectiveRadius + 1) - effectiveRadius;
            if (dx * dx + dz * dz <= radiusSq) {
                scanLightIfLoaded(mc, centerBlockX + dx, centerBlockZ + dz);
            }
        }
    }

    private void synchronizeCaveModeRevision() {
        long revision = CaveMode.getRevision();
        if (observedCaveModeRevision == revision) return;
        observedCaveModeRevision = revision;
        normalScans.clear();
        immediateCursors.clear();
        urgentChunkCursors.clear();
        viewportScans.clear();
        forcedRescanUntilNanos = Math.max(forcedRescanUntilNanos,
                System.nanoTime() + 1_500_000_000L);
    }

    private void scanColumnIfLoaded(Minecraft mc, int blockX, int blockZ) {
        if (mc.level.hasChunk(blockX >> 4, blockZ >> 4)) {
            MapBlockData data = buildBlockData(mc.level, blockX, blockZ);
            if (data != null) {
                // Don't overwrite existing data with empty scan
                MapBlockData existing = MapManager.getInstance().getBlockData(blockX, blockZ);
                if (existing == null || !data.isEmpty()) {
                    MapManager.getInstance().setBlockData(blockX, blockZ, data);
                }
            }
            int surfaceY = data != null && !data.isEmpty() ? data.topY
                    : getHighestY(mc.level, blockX, blockZ);
            updateSurfaceLight(mc.level, blockX, surfaceY, blockZ);
        }
    }

    private void scanCaveAroundPlayerUniform(Minecraft mc, int maxRadius) {
        int layerY = CaveMode.getLayerY(mc);
        boolean fullView = CaveMode.isFullView(mc);
        if (!fullView) CaveMapManager.getInstance().setActiveLayer(layerY);
        int effectiveRadius = getAdaptiveRadius(mc, maxRadius, viewKey(mc, true, fullView, layerY));
        int centerBlockX = (int) Math.floor(mc.player.getX());
        int centerBlockZ = (int) Math.floor(mc.player.getZ());
        int innerRadius = fullView ? FULL_CAVE_IMMEDIATE_RADIUS : IMMEDIATE_RADIUS;
        long deadline = System.nanoTime() + scanBudget(mc, true);
        scanImmediate(mc, centerBlockX, centerBlockZ, innerRadius,
                layerY, true, fullView, deadline);
        scanUrgentLoadedChunks(mc, layerY, true, fullView, deadline);

        // Downward projections can inspect many blocks per pixel. Keep their total
        // block-state work close to a predictable surface-scan budget.
        int samplesTarget = Math.max(256, resolveSampleTarget(true));
        int projectionMinimum = fullView
                ? mc.level.getMinBuildHeight()
                : CaveMode.getScanMinimum(mc.level, layerY);
        int projectionDepth = CaveMode.getScanMaximum(mc.level, layerY)
                - projectionMinimum + 1;
        long scaledTarget = (long) samplesTarget * BASE_VERTICAL_COST
                / Math.max(1, projectionDepth);
        samplesTarget = (int) Math.min(Integer.MAX_VALUE, Math.max(24L, scaledTarget));
        // Always scan nearest chunks for cave view as well. Random probing removed.
        scanNearestChunks(mc, effectiveRadius, samplesTarget, layerY, true, fullView, deadline);
    }

    /**
     * Cycles through the live area instead of re-reading the whole 13x13 disc every
     * client tick. Direct edits still converge quickly, while deep cave columns can
     * no longer monopolize a frame.
     */
    private void scanImmediate(Minecraft mc, int centerX, int centerZ, int radius,
            int layerY, boolean cave, boolean fullView, long deadline) {
        String key = viewKey(mc, cave, fullView, layerY) + ":immediate";
        int diameter = radius * 2 + 1;
        int area = diameter * diameter;
        int cursor = immediateCursors.getOrDefault(key, 0);
        int visited = 0;
        int processed = 0;
        while (visited < area && processed < IMMEDIATE_COLUMNS_PER_TICK
                && System.nanoTime() < deadline) {
            int index = cursor++ % area;
            int dx = index % diameter - radius;
            int dz = index / diameter - radius;
            visited++;
            if (dx * dx + dz * dz > radius * radius) continue;
            int blockX = centerX + dx;
            int blockZ = centerZ + dz;
            if (cave && dx * dx + dz * dz > 1 && !shouldRescanExplored()
                    && isExplored(blockX, blockZ, cave, fullView)) continue;
            scanPixel(mc, blockX, blockZ, layerY, cave, fullView);
            processed++;
        }
        immediateCursors.put(key, cursor % area);
    }

    

    /** Completes the player chunk first, plus the chunk in the movement direction. */
    private void scanUrgentLoadedChunks(Minecraft mc, int layerY, boolean cave,
            boolean fullView, long deadline) {
        int playerChunkX = ((int) Math.floor(mc.player.getX())) >> 4;
        int playerChunkZ = ((int) Math.floor(mc.player.getZ())) >> 4;
        int directionX = (int) Math.signum(mc.player.getDeltaMovement().x);
        int directionZ = (int) Math.signum(mc.player.getDeltaMovement().z);
        scanUrgentChunk(mc, playerChunkX, playerChunkZ, layerY, cave, fullView, deadline);
        if (!cave && System.nanoTime() < deadline && (directionX != 0 || directionZ != 0)) {
            scanUrgentChunk(mc, playerChunkX + directionX, playerChunkZ + directionZ,
                    layerY, false, false, deadline);
        }
    }

    private void scanUrgentChunk(Minecraft mc, int chunkX, int chunkZ, int layerY,
            boolean cave, boolean fullView, long deadline) {
        if (!mc.level.hasChunk(chunkX, chunkZ)) return;
        String key = viewKey(mc, cave, fullView, layerY) + ":urgent:" + chunkX + ',' + chunkZ;
        int cursor = urgentChunkCursors.getOrDefault(key, 0);
        int maximum = cave ? 96 : 256;
        int processed = 0;
        int visited = 0;
        while (visited < 256 && processed < maximum && System.nanoTime() < deadline) {
            int pixel = cursor++ & 255;
            visited++;
            int blockX = (chunkX << 4) + (pixel & 15);
            int blockZ = (chunkZ << 4) + (pixel >>> 4);
            if (!shouldRescanExplored() && isExplored(blockX, blockZ, cave, fullView)) continue;
            scanPixel(mc, blockX, blockZ, layerY, cave, fullView);
            processed++;
        }
        urgentChunkCursors.put(key, cursor & 255);
        if (urgentChunkCursors.size() > 256) urgentChunkCursors.clear();
    }

    /**
     * Called by the full-screen map. It progressively fills every client-loaded
     * chunk intersecting the viewport, rather than only a circle around the player.
     */
    public void scanVisibleArea(Minecraft mc, double minX, double maxX,
            double minZ, double maxZ) {
        if (mc == null || mc.level == null || mc.player == null) return;
        boolean cave = CaveMode.isActive(mc);
        synchronizeCaveModeRevision();
        boolean fullView = cave && CaveMode.isFullView(mc);
        int layerY = cave ? CaveMode.getLayerY(mc) : 0;
        if (cave && !fullView) CaveMapManager.getInstance().setActiveLayer(layerY);

        int minChunkX = ((int) Math.floor(minX)) >> 4;
        int maxChunkX = ((int) Math.floor(maxX)) >> 4;
        int minChunkZ = ((int) Math.floor(minZ)) >> 4;
        int maxChunkZ = ((int) Math.floor(maxZ)) >> 4;
        // Only client-loaded chunks can be scanned. Intersecting with the player's
        // live chunk window avoids spending the viewport budget walking thousands
        // of unloaded chunks when the map is heavily zoomed out.
        int playerChunkX = ((int) Math.floor(mc.player.getX())) >> 4;
        int playerChunkZ = ((int) Math.floor(mc.player.getZ())) >> 4;
        int liveRadius = Math.max(2, mc.options.renderDistance().get() + 4);
        minChunkX = Math.max(minChunkX, playerChunkX - liveRadius);
        maxChunkX = Math.min(maxChunkX, playerChunkX + liveRadius);
        minChunkZ = Math.max(minChunkZ, playerChunkZ - liveRadius);
        maxChunkZ = Math.min(maxChunkZ, playerChunkZ + liveRadius);
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) return;
        String key = viewKey(mc, cave, fullView, layerY) + ":viewport";
        ViewportScanState state = viewportScans.computeIfAbsent(key, ignored -> new ViewportScanState());
        int expectedChunkCount = Math.max(1, maxChunkX - minChunkX + 1)
                * Math.max(1, maxChunkZ - minChunkZ + 1);
        if (!state.matches(minChunkX, maxChunkX, minChunkZ, maxChunkZ)
                || state.chunkOrder.length != expectedChunkCount) {
            state.reset(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        }

        int width = Math.max(1, maxChunkX - minChunkX + 1);
        int height = Math.max(1, maxChunkZ - minChunkZ + 1);
        long rawChunkCount = (long) width * height;
        int chunkCount = (int) Math.min(rawChunkCount, 65_536L);
        long deadline = System.nanoTime() + MAP_SCREEN_SCAN_BUDGET_NANOS;
        int visitedPixels = 0;
        int pixelsPerChunkBurst = cave ? 48 : 256;
        while (visitedPixels < 32_768 && chunkCount > 0 && System.nanoTime() < deadline) {
            if (state.chunkCursor >= chunkCount) {
                state.chunkCursor = 0;
                state.pixelCursor = 0;
            }
            int chunkOffset = state.chunkOrder[state.chunkCursor];
            int chunkX = minChunkX + chunkOffset % width;
            int chunkZ = minChunkZ + chunkOffset / width;
            int regionAnchorX = chunkX << 4;
            int regionAnchorZ = chunkZ << 4;
            preloadKnownRegionForChunk(regionAnchorX, regionAnchorZ, cave, fullView);
            if (!mc.level.hasChunk(chunkX, chunkZ)) {
                state.chunkCursor++;
                state.pixelCursor = 0;
                continue;
            }
            int burstVisited = 0;
            while (burstVisited < pixelsPerChunkBurst && state.pixelCursor < 256
                    && visitedPixels < 32_768 && System.nanoTime() < deadline) {
                int pixel = state.pixelCursor++;
                visitedPixels++;
                burstVisited++;
                int blockX = (chunkX << 4) + (pixel & 15);
                int blockZ = (chunkZ << 4) + (pixel >>> 4);
                if (!shouldRescanExplored() && isExplored(blockX, blockZ, cave, fullView)) continue;
                scanPixel(mc, blockX, blockZ, layerY, cave, fullView);
            }
            if (state.pixelCursor >= 256) {
                state.pixelCursor = 0;
                state.chunkCursor++;
            }
        }
    }

    /** Refresh now reuses the normal chunk pipeline instead of a separate circle scan. */
    public void requestRefresh(Minecraft mc) {
        forcedRescanUntilNanos = System.nanoTime() + 2_000_000_000L;
        normalScans.clear();
        viewportScans.clear();
        urgentChunkCursors.clear();
        if (mc != null && mc.level != null && mc.player != null) {
            int renderDistance = mc.options.renderDistance().get();
            int radius = Math.max(16, renderDistance * 16);
            scanAroundPlayerUniform(mc, radius);
        }
    }

    private void scanNearestChunks(Minecraft mc, int radius, int samplesTarget,
            int layerY, boolean cave, boolean fullView, long deadline) {
        int playerX = (int) Math.floor(mc.player.getX());
        int playerZ = (int) Math.floor(mc.player.getZ());
        String key = viewKey(mc, cave, fullView, layerY);
        NormalScanState state = normalScans.computeIfAbsent(key, ignored -> new NormalScanState());
        long deltaX = (long) playerX - state.anchorX;
        long deltaZ = (long) playerZ - state.anchorZ;
        if (state.radius < 0
                || deltaX * deltaX + deltaZ * deltaZ > NORMAL_REANCHOR_DISTANCE * NORMAL_REANCHOR_DISTANCE) {
            state.anchorX = playerX;
            state.anchorZ = playerZ;
            state.anchorChunkX = playerX >> 4;
            state.anchorChunkZ = playerZ >> 4;
            state.chunkIndex = 0;
            state.pixelIndex = 0;
        }
        // Radius can contract while sprinting and expand again while idle without
        // restarting the nearest-chunk cursor on every small animation step.
        state.radius = radius;

        int[] order = getChunkOrder(radius);
        int processed = 0;
        int inspected = 0;
        int inspectionLimit = samplesTarget == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : Math.max(samplesTarget * 16, 512);
        while (processed < samplesTarget && inspected < inspectionLimit && order.length > 0
                && System.nanoTime() < deadline) {
            if (state.chunkIndex >= order.length) {
                state.chunkIndex = 0;
                state.pixelIndex = 0;
                state.pass++;
            }
            int packed = order[state.chunkIndex];
            int chunkDx = (short) (packed >>> 16);
            int chunkDz = (short) packed;
            int chunkX = state.anchorChunkX + chunkDx;
            int chunkZ = state.anchorChunkZ + chunkDz;
            if (!mc.level.hasChunk(chunkX, chunkZ)) {
                state.chunkIndex++;
                state.pixelIndex = 0;
                inspected += 16;
                continue;
            }

            int pixel = state.pixelIndex++;
            if (state.pixelIndex >= 256) {
                state.pixelIndex = 0;
                state.chunkIndex++;
            }
            inspected++;
            int blockX = (chunkX << 4) + (pixel & 15);
            int blockZ = (chunkZ << 4) + (pixel >>> 4);
            preloadKnownRegionForChunk(blockX, blockZ, cave, fullView);
            if (isExplored(blockX, blockZ, cave, fullView) && !shouldRescanExplored()) continue;
            scanPixel(mc, blockX, blockZ, layerY, cave, fullView);
            processed++;
        }
    }

    private boolean isExplored(int blockX, int blockZ, boolean cave, boolean fullView) {
        if (!cave) {
            MapBlockData data = MapManager.getInstance().getBlockData(blockX, blockZ);
            if (data == null || data.isEmpty()) return false;
            // Version-1 water pixels had no floor height and stored the water block
            // itself. Treat them as incomplete so they migrate lazily on sight.
            return !(data.isFluid() && !data.isGlowing() && data.floorY == data.topY);
        }
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        if (fullView) {
            FullCaveMapManager.FullRegion region = FullCaveMapManager.getInstance().getRegion(rx, rz, false);
            return region != null && region.isLoaded()
                    && region.getColor(blockX & 511, blockZ & 511) != 0;
        }
        CaveRegion region = CaveMapManager.getInstance().getRegion(rx, rz, false);
        return region != null && region.isLoaded()
                && region.getColor(blockX & 511, blockZ & 511) != 0;
    }

    private void scanPixel(Minecraft mc, int blockX, int blockZ,
            int layerY, boolean cave, boolean fullView) {
        if (cave) scanCavePixelIfLoaded(mc.level, blockX, layerY, blockZ, fullView);
        else scanColumnIfLoaded(mc, blockX, blockZ);
    }

    private int getAdaptiveRadius(Minecraft mc, int configuredRadius, String key) {
        // The previous speed-based contraction was the main reason flying into a
        // loaded chunk produced an empty minimap edge. CPU time is already bounded
        // by deadlines, so keep the full configured chunk radius at every speed.
        movementStates.computeIfAbsent(key, ignored -> new MovementState()).factor = 1.0f;
        return Math.max(16, configuredRadius);
    }

    private long scanBudget(Minecraft mc, boolean cave) {
        if (mc.screen instanceof MapScreen) return MAP_SCREEN_SCAN_BUDGET_NANOS;
        return cave ? CAVE_SCAN_BUDGET_NANOS : SURFACE_SCAN_BUDGET_NANOS;
    }

    private int resolveSampleTarget(boolean cave) {
        int configured = Math.max(1_000, MapConfig.scanPointsPerTick);
        // 100k is the new AUTO/MAX setting. The nano deadline, not a random-dot
        // counter, becomes the real safety limit.
        if (configured >= 100_000) return Integer.MAX_VALUE;
        return cave ? Math.max(256, configured / 2) : configured;
    }

    private boolean shouldRescanExplored() {
        return MapConfig.alwaysRescanExplored || System.nanoTime() < forcedRescanUntilNanos;
    }

    private String viewKey(Minecraft mc, boolean cave, boolean fullView, int layerY) {
        String dimension = mc.level.dimension().location().toString();
        return dimension + ":" + (cave ? (fullView ? "full" : "layer:" + layerY) : "surface");
    }

    private static final class NormalScanState {
        private int anchorX;
        private int anchorZ;
        private int anchorChunkX;
        private int anchorChunkZ;
        private int radius = -1;
        private int chunkIndex;
        private int pixelIndex;
        private long pass;
    }

    private static final class MovementState {
        private float factor = 1.0f;
    }

    private static final class ViewportScanState {
        private int minChunkX;
        private int maxChunkX;
        private int minChunkZ;
        private int maxChunkZ;
        private int chunkCursor;
        private int pixelCursor;
        private int[] chunkOrder = new int[0];

        private boolean matches(int minX, int maxX, int minZ, int maxZ) {
            return minChunkX == minX && maxChunkX == maxX
                    && minChunkZ == minZ && maxChunkZ == maxZ;
        }

        private void reset(int minX, int maxX, int minZ, int maxZ) {
            minChunkX = minX;
            maxChunkX = maxX;
            minChunkZ = minZ;
            maxChunkZ = maxZ;
            chunkCursor = 0;
            pixelCursor = 0;
            int width = Math.max(1, maxX - minX + 1);
            int height = Math.max(1, maxZ - minZ + 1);
            int centerX = (minX + maxX) >> 1;
            int centerZ = (minZ + maxZ) >> 1;
            List<Integer> ordered = new ArrayList<>(width * height);
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    ordered.add((z - minZ) * width + (x - minX));
                }
            }
            ordered.sort(Comparator.comparingInt(index -> {
                int x = minX + index % width;
                int z = minZ + index / width;
                int dx = x - centerX;
                int dz = z - centerZ;
                return dx * dx + dz * dz;
            }));
            chunkOrder = new int[ordered.size()];
            for (int i = 0; i < ordered.size(); i++) chunkOrder[i] = ordered.get(i);
        }
    }

    private int[] getChunkOrder(int radius) {
        int chunkRadius = (radius + 15) >> 4;
        return chunkOrders.computeIfAbsent(chunkRadius, requested -> {
            List<Integer> offsets = new ArrayList<>();
            for (int dz = -requested; dz <= requested; dz++) {
                for (int dx = -requested; dx <= requested; dx++) {
                    offsets.add(((dx & 0xFFFF) << 16) | (dz & 0xFFFF));
                }
            }
            offsets.sort(Comparator.comparingInt((Integer packed) -> {
                int value = packed;
                int dx = (short) (value >>> 16);
                int dz = (short) value;
                return dx * dx + dz * dz;
            }));
            int[] result = new int[offsets.size()];
            for (int i = 0; i < result.length; i++) result[i] = offsets.get(i);
            return result;
        });
    }

    private boolean isKnownSurfaceRegion(int blockX, int blockZ) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        return MapManager.getInstance().hasRegionFile(rx, rz)
                || MapManager.getInstance().isRegionLoadedInCache(rx, rz);
    }

    private void preloadKnownRegionForChunk(int blockX, int blockZ, boolean cave, boolean fullView) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        // Load only the exact region currently being scanned. The previous 3x3
        // preload multiplied every request by nine and caused I/O/eviction thrash.
        if (!cave) {
            MapManager manager = MapManager.getInstance();
            if (manager.hasRegionFile(rx, rz) && !manager.isRegionLoadedInCache(rx, rz)) {
                MapProcessor.getInstance().enqueueSurfaceLoad(rx, rz, 10_000);
            }
        } else if (fullView) {
            FullCaveMapManager manager = FullCaveMapManager.getInstance();
            if (manager.hasRegionFile(rx, rz) && !manager.isRegionLoaded(rx, rz)) {
                MapProcessor.getInstance().enqueueFullCaveLoad(rx, rz, 10_000);
            }
        } else {
            CaveMapManager manager = CaveMapManager.getInstance();
            if (manager.hasRegionFile(rx, rz) && !manager.isRegionLoaded(rx, rz)) {
                MapProcessor.getInstance().enqueueCaveLoad(manager.getActiveLayerY(), rx, rz, 10_000);
            }
        }
    }

    private void scanCavePixelIfLoaded(Level level, int blockX, int layerY, int blockZ, boolean fullView) {
        if (!level.hasChunk(blockX >> 4, blockZ >> 4)) return;
        CavePixel pixel = getCavePixel(level, blockX, layerY, blockZ, fullView);
        int displayedColor = pixel.surfaceY() == FullCaveMapManager.NO_SURFACE
                ? pixel.color()
                : applyAbgrShade(pixel.color(), calculateCaveTerrainShade(
                        level, blockX, blockZ, layerY, fullView, pixel.surfaceY()));
        // Every cave observation also enriches the stable FULL cache. LAYERED can
        // therefore render this composite as its background while a selected Top Y
        // is loaded over it, instead of replacing the whole map with an empty layer.
        if (pixel.surfaceY() != FullCaveMapManager.NO_SURFACE) {
            FullCaveMapManager.getInstance().mergeCandidate(
                    blockX, blockZ, displayedColor, pixel.surfaceY(),
                    CaveMode.getScanMaximum(level, layerY));
        }
        if (!fullView) CaveMapManager.getInstance().setColor(blockX, blockZ, displayedColor);
    }

    private CavePixel getCavePixel(Level level, int blockX, int layerY, int blockZ, boolean fullView) {
        int scanMinimum = fullView ? level.getMinBuildHeight() : CaveMode.getScanMinimum(level, layerY);
        int scanMaximum = CaveMode.getScanMaximum(level, layerY);
        BlockPos.MutableBlockPos openPos = new BlockPos.MutableBlockPos(blockX, layerY, blockZ);
        BlockPos.MutableBlockPos colorPos = new BlockPos.MutableBlockPos(blockX, layerY, blockZ);

        // LAYERED searches only a bounded band below Top Y. FULL is the only view
        // allowed to project to the world bottom and merge a global cave composite.
        for (int openY = scanMaximum; openY >= scanMinimum; openY--) {
            CavePixel pixel = getCaveSurface(level, openPos, colorPos, openY, layerY, fullView);
            if (pixel != null) return pixel;
        }

        return new CavePixel(0xFF0B0B0B, FullCaveMapManager.NO_SURFACE);
    }

    private CavePixel getCaveSurface(Level level, BlockPos.MutableBlockPos openPos,
            BlockPos.MutableBlockPos colorPos, int openY, int bandCenterY, boolean fullView) {
        openPos.setY(openY);
        BlockState openState = level.getBlockState(openPos);
        BlockState colorState;
        boolean emissiveFeature = isOpenEmissiveFeature(level, openPos, openState);
        boolean visibleFlower = MapConfig.displayFlowers
                && openState.is(net.minecraft.tags.BlockTags.FLOWERS);

        if (emissiveFeature || visibleFlower || !openState.getFluidState().isEmpty()) {
            colorPos.setY(openY);
            colorState = openState;
        } else {
            boolean openSpace = openState.isAir() || openState.getCollisionShape(level, openPos).isEmpty();
            if (!openSpace || openY <= level.getMinBuildHeight()) return null;
            colorPos.setY(openY - 1);
            colorState = level.getBlockState(colorPos);
            boolean floorOpen = colorState.isAir()
                    || (colorState.getCollisionShape(level, colorPos).isEmpty()
                            && colorState.getFluidState().isEmpty());
            if (floorOpen) return null;
        }

        int baseColor = emissiveFeature
                ? getEmissiveFeatureColor(level, colorPos, colorState)
                : getCaveBlockColor(level, colorPos, colorState);
        if (baseColor == 0) return null;
        int blockLight = Math.max(level.getBrightness(LightLayer.BLOCK, openPos), colorState.getLightEmission());
        int shadeOffset;
        if (fullView) {
            int dimensionMiddle = (level.getMinBuildHeight() + level.getMaxBuildHeight() - 1) / 2;
            shadeOffset = Math.round((colorPos.getY() - dimensionMiddle) / 8.0f);
        } else {
            shadeOffset = Math.round((colorPos.getY() - bandCenterY) / 8.0f);
        }
        return new CavePixel(applyCaveLighting(baseColor, blockLight, shadeOffset), colorPos.getY());
    }

    private record CavePixel(int color, int surfaceY) {
    }

    /** Cave relief follows the actual visible floor selected by the Top-Y band,
     * rather than the overworld heightmap. This makes LAYERED and FULL honour the
     * same OFF / 2D / 3D setting as the surface map. */
    private float calculateCaveTerrainShade(Level level, int x, int z, int layerY,
            boolean fullView, int centerY) {
        if (MapConfig.terrainSlopes <= 0) return 1.0f;
        int north = getCaveNeighbourY(level, x, z - 1, layerY, fullView, centerY);
        if (MapConfig.terrainSlopes == 1) {
            return Math.max(0.86f, Math.min(1.14f,
                    1.0f + (centerY - north) * 0.030f));
        }
        int south = getCaveNeighbourY(level, x, z + 1, layerY, fullView, centerY);
        int west = getCaveNeighbourY(level, x - 1, z, layerY, fullView, centerY);
        int east = getCaveNeighbourY(level, x + 1, z, layerY, fullView, centerY);
        float gradientX = Math.max(-8.0f, Math.min(8.0f, (west - east) * 0.5f));
        float gradientZ = Math.max(-8.0f, Math.min(8.0f, (north - south) * 0.5f));
        float directional = gradientX * 0.045f + gradientZ * 0.062f;
        int rim = Math.max(Math.max(north, south), Math.max(west, east));
        float pit = Math.min(0.30f, Math.max(0, rim - centerY) * 0.050f);
        float edge = Math.min(0.16f,
                (Math.abs(west - east) + Math.abs(north - south)) * 0.012f);
        return Math.max(0.54f, Math.min(1.24f, 1.0f + directional - pit - edge));
    }

    private int getCaveNeighbourY(Level level, int x, int z, int layerY,
            boolean fullView, int fallbackY) {
        if (!level.hasChunk(x >> 4, z >> 4)) return fallbackY;
        if (fullView) {
            int cached = FullCaveMapManager.getInstance().getSurfaceY(x, z);
            return cached == FullCaveMapManager.NO_SURFACE ? fallbackY : cached;
        }
        int minimum = CaveMode.getScanMinimum(level, layerY);
        int maximum = CaveMode.getScanMaximum(level, layerY);
        BlockPos.MutableBlockPos openPos = new BlockPos.MutableBlockPos(x, maximum, z);
        BlockPos.MutableBlockPos floorPos = new BlockPos.MutableBlockPos(x, maximum, z);
        for (int openY = maximum; openY >= minimum; openY--) {
            openPos.setY(openY);
            BlockState open = level.getBlockState(openPos);
            if (isOpenEmissiveFeature(level, openPos, open)
                    || (MapConfig.displayFlowers && open.is(net.minecraft.tags.BlockTags.FLOWERS))
                    || !open.getFluidState().isEmpty()) return openY;
            boolean openSpace = open.isAir() || open.getCollisionShape(level, openPos).isEmpty();
            if (!openSpace || openY <= level.getMinBuildHeight()) continue;
            floorPos.setY(openY - 1);
            BlockState floor = level.getBlockState(floorPos);
            boolean floorOpen = floor.isAir()
                    || (floor.getCollisionShape(level, floorPos).isEmpty()
                            && floor.getFluidState().isEmpty());
            if (!floorOpen && floor.getMapColor(level, floorPos) != MapColor.NONE) return openY - 1;
        }
        return fallbackY;
    }

    private int applyAbgrShade(int abgr, float shade) {
        int alpha = (abgr >>> 24) & 0xFF;
        int red = Math.max(0, Math.min(255, Math.round((abgr & 0xFF) * shade)));
        int green = Math.max(0, Math.min(255, Math.round(((abgr >>> 8) & 0xFF) * shade)));
        int blue = Math.max(0, Math.min(255, Math.round(((abgr >>> 16) & 0xFF) * shade)));
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private int getCaveBlockColor(Level level, BlockPos pos, BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Integer override = MapConfig.blockColorOverrides.get(blockId);
        if (override != null) return argbToAbgr(override);
        if (state.is(Blocks.LAVA)) return argbToAbgr(0xFFF3A52B);
        if (state.getLightEmission() > 0) return getEmissiveFeatureColor(level, pos, state);

        MapColor mapColor = state.getMapColor(level, pos);
        if (mapColor == MapColor.NONE) return 0;
        int rgb = resolveBlockRgb(level, pos, state, mapColor);
        boolean leaves = state.is(net.minecraft.tags.BlockTags.LEAVES);
        boolean cherry = state.is(Blocks.CHERRY_LEAVES);
        boolean grass = state.is(Blocks.GRASS_BLOCK);
        boolean wood = mapColor == MapColor.WOOD
                || state.is(net.minecraft.tags.BlockTags.PLANKS)
                || state.is(net.minecraft.tags.BlockTags.LOGS);
        if (MapConfig.blockColourMode == 0) {
            rgb = makeColorRich(rgb, leaves, cherry, grass, wood);
        }
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private boolean isOpenEmissiveFeature(Level level, BlockPos pos, BlockState state) {
        if (state.getLightEmission() <= 0 || !state.getFluidState().isEmpty()) return false;
        return state.is(Blocks.FIRE)
                || state.is(Blocks.SOUL_FIRE)
                || state.getCollisionShape(level, pos).isEmpty();
    }

    /** Gives flames and non-solid light sources a visible map core, not only a halo. */
    private int getEmissiveFeatureColor(Level level, BlockPos pos, BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Integer override = MapConfig.blockColorOverrides.get(blockId);
        if (override != null) return argbToAbgr(override);
        if (state.is(Blocks.LAVA)) return argbToAbgr(0xFFF3A52B);
        if (state.is(Blocks.SOUL_FIRE)) return argbToAbgr(0xFF45DDE8);
        if (state.is(Blocks.FIRE)) return argbToAbgr(0xFFFF8A24);

        int rgb = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        if (rgb == -1) {
            MapColor mapColor = state.getMapColor(level, pos);
            rgb = mapColor == MapColor.NONE ? 0xFFB13B : mapColor.col;
        }

        float boost = 1.0f + 0.25f * state.getLightEmission() / 15.0f;
        int red = Math.min(255, Math.round(((rgb >>> 16) & 0xFF) * boost));
        int green = Math.min(255, Math.round(((rgb >>> 8) & 0xFF) * boost));
        int blue = Math.min(255, Math.round((rgb & 0xFF) * boost));
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private int applyCaveLighting(int abgr, int light, int verticalOffset) {
        float normalized = Math.max(0.0f, Math.min(1.0f, light / 15.0f));
        float heightShade = Math.max(0.82f, Math.min(1.18f, 1.0f + verticalOffset * 0.018f));
        // Cave maps represent geometry, not the player's current gamma/light
        // exposure. Keep a readable ambient floor so one scanned region cannot be
        // pitch black while an adjacent cached region is bright.
        float brightness = (0.60f + 0.40f * (float) Math.pow(normalized, 0.90f)) * heightShade;
        brightness = Math.max(0.52f, Math.min(1.0f, brightness));
        int red = Math.round((abgr & 0xFF) * brightness);
        int green = Math.round(((abgr >>> 8) & 0xFF) * brightness);
        int blue = Math.round(((abgr >>> 16) & 0xFF) * brightness);

        if (light > 6) {
            float warmth = Math.min(0.45f, ((light - 6) / 9.0f) * 0.45f);
            red = Math.round(red + (255 - red) * warmth);
            green = Math.round(green + (185 - green) * warmth);
            blue = Math.round(blue + (80 - blue) * warmth);
        }
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private void scanLightIfLoaded(Minecraft mc, int blockX, int blockZ) {
        if (mc.level.hasChunk(blockX >> 4, blockZ >> 4)) {
            int surfaceY = getHighestY(mc.level, blockX, blockZ);
            updateSurfaceLight(mc.level, blockX, surfaceY, blockZ);
        }
    }

    private void updateSurfaceLight(Level level, int blockX, int surfaceY, int blockZ) {
        BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos(blockX, surfaceY, blockZ);
        int light = level.getBrightness(LightLayer.BLOCK, lightPos);
        lightPos.setY(surfaceY + 1);
        light = Math.max(light, level.getBrightness(LightLayer.BLOCK, lightPos));
        MapLightManager.getInstance().setLight(blockX, blockZ, light);
    }

    /**
     * Re-scans a single column (x, z) in the world synchronously.
     * Now stores raw MapBlockData for re-colorizable map.
     */
    public void scanBlockColumn(Level level, BlockPos pos) {
        if (!level.isClientSide()) return;
        try {
            int blockX = pos.getX();
            int blockZ = pos.getZ();
            MapBlockData data = buildBlockData(level, blockX, blockZ);
            if (data != null) {
                MapBlockData existing = MapManager.getInstance().getBlockData(blockX, blockZ);
                if (existing == null || !data.isEmpty()) {
                    MapManager.getInstance().setBlockData(blockX, blockZ, data);
                }
            }
            int surfaceY = data != null && !data.isEmpty() ? data.topY
                    : getHighestY(level, blockX, blockZ);
            updateSurfaceLight(level, blockX, surfaceY, blockZ);
        } catch (Exception e) {
            LOGGER.error("Error scanning block column at " + pos, e);
        }
    }

    /** Re-scans one map pixel using whichever surface/cave view is currently active. */
    public void scanDisplayedColumn(Minecraft mc, int blockX, int blockZ) {
        if (mc == null || mc.level == null || mc.player == null) return;
        if (CaveMode.isActive(mc)) {
            int layerY = CaveMode.getLayerY(mc);
            boolean fullView = CaveMode.isFullView(mc);
            if (!fullView) CaveMapManager.getInstance().setActiveLayer(layerY);
            scanCavePixelIfLoaded(mc.level, blockX, layerY, blockZ, fullView);
        } else {
            scanBlockColumn(mc.level, new BlockPos(blockX, 0, blockZ));
        }
    }

    /**
     * Builds a MapBlockData from the current world state at (blockX, blockZ).
     * Returns null if chunk is not loaded, or a data with EMPTY_Y if no surface
     * block was found.
     */
    private MapBlockData buildBlockData(Level level, int blockX, int blockZ) {
        if (!level.hasChunk(blockX >> 4, blockZ >> 4)) return null;
        int surfaceY = getHighestY(level, blockX, blockZ);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, surfaceY, blockZ);
        BlockState visibleState = level.getBlockState(pos);
        MapColor mapColor = visibleState.getMapColor(level, pos);
        boolean fluid = !visibleState.getFluidState().isEmpty();
        if (mapColor == MapColor.NONE && !fluid) {
            return new MapBlockData(MapBlockData.EMPTY_Y, MapBlockData.NO_BLOCK,
                    MapBlockData.NO_BIOME, (byte) 0, MapBlockData.EMPTY_Y);
        }

        int floorY = surfaceY;
        BlockState paletteState = visibleState;
        boolean water = visibleState.getFluidState().is(net.minecraft.tags.FluidTags.WATER);
        boolean lava = visibleState.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        if (water) {
            // Store the solid floor block in the palette and the separate floorY
            // in the packed high bits. This powers transparent-looking water and
            // prevents a flat water surface from being shaded as raised terrain.
            for (int y = surfaceY; y >= level.getMinBuildHeight(); y--) {
                pos.setY(y);
                BlockState candidate = level.getBlockState(pos);
                if (candidate.getFluidState().is(net.minecraft.tags.FluidTags.WATER)
                        || candidate.isAir()
                        || candidate.getCollisionShape(level, pos).isEmpty()) continue;
                floorY = y;
                paletteState = candidate;
                break;
            }
        }

        String blockId = BuiltInRegistries.BLOCK.getKey(paletteState.getBlock()).toString();
        pos.setY(surfaceY);
        String biomeId = "minecraft:plains";
        try {
            Holder<Biome> biomeHolder = level.getBiome(pos);
            java.util.Optional<net.minecraft.resources.ResourceKey<Biome>> keyOpt = biomeHolder.unwrapKey();
            if (keyOpt.isPresent()) biomeId = keyOpt.get().location().toString();
        } catch (Exception ignored) {
        }

        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        MapManager.Region region = MapManager.getInstance().getRegion(rx, rz, true);
        if (region == null || !region.isLoaded()) return null;
        int biomeIdx = region.getOrAddBiomeIndex(biomeId);
        int blockIdx = region.getOrAddBlockIndex(blockId);

        boolean leaves = !fluid && visibleState.is(net.minecraft.tags.BlockTags.LEAVES);
        boolean flower = !fluid && visibleState.is(net.minecraft.tags.BlockTags.FLOWERS);
        boolean glowing = lava || (!water && visibleState.getLightEmission() > 0);
        int blockLight = level.getBrightness(LightLayer.BLOCK,
                new BlockPos(blockX, surfaceY + 1, blockZ));

        return MapBlockData.builder()
                .topY(surfaceY)
                .floorY(floorY)
                .blockId(blockIdx)
                .biomeId(biomeIdx)
                .light(blockLight)
                .glowing(glowing)
                .fluid(fluid)
                .flower(flower)
                .leaves(leaves)
                .build();
    }

    private int getHighestY(Level level, int blockX, int blockZ) {
        boolean isNether = level.dimensionType().hasCeiling();
        int minBuildHeight = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, 0, blockZ);

        if (isNether) {
            int startY = 120;
            boolean foundAir = false;
            for (int y = startY; y >= minBuildHeight; y--) {
                pos.setY(y);
                BlockState state = level.getBlockState(pos);
                if (!foundAir) {
                    if (state.isAir()) {
                        foundAir = true;
                    }
                } else {
                    MapColor mapColor = state.getMapColor(level, pos);
                    if (!state.isAir() && mapColor != MapColor.NONE
                            && !isHiddenFlower(state)) {
                        return y;
                    }
                }
            }
            return minBuildHeight;
        } else {
            int highestY = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
            for (int y = highestY; y >= minBuildHeight; y--) {
                pos.setY(y);
                BlockState state = level.getBlockState(pos);
                if (state.getLightEmission() > 0) return y;
                MapColor mapColor = state.getMapColor(level, pos);
                if (!state.isAir() && mapColor != MapColor.NONE
                        && !isHiddenFlower(state)) {
                    return y;
                }
            }
            return minBuildHeight;
        }
    }

    private int getColumnColor(Level level, int blockX, int blockZ, int currentY) {
        BlockPos pos = new BlockPos(blockX, currentY, blockZ);
        BlockState targetState = level.getBlockState(pos);

        String blockId = BuiltInRegistries.BLOCK.getKey(targetState.getBlock()).toString();
        Integer overrideColor = MapConfig.blockColorOverrides.get(blockId);
        if (overrideColor != null) {
            return argbToAbgr(overrideColor);
        }

        // Keep exposed lava warm and readable. Its propagated light mask restores
        // this bright core at night while surrounding pixels fade naturally.
        if (targetState.is(Blocks.LAVA)) {
            return argbToAbgr(0xFFF3A52B);
        }
        if (targetState.getLightEmission() > 0) {
            return getEmissiveFeatureColor(level, pos, targetState);
        }

        // Vanilla-accurate water depth shading with transitional dithering (caro)
        // Transitions between shallow (1-2), medium (5-6) and deep (10+) using
        // checkerboard dither (3-4, 7-9)
        if (targetState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
            int depth = 0;
            BlockPos.MutableBlockPos depthPos = new BlockPos.MutableBlockPos(blockX, currentY, blockZ);
            while (depth < 30) {
                int checkY = currentY - depth - 1;
                if (checkY < level.getMinBuildHeight())
                    break;
                depthPos.setY(checkY);
                if (!level.getBlockState(depthPos).getFluidState().is(net.minecraft.tags.FluidTags.WATER))
                    break;
                depth++;
            }

            // Vanilla water MapColor base (col index 12 in MapColor = 0x3F76E4)
            int waterBase = MapConfig.blockColourMode == 1
                    ? MapColor.WATER.col
                    : Minecraft.getInstance().getBlockColors().getColor(targetState, level, pos, 0);
            if (waterBase == -1) waterBase = MapColor.WATER.col;
            int wr = (waterBase >> 16) & 0xFF;
            int wg = (waterBase >> 8) & 0xFF;
            int wb = waterBase & 0xFF;

            // Define three base shades for water depth (shallow, medium, deep)
            float shade;
            if (depth <= 3) {
                shade = 1.0f;  // Shallow water (0-3 blocks deep)
            } else if (depth <= 8) {
                shade = 0.85f; // Medium water (4-8 blocks deep)
            } else {
                shade = 0.70f; // Deep water (9+ blocks deep)
            }

            int r = Math.round(wr * shade);
            int g = Math.round(wg * shade);
            int b = Math.round(wb * shade);

            return 0xFF000000 | (b << 16) | (g << 8) | r; // ABGR (NativeImage format)
        }

        boolean isLeaves = targetState.is(net.minecraft.tags.BlockTags.LEAVES);
        boolean isCherry = targetState.is(Blocks.CHERRY_LEAVES);
        boolean isGrass = targetState.is(Blocks.GRASS_BLOCK);
        MapColor mapColor = targetState.getMapColor(level, pos);
        boolean isWood = (mapColor == MapColor.WOOD) || targetState.is(net.minecraft.tags.BlockTags.PLANKS) || targetState.is(net.minecraft.tags.BlockTags.LOGS);
        if (mapColor == MapColor.NONE) return 0;
        int rgb = resolveBlockRgb(level, pos, targetState, mapColor);
        if (MapConfig.blockColourMode == 0) {
            rgb = makeColorRich(rgb, isLeaves, isCherry, isGrass, isWood);
        }

        // Convert MapColor RGB to ABGR for NativeImage
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        float shade = calculateTerrainShade(level, blockX, blockZ, currentY);

        // Procedural micro-texture noise for organic feel (stable and fast coordinate hash)
        long hash = ((long) blockX * 312251L) ^ ((long) blockZ * 4390321L);
        hash = (hash ^ (hash >>> 16)) * 0x85ebca6bL;
        hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35L;
        float noise = (float) (hash & 0xFFFF) / 65535.0f;
        float noiseVal = noise * 2.0f - 1.0f; // -1.0 to 1.0

        float variation = 0.0f;
        if (isLeaves) {
            variation = 0.07f * noiseVal; // Speckled leaves foliage noise (+/- 7%)
        } else if (targetState.is(Blocks.GRASS_BLOCK) || targetState.is(Blocks.DIRT)
                || targetState.is(Blocks.SAND) || targetState.is(Blocks.GRAVEL)) {
            variation = 0.025f * noiseVal; // Subtle ground noise (+/- 2.5%)
        }

        shade *= (1.0f + variation);

        red = Math.max(0, Math.min(255, (int) (red * shade)));
        green = Math.max(0, Math.min(255, (int) (green * shade)));
        blue = Math.max(0, Math.min(255, (int) (blue * shade)));

        // NativeImage uses ABGR: (0xFF << 24) | (blue << 16) | (green << 8) | red
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    /**
     * ACCURATE uses Minecraft's registered BlockColors provider (including biome
     * tint and modded providers). VANILLA intentionally uses the small MapColor
     * palette used by vanilla maps.
     */
    private int resolveBlockRgb(Level level, BlockPos pos, BlockState state, MapColor fallback) {
        if (MapConfig.blockColourMode == 1) return fallback.col;
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        MapTextureManager textureManager = MapTextureManager.getInstance();
        int sampled = textureManager.resolveBlockColor(blockId, 0);
        if (sampled == 0) sampled = 0xFF000000 | fallback.col;
        BlockTintPolicy policy = textureManager.resolveTintPolicy(blockId);
        if (policy == BlockTintPolicy.NONE) return sampled & 0x00FFFFFF;

        int tint = switch (policy) {
            case SPRUCE -> 0x619961;
            case BIRCH -> 0x80A755;
            default -> Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        };
        if (tint == -1) return sampled & 0x00FFFFFF;
        float strength = policy == BlockTintPolicy.GRASS ? 0.88f : 0.84f;
        return SurfaceColorizer.applyTintPreservingTexture(
                sampled, 0xFF000000 | (tint & 0x00FFFFFF), strength) & 0x00FFFFFF;
    }

    /** OFF is flat, 2D keeps a restrained north-lit relief, and 3D evaluates a
     * four-direction height gradient for a stronger embossed terrain reading. */
    private float calculateTerrainShade(Level level, int x, int z, int centerY) {
        if (MapConfig.terrainSlopes <= 0) return 1.0f;
        int north = getTerrainHeightForSlope(level, x, z - 1, centerY);
        if (MapConfig.terrainSlopes == 1) {
            int delta = centerY - north;
            return Math.max(0.88f, Math.min(1.12f, 1.0f + delta * 0.025f));
        }

        int south = getTerrainHeightForSlope(level, x, z + 1, centerY);
        int west = getTerrainHeightForSlope(level, x - 1, z, centerY);
        int east = getTerrainHeightForSlope(level, x + 1, z, centerY);
        float gradientX = Math.max(-8.0f, Math.min(8.0f, (west - east) * 0.5f));
        float gradientZ = Math.max(-8.0f, Math.min(8.0f, (north - south) * 0.5f));

        // Simulated light from north-west. A small edge term darkens steep breaks,
        // producing clearer relief without pretending to render real 3D geometry.
        float directional = gradientX * 0.032f + gradientZ * 0.045f;
        float edge = Math.min(0.10f,
                (Math.abs(west - east) + Math.abs(north - south)) * 0.008f);
        float localPeak = centerY > north && centerY > south && centerY > west && centerY > east
                ? 0.035f : 0.0f;
        return Math.max(0.70f, Math.min(1.30f, 1.0f + directional - edge + localPeak));
    }

    /**
     * Slope sampling must stay cheap: calling the full column resolver four more
     * times for every map pixel makes 3D relief several times slower. Open-sky
     * dimensions can use the chunk heightmap directly. Ceiling dimensions only
     * inspect a small band around the already resolved centre surface, which is
     * enough to describe local relief without walking the whole Nether column.
     */
    private int getTerrainHeightForSlope(Level level, int x, int z, int referenceY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        if (!level.dimensionType().hasCeiling()) {
            int top = Math.min(level.getMaxBuildHeight() - 1,
                    level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
            int bottom = Math.max(level.getMinBuildHeight(), top - 8);
            for (int y = top; y >= bottom; y--) {
                pos.setY(y);
                BlockState state = level.getBlockState(pos);
                if (!state.isAir() && state.getMapColor(level, pos) != MapColor.NONE
                        && !isHiddenFlower(state)) return y;
            }
            return referenceY;
        }

        int top = Math.min(level.getMaxBuildHeight() - 1, referenceY + 8);
        int bottom = Math.max(level.getMinBuildHeight(), referenceY - 16);
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && state.getMapColor(level, pos) != MapColor.NONE
                    && !isHiddenFlower(state)) return y;
        }
        return referenceY;
    }

    private boolean isHiddenFlower(BlockState state) {
        return !MapConfig.displayFlowers && state.is(net.minecraft.tags.BlockTags.FLOWERS);
    }

    private int argbToAbgr(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    /**
     * Explicit Refresh Map action: synchronously re-scans every loaded column in
     * the circular area, independently of the selected progressive reveal order.
     */
    public void scanAroundPlayer(Minecraft mc, int radius) {
        if (mc.level == null || mc.player == null)
            return;

        if (CaveMode.isActive(mc)) {
            int layerY = CaveMode.getLayerY(mc);
            boolean fullView = CaveMode.isFullView(mc);
            if (!fullView) CaveMapManager.getInstance().setActiveLayer(layerY);
            int centerX = (int) Math.floor(mc.player.getX());
            int centerZ = (int) Math.floor(mc.player.getZ());
            int radiusSq = radius * radius;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (dx * dx + dz * dz <= radiusSq) {
                        scanCavePixelIfLoaded(mc.level, centerX + dx, layerY, centerZ + dz, fullView);
                    }
                }
            }
            return;
        }

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        int centerBlockX = (int) Math.floor(px);
        int centerBlockZ = (int) Math.floor(pz);
        int radiusSq = radius * radius;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radiusSq) {
                    scanColumnIfLoaded(mc, centerBlockX + dx, centerBlockZ + dz);
                }
            }
        }
    }

    public int makeColorRich(int rgb, boolean isLeaves, boolean isCherry,
            boolean isGrass, boolean isWood) {
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        float luma = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
        float saturation = isCherry ? 1.05f : (isLeaves || isGrass ? 1.10f : 1.06f);
        float brightness = isLeaves ? 0.88f : (isWood ? 0.92f : 0.97f);
        red = Math.max(0, Math.min(255, Math.round((luma + (red - luma) * saturation) * brightness)));
        green = Math.max(0, Math.min(255, Math.round((luma + (green - luma) * saturation) * brightness)));
        blue = Math.max(0, Math.min(255, Math.round((luma + (blue - luma) * saturation) * brightness)));
        return (red << 16) | (green << 8) | blue;
    }
}
