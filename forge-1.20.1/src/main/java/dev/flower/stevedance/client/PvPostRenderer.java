package dev.flower.stevedance.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * 将源后期参数应用于已渲染的世界，包括真实的YSM实体。
 * <p>Applies source post parameters to the already-rendered world, including real YSM entities.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class PvPostRenderer {
    private static final String VS =
            """
        #version 150
        out vec2 uv;
        void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.0-1.0,0,1);}
        """;
    private static final String FS =
            """
        #version 150
        uniform sampler2D Image;uniform vec2 Texel;uniform vec3 BloomColor;uniform vec3 Brightpass;uniform vec3 Radius;
        uniform vec4 Fade;uniform float Saturation;uniform float Exposure;uniform vec3 Gamma;uniform float Contrast;uniform float Lightness;
        in vec2 uv;out vec4 fragColor;
        void main(){vec4 source=texture(Image,uv);vec3 glow=vec3(0);float sum=0;
            for(int x=-2;x<=2;x++)for(int y=-2;y<=2;y++){float w=exp(-float(x*x+y*y)*.5);vec3 c=texture(Image,uv+vec2(x,y)*Texel*Radius.xy).rgb;glow+=max(c-Brightpass,vec3(0))*w;sum+=w;}
            vec3 c=source.rgb+glow/sum*BloomColor;
            float lum=dot(c,vec3(.2126,.7152,.0722));c=mix(vec3(lum),c,Saturation)*Exposure*Lightness;
            // CCRT gamma=2 is the identity tone range, not a square-root transfer.
            vec3 value=max(Gamma+vec3(Contrast),vec3(.125));vec3 power=value*.5;
            vec3 start=vec3(0),end=vec3(1);
            for(int i=0;i<3;i++){if(value[i]<2.0)start[i]=pow(.075,power[i]);else if(value[i]>2.0)end[i]=pow(.95,power[i]);}
            c=(c-start)/max(end-start,vec3(.0001));
            c=mix(c,Fade.rgb,clamp(Fade.a,0,1));fragColor=vec4(c,source.a);}
        """;
    private static int program, vao, texture, fbo, width, height;
    public static boolean enabled = true;
    public static int frames;

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

    private static void init(int w, int h) {
        if (program == 0) {
            int v = shader(GL20.GL_VERTEX_SHADER, VS), f = shader(GL20.GL_FRAGMENT_SHADER, FS);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, v);
            GL20.glAttachShader(program, f);
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(v);
            GL20.glDeleteShader(f);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
            vao = GL30.glGenVertexArrays();
            texture = GL11.glGenTextures();
            fbo = GL30.glGenFramebuffers();
        }
        if (width != w || height != h) {
            width = w;
            height = h;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA8,
                    w,
                    h,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    0L);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(
                    GL30.GL_DRAW_FRAMEBUFFER,
                    GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D,
                    texture,
                    0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)
                    != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("PV post framebuffer incomplete");
            }
        }
    }

    private static int loc(String n) {
        return GL20.glGetUniformLocation(program, n);
    }

    private static void vector(String n, float[] v) {
        GL20.glUniform3f(loc(n), v[0], v[1], v[2]);
    }

    public static void render() {
        if (enabled && dev.flower.stevedance.client.LiveArenaPostRenderer.render()) {
            return;
        }
        if (!dev.flower.stevedance.client.PvVisibility.visible("post")
                || !enabled
                || !PvPlaybackController.nativeCharacterLighting()
                || !PvPlaybackController.active()
                || PvPlaybackController.scene.lighting == null) {
            return;
        }
        if (PvPlaybackController.scene.hdr()) {
            PvHdrRenderer.render();
            return;
        }
        var target = Minecraft.getInstance().getMainRenderTarget();
        var light = PvPlaybackController.scene.lighting;
        light.sample(PvPlaybackController.timeMs());
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            init(target.width, target.height);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
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
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            GL20.glUseProgram(program);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL20.glUniform1i(loc("Image"), 0);
            GL20.glUniform2f(loc("Texel"), 1f / width, 1f / height);
            vector("BloomColor", light.bloomColor);
            vector("Brightpass", light.brightpass);
            vector("Radius", light.radius);
            vector("Gamma", light.gamma);
            var f = light.fade;
            GL20.glUniform4f(loc("Fade"), f[0], f[1], f[2], f[3]);
            GL20.glUniform1f(loc("Saturation"), light.saturation);
            GL20.glUniform1f(loc("Exposure"), light.exposure);
            GL20.glUniform1f(loc("Lightness"), light.lightness);
            GL20.glUniform1f(loc("Contrast"), light.contrast);
            GL30.glBindVertexArray(vao);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            frames++;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            GL30.glBindVertexArray(oldVao);
            GL20.glUseProgram(oldProgram);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            GL13.glActiveTexture(oldActive);
            state(GL11.GL_DEPTH_TEST, depth);
            state(GL11.GL_BLEND, blend);
            state(GL11.GL_CULL_FACE, cull);
            GL11.glDepthMask(mask);
        }
    }

    private static void state(int flag, boolean value) {
        if (value) {
            GL11.glEnable(flag);
        } else {
            GL11.glDisable(flag);
        }
    }

    public static void clear() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
        }
        if (fbo != 0) {
            GL30.glDeleteFramebuffers(fbo);
        }
        if (texture != 0) {
            GL11.glDeleteTextures(texture);
        }
        program = vao = fbo = texture = width = height = 0;
    }
}
