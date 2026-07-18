package com.velorise.simplemap.client;

import com.velorise.simplemap.MapBookHooks;
import com.velorise.simplemap.SimpleMap;
import com.velorise.simplemap.network.ClientNetworkHandler;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Physical-client entrypoint. Keeps client bootstrap code out of the common mod
 * constructor.
 */
@Mod(value = SimpleMap.MODID, dist = Dist.CLIENT)
public final class SimpleMapClientMod {
    public SimpleMapClientMod(IEventBus modEventBus, ModContainer modContainer) {
        SimpleMapClientBootstrap.registerConfigScreen(modContainer);
        MapBookHooks.install(MapBookClientActions::useEmptyBook, MapBookClientActions::useWrittenBook);
        ClientNetworkHandler.installPayloadHooks();
    }
}
