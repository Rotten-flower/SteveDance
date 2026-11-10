package dev.flower.stevedance;

import dev.flower.stevedance.core.PerformerModelChoice;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真实玩家的短暂世界预览，退出恢复。
 * <p>Temporary real-player preview with explicit restoration.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance")
public final class WorldPreview {
    private record Visit(
            ResourceKey<Level> dimension,
            Vec3 origin,
            float yaw,
            float pitch,
            boolean gravity,
            BlockPos marker,
            float facing) {}

    private static final Map<UUID, Visit> visits = new HashMap<>();

    public static void request(ServerPlayer p, boolean active, String kind, String model) {
        if (!active) {
            end(p);
            return;
        }
        PerformerModelChoice choice;
        try {
            choice = new PerformerModelChoice(PerformerModelChoice.Kind.valueOf(kind), model);
        } catch (IllegalArgumentException ex) {
            return;
        }
        var selected = WorldStagePlacement.selected(p);
        if (selected == null
                || !p.isAlive()
                || p.isPassenger()
                || p.isSpectator()
                || PvSessionService.busy(p.getUUID())
                || RhythmSessionService.busy(p.getUUID())
                || StageSessionService.busy(p.getUUID())) {
            end(p);
            p.sendSystemMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "ui.stevedance.stage_not_set"));
            return;
        }
        if (!visits.containsKey(p.getUUID())) {
            if (!PerformanceStageRegistry.usable(p, selected.pos())) {
                return;
            }
            visits.put(
                    p.getUUID(),
                    new Visit(
                            p.level().dimension(),
                            p.position(),
                            p.getYRot(),
                            p.getXRot(),
                            p.isNoGravity(),
                            selected.pos(),
                            selected.facing().toYRot() + 180));
        }
        var v = visits.get(p.getUUID());
        p.setNoGravity(true);
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        p.teleportTo(
                p.serverLevel(),
                v.marker().getX() + .5,
                v.marker().getY() + .125,
                v.marker().getZ() + .5,
                v.facing(),
                0);
        StageNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.ALL.noArg(),
                new StageNetwork.PreviewState(
                        p.getUUID(), true, choice.kind().name(), choice.modelId()));
    }

    public static void end(ServerPlayer p) {
        var v = visits.remove(p.getUUID());
        if (v == null) {
            return;
        }
        p.setNoGravity(v.gravity());
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        var level = p.getServer().getLevel(v.dimension());
        if (level != null && p.isAlive()) {
            p.teleportTo(level, v.origin().x, v.origin().y, v.origin().z, v.yaw(), v.pitch());
        }
        StageNetwork.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.ALL.noArg(),
                new StageNetwork.PreviewState(p.getUUID(), false, "PLAYER", ""));
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            end(p);
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (var id : List.copyOf(visits.keySet())) {
            var p = server.getPlayerList().getPlayer(id);
            var v = visits.get(id);
            if (p == null) {
                visits.remove(id);
                continue;
            }
            if (!p.isAlive()
                    || p.level().dimension() != v.dimension()
                    || !p.level().getBlockState(v.marker()).is(WorldStage.MARKER.get())
                    || WorldStagePlacement.selected(p) == null) {
                end(p);
                continue;
            }
            p.setDeltaMovement(Vec3.ZERO);
            p.fallDistance = 0;
        }
    }

    @SubscribeEvent
    public static void stop(net.minecraftforge.event.server.ServerStoppedEvent e) {
        visits.clear();
    }
}
