package dev.flower.stevedance.client;

import com.mojang.authlib.GameProfile;

import dev.flower.stevedance.BundledSongResources;
import dev.flower.stevedance.SongSelectionCatalog;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.PerformerModelChoice;
import dev.flower.stevedance.core.PlaybackClock;
import dev.flower.stevedance.core.PvBodyMotion;
import dev.flower.stevedance.core.PvFaceMotion;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 本地世界摄影，不移动真实玩家。 / Local world photography never moves the real player. */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class PhotoMode {
    private static final ExecutorService LOADER =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "SteveDance photography loader");
                        t.setDaemon(true);
                        return t;
                    });
    private static RemotePlayer actor;
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static CameraType previousCamera;
    private static Vec3 anchor = Vec3.ZERO, eye = Vec3.ZERO, sourceOffset = Vec3.ZERO;
    private static float heading, yaw, pitch, roll, fov = 55;
    private static boolean opened, playing, authored, music, loading;
    private static double held, frameTime = Double.NaN;
    private static long began, epoch, captureAt;
    private static int generation;
    private static PvBodyMotion body;
    private static PvFaceMotion faces;
    private static PvSceneData.CameraOnly camera;
    private static PvAudioPlayer audio;
    private static PerformerModelChoice choice = PerformerModelChoice.player();
    private static String song = "", error = "";
    private static PhotoScreen screen;

    private record Loaded(
            PvBodyMotion body,
            PvFaceMotion faces,
            PvSceneData.CameraOnly camera,
            PvAudioPlayer audio) {}

    public static boolean active() {
        var mc = Minecraft.getInstance();
        return opened && actor != null && mc.level == level && mc.player != null;
    }

    public static boolean isActor(UUID id) {
        return active() && actor.getUUID().equals(id);
    }

    public static RemotePlayer actor() {
        return actor;
    }

    public static Vec3 anchor() {
        return anchor;
    }

    public static PerformerModelChoice choice() {
        return choice;
    }

    public static String song() {
        return song;
    }

    public static String error() {
        return error;
    }

    public static boolean loading() {
        return loading;
    }

    public static boolean playing() {
        return playing;
    }

    public static boolean authored() {
        return authored;
    }

    public static boolean music() {
        return music;
    }

    public static float fov() {
        return fov;
    }

    public static float roll() {
        return roll;
    }

    public static float heading() {
        return heading;
    }

    public static int duration() {
        return camera == null ? 0 : camera.durationMs;
    }

    public static void open() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (active()) {
            mc.setScreen(screen);
            return;
        }
        if (RhythmClientController.session != null
                || PvPlaybackController.active()
                || ClientPlaybackRuntime.session != null) {
            mc.player.displayClientMessage(
                    Component.translatable("ui.stevedance.photo.busy"), true);
            return;
        }
        MenuCharacterPresentation.stop();
        level = mc.level;
        anchor = mc.player.position();
        heading = mc.player.getYRot();
        var profile = new GameProfile(UUID.randomUUID(), mc.player.getGameProfile().getName());
        profile.getProperties().putAll(mc.player.getGameProfile().getProperties());
        actor = new RemotePlayer(level, profile);
        int id = -44000;
        while (level.getEntity(id) != null) {
            id--;
        }
        actor.setId(id);
        actor.setNoGravity(true);
        actor.setInvulnerable(true);
        level.addPlayer(id, actor);
        PvPerformerRendering.inheritModel(mc.player, actor);
        String model = YsmModelLibraryBridge.current(mc.player);
        choice =
                model.isBlank()
                        ? PerformerModelChoice.player()
                        : new PerformerModelChoice(PerformerModelChoice.Kind.YSM, model);
        PerformerModelSelection.preview(actor, choice);
        placeActor();
        previousCamera = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        opened = true;
        playing = authored = music = loading = false;
        body = null;
        faces = null;
        camera = null;
        sourceOffset = Vec3.ZERO;
        song = error = "";
        held = 0;
        fov = 55;
        roll = 0;
        captureAt = 0;
        epoch++;
        generation++;
        Vec3 target = anchor.add(0, 1.1, 0);
        double radians = Math.toRadians(heading);
        eye = target.add(-Math.sin(radians) * 4, .5, Math.cos(radians) * 4);
        lookAt(target);
        screen = new PhotoScreen();
        OriginalUiAssets.prepare();
        SongThumbnailCache.prepare();
        SongSelectionCatalog.reload();
        mc.setScreen(screen);
    }

    public static void close() {
        if (!opened) {
            return;
        }
        opened = false;
        generation++;
        captureAt = 0;
        playing = loading = false;
        if (audio != null) {
            audio.close();
            audio = null;
        }
        if (actor != null) {
            PerformerModelSelection.releasePreview(actor);
            actor = null;
        }
        body = null;
        faces = null;
        camera = null;
        frameTime = Double.NaN;
        var mc = Minecraft.getInstance();
        if (previousCamera != null) {
            mc.options.setCameraType(previousCamera);
        }
        previousCamera = null;
        level = null;
    }

    public static void model(PerformerModelChoice value) {
        choice = value;
        if (actor != null) {
            PerformerModelSelection.preview(actor, value);
        }
        epoch++;
    }

    private static Vec3 sceneOrigin() {
        return anchor.subtract(sourceOffset.yRot((float) Math.toRadians(180 - heading)));
    }

    private static void placeActor() {
        if (actor == null) {
            return;
        }
        var origin = sceneOrigin();
        actor.setPos(origin);
        actor.xo = actor.xOld = origin.x;
        actor.yo = actor.yOld = origin.y;
        actor.zo = actor.zOld = origin.z;
        actor.setYRot(heading);
        actor.yRotO = actor.yBodyRot = actor.yBodyRotO = actor.yHeadRot = actor.yHeadRotO = heading;
    }

    public static void moveAnchor(double x, double y, double z) {
        anchor = anchor.add(x, y, z);
        placeActor();
        epoch++;
    }

    public static void heading(float delta) {
        heading += delta;
        placeActor();
        epoch++;
    }

    public static void load(String id) {
        if (!active() || loading) {
            return;
        }
        loading = true;
        error = "";
        int ticket = ++generation;
        CompletableFuture.supplyAsync(
                        () -> {
                            PvAudioPlayer a = null;
                            try {
                                var root = PvSceneData.root(id);
                                var m =
                                        new PvBodyMotion(
                                                PvSceneData.safe(root, "motion.json"),
                                                PvSceneData.safe(root, "motion.bin"));
                                var c =
                                        new PvSceneData.CameraOnly(
                                                PvSceneData.safe(root, "preview.json"));
                                PvFaceMotion f = null;
                                if (BundledSongResources.exists(root.resolve("faces.json"))) {
                                    f =
                                            new PvFaceMotion(
                                                    PvSceneData.safe(root, "faces.json"),
                                                    PvSceneData.safe(root, "faces.bin"),
                                                    PvSceneData.safe(root, "motion.bin"),
                                                    m,
                                                    0);
                                }
                                YsmAnimationAdapter.prepareNative(m);
                                a = PvAudioPlayer.load(PvSceneData.safe(root, "music.ogg"));
                                return new Loaded(m, f, c, a);
                            } catch (Exception ex) {
                                if (a != null) {
                                    a.close();
                                }
                                throw new CompletionException(ex);
                            }
                        },
                        LOADER)
                .whenComplete(
                        (value, failure) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (ticket != generation || !active()) {
                                                        if (value != null) {
                                                            value.audio.close();
                                                        }
                                                        return;
                                                    }
                                                    loading = false;
                                                    if (failure != null) {
                                                        error = "ui.stevedance.photo.load_failed";
                                                        SteveDanceMod.LOGGER.error(
                                                                "Photography motion import failed: {}",
                                                                id,
                                                                failure);
                                                        return;
                                                    }
                                                    if (audio != null) {
                                                        audio.close();
                                                    }
                                                    body = value.body;
                                                    faces = value.faces;
                                                    camera = value.camera;
                                                    audio = value.audio;
                                                    song = id;
                                                    var pose = body.sample(0);
                                                    var hip =
                                                            pose.get("LeftLeg")
                                                                    .position()
                                                                    .add(
                                                                            pose.get("RightLeg")
                                                                                    .position())
                                                                    .mul(.5);
                                                    sourceOffset = new Vec3(-hip.x(), 0, -hip.z());
                                                    placeActor();
                                                    held = 0;
                                                    began = System.nanoTime();
                                                    playing = false;
                                                    epoch++;
                                                    frameTime = Double.NaN;
                                                }));
    }

    private static double rawTime() {
        return Math.max(
                0, Math.min(duration(), held + (playing ? (System.nanoTime() - began) / 1e6 : 0)));
    }

    public static double time() {
        return Double.isNaN(frameTime) ? rawTime() : frameTime;
    }

    public static void seek(double ms) {
        held = Math.max(0, Math.min(duration(), ms));
        began = System.nanoTime();
        frameTime = Double.NaN;
        epoch++;
        if (audio != null) {
            audio.seek((int) held, !playing || !music);
        }
    }

    public static void togglePlay() {
        if (body == null) {
            return;
        }
        double t = time();
        playing = !playing;
        if (playing && t >= duration()) {
            t = 0;
        }
        seek(t);
    }

    public static void toggleMusic() {
        music = !music;
        if (audio != null) {
            audio.seek((int) time(), !playing || !music);
        }
    }

    public static void toggleCamera() {
        if (camera == null) {
            return;
        }
        if (authored) {
            var f = camera();
            eye = f.position();
            yaw = f.yaw();
            pitch = f.pitch();
            roll = f.roll();
            fov = f.fov();
            authored = false;
        } else {
            authored = true;
        }
    }

    private static void lookAt(Vec3 target) {
        Vec3 d = target.subtract(eye);
        yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
    }

    public static void look(double dx, double dy) {
        if (authored) {
            return;
        }
        yaw += (float) dx * .25f;
        pitch = Math.max(-89, Math.min(89, pitch + (float) dy * .25f));
    }

    public static void move(double forward, double right, double up, double distance) {
        if (authored) {
            return;
        }
        Vec3 facing = Vec3.directionFromRotation(pitch, yaw),
                side = new Vec3(-Math.cos(Math.toRadians(yaw)), 0, -Math.sin(Math.toRadians(yaw)));
        Vec3 delta = facing.scale(forward).add(side.scale(right)).add(0, up, 0);
        if (delta.lengthSqr() > 1) {
            delta = delta.normalize();
        }
        Vec3 next = eye.add(delta.scale(distance));
        if (next.distanceToSqr(anchor) <= 64 * 64) {
            eye = next;
        }
    }

    public static void lens(float delta) {
        if (!authored) {
            fov = Math.max(15, Math.min(110, fov + delta));
        }
    }

    public static void tilt(float delta) {
        if (!authored) {
            roll = Math.max(-180, Math.min(180, roll + delta));
        }
    }

    public static PerformanceCameraController.Frame camera() {
        var mc = Minecraft.getInstance();
        return authored && camera != null
                ? camera.sample(
                        time(),
                        sceneOrigin(),
                        heading - 180,
                        mc.getWindow().getWidth() / (float) mc.getWindow().getHeight())
                : new PerformanceCameraController.Frame(eye, yaw, pitch, roll, fov, true, 1);
    }

    public static Map<UUID, ClientPlaybackRuntime.MotionSnapshot> snapshots() {
        if (!active() || body == null) {
            return Map.of();
        }
        return Map.of(
                actor.getUUID(),
                new ClientPlaybackRuntime.MotionSnapshot(
                        null,
                        body,
                        new PlaybackClock(time() / 1000, System.nanoTime(), false, epoch),
                        duration() / 1000.0,
                        faces));
    }

    public static void beginFrame() {
        if (active()) {
            frameTime = rawTime();
        }
    }

    public static void photograph() {
        if (active() && captureAt == 0) {
            captureAt = System.nanoTime() + 3_000_000_000L;
        }
    }

    public static boolean captureHidden() {
        return captureAt != 0 && captureAt - System.nanoTime() < 200_000_000L;
    }

    public static int countdown() {
        return captureAt == 0
                ? 0
                : Math.max(0, (int) Math.ceil((captureAt - System.nanoTime()) / 1e9));
    }

    public static void endFrame() {
        if (active() && captureAt != 0 && System.nanoTime() >= captureAt) {
            captureAt = 0;
            var mc = Minecraft.getInstance();
            net.minecraft.client.Screenshot.grab(
                    mc.gameDirectory,
                    mc.getMainRenderTarget(),
                    message -> mc.execute(() -> mc.gui.getChat().addMessage(message)));
        }
        frameTime = Double.NaN;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !opened) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level != level || mc.player == null || !mc.player.isAlive() || mc.screen == null) {
            close();
            return;
        }
        if (playing && (!mc.isWindowActive() || rawTime() >= duration())) {
            held = rawTime();
            playing = false;
            if (audio != null) {
                audio.pause();
            }
        }
        placeActor();
    }

    @SubscribeEvent
    public static void hidePlayer(net.minecraftforge.client.event.RenderPlayerEvent.Pre event) {
        if (active() && event.getEntity() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        }
    }
}
