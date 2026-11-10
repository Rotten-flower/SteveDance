package dev.flower.stevedance.client;

import com.google.gson.JsonParser;

import dev.flower.stevedance.RhythmChartLibrary;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.PcmEffectMixer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * 一条专用输出线、独立重叠声部和创作的循环边界。
 * <p>One dedicated output line, independent overlapping voices and authored loop boundaries.</p>
 */
public final class RhythmSoundPlayer {
    private record Sample(PcmEffectMixer.Sample pcm, float gain) {}

    private static volatile Map<String, Sample> samples = Map.of();
    private static final PcmEffectMixer mixer = new PcmEffectMixer();
    private static volatile boolean ready, loading, flushRequested;

    public record Diagnostics(
            boolean ready, long triggers, long evictions, long nonSilentFrames, int voices) {}

    public static Diagnostics diagnostics() {
        return new Diagnostics(
                ready,
                mixer.triggers(),
                mixer.evictions(),
                mixer.nonSilentFrames(),
                mixer.voices());
    }

    public static synchronized void prepare() {
        if (ready || loading) {
            return;
        }
        loading = true;
        Thread worker = new Thread(RhythmSoundPlayer::output, "SteveDance effects mixer");
        worker.setDaemon(true);
        worker.start();
    }

    private static void output() {
        SourceDataLine line = null;
        try {
            var root = RhythmChartLibrary.root().resolve("ui/sound");
            var manifest =
                    JsonParser.parseString(Files.readString(root.resolve("samples.json")))
                            .getAsJsonObject();
            if (manifest.get("schema").getAsInt() != 1) {
                throw new IOException("Sound schema mismatch");
            }
            var entries = manifest.getAsJsonObject("samples");
            if (entries.size() > 48) {
                throw new IOException("Sound count budget");
            }
            Map<String, Sample> loaded = new HashMap<>();
            long memory = 0;
            for (var name : entries.keySet()) {
                if (!name.matches(
                        "(?:tap|double|hold_on|hold_off|star|double_star|link)(?:_[2-5])?|rush_on|rush_off|select|cancel|count")) {
                    throw new IOException("Unsafe sound name");
                }
                var meta = entries.getAsJsonObject(name);
                float gain = meta.get("gain").getAsFloat();
                if (!Float.isFinite(gain) || gain < 0 || gain > 2) {
                    throw new IOException("Invalid sample gain");
                }
                String file = meta.get("file").getAsString();
                if (!file.matches("[a-z][a-z0-9_]*\\.wav")) {
                    throw new IOException("Unsafe sound file");
                }
                var pcm =
                        decode(
                                root.resolve(file),
                                meta.get("loopStart").getAsInt(),
                                meta.get("loopEnd").getAsInt());
                memory += (long) pcm.stereo().length * 4;
                if (memory > 32L * 1024 * 1024) {
                    throw new IOException("Sound memory budget");
                }
                loaded.put(name, new Sample(pcm, gain));
            }
            var format = new AudioFormat(PcmEffectMixer.RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, 2048);
            line.start();
            samples = Map.copyOf(loaded);
            ready = true;
            loading = false;
            SteveDanceMod.LOGGER.info(
                    "Note effects mixer ready: {} samples, one output line, {} byte device buffer",
                    samples.size(),
                    line.getBufferSize());
            byte[] output = new byte[256 * 4];
            while (line.isOpen()) {
                if (flushRequested) {
                    flushRequested = false;
                    line.flush();
                }
                mixer.mix(output, 256);
                int written = 0;
                while (written < output.length) {
                    written += line.write(output, written, output.length - written);
                }
            }
        } catch (Exception ex) {
            SteveDanceMod.LOGGER.warn("Original note effects output failed", ex);
        } finally {
            ready = loading = false;
            samples = Map.of();
            mixer.stopAll();
            if (line != null) {
                line.close();
            }
        }
    }

    static PcmEffectMixer.Sample decode(Path file, int loopStart, int loopEnd) throws Exception {
        if (Files.size(file) > 2 * 1024 * 1024) {
            throw new IOException("Sound file budget");
        }
        try (var input =
                AudioSystem.getAudioInputStream(
                        new BufferedInputStream(Files.newInputStream(file)))) {
            var f = input.getFormat();
            int channels = f.getChannels();
            if (f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                    || f.getSampleSizeInBits() != 16
                    || f.isBigEndian()
                    || channels < 1
                    || channels > 2
                    || f.getSampleRate() < 8000
                    || f.getSampleRate() > 96000) {
                throw new IOException("Unsupported note PCM");
            }
            byte[] source = input.readNBytes(2 * 1024 * 1024 + 1);
            if (source.length > 2 * 1024 * 1024 || source.length % f.getFrameSize() != 0) {
                throw new IOException("Incomplete note PCM");
            }
            int sourceFrames = source.length / f.getFrameSize();
            if (sourceFrames == 0
                    || loopStart >= 0 && (loopStart >= loopEnd || loopEnd > sourceFrames)) {
                throw new IOException("Invalid sample loop");
            }
            double ratio = PcmEffectMixer.RATE / (double) f.getSampleRate();
            int frames = (int) Math.round(sourceFrames * ratio);
            float[] stereo = new float[frames * 2];
            for (int frame = 0; frame < frames; frame++) {
                double pos = frame / ratio;
                int a = Math.min(sourceFrames - 1, (int) pos),
                        b = Math.min(sourceFrames - 1, a + 1);
                float t = (float) (pos - a);
                for (int side = 0; side < 2; side++) {
                    int channel = Math.min(side, channels - 1),
                            ai = (a * channels + channel) * 2,
                            bi = (b * channels + channel) * 2;
                    float av = (short) ((source[ai] & 255) | (source[ai + 1] << 8)) / 32768f,
                            bv = (short) ((source[bi] & 255) | (source[bi + 1] << 8)) / 32768f;
                    stereo[frame * 2 + side] = av + (bv - av) * t;
                }
            }
            return new PcmEffectMixer.Sample(
                    stereo,
                    loopStart < 0 ? -1 : (int) Math.round(loopStart * ratio),
                    loopStart < 0 ? -1 : (int) Math.round(loopEnd * ratio));
        }
    }

    private static String resolve(String name) {
        if (name.equals("rush_on") || name.equals("rush_off")) {
            return RhythmSettingsScreen.buttonSound == 0 ? "" : name;
        }
        int choice =
                switch (name) {
                    case "tap", "double", "hold_on", "hold_off" -> RhythmSettingsScreen.buttonSound;
                    case "star", "double_star", "link" -> RhythmSettingsScreen.scratchSound;
                    default -> -1;
                };
        return choice == 0 ? "" : choice > 1 ? name + "_" + choice : name;
    }

    public static void play(String name) {
        if (!ready) {
            return;
        }
        var sample = samples.get(resolve(name));
        if (sample != null) {
            mixer.play(sample.pcm, sample.gain * RhythmSettingsScreen.soundVolume);
        }
    }

    public static void sustain(String token, String name) {
        if (!ready) {
            return;
        }
        var sample = samples.get(resolve(name));
        if (sample != null) {
            mixer.sustain(token, sample.pcm, sample.gain * RhythmSettingsScreen.soundVolume);
        }
    }

    public static void stop(String token) {
        if (token.startsWith("rush:")) {
            mixer.stop(token, PcmEffectMixer.RATE / 5);
        } else {
            mixer.stop(token);
        }
    }

    public static void retain(Set<String> tokens) {
        mixer.retain(tokens);
    }

    public static void stopAll() {
        mixer.stopAll();
        flushRequested = true;
    }
}
