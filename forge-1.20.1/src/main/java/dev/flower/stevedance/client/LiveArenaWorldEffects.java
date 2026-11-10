package dev.flower.stevedance.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;

import dev.flower.stevedance.MotionPackLibrary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Source particle shaders/meshes and native deterministic replay in the world.
 * 使用原粒子材质、网格与预烘焙参数；不替换成 Minecraft 通用粒子。 */
// 生命周期：install上传本曲GPU资源，captureScreen/render在渲染线程使用，clear释放。
// Lifecycle: install uploads song GPU resources, render-thread passes consume them, clear releases them.
public final class LiveArenaWorldEffects {
    public static boolean enabled = true, screenEnabled = true, crowdEnabled = true;
    public static int frames, particleInstances, crowdInstances, screenFrames, screenDraws;
    private static final MultiBufferSource.BufferSource feedBuffers =
            MultiBufferSource.immediate(new BufferBuilder(2048));

    public static final class Material {
        String name, vertex, fragment, shader;
        JsonObject uniforms;
        Map<String, Integer> textures;
        int src, dst, cull;
        boolean zwrite;
        transient int program;
        final transient Map<String, Integer> locations = new HashMap<>();
    }

    public static final class Crowd {
        int mesh, material, count;
        String instances;
        transient FloatBuffer data;
    }

    public static final class Replay {
        String name, file;
        int mesh, material, mode, maxInstances, frames;
        transient int[] counts, offsets;
        transient float[] data;
    }

    public static final class Data {
        int schema;
        Material[] materials;
        String[] meshes, textures;
        Crowd[] crowd;
        Replay[] particleReplay;
        JsonObject penlight, screen;
        JsonObject[] textureSettings;
    }

    private static final class Mesh {
        int vao, vbo, ebo, instances, count;
    }

    private static Data data;
    private static Mesh[] meshes;
    private static Mesh quad;
    private static int[] textures;
    private static final List<ResourceLocation> registered = new ArrayList<>();
    private static PvSceneData scene;
    private static PvSceneData.Geometry screenGeometry;
    private static TextureTarget screen;
    private static final FloatBuffer matrixBuffer = MemoryUtil.memAllocFloat(16);
    private static FloatBuffer instances;
    private static final Matrix4f vp = new Matrix4f(),
            inverse = new Matrix4f(),
            matrix = new Matrix4f();
    private static final Vector3f point = new Vector3f(),
            right = new Vector3f(),
            up = new Vector3f(),
            direction = new Vector3f();
    private static boolean capture;
    private static double seconds;

    private static double beat() {
        double previous = 0, total = 0;
        float speed = 1;
        for (var e : data.penlight.getAsJsonObject("events").getAsJsonArray("penLightDataList")) {
            var x = e.getAsJsonObject();
            if (x.get("paramType").getAsInt() != 1) {
                continue;
            }
            double t = x.get("second").getAsDouble();
            if (t > seconds) {
                break;
            }
            total += (t - previous) * speed;
            previous = t;
            speed = x.get("bpmValue").getAsFloat();
        }
        return (total + (seconds - previous) * speed) * 190 / 60;
    }

    private static final String FALLBACK_VS =
            """
        #version 150
        in vec4 in_POSITION0;
        in vec2 in_TEXCOORD0;
        in vec4 in_COLOR0;
        uniform vec4 hlslcc_mtx4x4unity_ObjectToWorld[4];uniform vec4 hlslcc_mtx4x4unity_MatrixVP[4];
        out vec2 uv;out vec4 color;
        void main(){mat4 m=mat4(hlslcc_mtx4x4unity_ObjectToWorld[0],hlslcc_mtx4x4unity_ObjectToWorld[1],hlslcc_mtx4x4unity_ObjectToWorld[2],hlslcc_mtx4x4unity_ObjectToWorld[3]);mat4 v=mat4(hlslcc_mtx4x4unity_MatrixVP[0],hlslcc_mtx4x4unity_MatrixVP[1],hlslcc_mtx4x4unity_MatrixVP[2],hlslcc_mtx4x4unity_MatrixVP[3]);gl_Position=v*m*in_POSITION0;uv=in_TEXCOORD0;color=in_COLOR0;}
        """;
    private static final String FALLBACK_FS =
            """
        #version 150
        in vec2 uv;in vec4 color;uniform sampler2D _MainTex;out vec4 SV_Target0;
        void main(){SV_Target0=texture(_MainTex,uv)*color;}
        """;

