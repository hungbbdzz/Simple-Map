package com.velorise.simplemap.recipe;

import com.velorise.simplemap.ModItems;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class MergeMapBooksRecipe extends CustomRecipe {
    public MergeMapBooksRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer input, Level level) {
        int bookWithDataCount = 0;

        for (int i = 0; i < input.getContainerSize(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.is(ModItems.MAP_BOOK.get())) {
                CompoundTag tag = stack.getTag();
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
    public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
        ItemStack result = new ItemStack(ModItems.MAP_BOOK.get(), 1);

        CompoundTag tag = new CompoundTag();
        tag.putBoolean("PendingMerge", true);
        result.setTag(tag);

        result.setHoverName(Component.translatable("item.simplemap.merged_book_map"));
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
