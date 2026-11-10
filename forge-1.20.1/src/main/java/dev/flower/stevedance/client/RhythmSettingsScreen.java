package dev.flower.stevedance.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.flower.stevedance.BundledSongResources;
import dev.flower.stevedance.RhythmChartLibrary;
import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RhythmSettingsScreen extends RhythmScreen
        implements PvPlaybackController.WorldView {
    private static final int[] DEFAULT_KEYS = {
        GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_F, GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_SPACE
    };
    public static final int[] KEYS = DEFAULT_KEYS.clone();
    public static int buttonSound = 1, scratchSound = 1;
    public static float soundVolume = 1;
    private static final List<String> VOICE_PACKS =
            List.of("auto", "mik", "rin", "len", "luk", "mei", "kai", "ner", "hak", "sak", "off");
    public static String voicePack = "auto";

    public static void cycleVoicePack(int delta) {
        load();
        voicePack =
                VOICE_PACKS.get(
                        Math.floorMod(VOICE_PACKS.indexOf(voicePack) + delta, VOICE_PACKS.size()));
        save();
    }

    public static Component voicePackLabel() {
        load();
        return Component.translatable(
                "ui.stevedance.voice_pack",
                Component.translatable("ui.stevedance.voice_pack." + voicePack));
    }

    public static String padIcons = "auto";
    public static float padSensitivity = .65f;
    private static boolean loaded, readOnly;
    private static JsonObject preserved = new JsonObject();

    private enum Page {
        GENERAL,
        SOUND,
        KEYS,
        PAD
    }

    private Page page;
    private int binding = -1, selected;
    private long previewAt;
    private final Screen parent;

    public RhythmSettingsScreen(Screen parent) {
        this(parent, false);
    }

    public RhythmSettingsScreen(Screen parent, boolean soundPage) {
        super("ui.stevedance.settings");
        this.parent = parent;
        page = soundPage ? Page.SOUND : Page.GENERAL;
        load();
        OriginalUiAssets.prepare();
        RhythmSoundPlayer.prepare();
    }

    public static String keyLabel(int i) {
        load();
        return com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                .getOrCreate(KEYS[i])
                .getDisplayName()
                .getString();
    }

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = BundledSongResources.settings().resolve("bindings.json");
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            if (Files.size(path) > 32768) {
                throw new java.io.IOException("Bindings budget");
            }
            JsonObject j = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            preserved = j;
            int schema = option(j, "schema", 0, 0, 1000);
            if (schema != 1 && schema != 2) {
                readOnly = true;
                throw new java.io.IOException("Unsupported bindings schema " + schema);
            }
            try {
                if (j.has("padIcons")) {
                    String icons = j.get("padIcons").getAsString();
                    if (Set.of("auto", "ps", "xbox").contains(icons)) {
                        padIcons = icons;
                    }
                }
            } catch (RuntimeException ex) {
                SteveDanceMod.LOGGER.warn("Invalid controller icon option; using automatic icons");
            }
            if (j.has("voicePack") && j.get("voicePack").isJsonPrimitive()) {
                String pack = j.get("voicePack").getAsString();
                if (VOICE_PACKS.contains(pack)) {
                    voicePack = pack;
                }
            }
            padSensitivity = option(j, "padSensitivity", 65, 45, 90) / 100f;
            buttonSound = option(j, "buttonSound", 1, 0, 5);
            scratchSound = option(j, "scratchSound", 1, 0, 5);
            soundVolume = option(j, "soundVolume", 100, 0, 100) / 100f;
            JsonArray a = j.getAsJsonArray("keys");
            int expected = schema == 1 ? 10 : 5;
            if (a == null || a.size() != expected) {
                throw new java.io.IOException("Bindings length");
            }
            int[] next = KEYS.clone();
            for (int i = 0; i < (schema == 1 ? 4 : 5); i++) {
                int key = a.get(i).getAsInt();
                if (key < 32
                        || key > 348
                        || key == GLFW.GLFW_KEY_ESCAPE
                        || dev.flower.stevedance.client.PerformanceControls.reserved(key)) {
                    throw new java.io.IOException("Invalid binding at " + i);
                }
                next[i] = key;
            }
            // 中文：Schema 1使用E/Q和单独的双快捷键。保留四个有效的自定义键和
            // EN: Schema 1 used E/Q and a separate double shortcut. Preserve four valid custom keys
            // and
            // 中文：声音选项。
            // EN: sound options.
            if (Arrays.stream(next).distinct().count() != 5) {
                throw new java.io.IOException("Duplicate five-key bindings");
            }
            System.arraycopy(next, 0, KEYS, 0, 5);
            if (schema == 1) {
                Files.copy(
                        path,
                        path.resolveSibling("bindings.schema1.json"),
                        StandardCopyOption.REPLACE_EXISTING);
                save();
            }
        } catch (Exception e) {
            SteveDanceMod.LOGGER.warn(
                    "Bindings cannot be read; valid sound options and source retained", e);
        }
    }

    private static int option(JsonObject j, String key, int fallback, int min, int max) {
        try {
            return j.has(key) ? Math.max(min, Math.min(max, j.get(key).getAsInt())) : fallback;
        } catch (RuntimeException ex) {
            SteveDanceMod.LOGGER.warn("Invalid F 2nd option {}", key);
            return fallback;
        }
    }

    static void save() {
        if (readOnly) {
            return;
        }
        JsonObject j = preserved.deepCopy();
        j.addProperty("schema", 2);
        j.addProperty("voicePack", voicePack);
        j.addProperty("padIcons", padIcons);
        j.addProperty("padSensitivity", Math.round(padSensitivity * 100));
        JsonArray a = new JsonArray();
        for (int k : KEYS) {
            a.add(k);
        }
        j.add("keys", a);
        j.addProperty("buttonSound", buttonSound);
        j.addProperty("scratchSound", scratchSound);
        j.addProperty("soundVolume", Math.round(soundVolume * 100));
        try {
            Files.createDirectories(dev.flower.stevedance.BundledSongResources.settings());
            Path p = dev.flower.stevedance.BundledSongResources.settings().resolve("bindings.json"),
                    tmp = p.resolveSibling("bindings.json.tmp");
            Files.writeString(tmp, RhythmChartLibrary.JSON.toJson(j));
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            preserved = j;
        } catch (Exception e) {
            SteveDanceMod.LOGGER.error("F 2nd bindings save failed", e);
        }
    }

    public static String soundLabel(int sound) {
        return sound == 0
                ? Component.translatable("options.off").getString()
                : sound == 1
                        ? tr("ui.stevedance.f2_default")
                        : Component.translatable("ui.stevedance.f2_sound_variant", sound)
                                .getString();
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    @Override
    boolean compactMenu() {
        return true;
    }

    @Override
    boolean nativeControls() {
        return true;
    }

    boolean capturingKey() {
        return binding >= 0;
    }

    private int count() {
        return page == Page.GENERAL || page == Page.KEYS ? 6 : 5;
    }

    private String label(int i) {
        if (page == Page.PAD) {
            return tr(
                    "ui.stevedance.pad."
                            + new String[] {"icons", "device", "sensitivity", "guide", "reset"}[i]);
        }
        if (page == Page.KEYS && i == 5) {
            return tr("ui.stevedance.keys.reset");
        }
        if (page == Page.KEYS) {
            return tr(
                    "ui.stevedance.f2_bind_"
                            + new String[] {"triangle", "circle", "cross", "square", "stars"}[i]);
        }
        return tr(
                (page == Page.SOUND
                                ? new String[] {
                                    "ui.stevedance.f2_button_sound",
                                    "ui.stevedance.f2_scratch_sound",
                                    "ui.stevedance.f2_sound_volume",
                                    "ui.stevedance.f2_preview_sound",
                                    "ui.stevedance.f2_options"
                                }
                                : new String[] {
                                    "ui.stevedance.f2_button_sound",
                                    "ui.stevedance.f2_keyboard",
                                    "ui.stevedance.f2_music_volume",
                                    "ui.stevedance.calibration.setting",
                                    "ui.stevedance.f2_guide",
                                    "ui.stevedance.pad.title"
                                })
                        [i]);
    }

    private String value(int i) {
        if (page == Page.PAD) {
            return switch (i) {
                case 0 -> tr("ui.stevedance.pad." + padIcons);
                case 1 ->
                        GamepadInput.connected()
                                ? GamepadInput.name()
                                : tr("ui.stevedance.pad.disconnected");
                case 2 -> Math.round(padSensitivity * 100) + "%";
                case 3 -> tr("ui.stevedance.pad.open_guide");
                default -> tr("ui.stevedance.pad.restore");
            };
        }
        if (page == Page.KEYS && i == 5) {
            return tr("ui.stevedance.pad.restore");
        }
        if (page == Page.KEYS) {
            return binding == i ? tr("ui.stevedance.press_key") : keyLabel(i);
        }
        if (page == Page.SOUND) {
            return switch (i) {
                case 0 -> soundLabel(buttonSound);
                case 1 -> soundLabel(scratchSound);
                case 2 -> Math.round(soundVolume * 100) + "%";
                case 3 -> tr("ui.stevedance.f2_listen");
                default -> tr("ui.stevedance.f2_options");
            };
        }
        return switch (i) {
            case 0 -> tr("ui.stevedance.f2_change_key");
            case 1 -> tr("ui.stevedance.f2_change_key");
            case 2 -> Math.round(ClientPlaybackSettings.volume * 100) + "%";
            case 3 ->
                    Component.translatable(
                                    "ui.stevedance.f2_timing_value",
                                    ClientPlaybackSettings.offsetMs)
                            .getString();
            case 4 ->
                    Component.translatable(
                                    ClientPlaybackSettings.guide ? "options.on" : "options.off")
                            .getString();
            default -> tr("ui.stevedance.pad.open");
        };
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        start(g);
        OriginalUiAssets.loop(g, "menu_common", "bg_home", seconds());
        OriginalUiAssets.loop(g, "common_info", "frame_top", seconds());
        OriginalUiAssets.draw(
                g,
                "common_info",
                "frame_top_option",
                OriginalUiAssets.loopFrame("common_info", "frame_top_option", seconds()),
                Map.of(
                        "p_txt_lt",
                        tr(
                                page == Page.PAD
                                        ? "ui.stevedance.pad.title"
                                        : page == Page.SOUND
                                                ? "ui.stevedance.f2_button_sound"
                                                : page == Page.KEYS
                                                        ? "ui.stevedance.f2_keyboard"
                                                        : "ui.stevedance.f2_options")));
        int first = page == Page.KEYS ? selected / 6 * 6 : 0;
        for (int i = first; i < Math.min(count(), first + 6); i++) {
            int row = i, y = 164 + (i - first) * 70;
            Map<String, String> fields =
                    Map.of(
                            "p_opt_menu_txt_lt",
                            label(i),
                            "p_opt_switch_txt_ct",
                            displayValue(i),
                            "p_arrow_l_c",
                            "←",
                            "p_arrow_r_c",
                            "→");
            g.pose().pushPose();
            g.pose().translate(640, y, 0);
            OriginalUiAssets.draw(g, "settings", "opt_row_option_b", 0, fields);
            g.pose().popPose();
            if (i == selected) {
                g.pose().pushPose();
                g.pose().translate(640, y, 0);
                OriginalUiAssets.loop(g, "settings", "opt_csr_a_option_loop", seconds());
                g.pose().popPose();
                g.pose().pushPose();
                g.pose().translate(640, y, 0);
                OriginalUiAssets.slots(g, "settings", "opt_row_option_b", 0, fields);
                g.pose().popPose();
            }
            controls.add(
                    new Control(
                            228,
                            y - 32,
                            826,
                            64,
                            label(i),
                            () -> {
                                selected = row;
                                change(1);
                            }));
        }
        long age = (System.nanoTime() - previewAt) / 1000000;
        if (page == Page.SOUND && age >= 0 && age < 650) {
            g.pose().pushPose();
            g.pose().translate(1112, 470, 0);
            OriginalUiAssets.draw(g, "settings", "hit_eff00", age * .06f);
            g.pose().popPose();
        }
        OriginalUiAssets.loop(g, "common_info", "cmn_info_btn_return_b.pic", seconds());
        nativeControl(
                g,
                mx,
                my,
                "common_info",
                "btn_return",
                1188,
                0,
                92,
                72,
                "ui.stevedance.back",
                this::onClose);
        finish(g);
    }

    private String displayValue(int i) {
        String v = value(i);
        return page == Page.PAD && i == 1 ? font.plainSubstrByWidth(v, 190) : v;
    }

    @Override
    protected void padPressed(int bit) {
        if (binding >= 0) {
            if (bit == 2 || bit == 128) {
                binding = -1;
            }
            return;
        }
        if (bit == 64) {
            open(Page.PAD);
            return;
        }
        super.padPressed(bit);
    }

    private void open(Page next) {
        page = next;
        selected = 0;
        binding = -1;
    }

    private void change(int delta) {
        if (page == Page.PAD) {
            switch (selected) {
                case 0 -> {
                    var values = List.of("auto", "ps", "xbox");
                    padIcons = values.get(Math.floorMod(values.indexOf(padIcons) + delta, 3));
                }
                case 2 ->
                        padSensitivity =
                                Math.max(.45f, Math.min(.9f, padSensitivity + delta * .05f));
                case 3 -> minecraft.setScreen(new GamepadGuideScreen(this));
                case 4 -> {
                    padIcons = "auto";
                    padSensitivity = .65f;
                }
                default -> {}
            }
            save();
            return;
        }
        if (page == Page.KEYS) {
            if (selected == 5) {
                for (int i = 0; i < 5; i++) {
                    ClientPlaybackSettings.bind(
                            i == 4 ? 8 : i == 1 ? 3 : i == 3 ? 1 : i, DEFAULT_KEYS[i]);
                }
                binding = -1;
            } else {
                binding = selected;
            }
            return;
        }
        if (page == Page.SOUND) {
            switch (selected) {
                case 0 -> buttonSound = Math.floorMod(buttonSound + delta, 6);
                case 1 -> scratchSound = Math.floorMod(scratchSound + delta, 6);
                case 2 -> soundVolume = Math.max(0, Math.min(1, soundVolume + delta * .05f));
                case 3 -> {
                    RhythmSoundPlayer.play("tap");
                    previewAt = System.nanoTime();
                }
                case 4 -> open(Page.GENERAL);
            }
            save();
            if (selected < 2 && page == Page.SOUND) {
                RhythmSoundPlayer.play(selected == 0 ? "tap" : "star");
                previewAt = System.nanoTime();
            }
            return;
        }
        switch (selected) {
            case 0 -> open(Page.SOUND);
            case 1 -> open(Page.KEYS);
            case 2 -> {
                ClientPlaybackSettings.volume =
                        Math.max(0, Math.min(1, ClientPlaybackSettings.volume + delta * .05f));
                ClientPlaybackSettings.save();
            }
            case 3 -> {
                ClientPlaybackSettings.offsetMs =
                        Math.max(-300, Math.min(300, ClientPlaybackSettings.offsetMs + delta * 5));
                ClientPlaybackSettings.save();
            }
            case 4 -> {
                ClientPlaybackSettings.guide = !ClientPlaybackSettings.guide;
                ClientPlaybackSettings.save();
            }
            case 5 -> open(Page.PAD);
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (binding >= 0) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                binding = -1;
                return true;
            }
            if (key < 32
                    || key > 348
                    || dev.flower.stevedance.client.PerformanceControls.reserved(key)) {
                return true;
            }
            ClientPlaybackSettings.bind(
                    binding == 4 ? 8 : binding == 1 ? 3 : binding == 3 ? 1 : binding, key);
            binding = -1;
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            selected = Math.floorMod(selected + (key == GLFW.GLFW_KEY_UP ? -1 : 1), count());
            RhythmSoundPlayer.play("select");
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER && page == Page.GENERAL && selected == 3) {
            minecraft.setScreen(new TimingCalibrationScreen(this));
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_ENTER) {
            change(key == GLFW.GLFW_KEY_LEFT ? -1 : 1);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (binding >= 0) {
            return true;
        }
        if (button == 0) {
            float vx = virtualX(x), vy = virtualY(y);
            int first = page == Page.KEYS ? selected / 6 * 6 : 0;
            for (int i = first; i < Math.min(count(), first + 6); i++) {
                float rowY = 164 + (i - first) * 70;
                if (vx >= 228 && vx < 1054 && vy >= rowY - 32 && vy < rowY + 32) {
                    selected = i;
                    if (page == Page.GENERAL && i == 3 && vx >= 790 && vx < 1000) {
                        minecraft.setScreen(new TimingCalibrationScreen(this));
                        return true;
                    }
                    change(vx < 790 ? -1 : 1);
                    return true;
                }
            }
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        selected = Math.floorMod(selected + (delta > 0 ? -1 : 1), count());
        return true;
    }

    @Override
    public void onClose() {
        if (page == Page.KEYS || page == Page.PAD) {
            open(Page.GENERAL);
            return;
        }
        RhythmSoundPlayer.play("cancel");
        minecraft.setScreen(parent);
    }
}
