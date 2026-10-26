package com.velorise.simplemap.network;

import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapManager.MapConfig;
import com.velorise.simplemap.client.MapTextureManager;
import com.velorise.simplemap.client.RegionDataStore;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.network.payload.AckLearnRegionPayload;
import com.velorise.simplemap.network.payload.LearnBookCompletePayload;
import com.velorise.simplemap.network.payload.RequestRegionFilePayload;
import com.velorise.simplemap.network.payload.SaveBookCompletePayload;
import com.velorise.simplemap.network.payload.SendLearnRegionPayload;
import com.velorise.simplemap.network.payload.SendRegionFilePayload;
import com.velorise.simplemap.network.payload.SimpleMapPayload;
import com.velorise.simplemap.network.payload.SyncConfigPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Client-only half of Simple Map's optional server extension protocol.
 */
public final class ClientNetworkHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean clientReceiversRegistered = false;

    private ClientNetworkHandler() {
    }

    /** Installs all play-to-client handlers without exposing this class to common code. */
    public static synchronized void installPayloadHooks() {
        ClientPayloadHooks.install(
                ClientNetworkHandler::handleSyncConfig,
                ClientNetworkHandler::handleRequestRegionFile,
                ClientNetworkHandler::handleSaveBookComplete,
                ClientNetworkHandler::handleSendLearnRegion,
                ClientNetworkHandler::handleLearnBookComplete);

    }

    /** True only when the connected server advertises Simple Map's optional payloads. */
    public static boolean isServerExtensionAvailable() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null
                && NetworkHandler.CHANNEL.isRemotePresent(connection.getConnection());
    }

    /** Sends only when the remote server supports the exact optional payload. */
    public static boolean sendToServer(SimpleMapPayload payload) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || payload == null
                || !NetworkHandler.CHANNEL.isRemotePresent(connection.getConnection())) return false;
        NetworkHandler.CHANNEL.sendToServer(payload);
        return true;
    }

    static void handleSyncConfig(SyncConfigPayload payload) {
        Minecraft.getInstance().execute(() -> {
            MapConfig.serverExtensionAvailable = true;
            MapConfig.serverRequireMapBook = payload.requireMapBook();
            MapConfig.serverCaveMapMode = Math.max(0, Math.min(2, payload.caveMapMode()));
            if (MapConfig.serverCaveMapMode == 0) {
                CaveMapManager.getInstance().deactivate();
            }
            LOGGER.info("SimpleMap synced server config: requireBook={}, caveMode={}",
                    payload.requireMapBook(), MapConfig.serverCaveMapMode);
        });
    }

    static void handleRequestRegionFile(RequestRegionFilePayload payload) {
        Minecraft.getInstance().execute(() -> {
            if (!NetworkHandler.validTransferMetadata(payload.bookId(), payload.regionName(),
                    payload.currentIdx(), payload.totalCount())) {
                LOGGER.warn("[SimpleMap] Rejected invalid region request from server");
                return;
            }

            File directory = MapManager.getInstance().getCurrentDimensionDir();
            if (directory == null) {
                sendEmptySaveReply(payload);
                return;
            }
            File file = new File(directory, payload.regionName());
            if (!NetworkHandler.isPathContained(file.toPath(), directory.toPath())) {
                LOGGER.warn("[SimpleMap] Client region path escaped map directory");
                sendEmptySaveReply(payload);
                return;
            }

            int[] coordinates = NetworkHandler.parseRegionCoordinates(payload.regionName());
            RegionDataStore.StoredRegion loadedSnapshot = coordinates == null ? null
                    : MapManager.getInstance().snapshotStoredRegion(coordinates[0], coordinates[1]);

            NetworkHandler.BOOK_IO_POOL.execute(() -> {
                byte[] data = new byte[0];
                try {
                    RegionDataStore.StoredRegion snapshot = loadedSnapshot;
                    if (snapshot == null && coordinates != null) {
                        snapshot = RegionDataStore.latestPending(directory,
                                coordinates[0], coordinates[1]);
                    }
                    if (snapshot != null) {
                        byte[] encoded = RegionDataStore.toBytes(snapshot);
                        if (encoded.length <= NetworkHandler.MAX_REGION_FILE_BYTES) data = encoded;
                    } else if (file.isFile()
                            && Files.size(file.toPath()) <= NetworkHandler.MAX_REGION_FILE_BYTES) {
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

    static void handleSaveBookComplete(SaveBookCompletePayload payload) {
        Minecraft.getInstance().execute(() -> {
            if (NetworkHandler.isValidBookId(payload.bookId(), false)
                    && MapConfig.serverRequireMapBook) {
                MapManager.getInstance().setHasLearnedMap(true);
            }
        });
    }

    static void handleSendLearnRegion(SendLearnRegionPayload payload) {
        Minecraft.getInstance().execute(() -> {
            if (!NetworkHandler.validTransferMetadata(payload.bookId(), payload.regionName(),
                    payload.currentIdx(), payload.totalCount())
                    || payload.data().length > NetworkHandler.MAX_REGION_FILE_BYTES) {
                LOGGER.warn("[SimpleMap] Rejected malformed learned region from server");
                return;
            }

            MapManager mapManager = MapManager.getInstance();
            File directory = mapManager.getCurrentDimensionDir();
            long mapGeneration = mapManager.getGeneration();
            byte[] data = Arrays.copyOf(payload.data(), payload.data().length);
            NetworkHandler.BOOK_IO_POOL.execute(() -> {
                int[] coordinates = NetworkHandler.parseRegionCoordinates(payload.regionName());
                boolean merged = false;
                try {
                    if (directory == null || coordinates == null || data.length == 0) {
                        throw new IOException("No active map directory or usable learned region data");
                    }
                    File localFile = new File(directory, payload.regionName());
                    if (!NetworkHandler.isPathContained(localFile.toPath(), directory.toPath())) {
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
                            && NetworkHandler.sameFile(currentManager.getCurrentDimensionDir(), directory);
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

    static void handleLearnBookComplete(LearnBookCompletePayload payload) {
        Minecraft.getInstance().execute(() -> {
            if (!NetworkHandler.isValidBookId(payload.bookId(), false)) return;
            MapManager.getInstance().setHasLearnedMap(true);
            MapManager.getInstance().setLastLearnedBookId(payload.bookId());
            MapTextureManager.getInstance().clearCache();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(Component.translatable("gui.simplemap.book.learn_success"));
            }
        });
    }
}
