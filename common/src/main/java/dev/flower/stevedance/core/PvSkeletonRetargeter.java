package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Output;
import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Skeleton;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;
import static dev.flower.stevedance.core.SkeletonRetargeter.twoBone;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传递求解后的来源关节坐标系，保留原生世界位置与目标肢体长度。
 * <p>Transfers solved source joint frames; preserves native world positions and target limb lengths.</p>
 */
public final class PvSkeletonRetargeter {
    private final Skeleton target;
    private final Map<String, Quat> alignment = new HashMap<>();
    public final double modelScale, unitsPerMeter, bodyScale;

    public PvSkeletonRetargeter(PvBodyMotion motion, Skeleton target) {
        this(motion, target, 1);
    }

    public PvSkeletonRetargeter(PvBodyMotion motion, Skeleton target, double bodyScale) {
        if (!Double.isFinite(bodyScale) || bodyScale <= 0 || bodyScale > 16) {
            throw new IllegalArgumentException("Native body scale outside calibration limits");
        }
        this.target = target;
        this.bodyScale = bodyScale;
        double leg =
                segment(target, "LeftLeg", "LeftLowerLeg")
                        + segment(target, "LeftLowerLeg", "LeftFoot");
        unitsPerMeter = leg / motion.sourceLegLength;
        modelScale = 16 / unitsPerMeter * bodyScale;
        for (String name : motion.names) {
            if (FingerRetargeter.isFinger(name)) {
                continue;
            }
            target.bone(name);
            alignment.put(
                    name,
                    PvBodyMotion.rotation(motion.bind.get(name).rotation())
                            .inv()
                            .mul(target.bind.get(name).rotation()));
        }
        // 中文：MOT逆运动学节段坐标系沿来源正X轴，须将该轴与各目标节段对齐。
        // EN: MOT IK segment frames point along source +X. Match that axis to each target
        // 中文：不要使用逆运动学朝向处于奇异位置的合成静止姿态。
        // EN: segment, instead of using a synthetic rest pose whose IK aim was singular.
        for (String side : List.of("Left", "Right")) {
            alignment.put(side + "Arm", armBasis(side + "Arm", side + "ForeArm"));
            alignment.put(side + "ForeArm", armBasis(side + "ForeArm", side + "Hand"));
            alignment.put(side + "Hand", alignment.get(side + "ForeArm"));
        }
        if (target.has("UpBody")) {
            alignment.put("UpBody", alignment.get("DownBody"));
        }
    }

    /**
     * 保留目标渲染器的世界尺寸，同时固定原曲根路径。
     * <p>Preserve the target renderer's world size, while the authored root path stays fixed.</p>
     */
    public static PvSkeletonRetargeter nativeSize(
            PvBodyMotion motion, Skeleton target, double rendererScale) {
        var base = new PvSkeletonRetargeter(motion, target);
        return new PvSkeletonRetargeter(motion, target, rendererScale / base.modelScale);
    }

    /**
     * 缩放身体时保持当前脚踝中点的世界位置不变，服装与肢体求解器继续使用原有模型单位。
     * <p>Keep the posed ankle midpoint fixed in world space while scaling the body. Clothing and limb solvers continue to work in their existing model units.</p>
     */
    public Vec fittedRoot(Map<String, Quat> rotations, Map<String, Vec> translations) {
        Vec root = translations.getOrDefault("AllBody", Vec.ZERO);
        if (bodyScale == 1) {
            return root;
        }
        var posed = target.fkTotal(rotations, translations);
        Vec anchor = mid(posed, "LeftFoot", "RightFoot");
        Vec correction = anchor.mul(1 / bodyScale - 1);
        String parent = target.bone("AllBody").parent();
        return root.add(
                parent == null ? correction : posed.get(parent).rotation().inv().turn(correction));
    }

