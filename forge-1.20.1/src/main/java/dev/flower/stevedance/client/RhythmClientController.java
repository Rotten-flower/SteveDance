package dev.flower.stevedance.client;

import dev.flower.stevedance.PlayerScoreStore;
import dev.flower.stevedance.RhythmChartLibrary;
import dev.flower.stevedance.RhythmNetwork;
import dev.flower.stevedance.RhythmSessionService;
import dev.flower.stevedance.SongSelectionCatalog;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.RhythmChart;
import dev.flower.stevedance.core.RhythmInputFeedback;
import dev.flower.stevedance.core.RhythmJudgeSession;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端选曲、输入反馈和谱面界面状态；向服务端提交操作，成绩与完成结果以服务端回包为准。
 * <p>Client song/input/chart UI state; submit commands to the server and consume authoritative results.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class RhythmClientController {
    public static String song = "two_breaths_f2nd", difficulty = "hard", error = "";
    public static RhythmJudgeSession session;
    public static RhythmSessionService.Completion completion;
    public static RhythmSessionService.Records records;
    public static final Map<String, PlayerScoreStore.Row> personal = new HashMap<>();

    public static void choose(String value) {
        difficulty = value;
        var e = RhythmChartLibrary.get(song, value);
        if (e != null) {
            RhythmNetwork.CHANNEL.sendToServer(
                    new RhythmNetwork.Command(9, song, value, e.digest(), 0, 0, 0, false));
        }
    }

    private static boolean settingsAfterPause, pausePending;

    public static void pauseForSettings() {
        if (session == null
                || paused
                || loading
                || completion != null
                || !PvPlaybackController.clockReady()
                || timeMs() < 0) {
            return;
        }
        settingsAfterPause = true;
        pause();
    }

    public static RhythmPlayScreen playScreen;
    public static boolean paused, loading, waiting, pvViewing;
    public static RhythmJudgeSession.Result official;
    private static int sequence, soundSerial, rushSoundSerial;
    private static long clockNanos;
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
    private static int sessionOffset;
    private static final Map<String, Integer> loops = new HashMap<>();
    private static List<SongSelectionCatalog.Song> serverCatalog;

    public static int timeMs() {
        return PvPlaybackController.playbackMs() + sessionOffset;
    }

    private static boolean selectionPending;
    private static net.minecraft.client.gui.screens.Screen selectionSource;
    private static net.minecraft.client.multiplayer.ClientLevel selectionLevel;

    public static void select() {
        MenuCharacterPresentation.stop();
        var mc = Minecraft.getInstance();
        // 保持当前画面，素材准备完成后直接进入选曲。 / Keep the current view until the menu is ready.
        selectionPending = true;
        selectionSource = mc.screen;
        selectionLevel = mc.level;
        OriginalUiAssets.prepare();
        SongThumbnailCache.prepare();
        MenuCharacterPresentation.prepare();
        RhythmSoundPlayer.prepare();
        RhythmChartLibrary.reload();
        SongSelectionCatalog.reload();
        SongCredits.reload();
        if (serverCatalog != null) {
            SongSelectionCatalog.restrictToServer(serverCatalog);
        }
        RhythmSettingsScreen.load();
        if (SongSelectionCatalog.get(song) == null && !SongSelectionCatalog.songs().isEmpty()) {
            song = SongSelectionCatalog.songs().get(0).id();
        }
        choose(difficulty);
        finishSelectionPreparation();
    }

    private static void finishSelectionPreparation() {
        if (!selectionPending) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (mc.level != selectionLevel || mc.screen != selectionSource) {
            selectionPending = false;
        } else if (OriginalUiAssets.ready() && MenuCharacterPresentation.ready() && SongThumbnailCache.ready()
                || !OriginalUiAssets.error.isEmpty() || !MenuCharacterPresentation.error.isEmpty()) {
            selectionPending = false;
            mc.setScreen(new SongSelectionScreen());
        }
        if (!selectionPending) {
            selectionSource = null;
            selectionLevel = null;
        }
    }

    public static void openSelection() {
        RhythmNetwork.CHANNEL.sendToServer(new RhythmNetwork.Command(0));
    }

    public static void chooseSong(String id) {
        if (SongSelectionCatalog.get(id) == null) {
            return;
        }
        song = id;
        var selected = SongSelectionCatalog.get(id);
        if (!selected.difficulties().contains(difficulty)) {
            difficulty =
                    List.of("normal", "easy", "hard", "extreme").stream()
                            .filter(selected.difficulties()::contains)
                            .findFirst()
                            .orElse(difficulty);
        }
        choose(difficulty);
    }

    public static void details() {
        Minecraft.getInstance().setScreen(new SongSetupScreen());
    }

    public static void choosePlaybackSpace() {
        ClientPlaybackSettings.load();
        if (ClientPlaybackSettings.chartOnly) {
            ClientPlaybackSettings.chartOnly = false;
            ClientPlaybackSettings.worldStage = false;
        } else if (ClientPlaybackSettings.worldStage) {
            ClientPlaybackSettings.worldStage = false;
            ClientPlaybackSettings.chartOnly = true;
        } else {
            ClientPlaybackSettings.worldStage = true;
        }
        ClientPlaybackSettings.save();
    }

    public static void requestPv() {
        var selected = SongSelectionCatalog.get(song);
        if (selected == null || !selected.pvAvailable()) {
            error = "ui.stevedance.f2_song_unavailable";
            return;
        }
        ClientPlaybackSettings.load();
        error = "";
        LoadingArtwork.prepare();
        MenuCharacterPresentation.stop();
        pvViewing = true;
        RhythmNetwork.CHANNEL.sendToServer(
                new RhythmNetwork.Command(
                        12, song, "", "", ClientPlaybackSettings.worldStage ? 1 : 0, 0, 0, false));
    }

    public static void request() {
        var e = RhythmChartLibrary.get(song, difficulty);
        if (e == null) {
            error = "ui.stevedance.f2_chart_missing";
            return;
        }
        ClientPlaybackSettings.load();
        error = "";
        LoadingArtwork.prepare();
        MenuCharacterPresentation.stop();
        RhythmNetwork.CHANNEL.sendToServer(
                new RhythmNetwork.Command(
                        1,
                        song,
                        difficulty,
                        e.digest(),
                        ClientPlaybackSettings.offsetMs,
                        0,
                        ClientPlaybackSettings.effectiveChartOnly() ? 1 : 0,
                        !ClientPlaybackSettings.effectiveChartOnly() && ClientPlaybackSettings.worldStage));
    }

    public static void records() {
        RhythmNetwork.CHANNEL.sendToServer(
                new RhythmNetwork.Command(8, song, difficulty, "", 0, 0, 0, false));
    }

    public static void receive(RhythmNetwork.State s) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        switch (s.kind()) {
            case 0 -> {
                serverCatalog =
                        s.json().isEmpty()
                                ? List.of()
                                : Arrays.asList(
                                        RhythmChartLibrary.JSON.fromJson(
                                                s.json(), SongSelectionCatalog.Song[].class));
                select();
            }
            case 1 -> {
                RhythmSoundPlayer.stopAll();
                loops.clear();
                var e = RhythmChartLibrary.get(s.song(), s.difficulty());
                if (e == null || !e.digest().equals(s.json())) {
                    stop();
                    error = "ui.stevedance.f2_chart_mismatch";
                    select();
                    return;
                }
                song = s.song();
                difficulty = s.difficulty();
                sessionOffset = ClientPlaybackSettings.offsetMs;
                session = new RhythmJudgeSession(e.chart());
                completion = null;
                official = null;
                sequence = soundSerial = rushSoundSerial = 0;
                settingsAfterPause = pausePending = false;
                paused = false;
                loading = true;
                waiting = false;
                PvPlaybackController.prepareGame(s);
                PerformanceAudioBus.beginRhythm();
                mc.setScreen(new SongLoadingScreen());
            }
            case 2 -> {
                PvPlaybackController.startGame(s);
                clockNanos = 0;
            }
            case 3 -> {
                long transitionBegan = System.nanoTime();
                PerformanceAudioBus.endRhythm();
                RhythmSoundPlayer.stopAll();
                loops.clear();
                song = s.song();
                difficulty = s.difficulty();
                completion =
                        RhythmChartLibrary.JSON.fromJson(
                                s.json(), RhythmSessionService.Completion.class);
                PvPlaybackController.stop(false);
                paused = true;
                waiting = false;
                mc.setScreen(new RhythmResultScreen());
                SteveDanceMod.LOGGER.info(
                        "Result transition completed in {} ms",
                        (System.nanoTime() - transitionBegan) / 1e6);
            }
            case 4 -> {
                PerformanceAudioBus.endRhythm();
                RhythmSoundPlayer.stopAll();
                loops.clear();
                PvPlaybackController.stop(false);
                session = null;
                paused = loading = waiting = false;
                if (mc.player.isAlive()) {
                    select();
                }
            }
            case 5 -> {
                PerformanceAudioBus.endRhythm();
                error = s.error();
                pvViewing = false;
                if (loading) {
                    PvPlaybackController.stop(false);
                    session = null;
                    loading = false;
                }
                select();
            }
            case 6 -> {
                pausePending = false;
                RhythmSoundPlayer.stopAll();
                paused = true;
                PvPlaybackController.pauseGame();
                var pauseScreen = new RhythmPauseScreen();
                mc.setScreen(
                        settingsAfterPause ? new RhythmSettingsScreen(pauseScreen) : pauseScreen);
                settingsAfterPause = false;
            }
            case 7 -> {
                pausePending = false;
                paused = false;
                PvPlaybackController.resumeGame();
                if (playScreen != null) {
                    playScreen.resumeInputs();
                    mc.setScreen(playScreen);
                } else {
                    mc.setScreen(new RhythmPlayScreen());
                }
            }
            case 8 -> {
                song = s.song();
                records =
                        RhythmChartLibrary.JSON.fromJson(
                                s.json(), RhythmSessionService.Records.class);
                if (!(mc.screen instanceof PlayerRecordsScreen)) {
                    mc.setScreen(new PlayerRecordsScreen());
                }
            }
            case 9 -> {
                var snapshot =
                        RhythmChartLibrary.JSON.fromJson(
                                s.json(), RhythmSessionService.Snapshot.class);
                official = snapshot.result();
            }
            case 10 -> {
                var data =
                        RhythmChartLibrary.JSON.fromJson(
                                s.json(), RhythmSessionService.Personal.class);
                if (data.row() == null) {
                    personal.remove(data.song() + "/" + data.difficulty());
                } else {
                    personal.put(data.song() + "/" + data.difficulty(), data.row());
                }
            }
            default -> {}
        }
    }

    public static void input(int bit, boolean pressed) {
        if (session == null
                || paused
                || loading
                || completion != null
                || !PvPlaybackController.clockReady()
                || timeMs() < 0) {
            return;
        }
        int ms = timeMs();
        var outcome = session.inputBatch(bit, pressed, ms);
        if (outcome == RhythmJudgeSession.InputResult.IGNORED) {
            return;
        }
        RhythmNetwork.CHANNEL.sendToServer(
                new RhythmNetwork.Command(3, "", "", ms, ++sequence, bit, pressed));
        if (pressed) {
            pressFeedback(
                    bit,
                    outcome,
                    session.hitsSince(soundSerial).stream()
                            .anyMatch(RhythmJudgeSession.Hit::wrong));
        }
        sounds();
        // 中文：空按压和中断的按住无法伪造成功的长按释放。
        // EN: Empty presses and broken holds cannot fabricate a successful long-note release.
    }

    private static void pressFeedback(
            int bit, RhythmJudgeSession.InputResult outcome, boolean wrong) {
        RhythmSoundPlayer.play(RhythmInputFeedback.pressSample(bit, outcome, wrong));
    }

    public static void advance(int ms) {
        if (session == null || paused || loading || completion != null || ms < 0) {
            return;
        }
        session.advance(ms);
        sounds();
    }

    private static void sounds() {
        if (session == null) {
            return;
        }
        for (var h : session.hitsSince(soundSerial)) {
            soundSerial = Math.max(soundSerial, h.serial());
            var n = session.chart.notes().get(h.note());
            String name = RhythmInputFeedback.sample(n, h);
            String token =
                    n.kind() == RhythmChart.Kind.HOLD
                            ? "hold:" + (n.release() ? n.pair() : h.note())
                            : "link:" + n.chain();
            if (n.kind() == RhythmChart.Kind.HOLD && n.release()
                    || n.kind() == RhythmChart.Kind.LINK
                            && (n.linkEnd() || h.grade().ordinal() > 1 || h.wrong())) {
                loops.remove(token);
                RhythmSoundPlayer.stop(token);
            }
            if (RhythmInputFeedback.loop(n, h)) {
                int endpoint = n.kind() == RhythmChart.Kind.HOLD ? h.note() : h.note() + 1;
                loops.put(token, endpoint);
                RhythmSoundPlayer.sustain(token, name);
            } else {
                RhythmSoundPlayer.play(name);
            }
        }
        for (var event : session.rushEventsSince(rushSoundSerial)) {
            rushSoundSerial = event.serial();
            String token = "rush:" + event.note();
            if (event.phase() == RhythmJudgeSession.RushPhase.START) {
                loops.put(token, event.note());
                RhythmSoundPlayer.sustain(token, "rush_on");
            } else if (event.phase() == RhythmJudgeSession.RushPhase.END) {
                loops.remove(token);
                RhythmSoundPlayer.stop(token);
                RhythmSoundPlayer.play(event.complete() ? "rush_off" : "tap");
            }
        }
        var iterator = loops.entrySet().iterator();
        while (iterator.hasNext()) {
            var e = iterator.next();
            boolean valid =
                    !session.finished()
                            && !session.failed()
                            && (e.getKey().startsWith("rush:")
                                    ? session.rushing(e.getValue(), session.timeMs())
                                    : e.getKey().startsWith("hold:")
                                            ? session.holding(e.getValue())
                                                    && session.timeMs()
                                                            < session.chart
                                                                    .notes()
                                                                    .get(e.getValue())
                                                                    .endMs()
                                            : session.linkSustaining(e.getValue()));
            if (!valid) {
                RhythmSoundPlayer.stop(e.getKey());
                iterator.remove();
            } else {
                RhythmSoundPlayer.sustain(
                        e.getKey(),
                        e.getKey().startsWith("rush:")
                                ? "rush_on"
                                : e.getKey().startsWith("hold:") ? "hold_on" : "link");
            }
        }
    }

    public static void pause() {
        if (session != null
                && !paused
                && !pausePending
                && PvPlaybackController.clockReady()
                && timeMs() >= 0) {
            pausePending = true;
            RhythmNetwork.CHANNEL.sendToServer(new RhythmNetwork.Command(5));
        }
    }

    public static void resume() {
        RhythmNetwork.CHANNEL.sendToServer(new RhythmNetwork.Command(6));
    }

    public static void stop() {
        PerformanceAudioBus.endRhythm();
        MenuCharacterPresentation.stop();
        RhythmSoundPlayer.stopAll();
        loops.clear();
        RhythmNetwork.CHANNEL.sendToServer(new RhythmNetwork.Command(7));
        PvPlaybackController.stop(false);
        session = null;
        completion = null;
        settingsAfterPause = pausePending = false;
        paused = loading = waiting = false;
    }

    public static void retry() {
        stop();
        request();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        finishSelectionPreparation();
        var mc = Minecraft.getInstance();
        if (lastLevel != mc.level) {
            PerformanceAudioBus.endRhythm();
            RhythmSoundPlayer.stopAll();
            loops.clear();
            session = null;
            completion = null;
            settingsAfterPause = pausePending = false;
            paused = loading = waiting = false;
            serverCatalog = null;
            lastLevel = mc.level;
        }
        if (session != null
                && !paused
                && !loading
                && completion == null
                && mc.screen instanceof RhythmPlayScreen) {
            int ms = timeMs();
            if (ms >= 0) {
                advance(ms);
                boolean justFinished = !waiting && session.finished();
                waiting = session.finished();
                long now = System.nanoTime();
                if (justFinished || now - clockNanos >= 250000000) {
                    clockNanos = now;
                    RhythmNetwork.CHANNEL.sendToServer(
                            new RhythmNetwork.Command(4, "", "", ms, 0, 0, false));
                }
            }
        }
    }
}
