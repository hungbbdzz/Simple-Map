package com.velorise.simplemap.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.velorise.simplemap.client.gui.MapScreen.MapConfigScreen;

/** Optional Mod Menu integration exposing Simple Map's configuration screen. */
public final class SimpleMapModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MapConfigScreen::new;
    }
}
