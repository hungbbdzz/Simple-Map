package com.velorise.simplemap.recipe;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

import java.util.function.Supplier;

public final class ModRecipes {
    private static final SimpleCraftingRecipeSerializer<CopyMapBookRecipe> COPY_RECIPE_INSTANCE = Registry.register(
            BuiltInRegistries.RECIPE_SERIALIZER,
            new ResourceLocation(SimpleMap.MODID, "copy_book_map"),
            new SimpleCraftingRecipeSerializer<>(CopyMapBookRecipe::new));

    private static final SimpleCraftingRecipeSerializer<MergeMapBooksRecipe> MERGE_RECIPE_INSTANCE = Registry.register(
            BuiltInRegistries.RECIPE_SERIALIZER,
            new ResourceLocation(SimpleMap.MODID, "merge_book_maps"),
            new SimpleCraftingRecipeSerializer<>(MergeMapBooksRecipe::new));

    public static final Supplier<RecipeSerializer<CopyMapBookRecipe>> COPY_RECIPE = () -> COPY_RECIPE_INSTANCE;
    public static final Supplier<RecipeSerializer<MergeMapBooksRecipe>> MERGE_RECIPE = () -> MERGE_RECIPE_INSTANCE;

    private ModRecipes() {
    }

    public static void register() {
        // Static fields register recipe serializers into BuiltInRegistries.RECIPE_SERIALIZER.
    }
}
