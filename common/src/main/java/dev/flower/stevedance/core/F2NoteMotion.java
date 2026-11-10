package dev.flower.stevedance.core;

/**
 * 飞行轨迹继续越过目标，不在判定点截停。
 * <p>Flight continues beyond the target; it is never clamped to the judgment point.</p>
 */
public final class F2NoteMotion {
    private F2NoteMotion() {}

    public static float progress(RhythmChart.Note n, int ms) {
        return Math.max(0, (ms - n.spawnMs()) / (float) n.flightMs());
    }

    /**
     * 目标在判定前半秒达到完整尺寸，便于辨认。
     * <p>The target is readable at full size half a second before its judgment time.</p>
     */
    public static final int FULL_SIZE_LEAD_MS = 500;

    public static float enterScale(RhythmChart.Note n, int ms) {
        return Math.max(
                0,
                Math.min(
                        1,
                        (ms - n.spawnMs())
                                / (float)
                                        Math.max(1, n.timeMs() - FULL_SIZE_LEAD_MS - n.spawnMs())));
    }

    public static float x(RhythmChart.Note n, float p) {
        float r = 1 - p;
        double a = Math.toRadians(n.angle());
        float wave =
                (float)
                        (Math.sin(r * Math.PI * n.frequency())
                                * n.distance()
                                * n.amplitude()
                                * r
                                * .5);
        return n.x() + (float) Math.sin(a) * n.distance() * r + (float) Math.cos(a) * wave;
    }

    public static float y(RhythmChart.Note n, float p) {
        float r = 1 - p;
        double a = Math.toRadians(n.angle());
        float wave =
                (float)
                        (Math.sin(r * Math.PI * n.frequency())
                                * n.distance()
                                * n.amplitude()
                                * r
                                * .5);
        return n.y() - (float) Math.cos(a) * n.distance() * r + (float) Math.sin(a) * wave;
    }
}
