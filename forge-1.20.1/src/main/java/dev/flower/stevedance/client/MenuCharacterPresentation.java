package dev.flower.stevedance.client;

import dev.flower.stevedance.RhythmChartLibrary;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.PlaybackClock;
import dev.flower.stevedance.core.PvBodyMotion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 窗口内独立模型播放原作菜单/结算动作。
 * <p>Original menu/result motion on a window-local actor.</p>
 */
public final class MenuCharacterPresentation {
    private static final ExecutorService LOADER =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "SteveDance menu motion loader");
                        t.setDaemon(true);
                        return t;
                    });
    private static volatile Map<String, PvBodyMotion> clips = Map.of();
    private static boolean loading;
    private static String mode = "";
    private static long began, epoch;
    private static net.minecraft.client.player.RemotePlayer actor;
    private static final Map<Integer, net.minecraft.client.player.RemotePlayer> actors =
            new LinkedHashMap<>();
    private static String song = "";
    private static final List<String> IDLES = List.of("ntrl", "cool", "cute", "sexy", "uniq");

    private static void syncActors() {
        var mc = Minecraft.getInstance();
        if (mode.isEmpty() || mc.player == null || mc.level == null) {
            return;
        }
        var selected = dev.flower.stevedance.SongSelectionCatalog.get(song);
        var slots =
                selected != null && !selected.pvAvailable()
                        ? List.of(0)
                        : PerformerModelSelection.slots(song).actors();
        if (slots.isEmpty()) {
            slots = List.of(0);
        }
        if (actors.keySet().equals(new LinkedHashSet<>(slots))) {
            return;
        }
        for (var old : actors.values()) {
            PerformerModelSelection.releasePreview(old);
            YsmAnimationAdapter.releaseActor(old.getUUID());
        }
        actors.clear();
        for (int slot : slots) {
            var profile =
                    new com.mojang.authlib.GameProfile(
                            UUID.randomUUID(), mc.player.getGameProfile().getName());
            profile.getProperties().putAll(mc.player.getGameProfile().getProperties());
            var model = new net.minecraft.client.player.RemotePlayer(mc.level, profile);
            model.setId(-30999 - slot);
            model.setPos(0, 0, 0);
            model.setNoGravity(true);
            PvPerformerRendering.inheritModel(mc.player, model);
            PerformerModelSelection.preview(model, PerformerModelSelection.choice(song, slot));
            actors.put(slot, model);
        }
        actor = actors.values().iterator().next();
        epoch++;
    }

    public static boolean contains(net.minecraft.world.entity.Entity entity) {
        return actors.containsValue(entity);
    }

    public static int count() {
        syncActors();
        return actors.size();
    }

    public static String error = "";

    /** Draw only inside the menu canvas; the ordinary world camera remains untouched.
     * 仅在菜单画布绘制，保留窗口外的游戏视角。 */
    public static void draw(GuiGraphics g) {
        if (!active()) {
            return;
        }
        syncActors();
        double seconds = (System.nanoTime() - began) / 1e9;
        OriginalUiEffects.backdrop(g, 0xffeef4f6, 1280, 728);
        if (mode.equals("select")) {
            OriginalUiAssets.loop(g, "song_setup", "bg", seconds);
        } else {
            OriginalUiAssets.loop(g, "results", "bg_" + mode, seconds);
        }
        // 中文：完整角色适配窗口右侧面板，不裁切全屏立绘。
        // EN: A full figure fits the window's right panel, rather than cropping a full-screen
        // portrait.
        g.flush();
        int count = actors.size(), index = 0;
        for (var model : actors.values()) {
            int x = modelX(index, count);
            int size = modelSize(count);
            net.minecraft.client.gui.screens.inventory.InventoryScreen
                    .renderEntityInInventoryFollowsMouse(g, x, 720, size, 0, 0, model);
            index++;
        }
        g.flush();
        // 模型绘制结束后清除窗口内深度，避免后续文字被模型遮挡。
        // 中文：清除受当前菜单裁剪框限制，框外世界像素保持不变。
        // EN: The active menu scissor confines this clear; world pixels outside remain intact.
        com.mojang.blaze3d.systems.RenderSystem.clear(
                org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
    }

    private static int modelX(int index, int count) {
        return count == 1 ? 870 : Math.round(550 + (index + .5f) * 680 / count);
    }

    private static int modelSize(int count) {
        return count == 1 ? 200 : Math.min(200, 600 / count);
    }

    /**
     * 原作编号牌的底框、横线和图标保持原贴图，只有文字换为MC字体。
     * <p>Retains the original number-plate frame, rules and icons; only text uses the Minecraft font.</p>
     */
    public static void drawCredits(GuiGraphics g) {
        if (!active() || !mode.equals("select")) {
            return;
        }
        int count = actors.size(), index = 0;
        Map<String, Integer> numbers = new HashMap<>();
        for (int slot : actors.keySet()) {
            var credit = PerformerModelSelection.credit(song, slot);
            boolean guest = credit.role().equals("GUEST");
            String role = guest ? "GUEST" : "VOCAL";
            int number = numbers.merge(role, 1, Integer::sum);
            float size = Math.min(1, 620f / count / 200f);
            float left = modelX(index++, count) - 98 * size,
                    top = 720 - modelSize(count) * 2.1f - 68 * size;
            String prefix = "song_setup_menu_frame_character_" + (guest ? "guest" : "vocal");
            g.pose().pushPose();
            g.pose().translate(left, top, 0);
            g.pose().scale(size, size, 1);
            OriginalUiAssets.sprite(g, "song_setup", prefix + "02", 0, 32, 1);
            OriginalUiAssets.sprite(g, "song_setup", prefix + "01", 144, 0, 1);
            String character = credit.character();
            String icon =
                    Set.of("MIK", "RIN", "LEN", "LUK", "MEI", "KAI").contains(character)
                            ? "common_icons_character_" + switch (character) {
                                case "MIK" -> "miku";
                                case "LUK" -> "luka";
                                case "MEI" -> "meiko";
                                case "KAI" -> "kaito";
                                default -> character.toLowerCase(Locale.ROOT);
                            }
                            : "common_icons_icon_character_unknown";
            OriginalUiAssets.spriteSized(g, "common_icons", icon, 157, 14, 28, 32);
            String label = String.format(Locale.ROOT, "%s %02d", role, number);
            var font = Minecraft.getInstance().font;
            float textScale = Math.min(2, 120f / Math.max(1, font.width(label)));
            g.pose().translate(78 - font.width(label) * textScale / 2, 17, 0);
            g.pose().scale(textScale, textScale, 1);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) {
                        g.drawString(font, label, dx * .5f, dy * .5f, 0xfff8ffff, false);
                    }
                }
            }
            g.drawString(font, label, 0, 0, 0xff101818, false);
            g.pose().popPose();
        }
    }

    public static boolean ready() {
        return !loading && !clips.isEmpty();
    }

    public static void prepare() {
        if (loading || !clips.isEmpty()) {
            return;
        }
        loading = true;
        CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                Map<String, PvBodyMotion> loaded = new HashMap<>();
                                for (String name :
                                        List.of(
                                                "select",
                                                "perfect",
                                                "excellent",
                                                "great",
                                                "standard",
                                                "cheap",
                                                "misstake")) {
                                    loaded.put(
                                            name,
                                            new PvBodyMotion(
                                                    RhythmChartLibrary.root()
                                                            .resolve("presentation")
                                                            .resolve(name)));
                                }
                                for (String gender : List.of("f", "m")) {
                                    for (String style : IDLES) {
                                        String name = "x-start_" + gender + "_" + style + "_idle";
                                        loaded.put(
                                                name,
                                                new PvBodyMotion(
                                                        RhythmChartLibrary.root()
                                                                .resolve("presentation")
                                                                .resolve(name)));
                                    }
                                }
                                return Map.copyOf(loaded);
                            } catch (Exception e) {
                                throw new CompletionException(e);
                            }
                        },
                        LOADER)
                .whenComplete(
                        (loaded, failure) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    loading = false;
                                                    if (failure != null) {
                                                        error =
                                                                "ui.stevedance.f2_presentation_failed";
                                                        SteveDanceMod.LOGGER.error(
                                                                "Original menu/result motion unavailable",
                                                                failure);
                                                        return;
                                                    }
                                                    try {
                                                        for (var clip : loaded.values()) {
                                                            YsmAnimationAdapter.prepareNative(clip);
                                                        }
                                                        clips = loaded;
                                                        error = "";
                                                    } catch (Exception e) {
                                                        error =
                                                                "ui.stevedance.f2_presentation_failed";
                                                        SteveDanceMod.LOGGER.error(
                                                                "Menu motion retarget failed", e);
                                                    }
                                                }));
    }

    public static void begin(String value) {
        prepare();
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (mode.equals(value)
                && song.equals(RhythmClientController.song)
                && actor != null
                && actor.level() == mc.level) {
            syncActors();
            return;
        }
        stop();
        mode = value;
        song = RhythmClientController.song;
        began = System.nanoTime();
        syncActors();
        epoch++;
    }

    public static net.minecraft.client.player.RemotePlayer actor() {
        return actor;
    }

    public static boolean active() {
        var mc = Minecraft.getInstance();
        return actor != null
                && mc.level != null
                && actor.level() == mc.level
                && (mc.screen instanceof SongSetupScreen
                        || mc.screen instanceof RhythmResultScreen);
    }

    public static String mode() {
        return mode;
    }

    public static Map<UUID, ClientPlaybackRuntime.MotionSnapshot> snapshots() {
        if (!active()) {
            return Map.of();
        }
        syncActors();
        Map<UUID, ClientPlaybackRuntime.MotionSnapshot> result = new HashMap<>();
        long now = System.nanoTime();
        double elapsed = (now - began) / 1e9;
        int index = 0;
        for (var model : actors.values()) {
            String name = mode;
            if (mode.equals("select") && actors.size() > 1) {
                name =
                        "x-start_"
                                + (index / 5 % 2 == 0 ? "f" : "m")
                                + "_"
                                + IDLES.get(index % 5)
                                + "_idle";
            }
            index++;
            PvBodyMotion clip = clips.get(name);
            if (clip == null) {
                continue;
            }
            double duration = (clip.frames - 1) / 60.0,
                    seconds =
                            mode.equals("select")
                                    ? elapsed % duration
                                    : Math.min(duration, elapsed);
            model.yBodyRot = model.yBodyRotO = model.yHeadRot = model.yHeadRotO = 0;
            model.setYRot(0);
            model.yRotO = 0;
            result.put(
                    model.getUUID(),
                    new ClientPlaybackRuntime.MotionSnapshot(
                            null,
                            clip,
                            new PlaybackClock(seconds, now, true, epoch),
                            duration,
                            null));
        }
        return Map.copyOf(result);
    }

    public static void stop() {
        for (var model : actors.values()) {
            PerformerModelSelection.releasePreview(model);
            YsmAnimationAdapter.releaseActor(model.getUUID());
        }
        actors.clear();
        actor = null;
        mode = "";
        song = "";
        epoch++;
    }
}
