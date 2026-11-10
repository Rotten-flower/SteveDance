package dev.flower.stevedance.client;

import dev.flower.stevedance.core.Chart;
import dev.flower.stevedance.core.RhythmSession;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;

public final class MotionPackPlayScreen extends Screen {
    private int previous;
    private long lastFeedback;
    private RhythmSession.Feedback seen;

    public MotionPackPlayScreen() {
        super(Component.translatable("ui.stevedance.rhythm"));
    }

    public void poll() {
        long window = minecraft.getWindow().getWindow();
        int bits = 0;
        for (int i = 0; i < 10; i++) {
            if (GLFW.glfwGetKey(window, ClientPlaybackSettings.key(i)) == GLFW.GLFW_PRESS) {
                bits |= 1 << i;
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWGamepadState state = GLFWGamepadState.malloc(stack);
            for (int j = GLFW.GLFW_JOYSTICK_1; j <= GLFW.GLFW_JOYSTICK_LAST; j++) {
                if (!GLFW.glfwJoystickIsGamepad(j) || !GLFW.glfwGetGamepadState(j, state)) {
                    continue;
                }
                int[] map = {
                    GLFW.GLFW_GAMEPAD_BUTTON_Y,
                    GLFW.GLFW_GAMEPAD_BUTTON_X,
                    GLFW.GLFW_GAMEPAD_BUTTON_A,
                    GLFW.GLFW_GAMEPAD_BUTTON_B,
                    GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP,
                    GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT,
                    GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN,
                    GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT
                };
                for (int i = 0; i < 8; i++) {
                    if (state.buttons(map[i]) == GLFW.GLFW_PRESS) {
                        bits |= 1 << i;
                    }
                }
                if (Math.abs(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_X)) > .65
                        || Math.abs(state.axes(GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y)) > .65) {
                    bits |= 256;
                }
                if (Math.abs(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_X)) > .65
                        || Math.abs(state.axes(GLFW.GLFW_GAMEPAD_AXIS_RIGHT_Y)) > .65) {
                    bits |= 512;
                }
                if (state.buttons(GLFW.GLFW_GAMEPAD_BUTTON_BACK) == GLFW.GLFW_PRESS) {
                    onClose();
                    return;
                }
                break;
            }
        }
        int changed = previous ^ bits;
        // 中文：先按压允许同时和弦的两侧在同一帧内被评估。
        // EN: Presses first allows both sides of a simultaneous chord to be evaluated in one frame.
        for (int i = 0; i < 10; i++) {
            if ((changed & (1 << i)) != 0 && (bits & (1 << i)) != 0) {
                ClientPlaybackRuntime.input(1 << i, true);
            }
        }
        for (int i = 0; i < 10; i++) {
            if ((changed & (1 << i)) != 0 && (bits & (1 << i)) == 0) {
                ClientPlaybackRuntime.input(1 << i, false);
            }
        }
        previous = bits;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float dt) {
        poll();
        var session = ClientPlaybackRuntime.session;
        if (session == null) {
            return;
        }
        int ms = ClientPlaybackRuntime.timeMs();
        var chart = session.chart();
        MotionPackHudRenderer.overlay(g, width, height);
        MotionPackHudRenderer.titleBar(
                g,
                ClientPlaybackRuntime.title(ClientPlaybackRuntime.selected),
                Component.literal(chart.difficulty()));
        g.fill(45, 65, 440, 76, 0xff141728);
        g.fill(
                47,
                67,
                47 + (int) (389 * session.life() / 100f),
                74,
                session.life() < 30 ? MotionPackHudRenderer.PINK : MotionPackHudRenderer.CYAN);
        MotionPackHudRenderer.text(
                g,
                Component.translatable("ui.stevedance.life"),
                47,
                83,
                MotionPackHudRenderer.WHITE,
                1);
        MotionPackHudRenderer.text(
                g,
                Component.translatable("ui.stevedance.score", session.score()),
                700,
                69,
                MotionPackHudRenderer.WHITE,
                1.4f);
        MotionPackHudRenderer.text(
                g,
                Component.translatable(
                        PerformanceCameraController.overview()
                                ? "ui.stevedance.camera_orbit"
                                : "ui.stevedance.camera_mmd"),
                700,
                94,
                MotionPackHudRenderer.CYAN,
                1);
        for (int i = session.cursor(); i < chart.notes().size(); i++) {
            Chart.Note n = chart.notes().get(i);
            if (n.timeMs() - ms > chart.approachMs()) {
                break;
            }
            if (session.resolved(i)) {
                continue;
            }
            int icon =
                    switch (n.kind()) {
                        case SCRATCH, LINK -> 4;
                        case DOUBLE_SCRATCH -> 5;
                        default -> n.button();
                    };
            int x = (int) n.x(), y = (int) n.y();
            MotionPackHudRenderer.icon(g, icon, x, y, 51, true);
            if (n.kind() == Chart.Kind.DOUBLE) {
                MotionPackHudRenderer.icon(g, n.button(), x - 42, y, 36, true);
            }
            if (session.holding(i)) {
                int length =
                        (int)
                                (120f
                                        * Math.max(0, n.endMs() - ms)
                                        / Math.max(1, n.endMs() - n.timeMs()));
                g.fill(x + 28, y - 4, x + 28 + length, y + 4, MotionPackHudRenderer.CYAN);
                continue;
            }
            float t = Math.max(0, Math.min(1, (n.timeMs() - ms) / (float) chart.approachMs()));
            int movingX = (int) (x + (n.fromX() - x) * t),
                    movingY = (int) (y + (n.fromY() - y) * t);
            if (n.kind() == Chart.Kind.LINK && i > 0) {
                var prior = chart.notes().get(i - 1);
                MotionPackHudRenderer.line(g, (int) prior.x(), (int) prior.y(), x, y, 0xaae7ed78);
            }
            MotionPackHudRenderer.icon(g, icon, movingX, movingY, 40, false);
            if (n.kind() == Chart.Kind.DOUBLE) {
                MotionPackHudRenderer.icon(g, n.button(), movingX - 35, movingY, 32, false);
            }
            if (n.kind() == Chart.Kind.HOLD) {
                MotionPackHudRenderer.text(
                        g,
                        Component.translatable("ui.stevedance.hold"),
                        x - 19,
                        y + 31,
                        MotionPackHudRenderer.WHITE,
                        1);
            }
            if (n.chance()) {
                MotionPackHudRenderer.text(
                        g,
                        Component.translatable("ui.stevedance.chance"),
                        420,
                        98,
                        MotionPackHudRenderer.PINK,
                        1.5f);
            }
        }
        if (session.feedback() != seen) {
            seen = session.feedback();
            lastFeedback = System.nanoTime();
        }
        if (seen != null && System.nanoTime() - lastFeedback < 650_000_000L) {
            var n = chart.notes().get(seen.note());
            int color = seen.grade().ordinal() < 2 ? 0xffffe968 : MotionPackHudRenderer.PINK;
            MotionPackHudRenderer.center(
                    g,
                    Component.translatable(
                            "grade.stevedance."
                                    + seen.grade().name().toLowerCase(java.util.Locale.ROOT)),
                    (int) n.x(),
                    (int) n.y() - 56,
                    color,
                    2);
        }
        if (session.combo() > 1) {
            MotionPackHudRenderer.center(
                    g,
                    Component.translatable("ui.stevedance.combo", session.combo()),
                    480,
                    444,
                    0xfffff071,
                    1.7f);
        }
        if (ms < 0) {
            MotionPackHudRenderer.center(
                    g,
                    Component.translatable("ui.stevedance.ready"),
                    480,
                    220,
                    MotionPackHudRenderer.WHITE,
                    3);
        }
        if (ms > chart.durationMs()) {
            MotionPackHudRenderer.center(
                    g,
                    Component.translatable("ui.stevedance.await_result"),
                    480,
                    220,
                    MotionPackHudRenderer.WHITE,
                    1.6f);
        }
        g.fill(0, 495, 960, 544, 0xde111827);
        if (ClientPlaybackSettings.guide) {
            for (int i = 0; i < 6; i++) {
                int x = 75 + i * 152;
                MotionPackHudRenderer.icon(g, i, x, 519, 28, false);
                Component label =
                        i < 4
                                ? Component.translatable(
                                        "ui.stevedance.bind_pair",
                                        ClientPlaybackSettings.keyName(i),
                                        ClientPlaybackSettings.keyName(i + 4))
                                : i == 4
                                        ? Component.literal(ClientPlaybackSettings.keyName(8))
                                        : Component.translatable(
                                                "ui.stevedance.bind_pair",
                                                ClientPlaybackSettings.keyName(8),
                                                ClientPlaybackSettings.keyName(9));
                MotionPackHudRenderer.text(
                        g, label, x + 22, 515, MotionPackHudRenderer.WHITE, .95f);
            }
        }
        MotionPackHudRenderer.end(g);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (key == GLFW.GLFW_KEY_F6) {
            PerformanceCameraController.toggle();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (button == 0 && PerformanceCameraController.overview()) {
            PerformanceCameraController.drag(dx, dy);
            return true;
        }
        return super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (PerformanceCameraController.overview()) {
            PerformanceCameraController.zoom(amount);
            return true;
        }
        return super.mouseScrolled(x, y, amount);
    }

    @Override
    public void onClose() {
        ClientPlaybackRuntime.cancel();
        minecraft.setScreen(new MotionPackSelectionScreen());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
