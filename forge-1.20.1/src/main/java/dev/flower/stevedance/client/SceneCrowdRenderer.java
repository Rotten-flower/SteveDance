package dev.flower.stevedance.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.flower.stevedance.core.PlaybackClock;
import dev.flower.stevedance.core.PvBodyMotion;
import dev.flower.stevedance.WorldStage;
import dev.flower.stevedance.core.SkeletonRetargeter.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/** Replaces explicitly marked scene people, retaining their own authored poses and clocks.
 * 明确标注的场景人物使用默认模型，保留各自原作姿态与时间轴。 */
public final class SceneCrowdRenderer {
    private static final Map<String, String> JOINTS = Map.ofEntries(
            Map.entry("AllBody", "gblctr"), Map.entry("UpperBody", "j_mune_wj"),
            Map.entry("DownBody", "j_kosi_wj"), Map.entry("Head", "j_kao_wj"),
            Map.entry("LeftArm", "j_kata_l_wj"), Map.entry("LeftForeArm", "j_ude_l_wj"),
            Map.entry("LeftHand", "j_te_l_wj"), Map.entry("RightArm", "j_kata_r_wj"),
            Map.entry("RightForeArm", "j_ude_r_wj"), Map.entry("RightHand", "j_te_r_wj"),
            Map.entry("LeftLeg", "j_momo_l_wj"), Map.entry("LeftLowerLeg", "j_sune_l_wj"),
            Map.entry("LeftFoot", "j_asi_l_wj"), Map.entry("RightLeg", "j_momo_r_wj"),
            Map.entry("RightLowerLeg", "j_sune_r_wj"), Map.entry("RightFoot", "j_asi_r_wj"));
    private static final List<Person> people = new ArrayList<>();
    private static final Set<UUID> ids = new HashSet<>();
    private static PvSceneData installed;

    private static final class Person {
        final String graph;
        final PvSceneData.Rig rig;
        final RemotePlayer actor;
        final Map<String, PvSceneData.BoneNode> joints = new HashMap<>();
        final PvBodyMotion motion;
        volatile Map<String, Transform> sampled;
        boolean visible;
        Person(String graph, PvSceneData.Rig rig) {
            this.graph = graph; this.rig = rig;
            for (var node : rig.nodes) joints.put(node.name, node);
            var reference = new LinkedHashMap<String, Transform>();
            // Inverse binds are independent of a hidden/zero-scale first frame.
            // 绑定姿态不取可能隐藏或零缩放的第一帧。
            for (var e : JOINTS.entrySet()) {
                float[] inverseBind = rig.replacementBind.get(e.getKey());
                if (inverseBind == null || inverseBind.length != 16)
                    throw new IllegalArgumentException("Missing scene-person bind: " + e.getKey());
                reference.put(e.getKey(), transform(new Matrix4f().set(inverseBind).invert()));
            }
            sampled = Map.copyOf(reference);
            motion = new PvBodyMotion("scene-person:" + graph + ":" + rig.name, reference, () -> sampled);
            var mc = Minecraft.getInstance();
            actor = new RemotePlayer(mc.level, new GameProfile(UUID.randomUUID(), "Performer"));
            actor.setNoGravity(true); ids.add(actor.getUUID());
            PerformerModelSelection.preview(actor, PerformerModelSelection.defaultChoice());
        }
        void sample(double ms) {
            visible = installed.active(ms).contains(graph) && rig.visible
                    && PvVisibility.graph(graph) && PvVisibility.rig(graph, rig.name);
            if (!visible) return;
            var result = new LinkedHashMap<String, Transform>();
            for (var e : JOINTS.entrySet()) {
                var matrix = new Matrix4f(rig.model).mul(joints.get(e.getValue()).matrix);
                // Authored disappearance sometimes uses zero scale instead of visibility.
                // 来源退场有时用零缩放，不能将奇异矩阵提交给角色求解器。
                if (Math.abs(matrix.determinant()) < 1e-12f) { visible = false; return; }
                result.put(e.getKey(), transform(matrix));
            }
            sampled = Map.copyOf(result);
        }
    }

    private static Transform transform(Matrix4f matrix) {
        var p = matrix.getTranslation(new Vector3f());
        var q = matrix.getUnnormalizedRotation(new Quaternionf()).normalize();
        return new Transform(new Vec(p.x, p.y, p.z), new Quat(q.x, q.y, q.z, q.w).unit());
    }

    public static boolean contains(UUID id) { return ids.contains(id); }

    public static void clear() {
        for (var person : people) PerformerModelSelection.releasePreview(person.actor);
        people.clear(); ids.clear(); installed = null;
    }

    public static Map<UUID, ClientPlaybackRuntime.MotionSnapshot> snapshots() {
        var scene = PvPlaybackController.scene;
        if (scene != installed) {
            clear(); installed = scene;
            if (scene != null) for (var e : scene.description.graphs().entrySet())
                for (var rig : e.getValue().rigs) if (rig.replacementCharacter)
                    people.add(new Person(e.getKey(), rig));
        }
        if (scene == null || people.isEmpty()) return Map.of();
        double ms = PvPlaybackController.timeMs();
        var result = new HashMap<UUID, ClientPlaybackRuntime.MotionSnapshot>();
        var evaluated = new HashSet<String>();
        for (var person : people) {
            if (evaluated.add(person.graph)) scene.evaluate(scene.description.graphs().get(person.graph), scene.graphFrame(person.graph, ms));
            person.sample(ms);
            PerformerModelSelection.preview(person.actor, PerformerModelSelection.defaultChoice());
            result.put(person.actor.getUUID(), new ClientPlaybackRuntime.MotionSnapshot(null, person.motion,
                    new PlaybackClock(ms / 1000, System.nanoTime(), false, PvPlaybackController.epoch()),
                    scene.description.durationMs() / 1000.0, null));
        }
        return result;
    }

    public static void render(WorldStage.Anchor anchor, PoseStack pose) {
        if (people.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var buffers = mc.renderBuffers().bufferSource();
        pose.pushPose();
        try {
            pose.translate(.5, .125, .5);
            for (var person : people) if (person.visible) {
                // Native adapters carry source world positions; do not apply the rig transform twice.
                // 原生适配器已有来源根坐标，不再叠乘场景骨骼矩阵。
                float yaw = anchor.yaw();
                person.actor.setPos(anchor.getBlockPos().getX() + .5, anchor.getBlockPos().getY() + .125, anchor.getBlockPos().getZ() + .5);
                person.actor.yBodyRot = person.actor.yBodyRotO = person.actor.yHeadRot = person.actor.yHeadRotO = yaw;
                person.actor.setYRot(yaw); person.actor.yRotO = yaw;
                mc.getEntityRenderDispatcher().render(person.actor, 0, 0, 0, yaw, 0, pose, buffers, 0xf000f0);
            }
            buffers.endBatch();
        } finally { pose.popPose(); }
    }
}
