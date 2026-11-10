package dev.flower.stevedance;

import dev.flower.stevedance.core.RhythmJudgeSession;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 持久世界排行榜；分数从不来自客户端提交的结果。
 * <p>Persistent world leaderboard; scores never come from a client-supplied result.</p>
 */
public final class PlayerScoreStore extends SavedData {
    public record Row(
            UUID player,
            String name,
            int score,
            double achievement,
            String rank,
            int combo,
            long date) {}

    public record Stats(int combo, String rank, long plays, boolean completeCount) {}

    private final ListTag preservedStats = new ListTag();
    private final Map<String, Map<UUID, Stats>> statistics = new HashMap<>();

    public Stats stats(String key, UUID id) {
        Stats s = statistics.getOrDefault(key, Map.of()).get(id);
        Row r = personal(key, id);
        return s != null ? s : r == null ? null : new Stats(r.combo(), r.rank(), 0, false);
    }

    private static int rankOrder(String rank) {
        return List.of("misstake", "cheap", "standard", "great", "excellent", "perfect")
                .indexOf(rank);
    }

    public void started(String key, UUID id) {
        if (readOnly) {
            return;
        }
        Stats old = stats(key, id);
        statistics
                .computeIfAbsent(key, k -> new HashMap<>())
                .put(
                        id,
                        new Stats(
                                old == null ? 0 : old.combo(),
                                old == null ? "misstake" : old.rank(),
                                old == null ? 1 : old.plays() + 1,
                                old == null || old.completeCount()));
        setDirty();
    }

    void recordStats(String key, UUID id, int combo, String rank) {
        if (readOnly) {
            return;
        }
        Stats old = stats(key, id);
        statistics
                .computeIfAbsent(key, k -> new HashMap<>())
                .put(
                        id,
                        new Stats(
                                Math.max(combo, old == null ? 0 : old.combo()),
                                old != null && rankOrder(old.rank()) > rankOrder(rank)
                                        ? old.rank()
                                        : rank,
                                old == null ? 0 : old.plays(),
                                old != null && old.completeCount()));
        setDirty();
    }

    private final Map<String, Map<UUID, Row>> records = new HashMap<>();
    private final ListTag preservedRows = new ListTag();
    private CompoundTag preservedTag;
    private boolean readOnly;

    public static PlayerScoreStore get(MinecraftServer s) {
        return s.overworld()
                .getDataStorage()
                .computeIfAbsent(
                        PlayerScoreStore::load, PlayerScoreStore::new, "stevedance_f2_records");
    }

    public static String key(String difficulty, String digest) {
        return key("two_breaths_f2nd", difficulty, digest);
    }

    public static String key(String song, String difficulty, String digest) {
        return song + "/" + difficulty + "/" + digest + "/rules-10";
    }

    public Row personal(String key, UUID player) {
        return records.getOrDefault(key, Map.of()).get(player);
    }

    public List<Row> top(String key) {
        return records.getOrDefault(key, Map.of()).values().stream()
                .sorted(Comparator.comparingInt(Row::score).reversed().thenComparingLong(Row::date))
                .limit(25)
                .toList();
    }

    public boolean submit(ServerPlayer p, RhythmChartLibrary.Entry e, RhythmJudgeSession.Result r) {
        if (readOnly || !r.finished()) {
            return false;
        }
        String key = key(e.chart().id(), e.chart().difficulty(), e.digest());
        var table = records.computeIfAbsent(key, k -> new HashMap<>());
        Row old = table.get(p.getUUID());
        recordStats(key, p.getUUID(), r.maxCombo(), r.rank(e.chart().clearPercent()));
        if (r.failed()) {
            return false;
        }
        if (old != null && old.score >= r.score()) {
            return false;
        }
        table.put(
                p.getUUID(),
                new Row(
                        p.getUUID(),
                        p.getGameProfile().getName(),
                        r.score(),
                        r.achievement(),
                        r.rank(e.chart().clearPercent()),
                        r.maxCombo(),
                        System.currentTimeMillis()));
        setDirty();
        return true;
    }

