package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SaveBookCompletePayload(String bookId) implements CustomPacketPayload {
    public static final Type<SaveBookCompletePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "save_book_complete"));

    public static final StreamCodec<FriendlyByteBuf, SaveBookCompletePayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> buf.writeUtf(val.bookId, 36),
            buf -> new SaveBookCompletePayload(buf.readUtf(36))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
