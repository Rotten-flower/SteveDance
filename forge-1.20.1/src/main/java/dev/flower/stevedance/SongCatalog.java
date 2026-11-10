package dev.flower.stevedance;

import com.google.gson.JsonParser;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 独立资源验证没有游戏目录。
 * <p>Shared song identity. PV packages and charts join by ID, never by menu position. An optional config/stevedance/songs.json extends the bundled catalog at startup. Adding a PV therefore needs data and validation, not a new renderer or screen.</p>
 */
public final class SongCatalog {
    public record Song(String id, String titleKey, int durationMs) {}

    public static final Map<String, Song> ALL = load();

    private static Map<String, Song> load() {
        Map<String, Song> bundled;
        try (var input =
                SongCatalog.class.getResourceAsStream("/assets/stevedance/songs/catalog.json")) {
            if (input == null) {
                throw new IOException("Bundled song catalog is missing");
            }
            bundled = parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Cannot read bundled song catalog", ex);
        }
        Path configDirectory = FMLPaths.CONFIGDIR.get();
        if (configDirectory == null) {
            return bundled;
        } // 中文：选择元数据和就绪快照；标识来自共享的 PV 目录。
        // EN: Standalone asset verification has no game directory.
        Map<String, Song> installed = new LinkedHashMap<>(bundled);
        for (var song : dev.flower.stevedance.content.InstalledSongs.get().songs().values()) {
            installed.put(song.id(), new Song(song.id(), song.title(), song.durationMs()));
        }
        bundled = Collections.unmodifiableMap(installed);
        Path local = configDirectory.resolve("stevedance/songs.json");
        if (Files.isRegularFile(local)) {
            try {
                if (Files.size(local) > 65536) {
                    throw new IOException("Song catalog exceeds budget");
                }
                Map<String, Song> merged = new LinkedHashMap<>(bundled);
                merged.putAll(parse(Files.readString(local)));
                if (merged.size() > 64) {
                    throw new IOException("Too many songs");
                }
                return Collections.unmodifiableMap(merged);
            } catch (IOException | RuntimeException ex) {
                SteveDanceMod.LOGGER.warn(
                        "Cannot load {}. Bundled songs remain available.", local, ex);
            }
        }
        return bundled;
    }

    private static Map<String, Song> parse(String json) {
        var root = JsonParser.parseString(json).getAsJsonObject();
        if (root.get("schema").getAsInt() != 1) {
            throw new IllegalArgumentException("Unsupported song catalog schema");
        }
        Map<String, Song> songs = new LinkedHashMap<>();
        for (var value : root.getAsJsonArray("songs")) {
            var row = value.getAsJsonObject();
            String id = row.get("id").getAsString(), title = row.get("titleKey").getAsString();
            int duration = row.get("durationMs").getAsInt();
            if (!id.matches("[a-z0-9_]{1,48}")
                    || !title.matches("[a-zA-Z0-9_.-]{1,128}")
                    || duration < 1000
                    || duration > 600000) {
                throw new IllegalArgumentException("Invalid song identity: " + id);
            }
            if (songs.put(id, new Song(id, title, duration)) != null || songs.size() > 64) {
                throw new IllegalArgumentException("Duplicate or excessive songs");
            }
        }
        return Collections.unmodifiableMap(songs);
    }

    public static Song get(String id) {
        var song = ALL.get(id);
        if (song == null) {
            throw new IllegalArgumentException("Unknown local PV");
        }
        return song;
    }

    public static int maximumSeekSeconds() {
        return ALL.values().stream().mapToInt(s -> (s.durationMs() - 1) / 1000).max().orElse(0);
    }

    private SongCatalog() {}
}
