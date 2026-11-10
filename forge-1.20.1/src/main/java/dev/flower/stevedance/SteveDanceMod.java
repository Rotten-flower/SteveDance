package dev.flower.stevedance;

import com.mojang.logging.LogUtils;

import net.minecraft.commands.Commands;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;

/**
 * Forge注册入口：方块、网络和服务端会话；客户端播放与绘制由各自事件订阅器接入。
 * <p>Forge registration entry for blocks, networking and server sessions; client subscribers own playback and drawing.</p>
 */
@Mod(SteveDanceMod.MOD_ID)
public final class SteveDanceMod {
    public static final String MOD_ID = "stevedance";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SteveDanceMod() {
        var bus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        WorldStage.BLOCKS.register(bus);
        WorldStage.ITEMS.register(bus);
        WorldStage.ENTITIES.register(bus);
        bus.addListener(
                (net.minecraftforge.event.BuildCreativeModeTabContentsEvent e) -> {
                    if (e.getTabKey()
                            == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                        e.accept(WorldStage.ITEM.get());
                        e.accept(WorldStage.MARKER_ITEM.get());
                    }
                });
        MotionPackLibrary.reload();
        StageNetwork.init();
        RhythmChartLibrary.reload();
        RhythmNetwork.init();
        MinecraftForge.EVENT_BUS.register(RhythmSessionService.class);
        MinecraftForge.EVENT_BUS.register(dev.flower.stevedance.rewards.ChallengeRewards.class);
        MinecraftForge.EVENT_BUS.register(StageSessionService.class);
        MinecraftForge.EVENT_BUS.register(PvSessionService.class);
        MinecraftForge.EVENT_BUS.addListener(this::commands);
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("stevedance")
                                .executes(
                                        c -> {
                                            RhythmSessionService.open(
                                                    c.getSource().getPlayerOrException());
                                            return 1;
                                        })
                                .then(
                                        Commands.literal("challenges")
                                                .requires(s -> s.hasPermission(2))
                                                .then(
                                                        Commands.literal("reload")
                                                                .executes(
                                                                        c ->
                                                                                dev.flower
                                                                                                .stevedance
                                                                                                .rewards
                                                                                                .ChallengeRewards
                                                                                                .reload(
                                                                                                        c
                                                                                                                .getSource())
                                                                                        ? 1
                                                                                        : 0)))
                                .then(
                                        Commands.literal("reload")
                                                .requires(s -> s.hasPermission(2))
                                                .executes(
                                                        c -> {
                                                            MotionPackLibrary.reload();
                                                            RhythmChartLibrary.reload();
                                                            SongSelectionCatalog.reload();
                                                            return 1;
                                                        })));
    }
}
