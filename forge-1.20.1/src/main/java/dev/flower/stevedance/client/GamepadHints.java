package dev.flower.stevedance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 在现有字幕位置显示输入相关文字。
 * <p>Input-specific text in the existing subtitle positions.</p>
 */
final class GamepadHints {
    private GamepadHints() {}

    static Component pad(String key) {
        boolean ps = GamepadInput.style().equals("ps");
        return Component.translatable(
                "ui.stevedance.pad." + key,
                ps ? "○" : "A",
                ps ? "×" : "B",
                ps ? "L1" : "LB",
                ps ? "R1" : "RB",
                ps ? "SHARE" : "View",
                ps ? "OPTIONS" : "Menu",
                ps ? "□" : "X",
                ps ? "△" : "Y");
    }

    static Component controls(String keyboard, String pad) {
        return GamepadInput.active() ? pad(pad) : Component.translatable(keyboard);
    }

    static void footer(
            GuiGraphics g, String keyboard, String pad, int x, int y, float scale, int maxWidth) {
        String text = controls(keyboard, pad).getString();
        float size =
                Math.min(scale, maxWidth / (float) Math.max(1, OriginalUiAssets.textWidth(text)));
        OriginalUiAssets.edge(g, true, false, () -> OriginalUiAssets.text(g, text, x, y, size));
    }

    static void viewport(GuiGraphics g, String keyboard, String pad, int width, int height) {
        var font = Minecraft.getInstance().font;
        var text = controls(keyboard, pad);
        float scale = Math.min(1, (width - 24) / (float) Math.max(1, font.width(text)));
        g.pose().pushPose();
        g.pose().translate(12, height - 18, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, 0, 0, 0xffffffff, true);
        g.pose().popPose();
    }

    static void extra(RhythmScreen screen, GuiGraphics g) {
        if (!GamepadInput.active() || screen.framedMenu() || screen instanceof RhythmPauseScreen) {
            return;
        }
        if (screen instanceof SongSelectionScreen
                || screen instanceof SongSetupScreen
                || screen instanceof RhythmSettingsScreen
                || screen instanceof PerformerModelSelectionScreen) {
            return;
        }
        if (screen instanceof RhythmPlayScreen) {
            OriginalUiAssets.text(g, pad("play_controls").getString(), 1080, 84, .72f);
            return;
        }
        if (screen instanceof RhythmResultScreen) {
            footer(g, "ui.stevedance.f2_result_controls", "result_controls", 28, 704, .72f, 1224);
            return;
        }
        footer(g, "ui.stevedance.f2_option_controls", "menu_controls", 28, 704, .72f, 1224);
    }
}
