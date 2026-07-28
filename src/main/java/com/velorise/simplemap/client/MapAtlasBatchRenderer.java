package com.velorise.simplemap.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Raw atlas batch replay for {@link MapRenderPlan}.
 *
 * <p>GuiGraphics queues must be flushed before direct BufferUploader work so the
 * cave backdrop and any prior GUI commands remain ordered. Depth is disabled for
 * the duration of the map batches; coarse-to-fine phase order then provides the
 * exact same overlay semantics as sequential GUI blits without z-fighting.</p>
 */
final class MapAtlasBatchRenderer {
    private static final int FLOATS_PER_VERTEX = 4;

    private MapAtlasBatchRenderer() {
    }

    static void draw(GuiGraphics graphics, List<MapRenderPlan.Batch> batches) {
        drawFiltered(graphics, batches, null);
    }

    static void drawPhase(GuiGraphics graphics,
            List<MapRenderPlan.Batch> batches, int targetPhase) {
        if (!hasPhase(batches, targetPhase)) return;
        drawFiltered(graphics, batches, targetPhase);
    }

    private static boolean hasPhase(List<MapRenderPlan.Batch> batches,
            int targetPhase) {
        if (batches == null || batches.isEmpty()) return false;
        for (MapRenderPlan.Batch batch : batches) {
            if (batch.phase() == targetPhase) return true;
            if (batch.phase() > targetPhase) return false;
        }
        return false;
    }

    private static void drawFiltered(GuiGraphics graphics,
            List<MapRenderPlan.Batch> batches, Integer targetPhase) {
        if (graphics == null || batches == null || batches.isEmpty()) return;

        // Preserve ordering with GuiGraphics.fill()/blit() commands emitted before
        // the raw map batches. Later GUI overlays will start a fresh buffer.
        graphics.flush();

        boolean depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.disableDepthTest();
        try {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            Matrix4f matrix = graphics.pose().last().pose();
            int submissions = 0;
            for (MapRenderPlan.Batch batch : batches) {
                if (targetPhase != null && batch.phase() != targetPhase) continue;
                float[] vertices = batch.vertices();
                if (vertices.length == 0) continue;
                RenderSystem.setShaderTexture(0, batch.texture());
                BufferBuilder buffer = Tesselator.getInstance().begin(
                        VertexFormat.Mode.QUADS,
                        DefaultVertexFormat.POSITION_TEX);
                for (int index = 0; index < vertices.length;
                        index += FLOATS_PER_VERTEX) {
                    buffer.addVertex(matrix,
                            vertices[index], vertices[index + 1], 0.0f)
                            .setUv(vertices[index + 2], vertices[index + 3]);
                }
                BufferUploader.drawWithShader(buffer.buildOrThrow());
                submissions++;
            }
            MapPipelineTelemetry.getInstance()
                    .recordRawBatchSubmissions(submissions);
        } finally {
            if (depthWasEnabled) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
        }
    }
}
