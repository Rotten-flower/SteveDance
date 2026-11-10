package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Bone;
import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Skeleton;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将原曲手指轨道映射到已核对的一节、两节或三节手指。
 * <p>Original digit tracks mapped onto inspected one-, two- or three-joint hands.</p>
 */
public final class FingerRetargeter {
    private static final Set<String> JOINTS;

    static {
        Set<String> names = new HashSet<>();
        for (String side : List.of("Left", "Right")) {
            for (String digit : List.of("Thumb", "Index", "Middle", "Ring", "Little")) {
                for (int joint = 1; joint <= 3; joint++) {
                    names.add(side + digit + joint);
                }
            }
        }
        JOINTS = Set.copyOf(names);
    }

    private FingerRetargeter() {}

    /**
     * 备用实体手共享来源角色，但保留各自独立的原生分支。
     * <p>Alternate physical hands share a source role but retain separate native branches.</p>
     */
    public static String sourceRole(String name) {
        int split = name.indexOf('@');
        return split < 0 ? name : name.substring(0, split);
    }

    public static boolean isFinger(String name) {
        return JOINTS.contains(sourceRole(name));
    }

    public static boolean available(PvBodyMotion motion, String target) {
        // 中文：备用手的位置与显隐由原生手部控制层负责。
        // EN: Native hand-frame controllers own alternate-hand placement and visibility.
        // 原生手部控制层负责备用手的移位和显隐，不由歌曲重置。
        if (target.startsWith("LeftHandFrame") || target.startsWith("RightHandFrame")) {
            return false;
        }
        if (!isFinger(target)) {
            return true;
        }
        String role = sourceRole(target), digit = role.substring(0, role.length() - 1);
        return motion != null
                && motion.bind.containsKey(digit + "1")
                && motion.bind.containsKey(digit + "2")
                && motion.bind.containsKey(digit + "3");
    }

    public static void apply(
            PvBodyMotion motion,
            Map<String, Transform> source,
            Skeleton target,
            Map<String, Quat> local) {
        for (Bone proximal : target.bones) {
            String role = sourceRole(proximal.name());
            if (!JOINTS.contains(role) || !role.endsWith("1")) {
                continue;
            }
            String suffix = proximal.name().substring(role.length()),
                    digit = role.substring(0, role.length() - 1);
            String first = digit + "1" + suffix,
                    second = digit + "2" + suffix,
                    third = digit + "3" + suffix,
                    hand = role.startsWith("Left") ? "LeftHand" : "RightHand";
            if (!target.has(first)
                    || target.has(third) && !target.has(second)
                    || !available(motion, first)) {
                continue;
            }
            // 中文：两节目标手指的末节累计原曲第二、三节旋转。
            // EN: A two-joint target accumulates source joints 2+3 in its distal joint.
            // 两节目标的末节保留原曲第二、三节累计旋转，不复制同一个握拳角度。
            Quat basis =
                    PvBodyMotion.rotation(motion.bind.get(hand).rotation())
                            .inv()
                            .mul(target.bind.get(proximal.parent()).rotation());
            String sourceParent = hand;
            // 单节保留第一指节方向，不把三节弯曲全叠到一根刚性手指上。
            // 中文：单节刚性手指跟随近节指骨，三节手指保持分别驱动。
            // EN: A rigid single joint follows the proximal phalanx; three joints remain separate.
            List<String> joints =
                    target.has(third)
                            ? List.of(first, second, third)
                            : target.has(second) ? List.of(first, second) : List.of(first);
            for (String name : joints) {
                String sourceJoint =
                        sourceRole(joints.size() == 2 && name.equals(second) ? third : name);
                Quat rest =
                        PvBodyMotion.rotation(motion.bind.get(sourceParent).rotation())
                                .inv()
                                .mul(
                                        PvBodyMotion.rotation(
                                                motion.bind.get(sourceJoint).rotation()));
                Quat next = rest.inv().mul(basis).mul(Quat.euler(target.bone(name).restEuler()));
                Quat pose =
                        PvBodyMotion.rotation(source.get(sourceParent).rotation())
                                .inv()
                                .mul(PvBodyMotion.rotation(source.get(sourceJoint).rotation()));
                local.put(name, basis.inv().mul(pose).mul(next));
                basis = next;
                sourceParent = sourceJoint;
            }
        }
    }
}
