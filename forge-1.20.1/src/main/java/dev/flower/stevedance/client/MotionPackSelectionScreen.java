package dev.flower.stevedance.client;

import dev.flower.stevedance.MotionPackLibrary;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 自定义内容使用相同的菜单画布，不改变其播放路径。
 * <p>Custom content uses the same menu canvas, without changing its playback path.</p>
 */
public final class MotionPackSelectionScreen extends RhythmScreen {
    private List<MotionPackLibrary.Pack> packs = List.of();
    private int selected, offset;

    public MotionPackSelectionScreen() {
        super("ui.stevedance.song_select");
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

    @Override
    protected void init() {
        packs = new ArrayList<>(MotionPackLibrary.all());
        selected = Math.max(0, Math.min(selected, packs.size() - 1));
    }

    private void play() {
        if (packs.isEmpty()) {
            return;
        }
        var p = packs.get(selected);
        if (p.playable()) {
            ClientPlaybackRuntime.prepare(p);
        } else {
            ClientPlaybackRuntime.error = p.error();
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        start(g);
        OriginalUiAssets.loop(g, "menu_common", "bg_home", seconds());
        text(g, title, 52, 44, 0xff133d4c, 2.4f);
        text(g, Component.translatable("ui.stevedance.choose_song"), 54, 108, 0xff278a92, 1.7f);
        for (int i = offset; i < Math.min(offset + 8, packs.size()); i++) {
            final int index = i;
            var p = packs.get(i);
            int y = 160 + (i - offset) * 52;
            g.fill(50, y, 690, y + 46, i == selected ? 0xffb82d4c : 0xeed5e9e9);
            text(
                    g,
                    font.plainSubstrByWidth(ClientPlaybackRuntime.title(p).getString(), 250),
                    70,
                    y + 12,
                    i == selected ? 0xffffffff : 0xff234b55,
                    2f);
            controls.add(new Control(50, y, 640, 46, p.manifest().id(), () -> selected = index));
        }
        if (!packs.isEmpty()) {
            var p = packs.get(selected);
            text(
                    g,
                    font.plainSubstrByWidth(ClientPlaybackRuntime.title(p).getString(), 220),
                    746,
                    164,
                    0xff133d4c,
                    2f);
            if (p.playable()) {
                text(
                        g,
                        Component.translatable(
                                "ui.stevedance.duration",
                                p.chart().durationMs() / 60000,
                                String.format(
                                        Locale.ROOT, "%02d", p.chart().durationMs() / 1000 % 60)),
                        746,
                        242,
                        0xff156773,
                        1.7f);
                text(
                        g,
                        Component.translatable(
                                "ui.stevedance.note_count", p.chart().notes().size()),
                        746,
                        290,
                        0xff234b55,
                        1.7f);
                text(
                        g,
                        Component.translatable("ui.stevedance.motion_ready"),
                        746,
                        338,
                        0xff234b55,
                        1.5f);
            } else {
                text(g, Component.translatable(p.error()), 746, 242, 0xffa32447, 1.4f);
            }
        }
        if (ClientPlaybackRuntime.loading()) {
            text(g, Component.translatable("ui.stevedance.loading"), 746, 440, 0xff156773, 1.7f);
        }
        if (!ClientPlaybackRuntime.error.isEmpty()) {
            text(g, Component.translatable(ClientPlaybackRuntime.error), 70, 604, 0xffa32447, 1.4f);
        }
        control(g, mx, my, 746, 620, 230, 62, "ui.stevedance.play", this::play);
        control(
                g,
                mx,
                my,
                996,
                620,
                230,
                62,
                "ui.stevedance.settings",
                () -> minecraft.setScreen(new ModelBindingSettingsScreen(this)));
        finish(g);
    }

    private void move(int delta) {
        if (packs.isEmpty()) {
            return;
        }
        selected = Math.max(0, Math.min(packs.size() - 1, selected + delta));
        offset = Math.max(0, Math.min(offset, selected));
        if (selected >= offset + 8) {
            offset = selected - 7;
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        move(amount > 0 ? -1 : 1);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER) {
            play();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F1) {
            minecraft.setScreen(new ModelBindingSettingsScreen(this));
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            move(key == GLFW.GLFW_KEY_UP ? -1 : 1);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        ClientPlaybackRuntime.cancel();
        super.onClose();
    }
}
