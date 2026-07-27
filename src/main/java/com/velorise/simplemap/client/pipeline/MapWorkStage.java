package com.velorise.simplemap.client.pipeline;

/** Ordered stages owned by a region, not by an executor queue. */
public enum MapWorkStage {
    SOURCE_CAPTURE,
    SOURCE_READ,
    SOURCE_DECODE,
    SOURCE_COMMIT,
    CAVE_PROJECTION,
    FULL_CAVE_PROJECTION,
    STYLE,
    LOD_DERIVE,
    GPU_PREPARE,
    GPU_UPLOAD,
    CACHE_COMMIT
}
