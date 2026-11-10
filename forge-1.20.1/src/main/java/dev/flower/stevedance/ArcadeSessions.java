package dev.flower.stevedance;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 机身仅在使用它的玩家演出时开屏。
 * <p>Only a linked live performance powers the cabinet.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance")
public final class ArcadeSessions {
    private record Cabinet(ResourceKey<Level> dimension, BlockPos pos) {}

    private static final Map<UUID, Cabinet> links = new HashMap<>();

    public static void open(ServerPlayer player, BlockPos pos) {
        links.put(player.getUUID(), new Cabinet(player.level().dimension(), pos.immutable()));
    }

    public static WorldStage.Anchor cabinet(ServerPlayer player) {
        var link = links.get(player.getUUID());
        if (link != null
                && link.dimension() == player.level().dimension()
                && player.serverLevel().hasChunkAt(link.pos())
                && player.serverLevel().getBlockEntity(link.pos()) instanceof WorldStage.Anchor a
                && a.availableTo(player)) {
            return a;
        }
        return WorldStage.nearby(player);
    }

    private static void power(Cabinet cabinet, boolean on) {
        var server = ServerLifecycleHooks.getCurrentServer();
        var level = server == null ? null : server.getLevel(cabinet.dimension());
        if (level == null || !level.hasChunkAt(cabinet.pos())) {
            return;
        }
        for (var pos : List.of(cabinet.pos(), cabinet.pos().above())) {
            var state = level.getBlockState(pos);
            if (state.is(WorldStage.ANCHOR.get())
                    && state.getValue(BlockStateProperties.LIT) != on) {
                level.setBlock(pos, state.setValue(BlockStateProperties.LIT, on), 3);
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getTickCount() % 2 != 0) {
            return;
        }
        Set<Cabinet> on = new HashSet<>(), all = new HashSet<>(links.values());
        for (var e : List.copyOf(links.entrySet())) {
            var player = server.getPlayerList().getPlayer(e.getKey());
            var cabinet = e.getValue();
            boolean active =
                    player != null
                            && (RhythmSessionService.performing(e.getKey())
                                    || PvSessionService.performing(e.getKey())
                                    || StageSessionService.busy(e.getKey()));
            if (active) {
                on.add(cabinet);
            } else if (player == null
                    || player.level().dimension() != cabinet.dimension()
                    || !player.serverLevel().hasChunkAt(cabinet.pos())
                    || !(player.serverLevel().getBlockEntity(cabinet.pos())
                            instanceof WorldStage.Anchor)) {
                links.remove(e.getKey());
            }
        }
        for (var cabinet : all) {
            power(cabinet, on.contains(cabinet));
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        var cabinet = links.remove(event.getEntity().getUUID());
        if (cabinet != null) {
            power(cabinet, false);
        }
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        links.clear();
    }
}
