package dev.flower.stevedance.client;

import dev.flower.stevedance.core.ChanceFrameTransition;
import dev.flower.stevedance.core.PerformanceHudState;
import dev.flower.stevedance.core.RhythmJudgeSession;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Map;

/** Original normal/Chance groups, sharing one section clock.
 *  普通和 Chance 两组使用同一区段时钟。 */
public final class RhythmPerformanceHud {
    private static final ResourceLocation SONG_ICON =
            new ResourceLocation("stevedance", "textures/gui/performance_song.png");
    private final PerformanceHudState state = new PerformanceHudState();

    public void draw(
            GuiGraphics g, RhythmJudgeSession session, int ms, String difficulty, String title) {
        state.updateGauge(session, ms);
        state.update(ms, session.result().score());
        int band = PerformanceHudState.lifeBand(session.life());
        double visibility = 0;
        int section = -1;
        for (int i = 0; i < session.chart.zones().size(); i++) {
            var z = session.chart.zones().get(i);
            if (!z.kind().equalsIgnoreCase("chance")) {
                continue;
            }
            double v = ChanceFrameTransition.visible(ms, z.startMs(), z.endMs());
            if (v > visibility) {
                visibility = v;
                section = i;
            }
        }
        final float hidden = (float) visibility;
        double seconds = Math.max(0, ms) / 1000.0;
        if (hidden < 1) {
            // 中文：皮肤渲染器已将自身的上边框延伸到视口。
            // EN: The skin renderer already extends its own upper frame to the viewport.
            g.pose().pushPose();
            g.pose().translate(0, -hidden * (64 + OriginalUiAssets.marginY()), 0);
            OriginalUiAssets.loop(
                    g, "gameplay_skin_default", band < 3 ? "frame_up_danger" : "frame_up", seconds);
            g.pose().popPose();
            panel(
                    g,
                    false,
                    hidden * (64 + OriginalUiAssets.marginY()),
                    false,
                    () -> {
                        OriginalUiAssets.loop(g, "gameplay_hud", "score_word", seconds);
                        OriginalUiAssets.loop(g, "gameplay_hud", "level_info_" + difficulty, seconds);
                        int score = state.score();
                        for (int i = 1; i <= 7; i++) {
                            String root = String.format(Locale.ROOT, "p_score%02d_c", i);
                            OriginalUiAssets.draw(
                                    g,
                                    "gameplay_hud",
                                    root,
                                    0,
                                    Map.of(root, Integer.toString(score % 10)));
                            score /= 10;
                        }
                        // 图一原作的纯音符，不使用 F2 素材中带底座的变体。
                        // 中文：来自所提供参考的普通音符，不带F2平台
                        // EN: Plain musical note from the supplied reference, without the F2
                        // platform
                        // 中文：变体。
                        // EN: variant.
                        g.pose().pushPose();
                        g.pose().translate(16, 2, 0);
                        OriginalUiAssets.keySprite(g, SONG_ICON, 32, 38, 1);
                        g.pose().popPose();
                        OriginalUiAssets.draw(
                                g,
                                "gameplay_hud",
                                "p_song_title_lt",
                                0,
                                Map.of("p_song_title_lt", title));
                    });
            panel(
                    g,
                    true,
                    hidden * (180 + OriginalUiAssets.marginY()),
                    true,
                    () -> {
                        OriginalUiAssets.progress(g, session.result().achievement());
                    });
            panel(
                    g,
                    true,
                    hidden * (180 + OriginalUiAssets.marginY()),
                    false,
                    () -> {
                        OriginalUiAssets.loop(g, "gameplay_hud", "gauge_base_skin", seconds);
                        String suffix = String.format(Locale.ROOT, "%02d", band);
                        // 中文：持续填充和一次性特效是独立的原始图层。
                        // EN: Persistent fill and one-shot effects are separate original layers.
                        // 血量本体常驻；短特效结束不能移除血量。
                        OriginalUiAssets.loop(g, "gameplay_hud", "gauge" + suffix, seconds);
                        float pulse = state.pulseFrame(ms), icon = state.iconFrame(ms);
                        if (pulse >= 0 && pulse < (state.direction() > 0 ? 9 : 40)) {
                            OriginalUiAssets.draw(
                                    g,
                                    "gameplay_hud",
                                    (state.direction() > 0 ? "gauge_up" : "gauge_down") + suffix,
                                    pulse);
                        }
                        OriginalUiAssets.loop(
                                g,
                                "gameplay_hud",
                                band == 15 ? "gauge_surface_b" : "gauge_surface",
                                seconds);
                        String circle =
                                icon >= 0 && icon < 80
                                        ? (state.direction() > 0 ? "up" : "down")
                                        : "n";
                        OriginalUiAssets.draw(
                                g, "gameplay_hud", "gauge_c_circle_" + circle, state.circleFrame(ms));
                        if (pulse >= 0 && pulse < 9) {
                            OriginalUiAssets.draw(
                                    g,
                                    "gameplay_hud",
                                    state.direction() > 0 ? "gauge_up_eff_b" : "gauge_up_eff_r",
                                    pulse);
                        }
                        if (icon >= 0 && icon < 80) {
                            OriginalUiAssets.draw(
                                    g,
                                    "gameplay_hud",
                                    state.direction() > 0 ? "gauge_icon_up" : "gauge_icon_down",
                                    icon);
                        }
                        if (band < 3) {
                            OriginalUiAssets.loop(g, "gameplay_hud", "gauge_icon_exclamation", seconds);
                        }
                        OriginalUiAssets.loop(g, "gameplay_hud", "gauge_edge", seconds);
                    });
        }
        if (section >= 0) {
            final float gauge = state.chanceFill(session, section, ms);
            panel(
                    g,
                    false,
                    (1 - hidden) * (48 + OriginalUiAssets.marginY()),
                    true,
                    () -> OriginalUiAssets.loop(g, "gameplay_hud", "chance_frame_top", seconds));
            panel(
                    g,
                    true,
                    (1 - hidden) * (96 + OriginalUiAssets.marginY()),
                    true,
                    () -> {
                        OriginalUiAssets.loop(g, "gameplay_hud", "chance_frame_bottom", seconds);
                        OriginalUiAssets.loop(g, "gameplay_hud", "chance_frame_bottom_gauge", seconds);
                    });
            panel(
                    g,
                    true,
                    (1 - hidden) * (96 + OriginalUiAssets.marginY()),
                    false,
                    () -> OriginalUiAssets.chanceGauge(g, gauge, seconds));
        }
    }

    private static void panel(
            GuiGraphics g, boolean bottom, float offset, boolean stretch, Runnable draw) {
        g.pose().pushPose();
        g.pose().translate(0, bottom ? offset : -offset, 0);
        OriginalUiAssets.edge(g, bottom, stretch, draw);
        g.pose().popPose();
    }
}
