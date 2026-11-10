package dev.flower.stevedance.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared playback keys and PV object visibility. / 演出共用按键与PV逐项显隐。 */
public final class PerformanceControls {

    private static boolean panel;
    private static int first;

    private PerformanceControls() {}

    public static void reset() {
        PvVisibility.reset();
        expanded.clear();
        expandedGraphs.clear();
        panel = false;
        first = 0;
        StageCamera.reset();
    }

    public static void togglePanel() {
        panel = !panel;
    }

    public static List<String> stages() {
        var scene = PvPlaybackController.scene;
        if (scene == null) {
            return List.of();
        }
        var names = new LinkedHashSet<String>();
        for (var cut : scene.description.timeline()) {
            names.add(cut.stage());
        }
        names.addAll(scene.description.stages().keySet());
        if (scene.description.stageGraphs() != null) {
            names.addAll(scene.description.stageGraphs().keySet());
        }
        names.remove(null);
        return List.copyOf(names);
    }

    private static List<String> stageKeys(String stage) {
        var scene = PvPlaybackController.scene;
        if (scene == null) {
            return List.of();
        }
        var keys = new ArrayList<String>();
        for (String mesh : scene.description.stages().getOrDefault(stage, List.of())) {
            keys.add("mesh:" + mesh);
        }
        if (scene.description.stageGraphs() != null) {
            for (String graph : scene.description.stageGraphs().getOrDefault(stage, List.of())) {
                keys.add("graph:" + graph);
            }
        }
        return keys;
    }

    public static boolean visible(String stage) {
        return stageKeys(stage).stream().allMatch(PvVisibility::visible);
    }

    public static void toggle(String stage) {
        boolean show = !visible(stage);
        for (String key : stageKeys(stage)) {
            if (PvVisibility.visible(key) != show) {
                PvVisibility.toggle(key);
            }
        }
    }

    public static boolean currentStageVisible() {
        var scene = PvPlaybackController.scene;
        return scene != null && visible(scene.stage(PvPlaybackController.timeMs()));
    }

    public static boolean reserved(int key) {
        return key == GLFW.GLFW_KEY_F4
                || key == GLFW.GLFW_KEY_F8
                || key == GLFW.GLFW_KEY_F1
                || key == GLFW.GLFW_KEY_F5
                || key == GLFW.GLFW_KEY_F6
                || key == GLFW.GLFW_KEY_F7;
    }

    public static boolean key(int key) {
        if (key == GLFW.GLFW_KEY_F1) {
            var mc = Minecraft.getInstance();
            if (mc.screen instanceof RhythmPlayScreen) {
                RhythmClientController.pauseForSettings();
            } else if (mc.screen instanceof PvViewerScreen) {
                if (!PvPlaybackController.paused()) {
                    PvPlaybackController.togglePause();
                }
                mc.setScreen(new RhythmSettingsScreen(mc.screen));
            }
            return true;
        }
        // Reuse the existing camera rig; keys must not be swallowed without action.
        // 沿用现有机位，功能键必须真正执行切换。
        if (key == GLFW.GLFW_KEY_F4) {
            StageCamera.toggleFree();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F5) {
            PvPlaybackController.toggle();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F8) {
            StageCamera.cycleActor();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F6) {
            togglePanel();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F7) {
            PvPlaybackController.toggleCharacterLighting();
            return true;
        }
        return false;
    }

    private static final net.minecraft.resources.ResourceLocation FRAME =
            new net.minecraft.resources.ResourceLocation(
                    "stevedance", "textures/gui/menu_window.png");
    private static final Set<String> expandedGraphs = new HashSet<>();
    private static final Set<PvVisibility.Group> expanded =
            EnumSet.noneOf(PvVisibility.Group.class);
    private static final int W = 440, H = 680, TOP = 144, ROW = 40, BOTTOM = 574;

    private record Row(PvVisibility.Group group, PvVisibility.Entry entry) {}

    private static List<Row> rows() {
        var rows = new ArrayList<Row>();
        for (var group : PvVisibility.Group.values()) {
            rows.add(new Row(group, null));
            if (expanded.contains(group)) {
                for (var entry : PvVisibility.entries(group)) {
                    String key = entry.key();
                    if (key.startsWith("node:") || key.startsWith("rig:")) {
                        int begin = key.indexOf(':') + 1, end = key.indexOf(':', begin);
                        if (end > begin
                                && !expandedGraphs.contains("graph:" + key.substring(begin, end))) {
                            continue;
                        }
                    }
                    rows.add(new Row(group, entry));
                }
            }
        }
        return rows;
    }

    private static float scale(int w, int h) {
        return Math.min(h / 740f, w / 1050f);
    }

