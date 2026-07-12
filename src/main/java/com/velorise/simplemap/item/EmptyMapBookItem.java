package com.velorise.simplemap.item;

import com.velorise.simplemap.client.MapManager;
import com.velorise.simplemap.network.NetworkHandler;
import com.velorise.simplemap.network.payload.InitSaveBookPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class EmptyMapBookItem extends Item {
    public EmptyMapBookItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        
        if (level.isClientSide) {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                triggerSaveHandshake(player, hand);
            }
        }
        
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private void triggerSaveHandshake(Player player, InteractionHand hand) {
        File dir = MapManager.getInstance().getCurrentDimensionDir();
        if (dir == null || !dir.exists()) {
            player.sendSystemMessage(Component.literal("§cNo map data found. Explore around to scan the map first!"));
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.startsWith("r.") && name.endsWith(".png"));
        if (files == null || files.length == 0) {
            player.sendSystemMessage(Component.literal("§cYou haven't explored any map regions to save!"));
            return;
        }

        List<String> regionNames = new ArrayList<>();
        for (File f : files) {
            regionNames.add(f.getName());
        }

        player.sendSystemMessage(Component.literal("§bPreparing to save " + regionNames.size() + " map regions to the book..."));
        
        NetworkHandler.sendToServer(new InitSaveBookPayload(regionNames, hand == InteractionHand.MAIN_HAND, ""));
        player.getCooldowns().addCooldown(this, 120); // 6 seconds cooldown
    }
}
