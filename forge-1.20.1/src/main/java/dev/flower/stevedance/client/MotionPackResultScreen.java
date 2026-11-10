package dev.flower.stevedance.client;

import dev.flower.stevedance.core.RhythmSession;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class MotionPackResultScreen extends RhythmScreen {
    private final RhythmSession.Result result;

    public MotionPackResultScreen(RhythmSession.Result result) {
        super("ui.stevedance.result");
        this.result = result;
        OriginalUiAssets.prepare();
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        start(g);
        OriginalUiAssets.loop(g, "menu_common", "bg_home", seconds());
        text(g, title, 52, 44, 0xff133d4c, 2.4f);
        String rank =
                result.perfect()
                        ? "perfect"
                        : result.accuracy() >= 80
                                ? "excellent"
                                : result.accuracy() >= 65
                                        ? "great"
                                        : result.accuracy() >= 50 ? "standard" : "cheap";
        text(g, Component.translatable("rank.stevedance." + rank), 430, 110, 0xffb82d4c, 3.4f);
        text(
                g,
                Component.translatable("ui.stevedance.score", result.score()),
                430,
                190,
                0xff234b55,
                2.5f);
        text(
                g,
                Component.translatable(
                        "ui.stevedance.accuracy",
                        String.format(java.util.Locale.ROOT, "%.2f", result.accuracy())),
                430,
                242,
                0xff156773,
                1.8f);
        int[] counts = {result.cool(), result.fine(), result.safe(), result.sad(), result.worst()};
        String[] keys = {"cool", "fine", "safe", "sad", "worst"};
        for (int i = 0; i < 5; i++) {
            int y = 310 + i * 43;
            text(
                    g,
                    Component.translatable("grade.stevedance." + keys[i]),
                    410,
                    y,
                    0xff234b55,
                    1.8f);
            text(g, Integer.toString(counts[i]), 800, y, 0xff156773, 1.8f);
        }
        text(
                g,
                Component.translatable("ui.stevedance.max_combo", result.maxCombo()),
                430,
                558,
                0xff234b55,
                1.8f);
        control(g, mx, my, 940, 646, 270, 52, "ui.stevedance.song_select", this::onClose);
        finish(g);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(new MotionPackSelectionScreen());
    }
}
