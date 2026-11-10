package dev.flower.stevedance.core;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端预测与服务端会话共用确定性计分。
 * <p>Deterministic scoring shared by prediction and the authoritative server session.</p>
 */
public final class RhythmJudgeSession {
    public enum Grade {
        COOL,
        FINE,
        SAFE,
        SAD,
        WORST
    }

    // 中文：F2nd PCSH00088原生表位于0x816c18f8，错键条目为零。
    // EN: F 2nd PCSH00088: native table 0x816c18f8; wrong-key entries are zero.
    // F 2nd 原程序规则；ARM 执行对照见 work/scoring-native-20261107。
    private static final int[] BASE_POINTS = {500, 300, 100, 30, 0};
    private static final int[][] LIFE_POINTS = {
        {5, 5, 0, -3, 0, 0, -2, -4, -5},
        {2, 1, 0, -5, 0, 0, -4, -6, -10},
        {2, 1, 0, -5, 0, -2, -4, -6, -10},
        {2, 1, 0, -7, 0, -3, -5, -10, -15}
    };
    private static final int[] CHANCE_COEFFICIENT = {75, 83, 87, 90};
    private static final int SCORE_LIMIT = 9_999_999;

    /**
     * 输入边可以等待双押的另一半，而无需消耗目标。
     * <p>Input edges can wait for the other half of a double without consuming a target.</p>
     */
    public enum InputResult {
        IGNORED,
        WAITING_DOUBLE,
        JUDGED,
        FREE_PRESS,
        RELEASED,
        RUSH_PRESS
    }

    public record Hit(
            int serial,
            int note,
            Grade grade,
            int timeMs,
            int deltaMs,
            boolean wrong,
            int points,
            int combo,
            int lifeDelta) {}

    public record Result(
            int score,
            int baseScore,
            int bonusScore,
            int maxCombo,
            int cool,
            int fine,
            int safe,
            int sad,
            int worst,
            int wrong,
            int life,
            int total,
            int judged,
            int technical,
            int chance,
            int maxScore,
            int achievementUnits,
            boolean finished,
            boolean failed) {
        public double achievement() {
            return Math.max(0, Math.min(1_000_000, achievementUnits)) / 10000.0;
        }

        public String rank(int clearPercent) {
            if (failed || !finished) {
                return "misstake";
            }
            double a = achievement();
            return a >= 100
                    ? "perfect"
                    : a >= 95
                            ? "excellent"
                            : a >= 90 ? "great" : a >= clearPercent ? "standard" : "cheap";
        }

        public boolean cleared(int threshold) {
            return finished && !failed && achievement() >= threshold;
        }
    }

    // 中文：节奏玩法规则保留原作目标时间，使用本层规定的判定窗口。
    // EN: Demo gameplay rules. Authored target times are preserved; these are our timing windows.
    public static final int COOL_MS = 50,
            FINE_MS = 100,
            SAFE_MS = 150,
            SAD_MS = 200,
            DOUBLE_MS = 66;
    public static final int SINGLE_DOUBLE = 1024;

    /**
     * 五个键盘输入与控制器组合键和划擦边相互独立。
     * <p>Five keyboard inputs are separate from controller chords and scratch edges.</p>
     */
    public static final int KEYBOARD_LINK = 2048,
            KEYBOARD_FACE_BASE = 4096,
            MAX_INPUT = 32768,
            INPUT_BITS = 16;

    public static final int HOLD_TAIL_MS = 300;

    public static int keyboardFace(int button) {
        return KEYBOARD_FACE_BASE << button;
    }

    public static boolean keyboardFaceBit(int bit) {
        return bit >= KEYBOARD_FACE_BASE && bit <= MAX_INPUT;
    }

    public static int timingWindow(RhythmChart.Note note) {
        return note.release() ? HOLD_TAIL_MS : SAD_MS;
    }

    public static int tailGrade(int delta) {
        int d = Math.abs(delta);
        return d <= 150 ? 0 : d <= HOLD_TAIL_MS ? 1 : 4;
    }

