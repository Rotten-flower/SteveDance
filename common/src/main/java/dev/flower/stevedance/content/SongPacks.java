package dev.flower.stevedance.content;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 只读歌曲包挂载，不解压或执行包内文件。
 * <p>Read-only song packs, never extracted or executed.</p>
 */
public final class SongPacks implements AutoCloseable {
    public record Song(
            String id,
            String title,
            int durationMs,
            int bpm,
            int order,
            String author,
            String license,
            Path root) {
        public Path nativeRoot() {
            return root.resolve("native").resolve(id);
        }

        public Path chart(String difficulty) {
            return root.resolve("f2nd/songs").resolve(id).resolve("chart-" + difficulty + ".json");
        }

        public Path thumbnail() {
            return root.resolve("f2nd/thumbnails").resolve(id + ".png");
        }
    }

    private final Map<String, Song> songs = new LinkedHashMap<>();
    private final List<FileSystem> mounts = new ArrayList<>();
    private final Map<Path, Path> aliases = new LinkedHashMap<>();
    private final List<Path> roots = new ArrayList<>();

    public Path resolve(Path logical) {
        return aliases.getOrDefault(logical.normalize(), logical);
    }

    public Path boundary(Path logical) {
        for (Path root : roots) {
            if (logical.getFileSystem() == root.getFileSystem()
                    && logical.normalize().startsWith(root)) {
                return root;
            }
        }
        return null;
    }

    private static Map<Path, Path> readAliases(Path content) throws IOException {
        Path index = content.resolve("resource-aliases.json");
        if (!Files.exists(index)) {
            return Map.of();
        }
        if (Files.size(index) > 16 * 1024 * 1024) {
            throw new IOException("Resource index exceeds budget");
        }
        var json = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
        if (json.get("schema").getAsInt() != 1) {
            throw new IOException("Unsupported resource index");
        }
        var files = json.getAsJsonObject("files");
        if (files.size() > 100000) {
            throw new IOException("Too many resource aliases");
        }
        Map<Path, Path> result = new LinkedHashMap<>();
        for (var entry : files.entrySet()) {
            String key = entry.getKey(), value = entry.getValue().getAsString();
            for (String name : List.of(key, value)) {
                if (name.isBlank()
                        || name.startsWith("/")
                        || name.contains("\\")
                        || name.contains(":")) {
                    throw new IOException("Invalid resource alias");
                }
                for (String part : name.split("/", -1)) {
                    if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                        throw new IOException("Invalid resource alias segment");
                    }
                }
            }
            Path logical = content.resolve(key).normalize(),
                    physical = content.resolve(value).normalize();
            if (logical.equals(physical)
                    || !Files.isRegularFile(physical)
                    || !physical.toRealPath().startsWith(content.toRealPath())) {
                throw new IOException("Invalid resource alias target");
            }
            result.put(logical, physical);
        }
        for (Path target : result.values()) {
            if (result.containsKey(target)) {
                throw new IOException("Chained resource aliases");
            }
        }
        return result;
    }

    public Map<String, Song> songs() {
        return Collections.unmodifiableMap(songs);
    }

    public static SongPacks load(Path directory, Consumer<String> diagnostic) throws IOException {
        Files.createDirectories(directory);
        SongPacks result = new SongPacks();
        try (var files = Files.list(directory)) {
            for (Path file : files.sorted().toList()) {
                if (!Files.isDirectory(file) && !file.getFileName().toString().endsWith(".zip")) {
                    continue;
                }
                FileSystem mount = null;
                try {
                    Path root;
                    if (Files.isDirectory(file)) {
                        root = file.toRealPath();
                    } else {
                        mount = FileSystems.newFileSystem(file, Map.of());
                        root = mount.getPath("/");
                    }
                    Path manifest = root.resolve("stevedance-pack.json");
                    if (!Files.isRegularFile(manifest) || Files.size(manifest) > 65536) {
                        throw new IOException("Missing/oversized stevedance-pack.json");
                    }
                    if (!manifest.toRealPath().startsWith(root.toRealPath())) {
                        throw new IOException("Manifest escapes package");
                    }
                    JsonObject json =
                            JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
                    if (json.get("schema").getAsInt() != 1) {
                        throw new IOException("Unsupported song pack schema");
                    }
                    String author = text(json, "author", 256),
                            license = text(json, "license", 1024);
                    Map<String, Song> pending = new LinkedHashMap<>();
                    for (var value : json.getAsJsonArray("songs")) {
                        JsonObject entry = value.getAsJsonObject();
                        String id = text(entry, "id", 48), title = text(entry, "title", 128);
                        int duration = entry.get("durationMs").getAsInt(),
                                bpm = entry.get("bpm").getAsInt();
                        int order = entry.has("order") ? entry.get("order").getAsInt() : 0;
                        if (!id.matches("[a-z0-9_]{1,48}")
                                || duration < 1000
                                || duration > 600000
                                || bpm < 1
                                || bpm > 1000) {
                            throw new IOException("Invalid song: " + id);
                        }
                        Path content = root.resolve("assets/stevedance/builtin");
                        if (Files.exists(content)
                                && !content.toRealPath().startsWith(root.toRealPath())) {
                            throw new IOException("Content escapes package");
                        }
                        if (!Files.isDirectory(content)) {
                            throw new IOException("Missing assets/stevedance/builtin");
                        }
                        if (songsOutside(content, id)) {
                            throw new IOException("Song paths escape package: " + id);
                        }
                        if (result.songs.containsKey(id)
                                || pending.put(
                                                id,
                                                new Song(
                                                        id, title, duration, bpm, order, author,
                                                        license, content))
                                        != null) {
                            throw new IOException("Duplicate song: " + id);
                        }
                        if (pending.size() + result.songs.size() > 256) {
                            throw new IOException("Song count exceeds 256");
                        }
                    }
                    if (pending.isEmpty()) {
                        throw new IOException("Empty song pack");
                    }
                    Path content = root.resolve("assets/stevedance/builtin");
                    var pendingAliases = readAliases(content);
                    result.songs.putAll(pending);
                    result.aliases.putAll(pendingAliases);
                    result.roots.add(content);
                    if (mount != null) {
                        result.mounts.add(mount);
                        mount = null;
                    }
                } catch (IOException | RuntimeException error) {
                    diagnostic.accept(file.getFileName() + ": " + error.getMessage());
                } finally {
                    if (mount != null) {
                        mount.close();
                    }
                }
            }
        }
        return result;
    }

    private static boolean songsOutside(Path content, String id) throws IOException {
        Path real = content.toRealPath();
        for (Path path :
                List.of(
                        content.resolve("native/" + id),
                        content.resolve("f2nd/songs/" + id),
                        content.resolve("f2nd/thumbnails/" + id + ".png"))) {
            if (Files.exists(path)) {
                if (!path.toRealPath().startsWith(real)) {
                    return true;
                }
                if (Files.isDirectory(path)) {
                    try (var entries = Files.walk(path)) {
                        for (Path entry : entries.toList()) {
                            if (Files.isSymbolicLink(entry)
                                    || !entry.toRealPath().startsWith(real)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static String text(JsonObject json, String field, int max) throws IOException {
        String value = json.get(field).getAsString();
        if (value.isBlank() || value.length() > max) {
            throw new IOException("Invalid " + field);
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (FileSystem mount : mounts) {
            try {
                mount.close();
            } catch (IOException error) {
                failure = error;
            }
        }
        mounts.clear();
        songs.clear();
        aliases.clear();
        roots.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
