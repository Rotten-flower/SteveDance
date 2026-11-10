package dev.flower.stevedance.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 模型坐标中的骨骼重定向：X 向左，Y 向上，负 Z 向前。
 * <p>Skeletal retargeting in model coordinates: X left, Y up, negative Z forward.</p>
 */
public final class SkeletonRetargeter {
    public record Vec(double x, double y, double z) {
        public static final Vec ZERO = new Vec(0, 0, 0);

        public Vec {
            if (!Double.isFinite(x + y + z)) {
                throw new IllegalArgumentException("Non-finite vector");
            }
        }

        public Vec add(Vec b) {
            return new Vec(x + b.x, y + b.y, z + b.z);
        }

        public Vec sub(Vec b) {
            return new Vec(x - b.x, y - b.y, z - b.z);
        }

        public Vec mul(double s) {
            return new Vec(x * s, y * s, z * s);
        }

        public double dot(Vec b) {
            return x * b.x + y * b.y + z * b.z;
        }

        public Vec cross(Vec b) {
            return new Vec(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x);
        }

        public double length() {
            return Math.sqrt(dot(this));
        }

        public Vec unit() {
            double n = length();
            return n < 1e-10 ? ZERO : mul(1 / n);
        }
    }

    public record Quat(double x, double y, double z, double w) {
        public static final Quat ID = new Quat(0, 0, 0, 1);

        public Quat {
            if (!Double.isFinite(x + y + z + w)) {
                throw new IllegalArgumentException("Non-finite quaternion");
            }
        }

        public Quat unit() {
            double n = Math.sqrt(x * x + y * y + z * z + w * w);
            if (n < 1e-10) {
                throw new IllegalArgumentException("Zero quaternion");
            }
            return new Quat(x / n, y / n, z / n, w / n);
        }

        public Quat inv() {
            return new Quat(-x, -y, -z, w);
        }

        public Quat mul(Quat b) {
            return new Quat(
                            w * b.x + x * b.w + y * b.z - z * b.y,
                            w * b.y - x * b.z + y * b.w + z * b.x,
                            w * b.z + x * b.y - y * b.x + z * b.w,
                            w * b.w - x * b.x - y * b.y - z * b.z)
                    .unit();
        }

        public Vec turn(Vec v) {
            Vec q = new Vec(x, y, z), t = q.cross(v).mul(2);
            return v.add(t.mul(w)).add(q.cross(t));
        }

        public static Quat axis(Vec axis, double angle) {
            Vec a = axis.unit();
            double s = Math.sin(angle / 2);
            return new Quat(a.x * s, a.y * s, a.z * s, Math.cos(angle / 2)).unit();
        }

        public static Quat between(Vec from, Vec to) {
            Vec a = from.unit(), b = to.unit();
            if (a.length() < .5 || b.length() < .5) {
                return ID;
            }
            double d = a.dot(b);
            if (d < -.999999) {
                Vec n = a.cross(new Vec(1, 0, 0));
                if (n.length() < 1e-5) {
                    n = a.cross(new Vec(0, 1, 0));
                }
                return axis(n, Math.PI);
            }
            Vec c = a.cross(b);
            return new Quat(c.x, c.y, c.z, 1 + d).unit();
        }

        public static Quat euler(Vec radians) {
            return axis(new Vec(0, 0, 1), radians.z)
                    .mul(axis(new Vec(0, 1, 0), radians.y))
                    .mul(axis(new Vec(1, 0, 0), radians.x));
        }

