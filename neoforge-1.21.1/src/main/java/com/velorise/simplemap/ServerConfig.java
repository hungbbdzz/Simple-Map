package com.velorise.simplemap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Server-authoritative gameplay settings synced to every joining client. */
public final class ServerConfig {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static File configFile;

    public static boolean requireMapBook = false;
    public static int caveMapMode = 0; // 0 = OFF, 1 = AUTO, 2 = ON

    private ServerConfig() {
    }

    public static synchronized void init() {
        configFile = FMLPaths.CONFIGDIR.get().resolve("simplemap-server.json").toFile();
        if (configFile.exists()) {
            try (var reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
                Data data = GSON.fromJson(reader, Data.class);
                if (data != null) {
                    requireMapBook = data.requireMapBook;
                    caveMapMode = Math.max(0, Math.min(2, data.caveMapMode));
                }
            } catch (Exception exception) {
                LOGGER.error("Failed to load Simple Map server config; preserving unreadable file", exception);
                quarantineCorruptConfig();
                requireMapBook = false;
                caveMapMode = 0;
            }
        }
        save();
    }

    public static synchronized void save() {
        if (configFile == null) return;
        Path target = configFile.toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Data data = new Data();
            data.requireMapBook = requireMapBook;
            data.caveMapMode = Math.max(0, Math.min(2, caveMapMode));
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
            moveReplacing(temporary, target);
        } catch (IOException exception) {
            LOGGER.error("Failed to save Simple Map server config atomically", exception);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }
    }

    private static void quarantineCorruptConfig() {
        if (configFile == null || !configFile.isFile()) return;
        Path source = configFile.toPath();
        Path quarantine = source.resolveSibling(configFile.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            moveReplacing(source, quarantine);
        } catch (IOException exception) {
            LOGGER.warn("Could not quarantine unreadable Simple Map server config {}", source, exception);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class Data {
        boolean requireMapBook = false;
        int caveMapMode = 0;
    }
}
