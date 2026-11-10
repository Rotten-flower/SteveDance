package dev.flower.stevedance.client;

import com.mojang.blaze3d.platform.NativeImage;

import dev.flower.stevedance.core.RhythmChart;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.glfw.GLFW;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 键盘美术使用源精灵自身的动画足迹和变换。
 * <p>Keyboard artwork uses the source sprite's own animated footprint and transforms.</p>
 */
public final class RhythmNoteGlyphs {
    public record Label(
            String first,
            String second,
            String join,
            int color,
            int highlight,
            boolean hold,
            boolean filledTarget) {}

    // 中文：从原始BUTTON精灵采样的精确主填充色和高亮色。
    // EN: Exact dominant fill and highlight colors sampled from original BUTTON sprites.
    private static final int[] COLORS = {0xff00af3a, 0xffff001e, 0xff0077ff, 0xffff66dd};
    private static final int[] HIGHLIGHTS = {0xff5fefaf, 0xffff7a9c, 0xff5fe4ff, 0xffff99ff};
    public static final String SPACE_ICON = "\uE000";
    private static final int[] cachedKeys = new int[5];
    private static final String[] keyNames = new String[5];
    private static final Label[][] cached = new Label[RhythmChart.Kind.values().length][4];

    private record Artwork(
            Label label, int width, int height, boolean target, boolean controllerDouble) {}

    private static final Map<Artwork, ResourceLocation> textures = new HashMap<>();
    private static String language = "";

    private RhythmNoteGlyphs() {}

    public static boolean controllerConnected() {
        return GamepadInput.connected();
    }

