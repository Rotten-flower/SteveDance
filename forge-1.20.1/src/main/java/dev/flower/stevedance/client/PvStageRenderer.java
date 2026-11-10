package dev.flower.stevedance.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.WorldStage;
import dev.flower.stevedance.core.PropBinding;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 舞台方块渲染器内的索引GPU蒙皮。源顶点仅上传一次。
 * <p>Indexed GPU skinning inside the stage block renderer. Source vertices upload only once.</p>
 */
public final class PvStageRenderer {
    public static boolean enabled = true, propsEnabled = true;

    static int textureId(int index) {
        return textureIds == null ? 0 : textureIds[index];
    }

    private static final String VERTEX =
            """
        #version 150
        in vec3 Position;
        in vec2 UV;
        in vec4 Color;
        in vec4 Joints;
        in vec4 Weights;
        in vec3 NextPosition;
        in vec3 Normal;
        in vec2 UV1;
        in vec4 Tangent;
        uniform mat4 Projection;
        uniform mat4 ModelView;
        uniform mat4 TextureTransform;
        uniform mat4 Bones[128];
        uniform int Skinned;
        uniform float Morph;
        out vec2 texCoord;
        out vec4 vertexColor;
        out vec3 viewNormal;out vec3 viewPosition;
        out vec2 sourceUv0;out vec2 sourceUv1;out vec3 viewTangent;
        void main(){
            vec4 p=vec4(mix(Position,NextPosition,Morph),1.0);vec3 n=Normal;
            if(Skinned!=0){
                mat4 skin=(Bones[int(Joints.x)]*Weights.x+Bones[int(Joints.y)]*Weights.y+
                   Bones[int(Joints.z)]*Weights.z+Bones[int(Joints.w)]*Weights.w);p=skin*p;n=mat3(skin)*n;
            }
            gl_Position=Projection*ModelView*p;
            texCoord=(TextureTransform*vec4(UV,0,1)).xy;vertexColor=Color;viewNormal=transpose(inverse(mat3(ModelView)))*n;viewPosition=(ModelView*p).xyz;
            sourceUv0=UV;sourceUv1=UV1;viewTangent=mat3(ModelView)*Tangent.xyz*Tangent.w;
        }
        """;
    private static final String FRAGMENT =
            """
        #version 150
        uniform sampler2D Image;
        uniform sampler2D SpecularImage,NormalImage,TransparencyImage,SecondaryImage;
        uniform int SecondaryMode,SecondaryUv;uniform float SecondaryWeight;uniform mat4 SecondaryTransform;
        uniform samplerCube EnvironmentImage;
        uniform int TextureSlots;uniform vec3 TextureWeights;uniform ivec3 TextureUvs;uniform mat4 AuxiliaryTransforms[3];uniform mat3 ViewToSource;
        uniform int Hdr,ToneMapOutput;uniform vec3 LightSpecular,MaterialAmbient;
        uniform vec4 Diffuse;
        uniform float AlphaCutoff;
        uniform int IgnoreAlpha;
        uniform int Lighting;uniform vec3 LightAmbient;uniform vec3 LightDiffuse;uniform vec3 LightDirection;uniform vec3 Specular;uniform vec3 Emission;uniform float Shininess;
        uniform float ToneExposure;uniform float ToneGamma;
        in vec2 texCoord;
        in vec4 vertexColor;
        in vec3 viewNormal;in vec3 viewPosition;
        in vec2 sourceUv0;in vec2 sourceUv1;in vec3 viewTangent;
        out vec4 fragColor;
        vec2 auxUv(int i){vec2 p=TextureUvs[i]==0?sourceUv0:sourceUv1;return (AuxiliaryTransforms[i]*vec4(p,0,1)).xy;}
        void main(){
            vec4 t=texture(Image,texCoord);
            if(SecondaryMode!=0){
                vec2 p=SecondaryUv==0?sourceUv0:sourceUv1;
                vec4 secondary=texture(SecondaryImage,(SecondaryTransform*vec4(p,0,1)).xy);
                if(SecondaryMode==16){
                    // PSV stage_blinn color variant 5: COLOR0^2 * COLOR1^2 /
                    // (COLOR1.a + 0.004). The light-map alpha encodes range;
                    // it is neither an overlay weight nor the leaf silhouette.
                    vec3 illumination=secondary.rgb*secondary.rgb/(secondary.a+0.004);
                    t.rgb=t.rgb*t.rgb*mix(vec3(1),illumination,SecondaryWeight);
                }else if(SecondaryMode==6)t.rgb*=mix(vec3(1),secondary.rgb,SecondaryWeight);
                else if(SecondaryMode==4)t.rgb+=secondary.rgb*SecondaryWeight;
                else t.rgb=mix(t.rgb,secondary.rgb,secondary.a*SecondaryWeight);
            }
            // Secondary color/light maps do not replace the base leaf silhouette.
            // Alpha blending has its own source sampler field; mixing entire RGBA
            // made opaque light maps fill every transparent tree card with white.

            if(IgnoreAlpha!=0)t.a=1.0;
            vec4 c=t*vertexColor*Diffuse;
            if((TextureSlots&4)!=0)c.a*=texture(TransparencyImage,auxUv(2)).r;
            if(c.a<AlphaCutoff)discard;
            if(Lighting==2)c.rgb*=Emission;
            else if(Lighting==1){
                vec3 n=normalize(viewNormal);if(!gl_FrontFacing)n=-n;
                if((TextureSlots&2)!=0){vec2 p=auxUv(1);vec3 q1=dFdx(viewPosition),q2=dFdy(viewPosition);vec2 s1=dFdx(p),s2=dFdy(p);vec3 tangent=length(viewTangent)>.01?normalize(viewTangent):normalize(q1*s2.y-q2*s1.y);vec3 bitangent=normalize(cross(n,tangent));n=normalize(mat3(tangent,bitangent,n)*(texture(NormalImage,p).xyz*2-1));}
                vec3 l=normalize(LightDirection);float nd=max(0.0,dot(n,l));vec3 h=normalize(l-normalize(viewPosition));vec3 spec=Specular;if((TextureSlots&1)!=0)spec*=texture(SpecularImage,auxUv(0)).rgb*TextureWeights.x;
                c.rgb=c.rgb*(LightAmbient*(Hdr!=0?MaterialAmbient:vec3(1))+LightDiffuse*nd+Emission)+spec*(Hdr!=0?LightSpecular:vec3(1))*pow(max(0.0,dot(n,h)),max(1.0,Shininess));
            }
            // Baked illumination disables direct Lambert lighting, not the
            // independently declared source specular/environment material slots.
            if(Lighting!=0&&(TextureSlots&8)!=0){
                vec3 n=normalize(viewNormal);if(!gl_FrontFacing)n=-n;
                vec3 spec=Specular;if((TextureSlots&1)!=0)spec*=texture(SpecularImage,auxUv(0)).rgb*TextureWeights.x;
                c.rgb+=texture(EnvironmentImage,ViewToSource*reflect(normalize(viewPosition),n)).rgb*spec*TextureWeights.z;
            }
            // Source HDR intensities must be compressed before Minecraft's RGBA8 target clips them.
            // Analytic form of ReDIVA's 1-exp(-radiance) tone lookup and gamma*1.5 power.
            if(Lighting!=0&&ToneMapOutput!=0)c.rgb=pow(vec3(1)-exp(-max(c.rgb,vec3(0))*max(ToneExposure,0.0)),vec3(max(.01,ToneGamma*1.5)));
            fragColor=c;
        }
        """;
    private static int lightingLocation,
            ambientLocation,
            lightDiffuseLocation,
            directionLocation,
            specularLocation,
            emissionLocation,
            shininessLocation;
    private static int toneExposureLocation, toneGammaLocation;
    private static int secondaryImageLocation,
            secondaryModeLocation,
            secondaryUvLocation,
            secondaryWeightLocation,
            secondaryTransformLocation;
    private static final Map<Integer, Integer> cubeTextures = new HashMap<>();
    private static final Matrix4f SLOT_MATRIX = new Matrix4f();
    private static final org.joml.Matrix3f VIEW_TO_SOURCE = new org.joml.Matrix3f();
    private static final float[] DEFAULT_AMBIENT = {1, 1, 1};
    private static final int[] UV_SETS = new int[3];
    private static final FloatBuffer SLOT_MATRICES = MemoryUtil.memAllocFloat(48),
            MAT3 = MemoryUtil.memAllocFloat(9);
    private static int hdrLocation,
            slotsLocation,
            weightsLocation,
            uvsLocation,
            auxTransformsLocation,
            viewToSourceLocation,
            lightSpecularLocation,
            materialAmbientLocation;
    private static final org.joml.Vector3f LIGHT = new org.joml.Vector3f();
    private static int program,
            projectionLocation,
            modelLocation,
            bonesLocation,
            skinnedLocation,
            morphLocation,
            diffuseLocation,
            alphaLocation,
            ignoreAlphaLocation,
            imageLocation,
            textureTransformLocation;
    private static final List<ResourceLocation> textures = new ArrayList<>();
    private static int[] textureIds;
    private static ResourceLocation white;
    private static PvSceneData installed;
    private static final FloatBuffer MATRIX = MemoryUtil.memAllocFloat(16);
    private static final FloatBuffer PROP_BONES = MemoryUtil.memAllocFloat(128 * 16);
    private static final Matrix4f model = new Matrix4f();
    public static int renderedFrames, drawCalls, visibleInstances;

