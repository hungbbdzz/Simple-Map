package com.velorise.simplemap.mixin;

import com.velorise.simplemap.client.MapMutationBus;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet-to-map mutation bridge. No scanning is performed inside network handlers. */
@Mixin(ClientPacketListener.class)
public abstract class SimpleMapClientPacketListenerMixin {
    private static final String ENSURE_CLIENT_THREAD =
            "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(" +
            "Lnet/minecraft/network/protocol/Packet;" +
            "Lnet/minecraft/network/PacketListener;" +
            "Lnet/minecraft/util/thread/BlockableEventLoop;)V";

    @Inject(method = "handleBlockUpdate",
            at = @At(value = "INVOKE", target = ENSURE_CLIENT_THREAD,
                    shift = At.Shift.AFTER))
    private void simplemap$onBlockUpdate(ClientboundBlockUpdatePacket packet,
            CallbackInfo callback) {
        MapMutationBus.getInstance().onBlockUpdate(packet.getPos());
    }

    @Inject(method = "handleBlockEntityData", at = @At("TAIL"), require = 0)
    private void simplemap$onBlockEntityData(ClientboundBlockEntityDataPacket packet,
            CallbackInfo callback) {
        MapMutationBus.getInstance().onBlockEntityUpdate(packet.getPos());
    }

    @Inject(method = "handleChunkBlocksUpdate",
            at = @At(value = "INVOKE", target = ENSURE_CLIENT_THREAD,
                    shift = At.Shift.AFTER))
    private void simplemap$onSectionBlocksUpdate(
            ClientboundSectionBlocksUpdatePacket packet, CallbackInfo callback) {
        SectionPos section = ((SimpleMapSectionBlocksUpdatePacketAccessor) packet)
                .simplemap$getSectionPos();
        MapMutationBus.getInstance().onSectionBlocksUpdate(
                section.getX(), section.getZ());
    }

    @Inject(method = "updateLevelChunk", at = @At("HEAD"))
    private void simplemap$onChunkData(int chunkX, int chunkZ,
            ClientboundLevelChunkPacketData packet, CallbackInfo callback) {
        MapMutationBus.getInstance().onChunkData(chunkX, chunkZ);
    }

    @Inject(method = "handleLevelChunkWithLight",
            at = @At(value = "INVOKE", target = ENSURE_CLIENT_THREAD,
                    shift = At.Shift.AFTER))
    private void simplemap$onChunkWithLight(
            ClientboundLevelChunkWithLightPacket packet, CallbackInfo callback) {
        MapMutationBus.getInstance().onChunkData(packet.getX(), packet.getZ());
    }

    @Inject(method = "handleLightUpdatePacket",
            at = @At(value = "INVOKE", target = ENSURE_CLIENT_THREAD,
                    shift = At.Shift.AFTER))
    private void simplemap$onLightUpdate(ClientboundLightUpdatePacket packet,
            CallbackInfo callback) {
        MapMutationBus.getInstance().onLightUpdate(packet.getX(), packet.getZ());
    }

    @Inject(method = "queueLightRemoval", at = @At("HEAD"))
    private void simplemap$onChunkUnload(ClientboundForgetLevelChunkPacket packet,
            CallbackInfo callback) {
        MapMutationBus.getInstance().onChunkUnload(
                packet.pos().x, packet.pos().z);
    }
}
