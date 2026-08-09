package com.velorise.simplemap.client;

/**
 * Allocation-free invalidation policy for fullscreen retained composition.
 *
 * <p>Viewport motion is represented by a retained anchor plus a bounded source
 * translation. Atlas publication is coalesced, while authority, projection,
 * dimensions, scale and visual configuration are hard invalidations.</p>
 */
final class RetainedFullscreenFramePolicy {
    enum Decision {
        REDRAW_HARD,
        REDRAW_STREAMING,
        DEFER_STREAMING,
        REUSE
    }

    private boolean valid;
    private long lastRedrawNanos;

    private long sessionId;
    private long sourceGeneration;
    private long styleGeneration;
    private long projectionGeneration;
    private long topologyRevision;
    private long contentRevision;
    private long pixelRevision;
    private String dimension;
    private int caveProjection;
    private int caveLayerY;
    private long anchorPixelX;
    private long anchorPixelZ;
    private int width;
    private int height;
    private int scaleBits;
    private int brightnessBucket;
    private int nightMode;

    private long nextSessionId;
    private long nextSourceGeneration;
    private long nextStyleGeneration;
    private long nextProjectionGeneration;
    private long nextTopologyRevision;
    private long nextContentRevision;
    private long nextPixelRevision;
    private String nextDimension;
    private int nextCaveProjection;
    private int nextCaveLayerY;
    private long nextAnchorPixelX;
    private long nextAnchorPixelZ;
    private int nextWidth;
    private int nextHeight;
    private int nextScaleBits;
    private int nextBrightnessBucket;
    private int nextNightMode;

    static boolean withinTranslationGuard(double centerX, double centerZ,
            double anchorX, double anchorZ, float pixelsPerBlock,
            float maximumOffsetPixels) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)
                || !Double.isFinite(anchorX) || !Double.isFinite(anchorZ)
                || !Float.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0.0f
                || !Float.isFinite(maximumOffsetPixels)
                || maximumOffsetPixels < 0.0f) return false;
        double offsetX = (centerX - anchorX) * pixelsPerBlock;
        double offsetZ = (centerZ - anchorZ) * pixelsPerBlock;
        return Double.isFinite(offsetX) && Double.isFinite(offsetZ)
                && Math.abs(offsetX) <= maximumOffsetPixels
                && Math.abs(offsetZ) <= maximumOffsetPixels;
    }

    void prepare(long sessionId, long sourceGeneration,
            long styleGeneration, long projectionGeneration,
            long topologyRevision, long contentRevision, long pixelRevision,
            String dimension, int caveProjection, int caveLayerY,
            long anchorPixelX, long anchorPixelZ,
            int width, int height, float pixelsPerBlock,
            int brightnessBucket, int nightMode) {
        nextSessionId = sessionId;
        nextSourceGeneration = sourceGeneration;
        nextStyleGeneration = styleGeneration;
        nextProjectionGeneration = projectionGeneration;
        nextTopologyRevision = topologyRevision;
        nextContentRevision = contentRevision;
        nextPixelRevision = pixelRevision;
        nextDimension = dimension == null ? "unknown" : dimension;
        nextCaveProjection = caveProjection;
        nextCaveLayerY = caveLayerY;
        nextAnchorPixelX = anchorPixelX;
        nextAnchorPixelZ = anchorPixelZ;
        nextWidth = width;
        nextHeight = height;
        nextScaleBits = Float.floatToIntBits(pixelsPerBlock);
        nextBrightnessBucket = brightnessBucket;
        nextNightMode = nightMode;
    }

    Decision decision(long nowNanos, long streamingIntervalNanos,
            boolean suppressStreamingRedraw) {
        if (!valid || hardChanged()) return Decision.REDRAW_HARD;
        if (!streamingChanged()) return Decision.REUSE;
        // During drag/momentum/zoom settling, keep translating the last coherent
        // snapshot. Replaying the whole atlas every 70-80 ms creates the rhythmic
        // micro-stutter seen in PASS70. One coalesced redraw follows interaction.
        if (suppressStreamingRedraw) return Decision.DEFER_STREAMING;
        long interval = Math.max(0L, streamingIntervalNanos);
        long elapsed = nowNanos - lastRedrawNanos;
        return elapsed < 0L || elapsed >= interval
                ? Decision.REDRAW_STREAMING
                : Decision.DEFER_STREAMING;
    }

    private boolean hardChanged() {
        return sessionId != nextSessionId
                || sourceGeneration != nextSourceGeneration
                || styleGeneration != nextStyleGeneration
                || projectionGeneration != nextProjectionGeneration
                || dimension == null || !dimension.equals(nextDimension)
                || caveProjection != nextCaveProjection
                || caveLayerY != nextCaveLayerY
                || anchorPixelX != nextAnchorPixelX
                || anchorPixelZ != nextAnchorPixelZ
                || width != nextWidth || height != nextHeight
                || scaleBits != nextScaleBits
                || brightnessBucket != nextBrightnessBucket
                || nightMode != nextNightMode;
    }

    private boolean streamingChanged() {
        return topologyRevision != nextTopologyRevision
                || contentRevision != nextContentRevision
                || pixelRevision != nextPixelRevision;
    }

    void commit(long nowNanos) {
        sessionId = nextSessionId;
        sourceGeneration = nextSourceGeneration;
        styleGeneration = nextStyleGeneration;
        projectionGeneration = nextProjectionGeneration;
        topologyRevision = nextTopologyRevision;
        contentRevision = nextContentRevision;
        pixelRevision = nextPixelRevision;
        dimension = nextDimension;
        caveProjection = nextCaveProjection;
        caveLayerY = nextCaveLayerY;
        anchorPixelX = nextAnchorPixelX;
        anchorPixelZ = nextAnchorPixelZ;
        width = nextWidth;
        height = nextHeight;
        scaleBits = nextScaleBits;
        brightnessBucket = nextBrightnessBucket;
        nightMode = nextNightMode;
        lastRedrawNanos = nowNanos;
        valid = true;
    }

    void invalidate() {
        valid = false;
        dimension = null;
        lastRedrawNanos = 0L;
    }
}
