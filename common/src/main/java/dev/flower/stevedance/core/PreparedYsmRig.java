package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Bone;
import static dev.flower.stevedance.core.SkeletonRetargeter.HUMANOID;
import static dev.flower.stevedance.core.SkeletonRetargeter.Skeleton;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Local skeleton metadata only. 不保存网格或贴图，映射可独立于模组编辑。 */
public final class PreparedYsmRig {
    public static final int VERSION = 1;

    public record Joint(String name, String parent, Vec pivot, Vec rotation) {}

    public record Result(
            YsmModelProfiles.Profile profile, List<String> missing, int fingers, boolean cached) {}

    private static final Gson JSON =
            new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private PreparedYsmRig() {}

    public static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 每个实测影响集合均包含骨骼自身，即使它没有顶点。
     * <p>Each measured influence set includes the bone itself, even if it owns no vertices.</p>
     */
    public static int[] parents(List<BitSet> influence) {
        int n = influence.size();
        int[] parents = new int[n];
        Arrays.fill(parents, -1);
        for (int child = 0; child < n; child++) {
            if (!influence.get(child).get(child)) {
                throw new IllegalArgumentException("Missing native location output at " + child);
            }
            int size = Integer.MAX_VALUE;
            for (int a = 0; a < n; a++) {
                if (a != child && influence.get(a).get(child)) {
                    int s = influence.get(a).cardinality();
                    if (s <= influence.get(child).cardinality()) {
                        throw new IllegalArgumentException("Ambiguous native hierarchy");
                    }
                    if (s < size) {
                        parents[child] = a;
                        size = s;
                    }
                }
            }
        }
        // 验证完整的传递闭包，而非只选一个看起来接近的父级。
        for (int child = 0; child < n; child++) {
            BitSet expected = new BitSet(n);
            int a = child;
            while (a >= 0) {
                if (expected.get(a)) {
                    throw new IllegalArgumentException("Cyclic native hierarchy");
                }
                expected.set(a);
                a = parents[a];
            }
            for (int ancestor = 0; ancestor < n; ancestor++) {
                if (expected.get(ancestor) != influence.get(ancestor).get(child)) {
                    throw new IllegalArgumentException("Inconsistent native hierarchy");
                }
            }
        }
        return parents;
    }

    public static JsonObject snapshot(String signature, List<Joint> joints) {
        if (joints.isEmpty() || joints.size() > 4096) {
            throw new IllegalArgumentException("Native bone budget");
        }
        JsonObject doc = new JsonObject();
        doc.addProperty("schema", VERSION);
        doc.addProperty("signature", signature);
        doc.addProperty("measurement", "YSM-2.6.5 native location translation influence");
        JsonArray rows = new JsonArray();
        for (Joint j : joints) {
            JsonObject r = new JsonObject();
            r.addProperty("name", j.name());
            if (j.parent() == null) {
                r.add("parent", JsonNull.INSTANCE);
            } else {
                r.addProperty("parent", j.parent());
            }
            r.add("position", array(j.pivot()));
            r.add("rotationRadians", array(j.rotation()));
            rows.add(r);
        }
        doc.add("bones", rows);
        return doc;
    }

