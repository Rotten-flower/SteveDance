package dev.flower.stevedance.client;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Unity PPv2 fast bloom math on the available Minecraft LDR world buffer.
 * 泛光按源阈值、软拐点和强度计算；LDR 输入不等同原引擎 HDR。 */
public final class LiveArenaPostRenderer {
    private static final String VS =
            """
        #version 150
        out vec2 uv;
        void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2-1,0,1);}
        """;
    private static final String FS =
            """
        #version 150
        uniform sampler2D Image, Base, Depth;
        uniform vec2 Texel;
        uniform int Pass;
        uniform vec4 Threshold;
        uniform float Intensity, SampleScale;
        uniform vec3 BloomColor;
        uniform vec2 DepthProjection, ProjectionScale;
        uniform vec3 CameraUp;
        uniform float CameraHeight, Aspect;
        uniform vec4 FogStart, FogEnd, FogRange, FlareColor0, FlareColor1, FlareShape;
        uniform vec4 FogShape;
        uniform vec2 FlareStops;
        in vec2 uv;out vec4 fragColor;
        vec3 linearColor(vec3 c){return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),step(vec3(.04045),c));}
        vec3 encodedColor(vec3 c){c=max(c,vec3(0));return mix(c*12.92,1.055*pow(c,vec3(1.0/2.4))-.055,step(vec3(.0031308),c));}
        vec3 sampleColor(vec2 p){return texture(Image,p).rgb;}
        void main(){
            if(Pass==4){
                vec4 source=texture(Image,uv);vec3 c=linearColor(source.rgb);float depth=texture(Depth,uv).r;
                if(depth<.999999){
                    float z=DepthProjection.y/(depth*2-1+DepthProjection.x);
                    vec3 p=vec3((uv*2-1)*z/ProjectionScale,-z);
                    float height=CameraHeight+dot(p,CameraUp);
                    float distanceFog=clamp((length(p)-FogRange.x)/max(.001,FogRange.y-FogRange.x),0,1);
                    float heightFog=pow(1-clamp((height-FogRange.z)/max(.001,FogRange.w-FogRange.z),0,1),max(.01,FogShape.y));
                    vec3 fogColor=mix(FogStart.rgb,FogEnd.rgb,distanceFog);
                    c=mix(c,fogColor,clamp(distanceFog*heightFog*FogShape.x,0,1));
                }
                fragColor=vec4(c,source.a);return;
            }
            if(Pass==3){
                vec4 source=texture(Base,uv);vec3 c=source.rgb+texture(Image,uv).rgb*BloomColor*Intensity;
                if(FlareShape.z>0){
                    float radius=length((uv-FlareShape.xy)*vec2(Aspect,1))/FlareShape.z;
                    vec4 f=mix(FlareColor0,FlareColor1,clamp((radius-FlareStops.x)/max(.00001,FlareStops.y-FlareStops.x),0,1));
                    vec3 fc=f.rgb,result=fc;
                    if(FlareShape.w>.5&&FlareShape.w<1.5)result=mix(2*c*fc,1-2*(1-c)*(1-fc),step(vec3(.5),c));
                    else if(FlareShape.w>1.5&&FlareShape.w<2.5)result=1-(1-c)*(1-fc);
                    else if(FlareShape.w>2.5)result=c*fc;
                    c=mix(c,result,clamp(f.a,0,1));
                }
                fragColor=vec4(encodedColor(c),source.a);return;
            }
            vec2 d=Texel*(Pass==2?SampleScale:.5);
            vec3 c=(sampleColor(uv+vec2(-d.x,-d.y))+sampleColor(uv+vec2(d.x,-d.y))+sampleColor(uv+vec2(-d.x,d.y))+sampleColor(uv+vec2(d.x,d.y)))*.25;
            if(Pass==0){float brightness=max(c.r,max(c.g,c.b));float soft=clamp(brightness-Threshold.y,0.0,Threshold.z);soft=soft*soft*Threshold.w;c*=max(soft,brightness-Threshold.x)/max(brightness,1e-5);}
            if(Pass==2)c+=texture(Base,uv).rgb;
            fragColor=vec4(c,1);
        }
        """;
    private static int program,
            vao,
            source,
            sourceFbo,
            linearScene,
            linearFbo,
            width,
            height,
            levels;
    private static final int[] down = new int[10],
            up = new int[10],
            df = new int[10],
            uf = new int[10],
            ws = new int[10],
            hs = new int[10];
    public static int frames;
    public static boolean enabled = true;

