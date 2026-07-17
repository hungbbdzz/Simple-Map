package com.velorise.simplemap.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only construction hooks kept out of the common mod initializer. */
public final class SimpleMapClientBootstrap {
    private SimpleMapClientBootstrap() {
    }

    public static void registerConfigScreen(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (client, parent) -> new MapConfigScreen(parent));
    }
}
