package dev.flower.stevedance.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;

import dev.flower.stevedance.MotionPackLibrary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 源AET前景镜头；表演者保留在关卡渲染器中。
 * <p>Source AET foreground footage; the performers remain in the level renderer.</p>
 */
public final class PvAetTimeline {
    public static final class Key {
        float Frame, Value, Tangent;
    }

    public static final class Curve {
        Key[] Keys;
    }

    public static final class Transfer {
        int BlendMode, Flags, TrackMatte;
    }

    public static final class ThreeD {
        Curve AnchorZ, PositionZ, DirectionX, DirectionY, DirectionZ, RotationX, RotationY, ScaleZ;
    }

    public static final class Video {
        Curve AnchorX, AnchorY, PositionX, PositionY, Rotation, ScaleX, ScaleY, Opacity;
        Transfer TransferMode;
        ThreeD Video3D;
    }

    public static final class Item {
        String type;
        int index;
    }

    public static final class Layer {
        int id, parent;
        String name, flags;
        float start, end, offset, timeScale;
        Video video;
        Item item;
    }

    public static final class Composition {
        Layer[] layers;
    }

    public static final class Source {
        String name, sprite;
    }

    public static final class Footage {
        int width, height, frames;
        float[] color;
        Source[] sources;
    }

    public static final class Scene {
        float width, height, fps;
        Composition[] compositions;
        Footage[] videos;
        transient Map<Integer, Layer> parents;
        transient Map<String, Layer> roots;
        transient Group group;
    }

    public static final class Sprite {
        String name;
        int texture;
        float x, y, width, height;
        float[] uvBegin, uvEnd;
    }

    public static final class Atlas {
        int width, height;
        String file;
    }

    public static final class Group {
        Sprite[] sprites;
        Atlas[] textures;
        transient Map<String, Sprite> byName;
        transient ResourceLocation[] locations;
        transient byte[][] images;
    }

    public static final class SceneRef {
        String file, group;
    }

    public static final class Cue {
        public String scene, root, target;
        public float frameStart, brightScale;
        public int priority;
    }

    public static final class Cut {
        public double timeMs;
        public int field;
        public Cue[] layers;
    }

    public static final class LyricEvent {
        public double timeMs;
        public int line, color;
    }

    public static final class LyricLine {
        public String text, alternateText;
        public int stringId, alternateStringId;
    }

    public static final class Lyrics {
        public String language;
        public Map<String, LyricLine> lines;
        public LyricEvent[] events;
        Video layout;
        float rasterScale;
        boolean centerInMv;
    }

    public static final class Data {
        public int schema;
        Map<String, Group> groups;
        Map<String, SceneRef> scenes;
        public Cut[] timeline;
        public Lyrics lyrics;
        transient Map<String, Scene> loaded = new HashMap<>();
    }

    private static Data installed;
    private static final List<ResourceLocation> registered = new ArrayList<>();
    private static int budget;
    public static int draws, lastField, lastLyric;
    public static double lastTimeMs;

    private static <T> T json(Path root, String name, Class<T> type) throws IOException {
        Path file = PvSceneData.safe(root, name);
        if (Files.size(file) > 16 * 1024 * 1024) {
            throw new IOException("AET metadata budget exceeded");
        }
        try (var r = dev.flower.stevedance.content.PackedResources.reader(file, 16 * 1024 * 1024)) {
            return MotionPackLibrary.GSON.fromJson(r, type);
        }
    }

