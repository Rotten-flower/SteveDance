package dev.flower.stevedance.content;

import com.mojang.brigadier.arguments.StringArgumentType;

import dev.flower.stevedance.MotionPackLibrary;
import dev.flower.stevedance.client.ClientPlaybackRuntime;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 自定义歌曲入口，沿用现有动作包播放。
 * <p>Custom songs use the existing motion pack player.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class CustomSongs {
    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("stevedancebones")
                                .then(
                                        Commands.literal("reload")
                                                .executes(
                                                        c -> {
                                                            MaidModels.reloadMappings();
                                                            return 1;
                                                        }))
                                .then(
                                        Commands.literal("maid")
                                                .then(
                                                        Commands.argument(
                                                                        "model",
                                                                        StringArgumentType.string())
                                                                .suggests(
                                                                        (c, b) ->
                                                                                net.minecraft
                                                                                        .commands
                                                                                        .SharedSuggestionProvider
                                                                                        .suggest(
                                                                                                MaidModels
                                                                                                        .ids(),
                                                                                                b))
                                                                .executes(
                                                                        c -> {
                                                                            try {
                                                                                var path =
                                                                                        MaidModels
                                                                                                .exportBones(
                                                                                                        StringArgumentType
                                                                                                                .getString(
                                                                                                                        c,
                                                                                                                        "model"));
                                                                                c.getSource()
                                                                                        .sendSuccess(
                                                                                                () ->
                                                                                                        Component
                                                                                                                .translatable(
                                                                                                                        "ui.stevedance.bones_exported",
                                                                                                                        path.getFileName()
                                                                                                                                .toString()),
                                                                                                false);
                                                                                return 1;
                                                                            } catch (Exception ex) {
                                                                                c.getSource()
                                                                                        .sendFailure(
                                                                                                Component
                                                                                                        .translatable(
                                                                                                                "ui.stevedance.model.unavailable"));
                                                                                return 0;
                                                                            }
                                                                        }))));
        event.getDispatcher()
                .register(
                        Commands.literal("stevedancecustom")
                                .then(
                                        Commands.literal("reload")
                                                .executes(
                                                        c -> {
                                                            MotionPackLibrary.reload();
                                                            return 1;
                                                        }))
                                .then(
                                        Commands.literal("play")
                                                .then(
                                                        song().executes(
                                                                        c -> {
                                                                            var p =
                                                                                    pack(
                                                                                            StringArgumentType
                                                                                                    .getString(
                                                                                                            c,
                                                                                                            "song"));
                                                                            if (p == null) {
                                                                                return 0;
                                                                            }
                                                                            ClientPlaybackRuntime
                                                                                    .prepare(p);
                                                                            return 1;
                                                                        }))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<
                    net.minecraft.commands.CommandSourceStack, String>
            song() {
        return Commands.argument("song", StringArgumentType.word())
                .suggests(
                        (c, b) ->
                                net.minecraft.commands.SharedSuggestionProvider.suggest(
                                        MotionPackLibrary.all().stream()
                                                .filter(MotionPackLibrary.Pack::playable)
                                                .map(p -> p.manifest().id()),
                                        b));
    }

    private static MotionPackLibrary.Pack pack(String id) {
        var p = MotionPackLibrary.get(id);
        if (p != null && p.playable()) {
            return p;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable("ui.stevedance.invalid_pack"), false);
        }
        return null;
    }
}
