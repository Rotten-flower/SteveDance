package dev.flower.stevedance.core;

import java.util.List;

/**
 * 时间单位为毫秒，目标坐标使用PSV的960×544视口。
 * <p>Millisecond timestamps; target coordinates use the PSV 960 by 544 viewport.</p>
 */
public record Chart(
        int schema,
        String id,
        String titleKey,
        int durationMs,
        int approachMs,
        String difficulty,
        List<Note> notes) {
    public enum Kind {
        TAP,
        DOUBLE,
        HOLD,
        SCRATCH,
        DOUBLE_SCRATCH,
        LINK
    }

    public record Note(
            int timeMs,
            int endMs,
            int button,
            Kind kind,
            float x,
            float y,
            float fromX,
            float fromY,
            boolean chance) {}

    public Chart {
        if (schema != 1
                || !id.matches("[a-z0-9_]{1,48}")
                || durationMs < 1000
                || durationMs > 600000
                || approachMs < 300
                || approachMs > 5000
                || notes == null
                || notes.size() > 12000
                || notes.isEmpty()) {
            throw new IllegalArgumentException("Invalid chart header");
        }
        notes = List.copyOf(notes);
        int last = -1;
        for (Note n : notes) {
            if (n == null
                    || n.kind() == null
                    || n.timeMs() < 0
                    || n.timeMs() < last
                    || n.timeMs() > durationMs
                    || n.endMs() < n.timeMs()
                    || n.endMs() > durationMs
                    || n.button() < 0
                    || n.button() > 3
                    || !Float.isFinite(n.x())
                    || !Float.isFinite(n.y())
                    || !Float.isFinite(n.fromX())
                    || !Float.isFinite(n.fromY())
                    || n.x() < 30
                    || n.x() > 930
                    || n.y() < 60
                    || n.y() > 470) {
                throw new IllegalArgumentException("Invalid chart note at " + last);
            }
            if (n.kind() == Kind.HOLD && n.endMs() <= n.timeMs()) {
                throw new IllegalArgumentException("Hold must have a duration");
            }
            last = n.timeMs();
        }
    }

    public static int face(int b) {
        return 1 << b;
    }

    public static int direction(int b) {
        return 1 << (b + 4);
    }

    public static final int SCRATCH_LEFT = 256, SCRATCH_RIGHT = 512;
}
