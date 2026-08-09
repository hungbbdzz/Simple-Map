package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/**
 * Persisted render/cave metadata for one map dimension.
 *
 * <p>Xaero's MapDimension owns a DimensionType and DimensionSpecialEffects even
 * when that dimension is not the player's current level. This compact profile
 * gives SimpleMap the same separation: remote Nether/End/custom maps no longer
 * borrow build limits, skylight/ceiling classification or coordinate scale from
 * the live ClientLevel.</p>
 */
public record DimensionMapProfile(String resourceId, int minY, int maxY,
        boolean hasSkyLight, boolean hasCeiling, boolean forceBrightLightmap,
        float ambientLight, double coordinateScale, boolean known) {
    private static final int VERSION = 2;
    private static final String FILE_NAME = ".dimension_profile";

    public DimensionMapProfile {
        resourceId = normalize(resourceId);
        if (maxY < minY) maxY = minY;
        if (!Float.isFinite(ambientLight)) ambientLight = 0.0f;
        ambientLight = Math.max(0.0f, Math.min(1.0f, ambientLight));
        if (!Double.isFinite(coordinateScale) || coordinateScale <= 0.0) {
            coordinateScale = 1.0;
        }
    }

    public static DimensionMapProfile fromLevel(Level level, String resourceId) {
        if (level == null) return fallback(resourceId);
        boolean forceBright = queryForceBrightLightmap(level);
        double scale = queryCoordinateScale(level);
        float ambient = queryAmbientLight(level);
        return new DimensionMapProfile(resourceId,
                level.getMinBuildHeight(), level.getMaxBuildHeight() - 1,
                level.dimensionType().hasSkyLight(),
                level.dimensionType().hasCeiling(), forceBright, ambient, scale, true);
    }

    /**
     * Resolves saved metadata first, then asks the integrated server for custom
     * DimensionType data when available. Xaero does the same for non-vanilla
     * dimensions instead of treating every unvisited custom dimension as Overworld.
     */
    public static DimensionMapProfile resolve(Path dimensionDirectory,
            String resourceId) {
        DimensionMapProfile saved = load(dimensionDirectory, resourceId);
        if (saved.known()) return saved;
        DimensionMapProfile integrated = fromIntegratedServer(resourceId);
        if (integrated != null) {
            integrated.save(dimensionDirectory);
            return integrated;
        }
        return saved;
    }

    public static DimensionMapProfile load(Path dimensionDirectory,
            String resourceId) {
        Path file = dimensionDirectory == null ? null
                : dimensionDirectory.resolve(FILE_NAME);
        if (file == null || !Files.isRegularFile(file)) return fallback(resourceId);
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            values.load(input);
            int version = parseInt(values.getProperty("version"), 0);
            if (version < 1 || version > VERSION) return fallback(resourceId);
            String id = normalize(values.getProperty("resource_id", resourceId));
            DimensionMapProfile fallback = fallback(id);
            return new DimensionMapProfile(id,
                    parseInt(values.getProperty("min_y"), fallback.minY()),
                    parseInt(values.getProperty("max_y"), fallback.maxY()),
                    Boolean.parseBoolean(values.getProperty("has_skylight")),
                    Boolean.parseBoolean(values.getProperty("has_ceiling")),
                    Boolean.parseBoolean(values.getProperty("force_bright_lightmap")),
                    version >= 2
                            ? parseFloat(values.getProperty("ambient_light"), fallback.ambientLight())
                            : fallback.ambientLight(),
                    parseDouble(values.getProperty("coordinate_scale"), 1.0),
                    true);
        } catch (IOException | RuntimeException ignored) {
            return fallback(resourceId);
        }
    }

    public void save(Path dimensionDirectory) {
        if (dimensionDirectory == null || !known) return;
        try {
            Files.createDirectories(dimensionDirectory);
            Path file = dimensionDirectory.resolve(FILE_NAME);
            Path temp = dimensionDirectory.resolve(FILE_NAME + ".tmp");
            Properties values = new Properties();
            values.setProperty("version", Integer.toString(VERSION));
            values.setProperty("resource_id", resourceId);
            values.setProperty("min_y", Integer.toString(minY));
            values.setProperty("max_y", Integer.toString(maxY));
            values.setProperty("has_skylight", Boolean.toString(hasSkyLight));
            values.setProperty("has_ceiling", Boolean.toString(hasCeiling));
            values.setProperty("force_bright_lightmap",
                    Boolean.toString(forceBrightLightmap));
            values.setProperty("ambient_light", Float.toString(ambientLight));
            values.setProperty("coordinate_scale", Double.toString(coordinateScale));
            try (OutputStream output = Files.newOutputStream(temp)) {
                values.store(output, "SimpleMap dimension profile v" + VERSION);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // Profile persistence is an optimisation/correctness hint; map data is
            // still usable with conservative vanilla/unknown fallbacks.
        }
    }

    public int clampY(int y) {
        return Math.max(minY, Math.min(maxY, y));
    }

    public static DimensionMapProfile fallback(String resourceId) {
        String id = normalize(resourceId);
        return switch (id) {
            case "minecraft:overworld" -> new DimensionMapProfile(id,
                    -64, 319, true, false, false, 0.0f, 1.0, true);
            case "minecraft:the_nether" -> new DimensionMapProfile(id,
                    0, 255, false, true, false, 0.1f, 8.0, true);
            case "minecraft:the_end" -> new DimensionMapProfile(id,
                    0, 255, false, false, true, 0.0f, 1.0, true);
            default -> new DimensionMapProfile(id,
                    -64, 319, false, false, false, 0.0f, 1.0, false);
        };
    }

    private static DimensionMapProfile fromIntegratedServer(String resourceId) {
        String target = normalize(resourceId);
        try {
            Object server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null) return null;
            Object levels = server.getClass().getMethod("getAllLevels").invoke(server);
            if (!(levels instanceof Iterable<?> iterable)) return null;
            for (Object candidate : iterable) {
                if (!(candidate instanceof Level level)) continue;
                if (!target.equals(normalize(level.dimension().location().toString()))) continue;
                return fromLevel(level, target);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Multiplayer/unknown server: persistent profile or conservative
            // fallback remains authoritative.
        }
        return null;
    }

    private static float queryAmbientLight(Level level) {
        try {
            return level.dimensionType().ambientLight();
        } catch (Throwable ignored) {
            return 0.0f;
        }
    }

    private static double queryCoordinateScale(Level level) {
        try {
            return level.dimensionType().coordinateScale();
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    /** Reflection keeps this optional across NeoForge/Parchment minor mappings. */
    private static boolean queryForceBrightLightmap(Level level) {
        try {
            Object effects = level.getClass().getMethod("effects").invoke(level);
            if (effects == null) return false;
            Object result = effects.getClass().getMethod("forceBrightLightmap").invoke(effects);
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static float parseFloat(String value, float fallback) {
        try { return Float.parseFloat(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static double parseDouble(String value, double fallback) {
        try { return Double.parseDouble(value); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static String normalize(String id) {
        if (id == null || id.isBlank()) return "minecraft:overworld";
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
