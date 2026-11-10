package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.glfw.GLFW;

/**
 * 同源完整键帽，局部按压；每个菜单持有自己的有限状态。
 * <p>Complete keycaps with local press motion; state belongs to each menu.</p>
 */
public final class MenuInputIcons {
    private static final ResourceLocation ATLAS =
            new ResourceLocation("stevedance", "textures/gui/input/menu_keys.png");
    private static final String[] NAMES = {
        "↑↓", "←→", "Q / E", "Enter", "Esc", "S", "R", "G", "H", "P", "M", "F1", "V", "F5", "Ctrl",
        "F", "F4", "F6", "F7", "F8"
    };
    private static final int[] WIDTHS = {
        34, 34, 34, 44, 32, 24, 24, 24, 24, 24, 24, 28, 24, 28, 36, 24, 28, 28, 28, 28
    };
    private static final int[][] KEYS = {
        {265, 264},
        {263, 262},
        {81, 69},
        {257},
        {256},
        {83},
        {82},
        {71},
        {72},
        {80},
        {77},
        {290},
        {86},
        {294},
        {341, 345},
        {70},
        {293},
        {295},
        {296},
        {297}
    };
    private static final int ATLAS_HEIGHT = (NAMES.length * 18 + 11) / 12 * 48;
    private final float[] amount = new float[NAMES.length], origin = new float[NAMES.length];
    private final long[] changed = new long[NAMES.length];
    private final boolean[] down = new boolean[NAMES.length];

    public void frame() {
        var mc = Minecraft.getInstance();
        boolean focused = mc.isWindowActive() && mc.player != null;
        long now = System.nanoTime(), window = mc.getWindow().getWindow();
        for (int i = 0; i < NAMES.length; i++) {
            if (!focused) {
                amount[i] = origin[i] = 0;
                down[i] = false;
                changed[i] = now;
                continue;
            }
            boolean pressed = false;
            for (int key : KEYS[i]) {
                pressed |= GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
            }
            if (pressed != down[i]) {
                origin[i] = amount[i];
                changed[i] = now;
                down[i] = pressed;
            }
            float t = Math.min(1, (now - changed[i]) / (pressed ? 120_000_000f : 200_000_000f));
            amount[i] = origin[i] + ((pressed ? 1 : 0) - origin[i]) * t * t * (3 - 2 * t);
        }
    }

    public float width(String name) {
        if (name.isEmpty()) {
            return 0;
        }
        if (name.contains("/") && !name.equals("Q / E")) {
            float w = 0;
            for (String key : name.split("/")) {
                w += width(key.trim()) + 6;
            }
            return w - 6;
        }
        if (name.equals("↑↓←→")) {
            return width("↑↓") + 6 + width("←→");
        }
        if (name.equals("Ctrl+F")) {
            return width("Ctrl") + 18 + width("F");
        }
        int i = java.util.Arrays.asList(NAMES).indexOf(name);
        return i < 0
                ? Math.max(40, Minecraft.getInstance().font.width(name) * 1.8f + 18)
                : WIDTHS[i] * 2 * 40f / 48;
    }

    public float draw(GuiGraphics g, String name, float x, float y) {
        if (name.isEmpty()) {
            return 0;
        }
        if (name.contains("/") && !name.equals("Q / E")) {
            float offset = 0;
            for (String key : name.split("/")) {
                offset += draw(g, key.trim(), x + offset, y) + 6;
            }
            return offset - 6;
        }
        if (name.equals("↑↓←→")) {
            float w = draw(g, "↑↓", x, y);
            draw(g, "←→", x + w + 6, y);
            return width(name);
        }
        if (name.equals("Ctrl+F")) {
            float w = draw(g, "Ctrl", x, y);
            var font = Minecraft.getInstance().font;
            g.pose().pushPose();
            g.pose().translate(x + w + 5, y + 12, 0);
            g.pose().scale(1.4f, 1.4f, 1);
            g.drawString(font, "+", 0, 0, 0xfff0fcff, true);
            g.pose().popPose();
            draw(g, "F", x + w + 18, y);
            return width(name);
        }
        int i = java.util.Arrays.asList(NAMES).indexOf(name);
        if (i < 0) {
            var font = Minecraft.getInstance().font;
            float w = width(name);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            g.setColor(1, 1, 1, 1);
            g.blit(
                    new ResourceLocation("stevedance", "textures/gui/keys/blank-default.png"),
                    (int) x,
                    (int) y,
                    (int) w,
                    40,
                    0,
                    0,
                    54,
                    50,
                    54,
                    50);
            g.pose().pushPose();
            g.pose().translate(x + (w - font.width(name) * 1.8f) / 2, y + 10, 0);
            g.pose().scale(1.8f, 1.8f, 1);
            g.drawString(font, name, 0, 0, 0xff171d20, false);
            g.pose().popPose();
            return w;
        }
        int pose = i * 18 + (down[i] ? 9 : 0) + Math.round(amount[i] * 8), sw = WIDTHS[i] * 2;
        float width = sw * 40f / 48;
        Minecraft.getInstance().getTextureManager().getTexture(ATLAS).setFilter(true, false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1, 1, 1, 1);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(40f / 48, 40f / 48, 1);
        g.blit(ATLAS, 0, 0, pose % 12 * 128, pose / 12 * 48, sw, 48, 1536, ATLAS_HEIGHT);
        g.pose().popPose();
        return width;
    }
}
