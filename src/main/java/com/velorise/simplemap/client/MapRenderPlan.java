package com.velorise.simplemap.client;

import com.velorise.simplemap.client.gpu.MapGpuInstancePlan;
import com.velorise.simplemap.client.renderer.MapGpuRenderer;
import com.velorise.simplemap.client.gpu.TileKey;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable cache-only draw plan for one quantized viewport.
 *
 * <p>Hierarchy traversal and atlas lookup happen only when the viewport key or
 * global residency topology changes. V16.9 also compacts resolved quads into
 * primitive raw-vertex batches, so replay uses one texture bind and one buffer
 * submission per atlas/phase instead of one {@code GuiGraphics.blit()} per
 * exact page or branch node.</p>
 */
final class MapRenderPlan {
    /** Region-centric M4 coverage always stays below factor-2 refinement. */
    static final int PHASE_REGION_COARSE = 4;
    static final int PHASE_BRANCH_BASE = 16;
    /** Exact fallback drawn immediately below the density-correct L1 branch. */
    static final int PHASE_L1_EXACT_UNDERLAY = 27;
    static final int PHASE_EXACT = 96;
    static final int PHASE_GLOW = 128;

    private final List<Batch> baseBatches;
    private final List<Batch> glowBatches;
    private final MapGpuInstancePlan instancePlan;
    private final int[] basePhases;
    private final int[] glowPhases;
    private final long[] pendingRegions;
    private final MapDrawResult result;
    private final long topologyRevision;
    private final long builtAtNanos;
    private final int quadCount;
    private final int batchCount;

    private MapRenderPlan(List<Batch> baseBatches, List<Batch> glowBatches,
            MapGpuInstancePlan instancePlan,
            int[] basePhases, int[] glowPhases,
            long[] pendingRegions, MapDrawResult result,
            long topologyRevision, long builtAtNanos,
            int quadCount, int batchCount) {
        this.baseBatches = baseBatches;
        this.glowBatches = glowBatches;
        this.instancePlan = instancePlan;
        this.basePhases = basePhases;
        this.glowPhases = glowPhases;
        this.pendingRegions = pendingRegions;
        this.result = result;
        this.topologyRevision = topologyRevision;
        this.builtAtNanos = builtAtNanos;
        this.quadCount = quadCount;
        this.batchCount = batchCount;
    }

    void drawBase(GuiGraphics graphics) {
        drawOrdered(graphics, baseBatches, basePhases);
    }

    void drawGlow(GuiGraphics graphics) {
        drawOrdered(graphics, glowBatches, glowPhases);
    }

    private void drawOrdered(GuiGraphics graphics, List<Batch> batches,
            int[] phases) {
        for (int phase : phases) {
            MapGpuRenderer.drawPhase(graphics, instancePlan, phase);
            MapAtlasBatchRenderer.drawPhase(graphics, batches, phase);
        }
    }

    long[] pendingRegions() {
        return pendingRegions;
    }

    MapDrawResult result() {
        return result;
    }

    int quadCount() {
        return quadCount;
    }

    int batchCount() {
        return batchCount;
    }

    boolean topologyValid(long revision) {
        return topologyRevision == revision;
    }

    boolean valid(long revision, long nowNanos, boolean hasPending) {
        // Residency content revisions now invalidate the owner CachedPlan. Do not
        // rebuild an unchanged cave hierarchy every 200–500 ms merely because a
        // source is pending; branch/leaf publication will advance contentRevision
        // when a renderable atlas slot actually changes.
        return topologyValid(revision);
    }

    static int branchPhase(int level) {
        int clamped = Math.max(1, Math.min(MapLodPolicy.MAX_BRANCH_LEVEL, level));
        // Keep one free phase between adjacent branch levels so L1 can place
        // an exact fallback underneath the branch without colliding with L2.
        return PHASE_BRANCH_BASE
                + (MapLodPolicy.MAX_BRANCH_LEVEL - clamped) * 2;
    }

    static final class Builder {
        private static final int MAX_QUADS = 8_192;
        private static final int FLOATS_PER_VERTEX = 4;
        private static final int VERTICES_PER_QUAD = 4;
        private static final int FLOATS_PER_QUAD = FLOATS_PER_VERTEX * VERTICES_PER_QUAD;

        private final List<Quad> quads = new ArrayList<>();
        private final MapGpuInstancePlan.Builder instances =
                new MapGpuInstancePlan.Builder();
        private final java.util.LinkedHashSet<Long> pending = new java.util.LinkedHashSet<>();
        private int instanceCount;
        private int exactPages;
        private int branchNodes;

        boolean add(ResourceLocation texture, int phase,
                int x, int y, int width, int height,
                float u, float v, int uWidth, int vHeight,
                int textureWidth, int textureHeight) {
            if (texture == null || width <= 0 || height <= 0
                    || uWidth <= 0 || vHeight <= 0
                    || textureWidth <= 0 || textureHeight <= 0
                    || quads.size() >= MAX_QUADS) return false;
            float inverseWidth = 1.0f / textureWidth;
            float inverseHeight = 1.0f / textureHeight;
            float u0 = u * inverseWidth;
            float v0 = v * inverseHeight;
            float u1 = (u + uWidth) * inverseWidth;
            float v1 = (v + vHeight) * inverseHeight;
            quads.add(new Quad(texture, phase, x, y, width, height,
                    u0, v0, u1, v1));
            return true;
        }

