package com.velorise.simplemap.client.color;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager;
import com.velorise.simplemap.client.cave.*;
import com.velorise.simplemap.client.cave.CaveTextureManager.CaveMapManager.CaveStateClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.Property;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.server.packs.resources.Resource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import com.velorise.simplemap.client.MapManager.MapConfig;

import com.velorise.simplemap.client.*;
import com.velorise.simplemap.client.session.MapSessionManager;
import net.minecraft.client.Minecraft;

import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/** CPU-only conversion of packed map pixels to final ABGR texture pixels. */
public final class SurfaceColorizer {
    private static final int SIZE = 512;
    private static final int VANILLA_WATER = 0xFF3F76E4;
    /** Opaque colour for a mapped air-only/void column. */
    private static final int MAPPED_VOID_ABGR = 0xFF17000A;

    private SurfaceColorizer() {
    }

    public static int[] colorize(long[] pixels, int[] tints,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks,
            int colourMode,
            boolean showFlowers,
            int terrainSlopes,
            int profile) {
        return colorize(pixels, tints, biomePalette, blockPalette, biomeLookup, blockColors,
                tintPolicies, tintDisabledBlocks, colourMode, showFlowers, terrainSlopes, profile, () -> true);
    }

    public static int[] colorize(long[] pixels, int[] tints,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks,
            int colourMode,
            boolean showFlowers,
            int terrainSlopes,
            int profile,
            BooleanSupplier stillValid) {
        if (tints == null || tints.length != pixels.length) {
            throw new IllegalArgumentException("Surface tint snapshot must match pixel snapshot");
        }
        int[] output = new int[SIZE * SIZE];
        for (int pz = 0; pz < SIZE; pz++) {
            if ((pz & 31) == 0 && !stillValid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Stale SimpleMap texture job");
            }
            for (int px = 0; px < SIZE; px++) {
                int index = pz * SIZE + px;
                long packed = pixels[index];
                if (MapBlockData.isEmpty(packed))
                    continue;
                if (MapBlockData.isFlower(packed) && !showFlowers)
                    continue;

                int baseColor = resolveBaseColor(packed, tints[index], blockPalette, blockColors, tintPolicies,
                        tintDisabledBlocks, biomeLookup, pixels, px, pz,
                        SIZE, SIZE, colourMode);
                if (baseColor == 0)
                    continue;

                int red = (baseColor >>> 16) & 0xFF;
                int green = (baseColor >>> 8) & 0xFF;
                int blue = baseColor & 0xFF;
                int reliefY = MapBlockData.reliefY(packed);
                boolean fluidPixel = MapBlockData.isFluid(packed);

                /*
                 * Water depth and bottom colour are already handled in resolveBaseColor().
                 * Applying terrain relief to floorY made a flat ocean inherit seabed steps
                 * and region/chunk boundaries as false rectangular shadows. Fluids therefore
                 * keep a flat lighting layer; land and cave terrain retain full relief.
                 */
                /*
                 * Water is already a final translucent overlay over the stored floor
                 * colour. Do not apply a second depth multiplier here: PASS88 shaded
                 * the same depth twice and turned one-block depth changes into the
                 * horizontal/vertical dash field visible in warm oceans.
                 */
                float shade = fluidPixel ? 1.0f
                        : calculateShade(pixels, px, pz, SIZE, SIZE,
                                reliefY, terrainSlopes);
                if (!fluidPixel) {
                    shade *= 1.0f + microNoise(index, packed, reliefY);
                }
                red = clamp(Math.round(red * shade));
                green = clamp(Math.round(green * shade));
                blue = clamp(Math.round(blue * shade));

                int argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
                if (colourMode == 0) {
                    argb = applyAccurateFilter(argb,
                            MapBlockData.isLeaves(packed),
                            MapBlockData.isFluid(packed));
                }
                int abgr = 0xFF000000 | ((argb & 0xFF) << 16)
                        | (argb & 0x0000FF00)
                        | ((argb >>> 16) & 0xFF);
                output[index] = MapColorProfile.apply(abgr, profile);
            }
        }
        return output;
    }

    /**
     * Colorizes one exact 64x64 leaf from a compact page snapshot. The snapshot
     * contains a two-column halo on every side, including data remapped from
     * adjacent 512x512 regions, so biome tint, slope and local depth remain
     * continuous without cloning the full parent region.
     */
    public static int[] colorizePageWindow(long[] pixels, int[] tints,
            int stride, int halo, int worldPageStartX, int worldPageStartZ,
            List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks,
            int colourMode,
            boolean showFlowers,
            int terrainSlopes,
            int profile,
            BooleanSupplier stillValid) {
        return colorizePageWindow(pixels, tints, null, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, profile,
                MapPageLayout.FULL_SUBTILE_MASK, stillValid);
    }

    public static int[] colorizePageWindow(long[] pixels, int[] tints,
            byte[] known, int stride, int halo, int worldPageStartX,
            int worldPageStartZ, List<String> biomePalette,
            List<String> blockPalette, IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, int profile,
            BooleanSupplier stillValid) {
        return colorizePageWindow(pixels, tints, known, stride, halo,
                worldPageStartX, worldPageStartZ, biomePalette, blockPalette,
                biomeLookup, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, profile,
                MapPageLayout.FULL_SUBTILE_MASK, stillValid);
    }

    /**
     * Styles only the requested Minecraft-chunk-sized parts of an exact page.
     * The returned arrays retain page coordinates so render publication can copy
     * the changed 16x16 rectangles directly into an existing atlas leaf.
     */
    public static int[] colorizePageWindow(long[] pixels, int[] tints,
            byte[] known, int stride, int halo, int worldPageStartX,
            int worldPageStartZ, List<String> biomePalette,
            List<String> blockPalette,
            IntFunction<Biome> biomeLookup,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks,
            int colourMode,
            boolean showFlowers,
            int terrainSlopes,
            int profile,
            int requestedSubtiles,
            BooleanSupplier stillValid) {
        int pageSize = MapPageLayout.PAGE_SIZE;
        if (stride < pageSize + halo * 2
                || pixels == null || pixels.length != stride * stride
                || tints == null || tints.length != pixels.length) {
            throw new IllegalArgumentException("Invalid compact surface page snapshot");
        }

        int[] output = new int[pageSize * pageSize];
        int updateMask = requestedSubtiles & MapPageLayout.FULL_SUBTILE_MASK;
        for (int localZ = 0; localZ < pageSize; localZ++) {
            if ((localZ & 15) == 0 && !stillValid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("Stale SimpleMap page job");
            }
            int subtileRow = (localZ / MapPageLayout.SUBTILE_SIZE)
                    * MapPageLayout.SUBTILES_PER_PAGE;
            int pz = halo + localZ;
            int pageRow = localZ * pageSize;
            for (int localX = 0; localX < pageSize; localX++) {
                int subtile = subtileRow
                        + localX / MapPageLayout.SUBTILE_SIZE;
                if ((updateMask & (1 << subtile)) == 0) continue;
                int px = halo + localX;
                int index = pz * stride + px;
                long packed = pixels[index];
                if (MapBlockData.isEmpty(packed)) {
                    // Distinguishes mapped void from undiscovered map data.
                    // SimpleMap already carries the same distinction in the source
                    // coverage byte; preserve it in the styled texture instead of
                    // making known End void transparent like an unvisited chunk.
                    if (known != null && known.length == pixels.length
                            && known[index] != 0) {
                        output[pageRow + localX] = MAPPED_VOID_ABGR;
                    }
                    continue;
                }
                if (MapBlockData.isFlower(packed) && !showFlowers) continue;

                int baseColor = resolveBaseColor(packed, tints[index], blockPalette,
                        blockColors, tintPolicies, tintDisabledBlocks, biomeLookup,
                        pixels, px, pz, stride, stride, colourMode);
                if (baseColor == 0) continue;

                int red = (baseColor >>> 16) & 0xFF;
                int green = (baseColor >>> 8) & 0xFF;
                int blue = baseColor & 0xFF;
                int reliefY = MapBlockData.reliefY(packed);
                boolean fluidPixel = MapBlockData.isFluid(packed);
                int worldX = worldPageStartX + localX;
                int worldZ = worldPageStartZ + localZ;
                float shade = fluidPixel ? 1.0f
                        : calculateShade(pixels, px, pz, stride, stride,
                                reliefY, terrainSlopes);
                int regionLocalX = Math.floorMod(worldX, SIZE);
                int regionLocalZ = Math.floorMod(worldZ, SIZE);
                if (!fluidPixel) {
                    shade *= 1.0f + microNoise(regionLocalZ * SIZE + regionLocalX,
                            packed, reliefY);
                }
                red = clamp(Math.round(red * shade));
                green = clamp(Math.round(green * shade));
                blue = clamp(Math.round(blue * shade));

                int argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
                if (colourMode == 0) {
                    argb = applyAccurateFilter(argb,
                            MapBlockData.isLeaves(packed),
                            MapBlockData.isFluid(packed));
                }
                int abgr = 0xFF000000 | ((argb & 0xFF) << 16)
                        | (argb & 0x0000FF00)
                        | ((argb >>> 16) & 0xFF);
                output[pageRow + localX] = MapColorProfile.apply(abgr, profile);
            }
        }
        return output;
    }

