package dev.flower.stevedance.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;

import dev.flower.stevedance.RhythmChartLibrary;
import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 在游戏世界上渲染导出的原始After Effects合成。
 * <p>Renders the exported original After Effects compositions over the game world.</p>
 */
public final class OriginalUiAssets {
    public static final float WIDTH = 1280, HEIGHT = 728;
    private static float canvasMarginX, canvasMarginY, edgeOverdraw;
    private static boolean compactCanvas;

    public static float marginX() {
        return canvasMarginX;
    }

    public static float marginY() {
        return canvasMarginY;
    }

    private static final ExecutorService LOADER =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "SteveDance UI loader");
                        t.setDaemon(true);
                        return t;
                    });
    private static volatile boolean loading, ready;
    public static String error = "";
    private static int generation;
    private static final Map<String, Scene> scenes = new HashMap<>();
    private static final Map<String, Group> groups = new HashMap<>();
    private static final List<ResourceLocation> registered = new ArrayList<>();

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

    public static final class Marker {
        float frame;
        String name;
    }

    public static final class Layer {
        int id, parent;
        String name, flags;
        float start, end, offset, timeScale;
        Video video;
        Item item;
        Marker[] markers;
        transient float glyphX = 480, glyphY = 272;
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
        transient String id;
    }

    public static final class Sprite {
        String name;
        int texture;
        float x, y, width, height;
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
    }

    public static final class Glyph {
        boolean half;
        int column, row, offset, width;
    }

    public static final class NativeFont {
        int mapId, advance, line, boxWidth, boxHeight;
        Map<String, Glyph> glyphs;
    }

    private static final Map<Integer, NativeFont> fonts = new HashMap<>();

    private record Images(
            Map<String, Scene> scenes,
            Map<String, Group> groups,
            Map<String, NativeImage[]> images) {}

    public static boolean ready() {
        return ready;
    }

    public static void prepare() {
        if (ready || loading || !error.isEmpty()) {
            return;
        }
        loading = true;
        int ticket = ++generation;
        CompletableFuture.supplyAsync(
                        () -> {
                            Map<String, NativeImage[]> images = new HashMap<>();
                            try {
                                Path root = RhythmChartLibrary.root().resolve("ui");
                                JsonObject manifest =
                                        JsonParser.parseString(
                                                        Files.readString(
                                                                root.resolve("manifest.json")))
                                                .getAsJsonObject();
                                if (manifest.get("schema").getAsInt() != 1) {
                                    throw new java.io.IOException("UI schema mismatch");
                                }
                                Map<String, Scene> loadedScenes = new HashMap<>();
                                Map<String, Group> loadedGroups = new HashMap<>();
                                long pixels = 0;
                                for (var entry : manifest.getAsJsonObject("groups").entrySet()) {
                                    String name = entry.getKey();
                                    if (!name.matches("[a-z0-9_]+")) {
                                        throw new java.io.IOException("Unsafe atlas group");
                                    }
                                    Group g =
                                            RhythmChartLibrary.JSON.fromJson(
                                                    entry.getValue(), Group.class);
                                    g.byName = new HashMap<>();
                                    for (Sprite s : g.sprites) {
                                        g.byName.put(s.name, s);
                                    }
                                    g.locations = new ResourceLocation[g.textures.length];
                                    NativeImage[] atlases = new NativeImage[g.textures.length];
                                    images.put(name, atlases);
                                    for (int i = 0; i < atlases.length; i++) {
                                        Atlas a = g.textures[i];
                                        // 清单显式命名图集，不再从原作页码猜文件名。
                                        // Atlas paths are explicit; source page numbers are not filenames.
                                        if (a.file == null || !a.file.matches("[a-z0-9_]+\\.png")) {
                                            throw new java.io.IOException("Unsafe atlas filename");
                                        }
                                        pixels += (long) a.width * a.height;
                                        if (a.width < 1
                                                || a.height < 1
                                                || a.width > 4096
                                                || a.height > 4096
                                                || pixels > 64000000) {
                                            throw new java.io.IOException(
                                                    "UI atlas budget exceeded");
                                        }
                                        try (var stream =
                                                Files.newInputStream(
                                                        root.resolve(name).resolve(a.file))) {
                                            atlases[i] = AssetImage.read(stream);
                                        }
                                        if (atlases[i].getWidth() != a.width
                                                || atlases[i].getHeight() != a.height) {
                                            throw new java.io.IOException(
                                                    "UI atlas dimensions mismatch");
                                        }
                                    }
                                    loadedGroups.put(name, g);
                                }
                                for (var e : manifest.getAsJsonObject("scenes").entrySet()) {
                                    String name = e.getKey();
                                    if (!name.matches("[a-z0-9_]+")) {
                                        throw new java.io.IOException("Unsafe scene name");
                                    }
                                    Scene s =
                                            RhythmChartLibrary.JSON.fromJson(
                                                    Files.readString(root.resolve(name + ".json")),
                                                    Scene.class);
                                    s.id = name;
                                    s.group =
                                            loadedGroups.get(
                                                    e.getValue()
                                                            .getAsJsonObject()
                                                            .get("group")
                                                            .getAsString());
                                    s.parents = new HashMap<>();
                                    s.roots = new HashMap<>();
                                    for (Composition c : s.compositions) {
                                        for (Layer l : c.layers) {
                                            s.parents.put(l.id, l);
                                        }
                                    }
                                    for (Layer l :
                                            s.compositions[s.compositions.length - 1].layers) {
                                        s.roots.put(l.name, l);
                                    }
                                    loadedScenes.put(name, s);
                                }
                                for (String gameplayId : List.of("gameplay_hud", "rush_notes")) {
                                    Scene gameplay = loadedScenes.get(gameplayId);
                                    if (gameplay != null) {
                                        for (Layer noteRoot : gameplay.roots.values()) {
                                            if (noteRoot.name.startsWith("target_")
                                                    || noteRoot.name.startsWith("button_")) {
                                                markNoteCenter(
                                                        gameplay,
                                                        noteRoot,
                                                        value(noteRoot.video.AnchorX, 0, 480),
                                                        value(noteRoot.video.AnchorY, 0, 272),
                                                        0);
                                            }
                                        }
                                    }
                                }
                                return new Images(loadedScenes, loadedGroups, images);
                            } catch (Exception ex) {
                                for (var a : images.values()) {
                                    for (var i : a) {
                                        if (i != null) {
                                            i.close();
                                        }
                                    }
                                }
                                throw new CompletionException(ex);
                            }
                        },
                        LOADER)
                .whenComplete(
                        (data, failure) ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    loading = false;
                                                    if (ticket != generation) {
                                                        if (data != null) {
                                                            for (var a : data.images.values()) {
                                                                for (var i : a) {
                                                                    i.close();
                                                                }
                                                            }
                                                        }
                                                        return;
                                                    }
                                                    if (failure != null) {
                                                        error = "ui.stevedance.f2_ui_failed";
                                                        SteveDanceMod.LOGGER.error(
                                                                "F 2nd UI import failed", failure);
                                                        return;
                                                    }
                                                    for (var e : data.groups.entrySet()) {
                                                        for (int i = 0;
                                                                i < e.getValue().textures.length;
                                                                i++) {
                                                            ResourceLocation loc =
                                                                    new ResourceLocation(
                                                                            "stevedance",
                                                                            "f2_external/"
                                                                                    + e.getKey()
                                                                                    + "/"
                                                                                    + i);
                                                            Minecraft.getInstance()
                                                                    .getTextureManager()
                                                                    .register(
                                                                            loc,
                                                                            new DynamicTexture(
                                                                                    data.images
                                                                                            .get(
                                                                                                    e
                                                                                                            .getKey())[
                                                                                            i]));
                                                            e.getValue().locations[i] = loc;
                                                            registered.add(loc);
                                                        }
                                                    }
                                                    groups.putAll(data.groups);
                                                    scenes.putAll(data.scenes);
                                                    try {
                                                        var file =
                                                                RhythmChartLibrary.root()
                                                                        .resolve(
                                                                                "ui/font-map.json");
                                                        JsonObject map =
                                                                JsonParser.parseString(
                                                                                Files.readString(
                                                                                        file))
                                                                        .getAsJsonObject();
                                                        for (var f : map.getAsJsonArray("fonts")) {
                                                            NativeFont font =
                                                                    RhythmChartLibrary.JSON
                                                                            .fromJson(
                                                                                    f,
                                                                                    NativeFont
                                                                                            .class);
                                                            fonts.put(font.mapId, font);
                                                        }
                                                    } catch (Exception ex) {
                                                        SteveDanceMod.LOGGER.warn(
                                                                "Original F 2nd font mapping unavailable",
                                                                ex);
                                                    }
                                                    ready = true;
                                                    SteveDanceMod.LOGGER.info(
                                                            "F 2nd original UI ready: {} scenes, {} atlases",
                                                            scenes.size(),
                                                            registered.size());
                                                }));
    }

    public static float begin(GuiGraphics g, int width, int height) {
        return begin(g, width, height, 1, false);
    }

    public static float begin(GuiGraphics g, int width, int height, float zoom, boolean compact) {
        g.flush();
        RenderSystem.disableDepthTest();
        compactCanvas = compact;
        float scale = Math.min(width / WIDTH, height / HEIGHT) * zoom;
        canvasMarginX = (width / scale - WIDTH) / 2;
        canvasMarginY = (height / scale - HEIGHT) / 2;
        edgeOverdraw = 1 / scale;
        g.pose().pushPose();
        g.pose().translate((width - WIDTH * scale) / 2, (height - HEIGHT * scale) / 2, 0);
        g.pose().scale(scale, scale, 1);
        return scale;
    }

    /**
     * 帧填充视口，而音符和卡片保持其原始纵横比。
     * <p>Frames fill the viewport while notes and cards keep their original aspect ratio.</p>
     */
    public static void edge(GuiGraphics g, boolean bottom, boolean stretch, Runnable draw) {
        g.pose().pushPose();
        if (!compactCanvas) {
            g.pose()
                    .translate(
                            -canvasMarginX - (stretch ? edgeOverdraw : 0),
                            bottom ? canvasMarginY : -canvasMarginY,
                            0);
            if (stretch) {
                g.pose().scale((WIDTH + 2 * (canvasMarginX + edgeOverdraw)) / WIDTH, 1, 1);
            }
        }
        try {
            draw.run();
        } finally {
            g.pose().popPose();
        }
    }

    public static void fullCanvas(GuiGraphics g, Runnable draw) {
        if (compactCanvas) {
            draw.run();
            return;
        }
        float padding = Math.max(16, edgeOverdraw);
        g.pose().pushPose();
        g.pose().translate(-canvasMarginX - padding, -canvasMarginY - padding, 0);
        g.pose()
                .scale(
                        (WIDTH + 2 * (canvasMarginX + padding)) / WIDTH,
                        (HEIGHT + 2 * (canvasMarginY + padding)) / HEIGHT,
                        1);
        try {
            draw.run();
        } finally {
            g.pose().popPose();
        }
    }

    public static void end(GuiGraphics g) {
        g.flush();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        g.setColor(1, 1, 1, 1);
        g.pose().popPose();
        compactCanvas = false;
    }

    public static boolean has(String scene, String layer) {
        Scene s = scenes.get(scene);
        return s != null && s.roots.containsKey(layer);
    }

    public static float exitFrame(String layer, int ageMs) {
        Scene s = scenes.get("gameplay_hud");
        Layer l = s == null ? null : s.roots.get(layer);
        if (l == null) {
            return 360 + ageMs * .06f;
        }
        float start = 360;
        for (Marker m : l.markers) {
            if (m.name.equals("out_start")) {
                start = m.frame;
            }
        }
        return start + ageMs * .06f;
    }

    /**
     * 原始移动音符缩放曲线也驱动目标的运行时移除。
     * <p>Original moving-note scale curve also drives the target's runtime removal.</p>
     */
    public static float exitScale(String layer, int ageMs) {
        Scene s = scenes.get("gameplay_hud");
        Layer l = s == null ? null : s.roots.get(layer);
        if (l == null || l.item == null || !"composition".equals(l.item.type)) {
            return 0;
        }
        float f = exitFrame(layer, ageMs), start = exitFrame(layer, 0);
        if (f >= l.end) {
            return 0;
        }
        for (Layer c : s.compositions[l.item.index].layers) {
            Curve curve = c.video == null ? null : c.video.ScaleX;
            if (curve != null
                    && curve.Keys.length > 1
                    && curve.Keys[curve.Keys.length - 1].Frame > start) {
                float initial = value(curve, start, 1);
                if (initial > 0) {
                    return Math.max(0, value(curve, f, 0) / initial);
                }
            }
        }
        return Math.max(0, 1 - (f - start) / Math.max(1, l.end - start));
    }

    public static float loopFrame(String scene, String layer, double seconds) {
        Scene s = scenes.get(scene);
        if (s == null) {
            return 0;
        }
        Layer l = s.roots.get(layer);
        if (l == null) {
            return 0;
        }
        float start = l.start, end = l.end, hold = -1;
        boolean loop = false;
        for (Marker m : l.markers) {
            if (m.name.equals("loop_start")) {
                start = m.frame;
                loop = true;
            }
            if (m.name.equals("loop_end")) {
                end = m.frame;
            }
            if (m.name.equals("in_ed")) {
                hold = m.frame;
            }
        }
        if (!loop && hold >= 0) {
            return Math.min(hold, start + (float) (seconds * 60));
        }
        return start + (float) (seconds * 60 % Math.max(1, end - start - .001));
    }

    public static void loop(GuiGraphics g, String scene, String layer, double seconds) {
        draw(g, scene, layer, loopFrame(scene, layer, seconds), Map.of());
    }

    /**
     * 循环边界处入切线稳定后的稳定帧。
     * <p>Stable frame after the incoming tangent at a loop boundary has settled.</p>
     */
    public static float settledFrame(String scene, String layer) {
        Scene s = scenes.get(scene);
        Layer l = s == null ? null : s.roots.get(layer);
        if (l == null) {
            return 0;
        }
        for (Marker marker : l.markers) {
            if (marker.name.equals("loop_start")) {
                return Math.min(l.end - .001f, marker.frame + 1);
            }
        }
        return l.start;
    }

    public static void draw(GuiGraphics g, String name, String root, float frame) {
        draw(g, name, root, frame, Map.of());
    }

    public static void draw(
            GuiGraphics g, String name, String root, float frame, Map<String, String> dynamic) {
        Scene s = scenes.get(name);
        if (s == null) {
            return;
        }
        Layer layer = s.roots.get(root);
        if (layer == null) {
            return;
        }
        Runnable paint = () -> render(g, s, layer, frame, 1, 0, new int[] {0}, dynamic);
        if (name.equals("common_info") && (root.equals("frame_top") || root.equals("frame_bottom"))) {
            edge(g, root.equals("frame_bottom"), true, paint);
        } else if (name.equals("menu_common") && root.equals("bg_home")
                || name.equals("song_setup") && root.equals("bg")
                || name.equals("results") && root.startsWith("bg_")) {
            fullCanvas(g, paint);
        } else {
            paint.run();
        }
    }

    public static void drawGray(
            GuiGraphics g,
            String name,
            String root,
            float frame,
            Map<String, String> dynamic,
            float alpha) {
        Scene s = scenes.get(name);
        if (s == null) {
            return;
        }
        Layer layer = s.roots.get(root);
        if (layer == null) {
            return;
        }
        g.flush();
        OriginalUiEffects.grayscale(true);
        try {
            render(g, s, layer, frame, Math.max(0, Math.min(1, alpha)), 0, new int[] {0}, dynamic);
            g.flush();
        } finally {
            OriginalUiEffects.grayscale(false);
        }
    }

    private static void markNoteCenter(Scene s, Layer l, float x, float y, int depth) {
        if (depth > 24) {
            return;
        }
        l.glyphX = x;
        l.glyphY = y;
        if (l.item != null && l.item.type.equals("composition")) {
            for (Layer child : s.compositions[l.item.index].layers) {
                markNoteCenter(s, child, x, y, depth + 1);
            }
        }
    }

    public static void note(
            GuiGraphics g,
            String root,
            float frame,
            dev.flower.stevedance.core.RhythmChart.Note note,
            boolean controller) {
        Scene s = scenes.get("gameplay_hud");
        if (s == null) {
            return;
        }
        Layer layer = s.roots.get(root);
        if (layer != null) {
            g.pose().pushPose();
            g.pose().scale(.75f, .75f, 1);
            if (!controller && layer.video != null) {
                g.pose()
                        .scale(
                                (4f / 3) / Math.max(.001f, value(layer.video.ScaleX, 0, 1)),
                                (4f / 3) / Math.max(.001f, value(layer.video.ScaleY, 0, 1)),
                                1);
            }
            render(
                    g,
                    s,
                    layer,
                    frame,
                    1,
                    0,
                    new int[] {0},
                    Map.of(),
                    false,
                    false,
                    controller
                            ? RhythmNoteGlyphs.controllerLabel(note)
                            : RhythmNoteGlyphs.label(note));
            g.pose().popPose();
            if (controller
                    && root.startsWith("target_")
                    && (note.kind() == dev.flower.stevedance.core.RhythmChart.Kind.STAR
                            || note.kind()
                                    == dev.flower.stevedance.core.RhythmChart.Kind.DOUBLE_STAR
                            || note.kind() == dev.flower.stevedance.core.RhythmChart.Kind.LINK
                            || note.kind()
                                    == dev.flower.stevedance.core.RhythmChart.Kind.CHANCE_STAR)) {
                GamepadArtwork.sticks(
                        g, note.kind() == dev.flower.stevedance.core.RhythmChart.Kind.DOUBLE_STAR);
            }
        }
    }

    /**
     * X RUSH使用单独的原生场景，保留所有F 2nd HUD/校准资源。
     * <p>X RUSH uses a separate native scene, preserving all F 2nd HUD/calibration assets.</p>
     */
    public static void rushEffect(GuiGraphics g, String root, float frame, float alpha) {
        Scene s = scenes.get("rush_notes");
        if (s == null) {
            return;
        }
        Layer layer = s.roots.get(root);
        if (layer == null) {
            return;
        }
        g.pose().pushPose();
        g.pose().scale(.75f, .75f, 1);
        render(g, s, layer, frame, alpha, 0, new int[] {0}, Map.of());
        g.pose().popPose();
    }

    public static void rushNote(
            GuiGraphics g,
            String root,
            float frame,
            dev.flower.stevedance.core.RhythmChart.Note note,
            boolean controller,
            boolean slotsOnly,
            Map<String, String> dynamic) {
        Scene s = scenes.get("rush_notes");
        if (s == null) {
            return;
        }
        Layer layer = s.roots.get(root);
        if (layer == null) {
            return;
        }
        g.pose().pushPose();
        g.pose().scale(.75f, .75f, 1);
        if (!controller
                && layer.video != null
                && (root.startsWith("button_")
                        || root.startsWith("target_") && !root.equals("target_balloon_eff"))) {
            g.pose()
                    .scale(
                            (4f / 3) / Math.max(.001f, value(layer.video.ScaleX, 0, 1)),
                            (4f / 3) / Math.max(.001f, value(layer.video.ScaleY, 0, 1)),
                            1);
        }
        render(
                g,
                s,
                layer,
                frame,
                1,
                0,
                new int[] {0},
                dynamic,
                false,
                slotsOnly,
                controller ? RhythmNoteGlyphs.controllerLabel(note) : RhythmNoteGlyphs.label(note));
        g.pose().popPose();
    }

    /**
     * 使用其原始AET曲线重新发射提取的蓝色音乐气泡。
     * <p>Re-emit the extracted blue musical bubble with its original AET curves.</p>
     */
    public static void holdEffect(GuiGraphics g, int ageMs) {
        Scene s = scenes.get("gameplay_hud");
        if (s == null || ageMs < 0) {
            return;
        }
        Layer root = s.roots.get("hit_eff00");
        if (root == null) {
            return;
        }
        float phase = ageMs * .06f % 14;
        g.pose().pushPose();
        transform(g, s, root, phase, 0);
        for (Layer child : s.compositions[root.item.index].layers) {
            if (child.name.equals("gam_cmn_eff_btn00_01.pic")) {
                for (int i = 0; i < 2; i++) {
                    render(g, s, child, 4 + phase + i * 14, 1, 0, new int[] {0}, Map.of());
                }
            }
        }
        g.pose().popPose();
    }

    /**
     * 按原始矩形遮罩裁切星形，并连续插值两轴，不改变判定进度。
     * <p>Crop the source star with its authored rectangular matte and interpolate both axes.</p>
     */
    public static void chanceGauge(GuiGraphics g, float fill, double seconds) {
        fill = Math.max(0, Math.min(1, fill));
        if (fill >= 1) {
            loop(g, "gameplay_hud", "gauge_ch15", seconds);
            return;
        }
        Scene scene = scenes.get("gameplay_hud");
        if (scene == null) {
            return;
        }
        float stage = fill * 15;
        int low = Math.min(14, (int) stage);
        Layer root = scene.roots.get(String.format(Locale.ROOT, "gauge_ch%02d", low));
        Layer[] layers = scene.compositions[root.item.index].layers;
        Layer mask = layers[0], image = layers[1];
        float fromX = value(mask.video.ScaleX, 0, 1);
        float fromY = value(mask.video.ScaleY, 0, 1);
        float toX, toY;
        if (low < 14) {
            Layer next = scene.roots.get(String.format(Locale.ROOT, "gauge_ch%02d", low + 1));
            Video nextMask = scene.compositions[next.item.index].layers[0].video;
            toX = value(nextMask.ScaleX, 0, 1);
            toY = value(nextMask.ScaleY, 0, 1);
        } else {
            Footage box = scene.videos[image.item.index];
            Footage matte = scene.videos[mask.item.index];
            toX = box.width * value(image.video.ScaleX, 0, 1) / matte.width;
            toY = (value(mask.video.PositionY, 0, 0)
                            - value(image.video.PositionY, 0, 0)
                            + value(image.video.AnchorY, 0, 0)) / matte.height;
        }
        float fraction = stage - low;
        rectangularMatte(g, scene, root, 0, mask, image,
                fromX + (toX - fromX) * fraction,
                fromY + (toY - fromY) * fraction);
    }

    public static void slots(
            GuiGraphics g, String name, String root, float frame, Map<String, String> dynamic) {
        Scene s = scenes.get(name);
        if (s == null) {
            return;
        }
        Layer layer = s.roots.get(root);
        if (layer != null) {
            render(g, s, layer, frame, 1, 0, new int[] {0}, dynamic, false, true);
        }
    }

    private static void transform(GuiGraphics g, Scene s, Layer l, float f, int depth) {
        if (depth > 16) {
            return;
        }
        if (l.parent >= 0) {
            Layer p = s.parents.get(l.parent);
            if (p != null) {
                transform(g, s, p, f, depth + 1);
            }
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

    private static void render(
            GuiGraphics g,
            Scene s,
            Layer l,
            float f,
            float alpha,
            int depth,
            int[] budget,
            Map<String, String> dynamic) {
        render(g, s, l, f, alpha, depth, budget, dynamic, false);
    }

    private static void render(
            GuiGraphics g,
            Scene s,
            Layer l,
            float f,
            float alpha,
            int depth,
            int[] budget,
            Map<String, String> dynamic,
            boolean forceVideo) {
        render(g, s, l, f, alpha, depth, budget, dynamic, forceVideo, false);
    }

    private static void render(
            GuiGraphics g,
            Scene s,
            Layer l,
            float f,
            float alpha,
            int depth,
            int[] budget,
            Map<String, String> dynamic,
            boolean forceVideo,
            boolean slotsOnly) {
        render(g, s, l, f, alpha, depth, budget, dynamic, forceVideo, slotsOnly, null);
    }

    private static void render(
            GuiGraphics g,
            Scene s,
            Layer l,
            float f,
            float alpha,
            int depth,
            int[] budget,
            Map<String, String> dynamic,
            boolean forceVideo,
            boolean slotsOnly,
            RhythmNoteGlyphs.Label key) {
        if (depth > 24
                || ++budget[0] > 3000
                || f < l.start
                || f >= l.end
                || l.flags == null
                || (!forceVideo && !l.flags.contains("VideoActive"))) {
            return;
        }
        if (slotsOnly
                && l.item != null
                && "video".equals(l.item.type)
                && !dynamic.containsKey(l.name)) {
            return;
        }
        float opacity = alpha * (l.video == null ? 1 : value(l.video.Opacity, f, 1));
        if (opacity <= .001) {
            return;
        }
        // 暂停遮罩覆盖宽屏和高屏；按钮仍保留原比例。 / Fit only the pause shade to the viewport.
        if (!compactCanvas && s.id.equals("gameplay_dialogs") && l.name.equals("gam_win_chip_w.pic")) {
            Footage footage = s.videos[l.item.index];
            Sprite shade = s.group.byName.get(footage.sources[0].sprite);
            fullCanvas(g, () -> blit(g, s.group, shade, 0, 0, 1280, 728, opacity, 3));
            return;
        }
        g.pose().pushPose();
        boolean upperEdge =
                s.id.equals("gameplay_skin_default") && l.name.endsWith("frame_above.pic")
                        || s.id.equals("results") && l.name.startsWith("rslt_menu_mm_frm_top");
        boolean lowerEdge = s.id.equals("results") && l.name.equals("rslt_menu_mm_frm_btm.pic");
        if (upperEdge || lowerEdge) {
            g.pose()
                    .translate(
                            -canvasMarginX - edgeOverdraw,
                            upperEdge ? -canvasMarginY : canvasMarginY,
                            0);
            g.pose().scale((WIDTH + 2 * (canvasMarginX + edgeOverdraw)) / WIDTH, 1, 1);
        }
        boolean glyph =
                key != null
                        && l.item != null
                        && "video".equals(l.item.type)
                        && RhythmNoteGlyphs.symbolLayer(l.name)
                        && l.video != null;
        if (glyph) {
            if (l.parent >= 0) {
                Layer parent = s.parents.get(l.parent);
                if (parent != null) {
                    transform(g, s, parent, f, 0);
                }
            }
            g.pose().translate(l.glyphX, l.glyphY, 0);
            g.pose().mulPose(Axis.ZP.rotationDegrees(value(l.video.Rotation, f, 0)));
            g.pose()
                    .scale(
                            value(l.video.ScaleX, f, 1)
                                    / Math.max(.001f, value(l.video.ScaleX, 0, 1)),
                            value(l.video.ScaleY, f, 1)
                                    / Math.max(.001f, value(l.video.ScaleY, 0, 1)),
                            1);
            g.pose().translate(-32, -32, 0);
        } else {
            transform(g, s, l, f, 0);
        }
        String text = dynamic.get(l.name);
        if (text == null && l.name.startsWith("p_")) {
            text = "";
        }
        if (key != null && l.name.startsWith("p_balloon") && l.item != null) {
            Footage box = s.videos[l.item.index];
            RhythmNoteGlyphs.draw(g, key, box.width, box.height, opacity, false);
        } else if (glyph) {
            RhythmNoteGlyphs.draw(g, key, 64, 64, opacity, !l.name.startsWith("gam_cmn_button_"));
        } else if (text != null
                && text.startsWith("@pv/")
                && l.item != null
                && l.item.index >= 0
                && l.item.index < s.videos.length) {
            Footage footprint = s.videos[l.item.index];
            SongThumbnailCache.draw(
                    g, text.substring(4), footprint.width, footprint.height, opacity);
        } else if (text != null && text.startsWith("@")) {
            int slash = text.indexOf('/');
            Group group = slash > 1 ? groups.get(text.substring(1, slash)) : null;
            Sprite sprite = group == null ? null : group.byName.get(text.substring(slash + 1));
            if (sprite != null
                    && l.item != null
                    && l.item.index >= 0
                    && l.item.index < s.videos.length) {
                Footage footprint = s.videos[l.item.index];
                if (s.id.equals("score_records") && l.name.startsWith("p_value")) {
                    float ax = value(l.video.AnchorX, f, 0), ay = value(l.video.AnchorY, f, 0);
                    blit(
                            g,
                            group,
                            sprite,
                            Math.round(ax - sprite.width / 2f),
                            Math.round(ay - sprite.height / 2f),
                            Math.round(sprite.width),
                            Math.round(sprite.height),
                            opacity,
                            3);
                } else {
                    blit(g, group, sprite, 0, 0, footprint.width, footprint.height, opacity, 3);
                }
            }
        } else if (text != null
                && (l.name.startsWith("p_combo2_num") || l.name.matches("p_score\\d+_c"))
                && l.item != null
                && l.item.index >= 0
                && l.item.index < s.videos.length) {
            // 中文：保留原生动画数字足迹和锚点；空槽位被隐藏。
            // EN: Preserve the native animated digit footprint and anchors; an empty slot is
            // hidden.
            if (text.length() == 1 && text.charAt(0) >= '0' && text.charAt(0) <= '9') {
                Sprite digit =
                        s.group.byName.get(
                                (l.name.startsWith("p_score")
                                                ? "gameplay_hud_score_number_0"
                                                : "gameplay_hud_combo2_number_0")
                                        + text);
                Footage footprint = s.videos[l.item.index];
                if (digit != null) {
                    blit(
                            g,
                            s.group,
                            digit,
                            0,
                            0,
                            footprint.width,
                            footprint.height,
                            opacity,
                            l.video == null || l.video.TransferMode == null
                                    ? 3
                                    : l.video.TransferMode.BlendMode);
                }
            }
        } else if (text != null && nativeSlot(g, s, l, f, text, opacity)) {
        } else if (text != null) {
            var font = Minecraft.getInstance().font;
            float size = l.name.contains("song_txt00") ? 3 : l.name.contains("song_txt") ? 1.9f : 2;
            float ax = l.video == null ? 0 : value(l.video.AnchorX, f, 0),
                    ay = l.video == null ? 0 : value(l.video.AnchorY, f, 0);
            float x =
                    l.name.endsWith("_rt")
                            ? ax - font.width(text) * size
                            : l.name.endsWith("_c") ? ax - font.width(text) * size / 2 : ax;
            if (l.name.endsWith(".pic")
                    && l.item != null
                    && l.item.index >= 0
                    && l.item.index < s.videos.length) {
                Footage label = s.videos[l.item.index];
                size = Math.min(2, label.width / (float) Math.max(1, font.width(text)));
                x = (label.width - font.width(text) * size) / 2;
                ay = (label.height - font.lineHeight * size) / 2;
            }
            g.pose().pushPose();
            g.pose().translate(x, ay, 0);
            g.pose().scale(size, size, 1);
            g.drawString(
                    font,
                    text,
                    0,
                    0,
                    (s.id.equals("song_setup") || s.id.equals("results"))
                            ? 0xfff3ffff
                            : 0xff162330,
                    false);
            g.pose().popPose();
        } else if (l.item != null) {
            float local = (f - l.start) * l.timeScale + l.offset;
            if ("composition".equals(l.item.type)
                    && l.item.index >= 0
                    && l.item.index < s.compositions.length) {
                Layer[] layers = s.compositions[l.item.index].layers;
                for (int i = layers.length - 1; i >= 0; i--) {
                    if (i + 1 < layers.length && matte(layers[i + 1]) != 0) {
                        continue;
                    }
                    Layer child = layers[i];
                    int mode =
                            child.video == null || child.video.TransferMode == null
                                    ? 3
                                    : child.video.TransferMode.BlendMode;
                    if (matte(child) != 0 && i > 0) {
                        Layer mask = layers[i - 1];
                        OriginalUiEffects.masked(
                                g,
                                matte(child),
                                mode,
                                () ->
                                        render(
                                                g, s, child, local, opacity, depth + 1, budget,
                                                dynamic, false, false, key),
                                () ->
                                        render(
                                                g, s, mask, local, 1, depth + 1, budget, Map.of(),
                                                true));
                    } else {
                        render(
                                g, s, child, local, opacity, depth + 1, budget, dynamic, false,
                                slotsOnly, key);
                    }
                }
            } else if ("video".equals(l.item.type)
                    && l.item.index >= 0
                    && l.item.index < s.videos.length) {
                Footage video = s.videos[l.item.index];
                if (video.sources.length > 0) {
                    Source source =
                            video.sources[
                                    Math.max(0, Math.min(video.sources.length - 1, (int) local))];
                    Sprite sprite = s.group.byName.get(source.sprite);
                    if (sprite != null) {
                        blit(
                                g,
                                s.group,
                                sprite,
                                0,
                                0,
                                video.width,
                                video.height,
                                opacity,
                                l.video != null && l.video.TransferMode != null
                                        ? l.video.TransferMode.BlendMode
                                        : 3);
                    }
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
        }
        g.pose().popPose();
    }

    private static int advance(NativeFont font, int c) {
        Glyph glyph = font.glyphs.get(Integer.toString(c));
        return glyph == null
                ? Math.round(
                        Minecraft.getInstance().font.width(Character.toString(c)) * font.line / 9f)
                : glyph.half ? font.advance / 2 : font.advance;
    }

    private static int textWidth(NativeFont font, String text) {
        return text.codePoints().map(c -> advance(font, c)).sum();
    }

    // 普通文字保持原界面的24像素行高；专用数字仍使用原图。 / 24px menu text; native digits stay intact.
    private static float menuTextScale() {
        return 24f / Minecraft.getInstance().font.lineHeight;
    }

    public static int textWidth(String text) {
        return Math.round(Minecraft.getInstance().font.width(text) * menuTextScale());
    }

    private static void menuText(
            GuiGraphics g, String text, float x, float y, float size, float alpha, int color) {
        if (text.isEmpty() || alpha <= 0) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(size, size, 1);
        g.drawString(
                Minecraft.getInstance().font,
                text,
                0,
                0,
                (Math.max(4, Math.round(Math.min(1, alpha) * 255)) << 24) | (color & 0xffffff),
                false);
        g.pose().popPose();
    }

    private static boolean nativeSlot(
            GuiGraphics g, Scene s, Layer l, float frame, String text, float alpha) {
        if (text.isEmpty()) {
            return true;
        }
        if (s.id.equals("gameplay_hud") && l.name.equals("p_song_title_lt")) {
            // 演奏歌名独立于菜单字号；沿用原 AET 位置和宽度，与难度标签对齐。
            // 中文：HUD标题保持其原生位置/宽度，带有一行与难度对齐的文本。
            // EN: The HUD title keeps its native placement/width, with a difficulty-aligned text
            // line.
            var font = Minecraft.getInstance().font;
            float size = 32f / font.lineHeight;
            float available = s.videos[l.item.index].width;
            size = Math.min(size, available / Math.max(1, font.width(text)));
            float ax = value(l.video.AnchorX, frame, 0), ay = value(l.video.AnchorY, frame, 0);
            menuText(
                    g,
                    text,
                    ax,
                    ay + 8 + (32 - font.lineHeight * size) / 2,
                    size,
                    alpha,
                    0xffffffff);
            return true;
        }
        if (s.id.equals("score_records") && text.equals("-")) {
            float size = menuTextScale(),
                    ax = value(l.video.AnchorX, frame, 0),
                    ay = value(l.video.AnchorY, frame, 0);
            float w = Minecraft.getInstance().font.width(text) * size;
            menuText(
                    g,
                    text,
                    l.name.endsWith("_rt") ? ax - w : ax - w / 2,
                    l.name.endsWith("_c") ? ay - 12 : ay,
                    size,
                    alpha,
                    0xff142c35);
            return true;
        }
        boolean numeric = text.matches("[0-9 .%+−-]+");
        if (!numeric) {
            var mcFont = Minecraft.getInstance().font;
            float size = menuTextScale();
            float ax = l.video == null ? 0 : value(l.video.AnchorX, frame, 0),
                    ay = l.video == null ? 0 : value(l.video.AnchorY, frame, 0);
            if (s.id.equals("song_selection") && l.name.startsWith("p_menu_txt")) {
                size = Math.min(size, 620f / Math.max(1, mcFont.width(text)));
            }
            float x =
                    l.name.endsWith("_rt")
                            ? ax - mcFont.width(text) * size
                            : l.name.endsWith("_c") || l.name.endsWith("_ct")
                                    ? ax - mcFont.width(text) * size / 2
                                    : ax;
            if (l.name.endsWith(".pic")
                    && l.item != null
                    && l.item.index >= 0
                    && l.item.index < s.videos.length) {
                Footage box = s.videos[l.item.index];
                // 原标签切片窄于按钮本体；中文按按钮可用宽度居中，不压成小字。
                // 中文：原生标签裁剪比其按钮更窄；将翻译适配到按钮
                // EN: The native label crop is narrower than its button; fit translations to the
                // button
                // 中文：内部。
                // EN: interior.
                float available =
                        s.id.equals("song_setup")
                                        && (l.name.equals("sel_start_mm_btn_txt_start.pic")
                                                || l.name.matches(
                                                        "sel_start_mm_btn_txt_sb_0[23]\\.pic"))
                                ? 150
                                : box.width;
                size =
                        Math.min(
                                size,
                                Math.min(
                                        available / Math.max(1, mcFont.width(text)),
                                        box.height / (float) mcFont.lineHeight));
                x = (box.width - mcFont.width(text) * size) / 2;
                ay = (box.height - mcFont.lineHeight * size) / 2;
            }
            if (s.id.equals("score_records") && l.name.startsWith("p_txt_top")) {
                ay -= 12;
            }
            menuText(
                    g,
                    text,
                    x,
                    ay,
                    size,
                    alpha,
                    s.id.equals("score_records") && !l.name.startsWith("p_txt_top")
                            ? 0xff142c35
                            : 0xfff3ffff);
            return true;
        }
        int map = 4;
        NativeFont font = fonts.get(map);
        Group group = groups.get("numeric_fonts");
        Sprite sheet = group == null ? null : group.byName.get("numeric_fonts_number18x18");
        if (font == null || sheet == null) {
            return false;
        }
        float ax = l.video == null ? 0 : value(l.video.AnchorX, frame, 0),
                ay = l.video == null ? 0 : value(l.video.AnchorY, frame, 0);
        float x =
                l.name.endsWith("_rt")
                        ? ax - textWidth(font, text)
                        : l.name.endsWith("_c") || l.name.endsWith("_ct")
                                ? ax - textWidth(font, text) / 2f
                                : ax;
        int color =
                (s.id.equals("song_selection") || s.id.equals("score_records")) && numeric
                        ? 0xff142c35
                        : 0xfff3ffff;
        if (l.name.endsWith(".pic")
                && l.item != null
                && l.item.index >= 0
                && l.item.index < s.videos.length) {
            Footage footprint = s.videos[l.item.index];
            x = (footprint.width - textWidth(font, text)) / 2f;
            ay = (footprint.height - font.line) / 2f;
        }
        nativeText(g, font, group, sheet, text, x, ay, alpha, color);
        return true;
    }

    private static void nativeText(
            GuiGraphics g,
            NativeFont font,
            Group group,
            Sprite sheet,
            String text,
            float x,
            float y,
            float alpha,
            int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        int cursor = 0;
        for (int c : text.codePoints().toArray()) {
            Glyph glyph = font.glyphs.get(Integer.toString(c));
            if (glyph == null) {
                g.pose().pushPose();
                g.pose().translate(cursor, 0, 0);
                float scale = font.line / 9f;
                g.pose().scale(scale, scale, 1);
                g.drawString(
                        Minecraft.getInstance().font,
                        Character.toString(c),
                        0,
                        0,
                        ((int) (alpha * 255) << 24) | (color & 0xffffff),
                        false);
                g.pose().popPose();
            } else {
                Sprite part = new Sprite();
                part.texture = sheet.texture;
                part.x = sheet.x + glyph.column * font.boxWidth + glyph.offset;
                part.y = sheet.y + glyph.row * font.boxHeight + 1;
                part.width = glyph.width;
                part.height = font.line;
                blit(g, group, part, cursor, 0, glyph.width, font.line, alpha, 3, color);
            }
            cursor += advance(font, c);
        }
        g.pose().popPose();
    }

    public static void text(GuiGraphics g, String text, int x, int y, float scale) {
        text(g, text, x, y, scale, 0xfff3ffff);
    }

    public static void keySprite(
            GuiGraphics g, ResourceLocation texture, int width, int height, float alpha) {
        g.flush();
        OriginalUiEffects.spriteShader(3);
        RenderSystem.setShaderTexture(0, texture);
        var m = g.pose().last().pose();
        var b = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        b.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        b.vertex(m, 0, height, 0).uv(0, 1).color(1, 1, 1, alpha).endVertex();
        b.vertex(m, width, height, 0).uv(1, 1).color(1, 1, 1, alpha).endVertex();
        b.vertex(m, width, 0, 0).uv(1, 0).color(1, 1, 1, alpha).endVertex();
        b.vertex(m, 0, 0, 0).uv(0, 0).color(1, 1, 1, alpha).endVertex();
        OriginalUiEffects.draw(
                b.end(),
                org.lwjgl.opengl.GL11.GL_SRC_ALPHA,
                org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    public static void text(GuiGraphics g, String text, int x, int y, float scale, int color) {
        menuText(g, text, x, y, scale * menuTextScale(), 1, color);
    }

    private static int matte(Layer l) {
        return l.video == null || l.video.TransferMode == null
                ? 0
                : l.video.TransferMode.TrackMatte;
    }

    public static float value(Curve curve, float frame, float fallback) {
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

    private static void blit(
            GuiGraphics g,
            Group group,
            Sprite s,
            int x,
            int y,
            int width,
            int height,
            float alpha,
            int mode) {
        blit(g, group, s, x, y, width, height, alpha, mode, 0xffffffff);
    }

    private static void blit(
            GuiGraphics g,
            Group group,
            Sprite s,
            int x,
            int y,
            int width,
            int height,
            float alpha,
            int mode,
            int color) {
        if (s.texture < 0 || s.texture >= group.textures.length) {
            return;
        }
        Atlas a = group.textures[s.texture];
        g.flush();
        OriginalUiEffects.spriteShader(mode);
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        int destination =
                mode == 5 || mode == 23
                        ? org.lwjgl.opengl.GL11.GL_ONE
                        : org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
        int source =
                mode == 6 ? org.lwjgl.opengl.GL11.GL_DST_COLOR : org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
        if (mode == 7) {
            source = org.lwjgl.opengl.GL11.GL_ONE;
            destination = org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_COLOR;
        }
        RenderSystem.blendFuncSeparate(
                source,
                destination,
                org.lwjgl.opengl.GL11.GL_ONE,
                org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL14.glBlendFuncSeparate(
                source,
                destination,
                org.lwjgl.opengl.GL11.GL_ONE,
                org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShaderTexture(0, group.locations[s.texture]);
        var matrix = g.pose().last().pose();
        var buffer = com.mojang.blaze3d.vertex.Tesselator.getInstance().getBuilder();
        buffer.begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR);
        float red = ((color >> 16) & 255) / 255f,
                green = ((color >> 8) & 255) / 255f,
                blue = (color & 255) / 255f;
        float u = s.x / a.width,
                v = s.y / a.height,
                ue = (s.x + s.width) / a.width,
                ve = (s.y + s.height) / a.height,
                opacity = Math.min(1, alpha);
        buffer.vertex(matrix, x, y + height, 0)
                .uv(u, ve)
                .color(red, green, blue, opacity)
                .endVertex();
        buffer.vertex(matrix, x + width, y + height, 0)
                .uv(ue, ve)
                .color(red, green, blue, opacity)
                .endVertex();
        buffer.vertex(matrix, x + width, y, 0)
                .uv(ue, v)
                .color(red, green, blue, opacity)
                .endVertex();
        buffer.vertex(matrix, x, y, 0).uv(u, v).color(red, green, blue, opacity).endVertex();
        OriginalUiEffects.draw(buffer.end(), source, destination);
    }

    public static void sprite(GuiGraphics g, String group, String name, int x, int y, float scale) {
        Group a = groups.get(group);
        if (a == null) {
            return;
        }
        Sprite s = a.byName.get(name);
        if (s == null) {
            return;
        }
        blit(g, a, s, x, y, (int) (s.width * scale), (int) (s.height * scale), 1, 3);
    }

    public static boolean spriteSized(
            GuiGraphics g, String group, String name, int x, int y, int width, int height) {
        Group a = groups.get(group);
        if (a == null) {
            return false;
        }
        Sprite s = a.byName.get(name);
        if (s == null) {
            return false;
        }
        blit(g, a, s, x, y, width, height, 1, 3);
        return true;
    }

    public static Map<String, String> stars(
            float count, String group, String on, String half, String off) {
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            values.put(
                    String.format(Locale.ROOT, "p_level_star%02d_c", i),
                    "@" + group + "/" + (count >= i ? on : count >= i - .5f ? half : off));
        }
        return values;
    }

    public static void progress(GuiGraphics g, double achievement) {
        float frame = (float) Math.max(0, Math.min(100, Math.floor(achievement)));
        String grade =
                achievement >= 100
                        ? "perfect"
                        : achievement >= 95
                                ? "excellent"
                                : achievement >= 90
                                        ? "great"
                                        : achievement >= 80 ? "standard" : "cheap";
        draw(g, "gameplay_hud", "rank_base_skin", 0);
        draw(g, "gameplay_hud", "rank_base", frame);
        if (grade.equals("perfect")) {
            draw(g, "gameplay_hud", "rank_bar_perfect", frame);
        } else {
            rankBar(g, grade, frame);
        }
        draw(g, "gameplay_hud", "rank_border_standard", 80);
        draw(g, "gameplay_hud", "rank_border_great", 90);
        draw(g, "gameplay_hud", "rank_border_excellent", 95);
    }

    /** These four authored bars use a rectangular alpha matte. Crop in their local
     *  coordinates, retaining the AET curves without a full-screen framebuffer.
     *  四种普通评级条均为矩形遮罩，直接按原曲线裁切贴图。 */
    private static void rankBar(GuiGraphics g, String grade, float frame) {
        Scene scene = scenes.get("gameplay_hud");
        if (scene == null) {
            return;
        }
        Layer root = scene.roots.get("rank_bar_" + grade);
        Layer[] layers = scene.compositions[root.item.index].layers;
        Layer mask = layers[0], image = layers[1];
        rectangularMatte(g, scene, root, frame, mask, image,
                value(mask.video.ScaleX, frame, 1), value(mask.video.ScaleY, frame, 1));
    }

    private static void rectangularMatte(
            GuiGraphics g, Scene scene, Layer root, float frame,
            Layer mask, Layer image, float maskScaleX, float maskScaleY) {
        Video m = mask.video, v = image.video;
        Footage maskBox = scene.videos[mask.item.index], box = scene.videos[image.item.index];
        Sprite original = scene.group.byName.get(box.sources[0].sprite);
        if (original == null) {
            return;
        }
        float sx = value(v.ScaleX, frame, 1), sy = value(v.ScaleY, frame, 1);
        float x = value(v.PositionX, frame, 0) - value(v.AnchorX, frame, 0) * sx,
                y = value(v.PositionY, frame, 0) - value(v.AnchorY, frame, 0) * sy;
        float
                mx =
                        value(m.PositionX, frame, 0)
                                - value(m.AnchorX, frame, 0) * maskScaleX,
                my =
                        value(m.PositionY, frame, 0)
                                - value(m.AnchorY, frame, 0) * maskScaleY;
        float left = Math.max(x, mx),
                top = Math.max(y, my),
                right =
                        Math.min(
                                x + box.width * sx, mx + maskBox.width * maskScaleX),
                bottom =
                        Math.min(
                                y + box.height * sy,
                                my + maskBox.height * maskScaleY);
        if (right <= left || bottom <= top) {
            return;
        }
        Sprite crop = new Sprite();
        crop.texture = original.texture;
        crop.x = original.x + (left - x) * original.width / (box.width * sx);
        crop.y = original.y + (top - y) * original.height / (box.height * sy);
        crop.width = (right - left) * original.width / (box.width * sx);
        crop.height = (bottom - top) * original.height / (box.height * sy);
        g.pose().pushPose();
        transform(g, scene, root, frame, 0);
        g.pose().translate(left, top, 0);
        g.pose().scale(right - left, bottom - top, 1);
        blit(
                g,
                scene.group,
                crop,
                0,
                0,
                1,
                1,
                value(v.Opacity, frame, 1) * value(m.Opacity, frame, 1),
                3);
        g.pose().popPose();
    }

    public static void number(
            GuiGraphics g,
            String group,
            String prefix,
            int value,
            int right,
            int y,
            int size,
            int minimumDigits) {
        String text =
                String.format(
                        java.util.Locale.ROOT,
                        "%0" + Math.max(1, minimumDigits) + "d",
                        Math.max(0, value));
        for (int i = text.length() - 1; i >= 0; i--) {
            int d = text.charAt(i) - '0';
            sprite(
                    g,
                    group,
                    prefix + String.format(java.util.Locale.ROOT, "%02d", d),
                    right - size * (text.length() - i),
                    y,
                    1);
        }
    }
}