        public Vec euler() {
            return new Vec(
                    Math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y)),
                    Math.asin(Math.max(-1, Math.min(1, 2 * (w * y - z * x)))),
                    Math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z)));
        }
    }

    public record Bone(String name, String parent, Vec pivot, Vec restEuler) {}

    public record Transform(Vec position, Quat rotation) {}

    public record Output(Map<String, Quat> localRotations, Vec rootTranslation, double footError) {}

    public static final Map<String, String> HUMANOID =
            Map.ofEntries(
                    Map.entry("AllBody", "センター"),
                    Map.entry("UpperBody", "上半身"),
                    Map.entry("DownBody", "下半身"),
                    Map.entry("Head", "頭"),
                    Map.entry("LeftArm", "左腕"),
                    Map.entry("RightArm", "右腕"),
                    Map.entry("LeftForeArm", "左ひじ"),
                    Map.entry("RightForeArm", "右ひじ"),
                    Map.entry("LeftHand", "左手首"),
                    Map.entry("RightHand", "右手首"),
                    Map.entry("LeftLeg", "左足"),
                    Map.entry("RightLeg", "右足"),
                    Map.entry("LeftLowerLeg", "左ひざ"),
                    Map.entry("RightLowerLeg", "右ひざ"),
                    Map.entry("LeftFoot", "左足首"),
                    Map.entry("RightFoot", "右足首"));

    public static final class Skeleton {
        public final List<Bone> bones;
        private final Map<String, Bone> index;
        public final Map<String, Transform> bind;

        public Skeleton(List<Bone> input) {
            if (input.isEmpty() || input.size() > 512) {
                throw new IllegalArgumentException("Skeleton size outside limits");
            }
            Map<String, Bone> map = new LinkedHashMap<>();
            for (Bone b : input) {
                if (b.name == null || b.name.isBlank() || map.put(b.name, b) != null) {
                    throw new IllegalArgumentException("Duplicate/empty bone");
                }
            }
            List<Bone> sorted = new ArrayList<>();
            Set<String> visiting = new HashSet<>(), done = new HashSet<>();
            for (Bone b : input) {
                visit(b, map, visiting, done, sorted);
            }
            bones = List.copyOf(sorted);
            index = Map.copyOf(map);
            bind = Map.copyOf(fk(n -> Quat.ID, n -> Vec.ZERO));
        }

        private static void visit(
                Bone b,
                Map<String, Bone> map,
                Set<String> visiting,
                Set<String> done,
                List<Bone> sorted) {
            if (done.contains(b.name)) {
                return;
            }
            if (!visiting.add(b.name)) {
                throw new IllegalArgumentException("Skeleton cycle: " + b.name);
            }
            if (b.parent != null) {
                Bone p = map.get(b.parent);
                if (p == null) {
                    throw new IllegalArgumentException("Missing parent: " + b.parent);
                }
                visit(p, map, visiting, done, sorted);
            }
            visiting.remove(b.name);
            done.add(b.name);
            sorted.add(b);
        }

        public Bone bone(String name) {
            Bone b = index.get(name);
            if (b == null) {
                throw new IllegalArgumentException("Missing bone: " + name);
            }
            return b;
        }

        public boolean has(String name) {
            return index.containsKey(name);
        }

        public Map<String, Transform> fk(
                Function<String, Quat> animation, Function<String, Vec> translation) {
            Map<String, Transform> result = new LinkedHashMap<>();
            for (Bone b : bones) {
                Transform p =
                        b.parent == null ? new Transform(Vec.ZERO, Quat.ID) : result.get(b.parent);
                Vec offset = b.parent == null ? b.pivot : b.pivot.sub(bone(b.parent).pivot);
                Quat local = Quat.euler(b.restEuler).mul(animation.apply(b.name));
                result.put(
                        b.name,
                        new Transform(
                                p.position.add(
                                        p.rotation.turn(offset.add(translation.apply(b.name)))),
                                p.rotation.mul(local)));
            }
            return result;
        }

        /**
         * 局部总旋转（已包含静止姿势），用于基于枢轴的层级引擎。
         * <p>Local total rotations (rest already included), for engines with pivot-based hierarchies.</p>
         */
        public Map<String, Transform> fkTotal(Map<String, Quat> local, Vec rootTranslation) {
            return fkTotal(local, Map.of("AllBody", rootTranslation));
        }

        public Map<String, Transform> fkTotal(
                Map<String, Quat> local, Map<String, Vec> translations) {
            Map<String, Transform> result = new LinkedHashMap<>();
            for (Bone b : bones) {
                Transform p =
                        b.parent == null ? new Transform(Vec.ZERO, Quat.ID) : result.get(b.parent);
                Vec offset = b.parent == null ? b.pivot : b.pivot.sub(bone(b.parent).pivot);
                offset = offset.add(translations.getOrDefault(b.name, Vec.ZERO));
                result.put(
                        b.name,
                        new Transform(
                                p.position.add(p.rotation.turn(offset)),
                                p.rotation.mul(
                                        local.getOrDefault(b.name, Quat.euler(b.restEuler)))));
            }
            return result;
        }
    }

    public record Limb(Vec knee, Vec foot, boolean clamped) {}

    /**
     * 解析式两骨骼 IK。pole 指定膝盖侧，包括接近笔直的姿势。
     * <p>Analytic two-bone IK. The pole specifies the knee side, including near-straight poses.</p>
     */
    public static Limb twoBone(Vec hip, Vec goal, Vec pole, double upper, double lower) {
        if (!Double.isFinite(upper + lower) || upper <= 0 || lower <= 0) {
            throw new IllegalArgumentException("Invalid limb length");
        }
        Vec ray = goal.sub(hip);
        double wanted = ray.length(),
                distance =
                        Math.max(
                                Math.abs(upper - lower) + 1e-6,
                                Math.min(upper + lower - 1e-6, wanted));
        Vec direction = wanted < 1e-8 ? new Vec(0, -1, 0) : ray.mul(1 / wanted);
        Vec bend = pole.sub(hip);
        bend = bend.sub(direction.mul(bend.dot(direction))).unit();
        if (bend.length() < .5) {
            bend = direction.cross(new Vec(1, 0, 0)).unit();
            if (bend.length() < .5) {
                bend = direction.cross(new Vec(0, 0, 1)).unit();
            }
        }
        double along = (upper * upper - lower * lower + distance * distance) / (2 * distance),
                height = Math.sqrt(Math.max(0, upper * upper - along * along));
        return new Limb(
                hip.add(direction.mul(along)).add(bend.mul(height)),
                hip.add(direction.mul(distance)),
                Math.abs(wanted - distance) > 1e-5);
    }

    private final Skeleton source, target;
    private final Map<String, String> bodyBindings;
    private final Map<String, Quat> alignment;
    public final double scale;

    public SkeletonRetargeter(Skeleton source, Skeleton target) {
        this.source = source;
        this.target = target;
        Map<String, Quat> corrections = new HashMap<>();
        Map<String, String> bindings = new HashMap<>(HUMANOID);
        // 中文：Wine Fox 将可见的臀部网格（UpBody）与腿部父级（DownBody）分离。
        // EN: Wine Fox separates the visible hip mesh (UpBody) from the leg parent (DownBody).
        // 中文：两者必须共享骨盆朝向，而上身躯干保持自身轨道。
        // EN: Both must share the pelvis orientation while the upper torso keeps its own track.
        if (target.has("UpBody")) {
            bindings.put("UpBody", HUMANOID.get("DownBody"));
        }
        bodyBindings = Map.copyOf(bindings);
        for (var entry : HUMANOID.entrySet()) {
            source.bone(entry.getValue());
            target.bone(entry.getKey());
        }
        scale =
                legLength(target, "LeftLeg", "LeftLowerLeg", "LeftFoot")
                        / legLength(source, "左足", "左ひざ", "左足首");
        for (String side : List.of("Left", "Right")) {
            String jp = side.equals("Left") ? "左" : "右";
            corrections.put(
                    side + "Arm", align(side + "Arm", side + "ForeArm", jp + "腕", jp + "ひじ"));
            Quat fore = align(side + "ForeArm", side + "Hand", jp + "ひじ", jp + "手首");
            corrections.put(side + "ForeArm", fore);
            corrections.put(side + "Hand", fore);
        }
        alignment = Map.copyOf(corrections);
    }

    private static double legLength(Skeleton s, String a, String b, String c) {
        return s.bind.get(a).position.sub(s.bind.get(b).position).length()
                + s.bind.get(b).position.sub(s.bind.get(c).position).length();
    }

    private Quat align(String t, String tc, String s, String sc) {
        return Quat.between(
                target.bind.get(tc).position.sub(target.bind.get(t).position),
                source.bind.get(sc).position.sub(source.bind.get(s).position));
    }

    public Output sample(VmdMotion motion, double seconds) {
        return solve(
                n -> motion.sample(n, seconds),
                n -> motion.ikEnabled(n, seconds) && motion.bones().contains(n));
    }

    public Output solve(
            Function<String, VmdMotion.Pose> poses,
            java.util.function.Predicate<String> ikEnabled) {
        Map<String, Transform> sourcePose =
                source.fk(n -> rotation(poses.apply(n)), n -> translation(poses.apply(n)));
        for (String jp : List.of("左", "右")) {
            if (ikEnabled.test(jp + "足ＩＫ")) {
                solveSourceLeg(sourcePose, jp);
            }
        }
        Map<String, Quat> globals = new HashMap<>(), local = new LinkedHashMap<>();
        for (Bone b : target.bones) {
            Quat parent = b.parent == null ? Quat.ID : globals.get(b.parent), desired;
            String mapped = bodyBindings.get(b.name);
            if (mapped == null) {
                desired = parent.mul(Quat.euler(b.restEuler));
            } else {
                desired =
                        sourcePose
                                .get(mapped)
                                .rotation
                                .mul(alignment.getOrDefault(b.name, Quat.ID))
                                .mul(target.bind.get(b.name).rotation);
            }
            globals.put(b.name, desired);
            local.put(b.name, parent.inv().mul(desired));
        }
        // 中文：MMD 与 Bedrock 将中心枢轴置于臀部相对两侧。
        // EN: MMD and Bedrock place their center pivots on opposite sides of the hips.
        // 中文：在全局空间中匹配骨盆位移，使中心/身体旋转不会绕脚部运行。
        // EN: Match pelvis displacement in global space so center/body rotation cannot orbit the
        // feet.
        Vec sourcePelvis = mid(sourcePose, "左足", "右足"),
                sourceBindPelvis = mid(source.bind, "左足", "右足");
        Vec desiredPelvis =
                mid(target.bind, "LeftLeg", "RightLeg")
                        .add(sourcePelvis.sub(sourceBindPelvis).mul(scale));
        Vec root = desiredPelvis.sub(mid(target.fkTotal(local, Vec.ZERO), "LeftLeg", "RightLeg"));
        Map<String, Vec> goals = new HashMap<>();
        for (String side : List.of("Left", "Right")) {
            String jp = side.equals("Left") ? "左" : "右";
            if (ikEnabled.test(jp + "足ＩＫ")) {
                goals.put(
                        side,
                        target.bind
                                .get(side + "Foot")
                                .position
                                .add(
                                        sourcePose
                                                .get(jp + "足ＩＫ")
                                                .position
                                                .sub(source.bind.get(jp + "足ＩＫ").position)
                                                .mul(scale)));
            }
        }
        // 中文：当目标臀宽/身体比例使某只脚刚好超出可达范围时，保持接触。
        // EN: Preserve contacts when target hip width/body proportions put a foot just beyond
        // reach.
        // 中文：将骨盆投影到两个可达球体内，并带有限定的校正预算。
        // EN: Project the pelvis into both reach spheres, with a bounded correction budget.
        var baseWorld = target.fkTotal(local, Vec.ZERO);
        Vec initialRoot = root;
        for (int iteration = 0; iteration < 8; iteration++) {
            for (String side : List.of("Left", "Right")) {
                Vec goal = goals.get(side);
                if (goal == null) {
                    continue;
                }
                Vec ray = goal.sub(baseWorld.get(side + "Leg").position.add(root));
                double distance = ray.length(),
                        reach =
                                segment(target, side + "Leg", side + "LowerLeg")
                                        + segment(target, side + "LowerLeg", side + "Foot")
                                        - 1e-4;
                if (distance > reach) {
                    root = root.add(ray.mul((distance - reach) / distance));
                }
                Vec correction = root.sub(initialRoot);
                double limit = legLength(target, "LeftLeg", "LeftLowerLeg", "LeftFoot") * .25;
                if (correction.length() > limit) {
                    root = initialRoot.add(correction.unit().mul(limit));
                }
            }
        }
        double error = 0;
        for (String side : List.of("Left", "Right")) {
            String jp = side.equals("Left") ? "左" : "右";
            if (!ikEnabled.test(jp + "足ＩＫ")) {
                continue;
            }
            Map<String, Transform> world = target.fkTotal(local, root);
            String hip = side + "Leg", knee = side + "LowerLeg", foot = side + "Foot";
            Vec goal = goals.get(side);
            Vec pole =
                    world.get(hip)
                            .position
                            .add(
                                    sourcePose
                                            .get(jp + "ひざ")
                                            .position
                                            .sub(sourcePose.get(jp + "足").position)
                                            .mul(scale));
            Limb limb =
                    twoBone(
                            world.get(hip).position,
                            goal,
                            pole,
                            segment(target, hip, knee),
                            segment(target, knee, foot));
            Quat thigh =
                    Quat.between(
                                    world.get(knee).position.sub(world.get(hip).position),
                                    limb.knee.sub(world.get(hip).position))
                            .mul(world.get(hip).rotation);
            local.put(hip, world.get(target.bone(hip).parent).rotation.inv().mul(thigh));
            world = target.fkTotal(local, root);
            Quat shin =
                    Quat.between(
                                    world.get(foot).position.sub(world.get(knee).position),
                                    limb.foot.sub(world.get(knee).position))
                            .mul(world.get(knee).rotation);
            local.put(knee, thigh.inv().mul(shin));
            // 中文：脚部 IK 朝向为全局；保留脚踝轨道及可选的脚趾 IK 俯仰。
            // EN: Foot IK orientation is global; retain the ankle track and optional toe IK pitch.
            Quat footRotation =
                    sourcePose.get(jp + "足ＩＫ").rotation.mul(rotation(poses.apply(jp + "足首")));
            if (ikEnabled.test(jp + "つま先ＩＫ") && source.has(jp + "つま先")) {
                Vec toe =
                        sourcePose
                                .get(jp + "つま先ＩＫ")
                                .position
                                .sub(sourcePose.get(jp + "足ＩＫ").position);
                Vec restToe =
                        source.bind
                                .get(jp + "つま先")
                                .position
                                .sub(source.bind.get(jp + "足首").position);
                if (toe.length() > .01) {
                    footRotation = Quat.between(footRotation.turn(restToe), toe).mul(footRotation);
                }
            }
            local.put(foot, shin.inv().mul(footRotation));
            error =
                    Math.max(
                            error,
                            target.fkTotal(local, root).get(foot).position.sub(goal).length());
        }
        return new Output(Collections.unmodifiableMap(local), root, error);
    }

    private static double segment(Skeleton s, String a, String b) {
        return s.bind.get(a).position.sub(s.bind.get(b).position).length();
    }

    private static Vec mid(Map<String, Transform> pose, String a, String b) {
        return pose.get(a).position.add(pose.get(b).position).mul(.5);
    }

    private void solveSourceLeg(Map<String, Transform> p, String jp) {
        String hip = jp + "足", knee = jp + "ひざ", foot = jp + "足首";
        Vec h = p.get(hip).position;
        Vec pole = h.add(p.get(hip).rotation.turn(new Vec(0, -1, -3)));
        Limb limb =
                twoBone(
                        h,
                        p.get(jp + "足ＩＫ").position,
                        pole,
                        segment(source, hip, knee),
                        segment(source, knee, foot));
        Quat thigh =
                Quat.between(p.get(knee).position.sub(h), limb.knee.sub(h))
                        .mul(p.get(hip).rotation);
        Quat shin =
                Quat.between(
                        source.bone(foot).pivot.sub(source.bone(knee).pivot),
                        limb.foot.sub(limb.knee));
        p.put(hip, new Transform(h, thigh));
        p.put(knee, new Transform(limb.knee, shin));
        p.put(foot, new Transform(limb.foot, p.get(jp + "足ＩＫ").rotation));
    }

    private static Vec translation(VmdMotion.Pose p) {
        return new Vec(p.x(), p.y(), p.z());
    }

    private static Quat rotation(VmdMotion.Pose p) {
        return new Quat(p.qx(), p.qy(), p.qz(), p.qw()).unit();
    }
}
