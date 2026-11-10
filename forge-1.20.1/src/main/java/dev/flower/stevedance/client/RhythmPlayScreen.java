package dev.flower.stevedance.client;

import dev.flower.stevedance.core.F2NoteMotion;
import dev.flower.stevedance.core.F2Scratch;
import dev.flower.stevedance.core.RhythmChart;
import dev.flower.stevedance.core.RhythmInputFeedback;
import dev.flower.stevedance.core.RhythmJudgeSession;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 实际关卡中舞台和玩家上方的原始二维HUD。
 * <p>Original two dimensional HUD over the stage and player in the actual level.</p>
 */
public final class RhythmPlayScreen extends RhythmScreen implements PvPlaybackController.WorldView {
    private static final String[] SYMBOL = {"sankaku", "maru", "batsu", "shikaku"};
    private static final String[] DOUBLE_SYMBOL = {"up_w", "right_w", "down_w", "left_w"};
    private int keyboardBits, padBits, previous, firstVisible;
    private boolean startDown, controllerConnected, padUsed;
    private int padEpoch = -1;
    private final Map<Integer, Integer> keyboardHeld = new HashMap<>();
    private final F2Scratch leftScratch = new F2Scratch(), rightScratch = new F2Scratch();
    private final dev.flower.stevedance.client.RhythmPerformanceHud performanceHud =
            new dev.flower.stevedance.client.RhythmPerformanceHud();

    public RhythmPlayScreen() {
        super("ui.stevedance.rhythm");
        // 纯谱面也使用共享字幕，在演奏界面创建时读取，不逐帧加载。 / Load chart subtitles once per play screen.
        if (PvPlaybackController.chartOnly()) {
            try {
                PvLyrics.install(
                        PvLyrics.load(
                                dev.flower.stevedance.BundledSongResources.nativeSong(
                                        RhythmClientController.song),
                                null));
            } catch (java.io.IOException | RuntimeException error) {
                dev.flower.stevedance.SteveDanceMod.LOGGER.warn(
                        "Chart subtitles unavailable for {}", RhythmClientController.song, error);
            }
        }
    }

    public void resumeInputs() {
        // 中文：暂停界面已转发释放。同时读取物理状态以处理
        // EN: Pause screens already forward releases. Read the physical state as well for
        // 中文：窗口失焦期间或更改绑定后发生的释放。
        // EN: releases made while the window was unfocused, or after changing bindings.
        keyboardBits = padBits = 0;
        keyboardHeld.clear();
        long window = net.minecraft.client.Minecraft.getInstance().getWindow().getWindow();
        previous =
                RhythmClientController.session == null ? 0 : RhythmClientController.session.held();
        RhythmSettingsScreen.load();
        for (int i = 0; i < 5; i++) {
            int key = RhythmSettingsScreen.KEYS[i];
            if (GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS) {
                int bit = pressBit(key);
                keyboardHeld.put(key, bit);
                keyboardBits |= bit;
            }
        }
        GamepadInput.navigate(this);
        padEpoch = GamepadInput.epoch();
        padBits = dev.flower.stevedance.core.GamepadNavigation.rhythmFaces(GamepadInput.buttons());
        long now = System.nanoTime() / 1_000_000;
        leftScratch.prime(GamepadInput.axis(0), GamepadInput.axis(1), now);
        rightScratch.prime(GamepadInput.axis(2), GamepadInput.axis(3), now);
        startDown = (GamepadInput.buttons() & (1 << 7)) != 0;
        updateBits();
    }

    private static int pressBit(int key) {
        for (int i = 0; i < 5; i++) {
            if (RhythmSettingsScreen.KEYS[i] == key) {
                return i == 4
                        ? RhythmJudgeSession.KEYBOARD_LINK
                        : RhythmJudgeSession.keyboardFace(i);
            }
        }
        return 0;
    }

    private void updateBits() {
        if (!PvPlaybackController.clockReady()) {
            return;
        }
        int bits = keyboardBits | padBits, changed = bits ^ previous;
        int released = changed & ~bits, pressed = changed & bits;
        if (released != 0) {
            RhythmClientController.input(released, false);
        }
        if (pressed != 0) {
            RhythmClientController.input(pressed, true);
        }
        previous = bits;
    }

    /**
     * 焦点变化也必须释放键盘边沿，即使GLFW无法传递key-up。
     * <p>Focus changes must release keyboard edges too, even if GLFW cannot deliver key-up.</p>
     */
    public void releaseKeyboardInputs() {
        keyboardHeld.clear();
        keyboardBits = 0;
        updateBits();
    }

