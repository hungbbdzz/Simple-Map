package com.velorise.simplemap.item;

import com.velorise.simplemap.MapBookHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public class EmptyMapBookItem extends Item {
    public EmptyMapBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            MapBookHooks.useEmptyBook(player, hand, this);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
