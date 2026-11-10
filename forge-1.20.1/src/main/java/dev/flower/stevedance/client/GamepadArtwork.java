package dev.flower.stevedance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 为摇杆手势绘制音符符号与紧凑文字，菜单提示使用字幕。
 * <p>Note symbols and compact text for stick gestures; menu hints use subtitles.</p>
 */
public final class GamepadArtwork {
    private GamepadArtwork() {}

    public static String face(int index, String style) {
        return (style.equals("ps")
                        ? new String[] {"△", "○", "×", "□"}
                        : new String[] {"Y", "B", "A", "X"})
                [index];
    }

    public static void sticks(GuiGraphics g, boolean both) {
        var font = Minecraft.getInstance().font;
        String label =
                Component.translatable(
                                both
                                        ? "ui.stevedance.pad.gesture_double_star"
                                        : "ui.stevedance.pad.gesture_star")
                        .getString();
        g.drawString(font, label, -font.width(label) / 2, 35, 0xffffffff, true);
    }
}
