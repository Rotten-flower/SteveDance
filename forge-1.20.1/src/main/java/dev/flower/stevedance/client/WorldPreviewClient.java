package dev.flower.stevedance.client;

import dev.flower.stevedance.StageNetwork;
import dev.flower.stevedance.core.PerformerModelChoice;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class WorldPreviewClient {
    private static final Map<UUID, PerformerModelChoice> choices = new HashMap<>();
    private static final Map<UUID, net.minecraft.world.entity.Entity> applied = new HashMap<>();
    private static net.minecraft.client.multiplayer.ClientLevel level;
    private static net.minecraft.client.CameraType previous;

    public static void request(PerformerModelChoice choice) {
        dev.flower.stevedance.client.VanillaSkins.share(choice);
        choice = dev.flower.stevedance.client.VanillaSkins.networkChoice(choice);
        StageNetwork.CHANNEL.sendToServer(
                new StageNetwork.PreviewRequest(true, choice.kind().name(), choice.modelId()));
    }

    public static void close() {
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            choices.remove(mc.player.getUUID());
            var actor = applied.remove(mc.player.getUUID());
            if (actor != null) {
                PerformerModelSelection.clearWorldPreview(actor);
            }
        }
        StageNetwork.CHANNEL.sendToServer(new StageNetwork.PreviewRequest(false, "PLAYER", ""));
        if (previous != null) {
            Minecraft.getInstance().options.setCameraType(previous);
        }
        previous = null;
    }

    public static void receive(StageNetwork.PreviewState state) {
        if (state.active()) {
            try {
                choices.put(
                        state.player(),
                        new PerformerModelChoice(
                                PerformerModelChoice.Kind.valueOf(state.kind()), state.model()));
            } catch (IllegalArgumentException ignored) {
            }
        } else {
            choices.remove(state.player());
            var old = applied.remove(state.player());
            if (old != null) {
                PerformerModelSelection.clearWorldPreview(old);
            }
        }
    }

    public static boolean local() {
        var mc = Minecraft.getInstance();
        return mc.player != null
                && choices.containsKey(mc.player.getUUID())
                && mc.screen instanceof PerformerModelSelectionScreen;
    }

    public static PerformanceCameraController.Frame camera() {
        var mc = Minecraft.getInstance();
        if (previous == null) {
            previous = mc.options.getCameraType();
            mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
        }
        var target = mc.player.position().add(0, 1.1, 0);
        double yaw = Math.toRadians(mc.player.getYRot());
        return dev.flower.stevedance.client.StageCamera.look(
                target.add(-Math.sin(yaw) * 3.5, .5, Math.cos(yaw) * 3.5), target);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        var mc = Minecraft.getInstance();
        if (level != mc.level) {
            choices.clear();
            applied.clear();
            level = mc.level;
            previous = null;
        }
        if (level == null) {
            return;
        }
        for (var entry : choices.entrySet()) {
            var actor = level.getPlayerByUUID(entry.getKey());
            if (actor != null) {
                PerformerModelSelection.preview(actor, entry.getValue());
                applied.put(entry.getKey(), actor);
            }
        }
    }
}
