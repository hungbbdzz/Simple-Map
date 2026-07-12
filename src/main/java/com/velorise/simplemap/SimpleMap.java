package com.velorise.simplemap;

import com.mojang.blaze3d.platform.NativeImage;
import com.velorise.simplemap.client.ChunkScanner;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapScreen;
import com.velorise.simplemap.client.MinimapRenderer;
import com.velorise.simplemap.client.MapTextureManager;
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
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Mod(SimpleMap.MODID)
public class SimpleMap {
    public static final String MODID = "simplemap";
    private static final Logger LOGGER = LogManager.getLogger();

    public SimpleMap(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
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

        @SubscribeEvent
        public static void onPlayerLoggedIn(PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                // Sync requireMapBook config from Server to Client when joining world
                NetworkHandler.sendToPlayer(serverPlayer, new SyncConfigPayload(MapConfig.requireMapBook));
                LOGGER.info("Synced requireMapBook config ({}) to joining player: {}", MapConfig.requireMapBook, serverPlayer.getName().getString());
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
                    String newBookId = UUID.randomUUID().toString();
                    tag.remove("PendingMerge");
                    tag.putString("MapBookID", newBookId);
                    result.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
                    result.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§6Merged Map Book"));

                    // Server-side PNG merge
                    Path worldDir = player.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                    File booksBaseDir = worldDir.resolve("simplemap_books").toFile();
                    
                    File dir1 = new File(booksBaseDir, firstId);
                    File dir2 = new File(booksBaseDir, secondId);
                    File newDir = new File(booksBaseDir, newBookId);

                    if (dir1.exists() || dir2.exists()) {
                        newDir.mkdirs();
                        mergeFolders(dir1, dir2, newDir);
                    }
                }
            }
        }

        private static void mergeFolders(File src1, File src2, File dest) {
            Set<String> subPaths = new HashSet<>();
            collectSubPaths(src1, "", subPaths);
            collectSubPaths(src2, "", subPaths);

            for (String subPath : subPaths) {
                File f1 = new File(src1, subPath);
                File f2 = new File(src2, subPath);
                File fDest = new File(dest, subPath);

                if (f1.isDirectory() || f2.isDirectory()) {
                    fDest.mkdirs();
                    continue;
                }

                fDest.getParentFile().mkdirs();
                if (f1.getName().endsWith(".png")) {
                    if (f1.exists() && f2.exists()) {
                        try {
                            NativeImage img1;
                            try (FileInputStream fis = new FileInputStream(f1)) {
                                img1 = NativeImage.read(fis);
                            }
                            NativeImage img2;
                            try (FileInputStream fis = new FileInputStream(f2)) {
                                img2 = NativeImage.read(fis);
                            }

                            int width = Math.min(512, Math.min(img1.getWidth(), img2.getWidth()));
                            int height = Math.min(512, Math.min(img1.getHeight(), img2.getHeight()));

                            NativeImage merged = new NativeImage(width, height, true);
                            for (int y = 0; y < height; y++) {
                                for (int x = 0; x < width; x++) {
                                    int p1 = img1.getPixelRGBA(x, y);
                                    int p2 = img2.getPixelRGBA(x, y);
                                    merged.setPixelRGBA(x, y, p1 != 0 ? p1 : p2);
                                }
                            }
                            merged.writeToFile(fDest);
                            merged.close();
                            img1.close();
                            img2.close();
                        } catch (IOException e) {
                            LOGGER.error("Failed to merge region files: " + subPath, e);
                        }
                    } else if (f1.exists()) {
                        try {
                            Files.copy(f1.toPath(), fDest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    } else if (f2.exists()) {
                        try {
                            Files.copy(f2.toPath(), fDest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    }
                } else {
                    if (f1.exists()) {
                        try {
                            Files.copy(f1.toPath(), fDest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    } else if (f2.exists()) {
                        try {
                            Files.copy(f2.toPath(), fDest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    }
                }
            }
        }

        private static void collectSubPaths(File dir, String currentPath, Set<String> paths) {
            if (dir == null || !dir.exists()) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                String sub = currentPath.isEmpty() ? f.getName() : currentPath + "/" + f.getName();
                paths.add(sub);
                if (f.isDirectory()) {
                    collectSubPaths(f, sub, paths);
                }
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
            if (SimpleMap.isMapUnlocked(mc.player)) {
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
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            // Save and flush map cache and release OpenGL textures on exit
            MapManager.getInstance().flushAndClear();
            MapTextureManager.getInstance().clearCache();
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
