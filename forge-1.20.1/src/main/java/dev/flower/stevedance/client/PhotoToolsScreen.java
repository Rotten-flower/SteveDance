package dev.flower.stevedance.client;

import dev.flower.stevedance.SongSelectionCatalog;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 使用模型选择页的原生按钮、切角面板和外框。 / Reuse the native model-browser artwork. */
public final class PhotoToolsScreen extends Screen {
    private static final ResourceLocation FRAME =
            new ResourceLocation("stevedance", "textures/gui/menu_window.png");

    private record Hit(int x, int y, int w, int h, Runnable action) {}

    private final PhotoScreen parent;
    private final List<Hit> hits = new ArrayList<>();
    private float scale;
    private int tab, first, focus;
    private final long born = System.nanoTime();

    public PhotoToolsScreen(PhotoScreen parent) {
        super(Component.translatable("ui.stevedance.photo.title"));
        this.parent = parent;
        OriginalUiAssets.prepare();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component tr(String key) {
        return Component.translatable("ui.stevedance.photo." + key);
    }

    private float vx(double x) {
        return (float) ((x - (width - 1280 * scale) / 2) / scale);
    }

    private float vy(double y) {
        return (float) ((y - (height - 728 * scale) / 2) / scale);
    }

    private void text(GuiGraphics g, Component c, int x, int y, int max) {
        text(g, c, x, y, max, 0xffe9ffff);
    }

    private void text(GuiGraphics g, Component c, int x, int y, int max, int color) {
        float size = Math.min(2, max / (float) Math.max(1, font.width(c)));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size, size, 1);
        g.drawString(font, c, 0, 0, color, false);
        g.pose().popPose();
    }

    private void button(
            GuiGraphics g,
            int mx,
            int my,
            int x,
            int y,
            int w,
            Component label,
            Runnable action,
            boolean enabled) {
        boolean over = vx(mx) >= x && vx(mx) < x + w && vy(my) >= y && vy(my) < y + 42;
        ModelSelectionStyle.nativeButton(
                g, x, y, w, 42, label, enabled && (over || hits.size() == focus), enabled);
        if (enabled) {
            hits.add(new Hit(x, y, w, 42, action));
        }
    }

