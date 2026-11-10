package dev.flower.stevedance.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原始 VMD 骨骼、变形和 IK 轨道位于同一 30 Hz 时间线上。
 * <p>Original VMD bone, morph and IK tracks on one 30 Hz timeline.</p>
 */
public final class VmdMotion {
    public record Pose(float x, float y, float z, float qx, float qy, float qz, float qw) {
        public static final Pose IDENTITY = new Pose(0, 0, 0, 0, 0, 0, 1);

        public float[] euler() {
            return new float[] {
                (float) Math.atan2(2 * (qw * qx + qy * qz), 1 - 2 * (qx * qx + qy * qy)),
                (float) Math.asin(Math.max(-1, Math.min(1, 2 * (qw * qy - qz * qx)))),
                (float) Math.atan2(2 * (qw * qz + qx * qy), 1 - 2 * (qy * qy + qz * qz))
            };
        }
    }

    private record Frame(int frame, Pose pose, byte[] interpolation) {}

    private final Map<String, Frame[]> tracks;

    private record MorphFrame(int frame, float weight) {}

    private final Map<String, MorphFrame[]> morphTracks;

    private record IkFrame(int frame, boolean enabled) {}

    private final Map<String, List<IkFrame>> ik;

    private VmdMotion(
            Map<String, Frame[]> tracks,
            Map<String, List<IkFrame>> ik,
            Map<String, MorphFrame[]> morphTracks) {
        this.tracks = tracks;
        this.ik = ik;
        this.morphTracks = morphTracks;
    }

    public Set<String> bones() {
        return Collections.unmodifiableSet(tracks.keySet());
    }

    public Set<String> morphs() {
        return Collections.unmodifiableSet(morphTracks.keySet());
    }

    public float morph(String name, double seconds) {
        var fs = morphTracks.get(name);
        if (fs == null || fs.length == 0) {
            return 0;
        }
        double frame = seconds * 30;
        if (frame < fs[0].frame()) {
            return fs[0].weight();
        }
        int lo = 0, hi = fs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (fs[mid].frame() <= frame) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        int a = lo - 1;
        if (a == fs.length - 1) {
            return fs[a].weight();
        }
        var from = fs[a];
        var to = fs[a + 1];
        return lerp(
                from.weight(),
                to.weight(),
                (float) ((frame - from.frame()) / (to.frame() - from.frame())));
    }

    public boolean ikEnabled(String bone, double seconds) {
        var frames = ik.get(bone);
        if (frames == null) {
            return true;
        }
        boolean enabled = true;
        double frame = seconds * 30;
        for (var f : frames) {
            if (f.frame() > frame) {
                break;
            }
            enabled = f.enabled();
        }
        return enabled;
    }

