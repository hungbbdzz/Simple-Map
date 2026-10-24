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
            player.sendSystemMessage(Component.translatable("gui.simplemap.book.no_regions_to_save"));
            return;
        }

        player.sendSystemMessage(Component.translatable("gui.simplemap.book.preparing_save", regionNames.size()));
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
            player.sendSystemMessage(Component.translatable("gui.simplemap.book.invalid"));
            return;
        }

        String bookId = tag.getString("MapBookID");
        if (player.isSecondaryUseActive()) {
            boolean isOwner = !tag.contains("OwnerUUID")
                    || tag.getString("OwnerUUID").equals(player.getUUID().toString());
            if (!isOwner) {
                player.sendSystemMessage(Component.translatable("gui.simplemap.book.not_owner"));
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

        player.sendSystemMessage(Component.translatable("gui.simplemap.book.reading"));
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
            player.sendSystemMessage(Component.translatable("gui.simplemap.book.no_regions_to_save"));
            return;
        }

        player.sendSystemMessage(Component.translatable("gui.simplemap.book.preparing_update", regionNames.size()));
        if (ClientNetworkHandler.sendToServer(
                new InitSaveBookPayload(regionNames, hand == InteractionHand.MAIN_HAND, bookId))) {
            player.getCooldowns().addCooldown(cooldownItem, 120);
        }
    }

    private static void unavailable(Player player) {
        player.sendSystemMessage(Component.translatable("gui.simplemap.book.server_required"));
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
            notify(mc, Component.translatable("gui.simplemap.msg.minimap_toggle", onOff(MapConfig.minimapEnabled)));
        }

        public static void toggleNorthLock(Minecraft mc) {
            MapConfig.minimapRotate = !MapConfig.minimapRotate;
            MapConfig.save();
            notify(mc, Component.translatable(MapConfig.minimapRotate
                    ? "gui.simplemap.msg.minimap_rotating"
                    : "gui.simplemap.msg.minimap_north_locked"));
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
            Component mode = switch (MapConfig.minimapNightMode) {
                case 1 -> Component.translatable("gui.simplemap.value.auto");
                case 2 -> Component.translatable("gui.simplemap.value.on");
                default -> Component.translatable("gui.simplemap.value.off");
            };
            notify(mc, Component.translatable("gui.simplemap.msg.map_brightness", mode));
        }

        public static void cycleCaveMode(Minecraft mc) {
            if (!ready(mc)) return;
            if (MapConfig.getEffectiveCaveMapMode() == 0) {
                notify(mc, Component.translatable(MapConfig.serverExtensionAvailable
                    ? "gui.simplemap.msg.cave_disabled_server"
                    : "gui.simplemap.msg.cave_disabled_local"));
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
            notify(mc, Component.translatable("gui.simplemap.msg.cave_mode", switch (type) {
                case OFF -> Component.translatable("gui.simplemap.cave_mode.off");
                case LAYERED -> Component.translatable("gui.simplemap.cave_mode.cave");
                case FULL -> Component.translatable("gui.simplemap.cave_mode.full");
            }));
        }

        public static void toggleWaypoints(Minecraft mc) {
            MapConfig.waypointsVisible = !MapConfig.waypointsVisible;
            MapConfig.save();
            notify(mc, Component.translatable("gui.simplemap.msg.waypoints_toggle", onOff(MapConfig.waypointsVisible)));
        }

        public static void toggleCoordinates(Minecraft mc) {
            MapConfig.coordsEnabled = !MapConfig.coordsEnabled;
            MapConfig.save();
            notify(mc, Component.translatable("gui.simplemap.msg.coordinates_toggle", onOff(MapConfig.coordsEnabled)));
        }

        public static void toggleShape(Minecraft mc) {
            MapConfig.minimapCircle = !MapConfig.minimapCircle;
            MapConfig.save();
            notify(mc, Component.translatable("gui.simplemap.msg.minimap_shape",
                    Component.translatable(MapConfig.minimapCircle ? "gui.simplemap.value.circle" : "gui.simplemap.value.square")));
        }

        public static void toggleScreenVisibility(Minecraft mc) {
            MapConfig.showMinimapInScreens = !MapConfig.showMinimapInScreens;
            MapConfig.save();
            notify(mc, Component.translatable("gui.simplemap.msg.minimap_in_menus", onOff(MapConfig.showMinimapInScreens)));
        }

        public static void toggleFastFullscreenLoading(Minecraft mc) {
            MapConfig.fastFullscreenLoading = !MapConfig.fastFullscreenLoading;
            MapConfig.save();
            notify(mc, Component.translatable("gui.simplemap.msg.full_map_loading",
                    Component.translatable(MapConfig.fastFullscreenLoading ? "gui.simplemap.value.fast" : "gui.simplemap.value.balanced")));
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
            mc.setScreen(new MapConfigScreen(mc.screen instanceof MapScreen ? mc.screen : new MapScreen()));
        }

        public static void refreshVisibleMap(Minecraft mc) {
            if (!ready(mc)) return;
            ChunkScanner.getInstance().requestRefresh(mc);
            if (CaveMode.isActive(mc)) {
                CaveTextureManager.getInstance().uploadDirtyTextures(true);
            } else {
                MapTextureManager.getInstance().uploadDirtyTextures(true);
            }
            notify(mc, Component.translatable("gui.simplemap.msg.refresh_requested"));
        }

        public static void clearNavigationPin(Minecraft mc) {
            if (!MapConfig.pinActive) {
                notify(mc, Component.translatable("gui.simplemap.msg.pin_none"));
                return;
            }
            PinNavigation.clear();
            MapManager.getInstance().savePin();
            notify(mc, Component.translatable("gui.simplemap.msg.pin_cleared"));
        }

        public static void toggleCompass(Minecraft mc) {
            MapConfig.compassLettersVisible = !MapConfig.compassLettersVisible;
            MapConfig.save();
            notify(mc, Component.translatable("gui.simplemap.msg.compass_letters", onOff(MapConfig.compassLettersVisible)));
        }

        public static void toggleCursorBiome(Minecraft mc) {
            MapConfig.cursorBiomeEnabled = !MapConfig.cursorBiomeEnabled;
            MapConfig.save();
            notify(mc, Component.translatable("gui.simplemap.msg.cursor_biome", onOff(MapConfig.cursorBiomeEnabled)));
        }

        public static void cycleColorMode(Minecraft mc) {
            MapConfig.blockColourMode = (MapConfig.blockColourMode + 1) % 2;
            invalidateMapStyle();
            MapConfig.save();
            notify(mc, Component.translatable("gui.simplemap.msg.map_colors",
                    Component.translatable(MapConfig.blockColourMode == 0 ? "gui.simplemap.value.accurate" : "gui.simplemap.value.vanilla")));
        }

        public static void cycleTerrainMode(Minecraft mc) {
            MapConfig.terrainSlopes = (MapConfig.terrainSlopes + 1) % 3;
            invalidateMapStyle();
            MapConfig.save();
            Component mode = switch (MapConfig.terrainSlopes) {
                case 1 -> Component.translatable("gui.simplemap.value.2d");
                case 2 -> Component.translatable("gui.simplemap.value.3d");
                default -> Component.translatable("gui.simplemap.value.off");
            };
            notify(mc, Component.translatable("gui.simplemap.msg.terrain_relief", mode));
        }

        private static void invalidateMapStyle() {
            MapSessionManager.getInstance().bumpStyleGeneration();
            MapTextureManager.getInstance().invalidateStyle();
            CaveTextureManager.getInstance().invalidateStyle();
        }

        private static boolean ready(Minecraft mc) {
            return mc != null && mc.player != null && mc.level != null;
        }

        private static Component onOff(boolean value) {
            return Component.translatable(value ? "gui.simplemap.value.on" : "gui.simplemap.value.off");
        }

        private static void notify(Minecraft mc, String text) {
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(Component.literal(text), true);
            }
        }

        private static void notify(Minecraft mc, Component message) {
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(message, true);
            }
        }
    }
}
