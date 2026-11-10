package dev.flower.stevedance.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import dev.flower.stevedance.BundledSongResources;
import dev.flower.stevedance.SongCatalog;
import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 原包歌曲署名，和曲包制作者署名分开。
 * <p>Song credits, distinct from pack authorship.</p>
 */
public final class SongCredits {
    public record Line(String role, String name, Map<String, String> names) {
        public Component text() {
            return Component.translatable(
                    "ui.stevedance.credit." + role, SongText.localized(names, name));
        }
    }

    private static Map<String, List<Line>> songs = Map.of();

    private SongCredits() {}

    public static List<Line> lines(String song) {
        return songs.getOrDefault(song, List.of());
    }

    public static void reload() {
        Map<String, List<Line>> loaded = new HashMap<>();
        try (var input =
                SongCredits.class.getResourceAsStream("/assets/stevedance/songs/credits.json")) {
            if (input == null) {
                throw new java.io.IOException("Song credits resource missing");
            }
            var root =
                    JsonParser.parseString(
                                    new String(
                                            input.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (var entry : root.getAsJsonObject("songs").entrySet()) {
                loaded.put(entry.getKey(), parse(entry.getValue().getAsJsonArray()));
            }
        } catch (Exception error) {
            SteveDanceMod.LOGGER.error("Cannot read original song credits", error);
        }
        for (var song : SongCatalog.ALL.values()) {
            Path file = BundledSongResources.nativeSong(song.id()).resolve("credits.json");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                if (Files.size(file) > 65536) {
                    throw new java.io.IOException("Oversized song credits");
                }
                var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.get("schema").getAsInt() != 1) {
                    throw new java.io.IOException("Unsupported credits schema");
                }
                loaded.put(song.id(), parse(root.getAsJsonArray("lines")));
            } catch (Exception error) {
                SteveDanceMod.LOGGER.warn("Cannot read song credits for {}", song.id(), error);
            }
        }
        songs = Map.copyOf(loaded);
        SongText.reload();
    }

    private static List<Line> parse(JsonArray rows) {
        if (rows.isEmpty() || rows.size() > 4) {
            throw new IllegalArgumentException("Song credits require 1 to 4 lines");
        }
        List<Line> result = new ArrayList<>();
        for (var value : rows) {
            var row = value.getAsJsonObject();
            String role = row.get("role").getAsString(), name = row.get("name").getAsString();
            if (!Set.of(
                                    "lyrics_music",
                                    "lyrics",
                                    "music",
                                    "arrangement",
                                    "creator",
                                    "vocal",
                                    "medley",
                                    "other")
                            .contains(role)
                    || name.isBlank()
                    || name.length() > 512
                    || name.indexOf('\n') >= 0
                    || name.indexOf('§') >= 0) {
                throw new IllegalArgumentException("Invalid song credit");
            }
            Map<String, String> names = new HashMap<>();
            if (row.has("names")) {
                for (var entry : row.getAsJsonObject("names").entrySet()) {
                    names.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            dev.flower.stevedance.content.SongLanguages.validate(names, 512, false);
            result.add(new Line(role, name, Map.copyOf(names)));
        }
        return List.copyOf(result);
    }
}