    /**
     * Styles a regularly sampled region grid for coarse Surface LOD. Unlike the
     * exact-page helper, neighbouring samples may represent several world blocks.
     * The returned image keeps the source-grid density; the caller performs the
     * final linear reduction to its branch texture.
     */
    public static int[] colorizeSampleWindow(long[] pixels, int[] tints,
            byte[] known, int stride, int halo, int outputSize, int worldStartX,
            int worldStartZ, int worldSampleStep,
            List<String> biomePalette, List<String> blockPalette,
            IntFunction<Biome> biomeLookup, Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, int profile,
            BooleanSupplier stillValid) {
        if (outputSize <= 0 || worldSampleStep <= 0
                || stride < outputSize + halo * 2
                || pixels == null || pixels.length != stride * stride
                || tints == null || tints.length != pixels.length) {
            throw new IllegalArgumentException("Invalid sampled surface window");
        }
        int[] output = new int[outputSize * outputSize];
        for (int localZ = 0; localZ < outputSize; localZ++) {
            if ((localZ & 15) == 0 && !stillValid.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException(
                        "Stale SimpleMap sampled Surface job");
            }
            int pz = halo + localZ;
            int outputRow = localZ * outputSize;
            for (int localX = 0; localX < outputSize; localX++) {
                int px = halo + localX;
                int index = pz * stride + px;
                long packed = pixels[index];
                if (MapBlockData.isEmpty(packed)) {
                    if (known != null && known.length == pixels.length
                            && known[index] != 0) {
                        output[outputRow + localX] = MAPPED_VOID_ABGR;
                    }
                    continue;
                }
                if (MapBlockData.isFlower(packed) && !showFlowers) continue;
                int baseColor = resolveBaseColor(packed, tints[index],
                        blockPalette, blockColors, tintPolicies,
                        tintDisabledBlocks, biomeLookup, pixels, px, pz,
                        stride, stride, colourMode);
                if (baseColor == 0) continue;
                int red = (baseColor >>> 16) & 0xFF;
                int green = (baseColor >>> 8) & 0xFF;
                int blue = baseColor & 0xFF;
                int reliefY = MapBlockData.reliefY(packed);
                boolean fluidPixel = MapBlockData.isFluid(packed);
                float shade = fluidPixel ? 1.0f
                        : calculateShade(pixels, px, pz, stride, stride,
                                reliefY, terrainSlopes);
                int worldX = worldStartX + localX * worldSampleStep;
                int worldZ = worldStartZ + localZ * worldSampleStep;
                if (!fluidPixel) {
                    int regionLocalX = Math.floorMod(worldX, SIZE);
                    int regionLocalZ = Math.floorMod(worldZ, SIZE);
                    shade *= 1.0f + microNoise(
                            regionLocalZ * SIZE + regionLocalX,
                            packed, reliefY);
                }
                red = clamp(Math.round(red * shade));
                green = clamp(Math.round(green * shade));
                blue = clamp(Math.round(blue * shade));
                int argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
                if (colourMode == 0) {
                    argb = applyAccurateFilter(argb,
                            MapBlockData.isLeaves(packed), fluidPixel);
                }
                int abgr = 0xFF000000 | ((argb & 0xFF) << 16)
                        | (argb & 0x0000FF00)
                        | ((argb >>> 16) & 0xFF);
                output[outputRow + localX] = MapColorProfile.apply(abgr,
                        profile);
            }
        }
        return output;
    }

    private static int resolveBaseColor(long packed, int storedTint,
            List<String> blockPalette,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks,
            IntFunction<Biome> biomeLookup,
            long[] allPixels,
            int px, int pz,
            int sampleWidth, int sampleHeight,
            int colourMode) {
        String blockName = blockId(packed, blockPalette);

        if (MapBlockData.isFluid(packed)) {
            // Fluid + glowing is the compact representation used for lava. Accurate
            // mode uses the sampled lava texture; the orange value is only a failure
            // fallback, not a normal hardcoded palette override.
            if (MapBlockData.isGlowing(packed)) {
                int sampledLava = blockName == null ? 0 : blockColors.getOrDefault(blockName, 0);
                return sampledLava != 0 ? sampledLava : 0xFFFF6A00;
            }

            /*
             * Never use the scan-time BlockColors result for water. Minecraft's
             * water provider performs biome blending against neighbouring chunks;
             * while a new chunk is arriving those neighbours can still be absent.
             * Persisting that transient result bakes an entire 16x16 chunk with a
             * different blue value and creates the rectangular bands seen on the
             * map. Rebuild water tint only from the region's stored biome IDs, which
             * are stable and are re-blended whenever adjacent map pixels arrive.
             */
            int waterTint = colourMode == 0
                    ? BiomeColors.blendWater(allPixels, biomeLookup, px, pz, sampleWidth, sampleHeight)
                    : VANILLA_WATER;
            if (waterTint == -1)
                waterTint = VANILLA_WATER;

            // For water pixels blockId points at the solid floor, not the water
            // block. This lets shallow water reveal sand/stone/grass instead of
            // becoming one flat blue sheet.
            /*
             * Accumulates one transparent-water overlay while walking the
             * liquid column and composites it over that column's final opaque
             * floor. Simple Map already stores that floor identity in the packed
             * water sample, so use it directly. A 5x5 depth/floor blur mixed
             * unrelated columns and amplified sparse Warm Ocean floor patterns into
             * long horizontal/vertical dashes.
             */
            String floorId = blockId(packed, blockPalette);
            int floorColor = floorId == null
                    ? 0xFF5E6B63
                    : blockColors.getOrDefault(floorId, 0xFF5E6B63);
            int waterDepth = Math.max(1, MapBlockData.waterDepth(packed));
            return composeWaterOverlay(waterTint, floorColor, waterDepth,
                    colourMode == 1);
        }

        int texture = blockName == null ? 0 : blockColors.getOrDefault(blockName, 0);

        MapVisualClassifier.VisualInfo visual = blockName == null
                ? null : MapVisualClassifier.getInstance().info(blockName);
        /* Fixed-colour foliage (vanilla cherry and modded equivalents classified
         * the same way) keeps its texture and never receives a biome multiplier. */
        if (visual != null && visual.fixedTextureColor()) {
            return texture != 0 ? texture : 0xFFE0A1B8;
        }

        BlockTintPolicy policy = blockName == null
                ? BlockTintPolicy.NONE
                : tintPolicies.getOrDefault(blockName,
                        visual == null ? BlockTintPolicy.NONE : visual.tintPolicy());
        boolean tintDisabled = blockName != null && tintDisabledBlocks.contains(blockName);

        /*
         * Never use a scan-time provider colour for biome-driven blocks. The live
         * BlockColors call is evaluated against whichever neighbouring chunks happen
         * to be loaded at capture time, so persisting it bakes one slightly different
         * green value into every 16x16 chunk. At close zoom those values become the
         * vertical/horizontal chunk grid visible across grass, leaves and water.
         *
         * Persists biome identity and evaluates tint while composing the map
         * tile against neighbouring map data. Do the equivalent here: standard
         * GRASS/FOLIAGE/SPRUCE/BIRCH policies always flow through BiomeBlend below.
         * A provider result remains authoritative only for policy NONE, which is the
         * custom/fixed tint case that cannot be reconstructed from biome identity.
         */
        if (colourMode == 0 && texture != 0 && !tintDisabled
                && usesStoredProviderTint(policy)) {
            if (SurfaceTintData.hasColor(storedTint)) {
                return applyBiomeTint(texture,
                        0xFF000000 | SurfaceTintData.rgb(storedTint), 1.0f);
            }
            if (storedTint == SurfaceTintData.NONE)
                return texture;
        }
        if (tintDisabled)
            return texture;

        if (MapBlockData.isLeaves(packed)) {
            if (texture == 0)
                texture = 0xFF71845F;
            if (colourMode != 0)
                return texture;
            return switch (policy) {
                case SPRUCE, BIRCH, FOLIAGE -> {
                    int tint = BiomeColors.blendFoliage(allPixels, biomeLookup, px, pz, sampleWidth, sampleHeight);
                    yield tint == -1 ? texture : applyBiomeTint(texture, tint, 0.95f);
                }
                case GRASS -> {
                    int tint = BiomeColors.blendGrass(allPixels, biomeLookup, px, pz, sampleWidth, sampleHeight);
                    yield tint == -1 ? texture : applyBiomeTint(texture, tint, 0.90f);
                }
                case NONE -> texture;
            };
        }

        if (blockName != null && (blockName.contains("glass") || blockName.contains("barrier"))) {
            return 0;
        }

        if (colourMode == 0 && texture != 0) {
            if (policy == BlockTintPolicy.GRASS) {
                int tint = BiomeColors.blendGrass(allPixels, biomeLookup, px, pz, sampleWidth, sampleHeight);
                return tint == -1 ? texture : applyBiomeTint(texture, tint, 0.90f);
            }
            if (policy == BlockTintPolicy.FOLIAGE) {
                int tint = BiomeColors.blendFoliage(allPixels, biomeLookup, px, pz, sampleWidth, sampleHeight);
                return tint == -1 ? texture : applyBiomeTint(texture, tint, 0.92f);
            }
        }

        return texture;
    }