    /**
     * 由现有背景歌曲加载器调用，不进行GL调用或帧时IO。
     * <p>Called by the existing background song loader, without GL calls or frame-time IO.</p>
     */
    public static Data load(Path song) throws IOException {
        if (!Files.isRegularFile(song.resolve("overlay/manifest.json"))) {
            return null;
        }
        Path root = song.resolve("overlay");
        Data data = json(root, "manifest.json", Data.class);
        if (data.schema != 1
                || data.groups == null
                || data.groups.size() > 8
                || data.scenes == null
                || data.scenes.size() > 8
                || data.timeline == null
                || data.timeline.length > 1024) {
            throw new IOException("Invalid AET manifest");
        }
        // 中文：旧版烘焙歌词图集不是PV镜头或字幕依赖项。
        // EN: Legacy baked lyric atlases are not PV footage or a subtitle dependency.
        data.groups.remove("lyrics");
        long pixels = 0;
        for (var e : data.groups.entrySet()) {
            if (!e.getKey().matches("[a-z0-9_]+")) {
                throw new IOException("Invalid AET group name");
            }
            Group g = e.getValue();
            if (g.textures == null
                    || g.textures.length > 64
                    || g.sprites == null
                    || g.sprites.length > 4096) {
                throw new IOException("AET atlas count budget exceeded");
            }
            g.byName = new HashMap<>();
            g.locations = new ResourceLocation[g.textures.length];
            g.images = new byte[g.textures.length][];
            for (var s : g.sprites) {
                if (s.texture < 0
                        || s.texture >= g.textures.length
                        || !Float.isFinite(s.width)
                        || !Float.isFinite(s.height)
                        || s.width <= 0
                        || s.height <= 0
                        || g.byName.put(s.name, s) != null) {
                    throw new IOException("Invalid AET sprite");
                }
                var a = g.textures[s.texture];
                // 中文：显示尺寸和打包矩形在下采样精灵上有所不同。
                // EN: Display dimensions and the packed rectangle differ on downsampled sprites.
                // 中文：SPRC矩形端点是原始规范化采样坐标。
                // EN: SPRC rectangle endpoints are the original normalized sampling coordinates.
                if (s.uvBegin == null && s.uvEnd == null) {
                    s.uvBegin = new float[] {s.x / a.width, s.y / a.height};
                    s.uvEnd = new float[] {(s.x + s.width) / a.width, (s.y + s.height) / a.height};
                }
                if (s.uvBegin == null
                        || s.uvEnd == null
                        || s.uvBegin.length != 2
                        || s.uvEnd.length != 2) {
                    throw new IOException("Invalid AET UV rectangle: " + s.name);
                }
                for (int axis = 0; axis < 2; axis++) {
                    if (!Float.isFinite(s.uvBegin[axis])
                            || !Float.isFinite(s.uvEnd[axis])
                            || s.uvBegin[axis] < 0
                            || s.uvEnd[axis] > 1
                            || s.uvBegin[axis] >= s.uvEnd[axis]) {
                        throw new IOException("AET sprite outside atlas: " + s.name);
                    }
                }
            }
            for (int i = 0; i < g.textures.length; i++) {
                var a = g.textures[i];
                pixels += (long) a.width * a.height;
                if (a.width < 1
                        || a.height < 1
                        || a.width > 4096
                        || a.height > 4096
                        || pixels > 32_000_000) {
                    throw new IOException("AET atlas size budget exceeded");
                }
                // 曲包清单显式记录图集路径，页码只用于精灵索引。
                // The manifest names atlas files; page indices only bind sprite rectangles.
                if (a.file == null || !a.file.matches("[a-z0-9_]+\\.png")) {
                    throw new IOException("Unsafe overlay atlas filename");
                }
                Path file = PvSceneData.safe(root, a.file);
                if (Files.size(file) > 8 * 1024 * 1024) {
                    throw new IOException("AET image budget exceeded");
                }
                g.images[i] = Files.readAllBytes(file);
            }
        }
        int layers = 0;
        for (var e : data.scenes.entrySet()) {
            Scene s = json(root, e.getValue().file, Scene.class);
            s.group = data.groups.get(e.getValue().group);
            s.parents = new HashMap<>();
            s.roots = new HashMap<>();
            if (s.group == null
                    || !Float.isFinite(s.fps)
                    || s.fps <= 0
                    || s.fps > 120
                    || !Float.isFinite(s.width)
                    || !Float.isFinite(s.height)
                    || s.width < 1
                    || s.height < 1
                    || s.width > 4096
                    || s.height > 4096
                    || s.compositions == null
                    || s.compositions.length == 0
                    || s.compositions.length > 512
                    || s.videos == null
                    || s.videos.length > 4096) {
                throw new IOException("Invalid AET scene");
            }
            for (var c : s.compositions) {
                for (var l : c.layers) {
                    if (++layers > 8192
                            || s.parents.put(l.id, l) != null
                            || l.flags == null
                            || !Float.isFinite(l.start)
                            || !Float.isFinite(l.end)
                            || !Float.isFinite(l.timeScale)) {
                        throw new IOException("Invalid AET layer");
                    }
                    if (l.item != null) {
                        int limit =
                                l.item.type.equals("composition")
                                        ? s.compositions.length
                                        : l.item.type.equals("video") ? s.videos.length : 0;
                        if (l.item.index < 0 || l.item.index >= limit) {
                            throw new IOException("Invalid AET item reference");
                        }
                    }
                    if (l.video != null && l.video.TransferMode != null) {
                        var t = l.video.TransferMode;
                        if (!Set.of(3, 5, 6, 7, 23).contains(t.BlendMode)
                                || t.TrackMatte < 0
                                || t.TrackMatte > 4
                                || (t.Flags & ~1) != 0
                                || (t.Flags != 0 && t.TrackMatte != 0)) {
                            throw new IOException("Unsupported AET transfer mode on " + l.name);
                        }
                    }
                }
            }
            for (var l : s.compositions[s.compositions.length - 1].layers) {
                s.roots.put(l.name, l);
            }
            for (var v : s.videos) {
                for (var src : v.sources) {
                    if (!s.group.byName.containsKey(src.sprite)) {
                        throw new IOException("Missing AET sprite: " + src.sprite);
                    }
                }
            }
            data.loaded.put(e.getKey(), s);
        }
        double previous = -1;
        for (var c : data.timeline) {
            if (c.timeMs < previous || c.layers == null || c.layers.length > 16) {
                throw new IOException("Invalid AET timeline");
            }
            previous = c.timeMs;
            for (var cue : c.layers) {
                var s = data.loaded.get(cue.scene);
                if (s == null
                        || !s.roots.containsKey(cue.root)
                        || !Float.isFinite(cue.brightScale)
                        || cue.brightScale < 0
                        || cue.brightScale > 8) {
                    throw new IOException("Missing AET root");
                }
            }
        }
        return data;
    }

