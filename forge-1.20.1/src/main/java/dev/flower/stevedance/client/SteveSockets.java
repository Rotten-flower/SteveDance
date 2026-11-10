package dev.flower.stevedance.client;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Actual vanilla mesh sockets, in renderer-local blocks. No source motion axes.
 * 原版真实网格挂点，单位为格；不混入原作骨骼旋转。
 * Local +X: across palm, +Y: fingers/toes, -Z: palm/front/eye direction.
 * 局部 +X 横向，+Y 指尖/脚尖，-Z 掌面/正面/视线。 */
public final class SteveSockets {
    private SteveSockets() {}

    public static Map<String, Transform> sample(PlayerModel<?> model) {
        Map<String, Transform> result = new LinkedHashMap<>();
        add(result, "eyes", model.head, 0, -4, -4);
        add(result, "head", model.head, 0, -4, 0);
        add(result, "chest", model.body, 0, 4, -2);
        add(result, "back", model.body, 0, 4, 2);
        add(result, "torso", model.body, 0, 4, 0);
        hand(result, "left", model.leftArm);
        hand(result, "right", model.rightArm);
        add(result, "left_sole", model.leftLeg, 0, 12, 0);
        add(result, "right_sole", model.rightLeg, 0, 12, 0);
        return Collections.unmodifiableMap(result);
    }

    private static void hand(Map<String, Transform> result, String side, ModelPart arm) {
        add(result, side + "_shoulder", arm, 0, 0, 0);
        // 中文：手臂枢轴偏离中心。读取实际立方体，包括细臂。
        // EN: Arm pivots sit off center. Read the actual cube, including slim arms.
        ModelPart.Cube[] mesh = new ModelPart.Cube[1];
        arm.visit(
                new PoseStack(),
                (pose, path, index, cube) -> {
                    if (mesh[0] == null) {
                        mesh[0] = cube;
                    }
                });
        var cube = Objects.requireNonNull(mesh[0], "Vanilla arm cube");
        float center = (cube.minX + cube.maxX) * .5f;
        add(result, side + "_palm", arm, center, 10, cube.minZ);
        add(result, side + "_grip", arm, center, 10, cube.minZ);
        add(result, side + "_fingers", arm, center, cube.maxY, cube.minZ);
    }

    private static void add(
            Map<String, Transform> result, String id, ModelPart part, float x, float y, float z) {
        PoseStack pose = new PoseStack();
        part.translateAndRotate(pose);
        var matrix = pose.last().pose();
        var p = matrix.transformPosition(new Vector3f(x / 16, y / 16, z / 16));
        var q = matrix.getUnnormalizedRotation(new Quaternionf()).normalize();
        result.put(id, new Transform(new Vec(p.x, p.y, p.z), new Quat(q.x, q.y, q.z, q.w)));
    }
}