    private void pollPad() {
        GamepadInput.navigate(this);
        controllerConnected = GamepadInput.active();
        boolean changed = padEpoch >= 0 && padEpoch != GamepadInput.epoch();
        padEpoch = GamepadInput.epoch();
        if (!GamepadInput.focused() || (changed && padUsed)) {
            padBits = 0;
            startDown = false;
            leftScratch.reset();
            rightScratch.reset();
            releaseKeyboardInputs();
            updateBits();
            RhythmClientController.pause();
            return;
        }
        padBits = dev.flower.stevedance.core.GamepadNavigation.rhythmFaces(GamepadInput.buttons());
        if (GamepadInput.active()) {
            padUsed = true;
        }
        long now = System.nanoTime() / 1_000_000;
        leftScratch.sensitivity(RhythmSettingsScreen.padSensitivity);
        rightScratch.sensitivity(RhythmSettingsScreen.padSensitivity);
        if (leftScratch.update(GamepadInput.axis(0), GamepadInput.axis(1), now)) {
            padBits |= 256;
        }
        if (rightScratch.update(GamepadInput.axis(2), GamepadInput.axis(3), now)) {
            padBits |= 512;
        }
        boolean pause = (GamepadInput.buttons() & (1 << 7)) != 0;
        updateBits();
        if (pause && !startDown) {
            RhythmClientController.pause();
        }
        startDown = pause;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            RhythmClientController.pause();
            return true;
        }
        if (PvPlaybackController.chartOnly() && key == GLFW.GLFW_KEY_F1) {
            RhythmClientController.pauseForSettings();
            return true;
        }
        if (!PvPlaybackController.chartOnly()
                && dev.flower.stevedance.client.PerformanceControls.key(key)) {
            return true;
        }
        if (keyboardHeld.containsKey(key)) {
            return true;
        }
        int bit = pressBit(key);
        if (bit != 0) {
            keyboardHeld.put(key, bit);
        }
        keyboardBits = 0;
        for (int held : keyboardHeld.values()) {
            keyboardBits |= held;
        }
        updateBits();
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        keyboardHeld.remove(key);
        keyboardBits = 0;
        for (int held : keyboardHeld.values()) {
            keyboardBits |= held;
        }
        updateBits();
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (!PvPlaybackController.chartOnly()) {
            dev.flower.stevedance.client.PerformanceControls.click(x, y, button, width, height);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        return !PvPlaybackController.chartOnly()
                && dev.flower.stevedance.client.PerformanceControls.scroll(
                        x, y, amount, width, height);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        pollPad();
        var s = RhythmClientController.session;
        if (s == null) {
            return;
        }
        int ms = RhythmClientController.timeMs();
        RhythmClientController.advance(ms);
        // 中文：两种模式共享已创作的前景叠加层。BACK层保持在世界几何体之前。
        // EN: Both modes share authored front overlays. BACK layers remain before world geometry.
        if (PvPlaybackController.chartOnly()) {
            PvPlaybackController.chartBackground(g, width, height);
        }
        if (!PvPlaybackController.chartOnly() && PvAetTimeline.ready()) {
            PvAetTimeline.draw(g, width, height, PvPlaybackController.timeMs());
        }
        start(g);
        // 中文：原始皮肤和AET HUD使用1280 x 728布局。
        // EN: The original skins and AET HUD use a 1280 x 728 layout.
        performanceHud.draw(
                g,
                s,
                ms,
                RhythmClientController.difficulty,
                dev.flower.stevedance.client.SongText.title(
                                dev.flower.stevedance.SongSelectionCatalog.displayTitleKey(
                                        RhythmClientController.song))
                        .getString());
        drawNotes(g, s, ms);
        List<RhythmJudgeSession.Hit> hits = s.hitsSince(0);
        for (var h : hits) {
            int age = ms - h.timeMs();
            if (age < 0 || age > 680) {
                continue;
            }
            var n = s.chart.notes().get(RhythmInputFeedback.feedbackNote(s.chart, h));
            if (n.kind() == RhythmChart.Kind.RUSH && h.grade().ordinal() <= 2 && !h.wrong()) {
            } else if (h.grade().ordinal() < 2 && !h.wrong()) {
                successfulTarget(g, s.chart, h, age);
                g.pose().pushPose();
                g.pose().translate(n.x() * 4 / 3, n.y() * 4 / 3, 0);
                OriginalUiAssets.draw(g, "gameplay_hud", "hit_eff00", age * .06f);
                g.pose().popPose();
            } else {
                failedTarget(g, s.chart, h, ms);
            }
            String[] gradeLayers = {
                "value_text_cool01",
                "value_text_fine01",
                "value_text_safe",
                "value_text_sad",
                "value_text_cool_r",
                "value_text_fine_r",
                "value_text_fine_b",
                "value_text_safe_b",
                "value_text_sad_b",
                "value_text_worst"
            };
            int gradeIndex =
                    h.wrong()
                            ? h.grade().ordinal() + 4
                            : h.grade().ordinal() == 4 ? 8 : h.grade().ordinal();
            String name = gradeLayers[gradeIndex];
            Map<String, String> digits = new HashMap<>();
            String number =
                    h.grade().ordinal() < 2 && !h.wrong() && h.combo() > 0
                            ? Integer.toString(Math.min(9999, h.combo()))
                            : "";
            for (int i = 0; i < 4; i++) {
                digits.put(
                        "p_combo2_num0" + (i + 1) + "_c",
                        i < number.length() ? number.substring(i, i + 1) : "");
            }
            g.pose().pushPose();
            g.pose()
                    .translate(
                            Math.max(52, Math.min(1110, n.x() * 4 / 3)),
                            Math.max(66, n.y() * 4 / 3 - 50),
                            0);
            OriginalUiAssets.draw(g, "gameplay_hud", name, Math.min(38, age * .06f), digits);
            g.pose().popPose();
        }
        for (var z : s.chart.zones()) {
            int age = ms - z.startMs();
            if (z.kind().equalsIgnoreCase("TECHNICAL")) {
                int failure = s.zoneFailureMs(z);
                if (failure >= 0) {
                    int exitAge = ms - failure;
                    if (exitAge >= 0 && exitAge < 400) {
                        g.pose().pushPose();
                        g.pose().translate(0, -24f * exitAge / 400, 0);
                        OriginalUiAssets.drawGray(
                                g,
                                "gameplay_hud",
                                "bonus_zone",
                                OriginalUiAssets.loopFrame("gameplay_hud", "bonus_zone", seconds()),
                                Map.of("p_notes_num_rt", Integer.toString(s.remaining(z))),
                                1 - exitAge / 400f);
                        g.pose().popPose();
                    }
                } else {
                    if (age >= 0 && ms <= z.endMs() + RhythmJudgeSession.HOLD_TAIL_MS) {
                        OriginalUiAssets.draw(
                                g,
                                "gameplay_hud",
                                "bonus_zone",
                                OriginalUiAssets.loopFrame("gameplay_hud", "bonus_zone", seconds()),
                                Map.of("p_notes_num_rt", Integer.toString(s.remaining(z))));
                    }
                    if (age >= 0 && age < 1500) {
                        OriginalUiAssets.draw(
                                g, "gameplay_hud", "bonus_start_txt", Math.min(79, age * .06f));
                    }
                    int after = ms - z.endMs() - RhythmJudgeSession.HOLD_TAIL_MS;
                    if (after > 0 && after < 1800 && s.zoneGood(z)) {
                        OriginalUiAssets.draw(
                                g, "gameplay_hud", "bonus_complete_txt", Math.min(79, after * .06f));
                    }
                }
            } else if (z.kind().equalsIgnoreCase("CHANCE")) {
                if (age >= 0 && ms <= z.endMs() && age < 3000) {
                    OriginalUiAssets.draw(g, "gameplay_hud", "chance_start_txt", age * .06f);
                }
                int after = ms - z.endMs();
                if (after > 0 && after < 3000) {
                    OriginalUiAssets.draw(
                            g,
                            "gameplay_hud",
                            s.chanceSucceeded(s.chart.zones().indexOf(z))
                                    ? "chance_result_success_txt"
                                    : "chance_end_txt",
                            after * .06f);
                }
            }
        }
        var result = s.result();
        int completedAge =
                ms
                        - s.chart.notes().get(s.chart.notes().size() - 1).timeMs()
                        - RhythmJudgeSession.SAD_MS;
        if (completedAge >= 0
                && completedAge < 3167
                && result.judged() == result.total()
                && result.cool() + result.fine() == result.total()
                && result.wrong() == 0) {
            OriginalUiAssets.draw(g, "gameplay_hud", "rank_perfect", completedAge * .06f);
        }
        if (ms < 0) {
            text(g, Component.translatable("ui.stevedance.f2_ready"), 534, 335, 0xffffffff, 2.8f);
        }
        if (RhythmClientController.waiting) {
            text(
                    g,
                    Component.translatable("ui.stevedance.f2_wait_result"),
                    492,
                    338,
                    0xffffffff,
                    1.8f);
        }
        finish(g);
        PvLyrics.drawInRhythmHud(g, width, height, PvPlaybackController.timeMs());
        if (ms < 0) {
            g.drawString(
                    font,
                    Component.translatable("ui.stevedance.performance.keys"),
                    8,
                    height - 12,
                    0xffd8ffff,
                    true);
        }
        if (!PvPlaybackController.chartOnly()) {
            dev.flower.stevedance.client.PerformanceControls.draw(g, width, height, mx, my);
        }
    }

