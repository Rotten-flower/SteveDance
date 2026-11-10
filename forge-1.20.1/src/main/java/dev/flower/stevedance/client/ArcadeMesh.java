package dev.flower.stevedance.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 用户街机的逐面网格与 STEP 待机动画。
 * <p>Cabinet mesh and stepped idle animation.</p>
 */
public final class ArcadeMesh {
    private static final ResourceLocation DATA =
            new ResourceLocation("stevedance", "arcade/mesh.json");
    private static final ResourceLocation[] TEXTURES = {
        new ResourceLocation("stevedance", "textures/block/arcade_cabinet_atlas.png"),
        new ResourceLocation("stevedance", "textures/block/arcade_trim_atlas.png"),
        new ResourceLocation("stevedance", "textures/block/arcade_screen_frames.png")
    };

    private record Face(float[] vertices, float nx, float ny, float nz) {}

    private record Batch(int texture, Face[] faces) {}

    private record Part(float[][] frames, Batch[] batches) {}

    private static Part[] parts;
    private static Object resources;

    private static Part[] parts() {
        var manager = Minecraft.getInstance().getResourceManager();
        if (parts == null || resources != manager) {
            try (var in = manager.open(DATA);
                    var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                var parsed = new java.util.ArrayList<Part>();
                var fixed = new java.util.ArrayList<JsonElement>();
                for (var value : JsonParser.parseReader(reader).getAsJsonArray()) {
                    var p = value.getAsJsonObject();
                    var offsets = p.getAsJsonArray("frames");
                    if (offsets.isEmpty()) {
                        p.getAsJsonArray("faces").forEach(fixed::add);
                        continue;
                    }
                    float[][] frames = new float[offsets.size()][3];
                    for (int i = 0; i < frames.length; i++) {
                        for (int a = 0; a < 3; a++) {
                            frames[i][a] = offsets.get(i).getAsJsonArray().get(a).getAsFloat() / 16;
                        }
                    }
                    parsed.add(new Part(frames, decode(p.getAsJsonArray("faces"))));
                }
                parsed.add(0, new Part(new float[0][], decode(fixed)));
                parts = parsed.toArray(Part[]::new);
                resources = manager;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot load arcade mesh", e);
            }
        }
        return parts;
    }

    private static Batch[] decode(Iterable<JsonElement> source) {
        var groups = new java.util.LinkedHashMap<Integer, java.util.List<Face>>();
        for (var value : source) {
            var face = value.getAsJsonObject();
            float[] vertices = new float[15];
            var input = face.getAsJsonArray("vertices");
            for (int i = 0; i < 3; i++) {
                for (int a = 0; a < 5; a++) {
                    vertices[i * 5 + a] = input.get(i).getAsJsonArray().get(a).getAsFloat();
                }
            }
            var n = face.getAsJsonArray("normal");
            groups.computeIfAbsent(face.get("texture").getAsInt(), k -> new java.util.ArrayList<>())
                    .add(
                            new Face(
                                    vertices,
                                    n.get(0).getAsFloat(),
                                    n.get(1).getAsFloat(),
                                    n.get(2).getAsFloat()));
        }
        return groups.entrySet().stream()
                .map(e -> new Batch(e.getKey(), e.getValue().toArray(Face[]::new)))
                .toArray(Batch[]::new);
    }

    public static void render(
            PoseStack pose,
            MultiBufferSource buffers,
            int light,
            int overlay,
            float yaw,
            double seconds,
            boolean powered) {
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw + 180));
        int frame = Math.floorMod((int) Math.floor(seconds * 12), 24);
        for (var part : parts()) {
            var frames = part.frames();
            if (!powered && frames.length != 0) {
                continue;
            }
            pose.pushPose();
            if (frames.length != 0) {
                var offset = frames[frame];
                pose.translate(-offset[0], offset[1], offset[2]);
            }
            for (var batch : part.batches()) {
                var b = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURES[batch.texture()]));
                for (var face : batch.faces()) {
                    var v = face.vertices();
                    // 中文：模型局部像素坐标；已验证的灵梦面部点，不猜测其他骨骼的眼睛。
                    // EN: Entity renderer uses quads; duplicate final vertex for each triangle.
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int i = Math.min(vertex, 2) * 5;
                        b.vertex(pose.last().pose(), v[i], v[i + 1], v[i + 2])
                                .color(255, 255, 255, 255)
                                .uv(v[i + 3], v[i + 4])
                                .overlayCoords(overlay)
                                .uv2(light)
                                .normal(pose.last().normal(), face.nx(), face.ny(), face.nz())
                                .endVertex();
                    }
                }
            }
            pose.popPose();
        }
        pose.popPose();
    }

    public static final class ItemRenderer extends BlockEntityWithoutLevelRenderer {
        public ItemRenderer() {
            super(
                    Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                    Minecraft.getInstance().getEntityModels());
        }

        @Override
        public void renderByItem(
                net.minecraft.world.item.ItemStack stack,
                net.minecraft.world.item.ItemDisplayContext context,
                PoseStack pose,
                MultiBufferSource buffers,
                int light,
                int overlay) {
            render(pose, buffers, light, overlay, 0, 0, false);
        }
    }
}
