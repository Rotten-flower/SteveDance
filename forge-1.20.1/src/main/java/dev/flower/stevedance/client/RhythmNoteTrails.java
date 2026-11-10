package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.flower.stevedance.core.RhythmChart;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

/**
 * 两个按住端点共享接近曲线；条带在其头部被按住期间保持存在。
 * <p>The two hold endpoints share the approach curve; the ribbon stays while its head is held.</p>
 */
public final class RhythmNoteTrails {
    private RhythmNoteTrails() {}

    public static float x(RhythmChart.Note n, float p) {
        return dev.flower.stevedance.core.F2NoteMotion.x(n, p);
    }

    public static float y(RhythmChart.Note n, float p) {
        return dev.flower.stevedance.core.F2NoteMotion.y(n, p);
    }

    public static float progress(RhythmChart.Note n, int ms) {
        return Math.max(0, Math.min(1, (ms - n.spawnMs()) / (float) n.flightMs()));
    }

    public static void hold(
            GuiGraphics g, RhythmChart.Note head, RhythmChart.Note tail, int ms, boolean held) {
        float end = progress(head, ms),
                start = Math.min(end, (ms - tail.spawnMs()) / (float) tail.flightMs());
        // 中文：长按可能延伸到视图之外。将几何体限制在头部后方一个行程内。
        // EN: A long hold can extend beyond the view. Bound geometry to one flight behind the head.
        start = Math.max(-1, start);
        if (end - start < .002f) {
            return;
        }
        g.flush();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        var buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        ribbon(g, buffer, head, start, end, 11, 0xff152843, 0xff152843);
        var palette = RhythmNoteGlyphs.label(head);
        ribbon(g, buffer, head, start, end, 8, palette.color(), palette.highlight());
        ribbon(g, buffer, head, start, end, 3, 0xbaffffff, 0x50ffffff);
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.defaultBlendFunc();
    }

    private static void ribbon(
            GuiGraphics g,
            BufferBuilder b,
            RhythmChart.Note n,
            float start,
            float end,
            float halfWidth,
            int left,
            int right) {
        var matrix = g.pose().last().pose();
        int steps = Math.max(8, Math.min(48, (int) Math.ceil((end - start) * 32)));
        for (int i = 0; i < steps; i++) {
            float a = start + (end - start) * i / steps,
                    c = start + (end - start) * (i + 1) / steps;
            float ax = x(n, a) * 4 / 3,
                    ay = y(n, a) * 4 / 3,
                    cx = x(n, c) * 4 / 3,
                    cy = y(n, c) * 4 / 3;
            // 中文：两个相邻段在其共享采样点使用相同的切线。
            // EN: Both neighbouring segments use the same tangent at their shared sample.
            // 中文：当接近路径弯曲时，逐段法线会留下三角形间隙。
            // EN: Per-segment normals leave triangular gaps when the approach path bends.
            float adx = x(n, a + .001f) - x(n, a - .001f), ady = y(n, a + .001f) - y(n, a - .001f);
            float cdx = x(n, c + .001f) - x(n, c - .001f), cdy = y(n, c + .001f) - y(n, c - .001f);
            float al = (float) Math.hypot(adx, ady), cl = (float) Math.hypot(cdx, cdy);
            if (al < .001f || cl < .001f) {
                continue;
            }
            float aox = -ady / al * halfWidth,
                    aoy = adx / al * halfWidth,
                    cox = -cdy / cl * halfWidth,
                    coy = cdx / cl * halfWidth;
            b.vertex(matrix, ax + aox, ay + aoy, 0).color(left).endVertex();
            b.vertex(matrix, cx + cox, cy + coy, 0).color(left).endVertex();
            b.vertex(matrix, cx - cox, cy - coy, 0).color(right).endVertex();
            b.vertex(matrix, ax - aox, ay - aoy, 0).color(right).endVertex();
        }
    }
}