    public Output sample(PvBodyMotion motion, double seconds) {
        var source = motion.sample(seconds);
        Map<String, Quat> globals = new HashMap<>(), local = new LinkedHashMap<>();
        for (var b : target.bones) {
            Quat parent = b.parent() == null ? Quat.ID : globals.get(b.parent());
            String mapped = b.name().equals("UpBody") ? "DownBody" : b.name();
            Quat desired =
                    alignment.containsKey(b.name())
                            ? PvBodyMotion.rotation(source.get(mapped).rotation())
                                    .mul(alignment.get(b.name()))
                            : parent.mul(Quat.euler(b.restEuler()));
            globals.put(b.name(), desired);
            local.put(b.name(), parent.inv().mul(desired));
        }
        Vec pelvis = PvBodyMotion.position(mid(source, "LeftLeg", "RightLeg")).mul(unitsPerMeter);
        Vec root = pelvis.sub(mid(target.fkTotal(local, Vec.ZERO), "LeftLeg", "RightLeg"));
        double error = 0;
        for (String side : List.of("Left", "Right")) {
            String upper = side + "Leg", lower = side + "LowerLeg", foot = side + "Foot";
            var world = target.fkTotal(local, root);
            Vec goal = PvBodyMotion.position(source.get(foot).position()).mul(unitsPerMeter);
            Vec pole =
                    world.get(upper)
                            .position()
                            .add(
                                    PvBodyMotion.position(
                                                    source.get(lower)
                                                            .position()
                                                            .sub(source.get(upper).position()))
                                            .mul(unitsPerMeter));
            var limb =
                    twoBone(
                            world.get(upper).position(),
                            goal,
                            pole,
                            segment(target, upper, lower),
                            segment(target, lower, foot));
            Quat thigh =
                    Quat.between(
                                    world.get(lower).position().sub(world.get(upper).position()),
                                    limb.knee().sub(world.get(upper).position()))
                            .mul(world.get(upper).rotation());
            local.put(upper, world.get(target.bone(upper).parent()).rotation().inv().mul(thigh));
            world = target.fkTotal(local, root);
            Quat shin =
                    Quat.between(
                                    world.get(foot).position().sub(world.get(lower).position()),
                                    limb.foot().sub(world.get(lower).position()))
                            .mul(world.get(lower).rotation());
            local.put(lower, thigh.inv().mul(shin));
            local.put(foot, shin.inv().mul(globals.get(foot)));
            error =
                    Math.max(
                            error,
                            target.fkTotal(local, root).get(foot).position().sub(goal).length());
        }
        for (String side : List.of("Left", "Right")) {
            String upper = side + "Arm", lower = side + "ForeArm", hand = side + "Hand";
            var world = target.fkTotal(local, root);
            // 不同比例的肩宽不应把源直臂强行拉成屈肘。Preserve reach relative to the actual shoulder.
            double sourceLength =
                    motion.bind
                                    .get(lower)
                                    .position()
                                    .sub(motion.bind.get(upper).position())
                                    .length()
                            + motion.bind
                                    .get(hand)
                                    .position()
                                    .sub(motion.bind.get(lower).position())
                                    .length();
            double armGain =
                    (segment(target, upper, lower) + segment(target, lower, hand)) / sourceLength;
            Vec goal =
                    world.get(upper)
                            .position()
                            .add(
                                    PvBodyMotion.position(
                                                    source.get(hand)
                                                            .position()
                                                            .sub(source.get(upper).position()))
                                            .mul(armGain));
            Vec pole =
                    world.get(upper)
                            .position()
                            .add(
                                    PvBodyMotion.position(
                                                    source.get(lower)
                                                            .position()
                                                            .sub(source.get(upper).position()))
                                            .mul(armGain));
            var limb =
                    twoBone(
                            world.get(upper).position(),
                            goal,
                            pole,
                            segment(target, upper, lower),
                            segment(target, lower, hand));
            Quat arm =
                    Quat.between(
                                    world.get(lower).position().sub(world.get(upper).position()),
                                    limb.knee().sub(world.get(upper).position()))
                            .mul(world.get(upper).rotation());
            local.put(upper, world.get(target.bone(upper).parent()).rotation().inv().mul(arm));
            world = target.fkTotal(local, root);
            Quat forearm =
                    Quat.between(
                                    world.get(hand).position().sub(world.get(lower).position()),
                                    limb.foot().sub(world.get(lower).position()))
                            .mul(world.get(lower).rotation());
            local.put(lower, arm.inv().mul(forearm));
            // 中文：保留原始手腕相对前臂的动作。
            // EN: Keep the original wrist motion relative to its forearm. The arm IK
            // 中文：手臂逆运动学会改变前臂的世界坐标系，若反向旋转回旧手腕世界坐标系，
            // EN: changes the forearm's world frame; counter-rotating back to an old
            // 中文：会在接触点引入无关扭转。
            // EN: world wrist frame introduced an unrelated twist at the contact point.
            Quat basis = alignment.get(lower);
            Quat wrist =
                    PvBodyMotion.rotation(source.get(lower).rotation())
                            .inv()
                            .mul(PvBodyMotion.rotation(source.get(hand).rotation()));
            local.put(hand, basis.inv().mul(wrist).mul(basis));
        }
        // 手腕 IK 完成后接局部手指，避免把手指锁回解算前的世界朝向。
        // 中文：在手腕逆运动学后应用手指，使局部动作继承修正后的手腕。
        // EN: Apply fingers after wrist IK so their local motion inherits the corrected wrist.
        FingerRetargeter.apply(motion, source, target, local);
        root = fittedRoot(local, Map.of("AllBody", root));
        return new Output(Map.copyOf(local), root, error);
    }

    private Quat armBasis(String joint, String child) {
        return Quat.between(
                target.bone(child).pivot().sub(target.bone(joint).pivot()), new Vec(1, 0, 0));
    }

    private static double segment(Skeleton s, String a, String b) {
        return s.bind.get(a).position().sub(s.bind.get(b).position()).length();
    }

    private static Vec mid(Map<String, Transform> p, String a, String b) {
        return p.get(a).position().add(p.get(b).position()).mul(.5);
    }
}
