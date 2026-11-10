package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 世界保留在其真实深度缓冲区中；在此 PV 期间仅其颜色附件使用 HDR。
 * <p>The world stays in its real depth buffer; only its color attachment uses HDR during this PV.</p>
 */
public final class PvHdrRenderer {
    private static final String VS =
            "#version 150\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}";
    public static int frames;
    public static boolean enableDof = true, enableBloom = true;
    private static final float[] NO_BLOOM = {0, 0, 0};
    private static final int[] VIEWPORT = new int[4], OLD_TEXTURES = new int[8];
    private static int vao, width, height, combineFbo, combineColor;
    private static boolean ownsHdr;
    private static Program blur, dof, combine;
    private static Target capture, focus;
    private static final Target[] glow = new Target[4], ping = new Target[4];

    public static boolean active() {
        return dev.flower.stevedance.client.PvVisibility.visible("post")
                && PvPlaybackController.nativeCharacterLighting()
                && PvPlaybackController.sceneryActive()
                && PvPlaybackController.scene.hdr();
    }

    public static void prepare() {
        var target = Minecraft.getInstance().getMainRenderTarget();
        boolean requested = active();
        if (!requested && !ownsHdr) {
            return;
        }
        int oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getColorTextureId());
            int desired = requested ? GL30.GL_RGBA16F : GL11.GL_RGBA8;
            if (GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)
                    != desired) {
                GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        0,
                        desired,
                        target.width,
                        target.height,
                        0,
                        GL11.GL_RGBA,
                        GL11.GL_FLOAT,
                        0L);
            }
            ownsHdr = requested;
            if (!requested) {
                release();
            }
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            GL13.glActiveTexture(oldActive);
        }
    }

    private static final class Program {
        final int id;
        final Map<String, Integer> locations = new HashMap<>();

        Program(String file) {
            int vs = shader(GL20.GL_VERTEX_SHADER, VS),
                    fs = shader(GL20.GL_FRAGMENT_SHADER, source(file));
            id = GL20.glCreateProgram();
            GL20.glAttachShader(id, vs);
            GL20.glAttachShader(id, fs);
            GL20.glLinkProgram(id);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(id));
            }
        }

        int loc(String name) {
            return locations.computeIfAbsent(name, n -> GL20.glGetUniformLocation(id, n));
        }

        void use() {
            GL20.glUseProgram(id);
        }

        void f(String n, float v) {
            GL20.glUniform1f(loc(n), v);
        }

        void i(String n, int v) {
            GL20.glUniform1i(loc(n), v);
        }

        void v2(String n, float x, float y) {
            GL20.glUniform2f(loc(n), x, y);
        }

        void v3(String n, float[] v) {
            GL20.glUniform3f(loc(n), v[0], v[1], v[2]);
        }

        void v4(String n, float x, float y, float z, float w) {
            GL20.glUniform4f(loc(n), x, y, z, w);
        }
    }

    private static String source(String file) {
        try (var in =
                PvHdrRenderer.class.getResourceAsStream("/assets/stevedance/shaders/" + file)) {
            if (in == null) {
                throw new IOException("Missing HDR shader " + file);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int shader(int type, String source) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == 0) {
            String log = GL20.glGetShaderInfoLog(id);
            GL20.glDeleteShader(id);
            throw new IllegalStateException(log);
        }
        return id;
    }

    private static final class Target {
        final int texture, fbo, w, h;

        Target(int w, int h) {
            this.w = w;
            this.h = h;
            texture = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL30.GL_RGBA16F,
                    w,
                    h,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_FLOAT,
                    0L);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(
                    GL30.GL_DRAW_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    texture,
                    0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)
                    != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete native HDR attachment");
            }
        }

        void draw() {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL11.glViewport(0, 0, w, h);
        }

        void close() {
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(texture);
        }
    }

    private static void init(int w, int h) {
        if (blur == null) {
            blur = new Program("native_hdr_blur.frag");
            dof = new Program("native_hdr_dof.frag");
            combine = new Program("native_hdr_combine.frag");
            vao = GL30.glGenVertexArrays();
        }
        if (width == w && height == h) {
            return;
        }
        releaseTargets();
        width = w;
        height = h;
        capture = new Target(w, h);
        focus = new Target(Math.max(1, w / 2), Math.max(1, h / 2));
        for (int i = 0; i < 4; i++) {
            int factor = 1 << (i + 1);
            glow[i] = new Target(Math.max(1, w / factor), Math.max(1, h / factor));
            ping[i] = new Target(Math.max(1, w / factor), Math.max(1, h / factor));
        }
    }

    private static void texture(int unit, int id) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
    }

    // 中文：在通过仅颜色 FBO 写入时采样世界深度。采样任何
    // EN: Sample world depth while writing through a color-only FBO. Sampling any
    // 中文：附加到目标帧缓冲区的纹理，即使关闭深度写入也是未定义的。
    // EN: texture attached to the destination framebuffer is undefined even with depth writes off.
    private static void bindOutput(int color) {
        if (combineFbo == 0) {
            combineFbo = GL30.glGenFramebuffers();
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, combineFbo);
        if (combineColor != color) {
            GL30.glFramebufferTexture2D(
                    GL30.GL_DRAW_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    color,
                    0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)
                    != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete native HDR output");
            }
            combineColor = color;
        }
    }

    private static void quad() {
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    public static void render() {
        if (!active()) {
            return;
        }
        var mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        var light = PvPlaybackController.scene.lighting;
        light.sample(PvPlaybackController.timeMs());
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] viewport = VIEWPORT;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int[] oldTextures = OLD_TEXTURES;
        for (int i = 0; i < 8; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            oldTextures[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            init(target.width, target.height);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            GL30.glBindVertexArray(vao);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, capture.fbo);
            GL30.glBlitFramebuffer(
                    0,
                    0,
                    width,
                    height,
                    0,
                    0,
                    width,
                    height,
                    GL11.GL_COLOR_BUFFER_BIT,
                    GL11.GL_NEAREST);
            blur.use();
            blur.i("Image", 0);
            blur.v3("Threshold", light.brightpass);
            for (int i = 0; i < 4; i++) {
                var input = i == 0 ? capture : glow[i - 1];
                ping[i].draw();
                texture(0, input.texture);
                blur.i("Extract", i == 0 ? (light.intensityBloom() ? 2 : 1) : 0);
                blur.v2("Step", 1f / input.w, 0);
                quad();
                glow[i].draw();
                texture(0, ping[i].texture);
                blur.i("Extract", 0);
                blur.v2("Step", 0, Math.max(.25f, light.radius[1]) / ping[i].h);
                quad();
            }
            focus.draw();
            dof.use();
            texture(0, capture.texture);
            texture(1, target.getDepthTextureId());
            dof.i("Image", 0);
            dof.i("Depth", 1);
            dof.v2("Texel", 1f / width, 1f / height);
            var projection = RenderSystem.getProjectionMatrix();
            dof.v2("DepthProjection", projection.m22(), projection.m32());
            dof.v4(
                    "Focus",
                    light.dofFocus,
                    light.dofRange,
                    Math.max(.01f, light.dofFuzz),
                    enableDof && light.dofEnabled ? light.dofRatio : 0);
            dof.f("PixelScale", height / 720f);
            quad();
            bindOutput(target.getColorTextureId());
            GL11.glViewport(0, 0, width, height);
            combine.use();
            combine.i("IntensityBloom", light.intensityBloom() ? 1 : 0);
            texture(0, capture.texture);
            texture(1, focus.texture);
            for (int i = 0; i < 4; i++) {
                texture(i + 2, glow[i].texture);
            }
            texture(6, target.getDepthTextureId());
            combine.i("Image", 0);
            combine.i("FocusImage", 1);
            for (int i = 0; i < 4; i++) {
                combine.i("Bloom" + i, i + 2);
            }
            combine.i("Depth", 6);
            combine.v2("DepthProjection", projection.m22(), projection.m32());
            combine.v3("BloomColor", enableBloom ? light.bloomColor : NO_BLOOM);
            combine.f("ToneExposure", light.toneExposure);
            combine.f("ToneGamma", light.toneGamma);
            combine.f("Saturation", light.saturation);
            combine.v3("Gamma", light.gamma);
            combine.f("Contrast", light.contrast);
            combine.v4("Fade", light.fade[0], light.fade[1], light.fade[2], light.fade[3]);
            combine.v3("FogColor", light.fogColor);
            combine.v4("Fog", light.fogStart, light.fogEnd, light.fogDensity, light.fogType);
            quad();
            frames++;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL30.glBindVertexArray(oldVao);
            GL20.glUseProgram(oldProgram);
            for (int i = 0; i < 8; i++) {
                texture(i, oldTextures[i]);
            }
            GL13.glActiveTexture(oldActive);
            state(GL11.GL_DEPTH_TEST, depth);
            state(GL11.GL_BLEND, blend);
            state(GL11.GL_CULL_FACE, cull);
            GL11.glDepthMask(mask);
        }
    }

    private static void state(int flag, boolean enabled) {
        if (enabled) {
            GL11.glEnable(flag);
        } else {
            GL11.glDisable(flag);
        }
    }

    private static void releaseTargets() {
        if (combineFbo != 0) {
            GL30.glDeleteFramebuffers(combineFbo);
            combineFbo = combineColor = 0;
        }
        if (capture != null) {
            capture.close();
            capture = null;
        }
        if (focus != null) {
            focus.close();
            focus = null;
        }
        for (int i = 0; i < 4; i++) {
            if (glow[i] != null) {
                glow[i].close();
                glow[i] = null;
            }
            if (ping[i] != null) {
                ping[i].close();
                ping[i] = null;
            }
        }
        width = height = 0;
    }

    public static void release() {
        releaseTargets();
        if (blur != null) {
            GL20.glDeleteProgram(blur.id);
            GL20.glDeleteProgram(dof.id);
            GL20.glDeleteProgram(combine.id);
            blur = dof = combine = null;
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
    }
}
