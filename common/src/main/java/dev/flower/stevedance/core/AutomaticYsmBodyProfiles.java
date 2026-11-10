package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Bone;
import static dev.flower.stevedance.core.SkeletonRetargeter.HUMANOID;
import static dev.flower.stevedance.core.SkeletonRetargeter.Skeleton;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 将已知YSM人形层级拟合到已加载模型的参考关节。YSM2.6.5提供关节名称和枢轴，但父子图保存在JNI中，因此自动配置会标明模板推断，不能作为导出的真实骨架。
 * <p>Fits known YSM humanoid hierarchies to an already loaded model's reference joints. YSM 2.6.5 exposes joint names and pivots, but keeps the parent graph in JNI. Automatic profiles therefore report their template inference; they are not exported rigs.</p>
 */
public final class AutomaticYsmBodyProfiles {
    public record Joint(Vec pivot, Vec rotation) {}

    private record Template(
            String family, SkeletonProfileReader.Definition definition, String topology) {}

    private record Candidate(Template template, double error) {}

    private final Skeleton source;
    private final List<Template> templates;
    private final Set<String> controllerNames;
    private final Map<String, List<SkeletonProfileReader.Definition>> fingerTemplates;

    public AutomaticYsmBodyProfiles(Collection<YsmModelProfiles.Profile> profiles)
            throws IOException {
        try (var in =
                AutomaticYsmBodyProfiles.class.getResourceAsStream(
                        "/assets/stevedance/retarget/miku-reference.json")) {
            source = SkeletonProfileReader.read(in);
        }
        Map<String, Template> unique = new TreeMap<>();
        Set<String> helpers = new HashSet<>();
        Map<String, List<SkeletonProfileReader.Definition>> fingers = new HashMap<>();
        for (var profile : profiles) {
            var definition = profile.definition();
            var skeleton = definition.skeleton();
            Set<String> kept = new HashSet<>();
            for (String role : HUMANOID.keySet()) {
                Bone bone = skeleton.bone(role);
                while (bone != null && kept.add(bone.name())) {
                    bone = bone.parent() == null ? null : skeleton.bone(bone.parent());
                }
            }
            List<Bone> body = skeleton.bones.stream().filter(b -> kept.contains(b.name())).toList();
            Map<String, String> names = new HashMap<>();
            for (var bone : body) {
                names.put(bone.name(), definition.nativeNames().get(bone.name()));
            }
            // 中文：排序忽略等价左右分支的顺序差异。
            // EN: Sorting ignores ordering differences in equivalent left/right branches.
            String topology =
                    body.stream()
                            .map(b -> b.name() + ":" + names.get(b.name()) + ":" + b.parent())
                            .sorted()
                            .reduce("", (a, b) -> a + ";" + b);
            unique.putIfAbsent(
                    topology,
                    new Template(
                            profile.family(),
                            new SkeletonProfileReader.Definition(
                                    new Skeleton(body), Map.copyOf(names)),
                            topology));
            if (skeleton.bones.stream().anyMatch(b -> FingerRetargeter.isFinger(b.name()))) {
                fingers.computeIfAbsent(topology, k -> new ArrayList<>()).add(definition);
            }
            for (var bone : body) {
                if (!HUMANOID.containsKey(bone.name())) {
                    helpers.add(names.get(bone.name()));
                }
            }
        }
        templates = List.copyOf(unique.values());
        controllerNames = Set.copyOf(helpers);
        fingers.replaceAll((k, v) -> List.copyOf(v));
        fingerTemplates = Map.copyOf(fingers);
    }

