package dev.flower.stevedance.core;

import java.util.List;

/**
 * 原始DSC目标时间与PSV坐标，不依赖游戏加载器。
 * <p>Original DSC target times and PSV coordinates, independent of the game loader.</p>
 */
public record RhythmChart(
        int schema,
        String id,
        String difficulty,
        float stars,
        int durationMs,
        List<Note> notes,
        List<Zone> zones,
        List<Lyric> lyrics,
        String sourceSha256) {
    public enum Kind {
        TAP,
        DOUBLE,
        HOLD,
        STAR,
        DOUBLE_STAR,
        LINK,
        CHANCE_STAR,
        RUSH
    }

    public record Note(
            int spawnMs,
            int timeMs,
            int endMs,
            int button,
            Kind kind,
            boolean release,
            boolean linkEnd,
            float x,
            float y,
            float angle,
            int frequency,
            float distance,
            float amplitude,
            int flightMs,
            int sourceType,
            int pair,
            int chain,
            int sourceOffset) {}

    public record Zone(String kind, int startMs, int endMs) {}

    public record Lyric(int timeMs, int line) {}

    public RhythmChart {
        if (schema != 1
                || id == null
                || !id.matches("[a-z][a-z0-9_]{0,47}")
                || !List.of("easy", "normal", "hard", "extreme").contains(difficulty)
                || durationMs < 1000
                || durationMs > 600000
                || !Float.isFinite(stars)
                || stars < 0
                || stars > 10
                || notes == null
                || notes.isEmpty()
                || notes.size() > 12000
                || sourceSha256 == null
                || !sourceSha256.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Invalid F 2nd chart header");
        }
        notes = List.copyOf(notes);
        zones = zones == null ? List.of() : List.copyOf(zones);
        lyrics = lyrics == null ? List.of() : List.copyOf(lyrics);
        int last = -1;
        for (int i = 0; i < notes.size(); i++) {
            Note n = notes.get(i);
            if (n == null
                    || n.kind == null
                    || n.spawnMs < 0
                    || n.timeMs < last
                    || n.timeMs > durationMs
                    || n.endMs < n.timeMs
                    || n.endMs > durationMs
                    || n.flightMs < 100
                    || n.flightMs > 10000
                    || Math.abs(n.timeMs - n.spawnMs - n.flightMs) > 2
                    || n.button < 0
                    || n.button > 3
                    || !Float.isFinite(n.x)
                    || !Float.isFinite(n.y)
                    || !Float.isFinite(n.angle)
                    || !Float.isFinite(n.distance)
                    || !Float.isFinite(n.amplitude)
                    || n.x < 0
                    || n.x > 960
                    || n.y < 0
                    || n.y > 544
                    || n.distance < 0
                    || n.distance > 4000
                    || Math.abs(n.amplitude) > 10
                    || Math.abs((long) n.frequency) > 100
                    || n.pair < -1
                    || n.pair >= notes.size()
                    || n.chain < -1
                    || n.chain >= notes.size()) {
                throw new IllegalArgumentException("Invalid F 2nd target " + i);
            }
            if (n.kind == Kind.HOLD) {
                if (n.pair < 0) {
                    throw new IllegalArgumentException("Unpaired hold " + i);
                }
                Note p = notes.get(n.pair);
                if (p == null
                        || p.kind != Kind.HOLD
                        || p.pair != i
                        || p.button != n.button
                        || p.release == n.release
                        || Math.abs(
                                        (n.release ? n.timeMs : n.endMs)
                                                - (p.release ? p.timeMs : p.endMs))
                                > 2) {
                    throw new IllegalArgumentException("Invalid hold pair " + i);
                }
            }
            if (n.kind == Kind.RUSH && (n.release || n.endMs <= n.timeMs || n.pair != -1)) {
                throw new IllegalArgumentException("Invalid rush target " + i);
            }
            last = n.timeMs;
        }
        for (Zone z : zones) {
            if (z == null
                    || !List.of("TECHNICAL", "CHANCE").contains(z.kind)
                    || z.startMs < 0
                    || z.endMs <= z.startMs
                    || z.endMs > durationMs) {
                throw new IllegalArgumentException("Invalid zone");
            }
        }
    }

    public boolean zone(String type, int ms) {
        return zones.stream()
                .anyMatch(z -> z.kind.equalsIgnoreCase(type) && ms >= z.startMs && ms <= z.endMs);
    }

    public int clearPercent() {
        return 80;
    }

    public static int face(int button) {
        return 1 << button;
    }

    public static int direction(int button) {
        return 1 << (button + 4);
    }

    public static final int SCRATCH_LEFT = 256, SCRATCH_RIGHT = 512;
}
