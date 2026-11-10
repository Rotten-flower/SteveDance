package dev.flower.stevedance.client;

import dev.flower.stevedance.MotionPackLibrary;
import dev.flower.stevedance.core.PvAnimationCurve;
import dev.flower.stevedance.core.PvSourceGraphClock;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 有边界的本地导入；打包的源几何使用只读捆绑资源提供器。
 * <p>A bounded local import; packaged source geometry uses the read-only bundled resource provider.</p>
 */
public final class PvSceneData implements AutoCloseable {
    public record Camera(
            Map<String, String> properties,
            Map<String, Map<String, PvAnimationCurve>> transforms,
            Map<String, PvAnimationCurve> curves) {}

    public record CameraCut(double timeMs, double startMs, String name) {}

    public record ItemTrack(int actor, int index, String graph) {}

    /**
     * 显式的源接触门控：驻留且独立的动画保留在世界中。
     * <p>Explicit source-contact gate: parked and independent animation stays in the world.</p>
     */
    public record ItemBinding(
            int actor,
            String graph,
            String rig,
            String role,
            float maximumSourceDistance,
            String fitMode,
            String pairedWith) {}

    public record Event(String command, double timeMs, int[] args) {}

    public record Cut(
            double timeMs,
            int field,
            String stage,
            Map<String, Double> starts,
            Map<String, Double> maxFrames) {}

    public record Description(
            int schema,
            String id,
            int durationMs,
            float coordinateScale,
            Camera camera,
            Map<String, List<String>> fields,
            List<Cut> timeline,
            Map<String, List<String>> stages,
            Map<String, List<String>> stageGraphs,
            Map<String, Graph> graphs,
            List<Event> events,
            List<ItemTrack> itemTracks,
            List<String> sourceAbsentMeshes,
            boolean hideTerrain,
            String renderPipeline,
            String motionCoordinateProfile,
            List<CameraCut> cameraCuts,
            Map<String, Camera> cameraTracks,
            Map<String, List<PvSourceGraphClock>> sourceGraphClocks) {}

    public static final class TextureSlot {
        public int texture, uvSet, samplerFlags;
        public String type;
        public float weight;
        public float[] matrix;
        public boolean repeatU, repeatV, mirrorU, mirrorV, ignoreAlpha;
    }

    public static final class SourceMaterial {
        public String shader;
        public int shaderFlags;
        public float[] ambient;
        public float intensity;
        public TextureSlot[] textures;

        /**
         * 可选的源因子；较旧的导入保留其scene.bin标志。
         * <p>Optional source factors; older imports retain their scene.bin flags.</p>
         */
        public String sourceBlend, destinationBlend;

        public int blendOperation;

        public boolean additive() {
            return "One".equals(destinationBlend);
        }
    }

    public record Materials(
            int schema, Map<String, List<String>> cubes, Map<String, SourceMaterial[]> objects) {}

    public static final class Node {
        public TextureTransform[] textureTransforms;
        public TexturePattern[] texturePatterns;
        public String name, mesh, parent, parentNode, propRig;
        public Map<String, PvAnimationCurve> transform;
        public Map<String, PvAnimationCurve>[] segments;
        public PvAnimationCurve morph;
        public float morphOffset;
        transient int parentIndex = -1;
        final transient Matrix4f matrix = new Matrix4f();
        transient boolean evaluated, visible;
        transient Transform animation;
        transient Geometry geometry;
        transient Geometry[] variants;
    }

    /**
     * 源tex_pat轨道替换纹理ID，而不移动人群网格。
     * <p>Source tex_pat track substitutes texture IDs, without moving the crowd mesh.</p>
     */
    public static final class TexturePattern {
        public int texture;
        public int[] frames;
        public PvAnimationCurve curve;
        public float offset, movieFps, movieStartMs;
        transient int selected;

        void evaluate(double frame) {
            if (movieFps > 0) {
                int index =
                        (int)
                                Math.floor(
                                        Math.max(0, PvPlaybackController.timeMs() - movieStartMs)
                                                * movieFps
                                                / 1000);
                selected = frames[Math.min(frames.length - 1, index)];
                return;
            }
            double sample = frame + offset;
            if (offset != 0 && sample >= curve.maximumFrame) {
                sample -= curve.maximumFrame;
            }
            int index = (int) Math.floor(curve.sample(sample) + .5);
            selected = index >= 0 && index < frames.length ? frames[index] : texture;
        }
    }

    public static final class TextureTransform {
        public int texture;
        public Map<String, PvAnimationCurve> curves;
        final transient Matrix4f matrix = new Matrix4f();

        private float sample(String name, double frame, float fallback) {
            var c = curves.get(name);
            return c == null || c.type == 0 ? fallback : c.sample(frame);
        }

        void evaluate(double frame) {
            float cu = sample("coverageU", frame, 1),
                    cv = sample("coverageV", frame, 1),
                    ru = sample("repeatU", frame, 1),
                    rv = sample("repeatV", frame, 1);
            float sx = Math.abs(cu) > 1e-6f ? 1 / cu : 1, sy = Math.abs(cv) > 1e-6f ? 1 / cv : 1;
            if (Math.abs(ru) < 1e-6f) {
                ru = 1;
            }
            if (Math.abs(rv) < 1e-6f) {
                rv = 1;
            }
            matrix.translation(
                            (sample("offsetU", frame, 0) - sample("translateFrameU", frame, 0))
                                    * ru,
                            (sample("offsetV", frame, 0) - sample("translateFrameV", frame, 0))
                                    * rv,
                            0)
                    .scale(sx, sy, 1)
                    .translate(1, 1, 0)
                    .scale(ru, rv, 1)
                    .translate(-1, -1, 0)
                    .translate(.5f, .5f, 0)
                    .rotateZ(sample("rotate", frame, 0) + sample("rotateFrame", frame, 0))
                    .translate(-.5f, -.5f, 0);
        }
    }

