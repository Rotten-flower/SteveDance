package dev.flower.stevedance.content;

import org.tukaani.xz.SingleXZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Exact-byte scene resources, streamed without extraction. 场景资源流式无损读取，不落盘。 */
public final class PackedResources {
    private static final byte[] MAGIC = "SDXZ0001".getBytes(StandardCharsets.US_ASCII);

    private PackedResources() {}

    public static InputStream open(Path path, int limit) throws IOException {
        if (limit < 0) {
            throw new IllegalArgumentException("Negative resource limit");
        }
        long size = Files.size(path);
        if (size > (long) limit + 48) {
            throw new IOException("Resource exceeds byte budget");
        }
        var source =
                new PushbackInputStream(
                        new BufferedInputStream(Files.newInputStream(path), 65536), 8);
        try {
            byte[] magic = source.readNBytes(8);
            if (!Arrays.equals(magic, MAGIC)) {
                if (size > limit) {
                    throw new IOException("Resource exceeds byte budget");
                }
                source.unread(magic);
                return new Checked(source, size, null, null);
            }
            long length = new DataInputStream(source).readLong();
            if (length < 0 || length > limit) {
                throw new IOException("Restored resource exceeds byte budget");
            }
            byte[] hash = source.readNBytes(32);
            if (hash.length != 32) {
                throw new EOFException("Truncated resource checksum");
            }
            return new Checked(new SingleXZInputStream(source, 65536), length, hash, source);
        } catch (IOException | RuntimeException ex) {
            try {
                source.close();
            } catch (IOException close) {
                ex.addSuppressed(close);
            }
            throw ex;
        }
    }

    public static BufferedReader reader(Path path, int limit) throws IOException {
        // 中文：解码器会报告无效UTF-8，与Files.newBufferedReader一致。
        // EN: A decoder reports invalid UTF-8, matching Files.newBufferedReader.
        // 显式使用解码器，不能把损坏的 UTF-8 静默替换为其他字符。
        return new BufferedReader(
                new InputStreamReader(open(path, limit), StandardCharsets.UTF_8.newDecoder()));
    }

    private static final class Checked extends InputStream {
        private final InputStream input, original;
        private final long expected;
        private final byte[] hash;
        private final MessageDigest digest;
        private long count;
        private boolean eof, closed;
        private IOException failure;

        Checked(InputStream input, long expected, byte[] hash, InputStream original) {
            this.input = input;
            this.expected = expected;
            this.hash = hash;
            this.original = original;
            try {
                digest = hash == null ? null : MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private void check() throws IOException {
            if (closed) {
                throw new IOException("Resource stream closed");
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void finish() throws IOException {
            if (eof) {
                return;
            }
            if (count != expected) {
                throw new IOException("Restored resource length mismatch");
            }
            if (hash != null
                    && (!MessageDigest.isEqual(digest.digest(), hash) || original.read() != -1)) {
                throw new IOException("Resource checksum or trailing data mismatch");
            }
            eof = true;
        }

        private void add(int n) throws IOException {
            count += n;
            if (count > expected) {
                throw new IOException("Resource exceeds declared length");
            }
        }

        @Override
        public int read() throws IOException {
            check();
            if (eof) {
                return -1;
            }
            try {
                int b = input.read();
                if (b < 0) {
                    finish();
                } else {
                    add(1);
                    if (digest != null) {
                        digest.update((byte) b);
                    }
                }
                return b;
            } catch (IOException ex) {
                failure = ex;
                throw ex;
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            check();
            if (eof) {
                return -1;
            }
            try {
                int n = input.read(bytes, offset, length);
                if (n < 0) {
                    finish();
                } else {
                    add(n);
                    if (digest != null) {
                        digest.update(bytes, offset, n);
                    }
                }
                return n;
            } catch (IOException ex) {
                failure = ex;
                throw ex;
            }
        }

        @Override
        public long skip(long n) throws IOException {
            if (n <= 0) {
                return 0;
            }
            byte[] buffer = new byte[8192];
            long skipped = 0;
            while (skipped < n) {
                int got = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (got < 0) {
                    break;
                }
                skipped += got;
            }
            return skipped;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            IOException problem = null;
            try {
                // 中文：Gson可能在文件末尾前停止读取，剩余内容仍须经同一校验路径读完。
                // EN: Gson may stop before EOF. Drain through the same checked path.
                // 即使解析器提前结束，也核对完整的原始字节与尾部。
                if (failure == null) {
                    byte[] buffer = new byte[8192];
                    while (read(buffer) != -1) {}
                }
            } catch (IOException ex) {
                problem = ex;
            } finally {
                closed = true;
                try {
                    input.close();
                } catch (IOException ex) {
                    if (problem == null) {
                        problem = ex;
                    } else {
                        problem.addSuppressed(ex);
                    }
                }
            }
            if (problem != null) {
                throw problem;
            }
        }
    }
}
