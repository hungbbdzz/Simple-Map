package com.velorise.simplemap.client.renderer;

import com.velorise.simplemap.client.*;
import com.velorise.simplemap.client.color.*;
import com.velorise.simplemap.client.gui.*;
import com.velorise.simplemap.client.lod.*;
import com.velorise.simplemap.client.waypoint.*;
import com.velorise.simplemap.client.cave.*;

/**
 * Allocation-free invalidation policy for fullscreen retained composition.
 *
 * <p>Viewport motion is represented by a retained anchor plus a bounded source
 * translation. Atlas publication is coalesced, while authority, projection,
 * dimensions, scale and visual configuration are hard invalidations.</p>
 */
public final class RetainedFullscreenFramePolicy {
    public enum Decision {
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

    public static boolean withinTranslationGuard(double centerX, double centerZ,
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

    public void prepare(long sessionId, long sourceGeneration,
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

    public Decision decision(long nowNanos, long streamingIntervalNanos,
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

    public void commit(long nowNanos) {
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

    public void invalidate() {
        valid = false;
        dimension = null;
        lastRedrawNanos = 0L;
    }

    /** Density-first hierarchy selection; geometry pressure must not silently drop coverage. */
    public static final class LodSelector {
        private LodSelector() { }

        public static int surfaceLevel(float logicalPixelsPerBlock) {
            if (logicalPixelsPerBlock >= 0.50f) return 0;
            if (logicalPixelsPerBlock >= 0.25f) return 1;
            if (logicalPixelsPerBlock >= 0.125f) return 2;
            return 3;
        }
    }


    public static final class RetainedMinimapFramePolicy {
    public enum Decision {
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
    public static boolean withinTranslationGuard(double centerX, double centerZ,
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

    public void prepare(long sessionId, long sourceGeneration,
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

    public Decision decision(long nowNanos, long streamingIntervalNanos) {
        if (!valid || hardChanged()) return Decision.REDRAW_HARD;
        if (!streamingChanged()) return Decision.REUSE;
        long interval = Math.max(0L, streamingIntervalNanos);
        long elapsed = nowNanos - lastRedrawNanos;
        return elapsed < 0L || elapsed >= interval
                ? Decision.REDRAW_STREAMING
                : Decision.DEFER_STREAMING;
    }

    /** Compatibility probe used by dependency-free checks. */
    public boolean needsRedraw() {
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

    public void commit(long nowNanos) {
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

    public void commit() {
        commit(System.nanoTime());
    }

    public void invalidate() {
        valid = false;
        dimension = null;
        lastRedrawNanos = 0L;
    }

    /**
     * Double-buffered loading/loaded handoff policy for the retained minimap target.
     * The last complete front frame remains visible while a cold projection builds
     * in the back target; failed cold redraws are retried at a bounded cadence.
     */
    public static final class RetainedMinimapHandoffPolicy {
        public static final long COLD_RETRY_NANOS = 125_000_000L;

        private RetainedMinimapHandoffPolicy() { }

        public static boolean shouldAttemptRedraw(boolean frontFrameValid,
                long nowNanos, long nextAttemptNanos) {
            return !frontFrameValid || nowNanos >= nextAttemptNanos;
        }

        public static boolean retainLastGood(boolean frontFrameValid,
                boolean redrawSucceeded) {
            return frontFrameValid && !redrawSucceeded;
        }

        public static boolean canPublishSuccessfulRedraw(boolean lastGoodCompatible,
                boolean retainedUnderlayDrawn, boolean coldCoverageReady) {
            return !lastGoodCompatible || retainedUnderlayDrawn || coldCoverageReady;
        }

        public static long nextAttemptNanos(long nowNanos) {
            long retry = nowNanos + COLD_RETRY_NANOS;
            return retry < nowNanos ? Long.MAX_VALUE : retry;
        }
    }
}


    public static final class RetainedViewportComposition {
    private RetainedViewportComposition() { }

    /**
     * Splits a fractional retained-texture window into integer texel bounds plus a
     * subpixel destination translation. This is the same stability principle used
     * by primary/secondary map offsets: atlas/FBO sampling stays on whole
     * texels while the residual camera motion remains smooth in screen space.
     */
    public static PixelAlignedAxis pixelAlignedAxis(float rawOrigin, float sourceSpan,
            int viewportPixels, int texturePixels) {
        if (!Float.isFinite(rawOrigin) || !Float.isFinite(sourceSpan)
                || sourceSpan <= 0.0f || viewportPixels <= 0
                || texturePixels <= 0) return PixelAlignedAxis.invalid();
        double rawEnd = (double) rawOrigin + sourceSpan;
        int sourceStart = Math.max(0, (int) Math.floor(rawOrigin));
        int sourceEnd = Math.min(texturePixels, (int) Math.ceil(rawEnd));
        if (sourceEnd <= sourceStart) return PixelAlignedAxis.invalid();
        float destinationPixelsPerSource = viewportPixels / sourceSpan;
        float destinationOffset = (sourceStart - rawOrigin)
                * destinationPixelsPerSource;
        float destinationSpan = (sourceEnd - sourceStart)
                * destinationPixelsPerSource;
        if (!Float.isFinite(destinationOffset)
                || !Float.isFinite(destinationSpan)
                || destinationSpan <= 0.0f) return PixelAlignedAxis.invalid();
        return new PixelAlignedAxis(sourceStart, sourceEnd - sourceStart,
                destinationOffset, destinationSpan, true);
    }

    public static float sourceSpan(int viewportPixels, float renderPixelsPerBlock,
            float displayPixelsPerBlock) {
        if (viewportPixels <= 0
                || !Float.isFinite(renderPixelsPerBlock)
                || !Float.isFinite(displayPixelsPerBlock)
                || renderPixelsPerBlock <= 0.0f
                || displayPixelsPerBlock <= 0.0f) {
            return Float.NaN;
        }
        float result = viewportPixels
                * (renderPixelsPerBlock / displayPixelsPerBlock);
        return Float.isFinite(result) && result > 0.0f
                ? result : Float.NaN;
    }

    public static float sourceOrigin(int overscanPixels, int viewportPixels,
            float sourceSpan, double center, double anchor,
            float renderPixelsPerBlock) {
        if (overscanPixels < 0 || viewportPixels <= 0
                || !Float.isFinite(sourceSpan) || sourceSpan <= 0.0f
                || !Double.isFinite(center) || !Double.isFinite(anchor)
                || !Float.isFinite(renderPixelsPerBlock)
                || renderPixelsPerBlock <= 0.0f) {
            return Float.NaN;
        }
        double result = overscanPixels
                + (viewportPixels - sourceSpan) * 0.5
                + (center - anchor) * renderPixelsPerBlock;
        return Double.isFinite(result) ? (float) result : Float.NaN;
    }

    public record PixelAlignedAxis(float sourceOrigin, float sourceSpan,
            float destinationOffsetPixels, float destinationSpanPixels,
            boolean valid) {
        private static PixelAlignedAxis invalid() {
            return new PixelAlignedAxis(Float.NaN, Float.NaN,
                    Float.NaN, Float.NaN, false);
        }
    }
}

}
