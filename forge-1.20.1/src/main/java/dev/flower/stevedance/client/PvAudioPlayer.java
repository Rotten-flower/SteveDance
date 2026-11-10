package dev.flower.stevedance.client;

import org.lwjgl.stb.STBVorbis;
import org.lwjgl.stb.STBVorbisInfo;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.Clip;

/**
 * 支持单声道、立体声及原曲四轨混音。
 * <p>Mono, stereo and original four-channel mixing.</p>
 */
public final class PvAudioPlayer implements AutoCloseable {
    private static final java.util.concurrent.ExecutorService CLOSER =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "SteveDance audio release");
                        t.setDaemon(true);
                        return t;
                    });
    private final Clip clip;
    public final int channels, rate;
    public final double mixGain;

    private PvAudioPlayer(Clip clip, int channels, int rate, double gain) {
        this.clip = clip;
        this.channels = channels;
        this.rate = rate;
        mixGain = gain;
    }

    public static PvAudioPlayer load() throws Exception {
        return load(PvSceneData.safe(PvSceneData.root(), "music.ogg"));
    }

    public static PvAudioPlayer load(Path path) throws Exception {
        var decoded = decode(path);
        Clip clip = PerformanceAudioBus.openClip();
        try (var stream =
                new AudioInputStream(
                        new ByteArrayInputStream(decoded.pcm()),
                        new AudioFormat(decoded.rate(), 16, 2, true, false),
                        decoded.frames())) {
            try {
                clip.open(stream);
            } catch (Exception ex) {
                clip.close();
                throw ex;
            }
        }
        return new PvAudioPlayer(clip, decoded.channels(), decoded.rate(), decoded.gain());
    }

    public record DecodedAudio(byte[] pcm, int channels, int rate, int frames, double gain) {}

    /**
     * 在单次 PCM16 转换之前混合浮点主干：Vorbis 每主干可超过 +/-1。
     * <p>Mix float stems before the single PCM16 conversion: Vorbis can exceed +/-1 per stem.</p>
     */
    public static DecodedAudio decode(Path path) throws IOException {
        if (Files.size(path) > 32 * 1024 * 1024) {
            throw new IOException("Native audio exceeds budget");
        }
        ByteBuffer encoded = MemoryUtil.memAlloc((int) Files.size(path));
        long decoder = 0;
        FloatBuffer block = MemoryUtil.memAllocFloat(4096 * 4);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            encoded.put(Files.readAllBytes(path)).flip();
            decoder = STBVorbis.stb_vorbis_open_memory(encoded, stack.mallocInt(1), null);
            if (decoder == 0) {
                throw new IOException("Vorbis decoding failed");
            }

            STBVorbisInfo info = STBVorbisInfo.malloc(stack);
            STBVorbis.stb_vorbis_get_info(decoder, info);
            int channels = info.channels();
            int rate = info.sample_rate();
            int frames = STBVorbis.stb_vorbis_stream_length_in_samples(decoder);

            if (channels != 1 && channels != 2 && channels != 4) {
                throw new IOException("Unsupported channel count: " + channels);
            }
            if (rate < 22050 || rate > 96000) {
                throw new IOException("Unsupported sample rate: " + rate);
            }
            if (frames < rate || frames > rate * 600) {
                throw new IOException("Unexpected native audio length");
            }
            if ((long) frames * 4L > 256 * 1024 * 1024) {
                throw new IOException("Decoded audio exceeds memory budget");
            }

            double peak = 1;
            int count, total = 0;
            while ((count =
                            STBVorbis.stb_vorbis_get_samples_float_interleaved(
                                    decoder, channels, block.clear().limit(4096 * channels)))
                    > 0) {
                total += count;
                if (total > frames) {
                    throw new IOException("Decoder exceeded declared length");
                }
                for (int i = 0; i < count; i++) {
                    for (int side = 0; side < 2; side++) {
                        double value = sample(block, i, side, channels);
                        if (!Double.isFinite(value)) {
                            throw new IOException("Invalid music sample");
                        }
                        peak = Math.max(peak, Math.abs(value));
                    }
                }
            }

            if (total != frames || !STBVorbis.stb_vorbis_seek_start(decoder)) {
                throw new IOException("Incomplete music decode");
            }

            double gain = .95 / peak;
            byte[] pcm = new byte[frames * 4];
            int offset = 0;
            while ((count =
                            STBVorbis.stb_vorbis_get_samples_float_interleaved(
                                    decoder, channels, block.clear().limit(4096 * channels)))
                    > 0) {
                if (offset + count > frames) {
                    throw new IOException("Decoder returned too many samples");
                }
                for (int i = 0; i < count; i++) {
                    for (int side = 0; side < 2; side++) {
                        short value =
                                (short) Math.round(sample(block, i, side, channels) * gain * 32767);
                        int p = (offset + i) * 4 + side * 2;
                        pcm[p] = (byte) value;
                        pcm[p + 1] = (byte) (value >> 8);
                    }
                }
                offset += count;
            }

            if (offset != frames) {
                throw new IOException("Music length changed during decode");
            }
            return new DecodedAudio(pcm, channels, rate, frames, gain);
        } finally {
            if (decoder != 0) {
                STBVorbis.stb_vorbis_close(decoder);
            }
            MemoryUtil.memFree(block);
            MemoryUtil.memFree(encoded);
        }
    }

    private static double sample(FloatBuffer block, int frame, int side, int channels) {
        int index = frame * channels + (channels == 1 ? 0 : side);
        return channels == 4 ? (double) block.get(index) + block.get(index + 2) : block.get(index);
    }

    public void start(int offsetMs, float volume) {
        clip.setMicrosecondPosition(offsetMs * 1000L);
        PerformanceAudioBus.musicGain(clip, volume);
        clip.start();
    }

    public void seek(int ms, boolean paused) {
        clip.stop();
        clip.setMicrosecondPosition(Math.max(0, Math.min(ms * 1000L, clip.getMicrosecondLength())));
        if (!paused) {
            clip.start();
        }
    }

    public void pause() {
        clip.stop();
    }

    public void resume() {
        clip.start();
    }

    public int timeMs() {
        return (int) (clip.getMicrosecondPosition() / 1000);
    }

    public int durationMs() {
        return (int) (clip.getMicrosecondLength() / 1000);
    }

    @Override
    public void close() {
        clip.stop();
        CLOSER.execute(clip::close);
    }
}
