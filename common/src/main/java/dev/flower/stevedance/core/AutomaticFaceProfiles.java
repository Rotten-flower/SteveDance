package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 识别面部结构，不依赖模型ID或身体重定向支持。
 * <p>Recognizes facial structures, independent of model ID and body retarget support.</p>
 */
public final class AutomaticFaceProfiles {
    private final List<FacialExpressionRig> templates;

    private AutomaticFaceProfiles(List<FacialExpressionRig> templates) {
        this.templates = List.copyOf(templates);
    }

    public int size() {
        return templates.size();
    }

    public static AutomaticFaceProfiles bundled() throws IOException {
        try (var in =
                AutomaticFaceProfiles.class.getResourceAsStream(
                        "/assets/stevedance/retarget/auto-faces.json")) {
            if (in == null) {
                throw new IOException("Missing automatic facial templates");
            }
            return read(in);
        }
    }

    public static AutomaticFaceProfiles read(InputStream in) throws IOException {
        try {
            byte[] bytes = in.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) {
                throw new IOException("Facial templates exceed size limit");
            }
            var root =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (root.get("schema").getAsInt() != 1) {
                throw new IOException("Unsupported facial template index");
            }
            List<FacialExpressionRig> rigs = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var value : root.getAsJsonArray("templates")) {
                var rig = FacialExpressionRig.read(value.getAsJsonObject());
                if (!ids.add(rig.id) || rigs.size() >= 128) {
                    throw new IOException("Duplicate/excessive facial templates");
                }
                rigs.add(rig);
            }
            return new AutomaticFaceProfiles(rigs);
        } catch (JsonParseException
                | IllegalArgumentException
                | IllegalStateException
                | NullPointerException ex) {
            throw new IOException("Invalid automatic facial templates", ex);
        }
    }

    public AutomaticFaceProfiles withLocal(Path file, Consumer<String> diagnostic) {
        if (!Files.isRegularFile(file)) {
            return this;
        }
        try (var in = Files.newInputStream(file)) {
            var local = read(in);
            Map<String, FacialExpressionRig> all = new LinkedHashMap<>();
            for (var rig : templates) {
                all.put(rig.id, rig);
            }
            for (var rig : local.templates) {
                all.put(rig.id, rig);
            }
            if (all.size() > 128) {
                throw new IOException("Combined facial template limit");
            }
            return new AutomaticFaceProfiles(new ArrayList<>(all.values()));
        } catch (IOException ex) {
            diagnostic.accept("Cannot load facial templates at " + file + ": " + ex.getMessage());
            return this;
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[_. -]", "");
    }

    public FacialExpressionRig recognize(Map<String, FacialExpressionRig.Node> bones) {
        Map<String, List<FacialExpressionRig.Node>> aliases = new HashMap<>();
        for (var b : bones.values()) {
            aliases.computeIfAbsent(key(b.name()), k -> new ArrayList<>()).add(b);
        }
        FacialExpressionRig best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (var template : templates) {
            Map<String, FacialExpressionRig.Node> nodes = new LinkedHashMap<>();
            Set<String> used = new HashSet<>();
            for (var e : template.roles().entrySet()) {
                var candidates = aliases.getOrDefault(key(e.getValue().name()), List.of());
                if (candidates.size() != 1 || !used.add(candidates.get(0).name())) {
                    break;
                }
                nodes.put(e.getKey(), candidates.get(0));
            }
            if (nodes.size() != template.roles().size()) {
                continue;
            }
            var reference = template.roles();
            Vec referenceLine =
                    reference.get("leftLid").pivot().sub(reference.get("rightLid").pivot());
            Vec actualLine = nodes.get("leftLid").pivot().sub(nodes.get("rightLid").pivot());
            if (referenceLine.length() < .1) {
                continue;
            }
            double scale = actualLine.length() / referenceLine.length();
            if (!Double.isFinite(scale) || scale < .5 || scale > 2) {
                continue;
            }
            Vec offset =
                    nodes.get("leftLid").pivot().sub(reference.get("leftLid").pivot().mul(scale));
            double error = 0;
            for (var e : reference.entrySet()) {
                var a = nodes.get(e.getKey());
                double distance =
                        a.pivot().sub(e.getValue().pivot().mul(scale).add(offset)).length();
                error = Math.max(error, distance);
                var q = Quat.euler(a.restEuler());
                var r = Quat.euler(e.getValue().restEuler());
                double dot =
                        Math.abs(q.x() * r.x() + q.y() * r.y() + q.z() * r.z() + q.w() * r.w());
                if (dot < Math.cos(.002)) {
                    error = Double.POSITIVE_INFINITY;
                    break;
                }
            }
            if (error > .025 * scale) {
                continue;
            }
            // 中文：优先选择已确认能力更多的配置，否则优先采用对应缩放的原生校准。
            // EN: Prefer more confirmed capabilities; otherwise prefer the native calibration at
            // scale
            // 1.
            double score = nodes.size() * 10 - error / scale - Math.abs(Math.log(scale));
            if (score > bestScore) {
                best = template.fitted(nodes, scale);
                bestScore = score;
            }
        }
        return best;
    }
}
