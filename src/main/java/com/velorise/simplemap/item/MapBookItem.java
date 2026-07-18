package com.velorise.simplemap.item;

import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.network.NetworkHandler;
import com.velorise.simplemap.network.payload.InitSaveBookPayload;
import com.velorise.simplemap.network.payload.RequestLearnBookPayload;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.List;

public class MapBookItem extends Item {
    public MapBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : null;
        
        if (tag == null || !tag.contains("MapBookID")) {
            if (!level.isClientSide) {
                player.sendSystemMessage(Component.literal("§cThis map book is invalid or contains no data!"));
            }
            return InteractionResultHolder.fail(stack);
        }

        String bookId = tag.getString("MapBookID");

        if (level.isClientSide) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                if (player.isSecondaryUseActive()) {
                    // SNEAKING: Save/update exploration to this book
                    // Only the owner of the book can save/update to it
                    boolean isOwner = true;
                    if (tag.contains("OwnerUUID")) {
                        isOwner = tag.getString("OwnerUUID").equals(player.getUUID().toString());
                    }
                    if (isOwner) {
                        triggerSaveHandshake(player, hand, bookId);
                        player.getCooldowns().addCooldown(this, 120); // 6s cooldown
                    } else {
                        player.sendSystemMessage(Component.literal("§cYou cannot update a map book that you do not own!"));
                    }
                } else {
                    // NOT SNEAKING:
                    boolean isOwner = false;
                    if (tag.contains("OwnerUUID")) {
                        isOwner = tag.getString("OwnerUUID").equals(player.getUUID().toString());
                    }

                    if (MapManager.getInstance().hasLearnedMap() && (isOwner || bookId.equals(MapManager.getInstance().getLastLearnedBookId()))) {
                        // Open the map screen!
                        MapManager.getInstance().openMapScreen();
                    } else {
                        // Start learning
                        player.sendSystemMessage(Component.literal("§bReading map book and syncing exploration data..."));
                        NetworkHandler.sendToServer(new RequestLearnBookPayload(bookId));
                        player.getCooldowns().addCooldown(this, 120); // 6s cooldown
                    }
                }
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private void triggerSaveHandshake(Player player, InteractionHand hand, String bookId) {
        List<String> regionNames = MapManager.getInstance().getKnownRegionNamesForBook(4096);
        if (regionNames.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cYou haven't explored any map regions to save!"));
            return;
        }

        player.sendSystemMessage(Component.literal("§bPreparing to update " + regionNames.size() + " map regions to the book..."));
        
        NetworkHandler.sendToServer(new InitSaveBookPayload(regionNames, hand == InteractionHand.MAIN_HAND, bookId));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : null;
        if (tag != null && tag.contains("MapBookID")) {
            String bookId = tag.getString("MapBookID");
            tooltipComponents.add(Component.literal("§7Map ID: §f" + bookId.substring(0, Math.min(8, bookId.length())) + "..."));
            if (tag.contains("OwnerName")) {
                tooltipComponents.add(Component.literal("§7Owner: §e" + tag.getString("OwnerName")));
            }

            // Check if player is owner (client-side check for tooltip display)
            boolean isOwner = true;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null && tag.contains("OwnerUUID")) {
                isOwner = tag.getString("OwnerUUID").equals(mc.player.getUUID().toString());
            }

            if (isOwner) {
                tooltipComponents.add(Component.literal("§aRight-click to open map."));
                tooltipComponents.add(Component.literal("§6Shift + Right-click to update map."));
            } else {
                tooltipComponents.add(Component.literal("§aRight-click to learn map §c(One-time use)§a."));
            }
        } else {
            tooltipComponents.add(Component.literal("§cNo Data"));
        }
    }
}
