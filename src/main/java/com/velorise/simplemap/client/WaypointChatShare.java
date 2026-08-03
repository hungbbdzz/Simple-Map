package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Opens chat with a location message without sending it automatically. */
final class WaypointChatShare {
    private WaypointChatShare() {
    }

    static void shareLocation(Minecraft minecraft, String label,
            double x, double y, double z, boolean hasY, String dimension) {
        if (minecraft == null) return;
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        String coordinates = hasY
                ? String.format(Locale.ROOT, "[%d, %d, %d]", blockX,
                        (int) Math.floor(y), blockZ)
                : String.format(Locale.ROOT, "[%d, ?, %d]", blockX, blockZ);
        String place = label == null || label.isBlank()
                ? "Location" : "Waypoint \"" + label.trim() + "\"";
        String text = place + " at " + coordinates + " in "
                + MapManager.displayDimensionName(dimension);
        minecraft.keyboardHandler.setClipboard(text);
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("Location copied; press Enter to share."), true);
        }
        MapViewportCoordinator.getInstance().closeFullscreen();
        minecraft.setScreen(new ChatScreen(text));
    }
}
