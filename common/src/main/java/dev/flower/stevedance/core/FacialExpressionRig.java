package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将原曲形变权重映射到模型专用的有界表情，不生成额外时间轴。
 * <p>Maps authored morph weights to a bounded, model-specific face. No procedural timeline.</p>
 */
public final class FacialExpressionRig {
    public record Node(String name, Vec pivot, Vec restEuler) {}

    public record Pose(Vec rotation, Vec translation, Vec scale) {}

    public record Frame(Map<String, Pose> bones, Map<String, Float> source, double seconds) {}

    /**
     * 与来源格式无关的语义权重，时间轴和来源记录仍由源数据负责。
     * <p>Source-independent semantic weights; the source owns the timeline and provenance.</p>
     */
    public record Input(Map<String, Float> channels, Map<String, Float> source, double seconds) {
        public Input {
            if (!Double.isFinite(seconds)
                    || seconds < 0
                    || channels.size() > 11
                    || source.size() > 128) {
                throw new IllegalArgumentException("Invalid face input");
            }
            for (var entry : channels.entrySet()) {
                if (!SEMANTIC_CHANNELS.contains(entry.getKey())
                        || entry.getValue() == null
                        || !Float.isFinite(entry.getValue())
                        || entry.getValue() < 0
                        || entry.getValue() > 1) {
                    throw new IllegalArgumentException("Invalid semantic face channel");
                }
            }
            for (var entry : source.entrySet()) {
                if (entry.getKey() == null
                        || entry.getKey().length() > 80
                        || entry.getValue() == null
                        || !Float.isFinite(entry.getValue())) {
                    throw new IllegalArgumentException("Invalid face source evidence");
                }
            }
            channels = Map.copyOf(channels);
            source = Map.copyOf(source);
        }
    }

    private static final Set<String> SEMANTIC_CHANNELS =
            Set.of(
                    "blink",
                    "smileEyes",
                    "winkLeft",
                    "winkRight",
                    "mouthSmile",
                    "browRaise",
                    "browLower",
                    "browSerious",
                    "browSmile",
                    "browTroubled",
                    "browAngry");
    private static final List<String> ROLES =
            List.of(
                    "eyes",
                    "lids",
                    "leftLid",
                    "rightLid",
                    "leftBase",
                    "rightBase",
                    "brows",
                    "leftBrow",
                    "rightBrow",
                    "leftBlinkBrow",
                    "rightBlinkBrow",
                    "mouth",
                    "expression",
                    "closedMouth",
                    "openMouth",
                    "smileMouth",
                    "smileOpenMouth");
    private static final Set<String> CHANNELS =
            Set.of(
                    "blink",
                    "smileEyes",
                    "winkLeft",
                    "winkRight",
                    "mouthSmile",
                    "browRaise",
                    "browLower",
                    "browSerious",
                    "browSmile",
                    "browTroubled",
                    "browAngry",
                    "a",
                    "i",
                    "u",
                    "o");
    // 中文：接受旧配置，但语音通道不接管或启用本表情。
    // EN: Accept old configurations, but speech channels never own or enable this face.
    private static final Set<String> SPEECH_CHANNELS = Set.of("a", "i", "u", "o");
    private final Map<String, Node> roles;
    private final Map<String, List<String>> channels;
    private final Set<String> sources;
    private final double lidLift,
            lidScale,
            blinkLift,
            blinkScale,
            blinkBrowDrop,
            smileBrowDrop,
            mouthForward,
            browLift,
            extraBlinkBrowDrop;
    public final String id;

