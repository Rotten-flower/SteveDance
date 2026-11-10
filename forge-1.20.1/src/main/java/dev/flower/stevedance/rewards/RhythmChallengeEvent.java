package dev.flower.stevedance.rewards;

import dev.flower.stevedance.core.RhythmJudgeSession;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/** Forge EVENT_BUS，服务端线程；只来自真实音游场次，不来自 PV 或客户端成绩。 */
public abstract class RhythmChallengeEvent extends Event {
    private final ServerPlayer player;
    private final UUID runId;
    private final String song, difficulty;
    private final int clearPercent, timeMs, durationMs;
    private final RhythmJudgeSession.Result result;

    protected RhythmChallengeEvent(
            ServerPlayer player,
            UUID runId,
            String song,
            String difficulty,
            int clearPercent,
            int timeMs,
            int durationMs,
            RhythmJudgeSession.Result result) {
        this.player = player;
        this.runId = runId;
        this.song = song;
        this.difficulty = difficulty;
        this.clearPercent = clearPercent;
        this.timeMs = timeMs;
        this.durationMs = durationMs;
        this.result = result;
    }

    public ServerPlayer player() {
        return player;
    }

    public UUID runId() {
        return runId;
    }

    public String song() {
        return song;
    }

    public String difficulty() {
        return difficulty;
    }

    public int clearPercent() {
        return clearPercent;
    }

    public int timeMs() {
        return timeMs;
    }

    public int durationMs() {
        return durationMs;
    }

    public RhythmJudgeSession.Result result() {
        return result;
    }

    public abstract ChallengeCondition.Trigger trigger();

    /** 每跨过一个整百分比发一次；暂停不前进，重试是新场次。 / Once per crossed integer percentage. */
    public static final class Progress extends RhythmChallengeEvent {
        private final int percent;

        public Progress(
                ServerPlayer player,
                UUID run,
                String song,
                String difficulty,
                int clear,
                int time,
                int duration,
                RhythmJudgeSession.Result result,
                int percent) {
            super(player, run, song, difficulty, clear, time, duration, result);
            this.percent = percent;
        }

        public int percent() {
            return percent;
        }

        @Override
        public ChallengeCondition.Trigger trigger() {
            return ChallengeCondition.Trigger.PROGRESS;
        }
    }

    /**
     * 每个正常结束或失败的场次恰好一次；中途退出不发结算。
     * <p>Emitted once for a completed or failed session; leaving early does not emit a settlement.</p>
     */
    public static final class Completed extends RhythmChallengeEvent {
        public Completed(
                ServerPlayer player,
                UUID run,
                String song,
                String difficulty,
                int clear,
                int time,
                int duration,
                RhythmJudgeSession.Result result) {
            super(player, run, song, difficulty, clear, time, duration, result);
        }

        @Override
        public ChallengeCondition.Trigger trigger() {
            return ChallengeCondition.Trigger.RESULT;
        }
    }
}
