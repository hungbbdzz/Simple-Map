package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SendLearnRegionPayload(String bookId, String regionName, byte[] data, int currentIdx, int totalCount) implements CustomPacketPayload {
    public static final Type<SendLearnRegionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "send_learn_region"));

    public static final StreamCodec<FriendlyByteBuf, SendLearnRegionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> {
                buf.writeUtf(val.bookId, 36);
                buf.writeUtf(val.regionName, 48);
                buf.writeByteArray(val.data);
                buf.writeInt(val.currentIdx);
                buf.writeInt(val.totalCount);
            },
            buf -> new SendLearnRegionPayload(
                    buf.readUtf(36),
                    buf.readUtf(48),
                    buf.readByteArray(4194304), // Bounded to 4MB; canonical .smdat is normally much smaller
                    buf.readInt(),
                    buf.readInt()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
