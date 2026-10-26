package com.velorise.simplemap.network.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

public interface SimpleMapPayload {
    ResourceLocation id();
    void write(FriendlyByteBuf buf);
}