    public final RhythmChart chart;
    private final byte[] statuses;
    private final int[] holdMask, holdGrade;
    private final long[] downAt = new long[INPUT_BITS];
    private final boolean[] keyboardChain;
    private int held,
            lastTime = -10000,
            expireCursor,
            score,
            baseScore,
            bonusScore,
            combo,
            maxCombo,
            life = 127,
            judged,
            wrong,
            serial,
            technical,
            chance,
            linkBonus;
    private final int[] counts = new int[5];
    private final boolean[] zoneDone;
    private final int[] zoneFailureMs;
    private final int[] chanceEligible, chanceGood, holdStarted;
    private final boolean[] chanceSuccess;
    private final int difficulty, noteBudget, noteUnits;
    private int achievementUnits, noteAchievement;
    private boolean finished, failed, practice;
    private final ArrayDeque<Hit> hits = new ArrayDeque<>();
    private int maxScore;

    /**
     * X 原生 RUSH 生命周期与普通评级和连击相互独立。
     * <p>X native RUSH lifecycle is separate from ordinary grades and combo.</p>
     */
    public enum RushPhase {
        START,
        PRESS,
        END
    }

    public record RushEvent(
            int serial,
            int note,
            RushPhase phase,
            int timeMs,
            int count,
            float scale,
            boolean complete) {}

    private final ArrayDeque<RushEvent> rushEvents = new ArrayDeque<>();
    private final Set<Integer> activeRushes = new LinkedHashSet<>(), batchRushes = new HashSet<>();
    private int[] rushCounts, rushStarts;
    private int rushSerial, lastRush = -1;

    public boolean rushing(int index, int ms) {
        return activeRushes.contains(index) && ms < chart.notes().get(index).endMs();
    }

    public int rushPresses() {
        return lastRush < 0 ? 0 : rushCounts[lastRush];
    }

    public int rushPresses(int index) {
        return rushCounts[index];
    }

    public int rushStart(int index) {
        return rushStarts[index];
    }

    public int rushThreshold(int index) {
        var n = chart.notes().get(index);
        return (int) ((n.endMs() - n.timeMs()) / 1000.0 * 4.5);
    }

    public float rushScale(int index) {
        int threshold = rushThreshold(index);
        return threshold <= 0 ? 1 : 1 + Math.min(rushCounts[index], threshold) / (float) threshold;
    }

    public int activeRush(int ms) {
        return activeRushes.stream().filter(i -> rushing(i, ms)).findFirst().orElse(-1);
    }

    public List<Integer> activeRushes(int ms) {
        return activeRushes.stream().filter(i -> rushing(i, ms)).toList();
    }

    public List<RushEvent> rushEventsSince(int after) {
        return rushEvents.stream().filter(e -> e.serial() > after).toList();
    }

    private void rushEvent(int index, RushPhase phase, int ms) {
        rushEvents.addLast(
                new RushEvent(
                        ++rushSerial,
                        index,
                        phase,
                        ms,
                        rushCounts[index],
                        rushScale(index),
                        rushCounts[index] >= rushThreshold(index)));
        while (rushEvents.size() > 128) {
            rushEvents.removeFirst();
        }
    }

    public RhythmJudgeSession(RhythmChart chart) {
        this(chart, false);
        maxScore = maximum(chart);
    }

    private RhythmJudgeSession(RhythmChart chart, boolean practice) {
        this.chart = chart;
        this.practice = practice;
        statuses = new byte[chart.notes().size()];
        rushCounts = new int[statuses.length];
        rushStarts = new int[statuses.length];
        holdMask = new int[statuses.length];
        holdGrade = new int[statuses.length];
        holdStarted = new int[statuses.length];
        keyboardChain = new boolean[statuses.length + 1];
        zoneDone = new boolean[chart.zones().size()];
        zoneFailureMs = new int[zoneDone.length];
        Arrays.fill(zoneFailureMs, -1);
        Arrays.fill(downAt, -100000);
        difficulty = List.of("easy", "normal", "hard", "extreme").indexOf(chart.difficulty());
        int technicalCount =
                (int) chart.zones().stream().filter(z -> z.kind().equals("TECHNICAL")).count();
        boolean hasChance = chart.zones().stream().anyMatch(z -> z.kind().equals("CHANCE"));
        noteBudget = Math.max(0, (100 - (hasChance ? 5 : 0) - 3 * technicalCount) * 10000);
        noteUnits = noteBudget / statuses.length;
        chanceEligible = new int[zoneDone.length];
        chanceGood = new int[zoneDone.length];
        chanceSuccess = new boolean[zoneDone.length];
        for (int i = 0; i < statuses.length; i++) {
            var n = chart.notes().get(i);
            for (int z = 0; z < zoneDone.length; z++) {
                if (inZone(chart.zones().get(z), n)
                        && chart.zones().get(z).kind().equals("CHANCE")
                        && n.kind() != RhythmChart.Kind.CHANCE_STAR) {
                    chanceEligible[z]++;
                }
            }
        }
    }

