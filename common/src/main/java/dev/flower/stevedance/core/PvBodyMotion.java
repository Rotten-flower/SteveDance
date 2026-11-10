package dev.flower.stevedance.core;

import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从MOTC曲线和原始CMN正逆运动学骨架烘焙得到不可变、有界的60Hz姿态。
 * <p>Immutable, bounded 60 Hz poses baked from MOTC curves and the original CMN FK/IK skeleton.</p>
 */
public final class PvBodyMotion {
    private final float[] poses;
    private java.util.function.Supplier<Map<String, Transform>> livePose;
    private final Path binaryPath;
    public final String binarySha256;
    private final BitSet playdataCuts = new BitSet();
    public final int frames, fps;
    public final String source;
    public final boolean sourceWorldCoordinates;
    public final double sourceLegLength;
    public final List<String> names;
    public final Map<String, Transform> bind;

    /** Evaluated scene-rig poses share the existing performer adapters. / 场景人物复用既有角色适配。 */
    public PvBodyMotion(String source, Map<String, Transform> reference,
            java.util.function.Supplier<Map<String, Transform>> sampled) {
        this.source = source;
        this.bind = Map.copyOf(reference);
        this.names = List.copyOf(reference.keySet());
        this.sourceLegLength = reference.get("LeftLeg").position().sub(reference.get("LeftLowerLeg").position()).length()
                + reference.get("LeftLowerLeg").position().sub(reference.get("LeftFoot").position()).length();
        this.sourceWorldCoordinates = true;
        this.frames = 2; this.fps = 60; this.poses = new float[0];
        this.binaryPath = null; this.binarySha256 = ""; this.livePose = sampled;
    }

    public PvBodyMotion(Path directory) throws IOException {
        this(directory.resolve("motion.json"), directory.resolve("motion.bin"));
    }

    public PvBodyMotion(Path metadata, Path binary) throws IOException {
        binaryPath = binary.toAbsolutePath().normalize();
        var json = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
        source = json.has("source") ? json.get("source").getAsString() : "";
        String coordinateProfile =
                json.has("coordinateProfile")
                        ? json.get("coordinateProfile").getAsString()
                        : "legacy";
        if (!Set.of("legacy", "source-world-ysm-2.6.5").contains(coordinateProfile)) {
            throw new IOException("Unsupported native coordinate profile");
        }
        sourceWorldCoordinates = coordinateProfile.equals("source-world-ysm-2.6.5");
        if (source.length() > 256) {
            throw new IOException("Native motion source label outside limits");
        }
        byte[] bytes = dev.flower.stevedance.content.MotionBytes.read(binary);
        binarySha256 = dev.flower.stevedance.content.MotionBytes.sha256(bytes);
        if (json.has("motionSha256")
                && !binarySha256.equals(json.get("motionSha256").getAsString())) {
            throw new IOException("Native motion integrity mismatch");
        }
        if (bytes.length < 20 || bytes.length > 40 * 1024 * 1024) {
            throw new IOException("Native motion size outside limits");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != 0x544f4d44 || buffer.getInt() != 1) {
            throw new IOException("Native motion header mismatch");
        }
        fps = buffer.getInt();
        frames = buffer.getInt();
        int count = buffer.getInt();
        if (fps != 60
                || frames < 2
                || frames > 30000
                || count < 1
                || count > 64
                || bytes.length != 20 + (long) frames * count * 28) {
            throw new IOException("Native motion dimensions outside limits");
        }
        List<String> ids = new ArrayList<>();
        Map<String, Transform> reference = new LinkedHashMap<>();
        for (var value : json.getAsJsonArray("bones")) {
            var b = value.getAsJsonObject();
            String id = b.get("name").getAsString();
            var t = b.getAsJsonObject("bind");
            var p = t.getAsJsonArray("p");
            var q = t.getAsJsonArray("q");
            if (reference.put(
                            id,
                            new Transform(
                                    new Vec(
                                            p.get(0).getAsDouble(),
                                            p.get(1).getAsDouble(),
                                            p.get(2).getAsDouble()),
                                    new Quat(
                                                    q.get(0).getAsDouble(),
                                                    q.get(1).getAsDouble(),
                                                    q.get(2).getAsDouble(),
                                                    q.get(3).getAsDouble())
                                            .unit()))
                    != null) {
                throw new IOException("Duplicate native motion bone");
            }
            ids.add(id);
        }
        if (ids.size() != count) {
            throw new IOException("Native motion skeleton mismatch");
        }
        names = List.copyOf(ids);
        bind = Map.copyOf(reference);
        sourceLegLength = json.get("sourceLegLength").getAsDouble();
        if (!Double.isFinite(sourceLegLength) || sourceLegLength < .1 || sourceLegLength > 3) {
            throw new IOException("Native motion leg length outside limits");
        }
        poses = new float[frames * count * 7];
        buffer.asFloatBuffer().get(poses);
        if (json.has("playdataSegments")) {
            for (var value : json.getAsJsonArray("playdataSegments")) {
                int cut = value.getAsJsonObject().get("offsetFrames").getAsInt();
                if (cut < 0 || cut >= frames) {
                    throw new IOException("Native playdata cut outside motion");
                }
                if (cut > 0) {
                    playdataCuts.set(cut);
                }
            }
        }
        for (float v : poses) {
            if (!Float.isFinite(v)) {
                throw new IOException("Non-finite native motion pose");
            }
        }
        for (int i = 3; i < poses.length; i += 7) {
            double n = 0;
            for (int j = 0; j < 4; j++) {
                n += poses[i + j] * poses[i + j];
            }
            if (Math.abs(n - 1) > .0001) {
                throw new IOException("Invalid native motion orientation");
            }
        }
    }

