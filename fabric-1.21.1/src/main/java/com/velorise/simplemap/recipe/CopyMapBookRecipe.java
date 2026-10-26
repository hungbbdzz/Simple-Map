package com.velorise.simplemap.recipe;

import com.velorise.simplemap.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class CopyMapBookRecipe extends CustomRecipe {
    public CopyMapBookRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        int bookWithDataCount = 0;
        int emptyBookCount = 0;

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
            } else if (stack.is(ModItems.EMPTY_MAP_BOOK.get())) {
                emptyBookCount++;
            } else {
                return false;
            }
        }

        return bookWithDataCount == 1 && emptyBookCount == 1;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack sourceBook = ItemStack.EMPTY;

        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.is(ModItems.MAP_BOOK.get())) {
                sourceBook = stack;
                break;
            }
        }

        if (sourceBook.isEmpty()) return ItemStack.EMPTY;

        ItemStack result = new ItemStack(ModItems.MAP_BOOK.get(), 2);
        
        // Copy custom NBT data components
        CustomData customData = sourceBook.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            result.set(DataComponents.CUSTOM_DATA, customData);
        }
        
        // Copy custom name component
        if (sourceBook.has(DataComponents.CUSTOM_NAME)) {
            result.set(DataComponents.CUSTOM_NAME, sourceBook.get(DataComponents.CUSTOM_NAME));
        }
        
        return result;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.COPY_RECIPE.get();
    }
}