    private FacialExpressionRig(JsonObject json) throws IOException {
        int schema = json.get("schema").getAsInt();
        if (schema != 1 && schema != 2) {
            throw new IOException("Unsupported face schema");
        }
        id = json.get("id").getAsString();
        if (!id.matches("[a-zA-Z0-9_-]{1,64}")) {
            throw new IOException("Invalid face ID");
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        Set<String> nativeNames = new HashSet<>();
        var definitions = json.getAsJsonObject("bones");
        for (String role : ROLES) {
            if (!definitions.has(role)) {
                if (schema == 1
                        && !Set.of("lids", "leftBlinkBrow", "rightBlinkBrow").contains(role)) {
                    throw new IOException("Missing face role: " + role);
                }
                continue;
            }
            var b = definitions.getAsJsonObject(role);
            String name = b.get("name").getAsString();
            if (name.isBlank()
                    || name.length() > 80
                    || name.chars().anyMatch(Character::isISOControl)
                    || !nativeNames.add(name)) {
                throw new IOException("Invalid/duplicate face bone");
            }
            Vec p = vector(b, "pivot"), r = vector(b, "rotation");
            if (p.length() > 256 || r.length() > 1440) {
                throw new IOException("Face reference outside limits");
            }
            nodes.put(role, new Node(name, p, new Vec(-r.x(), r.y(), -r.z()).mul(Math.PI / 180)));
        }
        if (!nodes.containsKey("leftLid") || !nodes.containsKey("rightLid")) {
            throw new IOException("Face needs a validated eyelid pair");
        }
        roles = Map.copyOf(nodes);
        Map<String, List<String>> mappings = new HashMap<>();
        Set<String> sourceNames = new LinkedHashSet<>();
        for (var entry : json.getAsJsonObject("channels").entrySet()) {
            if (!CHANNELS.contains(entry.getKey())
                    || entry.getValue().getAsJsonArray().size() > 8) {
                throw new IOException("Invalid face channel");
            }
            var names = new ArrayList<String>();
            for (var value : entry.getValue().getAsJsonArray()) {
                String name = value.getAsString();
                if (name.isBlank() || name.length() > 40 || names.contains(name)) {
                    throw new IOException("Invalid face morph name");
                }
                names.add(name);
            }
            boolean supported =
                    !entry.getKey().startsWith("brow")
                            || roles.containsKey("leftBrow") && roles.containsKey("rightBrow");
            if (entry.getKey().equals("mouthSmile")) {
                supported = roles.containsKey("smileMouth");
            }
            if (supported && !SPEECH_CHANNELS.contains(entry.getKey())) {
                sourceNames.addAll(names);
                mappings.put(entry.getKey(), List.copyOf(names));
            }
        }
        channels = Map.copyOf(mappings);
        sources = Set.copyOf(sourceNames);
        var calibration = json.getAsJsonObject("calibration");
        lidLift = number(calibration, "lidLift", -128, 128);
        lidScale = number(calibration, "closedLidScale", 0, 1);
        mouthForward = number(calibration, "mouthForward", -4, 4);
        browLift = calibration.has("browLift") ? number(calibration, "browLift", 0, 4) : .45;
        blinkLift = calibration.has("blinkLift") ? number(calibration, "blinkLift", -128, 128) : 0;
        blinkScale =
                calibration.has("closedBlinkScale")
                        ? number(calibration, "closedBlinkScale", 0, 1)
                        : 0;
        blinkBrowDrop =
                calibration.has("blinkBrowDrop")
                        ? number(calibration, "blinkBrowDrop", -4, 4)
                        : (schema == 1 ? -1.5 : 0);
        smileBrowDrop =
                calibration.has("smileBrowDrop") ? number(calibration, "smileBrowDrop", -4, 4) : 0;
        extraBlinkBrowDrop =
                calibration.has("extraBlinkBrowDrop")
                        ? number(calibration, "extraBlinkBrowDrop", -4, 4)
                        : 0;
    }

    public static FacialExpressionRig read(JsonObject json) throws IOException {
        try {
            return new FacialExpressionRig(json);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException ex) {
            throw new IOException("Invalid face configuration", ex);
        }
    }

    /**
     * 由已核对的左右眼睑枢轴确定中性眼位，单位为骨架像素。
     * <p>Neutral eye reference from the validated left/right eyelid pivots, in rig pixels.</p>
     */
    public Vec eyeReference() {
        return roles.get("leftLid").pivot().add(roles.get("rightLid").pivot()).mul(.5);
    }

    public Collection<Node> nodes() {
        return roles.values();
    }

    public Map<String, Node> roles() {
        return roles;
    }

    public Set<String> capabilities() {
        var result = new LinkedHashSet<String>(List.of("blink", "wink", "smileEyes"));
        if (roles.containsKey("leftBrow") && roles.containsKey("rightBrow")) {
            result.add("brows");
        }
        if (roles.containsKey("smileMouth")) {
            result.add("smileMouth");
        }
        return Set.copyOf(result);
    }

    /**
     * 使用实际名称、原点和统一缩放实例化结构模板。
     * <p>Instantiate a structural template with actual names, origin and uniform scale.</p>
     */
    FacialExpressionRig fitted(Map<String, Node> actual, double scale) {
        return new FacialExpressionRig(
                id,
                actual,
                channels,
                sources,
                lidLift * scale,
                lidScale,
                blinkLift * scale,
                blinkScale,
                blinkBrowDrop * scale,
                smileBrowDrop * scale,
                mouthForward * scale,
                browLift * scale,
                extraBlinkBrowDrop * scale);
    }

    private FacialExpressionRig(
            String id,
            Map<String, Node> roles,
            Map<String, List<String>> channels,
            Set<String> sources,
            double lidLift,
            double lidScale,
            double blinkLift,
            double blinkScale,
            double blinkBrowDrop,
            double smileBrowDrop,
            double mouthForward,
            double browLift,
            double extraBlinkBrowDrop) {
        this.id = id;
        this.roles = Map.copyOf(roles);
        this.channels = channels;
        this.sources = sources;
        this.lidLift = lidLift;
        this.lidScale = lidScale;
        this.blinkLift = blinkLift;
        this.blinkScale = blinkScale;
        this.blinkBrowDrop = blinkBrowDrop;
        this.smileBrowDrop = smileBrowDrop;
        this.mouthForward = mouthForward;
        this.browLift = browLift;
        this.extraBlinkBrowDrop = extraBlinkBrowDrop;
    }

    public boolean drives(VmdMotion motion) {
        return sources.stream().anyMatch(motion.morphs()::contains);
    }

    private double weight(String channel, Map<String, Float> values) {
        double result = 0;
        for (String name : channels.getOrDefault(channel, List.of())) {
            result += values.getOrDefault(name, 0f);
        }
        return clamp(result);
    }

    public Frame sample(VmdMotion motion, double seconds) {
        Map<String, Float> source = new LinkedHashMap<>();
        for (String name : sources) {
            source.put(name, motion.morph(name, seconds));
        }
        Map<String, Float> semantic = new LinkedHashMap<>();
        for (String channel : SEMANTIC_CHANNELS) {
            semantic.put(channel, (float) weight(channel, source));
        }
        return sample(new Input(semantic, source, seconds));
    }

    /**
     * 已确认的PV状态与VMD形变共用原生端点。
     * <p>Already confirmed PV states use the same native endpoints as VMD morphs.</p>
     */
    public Frame sample(Input input) {
        Map<String, Float> values = input.channels();
        Map<String, Pose> poses = new LinkedHashMap<>();
        for (var node : roles.values()) {
            poses.put(node.name(), new Pose(node.restEuler(), Vec.ZERO, new Vec(1, 1, 1)));
        }
        double blink = values.getOrDefault("blink", 0f),
                smileEyes = values.getOrDefault("smileEyes", 0f);
        double left = clamp(blink + values.getOrDefault("winkLeft", 0f)),
                right = clamp(blink + values.getOrDefault("winkRight", 0f));
        lid(poses, "leftLid", left, smileEyes);
        lid(poses, "rightLid", right, smileEyes);
        double raise = values.getOrDefault("browRaise", 0f) - values.getOrDefault("browLower", 0f),
                serious =
                        values.getOrDefault("browSerious", 0f)
                                + values.getOrDefault("browAngry", 0f)
                                - values.getOrDefault("browTroubled", 0f)
                                - values.getOrDefault("browSmile", 0f);
        if (roles.containsKey("brows")) {
            put(poses, "brows", Vec.ZERO, new Vec(0, browLift * raise, 0), new Vec(1, 1, 1));
        }
        brow(poses, "leftBrow", left * (1 - smileEyes), smileEyes, raise, serious);
        brow(poses, "rightBrow", right * (1 - smileEyes), smileEyes, raise, -serious);
        put(
                poses,
                "leftBlinkBrow",
                Vec.ZERO,
                new Vec(0, extraBlinkBrowDrop * (left * (1 - smileEyes) + smileEyes), 0),
                new Vec(1, 1, 1));
        put(
                poses,
                "rightBlinkBrow",
                Vec.ZERO,
                new Vec(0, extraBlinkBrowDrop * (right * (1 - smileEyes) + smileEyes), 0),
                new Vec(1, 1, 1));
        // 中文：避免模型的备用表情网格覆盖已接管的面部节点。
        // EN: Keep the model's alternative expression meshes from overriding these owned face
        // nodes.
        put(poses, "expression", Vec.ZERO, Vec.ZERO, Vec.ZERO);
        for (String role : List.of("closedMouth", "openMouth", "smileMouth", "smileOpenMouth")) {
            put(poses, role, Vec.ZERO, Vec.ZERO, Vec.ZERO);
        }
        double smile = values.getOrDefault("mouthSmile", 0f);
        double happy = Math.max(smile, smileEyes);
        // 中文：使用模型原有的闭合表情预设，不为元音重塑嘴部。
        // EN: Use the model's closed emotion presets; do not reshape its mouth for vowels.
        if (happy > .3) {
            put(poses, "smileMouth", Vec.ZERO, new Vec(0, 0, mouthForward), new Vec(1, 1, 1));
        } else {
            for (String role :
                    List.of("mouth", "closedMouth", "openMouth", "smileMouth", "smileOpenMouth")) {
                var node = roles.get(role);
                if (node != null) {
                    poses.remove(node.name());
                }
            }
        }
        return new Frame(Map.copyOf(poses), input.source(), input.seconds());
    }

    private void lid(Map<String, Pose> poses, String role, double blink, double smile) {
        double ordinary = blink * (1 - smile);
        put(
                poses,
                role,
                Vec.ZERO,
                new Vec(0, blinkLift * ordinary + lidLift * smile, 0),
                new Vec(1, 1 + (blinkScale - 1) * ordinary + (lidScale - 1) * smile, 1));
    }

    private void brow(
            Map<String, Pose> poses,
            String role,
            double closure,
            double smile,
            double raise,
            double serious) {
        // 中文：这些原生brow网格也构成闭合上眼睑，须保留两者联动。
        // EN: These native "brow" meshes also form the closing upper lid. Preserve that coupling.
        double blocking = (blinkBrowDrop != 0 ? closure : 0) + (smileBrowDrop != 0 ? smile : 0);
        double lift = browLift * raise;
        double y =
                lift * (1 - blocking)
                        + blinkBrowDrop * closure
                        + smileBrowDrop * smile
                        - (roles.containsKey("brows") ? lift : 0);
        put(
                poses,
                role,
                new Vec(0, 0, Math.toRadians(12) * serious * (1 - blocking)),
                new Vec(0, y, 0),
                new Vec(1, 1, 1));
    }

    private void put(Map<String, Pose> poses, String role, Vec delta, Vec translation, Vec scale) {
        var node = roles.get(role);
        if (node != null) {
            poses.put(node.name(), new Pose(node.restEuler().add(delta), translation, scale));
        }
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double number(JsonObject object, String key, double min, double max)
            throws IOException {
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IOException("Invalid face calibration: " + key);
        }
        return value;
    }

    private static Vec vector(JsonObject object, String key) throws IOException {
        var a = object.getAsJsonArray(key);
        if (a.size() != 3) {
            throw new IOException("Invalid face vector");
        }
        var v = new Vec(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        if (!Double.isFinite(v.x() + v.y() + v.z())) {
            throw new IOException("Nonfinite face vector");
        }
        return v;
    }
}
