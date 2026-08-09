package com.velorise.simplemap.client.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.velorise.simplemap.client.MapPipelineTelemetry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/** M5 page-table-backed primitive instance replay. */
public final class MapGpuInstanceRenderer {
    private static final int PHASE_GLOW = 128;

    private MapGpuInstanceRenderer() { }

    public static void draw(GuiGraphics graphics, MapGpuInstancePlan plan,
            boolean glow) {
        drawFiltered(graphics, plan, glow, null);
    }

    /** Replays exactly one global render phase so page-table instances can be
     * interleaved with raw atlas batches without losing coarse-to-fine order. */
    public static void drawPhase(GuiGraphics graphics, MapGpuInstancePlan plan,
            int targetPhase) {
        if (plan == null || !plan.hasPhase(targetPhase)) return;
        drawFiltered(graphics, plan, targetPhase >= PHASE_GLOW, targetPhase);
    }

    /** Draws one phase inside a state scope owned by MapRenderPlan. */
    public static void drawPhasePrepared(MapGpuInstancePlan plan,
            int targetPhase, Matrix4f matrix) {
        if (plan == null || plan.size() == 0 || matrix == null
                || !plan.hasPhase(targetPhase)) return;
        drawPrepared(plan, targetPhase >= PHASE_GLOW, targetPhase, matrix);
    }

    private static void drawFiltered(GuiGraphics graphics,
            MapGpuInstancePlan plan, boolean glow, Integer targetPhase) {
        if (graphics == null || plan == null || plan.size() == 0) return;
        graphics.flush();
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            Matrix4f matrix = graphics.pose().last().pose();
            drawPrepared(plan, glow, targetPhase == null ? -1 : targetPhase,
                    matrix);
        } finally {
            if (depthWasEnabled) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
        }
    }

    private static void drawPrepared(MapGpuInstancePlan plan, boolean glow,
            int targetPhase, Matrix4f matrix) {
        int start;
        int end;
        if (targetPhase >= 0) {
            start = plan.firstIndexAtOrAfter(targetPhase);
            end = plan.firstIndexAfter(targetPhase);
        } else if (glow) {
            start = plan.firstIndexAtOrAfter(PHASE_GLOW);
            end = plan.size();
        } else {
            start = 0;
            end = plan.firstIndexAtOrAfter(PHASE_GLOW);
        }
        if (start >= end) return;

        ResourceLocation activeTexture = null;
        BufferBuilder buffer = null;
        int submissions = 0;
        MapGpuPageTableService.RenderView pageTable =
                MapGpuPageTableService.getInstance().renderView();
        for (int index = start; index < end; index++) {
            PageTableEntry entry = pageTable.entry(plan.key(index));
            ResourceLocation texture = pageTable.texture(entry);
            // A keyed instance is authoritative only while its page-table entry is
            // resident. Replaying cached fallback UVs after eviction can sample a
            // slot that has already been reused by another page. Skip it and let
            // the coverage revision rebuild the plan instead.
            if (entry == null || texture == null || entry.atlasSize() <= 0) continue;
            int requiredCoverage = plan.requiredCoverageMask(index);
            int coverage = entry.coverageMask();
            boolean dynamicSurfaceCoverage =
                    requiredCoverage == MapGpuInstancePlan.DYNAMIC_SURFACE_SUBTILES;
            if (!dynamicSurfaceCoverage && requiredCoverage != 0
                    && (coverage & requiredCoverage) != requiredCoverage) continue;
            if (dynamicSurfaceCoverage && coverage == 0) continue;

            if (!texture.equals(activeTexture)) {
                if (buffer != null) {
                    BufferUploader.drawWithShader(buffer.buildOrThrow());
                    submissions++;
                }
                activeTexture = texture;
                RenderSystem.setShaderTexture(0, texture);
                buffer = Tesselator.getInstance().begin(
                        VertexFormat.Mode.QUADS,
                        DefaultVertexFormat.POSITION_TEX);
            }

            float inverse = 1.0f / entry.atlasSize();
            float sourceSize = entry.sourceSize();
            float localU0 = plan.localU0(index);
            float localV0 = plan.localV0(index);
            float localU1 = plan.localU1(index);
            float localV1 = plan.localV1(index);
            float x0 = plan.x(index);
            float y0 = plan.y(index);
            float width = plan.width(index);
            float height = plan.height(index);

            if (dynamicSurfaceCoverage && coverage != 0xFFFF) {
                /*
                 * Partial Surface page: split only at replay time. This keeps the
                 * immutable plan one-entry-per-page while preserving the rule that
                 * zero-filled unknown cells never cover the retained root underlay.
                 */
                float uvWidth = localU1 - localU0;
                float uvHeight = localV1 - localV0;
                float cellWidth = width * 0.25f;
                float cellHeight = height * 0.25f;
                for (int cellZ = 0; cellZ < 4; cellZ++) {
                    for (int cellX = 0; cellX < 4; cellX++) {
                        int bit = 1 << (cellZ * 4 + cellX);
                        if ((coverage & bit) == 0) continue;
                        float cellLocalU0 = localU0 + uvWidth * cellX * 0.25f;
                        float cellLocalV0 = localV0 + uvHeight * cellZ * 0.25f;
                        float cellLocalU1 = cellLocalU0 + uvWidth * 0.25f;
                        float cellLocalV1 = cellLocalV0 + uvHeight * 0.25f;
                        float u0 = (entry.sourceX()
                                + cellLocalU0 * sourceSize) * inverse;
                        float v0 = (entry.sourceY()
                                + cellLocalV0 * sourceSize) * inverse;
                        float u1 = (entry.sourceX()
                                + cellLocalU1 * sourceSize) * inverse;
                        float v1 = (entry.sourceY()
                                + cellLocalV1 * sourceSize) * inverse;
                        float cellX0 = x0 + cellX * cellWidth;
                        float cellY0 = y0 + cellZ * cellHeight;
                        float cellX1 = cellX0 + cellWidth;
                        float cellY1 = cellY0 + cellHeight;
                        addQuad(buffer, matrix, cellX0, cellY0, cellX1, cellY1,
                                u0, v0, u1, v1);
                    }
                }
                continue;
            }

            float u0 = (entry.sourceX() + localU0 * sourceSize) * inverse;
            float v0 = (entry.sourceY() + localV0 * sourceSize) * inverse;
            float u1 = (entry.sourceX() + localU1 * sourceSize) * inverse;
            float v1 = (entry.sourceY() + localV1 * sourceSize) * inverse;
            addQuad(buffer, matrix, x0, y0, x0 + width, y0 + height,
                    u0, v0, u1, v1);
        }
        if (buffer != null) {
            BufferUploader.drawWithShader(buffer.buildOrThrow());
            submissions++;
        }
        MapPipelineTelemetry.getInstance().recordRawBatchSubmissions(submissions);
    }

    private static void addQuad(BufferBuilder buffer, Matrix4f matrix,
            float x0, float y0, float x1, float y1,
            float u0, float v0, float u1, float v1) {
        buffer.addVertex(matrix, x0, y0, 0.0f).setUv(u0, v0);
        buffer.addVertex(matrix, x0, y1, 0.0f).setUv(u0, v1);
        buffer.addVertex(matrix, x1, y1, 0.0f).setUv(u1, v1);
        buffer.addVertex(matrix, x1, y0, 0.0f).setUv(u1, v0);
    }

}