    private void drawNotes(GuiGraphics g, RhythmJudgeSession session, int ms) {
        List<RhythmChart.Note> notes = session.chart.notes();
        while (firstVisible < notes.size()
                && notes.get(firstVisible).timeMs()
                                + RhythmJudgeSession.timingWindow(notes.get(firstVisible))
                                + 250
                        < ms) {
            firstVisible++;
        }
        for (int rush : session.activeRushes(ms)) {
            var n = notes.get(rush);
            float growth = session.rushScale(rush);
            float frame =
                    369 + 360f * Math.max(0, ms - n.timeMs()) / Math.max(1, n.endMs() - n.timeMs());
            g.pose().pushPose();
            rushPosition(g, n, session.rushStart(rush));
            g.pose().scale(growth, growth, 1);
            OriginalUiAssets.rushNote(
                    g,
                    "button_" + SYMBOL[n.button()] + "_bal",
                    Math.min(728.999f, frame),
                    n,
                    controllerConnected,
                    false,
                    Map.of());
            OriginalUiAssets.rushNote(
                    g,
                    "target_" + SYMBOL[n.button()] + "_bal",
                    359,
                    n,
                    controllerConnected,
                    true,
                    rushLabel(n));
            int age = ms - session.rushStart(rush);
            OriginalUiAssets.rushNote(
                    g,
                    "target_balloon_eff",
                    age * .06f % 240,
                    n,
                    controllerConnected,
                    false,
                    balloon(n));
            g.pose().popPose();
        }
        for (var event : session.rushEventsSince(0)) {
            int age = ms - event.timeMs();
            if (age < 0 || age > 667 || event.phase() == RhythmJudgeSession.RushPhase.START) {
                continue;
            }
            var n = notes.get(event.note());
            g.pose().pushPose();
            rushPosition(g, n, session.rushStart(event.note()));
            g.pose().scale(event.scale(), event.scale(), 1);
            if (event.phase() == RhythmJudgeSession.RushPhase.PRESS) {
                g.pose().scale(.7f, .7f, 1);
                OriginalUiAssets.rushEffect(g, "hit_eff00", age * .06f, .6f);
            } else if (event.complete()) {
                OriginalUiAssets.rushNote(
                        g, "bal_hit_eff", age * .06f, n, controllerConnected, false, Map.of());
            } else {
                OriginalUiAssets.rushEffect(g, "hit_eff00", age * .06f, 1);
            }
            g.pose().popPose();
        }
        // 中文：头部判断后，释放条目保持在可见范围内。据此绘制
        // EN: Release entries stay in the visible range after the head's judgment. Draw through
        // that
        // 中文：配对。
        // EN: pair.
        for (int i = firstVisible; i < notes.size(); i++) {
            var tail = notes.get(i);
            if (tail.spawnMs() > ms + 10000) {
                break;
            }
            if (!session.holdTailVisible(i)) {
                continue;
            }
            var head = notes.get(tail.pair());
            if (head.spawnMs() > ms) {
                continue;
            }
            RhythmNoteTrails.hold(g, head, tail, ms, session.holding(tail.pair()));
            if (session.holding(tail.pair())) {
                g.pose().pushPose();
                g.pose().translate(head.x() * 4 / 3, head.y() * 4 / 3, 0);
                if (ms < head.endMs()) {
                    OriginalUiAssets.holdEffect(g, ms - head.timeMs());
                }
                float holdFrame =
                        Math.max(
                                0,
                                Math.min(
                                        359,
                                        360f
                                                * (ms - head.timeMs())
                                                / Math.max(1, tail.timeMs() - head.timeMs())));
                OriginalUiAssets.note(
                        g,
                        "target_" + SYMBOL[head.button()] + "_long",
                        holdFrame,
                        head,
                        controllerConnected);
                g.pose().popPose();
            }
        }
        // 中文：在目标空间连接原生链接链。
        // EN: Connect the native link chain in the target space.
        for (int i = firstVisible; i < notes.size() - 1; i++) {
            var n = notes.get(i);
            if (n.spawnMs() > ms) {
                break;
            }
            var next = notes.get(i + 1);
            if (n.kind() != RhythmChart.Kind.LINK
                    || n.linkEnd()
                    || next.chain() != n.chain()
                    || next.spawnMs() > ms
                    || session.status(i) != 0) {
                continue;
            }
            link(g, n.x() * 4 / 3, n.y() * 4 / 3, next.x() * 4 / 3, next.y() * 4 / 3);
        }
        for (int i = firstVisible; i < notes.size(); i++) {
            var n = notes.get(i);
            if (n.spawnMs() > ms) {
                break;
            }
            if (session.status(i) != 0
                    || n.kind() == RhythmChart.Kind.HOLD
                            && n.release()
                            && !session.holdTailVisible(i)) {
                continue;
            }
            float p = F2NoteMotion.progress(n, ms), scale = F2NoteMotion.enterScale(n, ms);
            String icon =
                    switch (n.kind()) {
                        case STAR -> "touch";
                        case DOUBLE_STAR -> "touch_w";
                        case LINK -> "link";
                        case CHANCE_STAR -> "touch_ch";
                        case HOLD -> SYMBOL[n.button()] + "_long";
                        case DOUBLE -> DOUBLE_SYMBOL[n.button()];
                        default -> SYMBOL[n.button()];
                    };
            // 中文：释放返回第一个目标；它不会创建另一个目标/手。
            // EN: A release travels back to the first target; it does not create another
            // target/hand.
            if (!(n.kind() == RhythmChart.Kind.HOLD && n.release())) {
                g.pose().pushPose();
                g.pose().translate(n.x() * 4 / 3, n.y() * 4 / 3, 0);
                g.pose().scale(scale, scale, 1);
                if (n.kind() == RhythmChart.Kind.RUSH) {
                    OriginalUiAssets.rushNote(
                            g,
                            "target_" + icon + "_bal",
                            Math.min(539, p * 360),
                            n,
                            controllerConnected,
                            false,
                            rushLabel(n));
                } else {
                    OriginalUiAssets.note(
                            g, "target_" + icon, Math.min(539, p * 360), n, controllerConnected);
                }
                g.pose().popPose();

                int entryAge = ms - n.spawnMs();
                if (entryAge >= 0 && entryAge < 834) {
                    g.pose().pushPose();
                    g.pose().translate(n.x() * 4 / 3, n.y() * 4 / 3, 0);
                    g.pose().scale(scale, scale, 1);
                    OriginalUiAssets.draw(g, "gameplay_hud", "target_eff", entryAge * .06f);
                    g.pose().popPose();
                }
            }
            // 中文：原生DSC方向、距离和波形参数决定接近路径。
            // EN: Native DSC direction, distance and wave parameters determine the approach path.
            float bx = RhythmNoteTrails.x(n, p), by = RhythmNoteTrails.y(n, p);
            g.pose().pushPose();
            g.pose().translate(bx * 4 / 3, by * 4 / 3, 0);
            g.pose().scale(scale, scale, 1);
            if (n.kind() == RhythmChart.Kind.RUSH) {
                OriginalUiAssets.rushNote(
                        g,
                        "button_" + icon + "_bal",
                        Math.min(359, p * 360),
                        n,
                        controllerConnected,
                        false,
                        Map.of());
            } else {
                OriginalUiAssets.note(
                        g, "button_" + icon, Math.min(359, p * 360), n, controllerConnected);
            }
            g.pose().popPose();
        }
    }

