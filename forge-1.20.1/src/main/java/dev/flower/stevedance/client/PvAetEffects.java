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
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
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
public final class PvAetEffects {
    private static ShaderInstance matte, sprite;
    private static final RenderTarget[][] targets = new RenderTarget[4][2];
    private static int depth;

    public static void clear() {
        for (var pair : targets) {
            for (int i = 0; i < pair.length; i++) {
                if (pair[i] != null) {
                    pair[i].destroyBuffers();
                    pair[i] = null;
                }
            }
        }
        depth = 0;
    }

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

    public static void spriteShader(int mode) {
        spriteShader(mode, 1);
    }

    public static void spriteShader(int mode, float brightness) {
        RenderSystem.setShader(() -> sprite);
        sprite.safeGetUniform("BlendMode").set(mode);
        sprite.safeGetUniform("Brightness").set(brightness);
    }

    public static void draw(BufferBuilder.RenderedBuffer buffer, int source, int destination) {
        draw(buffer, source, destination, false);
    }

    public static void draw(
            BufferBuilder.RenderedBuffer buffer,
            int source,
            int destination,
            boolean preserveAlpha) {
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
        // 中文：原生世界通道直接绑定GL纹理。重新断言实际绑定
        // EN: Native world passes bind GL textures directly. Reassert the real bindings
        // 中文：在Minecraft的缓存采样器状态之后，包括HUD/遮罩目标。
        // EN: after Minecraft's cached sampler state, including the HUD/matte targets.
        GL20.glUseProgram(shader.getId());
        for (int i = 0; i < 2; i++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + i);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, RenderSystem.getShaderTexture(i));
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        // 中文：VertexBuffer.drawWithShader在绘制前立即再次应用JSON混合。
        // EN: VertexBuffer.drawWithShader applies the JSON blend again immediately before
        // 中文：在着色器之后应用AET模式，并使用已绑定的着色器。
        // EN: drawing. Apply the AET mode after the shader and use the already-bound shader.
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(
                source,
                destination,
                preserveAlpha ? GL11.GL_ZERO : GL11.GL_ONE,
                preserveAlpha ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);
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

    /**
     * 预合成上的混合属于其合并结果，而非其子项。
     * <p>A blend on a precomposition belongs to its combined result, not its children.</p>
     */
    public static void composed(GuiGraphics g, int blend, float opacity, Runnable content) {
        composed(g, blend, opacity, false, content);
    }

    /**
     * MML TransferFlags.PreserveAlpha：在最终混合期间保留目标alpha。
     * <p>MML TransferFlags.PreserveAlpha: retain destination alpha during the final blend.</p>
     */
    public static void composed(
            GuiGraphics g, int blend, float opacity, boolean preserveAlpha, Runnable content) {
        if (depth >= targets.length) {
            throw new IllegalStateException("AET composition target budget exceeded");
        }
        var mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth(), height = mc.getWindow().getHeight();
        g.flush();
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int index = depth++;
        var pair = targets[index];
        if (pair[0] == null) {
            pair[0] = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        } else if (pair[0].width != width || pair[0].height != height) {
            pair[0].resize(width, height, Minecraft.ON_OSX);
        }
        // 中文：乘法子树累积一个乘数，其中性颜色为白色。
        // EN: A multiply subtree accumulates a multiplier, whose neutral color is white.
        // 中文：将其清除为黑色会在到达世界之前抹除其子项。
        // EN: Clearing it to black would erase its children before it reaches the world.
        float neutral = blend == 6 ? 1 : 0;
        pair[0].setClearColor(neutral, neutral, neutral, 0);
        try {
            pair[0].clear(Minecraft.ON_OSX);
            pair[0].bindWrite(true);
            content.run();
            g.flush();
        } finally {
            depth--;
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
            RenderSystem.viewport(0, 0, width, height);
        }
        // 中文：目标已包含预乘RGB。不要再次乘以alpha。
        // EN: The target already contains premultiplied RGB. Do not multiply by alpha again.
        spriteShader(blend == 6 ? -6 : 3);
        RenderSystem.setShaderTexture(0, pair[0].getColorTextureId());
        int source = blend == 6 ? GL11.GL_DST_COLOR : GL11.GL_ONE;
        int destination =
                blend == 6
                        ? GL11.GL_ZERO
                        : blend == 5 || blend == 23
                                ? GL11.GL_ONE
                                : blend == 7
                                        ? GL11.GL_ONE_MINUS_SRC_COLOR
                                        : GL11.GL_ONE_MINUS_SRC_ALPHA;
        Matrix4f matrix = new Matrix4f();
        float w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        var b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        float a = Math.min(1, opacity), rgb = blend == 6 ? 1 : a;
        b.vertex(matrix, 0, h, 0).uv(0, 0).color(rgb, rgb, rgb, a).endVertex();
        b.vertex(matrix, w, h, 0).uv(1, 0).color(rgb, rgb, rgb, a).endVertex();
        b.vertex(matrix, w, 0, 0).uv(1, 1).color(rgb, rgb, rgb, a).endVertex();
        b.vertex(matrix, 0, 0, 0).uv(0, 1).color(rgb, rgb, rgb, a).endVertex();
        draw(b.end(), source, destination, preserveAlpha);
    }

    public static void masked(GuiGraphics g, int type, int blend, Runnable draw, Runnable mask) {
        if (depth >= targets.length) {
            throw new IllegalStateException("AET matte target budget exceeded");
        }
        if (matte == null) {
            throw new IllegalStateException("AET matte shader unavailable");
        }
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getWidth(), height = mc.getWindow().getHeight();
        int framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int index = depth++;
        RenderTarget[] pair = targets[index];
        for (int i = 0; i < 2; i++) {
            if (pair[i] == null) {
                pair[i] = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            } else if (pair[i].width != width || pair[i].height != height) {
                pair[i].resize(width, height, Minecraft.ON_OSX);
            }
            pair[i].setClearColor(0, 0, 0, 0);
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
        // 中文：draw()在分配此合成的采样器后应用着色器。
        // EN: draw() applies the shader after assigning this composition's samplers.
        // 中文：在此处应用会绑定在歌曲切换期间删除的遮罩纹理。
        // EN: Applying it here would bind matte textures deleted during a song switch.
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
        // 中文：FBO以屏幕空间存储像素；不要两次应用合成姿态。
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
