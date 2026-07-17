package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RequestRegionFilePayload(String bookId, String regionName, int currentIdx, int totalCount, boolean mainHand) implements CustomPacketPayload {
    public static final Type<RequestRegionFilePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "request_region_file"));

    public static final StreamCodec<FriendlyByteBuf, RequestRegionFilePayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> {
                buf.writeUtf(val.bookId, 36);
                buf.writeUtf(val.regionName, 48);
                buf.writeInt(val.currentIdx);
                buf.writeInt(val.totalCount);
                buf.writeBoolean(val.mainHand);
            },
            buf -> new RequestRegionFilePayload(
                    buf.readUtf(36),
                    buf.readUtf(48),
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
