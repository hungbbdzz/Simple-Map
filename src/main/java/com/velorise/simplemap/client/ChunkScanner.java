package com.velorise.simplemap.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;

public class ChunkScanner {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final ChunkScanner INSTANCE = new ChunkScanner();

    public static ChunkScanner getInstance() {
        return INSTANCE;
    }

    private final Random random = new Random();

    private ChunkScanner() {
    }

    /** Reset scan state so a fresh world starts with a clean slate */
    public void reset() {
        // Nothing to reset in random-sampling mode; stateless by design
    }

    /**
     * Each tick, picks MapConfig.scanPointsPerTick random block positions within
     * the circular radius
     * and scans them. This creates a smooth, speckled reveal that covers the full
     * area
     * evenly over time with minimal CPU impact (fixed cost regardless of radius).
     */
    public void scanAroundPlayerUniform(Minecraft mc, int maxRadius) {
        if (mc.level == null || mc.player == null)
            return;

        int centerBlockX = (int) Math.floor(mc.player.getX());
        int centerBlockZ = (int) Math.floor(mc.player.getZ());
        int radiusSq = maxRadius * maxRadius;

        // 1. Scan immediate small 13x13 circular region (radius = 6) around the player
        // instantly to capture direct player interactions (building/mining)
        int innerRadius = 6;
        int innerRadiusSq = innerRadius * innerRadius;
        for (int dz = -innerRadius; dz <= innerRadius; dz++) {
            for (int dx = -innerRadius; dx <= innerRadius; dx++) {
                if (dx * dx + dz * dz <= innerRadiusSq) {
                    scanColumnIfLoaded(mc, centerBlockX + dx, centerBlockZ + dz);
                }
            }
        }

        // 2. Scan random points across the wider radius (smooth reveal with unexplored
        // priority)
        int samplesTarget = MapConfig.scanPointsPerTick; // Read live from config
        int sampled = 0;
        while (sampled < samplesTarget) {
            int dx = random.nextInt(2 * maxRadius + 1) - maxRadius;
            int dz = random.nextInt(2 * maxRadius + 1) - maxRadius;
            if (dx * dx + dz * dz <= radiusSq) {
                int blockX = centerBlockX + dx;
                int blockZ = centerBlockZ + dz;

                boolean explored = MapManager.getInstance().getColor(blockX, blockZ) != 0;

                // If already explored and we do not want to rescan, skip the expensive scan entirely
                if (explored && !MapConfig.alwaysRescanExplored) {
                    sampled++;
                    continue;
                }

                // If the selected point is already explored, aggressively search
                // up to 10 times to find an unexplored block nearby. This fills the dithered
                // holes quickly and guarantees unexplored black areas are scanned first.
                if (explored) {
                    for (int retry = 0; retry < 10; retry++) {
                        int rdx = random.nextInt(2 * maxRadius + 1) - maxRadius;
                        int rdz = random.nextInt(2 * maxRadius + 1) - maxRadius;
                        if (rdx * rdx + rdz * rdz <= radiusSq) {
                            int rx = centerBlockX + rdx;
                            int rz = centerBlockZ + rdz;
                            if (MapManager.getInstance().getColor(rx, rz) == 0) {
                                blockX = rx;
                                blockZ = rz;
                                explored = false;
                                break; // Found an unexplored spot!
                            }
                        }
                    }
                }

                // Only scan if unexplored, or if continuous rescan config is enabled
                if (!explored || MapConfig.alwaysRescanExplored) {
                    scanColumnIfLoaded(mc, blockX, blockZ);
                }
                sampled++;
            }
        }
    }

    private void scanColumnIfLoaded(Minecraft mc, int blockX, int blockZ) {
        if (mc.level.hasChunk(blockX >> 4, blockZ >> 4)) {
            int color = getColumnColor(mc.level, blockX, blockZ);
            // If the scanned color is 0, but the map already has a color, do not overwrite
            // it (prevents temporary black-outs on chunk load)
            if (color != 0 || MapManager.getInstance().getColor(blockX, blockZ) == 0) {
                MapManager.getInstance().setColor(blockX, blockZ, color);
            }
        }
    }

    /**
     * Re-scans a single column (x, z) in the world synchronously.
     */
    public void scanBlockColumn(Level level, BlockPos pos) {
        if (!level.isClientSide())
            return;
        try {
            int blockX = pos.getX();
            int blockZ = pos.getZ();
            int color = getColumnColor(level, blockX, blockZ);
            // If the scanned color is 0, but the map already has a color, do not overwrite
            // it
            if (color != 0 || MapManager.getInstance().getColor(blockX, blockZ) == 0) {
                MapManager.getInstance().setColor(blockX, blockZ, color);
            }
        } catch (Exception e) {
            LOGGER.error("Error scanning block column at " + pos, e);
        }
    }

