package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SendRegionFilePayload(String bookId, String regionName, byte[] data, int currentIdx, int totalCount, boolean mainHand) implements CustomPacketPayload {
    public static final Type<SendRegionFilePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "send_region_file"));

    public static final StreamCodec<FriendlyByteBuf, SendRegionFilePayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> {
                buf.writeUtf(val.bookId);
                buf.writeUtf(val.regionName);
                buf.writeByteArray(val.data);
                buf.writeInt(val.currentIdx);
                buf.writeInt(val.totalCount);
                buf.writeBoolean(val.mainHand);
            },
            buf -> new SendRegionFilePayload(
                    buf.readUtf(),
                    buf.readUtf(),
                    buf.readByteArray(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readBoolean()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