    private static float left(int w, int h) {
        return w - W * scale(w, h) - 16 * scale(w, h);
    }

    private static int capacity() {
        return (BOTTOM - TOP) / ROW;
    }

    private static String tr(String key) {
        return Component.translatable("ui.stevedance.display." + key).getString();
    }

    public static void draw(GuiGraphics g, int width, int height) {
        draw(g, width, height, -1, -1);
    }

    public static void draw(GuiGraphics g, int width, int height, double mouseX, double mouseY) {
        if (!panel) {
            return;
        }
        OriginalUiAssets.prepare();
        float scale = scale(width, height),
                left = left(width, height),
                top = (height - H * scale) / 2;
        double mx = (mouseX - left) / scale, my = (mouseY - top) / scale;
        var rows = rows();
        first = Math.min(first, Math.max(0, rows.size() - capacity()));
        g.pose().pushPose();
        g.pose().translate(left, top, 600);
        g.pose().scale(scale, scale, 1);
        // Reuse the actual selection background, row sprite and outer bezel. / 复用选曲页生产素材。
        g.enableScissor((int) left, (int) top, (int) (left + W * scale), (int) (top + H * scale));
        g.fill(12, 12, W - 12, H - 12, 0xf3d7dce5);
        g.pose().pushPose();
        g.pose().scale(W / 1280f, H / 728f, 1);
        OriginalUiAssets.draw(g, "menu_common", "bg_home", 0);
        g.pose().popPose();
        g.fill(20, 24, W - 20, 77, 0xd52a2936);
        // The native red/white title plate, with Minecraft glyphs. / 原红白标题板，文字改用MC字形。
        OriginalUiAssets.spriteSized(
                g, "common_info", "common_info_menu_frame_title_background_home01", 20, 24, 62, 53);
        OriginalUiAssets.spriteSized(
                g, "common_info", "common_info_menu_frame_title_background_home02", 82, 24, 190, 53);
        OriginalUiAssets.spriteSized(
                g, "common_info", "common_info_menu_frame_title_background_home03", 272, 24, 48, 53);
        label(g, tr("title"), 88, 40, 2f, 0xff171722, 190);
        boolean all = PvVisibility.entries().stream().allMatch(e -> PvVisibility.visible(e.key()));
        drawRow(g, 24, 92, W - 48, ROW - 4, mx, my, all, false);
        check(g, 40, 102, all, false);
        label(g, tr("all"), 66, 101, 1.8f, 0xffffffff, 270);
        g.fill(22, TOP - 5, W - 22, BOTTOM + 3, 0x784b286e);
        for (int i = first; i < Math.min(rows.size(), first + capacity()); i++) {
            var row = rows.get(i);
            int y = TOP + (i - first) * ROW;
            var group = row.group();
            int count = PvVisibility.entries(group).size(), shown = PvVisibility.shown(group);
            boolean checked =
                    row.entry() == null
                            ? count > 0 && shown == count
                            : PvVisibility.visible(row.entry().key());
            boolean partial = row.entry() == null && shown > 0 && shown < count;
            drawRow(g, 26, y, W - 58, ROW - 4, mx, my, checked, row.entry() != null);
            boolean graph = row.entry() != null && row.entry().key().startsWith("graph:");
            int box = row.entry() == null ? 58 : graph ? 76 : 94;
            check(g, box, y + 10, checked, partial);
            if (row.entry() == null) {
                label(g, expanded.contains(group) ? "−" : "+", 36, y + 11, 1.6f, 0xffffffff, 22);
                label(
                        g,
                        tr(group.name().toLowerCase(Locale.ROOT)),
                        84,
                        y + 9,
                        1.8f,
                        0xffffffff,
                        200);
                label(g, shown + "/" + count, 300, y + 13, 1.2f, 0xffd7faff, 88);
            } else {
                if (graph) {
                    label(
                            g,
                            expandedGraphs.contains(row.entry().key()) ? "−" : "+",
                            57,
                            y + 11,
                            1.5f,
                            0xffffffff,
                            20);
                }
                label(
                        g,
                        row.entry().label(),
                        graph ? 102 : 120,
                        y + 10,
                        1.6f,
                        checked ? 0xffffffff : 0xffd0c3dd,
                        graph ? 286 : 268);
            }
        }
        int track = BOTTOM - TOP;
        g.fill(W - 27, TOP, W - 22, BOTTOM, 0x88724196);
        if (rows.size() > capacity()) {
            int thumb = Math.max(20, track * capacity() / rows.size()),
                    y = TOP + (track - thumb) * first / (rows.size() - capacity());
            g.fill(W - 27, y, W - 22, y + thumb, 0xff9dfaff);
        }
        drawRow(g, 26, 590, W - 52, 36, mx, my, false, false);
        label(g, tr("restore"), 40, 600, 1.8f, 0xffffffff, 340);
        label(g, tr("hint"), 30, 642, 1.55f, 0xff272537, 380);
        g.disableScissor();
        // 中文：最后绘制帧，保持与菜单相同的外层顺序。
        // EN: Draw the frame LAST, keeping the same outside-layer order as the menus.
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        int[] dx = {-6, 32, W - 32, W + 6},
                dy = {-6, 32, H - 32, H + 6},
                sx = {0, 96, 1440, 1536},
                sy = {0, 96, 928, 1024};
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                if (a != 1 || b != 1) {
                    g.blit(
                            FRAME,
                            dx[a],
                            dy[b],
                            dx[a + 1] - dx[a],
                            dy[b + 1] - dy[b],
                            sx[a],
                            sy[b],
                            sx[a + 1] - sx[a],
                            sy[b + 1] - sy[b],
                            1536,
                            1024);
                }
            }
        }
        g.pose().popPose();
    }

    private static void label(
            GuiGraphics g, String text, int x, int y, float size, int color, int width) {
        var font = Minecraft.getInstance().font;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size, size, 1);
        g.drawString(font, font.plainSubstrByWidth(text, (int) (width / size)), 0, 0, color, false);
        g.pose().popPose();
    }

    private static void check(GuiGraphics g, int x, int y, boolean checked, boolean partial) {
        g.fill(x, y, x + 16, y + 16, 0xffede7f7);
        g.fill(x + 2, y + 2, x + 14, y + 14, 0xff493d5d);
        if (partial) {
            g.fill(x + 4, y + 7, x + 12, y + 9, 0xffa2ffff);
        } else if (checked) {
            for (int q = 0; q < 4; q++) {
                g.fill(x + 3 + q, y + 7 + q, x + 5 + q, y + 9 + q, 0xff9affff);
            }
            for (int q = 0; q < 6; q++) {
                g.fill(x + 6 + q, y + 10 - q, x + 8 + q, y + 12 - q, 0xff9affff);
            }
        }
    }

    private static void drawRow(
            GuiGraphics g,
            int x,
            int y,
            int w,
            int h,
            double mx,
            double my,
            boolean checked,
            boolean child) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xb825cdd8 : child ? 0x60473064 : 0x905a3078);
        OriginalUiAssets.spriteSized(g, "song_selection", "song_selection_text_aa_base_b", x, y, w, h);
        if (hover) {
            g.fill(x, y, x + w, y + 2, 0xffa5ffff);
            g.fill(x, y + h - 2, x + w, y + h, 0xff72f7ff);
        }
    }

    public static boolean click(double x, double y, int button, int width, int height) {
        if (!panel) {
            return false;
        }
        float s = scale(width, height);
        double mx = (x - left(width, height)) / s, my = (y - (height - H * s) / 2) / s;
        if (mx < 0 || mx > W || my < 0 || my > H) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        if (mx >= 26 && mx < W - 26 && my >= 590 && my < 626) {
            PvVisibility.restoreDefaults();
            return true;
        }
        if (my >= 92 && my < 128) {
            PvVisibility.all(
                    !PvVisibility.entries().stream().allMatch(e -> PvVisibility.visible(e.key())));
            return true;
        }
        if (my >= TOP && my < TOP + capacity() * ROW) {
            var rows = rows();
            int index = first + (int) ((my - TOP) / ROW);
            if (index < rows.size()) {
                var row = rows.get(index);
                if (row.entry() != null) {
                    String key = row.entry().key();
                    if (key.startsWith("graph:") && mx < 76) {
                        if (!expandedGraphs.remove(key)) {
                            expandedGraphs.add(key);
                        }
                    } else {
                        PvVisibility.toggle(key);
                    }
                } else if (mx < 58) {
                    if (!expanded.remove(row.group())) {
                        expanded.add(row.group());
                    }
                    first = Math.min(first, Math.max(0, rows().size() - capacity()));
                } else {
                    PvVisibility.set(
                            row.group(),
                            PvVisibility.shown(row.group())
                                    != PvVisibility.entries(row.group()).size());
                }
            }
        }
        return true;
    }

    public static boolean scroll(double x, double y, double amount, int width, int height) {
        float s = scale(width, height), top = (height - H * s) / 2;
        if (!panel
                || x < left(width, height)
                || x > left(width, height) + W * s
                || y < top
                || y > top + H * s) {
            return false;
        }
        first =
                Math.max(
                        0,
                        Math.min(
                                Math.max(0, rows().size() - capacity()),
                                first - (int) Math.signum(amount) * 2));
        return true;
    }
}
