package com.velorise.simplemap.mixin;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges crafting result collection to Simple Map's server-side Map Book merger. */
@Mixin(ResultSlot.class)
public abstract class SimpleMapResultSlotMixin {
    @Shadow
    @Final
    private CraftingContainer craftSlots;

    @Shadow
    @Final
    private Player player;

    @Inject(method = "checkTakeAchievements", at = @At("HEAD"))
    private void simplemap$onCheckTakeAchievements(ItemStack stack, CallbackInfo callback) {
        SimpleMap.CommonGameEvents.onItemCrafted(this.player, stack, this.craftSlots);
    }
}
