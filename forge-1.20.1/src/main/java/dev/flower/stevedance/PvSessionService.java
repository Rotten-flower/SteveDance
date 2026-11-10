package dev.flower.stevedance;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import dev.flower.stevedance.content.StageTimeline;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Files;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端管理舞台、观众和播放控制。
 * <p>Server-owned stage, audience and playback controls.</p>
 */
public final class PvSessionService {
    public static final String SONG = "two_breaths_f2nd";

    private record Pending(
            BlockPos stage,
            String dimension,
            float yaw,
            String song,
            int offset,
            boolean worldStage,
            long expires) {}

    private record Active(Pending pending, StageTimeline clock, long created) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();
    private static final Map<UUID, UUID> VIEWERS = new HashMap<>();
    private static final Set<UUID> READY = new HashSet<>();
    private static final Set<UUID> AMBIENT = new HashSet<>();
    private static final Map<UUID, Map<Integer, dev.flower.stevedance.core.PerformerModelChoice>>
            MODELS = new HashMap<>();
    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();
    private static final Map<UUID, Long> CONTROLS = new HashMap<>();

    public static boolean performing(UUID player) {
        return ACTIVE.containsKey(player);
    }

    public static boolean busy(UUID player) {
        return ACTIVE.containsKey(player)
                || PENDING.containsKey(player)
                || VIEWERS.containsKey(player) && !AMBIENT.contains(player);
    }

    public static void leaveAmbient(ServerPlayer player) {
        if (AMBIENT.contains(player.getUUID())) {
            remove(player.getUUID());
        }
    }

    public static void models(
            ServerPlayer owner,
            Map<Integer, dev.flower.stevedance.core.PerformerModelChoice> choices) {
        MODELS.put(owner.getUUID(), Map.copyOf(choices));
    }