    private static final class Draw {
        final Matrix4f transform = new Matrix4f(), uv = new Matrix4f();
        PvSceneData.Batch batch;
        PvSceneData.TexturePattern[] patterns;
        FloatBuffer bones;
        float morph, depth;
    }

    private static final List<Draw> pool = new ArrayList<>(),
            opaque = new ArrayList<>(),
            transparent = new ArrayList<>();
    private static int used;

    private static int shader(int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            String error = GL20.glGetShaderInfoLog(s);
            GL20.glDeleteShader(s);
            throw new IllegalStateException(error);
        }
        return s;
    }

    // 中文：天空壳可与粒子和文本共享效果图。过滤节点，而非图。
    // EN: Sky shells can share an effect graph with particles and text. Filter the node, not the
    // graph.
    // 天空壳可能与粒子、文字共用动画图，仅按独立几何节点隐藏。
    private static boolean backdropMesh(String mesh) {
        if (mesh == null) {
            return false;
        }
        for (String token : mesh.toUpperCase(java.util.Locale.ROOT).split("_")) {
            if (token.equals("SKY")
                    || token.equals("SKYDOME")
                    || token.equals("SKYBOX")
                    || token.equals("DOME")) {
                return true;
            }
        }
        return false;
    }

    public static void install(PvSceneData scene) throws IOException {
        clear();
        PvCharacterRenderer.clear();
        installed = scene;
        dev.flower.stevedance.client.LiveArenaEffects.install(scene);
        int oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                oldArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            int vs = shader(GL20.GL_VERTEX_SHADER, VERTEX),
                    fs = shader(GL20.GL_FRAGMENT_SHADER, FRAGMENT);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            String[] attributes = {
                "Position",
                "UV",
                "Color",
                "Joints",
                "Weights",
                "NextPosition",
                "Normal",
                "UV1",
                "Tangent"
            };
            for (int i = 0; i < attributes.length; i++) {
                GL20.glBindAttribLocation(program, i, attributes[i]);
            }
            GL20.glLinkProgram(program);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IOException(GL20.glGetProgramInfoLog(program));
            }
            projectionLocation = GL20.glGetUniformLocation(program, "Projection");
            modelLocation = GL20.glGetUniformLocation(program, "ModelView");
            bonesLocation = GL20.glGetUniformLocation(program, "Bones[0]");
            skinnedLocation = GL20.glGetUniformLocation(program, "Skinned");
            morphLocation = GL20.glGetUniformLocation(program, "Morph");
            diffuseLocation = GL20.glGetUniformLocation(program, "Diffuse");
            alphaLocation = GL20.glGetUniformLocation(program, "AlphaCutoff");
            ignoreAlphaLocation = GL20.glGetUniformLocation(program, "IgnoreAlpha");
            imageLocation = GL20.glGetUniformLocation(program, "Image");
            hdrLocation = location("Hdr");
            slotsLocation = location("TextureSlots");
            weightsLocation = location("TextureWeights");
            uvsLocation = location("TextureUvs");
            auxTransformsLocation = location("AuxiliaryTransforms[0]");
            viewToSourceLocation = location("ViewToSource");
            lightSpecularLocation = location("LightSpecular");
            materialAmbientLocation = location("MaterialAmbient");
            secondaryImageLocation = location("SecondaryImage");
            secondaryModeLocation = location("SecondaryMode");
            secondaryUvLocation = location("SecondaryUv");
            secondaryWeightLocation = location("SecondaryWeight");
            secondaryTransformLocation = location("SecondaryTransform");
            textureTransformLocation = GL20.glGetUniformLocation(program, "TextureTransform");
            toneExposureLocation = GL20.glGetUniformLocation(program, "ToneExposure");
            toneGammaLocation = GL20.glGetUniformLocation(program, "ToneGamma");
            lightingLocation = GL20.glGetUniformLocation(program, "Lighting");
            ambientLocation = GL20.glGetUniformLocation(program, "LightAmbient");
            lightDiffuseLocation = GL20.glGetUniformLocation(program, "LightDiffuse");
            directionLocation = GL20.glGetUniformLocation(program, "LightDirection");
            specularLocation = GL20.glGetUniformLocation(program, "Specular");
            emissionLocation = GL20.glGetUniformLocation(program, "Emission");
            shininessLocation = GL20.glGetUniformLocation(program, "Shininess");
            var manager = Minecraft.getInstance().getTextureManager();
            textureIds = new int[scene.images.length];
            for (int i = 0; i < textureIds.length; i++) {
                NativeImage image = AssetImage.read(new ByteArrayInputStream(scene.images[i]));
                if (image.getWidth() > 4096 || image.getHeight() > 4096) {
                    image.close();
                    throw new IOException("Texture dimension exceeds budget");
                }
                DynamicTexture t = new DynamicTexture(image);
                t.setFilter(true, false);
                ResourceLocation id = manager.register("stevedance_f2nd_" + i, t);
                textures.add(id);
                textureIds[i] = t.getId();
            }
            NativeImage pixel = new NativeImage(1, 1, false);
            pixel.setPixelRGBA(0, 0, 0xffffffff);
            white = manager.register("stevedance_f2nd_white", new DynamicTexture(pixel));
            textures.add(white);
            int[] sizes = {3, 2, 4, 4, 4, 3, 3, 2, 4},
                    offsets = {0, 12, 20, 36, 52, 68, 80, 92, 100};
            for (var g : scene.geometry) {
                for (var b : g.batches) {
                    b.vao = GL30.glGenVertexArrays();
                    GL30.glBindVertexArray(b.vao);
                    b.vbo = GL15.glGenBuffers();
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, b.vbo);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, b.vertices, GL15.GL_STATIC_DRAW);
                    b.ebo = GL15.glGenBuffers();
                    GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, b.ebo);
                    GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, b.indices, GL15.GL_STATIC_DRAW);
                    for (int i = 0; i < (b.stride == 116 ? 9 : b.stride >= 92 ? 7 : 6); i++) {
                        GL20.glEnableVertexAttribArray(i);
                        GL20.glVertexAttribPointer(
                                i, sizes[i], GL11.GL_FLOAT, false, b.stride, offsets[i]);
                    }
                }
            }
            int savedProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            GL20.glUseProgram(program);
            GL20.glUniform1i(imageLocation, 0);
            GL20.glUniform1i(secondaryImageLocation, 5);
            GL20.glUniform1i(location("SpecularImage"), 1);
            GL20.glUniform1i(location("NormalImage"), 2);
            GL20.glUniform1i(location("TransparencyImage"), 3);
            GL20.glUniform1i(location("EnvironmentImage"), 4);
            GL20.glUseProgram(savedProgram);
            uploadCubes(scene);
            PvAetTimeline.install(scene.overlay);
            PvLyrics.install(scene.subtitles);
            PvMovieBackground.install(scene.movie);
            scene.releaseCpu();
        } catch (IOException | RuntimeException ex) {
            clear();
            throw ex;
        } finally {
            GL30.glBindVertexArray(oldVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArray);
        }
    }

    private static void matrix(int location, Matrix4f value) {
        MATRIX.clear();
        value.get(MATRIX);
        GL20.glUniformMatrix4fv(location, false, MATRIX);
    }

    public static void render(WorldStage.Anchor stage, PoseStack pose) {
        PvPlaybackController.ensure();
        PvSceneData scene = installed;
        if (!enabled || scene == null || !scene.description.id().equals(stage.song())) {
            return;
        }
        dev.flower.stevedance.client.LiveArenaEffects.sample(PvPlaybackController.timeMs());
        dev.flower.stevedance.client.LiveArenaDynamics.sample(PvPlaybackController.timeMs());
        dev.flower.stevedance.client.LiveArenaWorldEffects.captureScreen(pose.last().pose());
        pose.pushPose();
        pose.translate(.5, .125, .5);
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-stage.yaw()));
        pose.scale(-1, 1, -1);
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        AuxBindings auxiliary = captureAux();
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int oldFront = GL11.glGetInteger(GL11.GL_FRONT_FACE),
                oldCull = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        try {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glFrontFace(GL11.GL_CCW);
            GL11.glCullFace(GL11.GL_BACK);
            GL20.glUseProgram(program);
            GL20.glUniform1i(imageLocation, 0);
            GL20.glUniform1i(secondaryImageLocation, 5);
            GL20.glUniform1i(location("SpecularImage"), 1);
            GL20.glUniform1i(location("NormalImage"), 2);
            GL20.glUniform1i(location("TransparencyImage"), 3);
            GL20.glUniform1i(location("EnvironmentImage"), 4);
            matrix(projectionLocation, RenderSystem.getProjectionMatrix());
            VIEW_TO_SOURCE.set(pose.last().pose()).invert();
            MAT3.clear();
            VIEW_TO_SOURCE.get(MAT3);
            GL20.glUniformMatrix3fv(viewToSourceLocation, false, MAT3);
            drawCalls = 0;
            visibleInstances = 0;
            used = 0;
            opaque.clear();
            transparent.clear();
            double ms = PvPlaybackController.sceneTime(stage);
            if (scene.lighting != null) {
                scene.lighting.sample(ms);
                var p = scene.lighting.position;
                LIGHT.set(p[0], p[1], p[2]);
                pose.last().pose().transformDirection(LIGHT);
                GL20.glUniform3f(directionLocation, LIGHT.x, LIGHT.y, LIGHT.z);
            }
            // 中文：原生舞台及其原本黑色的背景都位于方块原点。
            // EN: Both the native stage and its otherwise black background live at the block
            // origin.
            var backdrop = scene.byName.get("$world_backdrop");
            if (PvPlaybackController.sceneryActive()
                    && PvVisibility.visible("backdrop")
                    && backdrop != null
                    && PvPlaybackController.isolatedPlayback()
                    && !PvAetTimeline.hasBack(ms)
                    && !PvMovieBackground.active(ms)) {
                enqueue(backdrop, pose.last().pose(), null, 0);
            }
            if (PvPlaybackController.sceneryActive()) {
                for (String mesh :
                        scene.description.stages().getOrDefault(scene.stage(ms), List.of())) {
                    var ground = scene.byName.get(mesh);
                    if (PvVisibility.mesh(mesh) && ground != null) {
                        enqueue(ground, pose.last().pose(), null, 0);
                    }
                }
            }
            for (String name : scene.active(ms)) {
                if (!PvVisibility.graph(name)) {
                    continue;
                }
                if (!PvPlaybackController.sceneryActive()
                        && scene.description.stageGraphs() != null
                        && scene.description
                                .stageGraphs()
                                .getOrDefault(scene.stage(ms), List.of())
                                .contains(name)) {
                    continue;
                }
                var graph = scene.description.graphs().get(name);
                if (graph == null) {
                    continue;
                }
                double frame = scene.graphFrame(name, ms);
                scene.evaluate(graph, frame);
                for (var node : graph.nodes) {
                    if (PvVisibility.node(name, node.name)
                            && node.visible
                            && node.geometry != null
                            && (PvPlaybackController.sceneryActive() || !backdropMesh(node.mesh))) {
                        PvSceneData.Geometry geometry = node.geometry;
                        float morph = 0;
                        if (node.morph != null
                                && node.variants != null
                                && node.variants.length > 0) {
                            double morphFrame = frame + node.morphOffset;
                            if (node.morphOffset != 0 && morphFrame >= node.morph.maximumFrame) {
                                morphFrame -= node.morph.maximumFrame;
                            }
                            float v = Math.max(0, node.morph.sample(morphFrame));
                            int index = (int) v;
                            if (index < node.variants.length) {
                                geometry = node.variants[index];
                                morph = index == node.variants.length - 1 ? 0 : v - index;
                            }
                        }
                        model.set(pose.last().pose()).mul(node.matrix);
                        dev.flower.stevedance.client.LiveArenaDynamics.apply(node.mesh, model);
                        enqueue(geometry, model, null, morph, node);
                    }
                }
                for (var rig : graph.rigs) {
                    if (PvVisibility.rig(name, rig.name)
                            && rig.geometry != null
                            && rig.visible
                            && !rig.replacementCharacter
                            && !SourcePropContacts.replaces(rig)) {
                        model.set(pose.last().pose()).mul(rig.model);
                        enqueue(rig.geometry, model, rig.palette, 0);
                    }
                }
            }
            if (scene.description.itemTracks() != null) {
                for (var item : scene.description.itemTracks()) {
                    if (!PvVisibility.graph(item.graph())) {
                        continue;
                    }
                    double frame = scene.itemFrame(item, ms);
                    if (frame < 0) {
                        continue;
                    }
                    var graph = scene.description.graphs().get(item.graph());
                    scene.evaluate(graph, frame);
                    for (var node : graph.nodes) {
                        if (PvVisibility.node(item.graph(), node.name)
                                && node.geometry != null
                                && node.visible
                                && !SourcePropContacts.replaces(item.graph(), node)) {
                            model.set(pose.last().pose()).mul(node.matrix);
                            enqueue(node.geometry, model, null, 0, node);
                        }
                    }
                    for (var rig : graph.rigs) {
                        if (PvVisibility.rig(item.graph(), rig.name)
                                && rig.geometry != null
                                && rig.visible
                                && !SourcePropContacts.replaces(rig)
                                && !YsmPropAdapter.replaceItem(
                                        item.actor(), item.graph(), rig, ms)) {
                            model.set(pose.last().pose()).mul(rig.model);
                            enqueue(rig.geometry, model, rig.palette, 0);
                        }
                    }
                }
            }
            for (var draw : opaque) {
                draw(draw);
            }
            transparent.sort(Comparator.comparingDouble(d -> d.depth));
            for (var draw : transparent) {
                draw(draw);
            }
            dev.flower.stevedance.client.LiveArenaWorldEffects.render(pose.last().pose());
            renderedFrames++;
        } finally {
            GL30.glBindVertexArray(oldVao);
            GL20.glUseProgram(oldProgram);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            restoreAux(auxiliary);
            GL13.glActiveTexture(oldActive);
            GL11.glDepthMask(depthMask);
            GL11.glFrontFace(oldFront);
            GL11.glCullFace(oldCull);
            GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
            state(GL11.GL_CULL_FACE, cull);
            state(GL11.GL_BLEND, blend);
            state(GL11.GL_DEPTH_TEST, depth);
            pose.popPose();
        }
    }

    /**
     * 使用与所选YSM模型相同的最终渲染器基调用。
     * <p>Called with the same final renderer basis used by the selected YSM model.</p>
     */
    public static void renderProps(
            PoseStack pose,
            int actor,
            Map<String, PropBinding.Fit> fits,
            PropBinding.Fit guitar,
            PropBinding.Fit pick,
            PropBinding.Fit torso) {
        PvSceneData scene = installed;
        if (!propsEnabled || scene == null || program == 0) {
            return;
        }
        double ms = PvPlaybackController.timeMs();
        for (var contact : SourcePropContacts.forActor(actor)) {
            if (!PvVisibility.graph(contact.graph())) {
                continue;
            }
            var graph = scene.description.graphs().get(contact.graph());
            if (scene.lighting != null) {
                scene.lighting.sample(ms);
            }
            scene.evaluate(graph, SourcePropContacts.frame(scene, contact.graph(), ms));
            int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                    oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                    oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            AuxBindings auxiliary = captureAux();
            boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                    blend = GL11.glIsEnabled(GL11.GL_BLEND),
                    depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                    depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                    dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                    srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                    dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                    oldFront = GL11.glGetInteger(GL11.GL_FRONT_FACE),
                    oldCull = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
            try {
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glFrontFace(GL11.GL_CCW);
                GL11.glCullFace(GL11.GL_BACK);
                GL20.glUseProgram(program);
                GL20.glUniform1i(imageLocation, 0);
                matrix(projectionLocation, RenderSystem.getProjectionMatrix());
                var handFit = fits.get(contact.role());
                Matrix4f body = fitMatrix(contact.instrument() ? guitar : handFit),
                        picking = fitMatrix(fits.get(contact.role())),
                        shoulder = fitMatrix(torso),
                        joint = new Matrix4f();
                // 独立道具绘制不能依赖舞台上一次留下的 uniform。Props initialize their own material view.
                GL20.glUniform1i(secondaryImageLocation, 5);
                GL20.glUniform1i(location("SpecularImage"), 1);
                GL20.glUniform1i(location("NormalImage"), 2);
                GL20.glUniform1i(location("TransparencyImage"), 3);
                GL20.glUniform1i(location("EnvironmentImage"), 4);
                Matrix4f sourceView = new Matrix4f(pose.last().pose()).mul(body);
                VIEW_TO_SOURCE.set(sourceView).invert();
                MAT3.clear();
                VIEW_TO_SOURCE.get(MAT3);
                GL20.glUniformMatrix3fv(viewToSourceLocation, false, MAT3);
                if (scene.lighting != null) {
                    var lp = scene.lighting.position;
                    LIGHT.set(lp[0], lp[1], lp[2]);
                    sourceView.transformDirection(LIGHT);
                    GL20.glUniform3f(directionLocation, LIGHT.x, LIGHT.y, LIGHT.z);
                }
                Draw d = new Draw();
                d.morph = 0;
                d.transform.set(pose.last().pose());
                d.bones = PROP_BONES;
                for (var rig : graph.rigs) {
                    if (PvVisibility.rig(contact.graph(), rig.name)
                            && rig == contact.rig()
                            && rig.geometry != null
                            && rig.visible) {
                        boolean isPick = contact.pick();
                        PROP_BONES.clear();
                        if (isPick
                                && rig.geometry.bones.length == 1
                                && rig.geometry.batches.length > 0) {
                            // 拨片是独立薄三维网格。将实际中心放到掌前握面，保留源手腕旋转。
                            // 中文：将拾取本身居中于握持处，而不是保留外来的
                            // EN: Center the pick itself at the grip instead of preserving a
                            // foreign
                            // 中文：手指偏移。
                            // EN: finger offset.
                            var motion = PvPlaybackController.performerMotion(actor);
                            if (motion != null) {
                                var wrist = motion.sample(ms / 1000).get(contact.role()).position();
                                var anchor = fits.get(contact.role()).point(wrist);
                                Vector3f center = new Vector3f(rig.geometry.batches[0].center);
                                new Matrix4f(rig.model)
                                        .mul(new Matrix4f().set(rig.palette.position(0)))
                                        .transformPosition(center);
                                picking.transformPosition(center);
                                picking.m30(picking.m30() + (float) anchor.x() - center.x);
                                picking.m31(picking.m31() + (float) anchor.y() - center.y);
                                picking.m32(picking.m32() + (float) anchor.z() - center.z);
                            }
                        }
                        for (int i = 0; i < rig.geometry.bones.length; i++) {
                            String bone = rig.geometry.bones[i].name();
                            Matrix4f correction =
                                    isPick
                                            ? picking
                                            : contact.instrument() && bone.contains("strap")
                                                    ? shoulder
                                                    : body;
                            joint.set(correction)
                                    .mul(rig.model)
                                    .mul(new Matrix4f().set(rig.palette.position(i * 16)));
                            joint.get(PROP_BONES);
                            PROP_BONES.position(PROP_BONES.position() + 16);
                        }
                        rig.palette.rewind();
                        PROP_BONES.flip();
                        for (var batch : rig.geometry.batches) {
                            d.batch = batch;
                            d.uv.identity();
                            if (batch.sourceMaterial != null) {
                                for (var slot : batch.sourceMaterial.textures) {
                                    if (slot.type.equals("Color")) {
                                        d.uv.set(slot.matrix);
                                        break;
                                    }
                                }
                            }
                            draw(d);
                        }
                    }
                }
                // 刀光保留原作的世界动画，再与刀身一起映射到当前演员的掌心。
                // 中文：保留已创作的特效运动；应用武器的同一手部校正。
                // EN: Preserve authored effect motion; apply the weapon's same hand correction.
                for (var node : graph.nodes) {
                    if (contact.rig().name.equals(node.propRig)
                            && node.geometry != null
                            && node.visible
                            && PvVisibility.node(contact.graph(), node.name)) {
                        d.bones = null;
                        d.patterns = node.texturePatterns;
                        d.transform.set(pose.last().pose()).mul(body).mul(node.matrix);
                        for (var batch : node.geometry.batches) {
                            d.batch = batch;
                            d.uv.identity();
                            if (node.textureTransforms != null) {
                                for (var t : node.textureTransforms) {
                                    if (t.texture == batch.texture) {
                                        d.uv.set(t.matrix);
                                    }
                                }
                            }
                            if (batch.sourceMaterial != null) {
                                for (var slot : batch.sourceMaterial.textures) {
                                    if (slot.type.equals("Color")) {
                                        d.uv.mul(SLOT_MATRIX.set(slot.matrix));
                                        break;
                                    }
                                }
                            }
                            draw(d);
                        }
                    }
                }
            } finally {
                GL30.glBindVertexArray(oldVao);
                GL20.glUseProgram(oldProgram);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
                restoreAux(auxiliary);
                GL13.glActiveTexture(oldActive);
                GL11.glDepthMask(depthMask);
                GL11.glFrontFace(oldFront);
                GL11.glCullFace(oldCull);
                GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
                state(GL11.GL_CULL_FACE, cull);
                state(GL11.GL_BLEND, blend);
                state(GL11.GL_DEPTH_TEST, depth);
            }
        }
    }

    /**
     * 角色特定的姿势插槽；独立/驻留的区间保留已创作的世界轨道。
     * <p>Actor-specific posed sockets; independent/parked intervals retain authored world tracks.</p>
     */
    public static void renderItemProps(
            PoseStack pose, int actor, Map<String, PropBinding.Fit> fits) {
        PvSceneData scene = installed;
        if (!propsEnabled
                || scene == null
                || program == 0
                || scene.itemBindings == null
                || scene.itemBindings.isEmpty()) {
            return;
        }
        double ms = PvPlaybackController.timeMs();
        if (scene.lighting != null) {
            scene.lighting.sample(ms);
        }
        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
                oldVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING),
                oldActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        AuxBindings auxiliary = captureAux();
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                mask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int sr = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),
                dr = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                sa = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),
                da = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                front = GL11.glGetInteger(GL11.GL_FRONT_FACE),
                face = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        try {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glFrontFace(GL11.GL_CCW);
            GL11.glCullFace(GL11.GL_BACK);
            GL20.glUseProgram(program);
            GL20.glUniform1i(imageLocation, 0);
            GL20.glUniform1i(secondaryImageLocation, 5);
            GL20.glUniform1i(location("SpecularImage"), 1);
            GL20.glUniform1i(location("NormalImage"), 2);
            GL20.glUniform1i(location("TransparencyImage"), 3);
            GL20.glUniform1i(location("EnvironmentImage"), 4);
            matrix(projectionLocation, RenderSystem.getProjectionMatrix());
            VIEW_TO_SOURCE.set(pose.last().pose()).invert();
            MAT3.clear();
            VIEW_TO_SOURCE.get(MAT3);
            GL20.glUniformMatrix3fv(viewToSourceLocation, false, MAT3);
            var lp = scene.lighting.position;
            LIGHT.set(-lp[0], lp[1], -lp[2]);
            pose.last().pose().transformDirection(LIGHT);
            GL20.glUniform3f(directionLocation, LIGHT.x, LIGHT.y, LIGHT.z);
            for (var item : scene.description.itemTracks()) {
                if (item.actor() == actor && PvVisibility.graph(item.graph())) {
                    double frame = scene.itemFrame(item, ms);
                    if (frame < 0) {
                        continue;
                    }
                    var graph = scene.description.graphs().get(item.graph());
                    scene.evaluate(graph, frame);
                    fitPairs(scene, graph, actor, item.graph(), fits, ms);
                    for (var rig : graph.rigs) {
                        if (!PvVisibility.rig(item.graph(), rig.name)) {
                            continue;
                        }
                        var binding = YsmPropAdapter.itemBinding(actor, item.graph(), rig, ms);
                        if (binding == null || rig.geometry == null) {
                            continue;
                        }
                        var fit =
                                fits.getOrDefault(
                                        YsmPropAdapter.fitKey(binding), fits.get(binding.role()));
                        if (fit == null) {
                            continue;
                        }
                        Draw d = new Draw();
                        d.transform.set(pose.last().pose()).mul(fitMatrix(fit)).mul(rig.model);
                        d.bones = rig.palette;
                        boolean drawn = false;
                        for (var batch : rig.geometry.batches) {
                            if (batch.count == 0) {
                                continue;
                            }
                            d.batch = batch;
                            d.uv.identity();
                            if (batch.sourceMaterial != null) {
                                for (var slot : batch.sourceMaterial.textures) {
                                    if (slot.type.equals("Color")) {
                                        d.uv.set(slot.matrix);
                                        break;
                                    }
                                }
                            }
                            draw(d);
                            drawn = true;
                        }
                        // 中文：其他道具保留其世界回退，直到它们自身被绘制。
                        // EN: Other props retain their world fallback until they themselves draw.
                        if (drawn) {
                            YsmPropAdapter.itemRendered(binding, ms);
                        }
                    }
                }
            }
        } finally {
            GL30.glBindVertexArray(oldVao);
            GL20.glUseProgram(oldProgram);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
            restoreAux(auxiliary);
            GL13.glActiveTexture(oldActive);
            GL11.glDepthMask(mask);
            GL11.glFrontFace(front);
            GL11.glCullFace(face);
            GL14.glBlendFuncSeparate(sr, dr, sa, da);
            state(GL11.GL_CULL_FACE, cull);
            state(GL11.GL_BLEND, blend);
            state(GL11.GL_DEPTH_TEST, depth);
        }
    }

    private static void fitPairs(
            PvSceneData scene,
            PvSceneData.Graph graph,
            int actor,
            String graphName,
            Map<String, PropBinding.Fit> fits,
            double ms) {
        var body = PvPlaybackController.performerMotion(actor);
        if (body == null) {
            return;
        }
        var source = body.sample(ms / 1000);
        for (var binding : scene.itemBindings) {
            if (binding.actor() != actor
                    || !binding.graph().equals(graphName)
                    || binding.pairedWith() == null) {
                continue;
            }

            var bow =
                    Arrays.stream(graph.rigs)
                            .filter(r -> r.name.equals(binding.rig()))
                            .findFirst()
                            .orElseThrow();
            var violin =
                    Arrays.stream(graph.rigs)
                            .filter(r -> r.name.equals(binding.pairedWith()))
                            .findFirst()
                            .orElseThrow();
            if (YsmPropAdapter.itemBinding(actor, graphName, bow, ms) == null
                    || YsmPropAdapter.itemBinding(actor, graphName, violin, ms) == null) {
                continue;
            }
            var reference =
                    scene.itemBindings.stream()
                            .filter(
                                    b ->
                                            b.actor() == actor
                                                    && b.graph().equals(graphName)
                                                    && b.rig().equals(binding.pairedWith()))
                            .findFirst()
                            .orElseThrow();
            var handFit = fits.get(binding.role());
            var instrumentFit =
                    fits.getOrDefault(YsmPropAdapter.fitKey(reference), fits.get(reference.role()));
            // 中文：一个相似性保留了原始的乐器/弓相对运动。
            // EN: One similarity preserves the original instrument/bow relative motion.
            // 两件道具共享变换，不把未经核实的接触点强行拉到弓毛上。
            fits.put(YsmPropAdapter.fitKey(binding), instrumentFit);
            if (actor == 0) {
                var grip = source.get(binding.role()).position();
                YsmPropAdapter.pairedRotationDegrees = 0;
                YsmPropAdapter.bowGripError =
                        instrumentFit.point(grip).sub(handFit.point(grip)).length();
            }
        }
    }

    private static Matrix4f fitMatrix(PropBinding.Fit fit) {
        var p = fit.translation();
        var q = fit.rotation();
        return new Matrix4f()
                .translate((float) p.x(), (float) p.y(), (float) p.z())
                .rotate(new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()))
                .scale((float) fit.scale());
    }

    private static void state(int flag, boolean enabled) {
        if (enabled) {
            GL11.glEnable(flag);
        } else {
            GL11.glDisable(flag);
        }
    }

    private static void enqueue(
            PvSceneData.Geometry geo, Matrix4f transform, FloatBuffer bones, float morph) {
        enqueue(geo, transform, bones, morph, null);
    }

    private static boolean transparent(PvSceneData.Batch b) {
        return (b.flags & 2) == 0
                && ((b.flags & (1 | 16)) != 0
                        || b.sourceMaterial != null && b.sourceMaterial.additive());
    }

    private static void enqueue(
            PvSceneData.Geometry geo,
            Matrix4f transform,
            FloatBuffer bones,
            float morph,
            PvSceneData.Node node) {
        visibleInstances++;
        for (var b : geo.batches) {
            if (used >= 4096) {
                throw new IllegalStateException("Native draw budget exceeded");
            }
            if (used == pool.size()) {
                pool.add(new Draw());
            }
            Draw d = pool.get(used++);
            d.batch = b;
            d.patterns = node == null ? null : node.texturePatterns;
            d.transform.set(transform);
            d.uv.identity();
            if (node != null && node.textureTransforms != null) {
                for (var t : node.textureTransforms) {
                    if (t.texture == b.texture) {
                        d.uv.set(t.matrix);
                    }
                }
            }
            d.bones = bones;
            if (b.sourceMaterial != null) {
                for (var slot : b.sourceMaterial.textures) {
                    if (slot.type.equals("Color")) {
                        d.uv.mul(SLOT_MATRIX.set(slot.matrix));
                        break;
                    }
                }
            }
            d.morph = morph;
            d.depth =
                    transform.m02() * b.center.x
                            + transform.m12() * b.center.y
                            + transform.m22() * b.center.z
                            + transform.m32();
            (transparent(b) ? transparent : opaque).add(d);
        }
    }

    private static void draw(Draw d) {
        var b = d.batch;
        if (dev.flower.stevedance.client.LiveArenaWorldEffects.screen(b, d.transform)) {
            return;
        }
        if (b.texture >= 0 && patternTexture(d, b.texture) < 0) {
            return;
        }
        if (b.sourceMaterial != null) {
            for (var slot : b.sourceMaterial.textures) {
                if (patternTexture(d, slot.texture) < 0) {
                    return;
                }
            }
        }
        bindMaterial(d);
        matrix(modelLocation, d.transform);
        GL20.glUniform1i(skinnedLocation, d.bones == null ? 0 : 1);
        if (d.bones != null) {
            GL20.glUniformMatrix4fv(bonesLocation, false, d.bones);
        }
        GL20.glUniform1f(morphLocation, d.morph);
        matrix(textureTransformLocation, d.uv);
        var tone = installed.lighting;
        GL20.glUniform1f(toneExposureLocation, tone == null ? 2 : tone.toneExposure);
        GL20.glUniform1f(toneGammaLocation, tone == null ? 1 : tone.toneGamma);
        var light = installed.lighting;
        GL20.glUniform1i(
                lightingLocation,
                light != null && b.stride >= 92 ? ((b.flags & 32) != 0 ? 2 : 1) : 0);
        if (light != null) {
            var a = light.ambient;
            var l = light.diffuse;
            GL20.glUniform3f(ambientLocation, a[0], a[1], a[2]);
            GL20.glUniform3f(lightDiffuseLocation, l[0], l[1], l[2]);
        }
        GL20.glUniform3f(specularLocation, b.specular[0], b.specular[1], b.specular[2]);
        GL20.glUniform3f(emissionLocation, b.emission[0], b.emission[1], b.emission[2]);
        GL20.glUniform1f(shininessLocation, b.shininess);
        boolean alpha = transparent(b);
        state(GL11.GL_BLEND, alpha);
        GL11.glDepthMask(!alpha);
        state(GL11.GL_CULL_FACE, (b.flags & 4) == 0);
        // 保留 Unity 滤色与带源透明度的加法。Preserve source screen/alpha-additive factors.
        if ((b.flags & 64) != 0) {
            GL14.glBlendFuncSeparate(
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_COLOR,
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else if ((b.flags & 128) != 0) {
            GL14.glBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        } else if (b.sourceMaterial != null && b.sourceMaterial.additive()) {
            int source =
                    switch (b.sourceMaterial.sourceBlend) {
                        case "SrcColor" -> GL11.GL_SRC_COLOR;
                        case "SrcAlpha" -> GL11.GL_SRC_ALPHA;
                        default -> GL11.GL_ONE;
                    };
            GL14.glBlendFuncSeparate(source, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
        } else if (b.sourceMaterial != null
                && "One".equals(b.sourceMaterial.sourceBlend)
                && "InverseSrcAlpha".equals(b.sourceMaterial.destinationBlend)) {
            GL14.glBlendFuncSeparate(
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else if ((b.flags & 16) != 0) {
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
        } else {
            GL14.glBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        var manager = Minecraft.getInstance().getTextureManager();
        GL11.glBindTexture(
                GL11.GL_TEXTURE_2D,
                b.texture < 0
                        ? manager.getTexture(white).getId()
                        : textureIds[patternTexture(d, b.texture)]);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, wrap(b.repeatU));
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, wrap(b.repeatV));
        GL20.glUniform1f(alphaLocation, (b.flags & 2) != 0 ? .5f : .01f);
        GL20.glUniform1i(ignoreAlphaLocation, (b.flags & 8) != 0 ? 1 : 0);
        var sourceColor = dev.flower.stevedance.client.LiveArenaEffects.diffuse(b);
        GL20.glUniform4f(
                diffuseLocation, sourceColor[0], sourceColor[1], sourceColor[2], sourceColor[3]);
        GL30.glBindVertexArray(b.vao);
        GL11.glDrawElements(GL11.GL_TRIANGLES, b.count, GL11.GL_UNSIGNED_INT, 0L);
        drawCalls++;
    }

    private static final Map<String, Integer> uniformLocations = new HashMap<>();

    private static int location(String name) {
        return uniformLocations.computeIfAbsent(name, n -> GL20.glGetUniformLocation(program, n));
    }

    private static final class AuxBindings {
        final int[] textures = new int[5];
        int cube;
    }

    private static final AuxBindings AUXILIARY = new AuxBindings();

    private static AuxBindings captureAux() {
        int[] ids = AUXILIARY.textures;
        int cube = 0;
        for (int i = 1; i <= 5; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            ids[i - 1] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (i == 4) {
                cube = GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP);
            }
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        AUXILIARY.cube = cube;
        return AUXILIARY;
    }

    private static void restoreAux(AuxBindings state) {
        for (int i = 1; i <= 5; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, state.textures[i - 1]);
            if (i == 4) {
                GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, state.cube);
            }
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static void uploadCubes(PvSceneData scene) throws IOException {
        int old = GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_CUBE_MAP);
        try {
            int[] faceOrder = {0, 1, 2, 3, 5, 4};
            for (var entry : scene.cubeImages.entrySet()) {
                int id = GL11.glGenTextures();
                cubeTextures.put(entry.getKey(), id);
                GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, id);
                int side = 0;
                for (int face = 0; face < 6; face++) {
                    try (var image =
                            AssetImage.read(new ByteArrayInputStream(entry.getValue()[face]))) {
                        if (image.getWidth() != image.getHeight()
                                || image.getWidth() > 1024
                                || (face > 0 && side != image.getWidth())) {
                            throw new IOException("Invalid source cube face size");
                        }
                        side = image.getWidth();
                        GL11.glTexImage2D(
                                GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X + faceOrder[face],
                                0,
                                GL11.GL_RGBA8,
                                side,
                                side,
                                0,
                                GL11.GL_RGBA,
                                GL11.GL_UNSIGNED_BYTE,
                                image.getPixelsRGBA());
                    }
                }
                GL11.glTexParameteri(
                        GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(
                        GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(
                        GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(
                        GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(
                        GL13.GL_TEXTURE_CUBE_MAP, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
            }
        } finally {
            GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, old);
        }
    }

    private static int patternTexture(Draw draw, int texture) {
        if (draw.patterns != null) {
            for (var p : draw.patterns) {
                if (p.texture == texture) {
                    return p.selected;
                }
            }
        }
        return texture;
    }

    private static void bindMaterial(Draw draw) {
        var b = draw.batch;
        GL20.glUniform1i(hdrLocation, installed.hdr() ? 1 : 0);
        GL20.glUniform1i(location("ToneMapOutput"), PvHdrRenderer.active() ? 0 : 1);
        var material = b.sourceMaterial;
        int mask = 0;
        int[] uvSets = UV_SETS;
        Arrays.fill(uvSets, 0);
        float specWeight = 1, normalWeight = 1, cubeWeight = 1;
        SLOT_MATRICES.clear();
        for (int index = 0; index < 3; index++) {
            SLOT_MATRIX.identity();
            if (material != null) {
                String type = index == 0 ? "Specular" : index == 1 ? "Normal" : "Transparency";
                for (var slot : material.textures) {
                    if (slot.type.equals(type)) {
                        mask |= 1 << index;
                        uvSets[index] = slot.uvSet;
                        SLOT_MATRIX.set(slot.matrix);
                        GL13.glActiveTexture(GL13.GL_TEXTURE0 + index + 1);
                        GL11.glBindTexture(
                                GL11.GL_TEXTURE_2D, textureIds[patternTexture(draw, slot.texture)]);
                        GL11.glTexParameteri(
                                GL11.GL_TEXTURE_2D,
                                GL11.GL_TEXTURE_WRAP_S,
                                wrap(slot.mirrorU ? 2 : slot.repeatU ? 1 : 0));
                        GL11.glTexParameteri(
                                GL11.GL_TEXTURE_2D,
                                GL11.GL_TEXTURE_WRAP_T,
                                wrap(slot.mirrorV ? 2 : slot.repeatV ? 1 : 0));
                        if (index == 0) {
                            specWeight = slot.weight;
                        }
                        if (index == 1) {
                            normalWeight = slot.weight;
                        }
                    }
                }
            }
            SLOT_MATRIX.get(SLOT_MATRICES);
            SLOT_MATRICES.position((index + 1) * 16);
        }
        SLOT_MATRICES.flip();
        GL20.glUniformMatrix4fv(auxTransformsLocation, false, SLOT_MATRICES);
        if (material != null) {
            for (var slot : material.textures) {
                if (slot.type.equals("EnvironmentCube")) {
                    Integer cube = cubeTextures.get(slot.texture);
                    if (cube == null) {
                        throw new IllegalStateException("Source environment cube not uploaded");
                    }
                    GL13.glActiveTexture(GL13.GL_TEXTURE4);
                    GL11.glBindTexture(GL13.GL_TEXTURE_CUBE_MAP, cube);
                    mask |= 8;
                    cubeWeight = slot.weight;
                }
            }
        }
        GL20.glUniform1i(secondaryModeLocation, 0);
        if (material != null) {
            boolean first = true;
            for (var slot : material.textures) {
                if (slot.type.equals("Color")) {
                    if (first) {
                        first = false;
                        continue;
                    }
                    GL13.glActiveTexture(GL13.GL_TEXTURE5);
                    GL11.glBindTexture(
                            GL11.GL_TEXTURE_2D, textureIds[patternTexture(draw, slot.texture)]);
                    GL11.glTexParameteri(
                            GL11.GL_TEXTURE_2D,
                            GL11.GL_TEXTURE_WRAP_S,
                            wrap(slot.mirrorU ? 2 : slot.repeatU ? 1 : 0));
                    GL11.glTexParameteri(
                            GL11.GL_TEXTURE_2D,
                            GL11.GL_TEXTURE_WRAP_T,
                            wrap(slot.mirrorV ? 2 : slot.repeatV ? 1 : 0));
                    GL20.glUniform1i(secondaryModeLocation, (slot.samplerFlags >>> 5) & 31);
                    GL20.glUniform1i(secondaryUvLocation, slot.uvSet);
                    GL20.glUniform1f(secondaryWeightLocation, slot.weight);
                    matrix(secondaryTransformLocation, SLOT_MATRIX.set(slot.matrix));
                    break;
                }
            }
        }
        GL20.glUniform1i(slotsLocation, mask);
        GL20.glUniform3i(uvsLocation, uvSets[0], uvSets[1], uvSets[2]);
        GL20.glUniform3f(weightsLocation, specWeight, normalWeight, cubeWeight);
        float[] ambient = material == null ? DEFAULT_AMBIENT : material.ambient;
        GL20.glUniform3f(materialAmbientLocation, ambient[0], ambient[1], ambient[2]);
        var light = installed.lighting;
        GL20.glUniform3f(
                lightSpecularLocation,
                light == null ? 1 : light.specular[0],
                light == null ? 1 : light.specular[1],
                light == null ? 1 : light.specular[2]);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    private static int wrap(int mode) {
        return mode == 2
                ? GL14.GL_MIRRORED_REPEAT
                : mode == 1 ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE;
    }

    public static boolean ready() {
        return installed != null && program != 0;
    }

    public static void clear() {
        dev.flower.stevedance.client.LiveArenaEffects.clear();
        PvStageScreen.clear();
        PvMovieBackground.clear();
        YsmPropAdapter.clearAttachments();
        uniformLocations.clear();
        PvAetTimeline.clear();
        PvLyrics.clear();
        if (installed != null) {
            for (var g : installed.geometry) {
                for (var b : g.batches) {
                    if (b.vao != 0) {
                        GL30.glDeleteVertexArrays(b.vao);
                    }
                    if (b.vbo != 0) {
                        GL15.glDeleteBuffers(b.vbo);
                    }
                    if (b.ebo != 0) {
                        GL15.glDeleteBuffers(b.ebo);
                    }
                    b.vao = b.vbo = b.ebo = 0;
                }
            }
            installed = null;
        }
        var manager = Minecraft.getInstance().getTextureManager();
        for (var id : textures) {
            manager.release(id);
        }
        textures.clear();
        textureIds = null;
        for (int id : cubeTextures.values()) {
            GL11.glDeleteTextures(id);
        }
        cubeTextures.clear();
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
        program = 0;
    }
}
