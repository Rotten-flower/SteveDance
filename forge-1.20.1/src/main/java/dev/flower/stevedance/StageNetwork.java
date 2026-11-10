package dev.flower.stevedance;

import dev.flower.stevedance.client.ClientPlaybackRuntime;
import dev.flower.stevedance.core.RhythmSession;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class StageNetwork {
    public static final SimpleChannel CHANNEL =
            NetworkRegistry.newSimpleChannel(
                    new ResourceLocation("stevedance", "play"),
                    () -> "10-skins",
                    "10-skins"::equals,
                    "10-skins"::equals);

    /**
     * kind：0 菜单，1 开始，2 已完成，3 已停止，4 已拒绝。
     * <p>Cosmetic actor choices, bounded independently of stage or control authority.</p>
     */
    public record ActorModels(
            java.util.UUID host,
            java.util.Map<Integer, dev.flower.stevedance.core.PerformerModelChoice> choices) {
        static ActorModels read(FriendlyByteBuf b) {
            var host = b.readUUID();
            int count = b.readVarInt();
            if (count < 0 || count > 16) {
                throw new IllegalArgumentException("Actor count");
            }
            var choices =
                    new java.util.HashMap<
                            Integer, dev.flower.stevedance.core.PerformerModelChoice>();
            for (int i = 0; i < count; i++) {
                int slot = b.readVarInt();
                if (slot < 0 || slot >= 16 || choices.containsKey(slot)) {
                    throw new IllegalArgumentException("Actor slot");
                }
                choices.put(
                        slot,
                        new dev.flower.stevedance.core.PerformerModelChoice(
                                b.readEnum(
                                        dev.flower.stevedance.core.PerformerModelChoice.Kind.class),
                                b.readUtf(256)));
            }
            return new ActorModels(host, java.util.Map.copyOf(choices));
        }

        void write(FriendlyByteBuf b) {
            b.writeUUID(host);
            b.writeVarInt(choices.size());
            for (var e : choices.entrySet()) {
                b.writeVarInt(e.getKey());
                b.writeEnum(e.getValue().kind());
                b.writeUtf(e.getValue().modelId(), 256);
            }
        }
    }

    public record PvControl(int action, int timeMs) {
        static PvControl read(FriendlyByteBuf b) {
            return new PvControl(b.readVarInt(), b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(action);
            b.writeVarInt(timeMs);
        }
    }

    public record NativeRequest(int action) {
        static NativeRequest read(FriendlyByteBuf b) {
            return new NativeRequest(b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(action);
        }
    }

    public record Clock(int timeMs) {
        static Clock read(FriendlyByteBuf b) {
            return new Clock(b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(timeMs);
        }
    }

    public record Request(String song, String digest, boolean stop) {
        static Request read(FriendlyByteBuf b) {
            return new Request(b.readUtf(48), b.readUtf(64), b.readBoolean());
        }

        void write(FriendlyByteBuf b) {
            b.writeUtf(song, 48);
            b.writeUtf(digest, 64);
            b.writeBoolean(stop);
        }
    }

    public record Input(int sequence, int bit, boolean pressed, int timeMs) {
        static Input read(FriendlyByteBuf b) {
            return new Input(b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(sequence);
            b.writeVarInt(bit);
            b.writeBoolean(pressed);
            b.writeVarInt(timeMs);
        }
    }

    /**
     * 音频设备进度按墙上时间检查，但音符跟随该音频时钟。
     * <p>kind: 0 menu, 1 start, 2 completed, 3 stopped, 4 refused.</p>
     */
    public record State(
            int kind,
            java.util.UUID player,
            String song,
            long startTick,
            String message,
            RhythmSession.Result result,
            net.minecraft.core.BlockPos stage,
            String dimension,
            float yaw,
            int playbackMs) {
        public State(
                int kind,
                java.util.UUID player,
                String song,
                long startTick,
                String message,
                RhythmSession.Result result) {
            this(
                    kind,
                    player,
                    song,
                    startTick,
                    message,
                    result,
                    net.minecraft.core.BlockPos.ZERO,
                    "",
                    0,
                    0);
        }

        static State read(FriendlyByteBuf b) {
            int k = b.readVarInt();
            var p = b.readUUID();
            String s = b.readUtf(48);
            long tick = b.readLong();
            String m = b.readUtf(100);
            RhythmSession.Result r = null;
            if (b.readBoolean()) {
                r =
                        new RhythmSession.Result(
                                b.readVarInt(),
                                b.readVarInt(),
                                b.readVarInt(),
                                b.readVarInt(),
                                b.readVarInt(),
                                b.readVarInt(),
                                b.readVarInt(),
                                b.readVarInt(),
                                b.readVarInt());
            }
            return new State(
                    k,
                    p,
                    s,
                    tick,
                    m,
                    r,
                    b.readBlockPos(),
                    b.readUtf(128),
                    b.readFloat(),
                    b.readVarInt());
        }

        void write(FriendlyByteBuf b) {
            b.writeVarInt(kind);
            b.writeUUID(player);
            b.writeUtf(song, 48);
            b.writeLong(startTick);
            b.writeUtf(message, 100);
            b.writeBoolean(result != null);
            if (result != null) {
                b.writeVarInt(result.score());
                b.writeVarInt(result.maxCombo());
                b.writeVarInt(result.cool());
                b.writeVarInt(result.fine());
                b.writeVarInt(result.safe());
                b.writeVarInt(result.sad());
                b.writeVarInt(result.worst());
                b.writeVarInt(result.life());
                b.writeVarInt(result.total());
            }
            b.writeBlockPos(stage);
            b.writeUtf(dimension, 128);
            b.writeFloat(yaw);
            b.writeVarInt(playbackMs);
        }
    }

    public record PreviewRequest(boolean active, String kind, String model) {
        static PreviewRequest read(FriendlyByteBuf b) {
            return new PreviewRequest(b.readBoolean(), b.readUtf(16), b.readUtf(256));
        }

        void write(FriendlyByteBuf b) {
            b.writeBoolean(active);
            b.writeUtf(kind, 16);
            b.writeUtf(model, 256);
        }
    }

    public record PreviewState(java.util.UUID player, boolean active, String kind, String model) {
        static PreviewState read(FriendlyByteBuf b) {
            return new PreviewState(b.readUUID(), b.readBoolean(), b.readUtf(16), b.readUtf(256));
        }

        void write(FriendlyByteBuf b) {
            b.writeUUID(player);
            b.writeBoolean(active);
            b.writeUtf(kind, 16);
            b.writeUtf(model, 256);
        }
    }

    public record SkinUpload(String hash, byte[] png) {
        static SkinUpload read(FriendlyByteBuf b) {
            return new SkinUpload(
                    b.readUtf(64), b.readByteArray(dev.flower.stevedance.SkinImage.MAX_BYTES));
        }

        void write(FriendlyByteBuf b) {
            b.writeUtf(hash, 64);
            b.writeByteArray(png);
        }
    }

    public record SkinRequest(String hash) {
        static SkinRequest read(FriendlyByteBuf b) {
            return new SkinRequest(b.readUtf(64));
        }

        void write(FriendlyByteBuf b) {
            b.writeUtf(hash, 64);
        }
    }

    public record SkinReply(String hash, byte[] png) {
        static SkinReply read(FriendlyByteBuf b) {
            return new SkinReply(
                    b.readUtf(64), b.readByteArray(dev.flower.stevedance.SkinImage.MAX_BYTES));
        }

        void write(FriendlyByteBuf b) {
            b.writeUtf(hash, 64);
            b.writeByteArray(png);
        }
    }

    public static void init() {
        CHANNEL.messageBuilder(SkinUpload.class, 10, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SkinUpload::write)
                .decoder(SkinUpload::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            var p = ctx.get().getSender();
                            if (p != null) {
                                dev.flower.stevedance.SkinSharing.upload(p, msg.hash(), msg.png());
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(SkinRequest.class, 11, NetworkDirection.PLAY_TO_SERVER)
                .encoder(SkinRequest::write)
                .decoder(SkinRequest::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            var p = ctx.get().getSender();
                            if (p != null) {
                                dev.flower.stevedance.SkinSharing.request(p, msg.hash());
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(SkinReply.class, 12, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SkinReply::write)
                .decoder(SkinReply::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT,
                                    () ->
                                            () ->
                                                    dev.flower.stevedance.client.VanillaSkins
                                                            .receive(msg.hash(), msg.png()));
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(ActorModels.class, 9)
                .encoder(ActorModels::write)
                .decoder(ActorModels::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            var sender = ctx.get().getSender();
                            if (sender != null) {
                                if (sender.getUUID().equals(msg.host())) {
                                    PvSessionService.models(sender, msg.choices());
                                }
                            } else {
                                DistExecutor.unsafeRunWhenOn(
                                        Dist.CLIENT,
                                        () ->
                                                () ->
                                                        dev.flower.stevedance.client
                                                                .PvPlaybackController.receiveModels(
                                                                msg));
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(PreviewRequest.class, 7, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PreviewRequest::write)
                .decoder(PreviewRequest::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            var p = ctx.get().getSender();
                            if (p != null) {
                                dev.flower.stevedance.WorldPreview.request(
                                        p, msg.active(), msg.kind(), msg.model());
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(PreviewState.class, 8, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PreviewState::write)
                .decoder(PreviewState::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT,
                                    () ->
                                            () ->
                                                    dev.flower.stevedance.client.WorldPreviewClient
                                                            .receive(msg));
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(PvControl.class, 6, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PvControl::write)
                .decoder(PvControl::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            ServerPlayer p = ctx.get().getSender();
                            if (p != null) {
                                PvSessionService.control(p, msg.action(), msg.timeMs());
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(Request.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Request::write)
                .decoder(Request::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            ServerPlayer p = ctx.get().getSender();
                            if (p != null) {
                                StageSessionService.request(p, msg);
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(Input.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Input::write)
                .decoder(Input::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            ServerPlayer p = ctx.get().getSender();
                            if (p != null) {
                                StageSessionService.input(p, msg);
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(State.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(State::write)
                .decoder(State::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            DistExecutor.unsafeRunWhenOn(
                                    Dist.CLIENT, () -> () -> ClientPlaybackRuntime.receive(msg));
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(Clock.class, 3, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Clock::write)
                .decoder(Clock::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            ServerPlayer p = ctx.get().getSender();
                            if (p != null) {
                                StageSessionService.clock(p, msg);
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
        CHANNEL.messageBuilder(NativeRequest.class, 5, NetworkDirection.PLAY_TO_SERVER)
                .encoder(NativeRequest::write)
                .decoder(NativeRequest::read)
                .consumerMainThread(
                        (msg, ctx) -> {
                            ServerPlayer p = ctx.get().getSender();
                            if (p != null) {
                                PvSessionService.request(p, msg.action());
                            }
                            ctx.get().setPacketHandled(true);
                        })
                .add();
    }

    public static void open(ServerPlayer p) {
        send(p, new State(0, p.getUUID(), "", 0, "", null));
    }

    public static void send(ServerPlayer p, State state) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), state);
    }

    public static void broadcast(State state) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), state);
    }

    public static void performance(ServerPlayer p, State state) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> p), state);
    }
}