    private static boolean inZone(RhythmChart.Zone zone, RhythmChart.Note note) {
        return note.timeMs() >= zone.startMs() && note.timeMs() <= zone.endMs();
    }

    /**
     * 累积的 GOOD 目标填充原始槽；MISS 不会重置它。
     * <p>Accumulated good targets fill the original gauge; misses do not reset it.</p>
     */
    public float chanceProgress(int zone) {
        return zone < 0 || zone >= chanceEligible.length || chanceEligible[zone] == 0
                ? 0
                : Math.min(
                        1,
                        chanceGood[zone]
                                * 100f
                                / (chanceEligible[zone] * CHANCE_COEFFICIENT[difficulty]));
    }

    public int chanceGauge(int zone) {
        return zone < 0 || zone >= chanceEligible.length || chanceEligible[zone] == 0
                ? 0
                : Math.min(
                        15,
                        (int)
                                (chanceGood[zone]
                                        * 1500L
                                        / (chanceEligible[zone] * CHANCE_COEFFICIENT[difficulty])));
    }

    /**
     * 每段结果，独立于先前段的成功与否。
     * <p>Per-section result, independent of successful earlier sections.</p>
     */
    public boolean chanceSucceeded(int zone) {
        return zone >= 0 && zone < chanceSuccess.length && chanceSuccess[zone];
    }

    /**
     * 首次失败后保持失败状态；待处理目标不算失败。使用实际判定时间。
     * <p>Sticky first failure; pending targets are not failures. Uses actual judgment time.</p>
     */
    public int zoneFailureMs(RhythmChart.Zone zone) {
        int index = chart.zones().indexOf(zone);
        return index < 0 ? -1 : zoneFailureMs[index];
    }

    public byte status(int index) {
        return statuses[index];
    }

    public boolean holding(int index) {
        return holdMask[index] != 0;
    }

    /**
     * 进入的尾可能预览未判定的头，但绝不会在 HOLD 被 MISS 或断开后继续存在。
     * <p>Incoming tails may preview an unjudged head, but never survive a missed or broken hold.</p>
     */
    public boolean holdTailVisible(int index) {
        var n = chart.notes().get(index);
        return n.kind() == RhythmChart.Kind.HOLD
                && n.release()
                && !failed
                && !finished
                && statuses[index] == 0
                && (statuses[n.pair()] == 0 || holding(n.pair()));
    }

    public int remaining(RhythmChart.Zone zone) {
        int n = 0;
        for (int i = 0; i < statuses.length; i++) {
            var note = chart.notes().get(i);
            if (statuses[i] == 0
                    && note.timeMs() >= zone.startMs()
                    && note.timeMs() <= zone.endMs()) {
                n++;
            }
        }
        return n;
    }

    public boolean zoneGood(RhythmChart.Zone zone) {
        for (int i = 0; i < statuses.length; i++) {
            var note = chart.notes().get(i);
            if (note.timeMs() >= zone.startMs()
                    && note.timeMs() <= zone.endMs()
                    && statuses[i] != 1) {
                return false;
            }
        }
        return true;
    }

    public int held() {
        return held;
    }

    public int combo() {
        return combo;
    }

    public int life() {
        return life;
    }

    public boolean failed() {
        return failed;
    }

    public boolean finished() {
        return finished;
    }

    public int timeMs() {
        return lastTime;
    }

    public List<Hit> hitsSince(int after) {
        return hits.stream().filter(h -> h.serial > after).toList();
    }

