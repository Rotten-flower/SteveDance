package dev.flower.stevedance;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.imageio.ImageIO;

/**
 * 仅会话期间有效的有界缓存；PNG 数据从不包含本地路径。
 * <p>Bounded vanilla skin input shared by client import and server validation.</p>
 */
public final class SkinImage {
    public static final int MAX_BYTES = 32768;

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] normalize(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Skin must be at most 32 KiB");
        }
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Not a PNG");
            }
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!reader.getFormatName().equalsIgnoreCase("png")
                        || reader.getWidth(0) != 64
                        || (reader.getHeight(0) != 64 && reader.getHeight(0) != 32)) {
                    throw new IOException("Expected 64x64 or 64x32 PNG");
                }
                BufferedImage source = reader.read(0),
                        image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                image.setRGB(
                        0,
                        0,
                        64,
                        source.getHeight(),
                        source.getRGB(0, 0, 64, source.getHeight(), null, 0, 64),
                        0,
                        64);
                if (source.getHeight() == 32) {
                    copy(image, 4, 16, 16, 32, 4, 4, true);
                    copy(image, 8, 16, 16, 32, 4, 4, true);
                    copy(image, 0, 20, 24, 32, 4, 12, true);
                    copy(image, 4, 20, 16, 32, 4, 12, true);
                    copy(image, 8, 20, 8, 32, 4, 12, true);
                    copy(image, 12, 20, 16, 32, 4, 12, true);
                    copy(image, 44, 16, -8, 32, 4, 4, true);
                    copy(image, 48, 16, -8, 32, 4, 4, true);
                    copy(image, 40, 20, 0, 32, 4, 12, true);
                    copy(image, 44, 20, -8, 32, 4, 12, true);
                    copy(image, 48, 20, -16, 32, 4, 12, true);
                    copy(image, 52, 20, -8, 32, 4, 12, true);
                    boolean opaque = true;
                    for (int y = 0; y < 32; y++) {
                        for (int x = 32; x < 64; x++) {
                            if ((image.getRGB(x, y) >>> 24) < 128) {
                                opaque = false;
                            }
                        }
                    }
                    if (opaque) {
                        for (int y = 0; y < 32; y++) {
                            for (int x = 32; x < 64; x++) {
                                image.setRGB(x, y, image.getRGB(x, y) & 0xffffff);
                            }
                        }
                    }
                }
                for (int[] box : new int[][] {{0, 0, 32, 16}, {0, 16, 64, 32}, {16, 48, 48, 64}}) {
                    for (int y = box[1]; y < box[3]; y++) {
                        for (int x = box[0]; x < box[2]; x++) {
                            image.setRGB(x, y, image.getRGB(x, y) | 0xff000000);
                        }
                    }
                }
                var out = new ByteArrayOutputStream();
                ImageIO.write(image, "png", out);
                return out.toByteArray();
            } finally {
                reader.dispose();
            }
        }
    }

    private static void copy(
            BufferedImage im, int x, int y, int dx, int dy, int w, int h, boolean mirror) {
        int[] data = im.getRGB(x, y, w, h, null, 0, w);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                im.setRGB(x + dx + i, y + dy + j, data[j * w + (mirror ? w - 1 - i : i)]);
            }
        }
    }
}