    private static int shader(int type, String source) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(id);
            GL20.glDeleteShader(id);
            throw new IllegalStateException(error);
        }
        return id;
    }

    private static String instanced(String source, boolean crowd) {
        String header =
                "\nin vec4 Instance0,Instance1,Instance2,Instance3,InstanceColor,InstanceCustom,InstanceRandom;\nuniform float NativeBeat,NativeRandomness,NativeRandomRotate,NativeRandomMove,NativeMicroWidth;\n";
        source = source.replaceFirst("\\n", "\n" + header);
        source =
                source.replaceAll(
                        "uniform\\s+vec4\\s+hlslcc_mtx4x4unity_ObjectToWorld\\[4\\];",
                        "#define hlslcc_mtx4x4unity_ObjectToWorld (mat4(Instance0,Instance1,Instance2,Instance3))\n");
        source =
                source.replaceAll(
                        "uniform\\s+vec4\\s+hlslcc_mtx4x4unity_WorldToObject\\[4\\];",
                        "#define hlslcc_mtx4x4unity_WorldToObject (inverse(mat4(Instance0,Instance1,Instance2,Instance3)))\n");
        source = source.replaceAll("in\\s+vec4\\s+in_COLOR0;", "#define in_COLOR0 InstanceColor\n");
        source =
                source.replaceAll(
                        "in\\s+vec4\\s+in_TEXCOORD0;",
                        "in vec2 NativeUV;\n#define in_TEXCOORD0 vec4(NativeUV,InstanceCustom.xy)\n");
        if (!crowd) {
            source =
                    source.replaceAll(
                            "in\\s+vec2\\s+in_TEXCOORD1;",
                            "#define in_TEXCOORD1 InstanceCustom.xy\n");
            source =
                    source.replaceAll(
                            "in\\s+vec4\\s+in_TEXCOORD1;", "#define in_TEXCOORD1 InstanceCustom\n");
        }
        if (crowd) {
            // 中文：原生网格实例使用仿射模型矩阵，来源粒子流的
            // EN: Native mesh instances use affine model matrices. The source particle
            // 中文：齐次表达式对这些矩阵求值得到零。
            // EN: stream's homogeneous expression evaluates to zero for those matrices.
            source =
                    source.replace(
                            "gl_Position = u_xlat3 * u_xlat5.xxxx + u_xlat0;",
                            "gl_Position = u_xlat3 + u_xlat0;");
            source =
                    source.replaceAll(
                            "uniform\\s+float\\s+_RandomTime;",
                            "#define _RandomTime (NativeBeat+InstanceRandom.x*NativeRandomness)\n");
            source =
                    source.replaceAll(
                            "uniform\\s+float\\s+_RandomRotation;",
                            "#define _RandomRotation ((InstanceRandom.y*2-1)*NativeRandomRotate)\n");
            source =
                    source.replaceAll(
                            "uniform\\s+float\\s+_RandomMovement;",
                            "#define _RandomMovement ((InstanceRandom.z*2-1)*NativeRandomMove)\n");
            source =
                    source.replaceAll(
                            "uniform\\s+float\\s+_RandomMicroWidth;",
                            "#define _RandomMicroWidth (InstanceRandom.w*NativeMicroWidth)\n");
            source =
                    source.replace(
                            "vs_TEXCOORD2 = uint(0u);", "vs_TEXCOORD2 = uint(InstanceCustom.w);");
        }
        return source;
    }

    private static void program(Material m, boolean screenMaterial) {
        String v = m.vertex.isEmpty() ? FALLBACK_VS : m.vertex,
                f = m.fragment.isEmpty() ? FALLBACK_FS : m.fragment;
        if (!screenMaterial) {
            v = instanced(v, m.shader.contains("MobShader"));
        }
        int vs = shader(GL20.GL_VERTEX_SHADER, v), fs = shader(GL20.GL_FRAGMENT_SHADER, f);
        m.program = GL20.glCreateProgram();
        GL20.glAttachShader(m.program, vs);
        GL20.glAttachShader(m.program, fs);
        GL20.glBindAttribLocation(m.program, 0, "in_POSITION0");
        GL20.glBindAttribLocation(m.program, 1, "in_NORMAL0");
        GL20.glBindAttribLocation(m.program, 2, "in_TEXCOORD0");
        GL20.glBindAttribLocation(m.program, 2, "NativeUV");
        GL20.glBindAttribLocation(m.program, 3, "in_COLOR0");
        GL20.glBindAttribLocation(m.program, 4, "in_TEXCOORD1");
        for (int i = 0; i < 4; i++) {
            GL20.glBindAttribLocation(m.program, 5 + i, "Instance" + i);
        }
        GL20.glBindAttribLocation(m.program, 9, "InstanceColor");
        GL20.glBindAttribLocation(m.program, 10, "InstanceCustom");
        GL20.glBindAttribLocation(m.program, 11, "InstanceRandom");
        GL30.glBindFragDataLocation(m.program, 0, "SV_Target0");
        GL20.glLinkProgram(m.program);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(m.program, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException(m.name + ": " + GL20.glGetProgramInfoLog(m.program));
        }
    }

    private static int loc(Material m, String name) {
        return m.locations.computeIfAbsent(name, n -> GL20.glGetUniformLocation(m.program, n));
    }

    private static void value(Material m, String name, JsonElement v) {
        int at = loc(m, name);
        if (at < 0) {
            return;
        }
        if (v.isJsonArray()) {
            var a = v.getAsJsonArray();
            switch (a.size()) {
                case 2 -> GL20.glUniform2f(at, a.get(0).getAsFloat(), a.get(1).getAsFloat());
                case 3 ->
                        GL20.glUniform3f(
                                at,
                                a.get(0).getAsFloat(),
                                a.get(1).getAsFloat(),
                                a.get(2).getAsFloat());
                case 4 ->
                        GL20.glUniform4f(
                                at,
                                a.get(0).getAsFloat(),
                                a.get(1).getAsFloat(),
                                a.get(2).getAsFloat(),
                                a.get(3).getAsFloat());
                default -> throw new IllegalArgumentException("Invalid source uniform " + name);
            }
        } else if (name.equals("_MovementType")) {
            GL20.glUniform1i(at, v.getAsInt());
        } else {
            GL20.glUniform1f(at, v.getAsFloat());
        }
    }

    private static void number(Material m, String name, float value) {
        int l = loc(m, name);
        if (l >= 0) {
            GL20.glUniform1f(l, value);
        }
    }

    private static void matrix(Material m, String name, Matrix4f value) {
        int l = loc(m, name);
        if (l < 0) {
            l = loc(m, name + "[0]");
        }
        if (l >= 0) {
            matrixBuffer.clear();
            value.get(matrixBuffer);
            GL20.glUniform4fv(l, matrixBuffer);
        }
    }

    private static Mesh mesh(float[] vertices, int[] indices) {
        Mesh m = new Mesh();
        m.vao = GL30.glGenVertexArrays();
        m.vbo = GL15.glGenBuffers();
        m.ebo = GL15.glGenBuffers();
        m.instances = GL15.glGenBuffers();
        m.count = indices.length;
        GL30.glBindVertexArray(m.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, m.vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, m.ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        int[] size = {3, 3, 2, 4, 2}, offset = {0, 12, 24, 32, 48};
        for (int i = 0; i < 5; i++) {
            GL20.glEnableVertexAttribArray(i);
            GL20.glVertexAttribPointer(i, size[i], GL11.GL_FLOAT, false, 56, offset[i]);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, m.instances);
        for (int i = 0; i < 7; i++) {
            int at = 5 + i;
            GL20.glEnableVertexAttribArray(at);
            GL20.glVertexAttribPointer(at, 4, GL11.GL_FLOAT, false, 112, i * 16L);
            GL33.glVertexAttribDivisor(at, 1);
        }
        return m;
    }

    public static void install(PvSceneData value) throws IOException {
        clear();
        Path root = PvSceneData.root(value.description.id()).resolve("livearena-extra");
        if (!Files.isRegularFile(root.resolve("assets.json"))) {
            return;
        }
        try (State ignored = new State()) {
            data =
                    MotionPackLibrary.GSON.fromJson(
                            Files.readString(root.resolve("assets.json")), Data.class);
            if (data.schema != 1
                    || data.materials.length > 32
                    || data.meshes.length > 32
                    || data.textures.length > 64) {
                throw new IOException("Invalid Live Arena world effects");
            }
            scene = value;
            LiveArenaDynamics.install(root);
            textures = new int[data.textures.length + 1];
            var manager = Minecraft.getInstance().getTextureManager();
            for (int i = 0; i < textures.length; i++) {
                NativeImage image =
                        i == data.textures.length
                                ? new NativeImage(1, 1, false)
                                : AssetImage.read(
                                        Files.readAllBytes(root.resolve(data.textures[i])));
                if (i == data.textures.length) {
                    image.setPixelRGBA(0, 0, 0xffffffff);
                } else {
                    for (int y = 0; y < image.getHeight() / 2; y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int a = image.getPixelRGBA(x, y),
                                    b = image.getPixelRGBA(x, image.getHeight() - 1 - y);
                            image.setPixelRGBA(x, y, b);
                            image.setPixelRGBA(x, image.getHeight() - 1 - y, a);
                        }
                    }
                }
                DynamicTexture t = new DynamicTexture(image);
                t.setFilter(true, false);
                registered.add(manager.register("stevedance_livearena_effect_" + i, t));
                textures[i] = t.getId();
                if (i < data.textures.length && data.textureSettings != null) {
                    var settings = data.textureSettings[i];
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.getId());
                    for (int axis = 0; axis < 2; axis++) {
                        String key = axis == 0 ? "m_WrapU" : "m_WrapV";
                        int wrap = settings.has(key) ? settings.get(key).getAsInt() : 0;
                        GL11.glTexParameteri(
                                GL11.GL_TEXTURE_2D,
                                axis == 0 ? GL11.GL_TEXTURE_WRAP_S : GL11.GL_TEXTURE_WRAP_T,
                                wrap == 1
                                        ? GL12.GL_CLAMP_TO_EDGE
                                        : wrap == 2 ? GL14.GL_MIRRORED_REPEAT : GL11.GL_REPEAT);
                    }
                }
            }
            meshes = new Mesh[data.meshes.length];
            for (int i = 0; i < meshes.length; i++) {
                var raw =
                        ByteBuffer.wrap(Files.readAllBytes(root.resolve(data.meshes[i])))
                                .order(ByteOrder.BIG_ENDIAN);
                int n = raw.getInt(), count = raw.getInt();
                if (n < 1
                        || n > 1000000
                        || count < 1
                        || count > 6000000
                        || raw.remaining() != (long) n * 56 + (long) count * 4) {
                    throw new IOException("Invalid source effect mesh");
                }
                float[] vertices = new float[n * 14];
                raw.asFloatBuffer().get(vertices);
                raw.position(raw.position() + n * 56);
                int[] ix = new int[count];
                raw.asIntBuffer().get(ix);
                meshes[i] = mesh(vertices, ix);
            }
            float[] q = {
                -.5f, -.5f, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1, 0, 0, .5f, -.5f, 0, 0, 0, 1, 1, 0, 1, 1,
                1, 1, 0, 0, .5f, .5f, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, -.5f, .5f, 0, 0, 0, 1, 0,
                1, 1, 1, 1, 1, 0, 0
            };
            quad = mesh(q, new int[] {0, 1, 2, 0, 2, 3});
            instances = MemoryUtil.memAllocFloat(8192 * 28);
            int screenMaterial = data.screen.get("material").getAsInt();
            for (int i = 0; i < data.materials.length; i++) {
                program(data.materials[i], i == screenMaterial);
            }
            for (var c : data.crowd) {
                if (c.count < 0 || c.count > 8192) {
                    throw new IOException("Invalid crowd count");
                }
                var raw =
                        ByteBuffer.wrap(Files.readAllBytes(root.resolve(c.instances)))
                                .order(ByteOrder.BIG_ENDIAN);
                if (raw.remaining() != c.count * 112) {
                    throw new IOException("Invalid crowd buffer");
                }
                c.data = MemoryUtil.memAllocFloat(c.count * 28);
                c.data.put(raw.asFloatBuffer()).flip();
            }
            for (var p : data.particleReplay) {
                var raw =
                        ByteBuffer.wrap(Files.readAllBytes(root.resolve(p.file)))
                                .order(ByteOrder.BIG_ENDIAN);
                int frames = raw.getInt(), stride = raw.getInt();
                if (frames != p.frames || stride != 21 || frames > 36000 || p.maxInstances > 8192) {
                    throw new IOException("Invalid particle replay header");
                }
                p.counts = new int[frames];
                p.offsets = new int[frames];
                int count = 0;
                for (int i = 0; i < frames; i++) {
                    int n = raw.getInt();
                    if (n < 0 || n > 8192) {
                        throw new IOException("Invalid particle frame count");
                    }
                    p.counts[i] = n;
                    p.offsets[i] = count;
                    count = Math.addExact(count, n * 21);
                }
                if (raw.remaining() != (long) count * 4) {
                    throw new IOException("Invalid particle replay length");
                }
                p.data = new float[count];
                raw.asFloatBuffer().get(p.data);
                for (float f : p.data) {
                    if (!Float.isFinite(f)) {
                        throw new IOException("Nonfinite particle replay");
                    }
                }
            }
            screenGeometry = value.byName.get(data.screen.get("geometry").getAsString());
            if (screenGeometry == null) {
                throw new IOException("Source screen mesh is absent");
            }
            screen = new TextureTarget(365, 135, true, Minecraft.ON_OSX);
            screen.setClearColor(.03724635f, .05693945f, .25471699f, 1);
            screen.clear(Minecraft.ON_OSX);
        } catch (IOException | RuntimeException e) {
            clear();
            throw e;
        }
    }

    private static int blend(int value) {
        return switch (value) {
            case 0 -> GL11.GL_ZERO;
            case 1 -> GL11.GL_ONE;
            case 2 -> GL11.GL_DST_COLOR;
            case 3 -> GL11.GL_SRC_COLOR;
            case 4 -> GL11.GL_ONE_MINUS_DST_COLOR;
            case 5 -> GL11.GL_SRC_ALPHA;
            case 6 -> GL11.GL_ONE_MINUS_SRC_COLOR;
            case 7 -> GL11.GL_DST_ALPHA;
            case 8 -> GL11.GL_ONE_MINUS_DST_ALPHA;
            case 9 -> GL11.GL_SRC_ALPHA_SATURATE;
            case 10 -> GL11.GL_ONE_MINUS_SRC_ALPHA;
            default -> throw new IllegalArgumentException("Unknown source blend " + value);
        };
    }

    private static void prepare(Material m, Matrix4f view) {
        GL20.glUseProgram(m.program);
        for (var e : m.uniforms.entrySet()) {
            value(m, e.getKey(), e.getValue());
        }
        matrix(m, "hlslcc_mtx4x4unity_MatrixVP", vp);
        inverse.set(view).invert();
        int camera = loc(m, "_WorldSpaceCameraPos");
        if (camera >= 0) {
            GL20.glUniform3f(camera, inverse.m30(), inverse.m31(), inverse.m32());
        }
        int time = loc(m, "_Time");
        if (time >= 0) {
            GL20.glUniform4f(
                    time,
                    (float) seconds / 20,
                    (float) seconds,
                    (float) seconds * 2,
                    (float) seconds * 3);
        }
        int unit = 0;
        for (var e : m.textures.entrySet()) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[e.getValue()]);
            GL20.glUniform1i(loc(m, e.getKey()), unit++);
        }
        if (m.textures.isEmpty()) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, textures[textures.length - 1]);
            GL20.glUniform1i(loc(m, "_MainTex"), 0);
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(m.zwrite);
        if (m.src == 1 && m.dst == 0) {
            GL11.glDisable(GL11.GL_BLEND);
        } else {
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(
                    blend(m.src), blend(m.dst), GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        if (m.cull == 0) {
            GL11.glDisable(GL11.GL_CULL_FACE);
        } else {
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(m.cull == 1 ? GL11.GL_FRONT : GL11.GL_BACK);
        }
    }

    private static void draw(
            Mesh mesh, Material material, FloatBuffer buffer, int count, Matrix4f view) {
        if (count == 0) {
            return;
        }
        prepare(material, view);
        GL30.glBindVertexArray(mesh.vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, mesh.instances);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        GL31.glDrawElementsInstanced(
                GL11.GL_TRIANGLES, mesh.count, GL11.GL_UNSIGNED_INT, 0L, count);
    }

    private static void crowd(Material m) {
        var presets = data.penlight.getAsJsonArray("presets");
        var events = data.penlight.getAsJsonObject("events");
        String preset = "Mob_Stay";
        float speed = 1;
        float[] percentages = new float[8];
        for (var e : events.getAsJsonArray("penLightDataList")) {
            var x = e.getAsJsonObject();
            if (seconds < x.get("second").getAsDouble()
                    || seconds >= x.get("second").getAsDouble() + x.get("duration").getAsDouble()) {
                continue;
            }
            switch (x.get("paramType").getAsInt()) {
                case 0 -> preset = x.get("presetName").getAsString();
                case 1 -> speed = x.get("bpmValue").getAsFloat();
                case 2 -> {
                    var c = x.getAsJsonObject("colorPercent");
                    int i = c.get("index").getAsInt() - 1;
                    if (i >= 0 && i < 8) {
                        percentages[i] = c.get("percent").getAsFloat();
                    }
                }
            }
        }
        for (var p : presets) {
            var x = p.getAsJsonObject();
            if (x.get("Key").getAsString().equals(preset)) {
                for (var v : x.getAsJsonArray("presetParams")) {
                    var param = v.getAsJsonObject();
                    value(m, param.get("paramName").getAsString(), param.get("value"));
                }
            }
        }
        number(m, "NativeBeat", (float) beat());
        for (var p : presets) {
            var x = p.getAsJsonObject();
            if (x.get("Key").getAsString().equals(preset)) {
                for (var v : x.getAsJsonArray("presetParams")) {
                    var q = v.getAsJsonObject();
                    String nativeName =
                            switch (q.get("paramName").getAsString()) {
                                case "_Randomness" -> "NativeRandomness";
                                case "_RandomRotate" -> "NativeRandomRotate";
                                case "_RandomMove" -> "NativeRandomMove";
                                case "_MicroWidth" -> "NativeMicroWidth";
                                default -> null;
                            };
                    if (nativeName != null) {
                        number(m, nativeName, q.get("value").getAsFloat());
                    }
                }
            }
        }
        String[] suffix = {"1st", "2nd", "3rd", "4th", "5th", "6th", "7th", "8th"};
        var colors = data.penlight.getAsJsonObject("colors").getAsJsonArray("colorSets");
        for (var ev : events.getAsJsonArray("penLightColorDataList")) {
            var x = ev.getAsJsonObject();
            int index = x.get("index").getAsInt() - 1;
            for (var cc : colors) {
                var c = cc.getAsJsonObject();
                if (c.get("colorIndex").getAsInt() != x.get("colorIndex").getAsInt()) {
                    continue;
                }
                for (String type : new String[] {"mainColor", "subColor"}) {
                    var v = c.getAsJsonObject(type);
                    int l =
                            loc(
                                    m,
                                    (type.equals("mainColor") ? "_Color_" : "_SubColor_")
                                            + suffix[index]);
                    if (l >= 0) {
                        GL20.glUniform4f(
                                l,
                                v.get("r").getAsFloat(),
                                v.get("g").getAsFloat(),
                                v.get("b").getAsFloat(),
                                v.get("a").getAsFloat());
                    }
                }
            }
        }
        // 中文：固定的来源随机样本按歌曲颜色比例分配。
        // EN: Fixed source random sample is partitioned by the song's color percentages.
        float sum = 0;
        for (float f : percentages) {
            sum += f;
        }
        if (sum <= 0) {
            percentages[0] = 1;
            sum = 1;
        }
        for (var c : data.crowd) {
            var f = c.data;
            for (int i = 0; i < c.count; i++) {
                float r = f.get(i * 28 + 24) * sum, total = 0;
                int color = 0;
                for (int j = 0; j < 8; j++) {
                    total += percentages[j];
                    if (r < total) {
                        color = j;
                        break;
                    }
                }
                f.put(i * 28 + 23, color);
                f.put(i * 28 + 20, f.get(i * 28 + 24));
            }
        }
    }

    public static void render(Matrix4f sourceView) {
        if (!enabled || data == null || capture || !PvPlaybackController.sceneryActive()) {
            return;
        }
        seconds = PvPlaybackController.timeMs() / 1000;
        LiveArenaDynamics.sample(seconds * 1000);
        particleInstances = crowdInstances = 0;
        try (State ignored = new State()) {
            vp.set(RenderSystem.getProjectionMatrix()).mul(sourceView);
            Material cm = data.materials[data.crowd[0].material];
            prepare(cm, sourceView);
            crowd(cm);
            if (crowdEnabled) {
                for (var c : data.crowd) {
                    GL30.glBindVertexArray(meshes[c.mesh].vao);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, meshes[c.mesh].instances);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, c.data, GL15.GL_STREAM_DRAW);
                    GL31.glDrawElementsInstanced(
                            GL11.GL_TRIANGLES,
                            meshes[c.mesh].count,
                            GL11.GL_UNSIGNED_INT,
                            0L,
                            c.count);
                    crowdInstances += c.count;
                }
            }
            inverse.set(sourceView).invert();
            var billboard = new Quaternionf();
            inverse.getUnnormalizedRotation(billboard);
            for (var p : data.particleReplay) {
                int frame = Math.max(0, Math.min(p.frames - 1, (int) (seconds * 60)));
                int n = p.counts[frame];
                if (n == 0) {
                    continue;
                }
                instances.clear();
                int base = p.offsets[frame];
                for (int i = 0; i < n; i++) {
                    int at = base + i * 21;
                    float[] row = p.data;
                    matrix.identity().translate(row[at], row[at + 1], row[at + 2]);
                    if (p.mode == 0 || p.mode == 3) {
                        matrix.rotate(billboard).rotateZ(row[at + 8]);
                    } else if (p.mode == 2) {
                        matrix.rotateX((float) -Math.PI / 2);
                    } else if (p.mode == 6) {
                        direction.set(row[at + 6], row[at + 7], row[at + 8]).normalize();
                        point.set(
                                        inverse.m30() - row[at],
                                        inverse.m31() - row[at + 1],
                                        inverse.m32() - row[at + 2])
                                .normalize();
                        right.set(direction).cross(point).normalize();
                        up.set(right).cross(direction).normalize();
                        matrix.setColumn(0, new Vector4f(right, 0));
                        matrix.setColumn(1, new Vector4f(direction, 0));
                        matrix.setColumn(2, new Vector4f(up, 0));
                    } else {
                        matrix.rotateZ(row[at + 8]).rotateX(row[at + 6]).rotateY(row[at + 7]);
                    }
                    matrix.scale(row[at + 3], row[at + 4], row[at + 5]);
                    matrix.get(instances);
                    instances.position(instances.position() + 16);
                    for (int k = 9; k < 21; k++) {
                        instances.put(row[at + k]);
                    }
                }
                instances.flip();
                draw(
                        p.mesh < 0 ? quad : meshes[p.mesh],
                        data.materials[p.material],
                        instances,
                        n,
                        sourceView);
                particleInstances += n;
            }
            frames++;
        }
    }

    /**
     * 仅演员实时画面，不替代世界演员或世界舞台绘制。
     * <p>Actor-only live feed; it never replaces world actors or world stage rendering.</p>
     */
    public static void captureScreen(Matrix4f stageView) {
        if (!enabled
                || !screenEnabled
                || data == null
                || capture
                || PvPlaybackController.worldActors().isEmpty()) {
            return;
        }
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        var sorting = RenderSystem.getVertexSorting();
        try (State ignored = new State()) {
            capture = true;
            // 深度写掩码也影响深度清除；沿用透明舞台的false会留下无效屏幕深度。
            // Depth clearing obeys the write mask, which may be false after transparent stage draws.
            GL11.glDepthMask(true);
            screen.clear(Minecraft.ON_OSX);
            screen.bindWrite(true);
            var feedProjection = new Matrix4f(projection);
            feedProjection.m00(feedProjection.m11() / (365f / 135));
            RenderSystem.setProjectionMatrix(feedProjection, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.getModelViewStack().pushPose();
            try {
                RenderSystem.getModelViewStack().setIdentity();
                RenderSystem.applyModelViewMatrix();
                var pose = new PoseStack();
                // 使用本次世界通道的真实视图旋转；演员坐标已在下面减去相机位置。
                // Keep the actual view rotation; actor translation below is already camera-relative.
                pose.mulPoseMatrix(new Matrix4f(stageView).m30(0).m31(0).m32(0));
                var dispatcher = mc.getEntityRenderDispatcher();
                var buffers = feedBuffers;
                RenderSystem.enableDepthTest();
                for (var actor : PvPlaybackController.worldActors().values()) {
                    pose.pushPose();
                    dispatcher.render(
                            actor,
                            actor.getX() - camera.getPosition().x,
                            actor.getY() - camera.getPosition().y,
                            actor.getZ() - camera.getPosition().z,
                            actor.getYRot(),
                            mc.getFrameTime(),
                            pose,
                            buffers,
                            15728880);
                    pose.popPose();
                }
                buffers.endBatch();
                screenFrames++;
            } finally {
                RenderSystem.getModelViewStack().popPose();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(projection, sorting);
            }
        } finally {
            capture = false;
        }
    }

    public static boolean screen(PvSceneData.Batch batch, Matrix4f model) {
        if (!enabled || !screenEnabled || data == null || capture || screenGeometry == null) {
            return false;
        }
        boolean match = false;
        for (var b : screenGeometry.batches) {
            if (b == batch) {
                match = true;
            }
        }
        if (!match) {
            return false;
        }
        try (State ignored = new State()) {
            Material m = data.materials[data.screen.get("material").getAsInt()];
            vp.set(RenderSystem.getProjectionMatrix());
            prepare(m, new Matrix4f());
            matrix(m, "hlslcc_mtx4x4unity_ObjectToWorld", model);
            matrix(m, "hlslcc_mtx4x4unity_MatrixVP", vp);
            number(m, "_ScreenCameraSwitch", 1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + 6);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, screen.getColorTextureId());
            GL20.glUniform1i(loc(m, "_ScreenCameraTex"), 6);
            // 中文：独立原作网格保留两套UV，不修改舞台VAO。
            // EN: Dedicated original mesh retains both UV sets; no stage-VAO edits.
            GL20.glUniform4f(loc(m, "_ScreenCameraTex_ST"), 1, 1, 0, 0);
            Mesh mesh = meshes[data.screen.get("mesh").getAsInt()];
            GL30.glBindVertexArray(mesh.vao);
            GL11.glDrawElements(GL11.GL_TRIANGLES, mesh.count, GL11.GL_UNSIGNED_INT, 0L);
            screenDraws++;
        }
        return true;
    }

    /**
     * 嵌套源效果通道的raw GL保存点。外层舞台还会恢复自己的raw状态。
     * 勿将这些raw值通过GlStateManager写回MC缓存，否则外层恢复后缓存与GL会不一致。
     * <p>Nested raw-GL scope; do not publish raw snapshots into MC caches behind the outer scope.</p>
     */
    private static final class State implements AutoCloseable {
        final net.minecraft.client.renderer.ShaderInstance shader = RenderSystem.getShader();
        final int[] shaderTextures = new int[8];
        final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                array = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING),
                active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
                read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                cullMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        final int sr = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                dr = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                sa = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                da = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int[] viewport = new int[4], texture = new int[8];

        State() {
            for (int i = 0; i < 8; i++) {
                shaderTextures[i] = RenderSystem.getShaderTexture(i);
            }
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            for (int i = 0; i < 8; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                texture[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            GL13.glActiveTexture(active);
        }

        public void close() {
            RenderSystem.setShader(() -> shader);
            for (int i = 0; i < 8; i++) {
                RenderSystem.setShaderTexture(i, shaderTextures[i]);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, array);
            for (int i = 0; i < 8; i++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture[i]);
            }
            GL13.glActiveTexture(active);
            GL14.glBlendFuncSeparate(sr, dr, sa, da);
            GL11.glCullFace(cullMode);
            GL11.glDepthMask(mask);
            state(GL11.GL_DEPTH_TEST, depth);
            state(GL11.GL_BLEND, blend);
            state(GL11.GL_CULL_FACE, cull);
        }
    }

    private static void state(int flag, boolean on) {
        if (on) {
            GL11.glEnable(flag);
        } else {
            GL11.glDisable(flag);
        }
    }

    private static void delete(Mesh m) {
        if (m != null) {
            GL30.glDeleteVertexArrays(m.vao);
            GL15.glDeleteBuffers(m.vbo);
            GL15.glDeleteBuffers(m.ebo);
            GL15.glDeleteBuffers(m.instances);
        }
    }

    public static void clear() {
        LiveArenaDynamics.clear();
        if (data != null) {
            for (var m : data.materials) {
                if (m.program != 0) {
                    GL20.glDeleteProgram(m.program);
                }
            }
            if (data.crowd != null) {
                for (var c : data.crowd) {
                    if (c.data != null) {
                        MemoryUtil.memFree(c.data);
                    }
                }
            }
        }
        if (meshes != null) {
            for (var m : meshes) {
                delete(m);
            }
        }
        delete(quad);
        quad = null;
        meshes = null;
        if (instances != null) {
            MemoryUtil.memFree(instances);
        }
        instances = null;
        var manager = Minecraft.getInstance().getTextureManager();
        for (var id : registered) {
            manager.release(id);
        }
        registered.clear();
        if (screen != null) {
            screen.destroyBuffers();
        }
        screen = null;
        screenGeometry = null;
        data = null;
        textures = null;
        scene = null;
        frames = screenFrames = screenDraws = particleInstances = crowdInstances = 0;
    }
}