    public Result result() {
        return new Result(
                score,
                baseScore,
                bonusScore,
                maxCombo,
                counts[0],
                counts[1],
                counts[2],
                counts[3],
                counts[4],
                wrong,
                life,
                statuses.length,
                judged,
                technical,
                chance,
                maxScore,
                achievementUnits,
                finished,
                failed);
    }

    public void releaseAll(int ms) {
        for (int i = 0; i < INPUT_BITS; i++) {
            if ((held & (1 << i)) != 0) {
                input(1 << i, false, ms);
            }
        }
    }

    public boolean linkSustaining(int next) {
        if (next < 0 || next >= statuses.length || statuses[next] != 0) {
            return false;
        }
        var n = chart.notes().get(next);
        return n.kind() == RhythmChart.Kind.LINK
                && (n.chain() < 0 || !keyboardChain[n.chain()] || (held & KEYBOARD_LINK) != 0);
    }

    /**
     * 将共享的物理绑定解析为一条逻辑边，锁定直到按键抬起。
     * <p>Resolve shared physical bindings to one logical edge, locked until key-up.</p>
     */
    public int keyboardPressBit(int regularBits, boolean shortcut, int ms) {
        int
                result =
                        (regularBits & KEYBOARD_LINK) != 0
                                ? KEYBOARD_LINK
                                : regularBits == 0
                                        ? (shortcut ? SINGLE_DOUBLE : 0)
                                        : Integer.lowestOneBit(regularBits),
                best = SAD_MS + 1;
        for (int i = expireCursor; i < statuses.length; i++) {
            var n = chart.notes().get(i);
            if (n.timeMs() > ms + SAD_MS) {
                break;
            }
            if (statuses[i] != 0 || n.release()) {
                continue;
            }
            int distance = Math.abs(n.timeMs() - ms);
            if (distance > best) {
                continue;
            }
            if (n.kind() == RhythmChart.Kind.LINK && (regularBits & KEYBOARD_LINK) != 0) {
                result = KEYBOARD_LINK;
                best = distance;
            } else if (shortcut && matches(n, SINGLE_DOUBLE)) {
                result = SINGLE_DOUBLE;
                best = distance;
            } else {
                for (int b = 0; b < INPUT_BITS; b++) {
                    int bit = 1 << b;
                    if ((regularBits & bit) != 0 && matches(n, bit)) {
                        result = bit;
                        best = distance;
                        break;
                    }
                }
            }
        }
        return result;
    }

    public InputResult input(int bit, boolean pressed, int ms) {
        return Integer.bitCount(bit) == 1 ? inputBatch(bit, pressed, ms) : InputResult.IGNORED;
    }

    /**
     * 一次物理输入更新，而不是毫秒时间戳，定义 RUSH 去重。
     * <p>One physical input update, not a millisecond timestamp, defines RUSH deduplication.</p>
     */
    public InputResult inputBatch(int mask, boolean pressed, int ms) {
        if (mask <= 0 || (mask & ~((MAX_INPUT << 1) - 1)) != 0) {
            return InputResult.IGNORED;
        }
        batchRushes.clear();
        InputResult result = InputResult.IGNORED;
        for (int remaining = mask; remaining != 0; remaining &= remaining - 1) {
            var edge = inputEdge(Integer.lowestOneBit(remaining), pressed, ms);
            if (edge == InputResult.JUDGED
                    || result != InputResult.JUDGED && edge != InputResult.IGNORED) {
                result = edge;
            }
        }
        return result;
    }

