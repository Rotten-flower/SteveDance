package dev.flower.stevedance.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 版本化元数据：共享身体解算器加上每套服装自身的有界约束。
 * <p>Versioned metadata: a shared body solver plus each outfit's own bounded constraints.</p>
 */
public final class YsmModelProfiles {
    public record Profile(
            String id,
            String family,
            String mode,
            SkeletonProfileReader.Definition definition,
            SkeletonRetargeter solver,
            GarmentPoseConstraints garments,
            FacialExpressionRig face,
            List<String> hiddenDuringPlayback) {
        public Profile {
            hiddenDuringPlayback = List.copyOf(hiddenDuringPlayback);
        }
    }

    public record NativeBone(SkeletonRetargeter.Vec pivot, SkeletonRetargeter.Quat rest) {}

    private static final String ROOT = "/assets/stevedance/retarget/";
    private final Map<String, Profile> profiles;

    private YsmModelProfiles(Map<String, Profile> profiles) {
        this.profiles = Map.copyOf(profiles);
    }

    public Profile get(String id) {
        return profiles.get(id);
    }

    public Collection<Profile> all() {
        return profiles.values();
    }

    public static YsmModelProfiles bundled() throws IOException {
        return read(path -> YsmModelProfiles.class.getResourceAsStream(ROOT + path));
    }

    /**
     * 本地导出的配置文件仅覆盖其自身的模型 ID；无效输入保留捆绑数据。
     * <p>Local exported profiles override only their own model IDs; invalid input preserves bundled data.</p>
     */
    public YsmModelProfiles withLocal(
            Path directory, java.util.function.Consumer<String> diagnostic) {
        if (!Files.isRegularFile(directory.resolve("batch/index.json"))) {
            return this;
        }
        try {
            Path realRoot = directory.toRealPath();
            var local =
                    read(
                            path -> {
                                Path file = realRoot.resolve(path).normalize();
                                try {
                                    if (!file.startsWith(realRoot)) {
                                        throw new IOException("Resource outside profile directory");
                                    }
                                    if (!Files.exists(file)) {
                                        return YsmModelProfiles.class.getResourceAsStream(
                                                ROOT + path);
                                    }
                                    if (!file.toRealPath().startsWith(realRoot)) {
                                        throw new IOException(
                                                "Linked resource outside profile directory");
                                    }
                                    return Files.newInputStream(file);
                                } catch (IOException ex) {
                                    throw new UncheckedIOException(ex);
                                }
                            });
            Map<String, Profile> merged = new HashMap<>(profiles);
            merged.putAll(local.profiles);
            if (merged.size() > 128) {
                throw new IOException("Combined YSM profile limit");
            }
            return new YsmModelProfiles(merged);
        } catch (IOException | UncheckedIOException | JsonParseException ex) {
            diagnostic.accept(
                    "Cannot load local YSM profiles at " + directory + ": " + ex.getMessage());
            return this;
        }
    }

