package dev.flower.stevedance.client;

import dev.flower.stevedance.SongSelectionCatalog;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;
import java.util.Locale;

public final class SongSetupScreen extends RhythmScreen {
    private static final List<String> DIFFICULTIES = List.of("easy", "normal", "hard", "extreme");
    private int optionFocus;
    private long performanceNoticeBegan;

    public SongSetupScreen() {
        super("ui.stevedance.f2_song_select");
        ClientPlaybackSettings.load();
        OriginalUiAssets.prepare();
        RhythmSoundPlayer.prepare();
        MenuCharacterPresentation.begin("select");
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    @Override
    boolean nativeControls() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        start(g);
        if (!OriginalUiAssets.ready()) {
            text(
                    g,
                    Component.translatable(
                            OriginalUiAssets.error.isEmpty()
                                    ? "ui.stevedance.native_loading"
                                    : OriginalUiAssets.error),
                    420,
                    340,
                    0xff214253,
                    1.8f);
            finish(g);
            return;
        }
        var selected = SongSelectionCatalog.get(RhythmClientController.song);
        boolean playable =
                selected != null
                        && (selected.pvAvailable() || ClientPlaybackSettings.effectiveChartOnly())
                        && selected.difficulties().contains(RhythmClientController.difficulty);
        OriginalUiAssets.draw(
                g,
                "song_setup",
                playable ? "base_play_loop" : "base_pv_loop",
                OriginalUiAssets.loopFrame(
                        "song_setup", playable ? "base_play_loop" : "base_pv_loop", seconds()),
                Map.of(
                        "p_hiscore_num_rt",
                        "",
                        "p_value_icon_rt",
                        bestRank(),
                        "p_se_txt_rt",
                        "",
                        "p_scratch_se_txt_rt",
                        "",
                        "p_skin_menu_rt",
                        ""));
        Map<String, String> stars =
                OriginalUiAssets.stars(
                        selected == null
                                ? 0
                                : selected.stars()
                                        .getOrDefault(RhythmClientController.difficulty, 0f),
                        "common_info",
                        "common_info_level_star_on",
                        "common_info_level_star_half",
                        "common_info_level_star_off");
        OriginalUiAssets.draw(
                g,
                "song_setup",
                "base_loop",
                OriginalUiAssets.loopFrame("song_setup", "base_loop", seconds()),
                stars);
        if (selected != null) {
            drawTitle(
                    g,
                    dev.flower.stevedance.client.SongText.title(
                                    SongSelectionCatalog.displayTitleKey(selected.id()))
                            .getString());
            drawSongCredits(g);
        }
        OriginalUiAssets.draw(
                g,
                "song_setup",
                "level_info_" + RhythmClientController.difficulty + "_loop",
                OriginalUiAssets.loopFrame(
                        "song_setup",
                        "level_info_" + RhythmClientController.difficulty + "_loop",
                        seconds()),
                stars);
        if (!MenuCharacterPresentation.error.isEmpty()) {
            OriginalUiAssets.text(
                    g,
                    Component.translatable(MenuCharacterPresentation.error).getString(),
                    784,
                    560,
                    .7f);
        }
        MenuCharacterPresentation.drawCredits(g);
        setupButton(
                g,
                mx,
                my,
                "btn_item",
                650,
                598,
                188,
                68,
                "sel_start_mm_btn_txt_sb_02.pic",
                "ui.stevedance.model.title",
                () -> minecraft.setScreen(new PerformerModelSelectionScreen(this)),
                selected != null && selected.pvAvailable(),
                0);
        setupButton(
                g,
                mx,
                my,
                "btn_pv",
                850,
                598,
                174,
                68,
                "sel_start_mm_btn_txt_sb_03.pic",
                "ui.stevedance.f2_pv",
                RhythmClientController::requestPv,
                selected != null && selected.pvAvailable(),
                1);
        setupButton(
                g,
                mx,
                my,
                "btn_start",
                1043,
                588,
                202,
                78,
                "sel_start_mm_btn_txt_start.pic",
                "ui.stevedance.f2_start",
                RhythmClientController::request,
                playable,
                2);
        rightValue(g, bestScore(), 290);
        rightValue(g, RhythmSettingsScreen.soundLabel(RhythmSettingsScreen.buttonSound), 344);
        rightValue(g, RhythmSettingsScreen.soundLabel(RhythmSettingsScreen.scratchSound), 398);
        rightValue(g, Component.translatable("ui.stevedance.f2_default").getString(), 452);

        if (!playable) {
            OriginalUiAssets.text(
                    g, Component.translatable("ui.stevedance.f2_pv_only").getString(), 176, 292, 1);
        }
        controls.add(new Control(174, 128, 344, 72, "ui.stevedance.f2_difficulty", () -> cycle(1)));
        finish(g);
    }

    public boolean hasPerformanceSwitch() {
        return SongSelectionCatalog.performances(RhythmClientController.song).size() > 1;
    }

