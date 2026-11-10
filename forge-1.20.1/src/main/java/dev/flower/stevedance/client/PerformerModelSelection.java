package dev.flower.stevedance.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.flower.stevedance.SongCatalog;
import dev.flower.stevedance.SteveDanceMod;
import dev.flower.stevedance.core.PerformerModelChoice;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 记忆的角色默认值、旧版歌曲选择、元数据加载和临时渲染器覆盖。
 * <p>Remembered actor defaults, legacy song choices, metadata loading and temporary renderer overrides.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class PerformerModelSelection {
    public record Slots(List<Integer> actors, String error) {
        public boolean ready() {
            return !actors.isEmpty() || !error.isEmpty();
        }
    }

    public record Credit(String character, String role) {}

    private static final Map<String, Map<Integer, Credit>> CREDITS = new ConcurrentHashMap<>();

    public static Credit credit(String song, int slot) {
        return CREDITS.getOrDefault(song, Map.of())
                .getOrDefault(slot, new Credit("UNKNOWN", "VOCAL"));
    }

    private static final Map<String, Slots> SLOTS = new ConcurrentHashMap<>();
    private static final ExecutorService METADATA =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "SteveDance actor metadata");
                        t.setDaemon(true);
                        return t;
                    });
    private static final Map<String, Map<Integer, PerformerModelChoice>> CHOICES = new HashMap<>();
    private static final Map<Integer, PerformerModelChoice> DEFAULTS = new HashMap<>();
    private static final Set<Integer> DEFAULT_REPEATS = new HashSet<>();
    private static final Map<String, Map<Integer, PerformerModelChoice>> RESOLVED = new HashMap<>();
    private static final Map<Entity, String> ORIGINAL = new WeakHashMap<>();
    private static final Map<UUID, PerformerModelChoice> PREVIEWS = new HashMap<>();
    private static boolean loaded, writeAllowed = true;
    private static net.minecraft.client.multiplayer.ClientLevel level;

    /**
     * 异步从preview的MIKU_DISP事件读取演员槽位；署名来自original-metadata。
     * 当前safe要求署名文件已存在，缺失会进入actors_failed；可选查询契约仍待单独修正。
     * <p>Read slots from preview events; the current safe call also requires the credits file to exist.</p>
     */
    public static Slots slots(String song) {
        return SLOTS.computeIfAbsent(
                song,
                id -> {
                    METADATA.execute(
                            () -> {
                                try {
                                    var root = PvSceneData.root(id);
                                    var path = PvSceneData.safe(root, "preview.json");
                                    if (Files.size(path) > 64 * 1024 * 1024) {
                                        throw new java.io.IOException("Actor metadata too large");
                                    }
                                    JsonObject data;
                                    try (var reader =
                                            dev.flower.stevedance.content.PackedResources.reader(
                                                    path, 64 * 1024 * 1024)) {
                                        data = JsonParser.parseReader(reader).getAsJsonObject();
                                    }
                                    var creditFile =
                                            PvSceneData.safe(root, "original-metadata.json");
                                    if (Files.isRegularFile(creditFile)
                                            && Files.size(creditFile) <= 1024 * 1024) {
                                        try (var reader = Files.newBufferedReader(creditFile)) {
                                            var metadata =
                                                    JsonParser.parseReader(reader)
                                                            .getAsJsonObject();
                                            Map<Integer, Credit> credits = new HashMap<>();
                                            for (int slot = 0; slot < 16; slot++) {
                                                String key = "performer." + slot + ".";
                                                if (metadata.has(key + "chara")) {
                                                    credits.put(
                                                            slot,
                                                            new Credit(
                                                                    metadata.get(key + "chara")
                                                                            .getAsString(),
                                                                    metadata.has(key + "type")
                                                                            ? metadata.get(
                                                                                            key
                                                                                                    + "type")
                                                                                    .getAsString()
                                                                            : "VOCAL"));
                                                }
                                            }
                                            CREDITS.put(id, Map.copyOf(credits));
                                        }
                                    }
                                    Set<Integer> visible = new TreeSet<>();
                                    visible.add(0);
                                    for (var event : data.getAsJsonArray("events")) {
                                        var e = event.getAsJsonObject();
                                        if (!"MIKU_DISP".equals(e.get("command").getAsString())) {
                                            continue;
                                        }
                                        var args = e.getAsJsonArray("args");
                                        if (args.get(1).getAsInt() != 1) {
                                            continue;
                                        }
                                        int actor = args.get(0).getAsInt();
                                        if (actor < 0 || actor >= 16) {
                                            throw new java.io.IOException(
                                                    "Actor slot outside limits");
                                        }
                                        visible.add(actor);
                                    }
                                    SLOTS.put(id, new Slots(List.copyOf(visible), ""));
                                } catch (Exception ex) {
                                    SteveDanceMod.LOGGER.warn(
                                            "Cannot read actor slots for {}", id, ex);
                                    SLOTS.put(
                                            id,
                                            new Slots(
                                                    List.of(),
                                                    "ui.stevedance.model.actors_failed"));
                                }
                            });
                    return new Slots(List.of(), "");
                });
    }

    private static Path file() {
        return Minecraft.getInstance()
                .gameDirectory
                .toPath()
                .resolve("config/stevedance/performer-models.json");
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        var path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            if (Files.size(path) > 256 * 1024) {
                throw new java.io.IOException("Model selection file too large");
            }
            JsonObject data = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            int schema = data.get("schema").getAsInt();
            if (schema != 1 && schema != 2) {
                throw new java.io.IOException("Unsupported model selection schema");
            }
            if (schema == 2 && data.has("defaults")) {
                DEFAULTS.putAll(readActors(data.getAsJsonObject("defaults"), "defaults", path));
            }
            if (schema == 2 && data.has("repeatedActors")) {
                for (var value : data.getAsJsonArray("repeatedActors")) {
                    int actor = value.getAsInt();
                    if (actor >= 0 && actor < 16) {
                        DEFAULT_REPEATS.add(actor);
                    }
                }
            }
            for (var entry : data.getAsJsonObject("songs").entrySet()) {
                if (!SongCatalog.ALL.containsKey(entry.getKey())) {
                    continue;
                }
                CHOICES.put(
                        entry.getKey(),
                        readActors(
                                entry.getValue().getAsJsonObject(),
                                "songs." + entry.getKey(),
                                path));
            }
        } catch (Exception ex) {
            writeAllowed = false;
            SteveDanceMod.LOGGER.warn(
                    "Cannot load model selections at {}; preserving original file", path, ex);
        }
    }

    private static Map<Integer, PerformerModelChoice> readActors(
            JsonObject values, String location, Path path) {
        Map<Integer, PerformerModelChoice> actors = new HashMap<>();
        for (var actor : values.entrySet()) {
            try {
                int slot = Integer.parseInt(actor.getKey());
                if (slot < 0 || slot >= 16) {
                    throw new IllegalArgumentException("Actor outside limits");
                }
                var value = actor.getValue().getAsJsonObject();
                actors.put(
                        slot,
                        new PerformerModelChoice(
                                PerformerModelChoice.Kind.valueOf(value.get("kind").getAsString()),
                                value.has("modelId") ? value.get("modelId").getAsString() : ""));
            } catch (IllegalArgumentException | IllegalStateException | NullPointerException ex) {
                writeAllowed = false;
                SteveDanceMod.LOGGER.warn(
                        "Invalid actor choice at {} {}.{}", path, location, actor.getKey());
            }
        }
        return actors;
    }

    private static JsonObject actorJson(Map<Integer, PerformerModelChoice> actors) {
        var values = new JsonObject();
        for (var actor : new TreeMap<>(actors).entrySet()) {
            var value = new JsonObject();
            value.addProperty("kind", actor.getValue().kind().name());
            value.addProperty("modelId", actor.getValue().modelId());
            values.add(Integer.toString(actor.getKey()), value);
        }
        return values;
    }

    public static PerformerModelChoice choice(String song, int actor) {
        var remote = PvPlaybackController.remoteChoice(actor);
        if (remote != null && song.equals(PvPlaybackController.song())) {
            return remote;
        }
        load();
        var explicit = DEFAULTS.isEmpty()
                ? CHOICES.getOrDefault(song, Map.of()).get(actor) : DEFAULTS.get(actor);
        return explicit == null ? defaultChoice() : explicit;
    }

    /** Installed default model priority. / 已安装默认模型优先级。 */
    private static PerformerModelChoice cachedDefault;
    private static long defaultChecked;

    public static PerformerModelChoice defaultChoice() {
        long now = System.nanoTime();
        if (cachedDefault != null && now - defaultChecked < 1_000_000_000L) return cachedDefault;
        defaultChecked = now;
        return cachedDefault = installedDefault();
    }

    private static PerformerModelChoice installedDefault() {
        if (YsmModelLibraryBridge.modelIds().contains("default")) {
            return new PerformerModelChoice(PerformerModelChoice.Kind.YSM, "default");
        }
        if (dev.flower.stevedance.content.MaidModels.ids().contains("touhou_little_maid:hakurei_reimu")) {
            return new PerformerModelChoice(PerformerModelChoice.Kind.MAID, "touhou_little_maid:hakurei_reimu");
        }
        return new PerformerModelChoice(PerformerModelChoice.Kind.STEVE, "");
    }

    private static boolean sameModel(PerformerModelChoice a, PerformerModelChoice b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.kind() == PerformerModelChoice.Kind.YSM
                || b.kind() == PerformerModelChoice.Kind.YSM
                || a.kind() == PerformerModelChoice.Kind.MAID
                || b.kind() == PerformerModelChoice.Kind.MAID) {
            return a.equals(b);
        }
        return skin(a).equals(skin(b));
    }

    private static boolean containsModel(
            Collection<PerformerModelChoice> choices, PerformerModelChoice candidate) {
        return choices.stream().anyMatch(c -> sameModel(c, candidate));
    }

    public static void libraryChanged() {
        cachedDefault = null;
        RESOLVED.clear();
    }

    public static boolean save(String song, Map<Integer, PerformerModelChoice> actors) {
        return save(song, actors, actors.keySet());
    }

    public static boolean save(
            String song, Map<Integer, PerformerModelChoice> actors, Set<Integer> explicitActors) {
        if (!SongCatalog.ALL.containsKey(song)
                || actors.isEmpty()
                || actors.entrySet().stream()
                        .anyMatch(
                                e ->
                                        e.getKey() == null
                                                || e.getKey() < 0
                                                || e.getKey() >= 16
                                                || e.getValue() == null)) {
            return false;
        }
        load();
        var updated = new HashMap<>(CHOICES);
        updated.put(song, new HashMap<>(actors));
        var path = file();
        Set<Integer> repeats = new TreeSet<>();
        var previous = new TreeMap<Integer, PerformerModelChoice>();
        for (var entry : new TreeMap<>(actors).entrySet()) {
            if (previous.entrySet().stream()
                    .anyMatch(
                            p ->
                                    sameModel(p.getValue(), entry.getValue())
                                            && (explicitActors.contains(entry.getKey())
                                                    || explicitActors.contains(p.getKey())
                                                    || DEFAULT_REPEATS.contains(entry.getKey())
                                                            && Objects.equals(
                                                                    DEFAULTS.get(entry.getKey()),
                                                                    entry.getValue())))) {
                repeats.add(entry.getKey());
            }
            previous.put(entry.getKey(), entry.getValue());
        }
        try {
            Files.createDirectories(path.getParent());
            if (!writeAllowed && Files.exists(path)) {
                Files.copy(
                        path,
                        path.resolveSibling(
                                "performer-models.backup-" + System.currentTimeMillis() + ".json"));
                writeAllowed = true;
            }
            var data = new JsonObject();
            data.addProperty("schema", 2);
            data.add("defaults", actorJson(actors));
            var repeated = new JsonArray();
            repeats.forEach(repeated::add);
            data.add("repeatedActors", repeated);
            var songs = new JsonObject();
            for (var entry : updated.entrySet()) {
                songs.add(entry.getKey(), actorJson(entry.getValue()));
            }
            data.add("songs", songs);
            var temp = path.resolveSibling("performer-models.json.tmp");
            Files.writeString(
                    temp, new GsonBuilder().setPrettyPrinting().create().toJson(data) + "\n");
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            CHOICES.clear();
            CHOICES.putAll(updated);
            DEFAULTS.clear();
            DEFAULTS.putAll(actors);
            DEFAULT_REPEATS.clear();
            DEFAULT_REPEATS.addAll(repeats);
            RESOLVED.clear();
            return true;
        } catch (java.io.IOException ex) {
            SteveDanceMod.LOGGER.warn("Cannot save model selections at {}", path, ex);
            return false;
        }
    }

    public static PerformerModelChoice renderingChoice(Entity entity) {
        var preview = PREVIEWS.get(entity.getUUID());
        if (preview != null) {
            return preview;
        }

        if (PvPlaybackController.active()) {
            int actor = PvPlaybackController.actor(entity.getUUID());
            if (actor >= 0) {
                return choice(PvPlaybackController.song(), actor);
            }
        }
        return null;
    }

    public static boolean vanilla(Entity actor) {
        var c = renderingChoice(actor);
        return c != null && c.kind() != PerformerModelChoice.Kind.YSM;
    }

    public static ResourceLocation skin(PerformerModelChoice choice) {
        return skin(choice, null);
    }

    private static java.util.UUID skinOwner(Entity actor) {
        var mc = Minecraft.getInstance();
        return actor != null
                        && PvPlaybackController.active()
                        && PvPlaybackController.actor(actor.getUUID()) >= 0
                ? PvPlaybackController.hostId()
                : mc.player == null ? null : mc.player.getUUID();
    }

    public static ResourceLocation skin(PerformerModelChoice choice, Entity actor) {
        if (choice.kind() == PerformerModelChoice.Kind.SKIN) {
            return dev.flower.stevedance.client.VanillaSkins.texture(choice.modelId());
        }
        if (choice.kind() == PerformerModelChoice.Kind.STEVE) {
            return new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
        }
        if (choice.kind() == PerformerModelChoice.Kind.ALEX) {
            return new ResourceLocation("minecraft", "textures/entity/player/slim/alex.png");
        }
        var mc = Minecraft.getInstance();
        var owner =
                choice.kind() == PerformerModelChoice.Kind.ONLINE_PLAYER
                        ? java.util.UUID.fromString(choice.modelId())
                        : skinOwner(actor);
        var info =
                mc.getConnection() == null || owner == null
                        ? null
                        : mc.getConnection().getPlayerInfo(owner);
        return info == null
                ? new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png")
                : info.getSkinLocation();
    }

    public static String skinType(PerformerModelChoice choice) {
        return skinType(choice, null);
    }

    public static String skinType(PerformerModelChoice choice, Entity actor) {
        if (choice.kind() == PerformerModelChoice.Kind.SKIN) {
            return choice.modelId().endsWith(":slim") ? "slim" : "default";
        }
        if (choice.kind() == PerformerModelChoice.Kind.STEVE) {
            return "default";
        }
        if (choice.kind() == PerformerModelChoice.Kind.ALEX) {
            return "slim";
        }
        var mc = Minecraft.getInstance();
        var owner =
                choice.kind() == PerformerModelChoice.Kind.ONLINE_PLAYER
                        ? java.util.UUID.fromString(choice.modelId())
                        : skinOwner(actor);
        var info =
                mc.getConnection() == null || owner == null
                        ? null
                        : mc.getConnection().getPlayerInfo(owner);
        return info == null ? "default" : info.getModelName();
    }

    public static void preview(Entity actor, PerformerModelChoice choice) {
        if (choice.kind() == PerformerModelChoice.Kind.YSM) {
            ORIGINAL.computeIfAbsent(actor, YsmModelLibraryBridge::current);
        }
        PREVIEWS.put(actor.getUUID(), choice);
        if (choice.kind() == PerformerModelChoice.Kind.YSM) {
            YsmModelLibraryBridge.apply(actor, choice.modelId());
        }
    }

    static void clearWorldPreview(Entity actor) {
        PREVIEWS.remove(actor.getUUID());
        if (ORIGINAL.containsKey(actor)) {
            YsmModelLibraryBridge.apply(actor, ORIGINAL.remove(actor));
        }
        YsmAnimationAdapter.releaseActor(actor.getUUID());
    }

    public static void releasePreview(Entity actor) {
        PREVIEWS.remove(actor.getUUID());
        YsmAnimationAdapter.releaseActor(actor.getUUID());
        actor.discard();
    }

    @SubscribeEvent
    public static void previewName(net.minecraftforge.client.event.RenderNameTagEvent event) {
        if (PREVIEWS.containsKey(event.getEntity().getUUID())) {
            event.setResult(net.minecraftforge.eventbus.api.Event.Result.DENY);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (level != mc.level) {
            restoreAll();
            PREVIEWS.clear();
            level = mc.level;
        }
        if (mc.player == null) {
            return;
        }
        var selected = renderingChoice(mc.player);
        if (selected != null && selected.kind() == PerformerModelChoice.Kind.YSM) {
            ORIGINAL.computeIfAbsent(mc.player, YsmModelLibraryBridge::current);
            YsmModelLibraryBridge.apply(mc.player, selected.modelId());
        } else if (ORIGINAL.containsKey(mc.player)) {
            String id = ORIGINAL.remove(mc.player);
            YsmModelLibraryBridge.apply(mc.player, id);
        }
        if (PvPlaybackController.active()) {
            for (var actor : PvPlaybackController.worldActors().values()) {
                var c = renderingChoice(actor);
                if (c != null && c.kind() == PerformerModelChoice.Kind.YSM) {
                    YsmModelLibraryBridge.apply(actor, c.modelId());
                }
            }
        }
    }

    private static void restoreAll() {
        for (var e : ORIGINAL.entrySet()) {
            YsmModelLibraryBridge.apply(e.getKey(), e.getValue());
        }
        ORIGINAL.clear();
    }
}
