package dev.flower.stevedance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 仅用于 UI 分组：播放、谱面和记录保留其原始标识。
 * <p>Selection metadata and readiness snapshot; identity comes from the shared PV catalog.</p>
 */
public final class SongSelectionCatalog {
    public record Song(
            String id,
            String titleKey,
            int pv,
            int bpm,
            Map<String, Float> stars,
            boolean pvAvailable,
            Set<String> difficulties) {}

    private static volatile List<Song> songs = List.of();

    public static List<Song> songs() {
        return songs;
    }

    public static Song get(String id) {
        return songs.stream().filter(s -> s.id.equals(id)).findFirst().orElse(null);
    }

    /**
     * 外观角色选择，独立于舞台或控制权限进行有界限制。
     * <p>UI grouping only: playback, charts and records retain their authored identities.</p>
     */
    public static String normalId(String id) {
        if (id.endsWith("_success_f2nd")) {
            String normal = id.substring(0, id.length() - "_success_f2nd".length()) + "_f2nd";
            var a = get(normal);
            var b = get(id);
            if (a != null && b != null && a.pv() > 0 && a.pv() == b.pv()) {
                return normal;
            }
        }
        return id;
    }

    private static boolean available(Song song) {
        return song.pvAvailable() || !song.difficulties().isEmpty();
    }

    private static List<Song> family(String id) {
        String normal = normalId(id);
        var base = get(normal);
        if (base == null) {
            return List.of();
        }
        if (normal.endsWith("_f2nd") && !normal.endsWith("_success_f2nd")) {
            String success =
                    normal.substring(0, normal.length() - "_f2nd".length()) + "_success_f2nd";
            var alternate = get(success);
            if (alternate != null && base.pv() > 0 && base.pv() == alternate.pv()) {
                return List.of(base, alternate);
            }
        }
        return List.of(base);
    }

    public static List<Song> performances(String id) {
        return family(id).stream().filter(SongSelectionCatalog::available).toList();
    }

    public static List<Song> selectionSongs() {
        List<Song> rows = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (var song : songs) {
            String normal = normalId(song.id());
            if (!used.add(normal)) {
                continue;
            }
            var family = family(song.id());
            rows.add(
                    family.stream()
                            .filter(SongSelectionCatalog::available)
                            .findFirst()
                            .orElse(song));
        }
        return List.copyOf(rows);
    }

    public static String displayTitleKey(String id) {
        var row = get(normalId(id));
        return row == null ? SongCatalog.get(id).titleKey() : row.titleKey();
    }

    public static void restrictToServer(List<Song> server) {
        Map<String, Song> allowed = new HashMap<>();
        for (var s : server) {
            allowed.put(s.id, s);
        }
        songs =
                songs.stream()
                        .map(
                                s -> {
                                    var remote = allowed.get(s.id);
                                    Set<String> difficulties = new HashSet<>(s.difficulties);
                                    if (remote == null) {
                                        difficulties.clear();
                                    } else {
                                        difficulties.retainAll(remote.difficulties);
                                    }
                                    return new Song(
                                            s.id,
                                            s.titleKey,
                                            s.pv,
                                            s.bpm,
                                            s.stars,
                                            s.pvAvailable && remote != null && remote.pvAvailable,
                                            Set.copyOf(difficulties));
                                })
                        .toList();
    }

    public static synchronized void reload() {
        JsonObject metadata = new JsonObject();
        try {
            Path p = RhythmChartLibrary.root().resolve("catalog.json");
            if (Files.isRegularFile(p) && Files.size(p) < 65536) {
                metadata =
                        JsonParser.parseString(Files.readString(p))
                                .getAsJsonObject()
                                .getAsJsonObject("songs");
            }
        } catch (Exception ex) {
            SteveDanceMod.LOGGER.warn("F 2nd catalog metadata cannot be read", ex);
        }
        List<Song> loaded = new ArrayList<>();
        for (var identity : SongCatalog.ALL.values()) {
            JsonObject meta = metadata.getAsJsonObject(identity.id());
            int pv = meta == null ? 0 : meta.get("pv").getAsInt(),
                    bpm = meta == null ? 0 : meta.get("bpm").getAsInt();
            Map<String, Float> stars = new LinkedHashMap<>();
            Set<String> difficulties = new LinkedHashSet<>();
            for (String d : List.of("easy", "normal", "hard", "extreme")) {
                var chart = RhythmChartLibrary.get(identity.id(), d);
                if (chart != null) {
                    difficulties.add(d);
                    stars.put(d, chart.chart().stars());
                } else if (meta != null && meta.getAsJsonObject("stars").has(d)) {
                    stars.put(d, meta.getAsJsonObject("stars").get(d).getAsFloat());
                }
            }
            var pack = dev.flower.stevedance.content.InstalledSongs.song(identity.id());
            if (pack != null) {
                pv = pack.order();
                bpm = pack.bpm();
            }
            Path nativeRoot = BundledSongResources.nativeSong(identity.id());
            boolean pvReady =
                    List.of("preview.json", "scene.bin", "music.ogg").stream()
                            .allMatch(f -> BundledSongResources.exists(nativeRoot.resolve(f)));
            if (pack == null && !pvReady && difficulties.isEmpty()) {
                continue;
            }
            loaded.add(
                    new Song(
                            identity.id(),
                            identity.titleKey(),
                            pv,
                            bpm,
                            Map.copyOf(stars),
                            pvReady,
                            Set.copyOf(difficulties)));
        }
        loaded.sort(Comparator.comparingInt(Song::pv));
        songs = List.copyOf(loaded);
    }
}