    private InputResult inputEdge(int bit, boolean pressed, int ms) {
        if (finished || failed || Integer.bitCount(bit) != 1 || bit < 1 || bit > MAX_INPUT) {
            return InputResult.IGNORED;
        }
        advance(ms);
        if (finished || failed) {
            return InputResult.IGNORED;
        }
        int b = Integer.numberOfTrailingZeros(bit);
        boolean prior = (held & bit) != 0;
        if (prior == pressed) {
            return InputResult.IGNORED;
        }
        if (pressed) {
            held |= bit;
            downAt[b] = ms;
        } else {
            held &= ~bit;
        }
        int candidate = -1, best = (pressed ? SAD_MS : HOLD_TAIL_MS) + 1;
        // 中文：只在较小的判定窗口内查找；expireCursor 单调前进。
        // EN: Look only in the small timing window; expireCursor is monotonically advanced.
        for (int i = expireCursor; i < statuses.length; i++) {
            var n = chart.notes().get(i);
            if (n.timeMs() > ms + (pressed ? SAD_MS : HOLD_TAIL_MS)) {
                break;
            }
            if (statuses[i] != 0 || n.release() == pressed) {
                continue;
            }
            // 中文：一次释放只属于玩家实际已经开始的长按。
            // EN: A release belongs only to a hold the player has actually started.
            // 中文：加宽的尾判定窗口不得消耗未来的短按 HOLD，
            // EN: The widened tail window must not consume a future short hold while
            // 中文：同时在同一物理按键上释放前一个 TAP。
            // EN: releasing the preceding tap on the same physical key.
            if (n.release()
                    && (statuses[n.pair()] != 1
                            || holdMask[n.pair()] == 0
                            || (holdMask[n.pair()] & bit) == 0)) {
                continue;
            }
            int delta = Math.abs(ms - n.timeMs());
            if (delta > best || delta > timingWindow(n)) {
                continue;
            }
            if (matches(n, bit)) {
                candidate = i;
                best = delta;
            }
        }
        if (candidate >= 0) {
            var n = chart.notes().get(candidate);
            int grade = n.release() ? tailGrade(ms - n.timeMs()) : grade(ms - n.timeMs());
            if (bit != SINGLE_DOUBLE
                    && bit != KEYBOARD_LINK
                    && !keyboardFaceBit(bit)
                    && (n.kind() == RhythmChart.Kind.DOUBLE
                            || n.kind() == RhythmChart.Kind.DOUBLE_STAR)) {
                int mask = mask(n);
                if ((held & mask) != mask) {
                    return InputResult.WAITING_DOUBLE;
                }
                long a = downAt[Integer.numberOfTrailingZeros(mask & -mask)],
                        c = downAt[Integer.numberOfTrailingZeros(mask ^ (mask & -mask))];
                if (Math.abs(a - c) > DOUBLE_MS) {
                    return InputResult.WAITING_DOUBLE;
                }
            }
            if (n.release()) {
                int head = n.pair();
                if (statuses[head] != 1 || holdMask[head] == 0 || (holdMask[head] & bit) == 0) {
                    judge(candidate, Grade.WORST, ms, ms - n.timeMs(), false);
                    return InputResult.JUDGED;
                }
                holdMask[head] = 0;
            }
            if (n.kind() == RhythmChart.Kind.LINK && n.chain() >= 0) {
                keyboardChain[n.chain()] = bit == KEYBOARD_LINK;
            }
            judge(candidate, Grade.values()[grade], ms, ms - n.timeMs(), false);
            if (n.kind() == RhythmChart.Kind.HOLD && !n.release() && grade <= 1) {
                holdMask[candidate] = bit;
                holdGrade[candidate] = grade;
                holdStarted[candidate] = ms;
            }
            if (n.kind() == RhythmChart.Kind.RUSH && grade <= 2 && ms < n.endMs()) {
                activeRushes.add(candidate);
                lastRush = candidate;
                rushStarts[candidate] = ms;
                batchRushes.add(candidate);
                rushEvent(candidate, RushPhase.START, ms);
            }
            return InputResult.JUDGED;
        }
        if (pressed && rushEdge(bit, ms)) {
            return InputResult.RUSH_PRESS;
        }
        if (!pressed) {
            boolean releasedHold = false;
            for (int i = 0; i < holdMask.length; i++) {
                if ((holdMask[i] & bit) != 0) {
                    holdMask[i] = 0;
                    int release = chart.notes().get(i).pair();
                    if (statuses[release] == 0) {
                        judge(
                                release,
                                Grade.WORST,
                                ms,
                                ms - chart.notes().get(release).timeMs(),
                                false);
                        releasedHold = true;
                    }
                }
            }
            return releasedHold ? InputResult.JUDGED : InputResult.RELEASED;
        }
        if (bit > 128 && !keyboardFaceBit(bit)) {
            return InputResult.FREE_PRESS;
        }
        // 中文：错误的面键或方向键仍会消耗最近的普通目标。
        // EN: Wrong face/direction keys still consume the nearest ordinary target.
        best = SAD_MS + 1;
        for (int i = expireCursor; i < statuses.length; i++) {
            var n = chart.notes().get(i);
            if (n.timeMs() > ms + SAD_MS) {
                break;
            }
            if (statuses[i] != 0
                    || n.release()
                    || n.kind() == RhythmChart.Kind.STAR
                    || n.kind() == RhythmChart.Kind.LINK
                    || n.kind() == RhythmChart.Kind.CHANCE_STAR
                    || n.kind() == RhythmChart.Kind.DOUBLE_STAR) {
                continue;
            }
            int d = Math.abs(ms - n.timeMs());
            if (d < best) {
                best = d;
                candidate = i;
            }
        }
        if (candidate >= 0) {
            judge(
                    candidate,
                    Grade.values()[grade(ms - chart.notes().get(candidate).timeMs())],
                    ms,
                    ms - chart.notes().get(candidate).timeMs(),
                    true);
            return InputResult.JUDGED;
        }
        return InputResult.FREE_PRESS;
    }

