package com.velorise.simplemap.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientboundSectionBlocksUpdatePacket.class)
public interface SimpleMapSectionBlocksUpdatePacketAccessor {
    @Accessor("sectionPos")
    SectionPos simplemap$getSectionPos();
}