        boolean addTile(TileKey key, ResourceLocation fallbackTexture,
                int phase, int x, int y, int width, int height,
                float u, float v, int uWidth, int vHeight,
                int textureWidth, int textureHeight) {
            if (key == null || fallbackTexture == null || width <= 0 || height <= 0
                    || uWidth <= 0 || vHeight <= 0 || textureWidth <= 0
                    || textureHeight <= 0
                    || quads.size() + instanceCount >= MAX_QUADS) return false;
            float inverseWidth = 1.0f / textureWidth;
            float inverseHeight = 1.0f / textureHeight;
            boolean added = instances.add(key, fallbackTexture, phase,
                    x, y, width, height,
                    u * inverseWidth, v * inverseHeight,
                    (u + uWidth) * inverseWidth,
                    (v + vHeight) * inverseHeight);
            if (added) instanceCount++;
            return added;
        }

        void exact() {
            exactPages++;
        }

        void branch() {
            branchNodes++;
        }

        void pending(int regionX, int regionZ) {
            if (pending.size() >= 256) return;
            pending.add(((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL));
        }

        MapRenderPlan build(long topologyRevision) {
            // Preserve coarse-to-fine phases and group equal atlas textures inside
            // each phase. The resulting primitive batches are immutable and contain
            // normalized UVs, so replay performs no atlas arithmetic or quad object
            // allocation on the render path.
            quads.sort(Comparator.comparingInt((Quad q) -> q.phase)
                    .thenComparing(q -> q.texture.toString()));

            List<Batch> base = new ArrayList<>();
            List<Batch> glow = new ArrayList<>();
            int cursor = 0;
            while (cursor < quads.size()) {
                Quad first = quads.get(cursor);
                int end = cursor + 1;
                while (end < quads.size()) {
                    Quad candidate = quads.get(end);
                    if (candidate.phase != first.phase
                            || !candidate.texture.equals(first.texture)) break;
                    end++;
                }
                int groupQuads = end - cursor;
                float[] vertices = new float[groupQuads * FLOATS_PER_QUAD];
                int output = 0;
                for (int index = cursor; index < end; index++) {
                    output = writeQuad(vertices, output, quads.get(index));
                }
                Batch batch = new Batch(first.texture, first.phase,
                        vertices, groupQuads);
                if (first.phase >= PHASE_GLOW) glow.add(batch);
                else base.add(batch);
                cursor = end;
            }

            long[] pendingArray = new long[pending.size()];
            int i = 0;
            for (Long value : pending) pendingArray[i++] = value;
            MapGpuInstancePlan instancePlan = instances.build();
            List<Batch> immutableBase = List.copyOf(base);
            List<Batch> immutableGlow = List.copyOf(glow);
            int[] basePhases = collectPhases(immutableBase, instancePlan, false);
            int[] glowPhases = collectPhases(immutableGlow, instancePlan, true);
            return new MapRenderPlan(immutableBase, immutableGlow,
                    instancePlan, basePhases, glowPhases, pendingArray,
                    new MapDrawResult(exactPages, branchNodes, 0),
                    topologyRevision, System.nanoTime(),
                    quads.size() + instancePlan.size(),
                    base.size() + glow.size() + (instancePlan.size() == 0 ? 0 : 1));
        }

        private static int[] collectPhases(List<Batch> batches,
                MapGpuInstancePlan instances, boolean glow) {
            int[] phases = new int[batches.size() + instances.size()];
            int count = 0;
            for (Batch batch : batches) {
                boolean batchGlow = batch.phase() >= PHASE_GLOW;
                if (batchGlow == glow) phases[count++] = batch.phase();
            }
            for (int index = 0; index < instances.size(); index++) {
                int phase = instances.phase(index);
                boolean instanceGlow = phase >= PHASE_GLOW;
                if (instanceGlow == glow) phases[count++] = phase;
            }
            if (count == 0) return new int[0];
            Arrays.sort(phases, 0, count);
            int unique = 1;
            for (int index = 1; index < count; index++) {
                if (phases[index] != phases[unique - 1]) {
                    phases[unique++] = phases[index];
                }
            }
            return Arrays.copyOf(phases, unique);
        }

        private static int writeQuad(float[] target, int offset, Quad quad) {
            float x0 = quad.x;
            float y0 = quad.y;
            float x1 = quad.x + quad.width;
            float y1 = quad.y + quad.height;

            offset = writeVertex(target, offset, x0, y0, quad.u0, quad.v0);
            offset = writeVertex(target, offset, x0, y1, quad.u0, quad.v1);
            offset = writeVertex(target, offset, x1, y1, quad.u1, quad.v1);
            return writeVertex(target, offset, x1, y0, quad.u1, quad.v0);
        }

        private static int writeVertex(float[] target, int offset,
                float x, float y, float u, float v) {
            target[offset++] = x;
            target[offset++] = y;
            target[offset++] = u;
            target[offset++] = v;
            return offset;
        }
    }

    /** One immutable atlas/phase submission. */
    record Batch(ResourceLocation texture, int phase,
            float[] vertices, int quadCount) {
        Batch {
            if (texture == null) throw new IllegalArgumentException("texture");
            if (vertices == null || vertices.length != quadCount * 16) {
                throw new IllegalArgumentException("Invalid map batch vertex data");
            }
        }
    }

    private record Quad(ResourceLocation texture, int phase,
            int x, int y, int width, int height,
            float u0, float v0, float u1, float v1) {
    }
}
