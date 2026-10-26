package com.velorise.simplemap.mixin;

import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ResultSlot.class)
public interface SimpleMapResultSlotAccessor {
    @Accessor("craftSlots")
    CraftingContainer simplemap$getCraftSlots();
}
