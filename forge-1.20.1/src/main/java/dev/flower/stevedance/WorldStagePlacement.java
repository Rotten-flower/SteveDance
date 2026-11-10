package dev.flower.stevedance;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 每台街机保存一个定位地毯。
 * <p>Each cabinet owns one persistent carpet location.</p>
 */
public final class WorldStagePlacement {
    public record Placement(BlockPos pos, Direction facing) {}

    private WorldStagePlacement() {}

    public static boolean inRange(BlockPos cabinet, BlockPos marker) {
        return marker.getX() >= cabinet.getX() - 32
                && marker.getX() < cabinet.getX() + 32
                && marker.getZ() >= cabinet.getZ() - 32
                && marker.getZ() < cabinet.getZ() + 32;
    }

    public static Placement selected(ServerPlayer player) {
        var a = ArcadeSessions.cabinet(player);
        if (a == null
                || !a.availableTo(player)
                || a.marker() == null
                || !inRange(a.getBlockPos(), a.marker())
                || !player.serverLevel().hasChunkAt(a.marker())
                || !player.level().getBlockState(a.marker()).is(WorldStage.MARKER.get())) {
            return null;
        }
        return new Placement(a.marker(), Direction.fromYRot(a.yaw()));
    }

    public static boolean bind(ServerPlayer player, BlockPos pos) {
        var a = ArcadeSessions.cabinet(player);
        if (a == null || !a.availableTo(player) || !inRange(a.getBlockPos(), pos)) {
            message(player, "ui.stevedance.marker.range");
            return false;
        }
        if (a.getBlockState()
                .getValue(
                        net.minecraft.world.level.block.state.properties.BlockStateProperties
                                .LIT)) {
            message(player, "ui.stevedance.stage_busy");
            return false;
        }
        if (a.marker() != null
                && !a.marker().equals(pos)
                && player.level().getBlockState(a.marker()).is(WorldStage.MARKER.get())) {
            message(player, "ui.stevedance.marker.exists");
            return false;
        }
        a.marker(pos);
        player.sendSystemMessage(
                Component.translatable(
                        "ui.stevedance.stage_set", pos.getX(), pos.getY(), pos.getZ()));
        return true;
    }

    public static int info(ServerPlayer player) {
        var s = selected(player);
        if (s == null) {
            return message(player, "ui.stevedance.stage_not_set");
        }
        player.sendSystemMessage(
                Component.translatable(
                        "ui.stevedance.stage_set", s.pos().getX(), s.pos().getY(), s.pos().getZ()));
        return 1;
    }

    private static int message(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key));
        return 0;
    }
}
