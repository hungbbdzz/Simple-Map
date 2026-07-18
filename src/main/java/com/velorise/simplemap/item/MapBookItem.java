package com.velorise.simplemap.item;

import com.velorise.simplemap.MapBookHooks;
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
                player.sendSystemMessage(Component.literal(
                        "§cThis map book is invalid or contains no data!"));
            }
            return InteractionResultHolder.fail(stack);
        }

        if (level.isClientSide) {
            MapBookHooks.useWrittenBook(player, hand, stack, this);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
            List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = customData != null ? customData.copyTag() : null;
        if (tag == null || !tag.contains("MapBookID")) {
            tooltipComponents.add(Component.literal("§cNo Data"));
            return;
        }

        String bookId = tag.getString("MapBookID");
        tooltipComponents.add(Component.literal("§7Map ID: §f"
                + bookId.substring(0, Math.min(8, bookId.length())) + "..."));
        if (tag.contains("OwnerName")) {
            tooltipComponents.add(Component.literal("§7Owner: §e" + tag.getString("OwnerName")));
        }
        tooltipComponents.add(Component.literal("§aRight-click: open or learn map."));
        tooltipComponents.add(Component.literal("§6Shift + Right-click: update an owned map."));
    }
}
