package dev.flower.stevedance.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import dev.flower.stevedance.MotionPackLibrary;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.WorldStage;
import dev.flower.stevedance.core.PmxStage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import org.lwjgl.opengl.GL11;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PMX网格仅由世界方块实体渲染。
 * <p>PMX meshes are rendered only by world block entities.</p>
 */
public final class PerformanceSceneRenderer {
    public record Asset(PmxStage mesh, List<byte[]> images) {}

    private record Batch(VertexBuffer buffer, ResourceLocation texture) {}

    private record Mesh(List<Batch> batches, List<ResourceLocation> textures) {
        void close() {
            for (var b : batches) {
                b.buffer.close();
            }
            for (var id : textures) {
                Minecraft.getInstance().getTextureManager().release(id);
            }
        }
    }

    private static final ResourceLocation WHITE =
            new ResourceLocation("stevedance", "textures/stage/white.png");
    private static final LinkedHashMap<String, Mesh> CACHE = new LinkedHashMap<>(4, .75f, true);
    private static final Set<String> PENDING = new HashSet<>(), FAILED = new HashSet<>();
    private static final ExecutorService LOADER =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "SteveDance scenery loader");
                        t.setDaemon(true);
                        return t;
                    });
    private static int generation;
    public static int renderedFrames;

    public static Asset load(MotionPackLibrary.Pack pack) throws IOException {
        PmxStage mesh;
        try (var input = pack.stageStream()) {
            // 未提供舞台就保留MC世界，不再借用示例舞台。 / No stage means the existing MC world.
            if (input == null) {
                return new Asset(
                        new PmxStage(new float[0], new int[0], List.of(), List.of()), List.of());
            }
            mesh = PmxStage.read(input.readNBytes(64 * 1024 * 1024 + 1));
        }
        List<byte[]> images = new ArrayList<>();
        String parent =
                pack.manifest().stage() == null
                        ? ""
                        : Optional.ofNullable(Path.of(pack.manifest().stage()).getParent())
                                .map(Object::toString)
                                .orElse("");
        for (String name : mesh.textures()) {
            String relative = (parent.isEmpty() ? "" : parent + "/") + name.replace('\\', '/');
            try (var input = pack.assetStream(relative)) {
                if (input == null) {
                    throw new IOException("Stage texture missing");
                }
                byte[] bytes = input.readNBytes(8 * 1024 * 1024 + 1);
                if (bytes.length > 8 * 1024 * 1024) {
                    throw new IOException("Stage texture exceeds 8 MiB");
                }
                images.add(bytes);
            }
        }
        return new Asset(mesh, List.copyOf(images));
    }

    public static void install(String song, Asset asset) {
        Mesh previous = CACHE.remove(song);
        if (previous != null) {
            previous.close();
        }
        List<Batch> batches = new ArrayList<>();
        List<ResourceLocation> textures = new ArrayList<>();
        var manager = Minecraft.getInstance().getTextureManager();
        try {
            for (int i = 0; i < asset.images.size(); i++) {
                NativeImage image = AssetImage.read(new ByteArrayInputStream(asset.images.get(i)));
                if (image.getWidth() > 4096 || image.getHeight() > 4096) {
                    image.close();
                    throw new IOException("Stage texture dimensions exceed limits");
                }
                DynamicTexture texture = new DynamicTexture(image);
                texture.setFilter(true, false);
                textures.add(manager.register("stevedance_" + song + "_" + i, texture));
            }
            PmxStage m = asset.mesh;
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            for (var material : m.materials()) {
                b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
                for (int i = material.start(); i < material.start() + material.count(); i++) {
                    int p = m.indices()[i] * 8;
                    float[] v = m.vertices();
                    float light =
                            .52f
                                    + .48f
                                            * Math.max(
                                                    0,
                                                    v[p + 3] * .25f
                                                            + v[p + 4] * .85f
                                                            - v[p + 5] * .4f);
                    b.vertex(v[p] * .1f, v[p + 1] * .1f, -v[p + 2] * .1f)
                            .uv(v[p + 6], v[p + 7])
                            .color(
                                    material.r() * light,
                                    material.g() * light,
                                    material.b() * light,
                                    material.a())
                            .endVertex();
                }
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(b.end());
                VertexBuffer.unbind();
                batches.add(
                        new Batch(
                                buffer,
                                material.texture() < 0 ? WHITE : textures.get(material.texture())));
            }
            CACHE.put(song, new Mesh(List.copyOf(batches), List.copyOf(textures)));
            FAILED.remove(song);
            while (CACHE.size() > 4) {
                var iterator = CACHE.entrySet().iterator();
                iterator.next().getValue().close();
                iterator.remove();
            }
        } catch (IOException | RuntimeException ex) {
            new Mesh(batches, textures).close();
            throw new IllegalStateException("Cannot upload MMD stage", ex);
        }
    }

    private static Mesh ensure(String song) {
        Mesh mesh = CACHE.get(song);
        if (mesh != null) {
            return mesh;
        }
        if (!PENDING.contains(song) && !FAILED.contains(song) && PENDING.size() < 4) {
            var pack = MotionPackLibrary.get(song);
            if (pack == null || !pack.playable()) {
                FAILED.add(song);
                return null;
            }
            PENDING.add(song);
            int ticket = generation;
            CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return load(pack);
                                } catch (IOException ex) {
                                    throw new CompletionException(ex);
                                }
                            },
                            LOADER)
                    .whenComplete(
                            (asset, error) ->
                                    Minecraft.getInstance()
                                            .execute(
                                                    () -> {
                                                        if (ticket != generation) {
                                                            return;
                                                        }
                                                        PENDING.remove(song);
                                                        if (error != null) {
                                                            FAILED.add(song);
                                                            SteveDanceMod.LOGGER.warn(
                                                                    "World stage {} could not be loaded",
                                                                    song,
                                                                    error);
                                                            return;
                                                        }
                                                        try {
                                                            install(song, asset);
                                                        } catch (RuntimeException failure) {
                                                            FAILED.add(song);
                                                            SteveDanceMod.LOGGER.warn(
                                                                    "World stage {} could not be uploaded",
                                                                    song,
                                                                    failure);
                                                        }
                                                    }));
        }
        return null;
    }

    public static void render(WorldStage.Anchor anchor, PoseStack pose) {
        if (dev.flower.stevedance.SongCatalog.ALL.containsKey(anchor.song())) {
            PvStageRenderer.render(anchor, pose);
            return;
        }
        Mesh mesh = ensure(anchor.song());
        if (mesh == null) {
            return;
        }
        pose.pushPose();
        pose.translate(.5, .125, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-anchor.yaw()));
        var previousShader = RenderSystem.getShader();
        int previousTexture = RenderSystem.getShaderTexture(0);
        float[] previousColor = RenderSystem.getShaderColor().clone();
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE),
                blend = GL11.glIsEnabled(GL11.GL_BLEND),
                depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1, 1, 1, 1);
        try {
            for (var batch : mesh.batches) {
                RenderSystem.setShaderTexture(0, batch.texture);
                RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
                batch.buffer.bind();
                batch.buffer.drawWithShader(
                        pose.last().pose(),
                        RenderSystem.getProjectionMatrix(),
                        RenderSystem.getShader());
            }
            renderedFrames++;
        } finally {
            VertexBuffer.unbind();
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.setShader(() -> previousShader);
            RenderSystem.setShaderColor(
                    previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
            if (cull) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            if (blend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            if (depth) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            pose.popPose();
        }
    }

    public static void clear() {
        generation++;
        for (var m : CACHE.values()) {
            m.close();
        }
        CACHE.clear();
        PENDING.clear();
        FAILED.clear();
    }
}
