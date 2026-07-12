package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestLearnBookPayload(String bookId) implements CustomPacketPayload {
    public static final Type<RequestLearnBookPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "request_learn_book"));

    public static final StreamCodec<FriendlyByteBuf, RequestLearnBookPayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> buf.writeUtf(val.bookId),
            buf -> new RequestLearnBookPayload(buf.readUtf())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
