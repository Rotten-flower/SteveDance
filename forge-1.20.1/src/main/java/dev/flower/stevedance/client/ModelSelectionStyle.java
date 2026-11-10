package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;

/**
 * 选曲与歌曲三项操作共用青色切角卡片。
 * <p>Shared cyan, clipped-corner cards for selection and the song's three actions.</p>
 */
public final class ModelSelectionStyle {
    public static void panel(GuiGraphics g, float x, float y, float w, float h, int color) {
        g.flush();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        var buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        var matrix = g.pose().last().pose();
        float cut = Math.min(15, Math.min(w, h) / 3);
        float[][] points = {
            {x + cut, y},
            {x + w, y},
            {x + w, y + h - cut},
            {x + w - cut, y + h},
            {x, y + h},
            {x, y + cut}
        };
        for (var p : points) {
            buffer.vertex(matrix, p[0], p[1], 0).color(color).endVertex();
        }
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.enableCull();
    }

    static void label(
            RhythmScreen screen,
            GuiGraphics g,
            Component text,
            int center,
            int y,
            int maxWidth,
            float size,
            int color) {
        var font = net.minecraft.client.Minecraft.getInstance().font;
        size = Math.min(size, maxWidth / (float) Math.max(1, font.width(text)));
        screen.text(g, text, (int) (center - font.width(text) * size / 2), y, color, size);
    }

    /**
     * 两个对角的角标记；模型背景保持完全透明。
     * <p>Two opposite corner marks; the model's background stays fully transparent.</p>
     */
    static void modelCorners(GuiGraphics g, int x, int y, int width, int height, boolean selected) {
        int length = selected ? 28 : 22, thickness = selected ? 3 : 2;
        int color = selected ? 0xff03b9c8 : 0xff536b74;
        g.fill(x, y, x + length, y + thickness, color);
        g.fill(x, y, x + thickness, y + length, color);
        g.fill(x + width - length, y + height - thickness, x + width, y + height, color);
        g.fill(x + width - thickness, y + height - length, x + width, y + height, color);
    }

    static void button(
            RhythmScreen screen,
            GuiGraphics g,
            int mx,
            int my,
            float x,
            float y,
            float w,
            float h,
            String key,
            Runnable action,
            boolean enabled) {
        boolean hover =
                !GamepadInput.active()
                        && screen.virtualX(mx) >= x
                        && screen.virtualX(mx) < x + w
                        && screen.virtualY(my) >= y
                        && screen.virtualY(my) < y + h;
        boolean selected = hover || screen.focus == screen.controls.size();
        nativeButton(g, x, y, w, h, Component.translatable(key), selected, enabled);
        if (enabled) {
            screen.controls.add(new RhythmScreen.Control(x, y, w, h, key, action));
        }
    }

    public static void nativeButton(
            GuiGraphics g,
            float x,
            float y,
            float w,
            float h,
            Component label,
            boolean selected,
            boolean enabled) {
        if (enabled && selected) {
            OriginalUiAssets.spriteSized(
                    g,
                    "song_setup",
                    "song_setup_menu_button_base_sb_actlg",
                    (int) x - 22,
                    (int) y - 18,
                    (int) w + 44,
                    (int) h + 36);
        }
        if (!OriginalUiAssets.spriteSized(
                g,
                "song_setup",
                "song_setup_menu_button_base_sb",
                (int) x,
                (int) y,
                (int) w,
                (int) h)) {
            panel(g, x - 2, y - 2, w + 4, h + 4, enabled && selected ? 0xffa5ffff : 0xffa7b8bb);
            panel(g, x, y, w, h, enabled ? (selected ? 0xeb1ab9c8 : 0xde273b43) : 0xe04e6069);
        }
        var font = net.minecraft.client.Minecraft.getInstance().font;
        float size = Math.min(2, (w - 30) / Math.max(1, font.width(label)));
        g.pose().pushPose();
        g.pose().translate(x + (w - font.width(label) * size) / 2, y + (h - 9 * size) / 2, 0);
        g.pose().scale(size, size, 1);
        g.drawString(font, label, 0, 0, enabled ? 0xffffffff : 0xffb6c9cb, false);
        g.pose().popPose();
    }
}
