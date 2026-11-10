package dev.flower.stevedance;

import dev.flower.stevedance.client.RhythmClientController;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class RhythmNetwork {
    public static final SimpleChannel CHANNEL =
            NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("stevedance", "f2_game"),
                    () -> "14-chart-only",
                    "14-chart-only"::equals,
                    "14-chart-only"::equals);

    public record Command(
            int action,
            String song,
            String difficulty,
            String digest,
            int timeMs,
            int sequence,
            int bit,
            boolean pressed) {
        public Command(int action) {
            this(action, "", "", 0, 0, 0, false);
        }

        public Command(
                int action,
                String difficulty,
                String digest,
                int timeMs,
                int sequence,
                int bit,
                boolean pressed) {
            this(action, "two_breaths_f2nd", difficulty, digest, timeMs, sequence, bit, pressed);
        }

        static Command read(FriendlyByteBuf b) {
            return new Command(
                    b.readVarInt(),
                    b.readUtf(48),
                    b.readUtf(12),
                    b.readUtf(64),
                    b.readInt(),
                    b.readVarInt(),
                    b.readVarInt(),
                    b.readBoolean());
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(action);
            b.writeUtf(song, 48);
            b.writeUtf(difficulty, 12);
            b.writeUtf(digest, 64);
            b.writeInt(timeMs);
            b.writeVarInt(sequence);
            b.writeVarInt(bit);
            b.writeBoolean(pressed);
        }
    }

    /**
     * 0 菜单，1 加载中，2 游玩，3 结果，4 已停止，5 错误，6 暂停，7 恢复，8 记录。
     * <p>0 menu, 1 loading, 2 play, 3 result, 4 stopped, 5 error, 6 pause, 7 resume, 8 records.</p>
     */
    public record State(
            int kind,
            String song,
            String difficulty,
            long startTick,
            BlockPos stage,
            String dimension,
            float yaw,
            int timeMs,
            String json,
            String error) {
        public State(
                int kind,
                String difficulty,
                long startTick,
                BlockPos stage,
                String dimension,
                float yaw,
                int timeMs,
                String json,
                String error) {
            this(
                    kind,
                    "two_breaths_f2nd",
                    difficulty,
                    startTick,
                    stage,
                    dimension,
                    yaw,
                    timeMs,
                    json,
                    error);
        }

        static State read(FriendlyByteBuf b) {
            return new State(
                    b.readVarInt(),
                    b.readUtf(48),
                    b.readUtf(12),
                    b.readLong(),
                    b.readBlockPos(),
                    b.readUtf(128),
                    b.readFloat(),
                    b.readInt(),
                    b.readUtf(16384),
                    b.readUtf(100));
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(kind);
            b.writeUtf(song, 48);
            b.writeUtf(difficulty, 12);
            b.writeLong(startTick);
            b.writeBlockPos(stage);
            b.writeUtf(dimension, 128);
            b.writeFloat(yaw);
            b.writeInt(timeMs);
            b.writeUtf(json, 16384);
            b.writeUtf(error, 100);
        }
    }

    public static void init() {
        CHANNEL.messageBuilder(Command.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Command::write)
                .decoder(Command::read)
                .consumerMainThread(
                        (m, c) -> {
                            ServerPlayer p = c.get().getSender();
                            if (p != null) {
                                RhythmSessionService.command(p, m);
                            }
                            c.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(State.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(State::write)
                .decoder(State::read)
                .consumerMainThread(
                        (m, c) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT, () -> () -> RhythmClientController.receive(m));
                            c.get().setPacketHandled(true);
                        })
                .add();
    }

    public static void send(ServerPlayer p, State s) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), s);
    }

    public static void simple(ServerPlayer p, int kind, String data, String error) {
        send(p, new State(kind, "", 0, BlockPos.ZERO, "", 0, 0, data, error));
    }

    public static void simple(ServerPlayer p, int kind, String song, String data, String error) {
        send(p, new State(kind, song, "", 0, BlockPos.ZERO, "", 0, 0, data, error));
    }
}
