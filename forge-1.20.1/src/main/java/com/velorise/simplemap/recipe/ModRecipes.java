package com.velorise.simplemap.recipe;

import com.velorise.simplemap.SimpleMap;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModRecipes {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, SimpleMap.MODID);

    public static final RegistryObject<SimpleCraftingRecipeSerializer<CopyMapBookRecipe>> COPY_RECIPE =
            SERIALIZERS.register("copy_book_map", () -> new SimpleCraftingRecipeSerializer<>(CopyMapBookRecipe::new));

    public static final RegistryObject<SimpleCraftingRecipeSerializer<MergeMapBooksRecipe>> MERGE_RECIPE =
            SERIALIZERS.register("merge_book_maps", () -> new SimpleCraftingRecipeSerializer<>(MergeMapBooksRecipe::new));
}
