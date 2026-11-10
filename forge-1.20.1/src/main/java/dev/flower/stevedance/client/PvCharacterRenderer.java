package dev.flower.stevedance.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 在共享世界 HDR 后处理之前，在实际 YSM 网格上进行源光照。
 * <p>Source light on the actual YSM mesh before the shared world HDR post.</p>
 */
@Mod.EventBusSubscriber(
        modid = "stevedance",
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public abstract class PvCharacterRenderer extends RenderType {
    private PvCharacterRenderer() {
        super(
                "stevedance_pv_character",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256,
                false,
                true,
                () -> {},
                () -> {});
    }

    private static ShaderInstance shader;

    private record Key(ResourceLocation texture, int actor) {}

    private static final Map<Key, RenderType> types = new HashMap<>();
    private static final Vector3f direction = new Vector3f();
    private static final Quaternionf rotation = new Quaternionf();
    public static int draws;
    public static boolean enabled = true;

    public static boolean nativeShader() {
        return enabled && !dev.flower.stevedance.client.ShaderPackBridge.active();
    }

    @SubscribeEvent
    public static void shaders(RegisterShadersEvent e) throws IOException {
        e.registerShader(
                new ShaderInstance(
                        e.getResourceProvider(),
                        new ResourceLocation("stevedance", "native_character"),
                        DefaultVertexFormat.NEW_ENTITY) {
                    @Override
                    public void apply() {
                        org.lwjgl.opengl.GL20.glUseProgram(getId());
                        super.apply();
                        int active =
                                org.lwjgl.opengl.GL11.glGetInteger(
                                        org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
                        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
                        org.lwjgl.opengl.GL11.glBindTexture(
                                org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                                com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(0));
                        org.lwjgl.opengl.GL13.glActiveTexture(active);
                    }
                },
                s -> {
                    shader = s;
                    types.clear();
                });
    }

    public static void clear() {
        types.clear();
    }

    public static RenderType forActor(
            net.minecraft.world.entity.Entity entity,
            ResourceLocation texture,
            RenderType original) {
        if (!nativeShader()
                || !PvPlaybackController.active()
                || !PvPlaybackController.nativeCharacterLighting()
                || !PvPlaybackController.scene.hdr()
                || PvPlaybackController.scene.lighting == null) {
            return original;
        }
        int actor = PvPlaybackController.actor(entity.getUUID());
        return actor < 0 && !SceneCrowdRenderer.contains(entity.getUUID()) ? original : type(texture, Math.max(0, actor));
    }

    public static RenderType type(ResourceLocation texture, int actor) {
        if (shader == null) {
            throw new IllegalStateException("Native character shader unavailable");
        }
        Key key = new Key(texture, actor);
        RenderType found = types.get(key);
        if (found != null) {
            return found;
        }
        if (types.size() >= 32) {
            throw new IllegalStateException("Native character texture budget exceeded");
        }
        RenderType type =
                create(
                        "stevedance_pv_character_" + actor,
                        DefaultVertexFormat.NEW_ENTITY,
                        VertexFormat.Mode.QUADS,
                        256,
                        false,
                        true,
                        CompositeState.builder()
                                .setShaderState(
                                        new ShaderStateShard(PvCharacterRenderer::preparedShader))
                                .setTextureState(new TextureStateShard(texture, false, false))
                                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                                .setCullState(NO_CULL)
                                .setWriteMaskState(COLOR_DEPTH_WRITE)
                                .createCompositeState(false));
        types.put(key, type);
        dev.flower.stevedance.SteveDanceMod.LOGGER.info(
                "Native HDR character mesh: actor {}, texture {}", actor, texture);
        return type;
    }

    private static ShaderInstance preparedShader() {
        var light = PvPlaybackController.scene.lighting;
        light.sample(PvPlaybackController.timeMs());
        shader.safeGetUniform("LinearAlbedo").set(light.characterAlbedoMode());
        shader.safeGetUniform("HalfLambert").set(light.halfLambertCharacter() ? 1 : 0);
        shader.safeGetUniform("NativeAmbient")
                .set(
                        light.characterAmbient[0],
                        light.characterAmbient[1],
                        light.characterAmbient[2]);
        shader.safeGetUniform("NativeDiffuse")
                .set(
                        light.characterDiffuse[0],
                        light.characterDiffuse[1],
                        light.characterDiffuse[2]);
        shader.safeGetUniform("NativeColor")
                .set(light.characterColor[0], light.characterColor[1], light.characterColor[2]);
        shader.safeGetUniform("NativeAdd")
                .set(light.characterAdd[0], light.characterAdd[1], light.characterAdd[2]);
        float[] p = light.characterPosition;
        direction.set(-p[0], p[1], -p[2]);
        direction.rotateY((float) Math.toRadians(180 - PvPlaybackController.performerYaw()));
        rotation.set(Minecraft.getInstance().gameRenderer.getMainCamera().rotation()).conjugate();
        direction.rotate(rotation);
        if (direction.lengthSquared() < .000001f) {
            direction.set(0, 1, 0);
        } else {
            direction.normalize();
        }
        shader.safeGetUniform("NativeDirection").set(direction.x, direction.y, direction.z);
        draws++;
        return shader;
    }

    /**
     * 保留着色器包自身的实体材质，并通过顶点提供源颜色。
     * <p>Keep the shader pack's own entity material and supply source color through vertices.</p>
     */
    public static VertexConsumer compatibleVertices(
            net.minecraft.world.entity.Entity entity, VertexConsumer output) {
        if (!dev.flower.stevedance.client.ShaderPackBridge.active()
                || !enabled
                || !PvPlaybackController.active()
                || !PvPlaybackController.nativeCharacterLighting()
                || PvPlaybackController.actor(entity.getUUID()) < 0 && !SceneCrowdRenderer.contains(entity.getUUID())
                || PvPlaybackController.scene.lighting == null) {
            return output;
        }
        var light = PvPlaybackController.scene.lighting;
        light.sample(PvPlaybackController.timeMs());
        float r = light.character[0],
                g = light.character[1],
                b = light.character[2],
                ar = light.characterAdd[0],
                ag = light.characterAdd[1],
                ab = light.characterAdd[2];
        return new VertexConsumer() {
            private int tint(int c, float factor, float add) {
                return Math.max(0, Math.min(255, Math.round(c * factor + 255 * add)));
            }

            public VertexConsumer vertex(double x, double y, double z) {
                output.vertex(x, y, z);
                return this;
            }

            public VertexConsumer color(int red, int green, int blue, int alpha) {
                output.color(tint(red, r, ar), tint(green, g, ag), tint(blue, b, ab), alpha);
                return this;
            }

            public VertexConsumer uv(float u, float v) {
                output.uv(u, v);
                return this;
            }

            public VertexConsumer overlayCoords(int u, int v) {
                output.overlayCoords(u, v);
                return this;
            }

            public VertexConsumer uv2(int u, int v) {
                output.uv2(u, v);
                return this;
            }

            public VertexConsumer normal(float x, float y, float z) {
                output.normal(x, y, z);
                return this;
            }

            public void endVertex() {
                output.endVertex();
            }

            public void defaultColor(int red, int green, int blue, int alpha) {
                output.defaultColor(tint(red, r, ar), tint(green, g, ag), tint(blue, b, ab), alpha);
            }

            public void unsetDefaultColor() {
                output.unsetDefaultColor();
            }
        };
    }
}
