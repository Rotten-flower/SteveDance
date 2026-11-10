package dev.flower.stevedance;

import com.google.gson.Gson;

import dev.flower.stevedance.core.RhythmChart;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RhythmChartLibrary {
    public static final Gson JSON = new Gson();

    public record Entry(RhythmChart chart, String digest) {}

    private record Stamp(Path path, long size, java.nio.file.attribute.FileTime modified) {}

    private static volatile Map<String, Entry> charts = Map.of();
    private static Map<String, Stamp> stamps = Map.of();

    public static Path root() {
        return BundledSongResources.root().resolve("f2nd");
    }

    public static Entry get(String difficulty) {
        return get("two_breaths_f2nd", difficulty);
    }

    public static Entry get(String song, String difficulty) {
        return charts.get(song + "/" + difficulty);
    }

    public static synchronized void reload() {
        Map<String, Entry> next = new LinkedHashMap<>();
        Map<String, Stamp> nextStamps = new HashMap<>();
        for (String song : SongCatalog.ALL.keySet()) {
            for (String d : List.of("easy", "normal", "hard", "extreme")) {
                try {
                    Path path = BundledSongResources.chart(song, d);
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    long size = Files.size(path);
                    if (size > 4 * 1024 * 1024) {
                        throw new java.io.IOException("Chart exceeds budget");
                    }
                    String key = song + "/" + d;
                    Stamp stamp = new Stamp(path, size, Files.getLastModifiedTime(path));
                    if (stamp.equals(stamps.get(key)) && charts.containsKey(key)) {
                        next.put(key, charts.get(key));
                        nextStamps.put(key, stamp);
                        continue;
                    }
                    byte[] bytes = Files.readAllBytes(path);
                    RhythmChart chart =
                            JSON.fromJson(
                                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8),
                                    RhythmChart.class);
                    if (!chart.difficulty().equals(d) || !chart.id().equals(song)) {
                        throw new java.io.IOException("Song or difficulty mismatch");
                    }
                    next.put(
                            key,
                            new Entry(
                                    chart,
                                    HexFormat.of()
                                            .formatHex(
                                                    MessageDigest.getInstance("SHA-256")
                                                            .digest(bytes))));
                    nextStamps.put(key, stamp);
                } catch (Exception error) {
                    SteveDanceMod.LOGGER.error(
                            "Unable to load F 2nd chart {}/{}. Existing valid data is retained.",
                            song,
                            d,
                            error);
                    String key = song + "/" + d;
                    if (charts.containsKey(key)) {
                        next.put(key, charts.get(key));
                    }
                }
            }
        }
        charts = Map.copyOf(next);
        stamps = Map.copyOf(nextStamps);
    }
}
