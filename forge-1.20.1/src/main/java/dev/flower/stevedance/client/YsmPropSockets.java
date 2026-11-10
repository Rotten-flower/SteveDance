package dev.flower.stevedance.client;

import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 渲染套接字使用已验证的身体骨架，包括加密模型配置。
 * <p>Render sockets use the validated body skeleton, including encrypted model profiles.</p>
 */
final class YsmPropSockets {
    record Frame(Map<String, Transform> sockets, double scale, String modelId) {}

    private record Socket(int joint, Vec offset, Quat rotation, String role) {}

    private record Profile(
            Object geometry,
            String id,
            YsmAnimationAdapter.PropSkeleton body,
            List<Socket> sockets,
            double scale) {}

    private static final Map<Object, Profile> CACHE = new WeakHashMap<>();
    private static Method[] pose, point;
    static volatile Map<String, Vec> lastNativePoints = Map.of();

    static void clear() {
        CACHE.clear();
    }

    static Frame sample(Object model, int actor)
            throws ReflectiveOperationException, java.io.IOException {
        synchronized (YsmAnimationAdapter.class) {
            var body = YsmAnimationAdapter.propSkeleton(model);
            if (body == null) {
                return null;
            }
            if (pose == null) {
                Class<?> type = body.joints().get(0).bone().getClass();
                String[] names = {
                    "Oo0Oo0o00O00Oo0OOoOOoooo",
                    "o0OOooo0o0OO00OoOOOo0o0O",
                    "O00OOOooOoooOoo0o0o0oO0O",
                    "oOOOo0OOO0ooooo0O00OO0o0",
                    "OOOOo0O0oO0OOo0O0O0Oo0O0",
                    "Ooooo0oooO0oooOOOoO0000O",
                    "oo0OoO00oOoo000O0000o0oo",
                    "oooooooOOoOOoO00OooOo00O",
                    "Oo00o0OooOOo0ooOoo0oO0o0"
                };
                pose = new Method[names.length];
                for (int i = 0; i < names.length; i++) {
                    pose[i] = type.getMethod(names[i]);
                }
                point =
                        new Method[] {
                            type.getMethod("Ooo0O0000OO0OOO0o0Oo0oOO"),
                            type.getMethod("o0o0Oooo00OoOoOOOooo0000"),
                            type.getMethod("ooooO0o00oO0Oo0OOo0O0O0o")
                        };
            }
            var profile = CACHE.get(model);
            if (profile == null
                    || profile.geometry() != body.geometry()
                    || !profile.id().equals(body.modelId())) {
                profile = load(body, actor);
                CACHE.put(model, profile);
            }
            var motion = PvPlaybackController.performerMotion(actor);
            if (motion == null) {
                return null;
            }
            var reference = body.profile().definition().skeleton();
            var joints = body.joints();
            Map<String, Matrix4f> matrices = new HashMap<>();
            double rootScale = 1;
            for (var joint : joints) {
                var b = joint.definition();
                float[] v = new float[9];
                for (int i = 0; i < v.length; i++) {
                    v[i] = ((Number) pose[i].invoke(joint.bone())).floatValue();
                }
                if (v[6] <= 0 || v[7] <= 0 || v[8] <= 0) {
                    return null;
                }
                // 中文：与身体重定向所用的相同公共空间轴向反射。根X
                // EN: Same common-space axial reflection used by body retargeting. Root X
                // 中文：已在源世界空间中，与局部骨骼平移不同。
                // EN: is already in source-world space, unlike local bone translation.
                double x =
                        motion.sourceWorldCoordinates && b.name().equals("AllBody") ? v[3] : -v[3];
                Vec parent = b.parent() == null ? Vec.ZERO : reference.bone(b.parent()).pivot();
                Vec p = b.pivot().sub(parent).add(new Vec(x, v[4], v[5]));
                Matrix4f matrix =
                        new Matrix4f(b.parent() == null ? new Matrix4f() : matrices.get(b.parent()))
                                .translate((float) p.x(), (float) p.y(), (float) p.z())
                                .rotateZ(-v[2])
                                .rotateY(-v[1])
                                .rotateX(v[0])
                                .scale(v[6], v[7], v[8]);
                matrices.put(b.name(), matrix);
                if (b.parent() == null) {
                    rootScale *= v[6];
                }
            }
            Map<String, Transform> sockets = new HashMap<>();
            Map<String, Vec> nativePoints = new HashMap<>();
            for (var socket : profile.sockets()) {
                var joint = joints.get(socket.joint());
                var b = joint.definition();
                var m = matrices.get(b.name());
                var offset = socket.offset();
                var p =
                        m.transformPosition(
                                        new Vector3f(
                                                (float) offset.x(),
                                                (float) offset.y(),
                                                (float) offset.z()))
                                .div(16);
                var q = m.getUnnormalizedRotation(new Quaternionf()).normalize();
                Quat rotation = new Quat(q.x, q.y, q.z, q.w);
                // 中文：取消目标的参考手腕轴，然后恢复源
                // EN: Cancel the target's reference wrist axes, then restore the source
                // 中文：道具的参考轴。服装T姿势不得扭曲乐器。
                // EN: prop's reference axes. Outfit T poses must not twist the instrument.
                if (motion.bind.containsKey(socket.role())) {
                    rotation =
                            rotation.mul(reference.bind.get(b.name()).rotation().inv())
                                    .mul(motion.bind.get(socket.role()).rotation());
                }
                // 身体求解采用公共坐标，JNI 网格实际反射 X。挂点必须回到同一个原生绘制坐标。
                // 中文：JNI网格反射公共空间X；道具必须使用同一原生帧。
                // EN: The JNI mesh reflects common-space X; props must use that same native frame.
                rotation = rotation.mul(socket.rotation());
                sockets.put(
                        socket.role(),
                        new Transform(
                                new Vec(-p.x, p.y, p.z),
                                new Quat(
                                        rotation.x(), -rotation.y(), -rotation.z(), rotation.w())));
                nativePoints.put(
                        socket.role(),
                        new Vec(
                                ((Number) point[0].invoke(joint.bone())).doubleValue(),
                                ((Number) point[1].invoke(joint.bone())).doubleValue(),
                                ((Number) point[2].invoke(joint.bone())).doubleValue()));
            }
            lastNativePoints = Map.copyOf(nativePoints);
            return new Frame(Map.copyOf(sockets), profile.scale() * rootScale, body.modelId());
        }
    }

