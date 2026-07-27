package com.velorise.simplemap.client.cave;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;

/** Current-format Anvil/NBT source adapter for the dense cave projector. */
final class NbtCaveChunkSource implements CaveDisplayProjector.ChunkSource {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final int chunkX;
    private final int chunkZ;
    private final int minimumY;
    private final int maximumY;
    private final int minimumSectionY;
    private final Section[] sections;
    private final int[] surfaceHeights;
    /* A decoded source can be reused by Full and Layered projection workers.
     * Keep mutable positions thread-local so concurrent read-only projections do not
     * race on one BlockPos instance. */
    private final ThreadLocal<BlockPos.MutableBlockPos> positions =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
    private final CaveColorResolver colors = CaveColorResolver.getInstance();

    private NbtCaveChunkSource(int chunkX, int chunkZ, int minimumY, int maximumY,
            int minimumSectionY, Section[] sections, int[] surfaceHeights) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minimumY = minimumY;
        this.maximumY = maximumY;
        this.minimumSectionY = minimumSectionY;
        this.sections = sections;
        this.surfaceHeights = surfaceHeights;
    }

    static NbtCaveChunkSource decode(CompoundTag source, int chunkX, int chunkZ,
            int minimumY, int maximumY, Registry<Biome> biomeRegistry) {
        if (source == null || maximumY <= minimumY) return null;
        CompoundTag root = source.contains("Level", Tag.TAG_COMPOUND)
                ? source.getCompound("Level") : source;
        ListTag sectionTags = root.getList("sections", Tag.TAG_COMPOUND);
        if (sectionTags.isEmpty()) return null;

        int minimumSectionY = Math.floorDiv(minimumY, 16);
        int maximumSectionY = Math.floorDiv(maximumY - 1, 16);
        Section[] sections = new Section[maximumSectionY - minimumSectionY + 1];
        for (int i = 0; i < sectionTags.size(); i++) {
            CompoundTag sectionTag = sectionTags.getCompound(i);
            int sectionY = sectionTag.getByte("Y");
            int target = sectionY - minimumSectionY;
            if (target < 0 || target >= sections.length) continue;
            sections[target] = Section.decode(sectionTag, biomeRegistry);
        }

        int[] heights = decodeHeightmap(root, minimumY, maximumY);
        boolean needsSurfaceScan = heights == null;
        if (heights == null) heights = new int[DenseCaveTile.COLUMN_COUNT];
        NbtCaveChunkSource decoded = new NbtCaveChunkSource(chunkX, chunkZ,
                minimumY, maximumY, minimumSectionY, sections, heights);
        if (needsSurfaceScan) decoded.fillSurfaceHeightsByScan();
        return decoded;
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    @Override
    public int minimumY() {
        return minimumY;
    }

    @Override
    public int maximumY() {
        return maximumY;
    }

    @Override
    public int surfaceY(int localX, int localZ) {
        return surfaceHeights[DenseCaveTile.index(localX, localZ)];
    }

    @Override
    public BlockState stateAt(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return AIR;
        Section section = sections[sectionIndex];
        return section == null ? AIR : section.stateAt(localX, y & 15, localZ);
    }

    @Override
    public BlockGetter blockGetter() {
        return EmptyBlockGetter.INSTANCE;
    }

    @Override
    public BlockPos position(int localX, int y, int localZ) {
        return positions.get().set((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }

    @Override
    public int lightAt(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return 0;
        Section section = sections[sectionIndex];
        return section == null ? 0 : section.blockLight(localX, y & 15, localZ);
    }

    @Override
    public int skyLightAt(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return 0;
        Section section = sections[sectionIndex];
        return section == null ? 0 : section.skyLight(localX, y & 15, localZ);
    }

    private Biome biomeAt(int localX, int y, int localZ) {
        int sectionIndex = Math.floorDiv(y, 16) - minimumSectionY;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return null;
        Section section = sections[sectionIndex];
        return section == null ? null : section.biomeAt(localX, y & 15, localZ);
    }

    @Override
    public int resolveBlockColor(BlockState state, int localX, int y, int localZ) {
        return colors.resolveDenseOffline(state, biomeAt(localX, y, localZ), 0);
    }

    @Override
    public int resolveFluidColor(BlockState state, int localX, int y, int localZ) {
        BlockState fluidState = state.getFluidState().isEmpty()
                ? state : state.getFluidState().createLegacyBlock();
        return colors.resolveDenseOfflineFluid(
                fluidState, biomeAt(localX, y, localZ));
    }

    private void fillSurfaceHeightsByScan() {
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                int height = minimumY;
                for (int y = maximumY - 1; y >= minimumY; y--) {
                    if (!stateAt(localX, y, localZ).isAir()) {
                        height = y;
                        break;
                    }
                }
                surfaceHeights[DenseCaveTile.index(localX, localZ)] = height;
            }
        }
    }

    private static int[] decodeHeightmap(CompoundTag root,
            int minimumY, int maximumY) {
        int[] result = new int[DenseCaveTile.COLUMN_COUNT];
        CompoundTag maps = root.getCompound("Heightmaps");
        long[] packed = maps.getLongArray("WORLD_SURFACE");
        if (packed.length == 0) return null;
        int bits = packed.length / 4;
        if (bits <= 0 || bits > 16) return null;
        int baseY = root.contains("yPos", Tag.TAG_ANY_NUMERIC)
                ? root.getInt("yPos") * 16 : minimumY;
        /* Heightmaps use Minecraft's SimpleBitStorage layout: entries never span
         * two longs. Treating it as one continuous bit stream shifts most columns
         * whenever 64 is not divisible by the entry width and produces incorrect
         * Full Cave start heights. */
        int valuesPerLong = 64 / bits;
        if (valuesPerLong <= 0
                || (result.length + valuesPerLong - 1) / valuesPerLong != packed.length) {
            return null;
        }
        long mask = (1L << bits) - 1L;
        for (int index = 0; index < result.length; index++) {
            int cell = index / valuesPerLong;
            int shift = (index - cell * valuesPerLong) * bits;
            int height = baseY + (int) ((packed[cell] >>> shift) & mask);
            result[index] = Math.max(minimumY,
                    Math.min(maximumY - 1, height));
        }
        return result;
    }

    private static final class Section {
        private final BlockState[] palette;
        private final long[] data;
        private final int bits;
        private final int valuesPerLong;
        private final long mask;
        private final byte[] blockLight;
        private final byte[] skyLight;
        private final Biome[] biomePalette;
        private final long[] biomeData;
        private final int biomeBits;
        private final int biomeValuesPerLong;
        private final long biomeMask;

        private Section(BlockState[] palette, long[] data, int bits,
                byte[] blockLight, byte[] skyLight,
                Biome[] biomePalette, long[] biomeData, int biomeBits) {
            this.palette = palette;
            this.data = data;
            this.bits = bits;
            this.valuesPerLong = bits == 0 ? 0 : 64 / bits;
            this.mask = bits == 0 ? 0L : (1L << bits) - 1L;
            this.blockLight = validLight(blockLight);
            this.skyLight = validLight(skyLight);
            this.biomePalette = biomePalette;
            this.biomeData = biomeData;
            this.biomeBits = biomeBits;
            this.biomeValuesPerLong = biomeBits == 0 ? 0 : 64 / biomeBits;
            this.biomeMask = biomeBits == 0 ? 0L : (1L << biomeBits) - 1L;
        }

        static Section decode(CompoundTag sectionTag, Registry<Biome> biomeRegistry) {
            CompoundTag blockStates = sectionTag == null
                    ? new CompoundTag() : sectionTag.getCompound("block_states");
            BlockState[] palette;
            long[] data;
            int bits;
            if (blockStates == null || blockStates.isEmpty()) {
                palette = new BlockState[] { AIR };
                data = new long[0];
                bits = 0;
            } else {
                ListTag paletteTag = blockStates.getList("palette", Tag.TAG_COMPOUND);
                if (paletteTag.isEmpty()) {
                    palette = new BlockState[] { AIR };
                    data = new long[0];
                    bits = 0;
                } else {
                    palette = new BlockState[paletteTag.size()];
                    for (int i = 0; i < palette.length; i++) {
                        palette[i] = decodeState(paletteTag.getCompound(i));
                    }
                    data = blockStates.contains("data", Tag.TAG_LONG_ARRAY)
                            ? blockStates.getLongArray("data") : new long[0];
                    bits = palette.length == 1 || data.length == 0
                            ? 0 : detectBits(palette.length, data.length, 4096, 4);
                }
            }

            Biome[] biomePalette = new Biome[0];
            long[] biomeData = new long[0];
            int biomeBits = 0;
            CompoundTag biomes = sectionTag == null
                    ? new CompoundTag() : sectionTag.getCompound("biomes");
            ListTag biomePaletteTag = biomes.getList("palette", Tag.TAG_STRING);
            if (!biomePaletteTag.isEmpty()) {
                biomePalette = new Biome[biomePaletteTag.size()];
                for (int i = 0; i < biomePalette.length; i++) {
                    try {
                        biomePalette[i] = biomeRegistry == null ? null
                                : biomeRegistry.get(ResourceLocation.parse(
                                        biomePaletteTag.getString(i)));
                    } catch (Throwable ignored) {
                        biomePalette[i] = null;
                    }
                }
                biomeData = biomes.contains("data", Tag.TAG_LONG_ARRAY)
                        ? biomes.getLongArray("data") : new long[0];
                if (biomePalette.length > 1 && biomeData.length > 0) {
                    biomeBits = detectBits(biomePalette.length,
                            biomeData.length, 64, 1);
                }
            }

            byte[] blockLight = sectionTag != null
                    && sectionTag.contains("BlockLight", Tag.TAG_BYTE_ARRAY)
                    ? sectionTag.getByteArray("BlockLight") : null;
            byte[] skyLight = sectionTag != null
                    && sectionTag.contains("SkyLight", Tag.TAG_BYTE_ARRAY)
                    ? sectionTag.getByteArray("SkyLight") : null;
            return new Section(palette, data, bits, blockLight, skyLight,
                    biomePalette, biomeData, biomeBits);
        }

        BlockState stateAt(int localX, int localY, int localZ) {
            if (palette.length == 0) return AIR;
            if (bits == 0 || data.length == 0) return palette[0];
            int index = (localY << 8) | (localZ << 4) | localX;
            int cell = index / valuesPerLong;
            if (cell < 0 || cell >= data.length) return AIR;
            int shift = (index - cell * valuesPerLong) * bits;
            int paletteIndex = (int) ((data[cell] >>> shift) & mask);
            return paletteIndex >= 0 && paletteIndex < palette.length
                    ? palette[paletteIndex] : AIR;
        }

        int blockLight(int localX, int localY, int localZ) {
            return nibble(blockLight, (localY << 8) | (localZ << 4) | localX);
        }

        int skyLight(int localX, int localY, int localZ) {
            return nibble(skyLight, (localY << 8) | (localZ << 4) | localX);
        }

        Biome biomeAt(int localX, int localY, int localZ) {
            if (biomePalette.length == 0) return null;
            if (biomeBits == 0 || biomeData.length == 0) return biomePalette[0];
            int index = ((localY >> 2) << 4) | ((localZ >> 2) << 2) | (localX >> 2);
            int cell = index / biomeValuesPerLong;
            if (cell < 0 || cell >= biomeData.length) return biomePalette[0];
            int shift = (index - cell * biomeValuesPerLong) * biomeBits;
            int paletteIndex = (int) ((biomeData[cell] >>> shift) & biomeMask);
            return paletteIndex >= 0 && paletteIndex < biomePalette.length
                    ? biomePalette[paletteIndex] : biomePalette[0];
        }

        private static int detectBits(int paletteSize, int longCount,
                int valueCount, int minimumBits) {
            int paletteBits = Math.max(minimumBits,
                    ceilLog2(Math.max(1, paletteSize)));
            for (int candidate = paletteBits; candidate <= 32; candidate++) {
                int perLong = 64 / candidate;
                if (perLong > 0
                        && (valueCount + perLong - 1) / perLong == longCount) {
                    return candidate;
                }
            }
            return paletteBits;
        }

        private static byte[] validLight(byte[] data) {
            return data != null && data.length == 2048 ? data : null;
        }

        private static int nibble(byte[] data, int index) {
            if (data == null) return 0;
            int packed = data[index >> 1] & 0xFF;
            return (index & 1) == 0 ? packed & 0xF : (packed >>> 4) & 0xF;
        }
    }

    private static BlockState decodeState(CompoundTag stateTag) {
        ResourceLocation id;
        try {
            id = ResourceLocation.parse(stateTag.getString("Name"));
        } catch (Throwable ignored) {
            id = null;
        }
        Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
        if (block == null) block = Blocks.AIR;
        BlockState state = block.defaultBlockState();
        CompoundTag properties = stateTag.getCompound("Properties");
        for (String name : properties.getAllKeys()) {
            Property<?> property = block.getStateDefinition().getProperty(name);
            if (property == null) continue;
            state = applyProperty(state, property, properties.getString(name));
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState applyProperty(
            BlockState state, Property<T> property, String serialized) {
        Optional<T> value = property.getValue(serialized);
        return value.map(candidate -> state.setValue(property, candidate)).orElse(state);
    }

    private static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }
}