    public static void install(Data data) throws IOException {
        clear();
        if (data == null) {
            return;
        }
        var manager = Minecraft.getInstance().getTextureManager();
        try {
            for (var e : data.groups.entrySet()) {
                Group g = e.getValue();
                for (int i = 0; i < g.images.length; i++) {
                    NativeImage img = AssetImage.read(new ByteArrayInputStream(g.images[i]));
                    var a = g.textures[i];
                    if (img.getWidth() != a.width || img.getHeight() != a.height) {
                        img.close();
                        throw new IOException("AET image dimension mismatch");
                    }
                    var t = new DynamicTexture(img);
                    t.setFilter(true, false);
                    var id = manager.register("stevedance_pv_aet_" + e.getKey() + "_" + i, t);
                    g.locations[i] = id;
                    registered.add(id);
                }
                g.images = null;
            }
            installed = data;
        } catch (IOException | RuntimeException ex) {
            clear();
            throw ex;
        }
    }

    public static boolean ready() {
        return installed != null;
    }

    public static Cut cut(Data data, double ms) {
        int lo = 0, hi = data.timeline.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (data.timeline[m].timeMs <= ms) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo == 0 ? null : data.timeline[lo - 1];
    }

    public static LyricEvent lyric(Data data, double ms) {
        int lo = 0, hi = data.lyrics.events.length;
        while (lo < hi) {
            int m = (lo + hi) >>> 1;
            if (data.lyrics.events[m].timeMs <= ms) {
                lo = m + 1;
            } else {
                hi = m;
            }
        }
        return lo == 0 ? null : data.lyrics.events[lo - 1];
    }

