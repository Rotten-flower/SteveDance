package dev.flower.stevedance.client;

import com.mojang.blaze3d.platform.NativeImage;

import dev.flower.stevedance.SkinImage;
import dev.flower.stevedance.StageNetwork;
import dev.flower.stevedance.core.PerformerModelChoice;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 本地库使用显式手臂文件夹；只有选定的皮肤会离开此客户端。
 * <p>Local library uses explicit arm folders; only selected skins leave this client.</p>
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
        modid = "stevedance",
        value = net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class VanillaSkins {
    public record Entry(String id, String name) {}

    private static List<Entry> entries = List.of();
    private static final Map<String, byte[]> images = new LinkedHashMap<>(16, .75f, true);
    private static String fingerprint = "";
    private static net.minecraft.network.chat.Component importStatus;

    public static net.minecraft.network.chat.Component status() {
        return importStatus == null
                ? net.minecraft.network.chat.Component.translatable("ui.stevedance.model.skin_drop")
                : importStatus;
    }

    private static void afterScan() {
        scanning = false;
        var pending = List.copyOf(pendingShares);
        pendingShares.clear();
        pending.forEach(VanillaSkins::share);
    }

    private static final Map<String, ResourceLocation> textures = new HashMap<>();
    private static final Map<String, Long> requested = new HashMap<>();
    private static final Set<String> uploaded = new HashSet<>();
    private static final Set<PerformerModelChoice> pendingShares = new HashSet<>();
    private static Object connection;
    private static boolean scanning;
    private static long nextScan;

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void login(
            net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        session();
        refresh();
    }

    public static Path directory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("stevedance/skins");
    }

    public static List<Entry> entries() {
        if (System.currentTimeMillis() > nextScan) {
            refresh();
        }
        return entries;
    }

    public static void refresh() {
        if (scanning) {
            return;
        }
        scanning = true;
        nextScan = System.currentTimeMillis() + 5000;
        Path root = directory();
        CompletableFuture.runAsync(
                () -> {
                    var found = new ArrayList<Entry>();
                    var loaded = new HashMap<String, byte[]>();
                    try {
                        Files.createDirectories(root.resolve("steve"));
                        Files.createDirectories(root.resolve("alex"));
                        try (var files = Files.walk(root, 2)) {
                            var paths =
                                    files.filter(Files::isRegularFile)
                                            .filter(
                                                    p ->
                                                            p.getFileName()
                                                                    .toString()
                                                                    .toLowerCase(Locale.ROOT)
                                                                    .endsWith(".png"))
                                            .sorted()
                                            .limit(512)
                                            .toList();
                            StringBuilder signature = new StringBuilder();
                            for (Path p : paths) {
                                signature
                                        .append(p)
                                        .append(Files.size(p))
                                        .append(Files.getLastModifiedTime(p).toMillis());
                            }
                            String stamp = signature.toString();
                            if (stamp.equals(fingerprint)) {
                                Minecraft.getInstance().execute(VanillaSkins::afterScan);
                                return;
                            }
                            fingerprint = stamp;
                            for (Path p : paths) {
                                if (!p.getFileName()
                                        .toString()
                                        .toLowerCase(Locale.ROOT)
                                        .endsWith(".png")) {
                                    continue;
                                }
                                try {
                                    if (Files.size(p) > SkinImage.MAX_BYTES) {
                                        continue;
                                    }
                                    byte[] data = SkinImage.normalize(Files.readAllBytes(p));
                                    String hash = SkinImage.hash(data),
                                            arm =
                                                    p.getParent()
                                                                    .getFileName()
                                                                    .toString()
                                                                    .equalsIgnoreCase("alex")
                                                            ? "slim"
                                                            : "wide",
                                            id = hash + ":" + arm;
                                    loaded.put(hash, data);
                                    if (found.stream().noneMatch(e -> e.id().equals(id))) {
                                        found.add(
                                                new Entry(
                                                        id,
                                                        p.getFileName()
                                                                        .toString()
                                                                        .replaceFirst(
                                                                                "(?i)\\.png$", "")
                                                                + " ("
                                                                + (arm.equals("slim")
                                                                        ? "Alex"
                                                                        : "Steve")
                                                                + ")"));
                                    }
                                } catch (Exception ex) {
                                    org.slf4j.LoggerFactory.getLogger("stevedance")
                                            .warn(
                                                    "Skipped invalid skin {}: {}",
                                                    p.getFileName(),
                                                    ex.getMessage());
                                }
                            }
                        }
                    } catch (Exception ex) {
                        org.slf4j.LoggerFactory.getLogger("stevedance")
                                .warn("Cannot read skin library", ex);
                    }
                    Minecraft.getInstance()
                            .execute(
                                    () -> {
                                        images.putAll(loaded);
                                        trim();
                                        entries = List.copyOf(found);
                                        afterScan();
                                        dev.flower.stevedance.client.PerformerModelSelection
                                                .libraryChanged();
                                    });
                });
    }

    public static void importFiles(List<Path> paths, boolean slim) {
        Path target = directory().resolve(slim ? "alex" : "steve");
        CompletableFuture.runAsync(
                () -> {
                    int imported = 0, failed = 0;
                    try {
                        Files.createDirectories(target);
                        for (Path p : paths) {
                            try {
                                if (!p.getFileName()
                                                .toString()
                                                .toLowerCase(Locale.ROOT)
                                                .endsWith(".png")
                                        || Files.size(p) > SkinImage.MAX_BYTES) {
                                    failed++;
                                    continue;
                                }
                                byte[] data = SkinImage.normalize(Files.readAllBytes(p));
                                String name = p.getFileName().toString();
                                Path dest = target.resolve(name);
                                if (Files.exists(dest)) {
                                    dest =
                                            target.resolve(
                                                    name.replaceFirst("(?i)\\.png$", "")
                                                            + "-"
                                                            + SkinImage.hash(data).substring(0, 8)
                                                            + ".png");
                                }
                                Files.write(dest, data);
                                imported++;
                            } catch (Exception ex) {
                                failed++;
                                org.slf4j.LoggerFactory.getLogger("stevedance")
                                        .warn("Cannot import skin {}", p.getFileName(), ex);
                            }
                        }
                    } catch (Exception ex) {
                        org.slf4j.LoggerFactory.getLogger("stevedance")
                                .warn("Cannot import skins", ex);
                        failed = paths.size();
                    }
                    int done = imported, skipped = failed;
                    Minecraft.getInstance()
                            .execute(
                                    () -> {
                                        importStatus =
                                                net.minecraft.network.chat.Component.translatable(
                                                        "ui.stevedance.model.skin_imported",
                                                        done,
                                                        skipped);
                                        nextScan = 0;
                                        refresh();
                                    });
                });
    }

    private static void session() {
        Object current = Minecraft.getInstance().getConnection();
        if (current != connection) {
            connection = current;
            uploaded.clear();
            requested.clear();
            pendingShares.clear();
        }
    }

    public static void share(PerformerModelChoice choice) {
        session();
        if (choice.kind() != PerformerModelChoice.Kind.SKIN || connection == null) {
            return;
        }
        entries();
        String hash = choice.modelId().substring(0, 64);
        var bytes = images.get(hash);
        if (bytes == null && scanning) {
            pendingShares.add(choice);
        }
        if (bytes != null && uploaded.add(hash)) {
            StageNetwork.CHANNEL.sendToServer(new StageNetwork.SkinUpload(hash, bytes));
        }
    }

    public static ResourceLocation texture(String id) {
        session();
        entries();
        String hash = id.substring(0, 64);
        var texture = textures.get(hash);
        if (texture != null) {
            return texture;
        }
        var bytes = images.get(hash);
        if (bytes != null) {
            try {
                texture =
                        Minecraft.getInstance()
                                .getTextureManager()
                                .register(
                                        "stevedance/skin/" + hash,
                                        new DynamicTexture(
                                                NativeImage.read(
                                                        new java.io.ByteArrayInputStream(bytes))));
                textures.put(hash, texture);
                return texture;
            } catch (Exception ex) {
                images.remove(hash);
            }
        }
        long now = System.currentTimeMillis();
        if (connection != null && now - requested.getOrDefault(hash, 0L) > 5000) {
            requested.put(hash, now);
            StageNetwork.CHANNEL.sendToServer(new StageNetwork.SkinRequest(hash));
        }
        return new ResourceLocation(
                "minecraft",
                "textures/entity/player/"
                        + (id.endsWith(":slim") ? "slim/alex" : "wide/steve")
                        + ".png");
    }

    public static void receive(String hash, byte[] bytes) {
        if (!SkinImage.hash(bytes).equals(hash)) {
            return;
        }
        try {
            byte[] normalized = SkinImage.normalize(bytes);
            if (!SkinImage.hash(normalized).equals(hash)) {
                return;
            }
            images.put(hash, normalized);
            trim();
        } catch (Exception ignored) {
        }
    }

    private static void trim() {
        while (images.size() > 768) {
            String hash = images.keySet().iterator().next();
            images.remove(hash);
            var texture = textures.remove(hash);
            if (texture != null) {
                Minecraft.getInstance().getTextureManager().release(texture);
            }
        }
    }

    public static PerformerModelChoice networkChoice(PerformerModelChoice choice) {
        var p = Minecraft.getInstance().player;
        return choice.kind() == PerformerModelChoice.Kind.PLAYER && p != null
                ? new PerformerModelChoice(
                        PerformerModelChoice.Kind.ONLINE_PLAYER, p.getUUID().toString())
                : choice;
    }

    public static List<net.minecraft.client.multiplayer.PlayerInfo> players() {
        var c = Minecraft.getInstance().getConnection();
        return c == null
                ? List.of()
                : c.getOnlinePlayers().stream()
                        .sorted(
                                Comparator.comparing(
                                        p -> p.getProfile().getName(),
                                        String.CASE_INSENSITIVE_ORDER))
                        .toList();
    }
}
