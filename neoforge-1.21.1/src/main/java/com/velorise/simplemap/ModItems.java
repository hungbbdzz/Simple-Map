package com.velorise.simplemap;

import com.velorise.simplemap.item.EmptyMapBookItem;
import com.velorise.simplemap.item.MapBookItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SimpleMap.MODID);

    public static final DeferredHolder<Item, EmptyMapBookItem> EMPTY_MAP_BOOK = ITEMS.register("empty_book_map",
            () -> new EmptyMapBookItem(new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, MapBookItem> MAP_BOOK = ITEMS.register("book_map",
            () -> new MapBookItem(new Item.Properties().stacksTo(1)));
}