    public static List<Joint> joints(JsonObject doc, String signature) {
        if (doc.get("schema").getAsInt() != VERSION
                || !signature.equals(doc.get("signature").getAsString())) {
            throw new IllegalArgumentException("Skeleton signature changed");
        }
        List<Joint> joints = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonElement item : doc.getAsJsonArray("bones")) {
            JsonObject r = item.getAsJsonObject();
            String name = r.get("name").getAsString();
            if (name.isBlank()
                    || !names.add(name)
                    || name.length() > 128
                    || joints.size() >= 4096) {
                throw new IllegalArgumentException("Invalid native bone");
            }
            joints.add(
                    new Joint(
                            name,
                            r.get("parent").isJsonNull() ? null : r.get("parent").getAsString(),
                            vec(r, "position"),
                            vec(r, "rotationRadians")));
        }
        Map<String, Joint> index = new HashMap<>();
        joints.forEach(j -> index.put(j.name(), j));
        for (Joint j : joints) {
            Set<String> visited = new HashSet<>();
            String at = j.name();
            while (at != null) {
                if (!visited.add(at)) {
                    throw new IllegalArgumentException("Cyclic native skeleton");
                }
                Joint node = index.get(at);
                if (node == null) {
                    throw new IllegalArgumentException("Missing native parent");
                }
                at = node.parent();
            }
        }
        return List.copyOf(joints);
    }

    public static JsonObject mapping(
            String id, String signature, List<Joint> joints, YsmModelProfiles.Profile seed) {
        Map<String, String> roles = new TreeMap<>();
        Set<String> names = new HashSet<>();
        joints.forEach(j -> names.add(j.name()));
        if (seed != null) {
            seed.definition()
                    .nativeNames()
                    .forEach(
                            (role, n) -> {
                                if (names.contains(n)) {
                                    roles.put(role, n);
                                }
                            });
        }
        for (String role : HUMANOID.keySet()) {
            if (!roles.containsKey(role)) {
                List<String> match =
                        joints.stream()
                                .map(Joint::name)
                                .filter(n -> aliases(role).contains(normalize(n)))
                                .toList();
                if (match.size() == 1 && !roles.containsValue(match.get(0))) {
                    roles.put(role, match.get(0));
                }
            }
        }
        // 中文：单独的UpBody通常表示躯干，不能额外绑定为骨盆。
        // EN: UpBody alone conventionally names the torso, never an additional pelvis binding.
        if (!roles.containsKey("UpperBody")) {
            List<String> up = names.stream().filter(n -> normalize(n).equals("upbody")).toList();
            if (up.size() == 1 && !roles.containsValue(up.get(0))) {
                roles.put("UpperBody", up.get(0));
            }
        }
        for (String side : List.of("Left", "Right")) {
            for (String digit : List.of("Thumb", "Index", "Middle", "Ring", "Little")) {
                for (int segment = 1; segment <= 3; segment++) {
                    String role = side + digit + segment;
                    if (roles.containsKey(role)) {
                        continue;
                    }
                    String suffix =
                            switch (segment) {
                                case 1 -> "proximal";
                                case 2 -> "intermediate";
                                default -> "distal";
                            };
                    Set<String> aliases =
                            new HashSet<>(
                                    List.of(
                                            normalize(role),
                                            normalize(side + digit + suffix),
                                            normalize(
                                                    side
                                                            + (digit.equals("Little")
                                                                    ? "pinky"
                                                                    : digit)
                                                            + segment)));
                    List<String> matches =
                            names.stream().filter(n -> aliases.contains(normalize(n))).toList();
                    if (matches.size() == 1 && !roles.containsValue(matches.get(0))) {
                        roles.put(role, matches.get(0));
                    }
                }
            }
        }
        JsonObject map = new JsonObject();
        map.addProperty("schema", VERSION);
        map.addProperty("signature", signature);
        map.addProperty("model", id);
        map.addProperty(
                "help",
                "Edit roles: semantic role -> native bone name. Body needs all listed required roles. Finger names: Left/Right + Thumb/Index/Middle/Ring/Little + 1..3; @2 denotes another physical hand. Re-read on the selection screen after editing. Numbered bones are never guessed.");
        JsonArray required = new JsonArray();
        HUMANOID.keySet().stream().sorted().forEach(required::add);
        map.add("requiredBodyRoles", required);
        JsonObject r = new JsonObject();
        roles.forEach(r::addProperty);
        map.add("roles", r);
        return map;
    }

    public static Result compile(
            String id,
            String signature,
            List<Joint> joints,
            JsonObject map,
            YsmModelProfiles.Profile metadata,
            boolean cached)
            throws IOException {
        if (map.get("schema").getAsInt() != VERSION
                || !signature.equals(map.get("signature").getAsString())) {
            throw new IllegalArgumentException("Mapping belongs to another skeleton");
        }
        Map<String, Joint> nativeBones = new HashMap<>();
        joints.forEach(j -> nativeBones.put(j.name(), j));
        Map<String, String> roles = new LinkedHashMap<>(), canonical = new HashMap<>();
        for (var e : map.getAsJsonObject("roles").entrySet()) {
            String role = e.getKey(), name = e.getValue().getAsString();
            if (name.isBlank()) {
                continue;
            }
            if (role.isBlank()
                    || role.length() > 128
                    || !nativeBones.containsKey(name)
                    || canonical.put(name, role) != null) {
                throw new IllegalArgumentException("Invalid/duplicate mapping: " + role);
            }
            roles.put(role, name);
        }
        List<String> missing =
                HUMANOID.keySet().stream().filter(r -> !roles.containsKey(r)).sorted().toList();
        if (!missing.isEmpty()) {
            return new Result(null, missing, 0, cached);
        }
        Set<String> kept = new HashSet<>();
        for (String n : List.copyOf(roles.values())) {
            Set<String> path = new HashSet<>();
            while (n != null) {
                if (!path.add(n)) {
                    throw new IllegalArgumentException("Cyclic parent");
                }
                Joint j = nativeBones.get(n);
                if (j == null) {
                    throw new IllegalArgumentException("Missing native parent");
                }
                kept.add(n);
                n = j.parent();
            }
        }
        int helper = 0;
        for (String n : new TreeSet<>(kept)) {
            if (!canonical.containsKey(n)) {
                String role;
                do {
                    role = "RigNode" + (++helper);
                } while (roles.containsKey(role));
                canonical.put(n, role);
                roles.put(role, n);
            }
        }
        // 中文：保留中间手部控制节点，包括备用手的偏移与零缩放。
        // EN: Preserve intervening hand controls, including offsets and zero scales for spare
        // hands.
        for (var e : List.copyOf(roles.entrySet())) {
            if (FingerRetargeter.isFinger(e.getKey())) {
                String side = e.getKey().startsWith("Left") ? "Left" : "Right",
                        hand = roles.get(side + "Hand"),
                        n = nativeBones.get(e.getValue()).parent();
                Set<String> visited = new HashSet<>();
                boolean handAncestor = false;
                String at = n;
                while (at != null && visited.add(at)) {
                    if (at.equals(hand)) {
                        handAncestor = true;
                        break;
                    }
                    at = nativeBones.get(at).parent();
                }
                // 中文：有些原作手部将手掌网格与手指放在前臂控制器的同级分支中。
                // EN: Some authored hands put palm geometry and digits on sibling branches of a
                // forearm
                // 中文：前臂控制节点。
                // EN: controller.
                if (!handAncestor) {
                    hand = roles.get(side + "ForeArm");
                    at = n;
                    visited.clear();
                    while (at != null && visited.add(at)) {
                        if (at.equals(hand)) {
                            handAncestor = true;
                            break;
                        }
                        at = nativeBones.get(at).parent();
                    }
                }
                if (!handAncestor) {
                    throw new IllegalArgumentException(
                            "Finger is not below " + side + "ForeArm: " + e.getKey());
                }
                while (n != null && !n.equals(hand)) {
                    String old = canonical.get(n);
                    if (!FingerRetargeter.isFinger(old) && !old.startsWith(side + "HandFrame")) {
                        if (HUMANOID.containsKey(old)) {
                            throw new IllegalArgumentException("Body role inside finger chain");
                        }
                        if (old.startsWith("RigNode")) {
                            String role = side + "HandFrame" + (++helper);
                            roles.remove(old);
                            roles.put(role, n);
                            canonical.put(n, role);
                        }
                    }
                    n = nativeBones.get(n).parent();
                }
            }
        }
        List<Bone> bones = new ArrayList<>();
        for (Joint j : joints) {
            if (kept.contains(j.name())) {
                bones.add(
                        new Bone(
                                canonical.get(j.name()),
                                j.parent() == null ? null : canonical.get(j.parent()),
                                j.pivot(),
                                j.rotation()));
            }
        }
        if (bones.size() > 512) {
            throw new IllegalArgumentException("Prepared profile exceeds 512 mapped nodes");
        }
        Skeleton target = new Skeleton(bones);
        for (String side : List.of("Left", "Right")) {
            for (String[] chain :
                    List.of(
                            new String[] {"Arm", "ForeArm", "Hand"},
                            new String[] {"Leg", "LowerLeg", "Foot"})) {
                for (int i = 1; i < chain.length; i++) {
                    String n = roles.get(side + chain[i]), parent = roles.get(side + chain[i - 1]);
                    boolean found = false;
                    while (n != null) {
                        n = nativeBones.get(n).parent();
                        if (Objects.equals(n, parent)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        throw new IllegalArgumentException(
                                "Incompatible limb ancestry: " + side + chain[i]);
                    }
                    double length =
                            target.bind
                                    .get(side + chain[i])
                                    .position()
                                    .sub(target.bind.get(side + chain[i - 1]).position())
                                    .length();
                    if (length < .1 || length > 128) {
                        throw new IllegalArgumentException("Invalid limb length");
                    }
                }
            }
        }
        Skeleton source;
        try (var in =
                PreparedYsmRig.class.getResourceAsStream(
                        "/assets/stevedance/retarget/miku-reference.json")) {
            source = SkeletonProfileReader.read(in);
        }
        var definition = new SkeletonProfileReader.Definition(target, Map.copyOf(roles));
        var profile =
                new YsmModelProfiles.Profile(
                        id,
                        "prepared_" + signature.substring(0, 16),
                        "body",
                        definition,
                        new SkeletonRetargeter(source, target),
                        metadata == null ? null : metadata.garments(),
                        metadata == null ? null : metadata.face(),
                        metadata == null ? List.of() : metadata.hiddenDuringPlayback());
        return new Result(
                profile,
                List.of(),
                (int) roles.keySet().stream().filter(FingerRetargeter::isFinger).count(),
                cached);
    }

    public static JsonObject read(Path path) throws IOException {
        if (Files.size(path) > 2 * 1024 * 1024) {
            throw new IOException("Skeleton metadata too large");
        }
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    public static void write(Path path, JsonObject json) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "rig-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(json), StandardCharsets.UTF_8);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Set<String> aliases(String role) {
        Set<String> a = new HashSet<>();
        a.add(normalize(role));
        switch (role) {
            case "AllBody" -> a.add("hipsroot");
            case "UpperBody" -> {
                a.add("chest");
                a.add("torso");
            }
            case "DownBody" -> {
                a.add("hips");
                a.add("pelvis");
            }
            default -> {}
        }
        if (role.endsWith("ForeArm")) {
            a.add(normalize(role.replace("ForeArm", "LowerArm")));
        }
        if (role.endsWith("LowerLeg")) {
            a.add(normalize(role.replace("LowerLeg", "Calf")));
        }
        if (role.endsWith("Leg") && !role.endsWith("LowerLeg")) {
            a.add(normalize(role.replace("Leg", "Thigh")));
        }
        return a;
    }

    private static JsonArray array(Vec v) {
        JsonArray a = new JsonArray();
        a.add(v.x());
        a.add(v.y());
        a.add(v.z());
        return a;
    }

    private static Vec vec(JsonObject r, String k) {
        JsonArray a = r.getAsJsonArray(k);
        if (a.size() != 3) {
            throw new IllegalArgumentException("Expected vector");
        }
        Vec v = new Vec(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        if (!Double.isFinite(v.x() + v.y() + v.z())) {
            throw new IllegalArgumentException("Nonfinite reference");
        }
        return v;
    }
}
