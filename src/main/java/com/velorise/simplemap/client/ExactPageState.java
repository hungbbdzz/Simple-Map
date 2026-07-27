package com.velorise.simplemap.client;

/**
 * Explicit lifecycle for one exact 64x64 render page. The renderer must not infer
 * these states from null textures, missing branches or cache residency.
 */
public enum ExactPageState {
    ABSENT,
    KNOWN_EMPTY,
    REQUESTED,
    DECODING,
    CPU_PARTIAL,
    CPU_READY,
    BUILDING,
    UPLOAD_QUEUED,
    GPU_READY,
    GPU_EVICTED,
    FAILED_RETRYABLE,
    STALE_GENERATION
}
