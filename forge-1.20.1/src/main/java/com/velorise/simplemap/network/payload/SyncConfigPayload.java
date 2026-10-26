package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SyncConfigPayload(boolean requireMapBook, int caveMapMode) implements SimpleMapPayload {
    public static final ResourceLocation ID = new ResourceLocation(SimpleMap.MODID, "sync_config");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(requireMapBook);
        buf.writeByte(caveMapMode);
    }

    public static SyncConfigPayload read(FriendlyByteBuf buf) {
        return new SyncConfigPayload(buf.readBoolean(), buf.readUnsignedByte());
    }
}
