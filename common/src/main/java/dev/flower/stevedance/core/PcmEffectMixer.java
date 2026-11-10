package dev.flower.stevedance.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

/**
 * 提供数量受限的立体声PCM声部，触发逻辑不依赖输出设备状态。
 * <p>Bounded stereo PCM voices; triggers are independent of output-device state.</p>
 */
public final class PcmEffectMixer {
    public static final int RATE = 48000, MAX_VOICES = 64;

    public record Sample(float[] stereo, int loopStart, int loopEnd) {
        public Sample {
            if (stereo.length == 0
                    || stereo.length % 2 != 0
                    || loopStart < -1
                    || loopStart >= 0 && (loopStart >= loopEnd || loopEnd > stereo.length / 2)) {
                throw new IllegalArgumentException("Invalid PCM sample");
            }
        }
    }

    private static final class Voice {
        final Sample sample;
        final String token;
        final float gain;
        int position, release = -1, releaseLength = 96;

        Voice(Sample sample, String token, float gain) {
            this.sample = sample;
            this.token = token;
            this.gain = gain;
        }
    }

    private final ArrayList<Voice> voices = new ArrayList<>(MAX_VOICES);
    private final float[] mixed = new float[512];
    private long triggers, evictions, nonSilentFrames;

    public synchronized void play(Sample sample, float gain) {
        add(sample, null, gain);
    }

    public synchronized void sustain(String token, Sample sample, float gain) {
        if (sample.loopStart < 0
                || voices.stream().anyMatch(v -> token.equals(v.token) && v.release < 0)) {
            return;
        }
        add(sample, token, gain);
    }

    private void add(Sample sample, String token, float gain) {
        if (!Float.isFinite(gain) || gain < 0 || gain > 2) {
            throw new IllegalArgumentException("Invalid PCM gain");
        }
        if (voices.size() == MAX_VOICES) {
            int index = -1;
            for (int i = 0; i < voices.size(); i++) {
                if (voices.get(i).token == null || voices.get(i).release >= 0) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                return;
            }
            voices.remove(index);
            evictions++;
        }
        voices.add(new Voice(sample, token, gain));
        triggers++;
    }

    public synchronized void stop(String token) {
        stop(token, 96);
    }

    /**
     * 原作释放包络以输出PCM帧数表示。
     * <p>Authored release envelopes are expressed in output PCM frames.</p>
     */
    public synchronized void stop(String token, int frames) {
        if (frames < 1 || frames > RATE) {
            throw new IllegalArgumentException("Release frame budget");
        }
        for (var v : voices) {
            if (token.equals(v.token) && v.release < 0) {
                v.release = v.releaseLength = frames;
            }
        }
    }

    public synchronized void retain(Set<String> tokens) {
        for (var v : voices) {
            if (v.token != null && !tokens.contains(v.token) && v.release < 0) {
                v.release = 96;
            }
        }
    }

    public synchronized void stopAll() {
        voices.clear();
    }

    public synchronized int voices() {
        return voices.size();
    }

    public synchronized long triggers() {
        return triggers;
    }

    public synchronized long evictions() {
        return evictions;
    }

    public synchronized long nonSilentFrames() {
        return nonSilentFrames;
    }

    /**
     * 每块最多256帧，不在逐块处理时分配对象。
     * <p>Up to 256 frames per block, with no per-block allocations.</p>
     */
    public synchronized void mix(byte[] output, int frames) {
        if (frames < 1 || frames > 256 || output.length < frames * 4) {
            throw new IllegalArgumentException("PCM block budget");
        }
        Arrays.fill(mixed, 0, frames * 2, 0);
        for (int index = voices.size() - 1; index >= 0; index--) {
            Voice v = voices.get(index);
            int length = v.sample.stereo.length / 2;
            for (int frame = 0; frame < frames; frame++) {
                if (v.token != null && v.position >= v.sample.loopEnd) {
                    v.position = v.sample.loopStart;
                }
                if (v.position >= length || v.release == 0) {
                    break;
                }
                float gain = v.gain * (v.release < 0 ? 1 : v.release / (float) v.releaseLength);
                mixed[frame * 2] += v.sample.stereo[v.position * 2] * gain;
                mixed[frame * 2 + 1] += v.sample.stereo[v.position * 2 + 1] * gain;
                v.position++;
                if (v.release > 0) {
                    v.release--;
                }
            }
            if (v.position >= length && v.token == null || v.release == 0) {
                voices.remove(index);
            }
        }
        for (int i = 0; i < frames * 2; i++) {
            float value = mixed[i];
            float magnitude = Math.abs(value);
            // 中文：保留正常瞬态，重叠的大音量效果使用软拐点处理。
            // EN: Preserve normal transients; overlapping loud effects use a soft knee.
            if (magnitude > .9f) {
                value =
                        Math.copySign(
                                .9f + .1f * (1 - (float) Math.exp(-(magnitude - .9f) * 10)), value);
            }
            int pcm = Math.round(value * 32767);
            output[i * 2] = (byte) pcm;
            output[i * 2 + 1] = (byte) (pcm >> 8);
            if ((i & 1) == 0 && (Math.abs(mixed[i]) > 1e-6f || Math.abs(mixed[i + 1]) > 1e-6f)) {
                nonSilentFrames++;
            }
        }
    }
}