    private static Profile load(YsmAnimationAdapter.PropSkeleton body, int actor)
            throws java.io.IOException {
        var reference = body.profile().definition().skeleton();
        var motion = PvPlaybackController.performerMotion(actor);
        Map<String, Integer> indices = new HashMap<>();
        for (int i = 0; i < body.joints().size(); i++) {
            var j = body.joints().get(i);
            indices.put(j.definition().name(), i);
            indices.put(j.nativeName(), i);
        }
        Map<String, JsonObject> overrides = new HashMap<>();
        double multiplier = 1;
        Path path =
                Minecraft.getInstance()
                        .gameDirectory
                        .toPath()
                        .resolve("config/stevedance/prop-sockets.json");
        if (Files.isRegularFile(path)) {
            if (Files.size(path) > 256 * 1024) {
                throw new java.io.IOException("Prop socket configuration too large");
            }
            JsonObject config = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            int schema = config.get("schema").getAsInt();
            if (schema != 1 && schema != 2) {
                throw new java.io.IOException("Unsupported prop socket schema");
            }
            for (String group : List.of("families", "models")) {
                if (config.has(group)) {
                    var entries = config.getAsJsonObject(group);
                    String key = group.equals("models") ? body.modelId() : body.profile().family();
                    if (entries.has(key)) {
                        var entry = entries.getAsJsonObject(key);
                        if (entry.has("scale")) {
                            multiplier = entry.get("scale").getAsDouble();
                        }
                        if (entry.has("sockets")) {
                            for (var e : entry.getAsJsonObject("sockets").entrySet()) {
                                overrides.put(e.getKey(), e.getValue().getAsJsonObject());
                            }
                        }
                    }
                }
            }
        }
        if (!Double.isFinite(multiplier) || multiplier < .1 || multiplier > 4) {
            throw new java.io.IOException("Invalid prop scale");
        }
        var bind = reference.bind;
        double length =
                bind.get("LeftArm").position().sub(bind.get("LeftForeArm").position()).length()
                        + bind.get("LeftForeArm")
                                .position()
                                .sub(bind.get("LeftHand").position())
                                .length();
        double source =
                motion.bind
                                .get("LeftArm")
                                .position()
                                .sub(motion.bind.get("LeftForeArm").position())
                                .length()
                        + motion.bind
                                .get("LeftForeArm")
                                .position()
                                .sub(motion.bind.get("LeftHand").position())
                                .length();
        List<Socket> sockets = new ArrayList<>();
        for (String role : List.of("LeftHand", "RightHand", "UpperBody", "LeftArm", "RightArm")) {
            int index = indices.get(role);
            Vec offset = Vec.ZERO;
            Quat rotation = Quat.ID;
            if (role.endsWith("Hand")) {
                String fore = role.replace("Hand", "ForeArm");
                offset =
                        bind.get(role)
                                .rotation()
                                .inv()
                                .turn(
                                        bind.get(role)
                                                .position()
                                                .sub(bind.get(fore).position())
                                                .mul(.08));
            }
            JsonObject value = overrides.get(role);
            if (value != null) {
                if (value.has("bone")) {
                    Integer found = indices.get(value.get("bone").getAsString());
                    if (found == null) {
                        throw new java.io.IOException("Unknown prop socket bone for " + role);
                    }
                    index = found;
                }
                if (value.has("offset")) {
                    offset = vector(value.getAsJsonArray("offset"));
                }
                if (value.has("rotation")) {
                    rotation =
                            Quat.euler(
                                    vector(value.getAsJsonArray("rotation"))
                                            .mul(java.lang.Math.PI / 180));
                }
            }
            if (offset.length() > 32) {
                throw new java.io.IOException("Prop socket offset exceeds model bounds");
            }
            sockets.add(new Socket(index, offset, rotation, role));
        }
        return new Profile(
                body.geometry(),
                body.modelId(),
                body,
                List.copyOf(sockets),
                length / (16 * source) * multiplier);
    }

    private static Vec vector(JsonArray a) {
        if (a == null || a.size() != 3) {
            throw new IllegalArgumentException("Socket vector requires three finite components");
        }
        return new Vec(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
    }
}
