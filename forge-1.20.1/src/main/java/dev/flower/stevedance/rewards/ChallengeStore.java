package dev.flower.stevedance.rewards;

import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 按世界和玩家保存挑战进度及领取记录。 / World-owned progress and claims. */
public final class ChallengeStore extends SavedData {
    private record Key(UUID player, String challenge) {}

    private static final class Progress {
        long day = Long.MIN_VALUE;
        boolean claimed;
        UUID run;
        final Set<String> songs = new HashSet<>();
    }

    private final Map<Key, Progress> entries = new HashMap<>();
    private boolean readOnly;
    private CompoundTag original;
    private final ListTag invalid = new ListTag();

    public static ChallengeStore get(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(
                        ChallengeStore::load, ChallengeStore::new, "stevedance_challenges");
    }

    /**
     * 仅在符合条件的服务端事件后调用，在执行奖励或命令前记录领取。
     * <p>Call only after a qualifying server event; claim before invoking rewards/commands.</p>
     */
    public boolean observe(
            UUID player,
            String challenge,
            ChallengeCondition.Repeat repeat,
            List<String> required,
            String song,
            long day,
            UUID run) {
        if (readOnly) {
            return false;
        }
        Progress p = entries.computeIfAbsent(new Key(player, challenge), k -> new Progress());
        if (repeat == ChallengeCondition.Repeat.DAILY && p.day != day) {
            p.day = day;
            p.claimed = false;
            p.songs.clear();
        }
        if (repeat == ChallengeCondition.Repeat.EVERY_RUN) {
            if (run.equals(p.run)) {
                return false;
            }
            p.run = run;
            p.claimed = false;
            p.songs.clear();
        }
        if (p.claimed) {
            return false;
        }
        p.songs.add(song);
        setDirty();
        if (!required.contains("*") && !p.songs.containsAll(required)) {
            return false;
        }
        p.claimed = true;
        return true;
    }

    public static ChallengeStore load(CompoundTag tag) {
        var store = new ChallengeStore();
        store.original = tag.copy();
        if (tag.getInt("schema") != 1) {
            store.readOnly = true;
            SteveDanceMod.LOGGER.error(
                    "Unsupported challenge save schema {}; rewards disabled, data retained",
                    tag.getInt("schema"));
            return store;
        }
        if (!tag.contains("entries", Tag.TAG_LIST)) {
            store.readOnly = true;
            SteveDanceMod.LOGGER.error(
                    "Missing challenge save entries; rewards disabled, data retained");
            return store;
        }
        var raw = (ListTag) tag.get("entries");
        if (!raw.isEmpty() && raw.getElementType() != Tag.TAG_COMPOUND) {
            store.readOnly = true;
            SteveDanceMod.LOGGER.error(
                    "Invalid challenge save entries; rewards disabled, data retained");
            return store;
        }
        ListTag rows = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < rows.size(); i++) {
            var n = rows.getCompound(i);
            try {
                if (!n.contains("claimed", Tag.TAG_BYTE)
                        || !n.contains("day", Tag.TAG_LONG)
                        || !n.contains("songs", Tag.TAG_LIST)) {
                    throw new IllegalArgumentException("Missing claim fields");
                }
                String id = n.getString("challenge");
                if (!id.matches("[a-z][a-z0-9_.-]{0,63}") || !n.hasUUID("player")) {
                    throw new IllegalArgumentException("Invalid challenge row");
                }
                var p = new Progress();
                p.day = n.getLong("day");
                p.claimed = n.getBoolean("claimed");
                if (n.hasUUID("run")) {
                    p.run = n.getUUID("run");
                }
                var songs = n.getList("songs", Tag.TAG_STRING);
                if (songs.size() > 128) {
                    throw new IllegalArgumentException("Invalid song count");
                }
                for (int j = 0; j < songs.size(); j++) {
                    String song = songs.getString(j);
                    if (!song.matches("[a-z][a-z0-9_]{0,47}")) {
                        throw new IllegalArgumentException("Invalid song");
                    }
                    p.songs.add(song);
                }
                if (store.entries.putIfAbsent(new Key(n.getUUID("player"), id), p) != null) {
                    throw new IllegalArgumentException("Duplicate challenge row");
                }
            } catch (RuntimeException ex) {
                store.invalid.add(n.copy());
                store.readOnly = true;
                SteveDanceMod.LOGGER.error(
                        "Invalid challenge save row {}; rewards disabled, data retained", i, ex);
            }
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (readOnly) {
            return original.copy();
        }
        if (original != null) {
            tag.merge(original.copy());
        }
        tag.putInt("schema", 1);
        ListTag rows = invalid.copy();
        entries.forEach(
                (key, p) -> {
                    var n = new CompoundTag();
                    n.putUUID("player", key.player);
                    n.putString("challenge", key.challenge);
                    n.putLong("day", p.day);
                    n.putBoolean("claimed", p.claimed);
                    if (p.run != null) {
                        n.putUUID("run", p.run);
                    }
                    ListTag songs = new ListTag();
                    p.songs.stream().sorted().forEach(s -> songs.add(StringTag.valueOf(s)));
                    n.put("songs", songs);
                    rows.add(n);
                });
        tag.put("entries", rows);
        return tag;
    }
}
