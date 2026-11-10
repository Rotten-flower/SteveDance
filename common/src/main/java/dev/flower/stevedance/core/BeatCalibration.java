package dev.flower.stevedance.core;

import java.util.Arrays;

/** F 2nd 三轮视觉校准。 / Three-round visual calibration from F 2nd. */
public final class BeatCalibration {
    public static final int SAMPLE_BEATS = 3, ROUND_MS = 3400, END_MS = 10200;
    private final double[] hits = {Double.NaN, Double.NaN, Double.NaN};

    public void reset() {
        Arrays.fill(hits, Double.NaN);
    }

    public int roundIndex(double totalMs) {
        return Math.max(0, Math.min(2, (int) Math.ceil(totalMs / ROUND_MS) - 1));
    }

    public double roundTime(double totalMs) {
        return totalMs - roundIndex(totalMs) * ROUND_MS;
    }

    public void tap(double totalMs) {
        if (!Double.isFinite(totalMs) || totalMs < 0 || finished(totalMs)) {
            return;
        }
        int round = roundIndex(totalMs);
        double local = roundTime(totalMs);
        if (!recorded(round) && local >= 2850 && local <= 3150) {
            hits[round] = local;
        }
    }

    public boolean recorded(int round) {
        return Double.isFinite(hits[round]);
    }

    public double hitTime(int round) {
        return hits[round];
    }

    public int count() {
        int count = 0;
        for (double hit : hits) {
            if (Double.isFinite(hit)) {
                count++;
            }
        }
        return count;
    }

    public boolean complete() {
        return count() == SAMPLE_BEATS;
    }

    public boolean finished(double totalMs) {
        return Double.isFinite(totalMs) && totalMs > END_MS;
    }

    public double[] offsets() {
        return Arrays.stream(hits).filter(Double::isFinite).map(hit -> hit - 3036).toArray();
    }

    public int recommendation() {
        double mean = Arrays.stream(offsets()).average().orElse(0);
        int original = (int) Math.copySign(Math.floor(Math.abs(mean) + .5), mean);
        // 原作晚按为正，本模组的音频时间加偏移须取反。 / Invert original sign for audioTime + offset.
        return -Math.max(-150, Math.min(150, original));
    }
}
