package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Locale;

/**
 * Lightweight pixel transition used before a cross-dimension teleport command.
 * The command is delayed briefly so the transition is visible before the normal
 * vanilla/modded dimension-loading sequence takes over.
 */
public final class DimensionTeleportTransition {
    private static final long COMMAND_DELAY_NANOS = 220_000_000L;
    private static final long DURATION_NANOS = 900_000_000L;
    private static volatile long startedAtNanos;
    private static volatile int tintRgb = 0x6D3AA8;
    private static volatile String pendingCommand;

    private DimensionTeleportTransition() {
    }

    public static void start(String targetDimension, String command) {
        String id = targetDimension == null ? "" : targetDimension.toLowerCase(Locale.ROOT);
        if (id.contains("nether")) tintRgb = 0x7B245E;
        else if (id.contains("end")) tintRgb = 0xD8D29B;
        else tintRgb = 0x397A9A;
        pendingCommand = command;
        startedAtNanos = System.nanoTime();
    }

    public static void tick() {
        String command = pendingCommand;
        long start = startedAtNanos;
        if (command == null || start == 0L
                || System.nanoTime() - start < COMMAND_DELAY_NANOS) {
            return;
        }
        pendingCommand = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.player.connection != null) {
            minecraft.player.connection.sendCommand(command);
        }
    }

    public static void render(GuiGraphics graphics) {
        long start = startedAtNanos;
        if (start == 0L || graphics == null) return;
        long elapsed = System.nanoTime() - start;
        if (elapsed < 0L || elapsed >= DURATION_NANOS) {
            startedAtNanos = 0L;
            pendingCommand = null;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        float progress = elapsed / (float) DURATION_NANOS;
        float pulse = progress < 0.45f
                ? progress / 0.45f
                : Math.max(0.0f, 1.0f - (progress - 0.45f) / 0.55f);
        int overlayAlpha = Math.min(150, Math.max(0, Math.round(112.0f * pulse)));
        graphics.fill(0, 0, width, height, (overlayAlpha << 24) | tintRgb);

        int centerX = width / 2;
        int centerY = height / 2;
        int maximumRadius = Math.max(20, Math.min(width, height) / 3);
        int radius = Math.max(10, Math.round(maximumRadius * (0.25f + progress * 0.75f)));
        int phase = (int) ((elapsed / 70_000_000L) & 15L);
        int dotSize = Math.max(2, Math.min(6, Math.round(Math.min(width, height) / 180.0f)));

        for (int i = 0; i < 16; i++) {
            double angle = ((i + phase) & 15) * (Math.PI * 2.0 / 16.0);
            int x = centerX + (int) Math.round(Math.cos(angle) * radius);
            int y = centerY + (int) Math.round(Math.sin(angle) * radius * 0.62);
            int trail = (i - phase + 16) & 15;
            int alpha = trail == 0 ? 0xFF : trail <= 3 ? 0xB0 : 0x50;
            int color = (alpha << 24) | 0x00F2F2F2;
            graphics.fill(x - dotSize, y - dotSize,
                    x + dotSize, y + dotSize, color);
        }
    }
}
