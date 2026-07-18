package com.velorise.simplemap.network;

import com.velorise.simplemap.ModItems;
import com.velorise.simplemap.client.CaveMapManager;
import com.velorise.simplemap.client.CaveMode;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapTextureManager;
import com.velorise.simplemap.client.RegionDataStore;
import com.velorise.simplemap.network.payload.AckLearnRegionPayload;
import com.velorise.simplemap.network.payload.InitSaveBookPayload;
import com.velorise.simplemap.network.payload.LearnBookCompletePayload;
import com.velorise.simplemap.network.payload.RequestLearnBookPayload;
import com.velorise.simplemap.network.payload.RequestRegionFilePayload;
import com.velorise.simplemap.network.payload.SaveBookCompletePayload;
import com.velorise.simplemap.network.payload.SendLearnRegionPayload;
import com.velorise.simplemap.network.payload.SendRegionFilePayload;
import com.velorise.simplemap.network.payload.SyncConfigPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Map-book transport with bounded sessions and palette-safe {@code .smdat} merging. */
public final class NetworkHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final int MAX_REGIONS_PER_BOOK = 4096;
    private static final int MAX_REGION_FILE_BYTES = 4 * 1024 * 1024;
    private static final long SESSION_TIMEOUT_MS = 2 * 60 * 1000L;

    private static final ExecutorService BOOK_IO_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "SimpleMap-BookIO");
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    });

    private static final Map<String, SavingSession> ACTIVE_SAVES = new ConcurrentHashMap<>();
    private static final Map<String, LearningSession> ACTIVE_LEARNS = new ConcurrentHashMap<>();

    private NetworkHandler() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("3");
        registrar.playToClient(SyncConfigPayload.TYPE, SyncConfigPayload.STREAM_CODEC,
                NetworkHandler::handleSyncConfigOnClient);
        registrar.playToServer(InitSaveBookPayload.TYPE, InitSaveBookPayload.STREAM_CODEC,
                NetworkHandler::handleInitSaveBookOnServer);
        registrar.playToClient(RequestRegionFilePayload.TYPE, RequestRegionFilePayload.STREAM_CODEC,
                NetworkHandler::handleRequestRegionFileOnClient);
        registrar.playToServer(SendRegionFilePayload.TYPE, SendRegionFilePayload.STREAM_CODEC,
                NetworkHandler::handleSendRegionFileOnServer);
        registrar.playToClient(SaveBookCompletePayload.TYPE, SaveBookCompletePayload.STREAM_CODEC,
                NetworkHandler::handleSaveBookCompleteOnClient);
        registrar.playToServer(RequestLearnBookPayload.TYPE, RequestLearnBookPayload.STREAM_CODEC,
                NetworkHandler::handleRequestLearnBookOnServer);
        registrar.playToClient(SendLearnRegionPayload.TYPE, SendLearnRegionPayload.STREAM_CODEC,
                NetworkHandler::handleSendLearnRegionOnClient);
        registrar.playToClient(LearnBookCompletePayload.TYPE, LearnBookCompletePayload.STREAM_CODEC,
                NetworkHandler::handleLearnBookCompleteOnClient);
        registrar.playToServer(AckLearnRegionPayload.TYPE, AckLearnRegionPayload.STREAM_CODEC,
                NetworkHandler::handleAckLearnRegionOnServer);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    private static void handleSyncConfigOnClient(SyncConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            MapConfig.serverRequireMapBook = payload.requireMapBook();
            MapConfig.serverCaveMapMode = Math.max(0, Math.min(2, payload.caveMapMode()));
            if (MapConfig.serverCaveMapMode != 2) {
                CaveMode.clearManualLayer();
                CaveMapManager.getInstance().deactivate();
            }
            LOGGER.info("SimpleMap synced server config: requireBook={}, caveMode={}",
                    payload.requireMapBook(), MapConfig.serverCaveMapMode);
        });
    }

    private static void handleInitSaveBookOnServer(InitSaveBookPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            cleanupExpiredSessions();
            ACTIVE_SAVES.entrySet().removeIf(entry -> entry.getValue().playerId.equals(player.getUUID()));

            if (!isValidBookId(payload.bookId(), true)
                    || payload.regionNames() == null
                    || payload.regionNames().size() > MAX_REGIONS_PER_BOOK) {
                LOGGER.warn("[SimpleMap] Rejected invalid save-book initialization from {}",
                        player.getName().getString());
                return;
            }

            List<String> regionNames = sanitizeRegionNames(payload.regionNames());
            if (regionNames == null) {
                LOGGER.warn("[SimpleMap] Rejected invalid region list from {}", player.getName().getString());
                return;
            }

            InteractionHand hand = payload.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack held = player.getItemInHand(hand);
            String requestedId = payload.bookId();
            if (!canSaveHeldBook(player, held, requestedId)) {
                LOGGER.warn("[SimpleMap] {} attempted to save a map book not held or not owned",
                        player.getName().getString());
                return;
            }
            if (!requestedId.isEmpty() && isBookMergePending(player, requestedId)) {
                player.sendSystemMessage(Component.literal("§eThis merged map book is still being prepared."));
                return;
            }
            player.getCooldowns().addCooldown(held.getItem(), 120);

            String bookId = requestedId.isEmpty() ? UUID.randomUUID().toString() : requestedId;
            SavingSession session = new SavingSession(player.getUUID(), bookId, regionNames,
                    payload.mainHand(), dimensionFolder(player), System.currentTimeMillis());
            ACTIVE_SAVES.put(sessionKey(bookId, player.getUUID()), session);

            LOGGER.info("Started map-book save {} for {} with {} regions", bookId,
                    player.getName().getString(), regionNames.size());
            requestNextSaveRegion(player, session);
        });
    }

    private static void requestNextSaveRegion(ServerPlayer player, SavingSession session) {
        synchronized (session) {
            if (session.expired() || !session.playerId.equals(player.getUUID())
                    || !session.dimensionFolder.equals(dimensionFolder(player))) {
                ACTIVE_SAVES.remove(sessionKey(session.bookId, session.playerId), session);
                if (!session.dimensionFolder.equals(dimensionFolder(player))) {
                    player.sendSystemMessage(Component.literal("§eMap-book saving stopped because you changed dimension."));
                }
                return;
            }
            session.touch();
            if (session.nextIndex >= session.regionNames.size()) {
                completeSaveBook(player, session);
                return;
            }
            String name = session.regionNames.get(session.nextIndex);
            sendToPlayer(player, new RequestRegionFilePayload(session.bookId, name,
                    session.nextIndex, session.regionNames.size(), session.mainHand));
        }
    }

    private static void handleRequestRegionFileOnClient(RequestRegionFilePayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!validTransferMetadata(payload.bookId(), payload.regionName(), payload.currentIdx(),
                    payload.totalCount())) {
                LOGGER.warn("[SimpleMap] Rejected invalid region request from server");
                return;
            }
            File directory = MapManager.getInstance().getCurrentDimensionDir();
            if (directory == null) {
                sendEmptySaveReply(payload);
                return;
            }
            File file = new File(directory, payload.regionName());
            if (!isPathContained(file.toPath(), directory.toPath())) {
                LOGGER.warn("[SimpleMap] Client region path escaped map directory");
                sendEmptySaveReply(payload);
                return;
            }
            int[] coordinates = parseRegionCoordinates(payload.regionName());
            RegionDataStore.StoredRegion loadedSnapshot = coordinates == null ? null
                    : MapManager.getInstance().snapshotStoredRegion(coordinates[0], coordinates[1]);

            BOOK_IO_POOL.execute(() -> {
                byte[] data = new byte[0];
                try {
                    RegionDataStore.StoredRegion snapshot = loadedSnapshot;
                    if (snapshot == null && coordinates != null) {
                        snapshot = RegionDataStore.latestPending(directory,
                                coordinates[0], coordinates[1]);
                    }
                    if (snapshot != null) {
                        byte[] encoded = RegionDataStore.toBytes(snapshot);
                        if (encoded.length <= MAX_REGION_FILE_BYTES) data = encoded;
                    } else if (file.isFile() && Files.size(file.toPath()) <= MAX_REGION_FILE_BYTES) {
                        // Validate before sending so a corrupt local cache cannot poison a book.
                        RegionDataStore.read(file);
                        data = Files.readAllBytes(file.toPath());
                    }
                } catch (IOException exception) {
                    LOGGER.warn("Failed to read map-book region {}", file.getName(), exception);
                }
                byte[] result = data;
                Minecraft.getInstance().execute(() -> sendToServer(new SendRegionFilePayload(
                        payload.bookId(), payload.regionName(), result, payload.currentIdx(),
                        payload.totalCount(), payload.mainHand())));
            });
        });
    }

    private static void sendEmptySaveReply(RequestRegionFilePayload payload) {
        sendToServer(new SendRegionFilePayload(payload.bookId(), payload.regionName(), new byte[0],
                payload.currentIdx(), payload.totalCount(), payload.mainHand()));
    }

    private static void handleSendRegionFileOnServer(SendRegionFilePayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!validTransferMetadata(payload.bookId(), payload.regionName(), payload.currentIdx(),
                    payload.totalCount()) || payload.data().length > MAX_REGION_FILE_BYTES) {
                LOGGER.warn("[SimpleMap] Rejected malformed region upload from {}",
                        player.getName().getString());
                return;
            }

            String key = sessionKey(payload.bookId(), player.getUUID());
            SavingSession session = ACTIVE_SAVES.get(key);
            if (session == null) return;
            synchronized (session) {
                if (session.expired()
                        || !session.dimensionFolder.equals(dimensionFolder(player))
                        || session.processing
                        || payload.currentIdx() != session.nextIndex
                        || payload.totalCount() != session.regionNames.size()
                        || payload.mainHand() != session.mainHand
                        || !payload.regionName().equals(session.regionNames.get(session.nextIndex))) {
                    LOGGER.warn("[SimpleMap] Rejected out-of-order region upload from {}",
                            player.getName().getString());
                    return;
                }
                session.processing = true;
                session.touch();
            }

            byte[] data = Arrays.copyOf(payload.data(), payload.data().length);
            BOOK_IO_POOL.execute(() -> {
                boolean accepted = true;
                try {
                    if (data.length > 0) {
                        RegionDataStore.StoredRegion decoded = RegionDataStore.read(data);
                        Path base = player.server.getWorldPath(LevelResource.ROOT)
                                .resolve("simplemap_books").normalize();
                        Path destinationDirectory = base.resolve(session.bookId)
                                .resolve(session.dimensionFolder).normalize();
                        Path destination = destinationDirectory.resolve(payload.regionName()).normalize();
                        if (!isPathContained(destination, base)) {
                            throw new IOException("Map-book destination escaped base directory");
                        }
                        Files.createDirectories(destinationDirectory);
                        // The client sends its complete canonical surface region, so update replaces it.
                        RegionDataStore.writeAtomic(destination.toFile(), decoded);
                    }
                } catch (IOException exception) {
                    accepted = false;
                    LOGGER.warn("Rejected or failed map-book region {}", payload.regionName(), exception);
                }

                boolean finalAccepted = accepted;
                player.server.execute(() -> {
                    SavingSession current = ACTIVE_SAVES.get(key);
                    if (current != session) return;
                    synchronized (session) {
                        session.processing = false;
                        // Invalid data is skipped rather than stalling the entire session.
                        session.nextIndex++;
                    }
                    if (!finalAccepted) {
                        player.sendSystemMessage(Component.literal("§eSkipped one damaged map region while saving."));
                    }
                    requestNextSaveRegion(player, session);
                });
            });
        });
    }

    private static void completeSaveBook(ServerPlayer player, SavingSession session) {
        ACTIVE_SAVES.remove(sessionKey(session.bookId, session.playerId), session);
        InteractionHand hand = session.mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack held = player.getItemInHand(hand);

        if (held.is(ModItems.EMPTY_MAP_BOOK.get())) {
            ItemStack writtenBook = new ItemStack(ModItems.MAP_BOOK.get());
            CompoundTag tag = new CompoundTag();
            tag.putString("MapBookID", session.bookId);
            tag.putString("OwnerUUID", player.getUUID().toString());
            tag.putString("OwnerName", player.getName().getString());
            writtenBook.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            writtenBook.set(DataComponents.CUSTOM_NAME,
                    Component.literal("§6Map Book of " + player.getName().getString()));
            player.setItemInHand(hand, writtenBook);
            player.sendSystemMessage(Component.literal("§aMap successfully saved to the book!"));
            sendToPlayer(player, new SaveBookCompletePayload(session.bookId));
            return;
        }

        if (held.is(ModItems.MAP_BOOK.get()) && heldBookId(held).equals(session.bookId)) {
            CompoundTag tag = customTag(held);
            tag.putString("MapBookID", session.bookId);
            tag.putString("OwnerUUID", player.getUUID().toString());
            tag.putString("OwnerName", player.getName().getString());
            held.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            player.sendSystemMessage(Component.literal("§aMap successfully updated in the book!"));
            sendToPlayer(player, new SaveBookCompletePayload(session.bookId));
        }
    }

    private static void handleSaveBookCompleteOnClient(SaveBookCompletePayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (isValidBookId(payload.bookId(), false) && MapConfig.serverRequireMapBook) {
                MapManager.getInstance().setHasLearnedMap(true);
            }
        });
    }

    private static void handleRequestLearnBookOnServer(RequestLearnBookPayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            cleanupExpiredSessions();
            ACTIVE_LEARNS.entrySet().removeIf(entry -> entry.getValue().playerId.equals(player.getUUID()));
            if (!isValidBookId(payload.bookId(), false) || !playerHasBook(player, payload.bookId())) {
                LOGGER.warn("[SimpleMap] Rejected learn request without matching book from {}",
                        player.getName().getString());
                return;
            }
            if (isBookMergePending(player, payload.bookId())) {
                player.sendSystemMessage(Component.literal("§eThis merged map book is still being prepared."));
                return;
            }

            Path base = player.server.getWorldPath(LevelResource.ROOT).resolve("simplemap_books").normalize();
            String requestedDimension = dimensionFolder(player);
            Path bookRoot = base.resolve(payload.bookId()).normalize();
            Path bookDirectory = bookRoot.resolve(requestedDimension).normalize();
            if (!isPathContained(bookDirectory, base)) return;
            File directory = bookDirectory.toFile();
            // Compatibility with books written by older builds that used only the
            // custom dimension path and omitted its namespace.
            if (!directory.isDirectory()) {
                String legacy = legacyDimensionFolder(player);
                Path legacyDirectory = bookRoot.resolve(legacy).normalize();
                if (isPathContained(legacyDirectory, base) && legacyDirectory.toFile().isDirectory()) {
                    directory = legacyDirectory.toFile();
                }
            }
            if (!directory.isDirectory()) {
                player.sendSystemMessage(Component.literal("§cThis book has no map data for the current dimension!"));
                return;
            }

            File[] files = directory.listFiles((dir, name) -> isValidRegionName(name));
            if (files == null || files.length == 0) {
                player.sendSystemMessage(Component.literal("§cThis book contains no usable map regions here."));
                return;
            }
            Arrays.sort(files, Comparator.comparing(File::getName));
            List<File> bounded = Arrays.asList(files).subList(0, Math.min(files.length, MAX_REGIONS_PER_BOOK));
            LearningSession session = new LearningSession(player.getUUID(), payload.bookId(),
                    requestedDimension, new ArrayList<>(bounded), System.currentTimeMillis());
            ACTIVE_LEARNS.put(sessionKey(payload.bookId(), player.getUUID()), session);
            sendNextLearnRegion(player, session);
        });
    }

    private static void sendNextLearnRegion(ServerPlayer player, LearningSession session) {
        String key = sessionKey(session.bookId, session.playerId);
        synchronized (session) {
            if (session.expired() || !session.playerId.equals(player.getUUID())
                    || !session.dimensionFolder.equals(dimensionFolder(player))) {
                ACTIVE_LEARNS.remove(key, session);
                if (!session.dimensionFolder.equals(dimensionFolder(player))) {
                    player.sendSystemMessage(Component.literal("§eMap-book learning stopped because you changed dimension."));
                }
                return;
            }
            session.touch();
            if (session.nextIndex >= session.regionFiles.size()) {
                ACTIVE_LEARNS.remove(key, session);
                if (session.successfulRegions <= 0) {
                    player.sendSystemMessage(Component.literal(
                            "§cNo map regions could be learned from this book."));
                    return;
                }
                consumeLearnedBookIfNeeded(player, session.bookId);
                if (session.failedRegions > 0) {
                    player.sendSystemMessage(Component.literal(
                            "§eMap learned, but " + session.failedRegions
                                    + " damaged region(s) were skipped."));
                }
                sendToPlayer(player, new LearnBookCompletePayload(session.bookId));
                return;
            }
            if (session.waitingForAck || session.reading) return;
            session.reading = true;
        }

        int index = session.nextIndex;
        File file = session.regionFiles.get(index);
        BOOK_IO_POOL.execute(() -> {
            byte[] data = null;
            try {
                if (Files.size(file.toPath()) <= MAX_REGION_FILE_BYTES) {
                    RegionDataStore.read(file);
                    data = Files.readAllBytes(file.toPath());
                }
            } catch (IOException exception) {
                LOGGER.warn("Skipping damaged map-book region {}", file.getName(), exception);
            }
            byte[] result = data;
            player.server.execute(() -> {
                if (ACTIVE_LEARNS.get(key) != session) return;
                synchronized (session) {
                    session.reading = false;
                    if (result == null) {
                        session.nextIndex++;
                        session.failedRegions++;
                    } else {
                        session.waitingForAck = true;
                    }
                }
                if (result == null) {
                    sendNextLearnRegion(player, session);
                } else {
                    sendToPlayer(player, new SendLearnRegionPayload(session.bookId, file.getName(),
                            result, index, session.regionFiles.size()));
                }
            });
        });
    }

    private static void handleAckLearnRegionOnServer(AckLearnRegionPayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !isValidBookId(payload.bookId(), false)) return;
            String key = sessionKey(payload.bookId(), player.getUUID());
            LearningSession session = ACTIVE_LEARNS.get(key);
            if (session == null) return;
            synchronized (session) {
                if (session.expired() || !session.dimensionFolder.equals(dimensionFolder(player))
                        || !session.waitingForAck
                        || payload.nextIdx() != session.nextIndex + 1) {
                    LOGGER.warn("[SimpleMap] Rejected out-of-order map-book acknowledgement from {}",
                            player.getName().getString());
                    return;
                }
                session.waitingForAck = false;
                session.nextIndex++;
                if (payload.accepted()) {
                    session.successfulRegions++;
                } else {
                    session.failedRegions++;
                }
                session.touch();
            }
            sendNextLearnRegion(player, session);
        });
    }

    private static void handleSendLearnRegionOnClient(SendLearnRegionPayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!validTransferMetadata(payload.bookId(), payload.regionName(), payload.currentIdx(),
                    payload.totalCount()) || payload.data().length > MAX_REGION_FILE_BYTES) {
                LOGGER.warn("[SimpleMap] Rejected malformed learned region from server");
                return;
            }

            MapManager mapManager = MapManager.getInstance();
            File directory = mapManager.getCurrentDimensionDir();
            long mapGeneration = mapManager.getGeneration();
            byte[] data = Arrays.copyOf(payload.data(), payload.data().length);
            BOOK_IO_POOL.execute(() -> {
                int[] coordinates = parseRegionCoordinates(payload.regionName());
                boolean merged = false;
                try {
                    if (directory == null || coordinates == null || data.length == 0) {
                        throw new IOException("No active map directory or usable learned region data");
                    }
                    File localFile = new File(directory, payload.regionName());
                    if (!isPathContained(localFile.toPath(), directory.toPath())) {
                        throw new IOException("Learned region escaped map directory");
                    }
                    Files.createDirectories(directory.toPath());
                    RegionDataStore.mergeIntoFile(localFile, data);
                    merged = true;
                } catch (IOException exception) {
                    LOGGER.error("Failed to merge learned region {}", payload.regionName(), exception);
                }

                boolean diskMergeSucceeded = merged;
                Minecraft.getInstance().execute(() -> {
                    MapManager currentManager = MapManager.getInstance();
                    boolean stillCurrent = diskMergeSucceeded
                            && currentManager.isGenerationCurrent(mapGeneration)
                            && sameFile(currentManager.getCurrentDimensionDir(), directory);
                    if (stillCurrent) {
                        currentManager.unloadRegion(coordinates[0], coordinates[1]);
                        currentManager.invalidateRegionFile(coordinates[0], coordinates[1]);
                        MapTextureManager.getInstance().markRegionDirty(coordinates[0], coordinates[1]);
                    }
                    sendToServer(new AckLearnRegionPayload(payload.bookId(),
                            payload.currentIdx() + 1, stillCurrent));
                });
            });
        });
    }

    private static void handleLearnBookCompleteOnClient(LearnBookCompletePayload payload,
            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!isValidBookId(payload.bookId(), false)) return;
            MapManager.getInstance().setHasLearnedMap(true);
            MapManager.getInstance().setLastLearnedBookId(payload.bookId());
            MapTextureManager.getInstance().clearCache();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.literal(
                        "§aMap learned successfully! Your map has been updated."));
            }
        });
    }

    private static boolean canSaveHeldBook(ServerPlayer player, ItemStack held, String requestedId) {
        if (requestedId.isEmpty()) return held.is(ModItems.EMPTY_MAP_BOOK.get());
        if (!held.is(ModItems.MAP_BOOK.get()) || !requestedId.equals(heldBookId(held))) return false;
        CompoundTag tag = customTag(held);
        return !tag.contains("OwnerUUID") || player.getUUID().toString().equals(tag.getString("OwnerUUID"));
    }

    private static boolean playerHasBook(ServerPlayer player, String bookId) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ModItems.MAP_BOOK.get()) && bookId.equals(heldBookId(stack))) return true;
        }
        return false;
    }

    private static void consumeLearnedBookIfNeeded(ServerPlayer player, String bookId) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(ModItems.MAP_BOOK.get()) || !bookId.equals(heldBookId(stack))) continue;
            CompoundTag tag = customTag(stack);
            if (tag.contains("OwnerUUID")
                    && !player.getUUID().toString().equals(tag.getString("OwnerUUID"))) {
                stack.shrink(1);
                player.sendSystemMessage(Component.literal(
                        "§eThe map book crumbles to dust as you finish learning its secrets!"));
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                        net.minecraft.sounds.SoundEvents.ITEM_BREAK,
                        net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
            }
            return;
        }
    }

    private static CompoundTag customTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static String heldBookId(ItemStack stack) {
        CompoundTag tag = customTag(stack);
        return tag.contains("MapBookID") ? tag.getString("MapBookID") : "";
    }

    private static List<String> sanitizeRegionNames(List<String> names) {
        if (names.size() > MAX_REGIONS_PER_BOOK) return null;
        Set<String> unique = new HashSet<>();
        for (String name : names) {
            if (!isValidRegionName(name) || !unique.add(name)) return null;
        }
        List<String> result = new ArrayList<>(unique);
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private static boolean validTransferMetadata(String bookId, String regionName, int index, int total) {
        return isValidBookId(bookId, false)
                && isValidRegionName(regionName)
                && total >= 0 && total <= MAX_REGIONS_PER_BOOK
                && index >= 0 && index < total;
    }

    private static boolean isValidBookId(String id, boolean allowEmpty) {
        if (id == null) return false;
        if (allowEmpty && id.isEmpty()) return true;
        return id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /** Canonical surface-map filename: {@code r.<rx>.<rz>.smdat}. */
    public static boolean isValidRegionName(String name) {
        return name != null && name.matches("r\\.-?\\d{1,7}\\.-?\\d{1,7}\\.smdat");
    }

    private static int[] parseRegionCoordinates(String name) {
        if (!isValidRegionName(name)) return null;
        String[] parts = name.split("\\.");
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isPathContained(Path file, Path baseDirectory) {
        try {
            return file.toAbsolutePath().normalize().startsWith(baseDirectory.toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to normalize map-book path", exception);
            return false;
        }
    }

    private static boolean isBookMergePending(ServerPlayer player, String bookId) {
        if (!isValidBookId(bookId, false)) return false;
        Path root = player.server.getWorldPath(LevelResource.ROOT)
                .resolve("simplemap_books").resolve(bookId).normalize();
        return Files.isRegularFile(root.resolve(".merge_pending"));
    }

    private static String dimensionFolder(ServerPlayer player) {
        var location = player.level().dimension().location();
        return location.getNamespace().equals("minecraft")
                ? location.getPath()
                : location.toString().replace(':', '_').replace('/', '_');
    }

    private static String legacyDimensionFolder(ServerPlayer player) {
        String path = player.level().dimension().location().getPath();
        return path.replace('/', '_');
    }

    private static boolean sameFile(File first, File second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        return first.toPath().toAbsolutePath().normalize()
                .equals(second.toPath().toAbsolutePath().normalize());
    }

    private static String sessionKey(String bookId, UUID playerId) {
        return bookId + ':' + playerId;
    }

    private static void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        ACTIVE_SAVES.entrySet().removeIf(entry -> now - entry.getValue().lastActivity > SESSION_TIMEOUT_MS);
        ACTIVE_LEARNS.entrySet().removeIf(entry -> now - entry.getValue().lastActivity > SESSION_TIMEOUT_MS);
    }

    private static final class SavingSession {
        final UUID playerId;
        final String bookId;
        final List<String> regionNames;
        final boolean mainHand;
        final String dimensionFolder;
        final long startedAt;
        volatile long lastActivity;
        int nextIndex;
        boolean processing;

        SavingSession(UUID playerId, String bookId, List<String> regionNames, boolean mainHand,
                String dimensionFolder, long startedAt) {
            this.playerId = playerId;
            this.bookId = bookId;
            this.regionNames = regionNames;
            this.mainHand = mainHand;
            this.dimensionFolder = dimensionFolder;
            this.startedAt = startedAt;
            this.lastActivity = startedAt;
        }

        void touch() {
            lastActivity = System.currentTimeMillis();
        }

        boolean expired() {
            return System.currentTimeMillis() - lastActivity > SESSION_TIMEOUT_MS;
        }
    }

    private static final class LearningSession {
        final UUID playerId;
        final String bookId;
        final String dimensionFolder;
        final List<File> regionFiles;
        final long startedAt;
        volatile long lastActivity;
        int nextIndex;
        int successfulRegions;
        int failedRegions;
        boolean reading;
        boolean waitingForAck;

        LearningSession(UUID playerId, String bookId, String dimensionFolder,
                List<File> regionFiles, long startedAt) {
            this.playerId = playerId;
            this.bookId = bookId;
            this.dimensionFolder = dimensionFolder;
            this.regionFiles = regionFiles;
            this.startedAt = startedAt;
            this.lastActivity = startedAt;
        }

        void touch() {
            lastActivity = System.currentTimeMillis();
        }

        boolean expired() {
            return System.currentTimeMillis() - lastActivity > SESSION_TIMEOUT_MS;
        }
    }
}
