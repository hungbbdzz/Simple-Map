package com.velorise.simplemap;

import com.velorise.simplemap.item.EmptyMapBookItem;
import com.velorise.simplemap.item.MapBookItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, SimpleMap.MODID);

    public static final RegistryObject<EmptyMapBookItem> EMPTY_MAP_BOOK = ITEMS.register("empty_book_map",
            () -> new EmptyMapBookItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<MapBookItem> MAP_BOOK = ITEMS.register("book_map",
            () -> new MapBookItem(new Item.Properties().stacksTo(1)));
}
