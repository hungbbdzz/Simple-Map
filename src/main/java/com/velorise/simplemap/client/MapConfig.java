package com.velorise.simplemap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MapConfig {
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
    public static boolean createDeathWaypoint = true;
    public static int maxDeathWaypoints = 3;

    // Pin scale (0.1x to 1.0x, defaults to 0.5x)
    public static float pinScale = 0.5f;
    public static boolean autoClearPin = true;

    // Pin navigation marker (session-persistent, not saved to disk)
    public static boolean pinActive = false;
    public static double pinWorldX = 0;
    public static double pinWorldZ = 0;

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
                minimapAnchor = MinimapPosition.sanitizeAnchor(data.minimapAnchor);
                minimapOffsetX = data.minimapOffsetX;
                minimapOffsetY = data.minimapOffsetY;
            }
            minimapSize = Math.max(16, Math.min(150, data.minimapSize));
            minimapZoom = Math.max(0.05f, Math.min(2.0f, data.minimapZoom));
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
