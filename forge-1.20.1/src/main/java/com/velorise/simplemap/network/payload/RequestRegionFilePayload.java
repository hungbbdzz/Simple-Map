package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record RequestRegionFilePayload(String bookId, String regionName, int currentIdx, int totalCount, boolean mainHand)
        implements SimpleMapPayload {
    public static final ResourceLocation ID = new ResourceLocation(SimpleMap.MODID, "request_region_file");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(bookId, 36);
        buf.writeUtf(regionName, 48);
        buf.writeInt(currentIdx);
        buf.writeInt(totalCount);
        buf.writeBoolean(mainHand);
    }

    public static RequestRegionFilePayload read(FriendlyByteBuf buf) {
        return new RequestRegionFilePayload(
                buf.readUtf(36),
                buf.readUtf(48),
                buf.readInt(),
                buf.readInt(),
                buf.readBoolean()
        );
    }
}