    public static void draw(GuiGraphics g, int width, int height, double ms) {
        drawTarget(g, width, height, ms, false);
    }

    public static boolean hasBack(double ms) {
        if (installed == null || !enabled) {
            return false;
        }
        var c = cut(installed, ms);
        if (c == null) {
            return false;
        }
        for (var cue : c.layers) {
            if ("back".equals(cue.target)
                    && dev.flower.stevedance.client.PvVisibility.overlay(cue.scene, cue.root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 原始SPR_TARGET_BACK在世界几何体之前绘制，不写入世界深度。
     * <p>Original SPR_TARGET_BACK is drawn before world geometry, without writing world depth.</p>
     */
    public static void drawBack() {
        if (!PvPlaybackController.sceneryActive() || !hasBack(PvPlaybackController.timeMs())) {
            return;
        }
        var mc = Minecraft.getInstance();
        var projection = new org.joml.Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        RenderSystem.getModelViewStack().pushPose();
        try {
            RenderSystem.getModelViewStack().setIdentity();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(
                    new org.joml.Matrix4f()
                            .setOrtho(
                                    0,
                                    mc.getWindow().getWidth(),
                                    mc.getWindow().getHeight(),
                                    0,
                                    -1000,
                                    1000),
                    VertexSorting.ORTHOGRAPHIC_Z);
            RenderSystem.depthMask(false);
            drawTarget(
                    new GuiGraphics(mc, mc.renderBuffers().bufferSource()),
                    mc.getWindow().getWidth(),
                    mc.getWindow().getHeight(),
                    PvPlaybackController.timeMs(),
                    true);
        } finally {
            RenderSystem.depthMask(depthMask);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.getModelViewStack().popPose();
            RenderSystem.applyModelViewMatrix();
            GL20.glUseProgram(oldProgram);
        }
    }

    private static void drawTarget(GuiGraphics g, int width, int height, double ms, boolean back) {
        if (installed == null || !enabled) {
            return;
        }
        draws = budget = 0;
        lastTimeMs = ms;
        var data = installed;
        g.flush();
        g.pose().pushPose();
        try {
            RenderSystem.disableDepthTest();
            Cut c = cut(data, ms);
            lastField = c == null ? -1 : c.field;
            if (c != null) {
                for (var cue : c.layers) {
                    if (back != "back".equals(cue.target)
                            || !dev.flower.stevedance.client.PvVisibility.overlay(
                                    cue.scene, cue.root)) {
                        continue;
                    }
                    var s = data.loaded.get(cue.scene);
                    float scale = Math.min(width / s.width, height / s.height);
                    g.pose().pushPose();
                    try {
                        g.pose()
                                .translate(
                                        (width - s.width * scale) / 2,
                                        (height - s.height * scale) / 2,
                                        0);
                        g.pose().scale(scale, scale, 1);
                        render(
                                g,
                                s,
                                s.roots.get(cue.root),
                                cue.frameStart + (float) ((ms - c.timeMs) * s.fps / 1000),
                                1,
                                cue.brightScale,
                                0,
                                false);
                    } finally {
                        g.pose().popPose();
                    }
                }
            }

        } finally {
            g.flush();
            g.pose().popPose();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            g.setColor(1, 1, 1, 1);
        }
    }

    private static void transform(GuiGraphics g, Scene s, Layer l, float f, int depth) {
        if (depth > 24) {
            throw new IllegalStateException("AET parent cycle");
        }
        if (l.parent >= 0) {
            Layer p = s.parents.get(l.parent);
            if (p == null) {
                throw new IllegalStateException("Missing AET parent");
            }
            transform(g, s, p, f, depth + 1);
        }
        Video v = l.video;
        if (v == null) {
            return;
        }
        ThreeD d = v.Video3D;
        g.pose()
                .translate(
                        value(v.PositionX, f, 0),
                        value(v.PositionY, f, 0),
                        d == null ? 0 : value(d.PositionZ, f, 0));
        if (d != null) {
            g.pose().mulPose(Axis.ZP.rotationDegrees(value(d.DirectionZ, f, 0)));
            g.pose().mulPose(Axis.YP.rotationDegrees(value(d.DirectionY, f, 0)));
            g.pose().mulPose(Axis.XP.rotationDegrees(value(d.DirectionX, f, 0)));
            g.pose().mulPose(Axis.YP.rotationDegrees(value(d.RotationY, f, 0)));
            g.pose().mulPose(Axis.XP.rotationDegrees(value(d.RotationX, f, 0)));
        }
        g.pose().mulPose(Axis.ZP.rotationDegrees(value(v.Rotation, f, 0)));
        g.pose()
                .scale(
                        value(v.ScaleX, f, 1),
                        value(v.ScaleY, f, 1),
                        d == null ? 1 : value(d.ScaleZ, f, 1));
        g.pose()
                .translate(
                        -value(v.AnchorX, f, 0),
                        -value(v.AnchorY, f, 0),
                        d == null ? 0 : -value(d.AnchorZ, f, 0));
    }

    private static int matte(Layer l) {
        return l.video == null || l.video.TransferMode == null
                ? 0
                : l.video.TransferMode.TrackMatte;
    }

    private static int blend(Layer l) {
        return l.video == null || l.video.TransferMode == null ? 3 : l.video.TransferMode.BlendMode;
    }

    private static boolean preserveAlpha(Layer l) {
        return l.video != null
                && l.video.TransferMode != null
                && (l.video.TransferMode.Flags & 1) != 0;
    }

    private static void render(
            GuiGraphics g,
            Scene s,
            Layer l,
            float f,
            float alpha,
            float bright,
            int depth,
            boolean mask) {
        if (depth > 24 || ++budget > 4096) {
            throw new IllegalStateException("AET rendering budget exceeded");
        }
        if (f < l.start || f >= l.end || (!mask && !l.flags.contains("VideoActive"))) {
            return;
        }
        float opacity = alpha * (l.video == null ? 1 : value(l.video.Opacity, f, 1));
        if (opacity <= .001) {
            return;
        }
        g.pose().pushPose();
        try {
            transform(g, s, l, f, 0);
            float local = (f - l.start) * l.timeScale + l.offset;
            if (l.item == null) {
                return;
            }
            if (l.item.type.equals("composition")) {
                if ((blend(l) != 3 || preserveAlpha(l)) && !mask) {
                    PvAetEffects.composed(
                            g,
                            blend(l),
                            opacity,
                            preserveAlpha(l),
                            () -> children(g, s, l, local, 1, bright, depth));
                } else {
                    children(g, s, l, local, opacity, bright, depth);
                }
            } else {
                var video = s.videos[l.item.index];
                if (video.sources.length > 0) {
                    var src =
                            video.sources[
                                    Math.max(0, Math.min(video.sources.length - 1, (int) local))];
                    var sprite = s.group.byName.get(src.sprite);
                    blit(
                            g,
                            s.group,
                            sprite,
                            video.width,
                            video.height,
                            opacity,
                            bright,
                            blend(l),
                            0xffffffff,
                            preserveAlpha(l));
                } else if (video.color != null && video.color.length >= 4) {
                    int a = (int) (Math.min(1, opacity * video.color[3]) * 255);
                    int color =
                            (a << 24)
                                    | ((int) (video.color[0] * 255) << 16)
                                    | ((int) (video.color[1] * 255) << 8)
                                    | (int) (video.color[2] * 255);
                    g.fill(0, 0, video.width, video.height, color);
                }
            }
        } finally {
            g.pose().popPose();
        }
    }

    static float value(Curve curve, float frame, float fallback) {
        if (curve == null || curve.Keys == null || curve.Keys.length == 0) {
            return fallback;
        }
        Key[] keys = curve.Keys;
        if (frame <= keys[0].Frame) {
            return keys[0].Value;
        }
        int lo = 0, hi = keys.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid].Frame <= frame) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        if (frame >= keys[hi].Frame) {
            return keys[hi].Value;
        }
        Key a = keys[lo], b = keys[hi];
        float dt = b.Frame - a.Frame;
        if (dt <= 0) {
            return b.Value;
        }
        float t = (frame - a.Frame) / dt, t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * a.Value
                + (t3 - 2 * t2 + t) * a.Tangent * dt
                + (-2 * t3 + 3 * t2) * b.Value
                + (t3 - t2) * b.Tangent * dt;
    }

    private static void children(
            GuiGraphics g,
            Scene s,
            Layer parent,
            float frame,
            float opacity,
            float bright,
            int depth) {
        var layers = s.compositions[parent.item.index].layers;
        for (int i = layers.length - 1; i >= 0; i--) {
            if (i + 1 < layers.length && matte(layers[i + 1]) != 0) {
                continue;
            }
            Layer child = layers[i];
            if (matte(child) != 0) {
                if (i == 0) {
                    throw new IllegalStateException("Missing AET matte layer");
                }
                Layer m = layers[i - 1];
                PvAetEffects.masked(
                        g,
                        matte(child),
                        blend(child),
                        () -> render(g, s, child, frame, opacity, bright, depth + 1, false),
                        () -> render(g, s, m, frame, 1, 1, depth + 1, true));
            } else {
                render(g, s, child, frame, opacity, bright, depth + 1, false);
            }
        }
    }

    private static void blit(
            GuiGraphics g,
            Group group,
            Sprite s,
            float width,
            float height,
            float alpha,
            float bright,
            int mode,
            int color,
            boolean preserveAlpha) {
        Atlas a = group.textures[s.texture];
        g.flush();
        PvAetEffects.spriteShader(mode, bright);
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        int destination = mode == 5 || mode == 23 ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA;
        int source = mode == 6 ? GL11.GL_DST_COLOR : GL11.GL_SRC_ALPHA;
        if (mode == 7) {
            source = GL11.GL_ONE;
            destination = GL11.GL_ONE_MINUS_SRC_COLOR;
        }
        RenderSystem.setShaderTexture(0, group.locations[s.texture]);
        var matrix = g.pose().last().pose();
        var b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float red = ((color >> 16) & 255) / 255f,
                green = ((color >> 8) & 255) / 255f,
                blue = (color & 255) / 255f;
        float u = s.uvBegin[0],
                v = s.uvBegin[1],
                ue = s.uvEnd[0],
                ve = s.uvEnd[1],
                opacity = Math.min(1, alpha) * ((color >>> 24) / 255f);
        b.vertex(matrix, 0, height, 0).uv(u, ve).color(red, green, blue, opacity).endVertex();
        b.vertex(matrix, width, height, 0).uv(ue, ve).color(red, green, blue, opacity).endVertex();
        b.vertex(matrix, width, 0, 0).uv(ue, v).color(red, green, blue, opacity).endVertex();
        b.vertex(matrix, 0, 0, 0).uv(u, v).color(red, green, blue, opacity).endVertex();
        PvAetEffects.draw(b.end(), source, destination, preserveAlpha);
        draws++;
    }

    public static boolean enabled = true;

    public static void clear() {
        installed = null;
        var manager = Minecraft.getInstance().getTextureManager();
        for (var id : registered) {
            manager.release(id);
        }
        registered.clear();
        PvAetEffects.clear();
    }
}