    private static Map<String, String> rushLabel(RhythmChart.Note n) {
        return Map.of(
                "p_rush",
                "@rush_notes/gameplay_hud_rush_text_icon_"
                        + SYMBOL[n.button()].toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> balloon(RhythmChart.Note n) {
        String sprite =
                "@rush_notes/gameplay_hud_button_" + SYMBOL[n.button()].toLowerCase(Locale.ROOT);
        return Map.of("p_balloon01", sprite, "p_balloon02", sprite, "p_balloon03", sprite);
    }

    private static void rushPosition(GuiGraphics g, RhythmChart.Note n, int started) {
        float progress = F2NoteMotion.progress(n, started);
        g.pose()
                .translate(
                        RhythmNoteTrails.x(n, progress) * 4 / 3,
                        RhythmNoteTrails.y(n, progress) * 4 / 3,
                        0);
    }

    private static String icon(RhythmChart.Note n) {
        return switch (n.kind()) {
            case STAR -> "touch";
            case DOUBLE_STAR -> "touch_w";
            case LINK -> "link";
            case CHANCE_STAR -> "touch_ch_miss";
            case HOLD -> SYMBOL[n.button()] + "_long";
            case DOUBLE -> DOUBLE_SYMBOL[n.button()];
            default -> SYMBOL[n.button()];
        };
    }

    private void successfulTarget(
            GuiGraphics g, RhythmChart chart, RhythmJudgeSession.Hit hit, int age) {
        var moving = chart.notes().get(hit.note());
        if (moving.kind() == RhythmChart.Kind.HOLD && !moving.release()) {
            return;
        }
        var target = chart.notes().get(RhythmInputFeedback.feedbackNote(chart, hit));
        String body =
                "button_"
                        + (moving.kind() == RhythmChart.Kind.CHANCE_STAR
                                ? "touch_ch"
                                : icon(moving));
        float scale = OriginalUiAssets.exitScale(body, age);
        if (scale <= 0) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(target.x() * 4 / 3, target.y() * 4 / 3, 0);
        g.pose().scale(scale, scale, 1);
        OriginalUiAssets.note(
                g,
                "target_"
                        + (target.kind() == RhythmChart.Kind.CHANCE_STAR
                                ? "touch_ch"
                                : icon(target)),
                359,
                target,
                controllerConnected);
        g.pose().popPose();
        g.pose().pushPose();
        g.pose().translate(target.x() * 4 / 3, target.y() * 4 / 3, 0);
        OriginalUiAssets.note(
                g, body, OriginalUiAssets.exitFrame(body, age), moving, controllerConnected);
        g.pose().popPose();
    }

    private void failedTarget(
            GuiGraphics g, RhythmChart chart, RhythmJudgeSession.Hit hit, int ms) {
        var moving = chart.notes().get(hit.note());
        if (moving.kind() == RhythmChart.Kind.HOLD && moving.release()) {
            return;
        }
        int age = ms - hit.timeMs();
        var target = chart.notes().get(RhythmInputFeedback.feedbackNote(chart, hit));
        String body = "button_" + icon(moving);
        float scale = OriginalUiAssets.exitScale(body, age);
        if (scale <= 0) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(target.x() * 4 / 3, target.y() * 4 / 3, 0);
        g.pose().scale(scale, scale, 1);
        OriginalUiAssets.note(
                g,
                "target_" + icon(target),
                Math.min(539, F2NoteMotion.progress(target, ms) * 360),
                target,
                controllerConnected);
        g.pose().popPose();
        float p = F2NoteMotion.progress(moving, ms);
        g.pose().pushPose();
        g.pose().translate(F2NoteMotion.x(moving, p) * 4 / 3, F2NoteMotion.y(moving, p) * 4 / 3, 0);
        OriginalUiAssets.note(
                g, body, OriginalUiAssets.exitFrame(body, age), moving, controllerConnected);
        g.pose().popPose();
    }

    private static void link(GuiGraphics g, float x, float y, float tx, float ty) {
        float distance = (float) Math.hypot(tx - x, ty - y);
        if (distance < 1) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate((x + tx) / 2, (y + ty) / 2, 0);
        g.pose()
                .mulPose(
                        com.mojang.math.Axis.ZP.rotationDegrees(
                                (float) Math.toDegrees(Math.atan2(ty - y, tx - x))));
        g.pose().scale(distance / 144f, 1, 1);
        OriginalUiAssets.draw(g, "gameplay_hud", "gam_cmn_link_line_02__n.pic", 0);
        OriginalUiAssets.draw(g, "gameplay_hud", "gam_cmn_link_line_01__n.pic", 0);
        g.pose().popPose();
    }

    @Override
    public void onClose() {
        RhythmClientController.pause();
    }
}
