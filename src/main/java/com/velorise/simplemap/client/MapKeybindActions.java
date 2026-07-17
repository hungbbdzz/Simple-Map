package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Centralized, side-safe actions used by configurable key mappings. */
public final class MapKeybindActions {
    private static final float MIN_ZOOM = 0.05f;
    private static final float MAX_ZOOM = 2.0f;
    private static final float ZOOM_STEP = 1.20f;

    private MapKeybindActions() {
    }

    public static void toggleFullMap(Minecraft mc) {
        if (!ready(mc)) return;
        if (mc.screen instanceof MapScreen) {
            mc.setScreen(null);
        } else {
            mc.setScreen(new MapScreen());
        }
    }

    public static void toggleMinimap(Minecraft mc) {
        MapConfig.minimapEnabled = !MapConfig.minimapEnabled;
        MapConfig.save();
        notify(mc, "Minimap: " + onOff(MapConfig.minimapEnabled));
    }

    /**
     * "Locked" means North stays at the top. Unlocked means the map rotates with
     * the player.
     */
    public static void toggleNorthLock(Minecraft mc) {
        MapConfig.minimapRotate = !MapConfig.minimapRotate;
        MapConfig.save();
        notify(mc, MapConfig.minimapRotate ? "Minimap: ROTATING" : "Minimap: NORTH LOCKED");
    }

    public static void zoomIn(Minecraft mc) {
        MapConfig.minimapZoom = Math.min(MAX_ZOOM, MapConfig.minimapZoom * ZOOM_STEP);
        MapConfig.save();
        notify(mc, String.format(Locale.ROOT, "Minimap zoom: %.2fx", MapConfig.minimapZoom));
    }

    public static void zoomOut(Minecraft mc) {
        MapConfig.minimapZoom = Math.max(MIN_ZOOM, MapConfig.minimapZoom / ZOOM_STEP);
        MapConfig.save();
        notify(mc, String.format(Locale.ROOT, "Minimap zoom: %.2fx", MapConfig.minimapZoom));
    }

    public static void resetZoom(Minecraft mc) {
        MapConfig.minimapZoom = 1.0f;
        MapConfig.save();
        notify(mc, "Minimap zoom: 1.00x");
    }

    public static void cycleNightMode(Minecraft mc) {
        MapConfig.minimapNightMode = (MapConfig.minimapNightMode + 1) % 3;
        MapConfig.save();
        String mode = switch (MapConfig.minimapNightMode) {
            case 1 -> "AUTO";
            case 2 -> "ON";
            default -> "OFF";
        };
        notify(mc, "Map brightness: " + mode);
    }

    public static void cycleCaveMode(Minecraft mc) {
        if (!ready(mc)) return;
        if (MapConfig.getEffectiveCaveMapMode() == 0) {
            notify(mc, MapConfig.serverExtensionAvailable
                    ? "Cave map: disabled by server"
                    : "Cave map: disabled in local settings");
            return;
        }
        if (MapConfig.getEffectiveCaveMapMode() == 1) {
            notify(mc, MapConfig.serverExtensionAvailable
                    ? "Cave map: automatic (server controlled)"
                    : "Cave map: automatic (local setting)");
            return;
        }
        CaveMode.cycleCaveType(mc);
        if (CaveMode.getCaveType(mc) == CaveMode.CaveType.OFF) {
            CaveMapManager.getInstance().deactivate();
        }
        notify(mc, "Cave map: " + switch (CaveMode.getCaveType(mc)) {
            case OFF -> "OFF";
            case LAYERED -> "LAYERED";
            case FULL -> "FULL";
        });
    }

    public static void toggleWaypoints(Minecraft mc) {
        MapConfig.waypointsVisible = !MapConfig.waypointsVisible;
        MapConfig.save();
        notify(mc, "Waypoints: " + onOff(MapConfig.waypointsVisible));
    }

    public static void toggleCoordinates(Minecraft mc) {
        MapConfig.coordsEnabled = !MapConfig.coordsEnabled;
        MapConfig.save();
        notify(mc, "Coordinates: " + onOff(MapConfig.coordsEnabled));
    }

