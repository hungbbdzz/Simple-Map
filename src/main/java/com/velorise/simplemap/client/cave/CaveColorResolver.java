package com.velorise.simplemap.client.cave;

import com.velorise.simplemap.client.BiomeColors;
import com.velorise.simplemap.client.BlockTintPolicy;
import com.velorise.simplemap.client.MapConfig;
import com.velorise.simplemap.client.MapTextureManager;
import com.velorise.simplemap.client.MapVisualClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/** Converts cave floor materials, biome tint and fluids into persistent ABGR colour. */
public final class CaveColorResolver {
    private static final CaveColorResolver INSTANCE = new CaveColorResolver();
    private final MapVisualClassifier visualClassifier = MapVisualClassifier.getInstance();

    private CaveColorResolver() {
    }

    public static CaveColorResolver getInstance() {
        return INSTANCE;
    }

    /** Legacy archive path. Dense display tiles use {@link #resolveDense}. */
    public int resolve(Level level, BlockPos.MutableBlockPos floorPos,
            BlockState floorState, int referenceY, boolean fullView,
            int waterTopY, int waterDepth) {
        int base = blockColor(level, floorPos, floorState, true);
        if (base == 0) return 0;
        if (waterDepth > 0) {
            int floorY = floorPos.getY();
            floorPos.setY(waterTopY);
            base = waterOverlay(level, floorPos, base, waterDepth);
            floorPos.setY(floorY);
        }
        int light = Math.max(floorState.getLightEmission(),
                level.getBrightness(LightLayer.BLOCK, floorPos));
        int center = fullView
                ? (level.getMinBuildHeight() + level.getMaxBuildHeight() - 1) / 2
                : referenceY;
        int verticalOffset = Math.round((floorPos.getY() - center) / 8.0f);
        return lighting(base, light, verticalOffset);
    }

    /**
     * Dense display path. Lighting and vertical depth are deliberately deferred to
     * {@link CavePageStyler}, like Xaero's MapPixel pipeline. This keeps the cached
     * material/tint colour reusable when the height or lighting style changes.
     */
    public int resolveDense(Level level, BlockPos.MutableBlockPos floorPos,
            BlockState floorState, int waterTopY, int waterDepth) {
        int base = blockColor(level, floorPos, floorState, false);
        if (base == 0) return 0;
        if (waterDepth > 0) {
            int floorY = floorPos.getY();
            floorPos.setY(waterTopY);
            base = waterOverlay(level, floorPos, base, waterDepth);
            floorPos.setY(floorY);
        }
        return base;
    }

    /** Legacy world-save archive path. */
    public int resolveOffline(BlockState state, int floorY, int minimumY,
            int maximumY, int waterDepth) {
        int base = offlineBlockColor(state, null, true);
        if (base == 0) return 0;
        if (waterDepth > 0) base = offlineWaterOverlay(base, waterDepth, null);
        int light = state.getLightEmission();
        int center = minimumY + Math.max(1, maximumY - minimumY) / 2;
        int verticalOffset = Math.round((floorY - center) / 8.0f);
        int lit = lighting(base, Math.max(9, light), verticalOffset);
        return liftForOffline(lit);
    }

    /** Dense world-save path with biome-aware grass, foliage and water tint. */
    public int resolveDenseOffline(BlockState state, Biome biome, int waterDepth) {
        int base = offlineBlockColor(state, biome, false);
        if (base == 0) return 0;
        return waterDepth > 0 ? offlineWaterOverlay(base, waterDepth, biome) : base;
    }

    public int resolveOfflineFluid(BlockState state, int floorY,
            int minimumY, int maximumY) {
        return resolveOffline(state, floorY, minimumY, maximumY, 0);
    }

    public int resolveDenseOfflineFluid(BlockState state, Biome biome) {
        if (state.getFluidState().is(FluidTags.WATER)) {
            int waterRgb = MapColor.WATER.col;
            if (biome != null) {
                try {
                    waterRgb = BiomeColors.getWaterColor(biome) & 0x00FFFFFF;
                } catch (Throwable ignored) {
                }
            }
            return rgbToAbgr(waterRgb);
        }
        return offlineBlockColor(state, biome, false);
    }

