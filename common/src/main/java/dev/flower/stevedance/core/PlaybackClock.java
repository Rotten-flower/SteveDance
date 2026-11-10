package dev.flower.stevedance.core;

/**
 * 不可变演员时钟，所有姿态通道直接采样原生音频快照。
 * <p>Immutable actor clock. Native audio snapshots are sampled directly by all pose channels.</p>
 */
public record PlaybackClock(double seconds, long capturedNanos, boolean playing, long epoch) {
    public PlaybackClock {
        if (!Double.isFinite(seconds) || epoch < 0) {
            throw new IllegalArgumentException("Invalid playback clock");
        }
    }

    public double extrapolate(long now) {
        return seconds + (playing ? Math.max(0, now - capturedNanos) / 1e9 : 0);
    }
}
