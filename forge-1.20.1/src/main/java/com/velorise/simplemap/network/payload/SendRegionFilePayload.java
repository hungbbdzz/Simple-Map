package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SendRegionFilePayload(String bookId, String regionName, byte[] data, int currentIdx, int totalCount, boolean mainHand)
        implements SimpleMapPayload {
    public static final ResourceLocation ID = new ResourceLocation(SimpleMap.MODID, "send_region_file");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(bookId, 36);
        buf.writeUtf(regionName, 48);
        buf.writeByteArray(data);
        buf.writeInt(currentIdx);
        buf.writeInt(totalCount);
        buf.writeBoolean(mainHand);
    }

    public static SendRegionFilePayload read(FriendlyByteBuf buf) {
        return new SendRegionFilePayload(
                buf.readUtf(36),
                buf.readUtf(48),
                buf.readByteArray(4194304),
                buf.readInt(),
                buf.readInt(),
                buf.readBoolean()
        );
    }
}
