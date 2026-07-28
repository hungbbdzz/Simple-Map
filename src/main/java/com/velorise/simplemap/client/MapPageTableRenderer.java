package com.velorise.simplemap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/** Page-table renderer used by M5 transition. Geometry is independent from atlas slots. */
final class MapPageTableRenderer {
    private MapPageTableRenderer() {}

    static boolean draw(GuiGraphics graphics, MapPageTableRenderPlan plan,
            int minimumPhase, int maximumPhase) {
        if (graphics == null || plan == null || plan.count() == 0) return false;
        MapGpuPageTable table = MapGpuPageTable.getInstance();
        table.swapAtFrameBoundary();
        graphics.flush();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        ResourceLocation bound = null;
        BufferBuilder buffer = null;
        Matrix4f matrix = graphics.pose().last().pose();
        int submissions = 0;
        int drawn = 0;
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            for (int i = 0; i < plan.count(); i++) {
                int phase = plan.phase(i);
                if (phase < minimumPhase || phase > maximumPhase) continue;
                MapGpuPageTable.Entry entry = table.front(plan.handle(i));
                if (entry == null || !entry.resident()) continue;
                if (!entry.texture().equals(bound)) {
                    if (buffer != null) {
                        BufferUploader.drawWithShader(buffer.buildOrThrow());
                        submissions++;
                    }
                    bound = entry.texture();
                    RenderSystem.setShaderTexture(0, bound);
                    buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                            DefaultVertexFormat.POSITION_TEX);
                }
                float x0 = plan.x(i), y0 = plan.y(i);
                float x1 = x0 + plan.width(i), y1 = y0 + plan.height(i);
                buffer.addVertex(matrix, x0, y0, 0).setUv(entry.u0(), entry.v0());
                buffer.addVertex(matrix, x0, y1, 0).setUv(entry.u0(), entry.v1());
                buffer.addVertex(matrix, x1, y1, 0).setUv(entry.u1(), entry.v1());
                buffer.addVertex(matrix, x1, y0, 0).setUv(entry.u1(), entry.v0());
                drawn++;
            }
            if (buffer != null) {
                BufferUploader.drawWithShader(buffer.buildOrThrow());
                submissions++;
            }
            MapPipelineTelemetry.getInstance().recordRawBatchSubmissions(submissions);
            return drawn > 0;
        } finally {
            if (depth) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
        }
    }
}
