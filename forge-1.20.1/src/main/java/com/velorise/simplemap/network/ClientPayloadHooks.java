package com.velorise.simplemap.network;

import com.velorise.simplemap.network.payload.LearnBookCompletePayload;
import com.velorise.simplemap.network.payload.RequestRegionFilePayload;
import com.velorise.simplemap.network.payload.SaveBookCompletePayload;
import com.velorise.simplemap.network.payload.SendLearnRegionPayload;
import com.velorise.simplemap.network.payload.SyncConfigPayload;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Common-side dispatch bridge for play-to-client payload handlers.
 * The physical client installs real handlers during its dedicated mod bootstrap.
 */
public final class ClientPayloadHooks {
    private static final Consumer<SyncConfigPayload> NO_SYNC = payload -> {};
    private static final Consumer<RequestRegionFilePayload> NO_REGION_REQUEST = payload -> {};
    private static final Consumer<SaveBookCompletePayload> NO_SAVE_COMPLETE = payload -> {};
    private static final Consumer<SendLearnRegionPayload> NO_LEARN_REGION = payload -> {};
    private static final Consumer<LearnBookCompletePayload> NO_LEARN_COMPLETE = payload -> {};

    private static volatile Consumer<SyncConfigPayload> syncConfig = NO_SYNC;
    private static volatile Consumer<RequestRegionFilePayload> requestRegion = NO_REGION_REQUEST;
    private static volatile Consumer<SaveBookCompletePayload> saveComplete = NO_SAVE_COMPLETE;
    private static volatile Consumer<SendLearnRegionPayload> learnRegion = NO_LEARN_REGION;
    private static volatile Consumer<LearnBookCompletePayload> learnComplete = NO_LEARN_COMPLETE;

    private ClientPayloadHooks() {
    }

    public static void install(
            Consumer<SyncConfigPayload> syncConfigHandler,
            Consumer<RequestRegionFilePayload> requestRegionHandler,
            Consumer<SaveBookCompletePayload> saveCompleteHandler,
            Consumer<SendLearnRegionPayload> learnRegionHandler,
            Consumer<LearnBookCompletePayload> learnCompleteHandler) {
        syncConfig = Objects.requireNonNull(syncConfigHandler, "syncConfigHandler");
        requestRegion = Objects.requireNonNull(requestRegionHandler, "requestRegionHandler");
        saveComplete = Objects.requireNonNull(saveCompleteHandler, "saveCompleteHandler");
        learnRegion = Objects.requireNonNull(learnRegionHandler, "learnRegionHandler");
        learnComplete = Objects.requireNonNull(learnCompleteHandler, "learnCompleteHandler");
    }

    public static void handleSyncConfig(SyncConfigPayload payload) {
        syncConfig.accept(payload);
    }

    public static void handleRequestRegion(RequestRegionFilePayload payload) {
        requestRegion.accept(payload);
    }

    public static void handleSaveComplete(SaveBookCompletePayload payload) {
        saveComplete.accept(payload);
    }

    public static void handleLearnRegion(SendLearnRegionPayload payload) {
        learnRegion.accept(payload);
    }

    public static void handleLearnComplete(LearnBookCompletePayload payload) {
        learnComplete.accept(payload);
    }
}
