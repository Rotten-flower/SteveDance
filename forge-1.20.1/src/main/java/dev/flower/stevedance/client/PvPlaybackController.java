package dev.flower.stevedance.client;

import dev.flower.stevedance.BundledSongResources;
import dev.flower.stevedance.PvSessionService;
import dev.flower.stevedance.RhythmNetwork;
import dev.flower.stevedance.SongCatalog;
import dev.flower.stevedance.StageNetwork;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.WorldStage;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客户端PV会话协调入口。选曲与判定见RhythmClientController；服务端确认通过receive与prepareGame/startGame进入。
 * <p>Client PV session coordinator. RhythmClientController owns chart/UI state; server acknowledgements enter through receive and prepareGame/startGame.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class PvPlaybackController {
    private static final ExecutorService LOADER =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "SteveDance PV loader");
                        t.setDaemon(true);
                        return t;
                    });
    public static PvSceneData scene;
    private static PvAudioPlayer audio;
    private static boolean loading, failed, playing, requested, overview;
    // 切歌/停止使异步加载ticket失效；不是音频位置或网络序号。
    // Generation invalidates loader tickets; it is not an audio position or packet sequence.
    private static int generation;
    public static dev.flower.stevedance.core.PvBodyMotion motion, partnerMotion;
    public static net.minecraft.client.player.RemotePlayer partner;
    public static dev.flower.stevedance.core.PvFaceMotion faces, partnerFaces;
    // 暂停后跳转、开始和停止等操作会改变采样代际，依赖者据此重置缓存。
    // Playback discontinuities advance this sampling epoch so consumers can reset caches.
    private static long epoch;
    private static int preparedFrames;
    private static boolean readySent;

    public static long epoch() {
        return epoch;
    }

    public static boolean playing() {
        return playing && !paused;
    }

    private static dev.flower.stevedance.core.PvBodyMotion[] actorMotions =
            new dev.flower.stevedance.core.PvBodyMotion[0];
    private static dev.flower.stevedance.core.PvFaceMotion[] actorFaces =
            new dev.flower.stevedance.core.PvFaceMotion[0];
    private static final java.util.Map<Integer, net.minecraft.client.player.RemotePlayer>
            worldActors = new java.util.LinkedHashMap<>();

    public static dev.flower.stevedance.core.PvBodyMotion performerMotion(int actor) {
        return actor >= 0 && actor < actorMotions.length ? actorMotions[actor] : null;
    }

    public static java.util.Map<Integer, net.minecraft.client.player.RemotePlayer> worldActors() {
        return java.util.Collections.unmodifiableMap(worldActors);
    }

    public static dev.flower.stevedance.core.PvFaceMotion performerFaces(int actor) {
        return actor >= 0 && actor < actorFaces.length ? actorFaces[actor] : null;
    }

    private record Loaded(
            PvSceneData scene,
            dev.flower.stevedance.core.PvBodyMotion[] motions,
            dev.flower.stevedance.core.PvFaceMotion[] faces) {}

    private static WorldStage.Anchor sessionStage;

    public static WorldStage.Anchor sessionStage() {
        return active() ? sessionStage : null;
    }

    private static StageNetwork.State pending, state;
    private static long startNanos, endedNanos;
    private static double heldMs;
    private static CameraType previous, playerCamera;
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;

    public interface WorldView {}

    private static boolean worldView, ambient;
    private static StageNetwork.ActorModels audienceModels;

    public static void receiveModels(StageNetwork.ActorModels message) {
        var current = state == null ? pending : state;
        if (current != null && current.player().equals(message.host())) {
            audienceModels = message;
        }
    }

    public static dev.flower.stevedance.core.PerformerModelChoice remoteChoice(int actor) {
        return state != null
                        && !host()
                        && audienceModels != null
                        && audienceModels.host().equals(state.player())
                ? audienceModels
                        .choices()
                        .getOrDefault(
                                actor, dev.flower.stevedance.core.PerformerModelChoice.player())
                : null;
    }

    private static void sendModels() {
        var choices =
                new java.util.HashMap<Integer, dev.flower.stevedance.core.PerformerModelChoice>();
        for (int actor = 0; actor < actorMotions.length; actor++) {
            if (actorMotions[actor] != null) {
                choices.put(
                        actor,
                        dev.flower.stevedance.client.VanillaSkins.networkChoice(
                                PerformerModelSelection.choice(song, actor)));
            }
        }
        for (var choice : choices.values()) {
            dev.flower.stevedance.client.VanillaSkins.share(choice);
        }
        StageNetwork.CHANNEL.sendToServer(
                new StageNetwork.ActorModels(Minecraft.getInstance().player.getUUID(), choices));
    }

    public static boolean worldStage;

    public static boolean isolatedPlayback() {
        return active() && !worldStage;
    }

    public static boolean sceneryActive() {
        return active();
    }

    public static boolean nativeCharacterLighting() {
        ClientPlaybackSettings.load();
        if (active() ? worldStage : ClientPlaybackSettings.worldStage) {
            return ClientPlaybackSettings.worldPvLighting;
        }
        return switch (ClientPlaybackSettings.renderingMode) {
            case PV -> true;
            case MINECRAFT -> false;
            case AUTO -> !(active() ? worldStage : ClientPlaybackSettings.worldStage);
        };
    }

    public static void toggleCharacterLighting() {
        ClientPlaybackSettings.load();
        if (active() ? worldStage : ClientPlaybackSettings.worldStage) {
            ClientPlaybackSettings.worldPvLighting = !ClientPlaybackSettings.worldPvLighting;
            ClientPlaybackSettings.save();
            return;
        }
        ClientPlaybackSettings.renderingMode =
                nativeCharacterLighting()
                        ? ClientPlaybackSettings.RenderingMode.MINECRAFT
                        : ClientPlaybackSettings.RenderingMode.PV;
        ClientPlaybackSettings.save();
    }

    private static boolean chartOnly, chartStarted;

    public static boolean chartOnly() {
        return chartOnly;
    }

    public static void chartBackground(
            net.minecraft.client.gui.GuiGraphics g, int width, int height) {
        g.fill(0, 0, width, height, 0xff000000);
        try {
            if (!PvMovieBackground.renderChart(g, timeMs())) {
                SongThumbnailCache.drawCover(g, RhythmClientController.song, width, height);
            }
        } catch (RuntimeException ex) {
            chartFailed(ex);
            return;
        }
        g.fill(0, 0, width, height, 0x99050b16);
    }

    private static boolean gameMode, paused, musicAcknowledged, resumeAcknowledgement;
    private static long pauseStarted;
    private static int resumePosition;

    public static void prepareGame(RhythmNetwork.State s) {
        if (s.error().equals("chart")) {
            prepareChart(s);
            return;
        }
        receive(
                new StageNetwork.State(
                        8,
                        Minecraft.getInstance().player.getUUID(),
                        s.song(),
                        0,
                        s.error(),
                        null,
                        s.stage(),
                        s.dimension(),
                        s.yaw(),
                        0));
        gameMode = true;
    }

    public static void startGame(RhythmNetwork.State s) {
        if (chartOnly) {
            chartStarted = true;
            heldMs = 0;
            startNanos = System.nanoTime() - s.timeMs() * 1000000L;
            return;
        }
        musicAcknowledged = false;
        resumeAcknowledgement = false;
        receive(
                new StageNetwork.State(
                        9,
                        Minecraft.getInstance().player.getUUID(),
                        s.song(),
                        s.startTick(),
                        s.error(),
                        null,
                        s.stage(),
                        s.dimension(),
                        s.yaw(),
                        0));
        startNanos = System.nanoTime() - s.timeMs() * 1000000L;
    }

    public static boolean clockReady() {
        return !gameMode || (musicAcknowledged && !resumeAcknowledgement);
    }

    public static int playbackMs() {
        return state == null
                ? 0
                : !playing ? (int) ((System.nanoTime() - startNanos) / 1000000) : (int) timeMs();
    }

    public static void pauseGame() {
        heldMs = timeMs();
        paused = true;
        pauseStarted = System.nanoTime();
        if (audio != null) {
            audio.pause();
            heldMs = audio.timeMs();
        }
    }

    public static void resumeGame() {
        if (endedNanos != 0) {
            endedNanos += System.nanoTime() - pauseStarted;
        }
        resumePosition = (int) heldMs;
        if (audio != null) {
            audio.resume();
        }
        paused = false;
        resumeAcknowledgement = gameMode;
    }

    private static float orbitYaw = 30, orbitPitch = 16, orbitDistance = 5;
    public static String status = "";
    private static String song = PvSessionService.SONG;

    public static String song() {
        return song;
    }

    // 同曲可复用已加载场景；换曲才清GPU与CPU场景。stop只结束当前会话。
    // Reuse a loaded same-song scene; a song change clears GPU/CPU scene data.
    private static void select(String id) {
        SongCatalog.get(id);
        if (song.equals(id)) {
            return;
        }
        generation++;
        PvStageRenderer.clear();
        if (scene != null) {
            scene.close();
        }
        scene = null;
        actorMotions = new dev.flower.stevedance.core.PvBodyMotion[0];
        actorFaces = new dev.flower.stevedance.core.PvFaceMotion[0];
        motion = partnerMotion = null;
        faces = partnerFaces = null;
        loading = false;
        failed = false;
        song = id;
    }

    /**
     * 后台读取CPU场景/动作，回到客户端线程再提交GPU资源。旧ticket的结果必须关闭。
     * <p>Load scene/motion on LOADER; publish and upload on the client thread, closing stale results.</p>
     */
    public static void ensure() {
        if (scene != null || loading || failed) {
            return;
        }
        final String loadingSong = song;
        loading = true;
        int ticket = generation;
        CompletableFuture.supplyAsync(
                        () -> {
                            PvSceneData stage = null;
                            try {
                                var root = PvSceneData.root(loadingSong);
                                stage = PvSceneData.load(root);
                                int count = 1;
                                for (var e : stage.description.events()) {
                                    if (e.command().equals("MIKU_DISP")) {
                                        count = Math.max(count, e.args()[0] + 1);
                                    }
                                }
                                if (count > 16) {
                                    throw new java.io.IOException("Native actor budget exceeded");
                                }
                                var bodies = new dev.flower.stevedance.core.PvBodyMotion[count];
                                var facial = new dev.flower.stevedance.core.PvFaceMotion[count];
                                for (int actor = 0; actor < count; actor++) {
                                    var folder =
                                            actor == 0
                                                    ? root
                                                    : root.resolve("performer" + (actor + 1));
                                    if (!dev.flower.stevedance.BundledSongResources.exists(
                                            folder.resolve("motion.bin"))) {
                                        final int slot = actor;
                                        if (stage.description.events().stream()
                                                .anyMatch(
                                                        e ->
                                                                e.command().equals("MIKU_DISP")
                                                                        && e.args()[0] == slot
                                                                        && e.args()[1] == 1)) {
                                            throw new java.io.IOException(
                                                    "Visible native actor has no motion: " + actor);
                                        }
                                        continue;
                                    }
                                    bodies[actor] =
                                            new dev.flower.stevedance.core.PvBodyMotion(
                                                    PvSceneData.safe(folder, "motion.json"),
                                                    PvSceneData.safe(folder, "motion.bin"));
                                    stage.validateMotion(bodies[actor]);
                                    facial[actor] = loadFaces(folder, bodies[actor], actor);
                                }
                                if (bodies[0] == null) {
                                    throw new java.io.IOException("Missing primary native actor");
                                }
                                return new Loaded(stage, bodies, facial);
                            } catch (Exception ex) {
                                if (stage != null) {
                                    stage.close();
                                }
                                throw new CompletionException(ex);
                            }
                        },
                        LOADER)
                .whenComplete(
                        (loaded, error) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    if (ticket != generation) {
                                                        if (loaded != null) {
                                                            loaded.scene().close();
                                                        }
                                                        return;
                                                    }
                                                    loading = false;
                                                    if (error != null) {
                                                        failed = true;
                                                        status = "ui.stevedance.native_failed";
                                                        SteveDanceMod.LOGGER.error(
                                                                "Native scene import failed",
                                                                error);
                                                        return;
                                                    }
                                                    try {
                                                        for (var body : loaded.motions()) {
                                                            if (body != null) {
                                                                YsmAnimationAdapter.prepareNative(
                                                                        body);
                                                            }
                                                        }
                                                        PvStageRenderer.install(loaded.scene());
                                                        scene = loaded.scene();
                                                        actorMotions = loaded.motions();
                                                        actorFaces = loaded.faces();
                                                        motion = performerMotion(0);
                                                        partnerMotion = performerMotion(1);
                                                        faces = performerFaces(0);
                                                        partnerFaces = performerFaces(1);
                                                        SteveDanceMod.LOGGER.info(
                                                                "Native PV stage installed: {}"
                                                                        + " geometry objects, {} source"
                                                                        + " graphs; {} authored actor"
                                                                        + " slots",
                                                                scene.geometry.length,
                                                                scene.description.graphs().size(),
                                                                actorMotions.length);
                                                    } catch (Exception ex) {
                                                        loaded.scene().close();
                                                        failed = true;
                                                        status = "ui.stevedance.native_failed";
                                                        SteveDanceMod.LOGGER.error(
                                                                "Native stage upload failed", ex);
                                                    }
                                                }));
    }

    private static dev.flower.stevedance.core.PvFaceMotion loadFaces(
            java.nio.file.Path root, dev.flower.stevedance.core.PvBodyMotion body, int actor)
            throws java.io.IOException {
        if (!dev.flower.stevedance.BundledSongResources.exists(root.resolve("faces.json"))) {
            SteveDanceMod.LOGGER.info("Native actor {} has no authored facial track", actor);
            return null;
        }
        PvSceneData.safe(root, "faces.json");
        PvSceneData.safe(root, "faces.bin");
        var result =
                new dev.flower.stevedance.core.PvFaceMotion(
                        PvSceneData.safe(root, "faces.json"),
                        PvSceneData.safe(root, "faces.bin"),
                        PvSceneData.safe(root, "motion.bin"),
                        body,
                        actor);
        SteveDanceMod.LOGGER.info(
                "Native facial track {} actor {}: {} frames, unsupported expressions {}",
                result.motion,
                actor,
                result.frames,
                result.unsupportedExpressions);
        return result;
    }

    /**
     * StageNetwork状态入口：8准备、9开始、10停止、11至13暂停/继续/跳转。
     * <p>Stage state entry: prepare/start/stop (8/9/10), then playback controls (11..13).</p>
     */
    public static void receive(StageNetwork.State message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        levelChanged(mc);
        if (message.kind() != 8
                && message.kind() != 10
                && (state == null
                        ? pending == null || !pending.player().equals(message.player())
                        : !state.player().equals(message.player()))) {
            return;
        }
        if (message.kind() >= 11 && message.kind() <= 13) {
            applyControl(message);
            return;
        }
        if (message.kind() == 10) {
            if (state != null
                    && !state.player().equals(message.player())
                    && !mc.player.getUUID().equals(message.player())) {
                return;
            }
            stop(false);
            return;
        }
        if (message.kind() == 8) {
            stop(false);
            select(message.song());
            ClientPlaybackRuntime.cancel();
            pending = message;
            status = "ui.stevedance.native_loading";
            failed = false;
            ambient = message.message().startsWith("ambient");
            worldView = ambient;
            ensure();
            if (!ambient) {
                mc.setScreen(new PvViewerScreen());
            }
            return;
        }
        if (message.kind() == 9) {
            epoch++;
            dev.flower.stevedance.client.PerformanceControls.reset();
            state = message;
            pending = null;
            worldStage = !message.message().startsWith("isolated");
            sessionStage =
                    new WorldStage.Anchor(
                            message.stage(),
                            WorldStage.ANCHOR
                                    .get()
                                    .defaultBlockState()
                                    .setValue(
                                            net.minecraft.world.level.block.state.properties
                                                    .BlockStateProperties.HORIZONTAL_FACING,
                                            net.minecraft.core.Direction.fromYRot(message.yaw())));
            sessionStage.setLevel(mc.level);
            sessionStage.configureSession(message.song(), message.player());
            PvLyrics.install(scene.subtitles);
            ambient = message.message().startsWith("ambient");
            if (!ambient) {
                if (previous == null) {
                    previous = mc.options.getCameraType();
                }
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            PerformanceCameraController.origin = origin();
            overview = false;
            heldMs = message.playbackMs();
            startNanos =
                    System.nanoTime()
                            + Math.max(0, message.startTick() - mc.level.getGameTime())
                                    * 50_000_000L;
            playing = false;
            paused = message.message().endsWith("paused");
            worldView = ambient;
            ClientPlaybackSettings.load();
            endedNanos = 0;
            status = "ui.stevedance.native_loading";
            if (worldActors.isEmpty()) {
                createPartner();
            }
            if (!ambient) {
                dev.flower.stevedance.client.StageCamera.begin(
                        dev.flower.stevedance.client.StageCamera.Mode.AUTHORED);
            }
        }
    }

    public static boolean active() {
        var mc = Minecraft.getInstance();
        return !chartOnly
                && state != null
                && scene != null
                && mc.level != null
                && mc.player != null
                && mc.level.dimension().location().toString().equals(state.dimension())
                && (worldView
                        || mc.screen instanceof PvViewerScreen
                        || mc.screen instanceof RhythmPlayScreen
                        || mc.screen instanceof WorldView);
    }

    public static Vec3 origin() {
        return state == null
                ? Vec3.ZERO
                : new Vec3(
                        state.stage().getX() + .5,
                        state.stage().getY() + .125,
                        state.stage().getZ() + .5);
    }

    public static float performerYaw() {
        return state == null ? 0 : state.yaw() + 180;
    }

    private static volatile double frameMs = Double.NaN;

    // 整帧共用同一次时钟采样，避免人物、镜头与效果在同一帧各走一步。
    // Pin one clock sample per frame for actors, camera and effects.
    public static void beginFrame() {
        frameMs = rawTimeMs();
    }

    public static void endFrame() {
        frameMs = Double.NaN;
    }

    public static double timeMs() {
        double frame = frameMs;
        return Double.isNaN(frame) ? rawTimeMs() : frame;
    }

    // 正常播放读音频位置；暂停/未开始返回heldMs。音频结束后允许PV尾帧继续。
    // Audio is the running clock; heldMs freezes pause/pre-start; wall time only extends the audio
    // tail.
    private static double rawTimeMs() {
        if (state == null || !playing || audio == null || paused) {
            return heldMs;
        }
        int ms = audio.timeMs();
        if (ms >= audio.durationMs() - 5) {
            if (endedNanos == 0) {
                endedNanos = System.nanoTime();
            }
            return audio.durationMs() + (System.nanoTime() - endedNanos) / 1e6;
        }
        return ms;
    }

    public static double sceneTime(WorldStage.Anchor anchor) {
        if (state != null
                && anchor.getBlockPos().equals(state.stage())
                && anchor.getLevel().dimension().location().toString().equals(state.dimension())) {
            return timeMs();
        }
        return heldMs;
    }

    public static PerformanceCameraController.Frame camera() {
        var alternate = dev.flower.stevedance.client.StageCamera.frame();
        if (alternate != null) {
            return alternate;
        }
        return authoredCamera();
    }

    public static PerformanceCameraController.Frame authoredCamera() {
        var mc = Minecraft.getInstance();
        float aspect = mc.getWindow().getWidth() / (float) mc.getWindow().getHeight();
        if (!overview) {
            return dev.flower.stevedance.client.CameraCalibration.apply(
                    scene.camera(timeMs(), origin(), state.yaw(), aspect),
                    timeMs(),
                    origin(),
                    state.yaw());
        }
        double yaw = Math.toRadians(orbitYaw - state.yaw()), pitch = Math.toRadians(orbitPitch);
        Vec3 target = orbitTarget(),
                eye =
                        target.add(
                                Math.sin(yaw) * orbitDistance * Math.cos(pitch),
                                Math.sin(pitch) * orbitDistance,
                                Math.cos(yaw) * orbitDistance * Math.cos(pitch)),
                d = target.subtract(eye);
        return new PerformanceCameraController.Frame(
                eye,
                (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))),
                0,
                55,
                true,
                1);
    }

    public static Vec3 orbitTarget() {
        if (motion == null || !motion.sourceWorldCoordinates) {
            return origin().add(0, 1.1, 0);
        }
        Vec3 center = Vec3.ZERO;
        int count = 0;
        double ms = timeMs();
        for (int actor = 0; actor < actorMotions.length; actor++) {
            var body = actorMotions[actor];
            if (body == null || !scene.performerVisible(ms, actor)) {
                continue;
            }
            var head = body.sample(ms / 1000).get("Head").position();
            if (head.y() < -100) {
                continue;
            }
            center = center.add(-head.x(), head.y() - .5, -head.z());
            count++;
        }
        if (count == 0) {
            return origin().add(0, 1.1, 0);
        }
        return origin().add(center.scale(1.0 / count).yRot((float) -Math.toRadians(state.yaw())));
    }

    /** 玩家观看只释放本地镜头，不改变会话、播放时间或玩家位置。
     * <p>Player viewing releases the local camera without restarting or moving the player.</p> */
    public static boolean canWatchAsPlayer() {
        return active() && worldStage && !gameMode && !ambient && playing && status.isEmpty();
    }

    public static boolean playerViewing() {
        return worldView && !ambient && !gameMode && state != null;
    }

    public static void watchAsPlayer() {
        if (!canWatchAsPlayer()) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (!playerViewing()) {
            if (playerCamera == null) {
                playerCamera = previous == null ? CameraType.FIRST_PERSON : previous;
            }
            worldView = true;
            mc.options.setCameraType(playerCamera);
        }
        mc.setScreen(null);
    }

    public static void returnToPreview() {
        if (!playerViewing()) {
            return;
        }
        var mc = Minecraft.getInstance();
        playerCamera = mc.options.getCameraType();
        worldView = false;
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        // 不调用StageCamera.begin，保留自由机位与转向。 / Preserve the saved preview rig.
        if (!(mc.screen instanceof PvViewerScreen)) {
            mc.setScreen(new PvViewerScreen());
        }
    }

    public static boolean cameraActive() {
        return active() && !worldView;
    }

    /**
     * 舞台始终渲染一个独立角色；本地玩家仅是控制器。
     * <p>The stage always renders an independent actor; the local player is only the controller.</p>
     */
    public static net.minecraft.world.entity.player.Player performer() {
        if (state == null) {
            return null;
        }
        return worldActors.get(0);
    }

    public static boolean host() {
        var mc = Minecraft.getInstance();
        return state != null && mc.player != null && state.player().equals(mc.player.getUUID());
    }

    public static java.util.UUID hostId() {
        return state == null ? null : state.player();
    }

    public static boolean paused() {
        return paused;
    }

    public static boolean gameMode() {
        return gameMode;
    }

    public static void control(int action, int position) {
        if (active() && !gameMode && host()) {
            StageNetwork.CHANNEL.sendToServer(new StageNetwork.PvControl(action, position));
        }
    }

    public static void togglePause() {
        control(paused ? 3 : 2, 0);
    }

    public static void seekRelative(int delta) {
        if (scene != null) {
            control(
                    4,
                    Math.max(
                            0,
                            Math.min(scene.description.durationMs() - 1, (int) timeMs() + delta)));
        }
    }

    public static int durationMs() {
        return scene == null ? 0 : scene.description.durationMs();
    }

    public static void seekAbsolute(int position) {
        if (scene != null) {
            control(4, Math.max(0, Math.min(durationMs() - 1, position)));
        }
    }

    private static void applyControl(StageNetwork.State message) {
        if (audio == null || gameMode) {
            return;
        }
        double position = message.playbackMs();
        boolean nextPaused = message.message().endsWith("paused");
        if (message.message().startsWith("sync")
                && playing
                && paused == nextPaused
                && Math.abs(timeMs() - position) < 250) {
            return;
        }
        heldMs = position;
        paused = nextPaused;
        endedNanos = 0;
        epoch++;
        audio.seek((int) heldMs, paused);
        playing = true;
    }

    public static void toggle() {
        overview = false;
        dev.flower.stevedance.client.StageCamera.cycle();
    }

    public static void cameraMode(dev.flower.stevedance.client.StageCamera.Mode mode) {
        returnToPreview();
        overview = false;
        worldView = false;
        dev.flower.stevedance.client.StageCamera.begin(mode);
    }

    @SubscribeEvent
    public static void blockOutline(
            net.minecraftforge.client.event.RenderHighlightEvent.Block event) {
        if (!PvVisibility.world("outline")) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void worldKeys(net.minecraftforge.client.event.InputEvent.Key event) {
        var mc = Minecraft.getInstance();
        if (!active()
                || !worldView
                || ambient
                || mc.screen != null
                || event.getAction() != org.lwjgl.glfw.GLFW.GLFW_PRESS) {
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_F9 && canWatchAsPlayer()) {
            mc.setScreen(new PvViewerScreen());
            return;
        }
        if (event.getKey() == org.lwjgl.glfw.GLFW.GLFW_KEY_P) {
            togglePause();
        }
    }

    @SubscribeEvent
    public static void playerViewHint(net.minecraftforge.client.event.RenderGuiEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (!playerViewing() || !active() || mc.screen != null || mc.options.hideGui) {
            return;
        }
        var g = event.getGuiGraphics();
        String text = Component.translatable("ui.stevedance.pv_viewer.player_controls").getString();
        g.pose().pushPose();
        g.pose().translate(mc.getWindow().getGuiScaledWidth() - 8,
                mc.getWindow().getGuiScaledHeight() - 30, 0);
        g.pose().scale(.6f, .6f, 1);
        g.drawString(mc.font, text, -mc.font.width(text), 0, 0xffffffff, true);
        g.pose().popPose();
    }

    public static void drag(double x, double y) {
        dev.flower.stevedance.client.StageCamera.drag(x, y);
        if (overview) {
            orbitYaw += (float) x * .5f;
            orbitPitch = Math.max(-20, Math.min(75, orbitPitch + (float) y * .3f));
        }
    }

    public static void zoom(double amount) {
        if (overview) {
            orbitDistance = Math.max(1, Math.min(60, orbitDistance - (float) amount));
        }
    }

    /**
     * 结束会话、作废加载ticket并关闭音频/演员，恢复玩家相机；保留可复用的场景。
     * notify只控制是否通知服务端；换曲资源清理由select执行。
     * <p>End the session and restore the camera; select owns song-scene replacement.</p>
     */
    public static void stop(boolean notify) {
        var mc = Minecraft.getInstance();
        boolean returnToSong =
                RhythmClientController.pvViewing
                        && !gameMode
                        && (state != null || pending != null)
                        && mc.screen instanceof PvViewerScreen;
        if (notify && (state != null || pending != null)) {
            if (gameMode) {
                RhythmNetwork.CHANNEL.sendToServer(new RhythmNetwork.Command(7));
            } else {
                StageNetwork.CHANNEL.sendToServer(new StageNetwork.NativeRequest(1));
            }
        }
        if (playerViewing()) {
            playerCamera = mc.options.getCameraType();
        }
        heldMs = timeMs();
        generation++;
        loading = false;
        SceneCrowdRenderer.clear();
        chartOnly = chartStarted = false;
        musicAcknowledged = resumeAcknowledgement = false;
        dev.flower.stevedance.client.StageCamera.reset();
        epoch++;
        PvMovieBackground.clear();
        removePartner();
        PvLyrics.clear();
        state = pending = null;
        sessionStage = null;
        ClientPlaybackRuntime.MOTION_SNAPSHOTS = java.util.Map.of();
        YsmAnimationAdapter.releaseAll();
        requested = false;
        readySent = false;
        preparedFrames = 0;
        playing = false;
        gameMode = false;
        paused = false;
        worldView = false;
        ambient = false;
        audienceModels = null;
        worldStage = false;
        status = "";
        if (audio != null) {
            audio.close();
            audio = null;
        }
        if (playerCamera != null || previous != null) {
            mc.options.setCameraType(playerCamera != null ? playerCamera : previous);
        }
        previous = playerCamera = null;
        if (mc.screen instanceof PvViewerScreen) {
            mc.setScreen(null);
        }
        if (returnToSong && mc.player != null && mc.level != null) {
            RhythmClientController.pvViewing = false;
            mc.setScreen(new SongSetupScreen());
        }
    }

    private record ChartMedia(PvAudioPlayer audio, PvMovieBackground.Data movie) {}

    private static void chartFailed(Throwable error) {
        SteveDanceMod.LOGGER.error("Chart media load failed", error);
        RhythmClientController.stop();
        RhythmClientController.error = "ui.stevedance.native_failed";
        RhythmClientController.select();
    }

    /** 纯谱面可选视频及字幕，不安装 PV 场景。 / Optional chart movies never install a PV scene. */
    private static void prepareChart(RhythmNetwork.State s) {
        var mc = Minecraft.getInstance();
        levelChanged(mc);
        stop(false);
        select(s.song());
        PvPostRenderer.clear();
        PvStageRenderer.clear();
        if (scene != null) {
            scene.close();
        }
        scene = null;
        actorMotions = new dev.flower.stevedance.core.PvBodyMotion[0];
        actorFaces = new dev.flower.stevedance.core.PvFaceMotion[0];
        motion = partnerMotion = null;
        faces = partnerFaces = null;
        loading = false;
        failed = false;
        chartOnly = true;
        gameMode = true;
        heldMs = 0;
        endedNanos = 0;
        state =
                new StageNetwork.State(
                        8,
                        mc.player.getUUID(),
                        s.song(),
                        0,
                        "chart",
                        null,
                        s.stage(),
                        s.dimension(),
                        s.yaw(),
                        0);
        OriginalUiAssets.prepare();
        SongThumbnailCache.prepare();
        int ticket = generation;
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                var root = BundledSongResources.nativeSong(s.song());
                                var movie = PvMovieBackground.load(root);
                                return new ChartMedia(
                                        PvAudioPlayer.load(
                                                BundledSongResources.resolve(
                                                        root.resolve("music.ogg"))),
                                        movie);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        },
                        LOADER)
                .whenComplete(
                        (loaded, error) ->
                                mc.execute(
                                        () -> {
                                            if (ticket != generation || !chartOnly) {
                                                if (loaded != null) {
                                                    loaded.audio.close();
                                                }
                                                return;
                                            }
                                            if (error != null) {
                                                chartFailed(error);
                                                return;
                                            }
                                            audio = loaded.audio;
                                            try {
                                                PvMovieBackground.install(loaded.movie);
                                            } catch (RuntimeException ex) {
                                                chartFailed(ex);
                                                return;
                                            }
                                            SteveDanceMod.LOGGER.info(
                                                    "Chart media ready: {}, {} ms, movie={}; no PV"
                                                            + " scene installed",
                                                    s.song(),
                                                    audio.durationMs(),
                                                    loaded.movie != null);
                                        }));
    }

    private static void tickChart() {
        var mc = Minecraft.getInstance();
        if (audio == null) {
            return;
        }
        if (!readySent) {
            try {
                if (OriginalUiAssets.ready()
                        && SongThumbnailCache.ready()
                        && PvMovieBackground.ready()) {
                    readySent = true;
                    RhythmNetwork.CHANNEL.sendToServer(new RhythmNetwork.Command(2));
                }
            } catch (RuntimeException ex) {
                chartFailed(ex);
                return;
            }
        }
        if (chartStarted && !playing && System.nanoTime() >= startNanos) {
            playing = true;
            audio.start(0, ClientPlaybackSettings.volume);
            RhythmClientController.loading = false;
            RhythmClientController.playScreen = new RhythmPlayScreen();
            mc.setScreen(RhythmClientController.playScreen);
        }
        acknowledgeMusic();
        if (mc.screen == null
                || mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
            RhythmClientController.stop();
        }
    }

    private static void acknowledgeMusic() {
        if (gameMode && playing && !paused && audio != null) {
            int ms = (int) timeMs();
            if (!musicAcknowledged && ms >= 20) {
                musicAcknowledged = true;
                RhythmNetwork.CHANNEL.sendToServer(
                        new RhythmNetwork.Command(10, "", "", ms, 0, 0, false));
            } else if (resumeAcknowledgement && ms >= resumePosition + 20) {
                resumeAcknowledgement = false;
                RhythmNetwork.CHANNEL.sendToServer(
                        new RhythmNetwork.Command(11, "", "", ms, 0, 0, false));
            }
        }
    }

    // 中文：加入包可能在新世界的第一个客户端刻之前到达。
    // EN: Join packets can arrive before the first client tick in a new world.
    private static void levelChanged(Minecraft mc) {
        if (mc.level != lastLevel) {
            generation++;
            stop(false);
            PvPostRenderer.clear();
            PvStageRenderer.clear();
            if (scene != null) {
                scene.close();
            }
            scene = null;
            actorMotions = new dev.flower.stevedance.core.PvBodyMotion[0];
            actorFaces = new dev.flower.stevedance.core.PvFaceMotion[0];
            motion = partnerMotion = null;
            faces = partnerFaces = null;
            loading = false;
            failed = false;
            heldMs = 0;
            lastLevel = mc.level;
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var mc = Minecraft.getInstance();
        levelChanged(mc);
        if (chartOnly) {
            tickChart();
            return;
        }
        if (pending != null && scene != null && !requested) {
            requested = true;
            int ticket = generation;
            StageNetwork.State request = pending;
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return PvAudioPlayer.load(
                                            PvSceneData.safe(
                                                    PvSceneData.root(request.song()), "music.ogg"));
                                } catch (Exception ex) {
                                    throw new CompletionException(ex);
                                }
                            },
                            LOADER)
                    .whenComplete(
                            (loaded, error) ->
                                    mc.execute(
                                            () -> {
                                                if (ticket != generation || pending != request) {
                                                    if (loaded != null) {
                                                        loaded.close();
                                                    }
                                                    return;
                                                }
                                                if (error != null) {
                                                    SteveDanceMod.LOGGER.error(
                                                            "Native PV music failed", error);
                                                    failed = true;
                                                    status = "ui.stevedance.native_failed";
                                                    return;
                                                }
                                                audio = loaded;
                                                SteveDanceMod.LOGGER.info(
                                                        "Native music: channels={}, rate={},"
                                                                + " sharedMixGain={}, durationMs={}",
                                                        audio.channels,
                                                        audio.rate,
                                                        audio.mixGain,
                                                        audio.durationMs()); // 中文：渲染真实
                                                // EN: Render the real
                                                // 中文：舞台与角色
                                                // EN: stage and actors
                                                // 中文：在
                                                // EN: beneath the
                                                // 中文：加载图稿
                                                // EN: loading artwork
                                                // 中文：下方
                                                // EN: before announcing
                                                // 中文：宣布就绪之前。
                                                // EN: readiness.
                                                state = request;
                                                worldStage =
                                                        !request.message().startsWith("isolated");
                                                sessionStage =
                                                        new WorldStage.Anchor(
                                                                request.stage(),
                                                                WorldStage.ANCHOR
                                                                        .get()
                                                                        .defaultBlockState()
                                                                        .setValue(
                                                                                net.minecraft.world
                                                                                        .level.block
                                                                                        .state
                                                                                        .properties
                                                                                        .BlockStateProperties
                                                                                        .HORIZONTAL_FACING,
                                                                                net.minecraft.core
                                                                                        .Direction
                                                                                        .fromYRot(
                                                                                                request
                                                                                                        .yaw())));
                                                sessionStage.setLevel(mc.level);
                                                sessionStage.configureSession(
                                                        request.song(), request.player());
                                                heldMs = 0;
                                                playing = false;
                                                preparedFrames = 0;
                                                readySent = false;
                                                if (!ambient) {
                                                    if (previous == null) {
                                                        previous = mc.options.getCameraType();
                                                    }
                                                    mc.options.setCameraType(
                                                            CameraType.THIRD_PERSON_BACK);
                                                }
                                                PerformanceCameraController.origin = origin();
                                                createPartner();
                                                OriginalUiAssets.prepare();
                                                MenuCharacterPresentation.prepare();
                                            }));
        }
        if (pending != null
                && audio != null
                && !readySent
                && preparedFrames >= 3
                && OriginalUiAssets.ready()
                && MenuCharacterPresentation.ready()
                && PvMovieBackground.ready()) {
            readySent = true;
            if (host()) {
                sendModels();
            }
            if (gameMode) {
                RhythmNetwork.CHANNEL.sendToServer(new RhythmNetwork.Command(2));
            } else {
                StageNetwork.CHANNEL.sendToServer(new StageNetwork.NativeRequest(0));
            }
            SteveDanceMod.LOGGER.info(
                    "PV preparation complete after {} rendered frames: {}", preparedFrames, song);
        }
        if (pending == null
                && state != null
                && audio != null
                && active()
                && !playing
                && System.nanoTime() >= startNanos) {
            playing = true;
            audio.start((int) heldMs, ClientPlaybackSettings.volume);
            if (paused) {
                audio.pause();
            }
            status = "";
            if (gameMode) {
                RhythmClientController.loading = false;
                RhythmClientController.playScreen = new RhythmPlayScreen();
                mc.setScreen(RhythmClientController.playScreen);
            }
        }
        acknowledgeMusic();
        if (state != null
                && playing
                && !gameMode
                && scene != null
                && timeMs() >= scene.description.durationMs()) {
            stop(true);
        }
        if (state != null
                && (!worldView && mc.screen == null
                        || mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen)) {
            stop(true);
        }
        if (pending != null && failed && !ambient) {
            stop(true);
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable("ui.stevedance.native_failed"), false);
            }
        }
    }

    @SubscribeEvent
    public static void preparedFrame(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage()
                        == net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL
                && pending != null
                && audio != null
                && active()) {
            preparedFrames++;
        }
    }

    private static void removePartner() {
        for (var actor : worldActors.values()) {
            YsmAnimationAdapter.releaseActor(actor.getUUID());
            actor.discard();
        }
        worldActors.clear();
        partner = null;
    }

    private static void createPartner() {
        removePartner();
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        var source = mc.level.getPlayerByUUID(state.player());
        createActor(0, source == null ? mc.player : source);
        for (int slot = 1; slot < actorMotions.length; slot++) {
            if (actorMotions[slot] == null) {
                continue;
            }
            createActor(slot, performer() == null ? mc.player : performer());
        }
    }

    private static void createActor(int slot, net.minecraft.world.entity.player.Player source) {
        var mc = Minecraft.getInstance();
        var id = java.util.UUID.randomUUID();
        var profile = new com.mojang.authlib.GameProfile(id, source.getGameProfile().getName());
        profile.getProperties().putAll(source.getGameProfile().getProperties());
        var actor = new net.minecraft.client.player.RemotePlayer(mc.level, profile);
        actor.setId(-31000 - slot);
        actor.getInventory().replaceWith(source.getInventory());
        var o = origin();
        actor.setPos(o.x, o.y, o.z);
        actor.setNoGravity(true);
        actor.setInvulnerable(true);
        mc.level.addPlayer(actor.getId(), actor);
        PvPerformerRendering.inheritModel(source, actor);
        worldActors.put(slot, actor);
        if (slot == 1) {
            partner = actor;
        }
        SteveDanceMod.LOGGER.info("PV world actor slot {} installed: {}", slot, id);
    }

    public static int actor(java.util.UUID id) {
        for (var entry : worldActors.entrySet()) {
            if (entry.getValue().getUUID().equals(id)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void performer(net.minecraftforge.client.event.RenderPlayerEvent.Pre event) {
        if (!active()) {
            return;
        }
        if (SceneCrowdRenderer.contains(event.getEntity().getUUID())) return;
        int actor = actor(event.getEntity().getUUID());
        if (event.getEntity() == Minecraft.getInstance().player && cameraActive()
                || !PvVisibility.world("entities") && actor < 0
                || actor >= 0
                        && (!dev.flower.stevedance.client.PvVisibility.actor(actor)
                                || !scene.performerVisible(timeMs(), actor))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void isolateWorldCreatures(
            net.minecraftforge.client.event.RenderLivingEvent.Pre<?, ?> event) {
        if (SceneCrowdRenderer.contains(event.getEntity().getUUID())) return;
        if (cameraActive() && event.getEntity() == Minecraft.getInstance().player
                || !PvVisibility.world("entities") && actor(event.getEntity().getUUID()) < 0
                || active()
                        && actor(event.getEntity().getUUID()) >= 0
                        && !dev.flower.stevedance.client.PvVisibility.actor(
                                actor(event.getEntity().getUUID()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void performerName(net.minecraftforge.client.event.RenderNameTagEvent event) {
        if (active() && actor(event.getEntity().getUUID()) >= 0) {
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
        }
    }
}
