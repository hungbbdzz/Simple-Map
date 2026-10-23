package com.velorise.simplemap.client;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.client.MapManager.MapConfig;
import com.velorise.simplemap.client.gui.MapScreen.MapConfigScreen;
import com.velorise.simplemap.client.gui.MapScreen.MapConfigScreen.WaypointListScreen;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager.Waypoint;

import com.velorise.simplemap.MapBookHooks;
import com.velorise.simplemap.SimpleMap;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.gui.*;
import com.velorise.simplemap.client.waypoint.*;
import com.velorise.simplemap.client.session.MapSessionManager;
import com.velorise.simplemap.network.ClientNetworkHandler;
import com.velorise.simplemap.network.payload.InitSaveBookPayload;
import com.velorise.simplemap.network.payload.RequestLearnBookPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.List;

/**
 * Physical-client entrypoint. Keeps client bootstrap code out of the common mod
 * constructor.
 */
@Mod(value = SimpleMap.MODID, dist = Dist.CLIENT)
public final class SimpleMapClientMod {
    public SimpleMapClientMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (client, parent) -> new MapConfigScreen(parent));
        MapBookHooks.install(SimpleMapClientMod::useEmptyBook, SimpleMapClientMod::useWrittenBook);
        ClientNetworkHandler.installPayloadHooks();
    }

    public static void useEmptyBook(Player player, InteractionHand hand, Item cooldownItem) {
        if (player == null) return;
        if (!ClientNetworkHandler.isServerExtensionAvailable()) {
            unavailable(player);
            return;
        }
        List<String> regionNames = MapManager.getInstance().getKnownRegionNamesForBook(4096);
        if (regionNames.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cYou haven't explored any map regions to save!"));
            return;
        }

        player.sendSystemMessage(Component.literal(
                "§bPreparing to save " + regionNames.size() + " map regions to the book..."));
        if (ClientNetworkHandler.sendToServer(
                new InitSaveBookPayload(regionNames, hand == InteractionHand.MAIN_HAND, ""))) {
            player.getCooldowns().addCooldown(cooldownItem, 120);
        }
    }

    public static void useWrittenBook(Player player, InteractionHand hand, ItemStack stack, Item cooldownItem) {
        if (player == null || stack == null || stack.isEmpty()) return;
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : null;
        if (tag == null || !tag.contains("MapBookID")) {
            player.sendSystemMessage(Component.literal("§cThis map book is invalid or contains no data!"));
            return;
        }

        String bookId = tag.getString("MapBookID");
        if (player.isSecondaryUseActive()) {
            boolean isOwner = !tag.contains("OwnerUUID")
                    || tag.getString("OwnerUUID").equals(player.getUUID().toString());
            if (!isOwner) {
                player.sendSystemMessage(Component.literal(
                        "§cYou cannot update a map book that you do not own!"));
                return;
            }
            updateBook(player, hand, bookId, cooldownItem);
            return;
        }

        boolean isOwner = tag.contains("OwnerUUID")
                && tag.getString("OwnerUUID").equals(player.getUUID().toString());
        MapManager mapManager = MapManager.getInstance();
        if (mapManager.hasLearnedMap()
                && (isOwner || bookId.equals(mapManager.getLastLearnedBookId()))) {
            mapManager.openMapScreen();
            return;
        }
        if (!ClientNetworkHandler.isServerExtensionAvailable()) {
            unavailable(player);
            return;
        }

        player.sendSystemMessage(Component.literal(
                "§bReading map book and syncing exploration data..."));
        if (ClientNetworkHandler.sendToServer(new RequestLearnBookPayload(bookId))) {
            player.getCooldowns().addCooldown(cooldownItem, 120);
        }
    }

    private static void updateBook(Player player, InteractionHand hand, String bookId, Item cooldownItem) {
        if (!ClientNetworkHandler.isServerExtensionAvailable()) {
            unavailable(player);
            return;
        }
        List<String> regionNames = MapManager.getInstance().getKnownRegionNamesForBook(4096);
        if (regionNames.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cYou haven't explored any map regions to save!"));
            return;
        }

        player.sendSystemMessage(Component.literal(
                "§bPreparing to update " + regionNames.size() + " map regions to the book..."));
        if (ClientNetworkHandler.sendToServer(
                new InitSaveBookPayload(regionNames, hand == InteractionHand.MAIN_HAND, bookId))) {
            player.getCooldowns().addCooldown(cooldownItem, 120);
        }
    }

    private static void unavailable(Player player) {
        player.sendSystemMessage(Component.literal(
                "§eMap Books require Simple Map on the server. Your local minimap still works."));
    }

    /** Centralized, side-safe actions used by configurable key mappings. */
    public static final class MapKeybindActions {
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

        public static void toggleNorthLock(Minecraft mc) {
            MapConfig.minimapRotate = !MapConfig.minimapRotate;
            MapConfig.save();
            notify(mc, MapConfig.minimapRotate ? "Minimap: ROTATING" : "Minimap: NORTH LOCKED");
        }

        public static void zoomIn(Minecraft mc) {
            MapConfig.minimapZoom = Math.min(MAX_ZOOM, MapConfig.minimapZoom * ZOOM_STEP);
            MapConfig.save();
            notify(mc, String.format(java.util.Locale.ROOT, "%.2fx", MapConfig.minimapZoom));
        }

        public static void zoomOut(Minecraft mc) {
            MapConfig.minimapZoom = Math.max(MapConfig.MINIMUM_ZOOM_SCALE,
                    MapConfig.minimapZoom / ZOOM_STEP);
            MapConfig.save();
            notify(mc, String.format(java.util.Locale.ROOT, "%.2fx", MapConfig.minimapZoom));
        }

        public static void resetZoom(Minecraft mc) {
            MapConfig.minimapZoom = 1.0f;
            MapConfig.save();
            notify(mc, "1.00x");
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
            CaveMode.cycleCaveType(mc);
            CaveMode.CaveType type = CaveMode.getCaveType(mc);
            switch (type) {
                case OFF -> CaveMapManager.getInstance().deactivate();
                case LAYERED -> ChunkScanner.getInstance()
                        .requestImmediateCaveLayerRefresh(mc);
                case FULL -> ChunkScanner.getInstance()
                        .requestImmediateCaveLayerRefresh(mc);
            }
            notify(mc, "Cave map: " + switch (type) {
                case OFF -> "OFF";
                case LAYERED -> "CAVE";
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
            mc.setScreen(new WaypointListScreen.AddWaypointScreen(mc.screen,
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    MapManager.getInstance().getLiveDimensionResourceId()));
        }

        public static void openWaypointList(Minecraft mc) {
            if (!ready(mc)) return;
            if (mc.screen instanceof WaypointListScreen) {
                mc.setScreen(null);
            } else {
                mc.setScreen(new WaypointListScreen(mc.screen));
            }
        }

        public static void openSettings(Minecraft mc) {
            mc.setScreen(new MapConfigScreen(mc.screen));
        }

        public static void refreshVisibleMap(Minecraft mc) {
            if (!ready(mc)) return;
            ChunkScanner.getInstance().requestRefresh(mc);
            if (CaveMode.isActive(mc)) {
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
            PinNavigation.clear();
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
            MapSessionManager.getInstance().bumpStyleGeneration();
            MapTextureManager.getInstance().invalidateStyle();
            CaveTextureManager.getInstance().invalidateStyle();
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
}
