package com.velorise.simplemap.client;

import com.velorise.simplemap.client.pipeline.RevisionStamp;
import net.minecraft.world.level.biome.Biome;

import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** Immutable style generation consumed by surface workers. */
public final class MapStyleSnapshot {
    private final RevisionStamp stamp;
    private final Biome[] biomes;
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

    public RevisionStamp stamp() { return stamp; }
    public IntFunction<Biome> biomeLookup() { return biomeLookup; }
    public Map<String, Integer> blockColors() { return blockColors; }
    public Map<String, BlockTintPolicy> tintPolicies() { return tintPolicies; }
    public Set<String> tintDisabledBlocks() { return tintDisabledBlocks; }
    public int colourMode() { return colourMode; }
    public boolean showFlowers() { return showFlowers; }
    public int terrainSlopes() { return terrainSlopes; }
    public int profile() { return profile; }
}