    static PlayerScoreStore load(CompoundTag tag) {
        PlayerScoreStore r = new PlayerScoreStore();
        r.preservedTag = tag.copy();
        if (tag.getInt("schema") != 1 && tag.getInt("schema") != 2) {
            r.readOnly = true;
            SteveDanceMod.LOGGER.error(
                    "Unsupported F 2nd record schema {}; saved data is retained in read-only mode",
                    tag.getInt("schema"));
            return r;
        }
        ListTag entries = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag n = entries.getCompound(i);
            try {
                String key = n.getString("key");
                UUID id = n.getUUID("player");
                int score = n.getInt("score");
                if (!key.matches(
                                "[a-z][a-z0-9_]{0,47}/(easy|normal|hard|extreme)/[a-f0-9]{64}(/rules-[0-9]+)?")
                        || score < 0
                        || !Double.isFinite(n.getDouble("achievement"))
                        || n.getDouble("achievement") < 0
                        || n.getDouble("achievement") > 100
                        || n.getInt("combo") < 0) {
                    throw new IllegalArgumentException("Invalid record");
                }
                Row row =
                        new Row(
                                id,
                                n.getString("name"),
                                score,
                                n.getDouble("achievement"),
                                n.getString("rank"),
                                n.getInt("combo"),
                                n.getLong("date"));
                r.records
                        .computeIfAbsent(key, k -> new HashMap<>())
                        .merge(id, row, (a, b) -> a.score() >= b.score() ? a : b);
            } catch (Exception error) {
                SteveDanceMod.LOGGER.error("Invalid F 2nd record row {}", i, error);
                r.preservedRows.add(n.copy());
            }
        }
        ListTag stats = tag.getList("statistics", Tag.TAG_COMPOUND);
        for (int i = 0; i < stats.size(); i++) {
            CompoundTag n = stats.getCompound(i);
            try {
                UUID id = n.getUUID("player");
                String k = n.getString("key");
                if (!k.matches(
                                "[a-z][a-z0-9_]{0,47}/(easy|normal|hard|extreme)/[a-f0-9]{64}(/rules-[0-9]+)?")
                        || n.getInt("combo") < 0
                        || n.getLong("plays") < 0
                        || rankOrder(n.getString("rank")) < 0) {
                    throw new IllegalArgumentException("Invalid statistics");
                }
                r.statistics
                        .computeIfAbsent(k, x -> new HashMap<>())
                        .put(
                                id,
                                new Stats(
                                        n.getInt("combo"),
                                        n.getString("rank"),
                                        n.getLong("plays"),
                                        n.getBoolean("completeCount")));
            } catch (Exception invalid) {
                r.preservedStats.add(n.copy());
            }
        }
        return r;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (readOnly) {
            return preservedTag.copy();
        }
        if (preservedTag != null) {
            tag.merge(preservedTag.copy());
        }
        tag.putInt("schema", 2);
        ListTag list = preservedRows.copy();
        records.forEach(
                (key, table) ->
                        table.values()
                                .forEach(
                                        r -> {
                                            CompoundTag n = new CompoundTag();
                                            n.putString("key", key);
                                            n.putUUID("player", r.player);
                                            n.putString("name", r.name);
                                            n.putInt("score", r.score);
                                            n.putDouble("achievement", r.achievement);
                                            n.putString("rank", r.rank);
                                            n.putInt("combo", r.combo);
                                            n.putLong("date", r.date);
                                            list.add(n);
                                        }));
        tag.put("entries", list);
        ListTag stats = preservedStats.copy();
        statistics.forEach(
                (k, table) ->
                        table.forEach(
                                (id, v) -> {
                                    CompoundTag n = new CompoundTag();
                                    n.putString("key", k);
                                    n.putUUID("player", id);
                                    n.putInt("combo", v.combo());
                                    n.putString("rank", v.rank());
                                    n.putLong("plays", v.plays());
                                    n.putBoolean("completeCount", v.completeCount());
                                    stats.add(n);
                                }));
        tag.put("statistics", stats);
        return tag;
    }
}
