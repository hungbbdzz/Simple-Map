package com.velorise.simplemap.network;

import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.client.MapManager.MapConfig;
import com.velorise.simplemap.client.MapTextureManager;
import com.velorise.simplemap.client.RegionDataStore;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.network.payload.AckLearnRegionPayload;
import com.velorise.simplemap.network.payload.InitSaveBookPayload;
import com.velorise.simplemap.network.payload.LearnBookCompletePayload;
import com.velorise.simplemap.network.payload.RequestRegionFilePayload;
import com.velorise.simplemap.network.payload.SaveBookCompletePayload;
import com.velorise.simplemap.network.payload.SendLearnRegionPayload;
import com.velorise.simplemap.network.payload.SendRegionFilePayload;
import com.velorise.simplemap.network.payload.SyncConfigPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.Executor;

/**
 * Client-only half of Simple Map's optional server extension protocol.
 *
 * <p>Keeping Minecraft client references out of {@link NetworkHandler} prevents
 * common/server class loading from touching client classes on a dedicated server.</p>
 */
public final class ClientNetworkHandler {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean receiversRegistered = false;

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

        if (receiversRegistered) return;
        receiversRegistered = true;

        ClientPlayNetworking.registerGlobalReceiver(SyncConfigPayload.TYPE,
                (payload, context) -> ClientPayloadHooks.handleSyncConfig(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(RequestRegionFilePayload.TYPE,
                (payload, context) -> ClientPayloadHooks.handleRequestRegion(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(SaveBookCompletePayload.TYPE,
                (payload, context) -> ClientPayloadHooks.handleSaveComplete(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(SendLearnRegionPayload.TYPE,
                (payload, context) -> ClientPayloadHooks.handleLearnRegion(payload, context.client()));
        ClientPlayNetworking.registerGlobalReceiver(LearnBookCompletePayload.TYPE,
                (payload, context) -> ClientPayloadHooks.handleLearnComplete(payload, context.client()));
    }

    /** True only when the connected server advertises Simple Map's optional C2S payloads. */
    public static boolean isServerExtensionAvailable() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null
                && (MapConfig.serverExtensionAvailable
                        || ClientPlayNetworking.canSend(InitSaveBookPayload.TYPE));
    }

    /** Sends only when the remote server supports the exact optional payload. */
    public static boolean sendToServer(CustomPacketPayload payload) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || payload == null || !ClientPlayNetworking.canSend(payload.type())) {
            return false;
        }
        ClientPlayNetworking.send(payload);
        return true;
    }

    static void handleSyncConfig(SyncConfigPayload payload, Executor executor) {
        executor.execute(() -> {
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

    static void handleRequestRegionFile(RequestRegionFilePayload payload, Executor executor) {
        executor.execute(() -> {
            if (!NetworkHandler.validTransferMetadata(payload.bookId(), payload.regionName(),
                    payload.currentIdx(), payload.totalCount())) {
                LOGGER.warn("[SimpleMap] Rejected invalid region request from server");
                return;
            }

            MapManager mapManager = MapManager.getInstance();
            if (!mapManager.isViewingLiveDimension()) {
                mapManager.setViewDimensionOverride(null);
                mapManager.updateWorldAndDimension(Minecraft.getInstance());
            }
            File directory = mapManager.getCurrentDimensionDir();
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

    static void handleSaveBookComplete(SaveBookCompletePayload payload, Executor executor) {
        executor.execute(() -> {
            if (NetworkHandler.isValidBookId(payload.bookId(), false)) {
                MapManager.getInstance().setHasLearnedMap(true);
                MapManager.getInstance().setLastLearnedBookId(payload.bookId());
            }
        });
    }

    static void handleSendLearnRegion(SendLearnRegionPayload payload, Executor executor) {
        executor.execute(() -> {
            if (!NetworkHandler.validTransferMetadata(payload.bookId(), payload.regionName(),
                    payload.currentIdx(), payload.totalCount())
                    || payload.data().length > NetworkHandler.MAX_REGION_FILE_BYTES) {
                LOGGER.warn("[SimpleMap] Rejected malformed learned region from server");
                return;
            }

            MapManager mapManager = MapManager.getInstance();
            if (!mapManager.isViewingLiveDimension()) {
                mapManager.setViewDimensionOverride(null);
                mapManager.updateWorldAndDimension(Minecraft.getInstance());
            }
            File directory = mapManager.getCurrentDimensionDir();
            long mapGeneration = mapManager.getGeneration();
            int[] parsedCoords = NetworkHandler.parseRegionCoordinates(payload.regionName());
            RegionDataStore.StoredRegion liveSnapshot = parsedCoords == null ? null
                    : mapManager.snapshotStoredRegion(parsedCoords[0], parsedCoords[1]);
            byte[] data = Arrays.copyOf(payload.data(), payload.data().length);
            NetworkHandler.BOOK_IO_POOL.execute(() -> {
                int[] coordinates = parsedCoords;
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
                    if (liveSnapshot != null) {
                        byte[] liveBytes = RegionDataStore.toBytes(liveSnapshot);
                        if (liveBytes.length <= NetworkHandler.MAX_REGION_FILE_BYTES) {
                            RegionDataStore.mergeIntoFile(localFile, liveBytes);
                        }
                    }
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

    static void handleLearnBookComplete(LearnBookCompletePayload payload, Executor executor) {
        executor.execute(() -> {
            if (!NetworkHandler.isValidBookId(payload.bookId(), false)) return;
            MapManager.getInstance().setHasLearnedMap(true);
            MapManager.getInstance().setLastLearnedBookId(payload.bookId());
            com.velorise.simplemap.client.SurfaceRegionSourceDatabase.getInstance().clear();
            com.velorise.simplemap.client.RegionSurfaceLodService.getInstance().clear();
            MapTextureManager.getInstance().clearCache();
            com.velorise.simplemap.client.MapOverviewTextureManager.getInstance().clearCache();
            com.velorise.simplemap.client.renderer.FullscreenMapFramebufferRenderer.getInstance().invalidate();
            com.velorise.simplemap.client.renderer.MinimapFramebufferRenderer.getInstance().invalidate();
            com.velorise.simplemap.client.MapRenderer.getInstance().clearFullscreenFallback();
            com.velorise.simplemap.client.MapViewportCoordinator.getInstance().onLayerChanged();
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.player != null) {
                com.velorise.simplemap.client.ChunkScanner.getInstance().requestRefresh(minecraft);
                minecraft.player.sendSystemMessage(Component.translatable("gui.simplemap.book.learn_success"));
            }
        });
    }
}
