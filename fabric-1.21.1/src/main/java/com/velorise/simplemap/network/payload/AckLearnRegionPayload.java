package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AckLearnRegionPayload(String bookId, int nextIdx, boolean accepted) implements CustomPacketPayload {
    public static final Type<AckLearnRegionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "ack_learn_region"));

    public static final StreamCodec<FriendlyByteBuf, AckLearnRegionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> {
                buf.writeUtf(val.bookId, 36);
                buf.writeInt(val.nextIdx);
                buf.writeBoolean(val.accepted);
            },
            buf -> new AckLearnRegionPayload(buf.readUtf(36), buf.readInt(), buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
