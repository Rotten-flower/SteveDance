package dev.flower.stevedance.client;

import com.mojang.blaze3d.platform.NativeImage;

import dev.flower.stevedance.BundledSongResources;
import dev.flower.stevedance.SongCatalog;
import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 项目捕获的小型PV照片；原始游戏角色缩略图绝不作为回退。
 * <p>Small, project-captured PV photographs; original-game actor thumbnails are never a fallback.</p>
 */
public final class SongThumbnailCache {
    private static final Map<String, ResourceLocation> textures = new HashMap<>();
    private static final Map<String, int[]> sizes = new HashMap<>();
    private static boolean loading, loaded;

    public static void prepare() {
        if (loading || loaded) {
            return;
        }
        loading = true;
        CompletableFuture.supplyAsync(
                        () -> {
                            Map<String, NativeImage> result = new HashMap<>();
                            for (String id : SongCatalog.ALL.keySet()) {
                                Path p = BundledSongResources.thumbnail(id);
                                try {
                                    if (!Files.isRegularFile(p)) {
                                        continue;
                                    }
                                    if (Files.size(p) > 1024 * 1024) {
                                        throw new java.io.IOException(
                                                "PV thumbnail exceeds byte budget");
                                    }
                                    NativeImage image;
                                    try (var stream = Files.newInputStream(p)) {
                                        image = AssetImage.read(stream);
                                    }
                                    if (image.getWidth() > 960 || image.getHeight() > 544) {
                                        image.close();
                                        throw new java.io.IOException(
                                                "PV thumbnail exceeds dimension budget");
                                    }
                                    result.put(id, image);
                                } catch (Exception e) {
                                    SteveDanceMod.LOGGER.warn("PV thumbnail {} unavailable", id, e);
                                }
                            }
                            return result;
                        })
                .thenAccept(
                        images ->
                                Minecraft.getInstance()
                                        .execute(
                                                () -> {
                                                    for (var entry : images.entrySet()) {
                                                        var location =
                                                                new ResourceLocation(
                                                                        "stevedance",
                                                                        "pv_thumbnail/"
                                                                                + entry.getKey());
                                                        sizes.put(
                                                                entry.getKey(),
                                                                new int[] {
                                                                    entry.getValue().getWidth(),
                                                                    entry.getValue().getHeight()
                                                                });
                                                        Minecraft.getInstance()
                                                                .getTextureManager()
                                                                .register(
                                                                        location,
                                                                        new DynamicTexture(
                                                                                entry.getValue()));
                                                        textures.put(entry.getKey(), location);
                                                    }
                                                    loaded = true;
                                                    loading = false;
                                                }));
    }

    public static void draw(GuiGraphics g, String id, int w, int h, float alpha) {
        prepare();
        var texture = textures.get(id);
        if (texture != null) {
            OriginalUiAssets.keySprite(g, texture, w, h, alpha);
        } else {
            g.fill(0, 0, w, h, 0xff163242);
            OriginalUiAssets.text(
                    g,
                    net.minecraft.network.chat.Component.translatable("ui.stevedance.f2_pv")
                            .getString(),
                    20,
                    h / 2,
                    .8f);
        }
    }

    /** Center crop without changing photograph proportions. / 等比居中裁切。 */
    public static boolean ready() {
        prepare();
        return loaded;
    }

    /**
     * 成功解码的包缩略图的渲染线程快照。
     * <p>Render-thread snapshot of successfully decoded pack thumbnails.</p>
     */
    public static java.util.List<String> availableIds() {
        return java.util.List.copyOf(textures.keySet());
    }

    public static void drawCover(GuiGraphics g, String id, int w, int h) {
        prepare();
        var texture = textures.get(id);
        var size = sizes.get(id);
        if (texture == null || size == null) {
            return;
        }
        float scale = Math.max(w / (float) size[0], h / (float) size[1]);
        int dw = Math.round(size[0] * scale), dh = Math.round(size[1] * scale);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        g.blit(
                texture,
                (w - dw) / 2,
                (h - dh) / 2,
                dw,
                dh,
                0,
                0,
                size[0],
                size[1],
                size[0],
                size[1]);
    }
}
