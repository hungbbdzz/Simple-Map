package com.velorise.simplemap.client;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.client.color.SurfaceColorizer.MapVisualClassifier;
import java.util.BitSet;
import com.velorise.simplemap.client.renderer.*;
import com.velorise.simplemap.client.color.SurfaceColorizer.MapColorProfile;
import com.velorise.simplemap.client.RegionDataStore.SurfaceChunkCoverage;
import com.velorise.simplemap.client.RegionDataStore.RegionContainerV2;
import com.velorise.simplemap.client.MapWorkScheduler.MapProcessor;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager.Waypoint;
import com.velorise.simplemap.client.gpu.GpuPageTable.MapUploadEngine;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager.CaveStateClassifier;
import com.velorise.simplemap.client.color.SurfaceColorizer.SurfaceTintData;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.color.*;
import com.velorise.simplemap.client.gpu.*;
import com.velorise.simplemap.client.MinimapRenderer.MinimapService;

import com.velorise.simplemap.client.RegionDataStore.RegionContainerV2.MapPersistenceV2Service;
import com.velorise.simplemap.client.gui.*;
import com.velorise.simplemap.client.waypoint.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.MapWorkKey;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.MapWorkStage;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RevisionStamp;
import com.velorise.simplemap.client.MapWorkScheduler.MapWorkGraph.RegionRecord;
import com.velorise.simplemap.client.session.MapSessionManager;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.Writer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Central, lazy-loaded surface map data store. */
public class MapManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapManager INSTANCE = new MapManager();
    private static final int MAX_LOADED_REGIONS = 160;
    private static final int PIXEL_COUNT = 512 * 512;
    /** One corrupt live chunk is enough evidence; isolated legacy fallback pixels are not. */
    private static final int FOREIGN_DIMENSION_COLUMN_THRESHOLD = 64;

    // Saved surface region reads share the global IO control plane.


    /* Region coordinates are queried for every viewport candidate. String keys
     * ("rx,rz") allocated on each lookup and dominated render-thread allocation. */
    private final Long2ObjectLinkedOpenHashMap<Region> loadedRegions =
            new Long2ObjectLinkedOpenHashMap<>(16);
    private final LongOpenHashSet dirtyRegions = new LongOpenHashSet();
    private final Long2BooleanOpenHashMap regionFileExists =
            new Long2BooleanOpenHashMap();
    private final AtomicLong worldGeneration = new AtomicLong(1L);

    private volatile File currentDimensionDir;
    private volatile File currentWorldDir;
    private String currentWorldId = "";
    /** Directory-safe dimension key used by the cache layout. */
    private String currentDimensionId = "";
    /** Canonical registry id, e.g. minecraft:the_nether or modid:moon. */
    private volatile String currentDimensionResourceId = "minecraft:overworld";
    /** Persisted target-dimension metadata; never borrowed from a remote live level. */
    private volatile DimensionMapProfile currentDimensionProfile =
            DimensionMapProfile.fallback("minecraft:overworld");
    private String liveDimensionId = "overworld";
    private String liveDimensionResourceId = "minecraft:overworld";
    /** Per-ClientLevel world identity cache. This method is called every client
     * tick; resolving/normalising the same server root repeatedly created GiB of
     * Path objects during Cave profiling. */
    private Level observedIdentityLevel;
    private String observedWorldId = "";
    private File observedWorldDirectory;
    private String observedLiveDimensionId = "overworld";
    private String observedLiveDimensionResourceId = "minecraft:overworld";
    /** Non-null while the fullscreen map is browsing a dimension other than the live level. */
    private String viewDimensionOverride;
    private long lastSaveTime;
    private volatile boolean currentDimensionHasSavedData;
    private volatile long lastSavedDataProbeMs;

    private static final long PERSIST_ACTIVE_RETRY_MS = 1_000L;
    private static final long PERSIST_SURFACE_IDLE_INTERVAL_MS = 10_000L;
    private static final long PERSIST_LIGHT_IDLE_INTERVAL_MS = 10_000L;
    private static final long PERSIST_PLAYER_IDLE_BEFORE_SAVE_MS = 3_000L;
    private static final double PERSIST_MOVEMENT_EPSILON_SQ = 0.0001D;

    private long nextSurfacePumpMs;
    private long nextLightPumpMs;
    private long lastPlayerMovementMs;
    private double lastPlayerX = Double.NaN;
    private double lastPlayerZ = Double.NaN;
    private int nextSubsystem;
    /** Immutable filesystem discovery snapshot. Never walk cache directories from
     * minimap/fullscreen rendering or waypoint lookup. */
    private volatile List<String> discoveredDimensionsSnapshot = List.of();
    private final Set<String> visitedDimensionsInWorld = ConcurrentHashMap.newKeySet();

    private boolean hasLearnedMap;
    private String lastLearnedBookId = "";

    private MapManager() {
    }

    public static MapManager getInstance() {
        return INSTANCE;
    }

    public long getGeneration() {
        return worldGeneration.get();
    }

    public boolean isGenerationCurrent(long generation) {
        return generation == worldGeneration.get();
    }

    public boolean hasLearnedMap() {
        return !MapConfig.serverRequireMapBook || hasLearnedMap;
    }

    public String getLastLearnedBookId() {
        return lastLearnedBookId;
    }

    public void setLastLearnedBookId(String bookId) {
        lastLearnedBookId = isCanonicalBookId(bookId) ? bookId.toLowerCase(Locale.ROOT) : "";
        saveLearningState(hasLearnedMap);
    }

    public void openMapScreen() {
        Minecraft.getInstance().setScreen(new MapScreen());
    }

    public void setHasLearnedMap(boolean learned) {
        hasLearnedMap = learned;
        saveLearningState(learned);
    }

    private synchronized void saveLearningState(boolean learned) {
        if (currentWorldDir == null) return;
        Path worldPath = currentWorldDir.toPath();
        Path learnedFile = worldPath.resolve("map_learned.dat");
        Path idFile = worldPath.resolve("last_book_id.dat");
        try {
            Files.createDirectories(worldPath);
            if (!learned) {
                Files.deleteIfExists(learnedFile);
                Files.deleteIfExists(idFile);
                return;
            }

            writeUtf8Atomic(learnedFile, "learned\n");
            if (isCanonicalBookId(lastLearnedBookId)) {
                writeUtf8Atomic(idFile, lastLearnedBookId + "\n");
            } else {
                Files.deleteIfExists(idFile);
            }
        } catch (IOException exception) {
            LOGGER.error("Failed to update map learning state atomically", exception);
        }
    }

    public synchronized void updateWorldAndDimension(Minecraft minecraft) {
        Level level = minecraft.level;
        if (level == null) {
            if (currentDimensionDir != null) flushAndClear();
            else clearObservedWorldIdentity();
            return;
        }

        if (level == observedIdentityLevel && observedWorldDirectory != null
                && !observedWorldId.isEmpty()) {
            liveDimensionId = observedLiveDimensionId;
            liveDimensionResourceId = observedLiveDimensionResourceId;
            visitedDimensionsInWorld.add(liveDimensionResourceId);
            if (viewDimensionOverride != null) return;
            if (!liveDimensionId.equals(currentDimensionId)) {
                switchDimensionData(liveDimensionId, liveDimensionResourceId, true);
            }
            return;
        }

        String worldId;
        File worldDirectory;
        if (minecraft.isLocalServer() && minecraft.getSingleplayerServer() != null) {
            try {
                var root = minecraft.getSingleplayerServer().getWorldPath(
                        net.minecraft.world.level.storage.LevelResource.ROOT);
                worldId = "sp_" + safePathComponent(root.toAbsolutePath().normalize().toString());
                worldDirectory = root.resolve("simplemap").toFile();
            } catch (Exception exception) {
                worldId = "sp_" + safePathComponent(
                        minecraft.getSingleplayerServer().getWorldData().getLevelName());
                worldDirectory = new File(minecraft.gameDirectory, "simplemap/saves/" + worldId);
            }
        } else if (minecraft.getCurrentServer() != null) {
            String address = minecraft.getCurrentServer().ip;
            worldId = multiplayerWorldId(address);
            worldDirectory = resolveMultiplayerWorldDirectory(minecraft, address, worldId);
        } else {
            worldId = "dim_" + safePathComponent(level.dimension().location().toString());
            worldDirectory = new File(minecraft.gameDirectory, "simplemap/saves/" + worldId);
        }

        String liveResourceId = canonicalDimensionId(level.dimension().location().toString());
        String liveStorageId = dimensionStorageId(liveResourceId);
        boolean liveDimensionChanged = !liveStorageId.equals(observedLiveDimensionId)
                && !observedLiveDimensionId.isEmpty();
        liveDimensionResourceId = liveResourceId;
        liveDimensionId = liveStorageId;
        if (liveDimensionChanged) {
            viewDimensionOverride = null;
        }

        boolean worldChanged = !worldId.equals(currentWorldId)
                || currentWorldDir == null
                || !currentWorldDir.getAbsoluteFile().equals(worldDirectory.getAbsoluteFile());
        if (worldChanged) {
            LOGGER.info("SimpleMap world changed [{}/{}] -> [{}/{}]",
                    currentWorldId, currentDimensionId, worldId, liveStorageId);
            flushAndClear();
            visitedDimensionsInWorld.clear();
            visitedDimensionsInWorld.add(liveResourceId);
            viewDimensionOverride = null;
            currentWorldId = worldId;
            currentWorldDir = worldDirectory;
            if (!worldDirectory.exists() && !worldDirectory.mkdirs()) {
                LOGGER.warn("Could not create SimpleMap world directory {}", worldDirectory);
            }
            loadLearningState(worldDirectory);
            WaypointManager.getInstance().updateWorldDir(worldDirectory);
            configureDimension(liveStorageId, liveResourceId);
            observeLiveDimensionProfile(level, worldDirectory, liveStorageId, liveResourceId);
            MapSessionManager.getInstance().open(currentWorldId, liveResourceId);
            rememberObservedWorldIdentity(level, worldId, worldDirectory,
                    liveStorageId, liveResourceId);
            return;
        }

        visitedDimensionsInWorld.add(liveResourceId);
        observeLiveDimensionProfile(level, worldDirectory, liveStorageId, liveResourceId);
        rememberObservedWorldIdentity(level, worldId, worldDirectory,
                liveStorageId, liveResourceId);
        if (viewDimensionOverride != null) return;
        if (!liveStorageId.equals(currentDimensionId)) {
            switchDimensionData(liveStorageId, liveResourceId, true);
        }
    }

    private void observeLiveDimensionProfile(Level level, File worldDirectory,
            String storageId, String resourceId) {
        if (level == null || worldDirectory == null) return;
        DimensionMapProfile observed = DimensionMapProfile.fromLevel(level, resourceId);
        File dimensionDirectory = new File(worldDirectory, storageId);
        observed.save(dimensionDirectory.toPath());
        if (resourceId.equals(currentDimensionResourceId)) {
            currentDimensionProfile = observed;
        }
    }

    private void rememberObservedWorldIdentity(Level level, String worldId,
            File worldDirectory, String liveStorageId, String liveResourceId) {
        observedIdentityLevel = level;
        observedWorldId = worldId;
        observedWorldDirectory = worldDirectory;
        observedLiveDimensionId = liveStorageId;
        observedLiveDimensionResourceId = liveResourceId;
    }

    private void clearObservedWorldIdentity() {
        observedIdentityLevel = null;
        observedWorldId = "";
        observedWorldDirectory = null;
        observedLiveDimensionId = "overworld";
        observedLiveDimensionResourceId = "minecraft:overworld";
    }

    /** Switches the map data source without changing the player's actual level. */
    public synchronized void switchToDimension(String dimensionId) {
        if (currentWorldDir == null || dimensionId == null || dimensionId.isBlank()) return;
        String canonical = canonicalDimensionId(dimensionId);
        boolean live = canonical.equals(liveDimensionResourceId)
                || "LIVE".equalsIgnoreCase(dimensionId);
        if (live) {
            viewDimensionOverride = null;
            canonical = liveDimensionResourceId;
        } else {
            viewDimensionOverride = canonical;
        }
        String storageId = live ? liveDimensionId : dimensionStorageId(canonical);
        if (storageId.equals(currentDimensionId)
                && canonical.equals(currentDimensionResourceId)) return;
        switchDimensionData(storageId, canonical, true);
    }

    public synchronized void returnToLiveDimension(Minecraft minecraft) {
        viewDimensionOverride = null;
        if (minecraft != null && minecraft.level != null) {
            liveDimensionResourceId = canonicalDimensionId(
                    minecraft.level.dimension().location().toString());
            liveDimensionId = dimensionStorageId(liveDimensionResourceId);
        }
        if (currentWorldDir != null && (!liveDimensionId.equals(currentDimensionId)
                || !liveDimensionResourceId.equals(currentDimensionResourceId))) {
            switchDimensionData(liveDimensionId, liveDimensionResourceId, true);
        }
    }

    /** Switches mutable source ownership while retaining dimension-keyed immutable
     * texture/LOD caches. Old worker commits are rejected by session/generation
     * stamps; inactive GPU pages are reclaimed by the shared residency manager. */
    private void switchDimensionData(String storageId, String resourceId, boolean preserveTextures) {
        // Cancel before clearing managers so no old worker can publish while the
        // dimension-bound stores are in transition.
        MapSessionManager.getInstance().closeActive();
        saveDirtyRegionsAsync();
        resetPersistence();
        worldGeneration.incrementAndGet();
        // Packet callbacks target the player's live ClientLevel, not the remote
        // dimension selected by the fullscreen map. Never carry their compact
        // cursors across a map-dimension ownership change.
        MapMutationBus.getInstance().reset();
        MapProcessor.getInstance().clear();
        clearSurfaceDimensionData();
        SurfaceRegionSourceDatabase.getInstance().clear();
        RegionSurfaceLodService.getInstance().clear();
        MapTextureManager.getInstance().clearCache();
        MapOverviewTextureManager.getInstance().clearCache();
        MapLightManager.getInstance().flushAndClear();
        CaveMapManager.getInstance().flushDataForDimensionSwitch();
        CaveTextureManager.getInstance().suspendForDimensionSwitch();
        ChunkScanner.getInstance().reset();
        com.velorise.simplemap.client.renderer.FullscreenMapFramebufferRenderer.getInstance().invalidate();
        com.velorise.simplemap.client.renderer.MinimapFramebufferRenderer.getInstance().invalidate();
        MapRenderer.getInstance().clearFullscreenFallback();
        configureDimension(storageId, resourceId);
        MapSessionManager.getInstance().open(currentWorldId, resourceId);
    }

    private void clearSurfaceDimensionData() {
        synchronized (loadedRegions) {
            for (Region region : loadedRegions.values()) region.close();
            loadedRegions.clear();
        }
        synchronized (dirtyRegions) {
            dirtyRegions.clear();
        }
        regionFileExists.clear();
        currentDimensionDir = null;
    }

    private void configureDimension(String storageId, String resourceId) {
        currentDimensionId = storageId;
        currentDimensionResourceId = resourceId;
        currentDimensionDir = new File(currentWorldDir, storageId);
        if (!currentDimensionDir.exists() && !currentDimensionDir.mkdirs()) {
            LOGGER.warn("Could not create SimpleMap dimension directory {}", currentDimensionDir);
        }
        writeDimensionMetadata(currentDimensionDir, resourceId);
        currentDimensionProfile = DimensionMapProfile.resolve(
                currentDimensionDir.toPath(), resourceId);

        MapLightManager.getInstance().setCacheDirectory(
                new File(currentWorldDir, "light_cache/" + storageId));
        // Cave storage is dimension-scoped, but never dimension-disabled. The engine
        // keeps the same per-dimension index for Surface, Layered and Full modes;
        // the selected cave-start controls projection without destroying or
        // bypassing the archive. Open dimensions such as The End therefore retain
        // their index while AUTO normally resolves to the surface view.
        CaveMapManager.getInstance().setBaseDirectory(
                new File(currentWorldDir, "caves/" + storageId), currentDimensionDir);
        scanRegionFiles(currentDimensionDir);
        currentDimensionHasSavedData = !regionFileExists.isEmpty()
                || hasAnyMapFiles(currentDimensionDir)
                || hasAnyMapFiles(new File(currentWorldDir, "caves/" + storageId));
        lastSavedDataProbeMs = System.currentTimeMillis();
        discoveredDimensionsSnapshot = List.copyOf(scanDiscoveredDimensions());
        loadPin();
        LOGGER.info("SimpleMap view switched to dimension {} at {}", resourceId, currentDimensionDir);
    }

    /** Canonical ids available to the switcher. Only
     * dimensions that have been visited or have saved map data on disk are selectable. */
    public List<String> getSelectableDimensions() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (liveDimensionResourceId != null && !liveDimensionResourceId.isBlank()) {
            result.add(canonicalDimensionId(liveDimensionResourceId));
        }
        for (String visited : visitedDimensionsInWorld) {
            if (visited != null && !visited.isBlank()) {
                result.add(canonicalDimensionId(visited));
            }
        }
        List<String> discovered = scanDiscoveredDimensions();
        discoveredDimensionsSnapshot = List.copyOf(discovered);
        result.addAll(discovered);
        return new ArrayList<>(result);
    }

    public List<String> getDiscoveredDimensions() {
        return new ArrayList<>(discoveredDimensionsSnapshot);
    }

    private List<String> scanDiscoveredDimensions() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (currentWorldDir == null || !currentWorldDir.isDirectory()) return new ArrayList<>();
        File[] subFiles = currentWorldDir.listFiles();
        if (subFiles != null) {
            for (File file : subFiles) {
                if (!file.isDirectory() || file.getName().startsWith(".")
                        || file.getName().equals("light_cache") || file.getName().equals("caves")) continue;
                File caveDir = new File(currentWorldDir, "caves/" + file.getName());
                if (hasAnyMapFiles(file) || hasAnyMapFiles(caveDir)) {
                    result.add(readDimensionMetadata(file, inferDimensionResourceId(file.getName())));
                }
            }
        }
        File cavesDir = new File(currentWorldDir, "caves");
        File[] caveSub = cavesDir.listFiles();
        if (caveSub != null) {
            for (File file : caveSub) {
                if (!file.isDirectory() || file.getName().startsWith(".")) continue;
                File surfaceDir = new File(currentWorldDir, file.getName());
                if (hasAnyMapFiles(file) || hasAnyMapFiles(surfaceDir)) {
                    result.add(readDimensionMetadata(surfaceDir,
                            inferDimensionResourceId(file.getName())));
                }
            }
        }
        return new ArrayList<>(result);
    }

    public boolean isViewingLiveDimension() {
        return viewDimensionOverride == null;
    }

    public String getViewDimensionOverride() {
        return viewDimensionOverride;
    }

    /**
     * Returns the coordinate scale multiplier from the player's live dimension
     * to the currently viewed map dimension (e.g., 8.0 when in Nether viewing Overworld,
     * or 0.125 when in Overworld viewing Nether).
     */
    public double getCrossDimensionCoordinateScale() {
        if (viewDimensionOverride == null) return 1.0;
        DimensionMapProfile liveProfile = getDimensionProfile(liveDimensionResourceId);
        DimensionMapProfile viewedProfile = currentDimensionProfile != null
                ? currentDimensionProfile : getDimensionProfile(currentDimensionResourceId);
        double liveScale = liveProfile != null ? liveProfile.coordinateScale() : 1.0;
        double viewedScale = viewedProfile != null ? viewedProfile.coordinateScale() : 1.0;
        double ratio = liveScale / Math.max(0.0001, viewedScale);
        return (Double.isFinite(ratio) && ratio > 0.0) ? ratio : 1.0;
    }

    /**
     * Dimension fence for data sampled from the live ClientLevel.
     *
     * <p>The fullscreen map can point this manager at a saved remote dimension
     * while packets continue to mutate Minecraft's live level. Callers that own
     * live block/light samples must pass this fence immediately before committing;
     * otherwise Overworld samples can be persisted into an End/Nether region.</p>
     */
    public boolean acceptsLiveLevel(Level level) {
        return level != null
                && viewDimensionOverride == null
                && canonicalDimensionId(level.dimension().location().toString())
                        .equals(currentDimensionResourceId);
    }

    public String getCurrentDimensionResourceId() {
        return currentDimensionResourceId;
    }

    /** Metadata of the map dimension currently being rendered/browsed. */
    public DimensionMapProfile getCurrentDimensionProfile() {
        return currentDimensionProfile;
    }

    /** Persisted metadata for any discovered dimension in the current world. */
    public synchronized DimensionMapProfile getDimensionProfile(String dimensionId) {
        String canonical = canonicalDimensionId(dimensionId);
        if (canonical.equals(currentDimensionResourceId)) return currentDimensionProfile;
        if (currentWorldDir == null) return DimensionMapProfile.fallback(canonical);
        File directory = new File(currentWorldDir, dimensionStorageId(canonical));
        return DimensionMapProfile.resolve(directory.toPath(), canonical);
    }

    public String getLiveDimensionResourceId() {
        return liveDimensionResourceId;
    }

    /** Stable namespace for texture-cache keys. */
    public String getDimensionCacheKey() {
        return currentDimensionId == null || currentDimensionId.isBlank()
                ? "unknown" : currentDimensionId;
    }

    public boolean hasSavedDataForCurrentDimension() {
        if (!regionFileExists.isEmpty() || currentDimensionHasSavedData) return true;
        long now = System.currentTimeMillis();
        if (now - lastSavedDataProbeMs < 1_000L) return false;
        lastSavedDataProbeMs = now;
        File cave = currentWorldDir == null ? null
                : new File(currentWorldDir, "caves/" + currentDimensionId);
        currentDimensionHasSavedData = hasAnyMapFiles(currentDimensionDir)
                || hasAnyMapFiles(cave)
                || CaveMapManager.getInstance().hasAnyMcaFilesForCurrentDimension();
        return currentDimensionHasSavedData;
    }

    private static String translateOrFallback(String key, String fallback) {
        try {
            net.minecraft.locale.Language lang = net.minecraft.locale.Language.getInstance();
            if (lang != null && lang.has(key)) {
                return Component.translatable(key).getString();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public static String displayDimensionName(String resourceId) {
        String canonical = canonicalDimensionId(resourceId);
        if (canonical.equals("minecraft:overworld")) return translateOrFallback("gui.simplemap.dim.overworld", "Overworld");
        if (canonical.equals("minecraft:the_nether")) return translateOrFallback("gui.simplemap.dim.the_nether", "Nether");
        if (canonical.equals("minecraft:the_end")) return translateOrFallback("gui.simplemap.dim.the_end", "The End");
        int colon = canonical.indexOf(':');
        String namespace = colon >= 0 ? canonical.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? canonical.substring(colon + 1) : canonical;
        String pretty = java.util.Arrays.stream(path.split("[_/.-]+"))
                .filter(part -> !part.isBlank())
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
        return namespace.equals("minecraft") ? pretty : pretty + " (" + namespace + ")";
    }

    private boolean hasAnyMapFiles(File dir) {
        return hasAnyMapFiles(dir, 0);
    }

    private boolean hasAnyMapFiles(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 4) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile()) {
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith(".smdat") || name.endsWith(".scdat")) return true;
            } else if (file.isDirectory() && hasAnyMapFiles(file, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCeilingDimension(String resourceId, DimensionMapProfile profile) {
        return (profile != null && profile.hasCeiling())
                || "minecraft:the_nether".equals(resourceId);
    }

    private File getMcaDirectory(String targetDimId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || !mc.isLocalServer() || mc.getSingleplayerServer() == null) return null;
        try {
            var server = mc.getSingleplayerServer();
            var root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
            String dimId = (targetDimId == null || targetDimId.isBlank())
                    ? currentDimensionResourceId : targetDimId;
            if ("minecraft:overworld".equals(dimId)) {
                return root.resolve("region").toFile();
            } else if ("minecraft:the_nether".equals(dimId)) {
                return root.resolve("DIM-1").resolve("region").toFile();
            } else if ("minecraft:the_end".equals(dimId)) {
                return root.resolve("DIM1").resolve("region").toFile();
            } else {
                net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(dimId);
                if (rl != null) {
                    return root.resolve("dimensions")
                            .resolve(rl.getNamespace())
                            .resolve(rl.getPath())
                            .resolve("region").toFile();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean hasMcaRegionFile(String targetDimId, int rx, int rz) {
        File mcaDir = getMcaDirectory(targetDimId);
        if (mcaDir == null || !mcaDir.isDirectory()) return false;
        File mca = new File(mcaDir, "r." + rx + "." + rz + ".mca");
        return mca.isFile() && mca.length() >= 8192L;
    }

    private void scanRegionFiles(File directory) {
        regionFileExists.clear();
        if (directory != null && directory.isDirectory()) {
            String[] files = directory.list();
            if (files != null) {
                for (String name : files) {
                    if (name.startsWith("r.") && name.endsWith(".smdat")) {
                        String[] parts = name.split("\\.");
                        if (parts.length == 4) {
                            try {
                                int rx = Integer.parseInt(parts[1]);
                                int rz = Integer.parseInt(parts[2]);
                                regionFileExists.put(key(rx, rz), true);
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
        }
        if (isCeilingDimension(currentDimensionResourceId, currentDimensionProfile)) {
            File mcaDir = getMcaDirectory(currentDimensionResourceId);
            if (mcaDir != null && mcaDir.isDirectory()) {
                File[] mcaFiles = mcaDir.listFiles();
                if (mcaFiles != null) {
                    for (File f : mcaFiles) {
                        String name = f.getName();
                        if (name.startsWith("r.") && name.endsWith(".mca") && f.length() >= 8192L) {
                            String[] parts = name.split("\\.");
                            if (parts.length == 4) {
                                try {
                                    int rx = Integer.parseInt(parts[1]);
                                    int rz = Integer.parseInt(parts[2]);
                                    regionFileExists.put(key(rx, rz), true);
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            }
        }
    }

    private void loadLearningState(File worldDirectory) {
        File learnedFile = new File(worldDirectory, "map_learned.dat");
        hasLearnedMap = learnedFile.exists();
        File idFile = new File(worldDirectory, "last_book_id.dat");
        if (!idFile.exists()) {
            lastLearnedBookId = "";
            return;
        }
        try {
            String storedId = Files.readString(idFile.toPath(), StandardCharsets.UTF_8).trim();
            lastLearnedBookId = isCanonicalBookId(storedId) ? storedId : "";
            if (lastLearnedBookId.isEmpty()) Files.deleteIfExists(idFile.toPath());
        } catch (IOException exception) {
            lastLearnedBookId = "";
        }
    }

    public List<String> getLoadedRegionKeysSnapshot() {
        synchronized (loadedRegions) {
            List<String> keys = new ArrayList<>(loadedRegions.size());
            LongIterator iterator = loadedRegions.keySet().iterator();
            while (iterator.hasNext()) {
                long packed = iterator.nextLong();
                keys.add(regionX(packed) + "," + regionZ(packed));
            }
            return keys;
        }
    }

    public MapBlockData getBlockData(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region != null && region.isLoaded()) {
            MapBlockData data = region.getBlockData(blockX & 511, blockZ & 511);
            if (data != null && !data.isEmpty()) return data;
        }
        return SurfaceRegionSourceDatabase.getInstance().getBlockData(blockX, blockZ);
    }

    /**
     * Hot-path counterpart to {@link #getBlockData}. Unknown/empty columns return
     * {@link MapBlockData#EMPTY_PACKED}; callers that only inspect flags/heights do
     * not need to allocate a decoded object.
     */
    public long getPackedBlockData(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region != null && region.isLoaded()) {
            long packed = region.getPackedBlockData(blockX & 511, blockZ & 511);
            if (!MapBlockData.isEmpty(packed)) return packed;
        }
        return SurfaceRegionSourceDatabase.getInstance().getPackedBlockData(blockX, blockZ);
    }

    public int getSurfaceTint(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null || !region.isLoaded()) return SurfaceTintData.UNKNOWN;
        return region.getTint(blockX & 511, blockZ & 511);
    }

    public String getBlockId(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region != null && region.isLoaded()) {
            String blockId = region.getBlockId(blockX & 511, blockZ & 511);
            if (blockId != null && !blockId.isBlank()) return blockId;
        }
        return SurfaceRegionSourceDatabase.getInstance().getBlockId(blockX, blockZ);
    }

    public String getBiomeId(int blockX, int blockZ) {
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region != null && region.isLoaded()) {
            String biomeId = region.getBiomeId(blockX & 511, blockZ & 511);
            if (biomeId != null && !biomeId.isBlank()) return biomeId;
        }
        return SurfaceRegionSourceDatabase.getInstance().getBiomeId(blockX, blockZ);
    }

    /** O(1) chunk-completion probe used by the chunk-granular live writer. */
    public boolean isChunkSurfaceComplete(int chunkX, int chunkZ) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        return region != null && region.isLoaded()
                && region.isChunkSurfaceComplete((blockX & 511) >>> 4,
                        (blockZ & 511) >>> 4);
    }

    /** O(1) count used to reject legacy/placeholder chunks completed as all EMPTY. */
    public int knownSurfaceColumnCount(int chunkX, int chunkZ) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        return region == null || !region.isLoaded() ? 0
                : region.knownSurfaceColumnCount((blockX & 511) >>> 4,
                        (blockZ & 511) >>> 4);
    }

    public int getColor(int blockX, int blockZ) {
        long packed = getPackedBlockData(blockX, blockZ);
        if (MapBlockData.isEmpty(packed)) return 0;
        Region region = getRegion(blockX >> 9, blockZ >> 9, false);
        if (region == null) return 0;
        List<String> palette = region.snapshotBlockPalette();
        int index = MapBlockData.blockId(packed) & 0xFFFF;
        if (index >= palette.size()) return 0;
        Integer argb = MapTextureManager.getInstance().getBlockColor(palette.get(index));
        if (argb == null) return 0;
        return argbToAbgr(argb);
    }

    public void setBlockData(int blockX, int blockZ, MapBlockData data) {
        setBlockData(blockX, blockZ, data, SurfaceTintData.UNKNOWN);
    }

    public void setBlockData(int blockX, int blockZ, MapBlockData data, int tint) {
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        Region region = getRegion(regionX, regionZ, true);
        if (region == null || !region.isLoaded()) return;
        if (region.setBlockData(blockX & 511, blockZ & 511, data, tint)) {
            markColumnDirty(blockX, blockZ);
            RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
            if (stamp != null) {
                int localChunkX = (blockX & 511) >>> 4;
                int localChunkZ = (blockZ & 511) >>> 4;
                int localChunkIndex = localChunkZ * 32 + localChunkX;
                MapWorkGraph.getInstance().markSourceChunkDirty(stamp,
                        regionX, regionZ, localChunkIndex,
                        region.sourceRevision());
                SurfaceRegionSourceDatabase.getInstance().markChunkDirty(
                        regionX, regionZ, localChunkIndex);
            }
        }
    }

    /**
     * Commits one contiguous row-major slice of a Minecraft 16x16 chunk as a
     * single Surface transaction. Column scanners still resolve the authoritative
     * block/biome/tint payload on the client thread. Region storage is committed
     * once per slice; exact-page invalidation, persistence and retained-source
     * publication happen only when the full 16x16 transaction reaches cursor 256.
     */
    public SurfaceChunkCommit commitSurfaceChunkSlice(int chunkX, int chunkZ,
            int startColumn, long[] packedPixels, int[] surfaceTints,
            boolean[] validColumns, int sourceOffset, int count) {
        if (packedPixels == null || surfaceTints == null || validColumns == null
                || startColumn < 0 || startColumn >= 256 || sourceOffset < 0
                || count <= 0 || sourceOffset + count > packedPixels.length
                || sourceOffset + count > surfaceTints.length
                || sourceOffset + count > validColumns.length) {
            return SurfaceChunkCommit.empty();
        }
        int safeCount = Math.min(count, 256 - startColumn);
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        Region region = getRegion(regionX, regionZ, true);
        if (region == null || !region.isLoaded()) return SurfaceChunkCommit.empty();
        int localChunkX = (blockX & 511) >>> 4;
        int localChunkZ = (blockZ & 511) >>> 4;
        SurfaceChunkCommit result = region.commitSurfaceChunkSlice(
                localChunkX, localChunkZ, startColumn, packedPixels,
                surfaceTints, validColumns, sourceOffset, safeCount);
        // Exact/source/persistence authority remains on the previous complete
        // 16x16 tile until finishSurfaceChunkTransaction() at cursor 256.
        return result;
    }

    /** Publishes one completed 16x16 Surface scan transaction to downstream work. */
    public SurfaceChunkCommit finishSurfaceChunkTransaction(int chunkX, int chunkZ) {
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        Region region = getRegion(regionX, regionZ, false);
        if (region == null || !region.isLoaded()) return SurfaceChunkCommit.empty();
        int localChunkX = (blockX & 511) >>> 4;
        int localChunkZ = (blockZ & 511) >>> 4;
        long revision = region.finishSurfaceChunkTransaction(localChunkX, localChunkZ);
        if (revision <= 0L) return new SurfaceChunkCommit(0, false, revision);
        markRegionStorageDirty(regionX, regionZ);
        MapTextureManager.getInstance().markPageDirtyForChunk(chunkX, chunkZ);
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        if (stamp != null) {
            int localChunkIndex = localChunkZ * 32 + localChunkX;
            MapWorkGraph.getInstance().markSourceChunkDirty(stamp,
                    regionX, regionZ, localChunkIndex, revision);
            SurfaceRegionSourceDatabase.getInstance().markChunkDirty(
                    regionX, regionZ, localChunkIndex);
        }
        return new SurfaceChunkCommit(0, true, revision);
    }

    public void markRegionDirty(int regionX, int regionZ) {
        markRegionStorageDirty(regionX, regionZ);
        MapTextureManager.getInstance().markRegionDirty(regionX, regionZ);
        // Far-zoom standalone overview textures are a compatibility/fallback path
        // beside the page-rooted LOD tree. Keep their source revision in sync with
        // live scans so they do not remain stale until MapScreen is opened.
        MapOverviewTextureManager.getInstance().markSurfaceRegionDirty(regionX, regionZ);
    }

    private void markColumnDirty(int blockX, int blockZ) {
        int regionX = blockX >> 9;
        int regionZ = blockZ >> 9;
        markRegionStorageDirty(regionX, regionZ);
        MapTextureManager.getInstance().markPageDirtyForBlock(blockX, blockZ);
    }

    private void markRegionStorageDirty(int regionX, int regionZ) {
        long key = key(regionX, regionZ);
        synchronized (dirtyRegions) {
            dirtyRegions.add(key);
        }
        regionFileExists.put(key, true);
    }

    public Region getRegion(int regionX, int regionZ, boolean loadIfMissing) {
        long key = key(regionX, regionZ);
        synchronized (loadedRegions) {
            Region existing = loadedRegions.getAndMoveToLast(key);
            if (existing != null || !loadIfMissing || currentDimensionDir == null) return existing;

            long generation = worldGeneration.get();
            Region created = new Region(regionX, regionZ, generation);
            loadedRegions.putAndMoveToLast(key, created);
            File directory = currentDimensionDir;
            if (hasRegionFile(regionX, regionZ)) {
                loadRegionAsync(created, directory, generation);
            } else {
                created.markLoaded();
            }
            evictOldRegions();
            return created;
        }
    }

    public void requestRegionLoad(int regionX, int regionZ) {
        getRegion(regionX, regionZ, true);
    }

    /**
     * M2 bridge: keep SOURCE_READ running until this region's async disk read
     * has actually committed its CPU data. A queue admission is not a source
     * completion.
     */
    public boolean requestRegionLoad(MapWorkKey workKey, long sourceRevision) {
        if (workKey == null || !isGenerationCurrent(sourceRevision)) return false;
        Region region = getRegion(workKey.regionX(), workKey.regionZ(), true);
        if (region == null) return false;
        region.registerSourceCompletion(workKey, sourceRevision);
        return true;
    }

    public void unloadRegion(int regionX, int regionZ) {
        long key = key(regionX, regionZ);
        Region removed;
        synchronized (loadedRegions) {
            removed = loadedRegions.remove(key);
        }
        synchronized (dirtyRegions) {
            dirtyRegions.remove(key);
        }
        regionFileExists.remove(key);
        if (removed != null) removed.close();
    }

    public void invalidateRegionFile(int regionX, int regionZ) {
        regionFileExists.remove(key(regionX, regionZ));
    }

    public boolean hasRegionFile(int regionX, int regionZ) {
        File directory = currentDimensionDir;
        if (directory == null) return false;
        long key = key(regionX, regionZ);
        synchronized (regionFileExists) {
            if (regionFileExists.containsKey(key)) return regionFileExists.get(key);
        }
        boolean exists = RegionDataStore.hasFile(directory, regionX, regionZ)
                || (isCeilingDimension(currentDimensionResourceId, currentDimensionProfile)
                        && hasMcaRegionFile(currentDimensionResourceId, regionX, regionZ));
        synchronized (regionFileExists) {
            regionFileExists.put(key, exists);
        }
        return exists;
    }

    public boolean isRegionLoadedInCache(int regionX, int regionZ) {
        synchronized (loadedRegions) {
            return loadedRegions.containsKey(key(regionX, regionZ));
        }
    }

    public String getCurrentDimensionId() {
        return currentDimensionId;
    }

    /** Resolves vanilla names, cache folder ids and advertised modded ids to a registry id. */
    public synchronized String resolveDimensionResourceId(String value) {
        if (value == null || value.isBlank() || "LIVE".equalsIgnoreCase(value)) {
            return liveDimensionResourceId;
        }
        if (value.equals(currentDimensionId)) return currentDimensionResourceId;
        if (value.equals(liveDimensionId)) return liveDimensionResourceId;
        // Render overlays pass canonical registry ids. Return those without
        // connection reflection or a cache-directory discovery walk.
        if (value.indexOf(':') >= 0) return canonicalDimensionId(value);
        for (String candidate : getSelectableDimensions()) {
            if (value.equals(candidate) || value.equals(dimensionStorageId(candidate))) {
                return canonicalDimensionId(candidate);
            }
        }
        return canonicalDimensionId(value);
    }

    public static String normalizeDimensionResourceId(String value) {
        return canonicalDimensionId(value);
    }

    public File getCurrentDimensionDir() {
        return currentDimensionDir;
    }

    /**
     * Re-colors and reloads all saved regions without removing the currently
     * displayed image. Existing CPU/GPU pages remain the visual fallback while
     * replacement revisions are built and uploaded region by region.
     */
    public List<Long> loadedRegionCoordinates() {
        List<Long> coords = new ArrayList<>();
        synchronized (loadedRegions) {
            for (Region region : loadedRegions.values()) {
                if (region != null && region.isLoaded() && region.hasAnyData()) {
                    coords.add(key(region.rx, region.rz));
                }
            }
        }
        return coords;
    }

    /**
     * Lightweight visual refresh when cycling Map Brightness (Day / Auto / Night).
     * Never invalidates SurfaceLodTree, disk caches, or region textures.
     */
    public void onNightModeChanged() {
        com.velorise.simplemap.client.renderer.FullscreenMapFramebufferRenderer.getInstance().invalidate();
        com.velorise.simplemap.client.renderer.MinimapFramebufferRenderer.getInstance().invalidate();
    }

    public void reloadAllRegions() {
        MapSessionManager.getInstance().bumpStyleGeneration();
        MapTextureManager.getInstance().clearDerivedColorCaches();
        MapVisualClassifier.getInstance().clear();
        MapVisualClassifier.MapBlockEntityVisualResolver.getInstance().clear();
        com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager.CaveStateClassifier.getInstance().clear();

        // Do not increment worldGeneration and do not clear any texture/data store.
        // Generation changes are reserved for a real world/dimension replacement.
        SurfaceRegionSourceDatabase.getInstance().invalidateStyle();
        MapTextureManager.getInstance().invalidateStyle();
        MapOverviewTextureManager.getInstance().invalidateStyle();
        CaveTextureManager.getInstance().invalidateStyle();
        com.velorise.simplemap.client.renderer.FullscreenMapFramebufferRenderer.getInstance().invalidate();
        com.velorise.simplemap.client.renderer.MinimapFramebufferRenderer.getInstance().invalidate();
        MapRenderer.getInstance().clearFullscreenFallback();

        // Do not enqueue every .smdat file in filesystem order. That old path
        // saturated the worker queue and made Reload All reveal random distant
        // islands. The viewport coordinator now re-admits visible regions through
        // the deterministic region sweep; off-screen regions are restyled lazily.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            ChunkScanner.getInstance().requestRefresh(mc);
        }
    }

    /** Includes disk regions plus loaded unsaved regions for map-book snapshots. */
    public List<String> getKnownRegionNamesForBook(int maximum) {
        int limit = Math.max(0, maximum);
        TreeSet<String> names = new TreeSet<>();
        File directory = currentDimensionDir;
        if (directory != null) {
            File[] files = directory.listFiles((ignored, name) ->
                    name != null && name.matches("r\\.-?\\d{1,7}\\.-?\\d{1,7}\\.smdat"));
            if (files != null) {
                for (File file : files) names.add(file.getName());
            }
        }
        synchronized (loadedRegions) {
            for (Region region : loadedRegions.values()) {
                if (region.isLoaded() && region.hasAnyData()) {
                    names.add(RegionDataStore.fileName(region.rx, region.rz));
                }
            }
        }
        List<String> result = new ArrayList<>(Math.min(limit, names.size()));
        for (String name : names) {
            if (result.size() >= limit) break;
            result.add(name);
        }
        return result;
    }

    /** Returns a canonical snapshot of a loaded region, including unsaved edits. */
    public RegionDataStore.StoredRegion snapshotStoredRegion(int rx, int rz) {
        synchronized (loadedRegions) {
            Region region = loadedRegions.get(key(rx, rz));
            if (region == null || !region.isLoaded()) return null;
            RegionSnapshot snapshot = region.snapshot();
            return new RegionDataStore.StoredRegion(snapshot.pixels(), snapshot.tints(),
                    snapshot.biomePalette(), snapshot.blockPalette(),
                    snapshot.completeChunks());
        }
    }

    private void loadRegionAsync(Region region, File directory, long generation) {
        if (!region.beginLoad()) return;
        // Keep the same Region object in the loaded map while IO is saturated.
        // The system keeps requested regions in a pull queue; repeatedly removing and
        // recreating them wastes allocations and can leave visible holes near the
        // viewport centre.
        MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                MapRequestLane.FULLSCREEN, MapWorkScheduler.WorkType.DISK_READ,
                MapRequestLane.FULLSCREEN.priorityBase(), 24,
                () -> isLoadTargetCurrent(region, directory, generation), () -> {
            try {
                long[] pixels = new long[PIXEL_COUNT];
                int[] tints = new int[PIXEL_COUNT];
                long[] completeChunks = new long[RegionDataStore.COMPLETE_CHUNK_WORDS];
                Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
                Arrays.fill(tints, SurfaceTintData.UNKNOWN);
                List<String> biomePalette = new ArrayList<>();
                List<String> blockPalette = new ArrayList<>();
                boolean hasSmdat = RegionDataStore.hasFile(directory, region.rx, region.rz);
                boolean loaded = hasSmdat && RegionDataStore.load(directory, region.rx, region.rz,
                        pixels, tints, completeChunks, biomePalette, blockPalette);
                if (hasSmdat && !loaded) {
                    quarantineRegion(directory, region.rx, region.rz, generation,
                            "corrupt", "unreadable");
                } else if (loaded && isLoadTargetCurrent(region, directory, generation)
                        && hasForeignVanillaBiome(currentDimensionResourceId,
                                pixels, biomePalette)) {
                    MapDebugRecorder.getInstance().event(
                            "SURFACE_REGION_DIMENSION_MISMATCH",
                            "dimension=" + currentDimensionResourceId
                                    + " region=" + region.rx + ',' + region.rz
                                    + " action=quarantine_and_rebuild");
                    quarantineRegion(directory, region.rx, region.rz, generation,
                            "dimension-mismatch", "cross-dimension");
                    Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
                    Arrays.fill(tints, SurfaceTintData.UNKNOWN);
                    Arrays.fill(completeChunks, 0L);
                    biomePalette.clear();
                    blockPalette.clear();
                    loaded = false;
                }
                if (!isLoadTargetCurrent(region, directory, generation)) return;
                boolean backfilledFromAnvil = false;
                if (isCeilingDimension(currentDimensionResourceId, currentDimensionProfile)) {
                    backfilledFromAnvil = backfillCeilingSurfaceFromAnvil(
                            region.rx, region.rz, currentDimensionResourceId,
                            pixels, tints, completeChunks, biomePalette, blockPalette);
                    if (backfilledFromAnvil) {
                        loaded = true;
                    }
                    region.anvilBackfilled = true;
                }
                if (!isLoadTargetCurrent(region, directory, generation)) return;
                region.applyLoadedData(pixels, tints, completeChunks,
                        biomePalette, blockPalette);
                region.markLoaded();
                region.completeSourceCompletions();
                if (loaded) {
                    MapTextureManager.getInstance()
                            .markRegionSourceAvailable(region.rx, region.rz);
                }
                if (backfilledFromAnvil) {
                    markRegionDirty(region.rx, region.rz);
                }
                synchronized (loadedRegions) {
                    evictOldRegions();
                }
            } finally {
                region.finishLoad();
            }
        });
    }

    public void ensureCeilingSurfaceRegionsBackfilled() {
        File directory = currentDimensionDir;
        long generation = worldGeneration.get();
        if (directory == null || !isCeilingDimension(currentDimensionResourceId, currentDimensionProfile)) {
            return;
        }
        List<Region> candidates = new ArrayList<>();
        synchronized (loadedRegions) {
            for (Region region : loadedRegions.values()) {
                if (region != null && region.isLoaded() && !region.anvilBackfilled) {
                    region.anvilBackfilled = true;
                    candidates.add(region);
                }
            }
        }
        for (Region region : candidates) {
            MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                    MapRequestLane.FULLSCREEN, MapWorkScheduler.WorkType.DISK_READ,
                    MapRequestLane.FULLSCREEN.priorityBase(), 24,
                    () -> isLoadTargetCurrent(region, directory, generation), () -> {
                        RegionSnapshot snap = region.snapshot();
                        long[] pixels = snap.pixels();
                        int[] tints = snap.tints();
                        long[] completeChunks = snap.completeChunks();
                        List<String> biomePalette = new ArrayList<>(Arrays.asList(snap.biomePalette()));
                        List<String> blockPalette = new ArrayList<>(Arrays.asList(snap.blockPalette()));
                        if (backfillCeilingSurfaceFromAnvil(region.rx, region.rz,
                                currentDimensionResourceId, pixels, tints, completeChunks,
                                biomePalette, blockPalette)
                                && isLoadTargetCurrent(region, directory, generation)) {
                            region.applyLoadedData(pixels, tints, completeChunks,
                                    biomePalette, blockPalette);
                            MapTextureManager.getInstance()
                                    .markRegionSourceAvailable(region.rx, region.rz);
                            markRegionDirty(region.rx, region.rz);
                        }
                    });
        }
    }

    private boolean backfillCeilingSurfaceFromAnvil(int rx, int rz, String dimensionId,
            long[] pixels, int[] tints, long[] completeChunks,
            List<String> biomePalette, List<String> blockPalette) {
        if (pixels == null || tints == null || completeChunks == null) return false;
        boolean allComplete = true;
        for (int i = 0; i < completeChunks.length; i++) {
            if (completeChunks[i] != -1L) {
                allComplete = false;
                break;
            }
        }
        if (allComplete) return false;

        File mcaDir = getMcaDirectory(dimensionId);
        if (mcaDir == null) return false;
        File mcaFile = new File(mcaDir, "r." + rx + "." + rz + ".mca");
        if (!mcaFile.isFile() || mcaFile.length() < 8192L) return false;

        Map<String, Integer> biomeMap = new HashMap<>();
        for (int i = 0; i < biomePalette.size(); i++) {
            biomeMap.put(biomePalette.get(i), i);
        }
        Map<String, Integer> blockMap = new HashMap<>();
        for (int i = 0; i < blockPalette.size(); i++) {
            blockMap.put(blockPalette.get(i), i);
        }

        boolean backfilledAny = false;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mcaFile, "r")) {
            byte[] header = new byte[4096];
            raf.readFully(header);
            byte[] chunkDataBuffer = new byte[65536];

            for (int cz = 0; cz < 32; cz++) {
                for (int cx = 0; cx < 32; cx++) {
                    int chunkIndex = (cz << 5) | cx;
                    int sampleIdx = ((cz << 4) + 8) * 512 + (cx << 4) + 8;
                    if (SurfaceChunkCoverage.isComplete(completeChunks, chunkIndex)
                            && !MapBlockData.isEmpty(pixels[sampleIdx])
                            && MapBlockData.topY(pixels[sampleIdx]) >= 123) {
                        continue;
                    }
                    int offset = chunkIndex * 4;
                    int sectorOffset = ((header[offset] & 0xFF) << 16)
                            | ((header[offset + 1] & 0xFF) << 8)
                            | (header[offset + 2] & 0xFF);
                    int sectorCount = header[offset + 3] & 0xFF;
                    if (sectorOffset < 2 || sectorCount == 0) continue;

                    long byteOffset = (long) sectorOffset * 4096L;
                    if (byteOffset + 5 > raf.length()) continue;
                    raf.seek(byteOffset);
                    int length = raf.readInt();
                    if (length <= 1 || length > sectorCount * 4096) continue;
                    byte compressionType = raf.readByte();
                    int dataLength = length - 1;
                    if (chunkDataBuffer.length < dataLength) {
                        chunkDataBuffer = new byte[Math.max(dataLength, chunkDataBuffer.length * 2)];
                    }
                    raf.readFully(chunkDataBuffer, 0, dataLength);

                    java.io.InputStream in;
                    if (compressionType == 2) {
                        in = new java.util.zip.InflaterInputStream(
                                new java.io.ByteArrayInputStream(chunkDataBuffer, 0, dataLength));
                    } else if (compressionType == 1) {
                        in = new java.util.zip.GZIPInputStream(
                                new java.io.ByteArrayInputStream(chunkDataBuffer, 0, dataLength));
                    } else if (compressionType == 3) {
                        in = new java.io.ByteArrayInputStream(chunkDataBuffer, 0, dataLength);
                    } else {
                        continue;
                    }

                    net.minecraft.nbt.CompoundTag tag;
                    try (java.io.DataInputStream dis = new java.io.DataInputStream(in)) {
                        tag = net.minecraft.nbt.NbtIo.read(
                                dis, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (tag == null || !tag.contains("sections", net.minecraft.nbt.Tag.TAG_LIST)) {
                        continue;
                    }
                    net.minecraft.nbt.ListTag sections = tag.getList(
                            "sections", net.minecraft.nbt.Tag.TAG_COMPOUND);
                    if (sections.isEmpty()) continue;

                    net.minecraft.nbt.CompoundTag sec7 = null;
                    net.minecraft.nbt.CompoundTag sec8 = null;
                    for (int i = 0; i < sections.size(); i++) {
                        net.minecraft.nbt.CompoundTag st = sections.getCompound(i);
                        int sy = st.getByte("Y");
                        if (sy == 7) sec7 = st;
                        else if (sy == 8) sec8 = st;
                    }
                    if (sec7 == null && sec8 == null) continue;

                    AnvilRoofSection roof8 = sec8 != null ? AnvilRoofSection.decode(sec8) : null;
                    AnvilRoofSection roof7 = sec7 != null ? AnvilRoofSection.decode(sec7) : null;
                    if (roof7 == null && roof8 == null) continue;

                    int baseX = cx << 4;
                    int baseZ = cz << 4;
                    boolean chunkFilled = false;
                    for (int lz = 0; lz < 16; lz++) {
                        int row = (baseZ + lz) * 512 + baseX;
                        for (int lx = 0; lx < 16; lx++) {
                            int idx = row + lx;
                            if (!MapBlockData.isEmpty(pixels[idx])
                                    && MapBlockData.topY(pixels[idx]) >= 123) {
                                chunkFilled = true;
                                continue;
                            }
                            int foundY = Integer.MIN_VALUE;
                            String blockName = null;
                            String biomeName = null;
                            int blockLight = 0;
                            if (roof8 != null) {
                                for (int ly = 15; ly >= 0; ly--) {
                                    String candidate = roof8.getBlockName(lx, ly, lz);
                                    if (!isAirOrBarrierBlockName(candidate)) {
                                        foundY = 128 + ly;
                                        blockName = candidate;
                                        biomeName = roof8.getBiomeName(lx, ly, lz);
                                        blockLight = roof8.getBlockLight(lx, Math.min(15, ly + 1), lz);
                                        break;
                                    }
                                }
                            }
                            if (foundY == Integer.MIN_VALUE && roof7 != null) {
                                for (int ly = 15; ly >= 11; ly--) {
                                    String candidate = roof7.getBlockName(lx, ly, lz);
                                    if (!isAirOrBarrierBlockName(candidate)) {
                                        foundY = 112 + ly;
                                        blockName = candidate;
                                        biomeName = roof7.getBiomeName(lx, ly, lz);
                                        blockLight = (ly < 15)
                                                ? roof7.getBlockLight(lx, ly + 1, lz)
                                                : (roof8 != null ? roof8.getBlockLight(lx, 0, lz) : 0);
                                        break;
                                    }
                                }
                            }
                            if (foundY == Integer.MIN_VALUE || blockName == null) continue;
                            if (biomeName == null || biomeName.isBlank()
                                    || "minecraft:plains".equals(biomeName)) {
                                biomeName = "minecraft:nether_wastes";
                            }
                            int blkIdx = internPaletteEntry(blockName, blockPalette, blockMap, 65534);
                            int bioIdx = internPaletteEntry(biomeName, biomePalette, biomeMap, 254);
                            pixels[idx] = MapBlockData.packRaw((short) foundY, (short) blkIdx,
                                    (byte) bioIdx, (byte) Math.max(0, Math.min(15, blockLight)));
                            tints[idx] = SurfaceTintData.NONE;
                            chunkFilled = true;
                        }
                    }
                    if (chunkFilled) {
                        SurfaceChunkCoverage.markComplete(completeChunks, chunkIndex);
                        backfilledAny = true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return backfilledAny;
    }

    private static boolean isAirOrBarrierBlockName(String name) {
        return name == null || name.isEmpty()
                || "minecraft:air".equals(name)
                || "minecraft:cave_air".equals(name)
                || "minecraft:void_air".equals(name)
                || "minecraft:barrier".equals(name)
                || "minecraft:light".equals(name);
    }

    private static int internPaletteEntry(String value, List<String> palette,
            Map<String, Integer> map, int maxIndex) {
        Integer existing = map.get(value);
        if (existing != null) return existing;
        if (palette.size() >= maxIndex) return maxIndex;
        int idx = palette.size();
        palette.add(value);
        map.put(value, idx);
        return idx;
    }

    private static final class AnvilRoofSection {
        final String[] blockNames;
        final long[] data;
        final int bits;
        final int valuesPerLong;
        final long mask;
        final boolean singleBlock;
        final byte[] blockLight;
        final String[] biomeNames;
        final long[] biomeData;
        final int biomeBits;
        final int biomesPerLong;
        final long biomeMask;
        final boolean singleBiome;

        private AnvilRoofSection(String[] blockNames, long[] data, int bits,
                int valuesPerLong, long mask, boolean singleBlock, byte[] blockLight,
                String[] biomeNames, long[] biomeData, int biomeBits,
                int biomesPerLong, long biomeMask, boolean singleBiome) {
            this.blockNames = blockNames;
            this.data = data;
            this.bits = bits;
            this.valuesPerLong = valuesPerLong;
            this.mask = mask;
            this.singleBlock = singleBlock;
            this.blockLight = blockLight;
            this.biomeNames = biomeNames;
            this.biomeData = biomeData;
            this.biomeBits = biomeBits;
            this.biomesPerLong = biomesPerLong;
            this.biomeMask = biomeMask;
            this.singleBiome = singleBiome;
        }

        static AnvilRoofSection decode(net.minecraft.nbt.CompoundTag secTag) {
            if (!secTag.contains("block_states", net.minecraft.nbt.Tag.TAG_COMPOUND)) return null;
            net.minecraft.nbt.CompoundTag bs = secTag.getCompound("block_states");
            if (!bs.contains("palette", net.minecraft.nbt.Tag.TAG_LIST)) return null;
            net.minecraft.nbt.ListTag pal = bs.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
            int size = pal.size();
            if (size == 0) return null;
            String[] names = new String[size];
            for (int i = 0; i < size; i++) {
                names[i] = pal.getCompound(i).getString("Name");
            }
            byte[] bl = null;
            if (secTag.contains("BlockLight", net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
                byte[] raw = secTag.getByteArray("BlockLight");
                if (raw.length == 2048) bl = raw;
            }
            String[] bNames = null;
            long[] bData = null;
            int bBits = 0;
            int bPerLong = 1;
            long bMask = 0L;
            boolean singleBio = true;
            if (secTag.contains("biomes", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                net.minecraft.nbt.CompoundTag bio = secTag.getCompound("biomes");
                if (bio.contains("palette", net.minecraft.nbt.Tag.TAG_LIST)) {
                    net.minecraft.nbt.ListTag bPal = bio.getList("palette", net.minecraft.nbt.Tag.TAG_STRING);
                    int bSize = bPal.size();
                    if (bSize > 0) {
                        bNames = new String[bSize];
                        for (int i = 0; i < bSize; i++) bNames[i] = bPal.getString(i);
                        if (bSize > 1 && bio.contains("data", net.minecraft.nbt.Tag.TAG_LONG_ARRAY)) {
                            long[] rawBio = bio.getLongArray("data");
                            if (rawBio.length > 0) {
                                bData = rawBio;
                                bBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(bSize - 1));
                                bPerLong = 64 / bBits;
                                bMask = (1L << bBits) - 1L;
                                singleBio = false;
                            }
                        }
                    }
                }
            }
            if (size == 1) {
                return new AnvilRoofSection(names, null, 0, 1, 0L, true, bl,
                        bNames, bData, bBits, bPerLong, bMask, singleBio);
            }
            long[] data = bs.getLongArray("data");
            if (data.length == 0) return null;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(size - 1));
            int valuesPerLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            return new AnvilRoofSection(names, data, bits, valuesPerLong, mask, false, bl,
                    bNames, bData, bBits, bPerLong, bMask, singleBio);
        }

        String getBlockName(int lx, int ly, int lz) {
            if (singleBlock) return blockNames[0];
            int blockIdx = (ly << 8) | (lz << 4) | lx;
            int longIdx = blockIdx / valuesPerLong;
            if (longIdx < 0 || longIdx >= data.length) return blockNames[0];
            int bitOff = (blockIdx % valuesPerLong) * bits;
            int idx = (int) ((data[longIdx] >>> bitOff) & mask);
            return (idx >= 0 && idx < blockNames.length) ? blockNames[idx] : "minecraft:air";
        }

        String getBiomeName(int lx, int ly, int lz) {
            if (biomeNames == null || biomeNames.length == 0) return null;
            if (singleBiome || biomeData == null) return biomeNames[0];
            int quartIdx = ((ly >> 2) << 4) | ((lz >> 2) << 2) | (lx >> 2);
            int longIdx = quartIdx / biomesPerLong;
            if (longIdx < 0 || longIdx >= biomeData.length) return biomeNames[0];
            int bitOff = (quartIdx % biomesPerLong) * biomeBits;
            int idx = (int) ((biomeData[longIdx] >>> bitOff) & biomeMask);
            return (idx >= 0 && idx < biomeNames.length) ? biomeNames[idx] : biomeNames[0];
        }

        int getBlockLight(int lx, int ly, int lz) {
            if (blockLight == null || blockLight.length < 2048) return 0;
            int blockIdx = (ly << 8) | (lz << 4) | lx;
            int byteIdx = blockIdx >> 1;
            if (byteIdx < 0 || byteIdx >= blockLight.length) return 0;
            int b = blockLight[byteIdx] & 0xFF;
            return (blockIdx & 1) == 0 ? (b & 0x0F) : (b >>> 4);
        }
    }

    private boolean isLoadTargetCurrent(Region region, File directory, long generation) {
        if (!isGenerationCurrent(generation) || currentDimensionDir == null
                || !currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) return false;
        synchronized (loadedRegions) {
            return loadedRegions.get(key(region.rx, region.rz)) == region;
        }
    }

    public void tickSave() {
        if (MapActivityGate.getInstance().blocksMapWork()) return;
        long now = System.currentTimeMillis();
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.player == null) return;

        double playerX = minecraft.player.getX();
        double playerZ = minecraft.player.getZ();
        if (Double.isNaN(lastPlayerX)) {
            lastPlayerX = playerX;
            lastPlayerZ = playerZ;
            lastPlayerMovementMs = now;
            return;
        }
        double dx = playerX - lastPlayerX;
        double dz = playerZ - lastPlayerZ;
        lastPlayerX = playerX;
        lastPlayerZ = playerZ;
        if (dx * dx + dz * dz > PERSIST_MOVEMENT_EPSILON_SQ) {
            lastPlayerMovementMs = now;
            return;
        }

        if (now - lastPlayerMovementMs < PERSIST_PLAYER_IDLE_BEFORE_SAVE_MS
                || MapMutationBus.getInstance().pendingColumns() != 0
                || MapMutationBus.getInstance().pendingChunks() != 0
                || MapMutationBus.getInstance().pendingRegions() != 0
                || MapWorkScheduler.ioQueuedCount() != 0) {
            return;
        }

        switch (nextSubsystem++ % 3) {
            case 0 -> {
                if (now >= nextSurfacePumpMs) {
                    int remaining = pumpDirtyRegionSaves(1);
                    nextSurfacePumpMs = now + (remaining > 0
                            ? PERSIST_ACTIVE_RETRY_MS : PERSIST_SURFACE_IDLE_INTERVAL_MS);
                }
            }
            case 1 -> {
                if (now >= nextLightPumpMs) {
                    int remaining = MapLightManager.getInstance()
                            .pumpDirtyRegionSaves(1);
                    nextLightPumpMs = now + (remaining > 0
                            ? PERSIST_ACTIVE_RETRY_MS : PERSIST_LIGHT_IDLE_INTERVAL_MS);
                }
            }
            default -> CaveMapManager.getInstance().tickSave();
        }
    }

    public void resetPersistence() {
        nextSurfacePumpMs = 0L;
        nextLightPumpMs = 0L;
        lastPlayerMovementMs = 0L;
        lastPlayerX = Double.NaN;
        lastPlayerZ = Double.NaN;
        nextSubsystem = 0;
    }

    /** Pulls at most {@code maximum} dirty surface regions into persistence. */
    int pumpDirtyRegionSaves(int maximum) {
        File directory = currentDimensionDir;
        if (directory == null || maximum <= 0) return dirtyRegionCount();
        int admitted = 0;
        while (admitted < maximum) {
            long selected;
            synchronized (dirtyRegions) {
                LongIterator iterator = dirtyRegions.iterator();
                if (!iterator.hasNext()) break;
                selected = iterator.nextLong();
                iterator.remove();
            }
            Region region;
            synchronized (loadedRegions) {
                region = loadedRegions.get(selected);
            }
            if (region == null || !region.isLoaded()) continue;
            if (!RegionDataStore.canAcceptSave(directory, region.rx, region.rz)
                    || !saveRegionSnapshot(region, directory, false)) {
                synchronized (dirtyRegions) {
                    dirtyRegions.add(selected);
                }
                break;
            }
            admitted++;
        }
        return dirtyRegionCount();
    }

    int dirtyRegionCount() {
        synchronized (dirtyRegions) {
            return dirtyRegions.size();
        }
    }

    public synchronized void flushAndClear() {
        saveDirtyRegionsAsync();
        MapSessionManager.getInstance().closeActive();
        resetPersistence();
        worldGeneration.incrementAndGet();
        MapProcessor.getInstance().clear();
        clearCache();
        SurfaceRegionSourceDatabase.getInstance().clear();
        RegionSurfaceLodService.getInstance().clear();
        MapTextureManager.getInstance().clearCache();
        MapOverviewTextureManager.getInstance().clearCache();
        MapLightManager.getInstance().flushAndClear();
        CaveMapManager.getInstance().flushAndClear();
        CaveTextureManager.getInstance().clearCache();
        ChunkScanner.getInstance().reset();
        com.velorise.simplemap.client.renderer.FullscreenMapFramebufferRenderer.getInstance().invalidate();
        com.velorise.simplemap.client.renderer.MinimapFramebufferRenderer.getInstance().invalidate();
        MapRenderer.getInstance().clearFullscreenFallback();
    }

    private void clearCache() {
        synchronized (loadedRegions) {
            for (Region region : loadedRegions.values()) region.close();
            loadedRegions.clear();
        }
        synchronized (dirtyRegions) {
            dirtyRegions.clear();
        }
        regionFileExists.clear();
        currentDimensionDir = null;
        currentWorldDir = null;
        currentWorldId = "";
        currentDimensionId = "";
        currentDimensionResourceId = "minecraft:overworld";
        currentDimensionProfile = DimensionMapProfile.fallback("minecraft:overworld");
        liveDimensionId = "overworld";
        liveDimensionResourceId = "minecraft:overworld";
        clearObservedWorldIdentity();
        viewDimensionOverride = null;
        currentDimensionHasSavedData = false;
        lastSavedDataProbeMs = 0L;
        discoveredDimensionsSnapshot = List.of();
        MapConfig.pinActive = false;
        MapConfig.pinWorldX = 0;
        MapConfig.pinWorldZ = 0;
        MapConfig.pinRouteStartValid = false;
        MapConfig.pinRouteStartWorldX = 0;
        MapConfig.pinRouteStartWorldZ = 0;
    }

    private void saveDirtyRegionsAsync() {
        File directory = currentDimensionDir;
        if (directory == null) return;
        LongOpenHashSet keys;
        synchronized (dirtyRegions) {
            if (dirtyRegions.isEmpty()) return;
            keys = new LongOpenHashSet(dirtyRegions);
            dirtyRegions.clear();
        }
        // Dimension/world replacement is the rare forced-flush path. Snapshot all
        // remaining dirty regions before their canonical CPU objects are closed.
        LongIterator dirtyIterator = keys.iterator();
        while (dirtyIterator.hasNext()) {
            long key = dirtyIterator.nextLong();
            Region region;
            synchronized (loadedRegions) {
                region = loadedRegions.get(key);
            }
            if (region != null && region.isLoaded()) saveRegionSnapshot(region, directory, true);
        }
    }

    private boolean saveRegionSnapshot(Region region, File directory, boolean force) {
        if (region == null || directory == null || !region.isLoaded()) return false;
        if (!force && !RegionDataStore.canAcceptSave(directory, region.rx, region.rz)) return false;
        RegionSnapshot snapshot = region.snapshot();
        RevisionStamp stamp = MapSessionManager.getInstance().activeStamp();
        MapWorkKey workKey = stamp == null ? null : new MapWorkKey(stamp,
                region.rx, region.rz, MapWorkStage.CACHE_COMMIT, Integer.MIN_VALUE);
        MapWorkGraph graph = MapWorkGraph.getInstance();
        if (workKey != null) graph.request(workKey, snapshot.sourceRevision(),
                RegionRecord.ALL_LEAVES);
        final long persistenceWorldIdentity = (currentWorldId == null ? 0L
                : currentWorldId.hashCode() * 0x9E3779B97F4A7C15L)
                ^ directory.getAbsolutePath().hashCode();
        final long persistenceStyleRevision = stamp == null
                ? 0L : stamp.styleGeneration();

        java.util.function.Consumer<RegionDataStore.SaveResult> completion = result -> {
            if (workKey == null) return;
            if (result.success()) {
                graph.markCacheCommitted(workKey, snapshot.sourceRevision(),
                        RegionRecord.ALL_LEAVES);
                MapPersistenceV2Service.getInstance().appendSurface(directory,
                        persistenceWorldIdentity, region.rx, region.rz,
                        snapshot.sourceRevision(), persistenceStyleRevision,
                        new RegionDataStore.StoredRegion(snapshot.pixels(),
                                snapshot.tints(), snapshot.biomePalette(),
                                snapshot.blockPalette(), snapshot.completeChunks()));
            } else {
                graph.markCacheDirty(workKey, snapshot.sourceRevision(),
                        RegionRecord.ALL_LEAVES);
            }
        };

        boolean accepted;
        if (force) {
            RegionDataStore.saveAsync(directory, region.rx, region.rz,
                    snapshot.pixels(), snapshot.tints(), snapshot.biomePalette(),
                    snapshot.blockPalette(), snapshot.completeChunks(), completion);
            accepted = true;
        } else {
            accepted = RegionDataStore.trySaveOwnedAsync(directory, region.rx, region.rz,
                    snapshot.pixels(), snapshot.tints(), snapshot.biomePalette(),
                    snapshot.blockPalette(), snapshot.completeChunks(), completion);
        }
        if (accepted) regionFileExists.put(key(region.rx, region.rz), true);
        else if (workKey != null) graph.markCacheDirty(workKey,
                snapshot.sourceRevision(), RegionRecord.ALL_LEAVES);
        return accepted;
    }

    private void evictOldRegions() {
        while (loadedRegions.size() > MAX_LOADED_REGIONS) {
            long selectedKey = Long.MIN_VALUE;
            Region region = null;
            for (Long2ObjectMap.Entry<Region> candidate
                    : loadedRegions.long2ObjectEntrySet()) {
                if (candidate.getValue().isLoaded()) {
                    selectedKey = candidate.getLongKey();
                    region = candidate.getValue();
                    break;
                }
            }
            if (region == null) return;
            loadedRegions.remove(selectedKey);
            boolean dirty;
            synchronized (dirtyRegions) {
                dirty = dirtyRegions.remove(selectedKey);
            }
            if (dirty && currentDimensionDir != null
                    && !saveRegionSnapshot(region, currentDimensionDir, false)) {
                loadedRegions.putAndMoveToLast(selectedKey, region);
                synchronized (dirtyRegions) {
                    dirtyRegions.add(selectedKey);
                }
                return;
            }
            region.close();
        }
    }

    private static boolean hasForeignVanillaBiome(String dimension,
            long[] pixels, List<String> biomePalette) {
        if (pixels == null || biomePalette == null
                || !("minecraft:the_end".equals(dimension)
                        || "minecraft:the_nether".equals(dimension)
                        || "minecraft:overworld".equals(dimension))) return false;
        int foreignColumns = 0;
        for (long packed : pixels) {
            if (MapBlockData.isEmpty(packed)) continue;
            int index = MapBlockData.biomeId(packed) & 0xFF;
            if (index >= biomePalette.size()) continue;
            String biome = biomePalette.get(index);
            if (!isForeignVanillaBiome(dimension, biome)) continue;
            if (++foreignColumns >= FOREIGN_DIMENSION_COLUMN_THRESHOLD) return true;
        }
        return false;
    }

    private static boolean isForeignVanillaBiome(String dimension, String biome) {
        if (biome == null || !biome.startsWith("minecraft:")
                || "minecraft:plains".equals(biome)) return false;
        boolean end = switch (biome) {
            case "minecraft:the_end", "minecraft:end_highlands",
                    "minecraft:end_midlands", "minecraft:end_barrens",
                    "minecraft:small_end_islands" -> true;
            default -> false;
        };
        boolean nether = switch (biome) {
            case "minecraft:nether_wastes", "minecraft:soul_sand_valley",
                    "minecraft:crimson_forest", "minecraft:warped_forest",
                    "minecraft:basalt_deltas" -> true;
            default -> false;
        };
        if ("minecraft:the_end".equals(dimension)) return !end;
        if ("minecraft:the_nether".equals(dimension)) return !nether;
        return end || nether;
    }

    private void quarantineRegion(File directory, int rx, int rz,
            long requestedGeneration, String suffix, String description) {
        File file = new File(directory, RegionDataStore.fileName(rx, rz));
        if (!file.isFile() || RegionDataStore.hasPending(directory, rx, rz)) return;
        File quarantine = new File(directory,
                file.getName() + "." + suffix + "." + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), quarantine.toPath());
            if (isGenerationCurrent(requestedGeneration)
                    && currentDimensionDir != null
                    && currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) {
                regionFileExists.put(key(rx, rz), false);
            }
            LOGGER.warn("Moved {} SimpleMap region to {}", description,
                    quarantine.getName());
        } catch (IOException exception) {
            if (isGenerationCurrent(requestedGeneration)
                    && currentDimensionDir != null
                    && currentDimensionDir.getAbsoluteFile().equals(directory.getAbsoluteFile())) {
                regionFileExists.remove(key(rx, rz));
            }
            LOGGER.warn("Could not quarantine {} SimpleMap region {}",
                    description, file, exception);
        }
    }


    private static boolean isCanonicalBookId(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void writeUtf8Atomic(Path target, String contents) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Atomic write target has no parent: " + target);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            moveReplacing(temporary, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static String multiplayerWorldId(String address) {
        String normalized = address == null ? "unknown" : address.trim().toLowerCase(Locale.ROOT);
        String readable = safePathComponent(normalized);
        if (readable.length() > 48) readable = readable.substring(0, 48);
        return "mp_" + readable + "_" + shortSha256(normalized);
    }

    private static File resolveMultiplayerWorldDirectory(Minecraft minecraft, String address, String worldId) {
        File savesRoot = new File(minecraft.gameDirectory, "simplemap/saves");
        File target = new File(savesRoot, worldId);
        String legacyId = "mp_" + safePathComponent(address);
        File legacy = new File(savesRoot, legacyId);
        if (!target.exists() && legacy.isDirectory() && !legacy.equals(target)) {
            try {
                Files.createDirectories(savesRoot.toPath());
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
                LOGGER.info("Migrated legacy multiplayer map cache {} -> {}",
                        legacy.getName(), target.getName());
            } catch (IOException atomicFailure) {
                try {
                    Files.move(legacy.toPath(), target.toPath());
                    LOGGER.info("Migrated legacy multiplayer map cache {} -> {}",
                            legacy.getName(), target.getName());
                } catch (IOException moveFailure) {
                    LOGGER.warn("Could not migrate legacy multiplayer map cache {}; continuing to use it",
                            legacy, moveFailure);
                    return legacy;
                }
            }
        }
        return target;
    }

    private static String shortSha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                builder.append(String.format("%02x", digest[index] & 0xFF));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toUnsignedString(value.hashCode(), 16);
        }
    }

    private static String canonicalDimensionId(String value) {
        if (value == null || value.isBlank() || "LIVE".equalsIgnoreCase(value)) {
            return "minecraft:overworld";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("overworld") || normalized.equals("minecraft_overworld")) {
            return "minecraft:overworld";
        }
        if (normalized.equals("nether") || normalized.equals("the_nether")
                || normalized.equals("minecraft_the_nether")) {
            return "minecraft:the_nether";
        }
        if (normalized.equals("end") || normalized.equals("the_end")
                || normalized.equals("minecraft_the_end")) {
            return "minecraft:the_end";
        }
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private static String dimensionStorageId(String resourceId) {
        String canonical = canonicalDimensionId(resourceId);
        int colon = canonical.indexOf(':');
        String namespace = colon >= 0 ? canonical.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? canonical.substring(colon + 1) : canonical;
        return namespace.equals("minecraft") ? safePathComponent(path)
                : safePathComponent(namespace + ":" + path);
    }

    private static String inferDimensionResourceId(String storageId) {
        if (storageId == null) return "minecraft:overworld";
        if (storageId.equals("overworld")) return "minecraft:overworld";
        if (storageId.equals("the_nether") || storageId.equals("nether")) return "minecraft:the_nether";
        if (storageId.equals("the_end") || storageId.equals("end")) return "minecraft:the_end";
        // Legacy custom directories replaced ':' with '_'. Metadata written on the
        // next live visit removes this ambiguity; until then preserve the folder id.
        return storageId.contains(":") ? storageId : "simplemap-cache:" + storageId;
    }

    private static void writeDimensionMetadata(File directory, String resourceId) {
        if (directory == null || resourceId == null || resourceId.isBlank()) return;
        try {
            Files.createDirectories(directory.toPath());
            writeUtf8Atomic(directory.toPath().resolve(".dimension_id"),
                    canonicalDimensionId(resourceId) + "\n");
        } catch (IOException exception) {
            LOGGER.debug("Could not write dimension metadata for {}", directory, exception);
        }
    }

    private static String readDimensionMetadata(File directory, String fallback) {
        if (directory == null) return fallback;
        File metadata = new File(directory, ".dimension_id");
        if (!metadata.isFile()) return fallback;
        try {
            String value = Files.readString(metadata.toPath(), StandardCharsets.UTF_8).trim();
            return value.isBlank() ? fallback : canonicalDimensionId(value);
        } catch (IOException exception) {
            return fallback;
        }
    }

    private static String safePathComponent(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String cleaned = value.trim().replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_");
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (cleaned.isBlank()) return "unknown";
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    private static int argbToAbgr(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private static long key(int regionX, int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFF_FFFFL);
    }

    private static int regionX(long key) { return (int) (key >> 32); }
    private static int regionZ(long key) { return (int) key; }

    public static final class Region {
        public final int rx;
        public final int rz;
        private final long generation;
        private final long[] pixels = new long[PIXEL_COUNT];
        private final int[] tints = new int[PIXEL_COUNT];
        private final int[] knownColumnsPerPage = new int[MapPageLayout.PAGES_PER_REGION * MapPageLayout.PAGES_PER_REGION];
        /** One exact count per Minecraft chunk; 256 means the tile is publishable. */
        private final short[] knownColumnsPerChunk = new short[32 * 32];
        /** A completed scan is authoritative even when some/all columns are void. */
        private final long[] completeChunks =
                new long[RegionDataStore.COMPLETE_CHUNK_WORDS];
        /** O(1) explored-data test; maintained with page/chunk coverage counters. */
        private int knownColumnCount;
        private final List<String> biomePalette = new ArrayList<>();
        private final List<String> blockPalette = new ArrayList<>();
        private final Map<String, Integer> biomeIndex = new HashMap<>();
        private final Map<String, Integer> blockIndex = new HashMap<>();
        private final List<SourceCompletion> sourceCompletions = new ArrayList<>();
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean loaded;
        private volatile boolean closed;
        private volatile boolean anvilBackfilled;
        private final AtomicBoolean loadScheduled = new AtomicBoolean();
        private long sourceRevision = 1L;
        private long paletteRevision = 1L;
        private final long[] chunkRevisions = new long[32 * 32];
        /** Content changed during the current private 16x16 writer transaction. */
        private final boolean[] surfaceTransactionDirty = new boolean[32 * 32];

        private Region(int rx, int rz, long generation) {
            this.rx = rx;
            this.rz = rz;
            this.generation = generation;
            Arrays.fill(pixels, MapBlockData.EMPTY_PACKED);
            Arrays.fill(tints, SurfaceTintData.UNKNOWN);
        }

        public String getCacheKey() { return rx + "," + rz; }
        public boolean isLoaded() { return loaded && !closed; }
        public long getGeneration() { return generation; }
        public void lock() { lock.lock(); }
        public void unlock() { lock.unlock(); }
        public void close() {
            closed = true;
            deferSourceCompletions();
        }
        boolean beginLoad() { return !closed && loadScheduled.compareAndSet(false, true); }
        void finishLoad() { loadScheduled.set(false); }
        void markLoaded() { if (!closed) loaded = true; }
        void registerSourceCompletion(MapWorkKey key, long revision) {
            if (key == null) return;
            boolean completeNow = false;
            boolean deferNow = false;
            synchronized (sourceCompletions) {
                if (closed) deferNow = true;
                else if (loaded) completeNow = true;
                else sourceCompletions.add(new SourceCompletion(key, revision));
            }
            if (completeNow) MapWorkGraph.getInstance().complete(key, revision);
            if (deferNow) MapWorkGraph.getInstance().defer(key);
        }

        void completeSourceCompletions() {
            synchronized (sourceCompletions) {
                if (sourceCompletions.isEmpty()) return;
                for (int i = 0; i < sourceCompletions.size(); i++) {
                    SourceCompletion completion = sourceCompletions.get(i);
                    MapWorkGraph.getInstance().complete(completion.key(), completion.revision());
                }
                sourceCompletions.clear();
            }
        }

        private void deferSourceCompletions() {
            synchronized (sourceCompletions) {
                if (sourceCompletions.isEmpty()) return;
                for (int i = 0; i < sourceCompletions.size(); i++) {
                    SourceCompletion completion = sourceCompletions.get(i);
                    MapWorkGraph.getInstance().defer(completion.key());
                }
                sourceCompletions.clear();
            }
        }

        private record SourceCompletion(MapWorkKey key, long revision) {
        }

        public MapBlockData getBlockData(int pixelX, int pixelZ) {
            lock.lock();
            try {
                long packed = pixels[pixelZ * 512 + pixelX];
                return MapBlockData.isEmpty(packed) ? null : MapBlockData.unpack(packed);
            } finally {
                lock.unlock();
            }
        }

        public long getPackedBlockData(int pixelX, int pixelZ) {
            lock.lock();
            try {
                return pixels[pixelZ * 512 + pixelX];
            } finally {
                lock.unlock();
            }
        }

        public int getTint(int pixelX, int pixelZ) {
            lock.lock();
            try {
                return tints[pixelZ * 512 + pixelX];
            } finally {
                lock.unlock();
            }
        }

        public String getBlockId(int pixelX, int pixelZ) {
            lock.lock();
            try {
                long packed = pixels[pixelZ * 512 + pixelX];
                if (MapBlockData.isEmpty(packed)) return null;
                int index = MapBlockData.blockId(packed) & 0xFFFF;
                if (index < 0 || index >= blockPalette.size()) {
                    return null;
                }
                return blockPalette.get(index);
            } finally {
                lock.unlock();
            }
        }

        public String getBiomeId(int pixelX, int pixelZ) {
            lock.lock();
            try {
                long packed = pixels[pixelZ * 512 + pixelX];
                if (MapBlockData.isEmpty(packed)) return null;
                int index = MapBlockData.biomeId(packed) & 0xFF;
                if (index == (MapBlockData.NO_BIOME & 0xFF)
                        || index < 0 || index >= biomePalette.size()) {
                    return null;
                }
                return biomePalette.get(index);
            } finally {
                lock.unlock();
            }
        }

        /** O(1) exact-leaf explored-data test used by the fullscreen frontier. */
        public boolean hasAnyDataInPage(int pageX, int pageZ) {
            if (pageX < 0 || pageX >= MapPageLayout.PAGES_PER_REGION
                    || pageZ < 0 || pageZ >= MapPageLayout.PAGES_PER_REGION) {
                return false;
            }
            lock.lock();
            try {
                return knownColumnsPerPage[
                        pageZ * MapPageLayout.PAGES_PER_REGION + pageX] > 0;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns the 4x4 chunk mask whose source columns are complete. This is
         * the authoritative target coverage for progressive 16x16 publication.
         */
        public int completeChunkMaskInPage(int pageX, int pageZ) {
            if (pageX < 0 || pageX >= MapPageLayout.PAGES_PER_REGION
                    || pageZ < 0 || pageZ >= MapPageLayout.PAGES_PER_REGION) {
                return 0;
            }
            lock.lock();
            try {
                int result = 0;
                int startChunkX = pageX * MapPageLayout.SUBTILES_PER_PAGE;
                int startChunkZ = pageZ * MapPageLayout.SUBTILES_PER_PAGE;
                for (int localZ = 0; localZ < MapPageLayout.SUBTILES_PER_PAGE; localZ++) {
                    for (int localX = 0; localX < MapPageLayout.SUBTILES_PER_PAGE; localX++) {
                        int chunkIndex = (startChunkZ + localZ) * 32
                                + startChunkX + localX;
                        if (chunkCompleteUnsafe(chunkIndex)) {
                            result |= 1 << MapPageLayout.subtileIndex(localX, localZ);
                        }
                    }
                }
                return result;
            } finally {
                lock.unlock();
            }
        }

        public boolean isChunkSurfaceComplete(int localChunkX, int localChunkZ) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return false;
            lock.lock();
            try {
                return chunkCompleteUnsafe(localChunkZ * 32 + localChunkX);
            } finally {
                lock.unlock();
            }
        }

        public int knownSurfaceColumnCount(int localChunkX, int localChunkZ) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return 0;
            lock.lock();
            try {
                return knownColumnsPerChunk[localChunkZ * 32 + localChunkX]
                        & 0xFFFF;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Finishes the private row-major 16x16 writer transaction. A positive
         * revision means this transaction actually published new authority: either
         * some pixel/tint changed, or completion changed from unknown to complete.
         * Re-scanning an already-complete byte-identical chunk returns zero.
         *
         * <p>PASS120 returned the old positive chunk revision for every no-op scan.
         * MapManager interpreted that as a fresh publication and dirtied the exact
         * page plus its halo again, producing 4,084 atomic page refreshes in the
         * supplied ~248 s run. The system only updates a MapTileChunk buffer when its tile
         * transaction actually changed; mirror that invariant here.</p>
         */
        public long finishSurfaceChunkTransaction(int localChunkX, int localChunkZ) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return 0L;
            lock.lock();
            try {
                int chunkIndex = localChunkZ * 32 + localChunkX;
                boolean contentChanged = surfaceTransactionDirty[chunkIndex];
                surfaceTransactionDirty[chunkIndex] = false;
                boolean completionChanged = SurfaceChunkCoverage.markComplete(
                        completeChunks, chunkIndex);
                if (!contentChanged && !completionChanged) return 0L;
                if (!contentChanged) {
                    // Completion itself is source authority (notably v4->v5 water
                    // migration where retained pixels can compare byte-identical).
                    chunkRevisions[chunkIndex] = ++sourceRevision;
                }
                return chunkRevisions[chunkIndex];
            } finally {
                lock.unlock();
            }
        }

        private boolean chunkCompleteUnsafe(int chunkIndex) {
            return SurfaceChunkCoverage.isComplete(completeChunks, chunkIndex);
        }

        public boolean setBlockData(int pixelX, int pixelZ, MapBlockData data) {
            return setBlockData(pixelX, pixelZ, data, SurfaceTintData.UNKNOWN);
        }

        public boolean setBlockData(int pixelX, int pixelZ, MapBlockData data, int tint) {
            long packed = MapBlockData.pack(data);
            lock.lock();
            try {
                int index = pixelZ * 512 + pixelX;
                long previous = pixels[index];
                if (previous == packed && tints[index] == tint) return false;
                boolean wasKnown = !MapBlockData.isEmpty(previous);
                boolean isKnown = !MapBlockData.isEmpty(packed);
                if (wasKnown != isKnown) {
                    knownColumnCount += isKnown ? 1 : -1;
                    int pageX = pixelX / MapPageLayout.PAGE_SIZE;
                    int pageZ = pixelZ / MapPageLayout.PAGE_SIZE;
                    int pageIndex = pageZ * MapPageLayout.PAGES_PER_REGION + pageX;
                    knownColumnsPerPage[pageIndex] += isKnown ? 1 : -1;
                    int chunkIndex = (pixelZ >>> 4) * 32 + (pixelX >>> 4);
                    knownColumnsPerChunk[chunkIndex] += isKnown ? 1 : -1;
                }
                pixels[index] = packed;
                tints[index] = tint;
                long revision = ++sourceRevision;
                int chunkIndex = (pixelZ >>> 4) * 32 + (pixelX >>> 4);
                chunkRevisions[chunkIndex] = revision;
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** Applies one slice of one 16x16 chunk while holding the region lock once. */
        public SurfaceChunkCommit commitSurfaceChunkSlice(int localChunkX,
                int localChunkZ, int startColumn, long[] packedPixels,
                int[] surfaceTints, boolean[] validColumns,
                int sourceOffset, int count) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32
                    || startColumn < 0 || startColumn >= 256
                    || count <= 0) return SurfaceChunkCommit.empty();
            int safeCount = Math.min(count, 256 - startColumn);
            lock.lock();
            try {
                int changed = 0;
                int chunkIndex = localChunkZ * 32 + localChunkX;
                int chunkStartX = localChunkX << 4;
                int chunkStartZ = localChunkZ << 4;
                for (int offset = 0; offset < safeCount; offset++) {
                    int sourceIndex = sourceOffset + offset;
                    if (!validColumns[sourceIndex]) continue;
                    int column = startColumn + offset;
                    int pixelX = chunkStartX + (column & 15);
                    int pixelZ = chunkStartZ + (column >>> 4);
                    int index = pixelZ * 512 + pixelX;
                    long packed = packedPixels[sourceIndex];
                    int tint = surfaceTints[sourceIndex];
                    long previous = pixels[index];
                    // Preserve a durable non-empty observation if a transient live
                    // scan fails to find a surface while the chunk is changing.
                    if (MapBlockData.isEmpty(packed)
                            && !MapBlockData.isEmpty(previous)) continue;
                    if (previous == packed && tints[index] == tint) continue;
                    boolean wasKnown = !MapBlockData.isEmpty(previous);
                    boolean isKnown = !MapBlockData.isEmpty(packed);
                    if (wasKnown != isKnown) {
                        knownColumnCount += isKnown ? 1 : -1;
                        int pageX = pixelX / MapPageLayout.PAGE_SIZE;
                        int pageZ = pixelZ / MapPageLayout.PAGE_SIZE;
                        knownColumnsPerPage[pageZ * MapPageLayout.PAGES_PER_REGION
                                + pageX] += isKnown ? 1 : -1;
                        knownColumnsPerChunk[chunkIndex] += isKnown ? 1 : -1;
                    }
                    pixels[index] = packed;
                    tints[index] = tint;
                    changed++;
                }
                if (changed > 0) {
                    long revision = ++sourceRevision;
                    chunkRevisions[chunkIndex] = revision;
                    surfaceTransactionDirty[chunkIndex] = true;
                }
                return new SurfaceChunkCommit(changed,
                        chunkCompleteUnsafe(chunkIndex),
                        chunkRevisions[chunkIndex]);
            } finally {
                lock.unlock();
            }
        }

        /** Resolves both Surface palette indices under one region lock. */
        public long getOrAddSurfacePaletteIndices(String biomeId, String blockId) {
            String safeBiome = biomeId == null || biomeId.isBlank()
                    ? "minecraft:plains" : biomeId;
            String safeBlock = blockId == null || blockId.isBlank()
                    ? "minecraft:air" : blockId;
            lock.lock();
            try {
                Integer biome = biomeIndex.get(safeBiome);
                if (biome == null) {
                    biome = biomePalette.size() >= 255 ? 254 : biomePalette.size();
                    if (biome < 254 || biomePalette.size() < 255) {
                        biomePalette.add(safeBiome);
                        biomeIndex.put(safeBiome, biome);
                        paletteRevision++;
                    }
                }
                Integer block = blockIndex.get(safeBlock);
                if (block == null) {
                    block = blockPalette.size() >= 65_535
                            ? 65_534 : blockPalette.size();
                    if (block < 65_534 || blockPalette.size() < 65_535) {
                        blockPalette.add(safeBlock);
                        blockIndex.put(safeBlock, block);
                        paletteRevision++;
                    }
                }
                return ((long) biome << 32) | (block & 0xFFFF_FFFFL);
            } finally {
                lock.unlock();
            }
        }

        public int getOrAddBiomeIndex(String biomeId) {
            String safeId = biomeId == null || biomeId.isBlank() ? "minecraft:plains" : biomeId;
            lock.lock();
            try {
                Integer existing = biomeIndex.get(safeId);
                if (existing != null) return existing;
                if (biomePalette.size() >= 255) return 254;
                int index = biomePalette.size();
                biomePalette.add(safeId);
                biomeIndex.put(safeId, index);
                paletteRevision++;
                return index;
            } finally {
                lock.unlock();
            }
        }

        public int getOrAddBlockIndex(String blockId) {
            String safeId = blockId == null || blockId.isBlank() ? "minecraft:air" : blockId;
            lock.lock();
            try {
                Integer existing = blockIndex.get(safeId);
                if (existing != null) return existing;
                if (blockPalette.size() >= 65_535) return 65_534;
                int index = blockPalette.size();
                blockPalette.add(safeId);
                blockIndex.put(safeId, index);
                paletteRevision++;
                return index;
            } finally {
                lock.unlock();
            }
        }

        public long sourceRevision() {
            lock.lock();
            try { return sourceRevision; }
            finally { lock.unlock(); }
        }

        public long chunkRevision(int localChunkX, int localChunkZ) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return 0L;
            lock.lock();
            try { return chunkRevisions[localChunkZ * 32 + localChunkX]; }
            finally { lock.unlock(); }
        }

        public RegionSourcePalette snapshotSourcePalette() {
            return snapshotSourcePaletteIfChanged(Long.MIN_VALUE);
        }

        public RegionSourcePalette snapshotSourcePaletteIfChanged(
                long knownPaletteRevision) {
            lock.lock();
            try {
                if (knownPaletteRevision >= paletteRevision) return null;
                return RegionSourcePalette.owned(sourceRevision, paletteRevision,
                        biomePalette.toArray(new String[0]),
                        blockPalette.toArray(new String[0]));
            } finally {
                lock.unlock();
            }
        }

        public RegionChunkSnapshot snapshotChunk(int localChunkX, int localChunkZ,
                byte[] lightLevels) {
            if (localChunkX < 0 || localChunkX >= 32
                    || localChunkZ < 0 || localChunkZ >= 32) return null;
            lock.lock();
            try {
                int chunkIndex = localChunkZ * 32 + localChunkX;
                // The system exposes a MapTile only after its complete 16x16 payload is
                // available. Completion is observation state, not a count of
                // non-void blocks: a fully scanned End-island edge (or an entirely
                // void chunk) is authoritative even though fewer than 256 columns
                // contain a block. Requiring knownColumns == 256 permanently
                // rejected those chunks and produced the solid End-stone ribbons
                // surrounded by black tiles.
                if (!chunkCompleteUnsafe(chunkIndex)) return null;
                long[] chunkPixels = new long[16 * 16];
                int[] chunkTints = new int[16 * 16];
                int startX = localChunkX * 16;
                int startZ = localChunkZ * 16;
                byte[] resolvedLights = new byte[16 * 16];
                for (int z = 0; z < 16; z++) {
                    int source = (startZ + z) * 512 + startX;
                    int target = z * 16;
                    System.arraycopy(pixels, source, chunkPixels, target, 16);
                    System.arraycopy(tints, source, chunkTints, target, 16);
                    for (int x = 0; x < 16; x++) {
                        int idx = target + x;
                        long packed = chunkPixels[idx];
                        int packedLight = MapBlockData.isEmpty(packed) ? 0
                                : (MapBlockData.isGlowing(packed) ? 15 : MapBlockData.getBlockLight(packed));
                        int externalLight = (lightLevels != null && idx < lightLevels.length)
                                ? (lightLevels[idx] & 0xFF) : 0;
                        resolvedLights[idx] = (byte) Math.max(packedLight, externalLight);
                    }
                }
                return RegionChunkSnapshot.owned(localChunkX, localChunkZ,
                        chunkRevisions[chunkIndex],
                        chunkPixels, chunkTints, resolvedLights);
            } finally {
                lock.unlock();
            }
        }

        /**
         * Captures a supersampled level-0 fallback source for this 512x512
         * region. PASS117 took one centre sample for every 8x8 output texel. That
         * point sampling aliased ocean-floor detail into the regular dashed/grid
         * pattern visible at wide zoom. PASS118 samples every 4 blocks (128x128)
         * and the worker linearly reduces 2x2 styled samples into each 8x8 output
         * texel, matching filtered child-to-branch minification much more
         * closely without cloning the full 512x512 region.
         */
        public RegionLodSnapshot snapshotLodLevel0() {
            final int outputSize = 128;
            final int halo = 1;
            final int stride = outputSize + halo * 2;
            final int sampleStep = 4;
            lock.lock();
            try {
                long[] sampledPixels = new long[stride * stride];
                int[] sampledTints = new int[stride * stride];
                byte[] sampledKnown = new byte[stride * stride];
                int knownCells = 0;
                for (int sampleZ = 0; sampleZ < stride; sampleZ++) {
                    int localZ = Math.max(0, Math.min(511,
                            (sampleZ - halo) * sampleStep + sampleStep / 2));
                    int sourceRow = localZ * 512;
                    int targetRow = sampleZ * stride;
                    for (int sampleX = 0; sampleX < stride; sampleX++) {
                        int localX = Math.max(0, Math.min(511,
                                (sampleX - halo) * sampleStep + sampleStep / 2));
                        int target = targetRow + sampleX;
                        sampledPixels[target] = pixels[sourceRow + localX];
                        sampledTints[target] = tints[sourceRow + localX];
                        int chunkIndex = (localZ >>> 4) * 32 + (localX >>> 4);
                        if (chunkCompleteUnsafe(chunkIndex)) {
                            sampledKnown[target] = 1;
                            if (sampleX >= halo && sampleX < stride - halo
                                    && sampleZ >= halo && sampleZ < stride - halo) {
                                knownCells++;
                            }
                        }
                    }
                }
                return RegionLodSnapshot.owned(rx, rz, sourceRevision,
                        stride, halo, sampleStep, knownCells,
                        sampledPixels, sampledTints,
                        sampledKnown, biomePalette.toArray(new String[0]),
                        blockPalette.toArray(new String[0]));
            } finally {
                lock.unlock();
            }
        }

        public boolean hasAnyData() {
            lock.lock();
            try { return knownColumnCount > 0; }
            finally { lock.unlock(); }
        }

        public long[] snapshotPackedPixels() {
            lock.lock();
            try {
                return Arrays.copyOf(pixels, pixels.length);
            } finally {
                lock.unlock();
            }
        }

        public int[] snapshotTints() {
            lock.lock();
            try {
                return Arrays.copyOf(tints, tints.length);
            } finally {
                lock.unlock();
            }
        }

        /**
         * Copies only the requested local region rectangle. Page-rooted texture
         * jobs use this instead of cloning all 512x512 columns for every 64x64
         * leaf update. Palette arrays belong to this immutable window and allow
         * the caller to remap indices when a halo crosses a region boundary.
         */
        public RegionWindow snapshotWindow(int minX, int minZ, int width, int height) {
            if (minX < 0 || minZ < 0 || width <= 0 || height <= 0
                    || minX + width > MapPageLayout.REGION_SIZE
                    || minZ + height > MapPageLayout.REGION_SIZE) {
                throw new IllegalArgumentException("Invalid region window "
                        + minX + "," + minZ + " " + width + "x" + height);
            }
            lock.lock();
            try {
                long[] windowPixels = new long[width * height];
                int[] windowTints = new int[width * height];
                byte[] windowKnown = new byte[width * height];
                for (int z = 0; z < height; z++) {
                    int source = (minZ + z) * MapPageLayout.REGION_SIZE + minX;
                    int target = z * width;
                    System.arraycopy(pixels, source, windowPixels, target, width);
                    System.arraycopy(tints, source, windowTints, target, width);
                    int localZ = minZ + z;
                    for (int x = 0; x < width; x++) {
                        int chunkIndex = (localZ >>> 4) * 32
                                + ((minX + x) >>> 4);
                        if (chunkCompleteUnsafe(chunkIndex)) {
                            windowKnown[target + x] = 1;
                        }
                    }
                }
                return new RegionWindow(windowPixels, windowTints, windowKnown,
                        width, height,
                        biomePalette.toArray(new String[0]),
                        blockPalette.toArray(new String[0]));
            } finally {
                lock.unlock();
            }
        }

        /** Legacy snapshot API retained for compatibility with external code. */
        public MapBlockData[] snapshotPixels() {
            lock.lock();
            try {
                MapBlockData[] result = new MapBlockData[pixels.length];
                for (int i = 0; i < pixels.length; i++) {
                    if (!MapBlockData.isEmpty(pixels[i])) result[i] = MapBlockData.unpack(pixels[i]);
                }
                return result;
            } finally {
                lock.unlock();
            }
        }

        public List<String> snapshotBiomePalette() {
            lock.lock();
            try { return new ArrayList<>(biomePalette); }
            finally { lock.unlock(); }
        }

        public List<String> snapshotBlockPalette() {
            lock.lock();
            try { return new ArrayList<>(blockPalette); }
            finally { lock.unlock(); }
        }

        private RegionSnapshot snapshot() {
            lock.lock();
            try {
                return new RegionSnapshot(Arrays.copyOf(pixels, pixels.length),
                        Arrays.copyOf(tints, tints.length),
                        biomePalette.toArray(new String[0]), blockPalette.toArray(new String[0]),
                        Arrays.copyOf(completeChunks, completeChunks.length),
                        sourceRevision);
            } finally {
                lock.unlock();
            }
        }

        private void applyLoadedData(long[] loadedPixels, int[] loadedTints,
                long[] loadedCompleteChunks, List<String> biomes,
                List<String> blocks) {
            lock.lock();
            try {
                if (closed) return;
                System.arraycopy(loadedPixels, 0, pixels, 0, pixels.length);
                System.arraycopy(loadedTints, 0, tints, 0, tints.length);
                Arrays.fill(knownColumnsPerPage, 0);
                Arrays.fill(knownColumnsPerChunk, (short) 0);
                Arrays.fill(completeChunks, 0L);
                if (loadedCompleteChunks != null
                        && loadedCompleteChunks.length == completeChunks.length) {
                    System.arraycopy(loadedCompleteChunks, 0, completeChunks, 0,
                            completeChunks.length);
                }
                knownColumnCount = 0;
                for (int z = 0; z < 512; z++) {
                    int pageZ = z / MapPageLayout.PAGE_SIZE;
                    int row = z * 512;
                    for (int x = 0; x < 512; x++) {
                        if (MapBlockData.isEmpty(pixels[row + x])) continue;
                        knownColumnCount++;
                        int pageX = x / MapPageLayout.PAGE_SIZE;
                        knownColumnsPerPage[
                                pageZ * MapPageLayout.PAGES_PER_REGION + pageX]++;
                        knownColumnsPerChunk[(z >>> 4) * 32 + (x >>> 4)]++;
                    }
                }
                biomePalette.clear();
                biomePalette.addAll(biomes);
                blockPalette.clear();
                blockPalette.addAll(blocks);
                biomeIndex.clear();
                for (int i = 0; i < biomePalette.size(); i++) biomeIndex.put(biomePalette.get(i), i);
                blockIndex.clear();
                for (int i = 0; i < blockPalette.size(); i++) blockIndex.put(blockPalette.get(i), i);
                paletteRevision++;
                long loadedRevision = ++sourceRevision;
                Arrays.fill(chunkRevisions, loadedRevision);
            } finally {
                lock.unlock();
            }
        }
    }

    public record RegionWindow(long[] pixels, int[] tints, byte[] known,
            int width, int height,
            String[] biomePalette, String[] blockPalette) {
    }

    /** Immutable, ownership-transferred source for a 64x64 region LOD build. */
    public static final class RegionLodSnapshot {
        private final int regionX;
        private final int regionZ;
        private final long sourceRevision;
        private final int stride;
        private final int halo;
        private final int sampleStep;
        private final int knownCells;
        private final long[] pixels;
        private final int[] tints;
        private final byte[] known;
        private final String[] biomePalette;
        private final String[] blockPalette;

        private RegionLodSnapshot(int regionX, int regionZ,
                long sourceRevision, int stride, int halo, int sampleStep,
                int knownCells, long[] pixels, int[] tints, byte[] known,
                String[] biomePalette, String[] blockPalette) {
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.sourceRevision = Math.max(1L, sourceRevision);
            this.stride = stride;
            this.halo = halo;
            this.sampleStep = Math.max(1, sampleStep);
            this.knownCells = Math.max(0, knownCells);
            this.pixels = pixels;
            this.tints = tints;
            this.known = known;
            this.biomePalette = biomePalette;
            this.blockPalette = blockPalette;
        }

        static RegionLodSnapshot owned(int regionX, int regionZ,
                long sourceRevision, int stride, int halo, int sampleStep,
                int knownCells, long[] pixels, int[] tints, byte[] known,
                String[] biomePalette, String[] blockPalette) {
            return new RegionLodSnapshot(regionX, regionZ, sourceRevision,
                    stride, halo, sampleStep, knownCells, pixels, tints, known,
                    biomePalette, blockPalette);
        }

        public int regionX() { return regionX; }
        public int regionZ() { return regionZ; }
        public long sourceRevision() { return sourceRevision; }
        public int stride() { return stride; }
        public int halo() { return halo; }
        public int sampleStep() { return sampleStep; }
        public int knownCells() { return knownCells; }
        public long[] pixelsUnsafe() { return pixels; }
        public int[] tintsUnsafe() { return tints; }
        public byte[] knownUnsafe() { return known; }
        public String[] biomePaletteUnsafe() { return biomePalette; }
        public String[] blockPaletteUnsafe() { return blockPalette; }
    }

    public record SurfaceChunkCommit(int changedColumns,
            boolean chunkComplete, long chunkRevision) {
        private static SurfaceChunkCommit empty() {
            return new SurfaceChunkCommit(0, false, 0L);
        }
    }

    public static final class RegionSourcePalette {
        private final long sourceRevision;
        private final long paletteRevision;
        private final String[] biomePalette;
        private final String[] blockPalette;

        public RegionSourcePalette(long sourceRevision,
                String[] biomePalette, String[] blockPalette) {
            this(sourceRevision, sourceRevision, biomePalette, blockPalette, true);
        }

        public RegionSourcePalette(long sourceRevision, long paletteRevision,
                String[] biomePalette, String[] blockPalette) {
            this(sourceRevision, paletteRevision, biomePalette, blockPalette, true);
        }

        private RegionSourcePalette(long sourceRevision, long paletteRevision,
                String[] biomePalette, String[] blockPalette, boolean copy) {
            this.sourceRevision = Math.max(1L, sourceRevision);
            this.paletteRevision = Math.max(1L, paletteRevision);
            String[] safeBiomes = biomePalette == null ? new String[0] : biomePalette;
            String[] safeBlocks = blockPalette == null ? new String[0] : blockPalette;
            this.biomePalette = copy
                    ? Arrays.copyOf(safeBiomes, safeBiomes.length) : safeBiomes;
            this.blockPalette = copy
                    ? Arrays.copyOf(safeBlocks, safeBlocks.length) : safeBlocks;
        }

        static RegionSourcePalette owned(long sourceRevision,
                long paletteRevision, String[] biomePalette,
                String[] blockPalette) {
            return new RegionSourcePalette(sourceRevision, paletteRevision,
                    biomePalette, blockPalette, false);
        }

        public long sourceRevision() { return sourceRevision; }
        public long paletteRevision() { return paletteRevision; }
        public String[] biomePalette() {
            return Arrays.copyOf(biomePalette, biomePalette.length);
        }
        public String[] blockPalette() {
            return Arrays.copyOf(blockPalette, blockPalette.length);
        }
        String[] biomePaletteUnsafe() { return biomePalette; }
        String[] blockPaletteUnsafe() { return blockPalette; }
    }

    public static final class RegionChunkSnapshot {
        private final int localChunkX;
        private final int localChunkZ;
        private final long sourceRevision;
        private final long[] packedPixels;
        private final int[] tints;
        private final byte[] lightLevels;

        public RegionChunkSnapshot(int localChunkX, int localChunkZ,
                long sourceRevision, long[] packedPixels, int[] tints,
                byte[] lightLevels) {
            this(localChunkX, localChunkZ, sourceRevision,
                    packedPixels, tints, lightLevels, true);
        }

        private RegionChunkSnapshot(int localChunkX, int localChunkZ,
                long sourceRevision, long[] packedPixels, int[] tints,
                byte[] lightLevels, boolean copy) {
            if (packedPixels == null || packedPixels.length != 256
                    || tints == null || tints.length != 256
                    || (lightLevels != null && lightLevels.length != 256)) {
                throw new IllegalArgumentException("Region chunk snapshot requires 256 columns");
            }
            this.localChunkX = localChunkX;
            this.localChunkZ = localChunkZ;
            this.sourceRevision = Math.max(1L, sourceRevision);
            this.packedPixels = copy
                    ? Arrays.copyOf(packedPixels, packedPixels.length) : packedPixels;
            this.tints = copy ? Arrays.copyOf(tints, tints.length) : tints;
            this.lightLevels = lightLevels == null ? null
                    : copy ? Arrays.copyOf(lightLevels, lightLevels.length)
                    : lightLevels;
        }

        static RegionChunkSnapshot owned(int localChunkX, int localChunkZ,
                long sourceRevision, long[] packedPixels, int[] tints,
                byte[] lightLevels) {
            return new RegionChunkSnapshot(localChunkX, localChunkZ,
                    sourceRevision, packedPixels, tints, lightLevels, false);
        }

        public int localChunkX() { return localChunkX; }
        public int localChunkZ() { return localChunkZ; }
        public long sourceRevision() { return sourceRevision; }
        public long[] packedPixels() {
            return Arrays.copyOf(packedPixels, packedPixels.length);
        }
        public int[] tints() { return Arrays.copyOf(tints, tints.length); }
        public byte[] lightLevels() { return lightLevels == null ? null
                : Arrays.copyOf(lightLevels, lightLevels.length); }
        long[] packedPixelsUnsafe() { return packedPixels; }
        int[] tintsUnsafe() { return tints; }
        byte[] lightLevelsUnsafe() { return lightLevels; }
    }

    private record RegionSnapshot(long[] pixels, int[] tints,
            String[] biomePalette, String[] blockPalette, long[] completeChunks,
            long sourceRevision) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static class PinData {
        public boolean active;
        public double x;
        public double z;
        // Nullable for backward compatibility with pre-PASS128 pin.json files.
        public Boolean routeStartValid;
        public Double routeStartX;
        public Double routeStartZ;
    }

    private void loadPin() {
        if (currentDimensionDir == null) {
            resetPinState();
            return;
        }
        File file = new File(currentDimensionDir, "pin.json");
        if (!file.exists()) {
            resetPinState();
            return;
        }
        try (var reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            PinData data = GSON.fromJson(reader, PinData.class);
            if (data == null || !validPinCoordinate(data.x)
                    || !validPinCoordinate(data.z)) {
                throw new IOException("Invalid pin coordinates");
            }
            MapConfig.pinActive = data.active;
            MapConfig.pinWorldX = data.x;
            MapConfig.pinWorldZ = data.z;
            boolean validRouteStart = Boolean.TRUE.equals(data.routeStartValid)
                    && data.routeStartX != null && data.routeStartZ != null
                    && validPinCoordinate(data.routeStartX)
                    && validPinCoordinate(data.routeStartZ);
            MapConfig.pinRouteStartValid = data.active && validRouteStart;
            MapConfig.pinRouteStartWorldX = validRouteStart ? data.routeStartX : 0.0;
            MapConfig.pinRouteStartWorldZ = validRouteStart ? data.routeStartZ : 0.0;
        } catch (Exception exception) {
            LOGGER.error("Failed to load pin data; preserving unreadable file", exception);
            resetPinState();
            quarantineSmallJson(file);
        }
    }

    private static boolean validPinCoordinate(double value) {
        return Double.isFinite(value) && Math.abs(value) <= 30_000_000.0;
    }

    private static void resetPinState() {
        MapConfig.pinActive = false;
        MapConfig.pinWorldX = 0.0;
        MapConfig.pinWorldZ = 0.0;
        MapConfig.pinRouteStartValid = false;
        MapConfig.pinRouteStartWorldX = 0.0;
        MapConfig.pinRouteStartWorldZ = 0.0;
    }

    public synchronized void savePin() {
        if (currentDimensionDir == null) return;
        File file = new File(currentDimensionDir, "pin.json");
        Path temporary = null;
        try {
            Files.createDirectories(currentDimensionDir.toPath());
            temporary = Files.createTempFile(currentDimensionDir.toPath(), "pin.", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                PinData data = new PinData();
                boolean validCoordinates = validPinCoordinate(MapConfig.pinWorldX)
                        && validPinCoordinate(MapConfig.pinWorldZ);
                boolean validRouteStart = MapConfig.pinRouteStartValid
                        && validPinCoordinate(MapConfig.pinRouteStartWorldX)
                        && validPinCoordinate(MapConfig.pinRouteStartWorldZ);
                data.active = MapConfig.pinActive && validCoordinates;
                data.x = validCoordinates ? MapConfig.pinWorldX : 0.0;
                data.z = validCoordinates ? MapConfig.pinWorldZ : 0.0;
                data.routeStartValid = data.active && validRouteStart;
                data.routeStartX = validRouteStart
                        ? MapConfig.pinRouteStartWorldX : null;
                data.routeStartZ = validRouteStart
                        ? MapConfig.pinRouteStartWorldZ : null;
                GSON.toJson(data, writer);
            }
            moveReplacing(temporary, file.toPath());
            temporary = null;
        } catch (IOException exception) {
            LOGGER.error("Failed to save pin data atomically", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void quarantineSmallJson(File file) {
        if (file == null || !file.isFile()) return;
        Path source = file.toPath();
        Path quarantine = source.resolveSibling(file.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            moveReplacing(source, quarantine);
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine unreadable file {}", file, exception);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record DimensionMapProfile(String resourceId, int minY, int maxY,
            boolean hasSkyLight, boolean hasCeiling, boolean forceBrightLightmap,
            float ambientLight, double coordinateScale, boolean known) {
        private static final int VERSION = 2;
        private static final String FILE_NAME = ".dimension_profile";

        public DimensionMapProfile {
            resourceId = normalize(resourceId);
            if (maxY < minY) maxY = minY;
            if (!Float.isFinite(ambientLight)) ambientLight = 0.0f;
            ambientLight = Math.max(0.0f, Math.min(1.0f, ambientLight));
            if (!Double.isFinite(coordinateScale) || coordinateScale <= 0.0) {
                coordinateScale = 1.0;
            }
        }

        public static DimensionMapProfile fromLevel(Level level, String resourceId) {
            if (level == null) return fallback(resourceId);
            boolean forceBright = queryForceBrightLightmap(level);
            double scale = queryCoordinateScale(level);
            float ambient = queryAmbientLight(level);
            return new DimensionMapProfile(resourceId,
                    level.getMinBuildHeight(), level.getMaxBuildHeight() - 1,
                    level.dimensionType().hasSkyLight(),
                    level.dimensionType().hasCeiling(), forceBright, ambient, scale, true);
        }

        public static DimensionMapProfile resolve(Path dimensionDirectory,
                String resourceId) {
            DimensionMapProfile saved = load(dimensionDirectory, resourceId);
            if (saved.known()) return saved;
            DimensionMapProfile integrated = fromIntegratedServer(resourceId);
            if (integrated != null) {
                integrated.save(dimensionDirectory);
                return integrated;
            }
            return saved;
        }

        public static DimensionMapProfile load(Path dimensionDirectory,
                String resourceId) {
            Path file = dimensionDirectory == null ? null
                    : dimensionDirectory.resolve(FILE_NAME);
            if (file == null || !Files.isRegularFile(file)) return fallback(resourceId);
            java.util.Properties values = new java.util.Properties();
            try (java.io.InputStream input = Files.newInputStream(file)) {
                values.load(input);
                int version = parseInt(values.getProperty("version"), 0);
                if (version < 1 || version > VERSION) return fallback(resourceId);
                String id = normalize(values.getProperty("resource_id", resourceId));
                DimensionMapProfile fallback = fallback(id);
                return new DimensionMapProfile(id,
                        parseInt(values.getProperty("min_y"), fallback.minY()),
                        parseInt(values.getProperty("max_y"), fallback.maxY()),
                        Boolean.parseBoolean(values.getProperty("has_skylight")),
                        Boolean.parseBoolean(values.getProperty("has_ceiling")),
                        Boolean.parseBoolean(values.getProperty("force_bright_lightmap")),
                        version >= 2
                                ? parseFloat(values.getProperty("ambient_light"), fallback.ambientLight())
                                : fallback.ambientLight(),
                        parseDouble(values.getProperty("coordinate_scale"), 1.0),
                        true);
            } catch (IOException | RuntimeException ignored) {
                return fallback(resourceId);
            }
        }

        public void save(Path dimensionDirectory) {
            if (dimensionDirectory == null || !known) return;
            try {
                Files.createDirectories(dimensionDirectory);
                Path file = dimensionDirectory.resolve(FILE_NAME);
                Path temp = dimensionDirectory.resolve(FILE_NAME + ".tmp");
                java.util.Properties values = new java.util.Properties();
                values.setProperty("version", Integer.toString(VERSION));
                values.setProperty("resource_id", resourceId);
                values.setProperty("min_y", Integer.toString(minY));
                values.setProperty("max_y", Integer.toString(maxY));
                values.setProperty("has_skylight", Boolean.toString(hasSkyLight));
                values.setProperty("has_ceiling", Boolean.toString(hasCeiling));
                values.setProperty("force_bright_lightmap",
                        Boolean.toString(forceBrightLightmap));
                values.setProperty("ambient_light", Float.toString(ambientLight));
                values.setProperty("coordinate_scale", Double.toString(coordinateScale));
                try (java.io.OutputStream output = Files.newOutputStream(temp)) {
                    values.store(output, "SimpleMap dimension profile v" + VERSION);
                }
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ignored) {
            }
        }

        public int clampY(int y) {
            return Math.max(minY, Math.min(maxY, y));
        }

        public static DimensionMapProfile fallback(String resourceId) {
            String id = normalize(resourceId);
            return switch (id) {
                case "minecraft:overworld" -> new DimensionMapProfile(id,
                        -64, 319, true, false, false, 0.0f, 1.0, true);
                case "minecraft:the_nether" -> new DimensionMapProfile(id,
                        0, 255, false, true, false, 0.1f, 8.0, true);
                case "minecraft:the_end" -> new DimensionMapProfile(id,
                        0, 255, false, false, true, 0.0f, 1.0, true);
                default -> new DimensionMapProfile(id,
                        -64, 319, false, false, false, 0.0f, 1.0, false);
            };
        }

        private static DimensionMapProfile fromIntegratedServer(String resourceId) {
            String target = normalize(resourceId);
            try {
                Object server = Minecraft.getInstance().getSingleplayerServer();
                if (server == null) return null;
                Object levels = server.getClass().getMethod("getAllLevels").invoke(server);
                if (!(levels instanceof Iterable<?> iterable)) return null;
                for (Object candidate : iterable) {
                    if (!(candidate instanceof Level level)) continue;
                    if (!target.equals(normalize(level.dimension().location().toString()))) continue;
                    return fromLevel(level, target);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            return null;
        }

        private static float queryAmbientLight(Level level) {
            try {
                return level.dimensionType().ambientLight();
            } catch (Throwable ignored) {
                return 0.0f;
            }
        }

        private static double queryCoordinateScale(Level level) {
            try {
                return level.dimensionType().coordinateScale();
            } catch (Throwable ignored) {
                return 1.0;
            }
        }

        private static boolean queryForceBrightLightmap(Level level) {
            try {
                Object effects = level.getClass().getMethod("effects").invoke(level);
                if (effects == null) return false;
                Object result = effects.getClass().getMethod("forceBrightLightmap").invoke(effects);
                return result instanceof Boolean value && value;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }

        private static int parseInt(String value, int fallback) {
            try { return Integer.parseInt(value); }
            catch (RuntimeException ignored) { return fallback; }
        }

        private static float parseFloat(String value, float fallback) {
            try { return Float.parseFloat(value); }
            catch (RuntimeException ignored) { return fallback; }
        }

        private static double parseDouble(String value, double fallback) {
            try { return Double.parseDouble(value); }
            catch (RuntimeException ignored) { return fallback; }
        }

        private static String normalize(String id) {
            if (id == null || id.isBlank()) return "minecraft:overworld";
            return id.trim().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * Server-authoritative map teleport helper.
     */
    public static final class MapTeleportController {
        private MapTeleportController() {
        }

        public static boolean canTeleport(Minecraft minecraft) {
            return minecraft != null && minecraft.player != null
                    && (minecraft.player.isCreative()
                            || minecraft.player.isSpectator()
                            || minecraft.player.hasPermissions(2));
        }

        public static int defaultTargetY(String dimension) {
            String normalized = MapManager.normalizeDimensionResourceId(dimension)
                    .toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals("minecraft:the_nether") || normalized.endsWith(":the_nether")
                    || normalized.endsWith(":nether")) {
                return 65;
            }
            if (normalized.equals("minecraft:the_end") || normalized.endsWith(":the_end")
                    || normalized.endsWith(":end")) {
                return 80;
            }
            return 100;
        }

        public static boolean teleport(Minecraft minecraft, String dimension,
                int x, int y, int z) {
            if (minecraft == null || minecraft.player == null
                    || minecraft.player.connection == null) {
                return false;
            }
            if (!canTeleport(minecraft)) {
                minecraft.player.sendSystemMessage(Component.translatable("gui.simplemap.msg.teleport_permission"));
                return false;
            }

            MapManager manager = MapManager.getInstance();
            String targetDimension = manager.resolveDimensionResourceId(dimension);
            net.minecraft.resources.ResourceLocation targetId = net.minecraft.resources.ResourceLocation.tryParse(targetDimension);
            if (targetId == null) {
                minecraft.player.sendSystemMessage(Component.translatable("gui.simplemap.msg.invalid_dimension", targetDimension));
                return false;
            }

            String liveDimension = MapManager.normalizeDimensionResourceId(
                    manager.getLiveDimensionResourceId());
            boolean crossDimension = !targetId.toString().equals(liveDimension);
            String command;
            if (crossDimension) {
                command = String.format(java.util.Locale.ROOT,
                        "execute in %s run teleport @s %d %d %d",
                        targetId, x, y, z);
                startTransition(targetId.toString(), command);
                return true;
            } else {
                command = String.format(java.util.Locale.ROOT,
                        "teleport %d %d %d", x, y, z);
            }

            minecraft.player.connection.sendCommand(command);
            return true;
        }

        private static final long COMMAND_DELAY_NANOS = 220_000_000L;
        private static final long DURATION_NANOS = 900_000_000L;
        private static volatile long startedAtNanos;
        private static volatile int tintRgb = 0x6D3AA8;
        private static volatile String pendingCommand;

        public static void startTransition(String targetDimension, String command) {
            String id = targetDimension == null ? "" : targetDimension.toLowerCase(java.util.Locale.ROOT);
            if (id.contains("nether")) tintRgb = 0x7B245E;
            else if (id.contains("end")) tintRgb = 0xD8D29B;
            else tintRgb = 0x397A9A;
            pendingCommand = command;
            startedAtNanos = System.nanoTime();
        }

        public static void tickTransition() {
            String command = pendingCommand;
            long start = startedAtNanos;
            if (command == null || start == 0L
                    || System.nanoTime() - start < COMMAND_DELAY_NANOS) {
                return;
            }
            pendingCommand = null;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && minecraft.player.connection != null) {
                minecraft.player.connection.sendCommand(command);
            }
        }

        public static void renderTransition(GuiGraphics graphics) {
            long start = startedAtNanos;
            if (start == 0L || graphics == null) return;
            long elapsed = System.nanoTime() - start;
            if (elapsed < 0L || elapsed >= DURATION_NANOS) {
                startedAtNanos = 0L;
                pendingCommand = null;
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            int width = minecraft.getWindow().getGuiScaledWidth();
            int height = minecraft.getWindow().getGuiScaledHeight();
            float progress = elapsed / (float) DURATION_NANOS;
            float pulse = progress < 0.45f
                    ? progress / 0.45f
                    : Math.max(0.0f, 1.0f - (progress - 0.45f) / 0.55f);
            int overlayAlpha = Math.min(150, Math.max(0, Math.round(112.0f * pulse)));
            graphics.fill(0, 0, width, height, (overlayAlpha << 24) | tintRgb);

            int centerX = width / 2;
            int centerY = height / 2;
            int maximumRadius = Math.max(20, Math.min(width, height) / 3);
            int radius = Math.max(10, Math.round(maximumRadius * (0.25f + progress * 0.75f)));
            int phase = (int) ((elapsed / 70_000_000L) & 15L);
            int dotSize = Math.max(2, Math.min(6, Math.round(Math.min(width, height) / 180.0f)));

            for (int i = 0; i < 16; i++) {
                double angle = ((i + phase) & 15) * (Math.PI * 2.0 / 16.0);
                int x = centerX + (int) Math.round(Math.cos(angle) * radius);
                int y = centerY + (int) Math.round(Math.sin(angle) * radius * 0.62);
                int trail = (i - phase + 16) & 15;
                int alpha = trail == 0 ? 0xFF : trail <= 3 ? 0xB0 : 0x50;
                int color = (alpha << 24) | 0x00F2F2F2;
                graphics.fill(x - dotSize, y - dotSize,
                        x + dotSize, y + dotSize, color);
            }
        }
    }

    /**
     * Cross-milestone lifecycle boundary for the architecture stack.
     * Subsystems remain independently testable and never infer session ownership
     * from a late callback.
     */
    public static final class MapArchitectureCoordinator {
        public record Summary(GpuPageTable.MapGpuPageTableService.Summary pageTable,
                MapUploadEngine.Summary uploads,
                MinimapService.Summary minimap,
                SurfaceRegionSourceDatabase.Snapshot surfaceSource,
                com.velorise.simplemap.client.RegionSurfaceLodService.RegionLodGraph.Summary surfaceProjection,
                long surfaceRequests,
                MapPersistenceV2Service.Summary persistence) { }

        private static final MapArchitectureCoordinator INSTANCE =
                new MapArchitectureCoordinator();
        private long lastFrameId = Long.MIN_VALUE;

        private MapArchitectureCoordinator() { }
        public static MapArchitectureCoordinator getInstance() { return INSTANCE; }

        public void onWorldJoin() {
            lastFrameId = Long.MIN_VALUE;
        }

        public void onFrameBoundary(long frameId) {
            if (frameId == lastFrameId) return;
            lastFrameId = frameId;
            MapUploadEngine.getInstance().drain(
                    System.nanoTime() + 1_000_000L, 2 * 1024 * 1024);
            GpuPageTable.MapGpuPageTableService.getInstance().swapAtFrameBoundary();
            MapTextureManager.getInstance().onPageTableFrameBoundary();
            MapOverviewTextureManager.getInstance().onPageTableFrameBoundary();
            CaveTextureManager.getInstance().endRenderBatch();
        }

        public void onWorldLeave() {
            MinimapService.getInstance().clear();
            RegionSurfaceLodService.getInstance().clear();
            SurfaceRegionSourceDatabase.getInstance().clear();
            MapUploadEngine.getInstance().clear();
            GpuPageTable.MapGpuPageTableService.getInstance().clear();
            lastFrameId = Long.MIN_VALUE;
        }

        public Summary summary() {
            return new Summary(
                    GpuPageTable.MapGpuPageTableService.getInstance().summary(),
                    MapUploadEngine.getInstance().summary(),
                    MinimapService.getInstance().summary(),
                    SurfaceRegionSourceDatabase.getInstance().snapshot(),
                    RegionSurfaceLodService.getInstance().summary(),
                    MapViewportCoordinator.getInstance().getSubmittedSurfaceRequests(),
                    MapPersistenceV2Service.getInstance().summary());
        }
    }


    public static class MapConfig {
    /** Temporary common zoom-out floor for fullscreen and minimap views. */
    public static final float MINIMUM_ZOOM_SCALE = 0.1f;

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CURRENT_PERFORMANCE_DEFAULTS_VERSION = 1;
    private static File configFile;

    // Config values
    public static boolean minimapEnabled = true;
    public static float minimapXPercent = 0.82f; // Default Top-Right
    public static float minimapYPercent = 0.05f;
    public static String minimapAnchor = "TOP_RIGHT";
    public static int minimapOffsetX = -8;
    public static int minimapOffsetY = 8;
    public static boolean legacyMinimapPositionPending = false;
    public static int minimapSize = 64; // Square size (px) - smaller default for better UX
    public static float minimapZoom = 1.0f; // Zoom level inside (changed from 0.25f to 1.0f)
    public static int scanPointsPerTick = 100000; // Internal column budget; UI presents this as chunks/tick or AUTO
    public static int mapRevealMode = 1; // Legacy field; chunk-stream is now the only reveal pipeline
    public static int scanEngineVersion = 2;
    public static boolean alwaysRescanExplored = false; // Whether to continuously scan already explored blocks
    public static int blockColourMode = 0; // 0 = ACCURATE, 1 = VANILLA
    public static boolean displayFlowers = false;
    public static int terrainSlopes = 2; // 0 = OFF, 1 = 2D, 2 = 3D
    public static boolean cursorBiomeEnabled = true;
    public static boolean cursorBlockEnabled = true;

    // Player marker display config
    public static float playerMarkerScale = 0.5f; // Scale multiplier (0.1x to 1.0x)
    public static int playerMarkerMode = 1; // 0 = SKIN (player head only), 1 = ARROW_ONLY
    public static int playerPointerColor = 0xFFFF0000; // Default Red
    public static final int[] POINTER_COLORS = {
            0xFF00C8FF, // Cyan
            0xFF00FF00, // Green
            0xFFFF0000, // Red
            0xFFFFCC00, // Yellow
            0xFFC800FF, // Purple
            0xFFFF8800, // Orange
            0xFFFFFFFF, // White
            0xFF000001 // Rainbow
    };
    public static final String[] POINTER_COLOR_NAMES = {
            "Cyan",
            "Green",
            "Red",
            "Yellow",
            "Purple",
            "Orange",
            "White",
            "Rainbow"
    };

    // Coords config (-1.0f represents automatic snapping underneath the minimap)
    public static boolean coordsEnabled = true;
    public static float coordsXPercent = -1.0f;
    public static float coordsYPercent = -1.0f;
    public static float coordsScale = 0.64f;
    public static int coordsTextColor = 0xFFFFFFFF; // Default White

    // Waypoint scale & visibility configs
    public static float waypointScale = 5.0f; // Global waypoint scale (1.0x to 10.0x)
    public static boolean waypointsVisible = true; // Toggle visibility of waypoints
    public static boolean minimapWaypointsVisible = true; // Toggle waypoint icons on minimap
    public static boolean createDeathWaypoint = true;
    public static int maxDeathWaypoints = 3;

    // Pin scale (0.1x to 1.0x, defaults to 0.5x)
    public static float pinScale = 0.5f;
    public static boolean autoClearPin = true;

    // Pin navigation marker. The route origin is captured once when the pin is
    // created, then retained with the per-dimension pin so guide dots remain fixed
    // to world blocks instead of following the player or viewport.
    public static boolean pinActive = false;
    public static double pinWorldX = 0;
    public static double pinWorldZ = 0;
    public static boolean pinRouteStartValid = false;
    public static double pinRouteStartWorldX = 0;
    public static double pinRouteStartWorldZ = 0;

    // Minimap rotation config
    public static boolean minimapRotate = true; // Toggle whether minimap rotates with player
    public static boolean minimapCircle = false; // Toggle whether minimap is circular
    public static boolean showMinimapInScreens = true;
    public static int minimapNightMode = 1; // 0 = OFF, 1 = AUTO, 2 = ON
    public static int minimapRingColor = 0xFF2D3033;
    public static int mapColorProfile = 0;
    public static List<Integer> savedColors = new ArrayList<>();
    public static Map<String, Integer> blockColorOverrides = new LinkedHashMap<>();
    /** Uses a larger bounded scan/upload budget only while the full map is open. */
    public static boolean fastFullscreenLoading = false;
    /** Local cave policy used when the connected server has no Simple Map extension. */
    public static int localCaveMapMode = 2; // 0 = OFF, 1 = AUTO, 2 = ON
    /** Explicit OFF/LAYERED/FULL selection persisted independently for each dimension. */
    public static Map<String, String> caveDimensionModes = new LinkedHashMap<>();

    // Compass letter (N/E/S/W) display config
    public static boolean compassLettersVisible = true;
    public static int compassLetterColor = 0xFFFFFFFF; // Default White

    public static boolean isStencilEnabled(Object renderTarget) {
        if (renderTarget == null)
            return false;
        try {
            java.lang.reflect.Field f = renderTarget.getClass().getDeclaredField("useStencil");
            f.setAccessible(true);
            return f.getBoolean(renderTarget);
        } catch (Exception e1) {
            try {
                java.lang.reflect.Method m = renderTarget.getClass().getDeclaredMethod("useStencil");
                m.setAccessible(true);
                return (Boolean) m.invoke(renderTarget);
            } catch (Exception e2) {
                try {
                    java.lang.reflect.Method m = renderTarget.getClass().getDeclaredMethod("isStencilEnabled");
                    m.setAccessible(true);
                    return (Boolean) m.invoke(renderTarget);
                } catch (Exception e3) {
                    for (java.lang.reflect.Field field : renderTarget.getClass().getDeclaredFields()) {
                        if (field.getType() == boolean.class) {
                            String name = field.getName();
                            if (name.toLowerCase().contains("stencil")) {
                                try {
                                    field.setAccessible(true);
                                    return field.getBoolean(renderTarget);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                    return false;
                }
            }
        }
    }

    // Book map config
    public static boolean serverExtensionAvailable = false; // Ephemeral, never persisted
    public static boolean serverRequireMapBook = false; // Synced from server to client
    public static int serverCaveMapMode = 0; // Synced: 0 = OFF, 1 = AUTO, 2 = ON

    public static void init() {
        configFile = new File(Minecraft.getInstance().gameDirectory, "simplemap/config.json");
        if (!configFile.getParentFile().exists()) {
            configFile.getParentFile().mkdirs();
        }

        // New installs use the time-budgeted chunk-stream engine at full speed.
        if (!configFile.exists()) scanPointsPerTick = calculateDefaultScanPoints();

        load();
    }

    public static void load() {
        boolean rewriteMigratedConfig = false;
        if (!configFile.exists()) {
            save(); // Write defaults
            return;
        }

        try (var reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data == null) throw new IOException("Empty SimpleMap config");
            minimapEnabled = data.minimapEnabled;
            minimapXPercent = Math.max(0.0f, Math.min(1.0f, data.minimapXPercent));
            minimapYPercent = Math.max(0.0f, Math.min(1.0f, data.minimapYPercent));
            if (data.minimapAnchor == null) {
                legacyMinimapPositionPending = true;
            } else {
                minimapAnchor = MinimapRenderer.MinimapPosition.sanitizeAnchor(data.minimapAnchor);
                minimapOffsetX = data.minimapOffsetX;
                minimapOffsetY = data.minimapOffsetY;
            }
            minimapSize = Math.max(16, Math.min(150, data.minimapSize));
            minimapZoom = Math.max(MINIMUM_ZOOM_SCALE, Math.min(2.0f, data.minimapZoom));
            if (data.scanEngineVersion < 2) {
                // One-time migration: old configs often carried 500/1000 DOTS,
                // which made already-loaded chunks look missing.
                scanPointsPerTick = 100000;
                mapRevealMode = 1;
                scanEngineVersion = 2;
                rewriteMigratedConfig = true;
            } else {
                scanPointsPerTick = Math.max(1000, Math.min(100000, data.scanPointsPerTick));
                mapRevealMode = 1;
                scanEngineVersion = data.scanEngineVersion;
            }
            blockColourMode = Math.max(0, Math.min(1, data.blockColourMode));
            displayFlowers = data.displayFlowers;
            terrainSlopes = Math.max(0, Math.min(2, data.terrainSlopes));
            cursorBiomeEnabled = data.cursorBiomeEnabled == null || data.cursorBiomeEnabled;
            cursorBlockEnabled = data.cursorBlockEnabled == null || data.cursorBlockEnabled;

            playerMarkerScale = Math.max(0.1f,
                    Math.min(1.0f, data.playerMarkerScale == 0.0f ? 0.5f : data.playerMarkerScale));
            playerMarkerMode = data.playerMarkerMode;
            playerPointerColor = data.playerPointerColor == null ? 0xFFFF0000 : data.playerPointerColor;

            coordsEnabled = data.coordsEnabled;
            coordsXPercent = data.coordsXPercent == -1.0f ? -1.0f
                    : Math.max(0.0f, Math.min(1.0f, data.coordsXPercent));
            coordsYPercent = data.coordsYPercent == -1.0f ? -1.0f
                    : Math.max(0.0f, Math.min(1.0f, data.coordsYPercent));
            coordsScale = Math.max(0.1f, Math.min(2.0f, data.coordsScale));
            coordsTextColor = data.coordsTextColor == null ? 0xFFFFFFFF : data.coordsTextColor;

            waypointScale = Math.max(1.0f, Math.min(10.0f, data.waypointScale == 0.0f ? 5.0f : data.waypointScale));
            pinScale = Math.max(0.1f, Math.min(1.0f, data.pinScale == 0.0f ? 0.5f : data.pinScale));
            waypointsVisible = data.waypointsVisible;
            minimapWaypointsVisible = data.minimapWaypointsVisible == null || data.minimapWaypointsVisible;
            createDeathWaypoint = data.createDeathWaypoint == null || data.createDeathWaypoint;
            maxDeathWaypoints = data.maxDeathWaypoints == null
                    ? 3
                    : Math.max(0, Math.min(20, data.maxDeathWaypoints));

            minimapRotate = data.minimapRotate;
            minimapCircle = data.minimapCircle;
            showMinimapInScreens = data.showMinimapInScreens == null || data.showMinimapInScreens;
            minimapNightMode = data.minimapNightMode == null
                    ? 1
                    : Math.max(0, Math.min(2, data.minimapNightMode));
            minimapRingColor = data.minimapRingColor == null ? 0xFF2D3033 : data.minimapRingColor;
            mapColorProfile = Math.max(0, Math.min(MapColorProfile.NAMES.length - 1, data.mapColorProfile));
            savedColors = new ArrayList<>();
            if (data.savedColors != null) {
                for (Integer color : data.savedColors) {
                    if (color != null && !savedColors.contains(color)) savedColors.add(color);
                    if (savedColors.size() == 8) break;
                }
            }
            blockColorOverrides = new LinkedHashMap<>();
            if (data.blockColorOverrides != null) {
                for (Map.Entry<String, Integer> entry : data.blockColorOverrides.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null
                            && entry.getKey().length() <= 256
                            && entry.getKey().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                        blockColorOverrides.put(entry.getKey(), entry.getValue());
                    }
                    if (blockColorOverrides.size() == 1024) break;
                }
            }
            autoClearPin = data.autoClearPin;

            compassLettersVisible = data.compassLettersVisible;
            compassLetterColor = data.compassLetterColor == null ? 0xFFFFFFFF : data.compassLetterColor;

            alwaysRescanExplored = data.alwaysRescanExplored;
            if (data.performanceDefaultsVersion == null
                    || data.performanceDefaultsVersion < CURRENT_PERFORMANCE_DEFAULTS_VERSION) {
                // Beta migration: BALANCED is now the safe default. This runs once;
                // a later explicit FAST choice is preserved after the migrated config is saved.
                fastFullscreenLoading = false;
                rewriteMigratedConfig = true;
            } else {
                fastFullscreenLoading = Boolean.TRUE.equals(data.fastFullscreenLoading);
            }
            localCaveMapMode = data.localCaveMapMode == null
                ? 2
                : Math.max(0, Math.min(2, data.localCaveMapMode));
            caveDimensionModes = new LinkedHashMap<>();
            if (data.caveDimensionModes != null) {
                for (Map.Entry<String, String> entry : data.caveDimensionModes.entrySet()) {
                    String dimension = entry.getKey();
                    String mode = entry.getValue();
                    if (dimension == null || mode == null
                            || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) continue;
                    String normalized = mode.toUpperCase(java.util.Locale.ROOT);
                    if (normalized.equals("OFF") || normalized.equals("LAYERED")
                            || normalized.equals("FULL")) {
                        caveDimensionModes.put(dimension, normalized);
                    }
                    if (caveDimensionModes.size() == 256) break;
                }
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to load map config; preserving the unreadable file and restoring defaults", exception);
            quarantineCorruptConfig();
            restoreDefaults();
            save();
            return;
        }
        if (rewriteMigratedConfig) save();
    }

    public static synchronized void save() {
        if (configFile == null) return;
        Path target = configFile.toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                ConfigData data = new ConfigData();
                data.minimapEnabled = minimapEnabled;
                data.minimapXPercent = minimapXPercent;
                data.minimapYPercent = minimapYPercent;
                data.minimapAnchor = minimapAnchor;
                data.minimapOffsetX = minimapOffsetX;
                data.minimapOffsetY = minimapOffsetY;
                data.minimapSize = minimapSize;
                data.minimapZoom = minimapZoom;
                data.scanPointsPerTick = scanPointsPerTick;
                data.mapRevealMode = 1;
                data.scanEngineVersion = scanEngineVersion;
                data.blockColourMode = blockColourMode;
                data.displayFlowers = displayFlowers;
                data.terrainSlopes = terrainSlopes;
                data.cursorBiomeEnabled = cursorBiomeEnabled;
                data.cursorBlockEnabled = cursorBlockEnabled;

                data.playerMarkerScale = playerMarkerScale;
                data.playerMarkerMode = playerMarkerMode;
                data.playerPointerColor = playerPointerColor;

                data.coordsEnabled = coordsEnabled;
                data.coordsXPercent = coordsXPercent;
                data.coordsYPercent = coordsYPercent;
                data.coordsScale = coordsScale;
                data.coordsTextColor = coordsTextColor;

                data.waypointScale = waypointScale;
                data.pinScale = pinScale;
                data.autoClearPin = autoClearPin;
                data.waypointsVisible = waypointsVisible;
                data.minimapWaypointsVisible = minimapWaypointsVisible;
                data.createDeathWaypoint = createDeathWaypoint;
                data.maxDeathWaypoints = maxDeathWaypoints;

                data.minimapRotate = minimapRotate;
                data.minimapCircle = minimapCircle;
                data.showMinimapInScreens = showMinimapInScreens;
                data.minimapNightMode = minimapNightMode;
                data.minimapRingColor = minimapRingColor;
                data.mapColorProfile = mapColorProfile;
                data.savedColors = new ArrayList<>(savedColors);
                data.blockColorOverrides = new LinkedHashMap<>(blockColorOverrides);

                data.compassLettersVisible = compassLettersVisible;
                data.compassLetterColor = compassLetterColor;

                data.alwaysRescanExplored = alwaysRescanExplored;
                data.fastFullscreenLoading = fastFullscreenLoading;
                data.performanceDefaultsVersion = CURRENT_PERFORMANCE_DEFAULTS_VERSION;
                data.localCaveMapMode = localCaveMapMode;
                data.caveDimensionModes = new LinkedHashMap<>(caveDimensionModes);
                GSON.toJson(data, writer);
            }
            moveReplacing(temporary, target);
        } catch (IOException exception) {
            LOGGER.error("Failed to save map config atomically", exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void quarantineCorruptConfig() {
        if (configFile == null || !configFile.isFile()) return;
        Path source = configFile.toPath();
        Path quarantine = source.resolveSibling(configFile.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            moveReplacing(source, quarantine);
        } catch (IOException moveFailure) {
            LOGGER.warn("Could not quarantine unreadable SimpleMap config {}", source, moveFailure);
        }
    }

    /**
     * Restores configurable preferences while preserving user-authored data such as
     * saved palette colors and per-block overrides. Waypoints and explored map files
     * live outside this config and are never touched here.
     */
    public static void resetPreferencesToDefaults() {
        List<Integer> retainedSavedColors = new ArrayList<>(savedColors);
        Map<String, Integer> retainedBlockOverrides = new LinkedHashMap<>(blockColorOverrides);
        restoreDefaults();
        savedColors = retainedSavedColors;
        blockColorOverrides = retainedBlockOverrides;
        save();
    }

    private static void restoreDefaults() {
        minimapEnabled = true;
        minimapXPercent = 0.82f;
        minimapYPercent = 0.05f;
        minimapAnchor = "TOP_RIGHT";
        minimapOffsetX = -8;
        minimapOffsetY = 8;
        legacyMinimapPositionPending = false;
        minimapSize = 64;
        minimapZoom = 1.0f;
        scanPointsPerTick = calculateDefaultScanPoints();
        mapRevealMode = 1;
        scanEngineVersion = 2;
        alwaysRescanExplored = false;
        blockColourMode = 0;
        displayFlowers = false;
        terrainSlopes = 2;
        cursorBiomeEnabled = true;
        cursorBlockEnabled = true;
        playerMarkerScale = 0.5f;
        playerMarkerMode = 1;
        playerPointerColor = 0xFFFF0000;
        coordsEnabled = true;
        coordsXPercent = -1.0f;
        coordsYPercent = -1.0f;
        coordsScale = 0.64f;
        coordsTextColor = 0xFFFFFFFF;
        waypointScale = 5.0f;
        waypointsVisible = true;
        minimapWaypointsVisible = true;
        createDeathWaypoint = true;
        maxDeathWaypoints = 3;
        pinScale = 0.5f;
        autoClearPin = true;
        minimapRotate = true;
        minimapCircle = false;
        showMinimapInScreens = true;
        minimapNightMode = 1;
        minimapRingColor = 0xFF2D3033;
        mapColorProfile = 0;
        savedColors = new ArrayList<>();
        blockColorOverrides = new LinkedHashMap<>();
        fastFullscreenLoading = false;
        localCaveMapMode = 2;
        caveDimensionModes = new LinkedHashMap<>();
        compassLettersVisible = true;
        compassLetterColor = 0xFFFFFFFF;
    }

    private static class ConfigData {
        boolean minimapEnabled = true;
        float minimapXPercent = 0.82f;
        float minimapYPercent = 0.05f;
        String minimapAnchor;
        int minimapOffsetX = -8;
        int minimapOffsetY = 8;
        int minimapSize = 64;
        float minimapZoom = 1.0f;
        int scanPointsPerTick = 100000;
        int mapRevealMode = 1;
        int scanEngineVersion = 2;
        int blockColourMode = 0;
        boolean displayFlowers = false;
        int terrainSlopes = 2;
        Boolean cursorBiomeEnabled = true;
        Boolean cursorBlockEnabled = true;

        float playerMarkerScale = 0.5f;
        int playerMarkerMode = 1;
        Integer playerPointerColor = 0xFFFF0000;

        boolean coordsEnabled = true;
        float coordsXPercent = -1.0f;
        float coordsYPercent = -1.0f;
        float coordsScale = 0.64f;
        Integer coordsTextColor = 0xFFFFFFFF;

        float waypointScale = 5.0f;
        float pinScale = 0.5f;
        boolean waypointsVisible = true;
        Boolean minimapWaypointsVisible = true;
        Boolean createDeathWaypoint = true;
        Integer maxDeathWaypoints = 3;
        boolean autoClearPin = true;

        boolean minimapRotate = true;
        boolean minimapCircle = false;
        Boolean showMinimapInScreens = true;
        Integer minimapNightMode = 1;
        Integer minimapRingColor = 0xFF2D3033;
        int mapColorProfile = 0;
        List<Integer> savedColors = new ArrayList<>();
        Map<String, Integer> blockColorOverrides = new LinkedHashMap<>();

        boolean compassLettersVisible = true;
        Integer compassLetterColor = 0xFFFFFFFF;

        boolean alwaysRescanExplored = false;
        Boolean fastFullscreenLoading = false;
        Integer performanceDefaultsVersion;
        Integer localCaveMapMode = 2;
        Map<String, String> caveDimensionModes = new LinkedHashMap<>();
    }

    /**
     * Returns the server policy when the optional server extension is present,
     * otherwise the user's local cave policy. This keeps the client-only map fully
     * usable on ordinary public servers without pretending those servers control it.
     */
    public static int getEffectiveCaveMapMode() {
        return serverExtensionAvailable
                ? Math.max(0, Math.min(2, serverCaveMapMode))
                : Math.max(0, Math.min(2, localCaveMapMode));
    }

    public static int calculateDefaultScanPoints() {
        // The scanner is now bounded by a nano-time deadline and chunk priority,
        // so a high logical cap is safe. Users with weak systems can still lower
        // the setting manually to reduce background mapping work.
        return 100000;
    }

}



    /**
 * Local surface block-light cache. Disk work is asynchronous and every result
 * is guarded by a world-generation token so old dimensions cannot publish into
 * the newly active map.
 */
public static final class MapLightManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final MapLightManager INSTANCE = new MapLightManager();
    private static final int PIXEL_COUNT = 512 * 512;
    private static final int MAX_REGIONS = 96;
    private static final int MAX_PENDING_SAVES = 12;
    private static final long SAVE_RETRY_DELAY_MS = 500L;

    private final Map<String, LightRegion> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<String> dirtyRegions = new HashSet<>();
    private final Map<String, Boolean> fileExists = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, LightSaveRequest> pendingSaves = new ConcurrentHashMap<>();
    private final Map<String, LightSaveRequest> inFlightSaves = new ConcurrentHashMap<>();
    private final AtomicBoolean saveDrainScheduled = new AtomicBoolean();
    private volatile File cacheDirectory;
    private long lastSaveTime;

    public static MapLightManager getInstance() {
        return INSTANCE;
    }

    private MapLightManager() {
    }

    public void setLight(int blockX, int blockZ, int light) {
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        LightRegion region = getRegion(rx, rz, true);
        if (region == null) return;
        int px = blockX & 511;
        int pz = blockZ & 511;
        if (region.setLight(px, pz, light)) {
            synchronized (dirtyRegions) {
                dirtyRegions.add(key(rx, rz));
            }
            MapTextureManager.getInstance().markPageDirtyForBlock(blockX, blockZ);
            int localChunkIndex = ((blockZ & 511) >>> 4) * 32
                    + ((blockX & 511) >>> 4);
            SurfaceRegionSourceDatabase.getInstance().markChunkDirty(
                    rx, rz, localChunkIndex);
        }
    }

    /**
     * Commits one contiguous row-major slice of a 16x16 chunk under a single
     * region lock. Light packets used to call {@link #setLight} for every column,
     * causing up to 256 lock/unlock and dirty-notification cycles per chunk.
     */
    public void setChunkLightSlice(int chunkX, int chunkZ, int startColumn,
            byte[] source, int sourceOffset, int count) {
        setChunkLightSlice(chunkX, chunkZ, startColumn,
                source, sourceOffset, count, true);
    }

    /**
     * Batched light commit with optional downstream invalidation. Geometry chunk
     * transactions pass {@code false} and publish Surface/source dirtiness once at
     * cursor 256; light-only packets retain the immediate default path.
     */
    public boolean setChunkLightSlice(int chunkX, int chunkZ, int startColumn,
            byte[] source, int sourceOffset, int count, boolean publishDirty) {
        if (source == null || startColumn < 0 || startColumn >= 256
                || sourceOffset < 0 || count <= 0
                || sourceOffset + count > source.length) return false;
        int safeCount = Math.min(count, 256 - startColumn);
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        int rx = blockX >> 9;
        int rz = blockZ >> 9;
        LightRegion region = getRegion(rx, rz, true);
        if (region == null) return false;
        int localChunkX = (blockX & 511) >>> 4;
        int localChunkZ = (blockZ & 511) >>> 4;
        if (!region.setChunkSlice(localChunkX, localChunkZ, startColumn,
                source, sourceOffset, safeCount)) return false;
        synchronized (dirtyRegions) {
            dirtyRegions.add(key(rx, rz));
        }
        if (publishDirty) {
            MapTextureManager.getInstance().markPageDirtyForChunk(chunkX, chunkZ);
            int localChunkIndex = localChunkZ * 32 + localChunkX;
            SurfaceRegionSourceDatabase.getInstance().markChunkDirty(
                    rx, rz, localChunkIndex);
        }
        return true;
    }

    public LightRegion getRegion(int rx, int rz, boolean create) {
        String key = key(rx, rz);
        synchronized (regions) {
            LightRegion existing = regions.get(key);
            if (existing != null || !create || cacheDirectory == null) return existing;

            long token = generation.get();
            LightRegion created = new LightRegion(rx, rz, token);
            regions.put(key, created);
            File directory = cacheDirectory;
            File file = new File(directory, fileName(rx, rz));
            LightSaveRequest pending = latestSave(directory, rx, rz);
            boolean exists = pending != null || fileExists.computeIfAbsent(key, ignored -> file.isFile());
            if (pending != null) {
                created.applyLoaded(pending.levels());
                created.markLoaded();
            } else if (exists) loadRegionAsync(created, directory, token);
            else created.markLoaded();
            evictOldRegions(directory);
            return created;
        }
    }

    public synchronized void setCacheDirectory(File directory) {
        File normalized = directory == null ? null : directory.getAbsoluteFile();
        if (sameFile(cacheDirectory, normalized)) return;
        flushCurrentDirectory();
        generation.incrementAndGet();
        closeAndClearRegions();
        fileExists.clear();
        cacheDirectory = normalized;
        if (normalized != null) {
            try {
                Files.createDirectories(normalized.toPath());
            } catch (IOException exception) {
                LOGGER.warn("Could not create SimpleMap light-cache directory {}", normalized, exception);
            }
        }
    }

    public void tickSave() {
        long now = System.currentTimeMillis();
        if (now - lastSaveTime < 10_000L) return;
        lastSaveTime = now;
        pumpDirtyRegionSaves(2);
    }

    /** Pulls only a small number of dirty light regions into the IO queue. */
    int pumpDirtyRegionSaves(int maximum) {
        File directory = cacheDirectory;
        if (directory == null || maximum <= 0) return dirtyRegionCount();
        int admitted = 0;
        while (admitted < maximum) {
            String selected;
            synchronized (dirtyRegions) {
                Iterator<String> iterator = dirtyRegions.iterator();
                if (!iterator.hasNext()) break;
                selected = iterator.next();
                iterator.remove();
            }
            LightRegion region;
            synchronized (regions) {
                region = regions.get(selected);
            }
            if (region == null || region.closed) continue;
            if (!saveRegionAsync(region, directory, false)) {
                synchronized (dirtyRegions) {
                    dirtyRegions.add(selected);
                }
                break;
            }
            admitted++;
        }
        return dirtyRegionCount();
    }

    int dirtyRegionCount() {
        synchronized (dirtyRegions) {
            return dirtyRegions.size();
        }
    }

    int pendingSaveCount() {
        return pendingSaves.size();
    }

    int inFlightSaveCount() {
        return inFlightSaves.size();
    }

    public synchronized void flushAndClear() {
        flushCurrentDirectory();
        generation.incrementAndGet();
        closeAndClearRegions();
        fileExists.clear();
        cacheDirectory = null;
    }

    private void loadRegionAsync(LightRegion region, File directory, long token) {
        if (!region.beginLoad()) return;
        File file = new File(directory, fileName(region.rx, region.rz));
        MapWorkScheduler.scheduleIo(0L, TimeUnit.MILLISECONDS,
                MapRequestLane.FULLSCREEN, MapWorkScheduler.WorkType.DISK_READ,
                MapRequestLane.FULLSCREEN.priorityBase(), 10,
                () -> isCurrent(region, directory, token), () -> {
            try {
                byte[] bytes = null;
                LightSaveRequest pending = latestSave(directory, region.rx, region.rz);
                if (pending != null) {
                    bytes = Arrays.copyOf(pending.levels(), pending.levels().length);
                } else {
                    try {
                        long length = Files.size(file.toPath());
                        if (length == PIXEL_COUNT) {
                            bytes = Files.readAllBytes(file.toPath());
                        } else {
                            throw new IOException("Invalid light-cache size " + length);
                        }
                    } catch (IOException exception) {
                        LOGGER.warn("Failed to read light cache {}", file.getName(), exception);
                        quarantineCorruptFile(file);
                        if (token == generation.get() && sameFile(cacheDirectory, directory)) {
                            fileExists.remove(key(region.rx, region.rz));
                        }
                    }
                }
                if (!isCurrent(region, directory, token)) return;
                if (bytes != null) region.applyLoaded(bytes);
                region.markLoaded();
                MapTextureManager.getInstance()
                        .markRegionSourceAvailable(region.rx, region.rz);
            } finally {
                region.finishLoad();
            }
        });
    }

    private boolean isCurrent(LightRegion region, File directory, long token) {
        if (token != generation.get() || !sameFile(cacheDirectory, directory) || region.closed) return false;
        synchronized (regions) {
            return regions.get(key(region.rx, region.rz)) == region;
        }
    }

    private void flushCurrentDirectory() {
        File directory = cacheDirectory;
        if (directory == null) return;
        Set<String> keys;
        synchronized (dirtyRegions) {
            keys = new HashSet<>(dirtyRegions);
            dirtyRegions.clear();
        }
        synchronized (regions) {
            for (String key : keys) {
                LightRegion region = regions.get(key);
                if (region != null && !region.closed) saveRegionAsync(region, directory, true);
            }
        }
    }

    private boolean saveRegionAsync(LightRegion region, File directory, boolean force) {
        if (directory == null || region == null || region.closed) return false;
        String saveKey = saveKey(directory, region.rx, region.rz);
        if (!force && !pendingSaves.containsKey(saveKey)
                && !inFlightSaves.containsKey(saveKey)
                && pendingSaves.size() >= MAX_PENDING_SAVES) return false;
        LightSaveRequest request = new LightSaveRequest(directory, region.rx, region.rz,
                region.getGeneration(), region.snapshot());
        pendingSaves.put(request.key(), request);
        scheduleSaveDrain(0L);
        return true;
    }

    private void scheduleSaveDrain(long delayMs) {
        if (!saveDrainScheduled.compareAndSet(false, true)) return;
        MapWorkScheduler.scheduleIo(Math.max(0L, delayMs), TimeUnit.MILLISECONDS,
                MapRequestLane.BACKGROUND, MapWorkScheduler.WorkType.DISK_WRITE,
                0, 12, () -> true, () -> {
            boolean failed = false;
            LightSaveRequest request = null;
            try {
                Iterator<LightSaveRequest> iterator = pendingSaves.values().iterator();
                if (iterator.hasNext()) request = iterator.next();
                if (request == null || !pendingSaves.remove(request.key(), request)) return;
                inFlightSaves.put(request.key(), request);
                try {
                    if (!writeSaveRequest(request)) {
                        failed = true;
                        pendingSaves.putIfAbsent(request.key(), request);
                    }
                } finally {
                    inFlightSaves.remove(request.key(), request);
                }
            } finally {
                saveDrainScheduled.set(false);
                if (!pendingSaves.isEmpty()) {
                    scheduleSaveDrain(failed ? SAVE_RETRY_DELAY_MS : 2L);
                }
            }
        });
    }

    private boolean writeSaveRequest(LightSaveRequest request) {
        File directory = request.directory();
        File file = new File(directory, fileName(request.rx(), request.rz()));
        File temporary = null;
        try {
            Files.createDirectories(directory.toPath());
            temporary = Files.createTempFile(directory.toPath(), file.getName() + ".", ".tmp").toFile();
            Files.write(temporary.toPath(), request.levels());
            atomicReplace(temporary, file);
            if (request.generation() == generation.get() && sameFile(cacheDirectory, directory)) {
                fileExists.put(key(request.rx(), request.rz()), true);
            }
            return true;
        } catch (IOException exception) {
            LOGGER.warn("Failed to write light cache {}", file.getName(), exception);
            return false;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary.toPath());
                } catch (IOException ignored) {
                }
            }
        }
    }

    private LightSaveRequest latestSave(File directory, int rx, int rz) {
        String saveKey = saveKey(directory, rx, rz);
        LightSaveRequest request = pendingSaves.get(saveKey);
        return request != null ? request : inFlightSaves.get(saveKey);
    }

    private void evictOldRegions(File directory) {
        while (regions.size() > MAX_REGIONS) {
            Iterator<Map.Entry<String, LightRegion>> iterator = regions.entrySet().iterator();
            if (!iterator.hasNext()) return;
            Map.Entry<String, LightRegion> eldest = iterator.next();
            iterator.remove();
            boolean dirty;
            synchronized (dirtyRegions) {
                dirty = dirtyRegions.remove(eldest.getKey());
            }
            if (dirty && directory != null
                    && !saveRegionAsync(eldest.getValue(), directory, false)) {
                // Do not evict the only authoritative dirty copy merely because
                // the bounded save queue is full. It will be reconsidered later.
                regions.put(eldest.getKey(), eldest.getValue());
                synchronized (dirtyRegions) {
                    dirtyRegions.add(eldest.getKey());
                }
                return;
            }
            eldest.getValue().close();
        }
    }

    private void closeAndClearRegions() {
        synchronized (regions) {
            for (LightRegion region : regions.values()) region.close();
            regions.clear();
        }
        synchronized (dirtyRegions) {
            dirtyRegions.clear();
        }
    }



    private static void quarantineCorruptFile(File file) {
        if (file == null || !file.isFile()) return;
        File quarantine = new File(file.getParentFile(),
                file.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(file.toPath(), quarantine.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Moved unreadable light cache to {}", quarantine.getName());
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine unreadable light cache {}", file, exception);
        }
    }

    private static void atomicReplace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean sameFile(File first, File second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.toPath().toAbsolutePath().normalize()
                .equals(second.toPath().toAbsolutePath().normalize());
    }

    private static String key(int rx, int rz) {
        return rx + "," + rz;
    }

    private static String fileName(int rx, int rz) {
        return "l." + rx + "." + rz + ".bin";
    }

    private static String saveKey(File directory, int rx, int rz) {
        return new File(directory, fileName(rx, rz)).toPath().toAbsolutePath().normalize().toString();
    }

    private record LightSaveRequest(File directory, int rx, int rz, long generation, byte[] levels) {
        private LightSaveRequest {
            levels = Arrays.copyOf(levels, levels.length);
        }

        private String key() {
            return saveKey(directory, rx, rz);
        }
    }

    public static final class LightRegion {
        private final int rx;
        private final int rz;
        private final long generation;
        private final byte[] levels = new byte[PIXEL_COUNT];
        private final BitSet modifiedBeforeLoad = new BitSet(PIXEL_COUNT);
        private final ReentrantLock lock = new ReentrantLock();
        private volatile boolean loaded;
        private volatile boolean closed;
        private final AtomicBoolean loadScheduled = new AtomicBoolean();

        private LightRegion(int rx, int rz, long generation) {
            this.rx = rx;
            this.rz = rz;
            this.generation = generation;
        }

        public void lock() {
            lock.lock();
        }

        public void unlock() {
            lock.unlock();
        }

        public byte[] getLevelsDirect() {
            return levels;
        }

        /** Copies a small immutable light rectangle for a page + halo job. */
        public byte[] snapshotWindow(int minX, int minZ, int width, int height) {
            if (minX < 0 || minZ < 0 || width <= 0 || height <= 0
                    || minX + width > MapPageLayout.REGION_SIZE
                    || minZ + height > MapPageLayout.REGION_SIZE) {
                throw new IllegalArgumentException("Invalid light window "
                        + minX + "," + minZ + " " + width + "x" + height);
            }
            lock.lock();
            try {
                byte[] result = new byte[width * height];
                for (int z = 0; z < height; z++) {
                    System.arraycopy(levels,
                            (minZ + z) * MapPageLayout.REGION_SIZE + minX,
                            result, z * width, width);
                }
                return result;
            } finally {
                lock.unlock();
            }
        }

        public boolean isLoaded() {
            return loaded && !closed;
        }

        public long getGeneration() {
            return generation;
        }

        private boolean beginLoad() {
            return !closed && loadScheduled.compareAndSet(false, true);
        }

        private void finishLoad() {
            loadScheduled.set(false);
        }

        private void markLoaded() {
            if (!closed) loaded = true;
        }

        private void close() {
            closed = true;
        }

        private void applyLoaded(byte[] source) {
            lock.lock();
            try {
                if (closed) return;
                for (int i = 0; i < levels.length; i++) {
                    if (!modifiedBeforeLoad.get(i)) levels[i] = source[i];
                }
                modifiedBeforeLoad.clear();
            } finally {
                lock.unlock();
            }
        }

        private byte[] snapshot() {
            lock.lock();
            try {
                return Arrays.copyOf(levels, levels.length);
            } finally {
                lock.unlock();
            }
        }

        private boolean setLight(int px, int pz, int light) {
            int clamped = Math.max(0, Math.min(15, light));
            int index = pz * 512 + px;
            lock.lock();
            try {
                if (closed || (levels[index] & 0xFF) == clamped) return false;
                levels[index] = (byte) clamped;
                if (!loaded) modifiedBeforeLoad.set(index);
                return true;
            } finally {
                lock.unlock();
            }
        }

        private boolean setChunkSlice(int localChunkX, int localChunkZ,
                int startColumn, byte[] source, int sourceOffset, int count) {
            lock.lock();
            try {
                if (closed) return false;
                boolean changed = false;
                for (int index = 0; index < count; index++) {
                    int column = startColumn + index;
                    int localX = column & 15;
                    int localZ = column >>> 4;
                    int destination = (localChunkZ * 16 + localZ) * 512
                            + localChunkX * 16 + localX;
                    int light = source[sourceOffset + index] & 0xFF;
                    int clamped = Math.max(0, Math.min(15, light));
                    if ((levels[destination] & 0xFF) == clamped) continue;
                    levels[destination] = (byte) clamped;
                    if (!loaded) modifiedBeforeLoad.set(destination);
                    changed = true;
                }
                return changed;
            } finally {
                lock.unlock();
            }
        }
    }
}

}