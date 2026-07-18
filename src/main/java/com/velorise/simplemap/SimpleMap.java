package com.velorise.simplemap;

import com.velorise.simplemap.client.AddWaypointScreen;
import com.velorise.simplemap.client.BlockColorScreen;
import com.velorise.simplemap.client.ChunkScanner;
import com.velorise.simplemap.client.CaveMode;
import com.velorise.simplemap.client.CaveTextureManager;
import com.velorise.simplemap.client.FullCaveTextureManager;
import com.velorise.simplemap.client.MapLightManager;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapConfigScreen;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapScreen;
import com.velorise.simplemap.client.MinimapRenderer;
import com.velorise.simplemap.client.MapTextureManager;
import com.velorise.simplemap.client.RegionDataStore;
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

    public SimpleMap(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
        ServerConfig.init();
        // Register client setup listener
        modEventBus.addListener(this::onClientSetup);

        // Register Registries
        ModItems.ITEMS.register(modEventBus);
        ModRecipes.SERIALIZERS.register(modEventBus);

        // Register Network Handler payload listeners
        modEventBus.addListener(NetworkHandler::register);

        // Register creative tab contents
        modEventBus.addListener(this::addCreative);

        // Register Config Screen Factory so the "Config" button in the mod list screen is enabled
        if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) {
            modContainer.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                    (client, parent) -> new com.velorise.simplemap.client.MapConfigScreen(parent));
        }
    }

    private void onClientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(MapConfig::init);
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
        public static final KeyMapping OPEN_MAP_KEY = new KeyMapping(
            "key.simplemap.open_map",
            GLFW.GLFW_KEY_M,
            "key.categories.simplemap"
        );

        @SubscribeEvent
        public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MAP_KEY);
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
                NetworkHandler.sendToPlayer(serverPlayer,
                        new SyncConfigPayload(ServerConfig.requireMapBook, ServerConfig.caveMapMode));
                LOGGER.info("Synced server config (requireBook={}, caveMode={}) to joining player: {}",
                        ServerConfig.requireMapBook, ServerConfig.caveMapMode, serverPlayer.getName().getString());
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
                                "SimpleMap map-book merge in progress");
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
                                                : "Skipped " + failures + " unreadable region file(s).");
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

            // 3. Handle Key Input to open MapScreen
            while (ClientModEvents.OPEN_MAP_KEY.consumeClick()) {
                mc.setScreen(new MapScreen());
            }
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            // Render the Minimap HUD overlay
            MinimapRenderer.getInstance().renderHUD(event.getGuiGraphics(), 1.0f);
        }

        @SubscribeEvent
        public static void onRenderScreen(ScreenEvent.Render.Post event) {
            // The mod's own screens already provide a full map or a live minimap preview.
            // Other in-world screens (inventory, pause, chat, containers...) keep the HUD
            // minimap visible on top.
            if (event.getScreen() instanceof MapScreen
                    || event.getScreen() instanceof MapConfigScreen
                    || event.getScreen() instanceof AddWaypointScreen
                    || event.getScreen() instanceof BlockColorScreen) {
                return;
            }
            MinimapRenderer.getInstance().renderHUD(event.getGuiGraphics(), event.getPartialTick(), true);
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
            MapConfig.serverCaveMapMode = 0;
            MapConfig.serverRequireMapBook = false;
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
