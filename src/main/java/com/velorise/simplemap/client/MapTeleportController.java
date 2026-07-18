package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Server-authoritative map teleport helper. The client only exposes the action
 * outside normal Survival restrictions; the server still validates the command
 * and target dimension.
 */
public final class MapTeleportController {
    private MapTeleportController() {
    }

    public static boolean canTeleport(Minecraft minecraft) {
        return minecraft != null && minecraft.player != null
                && (minecraft.player.isCreative()
                        || minecraft.player.isSpectator()
                        || minecraft.player.hasPermissions(2));
    }

    public static int defaultTargetY(String dimension) {
        String normalized = MapManager.normalizeDimensionResourceId(dimension)
                .toLowerCase(Locale.ROOT);
        if (normalized.equals("minecraft:the_nether") || normalized.endsWith(":the_nether")
                || normalized.endsWith(":nether")) {
            return 65;
        }
        if (normalized.equals("minecraft:the_end") || normalized.endsWith(":the_end")
                || normalized.endsWith(":end")) {
            return 80;
        }
        return 100;
    }

    public static boolean teleport(Minecraft minecraft, String dimension,
            int x, int y, int z) {
        if (minecraft == null || minecraft.player == null
                || minecraft.player.connection == null) {
            return false;
        }
        if (!canTeleport(minecraft)) {
            minecraft.player.sendSystemMessage(Component.literal(
                    "Teleport requires Creative, Spectator, or permission level 2."));
            return false;
        }

        MapManager manager = MapManager.getInstance();
        String targetDimension = manager.resolveDimensionResourceId(dimension);
        ResourceLocation targetId = ResourceLocation.tryParse(targetDimension);
        if (targetId == null) {
            minecraft.player.sendSystemMessage(Component.literal(
                    "Invalid target dimension: " + targetDimension));
            return false;
        }

        String liveDimension = MapManager.normalizeDimensionResourceId(
                manager.getLiveDimensionResourceId());
        boolean crossDimension = !targetId.toString().equals(liveDimension);
        String command;
        if (crossDimension) {
            command = String.format(Locale.ROOT,
                    "execute in %s run teleport @s %d %d %d",
                    targetId, x, y, z);
            DimensionTeleportTransition.start(targetId.toString(), command);
            return true;
        } else {
            command = String.format(Locale.ROOT,
                    "teleport %d %d %d", x, y, z);
        }

        minecraft.player.connection.sendCommand(command);
        return true;
    }
}
