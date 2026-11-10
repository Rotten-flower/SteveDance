package dev.flower.stevedance.client;

import dev.flower.stevedance.MotionPackLibrary;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * 播放时间来自音频设备的采样位置，与游戏TPS无关。
 * <p>Playback time comes from the audio device's sample position, independent of game TPS.</p>
 */
public final class MotionPackAudioPlayer implements AutoCloseable {
    private final Clip clip;

    private MotionPackAudioPlayer(Clip clip) {
        this.clip = clip;
    }

    public static MotionPackAudioPlayer load(MotionPackLibrary.Pack pack) throws Exception {
        try (InputStream input = pack.audioStream()) {
            if (input == null) {
                throw new IOException("Song audio missing");
            }
            try (AudioInputStream stream =
                    AudioSystem.getAudioInputStream(new BufferedInputStream(input))) {
                AudioFormat format = stream.getFormat();
                if (!format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
                        || format.getChannels() > 2
                        || format.getSampleRate() > 48000
                        || stream.getFrameLength() > 0
                                && stream.getFrameLength() * format.getFrameSize()
                                        > 128 * 1024 * 1024L) {
                    throw new IOException(
                            "Audio must be PCM WAV, up to 48 kHz, 16-bit mono/stereo and 128 MiB");
                }
                if (format.getSampleSizeInBits() != 16) {
                    throw new IOException("Audio must be 16-bit PCM WAV");
                }
                Clip clip = AudioSystem.getClip();
                try {
                    clip.open(stream);
                    return new MotionPackAudioPlayer(clip);
                } catch (Exception ex) {
                    clip.close();
                    throw ex;
                }
            }
        }
    }

    public void start(float volume) {
        if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            gain.setValue(
                    volume <= 0
                            ? gain.getMinimum()
                            : Math.max(
                                    gain.getMinimum(),
                                    Math.min(
                                            gain.getMaximum(), (float) (20 * Math.log10(volume)))));
        }
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
        clip.close();
    }
}
