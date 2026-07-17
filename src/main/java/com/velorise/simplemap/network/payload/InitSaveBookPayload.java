package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record InitSaveBookPayload(List<String> regionNames, boolean mainHand, String bookId)
        implements CustomPacketPayload {
    private static final int MAX_REGIONS = 4096;
    public static final Type<InitSaveBookPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "init_save_book"));

    public static final StreamCodec<FriendlyByteBuf, InitSaveBookPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                int count = Math.min(value.regionNames.size(), MAX_REGIONS);
                buffer.writeVarInt(count);
                for (int i = 0; i < count; i++) buffer.writeUtf(value.regionNames.get(i), 48);
                buffer.writeBoolean(value.mainHand);
                buffer.writeUtf(value.bookId, 36);
            },
            buffer -> {
                int count = buffer.readVarInt();
                if (count < 0 || count > MAX_REGIONS) {
                    throw new IllegalArgumentException("Too many SimpleMap book regions: " + count);
                }
                List<String> names = new ArrayList<>(count);
                for (int i = 0; i < count; i++) names.add(buffer.readUtf(48));
                return new InitSaveBookPayload(names, buffer.readBoolean(), buffer.readUtf(36));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
