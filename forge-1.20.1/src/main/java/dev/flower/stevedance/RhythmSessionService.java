package dev.flower.stevedance;

import dev.flower.stevedance.core.RhythmJudgeSession;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RhythmSessionService {
    private static final class Run {
        final UUID runId = UUID.randomUUID();
        int lastProgress;
        final RhythmChartLibrary.Entry entry;
        final BlockPos stage;
        final String dimension;
        final float yaw;
        final RhythmJudgeSession engine;
        long readyDeadline,
                startNanos,
                startTick,
                pauseNanos,
                pausedTotal,
                lastAction,
                lastBudgetTick,
                lastSync,
                resumeDeadline;
        int sequence, budget, clock, sentSerial, observed, resumeClock, offset;
        boolean ready, paused, playing, resumePending, worldStage, chartOnly;

        Run(RhythmChartLibrary.Entry e, WorldStage.Anchor a, long tick) {
            entry = e;
            stage = a.getBlockPos().immutable();
            dimension = a.getLevel().dimension().location().toString();
            yaw = a.yaw();
            engine = new RhythmJudgeSession(e.chart());
            readyDeadline = tick + 1200;
        }

        int time() {
            return ready
                    ? (int)
                            (((paused ? pauseNanos : System.nanoTime()) - startNanos - pausedTotal)
                                    / 1000000)
                    : -2000;
        }
    }

    public record Completion(RhythmJudgeSession.Result result, boolean highScore, int bestScore) {}

    public record Snapshot(
            RhythmJudgeSession.Result result,
            int combo,
            int sequence,
            List<RhythmJudgeSession.Hit> hits) {}

    public record RecordLine(
            String difficulty, PlayerScoreStore.Row best, PlayerScoreStore.Stats stats) {}

    public record Records(String song, List<RecordLine> lines) {}

    public record Personal(String song, String difficulty, PlayerScoreStore.Row row) {}

    private static java.util.function.BiConsumer<ServerPlayer, RhythmNetwork.Command> pvLauncher =
            (p, command) -> {
                if (PvSessionService.prepareSong(p, command.song(), 0, command.timeMs() == 1)
                        == 0) {
                    fail(p, "ui.stevedance.start_refused");
                }
            };

    public static void registerPvLauncher(
            java.util.function.BiConsumer<ServerPlayer, RhythmNetwork.Command> launcher) {
        pvLauncher = Objects.requireNonNull(launcher);
    }

    private static final Map<UUID, Long> OPENS = new HashMap<>();
    private static final Map<UUID, Long> QUERIES = new HashMap<>();
    private static final Map<UUID, Run> ACTIVE = new HashMap<>();
    private static final Map<UUID, Long> REQUESTS = new HashMap<>();

    public static boolean performing(UUID id) {
        var run = ACTIVE.get(id);
        return run != null && run.playing;
    }

    public static boolean busy(UUID id) {
        return ACTIVE.containsKey(id);
    }

    public static Set<UUID> worldHosts() {
        var hosts = new HashSet<UUID>();
        for (var e : ACTIVE.entrySet()) {
            if (e.getValue().worldStage && e.getValue().playing) {
                hosts.add(e.getKey());
            }
        }
        return hosts;
    }

    public static StageNetwork.State worldPerformance(UUID host, int kind, String message) {
        var r = ACTIVE.get(host);
        var server = ServerLifecycleHooks.getCurrentServer();
        var owner = server == null ? null : server.getPlayerList().getPlayer(host);
        if (r == null || !r.worldStage || !r.playing || owner == null) {
            return null;
        }
        return new StageNetwork.State(
                kind,
                host,
                r.entry.chart().id(),
                owner.serverLevel().getGameTime(),
                message + (r.paused ? ":paused" : ""),
                null,
                r.stage,
                r.dimension,
                r.yaw,
                Math.max(0, r.time()));
    }

    public static boolean stageBusy(WorldStage.Anchor anchor) {
        return ACTIVE.values().stream()
                .anyMatch(
                        r ->
                                r.stage.equals(anchor.getBlockPos())
                                        && r.dimension.equals(
                                                anchor.getLevel()
                                                        .dimension()
                                                        .location()
                                                        .toString()));
    }

    private static boolean valid(ServerPlayer p, Run r) {
        var a = PerformanceStageRegistry.get(p, r.stage);
        return p.isAlive()
                && !p.isSpectator()
                && p.level().dimension().location().toString().equals(r.dimension)
                && a != null
                && a.song().equals(r.entry.chart().id())
                && a.availableTo(p)
                && p.position().distanceToSqr(a.origin()) <= (64 * 64);
    }

    private static void state(ServerPlayer p, Run r, int type, String json) {
        RhythmNetwork.send(
                p,
                new RhythmNetwork.State(
                        type,
                        r.entry.chart().id(),
                        r.entry.chart().difficulty(),
                        r.startTick,
                        r.stage,
                        r.dimension,
                        r.yaw,
                        r.time(),
                        json,
                        r.chartOnly ? "chart" : r.worldStage ? "world" : "isolated"));
    }

    private static void fail(ServerPlayer p, String key) {
        RhythmNetwork.simple(p, 5, "", key);
    }

    public static void open(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || busy(p.getUUID())) {
            return;
        }
        long tick = p.serverLevel().getGameTime();
        if (tick - OPENS.getOrDefault(p.getUUID(), -100L) < 10) {
            return;
        }
        OPENS.put(p.getUUID(), tick);
        RhythmChartLibrary.reload();
        SongSelectionCatalog.reload();
        RhythmNetwork.simple(
                p, 0, RhythmChartLibrary.JSON.toJson(SongSelectionCatalog.songs()), "");
    }

    public static void command(ServerPlayer p, RhythmNetwork.Command c) {
        long tick = p.serverLevel().getGameTime();
        Run r = ACTIVE.get(p.getUUID());
        if (c.action() == 0) {
            open(p);
            return;
        }
        if (c.action() == 7) {
            stop(p, true);
            return;
        }
        if (c.action() == 12) {
            long last = REQUESTS.getOrDefault(p.getUUID(), -100L);
            if (tick - last < 10) {
                return;
            }
            REQUESTS.put(p.getUUID(), tick);
            SongSelectionCatalog.reload();
            var song = SongSelectionCatalog.get(c.song());
            if (song == null
                    || !song.pvAvailable()
                    || r != null
                    || !p.isAlive()
                    || p.isSpectator()) {
                fail(p, "ui.stevedance.f2_song_unavailable");
                return;
            }
            pvLauncher.accept(p, c);
            return;
        }
        if (c.action() == 9) {
            long previous = QUERIES.getOrDefault(p.getUUID(), -100L);
            if (tick - previous < 4) {
                return;
            }
            QUERIES.put(p.getUUID(), tick);
            var entry = RhythmChartLibrary.get(c.song(), c.difficulty());
            if (entry == null || !entry.digest().equals(c.digest())) {
                return;
            }
            RhythmNetwork.simple(
                    p,
                    10,
                    RhythmChartLibrary.JSON.toJson(
                            new Personal(
                                    c.song(),
                                    c.difficulty(),
                                    PlayerScoreStore.get(p.server)
                                            .personal(
                                                    PlayerScoreStore.key(
                                                            c.song(),
                                                            c.difficulty(),
                                                            entry.digest()),
                                                    p.getUUID()))),
                    "");
            return;
        }
        if (c.action() == 8) {
            long last = REQUESTS.getOrDefault(p.getUUID(), -100L);
            if (tick - last < 10) {
                return;
            }
            REQUESTS.put(p.getUUID(), tick);
            if (!SongCatalog.ALL.containsKey(c.song())) {
                return;
            }
            var saved = PlayerScoreStore.get(p.server);
            List<RecordLine> lines = new ArrayList<>();
            for (String d : List.of("easy", "normal", "hard", "extreme")) {
                var e = RhythmChartLibrary.get(c.song(), d);
                String k = e == null ? null : PlayerScoreStore.key(c.song(), d, e.digest());
                lines.add(
                        new RecordLine(
                                d,
                                k == null ? null : saved.personal(k, p.getUUID()),
                                k == null ? null : saved.stats(k, p.getUUID())));
            }
            RhythmNetwork.simple(
                    p,
                    8,
                    c.song(),
                    RhythmChartLibrary.JSON.toJson(new Records(c.song(), lines)),
                    "");
            return;
        }
        if (c.action() == 1) {
            PvSessionService.leaveAmbient(p);
            long last = REQUESTS.getOrDefault(p.getUUID(), -100L);
            if (tick - last < 10) {
                return;
            }
            REQUESTS.put(p.getUUID(), tick);
            var entry = RhythmChartLibrary.get(c.song(), c.difficulty());
            if (entry == null || !entry.digest().equals(c.digest())) {
                fail(p, "ui.stevedance.f2_chart_mismatch");
                return;
            }
            if (r != null
                    || ACTIVE.size() >= 32
                    || !p.isAlive()
                    || p.isSpectator()
                    || StageSessionService.busy(p.getUUID())
                    || PvSessionService.busy(p.getUUID())) {
                fail(p, "ui.stevedance.start_refused");
                return;
            }
            dev.flower.stevedance.WorldPreview.end(p);
            if (Math.abs((long) c.timeMs()) > 300) {
                fail(p, "ui.stevedance.start_refused");
                return;
            }
            if (c.bit() < 0 || c.bit() > 1 || c.bit() == 1 && c.pressed()) {
                fail(p, "ui.stevedance.start_refused");
                return;
            }
            if (!SongCatalog.ALL.containsKey(c.song())
                    || !BundledSongResources.exists(
                            BundledSongResources.nativeSong(c.song()).resolve("music.ogg"))
                    || (c.bit() == 1
                            ? !BundledSongResources.exists(BundledSongResources.thumbnail(c.song()))
                            : !BundledSongResources.exists(
                                    BundledSongResources.nativeSong(c.song())
                                            .resolve("preview.json")))) {
                fail(p, "ui.stevedance.f2_song_unavailable");
                return;
            }
            if (c.pressed() && dev.flower.stevedance.WorldStagePlacement.selected(p) == null) {
                fail(p, "ui.stevedance.stage_not_set");
                return;
            }
            var a =
                    c.pressed()
                            ? PerformanceStageRegistry.acquireWorldStage(p, c.song())
                            : PerformanceStageRegistry.acquirePrivate(p, c.song());
            if (a == null) {
                fail(p, "ui.stevedance.stage_space");
                return;
            }
            r = new Run(entry, a, tick);
            r.offset = c.timeMs();
            r.worldStage = c.pressed();
            r.chartOnly = c.bit() == 1;
            ACTIVE.put(p.getUUID(), r);
            state(p, r, 1, entry.digest());
            return;
        }
        if (r == null) {
            return;
        }
        if (!valid(p, r)) {
            stop(p, true);
            return;
        }
        if (c.action() == 2) {
            if (r.ready || tick > r.readyDeadline) {
                return;
            }
            r.ready = true;
            r.startTick = tick + 40;
            r.startNanos = System.nanoTime() + 2000000000L;
            state(p, r, 2, "");
            return;
        }
        if (!r.ready) {
            return;
        }
        if (c.action() == 10 && !r.playing) {
            if (r.time() < -500 || r.time() > 5000 || c.timeMs() < 20 || c.timeMs() > 2000) {
                return;
            }
            r.playing = true;
            PlayerScoreStore.get(p.server)
                    .started(
                            PlayerScoreStore.key(
                                    r.entry.chart().id(),
                                    r.entry.chart().difficulty(),
                                    r.entry.digest()),
                            p.getUUID());
            r.startNanos = System.nanoTime() - c.timeMs() * 1000000L;
            SteveDanceMod.LOGGER.info("F 2nd music clock acknowledged: {}ms", c.timeMs());
            return;
        }
        if (!r.playing) {
            return;
        }
        if (c.action() == 5 && !r.paused) {
            if (r.time() < 0) {
                return;
            }
            r.lastAction = System.nanoTime();
            r.pauseNanos = System.nanoTime();
            r.paused = true;
            state(p, r, 6, "");
            PvSessionService.audienceChanged(p.getUUID());
            return;
        }
        if (c.action() == 6 && r.paused && !r.resumePending) {
            r.resumeClock = r.time();
            r.resumePending = true;
            r.resumeDeadline = System.nanoTime() + 5000000000L;
            state(p, r, 7, "");
            return;
        }
        // 中文：JavaSound 在重启已停止的输出线路时可能阻塞。仅在客户端音频线路恢复后再次判定，使用其有界时钟值。
        // EN: JavaSound may block while restarting a stopped output line. Judge again only
        // 中文：终端时钟数据包在同一有序通道上跟随所有输入边沿。
        // EN: after the client's audio line has resumed, using its bounded clock value.
        if (c.action() == 11 && r.resumePending) {
            if (c.timeMs() < 0 || Math.abs(c.timeMs() - r.resumeClock) > 1000) {
                return;
            }
            r.startNanos = System.nanoTime() - c.timeMs() * 1000000L;
            r.pausedTotal = 0;
            r.paused = false;
            r.resumePending = false;
            r.lastAction = System.nanoTime();
            PvSessionService.audienceChanged(p.getUUID());
            return;
        }
        if (r.paused || r.time() < 0) {
            return;
        }
        if (c.action() == 3) {
            if (r.observed++ < 12) {
                SteveDanceMod.LOGGER.debug(
                        "F2 input clock: sequence={}, bit={}, pressed={}, clientMs={}, serverMs={}, engineMs={}, previousSequence={}",
                        c.sequence(),
                        c.bit(),
                        c.pressed(),
                        c.timeMs(),
                        r.time(),
                        r.engine.timeMs(),
                        r.sequence);
            }
            if (r.lastBudgetTick != tick) {
                r.lastBudgetTick = tick;
                r.budget = 0;
            }
            if (++r.budget > 32) {
                return;
            }
            int now = r.time() + r.offset;
            if (c.sequence() <= r.sequence
                    || c.sequence() > r.sequence + 32
                    || c.timeMs() > now + 450
                    || c.timeMs() < now - 600
                    || c.timeMs() < 0
                    || c.bit() < 1
                    || c.bit() > ((RhythmJudgeSession.MAX_INPUT << 1) - 1)) {
                if (r.sequence == 0) {
                    SteveDanceMod.LOGGER.debug(
                            "First F 2nd input rejected: client={}, server={}, sequence={}",
                            c.timeMs(),
                            now,
                            c.sequence());
                }
                return;
            }
            r.sequence = c.sequence();
            r.engine.inputBatch(c.bit(), c.pressed(), c.timeMs());
        }
        if (c.action() == 4) {
            // 中文：在谱面结束时定案，不使用常规的 750 毫秒迟到输入缓冲。
            // EN: The terminal clock packet follows all input edges on the same ordered channel.
            // 中文：有界原版皮肤输入，由客户端导入和服务端验证共享。
            // EN: Finalize at the chart end without the regular 750 ms late-input cushion.
            if (c.timeMs() >= r.entry.chart().durationMs()
                    && Math.abs(c.timeMs() - (r.time() + r.offset)) <= 250) {
                r.engine.advance(r.entry.chart().durationMs());
            }
            if (Math.abs(c.timeMs() - r.time()) > 1000) {
                SteveDanceMod.LOGGER.debug("F 2nd audio clock drift {}ms", c.timeMs() - r.time());
            }
        }
    }

    public static void stop(ServerPlayer p, boolean notify) {
        if (ACTIVE.remove(p.getUUID()) != null) {
            PerformanceStageRegistry.release(p.getUUID());
            if (notify) {
                RhythmNetwork.simple(p, 4, "", "");
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (var e : List.copyOf(ACTIVE.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            Run r = e.getValue();
            if (p == null) {
                ACTIVE.remove(e.getKey());
                PerformanceStageRegistry.release(e.getKey());
                continue;
            }
            if (!valid(p, r)) {
                SteveDanceMod.LOGGER.warn(
                        "F 2nd run stopped: player={}, position={}, stage={}, dimension={}, alive={}",
                        p.getUUID(),
                        p.position(),
                        r.stage,
                        r.dimension,
                        p.isAlive());
                stop(p, true);
                continue;
            }
            if ((!r.ready && p.serverLevel().getGameTime() > r.readyDeadline)
                    || (r.paused && System.nanoTime() - r.pauseNanos > 600000000000L)) {
                stop(p, true);
                continue;
            }
            if (r.resumePending && System.nanoTime() > r.resumeDeadline) {
                stop(p, true);
                continue;
            }
            if (!r.ready || r.paused) {
                continue;
            }
            if (!r.playing) {
                if (r.time() > 5000) {
                    stop(p, true);
                }
                continue;
            }
            r.engine.advance(r.time() + r.offset - 750);
            int duration = r.entry.chart().durationMs();
            int percent =
                    (int) Math.min(100, Math.max(0, (long) r.engine.timeMs() * 100 / duration));
            while (!r.engine.result().failed()
                    && r.lastProgress < percent
                    && ACTIVE.get(e.getKey()) == r) {
                int crossed = ++r.lastProgress;
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new dev.flower.stevedance.rewards.RhythmChallengeEvent.Progress(
                                p,
                                r.runId,
                                r.entry.chart().id(),
                                r.entry.chart().difficulty(),
                                r.entry.chart().clearPercent(),
                                r.engine.timeMs(),
                                duration,
                                r.engine.result(),
                                crossed));
            }
            if (ACTIVE.get(e.getKey()) != r) {
                continue;
            }
            if (r.engine.finished()) {
                RhythmJudgeSession.Result result = r.engine.result();
                PlayerScoreStore saved = PlayerScoreStore.get(server);
                boolean hi = saved.submit(p, r.entry, result);
                var best =
                        saved.personal(
                                PlayerScoreStore.key(
                                        r.entry.chart().id(),
                                        r.entry.chart().difficulty(),
                                        r.entry.digest()),
                                p.getUUID());
                state(
                        p,
                        r,
                        3,
                        RhythmChartLibrary.JSON.toJson(
                                new Completion(result, hi, best == null ? 0 : best.score())));
                ACTIVE.remove(e.getKey());
                PerformanceStageRegistry.release(e.getKey());
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new dev.flower.stevedance.rewards.RhythmChallengeEvent.Completed(
                                p,
                                r.runId,
                                r.entry.chart().id(),
                                r.entry.chart().difficulty(),
                                r.entry.chart().clearPercent(),
                                r.engine.timeMs(),
                                duration,
                                result));
            } else if (System.nanoTime() - r.lastSync >= 200000000L) {
                r.lastSync = System.nanoTime();
                var hits = r.engine.hitsSince(r.sentSerial);
                if (!hits.isEmpty()) {
                    r.sentSerial = hits.get(hits.size() - 1).serial();
                }
                state(
                        p,
                        r,
                        9,
                        RhythmChartLibrary.JSON.toJson(
                                new Snapshot(
                                        r.engine.result(), r.engine.combo(), r.sequence, hits)));
            }
        }
    }

    @SubscribeEvent
    public static void logout(
            net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e) {
        ACTIVE.remove(e.getEntity().getUUID());
        PerformanceStageRegistry.release(e.getEntity().getUUID());
        REQUESTS.remove(e.getEntity().getUUID());
        QUERIES.remove(e.getEntity().getUUID());
        OPENS.remove(e.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent e) {
        ACTIVE.clear();
        PerformanceStageRegistry.clear();
        REQUESTS.clear();
        QUERIES.clear();
        OPENS.clear();
    }
}
