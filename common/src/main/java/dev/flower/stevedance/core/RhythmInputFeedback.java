package dev.flower.stevedance.core;

/**
 * 音符音效策略独立于设备，失误不播放成功音效。
 * <p>Note audio policy, independent of the device. Misses never emit success samples.</p>
 */
public final class RhythmInputFeedback {
    private RhythmInputFeedback() {}

    /**
     * 物理按下反馈独立于目标或成功判定。
     * <p>Physical press feedback is independent of a target or successful judgment.</p>
     */
    public static String pressSample(
            int bit, RhythmJudgeSession.InputResult result, boolean wrong) {
        if (result == RhythmJudgeSession.InputResult.FREE_PRESS
                || result == RhythmJudgeSession.InputResult.WAITING_DOUBLE
                || result == RhythmJudgeSession.InputResult.JUDGED && wrong) {
            return bit == RhythmJudgeSession.SINGLE_DOUBLE
                    ? "double"
                    : (bit == 256 || bit == 512 || bit == RhythmJudgeSession.KEYBOARD_LINK)
                            ? "star"
                            : "tap";
        }
        return "";
    }

    public static String sample(RhythmChart.Note n, RhythmJudgeSession.Hit h) {
        if (h.wrong() || h.grade() == RhythmJudgeSession.Grade.WORST) {
            return "";
        }
        boolean good =
                h.grade() == RhythmJudgeSession.Grade.COOL
                        || h.grade() == RhythmJudgeSession.Grade.FINE;
        return switch (n.kind()) {
            case HOLD -> good ? (n.release() ? "hold_off" : "hold_on") : (n.release() ? "" : "tap");
            case LINK -> good ? "link" : "star";
            case DOUBLE -> "double";
            case DOUBLE_STAR -> "double_star";
            case STAR, CHANCE_STAR -> "star";
            default -> "tap";
        };
    }

    public static boolean loop(RhythmChart.Note n, RhythmJudgeSession.Hit h) {
        return !h.wrong()
                && h.grade().ordinal() < 2
                && ((n.kind() == RhythmChart.Kind.HOLD && !n.release())
                        || (n.kind() == RhythmChart.Kind.LINK && !n.linkEnd()));
    }

    public static int feedbackNote(RhythmChart chart, RhythmJudgeSession.Hit h) {
        var n = chart.notes().get(h.note());
        return n.kind() == RhythmChart.Kind.HOLD
                        && n.release()
                        && h.timeMs() < n.timeMs() - RhythmJudgeSession.HOLD_TAIL_MS
                ? n.pair()
                : h.note();
    }
}
