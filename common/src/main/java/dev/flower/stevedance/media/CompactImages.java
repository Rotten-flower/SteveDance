package dev.flower.stevedance.media;

import java.io.IOException;
import java.nio.file.Path;

/** RIFF/WebP payloads retain their logical texture names. 纹理名字不改变原动画索引。 */
public final class CompactImages {
    private CompactImages() {}
    public static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[2] == 'F' && bytes[3] == 'F' && bytes[8] == 'W'
                && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }
    public static byte[] decode(byte[] bytes, Path cache) throws IOException {
        MovieNative.prepare(cache.toAbsolutePath().resolve("video-native-sd1"));
        return MovieNative.decodeImage(bytes);
    }
}
