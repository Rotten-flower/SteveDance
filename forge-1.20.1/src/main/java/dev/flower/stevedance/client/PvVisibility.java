package dev.flower.stevedance.client;

import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Client-only draw filters; source timelines and motion remain untouched. / 只过滤绘制，不修改原始显隐和动作。 */
public final class PvVisibility {
    public enum Group {
        BACKGROUND,
        STAGE,
        PROP,
        ACTOR,
        EFFECT,
        WORLD
    }

    public record Entry(Group group, String key, String label) {}

    private static final Set<Group> hiddenGroups = java.util.EnumSet.noneOf(Group.class);
    private static final Map<String, Boolean> overrides = new java.util.HashMap<>();
    private static final Map<String, Group> keyGroups = new java.util.HashMap<>();
    private static boolean loaded;
    private static final java.nio.file.Path PATH = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get()
            .resolve("stevedance/visibility.json");

    private static void load() {
        if (loaded) return;
        loaded = true;
        if (!java.nio.file.Files.isRegularFile(PATH)) return;
        try {
            if (java.nio.file.Files.size(PATH) > 1024 * 1024) throw new java.io.IOException("Display settings too large");
            var data = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(PATH)).getAsJsonObject();
            if (data.get("schema").getAsInt() != 1) throw new java.io.IOException("Unsupported display schema");
            for (var v : data.getAsJsonArray("hiddenGroups")) hiddenGroups.add(Group.valueOf(v.getAsString()));
            for (var e : data.getAsJsonObject("overrides").entrySet()) {
                if (e.getKey().length() <= 1024 && e.getValue().isJsonPrimitive()
                        && e.getValue().getAsJsonPrimitive().isBoolean()) overrides.put(e.getKey(), e.getValue().getAsBoolean());
            }
        } catch (java.io.IOException | RuntimeException ex) {
            dev.flower.stevedance.SteveDanceMod.LOGGER.warn("Cannot read display preferences; source preserved", ex);
        }
    }

    private static void save() {
        var data = new com.google.gson.JsonObject();
        data.addProperty("schema", 1);
        var groups = new com.google.gson.JsonArray();
        hiddenGroups.forEach(g -> groups.add(g.name()));
        data.add("hiddenGroups", groups);
        var values = new com.google.gson.JsonObject();
        overrides.forEach(values::addProperty);
        data.add("overrides", values);
        try {
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024 * 1024)
                throw new java.io.IOException("Display settings exceed budget");
            java.nio.file.Files.createDirectories(PATH.getParent());
            var tmp = PATH.resolveSibling("visibility.json.tmp");
            java.nio.file.Files.writeString(tmp, json);
            java.nio.file.Files.move(tmp, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException ex) {
            dev.flower.stevedance.SteveDanceMod.LOGGER.warn("Cannot save display preferences", ex);
        }
    }

    /** Reset scene cache only; explicit player preferences survive replay. / 重播只清缓存。 */
    public static void restoreDefaults() {
        load();
        hiddenGroups.clear(); overrides.clear();
        ClientPlaybackSettings.load();
        ClientPlaybackSettings.renderingMode = ClientPlaybackSettings.RenderingMode.PV;
        ClientPlaybackSettings.worldPvLighting = true;
        ClientPlaybackSettings.save();
        save();
    }

    public static boolean world(String key) {
        return !PvPlaybackController.active() || visible("world:" + key);
    }

    private static String scoped(String key) {
        Group group = keyGroups.getOrDefault(key, Group.WORLD);
        String song = key.startsWith("world:") ? "*" : PvPlaybackController.song();
        return group.name() + "|" + song + "|" + key;
    }
    private static PvSceneData installed;
    private static List<Entry> entries = List.of();
    private static final Map<Group, List<Entry>> grouped = new EnumMap<>(Group.class);

    private PvVisibility() {}

    public static void reset() {
        keyGroups.clear();
        installed = null;
        entries = List.of();
        grouped.clear();
    }

    public static boolean visible(String key) {
        load();
        if (key.equals("lighting")) return PvPlaybackController.nativeCharacterLighting();
        entries();
        return overrides.getOrDefault(scoped(key), !hiddenGroups.contains(keyGroups.getOrDefault(key, Group.WORLD)));
    }

    public static void toggle(String key) {
        boolean show = !visible(key);
        if (key.startsWith("graph:")) {
            String graph = key.substring(6);
            for (var e : entries()) {
                if (e.key().equals(key)
                        || e.key().startsWith("node:" + graph + ":")
                        || e.key().startsWith("rig:" + graph + ":")) {
                    setKey(e.key(), show);
                }
            }
        } else {
            setKey(key, show);
            if (show && (key.startsWith("node:") || key.startsWith("rig:"))) {
                int begin = key.indexOf(':') + 1, end = key.indexOf(':', begin);
                if (end > begin) {
                    setKey("graph:" + key.substring(begin, end), true);
                }
            }
        }
        save();
    }

    private static void setKey(String key, boolean show) {
        if (key.equals("lighting")) {
            ClientPlaybackSettings.load();
            ClientPlaybackSettings.renderingMode = show ? ClientPlaybackSettings.RenderingMode.PV : ClientPlaybackSettings.RenderingMode.MINECRAFT;
            ClientPlaybackSettings.worldPvLighting = show;
            ClientPlaybackSettings.save();
            return;
        }
        overrides.put(scoped(key), show);
    }

    public static boolean actor(int slot) {
        return visible("actor:" + slot);
    }

    public static boolean graph(String name) {
        return visible("graph:" + name);
    }

    public static boolean node(String graph, String name) {
        return visible("node:" + graph + ":" + name);
    }

    public static boolean rig(String graph, String name) {
        return visible("rig:" + graph + ":" + name);
    }

    public static boolean mesh(String name) {
        return visible("mesh:" + name);
    }

    public static boolean overlay(String scene, String root) {
        return visible("aet:" + scene + ":" + root);
    }

    public static List<Entry> entries() {
        var scene = PvPlaybackController.scene;
        if (scene == null) return List.of();
        if (scene == installed) {
            return entries;
        }
        installed = scene;
        var result = new LinkedHashMap<String, Entry>();
        keyGroups.clear();
        for (String key : List.of("sky", "clouds", "terrain", "weather", "entities", "block_entities", "particles", "outline"))
            add(result, Group.WORLD, "world:" + key, tr(key));
        add(result, Group.EFFECT, "lighting", tr("lighting"));
        if (scene.byName.containsKey("$world_backdrop")) {
            add(result, Group.BACKGROUND, "backdrop", tr("backdrop"));
        }
        if (scene.movie != null) {
            add(result, Group.BACKGROUND, "movie", tr("movie"));
        }
        if (scene.overlay != null && scene.overlay.timeline != null) {
            for (var cut : scene.overlay.timeline) {
                for (var cue : cut.layers) {
                    add(
                            result,
                            "back".equals(cue.target) ? Group.BACKGROUND : Group.EFFECT,
                            "aet:" + cue.scene + ":" + cue.root,
                            cue.root);
                }
            }
        }
        var props = new HashSet<String>();
        if (scene.description.itemTracks() != null) {
            for (var item : scene.description.itemTracks()) {
                props.add(item.graph());
            }
        }
        for (var list : scene.description.stages().values()) {
            for (String mesh : list) {
                add(result, Group.STAGE, "mesh:" + mesh, mesh);
            }
        }
        for (var e : scene.description.graphs().entrySet()) {
            // Classify by authored references, never by filename. / 依原数据引用分类，不猜文件名。
            Group group =
                    e.getValue().rigs.length > 0 && Arrays.stream(e.getValue().rigs).allMatch(r -> r.replacementCharacter)
                            ? Group.ACTOR : props.contains(e.getKey())
                                    || Arrays.stream(e.getValue().rigs)
                                            .anyMatch(SourcePropContacts::handProp)
                            ? Group.PROP
                            : Group.STAGE;
            add(result, group, "graph:" + e.getKey(), e.getKey());
            for (var node : e.getValue().nodes) {
                if (node.mesh != null) {
                    add(result, group, "node:" + e.getKey() + ":" + node.name, node.name);
                }
            }
            for (var rig : e.getValue().rigs) {
                if (rig.mesh != null || rig.replacementCharacter) {
                    add(result, group, "rig:" + e.getKey() + ":" + rig.name, rig.name);
                }
            }
        }
        var actors = new TreeSet<Integer>();
        actors.addAll(PvPlaybackController.worldActors().keySet());
        if (scene.description.events() != null) {
            for (var event : scene.description.events()) {
                if ("MIKU_DISP".equals(event.command()) && event.args().length > 0) {
                    actors.add(event.args()[0]);
                }
            }
        }
        for (int slot : actors) {
            add(
                    result,
                    Group.ACTOR,
                    "actor:" + slot,
                    Component.translatable("ui.stevedance.display.actor.number", slot + 1)
                            .getString());
        }
        if (scene.subtitles != null) {
            add(result, Group.EFFECT, "lyrics", tr("lyrics"));
        }
        if (scene.lighting != null) {
            add(result, Group.EFFECT, "post", tr("post"));
        }
        entries = List.copyOf(result.values());
        for (var group : Group.values()) {
            grouped.put(group, entries.stream().filter(e -> e.group() == group).toList());
        }
        return entries;
    }

    private static String tr(String key) {
        return Component.translatable("ui.stevedance.display." + key).getString();
    }

    private static void add(Map<String, Entry> result, Group group, String key, String label) {
        result.putIfAbsent(key, new Entry(group, key, label));
        keyGroups.put(key, group);
    }

    public static List<Entry> entries(Group group) {
        entries();
        return grouped.getOrDefault(group, List.of());
    }

    public static int shown(Group group) {
        return (int) entries(group).stream().filter(e -> visible(e.key())).count();
    }

    public static void set(Group group, boolean show) {
        load(); entries();
        if (show) hiddenGroups.remove(group); else hiddenGroups.add(group);
        // A group action applies to every song; individual choices remain scoped to their song.
        // 分组操作作用于所有歌曲，单项操作按曲记忆。
        overrides.keySet().removeIf(k -> k.startsWith(group.name() + "|"));
        if (group == Group.EFFECT) setKey("lighting", show);
        save();
    }

    public static void all(boolean show) {
        load(); entries();
        hiddenGroups.clear();
        if (!show) hiddenGroups.addAll(java.util.EnumSet.allOf(Group.class));
        overrides.clear();
        setKey("lighting", show);
        save();
    }
}
