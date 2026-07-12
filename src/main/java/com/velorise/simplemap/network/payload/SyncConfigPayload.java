package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SyncConfigPayload(boolean requireMapBook) implements CustomPacketPayload {
    public static final Type<SyncConfigPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "sync_config"));

    public static final StreamCodec<FriendlyByteBuf, SyncConfigPayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> buf.writeBoolean(val.requireMapBook),
            buf -> new SyncConfigPayload(buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
