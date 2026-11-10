package dev.flower.stevedance.core;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * MMD镜头轨道包含目标、欧拉旋转、距离、镜头参数和投影。
 * <p>MMD camera tracks: target, Euler rotation, distance, lens and projection.</p>
 */
public final class VmdCamera {
    public record Shot(
            float x,
            float y,
            float z,
            float rx,
            float ry,
            float rz,
            float distance,
            float fov,
            boolean perspective) {}

    private record Frame(int frame, Shot shot, byte[] curve) {}

    private final Frame[] frames;

    private VmdCamera(Frame[] frames) {
        this.frames = frames;
    }

    public int size() {
        return frames.length;
    }

    public static VmdCamera read(byte[] bytes) throws IOException {
        try {
            if (bytes.length < 62 || bytes.length > 64 * 1024 * 1024) {
                throw new IOException("Invalid camera VMD size");
            }
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] header = new byte[30];
            b.get(header);
            if (!new String(header, StandardCharsets.US_ASCII)
                    .startsWith("Vocaloid Motion Data 0002")) {
                throw new IOException("Camera requires VMD 0002");
            }
            b.position(50);
            skip(b, 111);
            skip(b, 23);
            long count = Integer.toUnsignedLong(b.getInt());
            if (count == 0 || count > 100000 || count * 61 > b.remaining()) {
                throw new IOException("Missing or truncated camera track");
            }
            Frame[] fs = new Frame[(int) count];
            for (int i = 0; i < fs.length; i++) {
                int f = b.getInt();
                float d = b.getFloat(),
                        x = b.getFloat(),
                        y = b.getFloat(),
                        z = b.getFloat(),
                        rx = b.getFloat(),
                        ry = b.getFloat(),
                        rz = b.getFloat();
                byte[] c = new byte[24];
                b.get(c);
                int lens = b.getInt();
                int projection = b.get() & 255;
                if (f < 0
                        || f > 180000
                        || !Float.isFinite(d + x + y + z + rx + ry + rz)
                        || lens < 1
                        || lens > 179
                        || projection > 1) {
                    throw new IOException("Invalid camera keyframe");
                }
                fs[i] = new Frame(f, new Shot(x, y, z, rx, ry, rz, d, lens, projection == 0), c);
            }
            Arrays.sort(fs, Comparator.comparingInt(Frame::frame));
            return new VmdCamera(fs);
        } catch (BufferUnderflowException | IllegalArgumentException ex) {
            throw new IOException("Truncated camera VMD", ex);
        }
    }

    private static void skip(ByteBuffer b, int size) throws IOException {
        long n = Integer.toUnsignedLong(b.getInt());
        if (n > 500000 || n * size > b.remaining()) {
            throw new IOException("Truncated VMD section");
        }
        b.position(b.position() + (int) (n * size));
    }

    public Shot sample(double seconds) {
        double frame = Math.max(0, seconds) * 30;
        if (frame <= frames[0].frame) {
            return frames[0].shot;
        }
        if (frame >= frames[frames.length - 1].frame) {
            return frames[frames.length - 1].shot;
        }
        int lo = 0, hi = frames.length - 1;
        while (lo + 1 < hi) {
            int m = (lo + hi) / 2;
            if (frames[m].frame <= frame) {
                lo = m;
            } else {
                hi = m;
            }
        }
        Frame a = frames[lo], b = frames[hi];
        // 中文：相邻帧表示一次剪切。对其插值会产生单帧甩镜。
        // EN: Adjacent frames represent a cut. Interpolating those would create a one-frame whip
        // pan.
        if (b.frame - a.frame == 1) {
            return a.shot;
        }
        float t = (float) ((frame - a.frame) / (b.frame - a.frame));
        Shot p = a.shot, q = b.shot;
        float r = ease(b.curve, 3, t);
        return new Shot(
                mix(p.x, q.x, ease(b.curve, 0, t)),
                mix(p.y, q.y, ease(b.curve, 1, t)),
                mix(p.z, q.z, ease(b.curve, 2, t)),
                mix(p.rx, q.rx, r),
                mix(p.ry, q.ry, r),
                mix(p.rz, q.rz, r),
                mix(p.distance, q.distance, ease(b.curve, 4, t)),
                mix(p.fov, q.fov, ease(b.curve, 5, t)),
                p.perspective);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float ease(byte[] c, int channel, float x) {
        int k = channel * 4;
        float x1 = (c[k] & 255) / 127f,
                x2 = (c[k + 1] & 255) / 127f,
                y1 = (c[k + 2] & 255) / 127f,
                y2 = (c[k + 3] & 255) / 127f;
        float lo = 0, hi = 1, t = .5f;
        for (int i = 0; i < 14; i++) {
            t = (lo + hi) / 2;
            if (curve(t, x1, x2) < x) {
                lo = t;
            } else {
                hi = t;
            }
        }
        return curve(t, y1, y2);
    }

    private static float curve(float t, float a, float b) {
        float r = 1 - t;
        return 3 * r * r * t * a + 3 * r * t * t * b + t * t * t;
    }
}