    public static void toggleShape(Minecraft mc) {
        MapConfig.minimapCircle = !MapConfig.minimapCircle;
        MapConfig.save();
        notify(mc, MapConfig.minimapCircle ? "Minimap shape: CIRCLE" : "Minimap shape: SQUARE");
    }

    public static void toggleScreenVisibility(Minecraft mc) {
        MapConfig.showMinimapInScreens = !MapConfig.showMinimapInScreens;
        MapConfig.save();
        notify(mc, "Minimap in menus: " + onOff(MapConfig.showMinimapInScreens));
    }

    public static void toggleFastFullscreenLoading(Minecraft mc) {
        MapConfig.fastFullscreenLoading = !MapConfig.fastFullscreenLoading;
        MapConfig.save();
        notify(mc, "Full map loading: " + (MapConfig.fastFullscreenLoading ? "FAST" : "BALANCED"));
    }

    public static void addWaypointAtPlayer(Minecraft mc) {
        if (!ready(mc)) return;
        mc.setScreen(new AddWaypointScreen(mc.screen, mc.player.getX(), mc.player.getZ(),
                MapManager.getInstance().getCurrentDimensionId()));
    }

    public static void openSettings(Minecraft mc) {
        mc.setScreen(new MapConfigScreen(mc.screen));
    }

    public static void refreshVisibleMap(Minecraft mc) {
        if (!ready(mc)) return;
        ChunkScanner.getInstance().requestRefresh(mc);
        if (CaveMode.isFullView(mc)) {
            FullCaveTextureManager.getInstance().uploadDirtyTextures(true);
        } else if (CaveMode.isActive(mc)) {
            CaveTextureManager.getInstance().uploadDirtyTextures(true);
        } else {
            MapTextureManager.getInstance().uploadDirtyTextures(true);
        }
        notify(mc, "Visible map refresh requested");
    }

    public static void clearNavigationPin(Minecraft mc) {
        if (!MapConfig.pinActive) {
            notify(mc, "Navigation pin: none");
            return;
        }
        MapConfig.pinActive = false;
        MapManager.getInstance().savePin();
        notify(mc, "Navigation pin cleared");
    }

    public static void toggleCompass(Minecraft mc) {
        MapConfig.compassLettersVisible = !MapConfig.compassLettersVisible;
        MapConfig.save();
        notify(mc, "Compass letters: " + onOff(MapConfig.compassLettersVisible));
    }

    public static void toggleCursorBiome(Minecraft mc) {
        MapConfig.cursorBiomeEnabled = !MapConfig.cursorBiomeEnabled;
        MapConfig.save();
        notify(mc, "Cursor biome: " + onOff(MapConfig.cursorBiomeEnabled));
    }

    public static void cycleColorMode(Minecraft mc) {
        MapConfig.blockColourMode = (MapConfig.blockColourMode + 1) % 2;
        invalidateMapStyle();
        MapConfig.save();
        notify(mc, "Map colors: " + (MapConfig.blockColourMode == 0 ? "ACCURATE" : "VANILLA"));
    }

    public static void cycleTerrainMode(Minecraft mc) {
        MapConfig.terrainSlopes = (MapConfig.terrainSlopes + 1) % 3;
        invalidateMapStyle();
        MapConfig.save();
        String mode = switch (MapConfig.terrainSlopes) {
            case 1 -> "2D";
            case 2 -> "3D";
            default -> "OFF";
        };
        notify(mc, "Terrain relief: " + mode);
    }

    private static void invalidateMapStyle() {
        MapTextureManager.getInstance().invalidateStyle();
        CaveTextureManager.getInstance().invalidateStyle();
        FullCaveTextureManager.getInstance().invalidateStyle();
    }

    private static boolean ready(Minecraft mc) {
        return mc != null && mc.player != null && mc.level != null;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private static void notify(Minecraft mc, String text) {
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal(text), true);
        }
    }
}
