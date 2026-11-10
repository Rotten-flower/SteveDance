package dev.flower.stevedance.core;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 静态PMX舞台几何，不播放舞台骨骼、形变或刚体动画。
 * <p>Static PMX stage geometry. Stage bones, morphs and rigid bodies are not animated.</p>
 */
public record PmxStage(
        float[] vertices, int[] indices, List<String> textures, List<Material> materials) {
    public record Material(float r, float g, float b, float a, int texture, int start, int count) {}

    public static PmxStage read(byte[] bytes) throws IOException {
        try {
            if (bytes.length > 64 * 1024 * 1024) {
                throw new IOException("PMX stage exceeds 64 MiB");
            }
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt() != 0x20584d50) {
                throw new IOException("Invalid PMX header");
            }
            float version = b.getFloat();
            if (version < 2 || version > 2.11f) {
                throw new IOException("Unsupported PMX version");
            }
            int hs = b.get() & 255;
            if (hs < 8 || hs > 32) {
                throw new IOException("Invalid PMX header size");
            }
            byte[] h = new byte[hs];
            b.get(h);
            Charset encoding =
                    switch (h[0]) {
                        case 0 -> StandardCharsets.UTF_16LE;
                        case 1 -> StandardCharsets.UTF_8;
                        default -> throw new IOException("Invalid PMX encoding");
                    };
            int extra = h[1] & 255, vi = h[2] & 255, ti = h[3] & 255, bi = h[5] & 255;
            if (extra > 4 || !validIndex(vi) || !validIndex(ti) || !validIndex(bi)) {
                throw new IOException("Invalid PMX index sizes");
            }
            for (int i = 0; i < 4; i++) {
                string(b, encoding);
            }
            int vc = count(b, 200000);
            float[] vs = new float[vc * 8];
            for (int v = 0; v < vc; v++) {
                for (int j = 0; j < 8; j++) {
                    float f = b.getFloat();
                    if (!Float.isFinite(f) || Math.abs(f) > 100000) {
                        throw new IOException("Invalid PMX vertex");
                    }
                    vs[v * 8 + j] = f;
                }
                advance(b, extra * 16);
                int skin = b.get() & 255;
                switch (skin) {
                    case 0 -> advance(b, bi);
                    case 1 -> advance(b, bi * 2 + 4);
                    case 2, 4 -> advance(b, bi * 4 + 16);
                    case 3 -> advance(b, bi * 2 + 40);
                    default -> throw new IOException("Invalid PMX skin type");
                }
                advance(b, 4);
            }
            int ic = count(b, 600000);
            if (ic % 3 != 0) {
                throw new IOException("Invalid PMX triangles");
            }
            int[] ids = new int[ic];
            for (int i = 0; i < ic; i++) {
                ids[i] = index(b, vi, true);
                if (ids[i] < 0 || ids[i] >= vc) {
                    throw new IOException("PMX index outside vertices");
                }
            }
            int tc = count(b, 128);
            List<String> textures = new ArrayList<>();
            for (int i = 0; i < tc; i++) {
                textures.add(string(b, encoding));
            }
            int mc = count(b, 256);
            List<Material> materials = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < mc; i++) {
                string(b, encoding);
                string(b, encoding);
                float r = b.getFloat(), g = b.getFloat(), bl = b.getFloat(), a = b.getFloat();
                if (!Float.isFinite(r + g + bl + a)) {
                    throw new IOException("Invalid PMX material");
                }
                advance(b, 12 + 4 + 12 + 1 + 16 + 4);
                int tex = index(b, ti, false);
                advance(b, ti + 1);
                int shared = b.get() & 255;
                if (shared > 1) {
                    throw new IOException("Invalid PMX toon mode");
                }
                advance(b, shared == 1 ? 1 : ti);
                string(b, encoding);
                int faces = count(b, ic);
                if (faces % 3 != 0 || start + faces > ic || tex < -1 || tex >= tc) {
                    throw new IOException("Invalid PMX material range");
                }
                materials.add(new Material(r, g, bl, a, tex, start, faces));
                start += faces;
            }
            if (start != ic) {
                throw new IOException("Incomplete PMX material ranges");
            }
            return new PmxStage(vs, ids, List.copyOf(textures), List.copyOf(materials));
        } catch (BufferUnderflowException | IllegalArgumentException ex) {
            throw new IOException("Truncated PMX stage", ex);
        }
    }

    private static boolean validIndex(int size) {
        return size == 1 || size == 2 || size == 4;
    }

    private static int count(ByteBuffer b, int max) throws IOException {
        int n = b.getInt();
        if (n < 0 || n > max) {
            throw new IOException("PMX count outside limits");
        }
        return n;
    }

    private static void advance(ByteBuffer b, int n) {
        b.position(b.position() + n);
    }

    private static int index(ByteBuffer b, int n, boolean unsigned) {
        return n == 1
                ? (unsigned ? b.get() & 255 : b.get())
                : n == 2 ? (unsigned ? b.getShort() & 65535 : b.getShort()) : b.getInt();
    }

    private static String string(ByteBuffer b, Charset c) throws IOException {
        int n = count(b, 65536);
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, c);
    }
}
