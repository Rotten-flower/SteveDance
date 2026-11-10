package dev.flower.stevedance.client;

import com.google.gson.JsonParser;

import dev.flower.stevedance.BundledSongResources;
import dev.flower.stevedance.SongCatalog;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.content.SongLanguages;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 曲包内曲名，与字幕和署名共用游戏语言。
 * <p>Pack-local titles use the game language.</p>
 */
public final class SongText {
    private static Map<String, Map<String, String>> titles = Map.of();
    private static boolean prepared;

    private SongText() {}

    public static String language() {
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

    public static String localized(Map<String, String> texts, String original) {
        return SongLanguages.select(texts, language(), original);
    }

    public static Component title(String key) {
        if (!prepared) reload();
        var texts = titles.get(key);
        return texts == null
                ? Component.translatable(key)
                : Component.literal(localized(texts, key));
    }

    public static void reload() {
        Map<String, Map<String, String>> loaded = new HashMap<>();
        Map<String, Map<String, String>> defaults = new HashMap<>();
        try (var input =
                SongText.class.getResourceAsStream("/assets/stevedance/songs/localization.json")) {
            if (input == null) throw new java.io.IOException("Missing song localization");
            var root =
                    JsonParser.parseString(
                                    new String(
                                            input.readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            for (var entry : root.getAsJsonObject("songs").entrySet()) {
                Map<String, String> names = new HashMap<>();
                for (var text : entry.getValue().getAsJsonObject().entrySet())
                    names.put(text.getKey(), text.getValue().getAsString());
                SongLanguages.validate(names, 256, false);
                defaults.put(entry.getKey(), Map.copyOf(names));
            }
        } catch (Exception error) {
            SteveDanceMod.LOGGER.warn("Cannot read bundled song titles", error);
        }
        for (var song : SongCatalog.ALL.values()) {
            var fallback = defaults.get(song.id());
            if (fallback != null) {
                loaded.put(song.titleKey(), fallback);
                loaded.put(song.id(), fallback);
            }
            var file = BundledSongResources.nativeSong(song.id()).resolve("metadata.json");
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                if (Files.size(file) > 65536) {
                    throw new java.io.IOException("Oversized song metadata");
                }
                var root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.get("schema").getAsInt() != 1) {
                    throw new java.io.IOException("Unsupported song metadata schema");
                }
                Map<String, String> names = new HashMap<>();
                for (var entry : root.getAsJsonObject("titles").entrySet()) {
                    names.put(entry.getKey(), entry.getValue().getAsString());
                }
                SongLanguages.validate(names, 256, false);
                if (names.size() != 3) {
                    throw new java.io.IOException("Missing song title language");
                }
                loaded.put(song.titleKey(), Map.copyOf(names));
                loaded.put(song.id(), Map.copyOf(names));
            } catch (Exception error) {
                SteveDanceMod.LOGGER.warn("Cannot read song titles for {}", song.id(), error);
            }
        }
        titles = Map.copyOf(loaded);
        prepared = true;
    }
}
