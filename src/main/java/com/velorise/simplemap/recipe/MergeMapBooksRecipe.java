package com.velorise.simplemap.recipe;

import com.velorise.simplemap.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class MergeMapBooksRecipe extends CustomRecipe {
    public MergeMapBooksRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        int bookWithDataCount = 0;

        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.is(ModItems.MAP_BOOK.get())) {
                CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
                CompoundTag tag = customData != null ? customData.copyTag() : null;
                if (tag != null && tag.contains("MapBookID")) {
                    bookWithDataCount++;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }

        return bookWithDataCount == 2;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = new ItemStack(ModItems.MAP_BOOK.get(), 1);
        
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("PendingMerge", true);
        result.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        
        result.set(DataComponents.CUSTOM_NAME, Component.literal("§6Merged Map Book"));
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.MERGE_RECIPE.get();
    }
}