    private boolean rushEdge(int bit, int ms) {
        boolean matched = false;
        for (var iterator = activeRushes.iterator(); iterator.hasNext(); ) {
            int index = iterator.next();
            if (!rushing(index, ms) || !matches(chart.notes().get(index), bit)) {
                continue;
            }
            matched = true;
            if (batchRushes.add(index)) {
                rushCounts[index]++;
                addBonus(30);
                rushEvent(index, RushPhase.PRESS, ms);
                if (rushCounts[index] >= Math.max(1, rushThreshold(index))) {
                    rushEvent(index, RushPhase.END, ms);
                    iterator.remove();
                }
            }
        }
        return matched;
    }

    private static boolean matches(RhythmChart.Note n, int bit) {
        if (bit == SINGLE_DOUBLE) {
            return n.kind() == RhythmChart.Kind.DOUBLE || n.kind() == RhythmChart.Kind.DOUBLE_STAR;
        }
        if (bit == KEYBOARD_LINK) {
            return n.kind() == RhythmChart.Kind.LINK
                    || n.kind() == RhythmChart.Kind.STAR
                    || n.kind() == RhythmChart.Kind.CHANCE_STAR
                    || n.kind() == RhythmChart.Kind.DOUBLE_STAR;
        }
        if (keyboardFaceBit(bit)) {
            return (n.kind() == RhythmChart.Kind.TAP
                            || n.kind() == RhythmChart.Kind.HOLD
                            || n.kind() == RhythmChart.Kind.DOUBLE
                            || n.kind() == RhythmChart.Kind.RUSH)
                    && bit == keyboardFace(n.button());
        }
        return switch (n.kind()) {
            case STAR, LINK, CHANCE_STAR, DOUBLE_STAR -> bit == 256 || bit == 512;
            default ->
                    bit == RhythmChart.face(n.button()) || bit == RhythmChart.direction(n.button());
        };
    }

    private static int mask(RhythmChart.Note n) {
        return n.kind() == RhythmChart.Kind.DOUBLE_STAR
                ? 768
                : RhythmChart.face(n.button()) | RhythmChart.direction(n.button());
    }

    public static int grade(int delta) {
        int d = Math.abs(delta);
        return d <= COOL_MS ? 0 : d <= FINE_MS ? 1 : d <= SAFE_MS ? 2 : d <= SAD_MS ? 3 : 4;
    }

