package dev.flower.stevedance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract class RhythmScreen extends Screen {
    final dev.flower.stevedance.client.MenuInputIcons menuInputIcons =
            new dev.flower.stevedance.client.MenuInputIcons();
    final long born = System.nanoTime();
    float scale;
    final List<Control> controls = new ArrayList<>();
    protected int focus;

    record Control(float x, float y, float w, float h, String key, Runnable action) {}

    RhythmScreen(String title) {
        super(Component.translatable(title));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    double seconds() {
        return (System.nanoTime() - born) / 1e9;
    }

    boolean compactMenu() {
        return false;
    }

    boolean framedMenu() {
        return compactMenu();
    }

    float windowZoom() {
        if (!compactMenu()) {
            return 1;
        }
        float t = Math.max(0, Math.min(1, (float) (seconds() / .28)));
        return .84f * (.76f + .24f * (1 - (1 - t) * (1 - t) * (1 - t)));
    }

    void start(GuiGraphics g) {
        if (!(this instanceof RhythmPlayScreen)) {
            GamepadInput.menu(this);
        }
        controls.clear();
        boolean compact = compactMenu();
        float zoom = windowZoom();
        scale = OriginalUiAssets.begin(g, width, height, zoom, compact);
        if (framedMenu()) {
            int l = Math.round((width - 1280 * scale) / 2),
                    t = Math.round((height - 728 * scale) / 2);
            g.enableScissor(l, t, Math.round(l + 1280 * scale), Math.round(t + 728 * scale));
        }
        if (!(this instanceof PerformerModelSelectionScreen)
                || !ClientPlaybackSettings.worldStage) {
            MenuCharacterPresentation.draw(g);
        }
    }

    boolean nativeControls() {
        return false;
    }

    void finish(GuiGraphics g) {
        if (!nativeControls() && !controls.isEmpty()) {
            Control c = controls.get(Math.min(focus, controls.size() - 1));
            g.fill((int) c.x, (int) c.y, (int) (c.x + c.w), (int) c.y + 2, 0xffa9ffff);
            g.fill(
                    (int) c.x,
                    (int) (c.y + c.h) - 2,
                    (int) (c.x + c.w),
                    (int) (c.y + c.h),
                    0xffa9ffff);
        }
        if (!RhythmClientController.error.isEmpty()) {
            text(
                    g,
                    Component.translatable(RhythmClientController.error),
                    36,
                    658,
                    0xffa32447,
                    1.8f);
        }
        GamepadHints.extra(this, g);
        if (framedMenu()) {
            g.disableScissor();
            MenuWindow.finish(this, g);
        }
        OriginalUiAssets.end(g);
    }

    void nativeControl(
            GuiGraphics g,
            int mx,
            int my,
            String scene,
            String root,
            float x,
            float y,
            float w,
            float h,
            String key,
            Runnable action) {
        boolean hover =
                !GamepadInput.active()
                        && virtualX(mx) >= x
                        && virtualX(mx) < x + w
                        && virtualY(my) >= y
                        && virtualY(my) < y + h;
        String layer = root + ((hover || focus == controls.size()) ? "_a" : "_b");
        if (scene.equals("song_setup") && root.equals("btn_pv")) {
            OriginalUiAssets.draw(
                    g,
                    scene,
                    layer,
                    OriginalUiAssets.loopFrame(scene, layer, seconds()),
                    Map.of(
                            "sel_start_mm_btn_txt_sb_03.pic",
                            Component.translatable(key).getString()));
        } else {
            OriginalUiAssets.loop(g, scene, layer, seconds());
        }
        controls.add(new Control(x, y, w, h, key, action));
    }

    void text(GuiGraphics g, Component c, int x, int y, int color, float factor) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(factor, factor, 1);
        g.drawString(font, c, 0, 0, color, false);
        g.pose().popPose();
    }

    void text(GuiGraphics g, String c, int x, int y, int color, float factor) {
        text(g, Component.literal(c), x, y, color, factor);
    }

    void control(
            GuiGraphics g,
            int mx,
            int my,
            float x,
            float y,
            float w,
            float h,
            String key,
            Runnable action) {
        control(g, mx, my, x, y, w, h, Component.translatable(key), action);
    }

    void control(
            GuiGraphics g,
            int mx,
            int my,
            float x,
            float y,
            float w,
            float h,
            Component label,
            Runnable action) {
        float vx = virtualX(mx), vy = virtualY(my);
        boolean hover = !GamepadInput.active() && vx >= x && vx < x + w && vy >= y && vy < y + h;
        g.fill((int) x, (int) y, (int) (x + w), (int) (y + h), hover ? 0xe923b8c2 : 0xdf173c52);
        g.fill((int) x, (int) y, (int) (x + 4), (int) (y + h), 0xff9bffff);
        float size = Math.min(2.2f, (w - 36) / Math.max(1, font.width(label)));
        text(
                g,
                label,
                (int) x + 18,
                (int) (y + (h - font.lineHeight * size) / 2),
                0xffffffff,
                size);
        controls.add(new Control(x, y, w, h, label.getString(), action));
    }

    float virtualX(double x) {
        return (float) ((x - (width - 1280 * scale) / 2) / scale);
    }

    float virtualY(double y) {
        return (float) ((y - (height - 728 * scale) / 2) / scale);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return super.mouseClicked(x, y, button);
        }
        float vx = virtualX(x), vy = virtualY(y);
        for (Control c : controls) {
            if (vx >= c.x && vx < c.x + c.w && vy >= c.y && vy < c.y + c.h) {
                RhythmSoundPlayer.play("select");
                c.action.run();
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (!controls.isEmpty()) {
            if (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_DOWN) {
                focus = (focus + 1) % controls.size();
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP) {
                focus = Math.floorMod(focus - 1, controls.size());
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER) {
                RhythmSoundPlayer.play("select");
                controls.get(Math.min(focus, controls.size() - 1)).action.run();
                return true;
            }
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scan, int mods) {
        if (RhythmClientController.paused && RhythmClientController.playScreen != null) {
            return RhythmClientController.playScreen.keyReleased(key, scan, mods);
        }
        return super.keyReleased(key, scan, mods);
    }

    protected void padPressed(int bit) {
        int key =
                switch (bit) {
                    case 1 -> GLFW.GLFW_KEY_ENTER;
                    case 2, 128 -> GLFW.GLFW_KEY_ESCAPE;
                    case 2048 -> GLFW.GLFW_KEY_UP;
                    case 8192 -> GLFW.GLFW_KEY_DOWN;
                    case 4096, 32 -> GLFW.GLFW_KEY_RIGHT;
                    case 16384, 16 -> GLFW.GLFW_KEY_LEFT;
                    default -> 0;
                };
        if (bit == 64) {
            minecraft.setScreen(new RhythmSettingsScreen(this));
            return;
        }
        if (key != 0) {
            keyPressed(key, 0, 0);
        }
    }
}
