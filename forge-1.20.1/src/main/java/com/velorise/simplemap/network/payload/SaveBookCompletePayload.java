package com.velorise.simplemap.network.payload;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public record SaveBookCompletePayload(String bookId) implements SimpleMapPayload {
    public static final ResourceLocation ID = new ResourceLocation(SimpleMap.MODID, "save_book_complete");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(bookId, 36);
    }

    public static SaveBookCompletePayload read(FriendlyByteBuf buf) {
        return new SaveBookCompletePayload(buf.readUtf(36));
    }
}
