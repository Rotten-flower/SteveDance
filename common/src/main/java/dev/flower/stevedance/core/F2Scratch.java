package dev.flower.stevedance.core;

/**
 * 通过滞回将模拟手势转换为有界组合输入脉冲。
 * <p>Converts analogue gestures to bounded chord pulses with hysteresis.</p>
 */
public final class F2Scratch {
    private static final long PULSE_MS = RhythmJudgeSession.DOUBLE_MS + 1L;
    private float x, y, travel, threshold = .65f;

    public void sensitivity(float value) {
        threshold = Math.max(.45f, Math.min(.9f, value));
    }

    public void prime(float nextX, float nextY, long ms) {
        x = nextX;
        y = nextY;
        travel = 0;
        active = Math.hypot(x, y) >= threshold;
        pulseUntil = Long.MIN_VALUE;
        last = ms;
    }

    private boolean active;
    private long pulseUntil = Long.MIN_VALUE, last = -1000;

    public boolean update(float nextX, float nextY, long ms) {
        if (!Float.isFinite(nextX) || !Float.isFinite(nextY)) {
            reset();
            return false;
        }
        float magnitude = (float) Math.hypot(nextX, nextY);
        travel += (float) Math.hypot(nextX - x, nextY - y);
        x = nextX;
        y = nextY;
        if (magnitude < .3f) {
            active = false;
            travel = 0;
            return ms < pulseUntil;
        }
        boolean entered = !active && magnitude >= threshold;
        if (entered) {
            active = true;
        }
        if (active && (entered || travel >= threshold) && (entered || ms - last >= 80)) {
            travel = 0;
            last = ms;
            pulseUntil = ms + PULSE_MS;
        }
        return ms < pulseUntil;
    }

    public void reset() {
        x = y = travel = 0;
        active = false;
        pulseUntil = Long.MIN_VALUE;
        last = -1000;
    }
}
