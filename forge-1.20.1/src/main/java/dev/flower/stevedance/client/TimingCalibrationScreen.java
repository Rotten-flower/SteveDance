package dev.flower.stevedance.client;

import dev.flower.stevedance.core.BeatCalibration;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.Map;

/** 原作四阶段校准页，草稿值仅在确认后保存。 / Four-stage calibration; save only on confirmation. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
        modid = "stevedance",
        value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class TimingCalibrationScreen extends RhythmScreen
        implements PvPlaybackController.WorldView {
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void hideWorldHud(net.minecraftforge.client.event.RenderGuiEvent.Pre event) {
        if (net.minecraft.client.Minecraft.getInstance().screen
                instanceof TimingCalibrationScreen) {
            event.setCanceled(true);
        }
    }

    private enum Phase {
        CONFIRM,
        INSTRUCTIONS,
        SAMPLING,
        RESULT
    }

    private final Screen parent;
    private final BeatCalibration calibration = new BeatCalibration();
    private final int tapKey;
    private Phase phase = Phase.CONFIRM;
    private long samplingStart;
    private boolean running, ended, spaceDown, interrupted;
    private double audioMs;
    private int draft;

    public TimingCalibrationScreen(Screen parent) {
        super("ui.stevedance.calibration.title");
        this.parent = parent;
        ClientPlaybackSettings.load();
        RhythmSettingsScreen.load();
        tapKey = RhythmSettingsScreen.KEYS[1];
        OriginalUiAssets.prepare();
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    @Override
    boolean nativeControls() {
        return true;
    }

    String tapKeyName() {
        return GamepadInput.active()
                ? (GamepadInput.style().equals("ps") ? "○" : "B")
                : RhythmNoteGlyphs.keyName(tapKey);
    }

    boolean sampling() {
        return phase == Phase.SAMPLING;
    }

    boolean showingResult() {
        return phase == Phase.RESULT;
    }

    String footerText() {
        String confirm =
                GamepadInput.active() ? (GamepadInput.style().equals("ps") ? "○" : "A") : "Enter";
        return tr(
                        phase == Phase.SAMPLING
                                ? "hint_tap"
                                : phase == Phase.RESULT ? "hint_result" : "hint_confirm",
                        phase == Phase.SAMPLING ? tapKeyName() : confirm)
                .getString();
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("ui.stevedance.calibration." + key, args);
    }

    private void instructions() {
        phase = Phase.INSTRUCTIONS;
        running = ended = false;
        focus = 0;
        spaceDown = false;
    }

    private void beginSampling() {
        calibration.reset();
        audioMs = 0;
        interrupted = ended = false;
        samplingStart = System.nanoTime();
        phase = Phase.SAMPLING;
        running = true;
        spaceDown = false;
    }

    private void updateClock() {
        if (!running) {
            return;
        }
        if (!minecraft.isWindowActive()) {
            instructions();
            interrupted = true;
            return;
        }
        audioMs = (System.nanoTime() - samplingStart) / 1e6;
        if (calibration.finished(audioMs)) {
            running = false;
            ended = true;
            phase = Phase.RESULT;
            draft = calibration.recommendation();
            focus = 0;
        }
    }

    private void tap() {
        updateClock();
        if (!running) {
            return;
        }
        int before = calibration.count();
        calibration.tap(audioMs);
        if (calibration.count() != before) {
            RhythmSoundPlayer.play("select");
        }
    }

    private void applyResult() {
        ClientPlaybackSettings.offsetMs = draft;
        ClientPlaybackSettings.save();
        RhythmSoundPlayer.play("select");
        minecraft.setScreen(parent);
    }

    private void adjust(int direction) {
        // 数值行保持原作符号，保存时已转换。 / Display the original sign; store the converted sign.
        draft = Math.max(-150, Math.min(150, draft - direction));
        RhythmSoundPlayer.play("select");
    }

    private void center(GuiGraphics g, Component c, int x, int y, float size, int maxWidth) {
        center(g, c, x, y, size, maxWidth, 0xff26303a);
    }

    private void center(
            GuiGraphics g, Component c, int x, int y, float size, int maxWidth, int color) {
        float fit = Math.min(size, maxWidth / (float) Math.max(1, font.width(c)));
        text(g, c, Math.round(x - font.width(c) * fit / 2), y, color, fit);
    }

    private void sprite(
            GuiGraphics g, String scene, String root, float frame, float x, float y, float size) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size, size, 1);
        OriginalUiAssets.draw(g, scene, root, frame);
        g.pose().popPose();
    }

    private void row(
            GuiGraphics g, int mx, int my, int y, int index, Component label, Runnable action) {
        boolean hover =
                virtualX(mx) >= 360 && virtualX(mx) <= 920 && Math.abs(virtualY(my) - y) < 36;
        boolean selected = focus == index || (!GamepadInput.active() && hover);
        sprite(
                g,
                "gameplay_dialogs",
                selected ? "cursor_loop" : "cursor_base",
                OriginalUiAssets.loopFrame(
                        "gameplay_dialogs", selected ? "cursor_loop" : "cursor_base", seconds()),
                640,
                y,
                1);
        center(g, label, 640, y - 13, 2.9f, 490, 0xfff3ffff);
        controls.add(
                new Control(
                        360,
                        y - 36,
                        560,
                        72,
                        label.getString(),
                        () -> {
                            focus = index;
                            action.run();
                        }));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        updateClock();
        start(g);
        if (!OriginalUiAssets.ready()) {
            g.fill(0, 0, 1280, 728, 0xffddecf0);
            center(g, tr("loading"), 640, 350, 3.1f, 1080);
            finish(g);
            return;
        }
        OriginalUiAssets.loop(g, "menu_common", "bg_home", seconds());
        OriginalUiAssets.fullCanvas(
                g, () -> OriginalUiAssets.loop(g, "gameplay_dialogs", "win_bg_all_loop", seconds()));
        if (phase != Phase.SAMPLING) {
            OriginalUiAssets.loop(g, "common_info", "frame_top", seconds());
            OriginalUiAssets.draw(
                    g,
                    "common_info",
                    "frame_top_option",
                    OriginalUiAssets.loopFrame("common_info", "frame_top_option", seconds()),
                    Map.of("p_txt_lt", tr("title").getString()));
        }
        switch (phase) {
            case CONFIRM -> {
                center(g, tr("question"), 640, 250, 3.2f, 1080);
                row(g, mx, my, 390, 0, tr("yes"), this::instructions);
                row(g, mx, my, 480, 1, tr("no"), this::onClose);
            }
            case INSTRUCTIONS -> {
                sprite(g, "settings", "target_maru", 0, 640, 272, 1.33f);
                center(
                        g,
                        tr(interrupted ? "interrupted" : "instructions_one"),
                        640,
                        410,
                        3.1f,
                        1080);
                center(g, tr("instructions_two", tapKeyName()), 640, 458, 3.1f, 1080);
                row(g, mx, my, 570, 0, tr("start"), this::beginSampling);
            }
            case SAMPLING -> {
                int round = calibration.roundIndex(audioMs);
                double local = calibration.roundTime(audioMs);
                if (calibration.recorded(round)) {
                    float frame = (float) ((local - calibration.hitTime(round)) * .06);
                    if (frame < 40) {
                        sprite(g, "settings", "hit_eff00", frame, 640, 364, 1.33f);
                    }
                } else {
                    float size =
                            local <= 3000
                                    ? 1.33f
                                    : (float) Math.max(0, 1.33 * (1 - (local - 3000) / 400));
                    if (size > 0) {
                        sprite(
                                g,
                                "settings",
                                "target_maru",
                                (float) (local * .12),
                                640,
                                364,
                                size);
                        float x = (float) ((520 - local * .0933333358) * (1280.0 / 480));
                        float y =
                                (float) ((136 + 10 * Math.sin(local * .002094395)) * (728.0 / 272));
                        sprite(
                                g,
                                "settings",
                                "button_maru",
                                (float) Math.min(369, local * .06),
                                x,
                                y,
                                size);
                    }
                }
                center(g, tr("instructions_one"), 640, 565, 2.7f, 1080);
                center(g, tr("instructions_two", tapKeyName()), 640, 607, 2.7f, 1080);
            }
            case RESULT -> {
                center(g, tr("result_one"), 640, 174, 3.1f, 1100);
                center(g, tr("result_two"), 640, 222, 3.1f, 1100);
                sprite(g, "gameplay_dialogs", "op_csr_base_b", 30, 640, 340, 1);
                if (focus == 0) {
                    sprite(g, "gameplay_dialogs", "op_csr_base_a", 30, 640, 340, 1);
                }
                center(g, tr("adjust"), 515, 328, 2.7f, 320, 0xfff3ffff);
                center(
                        g,
                        Component.literal((-draft > 0 ? "+" : "") + -draft + " ms"),
                        811,
                        328,
                        2.7f,
                        155);
                center(g, Component.literal("◀"), 715, 328, 2.5f, 40);
                center(g, Component.literal("▶"), 907, 328, 2.5f, 40);
                controls.add(
                        new Control(
                                685,
                                304,
                                72,
                                72,
                                "left",
                                () -> {
                                    focus = 0;
                                    adjust(-1);
                                }));
                controls.add(
                        new Control(
                                867,
                                304,
                                72,
                                72,
                                "right",
                                () -> {
                                    focus = 0;
                                    adjust(1);
                                }));
                row(g, mx, my, 440, 1, tr("retry"), this::instructions);
                row(g, mx, my, 530, 2, tr("apply"), this::applyResult);
            }
        }
        OriginalUiAssets.loop(g, "common_info", "cmn_info_btn_return_b.pic", seconds());
        controls.add(new Control(1188, 0, 92, 72, "ui.stevedance.back", this::onClose));
        finish(g);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (phase == Phase.SAMPLING) {
            if (key == tapKey) {
                if (!spaceDown) {
                    spaceDown = true;
                    tap();
                }
                return true;
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_TAB) {
            int count = phase == Phase.RESULT ? 3 : phase == Phase.CONFIRM ? 2 : 1;
            focus = Math.floorMod(focus + (key == GLFW.GLFW_KEY_UP ? -1 : 1), count);
            return true;
        }
        if (phase == Phase.RESULT
                && focus == 0
                && (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) {
            adjust(key == GLFW.GLFW_KEY_LEFT ? -1 : 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER) {
            RhythmSoundPlayer.play("select");
            switch (phase) {
                case CONFIRM -> {
                    if (focus == 0) {
                        instructions();
                    } else {
                        onClose();
                    }
                }
                case INSTRUCTIONS -> beginSampling();
                case RESULT -> {
                    if (focus == 1) {
                        instructions();
                    } else if (focus == 2) {
                        applyResult();
                    }
                }
                default -> {}
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (key == tapKey) {
            spaceDown = false;
            return true;
        }
        return super.keyReleased(key, scan, mods);
    }

    @Override
    protected void padPressed(int bit) {
        if (phase == Phase.SAMPLING) {
            if (bit == (GamepadInput.style().equals("ps") ? 1 : 2)) {
                tap();
            } else if (bit == 128) {
                onClose();
            }
            return;
        }
        if (bit != 64) {
            super.padPressed(bit);
        }
    }

    @Override
    public void removed() {
        running = false;
        super.removed();
    }

    @Override
    public void onClose() {
        running = false;
        RhythmSoundPlayer.play("cancel");
        minecraft.setScreen(parent);
    }
}
