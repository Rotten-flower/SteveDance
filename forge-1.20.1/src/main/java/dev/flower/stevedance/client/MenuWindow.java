package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** 外框最后绘制，按键置于框外。 / Bezel last, hints outside. */
final class MenuWindow {
    private static final ResourceLocation FRAME = id("menu_window");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("stevedance", "textures/gui/" + name + ".png");
    }

    static void finish(RhythmScreen screen, GuiGraphics g) {
        screen.menuInputIcons.frame();
        if (screen instanceof SongSetupScreen setup) {
            setup.modeIndicator(g);
        }
        g.flush();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1, 1, 1, 1);
        g.blit(FRAME, -18, -18, 1316, 764, 0, 0, 1536, 1024, 1536, 1024);
        if (screen instanceof SongSetupScreen setup) {
            setup.drawPerformanceNotice(g);
        }
        if (screen instanceof TimingCalibrationScreen calibration) {
            if (GamepadInput.active()) {
                message(g, calibration.footerText());
            } else if (calibration.sampling()) {
                row(
                        screen,
                        g,
                        new Hint(calibration.tapKeyName(), action("tap")),
                        new Hint("Esc", action("back")));
            } else if (calibration.showingResult()) {
                row(
                        screen,
                        g,
                        new Hint("↑↓", action("choose")),
                        new Hint("←→", action("fine_adjust")),
                        new Hint("Enter", action("confirm")),
                        new Hint("Esc", action("back")));
            } else {
                row(
                        screen,
                        g,
                        new Hint("↑↓", action("choose")),
                        new Hint("Enter", action("confirm")),
                        new Hint("Esc", action("back")));
            }
            return;
        }
        if (GamepadInput.active()) {
            String pad =
                    screen instanceof SongSelectionScreen
                            ? "select_controls"
                            : screen instanceof PerformerModelSelectionScreen
                                    ? "model_controls"
                                    : screen instanceof SongSetupScreen
                                            ? "song_controls"
                                            : screen instanceof RhythmSettingsScreen options
                                                    ? (options.capturingKey()
                                                            ? "guide_capture"
                                                            : "option_controls")
                                                    : screen instanceof RhythmResultScreen
                                                            ? "result_controls"
                                                            : "menu_controls";
            String label = GamepadHints.pad(pad).getString();
            if (screen instanceof SongSetupScreen setup && setup.hasPerformanceSwitch()) {
                label +=
                        "   "
                                + Component.translatable(
                                                "ui.stevedance.f2_performance_switch_pad",
                                                GamepadInput.style().equals("ps") ? "L3" : "LS")
                                        .getString();
            }
            message(g, label);
            return;
        }
        if (screen instanceof RhythmSettingsScreen options && options.capturingKey()
                || screen instanceof ModelBindingSettingsScreen bindings
                        && bindings.capturingKey()) {
            row(
                    screen,
                    g,
                    new Hint("", action("capture")),
                    new Hint("Esc", action("cancel")),
                    new Hint("F1/F4/F5/F6/F7/F8", action("reserved")));
            return;
        }
        if (screen instanceof SongSelectionScreen) {
            row(
                    screen,
                    g,
                    new Hint("↑↓", action("select")),
                    new Hint("←→", action("difficulty")),
                    new Hint("Enter", action("confirm")),
                    new Hint("Esc", action("back")),
                    new Hint("R", action("records")),
                    new Hint("F1", action("settings")),
                    new Hint("H", action("planet")),
                    new Hint("V", RhythmSettingsScreen.voicePackLabel()));
        } else if (screen instanceof PerformerModelSelectionScreen) {
            row(
                    screen,
                    g,
                    new Hint("↑↓←→", action("models")),
                    new Hint("Q / E", action("actors")),
                    new Hint("Ctrl+F", action("search")),
                    new Hint("Enter", action("confirm")),
                    new Hint("Esc", action("back")));
        } else if (screen instanceof SongSetupScreen setup) {
            var hints =
                    new java.util.ArrayList<Hint>(
                            java.util.List.of(
                                    new Hint("←→", action("choose")),
                                    new Hint("Enter", action("confirm")),
                                    new Hint("Esc", action("back")),
                                    new Hint("P", action("pv")),
                                    new Hint("M", action("models")),
                                    new Hint("F1", action("settings")),
                                    new Hint(
                                            "G",
                                            Component.translatable(
                                                    ClientPlaybackSettings.playbackLabel()))));
            if (setup.hasPerformanceSwitch()) {
                hints.add(
                        new Hint(
                                "S",
                                Component.translatable("ui.stevedance.f2_performance_switch"),
                                setup::switchPerformance));
            }
            row(screen, g, hints.toArray(Hint[]::new));
        } else if (screen instanceof RhythmSettingsScreen options) {
            row(
                    screen,
                    g,
                    new Hint("↑↓", action("choose")),
                    new Hint("←→", action("change")),
                    new Hint("Enter", action("confirm")),
                    new Hint("Esc", action("back")));
        } else if (screen instanceof RhythmResultScreen) {
            row(
                    screen,
                    g,
                    new Hint("←→", action("choose")),
                    new Hint("Enter", action("confirm")),
                    new Hint("Esc", action("back")));
        } else if (screen instanceof RhythmPauseScreen) {
            row(
                    screen,
                    g,
                    new Hint("↑↓", action("choose")),
                    new Hint("Enter", action("confirm")),
                    new Hint("F1", action("settings")),
                    new Hint("Esc", action("resume")));
        } else if (screen instanceof PlayerRecordsScreen) {
            row(screen, g, new Hint("←→", action("select")), new Hint("Esc", action("back")));
        } else if (screen instanceof MotionPackSelectionScreen) {
            row(
                    screen,
                    g,
                    new Hint("↑↓", action("select")),
                    new Hint("Enter", action("confirm")),
                    new Hint("F1", action("settings")),
                    new Hint("Esc", action("back")));
        } else if (screen instanceof ModelBindingSettingsScreen) {
            row(
                    screen,
                    g,
                    new Hint("↑↓", action("choose")),
                    new Hint("Enter", action("confirm")),
                    new Hint("Esc", action("back")));
        } else {
            row(screen, g, new Hint("Enter", action("confirm")), new Hint("Esc", action("back")));
        }
    }

    private record Hint(String key, Component label, Runnable action) {
        Hint(String key, Component label) {
            this(key, label, null);
        }
    }

    private static Component action(String name) {
        return Component.translatable("ui.stevedance.hint." + name);
    }

    private static void row(RhythmScreen screen, GuiGraphics g, Hint... items) {
        var font = Minecraft.getInstance().font;
        float total = 0, gap = 24;
        for (var item : items) {
            total +=
                    screen.menuInputIcons.width(item.key)
                            + (item.key.isEmpty() ? 0 : 8)
                            + font.width(item.label) * 1.8f;
        }
        total += gap * (items.length - 1);
        float scale = Math.min(1, 1260f / total), x = 0;
        g.pose().pushPose();
        g.pose().translate(10, 750, 0);
        g.pose().scale(scale, scale, 1);
        for (var item : items) {
            float w = screen.menuInputIcons.draw(g, item.key, x, 0),
                    keyGap = item.key.isEmpty() ? 0 : 8,
                    itemWidth = w + keyGap + font.width(item.label) * 1.8f;
            g.pose().pushPose();
            g.pose().translate(x + w + keyGap, 10, 0);
            g.pose().scale(1.8f, 1.8f, 1);
            g.drawString(font, item.label, 0, 0, 0xfff0fcff, true);
            g.pose().popPose();
            if (item.action != null) {
                screen.controls.add(
                        new RhythmScreen.Control(
                                10 + x * scale,
                                750,
                                itemWidth * scale,
                                40 * scale,
                                "ui.stevedance.f2_performance_switch",
                                item.action));
            }
            x += itemWidth + gap;
        }
        g.pose().popPose();
    }

    /** 与键盘行共用文字颜色、字号和基线。 / Match the keyboard footer typography. */
    private static void message(GuiGraphics g, String label) {
        var font = Minecraft.getInstance().font;
        float size = Math.min(1.8f, 1260f / Math.max(1, font.width(label)));
        g.pose().pushPose();
        g.pose().translate(10, 760, 0);
        g.pose().scale(size, size, 1);
        g.drawString(font, label, 0, 0, 0xfff0fcff, true);
        g.pose().popPose();
    }
}
