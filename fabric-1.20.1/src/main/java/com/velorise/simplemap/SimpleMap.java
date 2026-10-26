package com.velorise.simplemap;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.velorise.simplemap.client.ChunkScanner;
import com.velorise.simplemap.client.MapActivityGate;
import com.velorise.simplemap.client.MapDebugRecorder;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapManager.MapArchitectureCoordinator;
import com.velorise.simplemap.client.MapManager.MapConfig;
import com.velorise.simplemap.client.MapManager.MapTeleportController;
import com.velorise.simplemap.client.MapPerformanceGovernor;
import com.velorise.simplemap.client.MapViewportCoordinator;
import com.velorise.simplemap.client.MapWorkScheduler.MapObservationScheduler;
import com.velorise.simplemap.client.MinimapRenderer;
import com.velorise.simplemap.client.RegionDataStore;
import com.velorise.simplemap.client.SimpleMapClientMod.MapKeybindActions;
import com.velorise.simplemap.client.cave.CaveMode;
import com.velorise.simplemap.client.gui.MapScreen;
import com.velorise.simplemap.client.waypoint.PinNavigation;
import com.velorise.simplemap.client.waypoint.PinNavigation.WaypointManager;
import com.velorise.simplemap.network.ClientNetworkHandler;
import com.velorise.simplemap.network.NetworkHandler;
import com.velorise.simplemap.network.payload.SyncConfigPayload;
import com.velorise.simplemap.recipe.ModRecipes;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public class SimpleMap implements ModInitializer {
    public static final String MODID = "simplemap";
    private static final Logger LOGGER = LogManager.getLogger();

    @Override
    public void onInitialize() {
        ServerConfig.init();

        // Register Items, Recipes, and Network Payloads
        ModItems.register();
        ModRecipes.register();
        NetworkHandler.register();

        // Register creative tab contents
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(ModItems.EMPTY_MAP_BOOK.get());
            entries.accept(ModItems.MAP_BOOK.get());
        });

        // Register server play connection callbacks
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                CommonGameEvents.onPlayerLoggedIn(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                CommonGameEvents.onPlayerLoggedOut(handler.getPlayer()));
    }

    // =======================================================================
    // Client-only Mod KeyMappings & Bootstrap
    // =======================================================================
    public static class ClientModEvents {
        private static final String KEY_CATEGORY = "key.categories.simplemap";
        public static final KeyMapping OPEN_MAP_KEY = key("key.simplemap.open_map", GLFW.GLFW_KEY_M);
        public static final KeyMapping TOGGLE_MINIMAP_KEY = key("key.simplemap.toggle_minimap", GLFW.GLFW_KEY_N);
        public static final KeyMapping TOGGLE_NORTH_LOCK_KEY = key("key.simplemap.toggle_north_lock", GLFW.GLFW_KEY_L);
        public static final KeyMapping ZOOM_IN_KEY = key("key.simplemap.zoom_in", GLFW.GLFW_KEY_EQUAL);
        public static final KeyMapping ZOOM_OUT_KEY = key("key.simplemap.zoom_out", GLFW.GLFW_KEY_MINUS);

        // Useful actions exposed in Controls without forcing extra default conflicts.
        public static final KeyMapping RESET_ZOOM_KEY = unbound("key.simplemap.reset_zoom");
        public static final KeyMapping ADD_WAYPOINT_KEY = unbound("key.simplemap.add_waypoint");
        public static final KeyMapping OPEN_WAYPOINT_LIST_KEY = key("key.simplemap.open_waypoint_list", GLFW.GLFW_KEY_U);
        public static final KeyMapping TOGGLE_WAYPOINTS_KEY = unbound("key.simplemap.toggle_waypoints");
        public static final KeyMapping TOGGLE_COORDS_KEY = unbound("key.simplemap.toggle_coordinates");
        public static final KeyMapping CYCLE_CAVE_MODE_KEY = unbound("key.simplemap.cycle_cave_mode");
        public static final KeyMapping CYCLE_NIGHT_MODE_KEY = unbound("key.simplemap.cycle_night_mode");
        public static final KeyMapping TOGGLE_SHAPE_KEY = unbound("key.simplemap.toggle_shape");
        public static final KeyMapping TOGGLE_SCREEN_VISIBILITY_KEY = unbound("key.simplemap.toggle_screen_visibility");
        public static final KeyMapping TOGGLE_FAST_LOADING_KEY = unbound("key.simplemap.toggle_fast_loading");
        public static final KeyMapping OPEN_SETTINGS_KEY = unbound("key.simplemap.open_settings");
        public static final KeyMapping REFRESH_MAP_KEY = unbound("key.simplemap.refresh_map");
        public static final KeyMapping CLEAR_PIN_KEY = unbound("key.simplemap.clear_pin");
        public static final KeyMapping TOGGLE_COMPASS_KEY = unbound("key.simplemap.toggle_compass");
        public static final KeyMapping TOGGLE_CURSOR_BIOME_KEY = unbound("key.simplemap.toggle_cursor_biome");
        public static final KeyMapping CYCLE_COLOR_MODE_KEY = unbound("key.simplemap.cycle_color_mode");
        public static final KeyMapping CYCLE_TERRAIN_MODE_KEY = unbound("key.simplemap.cycle_terrain_mode");
        public static final KeyMapping CENTER_FULL_MAP_KEY = unbound("key.simplemap.center_full_map");
        /** Diagnostic build controls. F8/F9/F10 are intentionally grouped. */
        public static final KeyMapping TOGGLE_DEBUG_OVERLAY_KEY =
                key("key.simplemap.toggle_debug_overlay", GLFW.GLFW_KEY_F8);
        public static final KeyMapping TOGGLE_DEBUG_RECORDING_KEY =
                key("key.simplemap.toggle_debug_recording", GLFW.GLFW_KEY_F9);
        public static final KeyMapping MARK_DEBUG_ISSUE_KEY =
                key("key.simplemap.mark_debug_issue", GLFW.GLFW_KEY_F10);

        private static KeyMapping key(String translationKey, int glfwKey) {
            return new KeyMapping(translationKey, glfwKey, KEY_CATEGORY);
        }

        private static KeyMapping unbound(String translationKey) {
            return key(translationKey, GLFW.GLFW_KEY_UNKNOWN);
        }

        public static void onClientSetup() {
            MapConfig.init();
        }

        public static void registerKeyMappings() {
            KeyBindingHelper.registerKeyBinding(OPEN_MAP_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_MINIMAP_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_NORTH_LOCK_KEY);
            KeyBindingHelper.registerKeyBinding(ZOOM_IN_KEY);
            KeyBindingHelper.registerKeyBinding(ZOOM_OUT_KEY);
            KeyBindingHelper.registerKeyBinding(RESET_ZOOM_KEY);
            KeyBindingHelper.registerKeyBinding(ADD_WAYPOINT_KEY);
            KeyBindingHelper.registerKeyBinding(OPEN_WAYPOINT_LIST_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_WAYPOINTS_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_COORDS_KEY);
            KeyBindingHelper.registerKeyBinding(CYCLE_CAVE_MODE_KEY);
            KeyBindingHelper.registerKeyBinding(CYCLE_NIGHT_MODE_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_SHAPE_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_SCREEN_VISIBILITY_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_FAST_LOADING_KEY);
            KeyBindingHelper.registerKeyBinding(OPEN_SETTINGS_KEY);
            KeyBindingHelper.registerKeyBinding(REFRESH_MAP_KEY);
            KeyBindingHelper.registerKeyBinding(CLEAR_PIN_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_COMPASS_KEY);
            KeyBindingHelper.registerKeyBinding(TOGGLE_CURSOR_BIOME_KEY);
            KeyBindingHelper.registerKeyBinding(CYCLE_COLOR_MODE_KEY);
            KeyBindingHelper.registerKeyBinding(CYCLE_TERRAIN_MODE_KEY);
            KeyBindingHelper.registerKeyBinding(CENTER_FULL_MAP_KEY);
            if (MapDebugRecorder.DEBUG_ENABLED) {
                KeyBindingHelper.registerKeyBinding(TOGGLE_DEBUG_OVERLAY_KEY);
                KeyBindingHelper.registerKeyBinding(TOGGLE_DEBUG_RECORDING_KEY);
                KeyBindingHelper.registerKeyBinding(MARK_DEBUG_ISSUE_KEY);
            }
        }
    }

    // =======================================================================
    // Common Game Events (Server & Client)
    // =======================================================================
    public static class CommonGameEvents {
        private static final int MAX_MERGED_REGION_FILES = 16_384;
        private static final ExecutorService MAP_BOOK_MERGE_POOL = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SimpleMap-BookMerge");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        });

        public static void onPlayerLoggedIn(ServerPlayer serverPlayer) {
            if (serverPlayer != null) {
                boolean sent = NetworkHandler.sendToPlayer(serverPlayer,
                        new SyncConfigPayload(ServerConfig.requireMapBook, ServerConfig.caveMapMode));
                if (sent) {
                    LOGGER.info("Synced server config (requireBook={}, caveMode={}) to joining player: {}",
                            ServerConfig.requireMapBook, ServerConfig.caveMapMode,
                            serverPlayer.getName().getString());
                }
            }
        }

        public static void onPlayerLoggedOut(ServerPlayer serverPlayer) {
            if (serverPlayer != null) {
                NetworkHandler.clearSessionsForPlayer(serverPlayer.getUUID());
            }
        }

        public static void onItemCrafted(Player player, ItemStack result, Container container) {
            if (player == null || result == null || container == null) return;
            CompoundTag tag = result.getTag();

            if (result.is(ModItems.MAP_BOOK.get()) && tag != null && tag.contains("PendingMerge")) {
                if (player.level().isClientSide) return; // Server only

                String firstId = null;
                String secondId = null;

                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    CompoundTag stackTag = stack.getTag();

                    if (stack.is(ModItems.MAP_BOOK.get()) && stackTag != null) {
                        String id = stackTag.getString("MapBookID");
                        if (!id.isEmpty()) {
                            if (firstId == null) {
                                firstId = id;
                            } else if (secondId == null) {
                                secondId = id;
                            }
                        }
                    }
                }

                if (firstId != null && secondId != null) {
                    if (!firstId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                            || !secondId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                        LOGGER.warn("[SimpleMap] Merge blocked: invalid MapBookID format detected in crafting inventory");
                        return;
                    }

                    String newBookId = UUID.randomUUID().toString();

                    var server = player.getServer();
                    if (server == null) {
                        LOGGER.warn("[SimpleMap] Merge blocked because no server instance was available");
                        return;
                    }
                    Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                            .toAbsolutePath().normalize();
                    Path booksBasePath = worldDir.resolve("simplemap_books").toAbsolutePath().normalize();
                    File dir1 = booksBasePath.resolve(firstId).toAbsolutePath().normalize().toFile();
                    File dir2 = booksBasePath.resolve(secondId).toAbsolutePath().normalize().toFile();
                    File newDir = booksBasePath.resolve(newBookId).toAbsolutePath().normalize().toFile();

                    if (!dir1.toPath().toAbsolutePath().normalize().startsWith(booksBasePath)
                            || !dir2.toPath().toAbsolutePath().normalize().startsWith(booksBasePath)
                            || !newDir.toPath().toAbsolutePath().normalize().startsWith(booksBasePath)) {
                        LOGGER.warn("[SimpleMap] Merge blocked: path containment check failed for book IDs");
                        return;
                    }

                    try {
                        Files.createDirectories(newDir.toPath());
                        Files.writeString(newDir.toPath().resolve(".merge_pending"),
                                "SimpleMap map-book merge in progress", java.nio.charset.StandardCharsets.UTF_8);
                    } catch (IOException exception) {
                        LOGGER.error("Could not initialize merged map-book directory {}", newBookId, exception);
                        return;
                    }

                    tag.remove("PendingMerge");
                    tag.putString("MapBookID", newBookId);
                    result.setTag(tag);
                    result.setHoverName(Component.translatable("item.simplemap.merged_book_map"));

                    UUID playerId = player.getUUID();
                    MAP_BOOK_MERGE_POOL.execute(() -> {
                        int failures = mergeFolders(dir1, dir2, newDir);
                        boolean emptySources = !dir1.isDirectory() && !dir2.isDirectory();
                        try {
                            Files.deleteIfExists(newDir.toPath().resolve(".merge_pending"));
                            if (failures > 0 || emptySources) {
                                Files.writeString(newDir.toPath().resolve(".merge_warnings"),
                                        emptySources
                                                ? "Both source map-book directories were missing."
                                                : "Skipped " + failures + " unreadable region file(s).",
                                        java.nio.charset.StandardCharsets.UTF_8);
                            }
                        } catch (IOException exception) {
                            LOGGER.warn("Could not finalize merged map-book {}", newBookId, exception);
                        }
                        server.execute(() -> {
                            var online = server.getPlayerList().getPlayer(playerId);
                            if (online != null) {
                                online.sendSystemMessage(Component.translatable(emptySources
                                        ? "gui.simplemap.book.merge_ready_empty"
                                        : failures == 0
                                                ? "gui.simplemap.book.merge_ready"
                                                : "gui.simplemap.book.merge_ready_skipped"));
                            }
                        });
                    });
                }
            }
        }

        private static int mergeFolders(File src1, File src2, File dest) {
            Set<String> subPaths = new LinkedHashSet<>();
            collectRegionSubPaths(src1, subPaths);
            collectRegionSubPaths(src2, subPaths);

            Path src1Path = src1.toPath().toAbsolutePath().normalize();
            Path src2Path = src2.toPath().toAbsolutePath().normalize();
            Path destPath = dest.toPath().toAbsolutePath().normalize();
            int failures = 0;
            int processed = 0;

            for (String subPath : subPaths) {
                if (processed++ >= MAX_MERGED_REGION_FILES) {
                    LOGGER.warn("Merged map book reached the {} region safety limit",
                            MAX_MERGED_REGION_FILES);
                    failures++;
                    break;
                }
                Path firstPath = src1Path.resolve(subPath).normalize();
                Path secondPath = src2Path.resolve(subPath).normalize();
                Path outputPath = destPath.resolve(subPath).normalize();
                if (!firstPath.startsWith(src1Path) || !secondPath.startsWith(src2Path)
                        || !outputPath.startsWith(destPath)) {
                    LOGGER.warn("Skipped unsafe map-book merge path {}", subPath);
                    failures++;
                    continue;
                }

                File first = firstPath.toFile();
                File second = secondPath.toFile();
                File output = outputPath.toFile();
                try {
                    Files.createDirectories(outputPath.getParent());
                    if (first.isFile() && second.isFile()) {
                        RegionDataStore.StoredRegion merged = RegionDataStore.merge(
                                RegionDataStore.read(second), RegionDataStore.read(first));
                        RegionDataStore.writeAtomic(output, merged);
                    } else if (first.isFile()) {
                        RegionDataStore.writeAtomic(output, RegionDataStore.read(first));
                    } else if (second.isFile()) {
                        RegionDataStore.writeAtomic(output, RegionDataStore.read(second));
                    }
                } catch (IOException | RuntimeException exception) {
                    failures++;
                    LOGGER.error("Failed to merge map-book region {}", subPath, exception);
                }
            }
            return failures;
        }

        private static void collectRegionSubPaths(File root, Set<String> output) {
            if (root == null || !root.isDirectory()) return;
            Path rootPath = root.toPath().toAbsolutePath().normalize();
            try (Stream<Path> paths = Files.walk(rootPath)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> NetworkHandler.isValidRegionName(path.getFileName().toString()))
                        .limit(MAX_MERGED_REGION_FILES)
                        .forEach(path -> {
                            Path normalized = path.toAbsolutePath().normalize();
                            if (normalized.startsWith(rootPath)) {
                                output.add(rootPath.relativize(normalized).toString().replace('\\', '/'));
                            }
                        });
            } catch (IOException exception) {
                LOGGER.warn("Could not enumerate map-book directory {}", root, exception);
            }
        }
    }

    // =======================================================================
    // Client-only Game Events
    // =======================================================================
    public static class ClientGameEvents {
        private static boolean wasPlayerDead;
        /** One monotonically increasing ledger shared by GUI and Screen render hooks. */
        private static long mapRenderFrameId;

        public static void onClientTick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            MapTeleportController.tickTransition();
            MapActivityGate.getInstance().update(mc);

            // 1. Tick MapManager to update active directory and auto-save dirty regions
            MapManager.getInstance().updateWorldAndDimension(mc);
            MapManager.getInstance().tickSave();
            MapObservationScheduler.getInstance().tick(mc,
                    SimpleMap.isMapUnlocked(mc.player),
                    mc.screen instanceof MapScreen);
            if (MapDebugRecorder.DEBUG_ENABLED) {
                MapDebugRecorder.getInstance().tick(mc);
            }

            // 2.25. Create one bounded death waypoint on the transition into death.
            boolean playerDead = mc.player.isDeadOrDying();
            if (playerDead && !wasPlayerDead && MapConfig.createDeathWaypoint
                    && MapConfig.maxDeathWaypoints > 0) {
                WaypointManager.getInstance().addDeathWaypoint(
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                        MapManager.getInstance().getCurrentDimensionId(), MapConfig.maxDeathWaypoints);
            }
            wasPlayerDead = playerDead;

            // 2.5. Auto-clear pin if active, enabled, and player is within 5 blocks of the pin
            if (MapConfig.pinActive && MapConfig.autoClearPin) {
                double dx = mc.player.getX() - MapConfig.pinWorldX;
                double dz = mc.player.getZ() - MapConfig.pinWorldZ;
                double distSq = dx * dx + dz * dz;
                if (distSq <= 25.0) {
                    PinNavigation.clear();
                    MapManager.getInstance().savePin();
                    mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 1.0f);
                }
            }

            // 3. Handle configurable map actions only during normal gameplay.
            if (MapDebugRecorder.DEBUG_ENABLED) {
                drain(ClientModEvents.TOGGLE_DEBUG_OVERLAY_KEY,
                        () -> MapDebugRecorder.getInstance().toggleOverlay());
                drain(ClientModEvents.TOGGLE_DEBUG_RECORDING_KEY,
                        () -> MapDebugRecorder.getInstance().toggleRecording(mc));
                drain(ClientModEvents.MARK_DEBUG_ISSUE_KEY,
                        () -> MapDebugRecorder.getInstance().markIssue(mc));
            }

            if (mc.screen == null) {
                drain(ClientModEvents.OPEN_MAP_KEY, () -> MapKeybindActions.toggleFullMap(mc));
                drain(ClientModEvents.TOGGLE_MINIMAP_KEY, () -> MapKeybindActions.toggleMinimap(mc));
                drain(ClientModEvents.TOGGLE_NORTH_LOCK_KEY, () -> MapKeybindActions.toggleNorthLock(mc));
                drain(ClientModEvents.ZOOM_IN_KEY, () -> MapKeybindActions.zoomIn(mc));
                drain(ClientModEvents.ZOOM_OUT_KEY, () -> MapKeybindActions.zoomOut(mc));
                drain(ClientModEvents.RESET_ZOOM_KEY, () -> MapKeybindActions.resetZoom(mc));
                drain(ClientModEvents.ADD_WAYPOINT_KEY, () -> MapKeybindActions.addWaypointAtPlayer(mc));
                drain(ClientModEvents.OPEN_WAYPOINT_LIST_KEY, () -> MapKeybindActions.openWaypointList(mc));
                drain(ClientModEvents.TOGGLE_WAYPOINTS_KEY, () -> MapKeybindActions.toggleWaypoints(mc));
                drain(ClientModEvents.TOGGLE_COORDS_KEY, () -> MapKeybindActions.toggleCoordinates(mc));
                drain(ClientModEvents.CYCLE_CAVE_MODE_KEY, () -> MapKeybindActions.cycleCaveMode(mc));
                drain(ClientModEvents.CYCLE_NIGHT_MODE_KEY, () -> MapKeybindActions.cycleNightMode(mc));
                drain(ClientModEvents.TOGGLE_SHAPE_KEY, () -> MapKeybindActions.toggleShape(mc));
                drain(ClientModEvents.TOGGLE_SCREEN_VISIBILITY_KEY,
                        () -> MapKeybindActions.toggleScreenVisibility(mc));
                drain(ClientModEvents.TOGGLE_FAST_LOADING_KEY,
                        () -> MapKeybindActions.toggleFastFullscreenLoading(mc));
                drain(ClientModEvents.OPEN_SETTINGS_KEY, () -> MapKeybindActions.openSettings(mc));
                drain(ClientModEvents.REFRESH_MAP_KEY, () -> MapKeybindActions.refreshVisibleMap(mc));
                drain(ClientModEvents.CLEAR_PIN_KEY, () -> MapKeybindActions.clearNavigationPin(mc));
                drain(ClientModEvents.TOGGLE_COMPASS_KEY, () -> MapKeybindActions.toggleCompass(mc));
                drain(ClientModEvents.TOGGLE_CURSOR_BIOME_KEY, () -> MapKeybindActions.toggleCursorBiome(mc));
                drain(ClientModEvents.CYCLE_COLOR_MODE_KEY, () -> MapKeybindActions.cycleColorMode(mc));
                drain(ClientModEvents.CYCLE_TERRAIN_MODE_KEY, () -> MapKeybindActions.cycleTerrainMode(mc));
                discard(ClientModEvents.CENTER_FULL_MAP_KEY);
            } else {
                discardAllMapClicks();
            }
        }

        private static void drain(KeyMapping mapping, Runnable action) {
            if (!mapping.consumeClick()) return;
            action.run();
            discard(mapping);
        }

        private static void discard(KeyMapping mapping) {
            while (mapping.consumeClick()) {
            }
        }

        private static void discardAllMapClicks() {
            discard(ClientModEvents.OPEN_MAP_KEY);
            discard(ClientModEvents.TOGGLE_MINIMAP_KEY);
            discard(ClientModEvents.TOGGLE_NORTH_LOCK_KEY);
            discard(ClientModEvents.ZOOM_IN_KEY);
            discard(ClientModEvents.ZOOM_OUT_KEY);
            discard(ClientModEvents.RESET_ZOOM_KEY);
            discard(ClientModEvents.ADD_WAYPOINT_KEY);
            discard(ClientModEvents.OPEN_WAYPOINT_LIST_KEY);
            discard(ClientModEvents.TOGGLE_WAYPOINTS_KEY);
            discard(ClientModEvents.TOGGLE_COORDS_KEY);
            discard(ClientModEvents.CYCLE_CAVE_MODE_KEY);
            discard(ClientModEvents.CYCLE_NIGHT_MODE_KEY);
            discard(ClientModEvents.TOGGLE_SHAPE_KEY);
            discard(ClientModEvents.TOGGLE_SCREEN_VISIBILITY_KEY);
            discard(ClientModEvents.TOGGLE_FAST_LOADING_KEY);
            discard(ClientModEvents.OPEN_SETTINGS_KEY);
            discard(ClientModEvents.REFRESH_MAP_KEY);
            discard(ClientModEvents.CLEAR_PIN_KEY);
            discard(ClientModEvents.TOGGLE_COMPASS_KEY);
            discard(ClientModEvents.TOGGLE_CURSOR_BIOME_KEY);
            discard(ClientModEvents.CYCLE_COLOR_MODE_KEY);
            discard(ClientModEvents.CYCLE_TERRAIN_MODE_KEY);
            discard(ClientModEvents.CENTER_FULL_MAP_KEY);
        }

        public static void onRenderGui(GuiGraphics guiGraphics) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) {
                MapPerformanceGovernor.getInstance().onFrame();
                ChunkScanner.getInstance().onForegroundFrame(mc,
                        SimpleMap.isMapUnlocked(mc.player));
                MinimapRenderer.getInstance().renderHUD(guiGraphics, 1.0f);
                MapTeleportController.renderTransition(guiGraphics);
                if (mc.level != null && mc.player != null) {
                    long frameId = ++mapRenderFrameId;
                    MapViewportCoordinator.getInstance().drainPublicationFrame(frameId);
                    MapArchitectureCoordinator.getInstance().onFrameBoundary(frameId);
                }
                MapScreen.MapDebugOverlay.render(guiGraphics);
            }
        }

        public static void onRenderScreen(Screen screen, GuiGraphics guiGraphics, float partialTick) {
            MapPerformanceGovernor.getInstance().onFrame();
            Minecraft mc = Minecraft.getInstance();
            if (screen instanceof MapScreen) {
                ChunkScanner.getInstance().onForegroundFrame(mc,
                        SimpleMap.isMapUnlocked(mc.player));
            }
            if (MinimapRenderer.isAllowedScreenForMinimap(screen)) {
                MinimapRenderer.getInstance().renderHUD(guiGraphics, partialTick, true);
            }
            if (mc.level != null && mc.player != null) {
                long frameId = ++mapRenderFrameId;
                MapViewportCoordinator.getInstance().drainPublicationFrame(frameId);
                MapArchitectureCoordinator.getInstance().onFrameBoundary(frameId);
            }
            MapScreen.MapDebugOverlay.render(guiGraphics);
        }

        public static void onLoggingIn() {
            MapConfig.serverExtensionAvailable = ClientNetworkHandler.isServerExtensionAvailable();
            MapConfig.serverRequireMapBook = false;
            MapConfig.serverCaveMapMode = 0;
            wasPlayerDead = false;
            MinimapRenderer.getInstance().onWorldJoin();
            MapArchitectureCoordinator.getInstance().onWorldJoin();
        }

        public static void onLoggingOut() {
            MapDebugRecorder.getInstance().onWorldLeave();
            MapManager.getInstance().flushAndClear();
            MapObservationScheduler.getInstance().reset();
            ChunkScanner.getInstance().resetForegroundWriter();
            CaveMode.clearManualLayer();
            MapConfig.serverExtensionAvailable = false;
            MapConfig.serverCaveMapMode = 0;
            MapConfig.serverRequireMapBook = false;
            MinimapRenderer.getInstance().onWorldLeave();
            MapArchitectureCoordinator.getInstance().onWorldLeave();
            wasPlayerDead = false;
        }

        private static final java.util.regex.Pattern CHAT_COORDINATE_PATTERN =
                java.util.regex.Pattern.compile(
                        "(\\[\\s*(?:[xX]\\s*[:=]\\s*)?(-?\\d{1,7})\\s*(?:,\\s*|\\s+)(?:(?:[yY]\\s*[:=]\\s*)?(-?\\d{1,4}|\\?|~)\\s*(?:,\\s*|\\s+))?(?:[zZ]\\s*[:=]\\s*)?(-?\\d{1,7})\\s*\\])"
                        + "|(?i:\\b[xX]\\s*[:=]\\s*(-?\\d{1,7})\\s*(?:,\\s*|\\s+)(?:[yY]\\s*[:=]\\s*(-?\\d{1,4}|\\?|~)\\s*(?:,\\s*|\\s+))?[zZ]\\s*[:=]\\s*(-?\\d{1,7})\\b)"
                        + "|(?<![\\d.,-])((-?\\d{1,7})\\s*,\\s*(-?\\d{1,4}|\\?)\\s*,\\s*(-?\\d{1,7}))(?![\\d.,-])");

        public static Component enhanceIncomingChatMessage(Component original) {
            if (original == null) return null;
            Component enhanced = enhanceChatCoordinates(original);
            return enhanced != null ? enhanced : original;
        }

        public static boolean onChatMouseClicked(Screen screen, double mouseX, double mouseY, int button) {
            if (button != 0) return false;
            if (!(screen instanceof net.minecraft.client.gui.screens.ChatScreen)) return false;
            Minecraft mc = Minecraft.getInstance();
            if (mc.gui == null || mc.gui.getChat() == null) return false;
            net.minecraft.network.chat.Style style = mc.gui.getChat()
                    .getClickedComponentStyleAt(mouseX, mouseY);
            if (style == null || style.getClickEvent() == null) return false;
            net.minecraft.network.chat.ClickEvent click = style.getClickEvent();
            if (click.getAction() == net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND
                    && click.getValue() != null
                    && click.getValue().startsWith("/simplemap_view ")) {
                return handleSimpleMapViewCommand(click.getValue());
            }
            return false;
        }

        public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
            dispatcher.register(
                    ClientCommandManager.literal("simplemap_view")
                            .then(ClientCommandManager.argument(
                                    "args", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        String raw = StringArgumentType.getString(ctx, "args");
                                        handleSimpleMapViewCommand("/simplemap_view " + raw);
                                        return 1;
                                    })));
        }

        private static boolean handleSimpleMapViewCommand(String command) {
            if (command == null) return false;
            String trimmed = command.trim();
            if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 4 || !"simplemap_view".equals(parts[0])) return false;
            try {
                int x = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[3]);
                String dim = parts.length >= 5 ? parts[4] : "";
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null || !SimpleMap.isMapUnlocked(mc.player)) return false;
                mc.execute(() -> {
                    MapScreen mapScreen = mc.screen instanceof MapScreen existing
                            ? existing : new MapScreen();
                    mc.setScreen(mapScreen);
                    mapScreen.focusOnCoordinates(x + 0.5, z + 0.5, dim);
                });
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        private static String inferDimensionFromChatMessage(String fullText) {
            if (fullText == null || fullText.isEmpty()) return "";
            String lower = fullText.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("minecraft:the_nether")
                    || lower.contains("(the nether)")
                    || lower.contains("(nether)")
                    || lower.contains("(địa ngục)")
                    || lower.contains("(下界)")
                    || lower.contains("(地獄)")
                    || lower.contains("(ネザー)")
                    || lower.contains("(네더)")) {
                return "minecraft:the_nether";
            }
            if (lower.contains("minecraft:the_end")
                    || lower.contains("(the end)")
                    || lower.contains("(end)")
                    || lower.contains("(kết thúc)")
                    || lower.contains("(末地)")
                    || lower.contains("(終界)")
                    || lower.contains("(ジ・エンド)")
                    || lower.contains("(엔드)")) {
                return "minecraft:the_end";
            }
            if (lower.contains("minecraft:overworld")
                    || lower.contains("(overworld)")
                    || lower.contains("(thế giới thực)")
                    || lower.contains("(主世界)")
                    || lower.contains("(オーバーワールド)")
                    || lower.contains("(오버월드)")) {
                return "minecraft:overworld";
            }
            String overworldLocal = MapManager.displayDimensionName("minecraft:overworld")
                    .toLowerCase(java.util.Locale.ROOT);
            String netherLocal = MapManager.displayDimensionName("minecraft:the_nether")
                    .toLowerCase(java.util.Locale.ROOT);
            String endLocal = MapManager.displayDimensionName("minecraft:the_end")
                    .toLowerCase(java.util.Locale.ROOT);
            if (!netherLocal.isEmpty() && lower.contains("(" + netherLocal + ")")) {
                return "minecraft:the_nether";
            }
            if (!endLocal.isEmpty() && lower.contains("(" + endLocal + ")")) {
                return "minecraft:the_end";
            }
            if (!overworldLocal.isEmpty() && lower.contains("(" + overworldLocal + ")")) {
                return "minecraft:overworld";
            }
            return "";
        }

        private static Component enhanceChatCoordinates(Component message) {
            String fullPlain = message.getString();
            if (fullPlain == null || fullPlain.isEmpty()) return null;
            if (!CHAT_COORDINATE_PATTERN.matcher(fullPlain).find()) return null;

            String inferredDim = inferDimensionFromChatMessage(fullPlain);
            net.minecraft.network.chat.MutableComponent root = Component.empty();
            boolean[] changed = { false };

            message.visit((style, segment) -> {
                if (segment == null || segment.isEmpty()) {
                    return java.util.Optional.empty();
                }
                if (style.getClickEvent() != null) {
                    root.append(Component.literal(segment).withStyle(style));
                    return java.util.Optional.empty();
                }
                java.util.regex.Matcher matcher = CHAT_COORDINATE_PATTERN.matcher(segment);
                int lastEnd = 0;
                boolean foundInSegment = false;
                while (matcher.find()) {
                    String xStr;
                    String yStr;
                    String zStr;
                    if (matcher.group(1) != null) {
                        xStr = matcher.group(2);
                        yStr = matcher.group(3);
                        zStr = matcher.group(4);
                    } else if (matcher.group(5) != null) {
                        xStr = matcher.group(5);
                        yStr = matcher.group(6);
                        zStr = matcher.group(7);
                    } else {
                        xStr = matcher.group(9);
                        yStr = matcher.group(10);
                        zStr = matcher.group(11);
                    }
                    int xVal;
                    int zVal;
                    int yVal = 64;
                    try {
                        xVal = Integer.parseInt(xStr);
                        zVal = Integer.parseInt(zStr);
                        if (Math.abs(xVal) > 30_000_000 || Math.abs(zVal) > 30_000_000) {
                            continue;
                        }
                        if (yStr != null && !yStr.equals("?") && !yStr.equals("~")) {
                            yVal = Integer.parseInt(yStr);
                            if (yVal < -2048 || yVal > 2048) continue;
                        }
                    } catch (NumberFormatException ex) {
                        continue;
                    }
                    foundInSegment = true;
                    changed[0] = true;
                    if (matcher.start() > lastEnd) {
                        root.append(Component.literal(
                                segment.substring(lastEnd, matcher.start())).withStyle(style));
                    }
                    String matchedText = matcher.group(0);
                    String cmd = "/simplemap_view " + xVal + " " + yVal + " " + zVal
                            + (inferredDim.isEmpty() ? "" : (" " + inferredDim));
                    Component hoverTooltip = Component.translatable(
                            "gui.simplemap.chat.click_to_open_map", xVal, zVal)
                            .withStyle(net.minecraft.ChatFormatting.AQUA);
                    net.minecraft.network.chat.Style linkStyle = style
                            .withColor(net.minecraft.ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, cmd))
                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, hoverTooltip));
                    root.append(Component.literal(matchedText).withStyle(linkStyle));
                    lastEnd = matcher.end();
                }
                if (!foundInSegment) {
                    root.append(Component.literal(segment).withStyle(style));
                } else if (lastEnd < segment.length()) {
                    root.append(Component.literal(segment.substring(lastEnd)).withStyle(style));
                }
                return java.util.Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY);

            return changed[0] ? root : null;
        }
    }

    public static boolean isMapUnlocked(Player player) {
        if (player == null) return false;
        if (!MapConfig.serverRequireMapBook) {
            return true;
        }
        return MapManager.getInstance().hasLearnedMap() && hasMapBookInInventory(player);
    }

    public static boolean hasMapBookInInventory(Player player) {
        if (player == null) return false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(ModItems.MAP_BOOK.get())) {
                CompoundTag tag = stack.getTag();
                if (tag != null && tag.contains("MapBookID")) {
                    return true;
                }
            }
        }
        return false;
    }
}
