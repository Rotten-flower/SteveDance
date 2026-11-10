package dev.flower.stevedance.rewards;

import dev.flower.stevedance.core.RhythmJudgeSession;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 服务端挑战条件；不依赖客户端或加载器。 / Loader-independent challenge predicates. */
public record ChallengeCondition(
        Trigger trigger,
        List<String> songs,
        String minDifficulty,
        boolean cleared,
        String minRank,
        int minScore,
        double minAchievement,
        int minCombo,
        int progressPercent) {
    public enum Trigger {
        PROGRESS,
        RESULT
    }

    public enum Repeat {
        ONCE,
        DAILY,
        EVERY_RUN
    }

    public static final List<String> DIFFICULTIES = List.of("easy", "normal", "hard", "extreme");
    public static final List<String> RANKS =
            List.of("any", "cheap", "standard", "great", "excellent", "perfect");

    public ChallengeCondition {
        Objects.requireNonNull(trigger, "trigger");
        songs = List.copyOf(songs);
        if (songs.isEmpty()
                || songs.size() > 128
                || new HashSet<>(songs).size() != songs.size()
                || songs.contains("*") && songs.size() != 1) {
            throw new IllegalArgumentException("songs must be unique, or [*]");
        }
        for (String song : songs) {
            if (!song.equals("*") && !song.matches("[a-z][a-z0-9_]{0,47}")) {
                throw new IllegalArgumentException("Invalid song: " + song);
            }
        }
        if (!DIFFICULTIES.contains(minDifficulty) || !RANKS.contains(minRank)) {
            throw new IllegalArgumentException("Unknown difficulty or rank");
        }
        if (minScore < 0
                || minScore > 9_999_999
                || !Double.isFinite(minAchievement)
                || minAchievement < 0
                || minAchievement > 100
                || minCombo < 0
                || minCombo > 100000) {
            throw new IllegalArgumentException("Condition outside score/achievement/combo range");
        }
        if (trigger == Trigger.PROGRESS
                && (progressPercent < 1
                        || progressPercent > 100
                        || cleared
                        || !minRank.equals("any"))) {
            throw new IllegalArgumentException(
                    "PROGRESS needs 1..100 percent; cleared/rank require RESULT");
        }
        if (trigger == Trigger.RESULT && progressPercent != 0) {
            throw new IllegalArgumentException("RESULT progressPercent must be 0");
        }
    }

    public boolean matches(
            Trigger event,
            String song,
            String difficulty,
            int percent,
            int clearPercent,
            RhythmJudgeSession.Result result) {
        if (event != trigger
                || result.failed()
                || !songs.contains("*") && !songs.contains(song)
                || DIFFICULTIES.indexOf(difficulty) < DIFFICULTIES.indexOf(minDifficulty)) {
            return false;
        }
        if (trigger == Trigger.RESULT && !result.finished()
                || trigger == Trigger.PROGRESS && percent < progressPercent) {
            return false;
        }
        return (!cleared || result.cleared(clearPercent))
                && (minRank.equals("any")
                        || RANKS.indexOf(result.rank(clearPercent)) >= RANKS.indexOf(minRank))
                && result.score() >= minScore
                && result.achievement() >= minAchievement
                && result.maxCombo() >= minCombo;
    }
}