    public static final class BoneNode {
        public String name;
        public int parent;
        public float[] jointOrient;
        public Map<String, PvAnimationCurve> transform;
        public Map<String, PvAnimationCurve>[] segments;
        final transient Matrix4f matrix = new Matrix4f();
        transient Transform animation;
        transient boolean visible;
    }

    public static final class Rig {
        public String name, mesh, parent, parentNode;
        public BoneNode[] nodes;
        public Integer nodeSource;
        public boolean multi;
        /** Explicit conversion metadata, never inferred from resource names. / 转换器明确标注。 */
        public boolean replacementCharacter;
        public Map<String, float[]> replacementBind;
        public Map<String, PvAnimationCurve> group, instance;
        transient Transform groupAnimation, instanceAnimation;
        final transient Matrix4f model = new Matrix4f();
        transient Geometry geometry;
        transient int[] binding;
        transient boolean visible;
        final transient FloatBuffer palette = MemoryUtil.memAllocFloat(128 * 16);
    }

    public static final class Graph {
        public float fps, begin, size;
        public Node[] nodes;
        public Rig[] rigs;
        public BoneNode[][] rigNodeSources;

        /**
         * 只共享原始曲线，每个实例独立保存求值状态。
         * <p>Share source curves, never mutable instance state.</p>
         */
        private void materializeRigSources() {
            if (rigNodeSources == null) {
                return;
            }
            if (rigNodeSources.length > 128) {
                throw new IllegalArgumentException("Rig source budget exceeded");
            }
            for (var rig : rigs) {
                if (rig.nodeSource != null) {
                    int index = rig.nodeSource;
                    if (index < 0 || index >= rigNodeSources.length || rig.nodes != null) {
                        throw new IllegalArgumentException("Invalid rig source reference");
                    }
                    var source = rigNodeSources[index];
                    if (source == null || source.length > 256) {
                        throw new IllegalArgumentException("Invalid rig source nodes");
                    }
                    rig.nodes = new BoneNode[source.length];
                    for (int i = 0; i < source.length; i++) {
                        var original = Objects.requireNonNull(source[i]);
                        var node = new BoneNode();
                        node.name = original.name;
                        node.parent = original.parent;
                        node.jointOrient = original.jointOrient;
                        node.transform = original.transform;
                        node.segments = original.segments;
                        rig.nodes[i] = node;
                    }
                }
            }
            rigNodeSources = null;
        }
    }

    public static final class Transform {
        final PvAnimationCurve[] c = new PvAnimationCurve[10];
        Transform[] divided;

        Transform(Map<String, PvAnimationCurve> source) {
            int i = 0;
            for (String group : List.of("translation", "rotation", "scale")) {
                for (char axis : new char[] {'x', 'y', 'z'}) {
                    c[i++] = Objects.requireNonNull(source.get(group + "." + axis));
                }
            }
            c[9] = Objects.requireNonNull(source.get("visibility"));
            for (var curve : c) {
                curve.validate();
            }
        }

        Transform(Map<String, PvAnimationCurve> source, Map<String, PvAnimationCurve>[] segments) {
            this(source);
            if (segments != null) {
                if (segments.length > 32) {
                    throw new IllegalArgumentException("A3DA division budget exceeded");
                }
                divided = new Transform[segments.length];
                for (int i = 0; i < divided.length; i++) {
                    divided[i] = new Transform(segments[i]);
                }
            }
        }

        boolean apply(double frame, Matrix4f base, Matrix4f result) {
            if (divided != null) {
                return divided[Math.max(0, Math.min(divided.length - 1, (int) (frame / 1000)))]
                        .apply(frame, base, result);
            }
            float sx = c[6].sample(frame), sy = c[7].sample(frame), sz = c[8].sample(frame);
            result.set(base)
                    .translate(c[0].sample(frame), c[1].sample(frame), c[2].sample(frame))
                    .rotateZ(c[5].sample(frame))
                    .rotateY(c[4].sample(frame))
                    .rotateX(c[3].sample(frame))
                    .scale(sx, sy, sz);
            return c[9].sample(frame) >= .999f && sx != 0 && sy != 0 && sz != 0;
        }
    }

    public record Bone(String name, Matrix4f inverseBind) {}

    public static final class Batch {
        public SourceMaterial sourceMaterial;
        public int texture, flags, repeatU, repeatV, count, vao, vbo, ebo;
        public float[] diffuse, specular = {0, 0, 0, 0}, emission = {0, 0, 0, 0};
        public float shininess = 1;
        public int stride;
        public ByteBuffer vertices, indices;
        public final Vector3f center = new Vector3f();
    }

    public static final class Geometry {
        public float extent;
        public String name;
        public Bone[] bones;
        public Batch[] batches;
    }

    public List<PvStageScreen.Screen> screens = List.of();
    public PvMovieBackground.Data movie;
    public PvLyrics.Track subtitles;
    public PvAetTimeline.Data overlay;
    public PvLighting lighting;
    public final Description description;
    public final List<ItemBinding> itemBindings;
    public final Geometry[] geometry;
    public final byte[][] images;
    public final Map<Integer, byte[][]> cubeImages = new HashMap<>();
    public final Map<String, Geometry> byName = new HashMap<>();

    public boolean hdr() {
        return "hdr-source-v1".equals(description.renderPipeline);
    }

