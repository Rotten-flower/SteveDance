package dev.flower.stevedance.core;

/**
 * 计算Chance区屏幕边缘遮板的确定性滑动距离。
 * <p>Deterministic slide distance for the chance area's screen-edge shutters.</p>
 */
public final class ChanceFrameTransition {
    // 中文：原生HUD每次60Hz界面更新移动总距离的三十分之一。
    // EN: Native HUD moves distance / 30 once per 60 Hz UI update.
    public static final int DURATION_MS = 500;

    public static double visible(int now, int start, int end) {
        if (now <= start || now >= end + DURATION_MS) {
            return 0;
        }
        double amount =
                now < start + DURATION_MS
                        ? (now - start) / (double) DURATION_MS
                        : now <= end ? 1 : 1 - (now - end) / (double) DURATION_MS;
        return Math.max(0, Math.min(1, amount));
    }
}