    private void button(
            GuiGraphics g, int mx, int my, int x, int y, int w, String key, Runnable action) {
        button(g, mx, my, x, y, w, tr(key), action, true);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        hits.clear();
        scale = OriginalUiAssets.begin(g, width, height, .84f, true);
        double seconds = (System.nanoTime() - born) / 1e9;
        int left = Math.round((width - 1280 * scale) / 2),
                top = Math.round((height - 728 * scale) / 2);
        g.enableScissor(left, top, Math.round(left + 1280 * scale), Math.round(top + 728 * scale));
        OriginalUiAssets.loop(g, "song_setup", "bg", seconds);
        text(g, title, 48, 42, 500, 0xff173e4b);
        for (int i = 0; i < 3; i++) {
            final int page = i;
            button(
                    g,
                    mx,
                    my,
                    48 + i * 260,
                    100,
                    238,
                    tr(new String[] {"actor", "motion", "camera"}[i]),
                    () -> {
                        tab = page;
                        focus = page;
                    },
                    true);
        }
        ModelSelectionStyle.panel(g, 36, 162, 1208, 462, 0xd9233946);
        if (tab == 0) {
            text(g, tr("actor_hint"), 68, 190, 1000);
            button(
                    g,
                    mx,
                    my,
                    68,
                    246,
                    400,
                    "model",
                    () ->
                            minecraft.setScreen(
                                    new PerformerModelSelectionScreen(
                                            this, PhotoMode.choice(), PhotoMode::model)));
            text(g, tr("position"), 620, 190, 520);
            var a = PhotoMode.anchor();
            double[] values = {a.x, a.y, a.z};
            for (int i = 0; i < 3; i++) {
                final int axis = i;
                int y = 246 + i * 70;
                text(
                        g,
                        Component.literal(
                                String.format(
                                        Locale.ROOT,
                                        "%s   %.2f",
                                        new String[] {"X", "Y", "Z"}[i],
                                        values[i])),
                        620,
                        y + 12,
                        240);
                button(
                        g,
                        mx,
                        my,
                        910,
                        y,
                        124,
                        Component.literal("− 0.25"),
                        () -> nudge(axis, -.25),
                        true);
                button(
                        g,
                        mx,
                        my,
                        1050,
                        y,
                        124,
                        Component.literal("+ 0.25"),
                        () -> nudge(axis, .25),
                        true);
            }
            text(g, tr("heading"), 68, 400, 350);
            text(
                    g,
                    Component.literal(String.format(Locale.ROOT, "%.0f°", PhotoMode.heading())),
                    420,
                    400,
                    150);
            button(
                    g,
                    mx,
                    my,
                    68,
                    446,
                    184,
                    Component.literal("− 15°"),
                    () -> PhotoMode.heading(-15),
                    true);
            button(
                    g,
                    mx,
                    my,
                    278,
                    446,
                    184,
                    Component.literal("+ 15°"),
                    () -> PhotoMode.heading(15),
                    true);
        } else if (tab == 1) {
            text(g, tr(PhotoMode.loading() ? "loading" : "choose_motion"), 68, 184, 1000);
            var all =
                    SongSelectionCatalog.songs().stream()
                            .filter(SongSelectionCatalog.Song::pvAvailable)
                            .toList();
            first = Math.max(0, Math.min(first, Math.max(0, all.size() - 6)));
            for (int i = 0; i < 6 && first + i < all.size(); i++) {
                var song = all.get(first + i);
                button(
                        g,
                        mx,
                        my,
                        68,
                        228 + i * 56,
                        738,
                        dev.flower.stevedance.client.SongText.title(song.titleKey()),
                        () -> PhotoMode.load(song.id()),
                        !PhotoMode.loading());
            }
            button(g, mx, my, 870, 246, 300, "up", () -> first = Math.max(0, first - 6));
            button(g, mx, my, 870, 310, 300, "down", () -> first += 6);
            button(
                    g,
                    mx,
                    my,
                    870,
                    420,
                    300,
                    tr(PhotoMode.music() ? "music_on" : "music_off"),
                    PhotoMode::toggleMusic,
                    true);
        } else {
            button(
                    g,
                    mx,
                    my,
                    68,
                    198,
                    480,
                    tr(PhotoMode.authored() ? "authored" : "free"),
                    PhotoMode::toggleCamera,
                    PhotoMode.duration() > 0);
            text(g, tr("free_hint"), 620, 210, 560);
            text(g, tr("fov"), 68, 300, 320);
            text(
                    g,
                    Component.literal(String.format(Locale.ROOT, "%.0f°", PhotoMode.fov())),
                    460,
                    300,
                    120);
            button(
                    g,
                    mx,
                    my,
                    620,
                    288,
                    240,
                    Component.literal("− 5°"),
                    () -> PhotoMode.lens(-5),
                    !PhotoMode.authored());
            button(
                    g,
                    mx,
                    my,
                    900,
                    288,
                    240,
                    Component.literal("+ 5°"),
                    () -> PhotoMode.lens(5),
                    !PhotoMode.authored());
            text(g, tr("roll"), 68, 390, 320);
            text(
                    g,
                    Component.literal(String.format(Locale.ROOT, "%.0f°", PhotoMode.roll())),
                    460,
                    390,
                    120);
            button(
                    g,
                    mx,
                    my,
                    620,
                    378,
                    240,
                    Component.literal("− 5°"),
                    () -> PhotoMode.tilt(-5),
                    !PhotoMode.authored());
            button(
                    g,
                    mx,
                    my,
                    900,
                    378,
                    240,
                    Component.literal("+ 5°"),
                    () -> PhotoMode.tilt(5),
                    !PhotoMode.authored());
        }
        if (!PhotoMode.error().isEmpty()) {
            text(g, Component.translatable(PhotoMode.error()), 68, 586, 1100);
        }
        button(g, mx, my, 968, 660, 240, Component.translatable("gui.done"), this::onClose, true);
        g.disableScissor();
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.setColor(1, 1, 1, 1);
        g.blit(FRAME, -18, -18, 1316, 764, 0, 0, 1536, 1024, 1536, 1024);
        OriginalUiAssets.end(g);
    }

    private void nudge(int axis, double d) {
        PhotoMode.moveAnchor(axis == 0 ? d : 0, axis == 1 ? d : 0, axis == 2 ? d : 0);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
        for (var hit : hits) {
            if (vx(x) >= hit.x
                    && vx(x) < hit.x + hit.w
                    && vy(y) >= hit.y
                    && vy(y) < hit.y + hit.h) {
                hit.action.run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (tab == 1) {
            first += delta > 0 ? -1 : 1;
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            tab = (tab + 1) % 3;
            focus = tab;
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_UP) {
            focus =
                    Math.floorMod(
                            focus + (key == GLFW.GLFW_KEY_DOWN ? 1 : -1), Math.max(1, hits.size()));
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER && !hits.isEmpty()) {
            hits.get(Math.min(focus, hits.size() - 1)).action.run();
            return true;
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
