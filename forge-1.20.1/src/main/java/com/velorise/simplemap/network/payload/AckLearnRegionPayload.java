package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record AckLearnRegionPayload(String bookId, int nextIdx, boolean accepted) implements SimpleMapPayload {
    public static final ResourceLocation ID = new ResourceLocation(SimpleMap.MODID, "ack_learn_region");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(bookId, 36);
        buf.writeInt(nextIdx);
        buf.writeBoolean(accepted);
    }

    public static AckLearnRegionPayload read(FriendlyByteBuf buf) {
        return new AckLearnRegionPayload(buf.readUtf(36), buf.readInt(), buf.readBoolean());
    }
}