    public static String keyName(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z
                || key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) {
            return Character.toString((char) key);
        }
        return switch (key) {
            case GLFW.GLFW_KEY_UP -> "↑";
            case GLFW.GLFW_KEY_RIGHT -> "→";
            case GLFW.GLFW_KEY_DOWN -> "↓";
            case GLFW.GLFW_KEY_LEFT -> "←";
            case GLFW.GLFW_KEY_SPACE ->
                    Component.translatable("ui.stevedance.f2_key_space").getString();
            case GLFW.GLFW_KEY_LEFT_SHIFT ->
                    Component.translatable("ui.stevedance.f2_key_lshift").getString();
            case GLFW.GLFW_KEY_RIGHT_SHIFT ->
                    Component.translatable("ui.stevedance.f2_key_rshift").getString();
            case GLFW.GLFW_KEY_LEFT_CONTROL ->
                    Component.translatable("ui.stevedance.f2_key_lctrl").getString();
            case GLFW.GLFW_KEY_RIGHT_CONTROL ->
                    Component.translatable("ui.stevedance.f2_key_rctrl").getString();
            case GLFW.GLFW_KEY_LEFT_ALT ->
                    Component.translatable("ui.stevedance.f2_key_lalt").getString();
            case GLFW.GLFW_KEY_RIGHT_ALT ->
                    Component.translatable("ui.stevedance.f2_key_ralt").getString();
            default ->
                    com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM
                            .getOrCreate(key)
                            .getDisplayName()
                            .getString()
                            .toUpperCase(Locale.ROOT);
        };
    }

    private static final Label[][] padLabels = new Label[RhythmChart.Kind.values().length][4];

    public static Label controllerLabel(RhythmChart.Note note) {
        if (note.kind() == RhythmChart.Kind.STAR
                || note.kind() == RhythmChart.Kind.DOUBLE_STAR
                || note.kind() == RhythmChart.Kind.LINK
                || note.kind() == RhythmChart.Kind.CHANCE_STAR) {
            return null;
        }
        if (!GamepadInput.style().equals("xbox")) {
            return null;
        }
        Label result = padLabels[note.kind().ordinal()][note.button()];
        if (result != null) {
            return result;
        }
        int[] colors = {0xffffc627, 0xffff001e, 0xff2ac849, 0xff0077ff},
                highlights = {0xffffec7a, 0xffff7a9c, 0xff8fffaf, 0xff5fe4ff};
        String face = GamepadArtwork.face(note.button(), "xbox");
        result =
                new Label(
                        face,
                        "",
                        "",
                        colors[note.button()],
                        highlights[note.button()],
                        note.kind() == RhythmChart.Kind.HOLD,
                        note.kind() == RhythmChart.Kind.DOUBLE
                                || note.kind() == RhythmChart.Kind.HOLD);
        padLabels[note.kind().ordinal()][note.button()] = result;
        return result;
    }

    public static Label label(RhythmChart.Note note) {
        if (GamepadInput.active()) {
            Label controller = controllerLabel(note);
            if (controller != null) {
                return controller;
            }
        }
        RhythmSettingsScreen.load();
        String lang = Minecraft.getInstance().getLanguageManager().getSelected();
        boolean changed = !lang.equals(language);
        for (int i = 0; i < 5; i++) {
            changed |= cachedKeys[i] != RhythmSettingsScreen.KEYS[i];
        }
        if (changed) {
            clearTextures();
            language = lang;
            System.arraycopy(RhythmSettingsScreen.KEYS, 0, cachedKeys, 0, 5);
            for (int i = 0; i < 5; i++) {
                keyNames[i] =
                        cachedKeys[i] == GLFW.GLFW_KEY_SPACE ? SPACE_ICON : keyName(cachedKeys[i]);
            }
            for (Label[] row : cached) {
                Arrays.fill(row, null);
            }
        }
        Label result = cached[note.kind().ordinal()][note.button()];
        if (result != null) {
            return result;
        }
        result =
                switch (note.kind()) {
                    case DOUBLE ->
                            new Label(
                                    keyNames[note.button()],
                                    "",
                                    "",
                                    COLORS[note.button()],
                                    HIGHLIGHTS[note.button()],
                                    false,
                                    true);
                    case DOUBLE_STAR ->
                            new Label(keyNames[4], "", "", 0xffff4800, 0xffffd644, false, true);
                    case LINK ->
                            new Label(keyNames[4], "", "", 0xffffba00, 0xfffff500, false, false);
                    case CHANCE_STAR ->
                            new Label(keyNames[4], "", "", 0xffe2c33d, 0xfff7ea6e, false, false);
                    case STAR ->
                            new Label(keyNames[4], "", "", 0xffe1b623, 0xfffffa7c, false, false);
                    default ->
                            new Label(
                                    keyNames[note.button()],
                                    "",
                                    "",
                                    COLORS[note.button()],
                                    HIGHLIGHTS[note.button()],
                                    note.kind() == RhythmChart.Kind.HOLD,
                                    note.kind() == RhythmChart.Kind.HOLD);
                };
        cached[note.kind().ordinal()][note.button()] = result;
        return result;
    }

    public static boolean symbolLayer(String name) {
        return name.startsWith("gam_cmn_button_")
                || name.startsWith("gam_cmn_tgt_")
                || name.startsWith("gam_cmn_target_") && !name.startsWith("gam_cmn_target_hand");
    }

    private static boolean controllerDouble(Label label) {
        for (Label candidate : padLabels[RhythmChart.Kind.DOUBLE.ordinal()]) {
            if (candidate == label) {
                return true;
            }
        }
        return false;
    }

    public static void draw(
            GuiGraphics g, Label label, int width, int height, float opacity, boolean target) {
        Artwork key = new Artwork(label, width, height, target, controllerDouble(label));
        ResourceLocation texture = textures.get(key);
        if (texture == null) {
            if (textures.size() >= 128) {
                clearTextures();
            }
            NativeImage image = artwork(key);
            texture =
                    Minecraft.getInstance()
                            .getTextureManager()
                            .register("f2_keyboard", new DynamicTexture(image));
            textures.put(key, texture);
        }
        OriginalUiAssets.keySprite(g, texture, width, height, opacity);
    }

    private static void clearTextures() {
        var manager = Minecraft.getInstance().getTextureManager();
        for (var texture : textures.values()) {
            manager.release(texture);
        }
        textures.clear();
    }

    /**
     * 字形轮廓仅光栅化一次；热帧上不运行字体或矢量工作。
     * <p>Glyph silhouettes are rasterized once; no font or vector work runs on warm frames.</p>
     */
    private static NativeImage artwork(Artwork key) {
        int width = key.width(), height = key.height();
        Label label = key.label();
        BufferedImage bitmap =
                new BufferedImage(width * 2, height * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bitmap.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(2, 2);
            float cx = width / 2f, cy = height / 2f;
            if (label.second().isEmpty()) {
                glyph(g, label.first(), cx, cy, width - 12, height - 12, label, key.target());
                if (key.controllerDouble()) {
                    Label mark = new Label("W", "", "", 0xffffffff, 0xffffffff, false, false);
                    glyph(
                            g,
                            "W",
                            width * .70f,
                            height * .73f,
                            width * .38f,
                            height * .32f,
                            mark,
                            false);
                }
            } else {
                glyph(
                        g,
                        label.first(),
                        cx,
                        height * .235f,
                        width - 12,
                        height * .30f,
                        label,
                        key.target());
                glyph(
                        g,
                        label.second(),
                        cx,
                        height * .765f,
                        width - 12,
                        height * .30f,
                        label,
                        key.target());
                glyph(g, label.join(), cx, cy, width * .3f, height * .13f, label, key.target());
            }
        } finally {
            g.dispose();
        }
        NativeImage image = new NativeImage(width * 2, height * 2, true);
        for (int y = 0; y < height * 2; y++) {
            for (int x = 0; x < width * 2; x++) {
                int c = bitmap.getRGB(x, y);
                image.setPixelRGBA(x, y, (c & 0xff00ff00) | ((c & 255) << 16) | ((c >> 16) & 255));
            }
        }
        return image;
    }

    private static Shape outline(Graphics2D g, String text) {
        if (text.equals(SPACE_ICON)) {
            Path2D.Float shape = new Path2D.Float();
            shape.moveTo(-44, -38);
            shape.lineTo(-30, -38);
            shape.lineTo(-30, 20);
            shape.lineTo(30, 20);
            shape.lineTo(30, -38);
            shape.lineTo(44, -38);
            shape.lineTo(44, 38);
            shape.lineTo(-44, 38);
            shape.closePath();
            return shape;
        }
        Font font = new Font("Arial Black", Font.BOLD, 100);
        if (font.canDisplayUpTo(text) >= 0) {
            font = new Font("Dialog", Font.BOLD, 100);
        }
        return font.createGlyphVector(g.getFontRenderContext(), text).getOutline();
    }

    private static void glyph(
            Graphics2D g,
            String text,
            float cx,
            float cy,
            float width,
            float height,
            Label label,
            boolean target) {
        Shape raw = outline(g, text);
        Rectangle2D bounds = raw.getBounds2D();
        if (bounds.isEmpty()) {
            return;
        }
        double scale = Math.min(width / bounds.getWidth(), height / bounds.getHeight());
        AffineTransform transform = new AffineTransform();
        transform.translate(cx, cy);
        transform.scale(scale, scale);
        transform.translate(-bounds.getCenterX(), -bounds.getCenterY());
        Shape shape = transform.createTransformedShape(raw);
        float stroke = Math.max(1.1f, Math.min(3.2f, height * .075f));
        g.setStroke(new BasicStroke(stroke * 2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.BLACK);
        g.draw(shape);
        if (target || label.hold()) {
            g.setStroke(
                    new BasicStroke(stroke * 1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.WHITE);
            g.draw(shape);
        }
        if (target) {
            int fill =
                    label.filledTarget()
                            ? (label.color() == 0xffff4800 ? 0xffffdf21 : label.color())
                            : 0xff282852;
            g.setColor(new Color(fill, true));
            g.fill(shape);
        } else {
            g.setPaint(
                    new GradientPaint(
                            cx,
                            cy - height * .5f,
                            new Color(label.highlight(), true),
                            cx,
                            cy + height * .2f,
                            new Color(label.color(), true)));
            g.fill(shape);
        }
    }
}
