package dev.flower.stevedance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.Map;

/**
 * 暂停弹窗覆盖冻结的演出，保留视角与显隐操作。
 * <p>Overlays the frozen performance with pause controls while retaining view and visibility actions.</p>
 */
public final class RhythmPauseScreen extends RhythmScreen
        implements PvPlaybackController.WorldView {
    public RhythmPauseScreen() {
        super("ui.stevedance.f2_pause");
    }

    @Override
    boolean compactMenu() {
        return false;
    }

    @Override
    boolean framedMenu() {
        return false;
    }

    @Override
    boolean nativeControls() {
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_F1) {
            minecraft.setScreen(new RhythmSettingsScreen(this));
            return true;
        }
        if (!PvPlaybackController.chartOnly() && PerformanceControls.key(key)) {
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (!PvPlaybackController.chartOnly()
                && PerformanceControls.click(x, y, button, width, height)) {
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (PvPlaybackController.chartOnly()) {
            return true;
        }
        if (PerformanceControls.scroll(x, y, amount, width, height)) {
            return true;
        }
        PvPlaybackController.zoom(amount);
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (!PvPlaybackController.chartOnly() && button == 1) {
            PvPlaybackController.drag(dx, dy);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        if (PvPlaybackController.chartOnly()) {
            PvPlaybackController.chartBackground(g, width, height);
        }
        start(g);
        boolean entering = seconds() < 16 / 60.0;
        if (entering) {
            OriginalUiAssets.draw(g, "gameplay_dialogs", "gam_win_in", (float) (seconds() * 60));
        } else {
            OriginalUiAssets.loop(g, "gameplay_dialogs", "gam_win_loop", seconds());
        }
        controls.add(
                new Control(
                        448,
                        250,
                        384,
                        48,
                        "ui.stevedance.f2_resume",
                        RhythmClientController::resume));
        controls.add(
                new Control(
                        448,
                        340,
                        384,
                        48,
                        "ui.stevedance.f2_retry",
                        RhythmClientController::retry));
        controls.add(
                new Control(
                        448,
                        430,
                        384,
                        48,
                        "ui.stevedance.f2_return",
                        () -> {
                            RhythmClientController.stop();
                            RhythmClientController.select();
                        }));
        int selected = focus;
        float vx = virtualX(mx), vy = virtualY(my);
        for (int i = 0; i < controls.size(); i++) {
            var c = controls.get(i);
            if (!GamepadInput.active()
                    && vx >= c.x()
                    && vx < c.x() + c.w()
                    && vy >= c.y()
                    && vy < c.y() + c.h()) {
                selected = i;
            }
        }
        if (!entering) {
            g.pose().pushPose();
            g.pose().translate(640, 274 + selected * 90, 0);
            OriginalUiAssets.loop(g, "gameplay_dialogs", "cursor_loop", seconds());
            g.pose().popPose();
            OriginalUiAssets.draw(
                    g,
                    "gameplay_dialogs",
                    "gam_txt_loop",
                    0,
                    Map.of(
                            "gam_win_mm_btn_txt_resume.pic",
                                    Component.translatable("ui.stevedance.f2_resume").getString(),
                            "gam_win_mm_btn_txt_retry.pic",
                                    Component.translatable("ui.stevedance.f2_retry").getString(),
                            "gam_win_mm_btn_txt_return.pic",
                                    Component.translatable("ui.stevedance.f2_return").getString()));
        }
        finish(g);
        if (!PvPlaybackController.chartOnly()) {
            PerformanceControls.draw(g, width, height, mx, my);
        }
        String hint =
                PvPlaybackController.chartOnly()
                        ? Component.translatable("ui.stevedance.pause.chart_hint").getString()
                        : GamepadHints.controls("ui.stevedance.pause.view_hint", "pause_controls")
                                .getString();
        float size = Math.min(.65f, (width - 16) / (float) Math.max(1, font.width(hint)));
        g.pose().pushPose();
        g.pose().translate(8, height - 12, 0);
        g.pose().scale(size, size, 1);
        g.drawString(font, hint, 0, 0, 0xfff3ffff, true);
        g.pose().popPose();
    }

    @Override
    public void onClose() {
        RhythmClientController.resume();
    }
}
