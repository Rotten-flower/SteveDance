package dev.flower.stevedance.content;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Lossless integer-bit motion blocks. 无损恢复原始 DMOT 字节，不转换浮点值。 */
public final class MotionBytes {
    private static final int LIMIT = 40 * 1024 * 1024;

    private MotionBytes() {}

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] bounded(Path path, int limit) throws IOException {
        if (Files.size(path) > limit) {
            throw new IOException("Motion file exceeds budget");
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new IOException("Motion file exceeds budget");
            }
            return bytes;
        }
    }

    public static byte[] read(Path path) throws IOException {
        byte[] descriptor = bounded(path, LIMIT);
        if (descriptor.length < 20) {
            throw new IOException("Truncated motion header");
        }
        if (descriptor.length >= 8 && new String(descriptor, 0, 8, java.nio.charset.StandardCharsets.US_ASCII).equals("SDCURV01")) return CurveMotion.read(descriptor);
        var in = ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);
        if (in.getInt(0) == 0x544f4d44) {
            return descriptor;
        }
        if (descriptor.length < 68 || in.getInt() != 0x42434d44 || in.getInt() != 1) {
            throw new IOException("Unsupported motion encoding");
        }
        byte[] header = new byte[20];
        in.get(header);
        var h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int magic = h.getInt(),
                version = h.getInt(),
                fps = h.getInt(),
                frames = h.getInt(),
                bones = h.getInt();
        long length = 20 + (long) frames * bones * 28;
        if (magic != 0x544f4d44
                || version != 1
                || fps != 60
                || frames < 2
                || frames > 30000
                || bones < 1
                || bones > 64
                || length > LIMIT) {
            throw new IOException("Motion dimensions outside limits");
        }
        int blockFrames = in.getInt();
        byte[] wholeHash = new byte[32];
        in.get(wholeHash);
        int count = in.getInt();
        if ((blockFrames != 128 && blockFrames != 256 && blockFrames != 512)
                || count != (frames + blockFrames - 1) / blockFrames
                || descriptor.length != 68 + count * 32) {
            throw new IOException("Invalid motion block index");
        }
        byte[] result = new byte[(int) length];
        System.arraycopy(header, 0, result, 0, 20);
        int channels = bones * 7, stride = channels * 4, offset = 20;
        for (int block = 0; block < count; block++) {
            byte[] hash = new byte[32];
            in.get(hash);
            int n = Math.min(blockFrames, frames - block * blockFrames), size = n * stride;
            Path sibling = path.resolveSibling("block-" + HexFormat.of().formatHex(hash) + ".bin");
            if (!sibling.toRealPath().getParent().equals(path.toRealPath().getParent())) {
                throw new IOException("Motion block outside index directory");
            }
            byte[] compressed = bounded(sibling, size + 1024);
            byte[] shuffled = new byte[size + 1];
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(compressed);
                int total = 0;
                while (!inflater.finished() && total < shuffled.length) {
                    int got = inflater.inflate(shuffled, total, shuffled.length - total);
                    if (got == 0 && !inflater.finished()) {
                        throw new IOException("Truncated motion block");
                    }
                    total += got;
                }
                if (total != size || !inflater.finished() || inflater.getRemaining() != 0) {
                    throw new IOException("Motion block length mismatch");
                }
            } catch (DataFormatException ex) {
                throw new IOException("Invalid motion compression", ex);
            } finally {
                inflater.end();
            }
            // 中文：逐字节异或等同于uint32异或，保留有符号零和所有尾数位。
            // EN: Bytewise XOR is exactly uint32 XOR, including signed zero and every mantissa bit.
            for (int channel = 0; channel < channels; channel++) {
                for (int b = 0; b < 4; b++) {
                    int previous = 0, plane = (channel * 4 + b) * n;
                    for (int frame = 0; frame < n; frame++) {
                        previous ^= shuffled[plane + frame] & 255;
                        result[offset + frame * stride + channel * 4 + b] = (byte) previous;
                    }
                }
            }
            if (!sha256(Arrays.copyOfRange(result, offset, offset + size))
                    .equals(HexFormat.of().formatHex(hash))) {
                throw new IOException("Motion block integrity mismatch");
            }
            offset += size;
        }
        if (!sha256(result).equals(HexFormat.of().formatHex(wholeHash))) {
            throw new IOException("Motion integrity mismatch");
        }
        return result;
    }
}
