package com.velorise.simplemap.network;

import com.velorise.simplemap.network.payload.LearnBookCompletePayload;
import com.velorise.simplemap.network.payload.RequestRegionFilePayload;
import com.velorise.simplemap.network.payload.SaveBookCompletePayload;
import com.velorise.simplemap.network.payload.SendLearnRegionPayload;
import com.velorise.simplemap.network.payload.SyncConfigPayload;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * Common-side dispatch bridge for play-to-client payload handlers.
 * The physical client installs real handlers during its dedicated mod bootstrap.
 */
public final class ClientPayloadHooks {
    private static final BiConsumer<SyncConfigPayload, Executor> NO_SYNC = (payload, executor) -> {
    };
    private static final BiConsumer<RequestRegionFilePayload, Executor> NO_REGION_REQUEST =
            (payload, executor) -> {
            };
    private static final BiConsumer<SaveBookCompletePayload, Executor> NO_SAVE_COMPLETE =
            (payload, executor) -> {
            };
    private static final BiConsumer<SendLearnRegionPayload, Executor> NO_LEARN_REGION =
            (payload, executor) -> {
            };
    private static final BiConsumer<LearnBookCompletePayload, Executor> NO_LEARN_COMPLETE =
            (payload, executor) -> {
            };

    private static volatile BiConsumer<SyncConfigPayload, Executor> syncConfig = NO_SYNC;
    private static volatile BiConsumer<RequestRegionFilePayload, Executor> requestRegion = NO_REGION_REQUEST;
    private static volatile BiConsumer<SaveBookCompletePayload, Executor> saveComplete = NO_SAVE_COMPLETE;
    private static volatile BiConsumer<SendLearnRegionPayload, Executor> learnRegion = NO_LEARN_REGION;
    private static volatile BiConsumer<LearnBookCompletePayload, Executor> learnComplete = NO_LEARN_COMPLETE;

    private ClientPayloadHooks() {
    }

    public static void install(
            BiConsumer<SyncConfigPayload, Executor> syncConfigHandler,
            BiConsumer<RequestRegionFilePayload, Executor> requestRegionHandler,
            BiConsumer<SaveBookCompletePayload, Executor> saveCompleteHandler,
            BiConsumer<SendLearnRegionPayload, Executor> learnRegionHandler,
            BiConsumer<LearnBookCompletePayload, Executor> learnCompleteHandler) {
        syncConfig = Objects.requireNonNull(syncConfigHandler, "syncConfigHandler");
        requestRegion = Objects.requireNonNull(requestRegionHandler, "requestRegionHandler");
        saveComplete = Objects.requireNonNull(saveCompleteHandler, "saveCompleteHandler");
        learnRegion = Objects.requireNonNull(learnRegionHandler, "learnRegionHandler");
        learnComplete = Objects.requireNonNull(learnCompleteHandler, "learnCompleteHandler");
    }

    public static void handleSyncConfig(SyncConfigPayload payload, Executor executor) {
        syncConfig.accept(payload, executor);
    }

    public static void handleRequestRegion(RequestRegionFilePayload payload, Executor executor) {
        requestRegion.accept(payload, executor);
    }

    public static void handleSaveComplete(SaveBookCompletePayload payload, Executor executor) {
        saveComplete.accept(payload, executor);
    }

    public static void handleLearnRegion(SendLearnRegionPayload payload, Executor executor) {
        learnRegion.accept(payload, executor);
    }

    public static void handleLearnComplete(LearnBookCompletePayload payload, Executor executor) {
        learnComplete.accept(payload, executor);
    }
}
