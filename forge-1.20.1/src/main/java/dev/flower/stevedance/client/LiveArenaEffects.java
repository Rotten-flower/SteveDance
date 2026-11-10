package dev.flower.stevedance.client;

import dev.flower.stevedance.MotionPackLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Source-specific adapter; Unity effect values are not DIVA render parameters.
 * 原始效果单独适配，不把 Unity 参数当作 DIVA 参数。 */
public final class LiveArenaEffects {
    public record Bloom(
            float resolutionScale,
            float intensity,
            float threshold,
            float softKnee,
            float diffusion,
            float[] color) {}

    public record Clip(double start, double duration, float[] color) {}

    public record Material(
            String geometry, int batch, String property, String material, Clip[] clips) {}

    public record Data(
            int schema,
            String pipeline,
            Bloom bloom,
            Material[] materials,
            float[][] postSamples) {}

    private record Binding(Clip[] clips, float[] result) {}

    private static PvSceneData scene;
    private static Data data;
    private static double sampled = Double.NaN;
    private static final Map<PvSceneData.Batch, Binding> bindings = new IdentityHashMap<>();
    public static int materialSamples;
    public static boolean enabled = true;
    public static final float[] post = new float[29];

    public static Bloom bloom() {
        return data == null ? null : data.bloom;
    }

    public static boolean installed(PvSceneData value) {
        return value != null && scene == value && data != null;
    }

    public static void install(PvSceneData value) throws IOException {
        clear();
        var root = PvSceneData.root(value.description.id());
        if (!Files.isRegularFile(root.resolve("livearena-effects.json"))) {
            return;
        }
        var file = PvSceneData.safe(root, "livearena-effects.json");
        if (Files.size(file) > 8 * 1024 * 1024) {
            throw new IOException("Live Arena effect data exceeds budget");
        }
        Data parsed;
        try (var reader = Files.newBufferedReader(file)) {
            parsed = MotionPackLibrary.GSON.fromJson(reader, Data.class);
        }
        if (parsed.schema != 1
                || !"unity-ppv2-ldr-adapter".equals(parsed.pipeline)
                || parsed.bloom == null
                || parsed.materials == null
                || parsed.materials.length > 1024) {
            throw new IOException("Invalid Live Arena effect header");
        }
        var b = parsed.bloom;
        for (float f :
                new float[] {
                    b.intensity, b.threshold, b.softKnee, b.diffusion, b.resolutionScale
                }) {
            if (!Float.isFinite(f)) {
                throw new IOException("Nonfinite bloom value");
            }
        }
        if (b.intensity < 0
                || b.intensity > 100
                || b.threshold < 0
                || b.softKnee < 0
                || b.softKnee > 1
                || b.resolutionScale <= 0
                || b.resolutionScale > 1
                || b.diffusion < 1
                || b.diffusion > 10) {
            throw new IOException("Invalid bloom range");
        }
        validColor(b.color);
        if (parsed.postSamples == null
                || parsed.postSamples.length < 2
                || parsed.postSamples.length > 36000) {
            throw new IOException("Invalid source post sample count");
        }
        double lastTime = -1;
        for (var row : parsed.postSamples) {
            if (row == null || row.length != 29 || row[0] <= lastTime) {
                throw new IOException("Invalid source post sample");
            }
            for (float f : row) {
                if (!Float.isFinite(f)) {
                    throw new IOException("Nonfinite source post sample");
                }
            }
            lastTime = row[0];
        }
        for (var m : parsed.materials) {
            var g = value.byName.get(m.geometry);
            if (g == null
                    || m.batch < 0
                    || m.batch >= g.batches.length
                    || m.clips == null
                    || m.clips.length > 2048
                    || !Set.of("_Color", "_BaseColor").contains(m.property)) {
                throw new IOException("Invalid source material binding: " + m.geometry);
            }
            double previous = -1;
            for (var c : m.clips) {
                if (!Double.isFinite(c.start)
                        || !Double.isFinite(c.duration)
                        || c.start < previous
                        || c.duration <= 0) {
                    throw new IOException("Invalid source color interval");
                }
                previous = c.start;
                validColor(c.color);
            }
            if (bindings.put(
                            g.batches[m.batch],
                            new Binding(m.clips, g.batches[m.batch].diffuse.clone()))
                    != null) {
                throw new IOException("Duplicate source color binding");
            }
        }
        data = parsed;
        scene = value;
        LiveArenaWorldEffects.install(value);
    }

    private static void validColor(float[] value) throws IOException {
        if (value == null || value.length != 4) {
            throw new IOException("Invalid effect color");
        }
        for (float v : value) {
            if (!Float.isFinite(v)) {
                throw new IOException("Nonfinite effect color");
            }
        }
    }

    public static void sample(double ms) {
        if (data == null || sampled == ms) {
            return;
        }
        sampled = ms;
        double seconds = ms / 1000;
        var samples = data.postSamples;
        int lo = 0, hi = samples.length - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (samples[mid][0] <= seconds) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        float fraction =
                (float)
                        Math.max(
                                0,
                                Math.min(
                                        1,
                                        (seconds - samples[lo][0])
                                                / (samples[hi][0] - samples[lo][0])));
        for (int i = 0; i < post.length; i++) {
            post[i] = samples[lo][i] + (samples[hi][i] - samples[lo][i]) * fraction;
        }
        post[2] = samples[lo][2];
        for (var entry : bindings.entrySet()) {
            var binding = entry.getValue();
            var clips = binding.clips;
            var out = binding.result;
            Arrays.fill(out, 0);
            double sum = 0;
            for (int i = 0; i < clips.length; i++) {
                var c = clips[i];
                if (seconds < c.start || seconds >= c.start + c.duration) {
                    continue;
                }
                double weight = 1;
                // 中文：展开后的来源区间保留重叠，但不保留混合器曲线。
                // EN: Flattened source intervals retain overlap but not mixer curves.
                // 保留源重叠区间，当前线性权重是明示的适配差异。
                if (i > 0) {
                    var p = clips[i - 1];
                    double end = p.start + p.duration;
                    if (end > c.start && seconds < end) {
                        weight *= Math.min(1, (seconds - c.start) / (end - c.start));
                    }
                }
                if (i + 1 < clips.length) {
                    var n = clips[i + 1];
                    double end = c.start + c.duration;
                    if (n.start < end && seconds >= n.start) {
                        weight *= Math.max(0, (end - seconds) / (end - n.start));
                    }
                }
                sum += weight;
                for (int j = 0; j < 4; j++) {
                    out[j] += c.color[j] * weight;
                }
            }
            if (sum > 0) {
                for (int j = 0; j < 4; j++) {
                    out[j] /= (float) sum;
                }
            } else {
                System.arraycopy(entry.getKey().diffuse, 0, out, 0, 4);
            }
        }
        materialSamples++;
    }

    public static float[] diffuse(PvSceneData.Batch batch) {
        var b = bindings.get(batch);
        return b == null || !enabled ? batch.diffuse : b.result;
    }

    public static void clear() {
        LiveArenaWorldEffects.clear();
        scene = null;
        data = null;
        bindings.clear();
        Arrays.fill(post, 0);
        sampled = Double.NaN;
        materialSamples = 0;
        LiveArenaPostRenderer.clear();
    }
}