    public static boolean usesStoredProviderTint(BlockTintPolicy policy) {
        return policy == null || policy == BlockTintPolicy.NONE;
    }

    /**
     * Strong relief intended to read cliffs, pits and stepped terrain at minimap
     * scale. Water uses its stored floorY, so a lake no longer looks like a raised
     * plateau merely because its surface is level with the shore.
     */
    private static float calculateShade(long[] pixels, int px, int pz,
            int width, int height, int centerY, int terrainSlopes) {
        if (terrainSlopes <= 0)
            return 1.0f;
        int north = neighborY(pixels, px, pz - 1, width, height, centerY);
        if (terrainSlopes == 1) {
            int delta = centerY - north;
            float stepShadow = delta < 0 ? Math.max(-0.34f, delta * 0.060f)
                    : Math.min(0.20f, delta * 0.040f);
            return clamp(1.0f + stepShadow, 0.58f, 1.28f);
        }

        int south = neighborY(pixels, px, pz + 1, width, height, centerY);
        int west = neighborY(pixels, px - 1, pz, width, height, centerY);
        int east = neighborY(pixels, px + 1, pz, width, height, centerY);

        float dx = clamp((west - east) * 0.5f, -16.0f, 16.0f);
        float dz = clamp((north - south) * 0.5f, -16.0f, 16.0f);
        float directional = dx * 0.045f + dz * 0.062f; // light from north-west

        int highest = Math.max(Math.max(north, south), Math.max(west, east));
        int lowest = Math.min(Math.min(north, south), Math.min(west, east));
        int dropBelowRim = Math.max(0, highest - centerY);
        int riseAboveNeighbours = Math.max(0, centerY - lowest);
        float pitShadow = Math.min(0.46f, dropBelowRim * 0.065f);
        float ridgeLight = Math.min(0.10f, riseAboveNeighbours * 0.018f);

        float roughness = Math.min(0.22f,
                (Math.abs(west - east) + Math.abs(north - south)) * 0.014f);
        /*
         * Keep depth shading local. The old 2/8/24-block samples treated tall
         * buildings as terrain occluders, so square structures such as End Cities
         * projected large rectangular halos onto otherwise flat ground. Immediate
         * gradients already describe cliffs and building edges; a restrained
         * two-block sample is enough to retain crater/ravine depth without creating
         * artificial structure-shaped shadows.
         */
        float depthOcclusion = localDepthOcclusion(pixels, px, pz, width, height, centerY);
        float shade = 1.0f + directional + ridgeLight - pitShadow - roughness - depthOcclusion;
        return clamp(shade, 0.42f, 1.28f);
    }

    private static float localDepthOcclusion(long[] pixels, int px, int pz,
            int width, int height, int centerY) {
        return Math.min(0.12f,
                depthAtRadius(pixels, px, pz, width, height, centerY, 2, 0.012f));
    }

    private static float depthAtRadius(long[] pixels, int px, int pz,
            int width, int height, int centerY, int radius, float weight) {
        int sum = Math.max(0, neighborY(pixels, px - radius, pz, width, height, centerY) - centerY)
                + Math.max(0, neighborY(pixels, px + radius, pz, width, height, centerY) - centerY)
                + Math.max(0, neighborY(pixels, px, pz - radius, width, height, centerY) - centerY)
                + Math.max(0, neighborY(pixels, px, pz + radius, width, height, centerY) - centerY)
                + Math.max(0, neighborY(pixels, px - radius, pz - radius, width, height, centerY) - centerY)
                + Math.max(0, neighborY(pixels, px + radius, pz - radius, width, height, centerY) - centerY)
                + Math.max(0, neighborY(pixels, px - radius, pz + radius, width, height, centerY) - centerY)
                + Math.max(0, neighborY(pixels, px + radius, pz + radius, width, height, centerY) - centerY);
        return Math.min(0.16f, (sum * 0.125f) * weight);
    }

    private static int neighborY(long[] pixels, int px, int pz,
            int width, int height, int fallback) {
        if (px < 0 || px >= width || pz < 0 || pz >= height)
            return fallback;
        long packed = pixels[pz * width + px];
        return MapBlockData.isEmpty(packed) ? fallback : MapBlockData.reliefY(packed);
    }

    /**
     * Restored from the supplied beta SimpleMap implementation, whose ocean
     * rendering is the known-good visual baseline. The newer retained renderer
     * should not reinterpret water as a literal stack of overlay records:
     * SimpleMap stores one compact column containing the biome-tinted liquid, the
     * opaque basin-floor block and the exact depth. Reuse the beta compositor on
     * that representation so shallow columns reveal their floor while increasing
     * depth smoothly dominates the floor with water and attenuates the result.
     */
    public static int composeWaterOverlay(int waterTint, int floorColor,
            int waterDepth, boolean vanillaMode) {
        int depth = Math.min(64, Math.max(1, waterDepth));
        float waterAmount = vanillaMode
                ? clamp01(0.42f + depth * 0.035f)
                : clamp01(0.25f + depth * 0.045f);
        waterAmount = Math.min(vanillaMode ? 0.94f : 0.92f, waterAmount);
        int mixed = mixRgb(floorColor, waterTint, waterAmount);
        float attenuation = (float) Math.pow(0.975, Math.max(0, depth - 2));
        attenuation = Math.max(0.46f, attenuation);
        return scaleRgb(mixed, attenuation);
    }

    /** Compatibility helper for tests/old call sites: one-block shallow water. */
    public static int composeWaterOverlay(int waterTint, int floorColor,
            boolean vanillaMode) {
        return composeWaterOverlay(waterTint, floorColor, 1, vanillaMode);
    }

    private static float microNoise(int linearIndex, long packed, int y) {
        if (MapBlockData.isFluid(packed)) return 0.0f;
        long hash = ((long) linearIndex * 312251L) ^ ((long) y * 4390321L);
        hash = (hash ^ (hash >>> 16)) * 0x85ebca6bL;
        hash = (hash ^ (hash >>> 13)) * 0xc2b2ae35L;
        float noise = ((hash & 0xFFFFL) / 65535.0f) * 2.0f - 1.0f;
        if (MapBlockData.isLeaves(packed))
            return noise * 0.075f;
        return noise * 0.022f;
    }

