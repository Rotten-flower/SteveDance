package dev.flower.stevedance.client;

import dev.flower.stevedance.SongSelectionCatalog;

import net.minecraft.client.gui.GuiGraphics;

import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 原作单曲四难度记录。 / Native per-song records. */
public final class PlayerRecordsScreen extends RhythmScreen {
    private long lastChange = System.nanoTime();

    public PlayerRecordsScreen() {
        super("ui.stevedance.f2_records");
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
        OriginalUiAssets.loop(g, "menu_common", "bg_home", seconds());
        OriginalUiAssets.draw(
                g,
                "score_records",
                "tit_txt_s_loop",
                OriginalUiAssets.loopFrame("score_records", "tit_txt_s_loop", seconds()),
                Map.of(
                        "p_txt_top01_lt",
                        title.getString(),
                        "p_txt_top02_lt",
                        minecraft.player.getName().getString()));
        Map<String, String> slots = new HashMap<>();
        var song = SongSelectionCatalog.get(RhythmClientController.song);
        slots.put(
                "p_menu_txt_ct",
                dev.flower.stevedance.client.SongText.title(
                                dev.flower.stevedance.SongSelectionCatalog.displayTitleKey(
                                        RhythmClientController.song))
                        .getString());
        var data = RhythmClientController.records;
        for (int i = 0; i < 4; i++) {
            String n = String.format(Locale.ROOT, "%02d", i + 1);
            var line = data == null || i >= data.lines().size() ? null : data.lines().get(i);
            var best = line == null ? null : line.best();
            var stats = line == null ? null : line.stats();
            slots.put(
                    "p_num_score" + n + "_rt", best == null ? "-" : Integer.toString(best.score()));
            slots.put(
                    "p_num_combo" + n + "_rt",
                    stats == null ? "-" : Integer.toString(stats.combo()));
            slots.put(
                    "p_num_play" + n + "_rt",
                    stats == null || !stats.completeCount() ? "-" : Long.toString(stats.plays()));
            String rank =
                    stats == null
                            ? "HYPHEN"
                            : switch (stats.rank()) {
                                case "perfect" -> "P";
                                case "excellent" -> "E";
                                case "great" -> "G";
                                case "standard" -> "S";
                                case "cheap" -> "C";
                                default -> "M";
                            };
            slots.put(
                    "p_value" + n + "_rt", "@score_records/score_records_rank_" + rank.toLowerCase(Locale.ROOT) + "_text");
            slots.put("p_icon_item" + n + "_03_c", "-");
        }
        OriginalUiAssets.draw(
                g,
                "score_records",
                "info_song_single_loop",
                OriginalUiAssets.loopFrame("score_records", "info_song_single_loop", seconds()),
                slots);
        g.flush();
        boolean culled = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_CULL_FACE);
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        OriginalUiAssets.loop(g, "score_records", "side_arrow_l_b", seconds());
        OriginalUiAssets.loop(g, "score_records", "side_arrow_r_b", seconds());
        g.flush();
        if (culled) {
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
        }
        finish(g);
    }

    private void change(int direction) {
        if (System.nanoTime() - lastChange < 550000000L) {
            return;
        }
        lastChange = System.nanoTime();
        var songs = SongSelectionCatalog.songs();
        if (songs.isEmpty()) {
            return;
        }
        int index = 0;
        for (int i = 0; i < songs.size(); i++) {
            if (songs.get(i).id().equals(RhythmClientController.song)) {
                index = i;
                break;
            }
        }
        RhythmClientController.song =
                songs.get(Math.floorMod(index + direction, songs.size())).id();
        RhythmClientController.records = null;
        RhythmClientController.records();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
            change(key == GLFW.GLFW_KEY_LEFT ? -1 : 1);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == 0 && virtualY(y) > 300 && virtualY(y) < 600) {
            if (virtualX(x) < 85) {
                change(-1);
                return true;
            }
            if (virtualX(x) > 1195) {
                change(1);
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public void onClose() {
        RhythmClientController.select();
    }
}
