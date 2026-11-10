package dev.flower.stevedance;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端拥有的会话原点。这些锚点从不写入方块或世界存档。
 * <p>Server-owned session origins. These anchors never write blocks or world saves.</p>
 */
public final class PerformanceStageRegistry {
    private static final Map<UUID, WorldStage.Anchor> STAGES = new HashMap<>();
    private static final Set<UUID> MARKERS = new HashSet<>();

    private PerformanceStageRegistry() {}

    public static WorldStage.Anchor acquire(ServerPlayer player, String song) {
        var level = player.serverLevel();
        BlockPos feet = player.blockPosition();
        for (int y : new int[] {0, 1, -1}) {
            for (int radius = 0; radius <= 2; radius++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) != radius) {
                            continue;
                        }
                        BlockPos pos = feet.offset(x, y, z);
                        if (!level.hasChunkAt(pos)
                                || !level.getWorldBorder().isWithinBounds(pos)
                                || !level.mayInteract(player, pos)
                                || !level.getBlockState(pos).isAir()
                                || !level.getBlockState(pos.above()).isAir()
                                || !level.getFluidState(pos).isEmpty()
                                || !level.noCollision(
                                        player,
                                        new net.minecraft.world.phys.AABB(
                                                pos.getX() + .2,
                                                pos.getY() + .125,
                                                pos.getZ() + .2,
                                                pos.getX() + .8,
                                                pos.getY() + 1.925,
                                                pos.getZ() + .8))
                                || !Block.canSupportCenter(level, pos.below(), Direction.UP)) {
                            continue;
                        }
                        boolean occupied =
                                STAGES.entrySet().stream()
                                        .anyMatch(
                                                e ->
                                                        !e.getKey().equals(player.getUUID())
                                                                && e.getValue().getLevel() == level
                                                                && e.getValue()
                                                                        .getBlockPos()
                                                                        .equals(pos));
                        if (occupied) {
                            continue;
                        }
                        var state =
                                WorldStage.ANCHOR
                                        .get()
                                        .defaultBlockState()
                                        .setValue(
                                                BlockStateProperties.HORIZONTAL_FACING,
                                                Direction.fromYRot(player.getYRot()));
                        var anchor = new WorldStage.Anchor(pos, state);
                        anchor.setLevel(level);
                        anchor.configureSession(song, player.getUUID());
                        STAGES.put(player.getUUID(), anchor);
                        return anchor;
                    }
                }
            }
        }
        return null;
    }

    public static WorldStage.Anchor acquireAt(
            ServerPlayer player, String song, BlockPos requested) {
        var level = player.serverLevel();
        BlockPos pos = requested.immutable();
        if (!usable(player, pos)) {
            return null;
        }
        var state =
                WorldStage.ANCHOR
                        .get()
                        .defaultBlockState()
                        .setValue(
                                BlockStateProperties.HORIZONTAL_FACING,
                                Direction.fromYRot(player.getYRot()));
        var anchor = new WorldStage.Anchor(pos, state);
        anchor.setLevel(level);
        anchor.configureSession(song, player.getUUID());
        STAGES.put(player.getUUID(), anchor);
        return anchor;
    }

    public static boolean usable(ServerPlayer player, BlockPos pos) {
        var level = player.serverLevel();
        if (!level.hasChunkAt(pos)
                || !level.getWorldBorder().isWithinBounds(pos)
                || !level.mayInteract(player, pos)
                || !(level.getBlockState(pos).isAir()
                        || level.getBlockState(pos).is(WorldStage.MARKER.get()))
                || !level.getBlockState(pos.above()).isAir()
                || !level.getFluidState(pos).isEmpty()
                || !Block.canSupportCenter(level, pos.below(), Direction.UP)) {
            return false;
        }
        var box =
                new net.minecraft.world.phys.AABB(
                        pos.getX() + .2,
                        pos.getY() + .125,
                        pos.getZ() + .2,
                        pos.getX() + .8,
                        pos.getY() + 1.925,
                        pos.getZ() + .8);
        return level.noCollision(player, box)
                && STAGES.values().stream()
                        .noneMatch(a -> a.getLevel() == level && a.getBlockPos().equals(pos));
    }

    public static WorldStage.Anchor acquireWorldStage(ServerPlayer player, String song) {
        var chosen = dev.flower.stevedance.WorldStagePlacement.selected(player);
        if (chosen == null) {
            return null;
        }
        if (player.position().distanceToSqr(stageOrigin(chosen.pos())) > 64 * 64
                || acquireAt(player, song, chosen.pos()) == null) {
            return null;
        }
        MARKERS.add(player.getUUID());
        var anchor = STAGES.get(player.getUUID());
        anchor.setBlockState(
                WorldStage.ANCHOR
                        .get()
                        .defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, chosen.facing()));
        return anchor;
    }

    private static net.minecraft.world.phys.Vec3 stageOrigin(BlockPos pos) {
        return new net.minecraft.world.phys.Vec3(
                pos.getX() + .5, pos.getY() + .125, pos.getZ() + .5);
    }

    public static WorldStage.Anchor acquirePrivate(ServerPlayer player, String song) {
        var level = player.serverLevel();
        BlockPos pos = player.blockPosition().immutable();
        if (!level.hasChunkAt(pos)
                || !(level.getBlockState(pos).isAir()
                        || level.getBlockState(pos).is(WorldStage.MARKER.get()))
                || !level.getBlockState(pos.above()).isAir()
                || !level.getFluidState(pos).isEmpty()
                || !Block.canSupportCenter(level, pos.below(), Direction.UP)
                || STAGES.values().stream()
                        .anyMatch(a -> a.getLevel() == level && a.getBlockPos().equals(pos))) {
            return null;
        }
        var state =
                WorldStage.ANCHOR
                        .get()
                        .defaultBlockState()
                        .setValue(
                                BlockStateProperties.HORIZONTAL_FACING,
                                Direction.fromYRot(player.getYRot()));
        var anchor = new WorldStage.Anchor(pos, state);
        anchor.setLevel(level);
        anchor.configureSession(song, player.getUUID());
        STAGES.put(player.getUUID(), anchor);
        return anchor;
    }

    public static WorldStage.Anchor get(ServerPlayer player, BlockPos pos) {
        var anchor = STAGES.get(player.getUUID());
        if (MARKERS.contains(player.getUUID())
                && !player.level().getBlockState(pos).is(WorldStage.MARKER.get())) {
            return null;
        }
        if (anchor == null
                || anchor.getLevel() != player.serverLevel()
                || !anchor.getBlockPos().equals(pos)) {
            return null;
        }
        // 实体舞台拆除后结束演出；临时舞台没有放入世界。Placed stage removal ends playback.
        return !anchor.isRemoved()
                        && (player.serverLevel().getBlockState(pos).isAir()
                                || player.serverLevel()
                                        .getBlockState(pos)
                                        .is(WorldStage.MARKER.get())
                                || player.serverLevel()
                                        .getBlockState(pos)
                                        .is(WorldStage.ANCHOR.get()))
                        && (!player.serverLevel().getBlockState(pos).is(WorldStage.ANCHOR.get())
                                || player.serverLevel().getBlockEntity(pos) == anchor)
                ? anchor
                : null;
    }

    public static void release(UUID player) {
        STAGES.remove(player);
        MARKERS.remove(player);
    }

    public static void clear() {
        STAGES.clear();
        MARKERS.clear();
    }

    public static boolean performingAt(net.minecraft.world.level.Level level, BlockPos pos) {
        return STAGES.entrySet().stream()
                .anyMatch(
                        e ->
                                e.getValue().getLevel() == level
                                        && e.getValue().getBlockPos().equals(pos)
                                        && (PvSessionService.performing(e.getKey())
                                                || RhythmSessionService.performing(e.getKey())));
    }

    public static int size() {
        return STAGES.size();
    }
}
