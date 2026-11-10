package dev.flower.stevedance.client;

import com.google.gson.JsonParser;

import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 原作灯光曲线按W(t)乘以inverse(W0)烘焙，包含静态批次。
 * <p>Original light curves baked as W(t) * inverse(W0), including static batches.</p>
 */
public final class LiveArenaDynamics {
    private static final Map<String, Integer> bindings = new HashMap<>();
    private static float[] samples;
    private static Matrix4f[] current;
    private static int frames;
    private static double last = Double.NaN;
    public static int applied;
    public static boolean enabled = true;

    public static void install(Path root) throws IOException {
        clear();
        if (!Files.isRegularFile(root.resolve("lights.json"))) {
            return;
        }
        var data =
                JsonParser.parseString(Files.readString(root.resolve("lights.json")))
                        .getAsJsonObject();
        byte[] bytes = Files.readAllBytes(root.resolve("light-matrices.bin"));
        var in = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (in.getInt() != 0x4c414c31) {
            throw new IOException("Invalid light matrix header");
        }
        int tracks = in.getInt();
        frames = in.getInt();
        if (tracks < 1
                || tracks > 128
                || frames < 1
                || frames > 36000
                || in.remaining() != (long) tracks * frames * 64) {
            throw new IOException("Invalid light matrix dimensions");
        }
        samples = new float[tracks * frames * 16];
        in.asFloatBuffer().get(samples);
        for (float f : samples) {
            if (!Float.isFinite(f)) {
                throw new IOException("Nonfinite light matrix");
            }
        }
        for (var entry : data.getAsJsonObject("bindings").entrySet()) {
            int i = entry.getValue().getAsInt();
            if (i < 0 || i >= tracks) {
                throw new IOException("Invalid light binding");
            }
            bindings.put(entry.getKey(), i);
        }
        current = new Matrix4f[tracks];
        for (int i = 0; i < tracks; i++) {
            current[i] = new Matrix4f();
        }
    }

    public static void sample(double ms) {
        if (samples == null || last == ms) {
            return;
        }
        last = ms;
        float frame = (float) Math.max(0, Math.min(frames - 1, ms * .06));
        int a = (int) frame, b = Math.min(frames - 1, a + 1);
        float mix = frame - a;
        for (int i = 0; i < current.length; i++) {
            int x = (i * frames + a) * 16, y = (i * frames + b) * 16;
            var m = current[i];
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) {
                    m.set(
                            c,
                            r,
                            samples[x + c * 4 + r]
                                    + (samples[y + c * 4 + r] - samples[x + c * 4 + r]) * mix);
                }
            }
        }
    }

    public static void apply(String geometry, Matrix4f model) {
        if (!enabled) {
            return;
        }
        Integer i = bindings.get(geometry);
        if (i != null) {
            model.mul(current[i]);
            applied++;
        }
    }

    public static void clear() {
        bindings.clear();
        samples = null;
        current = null;
        frames = applied = 0;
        last = Double.NaN;
    }
}
