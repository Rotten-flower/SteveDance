package dev.flower.stevedance.core;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 原曲EXPC/MOTC面部权重，与单个演员的不可变身体数据配对。
 * <p>Authored EXPC/MOTC facial weights, paired to one actor's immutable body data.</p>
 */
public final class PvFaceMotion {
    private static final List<String> CHANNELS =
            List.of(
                    "blink",
                    "winkLeft",
                    "winkRight",
                    "smileEyes",
                    "mouthSmile",
                    "browRaise",
                    "browLower",
                    "sourceEyeWeight",
                    "sourceExpressionId",
                    "sourceExpressionWeight");
    private final float[] values;
    private final BitSet playdataCuts = new BitSet();
    public final int actor, frames;
    public final String motion, sourceSha256;
    public final List<Integer> unsupportedExpressions;

    public PvFaceMotion(Path root, PvBodyMotion body, int expectedActor) throws IOException {
        this(
                root.resolve("faces.json"),
                root.resolve("faces.bin"),
                root.resolve("motion.bin"),
                body,
                expectedActor);
    }

    public PvFaceMotion(
            Path metadata, Path binary, Path bodyBinary, PvBodyMotion body, int expectedActor)
            throws IOException {
        try {

            if (Files.size(metadata) > 1024 * 1024 || Files.size(binary) > 1024 * 1024) {
                throw new IOException("Face data exceeds budget");
            }
            var json = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
            actor = json.get("actor").getAsInt();
            motion = json.get("motion").getAsString();
            sourceSha256 = json.get("sourceSha256").getAsString();
            if (json.get("schema").getAsInt() != 1
                    || actor != expectedActor
                    || !body.source.startsWith(motion + " ")
                    || !sourceSha256.matches("[a-f0-9]{64}")
                    || !"source-preset-aperture-v1".equals(json.get("sampling").getAsString())) {
                throw new IOException("Face actor/source mismatch");
            }
            List<String> channels = new ArrayList<>();
            for (var v : json.getAsJsonArray("channels")) {
                channels.add(v.getAsString());
            }
            if (!channels.equals(CHANNELS)) {
                throw new IOException("Unsupported face channels");
            }
            byte[] bytes = Files.readAllBytes(binary);
            if (!digest(bytes).equals(json.get("binarySha256").getAsString())
                    || !body.originalSha256(bodyBinary)
                            .equals(json.get("bodySha256").getAsString())) {
                throw new IOException("Body/face integrity mismatch");
            }
            var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 20
                    || b.getInt() != 0x43414644
                    || b.getInt() != 1
                    || b.getInt() != 60) {
                throw new IOException("Unsupported face binary");
            }
            frames = b.getInt();
            int count = b.getInt();
            if (frames != body.frames
                    || frames != json.get("frameCount").getAsInt()
                    || count != CHANNELS.size()
                    || bytes.length != 20 + (long) frames * count * 4) {
                throw new IOException("Body/face clock mismatch");
            }
            values = new float[frames * count];
            b.asFloatBuffer().get(values);
            if (json.has("playdataSegments")) {
                for (var value : json.getAsJsonArray("playdataSegments")) {
                    double frame = value.getAsJsonObject().get("startMs").getAsDouble() * .06;
                    int cut = (int) Math.round(frame);
                    if (!Double.isFinite(frame)
                            || Math.abs(frame - cut) > .001
                            || cut < 0
                            || cut >= frames) {
                        throw new IOException("Invalid face playdata cut");
                    }
                    if (cut > 0) {
                        playdataCuts.set(cut);
                    }
                }
            }
            for (int i = 0; i < values.length; i++) {
                float v = values[i];
                int c = i % count;
                if (!Float.isFinite(v) || v < 0 || v > (c == 8 ? 77 : 1)) {
                    throw new IOException("Invalid authored face weight");
                }
            }
            List<Integer> unsupported = new ArrayList<>();
            for (var id : json.getAsJsonArray("unsupportedExpressionIds")) {
                int v = id.getAsInt();
                if (v < 0 || v > 77 || unsupported.size() > 77) {
                    throw new IOException("Invalid unsupported expression");
                }
                unsupported.add(v);
            }
            unsupportedExpressions = List.copyOf(unsupported);
        } catch (JsonParseException
                | IllegalArgumentException
                | IllegalStateException
                | NullPointerException ex) {
            throw new IOException("Invalid face track at " + metadata, ex);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public FacialExpressionRig.Input sample(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("Invalid face sample time");
        }
        double f = Math.min(frames - 1, seconds * 60);
        int a = (int) f, b = Math.min(frames - 1, a + 1);
        float t = (float) (f - a);
        var channels = new LinkedHashMap<String, Float>();
        var evidence = new LinkedHashMap<String, Float>();
        if (playdataCuts.get(b) && b != a) {
            b = a;
            t = 0;
        }
        for (int i = 0; i < CHANNELS.size(); i++) {
            float v = values[a * CHANNELS.size() + i];
            if (i != 8) {
                v += (values[b * CHANNELS.size() + i] - v) * t;
            }
            if (i < 7) {
                channels.put(CHANNELS.get(i), v);
            } else {
                evidence.put(CHANNELS.get(i), v);
            }
        }
        PvExpressionPresets.eyebrows(
                channels,
                Math.round(evidence.get("sourceExpressionId")),
                evidence.get("sourceExpressionWeight"));
        return new FacialExpressionRig.Input(channels, evidence, seconds);
    }
}
