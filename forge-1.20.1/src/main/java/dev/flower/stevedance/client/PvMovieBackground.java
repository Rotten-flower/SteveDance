package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.flower.stevedance.MotionPackLibrary;
import dev.flower.stevedance.media.MovieDecoder;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Shared song-clock movie backgrounds for original PVs and chart-only playback.
 * 原作 PV 与纯谱面共用歌曲时钟取帧，PV 演员仍在世界中渲染。 */
public final class PvMovieBackground {
    public record Interval(
            double startMs, double endMs, Double sourceStartMs, Integer sourceEndFrame) {}

    private record Metadata(
            int schema, double fps, int frameCount, String video, List<Interval> intervals) {}

    public record Data(double fps, int frameCount, Path video, List<Interval> intervals) {}

    private static Data installed;
    private static int texture, width, height;
    private static ByteBuffer uploadBuffer;
    private static MovieDecoder decoder;
    private static int frame = -1, program, vao;
    private static long frameEpoch = -1;
    private static Interval frameInterval;
    private static int firstAccepted = -1;
    public static int uploadedFrames, drawnFrames, delayedFrames;

    public static void install(Data data) {
        clear();
        installed = data;
        if (data != null) {
            decoder =
                    new MovieDecoder(
                            data.video,
                            Minecraft.getInstance()
                                    .gameDirectory
                                    .toPath()
                                    .resolve("stevedance/cache/video"),
                            data.fps,
                            data.frameCount);
            var first = data.intervals.get(0);
            decoder.request(
                    (int)
                            Math.floor(
                                    (first.sourceStartMs == null ? 0 : first.sourceStartMs)
                                                    * data.fps
                                                    / 1000
                                            + 1e-7));
        }
    }

    /**
     * 视频首帧与舞台一起预热，准备好后才开始演出时钟。
     * <p>Warms the first movie frame with the stage before starting the performance clock.</p>
     */
    public static boolean ready() {
        if (installed == null) {
            return true;
        }
        try {
            return decoder != null && decoder.current() != null;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot prepare original PV movie background", ex);
        }
    }

    public static Data load(Path root) throws IOException {
        if (!Files.isRegularFile(root.resolve("movie-background.json"))) {
            return null;
        }
        Path path = PvSceneData.safe(root, "movie-background.json");
        if (Files.size(path) > 1024 * 1024) {
            throw new IOException("Movie metadata exceeds budget");
        }
        Metadata m;
        try (var reader = Files.newBufferedReader(path)) {
            m = MotionPackLibrary.GSON.fromJson(reader, Metadata.class);
        }
        if (m == null
                || m.schema != 2
                || !Double.isFinite(m.fps)
                || m.fps <= 0
                || m.fps > 60
                || m.frameCount < 1
                || m.frameCount > 36000
                || m.video == null
                || m.intervals == null
                || m.intervals.isEmpty()) {
            throw new IOException("Invalid movie metadata (MP4 schema 2 required)");
        }
        Path video = PvSceneData.safe(root, m.video);
        if (!Files.isRegularFile(video)
                || Files.size(video) < 32
                || Files.size(video) > 512L * 1024 * 1024) {
            throw new IOException("Invalid movie file");
        }
        byte[] header;
        try (var input = Files.newInputStream(video)) {
            header = input.readNBytes(12);
        }
        // Logical resource names accept MP4/H.264 and WebM/VP9 signatures.
        boolean mp4 = header.length == 12 && header[4] == 102 && header[5] == 116
                && header[6] == 121 && header[7] == 112;
        boolean webm = header.length == 12 && (header[0] & 255) == 0x1a
                && (header[1] & 255) == 0x45 && (header[2] & 255) == 0xdf
                && (header[3] & 255) == 0xa3;
        if (!mp4 && !webm) {
            throw new IOException("Movie must contain an MP4 or WebM stream");
        }
        double end = -1;
        for (var i : m.intervals) {
            if (i == null
                    || !Double.isFinite(i.startMs + i.endMs)
                    || i.startMs < 0
                    || i.startMs < end
                    || i.endMs <= i.startMs
                    || i.sourceStartMs != null
                            && (!Double.isFinite(i.sourceStartMs) || i.sourceStartMs < 0)
                    || (i.sourceStartMs == null ? 0 : i.sourceStartMs) * m.fps / 1000
                            >= m.frameCount
                    || i.sourceEndFrame != null
                            && (i.sourceEndFrame <= 0
                                    || i.sourceEndFrame > m.frameCount
                                    || (i.sourceStartMs == null ? 0 : i.sourceStartMs)
                                                    * m.fps
                                                    / 1000
                                            >= i.sourceEndFrame)) {
                throw new IOException("Invalid movie interval");
            }
            end = i.endMs;
        }
        return new Data(m.fps, m.frameCount, video, List.copyOf(m.intervals));
    }

