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

    private static void drawFiltered(GuiGraphics graphics,
            MapGpuInstancePlan plan, boolean glow, Integer targetPhase) {
        if (graphics == null || plan == null || plan.size() == 0) return;
        graphics.flush();
        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            Matrix4f matrix = graphics.pose().last().pose();
            ResourceLocation activeTexture = null;
            BufferBuilder buffer = null;
            int submissions = 0;
            for (int index = 0; index < plan.size(); index++) {
                int phase = plan.phase(index);
                boolean instanceGlow = phase >= PHASE_GLOW;
                if (instanceGlow != glow
                        || (targetPhase != null && phase != targetPhase)) continue;
                MapGpuPageTableService.Resolved resolved =
                        MapGpuPageTableService.getInstance().resolve(plan.key(index));
                ResourceLocation texture;
                float u0;
                float v0;
                float u1;
                float v1;
                if (resolved != null) {
                    PageTableEntry entry = resolved.entry();
                    texture = resolved.texture();
                    float inverse = 1.0f / entry.atlasSize();
                    u0 = entry.sourceX() * inverse;
                    v0 = entry.sourceY() * inverse;
                    u1 = (entry.sourceX() + entry.sourceSize()) * inverse;
                    v1 = (entry.sourceY() + entry.sourceSize()) * inverse;
                } else {
                    texture = plan.fallbackTexture(index);
                    u0 = plan.fallbackU0(index);
                    v0 = plan.fallbackV0(index);
                    u1 = plan.fallbackU1(index);
                    v1 = plan.fallbackV1(index);
                }
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
                float x0 = plan.x(index);
                float y0 = plan.y(index);
                float x1 = x0 + plan.width(index);
                float y1 = y0 + plan.height(index);
                buffer.addVertex(matrix, x0, y0, 0.0f).setUv(u0, v0);
                buffer.addVertex(matrix, x0, y1, 0.0f).setUv(u0, v1);
                buffer.addVertex(matrix, x1, y1, 0.0f).setUv(u1, v1);
                buffer.addVertex(matrix, x1, y0, 0.0f).setUv(u1, v0);
            }
            if (buffer != null) {
                BufferUploader.drawWithShader(buffer.buildOrThrow());
                submissions++;
            }
            MapPipelineTelemetry.getInstance().recordRawBatchSubmissions(submissions);
        } finally {
            if (depthWasEnabled) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
        }
    }
}
