package dev.flower.stevedance.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class GamepadGuideScreen extends RhythmScreen
        implements PvPlaybackController.WorldView {
    private final Screen parent;

    public GamepadGuideScreen(Screen parent) {
        super("ui.stevedance.pad.guide_title");
        this.parent = parent;
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        start(g);
        g.fill(0, 0, 1280, 728, 0xff17313e);
        text(g, title, 54, 40, 0xffffffff, 2.5f);
        String[] directions = {"↑", "→", "↓", "←"};
        for (int i = 0; i < 4; i++) {
            int x = 74 + i * 280;
            text(
                    g,
                    GamepadArtwork.face(i, "ps")
                            + " / "
                            + GamepadArtwork.face(i, "xbox")
                            + "  "
                            + directions[i],
                    x,
                    119,
                    0xffd9faff,
                    2.8f);
        }
        String[] lines = {
            "guide_faces",
            "guide_double",
            "guide_stars",
            "guide_link",
            "guide_pause",
            "guide_switch"
        };
        for (int i = 0; i < lines.length; i++) {
            int y = 202 + i * 66;
            g.fill(48, y - 10, 1232, y + 48, 0xff244654);
            var text = font.split(Component.translatable("ui.stevedance.pad." + lines[i]), 630);
            g.pose().pushPose();
            g.pose().translate(70, y, 0);
            g.pose().scale(1.8f, 1.8f, 1);
            for (int n = 0; n < text.size(); n++) {
                g.drawString(font, text.get(n), 0, n * 12, 0xffe9faff, false);
            }
            g.pose().popPose();
        }
        control(g, mx, my, 970, 628, 260, 48, "ui.stevedance.back", this::onClose);
        finish(g);
    }

    @Override
    protected void padPressed(int bit) {
        if (bit == 1 || bit == 2 || bit == 128) {
            onClose();
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