    private static Interval interval(Data data, double ms) {
        if (data != null) {
            for (var i : data.intervals) {
                if (ms >= i.startMs && ms < i.endMs) {
                    return i;
                }
            }
        }
        return null;
    }

    public static boolean active(double ms) {
        return dev.flower.stevedance.client.PvVisibility.visible("movie")
                && PvPlaybackController.active()
                && PvPlaybackController.isolatedPlayback()
                && PvPlaybackController.sceneryActive()
                && interval(PvPlaybackController.scene.movie, ms) != null;
    }

    /** 纯谱面只绘制影片，按原比例留黑边。 / Letterbox the movie beneath the rhythm HUD. */
    public static boolean renderChart(net.minecraft.client.gui.GuiGraphics graphics, double ms) {
        var data = installed;
        var interval = interval(data, ms);
        if (interval == null) {
            return false;
        }
        graphics.flush();
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            if (!update(data, interval, ms)) {
                return false;
            }
            if (program == 0) {
                initialize();
            }
            double scale = Math.min((double) viewport[2] / width, (double) viewport[3] / height);
            int w = Math.max(1, (int) Math.round(width * scale)),
                    h = Math.max(1, (int) Math.round(height * scale));
            GL11.glViewport(
                    viewport[0] + (viewport[2] - w) / 2, viewport[1] + (viewport[3] - h) / 2, w, h);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL20.glUseProgram(program);
            RenderSystem.bindTexture(texture);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            drawnFrames++;
            return true;
        } catch (IOException ex) {
            clear();
            throw new IllegalStateException("Cannot read chart movie background", ex);
        } finally {
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL30.glBindVertexArray(oldVao);
            GL20.glUseProgram(oldProgram);
            RenderSystem.bindTexture(oldTexture);
            RenderSystem.activeTexture(oldActive);
            GL11.glDepthMask(mask);
            toggle(GL11.GL_DEPTH_TEST, depth);
            toggle(GL11.GL_BLEND, blend);
            toggle(GL11.GL_CULL_FACE, cull);
        }
    }

    private static boolean update(Data data, Interval interval, double ms) throws IOException {
        double sourceMs =
                ms
                        - interval.startMs
                        + (interval.sourceStartMs == null ? 0 : interval.sourceStartMs);
        int last = interval.sourceEndFrame == null ? data.frameCount : interval.sourceEndFrame;
        int selected = Math.min(last - 1, (int) Math.floor(sourceMs * data.fps / 1000 + 1e-7));
        long epoch = PvPlaybackController.epoch();
        if (frameEpoch != epoch || frameInterval != interval) {
            frameEpoch = epoch;
            frameInterval = interval;
            firstAccepted = selected;
            frame = -1;
        }
        if (selected != frame) {
            decoder.request(selected);
            var decoded = decoder.current();
            if (decoded != null
                    && decoded.frame() >= firstAccepted
                    && decoded.frame() <= selected
                    && decoded.frame() > frame) {
                upload(decoded);
                frame = decoded.frame();
                uploadedFrames++;
            }
        }
        if (frame < firstAccepted || frame > selected || texture == 0) {
            return false;
        }
        if (selected - frame > 2) {
            delayedFrames++;
        }
        return true;
    }

    public static void render() {
        double ms = PvPlaybackController.timeMs();
        if (!active(ms)) {
            return;
        }
        var data = PvPlaybackController.scene.movie;
        var interval = interval(data, ms);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int func = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        try {
            if (installed != data) {
                install(data);
            }
            // 顺播短卡保留最近帧；实际时钟回执或片段切换后只接收新位置的画面。
            // 中文：在解码停顿期间保持；已确认的跳转/剪切会使旧帧失效。
            // EN: Hold through decode stalls; an acknowledged seek/cut invalidates the old frame.
            if (!update(data, interval, ms)) {
                return;
            }
            if (program == 0) {
                initialize();
            }
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL20.glUseProgram(program);
            RenderSystem.bindTexture(texture);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            drawnFrames++;
        } catch (IOException ex) {
            clear();
            throw new IllegalStateException("Cannot read original PV movie background", ex);
        } finally {
            GL30.glBindVertexArray(oldVao);
            GL20.glUseProgram(oldProgram);
            RenderSystem.bindTexture(oldTexture);
            RenderSystem.activeTexture(oldActive);
            GL11.glDepthFunc(func);
            GL11.glDepthMask(mask);
            toggle(GL11.GL_DEPTH_TEST, depth);
            toggle(GL11.GL_BLEND, blend);
            toggle(GL11.GL_CULL_FACE, cull);
        }
    }

    private static void upload(MovieDecoder.Image image) {
        int pbo = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING),
                alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),
                row = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH),
                rows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS),
                columns = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            if (texture == 0 || width != image.width() || height != image.height()) {
                if (texture != 0) {
                    GL11.glDeleteTextures(texture);
                }
                if (uploadBuffer != null) {
                    MemoryUtil.memFree(uploadBuffer);
                }
                width = image.width();
                height = image.height();
                uploadBuffer = MemoryUtil.memAlloc(width * height * 4);
                texture = GL11.glGenTextures();
                RenderSystem.bindTexture(texture);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(
                        GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        GL11.GL_RGBA8,
                        width,
                        height,
                        0,
                        GL11.GL_RGBA,
                        GL11.GL_UNSIGNED_BYTE,
                        (ByteBuffer) null);
            } else {
                RenderSystem.bindTexture(texture);
            }
            uploadBuffer.clear().put(image.rgba()).flip();
            GL11.glTexSubImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    width,
                    height,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    uploadBuffer);
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, row);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, rows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, columns);
        }
    }

    private static void toggle(int flag, boolean value) {
        if (value) {
            GL11.glEnable(flag);
        } else {
            GL11.glDisable(flag);
        }
    }

    private static int shader(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(s);
            GL20.glDeleteShader(s);
            throw new IllegalStateException(log);
        }
        return s;
    }

    private static void initialize() {
        int vs =
                shader(
                        GL20.GL_VERTEX_SHADER,
                        """
            #version 150
            out vec2 uv;
            void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=vec2(p.x,1.0-p.y);gl_Position=vec4(p*2.0-1.0,1.0,1.0);}
            """);
        int fs =
                shader(
                        GL20.GL_FRAGMENT_SHADER,
                        """
            #version 150
            uniform sampler2D Image;in vec2 uv;out vec4 fragColor;
            void main(){fragColor=vec4(texture(Image,uv).rgb,1.0);}
            """);
        program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            String log = GL20.glGetProgramInfoLog(program);
            clear();
            throw new IllegalStateException(log);
        }
        vao = GL30.glGenVertexArrays();
    }

    public static void clear() {
        if (decoder != null) {
            decoder.close();
        }
        decoder = null;
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
        }
        texture = width = height = 0;
        if (uploadBuffer != null) {
            MemoryUtil.memFree(uploadBuffer);
        }
        uploadBuffer = null;
        installed = null;
        frame = -1;
        frameEpoch = -1;
        frameInterval = null;
        firstAccepted = -1;
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
        vao = program = 0;
    }
}