    public static VmdMotion read(byte[] bytes) throws IOException {
        if (bytes.length < 54 || bytes.length > 64 * 1024 * 1024) {
            throw new IOException("VMD file size outside limits");
        }
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] header = new byte[30];
        b.get(header);
        if (!new String(header, StandardCharsets.US_ASCII).startsWith("Vocaloid Motion Data")) {
            throw new IOException("Invalid VMD header");
        }
        b.position(50);
        long count = Integer.toUnsignedLong(b.getInt());
        if (count > 500000 || count * 111 > b.remaining()) {
            throw new IOException("Truncated or oversized VMD bone tracks");
        }
        Map<String, List<Frame>> work = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            byte[] name = new byte[15];
            b.get(name);
            int end = 0;
            while (end < 15 && name[end] != 0) {
                end++;
            }
            String bone = new String(name, 0, end, Charset.forName("Shift_JIS"));
            int f = b.getInt();
            if (f < 0 || f > 180000) {
                throw new IOException("VMD frame outside limits");
            }
            float x = b.getFloat(),
                    y = b.getFloat(),
                    z = b.getFloat(),
                    qx = b.getFloat(),
                    qy = b.getFloat(),
                    qz = b.getFloat(),
                    qw = b.getFloat();
            if (!Float.isFinite(x + y + z + qx + qy + qz + qw)) {
                throw new IOException("Non-finite VMD transform");
            }
            float norm = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (norm < .00001f) {
                throw new IOException("Invalid VMD quaternion");
            }
            var pose = new Pose(x, y, z, qx / norm, qy / norm, qz / norm, qw / norm);
            byte[] curve = new byte[64];
            b.get(curve);
            work.computeIfAbsent(bone, k -> new ArrayList<>()).add(new Frame(f, pose, curve));
        }
        Map<String, Frame[]> tracks = new LinkedHashMap<>();
        for (var e : work.entrySet()) {
            e.getValue().sort(Comparator.comparingInt(Frame::frame));
            tracks.put(e.getKey(), e.getValue().toArray(Frame[]::new));
        }
        Map<String, List<IkFrame>> ik = new HashMap<>();
        Map<String, MorphFrame[]> morphs = new LinkedHashMap<>();
        if (b.hasRemaining()) {
            if (b.remaining() < 4) {
                throw new IOException("Truncated VMD morph count");
            }
            long size = Integer.toUnsignedLong(b.getInt());
            if (size > 500000 || size * 23 > b.remaining()) {
                throw new IOException("Truncated or oversized VMD morph tracks");
            }
            Map<String, List<MorphFrame>> morphWork = new LinkedHashMap<>();
            for (int i = 0; i < size; i++) {
                byte[] raw = new byte[15];
                b.get(raw);
                int end = 0;
                while (end < 15 && raw[end] != 0) {
                    end++;
                }
                String name = new String(raw, 0, end, Charset.forName("Shift_JIS"));
                int frame = b.getInt();
                float weight = b.getFloat();
                if (frame < 0
                        || frame > 180000
                        || !Float.isFinite(weight)
                        || Math.abs(weight) > 100) {
                    throw new IOException("Invalid VMD morph frame");
                }
                morphWork
                        .computeIfAbsent(name, k -> new ArrayList<>())
                        .add(new MorphFrame(frame, weight));
            }
            for (var entry : morphWork.entrySet()) {
                // 中文：稳定排序使重复帧索引处最后写入的关键帧生效。
                // EN: Stable ordering makes the last authored key win at duplicate frame indices.
                entry.getValue().sort(Comparator.comparingInt(MorphFrame::frame));
                morphs.put(entry.getKey(), entry.getValue().toArray(MorphFrame[]::new));
            }
        }
        // 中文：VMD 允许文件在每个区段后结束。拒绝不完整区段，而不是
        // EN: VMD permits files ending after each section. Reject a partial section instead of
        // 中文：猜测。
        // EN: guessing.
        for (int stride : new int[] {61, 28, 9}) {
            if (!b.hasRemaining()) {
                break;
            }
            if (b.remaining() < 4) {
                throw new IOException("Truncated VMD section");
            }
            long size = Integer.toUnsignedLong(b.getInt());
            if (size > 500000 || size * stride > b.remaining()) {
                throw new IOException("Truncated VMD section");
            }
            b.position(b.position() + (int) (size * stride));
        }
        if (b.hasRemaining()) {
            if (b.remaining() < 4) {
                throw new IOException("Truncated VMD IK section");
            }
            long size = Integer.toUnsignedLong(b.getInt());
            if (size > 180001) {
                throw new IOException("Oversized VMD IK section");
            }
            for (int i = 0; i < size; i++) {
                if (b.remaining() < 9) {
                    throw new IOException("Truncated VMD IK frame");
                }
                int frame = b.getInt();
                b.get();
                long countIk = Integer.toUnsignedLong(b.getInt());
                if (frame < 0 || frame > 180000 || countIk > 512 || countIk * 21 > b.remaining()) {
                    throw new IOException("Invalid VMD IK frame");
                }
                for (int j = 0; j < countIk; j++) {
                    byte[] name = new byte[20];
                    b.get(name);
                    int end = 0;
                    while (end < 20 && name[end] != 0) {
                        end++;
                    }
                    String bone = new String(name, 0, end, Charset.forName("Shift_JIS"));
                    ik.computeIfAbsent(bone, k -> new ArrayList<>())
                            .add(new IkFrame(frame, b.get() != 0));
                }
            }
        }
        Map<String, List<IkFrame>> frozen = new HashMap<>();
        ik.forEach(
                (name, frames) -> {
                    frames.sort(Comparator.comparingInt(IkFrame::frame));
                    frozen.put(name, List.copyOf(frames));
                });
        return new VmdMotion(Map.copyOf(tracks), Map.copyOf(frozen), Map.copyOf(morphs));
    }

    public Pose sample(String bone, double seconds) {
        Frame[] fs = tracks.get(bone);
        if (fs == null || fs.length == 0) {
            return Pose.IDENTITY;
        }
        double frame = seconds * 30;
        if (frame <= fs[0].frame()) {
            return fs[0].pose();
        }
        if (frame >= fs[fs.length - 1].frame()) {
            return fs[fs.length - 1].pose();
        }
        int lo = 0, hi = fs.length - 1;
        while (lo + 1 < hi) {
            int m = (lo + hi) / 2;
            if (fs[m].frame() <= frame) {
                lo = m;
            } else {
                hi = m;
            }
        }
        Frame a = fs[lo], b = fs[hi];
        float t = (float) ((frame - a.frame()) / (b.frame() - a.frame()));
        Pose p = a.pose(), q = b.pose();
        float tx = ease(b.interpolation(), 0, t),
                ty = ease(b.interpolation(), 1, t),
                tz = ease(b.interpolation(), 2, t),
                tr = ease(b.interpolation(), 3, t);
        float dot = p.qx() * q.qx() + p.qy() * q.qy() + p.qz() * q.qz() + p.qw() * q.qw();
        float sign = dot < 0 ? -1 : 1;
        dot = Math.abs(dot);
        float w1, w2;
        if (dot > .9995) {
            w1 = 1 - tr;
            w2 = tr;
        } else {
            double angle = Math.acos(Math.min(1, dot));
            w1 = (float) (Math.sin((1 - tr) * angle) / Math.sin(angle));
            w2 = (float) (Math.sin(tr * angle) / Math.sin(angle));
        }
        float qx = p.qx() * w1 + q.qx() * w2 * sign,
                qy = p.qy() * w1 + q.qy() * w2 * sign,
                qz = p.qz() * w1 + q.qz() * w2 * sign,
                qw = p.qw() * w1 + q.qw() * w2 * sign;
        float n = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        return new Pose(
                lerp(p.x(), q.x(), tx),
                lerp(p.y(), q.y(), ty),
                lerp(p.z(), q.z(), tz),
                qx / n,
                qy / n,
                qz / n,
                qw / n);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float ease(byte[] c, int channel, float x) {
        float x1 = (c[channel] & 255) / 127f,
                y1 = (c[channel + 4] & 255) / 127f,
                x2 = (c[channel + 8] & 255) / 127f,
                y2 = (c[channel + 12] & 255) / 127f;
        float lo = 0, hi = 1, t = .5f;
        for (int i = 0; i < 14; i++) {
            t = (lo + hi) * .5f;
            if (bezier(t, x1, x2) < x) {
                lo = t;
            } else {
                hi = t;
            }
        }
        return bezier(t, y1, y2);
    }

    private static float bezier(float t, float a, float b) {
        float r = 1 - t;
        return 3 * r * r * t * a + 3 * r * t * t * b + t * t * t;
    }
}
