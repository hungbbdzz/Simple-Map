package com.velorise.simplemap.recipe;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS = DeferredRegister.create(BuiltInRegistries.RECIPE_SERIALIZER, SimpleMap.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<CopyMapBookRecipe>> COPY_RECIPE = SERIALIZERS.register("copy_book_map",
            () -> new SimpleCraftingRecipeSerializer<>(CopyMapBookRecipe::new));

    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<MergeMapBooksRecipe>> MERGE_RECIPE = SERIALIZERS.register("merge_book_maps",
            () -> new SimpleCraftingRecipeSerializer<>(MergeMapBooksRecipe::new));
}
