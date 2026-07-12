package com.velorise.simplemap.network;

import com.mojang.blaze3d.platform.NativeImage;
import com.velorise.simplemap.ModItems;
import com.velorise.simplemap.SimpleMap;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapTextureManager;
import com.velorise.simplemap.network.payload.*;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NetworkHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    // Server-side active saving sessions
    private static final Map<String, SavingSession> activeSavingSessions = new ConcurrentHashMap<>();
    
    // Server-side active learning sessions
    private static final Map<String, LearningSession> activeLearningSessions = new ConcurrentHashMap<>();

    public static void register(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");

        // 1. Sync Config (Server -> Client)
        registrar.playToClient(
                SyncConfigPayload.TYPE,
                SyncConfigPayload.STREAM_CODEC,
                NetworkHandler::handleSyncConfigOnClient
        );

        // 2. Init Save Book (Client -> Server)
        registrar.playToServer(
                InitSaveBookPayload.TYPE,
                InitSaveBookPayload.STREAM_CODEC,
                NetworkHandler::handleInitSaveBookOnServer
        );

        // 3. Request Region File (Server -> Client)
        registrar.playToClient(
                RequestRegionFilePayload.TYPE,
                RequestRegionFilePayload.STREAM_CODEC,
                NetworkHandler::handleRequestRegionFileOnClient
        );

        // 4. Send Region File (Client -> Server)
        registrar.playToServer(
                SendRegionFilePayload.TYPE,
                SendRegionFilePayload.STREAM_CODEC,
                NetworkHandler::handleSendRegionFileOnServer
        );

        // 5. Save Book Complete (Server -> Client)
        registrar.playToClient(
                SaveBookCompletePayload.TYPE,
                SaveBookCompletePayload.STREAM_CODEC,
                NetworkHandler::handleSaveBookCompleteOnClient
        );

        // 6. Request Learn Book (Client -> Server)
        registrar.playToServer(
                RequestLearnBookPayload.TYPE,
                RequestLearnBookPayload.STREAM_CODEC,
                NetworkHandler::handleRequestLearnBookOnServer
        );

        // 7. Send Learn Region (Server -> Client)
        registrar.playToClient(
                SendLearnRegionPayload.TYPE,
                SendLearnRegionPayload.STREAM_CODEC,
                NetworkHandler::handleSendLearnRegionOnClient
        );

        // 8. Learn Book Complete (Server -> Client)
        registrar.playToClient(
                LearnBookCompletePayload.TYPE,
                LearnBookCompletePayload.STREAM_CODEC,
                NetworkHandler::handleLearnBookCompleteOnClient
        );

        // 9. Ack Learn Region (Client -> Server)
        registrar.playToServer(
                AckLearnRegionPayload.TYPE,
                AckLearnRegionPayload.STREAM_CODEC,
                NetworkHandler::handleAckLearnRegionOnServer
        );
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    // =========================================================================
    // HANDLERS
    // =========================================================================

    private static void handleSyncConfigOnClient(SyncConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            MapConfig.serverRequireMapBook = payload.requireMapBook();
            LOGGER.info("SimpleMap synced requireMapBook config from server: {}", payload.requireMapBook());
        });
    }

    private static void handleInitSaveBookOnServer(InitSaveBookPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            // Enforce item cooldown on server side to prevent packet spam
            InteractionHand hand = payload.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack held = serverPlayer.getItemInHand(hand);
            if (!held.isEmpty()) {
                serverPlayer.getCooldowns().addCooldown(held.getItem(), 120); // 6s cooldown
            }

            String bookId = payload.bookId();
            if (bookId == null || bookId.isEmpty()) {
                bookId = UUID.randomUUID().toString();
            }

            SavingSession session = new SavingSession(bookId, payload.regionNames(), payload.mainHand());
            activeSavingSessions.put(bookId, session);

            LOGGER.info("Server initiated map book saving session {} for player {}", bookId, player.getName().getString());

            // Request the first region file
            if (!payload.regionNames().isEmpty()) {
                String firstRegion = payload.regionNames().get(0);
                sendToPlayer(serverPlayer, new RequestRegionFilePayload(bookId, firstRegion, 0, payload.regionNames().size(), payload.mainHand()));
            } else {
                // Empty book
                completeSaveBook(serverPlayer, session);
            }
        });
    }

    private static void handleRequestRegionFileOnClient(RequestRegionFilePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            File dir = MapManager.getInstance().getCurrentDimensionDir();
            if (dir == null || !dir.exists()) return;

            File file = new File(dir, payload.regionName());
            if (!file.exists()) {
                // Skip if file deleted in between
                sendToServer(new SendRegionFilePayload(payload.bookId(), payload.regionName(), new byte[0], payload.currentIdx(), payload.totalCount(), payload.mainHand()));
                return;
            }

            try {
                byte[] data = Files.readAllBytes(file.toPath());
                sendToServer(new SendRegionFilePayload(payload.bookId(), payload.regionName(), data, payload.currentIdx(), payload.totalCount(), payload.mainHand()));
            } catch (IOException e) {
                LOGGER.error("Failed to read region file: " + payload.regionName(), e);
                sendToServer(new SendRegionFilePayload(payload.bookId(), payload.regionName(), new byte[0], payload.currentIdx(), payload.totalCount(), payload.mainHand()));
            }
        });
    }

    private static void handleSendRegionFileOnServer(SendRegionFilePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            SavingSession session = activeSavingSessions.get(payload.bookId());
            if (session == null) return;

            // Save region bytes to server storage
            if (payload.data().length > 0) {
                Path serverBooksDir = serverPlayer.server.getWorldPath(LevelResource.ROOT).resolve("simplemap_books").resolve(payload.bookId()).resolve(serverPlayer.level().dimension().location().getPath());
                try {
                    Files.createDirectories(serverBooksDir);
                    Path destFile = serverBooksDir.resolve(payload.regionName());
                    Files.write(destFile, payload.data());
                } catch (IOException e) {
                    LOGGER.error("Server failed to save region file " + payload.regionName() + " for book " + payload.bookId(), e);
                }
            }

            int nextIdx = payload.currentIdx() + 1;
            if (nextIdx < payload.totalCount()) {
                String nextRegion = session.regionNames.get(nextIdx);
                sendToPlayer(serverPlayer, new RequestRegionFilePayload(payload.bookId(), nextRegion, nextIdx, payload.totalCount(), payload.mainHand()));
            } else {
                // Complete
                completeSaveBook(serverPlayer, session);
            }
        });
    }

    private static void completeSaveBook(ServerPlayer player, SavingSession session) {
        activeSavingSessions.remove(session.bookId);

        InteractionHand hand = session.mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack held = player.getItemInHand(hand);
        
        if (held.is(ModItems.EMPTY_MAP_BOOK.get())) {
            ItemStack writtenBook = new ItemStack(ModItems.MAP_BOOK.get());
            
            CompoundTag tag = new CompoundTag();
            tag.putString("MapBookID", session.bookId);
            tag.putString("OwnerUUID", player.getUUID().toString());
            tag.putString("OwnerName", player.getName().getString());
            writtenBook.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));
            
            // Set custom display name
            writtenBook.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§6Map Book of " + player.getName().getString()));
            
            player.setItemInHand(hand, writtenBook);
            player.sendSystemMessage(Component.literal("§aMap successfully saved to the book!"));
            
            sendToPlayer(player, new SaveBookCompletePayload(session.bookId));
        } else if (held.is(ModItems.MAP_BOOK.get())) {
            // Write owner tags just in case they were missing
            net.minecraft.world.item.component.CustomData customData = held.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            CompoundTag tag = customData != null ? customData.copyTag() : new CompoundTag();
            tag.putString("OwnerUUID", player.getUUID().toString());
            tag.putString("OwnerName", player.getName().getString());
            held.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag));

            player.sendSystemMessage(Component.literal("§aMap successfully updated in the book!"));
            sendToPlayer(player, new SaveBookCompletePayload(session.bookId));
        }
    }

    private static void handleSaveBookCompleteOnClient(SaveBookCompletePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (MapConfig.serverRequireMapBook) {
                MapManager.getInstance().setHasLearnedMap(true);
            }
        });
    }

    private static void handleRequestLearnBookOnServer(RequestLearnBookPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            Path serverBookDir = serverPlayer.server.getWorldPath(LevelResource.ROOT).resolve("simplemap_books").resolve(payload.bookId()).resolve(serverPlayer.level().dimension().location().getPath());
            File dir = serverBookDir.toFile();
            
            if (!dir.exists()) {
                player.sendSystemMessage(Component.literal("§cThis book has no map data for the current dimension!"));
                sendToPlayer(serverPlayer, new LearnBookCompletePayload(payload.bookId()));
                return;
            }

            File[] files = dir.listFiles((d, name) -> name.startsWith("r.") && name.endsWith(".png"));
            if (files == null || files.length == 0) {
                sendToPlayer(serverPlayer, new LearnBookCompletePayload(payload.bookId()));
                return;
            }

            List<File> fileList = Arrays.asList(files);
            LearningSession session = new LearningSession(payload.bookId(), fileList);
            activeLearningSessions.put(payload.bookId() + "_" + player.getUUID(), session);

            // Send first region
            sendNextLearnRegion(serverPlayer, session, 0);
        });
    }

    private static void sendNextLearnRegion(ServerPlayer player, LearningSession session, int idx) {
        if (idx >= session.regionFiles.size()) {
            activeLearningSessions.remove(session.bookId + "_" + player.getUUID());
            
            // Check if player is holding the Map Book and consume if they are not the owner
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (stack.is(ModItems.MAP_BOOK.get())) {
                    net.minecraft.world.item.component.CustomData customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                    CompoundTag tag = customData != null ? customData.copyTag() : null;
                    if (tag != null && tag.contains("MapBookID") && tag.getString("MapBookID").equals(session.bookId)) {
                        if (tag.contains("OwnerUUID")) {
                            String ownerUuid = tag.getString("OwnerUUID");
                            if (!ownerUuid.equals(player.getUUID().toString())) {
                                stack.shrink(1);
                                player.sendSystemMessage(Component.literal("§eThe map book crumbles to dust as you finish learning its secrets!"));
                                player.level().playSound(null, player.getX(), player.getY(), player.getZ(), 
                                        net.minecraft.sounds.SoundEvents.ITEM_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
                                break;
                            }
                        }
                    }
                }
            }

            sendToPlayer(player, new LearnBookCompletePayload(session.bookId));
            return;
        }

        File file = session.regionFiles.get(idx);
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            sendToPlayer(player, new SendLearnRegionPayload(session.bookId, file.getName(), data, idx, session.regionFiles.size()));
        } catch (IOException e) {
            LOGGER.error("Server failed to read book region: " + file.getName(), e);
            // Skip
            sendNextLearnRegion(player, session, idx + 1);
        }
    }

    private static void handleAckLearnRegionOnServer(AckLearnRegionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            if (!(player instanceof ServerPlayer serverPlayer)) return;

            LearningSession session = activeLearningSessions.get(payload.bookId() + "_" + player.getUUID());
            if (session == null) return;

            sendNextLearnRegion(serverPlayer, session, payload.nextIdx());
        });
    }

    private static void handleSendLearnRegionOnClient(SendLearnRegionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            File dir = MapManager.getInstance().getCurrentDimensionDir();
            if (dir != null && dir.exists()) {
                File localFile = new File(dir, payload.regionName());
                if (payload.data().length > 0) {
                    if (!localFile.exists()) {
                        // Write directly
                        try {
                            Files.write(localFile.toPath(), payload.data());
                        } catch (IOException e) {
                            LOGGER.error("Failed to write learned region: " + payload.regionName(), e);
                        }
                    } else {
                        // Merge pixel logic!
                        mergeRegionFiles(localFile, payload.data());
                    }
                }
            }

            // Unload region from MapManager memory cache to force reload from disk
            String[] parts = payload.regionName().split("\\.");
            if (parts.length >= 4) {
                try {
                    int rx = Integer.parseInt(parts[1]);
                    int rz = Integer.parseInt(parts[2]);
                    MapManager.getInstance().unloadRegion(rx, rz);
                } catch (NumberFormatException ignored) {}
            }

            // Send ACK back to Server to request next file
            sendToServer(new AckLearnRegionPayload(payload.bookId(), payload.currentIdx() + 1));
        });
    }

    private static void mergeRegionFiles(File localFile, byte[] serverData) {
        try {
            NativeImage localImage;
            try (FileInputStream fis = new FileInputStream(localFile)) {
                localImage = NativeImage.read(fis);
            }
            NativeImage serverImage;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(serverData)) {
                serverImage = NativeImage.read(bais);
            }

            int width = Math.min(512, Math.min(localImage.getWidth(), serverImage.getWidth()));
            int height = Math.min(512, Math.min(localImage.getHeight(), serverImage.getHeight()));

            boolean changed = false;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int serverPixel = serverImage.getPixelRGBA(x, y);
                    // NativeImage reads ABGR. If pixel is not transparent (color != 0)
                    if (serverPixel != 0) {
                        int localPixel = localImage.getPixelRGBA(x, y);
                        if (localPixel != serverPixel) {
                            localImage.setPixelRGBA(x, y, serverPixel);
                            changed = true;
                        }
                    }
                }
            }

            if (changed) {
                localImage.writeToFile(localFile);
            }
            localImage.close();
            serverImage.close();
        } catch (Exception e) {
            LOGGER.error("Error merging region file " + localFile.getName(), e);
        }
    }

    private static void handleLearnBookCompleteOnClient(LearnBookCompletePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            MapManager.getInstance().setHasLearnedMap(true);
            MapManager.getInstance().setLastLearnedBookId(payload.bookId());
            
            // Clear OpenGL textures cache and reload
            MapTextureManager.getInstance().clearCache();
            
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§aMap learned successfully! Your map has been updated."));
            }
        });
    }

    // =========================================================================
    // HELPER CLASSES FOR SECTIONS
    // =========================================================================

    private static class SavingSession {
        final String bookId;
        final List<String> regionNames;
        final boolean mainHand;

        SavingSession(String bookId, List<String> regionNames, boolean mainHand) {
            this.bookId = bookId;
            this.regionNames = regionNames;
            this.mainHand = mainHand;
        }
    }

    private static class LearningSession {
        final String bookId;
        final List<File> regionFiles;

        LearningSession(String bookId, List<File> regionFiles) {
            this.bookId = bookId;
            this.regionFiles = regionFiles;
        }
    }
}
