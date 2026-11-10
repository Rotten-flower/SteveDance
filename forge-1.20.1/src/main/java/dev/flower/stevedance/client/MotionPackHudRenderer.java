package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public final class MotionPackHudRenderer {
    public static final int WHITE = 0xfff5f5ff, PINK = 0xffff599e, CYAN = 0xff54e0ec;
    private static final ResourceLocation[] ICONS = new ResourceLocation[6],
            TARGETS = new ResourceLocation[6];

    static {
        String[] symbols = {"triangle", "square", "cross", "circle", "star", "linked_star"};
        for (int i = 0; i < 6; i++) {
            ICONS[i] = new ResourceLocation("stevedance", "textures/gui/note_" + symbols[i] + ".png");
            TARGETS[i] = new ResourceLocation("stevedance", "textures/gui/target_" + symbols[i] + ".png");
        }
    }

    public static float begin(GuiGraphics g, int width, int height) {
        g.fill(0, 0, width, height, 0xff090a18);
        float s = Math.min(width / 960f, height / 544f);
        g.pose().pushPose();
        g.pose().translate((width - 960 * s) / 2, (height - 544 * s) / 2, 0);
        g.pose().scale(s, s, 1);
        return s;
    }

    public static float overlay(GuiGraphics g, int width, int height) {
        float s = Math.min(width / 960f, height / 544f);
        g.pose().pushPose();
        g.pose().translate((width - 960 * s) / 2, (height - 544 * s) / 2, 0);
        g.pose().scale(s, s, 1);
        return s;
    }

    public static void end(GuiGraphics g) {
        g.pose().popPose();
    }

    public static void backdrop(GuiGraphics g, double seconds, boolean performer) {
        g.fillGradient(0, 0, 960, 544, 0xff211432, 0xff070f1e);
        for (int i = 0; i < 7; i++) {
            int x = (int) (i * 155 + Math.sin(seconds * .7 + i) * 55);
            g.fillGradient(x, 35, x + 50, 430, 0x70535be4, 0x00602b80);
        }
        g.fill(0, 405, 960, 544, 0xff161b34);
        g.fill(0, 405, 960, 408, CYAN);
        for (int y = 421; y < 544; y += 22) {
            g.fill(0, y, 960, y + 1, 0x556372aa);
        }
        for (int i = -6; i <= 6; i++) {
            line(g, 480 + i * 22, 406, 480 + i * 110, 544, 0x556372aa);
        }
        for (int i = 0; i < 16; i++) {
            int x = i * 61;
            int h = 8 + (int) ((Math.sin(seconds * 5 + i * .8) + 1) * 13);
            g.fill(x, 404 - h, x + 35, 404, 0xff463b76);
            g.fill(x, 402 - h, x + 35, 404 - h, i % 2 == 0 ? PINK : CYAN);
        }
    }

    public static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose()
                .mulPose(
                        new org.joml.Quaternionf().rotationZ((float) Math.atan2(y2 - y1, x2 - x1)));
        g.fill(0, -1, (int) Math.hypot(x2 - x1, y2 - y1), 1, color);
        g.pose().popPose();
    }

    public static void icon(GuiGraphics g, int type, int x, int y, int size, boolean target) {
        RenderSystem.enableBlend();
        g.blit(
                (target ? TARGETS : ICONS)[Math.min(5, type)],
                x - size / 2,
                y - size / 2,
                size,
                size,
                0,
                0,
                128,
                128,
                128,
                128);
    }

    public static void text(GuiGraphics g, Component c, int x, int y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(Minecraft.getInstance().font, c, 0, 0, color, false);
        g.pose().popPose();
    }

    public static void center(GuiGraphics g, Component c, int x, int y, int color, float scale) {
        text(g, c, x - (int) (Minecraft.getInstance().font.width(c) * scale / 2), y, color, scale);
    }

    public static void titleBar(GuiGraphics g, Component title, Component subtitle) {
        g.fill(0, 0, 960, 52, 0xef171926);
        g.fill(0, 50, 960, 53, PINK);
        text(g, title, 24, 13, WHITE, 2);
        text(g, subtitle, 700, 23, CYAN, 1.1f);
    }
}