    public boolean switchPerformance() {
        var variants = SongSelectionCatalog.performances(RhythmClientController.song);
        if (variants.size() < 2) {
            return false;
        }
        int current = 0;
        for (int i = 0; i < variants.size(); i++) {
            if (variants.get(i).id().equals(RhythmClientController.song)) {
                current = i;
            }
        }
        RhythmClientController.chooseSong(variants.get((current + 1) % variants.size()).id());
        MenuCharacterPresentation.begin("select");
        performanceNoticeBegan = System.nanoTime();
        return true;
    }

    // 窗口外提示由外框绘制后调用，避免被内容裁剪。 / Draw outside the content scissor after the bezel.
    void drawPerformanceNotice(GuiGraphics g) {
        if (performanceNoticeBegan == 0 || scale <= 0) {
            return;
        }
        double elapsed = (System.nanoTime() - performanceNoticeBegan) / 1e9;
        if (elapsed >= 1.6) {
            return;
        }
        float enter = (float) Math.min(1, elapsed / .18),
                exit = (float) Math.max(0, (elapsed - 1.35) / .25);
        float alpha = Math.min(enter, 1 - exit),
                slide = 1 - (1 - enter) * (1 - enter) * (1 - enter);
        float top = -(height - 728 * scale) / (2 * scale),
                barHeight = Math.min(40, Math.max(18, -18 - top - 8));
        float y = (top - 18 - barHeight) / 2;
        boolean success =
                !SongSelectionCatalog.normalId(RhythmClientController.song)
                        .equals(RhythmClientController.song);
        String label =
                Component.translatable(
                                success
                                        ? "ui.stevedance.f2_performance_success"
                                        : "ui.stevedance.f2_performance_normal")
                        .getString();
        float textScale =
                Math.min(
                        2,
                        Math.min(
                                (barHeight - 8) / font.lineHeight,
                                324f / Math.max(1, font.width(label))));
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        if (a < 4) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(-18 - (1 - slide) * 8 - exit * 8, y, 300);
        g.fill(0, 0, 360, (int) barHeight, a << 24);
        g.pose().translate(18, (barHeight - font.lineHeight * textScale) / 2, 0);
        g.pose().scale(textScale, textScale, 1);
        g.drawString(font, label, 0, 0, (a << 24) | 0xffffff, false);
        g.pose().popPose();
    }