    /**
     * 将表情与实际加载的字节配对，避免同一身体数据解码两次。
     * <p>Pair faces to the bytes actually loaded, without decoding the same body twice.</p>
     */
    public String originalSha256(Path binary) throws IOException {
        return binaryPath.equals(binary.toAbsolutePath().normalize())
                ? binarySha256
                : dev.flower.stevedance.content.MotionBytes.sha256(
                        dev.flower.stevedance.content.MotionBytes.read(binary));
    }

    public Map<String, Transform> sample(double seconds) {
        if (livePose != null) return livePose.get();
        double f = Math.max(0, Math.min(frames - 1, seconds * fps));
        int a = (int) f, b = Math.min(a + 1, frames - 1);
        double t = f - a;
        Map<String, Transform> result = new LinkedHashMap<>();
        // 中文：playdata切换属于镜头切断，旧演出不与后续分支混合。
        // EN: A playdata switch is a cut, so the old performance never blends into the future
        // branch.
        if (playdataCuts.get(b) && b != a) {
            b = a;
            t = 0;
        }
        for (int j = 0; j < names.size(); j++) {
            int x = (a * names.size() + j) * 7, y = (b * names.size() + j) * 7;
            double[] v = new double[7];
            for (int k = 0; k < 3; k++) {
                v[k] = poses[x + k] + (poses[y + k] - poses[x + k]) * t;
            }
            double d = 0;
            for (int k = 3; k < 7; k++) {
                d += poses[x + k] * poses[y + k];
            }
            double sign = d < 0 ? -1 : 1;
            for (int k = 3; k < 7; k++) {
                v[k] = poses[x + k] * (1 - t) + poses[y + k] * sign * t;
            }
            result.put(
                    names.get(j),
                    new Transform(
                            new Vec(v[0], v[1], v[2]), new Quat(v[3], v[4], v[5], v[6]).unit()));
        }
        return result;
    }

    /**
     * 公共骨架空间反射来源Z轴，YSM原生X镜像共同完成场景Y轴180度变换。
     * <p>Common rig space reflects source Z; YSM's native X mirror completes scene Y180.</p>
     */
    public static Vec position(Vec v) {
        return new Vec(v.x(), v.y(), -v.z());
    }

    public static Quat rotation(Quat q) {
        return new Quat(-q.x(), -q.y(), q.z(), q.w());
    }
}
