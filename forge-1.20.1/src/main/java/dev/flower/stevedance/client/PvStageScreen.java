package dev.flower.stevedance.client;

import dev.flower.stevedance.MotionPackLibrary;

import net.minecraft.client.Minecraft;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** STGC render_texture receives the completed world, as the original fb_copy does.
 * 复制已完成的世界画面到独立舞台纹理，不包含菜单和字幕。 */
public final class PvStageScreen {
    public record Screen(int texture, int width, int height) {}

    private record Metadata(int schema, List<Screen> screens) {}

    private static int framebuffer;
    public static int captures;

    public static List<Screen> load(Path root, int textureCount) throws IOException {
        if (!Files.isRegularFile(root.resolve("stage-screens.json"))) {
            return List.of();
        }
        Path p = PvSceneData.safe(root, "stage-screens.json");
        if (Files.size(p) > 65536) {
            throw new IOException("Screen metadata exceeds budget");
        }
        Metadata m;
        try (var reader = Files.newBufferedReader(p)) {
            m = MotionPackLibrary.GSON.fromJson(reader, Metadata.class);
        }
        if (m == null || m.schema != 1 || m.screens == null || m.screens.size() > 8) {
            throw new IOException("Invalid stage screens");
        }
        Set<Integer> seen = new HashSet<>();
        for (var s : m.screens) {
            if (s.texture < 0
                    || s.texture >= textureCount
                    || s.width < 1
                    || s.height < 1
                    || s.width > 2048
                    || s.height > 2048
                    || !seen.add(s.texture)) {
                throw new IOException("Invalid stage screen target");
            }
        }
        return List.copyOf(m.screens);
    }

    public static void capture() {
        if (!PvPlaybackController.active() || !PvPlaybackController.sceneryActive()) {
            return;
        }
        var scene = PvPlaybackController.scene;
        if (scene.screens.isEmpty()) {
            return;
        }
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
                draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            if (framebuffer == 0) {
                framebuffer = GL30.glGenFramebuffers();
            }
            var source = Minecraft.getInstance().getMainRenderTarget();
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.frameBufferId);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            for (var s : scene.screens) {
                int texture = PvStageRenderer.textureId(s.texture);
                if (texture == 0) {
                    continue;
                }
                GL30.glFramebufferTexture2D(
                        GL30.GL_DRAW_FRAMEBUFFER,
                        GL30.GL_COLOR_ATTACHMENT0,
                        GL11.GL_TEXTURE_2D,
                        texture,
                        0);
                if (GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER)
                        != GL30.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("Incomplete stage screen");
                }
                // 中文：源材质UV是自上而下的；帧缓冲坐标是自下而上的。
                // EN: Source material UVs are top-down; framebuffer coordinates are bottom-up.
                GL30.glBlitFramebuffer(
                        0,
                        0,
                        source.width,
                        source.height,
                        0,
                        s.height,
                        s.width,
                        0,
                        GL11.GL_COLOR_BUFFER_BIT,
                        GL11.GL_LINEAR);
                captures++;
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            if (scissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    public static void clear() {
        if (framebuffer != 0) {
            GL30.glDeleteFramebuffers(framebuffer);
        }
        framebuffer = 0;
    }
}