    final Transform cameraRoot;
    final Matrix4f identity = new Matrix4f(), temporary = new Matrix4f();
    private final Map<String, Transform> cameraRoots = new HashMap<>();
    final Vector3f eye = new Vector3f(), interest = new Vector3f();

    private record AdapterBindings(int schema, Map<String, List<ItemBinding>> songs) {}

    private static List<ItemBinding> adapterBindings(String song) throws IOException {
        try (var stream =
                PvSceneData.class.getResourceAsStream("/assets/stevedance/adaptation/props.json")) {
            if (stream == null) {
                throw new IOException("Missing prop adapter profiles");
            }
            var profiles =
                    MotionPackLibrary.GSON.fromJson(
                            new InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8),
                            AdapterBindings.class);
            if (profiles.schema() != 1) {
                throw new IOException("Unsupported prop adapter profile");
            }
            return List.copyOf(profiles.songs().getOrDefault(song, List.of()));
        }
    }

    private PvSceneData(Description d, Geometry[] g, byte[][] images) throws IOException {
        description = d;
        itemBindings = adapterBindings(d.id());
        geometry = g;
        this.images = images;
        for (var o : g) {
            byName.put(o.name, o);
        }
        cameraRoot = new Transform(d.camera.transforms.get("camera_root.0.model_transform"));
        for (var graph : d.graphs.values()) {
            graph.materializeRigSources();
        }
        bind();
    }

    public static Path root() {
        return root(dev.flower.stevedance.PvSessionService.SONG);
    }

    public static Path root(String id) {
        dev.flower.stevedance.SongCatalog.get(id);
        return dev.flower.stevedance.BundledSongResources.nativeSong(id);
    }

    /**
     * 解析必需且已存在的资源，同时检查别名后的真实路径边界。
     * toRealPath会对缺失文件抛异常；不能把此方法当作可选文件的存在性查询。
     * <p>Resolve an existing required resource and enforce its real-path boundary; missing files throw.</p>
     */
    public static Path safe(Path root, String name) throws IOException {
        Path logical = root.resolve(name).normalize();
        if (!logical.startsWith(root)) {
            throw new IOException("Native path outside song folder");
        }
        Path resolved = dev.flower.stevedance.BundledSongResources.resolve(logical);
        Path external = dev.flower.stevedance.content.InstalledSongs.get().boundary(logical);
        Path boundary =
                external != null
                        ? external
                        : resolved.equals(logical)
                                ? root
                                : dev.flower.stevedance.BundledSongResources.root();
        if (!resolved.toRealPath().startsWith(boundary.toRealPath())) {
            throw new IOException("Native path outside resource boundary");
        }
        return resolved;
    }

    public static PvSceneData load() throws IOException {
        return load(root());
    }

    public static PvSceneData load(Path root) throws IOException {
        Path json = safe(root, "preview.json");
        if (Files.size(json) > 64 * 1024 * 1024) {
            throw new IOException("Native metadata too large");
        }
        Description d;
        try (var r = dev.flower.stevedance.content.PackedResources.reader(json, 64 * 1024 * 1024)) {
            d = MotionPackLibrary.GSON.fromJson(r, Description.class);
        }
        if (d.schema != 2
                || !dev.flower.stevedance.SongCatalog.ALL.containsKey(d.id)
                || !d.id.equals(root.getFileName().toString())
                || d.durationMs != dev.flower.stevedance.SongCatalog.get(d.id).durationMs()
                || d.coordinateScale != 1
                || d.graphs.size() > 128
                || d.events.size() > 1024
                || d.timeline == null
                || d.timeline.size() > 1024) {
            throw new IOException("Invalid native import header");
        }
        List<ByteBuffer> allocated = new ArrayList<>();
        try (var in =
                new DataInputStream(
                        new BufferedInputStream(
                                dev.flower.stevedance.content.PackedResources.open(
                                        safe(root, "scene.bin"), 256 * 1024 * 1024)))) {
            if (in.readInt() != 0x44534632) {
                throw new IOException("Unsupported native scene format");
            }
            int version = in.readInt();
            if (version != 2 && version != 3 && version != 4) {
                throw new IOException("Unsupported native scene version");
            }
            int floats = version == 4 ? 29 : version == 3 ? 23 : 20;
            byte[][] images = new byte[count(in, 512)][];
            long imageBytes = 0;
            for (int i = 0; i < images.length; i++) {
                Path p = safe(root, in.readUTF());
                long size = Files.size(p);
                imageBytes += size;
                if (size > 8 * 1024 * 1024 || imageBytes > 128 * 1024 * 1024) {
                    throw new IOException("Native texture data exceeds budget");
                }
                images[i] = Files.readAllBytes(p);
            }
            Geometry[] geo = new Geometry[count(in, 2048)];
            long total = 0;
            for (int i = 0; i < geo.length; i++) {
                var g = new Geometry();
                geo[i] = g;
                g.name = in.readUTF();
                g.bones = new Bone[count(in, 128)];
                for (int j = 0; j < g.bones.length; j++) {
                    String name = in.readUTF();
                    float[] values = new float[16];
                    for (int k = 0; k < 16; k++) {
                        values[k] = finite(in);
                    }
                    g.bones[j] = new Bone(name, new Matrix4f().set(values));
                }
                g.batches = new Batch[count(in, 1024)];
                for (int j = 0; j < g.batches.length; j++) {
                    var b = new Batch();
                    g.batches[j] = b;
                    b.texture = in.readInt();
                    if (b.texture < -1 || b.texture >= images.length) {
                        throw new IOException("Invalid texture index");
                    }
                    b.diffuse = new float[4];
                    for (int k = 0; k < 4; k++) {
                        b.diffuse[k] = finite(in);
                    }
                    b.stride = floats * 4;
                    if (version >= 3) {
                        for (int k = 0; k < 4; k++) {
                            b.specular[k] = finite(in);
                        }
                        for (int k = 0; k < 4; k++) {
                            b.emission[k] = finite(in);
                        }
                        b.shininess = finite(in);
                    }
                    b.flags = in.readInt();
                    b.repeatU = in.readInt();
                    b.repeatV = in.readInt();
                    int n = count(in, 1000000);
                    total += (long) n * b.stride;
                    if (total > 96 * 1024 * 1024) {
                        throw new IOException("Native vertex budget exceeded");
                    }
                    b.vertices = MemoryUtil.memAlloc(n * b.stride);
                    allocated.add(b.vertices);
                    for (int k = 0; k < n * floats; k++) {
                        float v = finite(in);
                        b.vertices.putFloat(v);
                        int axis = k % floats;
                        if (axis < 3) {
                            b.center.setComponent(axis, b.center.get(axis) + v);
                        }
                    }
                    if (n > 0) {
                        b.center.div(n);
                    }
                    b.vertices.flip();
                    b.count = count(in, 3000000);
                    b.indices = MemoryUtil.memAlloc(b.count * 4);
                    allocated.add(b.indices);
                    for (int k = 0; k < b.count; k++) {
                        int index = in.readInt();
                        if (index < 0 || index >= n) {
                            throw new IOException("Invalid mesh index");
                        }
                        b.indices.putInt(index);
                    }
                    b.indices.flip();
                }
            }
            for (var g : geo) {
                Vector3f lo = new Vector3f(Float.POSITIVE_INFINITY),
                        hi = new Vector3f(Float.NEGATIVE_INFINITY);
                for (var b : g.batches) {
                    for (int offset = 0; offset < b.vertices.limit(); offset += b.stride) {
                        for (int axis = 0; axis < 3; axis++) {
                            float value = b.vertices.getFloat(offset + axis * 4);
                            lo.setComponent(axis, Math.min(lo.get(axis), value));
                            hi.setComponent(axis, Math.max(hi.get(axis), value));
                        }
                    }
                }
                g.extent = Math.max(hi.x - lo.x, Math.max(hi.y - lo.y, hi.z - lo.z));
            }
            if (in.read() != -1) {
                throw new IOException("Trailing native scene data");
            }
            var result = new PvSceneData(d, geo, images);
            try {
                result.screens = PvStageScreen.load(root, images.length);
                result.movie = PvMovieBackground.load(root);
                result.lighting = PvLighting.load(root);
                result.overlay = PvAetTimeline.load(root);
                result.subtitles =
                        PvLyrics.load(root, result.overlay == null ? null : result.overlay.lyrics);
                if (version == 4) {
                    result.loadMaterials(root);
                }
                return result;
            } catch (IOException | RuntimeException ex) {
                result.close();
                allocated.clear();
                throw ex;
            }
        } catch (IOException | RuntimeException ex) {
            for (var b : allocated) {
                MemoryUtil.memFree(b);
            }
            throw ex;
        }
    }

    private void loadMaterials(Path root) throws IOException {
        if (!hdr()) {
            throw new IOException("Scene v4 needs an explicit HDR render profile");
        }
        Materials data;
        Path file = safe(root, "materials.json");
        if (Files.size(file) > 4 * 1024 * 1024) {
            throw new IOException("Material metadata budget exceeded");
        }
        try (var reader = Files.newBufferedReader(file)) {
            data = MotionPackLibrary.GSON.fromJson(reader, Materials.class);
        }
        if (data.schema != 1 || data.cubes.size() > 16 || data.objects.size() != geometry.length) {
            throw new IOException("Invalid source materials");
        }
        for (var g : geometry) {
            var materials = data.objects.get(g.name);
            if (materials == null || materials.length != g.batches.length) {
                throw new IOException("Material batch mismatch: " + g.name);
            }
            for (int i = 0; i < materials.length; i++) {
                var m = materials[i];
                if (m.textures == null
                        || m.textures.length > 8
                        || m.ambient == null
                        || m.ambient.length != 4) {
                    throw new IOException("Invalid material slots");
                }
                if (m.sourceBlend != null || m.destinationBlend != null) {
                    boolean supported =
                            m.blendOperation == 0
                                    && ((("SrcAlpha".equals(m.sourceBlend)
                                                            || "One".equals(m.sourceBlend))
                                                    && "InverseSrcAlpha".equals(m.destinationBlend))
                                            || (("One".equals(m.sourceBlend)
                                                            || "SrcColor".equals(m.sourceBlend)
                                                            || "SrcAlpha".equals(m.sourceBlend))
                                                    && "One".equals(m.destinationBlend)));
                    if (!supported) {
                        throw new IOException(
                                "Unsupported source material blend: " + g.name + " batch " + i);
                    }
                }
                for (var t : m.textures) {
                    if (t.texture < 0
                            || t.texture >= images.length
                            || t.uvSet < 0
                            || t.uvSet > 1
                            || t.matrix == null
                            || t.matrix.length != 16
                            || !Float.isFinite(t.weight)) {
                        throw new IOException("Invalid source texture slot");
                    }
                    for (float v : t.matrix) {
                        if (!Float.isFinite(v)) {
                            throw new IOException("Nonfinite texture transform");
                        }
                    }
                }
                g.batches[i].sourceMaterial = m;
            }
        }
        for (var e : data.cubes.entrySet()) {
            int index = Integer.parseInt(e.getKey());
            if (index < 0 || index >= images.length || e.getValue().size() != 6) {
                throw new IOException("Invalid cube texture faces");
            }
            byte[][] faces = new byte[6][];
            for (int i = 0; i < 6; i++) {
                Path p = safe(root, e.getValue().get(i));
                if (Files.size(p) > 8 * 1024 * 1024) {
                    throw new IOException("Cube face budget exceeded");
                }
                faces[i] = Files.readAllBytes(p);
            }
            cubeImages.put(index, faces);
        }
        for (var g : geometry) {
            for (var batch : g.batches) {
                for (var slot : batch.sourceMaterial.textures) {
                    if (slot.type.equals("EnvironmentCube")
                            && !cubeImages.containsKey(slot.texture)) {
                        throw new IOException(
                                "Missing source environment cube: "
                                        + g.name
                                        + " texture "
                                        + slot.texture);
                    }
                }
            }
        }
    }

    private static int count(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) {
            throw new IOException("Native count exceeds budget");
        }
        return n;
    }

    private static float finite(DataInputStream in) throws IOException {
        float f = in.readFloat();
        if (!Float.isFinite(f)) {
            throw new IOException("Nonfinite native geometry");
        }
        return f;
    }

    private void bind() {
        if (itemBindings != null) {
            for (var binding : itemBindings) {
                if (binding.fitMode() != null && !binding.fitMode().equals("two-hand-instrument")) {
                    throw new IllegalArgumentException("Unsupported prop fit mode");
                }
            }
        }
        if (description.cameraCuts != null) {
            if (description.cameraCuts.size() > 32
                    || description.cameraTracks == null
                    || description.cameraTracks.size() > 8) {
                throw new IllegalArgumentException("Camera track budget exceeded");
            }
            double previous = -1;
            for (var cut : description.cameraCuts) {
                if (!Double.isFinite(cut.timeMs)
                        || !Double.isFinite(cut.startMs)
                        || cut.timeMs < previous
                        || cut.startMs < 0
                        || cut.startMs > cut.timeMs
                        || !description.cameraTracks.containsKey(cut.name)) {
                    throw new IllegalArgumentException("Invalid camera clock");
                }
                previous = cut.timeMs;
            }
            for (var entry : description.cameraTracks.entrySet()) {
                var camera = entry.getValue();
                for (var curve : camera.curves.values()) {
                    curve.validate();
                }
                for (var transform : camera.transforms.values()) {
                    for (var curve : transform.values()) {
                        curve.validate();
                    }
                }
                cameraRoots.put(
                        entry.getKey(),
                        new Transform(camera.transforms.get("camera_root.0.model_transform")));
            }
        }
        if (itemBindings != null) {
            if (itemBindings.size() > 32) {
                throw new IllegalArgumentException("Prop binding budget exceeded");
            }
            for (var b : itemBindings) {
                var graph = description.graphs.get(b.graph);
                if (b.actor < 0
                        || b.actor >= 16
                        || graph == null
                        || !Set.of("LeftHand", "RightHand", "UpperBody").contains(b.role)
                        || !(b.maximumSourceDistance > 0 && b.maximumSourceDistance <= .6f)
                        || Arrays.stream(graph.rigs).noneMatch(r -> r.name.equals(b.rig))
                        || description.itemTracks == null
                        || description.itemTracks.stream()
                                .noneMatch(t -> t.actor == b.actor && t.graph.equals(b.graph))) {
                    throw new IllegalArgumentException("Invalid explicit prop binding");
                }
                if (b.pairedWith != null) {
                    if (b.pairedWith.equals(b.rig)
                            || itemBindings.stream()
                                    .noneMatch(
                                            other ->
                                                    other.actor == b.actor
                                                            && other.graph.equals(b.graph)
                                                            && other.rig.equals(b.pairedWith)
                                                            && "two-hand-instrument"
                                                                    .equals(other.fitMode))) {
                        throw new IllegalArgumentException(
                                "Paired instrument has no two-hand binding");
                    }
                }
            }
        }
        for (var cut : description.timeline) {
            if (cut.maxFrames != null) {
                if (cut.maxFrames.size() > 128) {
                    throw new IllegalArgumentException("Scene clock budget exceeded");
                }
                for (var entry : cut.maxFrames.entrySet()) {
                    if (!description.graphs.containsKey(entry.getKey())
                            || entry.getValue() == null
                            || !Double.isFinite(entry.getValue())
                            || entry.getValue() < -1) {
                        throw new IllegalArgumentException("Invalid source scene frame limit");
                    }
                }
            }
        }
        if (description.sourceGraphClocks != null) {
            if (description.sourceGraphClocks.size() > 128) {
                throw new IllegalArgumentException("Authored graph clock budget exceeded");
            }
            int clockCount = 0;
            for (var entry : description.sourceGraphClocks.entrySet()) {
                if (!description.graphs.containsKey(entry.getKey())) {
                    throw new IllegalArgumentException("Clock refers to missing graph");
                }
                double previousEnd = -1;
                for (var clock : entry.getValue()) {
                    clock.validate();
                    if (clock.timeMs() < previousEnd) {
                        throw new IllegalArgumentException("Overlapping authored graph clocks");
                    }
                    previousEnd = clock.endMs();
                    clockCount++;
                }
            }
            if (clockCount > 16384) {
                throw new IllegalArgumentException("Authored graph clock total budget exceeded");
            }
        }
        int count = 0;
        for (var graph : description.graphs.values()) {
            for (var node : graph.nodes) {
                if (node.texturePatterns != null) {
                    for (var pattern : node.texturePatterns) {
                        if (!Float.isFinite(pattern.movieFps + pattern.movieStartMs)
                                || pattern.movieFps < 0
                                || pattern.movieFps > 60
                                || pattern.movieStartMs < 0
                                || pattern.movieStartMs > description.durationMs) {
                            throw new IllegalArgumentException("Invalid stage movie clock");
                        }
                    }
                }
            }
            if (graph.nodes.length > 1024 || graph.rigs.length > 128) {
                throw new IllegalArgumentException("Scene graph exceeds budget");
            }
            Map<String, Integer> names = new HashMap<>();
            for (int i = 0; i < graph.nodes.length; i++) {
                names.put(graph.nodes[i].name, i);
            }
            for (var node : graph.nodes) {
                node.animation = new Transform(node.transform, node.segments);
                node.geometry = byName.get(node.mesh);
                if (node.textureTransforms != null) {
                    if (node.textureTransforms.length > 8) {
                        throw new IllegalArgumentException("Texture animation budget exceeded");
                    }
                    for (var t : node.textureTransforms) {
                        if (t.texture < 0
                                || t.texture >= images.length
                                || t.curves == null
                                || t.curves.size() > 10) {
                            throw new IllegalArgumentException("Invalid animated texture");
                        }
                        for (var curve : t.curves.values()) {
                            curve.validate();
                        }
                    }
                }
                if (node.texturePatterns != null) {
                    if (node.texturePatterns.length > 8) {
                        throw new IllegalArgumentException("Texture pattern budget exceeded");
                    }
                    for (var p : node.texturePatterns) {
                        if (p.texture < 0
                                || p.texture >= images.length
                                || p.frames == null
                                || p.frames.length == 0
                                || p.frames.length > 1024
                                || !Float.isFinite(p.offset)
                                || p.curve == null) {
                            throw new IllegalArgumentException("Invalid texture pattern");
                        }
                        p.curve.validate();
                        for (int index : p.frames) {
                            if (index < -1 || index >= images.length) {
                                throw new IllegalArgumentException("Missing texture pattern frame");
                            }
                        }
                        p.selected = p.texture;
                    }
                }
                if (node.morph != null) {
                    node.morph.validate();
                    String prefix = node.mesh.replaceFirst("\\d{3}$", "");
                    List<Geometry> variants = new ArrayList<>();
                    for (int i = 0; i < 64; i++) {
                        var v = byName.get(prefix + String.format(Locale.ROOT, "%03d", i));
                        if (v == null) {
                            break;
                        }
                        variants.add(v);
                    }
                    node.variants = variants.toArray(Geometry[]::new);
                }
                node.parentIndex = names.getOrDefault(node.parent, -1);
                count++;
            }
            for (var rig : graph.rigs) {
                if (rig.multi) {
                    rig.groupAnimation = new Transform(rig.group);
                    rig.instanceAnimation = new Transform(rig.instance);
                }
                rig.geometry = byName.get(rig.mesh);
                rig.binding =
                        rig.geometry == null ? new int[0] : new int[rig.geometry.bones.length];
                Arrays.fill(rig.binding, -1);
                if (rig.nodes.length > 256) {
                    throw new IllegalArgumentException("Rig node budget exceeded");
                }
                for (int i = 0; i < rig.nodes.length; i++) {
                    var node = rig.nodes[i];
                    if (node.parent >= i || node.parent < -1) {
                        throw new IllegalArgumentException("Invalid native rig parent");
                    }
                    node.animation = new Transform(node.transform, node.segments);
                    for (int j = 0; j < rig.binding.length; j++) {
                        if (node.name.equals(rig.geometry.bones[j].name)) {
                            rig.binding[j] = i;
                        }
                    }
                }
                for (int b : rig.binding) {
                    if (b < 0) {
                        throw new IllegalArgumentException(
                                "Native skin bone has no animation node: " + rig.mesh);
                    }
                }
                count += rig.nodes.length;
            }
            // 中文：在渲染时间之外验证循环一次。
            // EN: Validate cycles once, away from render time.
            for (int i = 0; i < graph.nodes.length; i++) {
                int p = i, depth = 0;
                while (p >= 0) {
                    if (++depth > graph.nodes.length) {
                        throw new IllegalArgumentException("Cyclic native scene");
                    }
                    p = graph.nodes[p].parentIndex;
                }
            }
        }
        if (count > 10000) {
            throw new IllegalArgumentException("Native total node budget exceeded");
        }
    }

    public Cut cut(double ms) {
        Cut cut = description.timeline.get(0);
        for (var next : description.timeline) {
            if (next.timeMs > ms) {
                break;
            }
            cut = next;
        }
        return cut;
    }

    public List<String> active(double ms) {
        var c = cut(ms);
        var field = description.fields.getOrDefault(Integer.toString(c.field), List.of());
        var stage =
                description.stageGraphs == null
                        ? List.<String>of()
                        : description.stageGraphs.getOrDefault(c.stage, List.of());
        if (stage.isEmpty()) {
            return field;
        }
        var names = new ArrayList<String>(field);
        for (var graph : stage) {
            if (!names.contains(graph)) {
                names.add(graph);
            }
        }
        return names;
    }

    public double itemFrame(ItemTrack track, double ms) {
        double start = -1, cap = Double.POSITIVE_INFINITY;
        boolean visible = true;
        for (var e : description.events) {
            if (!e.command.equals("ITEM_ANIM")
                    || e.args[0] != track.actor
                    || e.args[1] != track.index) {
                continue;
            }
            if (e.timeMs > ms) {
                cap = e.timeMs;
                break;
            }
            if (e.args[2] == 0) {
                start = -1;
            } else if (e.args[2] == 1) {
                start = e.timeMs;
            }
            if (e.args[3] >= 0) {
                visible = e.args[3] != 0;
            }
        }
        if (start < 0 || !visible || !performerVisible(ms, track.actor)) {
            return -1;
        }
        var graph = description.graphs.get(track.graph);
        return Math.max(
                0,
                Math.min(
                        graph.size - 1,
                        Math.min(
                                (ms - start) * graph.fps / 1000,
                                Math.round((cap - start) * graph.fps / 1000) - 1)));
    }

    public double graphFrame(String name, double ms) {
        Graph graph = description.graphs.get(name);
        Cut cut = cut(ms);
        if (description.sourceGraphClocks != null
                && description.sourceGraphClocks.containsKey(name)) {
            var clocks = description.sourceGraphClocks.get(name);
            int lo = 0, hi = clocks.size();
            while (lo < hi) {
                int middle = (lo + hi) >>> 1;
                if (clocks.get(middle).timeMs() <= ms) {
                    lo = middle + 1;
                } else {
                    hi = middle;
                }
            }
            if (lo == 0) {
                return 0;
            }
            var clock = clocks.get(lo - 1);
            return clock.sample(Math.min(ms, clock.endMs()), graph.size - 1);
        }
        if (description.stageGraphs != null
                && description.stageGraphs.getOrDefault(cut.stage, List.of()).contains(name)) {
            double started = 0;
            String previous = "";
            for (var c : description.timeline) {
                if (c.timeMs > ms) {
                    break;
                }
                if (!c.stage.equals(previous)) {
                    started = c.timeMs;
                }
                previous = c.stage;
            }
            return graph.size <= 0
                    ? 0
                    : Math.max(0, (ms - started) * graph.fps / 1000) % graph.size;
        }
        double end = graph.size - 1;
        if (cut.maxFrames != null) {
            Double cap = cut.maxFrames.get(name);
            if (cap != null && cap >= 0) {
                end = Math.min(end, cap);
            }
        }
        return Math.max(
                0, Math.min(end, (ms - cut.starts.getOrDefault(name, 0.0)) * graph.fps / 1000));
    }

    public String stage(double ms) {
        return cut(ms).stage;
    }

    public boolean performerVisible(double ms) {
        return performerVisible(ms, 0);
    }

    public boolean performerVisible(double ms, int actor) {
        boolean visible = false;
        for (var event : description.events) {
            if (event.timeMs > ms) {
                break;
            }
            if (event.command.equals("MIKU_DISP") && event.args[0] == actor) {
                visible = event.args[1] != 0;
            }
        }
        return visible;
    }

    public void evaluate(Graph graph, double frame) {
        for (var n : graph.nodes) {
            n.evaluated = false;
        }
        for (int i = 0; i < graph.nodes.length; i++) {
            evaluateNode(graph, i, frame);
        }
        for (var rig : graph.rigs) {
            rig.visible = true;
            rig.model.identity();
            if (rig.multi) {
                rig.visible = rig.groupAnimation.apply(frame, identity, temporary);
                rig.visible &= rig.instanceAnimation.apply(frame, temporary, rig.model);
            }
            for (int j = 0; j < rig.nodes.length; j++) {
                var n = rig.nodes[j];
                Matrix4f base = n.parent < 0 ? identity : rig.nodes[n.parent].matrix;
                temporary.set(base);
                if (!rig.multi && n.jointOrient != null) {
                    temporary
                            .rotateZ(n.jointOrient[2])
                            .rotateY(n.jointOrient[1])
                            .rotateX(n.jointOrient[0]);
                }
                n.visible = n.animation.apply(frame, temporary, n.matrix);
                if (!rig.multi && j == 0) {
                    rig.visible &= n.visible;
                }
            }
            rig.palette.clear();
            if (rig.geometry != null) {
                for (int i = 0; i < rig.binding.length; i++) {
                    temporary
                            .set(rig.nodes[rig.binding[i]].matrix)
                            .mul(rig.geometry.bones[i].inverseBind);
                    temporary.get(rig.palette);
                    rig.palette.position(rig.palette.position() + 16);
                }
            }
            rig.palette.flip();
        }
    }

    private void evaluateNode(Graph graph, int i, double frame) {
        var n = graph.nodes[i];
        if (n.evaluated) {
            return;
        }
        Matrix4f base = identity;
        boolean parentVisible = true;
        if (n.parentIndex >= 0) {
            evaluateNode(graph, n.parentIndex, frame);
            base = graph.nodes[n.parentIndex].matrix;
            parentVisible = graph.nodes[n.parentIndex].visible;
        }
        n.visible = n.animation.apply(frame, base, n.matrix) && parentVisible;
        if (n.textureTransforms != null) {
            for (var t : n.textureTransforms) {
                t.evaluate(frame);
            }
        }
        if (n.texturePatterns != null) {
            for (var p : n.texturePatterns) {
                p.evaluate(frame);
            }
        }
        n.evaluated = true;
    }

    public PerformanceCameraController.Frame camera(
            double ms, net.minecraft.world.phys.Vec3 origin, float yaw, float aspect) {
        double frame = Math.max(0, ms) * .06;
        var camera = description.camera;
        var root = cameraRoot;
        if (description.cameraCuts != null) {
            CameraCut selected = null;
            for (var cut : description.cameraCuts) {
                if (cut.timeMs > ms) {
                    break;
                }
                selected = cut;
            }
            if (selected != null) {
                camera = description.cameraTracks.get(selected.name);
                root = cameraRoots.get(selected.name);
                frame = Math.max(0, ms - selected.startMs) * .06;
            }
        }
        return cameraFrame(camera, root, frame, origin, yaw, aspect, temporary, eye, interest);
    }

    private static PerformanceCameraController.Frame cameraFrame(
            Camera camera,
            Transform root,
            double frame,
            net.minecraft.world.phys.Vec3 origin,
            float yaw,
            float aspect,
            Matrix4f temporary,
            Vector3f eye,
            Vector3f interest) {
        root.apply(frame, new Matrix4f(), temporary);
        point(camera.transforms.get("camera_root.0.view_point.model_transform"), frame, eye);
        point(camera.transforms.get("camera_root.0.interest.model_transform"), frame, interest);
        temporary.transformPosition(eye);
        temporary.transformPosition(interest);
        // 中文：正确的180度旋转保留原生相机基和文本绕序。
        // EN: A proper 180 degree rotation preserves the native camera basis and text winding.
        eye.x = -eye.x;
        interest.x = -interest.x;
        eye.z = -eye.z;
        interest.z = -interest.z;
        float angle = (float) -java.lang.Math.toRadians(yaw);
        eye.rotateY(angle);
        interest.rotateY(angle);
        interest.sub(eye).normalize();
        float y = (float) java.lang.Math.toDegrees(java.lang.Math.atan2(-interest.x, interest.z)),
                pitch =
                        (float)
                                -java.lang.Math.toDegrees(
                                        java.lang.Math.atan2(
                                                interest.y,
                                                java.lang.Math.hypot(interest.x, interest.z)));
        double hFov = camera.curves.get("camera_root.0.view_point.fov").sample(frame),
                vfov = 2 * java.lang.Math.atan(java.lang.Math.tan(hFov / 2) / aspect);
        float roll =
                (float)
                        -java.lang.Math.toDegrees(
                                camera.curves.get("camera_root.0.view_point.roll").sample(frame));
        return new PerformanceCameraController.Frame(
                origin.add(eye.x, eye.y, eye.z),
                y,
                pitch,
                roll,
                (float) java.lang.Math.toDegrees(vfov),
                true,
                1);
    }

    /** 摄影只解析镜头，不分配舞台网格和贴图。 / Camera-only import without stage assets. */
    public static final class CameraOnly {
        private final Matrix4f temporary = new Matrix4f();
        private final Vector3f eye = new Vector3f(), interest = new Vector3f();
        private final Camera camera;
        private final CameraCut[] cuts;
        private final Map<String, Camera> tracks;
        private final Transform root;
        private final Map<String, Transform> roots = new HashMap<>();
        public final int durationMs;

        public CameraOnly(Path path) throws IOException {
            if (Files.size(path) > 64L * 1024 * 1024) {
                throw new IOException("Camera metadata exceeds budget");
            }
            com.google.gson.JsonObject data;
            try (var reader =
                    dev.flower.stevedance.content.PackedResources.reader(path, 64 * 1024 * 1024)) {
                data = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            }
            var gson = new com.google.gson.Gson();
            durationMs = data.get("durationMs").getAsInt();
            camera = gson.fromJson(data.get("camera"), Camera.class);
            root = new Transform(camera.transforms.get("camera_root.0.model_transform"));
            cuts =
                    data.has("cameraCuts")
                            ? gson.fromJson(data.get("cameraCuts"), CameraCut[].class)
                            : new CameraCut[0];
            tracks = new HashMap<>();
            if (data.has("cameraTracks")) {
                for (var e : data.getAsJsonObject("cameraTracks").entrySet()) {
                    var c = gson.fromJson(e.getValue(), Camera.class);
                    tracks.put(e.getKey(), c);
                    roots.put(
                            e.getKey(),
                            new Transform(c.transforms.get("camera_root.0.model_transform")));
                }
            }
        }

        public PerformanceCameraController.Frame sample(
                double ms, net.minecraft.world.phys.Vec3 origin, float yaw, float aspect) {
            Camera c = camera;
            Transform r = root;
            double frame = Math.max(0, ms) * .06;
            for (var cut : cuts) {
                if (cut.timeMs > ms) {
                    break;
                }
                c = tracks.get(cut.name);
                r = roots.get(cut.name);
                frame = Math.max(0, ms - cut.startMs) * .06;
            }
            return cameraFrame(c, r, frame, origin, yaw, aspect, temporary, eye, interest);
        }
    }

    private static void point(Map<String, PvAnimationCurve> t, double frame, Vector3f v) {
        v.set(
                t.get("translation.x").sample(frame),
                t.get("translation.y").sample(frame),
                t.get("translation.z").sample(frame));
    }

    public void validateMotion(dev.flower.stevedance.core.PvBodyMotion motion) throws IOException {
        String expected = description.motionCoordinateProfile();
        if (expected == null) {
            return;
        }
        if (!Set.of("legacy", "source-world-ysm-2.6.5").contains(expected)) {
            throw new IOException("Unsupported scene motion coordinate profile");
        }
        if (motion.sourceWorldCoordinates != expected.equals("source-world-ysm-2.6.5")) {
            throw new IOException("Motion and scene coordinate profiles differ");
        }
    }

    public void releaseCpu() {
        for (var g : geometry) {
            for (var b : g.batches) {
                if (b.vertices != null) {
                    MemoryUtil.memFree(b.vertices);
                    b.vertices = null;
                }
                if (b.indices != null) {
                    MemoryUtil.memFree(b.indices);
                    b.indices = null;
                }
            }
        }
    }

    public void close() {
        releaseCpu();
        for (var g : description.graphs.values()) {
            for (var r : g.rigs) {
                MemoryUtil.memFree(r.palette);
            }
        }
    }
}
