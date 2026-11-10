package dev.flower.stevedance.client;

import dev.flower.stevedance.SongSelectionCatalog;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 原始sel_pv歌曲列表，后接独立的sel_start设置页面。
 * <p>Original sel_pv song list, followed by the separate sel_start settings page.</p>
 */
public final class SongSelectionScreen extends RhythmScreen {
    private static final List<String> LEVELS = List.of("easy", "normal", "hard", "extreme");
    private int selected, scroll;
    private long changed = System.nanoTime();

    public SongSelectionScreen() {
        super("ui.stevedance.f2_song_select");
        for (int i = 0; i < songs().size(); i++) {
            if (SongSelectionCatalog.normalId(songs().get(i).id())
                    .equals(SongSelectionCatalog.normalId(RhythmClientController.song))) {
                selected = i;
            }
        }
        scroll = Math.max(0, selected - 6);
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    @Override
    boolean nativeControls() {
        return true;
    }

    private List<SongSelectionCatalog.Song> songs() {
        return SongSelectionCatalog.selectionSongs();
    }

    private void choose(int index) {
        var songs = songs();
        if (songs.isEmpty()) {
            return;
        }
        selected = Math.floorMod(index, songs.size());
        scroll = Math.max(0, Math.min(scroll, selected));
        if (selected >= scroll + 7) {
            scroll = selected - 6;
        }
        RhythmClientController.error = "";
        RhythmClientController.chooseSong(songs.get(selected).id());
        changed = System.nanoTime();
        RhythmSoundPlayer.play("select");
    }

    private void level(int index) {
        RhythmClientController.choose(LEVELS.get(Math.floorMod(index, 4)));
        changed = System.nanoTime();
        RhythmSoundPlayer.play("select");
    }

    private void confirm() {
        var song = SongSelectionCatalog.get(RhythmClientController.song);
        if (song == null || !song.pvAvailable() && song.difficulties().isEmpty()) {
            RhythmClientController.error = "ui.stevedance.f2_song_unavailable";
            RhythmSoundPlayer.play("cancel");
            return;
        }
        RhythmSoundPlayer.play("select");
        RhythmClientController.details();
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
        OriginalUiAssets.loop(g, "menu_common", "bg_home", seconds());
        OriginalUiAssets.loop(g, "common_info", "frame_top", seconds());
        OriginalUiAssets.draw(
                g,
                "common_info",
                "frame_top_freeplay",
                OriginalUiAssets.loopFrame("common_info", "frame_top_freeplay", seconds()),
                Map.of("p_txt_lt", "", "p_point_rt", ""));
        OriginalUiAssets.text(
                g,
                Component.translatable("ui.stevedance.f2_select_prompt").getString(),
                300,
                24,
                .85f);
        for (int i = 0; i < 4; i++) {
            String d = LEVELS.get(i);
            OriginalUiAssets.loop(
                    g,
                    "song_selection",
                    "tab_level_" + d + (d.equals(RhythmClientController.difficulty) ? "_a" : "_b"),
                    seconds());
            int index = i;
            controls.add(
                    new Control(
                            13 + i * 142,
                            71,
                            144,
                            58,
                            "difficulty.stevedance." + d,
                            () -> level(index)));
        }
        g.pose().pushPose();
        listTransform(g);
        OriginalUiAssets.loop(
                g, "song_selection", "tab_level_" + RhythmClientController.difficulty + "_base", seconds());
        g.pose().popPose();
        g.pose().pushPose();
        listTransform(g);
        OriginalUiAssets.loop(g, "song_selection", "scrl_gauge", seconds());
        g.pose().popPose();
        List<SongSelectionCatalog.Song> songs = songs();
        Map<String, String> rows = new HashMap<>();
        for (int i = 0; i <= 8; i++) {
            int index = scroll + i - 1;
            String slot = String.format(Locale.ROOT, "%02d", i);
            boolean visible = i >= 1 && i <= 7 && index >= 0 && index < songs.size();
            rows.put(
                    "p_menu_txt" + slot + "_lt",
                    visible
                            ? dev.flower.stevedance.client.SongText.title(
                                            SongSelectionCatalog.displayTitleKey(
                                                    songs.get(index).id()))
                                    .getString()
                            : "");
            rows.put(
                    "p_menu_icon" + slot + "_c",
                    visible ? "@gameplay_hud/gameplay_hud_rhythm_game_icon" : "");
            rows.put(
                    "p_menu_value_icon" + slot + "_c",
                    visible
                                    && !songs.get(index).pvAvailable()
                                    && songs.get(index).difficulties().isEmpty()
                            ? "@common_icons/common_icons_lock"
                            : "");
        }
        float listFrame = OriginalUiAssets.settledFrame("song_selection", "txt_plate");
        g.pose().pushPose();
        listTransform(g);
        OriginalUiAssets.draw(g, "song_selection", "txt_plate", listFrame, rows);
        OriginalUiAssets.loop(g, "song_selection", "txt_cursor0" + (selected - scroll + 1), seconds());
        OriginalUiAssets.slots(g, "song_selection", "txt_plate", listFrame, rows);
        if ((System.nanoTime() - changed) / 1e9 < 1) {
            g.pose().pushPose();
            g.pose().translate(362, 179 + (selected - scroll) * 73, 0);
            OriginalUiAssets.draw(
                    g,
                    "song_selection",
                    "touch_select_eff",
                    (float) ((System.nanoTime() - changed) / 1e9 * 60));
            g.pose().popPose();
        }
        g.pose().popPose();
        for (int i = 0; i < Math.min(7, songs.size() - scroll); i++) {
            int index = scroll + i;
            controls.add(
                    new Control(
                            16,
                            148 + i * 80.3f,
                            724,
                            68.2f,
                            songs.get(index).titleKey(),
                            () -> {
                                if (selected == index) {
                                    confirm();
                                } else {
                                    choose(index);
                                }
                            }));
        }
        var song = SongSelectionCatalog.get(RhythmClientController.song);
        if (song != null) {
            OriginalUiAssets.draw(
                    g, "song_selection", "p_thumb_c", 0, Map.of("p_thumb_c", "@pv/" + song.id()));
            Map<String, String> info =
                    new HashMap<>(
                            OriginalUiAssets.stars(
                                    song.stars()
                                            .getOrDefault(RhythmClientController.difficulty, 0f),
                                    "common_info",
                                    "common_info_level_star_on",
                                    "common_info_level_star_half",
                                    "common_info_level_star_off"));
            var best =
                    RhythmClientController.personal.get(
                            song.id() + "/" + RhythmClientController.difficulty);
            info.put("p_num_bpm_rt", Integer.toString(song.bpm()));
            info.put("p_num_score_rt", best == null ? "-" : Integer.toString(best.score()));
            info.put("p_num_clear_rt", "-");
            OriginalUiAssets.draw(
                    g,
                    "song_selection",
                    song.difficulties().contains(RhythmClientController.difficulty)
                            ? "song_info"
                            : "song_info_pv",
                    0,
                    info);
            if (!song.pvAvailable() && song.difficulties().isEmpty()) {
                OriginalUiAssets.loop(g, "song_selection", "unopen_loop", seconds());
            } else if (song.difficulties().isEmpty()) {
                OriginalUiAssets.loop(g, "song_selection", "value_pvonly_loop", seconds());
            } else {
                OriginalUiAssets.loop(
                        g,
                        "song_selection",
                        best == null ? "value_hyphen_loop" : "value_" + best.rank() + "_loop",
                        seconds());
            }
        }
        if (songs.isEmpty()) {
            g.fill(32, 276, 746, 320, 0xeef4f6f8);
            text(g, Component.translatable("ui.stevedance.install_packs"), 48, 290, 0xff214253, 2f);
        }
        finish(g);
    }

    // 中文：所有列表美术使用同一变换，包括其背景和选择效果。
    // EN: All list artwork uses one transform, including its background and selection effect.
    private static void listTransform(GuiGraphics g) {
        g.pose().translate(0, 148, 0);
        g.pose().scale(1, 1.10f, 1);
        g.pose().translate(0, -148, 0);
    }

    @Override
    protected void padPressed(int bit) {
        if (bit == 8) {
            RhythmClientController.records();
            return;
        }
        super.padPressed(bit);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        int difficulty = LEVELS.indexOf(RhythmClientController.difficulty);
        if (key == GLFW.GLFW_KEY_V) {
            RhythmSettingsScreen.cycleVoicePack(hasShiftDown() ? -1 : 1);
            RhythmSoundPlayer.play("select");
            return true;
        }
        if (key == GLFW.GLFW_KEY_F1) {
            minecraft.setScreen(new RhythmSettingsScreen(this));
            return true;
        }
        if (key == GLFW.GLFW_KEY_H) {
            minecraft.setScreen(new dev.flower.stevedance.client.planet.PlanetScreen(this));
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            choose(selected + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            level(difficulty + (key == GLFW.GLFW_KEY_RIGHT ? 1 : -1));
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER) {
            confirm();
            return true;
        }
        if (key == GLFW.GLFW_KEY_R) {
            RhythmClientController.records();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        choose(selected - (int) amount);
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
        float vx = virtualX(x), vy = virtualY(y);
        for (var c : controls) {
            if (vx >= c.x() && vx < c.x() + c.w() && vy >= c.y() && vy < c.y() + c.h()) {
                c.action().run();
                return true;
            }
        }
        return true;
    }

    @Override
    public void onClose() {
        RhythmSoundPlayer.play("cancel");
        minecraft.setScreen(null);
    }
}
