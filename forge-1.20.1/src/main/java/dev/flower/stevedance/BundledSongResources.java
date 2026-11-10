package dev.flower.stevedance;

import com.google.gson.JsonParser;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 模组内只读演示内容；用户设置存放在单独的可写位置。
 * <p>Read-only demo content inside the mod; user settings have a separate writable location.</p>
 */
public final class BundledSongResources {
    private static Path root;
    private static Map<String, String> aliases;

    private BundledSongResources() {}

    public static synchronized Path root() {
        if (root == null) {
            root =
                    ModList.get()
                            .getModFileById(SteveDanceMod.MOD_ID)
                            .getFile()
                            .findResource("assets", SteveDanceMod.MOD_ID, "builtin")
                            .toAbsolutePath()
                            .normalize();
        }
        return root;
    }

    public static Path nativeSong(String id) {
        SongCatalog.get(id);
        var pack = dev.flower.stevedance.content.InstalledSongs.song(id);
        return pack == null ? root().resolve("native").resolve(id) : pack.nativeRoot();
    }

    public static Path chart(String id, String difficulty) {
        var pack = dev.flower.stevedance.content.InstalledSongs.song(id);
        return pack == null
                ? root().resolve("f2nd/songs").resolve(id).resolve("chart-" + difficulty + ".json")
                : dev.flower.stevedance.content.InstalledSongs.get()
                        .resolve(pack.chart(difficulty));
    }

    public static Path thumbnail(String id) {
        var pack = dev.flower.stevedance.content.InstalledSongs.song(id);
        return pack == null
                ? root().resolve("f2nd/thumbnails").resolve(id + ".png")
                : dev.flower.stevedance.content.InstalledSongs.get().resolve(pack.thumbnail());
    }

    /**
     * 仅解析不可变包中的别名；开发/外部路径保留其自身文件。
     * <p>Resolve only aliases in the immutable bundle; development/external paths retain their own files.</p>
     */
    public static synchronized Path resolve(Path logical) throws IOException {
        var external = dev.flower.stevedance.content.InstalledSongs.get();
        if (external.boundary(logical) != null) {
            return external.resolve(logical);
        }
        if (root == null || !logical.normalize().startsWith(root)) {
            return logical;
        }
        if (aliases == null) {
            Path index = root.resolve("resource-aliases.json");
            if (!Files.isRegularFile(index)) {
                aliases = Map.of();
            } else {
                if (Files.size(index) > 1024 * 1024) {
                    throw new IOException("Bundled resource index exceeds budget");
                }
                try {
                    var data = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
                    if (data.get("schema").getAsInt() != 1
                            || data.getAsJsonObject("files").size() > 8192) {
                        throw new IOException("Unsupported bundled resource index");
                    }
                    Map<String, String> loaded = new HashMap<>();
                    for (var entry : data.getAsJsonObject("files").entrySet()) {
                        String key = entry.getKey(), target = entry.getValue().getAsString();
                        if (!validAlias(key) || !validAlias(target) || key.equals(target)) {
                            throw new IOException("Invalid bundled resource alias");
                        }
                        loaded.put(key, target);
                    }
                    for (String target : loaded.values()) {
                        if (loaded.containsKey(target)) {
                            throw new IOException("Chained resource aliases are unsupported");
                        }
                    }
                    aliases = Map.copyOf(loaded);
                } catch (RuntimeException ex) {
                    throw new IOException("Invalid bundled resource index", ex);
                }
            }
        }
        String target =
                aliases.get(root.relativize(logical.normalize()).toString().replace('\\', '/'));
        return target == null ? logical : root.resolve(target).normalize();
    }

    private static boolean validAlias(String value) {
        return value.length() <= 256
                && value.startsWith("native/")
                && !value.contains("..")
                && !value.contains("\\")
                && !value.contains(":")
                && (value.endsWith(".png")
                        || value.endsWith("/music.ogg")
                        || value.endsWith("/scene.bin"));
    }

    public static boolean exists(Path logical) {
        try {
            return Files.isRegularFile(resolve(logical));
        } catch (IOException ex) {
            SteveDanceMod.LOGGER.error("Bundled resource unavailable: {}", logical, ex);
            return false;
        }
    }

    public static Path settings() {
        return FMLPaths.CONFIGDIR.get().resolve("stevedance/f2nd");
    }
}
