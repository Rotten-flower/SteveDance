package dev.flower.stevedance.content;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 启动时安装随模组提供的曲包，只清理有归属记录且未被修改的文件。
 * Installs bundled packs at startup; removal is limited to unchanged, recorded files.
 */
public final class ManagedSongPacks {
    public record Source(Path path, String sha256) {}

    private ManagedSongPacks() {}

    public static void readIndex(Path root, Map<String, Source> desired) throws IOException {
        var index = JsonParser.parseString(Files.readString(root.resolve("index.json"))).getAsJsonObject();
        if (index.get("schema").getAsInt() != 1) {
            throw new IOException("Unsupported bundled pack index");
        }
        for (var row : index.getAsJsonObject("files").entrySet()) {
            String name = row.getKey(), hash = row.getValue().getAsString();
            validate(name, hash);
            if (desired.putIfAbsent(name, new Source(root.resolve(name), hash)) != null) {
                throw new IOException("Duplicate bundled pack: " + name);
            }
        }
    }

    private static void validate(String name, String hash) throws IOException {
        if (!name.matches("stevedance-(?:core|f2)-[a-z0-9_-]+\\.zip")
                || !hash.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid managed pack record");
        }
    }

    public static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static void sync(Path directory, Map<String, Source> desired, Consumer<String> diagnostic)
            throws IOException {
        Files.createDirectories(directory);
        Path state = directory.resolveSibling("managed-packs.json");
        Map<String, String> previous = new LinkedHashMap<>();
        if (Files.isRegularFile(state, LinkOption.NOFOLLOW_LINKS)) {
            try {
                var data = JsonParser.parseString(Files.readString(state)).getAsJsonObject();
                if (data.get("schema").getAsInt() != 1) throw new IOException("Unsupported ownership schema");
                for (var row : data.getAsJsonObject("files").entrySet()) {
                    validate(row.getKey(), row.getValue().getAsString());
                    previous.put(row.getKey(), row.getValue().getAsString());
                }
            } catch (IOException | RuntimeException invalid) {
                previous.clear();
                diagnostic.accept("Cannot read managed pack ownership; existing files are preserved: " + invalid.getMessage());
            }
        }
        Map<String, String> next = new LinkedHashMap<>();
        for (var entry : desired.entrySet()) {
            String name = entry.getKey();
            Source source = entry.getValue();
            validate(name, source.sha256());
            Path target = directory.resolve(name);
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            String current = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) ? sha256(target) : "";
            String old = previous.get(name);
            // 同名玩家文件与已修改文件不接管、不覆盖。 / Never adopt or overwrite player-owned files.
            if (exists && (old == null || !old.equals(current))) {
                diagnostic.accept("Preserved player-owned or modified pack: " + name);
                continue;
            }
            if (exists && source.sha256().equals(current)) {
                next.put(name, current);
                continue;
            }
            Path temporary = Files.createTempFile(directory, "stevedance-install-", ".tmp");
            try {
                Files.copy(source.path(), temporary, StandardCopyOption.REPLACE_EXISTING);
                if (!sha256(temporary).equals(source.sha256())) {
                    throw new IOException("Bundled song pack checksum mismatch: " + name);
                }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                next.put(name, source.sha256());
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        for (var entry : previous.entrySet()) {
            if (desired.containsKey(entry.getKey())) continue;
            Path target = directory.resolve(entry.getKey());
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && sha256(target).equals(entry.getValue())) {
                Files.delete(target);
            } else {
                diagnostic.accept("Preserved modified pack after addon removal: " + entry.getKey());
            }
        }
        Path temporary = Files.createTempFile(directory.getParent(), "stevedance-owned-", ".tmp");
        try {
            Files.writeString(temporary, new Gson().toJson(Map.of("schema", 1, "files", next)));
            Files.move(temporary, state, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
