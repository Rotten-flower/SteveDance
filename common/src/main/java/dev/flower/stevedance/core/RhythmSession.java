package dev.flower.stevedance.core;

import java.util.Arrays;

/**
 * 共享确定性判定。服务器重复输入判定并拥有最终结果。
 * <p>Shared deterministic judgement. The server repeats input judgement and owns the final result.</p>
 */
public final class RhythmSession {
    public enum Grade {
        COOL,
        FINE,
        SAFE,
        SAD,
        WORST
    }

    public record Feedback(int note, Grade grade, int errorMs) {}

    public record Result(
            int score,
            int maxCombo,
            int cool,
            int fine,
            int safe,
            int sad,
            int worst,
            int life,
            int total) {
        public double accuracy() {
            return total == 0 ? 0 : (cool + fine * .8 + safe * .5 + sad * .2) * 100 / total;
        }

        public boolean perfect() {
            return worst == 0 && safe == 0 && sad == 0 && cool + fine == total;
        }
    }

    private final Chart chart;
    private final byte[] states;
    private final int[] grades = new int[5];
    private final int[] downAt = new int[10];
    private int held, score, combo, maxCombo, life = 100, cursor;
    private Feedback feedback;

    public RhythmSession(Chart chart) {
        this.chart = chart;
        this.states = new byte[chart.notes().size()];
        Arrays.fill(downAt, -100000);
    }

    public Chart chart() {
        return chart;
    }

    public boolean resolved(int index) {
        return states[index] == 2;
    }

    public boolean holding(int index) {
        return states[index] == 1;
    }

    public int cursor() {
        return cursor;
    }

    public int score() {
        return score;
    }

    public int combo() {
        return combo;
    }

    public int life() {
        return life;
    }

    public Feedback feedback() {
        return feedback;
    }

    public Result result() {
        return new Result(
                score,
                maxCombo,
                grades[0],
                grades[1],
                grades[2],
                grades[3],
                grades[4],
                life,
                states.length);
    }

    public void advance(int ms) {
        // 中文：仅访问时间窗口内未解决的音符。
        // EN: Only unresolved notes within the time window are visited.
        for (int i = cursor; i < states.length; i++) {
            Chart.Note n = chart.notes().get(i);
            if (n.timeMs() > ms + 160) {
                break;
            }
            if (states[i] == 0 && ms > n.timeMs() + 160) {
                judge(i, Grade.WORST, ms - n.timeMs());
            } else if (states[i] == 1) {
                if (!faceHeld(n.button())) {
                    judge(i, Grade.WORST, ms - n.endMs());
                } else if (ms >= n.endMs()) {
                    judge(i, Grade.COOL, 0);
                }
            }
        }
        while (cursor < states.length && states[cursor] == 2) {
            cursor++;
        }
    }

    public void input(int bit, boolean pressed, int ms) {
        if (bit <= 0 || (bit & (bit - 1)) != 0 || bit > Chart.SCRATCH_RIGHT) {
            return;
        }
        advance(ms);
        if (pressed) {
            if ((held & bit) != 0) {
                return;
            }
            held |= bit;
            downAt[Integer.numberOfTrailingZeros(bit)] = ms;
        } else {
            held &= ~bit;
            advance(ms);
            return;
        }
        int candidate = -1, error = 100000;
        for (int i = cursor; i < states.length; i++) {
            Chart.Note n = chart.notes().get(i);
            if (n.timeMs() > ms + 160) {
                break;
            }
            int delta = Math.abs(ms - n.timeMs());
            if (states[i] != 0 || delta > 160 || !relevant(n, bit) || !matches(n, ms)) {
                continue;
            }
            if (delta < error) {
                error = delta;
                candidate = i;
            }
        }
        if (candidate < 0) {
            return;
        }
        Chart.Note n = chart.notes().get(candidate);
        Grade grade =
                error <= 35
                        ? Grade.COOL
                        : error <= 70 ? Grade.FINE : error <= 110 ? Grade.SAFE : Grade.SAD;
        if (n.kind() == Chart.Kind.HOLD && grade.ordinal() < 2) {
            states[candidate] = 1;
            feedback = new Feedback(candidate, grade, ms - n.timeMs());
        } else {
            judge(candidate, grade, ms - n.timeMs());
        }
    }

    private boolean faceHeld(int b) {
        return (held & (Chart.face(b) | Chart.direction(b))) != 0;
    }

    private boolean relevant(Chart.Note n, int bit) {
        return switch (n.kind()) {
            case SCRATCH, DOUBLE_SCRATCH, LINK ->
                    bit == Chart.SCRATCH_LEFT || bit == Chart.SCRATCH_RIGHT;
            default -> bit == Chart.face(n.button()) || bit == Chart.direction(n.button());
        };
    }

    private boolean matches(Chart.Note n, int ms) {
        return switch (n.kind()) {
            case DOUBLE ->
                    (held & (Chart.face(n.button()) | Chart.direction(n.button())))
                                    == (Chart.face(n.button()) | Chart.direction(n.button()))
                            && Math.abs(downAt[n.button()] - downAt[n.button() + 4]) <= 80;
            case DOUBLE_SCRATCH -> (held & 768) == 768 && Math.abs(downAt[8] - downAt[9]) <= 80;
            case SCRATCH, LINK -> (held & 768) != 0;
            default -> faceHeld(n.button());
        };
    }

    private void judge(int i, Grade grade, int error) {
        if (states[i] == 2) {
            return;
        }
        states[i] = 2;
        grades[grade.ordinal()]++;
        feedback = new Feedback(i, grade, error);
        int value =
                switch (grade) {
                    case COOL -> 500;
                    case FINE -> 400;
                    case SAFE -> 250;
                    case SAD -> 100;
                    case WORST -> 0;
                };
        if (grade.ordinal() < 2) {
            combo++;
            maxCombo = Math.max(maxCombo, combo);
            life = Math.min(100, life + 2);
            score += value + Math.min(250, (combo / 10) * 50);
        } else {
            combo = 0;
            life = Math.max(0, life - (grade == Grade.WORST ? 8 : grade == Grade.SAD ? 5 : 2));
            score += value;
        }
        if (chart.notes().get(i).chance() && grade.ordinal() < 2) {
            score += 100;
        }
    }
}