    private static void audienceModels(ServerPlayer viewer, UUID host) {
        StageNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> viewer),
                new StageNetwork.ActorModels(host, MODELS.getOrDefault(host, Map.of())));
    }

    private static StageNetwork.State worldState(UUID host, int kind, String message) {
        var active = ACTIVE.get(host);
        var server = ServerLifecycleHooks.getCurrentServer();
        var owner = server == null ? null : server.getPlayerList().getPlayer(host);
        if (active == null) {
            return RhythmSessionService.worldPerformance(host, kind, message);
        }
        if (!active.pending.worldStage || owner == null) {
            return null;
        }
        long tick = owner.serverLevel().getGameTime();
        return state(
                host,
                active.pending,
                kind,
                tick,
                active.clock.position(tick),
                message + (active.clock.paused() ? ":paused" : ""));
    }

    private static boolean inRange(ServerPlayer player, StageNetwork.State state) {
        return player.isAlive()
                && player.level().dimension().location().toString().equals(state.dimension())
                && player.position().distanceToSqr(Vec3.atCenterOf(state.stage())) <= 64 * 64;
    }

    public static void audienceChanged(UUID host) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (var entry : List.copyOf(VIEWERS.entrySet())) {
            if (entry.getValue().equals(host) && READY.contains(entry.getKey())) {
                var viewer = server.getPlayerList().getPlayer(entry.getKey());
                var snapshot = worldState(host, 13, "sync");
                if (viewer != null && snapshot != null) {
                    StageNetwork.send(viewer, snapshot);
                }
            }
        }
    }

    public static boolean occupied(WorldStage.Anchor stage) {
        String dimension = stage.getLevel().dimension().location().toString();
        return ACTIVE.values().stream()
                        .anyMatch(
                                a ->
                                        a.pending.stage.equals(stage.getBlockPos())
                                                && a.pending.dimension.equals(dimension))
                || PENDING.values().stream()
                        .anyMatch(
                                a ->
                                        a.stage.equals(stage.getBlockPos())
                                                && a.dimension.equals(dimension));
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("stevedancepv")
                                .then(
                                        Commands.literal("stage")
                                                .then(
                                                        Commands.literal("info")
                                                                .executes(
                                                                        c ->
                                                                                dev.flower
                                                                                        .stevedance
                                                                                        .WorldStagePlacement
                                                                                        .info(
                                                                                                c.getSource()
                                                                                                        .getPlayerOrException()))))
                                .then(
                                        Commands.literal("game")
                                                .executes(
                                                        c -> {
                                                            RhythmSessionService.open(
                                                                    c.getSource()
                                                                            .getPlayerOrException());
                                                            return 1;
                                                        }))
                                .then(
                                        Commands.literal("watch")
                                                .executes(
                                                        c ->
                                                                watch(
                                                                        c.getSource()
                                                                                .getPlayerOrException())))
                                .then(
                                        Commands.literal("pause")
                                                .executes(
                                                        c ->
                                                                control(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        2,
                                                                        0)))
                                .then(
                                        Commands.literal("resume")
                                                .executes(
                                                        c ->
                                                                control(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        3,
                                                                        0)))
                                .then(
                                        Commands.literal("play")
                                                .executes(
                                                        c ->
                                                                prepare(
                                                                        c.getSource()
                                                                                .getPlayerOrException(),
                                                                        0))
                                                .then(
                                                        Commands.argument(
                                                                        "song",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .StringArgumentType
                                                                                .word())
                                                                .suggests(
                                                                        (c, b) ->
                                                                                net.minecraft
                                                                                        .commands
                                                                                        .SharedSuggestionProvider
                                                                                        .suggest(
                                                                                                SongCatalog
                                                                                                        .ALL
                                                                                                        .keySet(),
                                                                                                b))
                                                                .executes(
                                                                        c ->
                                                                                prepareSong(
                                                                                        c.getSource()
                                                                                                .getPlayerOrException(),
                                                                                        com.mojang
                                                                                                .brigadier
                                                                                                .arguments
                                                                                                .StringArgumentType
                                                                                                .getString(
                                                                                                        c,
                                                                                                        "song"),
                                                                                        0))))
                                .then(
                                        Commands.literal("private")
                                                .then(
                                                        Commands.argument(
                                                                        "song",
                                                                        com.mojang.brigadier
                                                                                .arguments
                                                                                .StringArgumentType
                                                                                .word())
                                                                .suggests(
                                                                        (c, b) ->
                                                                                net.minecraft
                                                                                        .commands
                                                                                        .SharedSuggestionProvider
                                                                                        .suggest(
                                                                                                SongCatalog
                                                                                                        .ALL
                                                                                                        .keySet(),
                                                                                                b))
                                                                .executes(
                                                                        c ->
                                                                                prepareSong(
                                                                                        c.getSource()
                                                                                                .getPlayerOrException(),
                                                                                        com.mojang
                                                                                                .brigadier
                                                                                                .arguments
                                                                                                .StringArgumentType
                                                                                                .getString(
                                                                                                        c,
                                                                                                        "song"),
                                                                                        0,
                                                                                        false))))
                                .then(
                                        Commands.literal("seek")
                                                .then(
                                                        Commands.argument(
                                                                        "seconds",
                                                                        IntegerArgumentType.integer(
                                                                                0, 599))
                                                                .executes(
                                                                        c ->
                                                                                control(
                                                                                        c.getSource()
                                                                                                .getPlayerOrException(),
                                                                                        4,
                                                                                        IntegerArgumentType
                                                                                                        .getInteger(
                                                                                                                c,
                                                                                                                "seconds")
                                                                                                * 1000))))
                                .then(
                                        Commands.literal("stop")
                                                .executes(
                                                        c -> {
                                                            stop(
                                                                    c.getSource()
                                                                            .getPlayerOrException());
                                                            return 1;
                                                        })));
    }

    private static int fail(ServerPlayer p, String key) {
        p.sendSystemMessage(Component.translatable(key));
        return 0;
    }

    private static boolean available(String song) {
        return SongCatalog.ALL.containsKey(song)
                && Files.isRegularFile(
                        BundledSongResources.nativeSong(song).resolve("preview.json"));
    }

    private static boolean allowed(ServerPlayer p) {
        return p.isAlive()
                && !p.isSpectator()
                && !StageSessionService.busy(p.getUUID())
                && !RhythmSessionService.busy(p.getUUID());
    }

    public static int prepare(ServerPlayer p, int offset) {
        var stage = WorldStage.nearby(p);
        return prepareSong(p, stage == null ? SONG : stage.song(), offset, true);
    }

    public static int prepareSong(ServerPlayer p, String song, int offset) {
        return prepareSong(p, song, offset, true);
    }

    public static int prepareSong(ServerPlayer p, String song, int offset, boolean worldStage) {
        dev.flower.stevedance.WorldPreview.end(p);
        if (!available(song)) {
            return fail(p, "ui.stevedance.native_missing");
        }
        if (offset < 0 || offset >= SongCatalog.get(song).durationMs() || !allowed(p)) {
            return fail(p, "ui.stevedance.start_refused");
        }
        if (worldStage && dev.flower.stevedance.WorldStagePlacement.selected(p) == null) {
            return fail(p, "ui.stevedance.stage_not_set");
        }
        stop(p);
        var stage =
                worldStage
                        ? PerformanceStageRegistry.acquireWorldStage(p, song)
                        : PerformanceStageRegistry.acquirePrivate(p, song);
        if (stage == null) {
            return fail(p, "ui.stevedance.stage_space");
        }
        Pending pending =
                new Pending(
                        stage.getBlockPos().immutable(),
                        p.level().dimension().location().toString(),
                        stage.yaw(),
                        song,
                        offset,
                        worldStage,
                        p.serverLevel().getGameTime() + 600);
        PENDING.put(p.getUUID(), pending);
        StageNetwork.send(p, state(p.getUUID(), pending, 8, 0, offset, ""));
        return 1;
    }

    private static StageNetwork.State state(
            UUID owner, Pending pending, int kind, long tick, int position, String message) {
        String mode =
                pending.worldStage
                        ? message
                        : "isolated" + (message.isEmpty() ? "" : ":" + message);
        return new StageNetwork.State(
                kind,
                owner,
                pending.song,
                tick,
                mode,
                null,
                pending.stage,
                pending.dimension,
                pending.yaw,
                position);
    }

    public static int watch(ServerPlayer p) {
        leaveAmbient(p);
        if (busy(p.getUUID()) || !allowed(p)) {
            return fail(p, "ui.stevedance.start_refused");
        }
        var candidate =
                ACTIVE.entrySet().stream()
                        .filter(e -> e.getValue().pending.worldStage && inRange(p, e.getValue()))
                        .min(
                                Comparator.comparingDouble(
                                        e ->
                                                p.position()
                                                        .distanceToSqr(
                                                                origin(e.getValue().pending))))
                        .orElse(null);
        if (candidate == null) {
            return fail(p, "ui.stevedance.no_performance");
        }
        VIEWERS.put(p.getUUID(), candidate.getKey());
        var active = candidate.getValue();
        StageNetwork.send(
                p,
                state(
                        candidate.getKey(),
                        active.pending,
                        8,
                        0,
                        active.clock.position(p.serverLevel().getGameTime()),
                        ""));
        return 1;
    }

    private static Vec3 origin(Pending pending) {
        return new Vec3(
                pending.stage.getX() + .5, pending.stage.getY() + .125, pending.stage.getZ() + .5);
    }

    private static boolean inRange(ServerPlayer p, Active active) {
        return p.isAlive()
                && p.level().dimension().location().toString().equals(active.pending.dimension)
                && p.position().distanceToSqr(origin(active.pending)) <= 64 * 64;
    }

    public static void request(ServerPlayer p, int action) {
        if (action == 1) {
            stop(p);
            return;
        }
        if (action != 0) {
            return;
        }
        UUID host = VIEWERS.get(p.getUUID());
        if (host != null) {
            var snapshot = worldState(host, 9, AMBIENT.contains(p.getUUID()) ? "ambient" : "");
            if (snapshot == null || !inRange(p, snapshot)) {
                stop(p);
                return;
            }
            READY.add(p.getUUID());
            audienceModels(p, host);
            StageNetwork.send(p, snapshot);
            return;
        }
        Pending pending = PENDING.remove(p.getUUID());
        if (pending == null) {
            return;
        }
        var stage = PerformanceStageRegistry.get(p, pending.stage);
        if (!allowed(p)
                || pending.expires < p.serverLevel().getGameTime()
                || !pending.dimension.equals(p.level().dimension().location().toString())
                || stage == null
                || !stage.song().equals(pending.song)
                || !stage.availableTo(p)
                || p.position().distanceToSqr(stage.origin()) > (pending.worldStage ? 64 * 64 : 36)
                || occupied(stage)
                || StageSessionService.stageBusy(stage)
                || RhythmSessionService.stageBusy(stage)) {
            PerformanceStageRegistry.release(p.getUUID());
            StageNetwork.send(p, new StageNetwork.State(10, p.getUUID(), SONG, 0, "", null));
            return;
        }
        long tick = p.serverLevel().getGameTime();
        ACTIVE.put(
                p.getUUID(),
                new Active(pending, new StageTimeline(pending.offset, tick + 40), tick));
        StageNetwork.send(p, state(p.getUUID(), pending, 9, tick + 40, pending.offset, ""));
    }

    public static int control(ServerPlayer p, int action, int timeMs) {
        var active = ACTIVE.get(p.getUUID());
        if (active == null) {
            return fail(p, "ui.stevedance.host_control");
        }
        long tick = p.serverLevel().getGameTime();
        if (tick < active.created + 40 || tick - CONTROLS.getOrDefault(p.getUUID(), -100L) < 2) {
            return 0;
        }
        if (action == 2) {
            active.clock.pause(tick);
        } else if (action == 3) {
            active.clock.resume(tick);
        } else if (action == 4) {
            if (timeMs < 0 || timeMs >= SongCatalog.get(active.pending.song).durationMs()) {
                return 0;
            }
            active.clock.seek(timeMs, tick);
        } else {
            return 0;
        }
        CONTROLS.put(p.getUUID(), tick);
        broadcast(
                p.getUUID(),
                state(
                        p.getUUID(),
                        active.pending,
                        action == 2 ? 11 : action == 3 ? 12 : 13,
                        tick,
                        active.clock.position(tick),
                        active.clock.paused() ? "paused" : ""));
        return 1;
    }

    private static void broadcast(UUID host, StageNetwork.State message) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        var owner = server.getPlayerList().getPlayer(host);
        if (owner != null) {
            StageNetwork.send(owner, message);
        }
        for (var e : VIEWERS.entrySet()) {
            if (e.getValue().equals(host) && (message.kind() == 10 || READY.contains(e.getKey()))) {
                var viewer = server.getPlayerList().getPlayer(e.getKey());
                if (viewer != null) {
                    StageNetwork.send(viewer, message);
                }
            }
        }
    }

    public static void stop(ServerPlayer p) {
        remove(p.getUUID());
    }

    private static void remove(UUID id) {
        if (VIEWERS.remove(id) != null) {
            READY.remove(id);
            AMBIENT.remove(id);
            var server = ServerLifecycleHooks.getCurrentServer();
            var p = server == null ? null : server.getPlayerList().getPlayer(id);
            if (p != null) {
                StageNetwork.send(p, new StageNetwork.State(10, p.getUUID(), SONG, 0, "", null));
            }
            return;
        }
        var active = ACTIVE.get(id);
        var pending = PENDING.get(id);
        if (active != null || pending != null) {
            broadcast(id, new StageNetwork.State(10, id, SONG, 0, "", null));
            for (UUID viewer : List.copyOf(VIEWERS.keySet())) {
                if (id.equals(VIEWERS.get(viewer))) {
                    VIEWERS.remove(viewer);
                    READY.remove(viewer);
                    AMBIENT.remove(viewer);
                }
            }
            ACTIVE.remove(id);
            PENDING.remove(id);
            PerformanceStageRegistry.release(id);
        }
        CONTROLS.remove(id);
    }

    @SubscribeEvent
    public static void logout(
            net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        remove(id);
        COOLDOWN.remove(id);
        MODELS.remove(id);
    }

    @SubscribeEvent
    public static void stopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        ACTIVE.clear();
        PENDING.clear();
        VIEWERS.clear();
        READY.clear();
        AMBIENT.clear();
        MODELS.clear();
        PerformanceStageRegistry.clear();
        COOLDOWN.clear();
        CONTROLS.clear();
    }

    @SubscribeEvent
    public static void tick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (UUID id : List.copyOf(PENDING.keySet())) {
            var p = server.getPlayerList().getPlayer(id);
            if (p == null || PENDING.get(id).expires < p.serverLevel().getGameTime()) {
                remove(id);
            }
        }
        for (var entry : List.copyOf(ACTIVE.entrySet())) {
            var p = server.getPlayerList().getPlayer(entry.getKey());
            var a = entry.getValue();
            if (p == null) {
                remove(entry.getKey());
                continue;
            }
            var stage = PerformanceStageRegistry.get(p, a.pending.stage);
            long tick = p.serverLevel().getGameTime();
            if (!inRange(p, a)
                    || tick - a.created > 20 * 60 * 30
                    || (!a.clock.paused()
                            && a.clock.position(tick)
                                    > SongCatalog.get(a.pending.song).durationMs() + 5000)
                    || stage == null
                    || !stage.song().equals(a.pending.song)) {
                remove(entry.getKey());
                continue;
            }
        }
        for (var entry : List.copyOf(VIEWERS.entrySet())) {
            var p = server.getPlayerList().getPlayer(entry.getKey());
            var snapshot = worldState(entry.getValue(), 13, "sync");
            if (p == null || snapshot == null || !inRange(p, snapshot)) {
                remove(entry.getKey());
            } else if (READY.contains(entry.getKey()) && server.getTickCount() % 20 == 0) {
                StageNetwork.send(p, snapshot);
            }
        }
        if (server.getTickCount() % 20 == 0) {
            var hosts = new HashSet<>(ACTIVE.keySet());
            hosts.addAll(RhythmSessionService.worldHosts());
            for (var player : server.getPlayerList().getPlayers()) {
                if (busy(player.getUUID())
                        || VIEWERS.containsKey(player.getUUID())
                        || !allowed(player)) {
                    continue;
                }
                StageNetwork.State nearest = null;
                double distance = Double.MAX_VALUE;
                for (UUID host : hosts) {
                    if (host.equals(player.getUUID())) {
                        continue;
                    }
                    var snapshot = worldState(host, 8, "ambient");
                    if (snapshot == null || !inRange(player, snapshot)) {
                        continue;
                    }
                    double d = player.position().distanceToSqr(Vec3.atCenterOf(snapshot.stage()));
                    if (d < distance) {
                        distance = d;
                        nearest = snapshot;
                    }
                }
                if (nearest != null) {
                    VIEWERS.put(player.getUUID(), nearest.player());
                    AMBIENT.add(player.getUUID());
                    StageNetwork.send(player, nearest);
                    audienceModels(player, nearest.player());
                }
            }
        }
    }
}
