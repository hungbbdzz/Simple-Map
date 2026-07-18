package com.velorise.simplemap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

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
            try (FileReader reader = new FileReader(configFile)) {
                Data data = GSON.fromJson(reader, Data.class);
                if (data != null) {
                    requireMapBook = data.requireMapBook;
                    caveMapMode = Math.max(0, Math.min(2, data.caveMapMode));
                }
            } catch (IOException exception) {
                LOGGER.error("Failed to load Simple Map server config", exception);
            }
        }
        save();
    }

    public static synchronized void save() {
        if (configFile == null) return;
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Data data = new Data();
        data.requireMapBook = requireMapBook;
        data.caveMapMode = caveMapMode;
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(data, writer);
        } catch (IOException exception) {
            LOGGER.error("Failed to save Simple Map server config", exception);
        }
    }

    private static final class Data {
        boolean requireMapBook = false;
        int caveMapMode = 0;
    }
}
