package com.velorise.simplemap;

import com.velorise.simplemap.client.AddWaypointScreen;
import com.velorise.simplemap.client.BlockColorScreen;
import com.velorise.simplemap.client.BlockColorManagerScreen;
import com.velorise.simplemap.client.ChunkScanner;
import com.velorise.simplemap.client.CaveMode;
import com.velorise.simplemap.client.CaveTextureManager;
import com.velorise.simplemap.client.FullCaveTextureManager;
import com.velorise.simplemap.client.MapLightManager;
import com.velorise.simplemap.client.MapKeybindActions;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapConfigScreen;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapScreen;
import com.velorise.simplemap.client.MinimapRenderer;
import com.velorise.simplemap.client.MapTextureManager;
import com.velorise.simplemap.client.RegionDataStore;
import com.velorise.simplemap.client.WaypointManager;
import com.velorise.simplemap.network.ClientNetworkHandler;
import com.velorise.simplemap.network.NetworkHandler;
import com.velorise.simplemap.network.payload.SyncConfigPayload;
import com.velorise.simplemap.recipe.ModRecipes;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
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

@Mod(SimpleMap.MODID)
public class SimpleMap {
    public static final String MODID = "simplemap";
    private static final Logger LOGGER = LogManager.getLogger();

    public SimpleMap(IEventBus modEventBus) {
        ServerConfig.init();

        // Register Registries
        ModItems.ITEMS.register(modEventBus);
        ModRecipes.SERIALIZERS.register(modEventBus);

        // Register Network Handler payload listeners
        modEventBus.addListener(NetworkHandler::register);

        // Register creative tab contents
        modEventBus.addListener(this::addCreative);

    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.EMPTY_MAP_BOOK.get());
            event.accept(ModItems.MAP_BOOK.get());
        }
    }

    // =======================================================================
    // Client-only Mod Event Bus Subscribers
    // =======================================================================
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
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

        private static KeyMapping key(String translationKey, int glfwKey) {
            return new KeyMapping(translationKey, glfwKey, KEY_CATEGORY);
        }

        private static KeyMapping unbound(String translationKey) {
            return key(translationKey, GLFW.GLFW_KEY_UNKNOWN);
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(MapConfig::init);
        }

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MAP_KEY);
            event.register(TOGGLE_MINIMAP_KEY);
            event.register(TOGGLE_NORTH_LOCK_KEY);
            event.register(ZOOM_IN_KEY);
            event.register(ZOOM_OUT_KEY);
            event.register(RESET_ZOOM_KEY);
            event.register(ADD_WAYPOINT_KEY);
            event.register(TOGGLE_WAYPOINTS_KEY);
            event.register(TOGGLE_COORDS_KEY);
            event.register(CYCLE_CAVE_MODE_KEY);
            event.register(CYCLE_NIGHT_MODE_KEY);
            event.register(TOGGLE_SHAPE_KEY);
            event.register(TOGGLE_SCREEN_VISIBILITY_KEY);
            event.register(TOGGLE_FAST_LOADING_KEY);
            event.register(OPEN_SETTINGS_KEY);
            event.register(REFRESH_MAP_KEY);
            event.register(CLEAR_PIN_KEY);
            event.register(TOGGLE_COMPASS_KEY);
            event.register(TOGGLE_CURSOR_BIOME_KEY);
            event.register(CYCLE_COLOR_MODE_KEY);
            event.register(CYCLE_TERRAIN_MODE_KEY);
            event.register(CENTER_FULL_MAP_KEY);
        }
    }

    // =======================================================================
    // Common Game Event Bus Subscribers (Server & Client)
    // =======================================================================
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.GAME)
    public static class CommonGameEvents {
        private static final int MAX_MERGED_REGION_FILES = 16_384;
        private static final ExecutorService MAP_BOOK_MERGE_POOL = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SimpleMap-BookMerge");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        });

        @SubscribeEvent
        public static void onPlayerLoggedIn(PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                boolean sent = NetworkHandler.sendToPlayer(serverPlayer,
                        new SyncConfigPayload(ServerConfig.requireMapBook, ServerConfig.caveMapMode));
                if (sent) {
                    LOGGER.info("Synced server config (requireBook={}, caveMode={}) to joining player: {}",
                            ServerConfig.requireMapBook, ServerConfig.caveMapMode,
                            serverPlayer.getName().getString());
                }
            }
        }

        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                NetworkHandler.clearSessionsForPlayer(serverPlayer.getUUID());
            }
        }

        @SubscribeEvent
        public static void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
            ItemStack result = event.getCrafting();
            net.minecraft.world.item.component.CustomData customData = result.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            CompoundTag tag = customData != null ? customData.copyTag() : null;
            
            if (result.is(ModItems.MAP_BOOK.get()) && tag != null && tag.contains("PendingMerge")) {
                Player player = event.getEntity();
                if (player.level().isClientSide) return; // Server only

                String firstId = null;
                String secondId = null;
                net.minecraft.world.Container container = event.getInventory();
                
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    net.minecraft.world.item.component.CustomData stackCustomData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    CompoundTag stackTag = stackCustomData != null ? stackCustomData.copyTag() : null;
                    
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
                    // Validate both IDs are proper UUIDs before using them in file paths
                    if (!firstId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") ||
                        !secondId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                        LOGGER.warn("[SimpleMap] Merge blocked: invalid MapBookID format detected in crafting inventory");
                        return;
                    }

                    String newBookId = UUID.randomUUID().toString();

                    // Server-side canonical .smdat merge
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

                    // Path containment check: ensure resolved dirs stay inside simplemap_books.
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

                    // Publish the new identity only after its server-side directory and
                    // pending marker exist. This prevents a crafted result from pointing
                    // at a map book that was never initialized on disk.
                    tag.remove("PendingMerge");
                    tag.putString("MapBookID", newBookId);
                    result.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(tag));
                    result.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                            Component.literal("§6Merged Map Book"));

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
                                online.sendSystemMessage(Component.literal(emptySources
                                        ? "§eMerged Map Book is ready, but both source books had no stored data."
                                        : failures == 0
                                                ? "§aMerged Map Book is ready."
                                                : "§eMerged Map Book is ready, but some damaged regions were skipped."));
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
                        // Preserve first-book pixels where both books contain exploration.
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
    // Client-only Game Event Bus Subscribers
    // =======================================================================
    @EventBusSubscriber(modid = MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static class ClientGameEvents {
        private static boolean wasPlayerDead;

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;

            // 1. Tick MapManager to update active directory and auto-save dirty regions
            MapManager.getInstance().updateWorldAndDimension(mc);
            MapManager.getInstance().tickSave();

            // 2. Scan chunks ONLY if map is unlocked
            if (SimpleMap.isMapUnlocked(mc.player)
                    && !(mc.screen instanceof com.velorise.simplemap.client.MapScreen)) {
                int renderDistance = mc.options.renderDistance().get();
                int radius = (int) Math.max(16, (renderDistance - 1.5) * 16);
                ChunkScanner.getInstance().scanAroundPlayerUniform(mc, radius);
            }

            // 2.25. Create one bounded death waypoint on the transition into death.
            // Polling the local player avoids relying on loader-specific client death events.
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
                if (distSq <= 25.0) { // 5 blocks squared (5 * 5 = 25)
                    MapConfig.pinActive = false;
                    MapManager.getInstance().savePin();
                    // Play a satisfying experience orb pickup sound locally to notify player
                    mc.player.playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 1.0f);
                }
            }

            // 3. Handle configurable map actions only during normal gameplay. This
            // prevents N/L/M and zoom keys from firing while the player types in chat,
            // edits a waypoint name or uses another screen.
            if (mc.screen == null) {
                drain(ClientModEvents.OPEN_MAP_KEY, () -> MapKeybindActions.toggleFullMap(mc));
                drain(ClientModEvents.TOGGLE_MINIMAP_KEY, () -> MapKeybindActions.toggleMinimap(mc));
                drain(ClientModEvents.TOGGLE_NORTH_LOCK_KEY, () -> MapKeybindActions.toggleNorthLock(mc));
                drain(ClientModEvents.ZOOM_IN_KEY, () -> MapKeybindActions.zoomIn(mc));
                drain(ClientModEvents.ZOOM_OUT_KEY, () -> MapKeybindActions.zoomOut(mc));
                drain(ClientModEvents.RESET_ZOOM_KEY, () -> MapKeybindActions.resetZoom(mc));
                drain(ClientModEvents.ADD_WAYPOINT_KEY, () -> MapKeybindActions.addWaypointAtPlayer(mc));
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
                // KeyMapping click counters are independent from Screen.keyPressed(). Drain
                // them here so typing N/L/M in chat or text fields cannot trigger a map
                // action after the screen closes.
                discardAllMapClicks();
            }
        }

        private static void drain(KeyMapping mapping, Runnable action) {
            if (!mapping.consumeClick()) return;
            action.run();
            discard(mapping); // Collapse key-repeat bursts into one action per client tick.
        }

        private static void discard(KeyMapping mapping) {
            while (mapping.consumeClick()) {
                // Intentionally discard buffered clicks.
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

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            // Render the Minimap HUD overlay
            MinimapRenderer.getInstance().renderHUD(event.getGuiGraphics(), 1.0f);
        }

        @SubscribeEvent
        public static void onRenderScreen(ScreenEvent.Render.Post event) {
            if (!MinimapRenderer.isAllowedScreenForMinimap(event.getScreen())) {
                return;
            }
            MinimapRenderer.getInstance().renderHUD(event.getGuiGraphics(), event.getPartialTick(), true);
        }

        @SubscribeEvent
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            // Start every connection in local-only mode. A SyncConfig packet upgrades
            // the session once an installed server extension answers the handshake.
            MapConfig.serverExtensionAvailable = ClientNetworkHandler.isServerExtensionAvailable();
            MapConfig.serverRequireMapBook = false;
            MapConfig.serverCaveMapMode = 0;
            wasPlayerDead = false;
            MinimapRenderer.getInstance().onWorldJoin();
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            // Save and flush map cache and release OpenGL textures on exit
            MapManager.getInstance().flushAndClear();
            MapTextureManager.getInstance().clearCache();
            MapLightManager.getInstance().flushAndClear();
            CaveTextureManager.getInstance().clearCache();
            FullCaveTextureManager.getInstance().clearCache();
            CaveMode.clearManualLayer();
            MapConfig.serverExtensionAvailable = false;
            MapConfig.serverCaveMapMode = 0;
            MapConfig.serverRequireMapBook = false;
            wasPlayerDead = false;
        }
    }

    public static boolean isMapUnlocked(Player player) {
        if (player == null) return false;
        // If requireMapBook config is disabled, map is always unlocked
        if (!MapConfig.serverRequireMapBook) {
            return true;
        }
        // If requireMapBook is enabled, player must have learned the map AND hold a map book in inventory
        return MapManager.getInstance().hasLearnedMap() && hasMapBookInInventory(player);
    }

    public static boolean hasMapBookInInventory(Player player) {
        if (player == null) return false;
        // main inventory slots + armor slots + offhand slot (covers all slots)
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(ModItems.MAP_BOOK.get())) {
                net.minecraft.world.item.component.CustomData customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                if (customData != null && customData.copyTag().contains("MapBookID")) {
                    return true;
                }
            }
        }
        return false;
    }
}
