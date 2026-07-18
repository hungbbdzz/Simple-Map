package com.velorise.simplemap.client;

import com.velorise.simplemap.network.ClientNetworkHandler;
import com.velorise.simplemap.network.payload.InitSaveBookPayload;
import com.velorise.simplemap.network.payload.RequestLearnBookPayload;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/** Client-only actions for Map Book items. */
public final class MapBookClientActions {
    private MapBookClientActions() {
    }

    public static void useEmptyBook(Player player, InteractionHand hand, Item cooldownItem) {
        if (player == null) return;
        if (!ClientNetworkHandler.isServerExtensionAvailable()) {
            unavailable(player);
            return;
        }
        List<String> regionNames = MapManager.getInstance().getKnownRegionNamesForBook(4096);
        if (regionNames.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cYou haven't explored any map regions to save!"));
            return;
        }

        player.sendSystemMessage(Component.literal(
                "§bPreparing to save " + regionNames.size() + " map regions to the book..."));
        if (ClientNetworkHandler.sendToServer(
                new InitSaveBookPayload(regionNames, hand == InteractionHand.MAIN_HAND, ""))) {
            player.getCooldowns().addCooldown(cooldownItem, 120);
        }
    }

    public static void useWrittenBook(Player player, InteractionHand hand, ItemStack stack, Item cooldownItem) {
        if (player == null || stack == null || stack.isEmpty()) return;
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : null;
        if (tag == null || !tag.contains("MapBookID")) {
            player.sendSystemMessage(Component.literal("§cThis map book is invalid or contains no data!"));
            return;
        }

        String bookId = tag.getString("MapBookID");
        if (player.isSecondaryUseActive()) {
            boolean isOwner = !tag.contains("OwnerUUID")
                    || tag.getString("OwnerUUID").equals(player.getUUID().toString());
            if (!isOwner) {
                player.sendSystemMessage(Component.literal(
                        "§cYou cannot update a map book that you do not own!"));
                return;
            }
            updateBook(player, hand, bookId, cooldownItem);
            return;
        }

        boolean isOwner = tag.contains("OwnerUUID")
                && tag.getString("OwnerUUID").equals(player.getUUID().toString());
        MapManager mapManager = MapManager.getInstance();
        if (mapManager.hasLearnedMap()
                && (isOwner || bookId.equals(mapManager.getLastLearnedBookId()))) {
            mapManager.openMapScreen();
            return;
        }
        if (!ClientNetworkHandler.isServerExtensionAvailable()) {
            unavailable(player);
            return;
        }

        player.sendSystemMessage(Component.literal(
                "§bReading map book and syncing exploration data..."));
        if (ClientNetworkHandler.sendToServer(new RequestLearnBookPayload(bookId))) {
            player.getCooldowns().addCooldown(cooldownItem, 120);
        }
    }

    private static void updateBook(Player player, InteractionHand hand, String bookId, Item cooldownItem) {
        if (!ClientNetworkHandler.isServerExtensionAvailable()) {
            unavailable(player);
            return;
        }
        List<String> regionNames = MapManager.getInstance().getKnownRegionNamesForBook(4096);
        if (regionNames.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cYou haven't explored any map regions to save!"));
            return;
        }

        player.sendSystemMessage(Component.literal(
                "§bPreparing to update " + regionNames.size() + " map regions to the book..."));
        if (ClientNetworkHandler.sendToServer(
                new InitSaveBookPayload(regionNames, hand == InteractionHand.MAIN_HAND, bookId))) {
            player.getCooldowns().addCooldown(cooldownItem, 120);
        }
    }

    private static void unavailable(Player player) {
        player.sendSystemMessage(Component.literal(
                "§eMap Books require Simple Map on the server. Your local minimap still works."));
    }
}
