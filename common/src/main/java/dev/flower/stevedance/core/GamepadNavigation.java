package dev.flower.stevedance.core;

/**
 * 仅按输入边触发动作，并提供有界方向重复，不依赖GLFW或界面。
 * <p>Edge-only actions and bounded direction repeat, independent of GLFW and screens.</p>
 */
public final class GamepadNavigation {
    public static final int UP = 1 << 11, RIGHT = 1 << 12, DOWN = 1 << 13, LEFT = 1 << 14;
    private static final int[] PHYSICAL = {3, 1, 0, 2, 11, 12, 13, 14};
    private Object context;
    private int previous, stick;
    private final long[] repeatAt = new long[4];

    public int update(int buttons, float x, float y, Object screen, long now) {
        if (Math.abs(x) < .3f) {
            stick &= ~(LEFT | RIGHT);
        }
        if (Math.abs(y) < .3f) {
            stick &= ~(UP | DOWN);
        }
        if (x >= .65f) {
            stick = (stick & ~LEFT) | RIGHT;
        } else if (x <= -.65f) {
            stick = (stick & ~RIGHT) | LEFT;
        }
        if (y >= .65f) {
            stick = (stick & ~UP) | DOWN;
        } else if (y <= -.65f) {
            stick = (stick & ~DOWN) | UP;
        }
        int held = buttons | stick;
        if (context != screen) {
            context = screen;
            previous = held;
            for (int i = 0; i < 4; i++) {
                repeatAt[i] = now + 350;
            }
            return 0;
        }
        int events = held & ~previous;
        for (int i = 0; i < 4; i++) {
            int bit = 1 << (11 + i);
            if ((held & bit) == 0) {
                repeatAt[i] = now + 350;
            } else if ((events & bit) != 0) {
                repeatAt[i] = now + 350;
            } else if (now >= repeatAt[i]) {
                events |= bit;
                repeatAt[i] = now + 90;
            }
        }
        previous = held;
        return events;
    }

    public void reset() {
        context = null;
        previous = stick = 0;
    }

    /**
     * 仅定义菜单语义，PS圆圈用于确认，节奏输入保持实际物理位置。
     * <p>Menu semantics only: PS circle confirms; rhythm inputs retain physical positions.</p>
     */
    public static int menuEvents(int events, boolean playStation) {
        return playStation ? (events & ~3) | ((events & 1) << 1) | ((events & 2) >> 1) : events;
    }

    public static int rhythmFaces(int buttons) {
        // 中文：逻辑顺序为上、右、下、左，再接对应的方向键。
        // EN: Logical order: top, right, bottom, left, then matching d-pad directions.
        int result = 0;
        for (int i = 0; i < PHYSICAL.length; i++) {
            if ((buttons & (1 << PHYSICAL[i])) != 0) {
                result |= 1 << i;
            }
        }
        return result;
    }

    public static String family(String name) {
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return n.contains("playstation")
                        || n.contains("dualshock")
                        || n.contains("dualsense")
                        || n.contains("ps4")
                        || n.contains("ps5")
                        || n.contains("sony")
                        || n.contains("wireless controller") && !n.contains("xbox")
                ? "ps"
                : "xbox";
    }
}
