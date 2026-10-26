package com.velorise.simplemap.recipe;

import com.velorise.simplemap.ModItems;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

public class CopyMapBookRecipe extends CustomRecipe {
    public CopyMapBookRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer input, Level level) {
        int bookWithDataCount = 0;
        int emptyBookCount = 0;

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
            } else if (stack.is(ModItems.EMPTY_MAP_BOOK.get())) {
                emptyBookCount++;
            } else {
                return false;
            }
        }

        return bookWithDataCount == 1 && emptyBookCount == 1;
    }

    @Override
    public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
        ItemStack sourceBook = ItemStack.EMPTY;

        for (int i = 0; i < input.getContainerSize(); i++) {
            ItemStack stack = input.getItem(i);
            if (stack.isEmpty()) continue;

            if (stack.is(ModItems.MAP_BOOK.get())) {
                sourceBook = stack;
                break;
            }
        }

        if (sourceBook.isEmpty()) return ItemStack.EMPTY;

        ItemStack result = new ItemStack(ModItems.MAP_BOOK.get(), 2);

        CompoundTag sourceTag = sourceBook.getTag();
        if (sourceTag != null) {
            result.setTag(sourceTag.copy());
        }

        if (sourceBook.hasCustomHoverName()) {
            result.setHoverName(sourceBook.getHoverName());
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
