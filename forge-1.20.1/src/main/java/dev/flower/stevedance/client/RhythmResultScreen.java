package dev.flower.stevedance.client;

import dev.flower.stevedance.RhythmChartLibrary;
import dev.flower.stevedance.core.RhythmJudgeSession;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class RhythmResultScreen extends RhythmScreen
        implements PvPlaybackController.WorldView {
    private final net.minecraft.client.resources.sounds.SimpleSoundInstance resultVoice;

    public RhythmResultScreen() {
        super("ui.stevedance.f2_result");
        var entry =
                RhythmChartLibrary.get(
                        RhythmClientController.song, RhythmClientController.difficulty);
        if (RhythmClientController.completion != null && entry != null) {
            String rank =
                    RhythmClientController.completion.result().rank(entry.chart().clearPercent());
            MenuCharacterPresentation.begin(rank);
            resultVoice =
                    dev.flower.stevedance.client.ResultVoice.play(
                            RhythmClientController.song, rank);
        } else {
            resultVoice = null;
        }
    }

    @Override
    public void removed() {
        dev.flower.stevedance.client.ResultVoice.stop(resultVoice);
        super.removed();
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    @Override
    boolean nativeControls() {
        return true;
    }

    private void moveResultFocus(int direction) {
        focus = Math.floorMod(focus + direction, 2);
    }

    @Override
    protected void padPressed(int bit) {
        if (bit == 4096) {
            moveResultFocus(1);
            return;
        }
        if (bit == 16384) {
            moveResultFocus(-1);
            return;
        }
        if (bit == 2048 || bit == 8192) {
            return;
        }
        super.padPressed(bit);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_RIGHT) {
            moveResultFocus(1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT) {
            moveResultFocus(-1);
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        var completion = RhythmClientController.completion;
        if (completion == null) {
            return;
        }
        RhythmJudgeSession.Result r = completion.result();
        var entry =
                RhythmChartLibrary.get(
                        RhythmClientController.song, RhythmClientController.difficulty);
        String rank = r.rank(entry.chart().clearPercent());
        boolean clear = r.cleared(entry.chart().clearPercent());
        start(g);
        OriginalUiAssets.loop(
                g, "results", clear ? "result_clear_loop" : "result_not_clear_loop", seconds());
        if (seconds() < 26 / 60.0) {
            OriginalUiAssets.draw(
                    g, "results", "value_" + rank + "_in", (float) (seconds() * 60));
        } else {
            OriginalUiAssets.loop(g, "results", "value_" + rank + "_loop", seconds());
        }
        Map<String, String> values = new HashMap<>();
        int[] counts = {r.total(), r.maxCombo(), r.cool(), r.fine(), r.safe(), r.sad(), r.worst()};
        for (int i = 0; i < counts.length; i++) {
            values.put(
                    String.format(Locale.ROOT, "p_count_num_%02d_rt", i + 1),
                    Integer.toString(counts[i]));
        }
        values.put(
                "p_chance_result_rt",
                Component.translatable(
                                r.chance() > 0
                                        ? "ui.stevedance.f2_success"
                                        : "ui.stevedance.f2_failure")
                        .getString());
        values.put("p_score_num_rt", Integer.toString(r.score()));
        values.put("p_hiscore_num_rt", Integer.toString(completion.bestScore()));
        values.put("p_song_txt_lt", "");
        values.put("p_bonus_num_n_rt", Integer.toString(r.technical()));
        values.put(
                "p_bonus_num_d_rt",
                Long.toString(
                        entry.chart().zones().stream()
                                .filter(z -> z.kind().equals("TECHNICAL"))
                                .count()));
        values.putAll(
                OriginalUiAssets.stars(
                        entry.chart().stars(),
                        "common_info",
                        "common_info_level_star_on",
                        "common_info_level_star_half",
                        "common_info_level_star_off"));
        OriginalUiAssets.draw(
                g,
                "results",
                "base_loop",
                OriginalUiAssets.loopFrame("results", "base_loop", seconds()),
                values);
        OriginalUiAssets.loop(
                g,
                "results",
                "level_info_" + RhythmClientController.difficulty + "_loop",
                seconds());
        // 在可视标题区绘制，避免原作锚点将本地化曲名推到外框之外。
        // Draw inside the visible header; the original text anchor clips localized titles.
        String songTitle =
                SongText.title(
                                dev.flower.stevedance.SongSelectionCatalog.displayTitleKey(
                                        RhythmClientController.song))
                        .getString();
        float titleScale = Math.min(3f, 680f / Math.max(1, font.width(songTitle)));
        text(g, songTitle, 63, 24, 0xff243c46, titleScale);
        if (completion.highScore()) {
            OriginalUiAssets.loop(g, "results", "mark_hi_score", seconds());
        }
        nativeControl(
                g,
                mx,
                my,
                "results",
                "btn_retry",
                878,
                619,
                184,
                62,
                "ui.stevedance.f2_retry",
                RhythmClientController::retry);
        nativeControl(
                g,
                mx,
                my,
                "results",
                "btn_return",
                1066,
                619,
                184,
                62,
                "ui.stevedance.f2_return",
                () -> {
                    RhythmClientController.stop();
                    RhythmClientController.select();
                });
        finish(g);
    }

    @Override
    public void onClose() {
        RhythmClientController.stop();
        RhythmClientController.select();
    }
}