    public void advance(int ms) {
        if (finished) {
            return;
        }
        int now = Math.max(lastTime, ms);
        var rushIterator = activeRushes.iterator();
        while (rushIterator.hasNext()) {
            int i = rushIterator.next();
            if (now >= chart.notes().get(i).endMs()) {
                rushEvent(i, RushPhase.END, chart.notes().get(i).endMs());
                rushIterator.remove();
            }
        }
        if (!failed) {
            // 中文：按时间顺序处理到期目标，包括保持连接之间的过期。
            // EN: Process due targets in chronological order, including expiry between held links.
            for (int i = expireCursor; i < statuses.length; i++) {
                var n = chart.notes().get(i);
                if (n.timeMs() > now) {
                    break;
                }
                if (statuses[i] != 0) {
                    continue;
                }
                if (n.kind() == RhythmChart.Kind.LINK && (held & KEYBOARD_LINK) != 0) {
                    if (n.chain() >= 0) {
                        keyboardChain[n.chain()] = true;
                    }
                    judge(i, Grade.COOL, n.timeMs(), 0, false);
                } else if (n.timeMs() + timingWindow(n) < now) {
                    judge(
                            i,
                            Grade.WORST,
                            n.timeMs() + timingWindow(n) + 1,
                            timingWindow(n) + 1,
                            false);
                }
                if (failed) {
                    break;
                }
            }
            while (!failed
                    && expireCursor < statuses.length
                    && chart.notes().get(expireCursor).timeMs()
                                    + timingWindow(chart.notes().get(expireCursor))
                            < now) {
                if (statuses[expireCursor] == 0) {
                    judge(
                            expireCursor,
                            Grade.WORST,
                            chart.notes().get(expireCursor).timeMs()
                                    + timingWindow(chart.notes().get(expireCursor))
                                    + 1,
                            timingWindow(chart.notes().get(expireCursor)) + 1,
                            false);
                }
                expireCursor++;
            }
            settleZones(now);
        }
        lastTime = now;
        if (now >= chart.durationMs()) {
            finished = true;
        }
    }

    private void addBonus(int n) {
        int awarded = Math.min(n, SCORE_LIMIT - score);
        bonusScore += awarded;
        score += awarded;
    }

    private void addAchievement(int n) {
        achievementUnits = Math.min(1_000_000, achievementUnits + n);
    }

    private void settleZones(int now) {
        for (int i = 0; i < zoneDone.length; i++) {
            var z = chart.zones().get(i);
            if (zoneDone[i] || now <= z.endMs()) {
                continue;
            }
            boolean pending = false, good = true;
            int targets = 0;
            for (int j = 0; j < statuses.length; j++) {
                if (inZone(z, chart.notes().get(j))) {
                    targets++;
                    if (statuses[j] == 0) {
                        pending = true;
                    }
                    if (statuses[j] != 1) {
                        good = false;
                    }
                }
            }
            if (pending) {
                continue;
            }
            zoneDone[i] = true;
            if (z.kind().equals("TECHNICAL") && targets > 0 && good) {
                technical++;
                addBonus(5000);
                addAchievement(30000);
            }
            if (z.kind().equals("CHANCE") && chanceSuccess[i]) {
                addBonus(50000);
            }
        }
    }

