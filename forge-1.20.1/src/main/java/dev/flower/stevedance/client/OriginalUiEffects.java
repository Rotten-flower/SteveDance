package dev.flower.stevedance.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.io.IOException;

/**
 * 用于原生AET alpha和亮度轨道遮罩的有界GPU缓冲区。
 * <p>Bounded GPU buffers for native AET alpha and luminance track mattes.</p>
 */
@Mod.EventBusSubscriber(
        modid = "stevedance",
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class OriginalUiEffects {
    private static ShaderInstance matte, sprite;
    private static final RenderTarget[][] targets = new RenderTarget[4][2];
    private static int depth;
    private static boolean gray;

    public static boolean offscreen() {
        return depth > 0;
    }

    @SubscribeEvent
    public static void shaders(RegisterShadersEvent e) throws IOException {
        e.registerShader(
                new ShaderInstance(
                        e.getResourceProvider(),
                        new ResourceLocation("stevedance", "f2_matte"),
                        DefaultVertexFormat.POSITION_TEX_COLOR),
                s -> matte = s);
        e.registerShader(
                new ShaderInstance(
                        e.getResourceProvider(),
                        new ResourceLocation("stevedance", "f2_sprite"),
                        DefaultVertexFormat.POSITION_TEX_COLOR),
                s -> sprite = s);
    }

    public static void grayscale(boolean enabled) {
        gray = enabled;
    }

    public static void spriteShader(int mode) {
        RenderSystem.setShader(() -> sprite);
        sprite.safeGetUniform("BlendMode").set(mode);
        sprite.safeGetUniform("Grayscale").set(gray ? 1 : 0);
    }

    public static void draw(BufferBuilder.RenderedBuffer buffer, int source, int destination) {
        ShaderInstance shader = RenderSystem.getShader();
        shader.setSampler("Sampler0", RenderSystem.getShaderTexture(0));
        shader.setSampler("Sampler1", RenderSystem.getShaderTexture(1));
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(RenderSystem.getModelViewMatrix());
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
        }
        shader.apply();
        // 中文：VertexBuffer.drawWithShader在绘制前立即再次应用JSON混合。
        // EN: VertexBuffer.drawWithShader applies the JSON blend again immediately before
        // 中文：在着色器之后应用AET模式，并使用已绑定的着色器。
        // EN: drawing. Apply the AET mode after the shader and use the already-bound shader.
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(source, destination, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        try {
            BufferUploader.draw(buffer);
        } finally {
            shader.clear();
            RenderSystem.defaultBlendFunc();
            GL14.glBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        }
    }

    public static void backdrop(GuiGraphics g, int color, int width, int height) {
        g.flush();
        RenderSystem.disableDepthTest();
        g.fill(0, 0, width, height, color);
        g.flush();
    }

    public static void masked(GuiGraphics g, int type, int blend, Runnable draw, Runnable mask) {
        if (depth >= targets.length || matte == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth(), height = mc.getWindow().getHeight();
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int index = depth++;
        RenderTarget[] pair = targets[index];
        for (int i = 0; i < 2; i++) {
            if (pair[i] == null) {
                pair[i] = new TextureTarget(width, height, false, Minecraft.ON_OSX);
                pair[i].setClearColor(0, 0, 0, 0);
            } else if (pair[i].width != width || pair[i].height != height) {
                pair[i].resize(width, height, Minecraft.ON_OSX);
            }
        }
        g.flush();
        try {
            pair[0].clear(Minecraft.ON_OSX);
            pair[0].bindWrite(true);
            draw.run();
            g.flush();
            pair[1].clear(Minecraft.ON_OSX);
            pair[1].bindWrite(true);
            mask.run();
            g.flush();
        } finally {
            depth--;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            RenderSystem.viewport(0, 0, width, height);
        }
        RenderSystem.setShader(() -> matte);
        matte.apply();
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        if (blend == 5 || blend == 23) {
            RenderSystem.blendFuncSeparate(
                    GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            RenderSystem.blendFuncSeparate(
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE,
                    GL11.GL_ONE_MINUS_SRC_ALPHA);
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL14.glBlendFuncSeparate(
                GL11.GL_ONE,
                blend == 5 || blend == 23 ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE,
                GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(() -> matte);
        RenderSystem.setShaderTexture(0, pair[0].getColorTextureId());
        RenderSystem.setShaderTexture(1, pair[1].getColorTextureId());
        matte.safeGetUniform("MatteType").set(type);
        // 中文：FBO以屏幕空间存储像素；不要应用两次合成姿态。
        // EN: FBOs store pixels in screen space; do not apply the composition pose twice.
        Matrix4f matrix = new Matrix4f();
        float w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        b.vertex(matrix, 0, h, 0).uv(0, 0).color(1, 1, 1, 1).endVertex();
        b.vertex(matrix, w, h, 0).uv(1, 0).color(1, 1, 1, 1).endVertex();
        b.vertex(matrix, w, 0, 0).uv(1, 1).color(1, 1, 1, 1).endVertex();
        b.vertex(matrix, 0, 0, 0).uv(0, 1).color(1, 1, 1, 1).endVertex();
        draw(
                b.end(),
                GL11.GL_ONE,
                blend == 5 || blend == 23 ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);
    }
}
