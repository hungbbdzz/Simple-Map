package com.velorise.simplemap.mixin;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Enhances incoming chat messages with clickable map coordinate links. */
@Mixin(ChatComponent.class)
public abstract class SimpleMapChatComponentMixin {
    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private Component simplemap$enhanceChatCoordinates(Component message) {
        return SimpleMap.ClientGameEvents.enhanceIncomingChatMessage(message);
    }
}