    public static YsmModelProfiles read(Function<String, InputStream> resources)
            throws IOException {
        SkeletonRetargeter.Skeleton source;
        try (var in = resources.apply("miku-reference.json")) {
            source = SkeletonProfileReader.read(in);
        }
        Map<String, Profile> result = new LinkedHashMap<>();
        Map<String, SkeletonProfileReader.Definition> definitions = new HashMap<>();
        Map<String, SkeletonRetargeter> solvers = new HashMap<>();
        try {
            var index = json(resources, "batch/index.json");
            if (index.get("schema").getAsInt() != 1) {
                throw new IOException("Unsupported YSM profile index schema");
            }
            for (var value : index.getAsJsonArray("profiles")) {
                var entry = value.getAsJsonObject();
                String id = entry.get("id").getAsString(),
                        family = entry.get("family").getAsString(),
                        bodyPath = safe(entry.get("body").getAsString());
                if (!id.matches("[\\p{L}\\p{N}_/ .-]{1,128}")
                        || id.isBlank()
                        || id.startsWith("/")
                        || id.contains("..")
                        || !family.matches("[a-zA-Z0-9_-]{1,64}")) {
                    throw new IOException("Invalid YSM model or family identifier");
                }
                SkeletonProfileReader.Definition body = definitions.get(family);
                if (body == null) {
                    try (var in = resources.apply("batch/" + bodyPath)) {
                        body = SkeletonProfileReader.document(in);
                    }
                    definitions.put(family, body);
                    solvers.put(family, new SkeletonRetargeter(source, body.skeleton()));
                }
                SkeletonProfileReader.Definition definition = body;
                GarmentPoseConstraints garments = null;
                if (entry.has("rig")) {
                    try (var in = resources.apply(safe(entry.get("rig").getAsString()))) {
                        definition = SkeletonProfileReader.document(in);
                    }
                    try (var in = resources.apply(safe(entry.get("garments").getAsString()))) {
                        garments = new GarmentPoseConstraints(definition.skeleton(), in);
                    }
                } else if (entry.has("wardrobe")) {
                    var wardrobe =
                            json(resources, "batch/" + safe(entry.get("wardrobe").getAsString()));
                    if (wardrobe.get("schema").getAsInt() != 1) {
                        throw new IOException("Unsupported YSM wardrobe schema: " + id);
                    }
                    JsonArray extras = wardrobe.getAsJsonArray("bones");
                    if (extras.size() > 0) {
                        var merged = new JsonObject();
                        merged.addProperty("schema", 1);
                        merged.addProperty("coordinateConvention", "bedrock");
                        JsonArray bones = new JsonArray();
                        for (var b : body.skeleton().bones) {
                            var item = new JsonObject();
                            item.addProperty("name", b.name());
                            item.addProperty("nativeName", body.nativeNames().get(b.name()));
                            if (b.parent() == null) {
                                item.add("parent", JsonNull.INSTANCE);
                            } else {
                                item.addProperty("parent", b.parent());
                            }
                            item.add("position", array(b.pivot()));
                            item.add(
                                    "rotation",
                                    array(
                                            new SkeletonRetargeter.Vec(
                                                            -b.restEuler().x(),
                                                            b.restEuler().y(),
                                                            -b.restEuler().z())
                                                    .mul(180 / Math.PI)));
                            bones.add(item);
                        }
                        for (var bone : extras) {
                            bones.add(bone);
                        }
                        merged.add("bones", bones);
                        definition = SkeletonProfileReader.definition(merged);
                    }
                    var collision = wardrobe.getAsJsonObject("garments");
                    if (collision.getAsJsonArray("parts").size() > 0
                            || collision.getAsJsonArray("anchors").size() > 0) {
                        try (var in =
                                new ByteArrayInputStream(
                                        collision.toString().getBytes(StandardCharsets.UTF_8))) {
                            garments = new GarmentPoseConstraints(definition.skeleton(), in);
                        }
                    }
                }
                String mode = entry.get("mode").getAsString();
                if (!Set.of("calibrated", "sampled", "body").contains(mode)) {
                    throw new IOException("Unknown YSM profile mode");
                }
                FacialExpressionRig face = null;
                if (entry.has("face")) {
                    face =
                            FacialExpressionRig.read(
                                    json(
                                            resources,
                                            "batch/" + safe(entry.get("face").getAsString())));
                    for (var node : face.nodes()) {
                        if (definition.nativeNames().containsValue(node.name())) {
                            throw new IOException("Face node overlaps body: " + node.name());
                        }
                    }
                }
                List<String> hidden = new ArrayList<>();
                if (entry.has("hiddenDuringPlayback")) {
                    for (var node : entry.getAsJsonArray("hiddenDuringPlayback")) {
                        String name = node.getAsString();
                        if (name.isBlank()
                                || name.length() > 128
                                || hidden.contains(name)
                                || hidden.size() >= 256
                                || definition.nativeNames().containsValue(name)
                                || face != null
                                        && face.nodes().stream()
                                                .anyMatch(n -> n.name().equals(name))) {
                            throw new IOException("Invalid playback weapon node: " + name);
                        }
                        hidden.add(name);
                    }
                }
                if (result.put(
                                        id,
                                        new Profile(
                                                id,
                                                family,
                                                mode,
                                                definition,
                                                solvers.get(family),
                                                garments,
                                                face,
                                                hidden))
                                != null
                        || result.size() > 128) {
                    throw new IOException("Duplicate/excessive YSM profiles");
                }
            }
            return new YsmModelProfiles(result);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException ex) {
            throw new IOException("Invalid YSM profile metadata", ex);
        }
    }

    private static JsonArray array(SkeletonRetargeter.Vec vec) {
        JsonArray a = new JsonArray();
        a.add(vec.x());
        a.add(vec.y());
        a.add(vec.z());
        return a;
    }

    private static String safe(String path) throws IOException {
        if (!path.matches("[a-zA-Z0-9_/.-]+") || path.startsWith("/") || path.contains("..")) {
            throw new IOException("Invalid YSM resource path");
        }
        return path;
    }

    private static JsonObject json(Function<String, InputStream> resources, String path)
            throws IOException {
        try (var in = resources.apply(path)) {
            if (in == null) {
                throw new IOException("Missing YSM metadata: " + path);
            }
            byte[] bytes = in.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) {
                throw new IOException("YSM metadata too large: " + path);
            }
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new IOException("Invalid YSM metadata: " + path, ex);
        }
    }

    public static boolean matches(Profile profile, Map<String, NativeBone> nativeBones) {
        for (var bone : profile.definition().skeleton().bones) {
            var actual = nativeBones.get(profile.definition().nativeNames().get(bone.name()));
            if (actual == null || actual.pivot().sub(bone.pivot()).length() > .03) {
                return false;
            }
            var expected = SkeletonRetargeter.Quat.euler(bone.restEuler());
            double dot =
                    Math.abs(
                            actual.rest().x() * expected.x()
                                    + actual.rest().y() * expected.y()
                                    + actual.rest().z() * expected.z()
                                    + actual.rest().w() * expected.w());
            if (dot < Math.cos(.002)) {
                return false;
            }
        }
        return true;
    }
}
