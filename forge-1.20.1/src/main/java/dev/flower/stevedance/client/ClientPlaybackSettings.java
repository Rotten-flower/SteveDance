package dev.flower.stevedance.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.flower.stevedance.SteveDanceMod;

import net.minecraftforge.fml.loading.FMLPaths;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ClientPlaybackSettings {
    public static int offsetMs = 0;
    public static float volume = .75f;
    public static boolean guide = true;

    public enum RenderingMode {
        AUTO,
        PV,
        MINECRAFT
    }

    public static RenderingMode renderingMode = RenderingMode.PV;
    public static boolean worldStage;
    public static boolean chartOnly;

    public static String playbackLabel() {
        load();
        return effectiveChartOnly()
                ? "ui.stevedance.mode.chart"
                : worldStage ? "ui.stevedance.mode.world" : "ui.stevedance.mode.private";
    }

    /** A chart without a PV never changes the saved playback preference. / 无PV曲不覆盖全局选择。 */
    public static boolean effectiveChartOnly() {
        var selected = dev.flower.stevedance.SongSelectionCatalog.get(RhythmClientController.song);
        return chartOnly || selected != null && !selected.pvAvailable();
    }

    public static boolean worldPvLighting = true;
    public static dev.flower.stevedance.client.StageCamera.Mode cameraMode =
            dev.flower.stevedance.client.StageCamera.Mode.AUTHORED;
    private static final int[] alternateKeys = {
        GLFW.GLFW_KEY_UP,
        GLFW.GLFW_KEY_LEFT,
        GLFW.GLFW_KEY_DOWN,
        GLFW.GLFW_KEY_RIGHT,
        GLFW.GLFW_KEY_E
    };

    // All content reads the same four face keys and scratch binding. / 所有曲目共用主按键。
    private static int faceIndex(int i) {
        return i == 1 ? 3 : i == 3 ? 1 : i;
    }

    public static int key(int i) {
        load();
        RhythmSettingsScreen.load();
        return i < 4
                ? RhythmSettingsScreen.KEYS[faceIndex(i)]
                : i == 8 ? RhythmSettingsScreen.KEYS[4] : alternateKeys[i == 9 ? 4 : i - 4];
    }

    private static void assign(int i, int value) {
        if (i < 4) {
            RhythmSettingsScreen.KEYS[faceIndex(i)] = value;
        } else if (i == 8) {
            RhythmSettingsScreen.KEYS[4] = value;
        } else {
            alternateKeys[i == 9 ? 4 : i - 4] = value;
        }
    }

    public static void bind(int index, int value) {
        if (value < 32
                || value > 348
                || value == GLFW.GLFW_KEY_ESCAPE
                || dev.flower.stevedance.client.PerformanceControls.reserved(value)) {
            return;
        }
        int old = key(index);
        for (int i = 0; i < 10; i++) {
            if (i != index && key(i) == value) {
                assign(i, old);
            }
        }
        assign(index, value);
        RhythmSettingsScreen.save();
        save();
    }

    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve("stevedance/client.json");
    private static JsonObject preserved = new JsonObject();
    private static boolean loaded;

    public static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.exists(PATH)) {
            return;
        }
        try {
            if (Files.size(PATH) > 65536) {
                throw new IOException("Client options too large");
            }
            preserved = JsonParser.parseString(Files.readString(PATH)).getAsJsonObject();
            if (preserved.has("schema") && preserved.get("schema").getAsInt() != 1) {
                SteveDanceMod.LOGGER.warn(
                        "Unsupported client settings schema; defaults active; file preserved");
                return;
            }
            if (preserved.has("worldPvLighting")) {
                try {
                    worldPvLighting = preserved.get("worldPvLighting").getAsBoolean();
                } catch (RuntimeException ignored) {
                }
            }
            offsetMs = integer("offsetMs", 0, -300, 300);
            volume = integer("volumePercent", 75, 0, 100) / 100f;
            if (preserved.has("guide") && preserved.get("guide").isJsonPrimitive()) {
                try {
                    guide = preserved.get("guide").getAsBoolean();
                } catch (RuntimeException ex) {
                    SteveDanceMod.LOGGER.warn("Invalid client option: guide");
                }
            }
            if (preserved.has("renderingMode")
                    && preserved.get("renderingMode").isJsonPrimitive()) {
                try {
                    renderingMode =
                            RenderingMode.valueOf(preserved.get("renderingMode").getAsString());
                } catch (RuntimeException ex) {
                    SteveDanceMod.LOGGER.warn("Invalid client option: renderingMode");
                }
            }
            if (preserved.has("worldStage") && preserved.get("worldStage").isJsonPrimitive()) {
                try {
                    worldStage = preserved.get("worldStage").getAsBoolean();
                } catch (RuntimeException ex) {
                    SteveDanceMod.LOGGER.warn("Invalid client option: worldStage");
                }
            }
            if (preserved.has("chartOnly")) {
                try {
                    chartOnly = preserved.get("chartOnly").getAsBoolean();
                    if (chartOnly) {
                        worldStage = false;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            if (preserved.has("cameraMode")) {
                try {
                    cameraMode =
                            dev.flower.stevedance.client.StageCamera.Mode.valueOf(
                                    preserved.get("cameraMode").getAsString());
                } catch (RuntimeException ignored) {
                }
            }
            if (preserved.has("alternateKeys")) {
                try {
                    var a = preserved.getAsJsonArray("alternateKeys");
                    for (int i = 0; i < Math.min(5, a.size()); i++) {
                        int k = a.get(i).getAsInt();
                        if (k >= 32
                                && k <= 348
                                && k != GLFW.GLFW_KEY_ESCAPE
                                && !dev.flower.stevedance.client.PerformanceControls.reserved(k)) {
                            alternateKeys[i] = k;
                        }
                    }
                } catch (RuntimeException ex) {
                    SteveDanceMod.LOGGER.warn("Invalid alternate key bindings", ex);
                }
            }

            // Old builds saved chart-only automatically, so it is not evidence of a player choice.
            // 旧版自动写入的纯谱面无法视为玩家选择，本轮只重置这组演出默认值一次。
            if (integer("playbackDefaults", 0, 0, 2) < 2) {
                chartOnly = false;
                worldStage = false;
                renderingMode = RenderingMode.PV;
                worldPvLighting = true;
                preserved.addProperty("playbackDefaults", 2);
                save();
            }

        } catch (IOException | RuntimeException ex) {
            SteveDanceMod.LOGGER.warn(
                    "Cannot read {}: {}; defaults active, source preserved", PATH, ex.toString());
        }
    }

    private static int integer(String key, int fallback, int min, int max) {
        try {
            return preserved.has(key)
                    ? Math.max(min, Math.min(max, preserved.get(key).getAsInt()))
                    : fallback;
        } catch (RuntimeException ex) {
            SteveDanceMod.LOGGER.warn("Invalid client option: {}", key);
            return fallback;
        }
    }

    public static void save() {
        load();
        if (chartOnly) {
            worldStage = false;
        }
        preserved.addProperty("chartOnly", chartOnly);
        preserved.addProperty("playbackDefaults", 2);
        preserved.addProperty("worldPvLighting", worldPvLighting);
        preserved.remove("showScenery");
        preserved.remove("showWorldScenery");
        preserved.addProperty("cameraMode", cameraMode.name());
        preserved.addProperty("schema", 1);
        preserved.addProperty("offsetMs", offsetMs);
        preserved.addProperty("volumePercent", Math.round(volume * 100));
        preserved.addProperty("guide", guide);
        preserved.addProperty("renderingMode", renderingMode.name());
        preserved.remove("nativeCharacterLighting");
        preserved.addProperty("worldStage", worldStage);
        JsonArray keys = new JsonArray();
        for (int key : alternateKeys) {
            keys.add(key);
        }
        preserved.remove("keys");
        preserved.add("alternateKeys", keys);
        try {
            Files.createDirectories(PATH.getParent());
            Path tmp = PATH.resolveSibling("client.json.tmp");
            Files.writeString(
                    tmp, new GsonBuilder().setPrettyPrinting().create().toJson(preserved));
            Files.move(tmp, PATH, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            SteveDanceMod.LOGGER.warn("Cannot save client options: {}", ex.toString());
        }
    }

    public static String keyName(int i) {
        return com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                .getOrCreate(key(i))
                .getDisplayName()
                .getString();
    }
}
