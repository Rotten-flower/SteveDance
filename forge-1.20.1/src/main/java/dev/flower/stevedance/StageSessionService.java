package dev.flower.stevedance;

import dev.flower.stevedance.core.RhythmSession;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StageSessionService {
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    private static final Map<UUID, Long> STARTS = new HashMap<>();

    public static boolean busy(UUID id) {
        return ACTIVE.containsKey(id);
    }

    public static boolean stageBusy(WorldStage.Anchor stage) {
        return ACTIVE.values().stream()
                .anyMatch(
                        a ->
                                a.dimension == stage.getLevel().dimension()
                                        && a.stage.equals(stage.getBlockPos()));
    }

    private static final class Active {
        final String song;
        final RhythmSession judge;
        final long startNanos;
        final Vec3 anchor;
        final ResourceKey<Level> dimension;
        final net.minecraft.core.BlockPos stage;
        final float yaw;
        int sequence, lastTime = -2000, events, clockMs = -2000, clocks;
        long rateWindow, lastClockNanos;
        boolean playback;

        Active(ServerPlayer p, MotionPackLibrary.Pack pack, WorldStage.Anchor stage) {
            song = pack.manifest().id();
            judge = new RhythmSession(pack.chart());
            startNanos = System.nanoTime() + 2_000_000_000L;
            anchor = stage.origin();
            dimension = p.level().dimension();
            this.stage = stage.getBlockPos().immutable();
            yaw = stage.yaw();
            rateWindow = System.nanoTime();
        }
    }

    public static void request(ServerPlayer p, StageNetwork.Request req) {
        if (req.stop()) {
            stop(p.getUUID(), 3, "ui.stevedance.cancelled");
            return;
        }
        var pack = MotionPackLibrary.get(req.song());
        long tick = p.serverLevel().getGameTime();
        WorldStage.Anchor stage = null;
        if ((PvSessionService.busy(p.getUUID()) || RhythmSessionService.busy(p.getUUID()))
                || ACTIVE.containsKey(p.getUUID())
                || !p.isAlive()
                || p.isSpectator()
                || tick - STARTS.getOrDefault(p.getUUID(), -1000L) < 40
                || pack == null
                || !pack.playable()
                || !pack.digest().equals(req.digest())
                || ACTIVE.size() >= 32) {
            SteveDanceMod.LOGGER.warn(
                    "Song start refused: song={}, active={}, alive={}, spectator={}, cooldownTicks={}, packPresent={}, playable={}, digestMatches={}, performances={}",
                    req.song(),
                    ACTIVE.containsKey(p.getUUID()),
                    p.isAlive(),
                    p.isSpectator(),
                    tick - STARTS.getOrDefault(p.getUUID(), -1000L),
                    pack != null,
                    pack != null && pack.playable(),
                    pack != null && pack.digest().equals(req.digest()),
                    ACTIVE.size());
            StageNetwork.send(
                    p,
                    new StageNetwork.State(
                            4, p.getUUID(), req.song(), 0, "ui.stevedance.start_refused", null));
            return;
        }
        stage = PerformanceStageRegistry.acquire(p, req.song());
        if (stage == null) {
            StageNetwork.send(
                    p,
                    new StageNetwork.State(
                            4, p.getUUID(), req.song(), 0, "ui.stevedance.stage_required", null));
            return;
        }
        STARTS.put(p.getUUID(), tick);
        Active active = new Active(p, pack, stage);
        ACTIVE.put(p.getUUID(), active);
        StageNetwork.performance(p, state(p, active, 1, tick + 40, -2000));
    }

    private static StageNetwork.State state(
            ServerPlayer p, Active a, int kind, long tick, int progress) {
        return new StageNetwork.State(
                kind,
                p.getUUID(),
                a.song,
                tick,
                "",
                null,
                a.stage,
                a.dimension.location().toString(),
                a.yaw,
                progress);
    }

    public static void input(ServerPlayer p, StageNetwork.Input input) {
        Active a = ACTIVE.get(p.getUUID());
        if (a == null) {
            return;
        }
        long now = System.nanoTime();
        int elapsed = (int) ((now - a.startNanos) / 1_000_000);
        if (now - a.rateWindow > 1_000_000_000L) {
            a.rateWindow = now;
            a.events = 0;
            a.clocks = 0;
        }
        if (++a.events > 100
                || input.sequence() != a.sequence + 1
                || input.timeMs() < a.lastTime - 2
                || input.timeMs() > elapsed + 650
                || !a.playback
                || input.timeMs() < a.clockMs - 700
                || input.timeMs() > a.clockMs + (now - a.lastClockNanos) / 1_000_000 + 750
                || input.timeMs() < -1000
                || input.timeMs() > a.judge.chart().durationMs() + 2000
                || input.bit() <= 0
                || (input.bit() & (input.bit() - 1)) != 0
                || input.bit() > 512) {
            SteveDanceMod.LOGGER.warn(
                    "Rhythm input rejected: song={}, seq={}/{}, time={}, previous={}, serverElapsed={}, rate={}, bit={}",
                    a.song,
                    input.sequence(),
                    a.sequence,
                    input.timeMs(),
                    a.lastTime,
                    elapsed,
                    a.events,
                    input.bit());
            stop(p.getUUID(), 3, "ui.stevedance.input_rejected");
            return;
        }
        a.sequence = input.sequence();
        a.lastTime = input.timeMs();
        a.judge.input(input.bit(), input.pressed(), input.timeMs());
    }

    public static void clock(ServerPlayer p, StageNetwork.Clock clock) {
        Active a = ACTIVE.get(p.getUUID());
        if (a == null) {
            return;
        }
        long now = System.nanoTime();
        int ms = clock.timeMs();
        long delta = a.playback ? (now - a.lastClockNanos) / 1_000_000 : 0;
        int wall = (int) ((now - a.startNanos) / 1_000_000);
        if (now - a.rateWindow > 1_000_000_000L) {
            a.rateWindow = now;
            a.events = 0;
            a.clocks = 0;
        }
        if (++a.clocks > 20
                || ms < -500
                || ms > a.judge.chart().durationMs() + 2000
                || ms > wall + 750
                || ms < wall - 15000
                || a.playback && (ms < a.clockMs - 2 || ms > a.clockMs + delta + 750)) {
            SteveDanceMod.LOGGER.warn(
                    "Rhythm clock rejected: song={}, time={}, previous={}, wall={}",
                    a.song,
                    ms,
                    a.clockMs,
                    wall);
            stop(p.getUUID(), 3, "ui.stevedance.input_rejected");
            return;
        }
        a.playback = true;
        a.clockMs = ms;
        a.lastClockNanos = now;
        StageNetwork.performance(p, state(p, a, 5, 0, Math.max(0, ms)));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ACTIVE.isEmpty()) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (var id : List.copyOf(ACTIVE.keySet())) {
            Active a = ACTIVE.get(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null
                    || !p.isAlive()
                    || p.level().dimension() != a.dimension
                    || p.position().distanceToSqr(a.anchor) > 64 * 64
                    || PerformanceStageRegistry.get(p, a.stage) == null) {
                stop(id, 3, "ui.stevedance.left_stage");
                continue;
            }
            long now = System.nanoTime();
            int wall = (int) ((now - a.startNanos) / 1_000_000);
            if (wall > a.judge.chart().durationMs() + 20000
                    || a.playback && now - a.lastClockNanos > 10_000_000_000L
                    || !a.playback && wall > 10000) {
                stop(id, 3, "ui.stevedance.input_rejected");
                continue;
            }
            // 中文：有界缓冲区防止先前的输入数据包变成过期的未命中。
            // EN: Audio-device progress is checked against wall time, but notes follow that audio
            // 中文：服务端在当前关卡中拥有一个真实、持久的舞台锚点。
            // EN: clock.
            // 中文：有界本地查找，仅在玩家请求操作时执行。
            // EN: A bounded buffer keeps a preceding input packet from becoming an overdue miss.
            if (a.playback) {
                a.judge.advance(a.clockMs - 700);
            }
            if (a.playback && a.clockMs > a.judge.chart().durationMs() + 500) {
                a.judge.advance(a.clockMs);
                var result = a.judge.result();
                var tag = p.getPersistentData();
                var scores = tag.getCompound("stevedance_scores");
                if (scores.size() < 64 || scores.contains(a.song)) {
                    scores.putInt(a.song, Math.max(scores.getInt(a.song), result.score()));
                    tag.put("stevedance_scores", scores);
                    tag.putInt("stevedance_schema", 1);
                }
                ACTIVE.remove(id);
                PerformanceStageRegistry.release(id);
                StageNetwork.performance(p, new StageNetwork.State(2, id, a.song, 0, "", result));
            }
        }
    }

    private static void stop(UUID id, int kind, String reason) {
        Active a = ACTIVE.remove(id);
        PerformanceStageRegistry.release(id);
        if (a != null) {
            StageNetwork.broadcast(new StageNetwork.State(kind, id, a.song, 0, reason, null));
        }
    }

    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking e) {
        if (e.getEntity() instanceof ServerPlayer viewer
                && e.getTarget() instanceof ServerPlayer performer) {
            Active a = ACTIVE.get(performer.getUUID());
            if (a != null) {
                StageNetwork.send(
                        viewer,
                        state(
                                performer,
                                a,
                                1,
                                performer.serverLevel().getGameTime(),
                                a.playback
                                        ? a.clockMs
                                        : (int) ((System.nanoTime() - a.startNanos) / 1_000_000)));
            }
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        stop(e.getEntity().getUUID(), 3, "ui.stevedance.cancelled");
        STARTS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void clone(PlayerEvent.Clone e) {
        var source = e.getOriginal().getPersistentData();
        var target = e.getEntity().getPersistentData();
        if (source.contains("stevedance_scores")) {
            target.put("stevedance_scores", source.getCompound("stevedance_scores").copy());
        }
        if (source.contains("stevedance_schema")) {
            target.putInt("stevedance_schema", source.getInt("stevedance_schema"));
        }
    }

    @SubscribeEvent
    public static void stopped(net.minecraftforge.event.server.ServerStoppedEvent e) {
        ACTIVE.clear();
        STARTS.clear();
    }
}
