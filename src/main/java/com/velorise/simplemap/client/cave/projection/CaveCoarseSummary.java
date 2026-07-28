package com.velorise.simplemap.client.cave.projection;

/** One compact Full-Cave far-zoom summary for a 16x16 chunk. */
public record CaveCoarseSummary(int chunkX, int chunkZ, long archiveRevision,
        float coverageRatio, int dominantMaterialId, float waterRatio,
        float emissiveRatio, int minimumDepth, int maximumDepth,
        float completenessRatio) { }