    private static int shader(int type, String text) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, text);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException(GL20.glGetShaderInfoLog(s));
        }
        return s;
    }

    private static int location(String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    private static void allocate(int texture, int fbo, int w, int h, boolean hdr) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                hdr ? GL30.GL_RGBA16F : GL11.GL_RGBA8,
                w,
                h,
                0,
                GL11.GL_RGBA,
                hdr ? GL11.GL_FLOAT : GL11.GL_UNSIGNED_BYTE,
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
            throw new IllegalStateException("Live Arena bloom framebuffer incomplete");
        }
    }

    private static float sampleScale;

    private static void init(int w, int h, LiveArenaEffects.Bloom b) {
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
            source = GL11.glGenTextures();
            sourceFbo = GL30.glGenFramebuffers();
            linearScene = GL11.glGenTextures();
            linearFbo = GL30.glGenFramebuffers();
            for (int i = 0; i < 10; i++) {
                down[i] = GL11.glGenTextures();
                up[i] = GL11.glGenTextures();
                df[i] = GL30.glGenFramebuffers();
                uf[i] = GL30.glGenFramebuffers();
            }
        }
        if (width == w && height == h) {
            return;
        }
        width = w;
        height = h;
        allocate(source, sourceFbo, w, h, false);
        allocate(linearScene, linearFbo, w, h, true);
        int tw = Math.max(1, (int) (w * b.resolutionScale())),
                th = Math.max(1, (int) (h * b.resolutionScale()));
        double logs = Math.log(Math.max(tw, th)) / Math.log(2) + Math.min(b.diffusion(), 10) - 10;
        levels = Math.max(1, Math.min(10, (int) Math.floor(logs)));
        sampleScale = (float) (.5 + logs - Math.floor(logs));
        for (int i = 0; i < levels; i++) {
            ws[i] = tw;
            hs[i] = th;
            allocate(down[i], df[i], tw, th, true);
            allocate(up[i], uf[i], tw, th, true);
            tw = Math.max(1, tw / 2);
            th = Math.max(1, th / 2);
        }
    }

    private static void bind(int unit, int texture) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    private static void pass(
            int pass, int image, int base, int fbo, int w, int h, int inputW, int inputH) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, w, h);
        bind(0, image);
        bind(1, base);
        GL20.glUniform1i(location("Pass"), pass);
        GL20.glUniform2f(location("Texel"), 1f / inputW, 1f / inputH);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    private static float linear(float v) {
        return v <= .04045f ? v / 12.92f : (float) Math.pow((v + .055f) / 1.055f, 2.4);
    }

    public static boolean render() {
        if (!enabled
                || !PvPlaybackController.active()
                || !PvPlaybackController.nativeCharacterLighting()
                || !PvVisibility.visible("post")
                || !LiveArenaEffects.installed(PvPlaybackController.scene)) {
            return false;
        }
        LiveArenaEffects.sample(PvPlaybackController.timeMs());
        var p = LiveArenaEffects.post;
        var b = LiveArenaEffects.bloom();
        var mc = Minecraft.getInstance();
        var target = mc.getMainRenderTarget();
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                oldRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                oldDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
                oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int t0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int t1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        int t2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                srgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            init(target.width, target.height, b);
            GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDepthMask(false);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, target.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sourceFbo);
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
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL20.glUniform1i(location("Image"), 0);
            GL20.glUniform1i(location("Base"), 1);
            var projection = RenderSystem.getProjectionMatrix();
            GL20.glUniform2f(location("DepthProjection"), projection.m22(), projection.m32());
            GL20.glUniform2f(location("ProjectionScale"), projection.m00(), projection.m11());
            var camera = mc.gameRenderer.getMainCamera();
            var rotation = new org.joml.Matrix3f().rotation(camera.rotation());
            GL20.glUniform3f(location("CameraUp"), rotation.m01(), rotation.m11(), rotation.m21());
            GL20.glUniform1f(
                    location("CameraHeight"),
                    (float) (camera.getPosition().y - PvPlaybackController.origin().y));
            GL20.glUniform4f(location("FogStart"), p[15], p[16], p[17], p[18]);
            GL20.glUniform4f(location("FogEnd"), p[19], p[20], p[21], p[22]);
            GL20.glUniform4f(location("FogRange"), p[24], p[25], p[26], p[27]);
            GL20.glUniform4f(location("FogShape"), p[23], p[28], 0, 0);
            GL20.glUniform4f(location("FlareShape"), p[3], p[4], p[1], p[2]);
            GL20.glUniform2f(location("FlareStops"), p[5], p[10]);
            GL20.glUniform4f(location("FlareColor0"), p[6], p[7], p[8], p[9]);
            GL20.glUniform4f(location("FlareColor1"), p[11], p[12], p[13], p[14]);
            GL20.glUniform1f(location("Aspect"), width / (float) height);
            bind(2, target.getDepthTextureId());
            GL20.glUniform1i(location("Depth"), 2);
            pass(4, source, 0, linearFbo, width, height, width, height);
            bind(2, 0);
            float threshold = linear(b.threshold()), knee = threshold * b.softKnee() + 1e-5f;
            GL20.glUniform4f(
                    location("Threshold"), threshold, threshold - knee, knee * 2, .25f / knee);
            GL20.glUniform1f(location("Intensity"), (float) Math.pow(2, b.intensity() / 10) - 1);
            GL20.glUniform1f(location("SampleScale"), sampleScale);
            GL20.glUniform3f(
                    location("BloomColor"),
                    linear(b.color()[0]),
                    linear(b.color()[1]),
                    linear(b.color()[2]));
            for (int i = 0; i < levels; i++) {
                pass(
                        i == 0 ? 0 : 1,
                        i == 0 ? linearScene : down[i - 1],
                        0,
                        df[i],
                        ws[i],
                        hs[i],
                        i == 0 ? width : ws[i - 1],
                        i == 0 ? height : hs[i - 1]);
            }
            int last = down[levels - 1];
            for (int i = levels - 2; i >= 0; i--) {
                pass(2, last, down[i], uf[i], ws[i], hs[i], ws[i + 1], hs[i + 1]);
                last = up[i];
            }
            pass(3, last, linearScene, target.frameBufferId, width, height, ws[0], hs[0]);
            frames++;
            return true;
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, oldRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, oldDraw);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL30.glBindVertexArray(oldVao);
            GL20.glUseProgram(oldProgram);
            bind(0, t0);
            bind(1, t1);
            bind(2, t2);
            GL13.glActiveTexture(oldActive);
            state(GL11.GL_DEPTH_TEST, depth);
            state(GL11.GL_BLEND, blend);
            state(GL11.GL_CULL_FACE, cull);
            state(GL30.GL_FRAMEBUFFER_SRGB, srgb);
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
        if (source != 0) {
            GL11.glDeleteTextures(source);
        }
        if (sourceFbo != 0) {
            GL30.glDeleteFramebuffers(sourceFbo);
        }
        if (linearScene != 0) {
            GL11.glDeleteTextures(linearScene);
        }
        if (linearFbo != 0) {
            GL30.glDeleteFramebuffers(linearFbo);
        }
        for (int i = 0; i < 10; i++) {
            if (down[i] != 0) {
                GL11.glDeleteTextures(down[i]);
            }
            if (up[i] != 0) {
                GL11.glDeleteTextures(up[i]);
            }
            if (df[i] != 0) {
                GL30.glDeleteFramebuffers(df[i]);
            }
            if (uf[i] != 0) {
                GL30.glDeleteFramebuffers(uf[i]);
            }
            down[i] = up[i] = df[i] = uf[i] = 0;
        }
        program =
                vao =
                        source =
                                sourceFbo =
                                        linearScene =
                                                linearFbo = width = height = levels = frames = 0;
    }
}