    /**
     * Mirrors Minecraft's tinted-face principle: sampled texture RGB is multiplied
     * by the biome tint and then blended by strength. Unlike luminance-normalized
     * tinting this allows vanilla foliage to become naturally dark and saturated.
     */
    public static int applyBiomeTint(int textureArgb, int tintArgb, float strength) {
        float baseR = ((textureArgb >>> 16) & 0xFF) / 255.0f;
        float baseG = ((textureArgb >>> 8) & 0xFF) / 255.0f;
        float baseB = (textureArgb & 0xFF) / 255.0f;
        float tintR = ((tintArgb >>> 16) & 0xFF) / 255.0f;
        float tintG = ((tintArgb >>> 8) & 0xFF) / 255.0f;
        float tintB = (tintArgb & 0xFF) / 255.0f;
        float amount = clamp(strength, 0.0f, 1.0f);

        float outR = baseR + (baseR * tintR - baseR) * amount;
        float outG = baseG + (baseG * tintG - baseG) * amount;
        float outB = baseB + (baseB * tintB - baseB) * amount;
        int r = clamp(Math.round(clamp01(outR) * 255.0f));
        int g = clamp(Math.round(clamp01(outG) * 255.0f));
        int b = clamp(Math.round(clamp01(outB) * 255.0f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mixRgb(int first, int second, float amount) {
        float inverse = 1.0f - amount;
        int r = clamp(Math.round(((first >>> 16) & 0xFF) * inverse
                + ((second >>> 16) & 0xFF) * amount));
        int g = clamp(Math.round(((first >>> 8) & 0xFF) * inverse
                + ((second >>> 8) & 0xFF) * amount));
        int b = clamp(Math.round((first & 0xFF) * inverse + (second & 0xFF) * amount));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int scaleRgb(int argb, float amount) {
        int r = clamp(Math.round(((argb >>> 16) & 0xFF) * amount));
        int g = clamp(Math.round(((argb >>> 8) & 0xFF) * amount));
        int b = clamp(Math.round((argb & 0xFF) * amount));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String blockId(long packed, List<String> palette) {
        int index = MapBlockData.blockId(packed) & 0xFFFF;
        return index == (MapBlockData.NO_BLOCK & 0xFFFF) || index >= palette.size()
                ? null
                : palette.get(index);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0.0f, 1.0f);
    }

    private static int applyAccurateFilter(int argb, boolean leaves, boolean fluid) {
        float red = ((argb >>> 16) & 0xFF) / 255.0f;
        float green = ((argb >>> 8) & 0xFF) / 255.0f;
        float blue = (argb & 0xFF) / 255.0f;

        float luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
        float saturation = fluid ? 1.00f : (leaves ? 1.04f : 1.02f);
        red = luminance + (red - luminance) * saturation;
        green = luminance + (green - luminance) * saturation;
        blue = luminance + (blue - luminance) * saturation;

        float gamma = fluid ? 1.04f : (leaves ? 1.14f : 1.10f);
        red = (float) Math.pow(clamp01(red), gamma);
        green = (float) Math.pow(clamp01(green), gamma);
        blue = (float) Math.pow(clamp01(blue), gamma);

        float contrast = fluid ? 1.02f : (leaves ? 1.08f : 1.05f);
        float brightness = fluid ? 0.97f : (leaves ? 0.90f : 0.94f);
        red = ((red - 0.5f) * contrast + 0.5f) * brightness;
        green = ((green - 0.5f) * contrast + 0.5f) * brightness;
        blue = ((blue - 0.5f) * contrast + 0.5f) * brightness;

        int r = clamp(Math.round(clamp01(red) * 255.0f));
        int g = clamp(Math.round(clamp01(green) * 255.0f));
        int b = clamp(Math.round(clamp01(blue) * 255.0f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static final class BiomeColors {
        private BiomeColors() {}

        public static int getGrassColor(net.minecraft.world.level.biome.Biome biome) {
            var effects = biome.getSpecialEffects();
            var override = effects.getGrassColorOverride();
            if (override.isPresent()) return 0xFF000000 | override.get();

            float temp  = clamp01(biome.getBaseTemperature());
            float humid = clamp01(effects.getFoliageColorOverride().isPresent() ? 0.5f
                    : (float) biome.getModifiedClimateSettings().downfall());
            return sampleGrassColormap(temp, humid);
        }

        public static int getFoliageColor(net.minecraft.world.level.biome.Biome biome) {
            var effects = biome.getSpecialEffects();
            var override = effects.getFoliageColorOverride();
            if (override.isPresent()) return 0xFF000000 | override.get();

            float temp  = clamp01(biome.getBaseTemperature());
            float humid = clamp01((float) biome.getModifiedClimateSettings().downfall());
            return sampleFoliageColormap(temp, humid);
        }

        public static int getWaterColor(net.minecraft.world.level.biome.Biome biome) {
            int col = biome.getSpecialEffects().getWaterColor();
            return 0xFF000000 | col;
        }

        private static int sampleGrassColormap(float temperature, float downfall) {
            return 0xFF000000 | net.minecraft.world.level.GrassColor.get(temperature, downfall);
        }

        private static int sampleFoliageColormap(float temperature, float downfall) {
            return 0xFF000000 | net.minecraft.world.level.FoliageColor.get(temperature, downfall);
        }

        private static float clamp01(float v) {
            return Math.max(0.0f, Math.min(1.0f, v));
        }

        public static final int BLEND_RADIUS = 2;
        private static final int SIZE = 512;

        public static int blendGrass(long[] pixelData,
                java.util.function.IntFunction<net.minecraft.world.level.biome.Biome> biomeLookup, int px, int pz) {
            return blendGrass(pixelData, biomeLookup, px, pz, SIZE, SIZE);
        }

        public static int blendGrass(long[] pixelData,
                java.util.function.IntFunction<net.minecraft.world.level.biome.Biome> biomeLookup, int px, int pz,
                int width, int height) {
            return blend(pixelData, biomeLookup, px, pz, width, height, true,
                    BiomeColors::getGrassColor);
        }

        public static int blendFoliage(long[] pixelData,
                java.util.function.IntFunction<net.minecraft.world.level.biome.Biome> biomeLookup, int px, int pz) {
            return blendFoliage(pixelData, biomeLookup, px, pz, SIZE, SIZE);
        }

        public static int blendFoliage(long[] pixelData,
                java.util.function.IntFunction<net.minecraft.world.level.biome.Biome> biomeLookup, int px, int pz,
                int width, int height) {
            return blend(pixelData, biomeLookup, px, pz, width, height, true,
                    BiomeColors::getFoliageColor);
        }

        public static int blendWater(long[] pixelData,
                java.util.function.IntFunction<net.minecraft.world.level.biome.Biome> biomeLookup, int px, int pz) {
            return blendWater(pixelData, biomeLookup, px, pz, SIZE, SIZE);
        }

        public static int blendWater(long[] pixelData,
                java.util.function.IntFunction<net.minecraft.world.level.biome.Biome> biomeLookup, int px, int pz,
                int width, int height) {
            return blend(pixelData, biomeLookup, px, pz, width, height, true,
                    BiomeColors::getWaterColor);
        }

        @FunctionalInterface
        private interface ColorExtractor {
            int extract(net.minecraft.world.level.biome.Biome biome);
        }

        private static int blend(long[] pixelData,
                java.util.function.IntFunction<net.minecraft.world.level.biome.Biome> biomeLookup,
                int px, int pz, int width, int height, boolean includeDiagonals,
                ColorExtractor extractor) {
            int red = 0;
            int green = 0;
            int blue = 0;
            int count = 0;
            for (int dz = -BLEND_RADIUS; dz <= BLEND_RADIUS; dz++) {
                for (int dx = -BLEND_RADIUS; dx <= BLEND_RADIUS; dx++) {
                    if (!includeDiagonals && dx != 0 && dz != 0) continue;
                    int sampleX = px + dx;
                    int sampleZ = pz + dz;
                    if (sampleX < 0 || sampleX >= width || sampleZ < 0 || sampleZ >= height) continue;
                    long packed = pixelData[sampleZ * width + sampleX];
                    if (MapBlockData.isEmpty(packed)) continue;
                    int biomeIndex = MapBlockData.biomeId(packed) & 0xFF;
                    if (biomeIndex == (MapBlockData.NO_BIOME & 0xFF)) continue;
                    net.minecraft.world.level.biome.Biome biome = biomeLookup.apply(biomeIndex);
                    if (biome == null) continue;
                    int argb = extractor.extract(biome);
                    red += (argb >>> 16) & 0xFF;
                    green += (argb >>> 8) & 0xFF;
                    blue += argb & 0xFF;
                    count++;
                }
            }
            if (count == 0) {
                if (px >= 0 && px < width && pz >= 0 && pz < height) {
                    long selfPacked = pixelData[pz * width + px];
                    if (!MapBlockData.isEmpty(selfPacked)) {
                        int selfBiomeIndex = MapBlockData.biomeId(selfPacked) & 0xFF;
                        if (selfBiomeIndex != (MapBlockData.NO_BIOME & 0xFF)) {
                            net.minecraft.world.level.biome.Biome selfBiome = biomeLookup.apply(selfBiomeIndex);
                            if (selfBiome != null) {
                                return 0xFF000000 | (extractor.extract(selfBiome) & 0x00FFFFFF);
                            }
                        }
                    }
                }
                return -1;
            }
            return 0xFF000000 | ((red / count) << 16) | ((green / count) << 8) | (blue / count);
        }
    }

    /** Lightweight display-time color transforms. Input and output use NativeImage ABGR. */
    public static final class MapColorProfile {
        public static final String[] NAMES = { "Balanced", "Vibrant", "Natural", "Contrast" };

        private MapColorProfile() {
        }

        public static int apply(int abgr, int profile) {
            if (abgr == 0 || profile == 0) return abgr;

            int alpha = (abgr >>> 24) & 0xFF;
            float red = (abgr & 0xFF) / 255.0f;
            float green = ((abgr >>> 8) & 0xFF) / 255.0f;
            float blue = ((abgr >>> 16) & 0xFF) / 255.0f;

            float saturation;
            float contrast;
            float brightness;
            switch (profile) {
                case 1 -> { // Vibrant
                    saturation = 1.20f;
                    contrast = 1.08f;
                    brightness = 1.04f;
                }
                case 2 -> { // Natural
                    saturation = 0.84f;
                    contrast = 0.96f;
                    brightness = 1.01f;
                }
                case 3 -> { // High contrast
                    saturation = 1.06f;
                    contrast = 1.20f;
                    brightness = 1.0f;
                }
                default -> {
                    return abgr;
                }
            }

            float luminance = red * 0.2126f + green * 0.7152f + blue * 0.0722f;
            red = luminance + (red - luminance) * saturation;
            green = luminance + (green - luminance) * saturation;
            blue = luminance + (blue - luminance) * saturation;

            red = ((red - 0.5f) * contrast + 0.5f) * brightness;
            green = ((green - 0.5f) * contrast + 0.5f) * brightness;
            blue = ((blue - 0.5f) * contrast + 0.5f) * brightness;

            int r = Math.round(clamp(red) * 255.0f);
            int g = Math.round(clamp(green) * 255.0f);
            int b = Math.round(clamp(blue) * 255.0f);
            return (alpha << 24) | (b << 16) | (g << 8) | r;
        }

        private static float clamp(float value) {
            return Math.max(0.0f, Math.min(1.0f, value));
        }

        public static int parseColor(String text) {
            String value = text == null ? "" : text.trim();
            if (value.startsWith("#")) value = value.substring(1);
            if (value.length() != 6 && value.length() != 8) {
                throw new IllegalArgumentException("Use #RRGGBB or #AARRGGBB");
            }
            try {
                long parsed = Long.parseUnsignedLong(value, 16);
                if (value.length() == 6) parsed |= 0xFF000000L;
                return (int) parsed;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid hexadecimal color", exception);
            }
        }

        public static String formatColor(int argb) {
            return String.format("#%08X", argb);
        }

        public static void applyBlockOverride(String blockId, int argb) {
            if (blockId == null || blockId.isBlank()) return;
            MapConfig.blockColorOverrides.put(blockId, 0xFF000000 | (argb & 0x00FFFFFF));
            refreshBlockOverride(blockId);
        }

        public static void resetBlockOverride(String blockId) {
            if (blockId == null || blockId.isBlank()) return;
            MapConfig.blockColorOverrides.remove(blockId);
            refreshBlockOverride(blockId);
        }

        public static void clearAllBlockOverrides() {
            if (MapConfig.blockColorOverrides.isEmpty()) return;
            MapConfig.blockColorOverrides.clear();
            MapConfig.save();
            MapSessionManager.getInstance().bumpStyleGeneration();
            MapTextureManager.getInstance().clearDerivedColorCaches();
            MapTextureManager.getInstance().invalidateStyle();
            MapTextureManager.getInstance().uploadDirtyTextures(true);
            requestLiveCaveRefresh();
        }

        private static void refreshBlockOverride(String blockId) {
            MapConfig.save();
            MapSessionManager.getInstance().bumpStyleGeneration();
            MapTextureManager.getInstance().invalidateBlockColor(blockId);
            MapTextureManager.getInstance().uploadDirtyTextures(true);
            requestLiveCaveRefresh();
        }

        private static void requestLiveCaveRefresh() {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.player != null) {
                ChunkScanner.getInstance().requestRefresh(minecraft);
            }
        }
    }

    /**
     * Describes how an accurate-mode block texture should be combined with biome
     * colour. The model tint-index is authoritative: pre-coloured modded leaves that
     * do not request a tint stay texture-only instead of being recoloured green.
     */
    public enum BlockTintPolicy {
        NONE,
        FOLIAGE,
        GRASS,
        SPRUCE,
        BIRCH
    }

    /**
     * Persistent per-pixel tint result captured from Minecraft's registered
     * {@code BlockColors} provider while the source chunk is loaded.
     */
    public static final class SurfaceTintData {
        public static final int UNKNOWN = 0x80000000;
        public static final int NONE = 0x81000000;

        private SurfaceTintData() {
        }

        public static int fromProviderResult(int color) {
            return color == -1 ? NONE : color & 0x00FFFFFF;
        }

        public static boolean hasColor(int stored) {
            return stored != UNKNOWN && stored != NONE;
        }

        public static int rgb(int stored) {
            return stored & 0x00FFFFFF;
        }
    }




    /**
 * The single visual classification policy shared by surface, live cave and
 * world-save cave paths.
 *
 * <p>This class deliberately does not resolve a final colour. It describes how a
 * block participates in a map pixel: whether it is a base, an overlay or invisible,
 * which tint policy applies, and how much opacity an overlay contributes. Geometry
 * remains in {@link CaveStateClassifier}; colour sampling remains in
 * {@link MapTextureManager}. Keeping those concerns separate prevents the old
 * surface/cave classifiers from drifting apart.</p>
 */
public static final class MapVisualClassifier {
    public enum Role {
        INVISIBLE,
        OPAQUE_BASE,
        TRANSPARENT_OVERLAY,
        FLUID_OVERLAY,
        EMISSIVE_OVERLAY
    }

    public enum SamplingPolicy {
        NONE,
        MAP_COLOR_FIRST,
        TEXTURE_FIRST
    }

    private static final MapVisualClassifier INSTANCE = new MapVisualClassifier();

    private volatile AtomicReferenceArray<VisualInfo> byStateId =
            new AtomicReferenceArray<>(4096);
    private final Map<BlockState, VisualInfo> fallback = new IdentityHashMap<>();
    /**
     * Surface styling stores compact block palette entries as strings. Resolving
     * the same id through ResourceLocation + the block registry for every map
     * pixel dominated allocation while exact leaves were arriving. Keep the
     * worker-safe id view alongside the state-id cache.
     */
    private final Map<String, VisualInfo> byBlockId = new ConcurrentHashMap<>();
    private final CaveStateClassifier geometry = CaveStateClassifier.getInstance();

    private MapVisualClassifier() {
    }

    public static MapVisualClassifier getInstance() {
        return INSTANCE;
    }

    public VisualInfo info(BlockState state) {
        int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
        VisualInfo cached = null;
        if (stateId >= 0) {
            AtomicReferenceArray<VisualInfo> local = byStateId;
            if (stateId < local.length()) cached = local.get(stateId);
        } else {
            synchronized (this) {
                cached = fallback.get(state);
            }
        }
        if (cached != null) return cached;

        synchronized (this) {
            if (stateId >= 0) {
                ensureCapacityLocked(stateId + 1);
                cached = byStateId.get(stateId);
            } else {
                cached = fallback.get(state);
            }
            if (cached != null) return cached;
            VisualInfo resolved = resolve(state);
            if (stateId >= 0) byStateId.set(stateId, resolved);
            else fallback.put(state, resolved);
            return resolved;
        }
    }

    public VisualInfo info(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return VisualInfo.unknown(blockId == null ? "" : blockId);
        }
        return byBlockId.computeIfAbsent(blockId, this::resolveBlockId);
    }

    private VisualInfo resolveBlockId(String blockId) {
        try {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
            return info(block.defaultBlockState());
        } catch (Throwable ignored) {
            return VisualInfo.unknown(blockId);
        }
    }

    public Role role(BlockState state) {
        return info(state).role();
    }

    /**
     * Returns the authoritative tint policy. Static vanilla/tag rules are available
     * on worker threads; model tint-index inspection is added on the render thread.
     */
    public BlockTintPolicy tintPolicy(BlockState state) {
        VisualInfo info = info(state);
        if (info.fixedTextureColor()) return BlockTintPolicy.NONE;
        if (info.tintPolicy() != BlockTintPolicy.NONE) return info.tintPolicy();
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) return BlockTintPolicy.NONE;
        return MapTextureManager.getInstance().resolveTintPolicy(info.blockId());
    }

    public boolean isVisibleSurface(BlockGetter level, BlockPos pos,
            BlockState state, boolean showFlowers) {
        VisualInfo info = info(state);
        if (info.role() == Role.INVISIBLE) return false;
        // The vanilla #flowers tag also contains canopy/substantial blocks such as
        // cherry leaves. Only the classifier's small decorative flower role is
        // controlled by the Flowers option.
        if (info.leaves()) return true;
        if (info.flower()) return showFlowers;
        if (info.fluid() || info.role() == Role.OPAQUE_BASE) return true;

        // A non-empty translucent shape (glass, waterlogged slabs, framed blocks)
        // can still be the visible surface. Empty decorations remain overlays only.
        return !geometry.isCollisionEmpty(level, pos, state);
    }

    public boolean isOverlay(BlockState state) {
        Role role = info(state).role();
        return role == Role.TRANSPARENT_OVERLAY
                || role == Role.FLUID_OVERLAY
                || role == Role.EMISSIVE_OVERLAY;
    }

    public boolean isInvisibleDecoration(BlockState state) {
        return info(state).invisibleDecoration();
    }

    public int overlayOpacity(BlockState state) {
        return info(state).overlayOpacity();
    }

    /** Fluid opacity is independent from the containing waterlogged block role. */
    public int fluidOverlayOpacity(BlockState state) {
        VisualInfo info = info(state);
        if (!info.fluid()) return 0;
        return info.water() ? 176 : (info.emissive() ? 214 : 192);
    }

    public synchronized void clear() {
        AtomicReferenceArray<VisualInfo> current = byStateId;
        for (int i = 0; i < current.length(); i++) current.set(i, null);
        fallback.clear();
        byBlockId.clear();
    }

    private void ensureCapacityLocked(int required) {
        AtomicReferenceArray<VisualInfo> current = byStateId;
        if (required <= current.length()) return;
        int next = current.length();
        while (next < required) next = Math.max(next + 1, next << 1);
        AtomicReferenceArray<VisualInfo> expanded = new AtomicReferenceArray<>(next);
        for (int i = 0; i < current.length(); i++) expanded.set(i, current.get(i));
        byStateId = expanded;
    }

    private VisualInfo resolve(BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        boolean air = state.isAir();
        boolean water = state.getFluidState().is(FluidTags.WATER);
        boolean fluid = !state.getFluidState().isEmpty();
        boolean leaves = state.is(BlockTags.LEAVES)
                || path.endsWith("_leaves") || path.contains("foliage");
        boolean flowerTag = state.is(BlockTags.FLOWERS);
        boolean grass = state.is(Blocks.GRASS_BLOCK)
                || path.contains("grass") || path.contains("fern");
        boolean wood = state.is(BlockTags.PLANKS) || state.is(BlockTags.LOGS);
        boolean fixedTextureColor = state.is(Blocks.CHERRY_LEAVES);
        boolean invisibleDecoration = isTorchLike(state, path);
        boolean emissive = state.getLightEmission() > 0;
        boolean collisionEmpty = geometry.info(state).collisionEmpty();
        boolean solidRender = geometry.isSolidRender(state);
        int lightBlock = geometry.lightBlock(state);
        boolean renderInvisible = !fluid
                && state.getRenderShape() == RenderShape.INVISIBLE;
        boolean explicitTransparency = isExplicitlyTransparent(state, path);
        boolean substantialVisual = leaves || solidRender || !collisionEmpty;
        // Gameplay tags are broader than map semantics. Only small, non-canopy,
        // non-substantial flower-tagged states are hidden by the Flowers option.
        boolean flower = flowerTag && !substantialVisual;

        BlockTintPolicy tintPolicy = BlockTintPolicy.NONE;
        if (!fixedTextureColor) {
            if (state.is(Blocks.SPRUCE_LEAVES)) tintPolicy = BlockTintPolicy.SPRUCE;
            else if (state.is(Blocks.BIRCH_LEAVES)) tintPolicy = BlockTintPolicy.BIRCH;
            else if (leaves || path.contains("vine")) tintPolicy = BlockTintPolicy.FOLIAGE;
            else if (grass) tintPolicy = BlockTintPolicy.GRASS;
        }

        boolean fluidOnly = fluid && collisionEmpty && !solidRender;
        Role role;
        if (air || renderInvisible || invisibleDecoration) role = Role.INVISIBLE;
        else if (fluidOnly) role = Role.FLUID_OVERLAY;
        else if (emissive && collisionEmpty) role = Role.EMISSIVE_OVERLAY;
        /* The cave writer only treats genuinely translucent/cutout material as
         * an overlay. Light blocking, solidRender and partial collision are not
         * equivalent to visual transparency: slabs, stairs, walls and many modded
         * floors must remain authoritative opaque cave floors. */
        else if (leaves || flower || state.canBeReplaced()
                || explicitTransparency || collisionEmpty) {
            role = Role.TRANSPARENT_OVERLAY;
        } else role = Role.OPAQUE_BASE;

        int opacity = switch (role) {
            case INVISIBLE -> 0;
            case OPAQUE_BASE -> 255;
            case FLUID_OVERLAY -> water ? 176 : (emissive ? 214 : 192);
            case EMISSIVE_OVERLAY -> 164;
            case TRANSPARENT_OVERLAY -> {
                if (fixedTextureColor) yield 176;
                if (leaves) yield 132;
                if (flower) yield 100;
                if (state.canBeReplaced()) yield 84;
                if (collisionEmpty) yield 104;
                yield Math.min(188, 112 + lightBlock * 4);
            }
        };

        SamplingPolicy sampling = role == Role.INVISIBLE
                ? SamplingPolicy.NONE
                : (fixedTextureColor || leaves || grass || fluid
                        ? SamplingPolicy.TEXTURE_FIRST
                        : SamplingPolicy.MAP_COLOR_FIRST);

        return new VisualInfo(blockId, role, tintPolicy, sampling,
                opacity, Math.max(0, Math.min(15, lightBlock)),
                leaves, flower, grass, wood, fluid, water, emissive,
                fixedTextureColor, invisibleDecoration);
    }

    private static boolean isExplicitlyTransparent(BlockState state, String path) {
        if (state.getBlock() instanceof TransparentBlock) return true;
        // Minecraft's pane/ice/web/vine families are not all subclasses of the
        // same transparent base class. Registry-name fallbacks also cover common
        // modded variants without demoting every partial block to an overlay.
        return path.equals("ice") || path.endsWith("_ice")
                || path.contains("glass") || path.endsWith("_pane")
                || path.equals("cobweb") || path.endsWith("_web")
                || path.equals("vine") || path.endsWith("_vine")
                || path.contains("transparent");
    }

    private static boolean isTorchLike(BlockState state, String path) {
        if (state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH)) return true;
        return path.equals("torch") || path.endsWith("_torch")
                || path.endsWith("_wall_torch");
    }

    public record VisualInfo(String blockId, Role role,
            BlockTintPolicy tintPolicy, SamplingPolicy samplingPolicy,
            int overlayOpacity, int lightBlock,
            boolean leaves, boolean flower, boolean grass, boolean wood,
            boolean fluid, boolean water, boolean emissive,
            boolean fixedTextureColor, boolean invisibleDecoration) {
        private static VisualInfo unknown(String blockId) {
            return new VisualInfo(blockId, Role.OPAQUE_BASE,
                    BlockTintPolicy.NONE, SamplingPolicy.TEXTURE_FIRST,
                    255, 15, false, false, false, false,
                    false, false, false, false, false);
        }
    }

    public static final class MapBlockEntityVisualResolver {
        private static final MapBlockEntityVisualResolver INSTANCE =
                new MapBlockEntityVisualResolver();
        private static final int MAX_NBT_DEPTH = 4;
        private static final String[] SEMANTIC_TOKENS = {
                "camo", "camouflage", "material", "appearance", "copycat",
                "framed", "cover", "mimic", "modelstate", "blockstate"
        };

        private final Map<Class<?>, AccessPlan> accessPlans = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<Adapter> adapters = new CopyOnWriteArrayList<>();

        private MapBlockEntityVisualResolver() {
        }

        public static MapBlockEntityVisualResolver getInstance() {
            return INSTANCE;
        }

        public BlockState resolveLive(Level level, BlockPos pos, BlockState fallback) {
            if (level == null || pos == null || fallback == null || !fallback.hasBlockEntity()) return fallback;
            try {
                BlockEntity entity = level.getBlockEntity(pos);
                if (entity == null) return fallback;
                for (Adapter adapter : adapters) {
                    BlockState resolved = adapter.resolveLive(level, pos, fallback, entity);
                    if (usable(resolved)) return resolved;
                }
                AccessPlan plan = accessPlans.computeIfAbsent(entity.getClass(), this::inspectClass);
                BlockState resolved = plan.resolve(entity);
                return usable(resolved) ? resolved : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        public BlockState resolveOffline(BlockState fallback, CompoundTag blockEntityTag) {
            if (fallback == null || blockEntityTag == null || blockEntityTag.isEmpty()) return fallback;
            try {
                for (Adapter adapter : adapters) {
                    BlockState resolved = adapter.resolveOffline(fallback, blockEntityTag);
                    if (usable(resolved)) return resolved;
                }
                BlockState resolved = findSerializedState(blockEntityTag, 0, "");
                return usable(resolved) ? resolved : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        public void clear() {
            accessPlans.clear();
        }

        public void register(Adapter adapter) {
            if (adapter != null) adapters.addIfAbsent(adapter);
        }

        public void unregister(Adapter adapter) {
            adapters.remove(adapter);
        }

        private AccessPlan inspectClass(Class<?> type) {
            List<Method> methods = new ArrayList<>();
            List<Field> fields = new ArrayList<>();
            for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                    cursor = cursor.getSuperclass()) {
                for (Method method : cursor.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers())
                            || method.getParameterCount() != 0
                            || !supportedType(method.getReturnType())
                            || !semanticName(method.getName())) continue;
                    try {
                        method.setAccessible(true);
                        methods.add(method);
                    } catch (RuntimeException ignored) {
                    }
                }
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !supportedType(field.getType())
                            || !semanticName(field.getName())) continue;
                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return new AccessPlan(methods.toArray(Method[]::new), fields.toArray(Field[]::new));
        }

        private static boolean supportedType(Class<?> type) {
            return BlockState.class.isAssignableFrom(type)
                    || Block.class.isAssignableFrom(type)
                    || ResourceLocation.class.isAssignableFrom(type)
                    || String.class.isAssignableFrom(type)
                    || Optional.class.isAssignableFrom(type)
                    || CompoundTag.class.isAssignableFrom(type);
        }

        private static BlockState candidateState(Object value) {
            if (value instanceof BlockState state) return state;
            if (value instanceof Block block) return block.defaultBlockState();
            if (value instanceof ResourceLocation id) return decodeBlockId(id.toString());
            if (value instanceof String id) return decodeBlockId(id);
            if (value instanceof Optional<?> optional) return candidateState(optional.orElse(null));
            if (value instanceof CompoundTag tag) return decodeStateCompound(tag);
            return null;
        }

        private static boolean semanticName(String value) {
            if (value == null) return false;
            String lower = value.toLowerCase(Locale.ROOT).replace("_", "");
            for (String token : SEMANTIC_TOKENS) {
                if (lower.contains(token.replace("_", ""))) return true;
            }
            return false;
        }

        private static boolean usable(BlockState state) {
            return state != null && !state.isAir() && !state.is(Blocks.STRUCTURE_VOID);
        }

        private BlockState findSerializedState(CompoundTag tag, int depth, String semanticPath) {
            if (depth > MAX_NBT_DEPTH) return null;

            BlockState direct = decodeStateCompound(tag);
            if (direct != null && (depth > 0 || semanticName(semanticPath))) return direct;

            for (String key : tag.getAllKeys()) {
                String path = semanticPath.isEmpty() ? key : semanticPath + '.' + key;
                boolean semantic = semanticName(path);
                if (tag.contains(key, Tag.TAG_COMPOUND)) {
                    CompoundTag child = tag.getCompound(key);
                    BlockState childDirect = decodeStateCompound(child);
                    if (childDirect != null && semantic) return childDirect;
                    BlockState nested = findSerializedState(child, depth + 1, path);
                    if (nested != null && (semantic || depth < 2)) return nested;
                } else if (semantic && tag.contains(key, Tag.TAG_STRING)) {
                    BlockState stringState = decodeBlockId(tag.getString(key));
                    if (stringState != null) return stringState;
                }
            }
            return null;
        }

        private static BlockState decodeStateCompound(CompoundTag stateTag) {
            if (stateTag == null || stateTag.isEmpty()) return null;
            String id = null;
            if (stateTag.contains("Name", Tag.TAG_STRING)) id = stateTag.getString("Name");
            else if (stateTag.contains("name", Tag.TAG_STRING)) id = stateTag.getString("name");
            else if (stateTag.contains("block", Tag.TAG_STRING)) id = stateTag.getString("block");
            BlockState state = decodeBlockId(id);
            if (state == null) return null;
            CompoundTag properties = stateTag.contains("Properties", Tag.TAG_COMPOUND)
                    ? stateTag.getCompound("Properties")
                    : stateTag.getCompound("properties");
            Block block = state.getBlock();
            for (String propertyName : properties.getAllKeys()) {
                Property<?> property = block.getStateDefinition().getProperty(propertyName);
                if (property == null) continue;
                state = applyProperty(state, property, properties.getString(propertyName));
            }
            return state;
        }

        private static BlockState decodeBlockId(String raw) {
            if (raw == null || raw.isBlank()) return null;
            try {
                ResourceLocation id = ResourceLocation.parse(raw);
                Block block = BuiltInRegistries.BLOCK.get(id);
                return block == null || block == Blocks.AIR ? null : block.defaultBlockState();
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static <T extends Comparable<T>> BlockState applyProperty(
                BlockState state, Property<T> property, String serialized) {
            Optional<T> value = property.getValue(serialized);
            return value.map(candidate -> state.setValue(property, candidate)).orElse(state);
        }

        public interface Adapter {
            default BlockState resolveLive(Level level, BlockPos pos, BlockState fallback,
                    BlockEntity entity) {
                return null;
            }

            default BlockState resolveOffline(BlockState fallback, CompoundTag blockEntityTag) {
                return null;
            }
        }

        private record AccessPlan(Method[] methods, Field[] fields) {
            BlockState resolve(BlockEntity entity) {
                for (Method method : methods) {
                    try {
                        BlockState state = candidateState(method.invoke(entity));
                        if (usable(state)) return state;
                    } catch (Throwable ignored) {
                    }
                }
                for (Field field : fields) {
                    try {
                        BlockState state = candidateState(field.get(entity));
                        if (usable(state)) return state;
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }
        }
    }

    /** Best-effort average colour and tint metadata from the actual block model. */
    public static final class BlockTextureColorSampler {
        private static final int MAX_MODEL_DEPTH = 12;
        private static final Map<ResourceLocation, Sample> CACHE = new ConcurrentHashMap<>();
        private static final Set<ResourceLocation> FAILED = ConcurrentHashMap.newKeySet();
        private static final Set<String> BROKEN_TINTS = ConcurrentHashMap.newKeySet();

        private BlockTextureColorSampler() {
        }

        public static boolean isBrokenTint(String blockId) {
            return blockId == null || BROKEN_TINTS.contains(blockId);
        }

        public static void markBrokenTint(String blockId) {
            if (blockId != null) {
                BROKEN_TINTS.add(blockId);
            }
        }

        public static Integer sampleArgb(ResourceLocation blockId) {
            Sample sample = sample(blockId);
            return sample == null ? null : sample.argb();
        }

        public static boolean modelUsesTint(ResourceLocation blockId) {
            Sample sample = sample(blockId);
            return sample != null && sample.usesTintIndex();
        }

        public static synchronized Sample sample(ResourceLocation blockId) {
            Sample cached = CACHE.get(blockId);
            if (cached != null) return cached;
            if (FAILED.contains(blockId)) return null;

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getResourceManager() == null) return null;

            try {
                ResourceLocation model = findModel(minecraft, blockId);
                if (model != null) {
                    ModelInfo info = resolveModelInfo(minecraft, model);
                    Integer argb = info.texture() == null ? null : sampleTexture(minecraft, info.texture());
                    if (argb != null) {
                        Sample result = new Sample(argb, info.usesTintIndex());
                        CACHE.put(blockId, result);
                        return result;
                    }
                }
            } catch (Throwable ignored) {
            }

            for (ResourceLocation texture : directCandidates(blockId)) {
                Integer argb = sampleTexture(minecraft, texture);
                if (argb != null) {
                    Sample result = new Sample(argb, false);
                    CACHE.put(blockId, result);
                    return result;
                }
            }
            FAILED.add(blockId);
            return null;
        }

        public static void invalidate(ResourceLocation blockId) {
            if (blockId == null) return;
            CACHE.remove(blockId);
            FAILED.remove(blockId);
        }

        public static void clearCache() {
            CACHE.clear();
            FAILED.clear();
        }

        private static ResourceLocation findModel(Minecraft minecraft, ResourceLocation blockId) {
            ResourceLocation blockState = ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(),
                    "blockstates/" + blockId.getPath() + ".json");
            JsonObject json = readJson(minecraft, blockState);
            if (json == null) return null;

            if (json.has("variants") && json.get("variants").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("variants").entrySet()) {
                    ResourceLocation model = modelFromElement(blockId.getNamespace(), entry.getValue());
                    if (model != null) return model;
                }
            }
            if (json.has("multipart") && json.get("multipart").isJsonArray()) {
                for (JsonElement part : json.getAsJsonArray("multipart")) {
                    if (!part.isJsonObject()) continue;
                    ResourceLocation model = modelFromElement(blockId.getNamespace(),
                            part.getAsJsonObject().get("apply"));
                    if (model != null) return model;
                }
            }
            return null;
        }

        private static ResourceLocation modelFromElement(String defaultNamespace, JsonElement element) {
            if (element == null) return null;
            if (element.isJsonArray()) {
                JsonArray array = element.getAsJsonArray();
                for (JsonElement item : array) {
                    ResourceLocation model = modelFromElement(defaultNamespace, item);
                    if (model != null) return model;
                }
                return null;
            }
            if (!element.isJsonObject()) return null;
            JsonElement modelElement = element.getAsJsonObject().get("model");
            if (modelElement == null || !modelElement.isJsonPrimitive()) return null;
            return parseLocation(defaultNamespace, modelElement.getAsString());
        }

        private static ModelInfo resolveModelInfo(Minecraft minecraft, ResourceLocation initialModel) {
            Map<String, String> textures = new LinkedHashMap<>();
            ResourceLocation model = initialModel;
            String namespace = model.getNamespace();
            Set<ResourceLocation> visited = new LinkedHashSet<>();
            boolean usesTint = false;

            for (int depth = 0; depth < MAX_MODEL_DEPTH && model != null && visited.add(model); depth++) {
                ResourceLocation modelJson = ResourceLocation.fromNamespaceAndPath(model.getNamespace(),
                        "models/" + model.getPath() + ".json");
                JsonObject json = readJson(minecraft, modelJson);
                if (json == null) break;
                namespace = model.getNamespace();
                usesTint |= containsTintIndex(json);
                if (json.has("textures") && json.get("textures").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("textures").entrySet()) {
                        if (entry.getValue().isJsonPrimitive()) {
                            textures.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
                        }
                    }
                }
                if (!json.has("parent") || !json.get("parent").isJsonPrimitive()) break;
                model = parseLocation(namespace, json.get("parent").getAsString());
            }

            String[] preferred = { "top", "up", "all", "end", "particle", "side", "texture" };
            for (String key : preferred) {
                ResourceLocation location = resolveTextureReference(namespace, textures, textures.get(key));
                if (location != null) return new ModelInfo(toTextureFile(location), usesTint);
            }
            for (String value : textures.values()) {
                ResourceLocation location = resolveTextureReference(namespace, textures, value);
                if (location != null) return new ModelInfo(toTextureFile(location), usesTint);
            }
            return new ModelInfo(null, usesTint);
        }

        private static boolean containsTintIndex(JsonObject model) {
            if (!model.has("elements") || !model.get("elements").isJsonArray()) return false;
            for (JsonElement element : model.getAsJsonArray("elements")) {
                if (!element.isJsonObject()) continue;
                JsonObject object = element.getAsJsonObject();
                if (!object.has("faces") || !object.get("faces").isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> face : object.getAsJsonObject("faces").entrySet()) {
                    if (face.getValue().isJsonObject()
                            && face.getValue().getAsJsonObject().has("tintindex")) return true;
                }
            }
            return false;
        }

        private static ResourceLocation resolveTextureReference(String namespace,
                Map<String, String> textures, String value) {
            Set<String> visited = new LinkedHashSet<>();
            while (value != null && value.startsWith("#") && visited.add(value)) {
                value = textures.get(value.substring(1));
            }
            if (value == null || value.startsWith("#")) return null;
            return parseLocation(namespace, value);
        }

        private static ResourceLocation toTextureFile(ResourceLocation textureId) {
            String path = textureId.getPath();
            if (path.startsWith("textures/") && path.endsWith(".png")) return textureId;
            return ResourceLocation.fromNamespaceAndPath(textureId.getNamespace(),
                    "textures/" + path + ".png");
        }

        private static JsonObject readJson(Minecraft minecraft, ResourceLocation location) {
            try {
                Optional<Resource> resource = minecraft.getResourceManager().getResource(location);
                if (resource.isEmpty()) return null;
                try (InputStream stream = resource.get().open();
                        InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    JsonElement element = JsonParser.parseReader(reader);
                    return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Integer sampleTexture(Minecraft minecraft, ResourceLocation texture) {
            try {
                Optional<Resource> resource = minecraft.getResourceManager().getResource(texture);
                if (resource.isEmpty()) return null;
                try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
                    return average(image);
                }
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static List<ResourceLocation> directCandidates(ResourceLocation blockId) {
            String path = blockId.getPath();
            List<ResourceLocation> result = new ArrayList<>(12);
            addCandidate(result, blockId, path + "_top");
            addCandidate(result, blockId, path + "_up");
            if (path.equals("grass_block")) addCandidate(result, blockId, "grass_block_top");
            if (path.equals("dirt_path")) addCandidate(result, blockId, "dirt_path_top");
            if (path.equals("farmland")) {
                addCandidate(result, blockId, "farmland_moist");
                addCandidate(result, blockId, "farmland");
            }
            addCandidate(result, blockId, path);
            if (path.endsWith("_block")) {
                String stripped = path.substring(0, path.length() - "_block".length());
                addCandidate(result, blockId, stripped + "_block_top");
                addCandidate(result, blockId, stripped);
            }
            if (path.endsWith("_wall") || path.endsWith("_stairs") || path.endsWith("_slab")) {
                int separator = path.lastIndexOf('_');
                if (separator > 0) addCandidate(result, blockId, path.substring(0, separator));
            }
            return result;
        }

        private static void addCandidate(List<ResourceLocation> output,
                ResourceLocation blockId, String texturePath) {
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(),
                    "textures/block/" + texturePath + ".png");
            if (!output.contains(candidate)) output.add(candidate);
        }

        private static ResourceLocation parseLocation(String defaultNamespace, String text) {
            if (text == null || text.isBlank()) return null;
            return text.indexOf(':') >= 0
                    ? ResourceLocation.parse(text)
                    : ResourceLocation.fromNamespaceAndPath(defaultNamespace, text);
        }

        private static Integer average(NativeImage image) {
            long red = 0L;
            long green = 0L;
            long blue = 0L;
            long weight = 0L;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return null;

            int stepX = Math.max(1, width / 64);
            int stepY = Math.max(1, height / 64);
            for (int y = 0; y < height; y += stepY) {
                for (int x = 0; x < width; x += stepX) {
                    int abgr = image.getPixelRGBA(x, y);
                    int alpha = (abgr >>> 24) & 0xFF;
                    if (alpha < 24) continue;
                    int sampleRed = abgr & 0xFF;
                    int sampleGreen = (abgr >>> 8) & 0xFF;
                    int sampleBlue = (abgr >>> 16) & 0xFF;
                    red += (long) sampleRed * alpha;
                    green += (long) sampleGreen * alpha;
                    blue += (long) sampleBlue * alpha;
                    weight += alpha;
                }
            }
            if (weight == 0L) return null;
            int outRed = (int) (red / weight);
            int outGreen = (int) (green / weight);
            int outBlue = (int) (blue / weight);
            return 0xFF000000 | (outRed << 16) | (outGreen << 8) | outBlue;
        }

        public record Sample(int argb, boolean usesTintIndex) {
        }

        private record ModelInfo(ResourceLocation texture, boolean usesTintIndex) {
        }
    }
}

}