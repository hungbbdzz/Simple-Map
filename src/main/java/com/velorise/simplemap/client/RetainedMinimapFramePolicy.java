package com.velorise.simplemap.client;

/**
 * Allocation-free invalidation and publication policy for the retained minimap.
 *
 * <p>Authority, projection, camera-grid and retained style changes are hard invalidations
 * and publish immediately. Atlas topology/coverage/texel revisions are streaming
 * invalidations: while a valid last-good target exists they are coalesced to a
 * bounded cadence. This prevents a burst of page publications from replaying the
 * entire atlas once per visual frame. Live player/pin navigation content is deliberately
 * excluded from this policy and is drawn as a lightweight HUD overlay.</p>
 */
final class RetainedMinimapFramePolicy {
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
    private long waypointRevision;
    private String dimension;
    private int caveProjection;
    private int caveLayerY;
    private long snappedPixelX;
    private long snappedPixelZ;
    private int targetScaleBits;
    private int fixedOverlayScaleBits;
    private int brightnessBucket;
    private int nightMode;
    private int waypointScaleBits;
    private int pointerColor;

    private long nextSessionId;
    private long nextSourceGeneration;
    private long nextStyleGeneration;
    private long nextProjectionGeneration;
    private long nextTopologyRevision;
    private long nextContentRevision;
    private long nextPixelRevision;
    private long nextWaypointRevision;
    private String nextDimension;
    private int nextCaveProjection;
    private int nextCaveLayerY;
    private long nextSnappedPixelX;
    private long nextSnappedPixelZ;
    private int nextTargetScaleBits;
    private int nextFixedOverlayScaleBits;
    private int nextBrightnessBucket;
    private int nextNightMode;
    private int nextWaypointScaleBits;
    private int nextPointerColor;

    /**
     * True when the current player-centred view still fits inside the retained
     * target's guard band around its last rendered anchor.
     *
     * <p>This check is intentionally independent from atlas/style invalidation.
     * A streaming or style redraw may still happen at the old anchor; the final
     * HUD quad then applies the same bounded UV translation. Avoiding a recenter
     * for every target texel reduces walking-time atlas replay by roughly the
     * guard-band diameter.</p>
     */
    static boolean withinTranslationGuard(double centerX, double centerZ,
            double anchorX, double anchorZ, float targetScale,
            float maximumOffsetPixels) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)
                || !Double.isFinite(anchorX) || !Double.isFinite(anchorZ)
                || !Float.isFinite(targetScale) || targetScale <= 0.0f
                || !Float.isFinite(maximumOffsetPixels)
                || maximumOffsetPixels < 0.0f) return false;
        double offsetX = (centerX - anchorX) * targetScale;
        double offsetZ = (centerZ - anchorZ) * targetScale;
        return Double.isFinite(offsetX) && Double.isFinite(offsetZ)
                && Math.abs(offsetX) <= maximumOffsetPixels
                && Math.abs(offsetZ) <= maximumOffsetPixels;
    }

    void prepare(long sessionId, long sourceGeneration,
            long styleGeneration, long projectionGeneration,
            long topologyRevision, long contentRevision, long pixelRevision,
            long waypointRevision, String dimension,
            int caveProjection, int caveLayerY,
            long snappedPixelX, long snappedPixelZ,
            float targetScale, float fixedOverlayScale,
            int brightnessBucket, int nightMode,
            float waypointScale, int pointerColor) {
        nextSessionId = sessionId;
        nextSourceGeneration = sourceGeneration;
        nextStyleGeneration = styleGeneration;
        nextProjectionGeneration = projectionGeneration;
        nextTopologyRevision = topologyRevision;
        nextContentRevision = contentRevision;
        nextPixelRevision = pixelRevision;
        nextWaypointRevision = waypointRevision;
        nextDimension = dimension == null ? "unknown" : dimension;
        nextCaveProjection = caveProjection;
        nextCaveLayerY = caveLayerY;
        nextSnappedPixelX = snappedPixelX;
        nextSnappedPixelZ = snappedPixelZ;
        nextTargetScaleBits = Float.floatToIntBits(targetScale);
        nextFixedOverlayScaleBits = Float.floatToIntBits(fixedOverlayScale);
        nextBrightnessBucket = brightnessBucket;
        nextNightMode = nightMode;
        nextWaypointScaleBits = Float.floatToIntBits(waypointScale);
        nextPointerColor = pointerColor;
    }

    Decision decision(long nowNanos, long streamingIntervalNanos) {
        if (!valid || hardChanged()) return Decision.REDRAW_HARD;
        if (!streamingChanged()) return Decision.REUSE;
        long interval = Math.max(0L, streamingIntervalNanos);
        long elapsed = nowNanos - lastRedrawNanos;
        return elapsed < 0L || elapsed >= interval
                ? Decision.REDRAW_STREAMING
                : Decision.DEFER_STREAMING;
    }

    /** Compatibility probe used by dependency-free checks. */
    boolean needsRedraw() {
        return !valid || hardChanged() || streamingChanged();
    }

    private boolean hardChanged() {
        return sessionId != nextSessionId
                || sourceGeneration != nextSourceGeneration
                || styleGeneration != nextStyleGeneration
                || projectionGeneration != nextProjectionGeneration
                || waypointRevision != nextWaypointRevision
                || dimension == null || !dimension.equals(nextDimension)
                || caveProjection != nextCaveProjection
                || caveLayerY != nextCaveLayerY
                || snappedPixelX != nextSnappedPixelX
                || snappedPixelZ != nextSnappedPixelZ
                || targetScaleBits != nextTargetScaleBits
                || fixedOverlayScaleBits != nextFixedOverlayScaleBits
                || brightnessBucket != nextBrightnessBucket
                || nightMode != nextNightMode
                || waypointScaleBits != nextWaypointScaleBits
                || pointerColor != nextPointerColor;
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
        waypointRevision = nextWaypointRevision;
        dimension = nextDimension;
        caveProjection = nextCaveProjection;
        caveLayerY = nextCaveLayerY;
        snappedPixelX = nextSnappedPixelX;
        snappedPixelZ = nextSnappedPixelZ;
        targetScaleBits = nextTargetScaleBits;
        fixedOverlayScaleBits = nextFixedOverlayScaleBits;
        brightnessBucket = nextBrightnessBucket;
        nightMode = nextNightMode;
        waypointScaleBits = nextWaypointScaleBits;
        pointerColor = nextPointerColor;
        lastRedrawNanos = nowNanos;
        valid = true;
    }

    void commit() {
        commit(System.nanoTime());
    }

    void invalidate() {
        valid = false;
        dimension = null;
        lastRedrawNanos = 0L;
    }
}
