package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

public record InitSaveBookPayload(List<String> regionNames, boolean mainHand, String bookId) implements CustomPacketPayload {
    public static final Type<InitSaveBookPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "init_save_book"));

    public static final StreamCodec<FriendlyByteBuf, InitSaveBookPayload> STREAM_CODEC = StreamCodec.of(
            (buf, val) -> {
                buf.writeCollection(val.regionNames, FriendlyByteBuf::writeUtf);
                buf.writeBoolean(val.mainHand);
                buf.writeUtf(val.bookId);
            },
            buf -> new InitSaveBookPayload(
                    buf.readCollection(ArrayList::new, FriendlyByteBuf::readUtf),
                    buf.readBoolean(),
                    buf.readUtf()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