    private void drawTitle(GuiGraphics g, String title) {
        String label = "§l" + title;
        float size = Math.min(5f, 900f / Math.max(1, font.width(label)));
        g.pose().pushPose();
        g.pose().translate(63, 25, 0);
        g.pose().scale(size, size, 1);
        g.drawString(font, label, 0.8f, 1.2f, 0xff777777, false);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    g.drawString(font, label, dx * .4f, dy * .4f, 0xffffffff, false);
                }
            }
        }
        g.drawString(font, label, 0, 0, 0xff555555, false);
        g.drawString(font, label, .24f, 0, 0xff555555, false);
        g.pose().popPose();
    }

    private void drawSongCredits(GuiGraphics g) {
        var rows = dev.flower.stevedance.client.SongCredits.lines(RhythmClientController.song);
        if (rows.isEmpty()) {
            return;
        }
        var labels = rows.stream().map(row -> row.text().getString()).toList();
        int widest = labels.stream().mapToInt(font::width).max().orElse(1);
        float size =
                Math.min(3.5f, Math.min(1140f / Math.max(1, widest), 70f / (labels.size() * 10)));
        for (int i = 0; i < labels.size(); i++) {
            g.pose().pushPose();
            g.pose().translate(63, 86 + i * 10 * size, 0);
            g.pose().scale(size, size, 1);
            g.drawString(font, labels.get(i), 0, 0, 0xff101010, false);
            g.pose().popPose();
        }
    }

    private void rightValue(GuiGraphics g, String value, int y) {
        float size = 2;
        g.pose().pushPose();
        g.pose().translate(426 - font.width(value) * size, y, 0);
        g.pose().scale(size, size, 1);
        g.drawString(font, value, 0, 0, 0xfff3ffff, false);
        g.pose().popPose();
    }

    void modeIndicator(GuiGraphics g) {
        // 中文：小屏幕/世界字形指示当前选中的模式。
        // EN: Small screen/world glyph indicates the currently selected mode.
        int x = 1210, y = 24, c = 0xff15535f;
        g.fill(x, y, x + 36, y + 26, c);
        g.fill(x + 3, y + 3, x + 33, y + 23, 0xffa9f7f3);
        if (ClientPlaybackSettings.effectiveChartOnly()) {
            g.fill(x + 10, y + 6, x + 14, y + 18, c);
            g.fill(x + 20, y + 10, x + 24, y + 18, c);
        } else if (ClientPlaybackSettings.worldStage) {
            g.fill(x + 7, y + 13, x + 29, y + 21, c);
            g.fill(x + 13, y + 7, x + 22, y + 21, c);
        } else {
            g.fill(x + 15, y + 26, x + 21, y + 31, c);
            g.fill(x + 8, y + 31, x + 28, y + 34, c);
        }
    }

    // 复用原作按钮和选中动画。 / Reuse native button artwork and selection animation.
    private void setupButton(
            GuiGraphics g,
            int mx,
            int my,
            String root,
            int x,
            int y,
            int w,
            int h,
            String slot,
            String key,
            Runnable action,
            boolean enabled,
            int index) {
        boolean hover =
                !GamepadInput.active()
                        && virtualX(mx) >= x
                        && virtualX(mx) < x + w
                        && virtualY(my) >= y + 60
                        && virtualY(my) < y + h + 60;
        boolean overButtons =
                !GamepadInput.active()
                        && virtualX(mx) >= 650
                        && virtualX(mx) < 1245
                        && virtualY(my) >= 648
                        && virtualY(my) < 726;
        String layer =
                root
                        + (!enabled
                                ? "_c"
                                : hover || !overButtons && optionFocus == index ? "_a" : "_b");
        g.pose().pushPose();
        g.pose().translate(0, 60, 0);
        OriginalUiAssets.draw(
                g,
                "song_setup",
                layer,
                OriginalUiAssets.loopFrame("song_setup", layer, seconds()),
                Map.of(slot, Component.translatable(key).getString()));
        g.pose().popPose();
        if (enabled) {
            controls.add(new Control(x, y + 60, w, h, key, action));
        }
    }

    private void cycle(int direction) {
        int index = DIFFICULTIES.indexOf(RhythmClientController.difficulty);
        RhythmClientController.choose(DIFFICULTIES.get(Math.floorMod(index + direction, 4)));
        RhythmSoundPlayer.play("select");
    }

    @Override
    protected void padPressed(int bit) {
        if (bit == 256) {
            if (switchPerformance()) {
                RhythmSoundPlayer.play("select");
            }
            return;
        }
        if (bit == 4) {
            RhythmClientController.requestPv();
            return;
        }
        if (bit == 8) {
            minecraft.setScreen(new PerformerModelSelectionScreen(this));
            return;
        }
        if (bit == 4096 || bit == 16384 || bit == 2048 || bit == 8192) {
            moveOption(bit == 4096 || bit == 8192 ? 1 : -1);
            return;
        }
        if (bit == 16 || bit == 32) {
            cycle(bit == 32 ? 1 : -1);
            return;
        }
        if (bit == 1) {
            confirmOption();
            return;
        }
        super.padPressed(bit);
    }

    private int optionCount() {
        return 3;
    }

    private void moveOption(int direction) {
        optionFocus = Math.floorMod(optionFocus + direction, optionCount());
        RhythmSoundPlayer.play("select");
    }

    private void confirmOption() {
        optionFocus = Math.floorMod(optionFocus, optionCount());
        RhythmSoundPlayer.play("select");
        switch (optionFocus) {
            case 0 -> minecraft.setScreen(new PerformerModelSelectionScreen(this));
            case 1 -> RhythmClientController.requestPv();
            case 2 -> RhythmClientController.request();
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_S) {
            if (switchPerformance()) {
                RhythmSoundPlayer.play("select");
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_G) {
            RhythmClientController.choosePlaybackSpace();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F1) {
            minecraft.setScreen(new RhythmSettingsScreen(this));
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_TAB) {
            moveOption(
                    key == GLFW.GLFW_KEY_LEFT
                                    || (key == GLFW.GLFW_KEY_TAB
                                            && (modifiers & GLFW.GLFW_MOD_SHIFT) != 0)
                            ? -1
                            : 1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER) {
            confirmOption();
            return true;
        }
        if (key == GLFW.GLFW_KEY_R) {
            RhythmClientController.records();
            return true;
        }
        if (key == GLFW.GLFW_KEY_P) {
            RhythmClientController.requestPv();
            return true;
        }
        if (key == GLFW.GLFW_KEY_M) {
            minecraft.setScreen(new PerformerModelSelectionScreen(this));
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    private String bestScore() {
        var r =
                RhythmClientController.personal.get(
                        RhythmClientController.song + "/" + RhythmClientController.difficulty);
        return r == null ? "—" : Integer.toString(r.score());
    }

    private String bestRank() {
        var r =
                RhythmClientController.personal.get(
                        RhythmClientController.song + "/" + RhythmClientController.difficulty);
        String code =
                r == null
                        ? "HYPHEN"
                        : switch (r.rank()) {
                            case "perfect" -> "P";
                            case "excellent" -> "E";
                            case "great" -> "G";
                            case "standard" -> "S";
                            case "cheap" -> "C";
                            default -> "M";
                        };
        return "@song_setup/song_setup_rank_" + code.toLowerCase(Locale.ROOT) + "_text";
    }

    @Override
    public void onClose() {
        MenuCharacterPresentation.stop();
        RhythmSoundPlayer.play("cancel");
        minecraft.setScreen(new SongSelectionScreen());
    }
}
