package com.velorise.simplemap.client;

import net.minecraft.world.level.biome.Biome;

/** Pure 3x3 biome color blending over packed region pixels. */
public final class BiomeBlend {
    public static final int BLEND_RADIUS = 1;
    private static final int SIZE = 512;

    private BiomeBlend() {
    }

    public static int blendGrass(long[] pixelData,
            java.util.function.IntFunction<Biome> biomeLookup, int px, int pz) {
        return blend(pixelData, biomeLookup, px, pz, BiomeColors::getGrassColor);
    }

    public static int blendFoliage(long[] pixelData,
            java.util.function.IntFunction<Biome> biomeLookup, int px, int pz) {
        return blend(pixelData, biomeLookup, px, pz, BiomeColors::getFoliageColor);
    }

    public static int blendWater(long[] pixelData,
            java.util.function.IntFunction<Biome> biomeLookup, int px, int pz) {
        return blend(pixelData, biomeLookup, px, pz, BiomeColors::getWaterColor);
    }

    @FunctionalInterface
    private interface ColorExtractor {
        int extract(Biome biome);
    }

    private static int blend(long[] pixelData,
            java.util.function.IntFunction<Biome> biomeLookup,
            int px, int pz, ColorExtractor extractor) {
        int red = 0;
        int green = 0;
        int blue = 0;
        int count = 0;
        for (int dz = -BLEND_RADIUS; dz <= BLEND_RADIUS; dz++) {
            for (int dx = -BLEND_RADIUS; dx <= BLEND_RADIUS; dx++) {
                int sampleX = px + dx;
                int sampleZ = pz + dz;
                if (sampleX < 0 || sampleX >= SIZE || sampleZ < 0 || sampleZ >= SIZE) continue;
                long packed = pixelData[sampleZ * SIZE + sampleX];
                if (MapBlockData.isEmpty(packed)) continue;
                int biomeIndex = MapBlockData.biomeId(packed) & 0xFF;
                if (biomeIndex == (MapBlockData.NO_BIOME & 0xFF)) continue;
                Biome biome = biomeLookup.apply(biomeIndex);
                if (biome == null) continue;
                int argb = extractor.extract(biome);
                red += (argb >>> 16) & 0xFF;
                green += (argb >>> 8) & 0xFF;
                blue += argb & 0xFF;
                count++;
            }
        }
        if (count == 0) return -1;
        return 0xFF000000 | ((red / count) << 16) | ((green / count) << 8) | (blue / count);
    }
}
