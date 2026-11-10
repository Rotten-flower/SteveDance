package dev.flower.stevedance.client;

import dev.flower.stevedance.MotionPackLibrary;
import dev.flower.stevedance.StageNetwork;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.PlaybackClock;
import dev.flower.stevedance.core.PvBodyMotion;
import dev.flower.stevedance.core.PvFaceMotion;
import dev.flower.stevedance.core.RhythmSession;
import dev.flower.stevedance.core.VmdMotion;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class ClientPlaybackRuntime {
    private static final ExecutorService LOADER =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "SteveDance audio loader");
                        t.setDaemon(true);
                        return t;
                    });

    public record Dance(MotionPackLibrary.Pack pack, VmdMotion motion, long startNanos) {}

    public static final Map<UUID, Dance> DANCES = new ConcurrentHashMap<>();
    private static final Map<UUID, net.minecraft.client.player.RemotePlayer> packActors =
            new HashMap<>();

    private static void removePackActor(UUID owner) {
        var actor = packActors.remove(owner);
        if (actor != null) {
            YsmAnimationAdapter.releaseActor(actor.getUUID());
            actor.discard();
        }
    }

    private static void createPackActor(StageNetwork.State state) {
        var mc = Minecraft.getInstance();
        removePackActor(state.player());
        if (mc.level == null) {
            return;
        }
        var source = mc.level.getPlayerByUUID(state.player());
        if (source == null) {
            return;
        }
        var profile =
                new com.mojang.authlib.GameProfile(
                        UUID.randomUUID(), source.getGameProfile().getName());
        profile.getProperties().putAll(source.getGameProfile().getProperties());
        var actor = new net.minecraft.client.player.RemotePlayer(mc.level, profile);
        int entityId = -32000;
        while (mc.level.getEntity(entityId) != null) {
            entityId--;
        }
        actor.setId(entityId);
        actor.setPos(
                state.stage().getX() + .5, state.stage().getY() + .125, state.stage().getZ() + .5);
        actor.setNoGravity(true);
        actor.setYRot(state.yaw());
        actor.yRotO =
                actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = state.yaw();
        mc.level.addPlayer(actor.getId(), actor);
        PvPerformerRendering.inheritModel(source, actor);
        packActors.put(state.player(), actor);
    }

    public record MotionSnapshot(
            VmdMotion motion,
            PvBodyMotion nativeMotion,
            PlaybackClock clock,
            double durationSeconds,
            PvFaceMotion faces) {
        public MotionSnapshot(
                VmdMotion motion, double seconds, long capturedNanos, double durationSeconds) {
            this(
                    motion,
                    null,
                    new PlaybackClock(seconds, capturedNanos, true, 0),
                    durationSeconds,
                    null);
        }

        public double seconds() {
            return clock.seconds();
        }

        public long capturedNanos() {
            return clock.capturedNanos();
        }

        public double sampleSeconds() {
            return nativeMotion != null ? clock.seconds() : clock.extrapolate(System.nanoTime());
        }
    }

    public static volatile Map<UUID, MotionSnapshot> MOTION_SNAPSHOTS = Map.of();
    public static MotionPackLibrary.Pack selected;
    public static MotionPackAudioPlayer audio;
    public static VmdMotion motion;
    public static dev.flower.stevedance.core.VmdCamera camera;
    public static RhythmSession session;
    public static int sequence;
    private static long startTick, startNanos, lastClockNanos, endedNanos;
    private static boolean started, loading;
    private static int generation;
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
    private static long remoteSerial;
    private static final Map<UUID, Long> REMOTE_TICKETS = new HashMap<>();

    private record Progress(int ms, long nanos) {}

    private static final Map<UUID, Progress> REMOTE_PROGRESS = new HashMap<>();
    public static String error = "";
    public static RhythmSession.Result officialResult;

    @Mod.EventBusSubscriber(
            modid = "stevedance",
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void keys(RegisterKeyMappingsEvent event) {
            ClientPlaybackSettings.load();
        }
    }

    public static void prepare(MotionPackLibrary.Pack pack) {
        if (loading) {
            return;
        }
        loading = true;
        error = "";
        officialResult = null;
        selected = pack;
        int ticket = ++generation;
        CompletableFuture.supplyAsync(
                        () -> {
                            MotionPackAudioPlayer loaded = null;
                            try {
                                loaded = MotionPackAudioPlayer.load(pack);
                                if (Math.abs(loaded.durationMs() - pack.chart().durationMs())
                                        > 2000) {
                                    throw new java.io.IOException(
                                            "Audio duration does not match chart");
                                }
                                VmdMotion m;
                                try (var input = pack.motionStream()) {
                                    if (input == null) {
                                        throw new java.io.IOException("VMD missing");
                                    }
                                    m = VmdMotion.read(input.readNBytes(64 * 1024 * 1024 + 1));
                                }
                                YsmAnimationAdapter.prepare();
                                dev.flower.stevedance.core.VmdCamera c = null;
                                try (var input = pack.cameraStream()) {
                                    if (input != null) {
                                        c =
                                                dev.flower.stevedance.core.VmdCamera.read(
                                                        input.readNBytes(64 * 1024 * 1024 + 1));
                                    }
                                }
                                PerformanceSceneRenderer.Asset stage =
                                        PerformanceSceneRenderer.load(pack);
                                return new Object[] {loaded, m, c, stage};
                            } catch (Exception ex) {
                                if (loaded != null) {
                                    loaded.close();
                                }
                                throw new CompletionException(ex);
                            }
                        },
                        LOADER)
                .whenComplete(
                        (value, ex) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (ticket != generation) {
                                                        if (value != null) {
                                                            ((MotionPackAudioPlayer) value[0])
                                                                    .close();
                                                        }
                                                        return;
                                                    }
                                                    loading = false;
                                                    if (ex != null) {
                                                        SteveDanceMod.LOGGER.warn(
                                                                "Song preparation failed: {}",
                                                                ex.toString());
                                                        error = "ui.stevedance.audio_error";
                                                        return;
                                                    }
                                                    audio = (MotionPackAudioPlayer) value[0];
                                                    motion = (VmdMotion) value[1];
                                                    camera =
                                                            (dev.flower.stevedance.core.VmdCamera)
                                                                    value[2];
                                                    try {
                                                        PerformanceSceneRenderer.install(
                                                                pack.manifest().id(),
                                                                (PerformanceSceneRenderer.Asset)
                                                                        value[3]);
                                                    } catch (RuntimeException failure) {
                                                        SteveDanceMod.LOGGER.warn(
                                                                "Stage preparation failed",
                                                                failure);
                                                        closeAudio();
                                                        error = "ui.stevedance.stage_error";
                                                        return;
                                                    }
                                                    StageNetwork.CHANNEL.sendToServer(
                                                            new StageNetwork.Request(
                                                                    pack.manifest().id(),
                                                                    pack.digest(),
                                                                    false));
                                                }));
    }

    public static boolean loading() {
        return loading;
    }

    public static void receive(StageNetwork.State state) {
        if (state.kind() >= 8 && state.kind() <= 13) {
            PvPlaybackController.receive(state);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        boolean self = state.player().equals(mc.player.getUUID());
        if (state.kind() == 0) {
            if (session != null) {
                cancel();
            }
            RhythmClientController.openSelection();
            return;
        }
        if (state.kind() == 6) {
            error = "";
            mc.setScreen(null);
            mc.player.displayClientMessage(Component.translatable(state.message()), true);
            return;
        }
        if (state.kind() == 7) {
            error = state.message();
            return;
        }
        if (state.kind() == 5) {
            if (!self) {
                long now = System.nanoTime();
                REMOTE_PROGRESS.put(state.player(), new Progress(state.playbackMs(), now));
                var dance = DANCES.get(state.player());
                if (dance != null) {
                    DANCES.put(
                            state.player(),
                            new Dance(
                                    dance.pack(),
                                    dance.motion(),
                                    now - state.playbackMs() * 1_000_000L));
                }
            }
            return;
        }
        if (state.kind() == 1) {
            if (mc.level == null
                    || !mc.level.dimension().location().toString().equals(state.dimension())) {
                return;
            }
            if (self
                    && audio != null
                    && selected != null
                    && selected.manifest().id().equals(state.song())) {
                startTick = state.startTick();
                startNanos =
                        System.nanoTime()
                                + Math.max(0, startTick - mc.level.getGameTime()) * 50_000_000L;
                session = new RhythmSession(selected.chart());
                officialResult = null;
                sequence = 0;
                started = false;
                endedNanos = lastClockNanos = 0;
                createPackActor(state);
                DANCES.put(state.player(), new Dance(selected, motion, startNanos));
                PerformanceCameraController.begin(state);
                mc.setScreen(new MotionPackPlayScreen());
            } else {
                var pack = MotionPackLibrary.get(state.song());
                long ticket = ++remoteSerial;
                REMOTE_TICKETS.put(state.player(), ticket);
                REMOTE_PROGRESS.put(
                        state.player(), new Progress(state.playbackMs(), System.nanoTime()));
                if (pack != null && pack.playable()) {
                    CompletableFuture.supplyAsync(
                                    () -> {
                                        try (var stream = pack.motionStream()) {
                                            YsmAnimationAdapter.prepare();
                                            return VmdMotion.read(
                                                    stream.readNBytes(64 * 1024 * 1024 + 1));
                                        } catch (Exception ex) {
                                            SteveDanceMod.LOGGER.warn(
                                                    "Remote motion unavailable: {}", ex.toString());
                                            return null;
                                        }
                                    },
                                    LOADER)
                            .thenAccept(
                                    m ->
                                            mc.execute(
                                                    () -> {
                                                        if (m != null
                                                                && Objects.equals(
                                                                        REMOTE_TICKETS.get(
                                                                                state.player()),
                                                                        ticket)
                                                                && mc.level != null
                                                                && mc.level
                                                                        .dimension()
                                                                        .location()
                                                                        .toString()
                                                                        .equals(
                                                                                state
                                                                                        .dimension())) {
                                                            var progress =
                                                                    REMOTE_PROGRESS.get(
                                                                            state.player());
                                                            createPackActor(state);
                                                            DANCES.put(
                                                                    state.player(),
                                                                    new Dance(
                                                                            pack,
                                                                            m,
                                                                            progress.nanos()
                                                                                    - progress.ms()
                                                                                            * 1_000_000L));
                                                        }
                                                    }));
                }
            }
            return;
        }
        DANCES.remove(state.player());
        removePackActor(state.player());
        REMOTE_TICKETS.remove(state.player());
        REMOTE_PROGRESS.remove(state.player());
        if (self) {
            if (state.kind() == 2) {
                officialResult = state.result();
                closeAudio();
                PerformanceCameraController.stop();
                mc.setScreen(new MotionPackResultScreen(state.result()));
            } else if (state.kind() == 3 || state.kind() == 4) {
                error = state.message();
                closeAudio();
                PerformanceCameraController.stop();
                session = null;
                if (mc.player.isAlive()) {
                    mc.setScreen(new MotionPackSelectionScreen());
                } else if (mc.screen instanceof MotionPackPlayScreen) {
                    mc.setScreen(null);
                }
            }
        }
    }

    public static int timeMs() {
        if (session == null) {
            return 0;
        }
        if (!started) {
            return (int) ((System.nanoTime() - startNanos) / 1_000_000);
        }
        if (audio == null) {
            return selected.chart().durationMs() + 500;
        }
        int audioTime = audio.timeMs();
        if (audioTime >= audio.durationMs() - 5) {
            if (endedNanos == 0) {
                endedNanos = System.nanoTime();
            }
            return audio.durationMs() + (int) ((System.nanoTime() - endedNanos) / 1_000_000);
        }
        return audioTime;
    }

    public static void input(int bit, boolean pressed) {
        if (session == null || !started) {
            return;
        }
        int ms = timeMs() + ClientPlaybackSettings.offsetMs;
        session.input(bit, pressed, ms);
        StageNetwork.CHANNEL.sendToServer(new StageNetwork.Input(++sequence, bit, pressed, ms));
    }

    public static void cancel() {
        ++generation;
        loading = false;
        if (session != null) {
            StageNetwork.CHANNEL.sendToServer(new StageNetwork.Request("", "", true));
        }
        session = null;
        officialResult = null;
        closeAudio();
        PerformanceCameraController.stop();
        var p = Minecraft.getInstance().player;
        if (p != null) {
            DANCES.remove(p.getUUID());
            removePackActor(p.getUUID());
        }
        publishMotion();
    }

    private static void closeAudio() {
        if (audio != null) {
            audio.close();
            audio = null;
        }
        camera = null;
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            PvPlaybackController.beginFrame();
            PerformanceCameraController.beginFrame();
            dev.flower.stevedance.client.PhotoMode.beginFrame();
            publishMotion();
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void frameEnd(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            dev.flower.stevedance.client.PhotoMode.endFrame();
            PerformanceCameraController.endFrame();
            PvPlaybackController.endFrame();
        }
    }

    private static void publishMotion() {
        if (dev.flower.stevedance.client.PhotoMode.active()) {
            installSnapshots(dev.flower.stevedance.client.PhotoMode.snapshots());
            return;
        }
        if (MenuCharacterPresentation.active()) {
            installSnapshots(MenuCharacterPresentation.snapshots());
            return;
        }
        if (PvPlaybackController.active() && PvPlaybackController.motion != null) {
            var p = PvPlaybackController.performer();
            if (p == null) {
                installSnapshots(Map.of());
                return;
            }
            float heading = PvPlaybackController.performerYaw();
            p.yBodyRot = p.yBodyRotO = heading;
            p.yHeadRot = p.yHeadRotO = heading;
            p.setYRot(heading);
            p.yRotO = heading;
            var snaps = new java.util.HashMap<UUID, MotionSnapshot>();
            double time = PvPlaybackController.timeMs() / 1000.0,
                    duration = PvPlaybackController.scene.description.durationMs() / 1000.0;
            long now = System.nanoTime();
            snaps.put(
                    p.getUUID(),
                    new MotionSnapshot(
                            null,
                            PvPlaybackController.motion,
                            new PlaybackClock(
                                    time,
                                    now,
                                    PvPlaybackController.playing(),
                                    PvPlaybackController.epoch()),
                            duration,
                            PvPlaybackController.faces));
            for (var entry : PvPlaybackController.worldActors().entrySet()) {
                int slot = entry.getKey();
                var actor = entry.getValue();
                var body = PvPlaybackController.performerMotion(slot);
                if (body == null) {
                    continue;
                }
                actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = heading;
                actor.setYRot(heading);
                actor.yRotO = heading;
                snaps.put(
                        actor.getUUID(),
                        new MotionSnapshot(
                                null,
                                body,
                                new PlaybackClock(
                                        time,
                                        now,
                                        PvPlaybackController.playing(),
                                        PvPlaybackController.epoch()),
                                duration,
                                PvPlaybackController.performerFaces(slot)));
            }
            snaps.putAll(SceneCrowdRenderer.snapshots());
            installSnapshots(Map.copyOf(snaps));
            return;
        }
        if (DANCES.isEmpty()) {
            installSnapshots(Map.of());
            return;
        }
        long now = System.nanoTime();
        var self = Minecraft.getInstance().player;
        Map<UUID, MotionSnapshot> snapshots = new HashMap<>();
        DANCES.forEach(
                (id, dance) -> {
                    var actor = packActors.get(id);
                    if (actor != null) {
                        snapshots.put(
                                actor.getUUID(),
                                new MotionSnapshot(
                                        dance.motion(),
                                        self != null && self.getUUID().equals(id) && session != null
                                                ? timeMs() / 1000.0
                                                : (now - dance.startNanos()) / 1e9,
                                        now,
                                        dance.pack().chart().durationMs() / 1000.0));
                    }
                });
        installSnapshots(Map.copyOf(snapshots));
    }

    private static void installSnapshots(Map<UUID, MotionSnapshot> next) {
        var old = MOTION_SNAPSHOTS;
        MOTION_SNAPSHOTS = next;
        for (UUID id : old.keySet()) {
            if (!next.containsKey(id)) {
                YsmAnimationAdapter.releaseActor(id);
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!MenuCharacterPresentation.active() && !MenuCharacterPresentation.mode().isEmpty()) {
            MenuCharacterPresentation.stop();
        }
        if (mc.level != lastLevel) {
            cancel();
            for (var id : List.copyOf(packActors.keySet())) {
                removePackActor(id);
            }
            DANCES.clear();
            REMOTE_TICKETS.clear();
            REMOTE_PROGRESS.clear();
            PerformanceSceneRenderer.clear();
            MOTION_SNAPSHOTS = Map.of();
            YsmAnimationAdapter.releaseAll();
            lastLevel = mc.level;
        }
        if (mc.level == null) {
            if (session != null || audio != null) {
                cancel();
            }
            DANCES.clear();
            return;
        }
        if (mc.screen instanceof MotionPackPlayScreen && session != null) {
            if (!started && System.nanoTime() >= startNanos) {
                started = true;
                startNanos = System.nanoTime();
                audio.start(ClientPlaybackSettings.volume);
            }
            if (started) {
                int ms = timeMs() + ClientPlaybackSettings.offsetMs;
                session.advance(ms);
                long now = System.nanoTime();
                if (now - lastClockNanos >= 200_000_000L) {
                    StageNetwork.CHANNEL.sendToServer(new StageNetwork.Clock(ms));
                    lastClockNanos = now;
                }
            }
        }
    }

    public static Component title(MotionPackLibrary.Pack pack) {
        return pack.manifest().title() != null && !pack.manifest().title().isBlank()
                ? Component.literal(pack.manifest().title())
                : dev.flower.stevedance.client.SongText.title(pack.manifest().titleKey());
    }
}