    public YsmModelProfiles.Profile recognize(
            String id, Map<String, Joint> joints, Consumer<String> diagnostic) {
        if (joints.isEmpty() || joints.size() > 4096) {
            diagnostic.accept("Joint count outside automatic profile budget");
            return null;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (var template : templates) {
            var definition = template.definition();
            var skeleton = definition.skeleton();
            var names = definition.nativeNames();
            if (!joints.keySet().containsAll(names.values())) {
                continue;
            }
            // 中文：不能为匹配较小模板而遗漏已确认的中间身体控制节点。
            // EN: Do not omit a known intervening body controller from a smaller template.
            if (controllerNames.stream()
                    .anyMatch(n -> joints.containsKey(n) && !names.containsValue(n))) {
                continue;
            }
            double observedHeight =
                    Math.abs(
                            joints.get(names.get("Head")).pivot().y()
                                    - (joints.get(names.get("LeftFoot")).pivot().y()
                                                    + joints.get(names.get("RightFoot"))
                                                            .pivot()
                                                            .y())
                                            / 2);
            double referenceHeight =
                    Math.abs(
                            skeleton.bone("Head").pivot().y()
                                    - (skeleton.bone("LeftFoot").pivot().y()
                                                    + skeleton.bone("RightFoot").pivot().y())
                                            / 2);
            if (observedHeight < 1 || referenceHeight < 1) {
                continue;
            }
            Vec observedRoot = joints.get(names.get("AllBody")).pivot(),
                    referenceRoot = skeleton.bone("AllBody").pivot();
            double error = 0;
            for (var bone : skeleton.bones) {
                var joint = joints.get(names.get(bone.name()));
                error +=
                        joint.pivot()
                                .sub(observedRoot)
                                .mul(1 / observedHeight)
                                .sub(bone.pivot().sub(referenceRoot).mul(1 / referenceHeight))
                                .length();
            }
            candidates.add(new Candidate(template, error / skeleton.bones.size()));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::error));
        if (candidates.isEmpty()) {
            diagnostic.accept(
                    "No complete known humanoid controller hierarchy; an exported local profile is required");
            return null;
        }
        Candidate best = candidates.get(0);
        if (best.error() > .3
                || (candidates.size() > 1 && candidates.get(1).error() - best.error() < .005)) {
            diagnostic.accept(
                    "Ambiguous or incompatible body template; an exported local profile is required");
            return null;
        }
        try {
            var names = best.template().definition().nativeNames();
            List<Bone> bones = new ArrayList<>();
            for (var bone : best.template().definition().skeleton().bones) {
                var joint = joints.get(names.get(bone.name()));
                bones.add(new Bone(bone.name(), bone.parent(), joint.pivot(), joint.rotation()));
            }
            var fitted =
                    withFingers(
                            best.template(),
                            joints,
                            new SkeletonProfileReader.Definition(new Skeleton(bones), names));
            Skeleton target = fitted.skeleton();
            for (String side : List.of("Left", "Right")) {
                for (String[] segment :
                        List.of(
                                new String[] {"Arm", "ForeArm"},
                                new String[] {"ForeArm", "Hand"},
                                new String[] {"Leg", "LowerLeg"},
                                new String[] {"LowerLeg", "Foot"})) {
                    double length =
                            target.bind
                                    .get(side + segment[0])
                                    .position()
                                    .sub(target.bind.get(side + segment[1]).position())
                                    .length();
                    if (length < .1 || length > 128) {
                        throw new IllegalArgumentException("Invalid limb length");
                    }
                }
            }
            diagnostic.accept(
                    "Inferred template "
                            + best.template().family()
                            + " from loaded joints; native parent graph is not exposed by YSM 2.6.5");
            return new YsmModelProfiles.Profile(
                    id,
                    "auto_" + best.template().family(),
                    "body",
                    fitted,
                    new SkeletonRetargeter(source, target),
                    null,
                    null,
                    List.of());
        } catch (IllegalArgumentException ex) {
            diagnostic.accept("Invalid runtime reference rig: " + ex.getMessage());
            return null;
        }
    }

    /**
     * 已知手部独立核对，缺失或不匹配时仍保留身体识别。
     * <p>Optional measured hands never gate the body.</p>
     */
    private SkeletonProfileReader.Definition withFingers(
            Template template, Map<String, Joint> joints, SkeletonProfileReader.Definition body) {
        SkeletonProfileReader.Definition chosen = null;
        String chosenKey = null;
        for (var reference : fingerTemplates.getOrDefault(template.topology(), List.of())) {
            var rig = reference.skeleton();
            Set<String> extra = new HashSet<>();
            for (var bone : rig.bones) {
                if (FingerRetargeter.isFinger(bone.name())) {
                    Bone next = bone;
                    while (next != null
                            && !body.skeleton().has(next.name())
                            && extra.add(next.name())) {
                        next = next.parent() == null ? null : rig.bone(next.parent());
                    }
                }
            }
            var branches = rig.bones.stream().filter(b -> extra.contains(b.name())).toList();
            double referenceHeight =
                    Math.abs(
                            rig.bone("Head").pivot().y()
                                    - (rig.bone("LeftFoot").pivot().y()
                                                    + rig.bone("RightFoot").pivot().y())
                                            / 2);
            var fitted = body.skeleton();
            double height =
                    Math.abs(
                            fitted.bone("Head").pivot().y()
                                    - (fitted.bone("LeftFoot").pivot().y()
                                                    + fitted.bone("RightFoot").pivot().y())
                                            / 2);
            Vec root = fitted.bone("AllBody").pivot(), referenceRoot = rig.bone("AllBody").pivot();
            // 核对真实骨名和身体归一化位置，不猜测编号骨的语义。Match names and measured proportions.
            if (branches.stream()
                    .anyMatch(
                            b -> {
                                var joint = joints.get(reference.nativeNames().get(b.name()));
                                return joint == null
                                        || joint.pivot()
                                                        .sub(root)
                                                        .mul(1 / height)
                                                        .sub(
                                                                b.pivot()
                                                                        .sub(referenceRoot)
                                                                        .mul(1 / referenceHeight))
                                                        .length()
                                                > .03;
                            })) {
                continue;
            }
            String key =
                    branches.stream()
                            .map(
                                    b ->
                                            b.name()
                                                    + ":"
                                                    + reference.nativeNames().get(b.name())
                                                    + ":"
                                                    + b.parent())
                            .sorted()
                            .reduce("", (a, b) -> a + ";" + b);
            if (chosenKey != null && !chosenKey.equals(key)) {
                return body;
            }
            List<Bone> bones = new ArrayList<>(fitted.bones);
            Map<String, String> names = new HashMap<>(body.nativeNames());
            for (var bone : branches) {
                String nativeName = reference.nativeNames().get(bone.name());
                var joint = joints.get(nativeName);
                bones.add(new Bone(bone.name(), bone.parent(), joint.pivot(), joint.rotation()));
                names.put(bone.name(), nativeName);
            }
            chosen = new SkeletonProfileReader.Definition(new Skeleton(bones), Map.copyOf(names));
            chosenKey = key;
        }
        return chosen == null ? body : chosen;
    }
}
