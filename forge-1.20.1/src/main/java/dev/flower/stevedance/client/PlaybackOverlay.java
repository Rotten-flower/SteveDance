package dev.flower.stevedance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.function.IntConsumer;

/** PV 与摄影共用同一进度条和三秒收起动画。 / Shared PV/photo transport and idle animation. */
public final class PlaybackOverlay {
    private static final ResourceLocation MASCOT =
            new ResourceLocation("stevedance", "textures/gui/pv/miku_seiza.png");
    private long lastActivity, lastFrame;
    private float visibility;
    private boolean dragging;
    private int previewMs, duration, width, height;

    public void wake() {
        lastActivity = System.nanoTime();
    }

    public boolean dragging() {
        return dragging;
    }

    public float visibility() {
        return visibility;
    }

    private int left() {
        return width / 8 + 30;
    }

    private int right() {
        return width - width / 8 - 70;
    }

    private int y() {
        return height - 10 + Math.round((1 - visibility) * 24);
    }

    private int tint(int rgb) {
        return (Math.max(1, Math.round(visibility * 255)) << 24) | rgb;
    }

    public void render(
            GuiGraphics g,
            int width,
            int height,
            int time,
            int duration,
            boolean paused,
            Component hint) {
        this.width = width;
        this.height = height;
        this.duration = duration;
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0 : Math.min(.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        float target =
                lastActivity != 0 && (dragging || now - lastActivity < 3_000_000_000L) ? 1 : 0;
        visibility += (target - visibility) * (1 - (float) Math.exp(-dt * 14));
        if (Math.abs(target - visibility) < .005f) {
            visibility = target;
        }
        if (visibility <= 0) {
            return;
        }
        int left = left(), right = right(), y = y();
        if (right <= left) {
            return;
        }
        int shown = dragging ? previewMs : time;
        float progress = Math.max(0, Math.min(1, shown / (float) Math.max(1, duration)));
        int thumb = left + Math.round((right - left) * progress);
        g.fill(left, y, right, y + 3, tint(0x60646b));
        if (thumb > left) {
            g.fill(left, y, thumb, y + 3, tint(0x59c3c8));
        }
        int px = width / 8;
        if (paused) {
            for (int i = 0; i < 12; i++) {
                int half = i / 2;
                g.fill(px + i, y - 5 + half, px + i + 1, y + 7 - half, tint(0xffffff));
            }
        } else {
            g.fill(px, y - 4, px + 4, y + 7, tint(0xffffff));
            g.fill(px + 7, y - 4, px + 11, y + 7, tint(0xffffff));
        }
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.setColor(1, 1, 1, visibility);
        g.blit(MASCOT, thumb - 10, y - 17, 20, 20, 0, 0, 128, 128, 128, 128);
        g.setColor(1, 1, 1, 1);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        var font = Minecraft.getInstance().font;
        String clock = format(shown) + "/" + format(duration);
        g.pose().pushPose();
        g.pose().translate(right + 12, y - 2, 0);
        g.pose().scale(.75f, .75f, 1);
        g.drawString(font, clock, 0, 0, tint(0xffffff), false);
        g.pose().popPose();
        String[] lines = hint.getString().split("\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            float scale = Math.min(.6f, (width * .8f - 24) / (float) Math.max(1, font.width(line)));
            g.pose().pushPose();
            g.pose().translate(width - 8, height - 32 - (lines.length - 1 - i) * 8, 0);
            g.pose().scale(scale, scale, 1);
            g.drawString(font, line, -font.width(line), 0, tint(0xffffff), true);
            g.pose().popPose();
        }
    }

    private static String format(int ms) {
        int sec = Math.max(0, ms) / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
    }

    private int seek(double x) {
        return (int)
                Math.round(
                        duration
                                * Math.max(
                                        0,
                                        Math.min(
                                                1,
                                                (x - left())
                                                        / (double) Math.max(1, right() - left()))));
    }

    public boolean click(double x, double y, int button, boolean allowSeek, Runnable toggle) {
        boolean visible = visibility > .5f;
        wake();
        if (button != 0 || !visible) {
            return false;
        }
        if (allowSeek
                && duration > 0
                && y >= y() - 36
                && y <= y() + 12
                && x >= left() - 10
                && x <= right() + 10) {
            dragging = true;
            previewMs = seek(x);
            return true;
        }
        if (x >= width / 8 - 5 && x < width / 8 + 18 && y >= y() - 10 && y < y() + 14) {
            toggle.run();
            return true;
        }
        return false;
    }

    public boolean drag(double x) {
        wake();
        if (!dragging) {
            return false;
        }
        previewMs = seek(x);
        return true;
    }

    public boolean release(double x, int button, IntConsumer commit) {
        wake();
        if (button != 0 || !dragging) {
            return false;
        }
        previewMs = seek(x);
        dragging = false;
        commit.accept(previewMs);
        return true;
    }
}
