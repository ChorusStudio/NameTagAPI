package com.perry.nametagapi;

import com.perry.nametagapi.impl.NameTagRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NameTagAPI implements ModInitializer {
    public static final String MOD_ID = "nametagapi";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(NameTagRegistry::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> NameTagRegistry.clearAll());
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> NameTagRegistry.onDisconnect(handler.getPlayer()));
        NameTagCommands.register();
    }
}
