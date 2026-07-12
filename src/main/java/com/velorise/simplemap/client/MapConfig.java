package com.velorise.simplemap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class MapConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;

    // Config values
    public static boolean minimapEnabled = true;
    public static float minimapXPercent = 0.82f; // Default Top-Right
    public static float minimapYPercent = 0.05f;
    public static int minimapSize = 64; // Square size (px) - smaller default for better UX
    public static float minimapZoom = 1.0f; // Zoom level inside (changed from 0.25f to 1.0f)
    public static int scanPointsPerTick = 1000; // Random scan samples per tick (100 - 100000)
    public static boolean alwaysRescanExplored = false; // Whether to continuously scan already explored blocks

    // Player marker display config
    public static float playerMarkerScale = 0.5f; // Scale multiplier (0.1x to 1.0x)
    public static int playerMarkerMode = 1; // 0 = DEFAULT (Skin + Triangle), 1 = ARROW_ONLY (Triangle only)
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
    public static boolean requireMapBook = false; // Server-side loaded config
    public static boolean serverRequireMapBook = false; // Synced from server to client

    public static void init() {
        configFile = new File(Minecraft.getInstance().gameDirectory, "simplemap/config.json");
        if (!configFile.getParentFile().exists()) {
            configFile.getParentFile().mkdirs();
        }

        // Dynamically adjust default scanPointsPerTick based on device RAM if config
        // does not exist yet
        if (!configFile.exists()) {
            scanPointsPerTick = calculateDefaultScanPoints();
        }

        load();
    }

    public static void load() {
        if (!configFile.exists()) {
            save(); // Write defaults
            return;
        }

        try (FileReader reader = new FileReader(configFile)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data != null) {
                minimapEnabled = data.minimapEnabled;
                minimapXPercent = Math.max(0.0f, Math.min(1.0f, data.minimapXPercent));
                minimapYPercent = Math.max(0.0f, Math.min(1.0f, data.minimapYPercent));
                minimapSize = Math.max(16, Math.min(150, data.minimapSize));
                minimapZoom = Math.max(0.05f, Math.min(2.0f, data.minimapZoom));
                scanPointsPerTick = Math.max(100, Math.min(100000, data.scanPointsPerTick));

                playerMarkerScale = Math.max(0.1f,
                        Math.min(1.0f, data.playerMarkerScale == 0.0f ? 0.5f : data.playerMarkerScale));
                playerMarkerMode = data.playerMarkerMode;
                playerPointerColor = data.playerPointerColor == 0 ? 0xFFFF0000 : data.playerPointerColor;

                coordsEnabled = data.coordsEnabled;
                coordsXPercent = data.coordsXPercent == -1.0f ? -1.0f
                        : Math.max(0.0f, Math.min(1.0f, data.coordsXPercent));
                coordsYPercent = data.coordsYPercent == -1.0f ? -1.0f
                        : Math.max(0.0f, Math.min(1.0f, data.coordsYPercent));
                coordsScale = Math.max(0.1f, Math.min(2.0f, data.coordsScale));
                coordsTextColor = data.coordsTextColor == 0 ? 0xFFFFFFFF : data.coordsTextColor;

                waypointScale = Math.max(1.0f, Math.min(10.0f, data.waypointScale == 0.0f ? 5.0f : data.waypointScale));
                pinScale = Math.max(0.1f, Math.min(1.0f, data.pinScale == 0.0f ? 0.5f : data.pinScale));
                waypointsVisible = data.waypointsVisible;

                minimapRotate = data.minimapRotate;
                minimapCircle = data.minimapCircle;
                autoClearPin = data.autoClearPin;

                compassLettersVisible = data.compassLettersVisible;
                compassLetterColor = data.compassLetterColor == 0 ? 0xFFFFFFFF : data.compassLetterColor;

                requireMapBook = data.requireMapBook;
                alwaysRescanExplored = data.alwaysRescanExplored;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        try (FileWriter writer = new FileWriter(configFile)) {
            ConfigData data = new ConfigData();
            data.minimapEnabled = minimapEnabled;
            data.minimapXPercent = minimapXPercent;
            data.minimapYPercent = minimapYPercent;
            data.minimapSize = minimapSize;
            data.minimapZoom = minimapZoom;
            data.scanPointsPerTick = scanPointsPerTick;

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

            data.minimapRotate = minimapRotate;
            data.minimapCircle = minimapCircle;

            data.compassLettersVisible = compassLettersVisible;
            data.compassLetterColor = compassLetterColor;

            data.requireMapBook = requireMapBook;
            data.alwaysRescanExplored = alwaysRescanExplored;
            GSON.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ConfigData {
        boolean minimapEnabled = true;
        float minimapXPercent = 0.82f;
        float minimapYPercent = 0.05f;
        int minimapSize = 64;
        float minimapZoom = 1.0f;
        int scanPointsPerTick = 1000;

        float playerMarkerScale = 0.5f;
        int playerMarkerMode = 1;
        int playerPointerColor = 0xFFFF0000;

        boolean coordsEnabled = true;
        float coordsXPercent = -1.0f;
        float coordsYPercent = -1.0f;
        float coordsScale = 0.64f;
        int coordsTextColor = 0xFFFFFFFF;

        float waypointScale = 5.0f;
        float pinScale = 0.5f;
        boolean waypointsVisible = true;
        boolean autoClearPin = true;

        boolean minimapRotate = true;
        boolean minimapCircle = false;

        boolean compassLettersVisible = true;
        int compassLetterColor = 0xFFFFFFFF;

        boolean requireMapBook = false;
        boolean alwaysRescanExplored = false;
    }

    public static int calculateDefaultScanPoints() {
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                long totalMemory = sunOsBean.getTotalMemorySize();
                double gb = totalMemory / (1024.0 * 1024.0 * 1024.0);
                if (gb < 15.0) {
                    return 500;
                } else if (gb <= 17.0) {
                    return 1000;
                } else {
                    return 1500;
                }
            }
        } catch (Throwable t) {
            // Fallback to standard default if any exception/class loading issue occurs
        }
        return 1000;
    }
}