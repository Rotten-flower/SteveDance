package dev.flower.stevedance.client.planet;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** MC原生绘制与输入，玩法不在渲染线程运行。 / Native display/input, off-thread recovered gameplay. */
public final class PlanetScreen extends Screen {
    private record Texture(ResourceLocation id, int width, int height) {}

    private final Screen parent;
    private final PlanetRuntime runtime = new PlanetRuntime();
    private final Map<String, Texture> textures = new HashMap<>();
    private final Map<Integer, Integer> heldKeys = new HashMap<>();
    private final String[] actions = {
        "up",
        "down",
        "left",
        "right",
        "jump",
        "use",
        "cancel",
        "items",
        "previous",
        "next",
        "answer1",
        "answer2",
        "answer3"
    };
    private final int[] bits = {
        16, 64, 128, 32, 16384, 8192, 16384, 4096, 256, 512, 4096, 8192, 16384
    };
    private final int[] defaults = {
        GLFW.GLFW_KEY_W,
        GLFW.GLFW_KEY_S,
        GLFW.GLFW_KEY_A,
        GLFW.GLFW_KEY_D,
        GLFW.GLFW_KEY_SPACE,
        GLFW.GLFW_KEY_E,
        GLFW.GLFW_KEY_Q,
        GLFW.GLFW_KEY_TAB,
        GLFW.GLFW_KEY_Z,
        GLFW.GLFW_KEY_C,
        GLFW.GLFW_KEY_1,
        GLFW.GLFW_KEY_2,
        GLFW.GLFW_KEY_3
    };
    private int[] bindings = defaults.clone();
    private int pressed, titleSelection, capture = -1;
    private boolean title = true, choosing, paused, controlsOpen, expanded, disposed;
    private long next, lastSounds = -1, lastPresentation = -1;
    private float scale, originX, originY;
    private float musicVolume = .4f, effectVolume = .45f;
    private int dragging = -1, controlsScroll, pauseFocus;
    private String feedback = "";
    private static final ResourceLocation PIXEL_FONT = new ResourceLocation("stevedance", "planet");
    private String music = "";
    private PlanetMusic song;

