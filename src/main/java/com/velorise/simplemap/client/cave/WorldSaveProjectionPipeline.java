package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.GeneratedChunkIndex;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapRequestLane;
import com.velorise.simplemap.client.MapViewLoadPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Single world-save source pipeline shared by Surface, Layered Cave and Full Cave.
 *
 * <p>The pipeline performs viewport planning once, routes every requested native
 * Anvil region through {@link CaveNativeRegionImportService}, and lets that one
 * source transaction fan out into presentation projections. A chunk is read and
 * palette-decoded by {@link DecodedWorldRegionCache} once. Surface projection and
 * the reusable vertical cave archive are then derived from the same
 * {@link DecodedWorldChunkSource}; mode changes never start a second .mca reader.</p>
 */
public final class WorldSaveProjectionPipeline {
    private static final WorldSaveProjectionPipeline INSTANCE =
            new WorldSaveProjectionPipeline();
    private static final int FULLSCREEN_STICKY_HALO_PAGES = 2;

    private final DecodedWorldRegionCache sourceCache =
            DecodedWorldRegionCache.getInstance();
    private final CaveNativeRegionImportService regionImporter =
            CaveNativeRegionImportService.getInstance();
    private final AnvilPagePresenceIndex presenceIndex =
            AnvilPagePresenceIndex.getInstance();
    private final SurfaceWorldSaveReconstructor surface =
            SurfaceWorldSaveReconstructor.getInstance();

    private WorldSaveProjectionPipeline() {
    }

    public static WorldSaveProjectionPipeline getInstance() {
        return INSTANCE;
    }

    /**
     * Requests one visible projection without changing source ownership for other
     * projections. Surface and Cave views can therefore coexist (minimap plus open
     * world map) while sharing the same decoded native-region source cells.
     */
    public void requestVisible(Minecraft minecraft, WorldProjection projection,
            int requestedTopY, int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ, double centerChunkX,
            double centerChunkZ, float scale, MapRequestLane lane) {
        sourceCache.maintain();
        regionImporter.maintain();
        CaveRegionProjectionService.getInstance().maintain();
        surface.drainReadyApplications();
        if (minecraft == null || minecraft.level == null || projection == null) return;
        ServerLevel level = resolveViewedServerLevel(minecraft);
        if (level == null) return;

        GeneratedChunkIndex.getInstance().observeLevel(level);
        MapRequestLane effectiveLane = lane == null
                ? MapRequestLane.FULLSCREEN : lane;
        int minimumPageX = Math.floorDiv(Math.min(minChunkX, maxChunkX), 4);
        int maximumPageX = Math.floorDiv(Math.max(minChunkX, maxChunkX), 4);
        int minimumPageZ = Math.floorDiv(Math.min(minChunkZ, maxChunkZ), 4);
        int maximumPageZ = Math.floorDiv(Math.max(minChunkZ, maxChunkZ), 4);
        int foregroundMinPageX = minimumPageX;
        int foregroundMaxPageX = maximumPageX;
        int foregroundMinPageZ = minimumPageZ;
        int foregroundMaxPageZ = maximumPageZ;
        if (effectiveLane == MapRequestLane.FULLSCREEN) {
            minimumPageX -= FULLSCREEN_STICKY_HALO_PAGES;
            maximumPageX += FULLSCREEN_STICKY_HALO_PAGES;
            minimumPageZ -= FULLSCREEN_STICKY_HALO_PAGES;
            maximumPageZ += FULLSCREEN_STICKY_HALO_PAGES;
        } else if (effectiveLane == MapRequestLane.MINIMAP) {
            minimumPageX -= MapViewLoadPlanner.MINIMAP_HALO_PAGES;
            maximumPageX += MapViewLoadPlanner.MINIMAP_HALO_PAGES;
            minimumPageZ -= MapViewLoadPlanner.MINIMAP_HALO_PAGES;
            maximumPageZ += MapViewLoadPlanner.MINIMAP_HALO_PAGES;
            // The minimap renderer owns this same halo, unlike fullscreen's
            // source-only sticky halo. Keep foreground and source plans aligned.
            foregroundMinPageX = minimumPageX;
            foregroundMaxPageX = maximumPageX;
            foregroundMinPageZ = minimumPageZ;
            foregroundMaxPageZ = maximumPageZ;
        }
        if (minimumPageX > maximumPageX || minimumPageZ > maximumPageZ) return;

        int centerPageX = clamp((int) Math.floor(centerChunkX / 4.0),
                minimumPageX, maximumPageX);
        int centerPageZ = clamp((int) Math.floor(centerChunkZ / 4.0),
                minimumPageZ, maximumPageZ);
        long[] pagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                minimumPageX, maximumPageX, minimumPageZ, maximumPageZ,
                centerPageX, centerPageZ, true);
        long[] foregroundPagePlan = CaveLoadHierarchy.buildVisiblePagePlan(
                foregroundMinPageX, foregroundMaxPageX,
                foregroundMinPageZ, foregroundMaxPageZ,
                clamp((int) Math.floor(centerChunkX / 4.0),
                        foregroundMinPageX, foregroundMaxPageX),
                clamp((int) Math.floor(centerChunkZ / 4.0),
                        foregroundMinPageZ, foregroundMaxPageZ),
                true);
        if (pagePlan.length == 0 || foregroundPagePlan.length == 0) return;

        String dimension = MapManager.getInstance().getDimensionCacheKey();
        if (dimension == null || dimension.isBlank()) {
            dimension = level.dimension().location().toString();
        }
        AnvilPagePresenceIndex.Snapshot presence = presenceIndex.snapshot(level);
        long generation = CaveTileRepository.getInstance().generation();
        if (projection == WorldProjection.SURFACE) {
            regionImporter.suspendCaveLane(effectiveLane);
            regionImporter.requestSurfaceViewport(level, dimension, pagePlan,
                    centerPageX, centerPageZ, effectiveLane, generation, presence);
        } else {
            regionImporter.suspendSurfaceLane(effectiveLane);
            regionImporter.requestViewport(level, dimension,
                    projection.caveView(), projection.canonicalTopY(requestedTopY),
                    pagePlan, foregroundPagePlan, centerPageX, centerPageZ,
                    effectiveLane, generation, presence);
        }
    }

    public void maintain() {
        sourceCache.maintain();
        regionImporter.maintain();
        CaveRegionProjectionService.getInstance().maintain();
        surface.drainReadyApplications();
    }

    private static ServerLevel resolveViewedServerLevel(Minecraft minecraft) {
        if (minecraft == null || minecraft.getSingleplayerServer() == null) return null;
        String viewed = MapManager.getInstance().getCurrentDimensionResourceId();
        ResourceLocation location = ResourceLocation.tryParse(viewed);
        if (location == null) return null;
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        return minecraft.getSingleplayerServer().getLevel(key);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
