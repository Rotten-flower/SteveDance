package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.flower.stevedance.MotionPackLibrary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文本字幕采样自共享音乐时钟，独立于PV画面。
 * <p>Text subtitles sampled from the shared music clock, independently of PV footage.</p>
 */
public final class PvLyrics {
    public static final class Cue {
        public double timeMs;
        public int line, color;
    }

    public static final class Line {
        public String text, alternateText;
        public Map<String, String> texts;
        public int stringId, alternateStringId;
    }

    public static final class Track {
        public int schema;
        public String language;
        public Map<String, Line> lines;
        public Cue[] events;
    }

    private static Track installed;
    private static String cachedText = "";
    private static Component cachedComponent = Component.empty();
    private static int cachedWidth = -1;
    private static List<net.minecraft.util.FormattedCharSequence> cachedLines = List.of();
    public static int lastLine;

    public static Track load(Path root, PvAetTimeline.Lyrics legacy) throws IOException {
        Path path = root.resolve("subtitles.json");
        Track track;
        if (Files.isRegularFile(path)) {
            path = PvSceneData.safe(root, "subtitles.json");
            if (Files.size(path) > 1024 * 1024) {
                throw new IOException("Subtitle metadata budget exceeded");
            }
            try (var reader = Files.newBufferedReader(path)) {
                track = MotionPackLibrary.GSON.fromJson(reader, Track.class);
            }
        } else if (legacy != null) {
            track =
                    MotionPackLibrary.GSON.fromJson(
                            MotionPackLibrary.GSON.toJsonTree(legacy), Track.class);
            track.schema = 1;
        } else {
            return null;
        }
        validate(track);
        return track;
    }

    public static void validate(Track track) throws IOException {
        if (track == null
                || track.schema != 1
                || track.lines == null
                || track.events == null
                || track.lines.size() > 256
                || track.events.length > 1024) {
            throw new IOException("Invalid subtitle track");
        }
        for (var entry : track.lines.entrySet()) {
            if (!entry.getKey().matches("[1-9][0-9]*")
                    || entry.getValue() == null
                    || entry.getValue().text == null
                    || entry.getValue().text.length() > 2048) {
                throw new IOException("Invalid subtitle line");
            }
            try {
                dev.flower.stevedance.content.SongLanguages.validate(
                        entry.getValue().texts, 2048, true);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid subtitle translations", error);
            }
        }
        double previous = -1;
        for (var cue : track.events) {
            if (cue == null
                    || !Double.isFinite(cue.timeMs)
                    || cue.timeMs < 0
                    || cue.timeMs < previous
                    || cue.line != 0 && !track.lines.containsKey(Integer.toString(cue.line))) {
                throw new IOException("Invalid subtitle cue");
            }
            previous = cue.timeMs;
        }
    }

    public static Cue cue(Track track, double ms) {
        if (track == null || !Double.isFinite(ms)) {
            return null;
        }
        int lo = 0, hi = track.events.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (track.events[m].timeMs <= ms) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo == 0 ? null : track.events[lo - 1];
    }

    public static void install(Track track) {
        clear();
        installed = track;
    }

    public static void clear() {
        installed = null;
        cachedText = "";
        cachedComponent = Component.empty();
        cachedLines = List.of();
        cachedWidth = -1;
        lastLine = 0;
    }

    public static void draw(GuiGraphics graphics, int width, int height, double ms) {
        draw(graphics, width, height, ms, false);
    }

    public static void drawInRhythmHud(GuiGraphics graphics, int width, int height, double ms) {
        draw(graphics, width, height, ms, true);
    }

    private static void draw(
            GuiGraphics graphics, int width, int height, double ms, boolean rhythm) {
        if (!dev.flower.stevedance.client.PvVisibility.visible("lyrics")) {
            lastLine = 0;
            return;
        }
        Cue event = cue(installed, ms);
        lastLine = event == null ? 0 : event.line;
        if (lastLine == 0) {
            return;
        }
        Line lineText = installed.lines.get(Integer.toString(lastLine));
        String text = SongText.localized(lineText.texts, lineText.text);
        if (!cachedText.equals(text)) {
            cachedText = text;
            cachedComponent = Component.literal(text);
            cachedWidth = -1;
        }
        Font font = Minecraft.getInstance().font;
        float scale = Math.min(1f, Math.min(width / 426f, height / 240f));
        float hudScale = Math.min(width / 1280f, height / 728f);
        if (rhythm) {
            scale = Math.min(scale, 900 * hudScale / Math.max(1, font.width(cachedComponent)));
        }
        int safeWidth =
                rhythm
                        ? Math.max(1, font.width(cachedComponent) + 1)
                        : Math.max(1, (int) ((width - 24) / scale));
        if (cachedWidth != safeWidth) {
            cachedLines = font.split(cachedComponent, safeWidth);
            cachedWidth = safeWidth;
        }
        var lines = cachedLines;
        float y =
                rhythm
                        ? (height - 728 * hudScale) / 2
                                + 660 * hudScale
                                - font.lineHeight * scale / 2
                        : Math.max(
                                12,
                                height - height * .085f - lines.size() * font.lineHeight * scale);
        graphics.flush();
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        graphics.pose().pushPose();
        try {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            double pixels = Minecraft.getInstance().getWindow().getGuiScale();
            // 原生描边按每个字形的偏移绘制，避免整行复制造成细字断边。
            // 中文：原生轮廓使用各字形的自身偏移；将最终原点对齐到屏幕像素。
            // EN: Native outlines use each glyph's own offset; snap the final origin to screen
            // pixels.
            for (int i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                float x =
                        (float)
                                (Math.round((width - font.width(line) * scale) * .5 * pixels)
                                        / pixels);
                float top =
                        (float) (Math.round((y + i * font.lineHeight * scale) * pixels) / pixels);
                graphics.pose().pushPose();
                graphics.pose().translate(x, top, 400);
                graphics.pose().scale(scale, scale, 1);
                font.drawInBatch8xOutline(
                        line,
                        0,
                        0,
                        0xffffffff,
                        0xff000000,
                        graphics.pose().last().pose(),
                        graphics.bufferSource(),
                        15728880);
                graphics.pose().popPose();
            }
            graphics.flush();
        } finally {
            graphics.pose().popPose();
            RenderSystem.depthMask(mask);
            if (depth) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            graphics.setColor(1, 1, 1, 1);
        }
    }
}
