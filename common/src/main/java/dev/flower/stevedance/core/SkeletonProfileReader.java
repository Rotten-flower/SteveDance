package dev.flower.stevedance.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 读取有界、带版本的骨架元数据；不包含模型几何体。
 * <p>Reads bounded, versioned skeleton metadata; contains no model geometry.</p>
 */
public final class SkeletonProfileReader {
    public record Definition(
            SkeletonRetargeter.Skeleton skeleton, Map<String, String> nativeNames) {}

    public static SkeletonRetargeter.Skeleton read(InputStream stream) throws IOException {
        return document(stream).skeleton();
    }

    public static Definition document(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IOException("SkeletonRetargeter rig missing");
        }
        try {
            byte[] bytes = stream.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) {
                throw new IOException("SkeletonRetargeter rig too large");
            }
            JsonObject json =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (json.get("schema").getAsInt() != 1) {
                throw new IOException("Unsupported retarget rig schema");
            }
            return definition(json);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException ex) {
            throw new IOException("Invalid retarget rig", ex);
        }
    }

    public static Definition definition(JsonObject json) {
        if (json.get("schema").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported retarget rig schema");
        }
        boolean bedrock =
                json.has("coordinateConvention")
                        && json.get("coordinateConvention").getAsString().equals("bedrock");
        List<SkeletonRetargeter.Bone> bones = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (JsonElement element : json.getAsJsonArray("bones")) {
            JsonObject bone = element.getAsJsonObject();
            var rotation = vector(bone, "rotation").mul(Math.PI / 180);
            if (bedrock) {
                rotation = new SkeletonRetargeter.Vec(-rotation.x(), rotation.y(), -rotation.z());
            }
            String name = bone.get("name").getAsString(),
                    nativeName =
                            bone.has("nativeName") ? bone.get("nativeName").getAsString() : name;
            if (nativeName.isBlank() || nativeName.length() > 128 || !used.add(nativeName)) {
                throw new IllegalArgumentException("Invalid or duplicate native bone");
            }
            names.put(name, nativeName);
            bones.add(
                    new SkeletonRetargeter.Bone(
                            name,
                            bone.get("parent").isJsonNull()
                                    ? null
                                    : bone.get("parent").getAsString(),
                            vector(bone, "position"),
                            rotation));
            if (bones.size() > 512) {
                throw new IllegalArgumentException("SkeletonRetargeter rig bone limit");
            }
        }
        return new Definition(new SkeletonRetargeter.Skeleton(bones), Map.copyOf(names));
    }

    private static SkeletonRetargeter.Vec vector(JsonObject bone, String field) {
        JsonArray a = bone.getAsJsonArray(field);
        if (a.size() != 3) {
            throw new IllegalArgumentException("Expected 3 components");
        }
        return new SkeletonRetargeter.Vec(
                a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }
}
