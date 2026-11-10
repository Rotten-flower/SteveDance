package dev.flower.stevedance;

import com.google.gson.Gson;

import dev.flower.stevedance.core.Chart;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MotionPackLibrary {
    public record Manifest(
            int schema,
            String id,
            String titleKey,
            String title,
            String audio,
            String motion,
            String chart,
            String source,
            String license,
            String provenance,
            String camera,
            String stage) {}

    public record Pack(Manifest manifest, Chart chart, Path folder, String error, String digest) {
        public boolean playable() {
            return chart != null && error == null;
        }

        public InputStream audioStream() throws IOException {
            return Files.newInputStream(safe(folder, manifest.audio()));
        }

        public InputStream motionStream() throws IOException {
            return Files.newInputStream(safe(folder, manifest.motion()));
        }

        public InputStream cameraStream() throws IOException {
            return optionalStream(manifest.camera());
        }

        public InputStream stageStream() throws IOException {
            return optionalStream(manifest.stage());
        }

        public InputStream assetStream(String relative) throws IOException {
            return Files.newInputStream(safe(folder, relative));
        }

        private InputStream optionalStream(String name) throws IOException {
            return name == null || name.isBlank() ? null : assetStream(name);
        }
    }

    public static final Gson GSON = new Gson();
    private static volatile Map<String, Pack> packs = Map.of();

    public static Collection<Pack> all() {
        return packs.values();
    }

    public static Pack get(String id) {
        return packs.get(id);
    }

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("stevedance/packs");
    }

    public static void reload() {
        LinkedHashMap<String, Pack> result = new LinkedHashMap<>();
        try {
            Files.createDirectories(root());
            try (var stream = Files.list(root())) {
                for (Path folder : stream.filter(Files::isDirectory).sorted().limit(64).toList()) {
                    Manifest manifest = null;
                    try {
                        if (Files.isSymbolicLink(folder)) {
                            continue;
                        }
                        String manifestJson = readLimited(folder.resolve("pack.json"), 65536);
                        manifest = GSON.fromJson(manifestJson, Manifest.class);
                        if (manifest.schema() != 1 || !manifest.id().matches("[a-z0-9_]{1,48}")) {
                            throw new IOException("Invalid pack header");
                        }
                        String chartJson =
                                readLimited(safe(folder, manifest.chart()), 4 * 1024 * 1024);
                        Chart chart;
                        if (manifest.chart().endsWith(".osu")) {
                            var header =
                                    com.google.gson.JsonParser.parseString(manifestJson)
                                            .getAsJsonObject();
                            chart =
                                    dev.flower.stevedance.content.OsuManiaImport.read(
                                            chartJson,
                                            manifest.id(),
                                            manifest.titleKey(),
                                            header.get("durationMs").getAsInt());
                        } else {
                            chart = GSON.fromJson(chartJson, Chart.class);
                        }
                        if (!chart.id().equals(manifest.id())) {
                            throw new IOException("Chart ID does not match pack ID");
                        }
                        if (!Files.isRegularFile(safe(folder, manifest.audio()))
                                || !Files.isRegularFile(safe(folder, manifest.motion()))) {
                            throw new IOException("Missing audio or VMD");
                        }
                        if (manifest.source() == null || manifest.license() == null) {
                            throw new IOException("Missing source and license information");
                        }
                        result.put(
                                manifest.id(),
                                new Pack(manifest, chart, folder, null, digest(chartJson)));
                    } catch (IOException | RuntimeException ex) {
                        SteveDanceMod.LOGGER.warn(
                                "Invalid song pack {}: {}", folder, ex.toString());
                        if (manifest != null
                                && manifest.id() != null
                                && manifest.id().matches("[a-z0-9_]{1,48}")) {
                            result.put(
                                    manifest.id(),
                                    new Pack(
                                            manifest,
                                            null,
                                            folder,
                                            "ui.stevedance.invalid_pack",
                                            ""));
                        }
                    }
                }
            }
        } catch (IOException ex) {
            SteveDanceMod.LOGGER.warn("Song pack directory unavailable: {}", ex.toString());
        }
        packs = Collections.unmodifiableMap(result);
    }

    private static Path safe(Path folder, String relative) throws IOException {
        if (relative == null) {
            throw new IOException("Missing file path");
        }
        Path path = folder.resolve(relative).normalize();
        if (!path.startsWith(folder) || !path.toRealPath().startsWith(folder.toRealPath())) {
            throw new IOException("Pack path outside folder");
        }
        return path;
    }

    private static String readLimited(Path path, int max) throws IOException {
        if (Files.size(path) > max) {
            throw new IOException("Pack file too large");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