    private int getHighestY(Level level, int blockX, int blockZ) {
        boolean isNether = level.dimensionType().hasCeiling();
        int minBuildHeight = level.getMinBuildHeight();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, 0, blockZ);

        if (isNether) {
            int startY = 120;
            boolean foundAir = false;
            for (int y = startY; y >= minBuildHeight; y--) {
                pos.setY(y);
                BlockState state = level.getBlockState(pos);
                if (!foundAir) {
                    if (state.isAir()) {
                        foundAir = true;
                    }
                } else {
                    MapColor mapColor = state.getMapColor(level, pos);
                    if (!state.isAir() && mapColor != MapColor.NONE) {
                        return y;
                    }
                }
            }
            return minBuildHeight;
        } else {
            int highestY = level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
            for (int y = highestY; y >= minBuildHeight; y--) {
                pos.setY(y);
                BlockState state = level.getBlockState(pos);
                MapColor mapColor = state.getMapColor(level, pos);
                if (!state.isAir() && mapColor != MapColor.NONE) {
                    return y;
                }
            }
            return minBuildHeight;
        }
    }

    private int getColumnColor(Level level, int blockX, int blockZ) {
        int currentY = getHighestY(level, blockX, blockZ);
        int northY = getHighestY(level, blockX, blockZ - 1);

        BlockPos pos = new BlockPos(blockX, currentY, blockZ);
        BlockState targetState = level.getBlockState(pos);

        // Vanilla-accurate water depth shading with transitional dithering (caro)
        // Transitions between shallow (1-2), medium (5-6) and deep (10+) using
        // checkerboard dither (3-4, 7-9)
        if (targetState.is(Blocks.WATER)) {
            int depth = 0;
            BlockPos.MutableBlockPos depthPos = new BlockPos.MutableBlockPos(blockX, currentY, blockZ);
            while (depth < 30) {
                int checkY = currentY - depth - 1;
                if (checkY < level.getMinBuildHeight())
                    break;
                depthPos.setY(checkY);
                if (!level.getBlockState(depthPos).is(Blocks.WATER))
                    break;
                depth++;
            }

            // Vanilla water MapColor base (col index 12 in MapColor = 0x3F76E4)
            int waterBase = Minecraft.getInstance().getBlockColors().getColor(targetState, level, pos, 0);
            if (waterBase == -1) {
                waterBase = MapColor.WATER.col;
            }
            int wr = (waterBase >> 16) & 0xFF;
            int wg = (waterBase >> 8) & 0xFF;
            int wb = waterBase & 0xFF;

            // Define three base shades for water depth (shallow, medium, deep)
            float shade;
            if (depth <= 3) {
                shade = 1.0f;  // Shallow water (0-3 blocks deep)
            } else if (depth <= 8) {
                shade = 0.85f; // Medium water (4-8 blocks deep)
            } else {
                shade = 0.70f; // Deep water (9+ blocks deep)
            }

            int r = Math.round(wr * shade);
            int g = Math.round(wg * shade);
            int b = Math.round(wb * shade);

            return 0xFF000000 | (b << 16) | (g << 8) | r; // ABGR (NativeImage format)
        }

        int rgb = Minecraft.getInstance().getBlockColors().getColor(targetState, level, pos, 0);
        boolean isLeaves = targetState.is(net.minecraft.tags.BlockTags.LEAVES);
        boolean isCherry = targetState.is(Blocks.CHERRY_LEAVES);
        boolean isGrass = targetState.is(Blocks.GRASS_BLOCK);
        MapColor mapColor = targetState.getMapColor(level, pos);
        boolean isWood = (mapColor == MapColor.WOOD) || targetState.is(net.minecraft.tags.BlockTags.PLANKS) || targetState.is(net.minecraft.tags.BlockTags.LOGS);
        if (rgb == -1) {
            if (mapColor == MapColor.NONE) {
                return 0; // Transparent
            }
            rgb = mapColor.col;
            if (isLeaves || isWood) {
                rgb = makeColorRich(rgb, isLeaves, isCherry, isGrass, isWood);
            }
        } else {
            rgb = makeColorRich(rgb, isLeaves, isCherry, isGrass, isWood);
        }

        // Convert MapColor RGB to ABGR for NativeImage
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;

        // Apply slope shading based on Y difference to create a 3D topographic relief
        // effect
        float shade = 1.0f;
        if (currentY > northY) {
            // Slope rises to the south (brighter)
            shade = 1.08f + Math.min(0.12f, (currentY - northY) * 0.015f);
        } else if (currentY < northY) {
            // Slope drops to the south (darker)
            shade = 0.88f - Math.min(0.12f, (northY - currentY) * 0.015f);
        }

        // Procedural micro-texture noise for organic feel (stable and fast coordinate hash)
        long hash = ((long) blockX * 312251L) ^ ((long) blockZ * 4390321L);
        hash = (hash ^ (hash >>> 16)) * 0x85ebca6bL;
        hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35L;
        float noise = (float) (hash & 0xFFFF) / 65535.0f;
        float noiseVal = noise * 2.0f - 1.0f; // -1.0 to 1.0

        float variation = 0.0f;
        if (isLeaves) {
            variation = 0.07f * noiseVal; // Speckled leaves foliage noise (+/- 7%)
        } else if (targetState.is(Blocks.GRASS_BLOCK) || targetState.is(Blocks.DIRT) || targetState.is(Blocks.SAND) || targetState.is(Blocks.GRAVEL)) {
            variation = 0.025f * noiseVal; // Subtle ground noise (+/- 2.5%)
        }
        
        shade *= (1.0f + variation);

        red = Math.max(0, Math.min(255, (int) (red * shade)));
        green = Math.max(0, Math.min(255, (int) (green * shade)));
        blue = Math.max(0, Math.min(255, (int) (blue * shade)));

        // NativeImage uses ABGR: (0xFF << 24) | (blue << 16) | (green << 8) | red
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    /**
     * Scans a circular area around the player synchronously on the main thread.
     * This creates a beautiful, progressive, block-by-block circular reveal.
     */
    public void scanAroundPlayer(Minecraft mc, int radius) {
        if (mc.level == null || mc.player == null)
            return;

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        int centerBlockX = (int) Math.floor(px);
        int centerBlockZ = (int) Math.floor(pz);
        int radiusSq = radius * radius;

        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz <= radiusSq) {
                    int blockX = centerBlockX + dx;
                    int blockZ = centerBlockZ + dz;

                    // Only scan columns in loaded chunks to prevent triggering chunk load lag
                    if (mc.level.hasChunk(blockX >> 4, blockZ >> 4)) {
                        int color = getColumnColor(mc.level, blockX, blockZ);
                        // If the scanned color is 0, but the map already has a color, do not overwrite
                        // it
                        if (color != 0 || MapManager.getInstance().getColor(blockX, blockZ) == 0) {
                            MapManager.getInstance().setColor(blockX, blockZ, color);
                        }
                    }
                }
            }
        }
    }

    private int makeColorRich(int rgb, boolean isLeaves, boolean isCherry, boolean isGrass, boolean isWood) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        float rf = r / 255.0f;
        float gf = g / 255.0f;
        float bf = b / 255.0f;

        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float delta = max - min;

        float h = 0.0f;
        float s = 0.0f;
        float v = max; // Brightness

        if (max != 0.0f) {
            s = delta / max;
        }

        if (delta != 0.0f) {
            if (rf == max) {
                h = (gf - bf) / delta;
            } else if (gf == max) {
                h = 2.0f + (bf - rf) / delta;
            } else {
                h = 4.0f + (rf - gf) / delta;
            }
            h *= 60.0f;
            if (h < 0.0f) {
                h += 360.0f;
            }
        }

        // Apply contrast/richness boost
        if (isGrass) {
            s = Math.min(1.0f, s * 1.05f); // Very gentle boost to keep grass looking natural (no neon green)
            v = v * 0.68f; // Darken slightly to match actual in-game grass shading
        } else {
            s = Math.min(1.0f, s * 1.35f); // Standard 35% saturation increase
            if (isCherry) {
                v = v * 0.95f; // Keep cherry blossoms bright pink and beautiful!
            } else if (isLeaves) {
                v = v * 0.52f; // Darken leaves even more (48% reduction) for tree contrast
            } else if (isWood) {
                v = v * 0.62f; // Darken wood deeper (38% reduction) so it stands out as timber
            } else {
                v = v * 0.75f; // Standard 25% reduction
            }
        }

        // Convert HSB back to RGB
        float hScaled = h / 60.0f;
        int i = (int) Math.floor(hScaled);
        float f = hScaled - i;
        float p = v * (1.0f - s);
        float q = v * (1.0f - s * f);
        float t = v * (1.0f - s * (1.0f - f));

        float rout = 0, gout = 0, bout = 0;
        switch (i % 6) {
            case 0: rout = v; gout = t; bout = p; break;
            case 1: rout = q; gout = v; bout = p; break;
            case 2: rout = p; gout = v; bout = t; break;
            case 3: rout = p; gout = q; bout = v; break;
            case 4: rout = t; gout = p; bout = v; break;
            case 5: rout = v; gout = p; bout = q; break;
        }

        int routInt = Math.max(0, Math.min(255, Math.round(rout * 255.0f)));
        int goutInt = Math.max(0, Math.min(255, Math.round(gout * 255.0f)));
        int boutInt = Math.max(0, Math.min(255, Math.round(bout * 255.0f)));

        return (routInt << 16) | (goutInt << 8) | boutInt;
    }
}
