package com.velorise.simplemap;

import com.velorise.simplemap.item.EmptyMapBookItem;
import com.velorise.simplemap.item.MapBookItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.function.Supplier;

public final class ModItems {
    private static final EmptyMapBookItem EMPTY_MAP_BOOK_INSTANCE = Registry.register(
            BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "empty_book_map"),
            new EmptyMapBookItem(new Item.Properties().stacksTo(1)));

    private static final MapBookItem MAP_BOOK_INSTANCE = Registry.register(
            BuiltInRegistries.ITEM,
            ResourceLocation.fromNamespaceAndPath(SimpleMap.MODID, "book_map"),
            new MapBookItem(new Item.Properties().stacksTo(1)));

    public static final Supplier<EmptyMapBookItem> EMPTY_MAP_BOOK = () -> EMPTY_MAP_BOOK_INSTANCE;
    public static final Supplier<MapBookItem> MAP_BOOK = () -> MAP_BOOK_INSTANCE;

    private ModItems() {
    }

    public static void register() {
        // Static fields register items into BuiltInRegistries.ITEM during ModInitializer.onInitialize().
    }
}