    public PlanetScreen(Screen parent) {
        super(Component.translatable("ui.stevedance.planet.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        try {
            if (textures.isEmpty()) {
                try (var reader =
                        new InputStreamReader(
                                minecraft
                                        .getResourceManager()
                                        .open(
                                                new ResourceLocation(
                                                        "stevedance", "planet/textures.json")),
                                java.nio.charset.StandardCharsets.UTF_8)) {
                    var manifest = JsonParser.parseReader(reader).getAsJsonObject();
                    for (var e : manifest.entrySet()) {
                        // key保留恢复程序标识；file使用可维护的生产资源名。
                        // Keys bind the recovered program; file names describe runtime assets.
                        var asset = e.getValue().getAsJsonObject();
                        textures.put(
                                e.getKey(),
                                new Texture(
                                        new ResourceLocation(
                                                "stevedance", "planet/textures/" + asset.get("file").getAsString()),
                                        asset.get("width").getAsInt(),
                                        asset.get("height").getAsInt()));
                    }
                }
            }
            Path settings = settingsPath();
            if (Files.exists(settings)) {
                var s = JsonParser.parseString(Files.readString(settings)).getAsJsonObject();
                if (s.has("keys")) {
                    var keys = s.getAsJsonArray("keys");
                    if (keys.size() == bindings.length) {
                        for (int i = 0; i < bindings.length; i++) {
                            bindings[i] = keys.get(i).getAsInt();
                        }
                    }
                }
                if (s.has("musicVolume")) {
                    musicVolume =
                            net.minecraft.util.Mth.clamp(s.get("musicVolume").getAsFloat(), 0, 1);
                }
                if (s.has("effectVolume")) {
                    effectVolume =
                            net.minecraft.util.Mth.clamp(s.get("effectVolume").getAsFloat(), 0, 1);
                }
            }
        } catch (Exception e) {
            dev.flower.stevedance.SteveDanceMod.LOGGER.warn(
                    "Cannot load Planet display settings", e);
        }
        next = System.nanoTime();
    }

    private Path settingsPath() {
        return minecraft.gameDirectory.toPath().resolve("config/stevedance/planet/controls.json");
    }

    private void saveSettings() {
        try {
            Path file = settingsPath();
            Files.createDirectories(file.getParent());
            JsonObject s = new JsonObject();
            s.add("keys", new Gson().toJsonTree(bindings));
            s.addProperty("musicVolume", musicVolume);
            s.addProperty("effectVolume", effectVolume);
            Files.writeString(file, s.toString());
        } catch (IOException e) {
            dev.flower.stevedance.SteveDanceMod.LOGGER.warn("Cannot save Planet settings", e);
        }
    }

    private String tr(String key) {
        return Component.translatable("ui.stevedance.planet." + key).getString();
    }

    private Component label(String value) {
        return Component.literal(value).withStyle(style -> style.withFont(PIXEL_FONT));
    }

    private int textWidth(String value) {
        return font.width(label(value));
    }

    private void text(GuiGraphics g, String value, int x, int y, int color) {
        g.drawString(font, label(value), x, y, color, false);
    }

    private void center(GuiGraphics g, String text, int y, int color) {
        text(g, text, (480 - textWidth(text)) / 2, y, color);
    }

    private void drawSprite(
            GuiGraphics g,
            String file,
            float x,
            float y,
            float w,
            float h,
            float u,
            float v,
            float uw,
            float vh,
            float alpha,
            int flip) {
        if (w <= 0 || h <= 0 || uw <= 0 || vh <= 0 || alpha <= 0) {
            return;
        }
        if (file.startsWith("#")) {
            g.fill(
                    Math.round(x),
                    Math.round(y),
                    Math.round(x + w),
                    Math.round(y + h),
                    ((int) (alpha * 255) << 24) | Integer.parseInt(file.substring(1), 16));
            return;
        }
        Texture t = textures.get(file);
        if (t == null) {
            return;
        }
        // 翻转UV而非负缩放，保持正面绕序。 / Flip UVs, preserving front-face winding.
        boolean fx = (flip & 1) != 0, fy = (flip & 2) != 0;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(1, 1, 1, alpha);
        g.blit(
                t.id,
                Math.round(x),
                Math.round(y),
                Math.round(w),
                Math.round(h),
                u + (fx ? uw : 0),
                v + (fy ? vh : 0),
                Math.round(fx ? -uw : uw),
                Math.round(fy ? -vh : vh),
                t.width,
                t.height);
        g.setColor(1, 1, 1, 1);
    }

    private void lines(GuiGraphics g, String value, int x, int y, int width, int lineHeight) {
        for (String paragraph : value.split("\\n", -1)) {
            if (paragraph.isEmpty()) {
                y += lineHeight;
                continue;
            }
            for (var line : font.split(label(paragraph), width)) {
                g.drawString(font, line, x, y, 0xffffffff, false);
                y += lineHeight;
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        scale =
                Math.min(
                        (width - (expanded ? 0 : 32)) / 480f,
                        (height - (expanded ? 0 : 48)) / 272f);
        scale = Math.max(.1f, scale);
        originX = (width - 480 * scale) / 2;
        originY = (height - 272 * scale) / 2;
        g.fill(
                (int) originX - 3,
                (int) originY - 3,
                (int) (originX + 480 * scale) + 3,
                (int) (originY + 272 * scale) + 3,
                0xff30261b);
        g.enableScissor(
                (int) Math.ceil(originX),
                (int) Math.ceil(originY),
                (int) Math.floor(originX + 480 * scale),
                (int) Math.floor(originY + 272 * scale));
        g.fill(
                (int) originX,
                (int) originY,
                (int) (originX + 480 * scale),
                (int) (originY + 272 * scale),
                0xff000000);
        JsonObject s = runtime.state();
        if (!disposed
                && !title
                && !paused
                && s != null
                && runtime.error().isEmpty()
                && System.nanoTime() >= next
                && !runtime.busy()) {
            int held = 0;
            for (int bit : heldKeys.values()) {
                held |= bit;
            }
            if (runtime.submit("step", held, pressed)) {
                pressed = 0;
                next += 33_333_333L;
                if (System.nanoTime() - next > 200_000_000L) {
                    next = System.nanoTime();
                }
            }
        }
        if (!title
                && !paused
                && s != null
                && !minecraft.isWindowActive()
                && minecraft.options.pauseOnLostFocus) {
            paused = true;
            clearInput();
            stopMusic();
        }
        g.pose().pushPose();
        g.pose().translate(originX, originY, 0);
        g.pose().scale(scale, scale, 1);
        if (s != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            for (JsonElement element : s.getAsJsonArray("sprites")) {
                var d = element.getAsJsonArray();
                drawSprite(
                        g,
                        d.get(0).getAsString(),
                        d.get(1).getAsFloat(),
                        d.get(2).getAsFloat(),
                        d.get(3).getAsFloat(),
                        d.get(4).getAsFloat(),
                        d.get(5).getAsFloat(),
                        d.get(6).getAsFloat(),
                        d.get(7).getAsFloat(),
                        d.get(8).getAsFloat(),
                        d.get(9).getAsFloat(),
                        d.get(10).getAsInt());
            }
            if (!title) {
                String notice = s.get("notice_text").getAsString();
                if (!notice.isEmpty()) {
                    int count = font.split(label(notice), 192).size();
                    lines(g, notice, 176, 136 - count * 8, 192, 16);
                }
                if (!s.get("item_menu").isJsonNull()) {
                    lines(
                            g,
                            s.getAsJsonObject("item_menu").get("description").getAsString(),
                            40,
                            240,
                            416,
                            16);
                }
                if (!s.get("journal").isJsonNull()) {
                    var journal = s.getAsJsonObject("journal");
                    lines(
                            g,
                            journal.get("title").getAsString()
                                    + "\n"
                                    + journal.get("body").getAsString(),
                            48,
                            28,
                            384,
                            16);
                }
                if (!s.get("stage_menu").isJsonNull()) {
                    var menu = s.getAsJsonObject("stage_menu");
                    var stage =
                            menu.getAsJsonArray("stages")
                                    .get(menu.get("cursor").getAsInt())
                                    .getAsJsonObject();
                    center(g, stage.get("name").getAsString(), 242, 0xffffffff);
                }
                if (!s.get("quiz").isJsonNull()) {
                    var quiz = s.getAsJsonObject("quiz");
                    lines(g, quiz.get("body").getAsString(), 40, 24, 400, 16);
                    int i = 0;
                    for (var option : quiz.getAsJsonArray("options")) {
                        lines(g, (i + 1) + " " + option.getAsString(), 184, 184 + 20 * i, 272, 16);
                        i++;
                    }
                }
                if (!s.get("ending").isJsonNull()) {
                    center(g, tr("ending"), 120, 0xffffffff);
                    center(g, tr("ending_return"), 140, 0xffe7b757);
                }
            }
            audio(s);
        }
        if (title) {
            g.fill(0, 0, 480, 272, 0xff000000);
            drawSprite(g, "game1/menu/dlc1_t_titleall.png", 0, 0, 480, 148, 0, 0, 480, 148, 1, 0);
            drawSprite(g, "game1/menu/dlc1_t_titleall.png", 0, 220, 480, 44, 0, 220, 480, 44, 1, 0);
            if (choosing) {
                text(g, "♪", 176, 152 + 16 * titleSelection, 0xffe7b757);
                text(g, tr("new"), 192, 152, 0xffffffff);
                text(
                        g,
                        tr("continue"),
                        192,
                        168,
                        s != null && s.get("save_available").getAsBoolean()
                                ? 0xffffffff
                                : 0xff666666);
            } else {
                center(g, tr(s == null ? "loading" : "start"), 160, 0xffffffff);
            }
            String score =
                    String.format(
                            java.util.Locale.ROOT,
                            "%06d",
                            s == null ? 0 : s.get("high_score").getAsInt());
            int sx = (480 - textWidth(tr("score") + " " + score)) / 2;
            text(g, tr("score"), sx, 192, 0xffdb7893);
            text(g, score, sx + textWidth(tr("score") + " "), 192, 0xffffffff);
        }
        if (paused) {
            settings(g);
        }
        if (!runtime.error().isEmpty()) {
            g.fill(24, 96, 456, 176, 0xf0442222);
            lines(g, tr("runtime_error"), 36, 108, 408, 16);
            center(g, tr("exit_hint"), 148, 0xffffffff);
        }
        g.flush();
        g.pose().popPose();
        g.disableScissor();
        RenderSystem.disableBlend();
    }

    private void button(GuiGraphics g, String value, int x, int y, int w) {
        g.fill(x, y, x + w, y + 20, 0xffb39460);
        g.fill(x + 1, y + 1, x + w - 1, y + 19, 0xff222222);
        g.fill(x + 2, y + 2, x + w - 2, y + 18, 0xff555555);
        text(g, value, x + (w - textWidth(value)) / 2, y + 4, 0xffffffff);
    }

    private void slider(GuiGraphics g, String name, int y, float value) {
        text(g, tr(name), 64, y, 0xffffffff);
        g.fill(240, y + 5, 386, y + 8, 0xff30261b);
        int x = 240 + Math.round(value * 146);
        g.fill(240, y + 5, x, y + 8, 0xffd6b77f);
        g.fill(x - 3, y + 1, x + 3, y + 12, 0xffd6b77f);
        text(g, Math.round(value * 100) + "%", 394, y, 0xfff2d895);
    }

    private void settings(GuiGraphics g) {
        g.fill(0, 0, 480, 272, 0xaa000000);
        g.fill(38, 4, 442, 268, 0xff30261b);
        g.fill(40, 6, 440, 266, 0xffd0aa69);
        g.fill(42, 8, 438, 264, 0xff606060);
        g.fill(44, 10, 436, 262, 0xffd0aa69);
        g.fill(46, 12, 434, 260, 0xff606060);
        text(g, tr("settings"), 64, 24, 0xffffffff);
        slider(g, "music", 54, musicVolume);
        slider(g, "effects", 78, effectVolume);
        focusRect(g, 60, 48, 414, 70, pauseFocus == 0);
        focusRect(g, 60, 72, 414, 94, pauseFocus == 1);
        text(g, (controlsOpen ? "▾ " : "▸ ") + tr("controls"), 64, 103, 0xfff2d895);
        focusRect(g, 60, 98, 414, 117, pauseFocus == 2);
        if (controlsOpen) {
            for (int row = 0; row < 4; row++) {
                int i = controlsScroll + row;
                if (i >= actions.length) {
                    break;
                }
                int y = 122 + row * 15;
                text(g, tr(actions[i]), 64, y, 0xffffffff);
                g.fill(302, y - 2, 410, y + 12, 0xffb39460);
                g.fill(303, y - 1, 409, y + 11, 0xff555555);
                text(g, capture == i ? tr("capture") : keyName(bindings[i]), 308, y, 0xfff2d895);
                focusRect(g, 60, y - 3, 414, y + 13, pauseFocus == i + 3);
            }
            text(g, tr("scroll_controls"), 64, 186, 0xffdddddd);
            button(g, tr("defaults"), 288, 182, 122);
            focusRect(g, 286, 180, 412, 204, pauseFocus == 16);
        } else {
            lines(g, tr("settings_hint"), 64, 126, 346, 18);
            text(g, feedback, 64, 171, 0xfff2d895);
        }
        button(g, tr(expanded ? "window" : "fullscreen"), 64, 207, 64);
        button(g, tr("save"), 136, 207, 78);
        button(g, tr("load"), 222, 207, 78);
        button(g, tr("return_title"), 308, 207, 102);
        button(g, tr("resume"), 64, 230, 100);
        button(g, tr("exit"), 172, 230, 100);
        focusRect(g, 62, 205, 130, 229, pauseFocus == 17);
        focusRect(g, 134, 205, 216, 229, pauseFocus == 18);
        focusRect(g, 220, 205, 302, 229, pauseFocus == 19);
        focusRect(g, 306, 205, 412, 229, pauseFocus == 20);
        focusRect(g, 62, 228, 166, 252, pauseFocus == 21);
        focusRect(g, 170, 228, 274, 252, pauseFocus == 22);
        g.pose().pushPose();
        g.pose().translate(64, 253, 0);
        g.pose().scale(.6f, .6f, 1);
        text(g, tr("credits"), 0, 0, 0xffdddddd);
        g.pose().popPose();
    }

    private void focusRect(GuiGraphics g, int x1, int y1, int x2, int y2, boolean focused) {
        if (!focused) {
            return;
        }
        int color = 0xffffe27a;
        g.fill(x1, y1, x2, y1 + 1, color);
        g.fill(x1, y2 - 1, x2, y2, color);
        g.fill(x1, y1, x1 + 1, y2, color);
        g.fill(x2 - 1, y1, x2, y2, color);
    }

    private boolean focusVisible(int focus) {
        return controlsOpen || focus < 3 || focus > 16;
    }

    private void movePauseFocus(int direction) {
        for (int attempts = 0; attempts < 23; attempts++) {
            pauseFocus = (pauseFocus + direction + 23) % 23;
            if (focusVisible(pauseFocus)) {
                break;
            }
        }
        if (pauseFocus >= 3 && pauseFocus <= 15) {
            controlsScroll =
                    net.minecraft.util.Mth.clamp(
                            pauseFocus - 6, 0, actions.length - 4);
        }
    }

    private void adjustFocusedVolume(int direction) {
        if (pauseFocus == 0) {
            musicVolume = net.minecraft.util.Mth.clamp(musicVolume + direction * .05f, 0, 1);
            if (song != null) {
                song.gain(musicVolume);
            }
            saveSettings();
        } else if (pauseFocus == 1) {
            effectVolume = net.minecraft.util.Mth.clamp(effectVolume + direction * .05f, 0, 1);
            saveSettings();
        }
    }

    private void activatePauseFocus() {
        if (pauseFocus == 2) {
            controlsOpen = !controlsOpen;
            capture = -1;
        } else if (pauseFocus >= 3 && pauseFocus <= 15) {
            capture = pauseFocus - 3;
        } else if (pauseFocus == 16) {
            bindings = defaults.clone();
            saveSettings();
        } else if (pauseFocus == 17) {
            expanded = !expanded;
        } else if (pauseFocus == 18) {
            if (runtime.submit("save", 0, 0)) {
                feedback = "";
            }
        } else if (pauseFocus == 19) {
            if (runtime.submit("load", 0, 0)) {
                feedback = "";
            }
        } else if (pauseFocus == 20) {
            title = true;
            choosing = false;
            resume();
            stopMusic();
        } else if (pauseFocus == 21) {
            resume();
        } else if (pauseFocus == 22) {
            onClose();
        }
    }

    private void resume() {
        paused = false;
        capture = -1;
        clearInput();
        music = "";
        next = System.nanoTime();
    }

    private void volumeAt(float x) {
        float value = net.minecraft.util.Mth.clamp((x - 240) / 146, 0, 1);
        if (dragging == 0) {
            musicVolume = value;
            if (song != null) {
                song.gain(value);
            }
        } else {
            effectVolume = value;
        }
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging >= 0) {
            volumeAt((float) (mx - originX) / scale);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging >= 0) {
            dragging = -1;
            saveSettings();
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (paused && controlsOpen) {
            controlsScroll =
                    net.minecraft.util.Mth.clamp(
                            controlsScroll - (int) Math.signum(amount), 0, actions.length - 4);
            return true;
        }
        return super.mouseScrolled(mx, my, amount);
    }

    private String keyName(int key) {
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            default -> {
                String name = GLFW.glfwGetKeyName(key, 0);
                yield name == null ? Integer.toString(key) : name.toUpperCase(Locale.ROOT);
            }
        };
    }

    private void clearInput() {
        heldKeys.clear();
        pressed = 0;
    }

    private int bit(int key) {
        for (int i = 0; i < bindings.length; i++) {
            if (bindings[i] == key) {
                return bits[i];
            }
        }
        return switch (key) {
            case GLFW.GLFW_KEY_UP -> 16;
            case GLFW.GLFW_KEY_DOWN -> 64;
            case GLFW.GLFW_KEY_LEFT -> 128;
            case GLFW.GLFW_KEY_RIGHT -> 32;
            case GLFW.GLFW_KEY_ENTER -> 8192;
            default -> 0;
        };
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (capture >= 0) {
            if (key != GLFW.GLFW_KEY_ESCAPE) {
                int old = bindings[capture];
                for (int i = 0; i < bindings.length; i++) {
                    if (i != capture && bindings[i] == key) {
                        bindings[i] = old;
                    }
                }
                bindings[capture] = key;
                saveSettings();
            }
            capture = -1;
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (!runtime.error().isEmpty()) {
                onClose();
                return true;
            }
            paused = !paused;
            clearInput();
            if (paused) {
                stopMusic();
            } else {
                music = "";
                next = System.nanoTime();
            }
            return true;
        }
        if (paused) {
            if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_W) {
                movePauseFocus(-1);
            } else if (key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_S
                    || key == GLFW.GLFW_KEY_TAB) {
                movePauseFocus(1);
            } else if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) {
                adjustFocusedVolume(-1);
            } else if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D) {
                adjustFocusedVolume(1);
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_SPACE) {
                activatePauseFocus();
            }
            return true;
        }
        if (title) {
            if (runtime.state() == null) {
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP
                    || key == GLFW.GLFW_KEY_DOWN
                    || key == bindings[0]
                    || key == bindings[1]) {
                titleSelection = 1 - titleSelection;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == bindings[5] || key == bindings[4]) {
                if (!choosing) {
                    choosing = true;
                } else if (titleSelection == 0
                        || runtime.state().get("save_available").getAsBoolean()) {
                    if (runtime.submit(titleSelection == 0 ? "reset" : "load", 0, 0)) {
                        title = false;
                        next = System.nanoTime();
                    }
                }
            }
            return true;
        }
        JsonObject s = runtime.state();
        if (s != null
                && !s.get("ending").isJsonNull()
                && (key == bindings[5] || key == GLFW.GLFW_KEY_ENTER)) {
            runtime.submit("finish-ending", 0, 0);
            return true;
        }
        int bit = bit(key);
        if (bit != 0 && !heldKeys.containsKey(key)) {
            heldKeys.put(key, bit);
            pressed |= bit;
        }
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scan, int modifiers) {
        heldKeys.remove(key);
        return true;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return true;
        }
        float x = (float) (mx - originX) / scale, y = (float) (my - originY) / scale;
        if (paused) {
            if (x < 40 || x > 440) {
                return true;
            }
            if (x >= 232 && x <= 424 && ((y >= 50 && y < 70) || (y >= 74 && y < 94))) {
                dragging = y < 72 ? 0 : 1;
                pauseFocus = dragging;
                volumeAt(x);
                saveSettings();
                return true;
            }
            if (y >= 100 && y < 119) {
                pauseFocus = 2;
                controlsOpen = !controlsOpen;
                capture = -1;
                return true;
            }
            if (controlsOpen && x >= 302 && y >= 119 && y < 179) {
                capture = controlsScroll + (int) ((y - 119) / 15);
                pauseFocus = capture + 3;
                return true;
            }
            if (controlsOpen && x >= 288 && y >= 182 && y < 202) {
                pauseFocus = 16;
                bindings = defaults.clone();
                saveSettings();
                return true;
            }
            if (y >= 207 && y < 227) {
                if (x >= 64 && x < 128) {
                    pauseFocus = 17;
                    expanded = !expanded;
                } else if (x >= 136 && x < 214) {
                    pauseFocus = 18;
                    if (runtime.submit("save", 0, 0)) {
                        feedback = "";
                    }
                } else if (x >= 222 && x < 300) {
                    pauseFocus = 19;
                    if (runtime.submit("load", 0, 0)) {
                        feedback = "";
                    }
                } else if (x >= 308 && x < 410) {
                    pauseFocus = 20;
                    title = true;
                    choosing = false;
                    resume();
                    stopMusic();
                }
                return true;
            }
            if (y >= 230 && y < 250) {
                if (x >= 64 && x < 164) {
                    pauseFocus = 21;
                    resume();
                } else if (x >= 172 && x < 272) {
                    pauseFocus = 22;
                    onClose();
                }
            }
            return true;
        }
        if (x < 0 || x >= 480 || y < 0 || y >= 272) {
            return true;
        }
        if (title) {
            if (!choosing) {
                choosing = true;
            } else if (y >= 150 && y < 189) {
                titleSelection = y < 169 ? 0 : 1;
                keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
            }
            return true;
        }
        JsonObject s = runtime.state();
        if (s != null && !s.get("item_menu").isJsonNull()) {
            for (var v : s.getAsJsonArray("inventory")) {
                int i = v.getAsJsonObject().get("index").getAsInt();
                int ix = 104 + i / 3 * 80, iy = 32 + i % 3 * 64;
                if (x >= ix && x < ix + 32 && y >= iy && y < iy + 32) {
                    runtime.submit("equip", 0, 0, i);
                    return true;
                }
            }
        }
        return true;
    }

    private void audio(JsonObject s) {
        if (title || paused) {
            return;
        }
        String wanted = s.get("music").getAsString();
        if (!wanted.equals(music)) {
            stopMusic();
            music = wanted;
            song = new PlanetMusic(soundId(wanted), musicVolume);
            minecraft.getSoundManager().play(song);
        }
        long frame = s.get("frame").getAsLong();
        if (frame == lastPresentation) {
            return;
        }
        lastPresentation = frame;
        for (var e : s.getAsJsonArray("sounds")) {
            var event = e.getAsJsonObject();
            long id = event.get("id").getAsLong();
            if (id <= lastSounds) {
                continue;
            }
            lastSounds = id;
            String path =
                    s.getAsJsonObject("audio_sounds")
                            .get(event.get("sound").getAsString())
                            .getAsString();
            minecraft
                    .getSoundManager()
                    .play(
                            SimpleSoundInstance.forUI(
                                    SoundEvent.createVariableRangeEvent(soundId(path)),
                                    1,
                                    effectVolume));
        }
    }

    private ResourceLocation soundId(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).replace(".ogg", "");
        return new ResourceLocation("stevedance", "planet." + name);
    }

    private void stopMusic() {
        if (song != null) {
            minecraft.getSoundManager().stop(song);
            song = null;
        }
    }

    public JsonObject diagnostics() {
        JsonObject d = new JsonObject();
        d.addProperty("windowed", !expanded);
        d.addProperty("musicVolume", musicVolume);
        d.addProperty("effectVolume", effectVolume);
        d.addProperty("musicActive", song != null && minecraft.getSoundManager().isActive(song));
        d.addProperty("musicCategory", song == null ? "" : song.getSource().name());
        d.addProperty("alive", runtime.alive());
        d.addProperty("requests", runtime.requests());
        d.addProperty("averageStepMs", runtime.averageStepMs());
        d.addProperty("maxStepMs", runtime.maxStepMs());
        d.addProperty("paused", paused);
        d.addProperty("title", title);
        d.addProperty("controlsOpen", controlsOpen);
        d.addProperty("pauseFocus", pauseFocus);
        d.addProperty("capture", capture);
        d.add("bindings", new Gson().toJsonTree(bindings));
        d.addProperty("active", minecraft.isWindowActive());
        d.add("held", new Gson().toJsonTree(heldKeys));
        d.addProperty("pressed", pressed);
        d.addProperty("error", runtime.error());
        if (runtime.state() != null) {
            var s = runtime.state();
            for (String key : List.of("frame", "map_slot", "x", "y", "health", "substate")) {
                d.add(key, s.get(key));
            }
        }
        return d;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        disposed = true;
        clearInput();
        stopMusic();
        runtime.close();
    }

    private static final class PlanetMusic extends AbstractTickableSoundInstance {
        PlanetMusic(ResourceLocation id, float gain) {
            super(
                    SoundEvent.createVariableRangeEvent(id),
                    SoundSource.MASTER,
                    RandomSource.create());
            looping = true;
            relative = true;
            volume = gain;
            attenuation = Attenuation.NONE;
        }

        void gain(float gain) {
            volume = gain;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {}
    }
}