    private void judge(int index, Grade grade, int ms, int delta, boolean incorrect) {
        if (statuses[index] != 0) {
            return;
        }
        var n = chart.notes().get(index);
        int g = grade.ordinal();
        boolean good = g <= 1 && !incorrect;
        statuses[index] = (byte) (good ? 1 : 2);
        counts[g]++;
        judged++;
        if (incorrect) {
            wrong++;
        }
        if (!good) {
            for (int i = 0; i < zoneFailureMs.length; i++) {
                var zone = chart.zones().get(i);
                if (zoneFailureMs[i] < 0
                        && zone.kind().equalsIgnoreCase("technical")
                        && n.timeMs() >= zone.startMs()
                        && n.timeMs() <= zone.endMs()) {
                    zoneFailureMs[i] = ms;
                }
            }
        }
        int lifeBefore = life;
        int factor = chart.zone("chance", n.timeMs()) ? 2 : 1;
        int raw = Math.min(SCORE_LIMIT - score, incorrect ? 0 : BASE_POINTS[g] * factor);
        baseScore += raw;
        score += raw;
        int points = raw;
        if (!incorrect
                && g < 4
                && (n.kind() == RhythmChart.Kind.DOUBLE
                        || n.kind() == RhythmChart.Kind.DOUBLE_STAR)) {
            addBonus(200);
            points += 200;
        }
        if (good) {
            combo++;
            maxCombo = Math.max(combo, maxCombo);
            int extra = Math.min(250, (combo / 10) * 50);
            addBonus(extra);
            points += extra;
            int units = combo == statuses.length ? noteBudget - noteAchievement : noteUnits;
            noteAchievement += units;
            addAchievement(units);
        } else {
            combo = 0;
        }
        if (!chart.zone("chance", n.timeMs())) {
            int nativeGrade = incorrect ? g + 4 : g == 4 ? 8 : g;
            life = Math.max(0, Math.min(255, life + LIFE_POINTS[difficulty][nativeGrade]));
        }
        // 中文：键盘 STAR 适配保留源 22/23：计原始链，而不是 UI 类型。
        // EN: Keyboard STAR adaptation retains source 22/23: score the original chain, not the UI
        // kind.
        // 键盘把连星显示成逐颗星星，原始类型仍决定链分，避免修改曲包。
        int linkKind =
                n.sourceType() == 22
                        ? 1
                        : n.sourceType() == 23
                                ? 2
                                : n.kind() == RhythmChart.Kind.LINK ? (n.linkEnd() ? 2 : 1) : 0;
        if (good && linkKind != 0) {
            linkBonus += g == 0 ? 200 : 100;
            addBonus(linkBonus);
            points += linkBonus;
        } else {
            linkBonus = 0;
        }
        if (linkKind == 2) {
            linkBonus = 0;
        }
        if (good) {
            for (int z = 0; z < zoneDone.length; z++) {
                if (chart.zones().get(z).kind().equals("CHANCE")
                        && inZone(chart.zones().get(z), n)) {
                    if (n.kind() != RhythmChart.Kind.CHANCE_STAR) {
                        chanceGood[z]++;
                    } else if (!chanceSuccess[z] && chanceGauge(z) == 15) {
                        chanceSuccess[z] = true;
                        chance++;
                        addAchievement(50000);
                    }
                }
            }
        }
        // 中文：原生 HOLD 刻度在成功释放前是临时的；断开的 HOLD 会失去它们。
        // EN: Native hold ticks are provisional until a successful release; broken holds lose them.
        // 原程序每 100 ms 累积 20/10 分；成功松键才结算，断按取消。
        if (n.kind() == RhythmChart.Kind.HOLD && n.release()) {
            int head = n.pair();
            if (good && statuses[head] == 1) {
                int elapsed = Math.max(0, Math.min(ms, n.timeMs()) - holdStarted[head]);
                int extra = Math.max(0, (elapsed - 1) / 100) * (holdGrade[head] == 0 ? 20 : 10);
                addBonus(extra);
                points += extra;
            }
            holdMask[head] = 0;
        }
        hits.addLast(
                new Hit(
                        ++serial,
                        index,
                        grade,
                        ms,
                        delta,
                        incorrect,
                        points,
                        combo,
                        life - lifeBefore));
        while (hits.size() > 128) {
            hits.removeFirst();
        }
        if (life == 0 && !practice) {
            failed = true;
            finished = true;
        }
    }

    /**
     * 仅作为完美作者音符参考；无上限的 RUSH 额外项没有固定最大值。
     * <p>Perfect authored-note reference only; uncapped RUSH extras have no fixed maximum.</p>
     */
    public static int maximum(RhythmChart chart) {
        RhythmJudgeSession s = new RhythmJudgeSession(chart, true);
        for (var n : chart.notes()) {
            int bit =
                    switch (n.kind()) {
                        case STAR, LINK, CHANCE_STAR, DOUBLE_STAR -> 256;
                        default -> RhythmChart.face(n.button());
                    };
            if (n.release()) {
                int index = chart.notes().indexOf(n);
                s.input(s.holdMask[n.pair()] == 0 ? bit : s.holdMask[n.pair()], false, n.timeMs());
            } else {
                if ((s.held & bit) != 0) {
                    s.input(bit, false, n.timeMs() - 1);
                }
                if (n.kind() == RhythmChart.Kind.DOUBLE
                        || n.kind() == RhythmChart.Kind.DOUBLE_STAR) {
                    int other =
                            n.kind() == RhythmChart.Kind.DOUBLE_STAR
                                    ? 512
                                    : RhythmChart.direction(n.button());
                    if ((s.held & other) != 0) {
                        s.input(other, false, n.timeMs() - 1);
                    }
                    s.input(other, true, n.timeMs());
                }
                s.input(bit, true, n.timeMs());
            }
        }
        s.advance(chart.durationMs());
        return s.score;
    }
}
