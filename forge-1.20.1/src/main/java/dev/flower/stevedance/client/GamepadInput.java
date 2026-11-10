package dev.flower.stevedance.client;

import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.GamepadNavigation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWGamepadState;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.charset.StandardCharsets;

/**
 * 只使用选定的物理设备，连接手柄本身不会切换图标。
 * <p>One selected physical device; connecting a pad alone does not change the artwork.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class GamepadInput {
    private static final GamepadNavigation navigation = new GamepadNavigation();
    private static final Object WORLD = new Object();
    private static final int[] MENU_BUTTONS = {
        1,
        2,
        128,
        64,
        4,
        8,
        16,
        32,
        GamepadNavigation.UP,
        GamepadNavigation.DOWN,
        GamepadNavigation.LEFT,
        GamepadNavigation.RIGHT
    };
    private static boolean initialized, usingPad, focused = true;
    private static int joystick = -1, buttons, previousButtons, epoch;
    private static String name = "", family = "xbox";
    private static final float[] axes = new float[6], activityAxes = new float[6];
    private static Screen lastScreen;

    private GamepadInput() {}

    private static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        RhythmSettingsScreen.load();
        try (var stream =
                GamepadInput.class.getResourceAsStream("/assets/stevedance/gamecontrollerdb.txt")) {
            if (stream == null) {
                throw new java.io.IOException("Gamepad mappings absent");
            }
            var buffer =
                    MemoryUtil.memUTF8(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (!GLFW.glfwUpdateGamepadMappings(buffer)) {
                    SteveDanceMod.LOGGER.warn("Some gamepad mappings were rejected");
                }
            } finally {
                MemoryUtil.memFree(buffer);
            }
        } catch (Exception ex) {
            SteveDanceMod.LOGGER.warn("Using GLFW built-in gamepad mappings", ex);
        }
    }

    public static void poll() {
        initialize();
        boolean active = windowActive();
        if (focused != active) {
            focused = active;
            navigation.reset();
            lastScreen = null;
        }
        int old = joystick;
        joystick = selectedJoystick();
        if (old != joystick) {
            epoch++;
            navigation.reset();
            lastScreen = null;
            previousButtons = 0;
            java.util.Arrays.fill(activityAxes, 0);
            name = joystick < 0 ? "" : deviceName(joystick);
            family = GamepadNavigation.family(name);
        }
        buttons = 0;
        java.util.Arrays.fill(axes, 0);
        if (joystick >= 0) {
            readState(joystick);
        }
        if (active && joystick >= 0) {
            boolean activity = (buttons & ~previousButtons) != 0;
            for (int i = 0; i < 4; i++) {
                if (Math.abs(axes[i]) <= .35f) {
                    activityAxes[i] = axes[i];
                } else if (Math.abs(axes[i] - activityAxes[i]) > .2f) {
                    activity = true;
                    activityAxes[i] = axes[i];
                }
            }
            if (activity) {
                usingPad = true;
            }
        }
        previousButtons = buttons;
        if (!active) {
            buttons = 0;
            java.util.Arrays.fill(axes, 0);
        }
    }

    private static boolean windowActive() {
        return Minecraft.getInstance().isWindowActive();
    }

    private static String deviceName(int id) {
        return GLFW.glfwGetGamepadName(id);
    }

    private static int selectedJoystick() {
        if (joystick >= 0 && GLFW.glfwJoystickIsGamepad(joystick)) {
            return joystick;
        }
        for (int j = 0; j <= GLFW.GLFW_JOYSTICK_LAST; j++) {
            if (GLFW.glfwJoystickIsGamepad(j)) {
                return j;
            }
        }
        return -1;
    }

    private static void readState(int id) {
        try (var stack = MemoryStack.stackPush()) {
            var state = GLFWGamepadState.malloc(stack);
            if (GLFW.glfwGetGamepadState(id, state)) {
                for (int b = 0; b <= GLFW.GLFW_GAMEPAD_BUTTON_LAST; b++) {
                    if (state.buttons(b) == GLFW.GLFW_PRESS) {
                        buttons |= 1 << b;
                    }
                }
                for (int a = 0; a < 6; a++) {
                    axes[a] = state.axes(a);
                }
                if (axes[4] > .3f) {
                    buttons |= 1 << 15;
                }
                if (axes[5] > .3f) {
                    buttons |= 1 << 16;
                }
            }
        }
    }

    public static boolean connected() {
        return joystick >= 0;
    }

    public static boolean active() {
        return usingPad && connected();
    }

    public static boolean focused() {
        return focused;
    }

    public static int epoch() {
        return epoch;
    }

    public static int buttons() {
        return buttons;
    }

    public static float axis(int index) {
        return axes[index];
    }

    public static String name() {
        return name == null ? "" : name;
    }

    public static String style() {
        return RhythmSettingsScreen.padIcons.equals("auto")
                ? family
                : RhythmSettingsScreen.padIcons;
    }

    public static void keyboard() {
        usingPad = false;
    }

    public static int navigate(Screen screen) {
        poll();
        if (!focused || !connected()) {
            navigation.reset();
            lastScreen = null;
            return 0;
        }
        // 中文：游玩期间也轮询，跨界面持续按住的动作不作为新按下。
        // EN: Poll during gameplay too: actions held across screens never become new presses.
        if (lastScreen != screen) {
            navigation.reset();
            lastScreen = screen;
        }
        return GamepadNavigation.menuEvents(
                navigation.update(
                        buttons,
                        axis(0),
                        axis(1),
                        screen == null ? WORLD : screen,
                        System.nanoTime() / 1_000_000),
                style().equals("ps"));
    }

    public static void menu(RhythmScreen screen) {
        int events = navigate(screen);
        if (events == 0) {
            return;
        }
        for (int bit : MENU_BUTTONS) {
            if ((events & bit) != 0) {
                screen.padPressed(bit);
                if (Minecraft.getInstance().screen != screen || bit <= 128) {
                    return;
                }
            }
        }
    }

    private static net.minecraft.world.phys.BlockHitResult terminalTarget() {
        var mc = Minecraft.getInstance();
        return mc.level != null
                        && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                        && mc.level
                                .getBlockState(hit.getBlockPos())
                                .is(dev.flower.stevedance.WorldStage.ANCHOR.get())
                ? hit
                : null;
    }

    @SubscribeEvent
    public static void terminal(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.screen != null || mc.gameMode == null || mc.player == null) {
            return;
        }
        int events = navigate(null);
        var hit = terminalTarget();
        if (hit != null && (events & 1) != 0) {
            mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
        }
    }

    @SubscribeEvent
    public static void terminalHint(RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null || mc.options.hideGui || terminalTarget() == null || !active()) {
            return;
        }
        event.getGuiGraphics()
                .drawString(
                        mc.font,
                        GamepadHints.pad("terminal_controls"),
                        12,
                        mc.getWindow().getGuiScaledHeight() - 59,
                        0xffffffff,
                        true);
    }

    private static boolean ours(Screen screen) {
        return screen instanceof RhythmScreen || screen instanceof PvViewerScreen;
    }

    @SubscribeEvent
    public static void key(ScreenEvent.KeyPressed.Pre event) {
        if (ours(event.getScreen())) {
            keyboard();
        }
    }

    @SubscribeEvent
    public static void mouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (ours(event.getScreen())) {
            keyboard();
        }
    }

    @SubscribeEvent
    public static void scroll(ScreenEvent.MouseScrolled.Pre event) {
        if (ours(event.getScreen())) {
            keyboard();
        }
    }
}