    /** Legacy fluid path. */
    public int resolveFluid(Level level, BlockPos.MutableBlockPos pos,
            BlockState state, int referenceY, boolean fullView) {
        int base = blockColor(level, pos, state, true);
        if (base == 0) return 0;
        int light = Math.max(state.getLightEmission(), level.getBrightness(LightLayer.BLOCK, pos));
        int center = fullView
                ? (level.getMinBuildHeight() + level.getMaxBuildHeight() - 1) / 2
                : referenceY;
        return lighting(base, light, Math.round((pos.getY() - center) / 8.0f));
    }

    public int resolveDenseFluid(Level level, BlockPos.MutableBlockPos pos,
            BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER)) {
            int waterRgb = -1;
            try {
                waterRgb = Minecraft.getInstance().getBlockColors()
                        .getColor(state, level, pos, 0);
            } catch (Throwable ignored) {
            }
            if (waterRgb == -1) {
                try {
                    waterRgb = BiomeColors.getWaterColor(
                            level.getBiome(pos).value()) & 0x00FFFFFF;
                } catch (Throwable ignored) {
                    waterRgb = MapColor.WATER.col;
                }
            }
            return rgbToAbgr(waterRgb);
        }
        return blockColor(level, pos, state, false);
    }

    private int blockColor(Level level, BlockPos pos, BlockState state,
            boolean includeEmissionBoost) {
        MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
        String blockId = visual.blockId();
        Integer override = MapConfig.blockColorOverrides.get(blockId);
        if (override != null) return argbToAbgr(override);

        MapTextureManager textures = MapTextureManager.getInstance();
        int rgb = 0;
        if (MapConfig.blockColourMode == 0) {
            int sampled = textures.resolveBlockColor(blockId, 0);
            if (sampled != 0 && sampled != 0xFFFFFFFF) rgb = sampled & 0x00FFFFFF;
            if (visual.fixedTextureColor() && rgb == 0) rgb = 0xE0A1B8;

            if (!visual.fixedTextureColor()) {
                BlockTintPolicy policy = visualClassifier.tintPolicy(state);
                if (policy != BlockTintPolicy.NONE) {
                    int tint = liveTint(level, pos, state);
                    if (tint != -1) {
                        if (rgb == 0) rgb = tint & 0x00FFFFFF;
                        else rgb = applyBiomeTint(
                                0xFF000000 | rgb, 0xFF000000 | (tint & 0x00FFFFFF),
                                policy == BlockTintPolicy.GRASS ? 0.90f : 0.95f)
                                & 0x00FFFFFF;
                    }
                }
            }
        } else {
            int tint = liveTint(level, pos, state);
            if (tint != -1) rgb = tint & 0x00FFFFFF;
        }

        if (rgb == 0) {
            try {
                MapColor mapColor = state.getMapColor(level, pos);
                if (mapColor != MapColor.NONE) rgb = mapColor.col & 0x00FFFFFF;
            } catch (Throwable ignored) {
            }
        }
        if (rgb == 0) {
            int sampled = textures.resolveBlockColor(blockId, MapConfig.blockColourMode);
            if (sampled != 0 && sampled != 0xFFFFFFFF) rgb = sampled & 0x00FFFFFF;
        }
        if (rgb == 0) rgb = fallbackColor(state);

        if (MapConfig.blockColourMode == 0) {
            boolean leaves = visual.leaves();
            boolean cherry = visual.fixedTextureColor();
            boolean grass = visual.grass();
            MapColor mapColor;
            try {
                mapColor = state.getMapColor(level, pos);
            } catch (Throwable ignored) {
                mapColor = MapColor.NONE;
            }
            boolean wood = mapColor == MapColor.WOOD || visual.wood();
            rgb = enrich(rgb, leaves, cherry, grass, wood);
        }

        if (includeEmissionBoost && state.getLightEmission() > 0) {
            rgb = boostEmission(rgb, state.getLightEmission());
        }
        return rgbToAbgr(rgb);
    }

    private int offlineBlockColor(BlockState state, Biome biome,
            boolean includeEmissionBoost) {
        MapVisualClassifier.VisualInfo visual = visualClassifier.info(state);
        String blockId = visual.blockId();
        Integer override = MapConfig.blockColorOverrides.get(blockId);
        if (override != null) return argbToAbgr(override);

        MapTextureManager textures = MapTextureManager.getInstance();
        int rgb = 0;
        Integer cached = textures.getBlockColor(blockId);
        if (cached != null && cached != 0 && cached != 0xFFFFFFFF) {
            rgb = cached & 0x00FFFFFF;
        }
        if (visual.fixedTextureColor() && rgb == 0) rgb = 0xE0A1B8;

        if (rgb == 0) {
            try {
                MapColor mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (mapColor != MapColor.NONE) rgb = mapColor.col & 0x00FFFFFF;
            } catch (Throwable ignored) {
            }
        }
        if (rgb == 0) rgb = fallbackColor(state);

        if (biome != null && !visual.fixedTextureColor()) {
            BlockTintPolicy policy = visualClassifier.tintPolicy(state);
            int tint = biomeTint(policy, biome);
            if (tint != -1) {
                rgb = applyBiomeTint(
                        0xFF000000 | rgb, tint,
                        policy == BlockTintPolicy.GRASS ? 0.90f : 0.95f)
                        & 0x00FFFFFF;
            }
        }

        if (MapConfig.blockColourMode == 0) {
            boolean leaves = visual.leaves();
            boolean cherry = visual.fixedTextureColor();
            boolean grass = visual.grass();
            MapColor mapColor;
            try {
                mapColor = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (Throwable ignored) {
                mapColor = MapColor.NONE;
            }
            boolean wood = mapColor == MapColor.WOOD || visual.wood();
            rgb = enrich(rgb, leaves, cherry, grass, wood);
        }
        if (includeEmissionBoost && state.getLightEmission() > 0) {
            rgb = boostEmission(rgb, state.getLightEmission());
        }
        return rgbToAbgr(rgb);
    }

    private static int liveTint(Level level, BlockPos pos, BlockState state) {
        try {
            return Minecraft.getInstance().getBlockColors().getColor(state, level, pos, 0);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int biomeTint(BlockTintPolicy policy, Biome biome) {
        try {
            return switch (policy) {
                case FOLIAGE -> BiomeColors.getFoliageColor(biome);
                case GRASS -> BiomeColors.getGrassColor(biome);
                case SPRUCE -> 0xFF619961;
                case BIRCH -> 0xFF80A755;
                case NONE -> -1;
            };
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int offlineWaterOverlay(int floorAbgr, int depth, Biome biome) {
        int waterRgb = MapColor.WATER.col;
        if (biome != null) {
            try {
                waterRgb = BiomeColors.getWaterColor(biome) & 0x00FFFFFF;
            } catch (Throwable ignored) {
            }
        }
        return blendWater(floorAbgr, waterRgb, depth, false);
    }

    private static int liftForOffline(int abgr) {
        int red = abgr & 0xFF;
        int green = (abgr >>> 8) & 0xFF;
        int blue = (abgr >>> 16) & 0xFF;
        red = clamp(Math.round(red * 1.08f + 8.0f));
        green = clamp(Math.round(green * 1.08f + 8.0f));
        blue = clamp(Math.round(blue * 1.08f + 8.0f));
        return (abgr & 0xFF000000) | (blue << 16) | (green << 8) | red;
    }

    private int waterOverlay(Level level, BlockPos waterPos, int floorAbgr, int depth) {
        BlockState waterState = level.getBlockState(waterPos);
        int waterRgb = -1;
        try {
            waterRgb = Minecraft.getInstance().getBlockColors()
                    .getColor(waterState, level, waterPos, 0);
        } catch (Throwable ignored) {
        }
        if (waterRgb == -1) waterRgb = MapColor.WATER.col;
        return blendWater(floorAbgr, waterRgb, depth, true);
    }

    private static int blendWater(int floorAbgr, int waterRgb, int depth,
            boolean attenuate) {
        float amount = Math.min(0.82f, 0.34f + Math.max(1, depth) * 0.055f);
        float attenuation = attenuate ? Math.max(0.72f,
                (float) Math.pow(0.982f, Math.max(0, depth - 2))) : 1.0f;
        int floorRed = floorAbgr & 0xFF;
        int floorGreen = (floorAbgr >>> 8) & 0xFF;
        int floorBlue = (floorAbgr >>> 16) & 0xFF;
        int waterRed = (waterRgb >>> 16) & 0xFF;
        int waterGreen = (waterRgb >>> 8) & 0xFF;
        int waterBlue = waterRgb & 0xFF;

        int red = clamp(Math.round((floorRed + (waterRed - floorRed) * amount) * attenuation));
        int green = clamp(Math.round((floorGreen + (waterGreen - floorGreen) * amount) * attenuation));
        int blue = clamp(Math.round((floorBlue + (waterBlue - floorBlue) * amount) * attenuation));
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private int lighting(int abgr, int light, int verticalOffset) {
        float normalized = Math.max(0.0f, Math.min(1.0f, light / 15.0f));
        float heightShade = Math.max(0.90f,
                Math.min(1.10f, 1.0f + verticalOffset * 0.012f));
        float brightness = (0.82f + 0.18f * (float) Math.pow(normalized, 0.85f))
                * heightShade;
        brightness = Math.max(0.78f, Math.min(1.08f, brightness));
        int red = Math.round((abgr & 0xFF) * brightness);
        int green = Math.round(((abgr >>> 8) & 0xFF) * brightness);
        int blue = Math.round(((abgr >>> 16) & 0xFF) * brightness);
        if (light > 6) {
            float warmth = Math.min(0.45f, ((light - 6) / 9.0f) * 0.45f);
            red = Math.round(red + (255 - red) * warmth);
            green = Math.round(green + (185 - green) * warmth);
            blue = Math.round(blue + (80 - blue) * warmth);
        }
        return 0xFF000000 | (clamp(blue) << 16) | (clamp(green) << 8) | clamp(red);
    }

    private static int fallbackColor(BlockState state) {
        if (state.is(Blocks.CHERRY_LEAVES)) return 0xE0A1B8;
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.LAVA)) return 0xFF6A00;
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return 0x3F76E4;
        return 0x7F8588;
    }

    private static int boostEmission(int rgb, int light) {
        float boost = 1.0f + 0.25f * light / 15.0f;
        int red = clamp(Math.round(((rgb >>> 16) & 0xFF) * boost));
        int green = clamp(Math.round(((rgb >>> 8) & 0xFF) * boost));
        int blue = clamp(Math.round((rgb & 0xFF) * boost));
        return (red << 16) | (green << 8) | blue;
    }

    private static int enrich(int rgb, boolean leaves, boolean cherry,
            boolean grass, boolean wood) {
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        float luma = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
        float saturation = cherry ? 1.05f : (leaves || grass ? 1.10f : 1.06f);
        float brightness = cherry ? 0.98f : (leaves ? 0.96f : (grass ? 0.98f : (wood ? 0.94f : 0.98f)));
        red = clamp(Math.round((luma + (red - luma) * saturation) * brightness));
        green = clamp(Math.round((luma + (green - luma) * saturation) * brightness));
        blue = clamp(Math.round((luma + (blue - luma) * saturation) * brightness));
        return (red << 16) | (green << 8) | blue;
    }

    private static int applyBiomeTint(int textureArgb, int tintArgb, float strength) {
        float amount = Math.max(0.0f, Math.min(1.0f, strength));
        int tr = (textureArgb >>> 16) & 0xFF;
        int tg = (textureArgb >>> 8) & 0xFF;
        int tb = textureArgb & 0xFF;
        int br = (tintArgb >>> 16) & 0xFF;
        int bg = (tintArgb >>> 8) & 0xFF;
        int bb = tintArgb & 0xFF;
        int multipliedR = tr * br / 255;
        int multipliedG = tg * bg / 255;
        int multipliedB = tb * bb / 255;
        int red = clamp(Math.round(tr + (multipliedR - tr) * amount));
        int green = clamp(Math.round(tg + (multipliedG - tg) * amount));
        int blue = clamp(Math.round(tb + (multipliedB - tb) * amount));
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int rgbToAbgr(int rgb) {
        int red = (rgb >>> 16) & 0xFF;
        int green = (rgb >>> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return 0xFF000000 | (blue << 16) | (green << 8) | red;
    }

    private static int argbToAbgr(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
