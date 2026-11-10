package dev.flower.stevedance.core;

/**
 * 原始A3DA帧、值、入切线和出切线键，不依赖游戏或渲染器。
 * <p>Source A3DA frame/value/incoming/outgoing keys. No game or renderer dependency.</p>
 */
public final class PvAnimationCurve {
    public int type, pre, post;
    public float value, maximumFrame;
    public float[][] keys;

    public float sample(double frame) {
        if (type == 0) {
            return 0;
        }
        if (type == 1 || keys == null || keys.length == 0) {
            return value;
        }
        float[] first = keys[0], last = keys[keys.length - 1];
        if (keys.length == 1) {
            return first[1];
        }
        double offset = 0, span = last[0] - first[0];
        if (frame < first[0] || frame >= last[0]) {
            boolean before = frame < first[0];
            int mode = before ? pre : post;
            float[] end = before ? first : last;
            if (mode == 0 || span <= 0 || last[0] <= 0 || maximumFrame <= first[0]) {
                return end[1];
            }
            // 中文：DIVA存储的端点斜率使用从各端点向回计算的距离。
            // EN: DIVA's stored endpoint slopes use the distance back from each endpoint.
            if (mode == 1) {
                return (float)
                        (end[1]
                                + (before ? first[0] - frame : last[0] - frame)
                                        * (before ? first[2] : last[3]));
            }
            double distance = before ? first[0] - frame : frame - last[0];
            if (mode == 3) {
                offset = (Math.floor(distance / span) + 1) * (last[1] - first[1]);
            }
            frame = before ? last[0] - distance % span : first[0] + distance % span;
        }
        if (frame >= last[0]) {
            return (float) (last[1] + offset);
        }
        int low = 0, high = keys.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (keys[mid][0] <= frame) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        int index = Math.max(0, Math.min(keys.length - 2, low - 1));
        float[] a = keys[index], b = keys[index + 1];
        double duration = b[0] - a[0];
        if (duration <= 0) {
            return (float) (a[1] + offset);
        }
        double t = (frame - a[0]) / duration;
        if (type == 4) {
            return (float) (a[1] + offset);
        }
        if (type == 2) {
            return (float) (a[1] + (b[1] - a[1]) * t + offset);
        }
        double t2 = t * t, t3 = t2 * t;
        return (float)
                ((2 * t3 - 3 * t2 + 1) * a[1]
                        + (t3 - 2 * t2 + t) * a[3] * duration
                        + (-2 * t3 + 3 * t2) * b[1]
                        + (t3 - t2) * b[2] * duration
                        + offset);
    }

    public void validate() {
        if (type < 0
                || type > 4
                || pre < 0
                || pre > 3
                || post < 0
                || post > 3
                || !Float.isFinite(value)
                || keys != null && keys.length > 100000) {
            throw new IllegalArgumentException("Invalid native curve");
        }
        float previous = -Float.MAX_VALUE;
        if (keys != null) {
            for (float[] k : keys) {
                if (k.length != 4 || k[0] < previous) {
                    throw new IllegalArgumentException("Unordered native curve");
                }
                for (float v : k) {
                    if (!Float.isFinite(v)) {
                        throw new IllegalArgumentException("Nonfinite native curve");
                    }
                }
                previous = k[0];
            }
        }
    }
}
