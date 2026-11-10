package dev.flower.stevedance.rewards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.flower.stevedance.SteveDanceMod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 服务端配置及发奖入口。 / Server-owned configuration and reward execution. */
public final class ChallengeRewards {
    public record ItemReward(String item, int count) {}

    public record Rewards(List<ItemReward> items, int experience, List<String> commands) {}

    public record Challenge(
            String id,
            String title,
            ChallengeCondition condition,
            ChallengeCondition.Repeat repeat,
            Rewards rewards) {}

    private static List<Challenge> challenges = List.of();
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("stevedance/challenges.json");
    }

    @SubscribeEvent
    public static void starting(ServerAboutToStartEvent e) {
        try {
            reload();
        } catch (Exception ex) {
            challenges = List.of();
            SteveDanceMod.LOGGER.error(
                    "Cannot load challenges {}; rewards disabled", configPath(), ex);
        }
    }

    /**
     * 重载整体生效，编辑内容格式错误时保留上次有效配置。
     * <p>Atomic reload: malformed edits retain the previous valid configuration.</p>
     */
    public static int reload() throws java.io.IOException {
        Path path = configPath();
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            try (var source =
                    ChallengeRewards.class.getResourceAsStream(
                            "/assets/stevedance/challenges.example.json")) {
                if (source == null) {
                    throw new java.io.IOException("Missing challenge example");
                }
                Files.copy(source, path);
            }
        }
        if (Files.size(path) > 1024 * 1024) {
            throw new java.io.IOException("Challenge config exceeds 1 MiB");
        }
        try {
            var next = parse(Files.readString(path));
            challenges = next;
            SteveDanceMod.LOGGER.info("Loaded {} enabled song challenges", next.size());
            return next.size();
        } catch (RuntimeException ex) {
            throw new java.io.IOException(
                    "Invalid challenge configuration: " + ex.getMessage(), ex);
        }
    }

    public static List<Challenge> parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (root.get("schema").getAsInt() != 1) {
            throw new IllegalArgumentException("schema must be 1");
        }
        JsonArray rows = root.getAsJsonArray("challenges");
        if (rows == null || rows.size() > 256) {
            throw new IllegalArgumentException("challenges must contain at most 256 entries");
        }
        List<Challenge> next = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var row : rows) {
            var n = row.getAsJsonObject();
            String id = n.get("id").getAsString();
            if (!id.matches("[a-z][a-z0-9_.-]{0,63}") || !ids.add(id)) {
                throw new IllegalArgumentException("Invalid/duplicate id: " + id);
            }
            if (!n.get("enabled").getAsBoolean()) {
                continue;
            }
            String title = n.get("title").getAsString();
            if (title.isBlank() || title.length() > 120) {
                throw new IllegalArgumentException(id + ": invalid title");
            }
            var condition = JSON.fromJson(n.get("condition"), ChallengeCondition.class);
            var repeat = ChallengeCondition.Repeat.valueOf(n.get("repeat").getAsString());
            if (repeat == ChallengeCondition.Repeat.EVERY_RUN && condition.songs().size() != 1) {
                throw new IllegalArgumentException(
                        id + ": EVERY_RUN supports one song or wildcard");
            }
            var raw = JSON.fromJson(n.get("rewards"), Rewards.class);
            if (raw == null
                    || raw.items() == null
                    || raw.commands() == null
                    || raw.experience() < 0
                    || raw.experience() > 1000000
                    || raw.items().size() > 32
                    || raw.commands().size() > 32) {
                throw new IllegalArgumentException(id + ": invalid rewards");
            }
            List<ItemReward> items = new ArrayList<>();
            for (var item : raw.items()) {
                ResourceLocation key = ResourceLocation.tryParse(item.item());
                if (key == null
                        || !BuiltInRegistries.ITEM.containsKey(key)
                        || BuiltInRegistries.ITEM.get(key) == net.minecraft.world.item.Items.AIR
                        || item.count() < 1
                        || item.count() > 4096) {
                    throw new IllegalArgumentException(
                            id + ": unknown item or invalid count: " + item.item());
                }
                items.add(item);
            }
            List<String> commands = new ArrayList<>();
            for (String command : raw.commands()) {
                if (command == null
                        || command.isBlank()
                        || command.length() > 2048
                        || command.startsWith("/")
                        || command.contains("\n")
                        || command.contains("\r")) {
                    throw new IllegalArgumentException(id + ": invalid command");
                }
                commands.add(command);
            }
            if (items.isEmpty() && commands.isEmpty() && raw.experience() == 0) {
                throw new IllegalArgumentException(id + ": empty reward");
            }
            next.add(
                    new Challenge(
                            id,
                            title,
                            condition,
                            repeat,
                            new Rewards(
                                    List.copyOf(items), raw.experience(), List.copyOf(commands))));
        }
        return List.copyOf(next);
    }

    public static boolean reload(CommandSourceStack source) {
        try {
            int count = reload();
            source.sendSuccess(
                    () -> Component.translatable("commands.stevedance.challenges.loaded", count),
                    true);
            return true;
        } catch (Exception ex) {
            SteveDanceMod.LOGGER.error(
                    "Challenge reload rejected; prior configuration retained", ex);
            source.sendFailure(
                    Component.translatable(
                            "commands.stevedance.challenges.invalid", configPath().toString()));
            return false;
        }
    }

    @SubscribeEvent
    public static void event(RhythmChallengeEvent event) {
        int percent = event instanceof RhythmChallengeEvent.Progress p ? p.percent() : 100;
        long day = LocalDate.now(ZoneOffset.UTC).toEpochDay();
        for (var challenge : challenges) {
            if (!challenge.condition.matches(
                    event.trigger(),
                    event.song(),
                    event.difficulty(),
                    percent,
                    event.clearPercent(),
                    event.result())) {
                continue;
            }
            if (!ChallengeStore.get(event.player().server)
                    .observe(
                            event.player().getUUID(),
                            challenge.id,
                            challenge.repeat,
                            challenge.condition.songs(),
                            event.song(),
                            day,
                            event.runId())) {
                continue;
            }
            grant(event, challenge);
        }
    }

    private static void grant(RhythmChallengeEvent event, Challenge challenge) {
        var player = event.player();
        boolean commandsOk = true;
        for (var reward : challenge.rewards.items) {
            var item = BuiltInRegistries.ITEM.get(new ResourceLocation(reward.item));
            int remaining = reward.count;
            while (remaining > 0) {
                int count = Math.min(remaining, item.getMaxStackSize());
                remaining -= count;
                var stack = new ItemStack(item, count);
                player.getInventory().add(stack);
                if (!stack.isEmpty()) {
                    var dropped = player.drop(stack, false);
                    if (dropped != null) {
                        dropped.setNoPickUpDelay();
                        dropped.setTarget(player.getUUID());
                    }
                }
            }
        }
        if (challenge.rewards.experience > 0) {
            player.giveExperiencePoints(challenge.rewards.experience);
        }
        for (String template : challenge.rewards.commands) {
            String command =
                    template.replace("{player}", player.getGameProfile().getName())
                            .replace("{uuid}", player.getUUID().toString())
                            .replace("{song}", event.song())
                            .replace("{difficulty}", event.difficulty());
            int result =
                    player.server
                            .getCommands()
                            .performPrefixedCommand(
                                    player.createCommandSourceStack()
                                            .withPermission(4)
                                            .withSuppressedOutput(),
                                    command);
            if (result == 0) {
                commandsOk = false;
                SteveDanceMod.LOGGER.warn(
                        "Challenge {} reward command failed for {}: {}",
                        challenge.id,
                        player.getUUID(),
                        command);
            }
        }
        player.sendSystemMessage(
                Component.translatable("message.stevedance.challenge.reward", challenge.title));
        if (!commandsOk) {
            player.sendSystemMessage(
                    Component.translatable("message.stevedance.challenge.command_failed"));
        }
    }
}
