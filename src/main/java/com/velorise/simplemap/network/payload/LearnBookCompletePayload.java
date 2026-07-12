package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record LearnBookCompletePayload(String bookId) implements CustomPacketPayload {
    public static final Type<LearnBookCompletePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "learn_book_complete"));

    public static final StreamCodec<FriendlyByteBuf, LearnBookCompletePayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> buf.writeUtf(val.bookId),
            buf -> new LearnBookCompletePayload(buf.readUtf())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
