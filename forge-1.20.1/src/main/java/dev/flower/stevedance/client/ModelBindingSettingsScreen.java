package dev.flower.stevedance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

/**
 * 原有十键内容绑定在共享菜单窗口中显示。
 * <p>Original ten-key content bindings, presented in the shared menu window.</p>
 */
public final class ModelBindingSettingsScreen extends RhythmScreen {
    private final Screen parent;
    private int binding = -1;

    public ModelBindingSettingsScreen(Screen parent) {
        super("ui.stevedance.settings");
        this.parent = parent;
        OriginalUiAssets.prepare();
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    boolean capturingKey() {
        return binding >= 0;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (binding >= 0) {
            if (key != GLFW.GLFW_KEY_ESCAPE && key >= 32 && key <= 348) {
                ClientPlaybackSettings.bind(binding, key);
            }
            binding = -1;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return binding >= 0 || super.mouseClicked(x, y, button);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        start(g);
        OriginalUiAssets.loop(g, "menu_common", "bg_home", seconds());
        text(g, title, 52, 44, 0xff133d4c, 2.4f);
        control(
                g,
                mx,
                my,
                190,
                126,
                900,
                56,
                Component.translatable("ui.stevedance.offset", ClientPlaybackSettings.offsetMs),
                () ->
                        ClientPlaybackSettings.offsetMs =
                                ClientPlaybackSettings.offsetMs >= 300
                                        ? -300
                                        : ClientPlaybackSettings.offsetMs + 10);
        control(
                g,
                mx,
                my,
                190,
                196,
                900,
                56,
                Component.translatable(
                        "ui.stevedance.volume", Math.round(ClientPlaybackSettings.volume * 100)),
                () ->
                        ClientPlaybackSettings.volume =
                                ClientPlaybackSettings.volume >= .99
                                        ? 0
                                        : ClientPlaybackSettings.volume + .1f);
        for (int i = 0; i < 10; i++) {
            final int index = i;
            control(
                    g,
                    mx,
                    my,
                    190 + (i % 2) * 460,
                    276 + (i / 2) * 62,
                    440,
                    50,
                    binding == i
                            ? Component.translatable("ui.stevedance.press_key")
                            : Component.translatable(
                                    "ui.stevedance.binding." + i,
                                    ClientPlaybackSettings.keyName(i)),
                    () -> binding = index);
        }
        control(g, mx, my, 940, 646, 270, 52, "gui.done", this::onClose);
        finish(g);
    }

    @Override
    public void onClose() {
        ClientPlaybackSettings.save();
        minecraft.setScreen(parent);
    }
}
