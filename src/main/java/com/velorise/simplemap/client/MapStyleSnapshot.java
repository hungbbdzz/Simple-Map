package com.velorise.simplemap.client;

import com.velorise.simplemap.client.pipeline.RevisionStamp;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** Immutable style generation consumed by surface workers. */
public final class MapStyleSnapshot {
    private final RevisionStamp stamp;
    private final Biome[] biomes;
    private final Map<String, Biome> biomesById;
    private final Map<String, Integer> blockColors;
    private final Map<String, BlockTintPolicy> tintPolicies;
    private final Set<String> tintDisabledBlocks;
    private final IntFunction<Biome> biomeLookup;
    private final int colourMode;
    private final boolean showFlowers;
    private final int terrainSlopes;
    private final int profile;

    public MapStyleSnapshot(RevisionStamp stamp, Biome[] biomes,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, int profile) {
        this(stamp, biomes, blockColors, tintPolicies, tintDisabledBlocks,
                colourMode, showFlowers, terrainSlopes, profile, false);
    }

    static MapStyleSnapshot takeOwnership(RevisionStamp stamp, Biome[] biomes,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, int profile) {
        return new MapStyleSnapshot(stamp, biomes, blockColors, tintPolicies,
                tintDisabledBlocks, colourMode, showFlowers, terrainSlopes,
                profile, true);
    }

    private MapStyleSnapshot(RevisionStamp stamp, Biome[] biomes,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, int profile,
            boolean takeOwnership) {
        this.stamp = stamp;
        this.biomes = biomes == null ? new Biome[0]
                : (takeOwnership ? biomes : biomes.clone());
        this.biomesById = Map.of();
        this.blockColors = blockColors == null ? Map.of()
                : (takeOwnership ? blockColors : Map.copyOf(blockColors));
        this.tintPolicies = tintPolicies == null ? Map.of()
                : (takeOwnership ? tintPolicies : Map.copyOf(tintPolicies));
        this.tintDisabledBlocks = tintDisabledBlocks == null ? Set.of()
                : (takeOwnership ? tintDisabledBlocks
                : Set.copyOf(tintDisabledBlocks));
        this.biomeLookup = index -> index >= 0 && index < this.biomes.length
                ? this.biomes[index] : null;
        this.colourMode = colourMode;
        this.showFlowers = showFlowers;
        this.terrainSlopes = terrainSlopes;
        this.profile = profile;
    }

    static MapStyleSnapshot takeOwnershipById(RevisionStamp stamp,
            Map<String, Biome> biomesById,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, int profile) {
        return new MapStyleSnapshot(stamp, biomesById, blockColors,
                tintPolicies, tintDisabledBlocks, colourMode, showFlowers,
                terrainSlopes, profile);
    }

    private MapStyleSnapshot(RevisionStamp stamp, Map<String, Biome> biomesById,
            Map<String, Integer> blockColors,
            Map<String, BlockTintPolicy> tintPolicies,
            Set<String> tintDisabledBlocks, int colourMode,
            boolean showFlowers, int terrainSlopes, int profile) {
        this.stamp = stamp;
        this.biomes = new Biome[0];
        this.biomesById = biomesById == null || biomesById.isEmpty()
                ? Map.of() : Map.copyOf(biomesById);
        this.blockColors = blockColors == null ? Map.of() : blockColors;
        this.tintPolicies = tintPolicies == null ? Map.of() : tintPolicies;
        this.tintDisabledBlocks = tintDisabledBlocks == null
                ? Set.of() : tintDisabledBlocks;
        this.biomeLookup = index -> null;
        this.colourMode = colourMode;
        this.showFlowers = showFlowers;
        this.terrainSlopes = terrainSlopes;
        this.profile = profile;
    }

    public RevisionStamp stamp() { return stamp; }
    public IntFunction<Biome> biomeLookup() { return biomeLookup; }

    /**
     * Resolves the worker-owned union palette without requiring that union to be
     * built on the client thread. Legacy snapshots keep their indexed lookup.
     */
    public IntFunction<Biome> biomeLookup(List<String> biomePalette) {
        if (biomesById.isEmpty()) return biomeLookup;
        List<String> palette = biomePalette == null ? List.of() : biomePalette;
        return index -> {
            if (index < 0 || index >= palette.size()) return null;
            String id = palette.get(index);
            if (id == null || id.isBlank()) id = "minecraft:plains";
            return biomesById.get(id);
        };
    }
    public Map<String, Integer> blockColors() { return blockColors; }
    public Map<String, BlockTintPolicy> tintPolicies() { return tintPolicies; }
    public Set<String> tintDisabledBlocks() { return tintDisabledBlocks; }
    public int colourMode() { return colourMode; }
    public boolean showFlowers() { return showFlowers; }
    public int terrainSlopes() { return terrainSlopes; }
    public int profile() { return profile; }
}
