package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record InitSaveBookPayload(List<String> regionNames, boolean mainHand, String bookId) implements SimpleMapPayload {
    private static final int MAX_REGIONS = 4096;
    public static final ResourceLocation ID = new ResourceLocation(SimpleMap.MODID, "init_save_book");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buffer) {
        int count = Math.min(regionNames.size(), MAX_REGIONS);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) buffer.writeUtf(regionNames.get(i), 48);
        buffer.writeBoolean(mainHand);
        buffer.writeUtf(bookId, 36);
    }

    public static InitSaveBookPayload read(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_REGIONS) {
            throw new IllegalArgumentException("Too many SimpleMap book regions: " + count);
        }
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) names.add(buffer.readUtf(48));
        return new InitSaveBookPayload(names, buffer.readBoolean(), buffer.readUtf(36));
    }
}
