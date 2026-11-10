package dev.flower.stevedance.core;

/** Display-only score clock. Never changes judgment or reward totals.
 *  仅控制显示追分，不改判定和奖励数据。 */
public final class PerformanceHudState {
    private RhythmJudgeSession owner;
    private int gaugeMs = -1, serial, direction, pulseMs = -10000, iconMs = -10000;
    private int chanceSection = -1, chanceAt = -1, chanceStart;
    private float chanceFrom, chanceTarget;

    public float chanceFill(RhythmJudgeSession session, int section, int ms) {
        float target = session.chanceProgress(section);
        if (section != chanceSection || ms < chanceAt) {
            chanceSection = section;
            chanceFrom = chanceTarget = target;
            chanceStart = ms;
        }
        float progress = Math.max(0, Math.min(1, (ms - chanceStart) / 150f));
        float value = chanceFrom + (chanceTarget - chanceFrom) * progress;
        if (target != chanceTarget) {
            chanceFrom = value;
            chanceTarget = target;
            chanceStart = ms;
        }
        chanceAt = ms;
        return value;
    }

    public int direction() {
        return direction;
    }

    public float pulseFrame(int ms) {
        return (ms - pulseMs) * .06f;
    }

    public float iconFrame(int ms) {
        return (ms - iconMs) * .06f;
    }

    /** Consume judgment events once, even if multiple judgments arrive between renders.
     *  判定只消费一次；连续同向命中不反复重置长动画的淡入。 */
    public void updateGauge(RhythmJudgeSession session, int ms) {
        if (owner != session || ms < gaugeMs) {
            owner = session;
            serial = 0;
            direction = 0;
            pulseMs = iconMs = -10000;
            frame = -1;
            chanceSection = -1;
            chanceAt = -1;
        }
        gaugeMs = ms;
        for (var hit : session.hitsSince(serial)) {
            if (hit.timeMs() > ms) {
                continue;
            }
            serial = hit.serial();
            var note = session.chart.notes().get(hit.note());
            if (session.chart.zone("chance", note.timeMs())) {
                continue;
            }
            int sign = Integer.signum(hit.lifeDelta());
            if (sign == 0 && session.life() == 255 && direction > 0) {
                direction = 0;
                pulseMs = iconMs = -10000;
            }
            if (sign == 0) {
                continue;
            }
            if (sign != direction || hit.timeMs() - iconMs >= 80 / 0.06) {
                iconMs = hit.timeMs();
            }
            direction = sign;
            pulseMs = hit.timeMs();
        }
    }

    /**
     * 先播放原作起始段再保持循环，不改变其他界面时钟。
     * <p>Play the original intro before holding its loop, without changing other UI clocks.</p>
     */
    public float circleFrame(int ms) {
        float f = iconFrame(ms);
        return f < 40 ? Math.max(0, f) : 40 + (f - 40) % 19;
    }

    private int score, target;
    private long frame = -1;

    public static int lifeBand(int life) {
        return Math.max(0, Math.min(15, life / 17));
    }

    public int score() {
        return score;
    }

    public void update(int ms, int total) {
        long next = Math.max(0, ms) * 60L / 1000;
        if (frame < 0 || next < frame) {
            score = target = Math.max(0, total);
            frame = next;
            return;
        }
        // 中文：追赶先前观察到的目标，新分数的显示时间不能早于对应事件。
        // EN: Catch up with the previously observed target; a new score cannot predate its event.
        while (frame < next) {
            int delta = target - score;
            if (delta < 21) {
                score = target;
            } else {
                score += delta < 141 ? 20 : (int) (delta * 10L / 60);
            }
            frame++;
            if (score == target) {
                frame = next;
                break;
            }
        }
        target = Math.max(0, total);
    }
}
