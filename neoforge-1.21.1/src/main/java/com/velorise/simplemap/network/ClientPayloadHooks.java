package com.velorise.simplemap.network;

import com.velorise.simplemap.network.payload.LearnBookCompletePayload;
import com.velorise.simplemap.network.payload.RequestRegionFilePayload;
import com.velorise.simplemap.network.payload.SaveBookCompletePayload;
import com.velorise.simplemap.network.payload.SendLearnRegionPayload;
import com.velorise.simplemap.network.payload.SyncConfigPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Common-side dispatch bridge for play-to-client payload handlers.
 * The physical client installs real handlers during its dedicated mod bootstrap.
 */
public final class ClientPayloadHooks {
    private static final BiConsumer<SyncConfigPayload, IPayloadContext> NO_SYNC = (payload, context) -> {
    };
    private static final BiConsumer<RequestRegionFilePayload, IPayloadContext> NO_REGION_REQUEST =
            (payload, context) -> {
            };
    private static final BiConsumer<SaveBookCompletePayload, IPayloadContext> NO_SAVE_COMPLETE =
            (payload, context) -> {
            };
    private static final BiConsumer<SendLearnRegionPayload, IPayloadContext> NO_LEARN_REGION =
            (payload, context) -> {
            };
    private static final BiConsumer<LearnBookCompletePayload, IPayloadContext> NO_LEARN_COMPLETE =
            (payload, context) -> {
            };

    private static volatile BiConsumer<SyncConfigPayload, IPayloadContext> syncConfig = NO_SYNC;
    private static volatile BiConsumer<RequestRegionFilePayload, IPayloadContext> requestRegion = NO_REGION_REQUEST;
    private static volatile BiConsumer<SaveBookCompletePayload, IPayloadContext> saveComplete = NO_SAVE_COMPLETE;
    private static volatile BiConsumer<SendLearnRegionPayload, IPayloadContext> learnRegion = NO_LEARN_REGION;
    private static volatile BiConsumer<LearnBookCompletePayload, IPayloadContext> learnComplete = NO_LEARN_COMPLETE;

    private ClientPayloadHooks() {
    }

    public static void install(
            BiConsumer<SyncConfigPayload, IPayloadContext> syncConfigHandler,
            BiConsumer<RequestRegionFilePayload, IPayloadContext> requestRegionHandler,
            BiConsumer<SaveBookCompletePayload, IPayloadContext> saveCompleteHandler,
            BiConsumer<SendLearnRegionPayload, IPayloadContext> learnRegionHandler,
            BiConsumer<LearnBookCompletePayload, IPayloadContext> learnCompleteHandler) {
        syncConfig = Objects.requireNonNull(syncConfigHandler, "syncConfigHandler");
        requestRegion = Objects.requireNonNull(requestRegionHandler, "requestRegionHandler");
        saveComplete = Objects.requireNonNull(saveCompleteHandler, "saveCompleteHandler");
        learnRegion = Objects.requireNonNull(learnRegionHandler, "learnRegionHandler");
        learnComplete = Objects.requireNonNull(learnCompleteHandler, "learnCompleteHandler");
    }

    public static void handleSyncConfig(SyncConfigPayload payload, IPayloadContext context) {
        syncConfig.accept(payload, context);
    }

    public static void handleRequestRegion(RequestRegionFilePayload payload, IPayloadContext context) {
        requestRegion.accept(payload, context);
    }

    public static void handleSaveComplete(SaveBookCompletePayload payload, IPayloadContext context) {
        saveComplete.accept(payload, context);
    }

    public static void handleLearnRegion(SendLearnRegionPayload payload, IPayloadContext context) {
        learnRegion.accept(payload, context);
    }

    public static void handleLearnComplete(LearnBookCompletePayload payload, IPayloadContext context) {
        learnComplete.accept(payload, context);
    }
}
