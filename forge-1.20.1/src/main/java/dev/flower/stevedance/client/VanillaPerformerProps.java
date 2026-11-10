package dev.flower.stevedance.client;

import static dev.flower.stevedance.core.SkeletonRetargeter.Quat;
import static dev.flower.stevedance.core.SkeletonRetargeter.Transform;
import static dev.flower.stevedance.core.SkeletonRetargeter.Vec;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.flower.stevedance.core.PropBinding;
import dev.flower.stevedance.core.PvBodyMotion;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Steve 抓取使用姿势化和渲染器归一化后的最终 ModelParts。
 * <p>Steve grips use the final ModelParts, after posing and renderer normalization.</p>
 */
@Mod.EventBusSubscriber(
        modid = "stevedance",
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class VanillaPerformerProps
        extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private static final Quat SOURCE_TO_STEVE = Quat.axis(new Vec(1, 0, 0), java.lang.Math.PI);

    private VanillaPerformerProps(
            RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> renderer) {
        super(renderer);
    }

    @SubscribeEvent
    public static void layers(EntityRenderersEvent.AddLayers event) {
        for (String skin : event.getSkins()) {
            net.minecraft.client.renderer.entity.player.PlayerRenderer renderer =
                    event.getSkin(skin);
            if (renderer != null) {
                renderer.addLayer(new VanillaPerformerProps(renderer));
            }
        }
    }

    @Override
    public void render(
            PoseStack stack,
            MultiBufferSource buffers,
            int light,
            AbstractClientPlayer player,
            float swing,
            float amount,
            float partial,
            float age,
            float yaw,
            float pitch) {
        if (!PvPlaybackController.active()) {
            return;
        }
        int actor = PvPlaybackController.actor(player.getUUID());
        if (actor < 0) {
            return;
        }
        var motion = PvPlaybackController.performerMotion(actor);
        if (motion == null) {
            return;
        }
        var sockets = sockets(getParentModel(), motion, PvPlaybackController.timeMs() / 1000);
        double length =
                motion.bind
                                .get("LeftArm")
                                .position()
                                .sub(motion.bind.get("LeftForeArm").position())
                                .length()
                        + motion.bind
                                .get("LeftForeArm")
                                .position()
                                .sub(motion.bind.get("LeftHand").position())
                                .length();
        if (actor == 0) {
            YsmPropAdapter.activeModel = "minecraft:player";
            YsmPropAdapter.bound = true;
        }
        PerformerPropBindings.render(stack, actor, sockets, .625 / length);
    }

    static Map<String, Transform> sockets(PlayerModel<?> m, PvBodyMotion motion, double seconds) {
        var mesh = dev.flower.stevedance.client.SteveSockets.sample(m);
        Map<String, Transform> sockets = new HashMap<>();
        sockets.put("LeftHand", adapt(mesh.get("left_grip"), motion.bind.get("LeftHand")));
        sockets.put("RightHand", adapt(mesh.get("right_grip"), motion.bind.get("RightHand")));
        sockets.put("LeftArm", adapt(mesh.get("left_shoulder"), motion.bind.get("LeftArm")));
        sockets.put("RightArm", adapt(mesh.get("right_shoulder"), motion.bind.get("RightArm")));
        sockets.put("UpperBody", adapt(mesh.get("torso"), motion.bind.get("UpperBody")));
        var source = motion.sample(seconds);
        for (String side : List.of("Left", "Right")) {
            String arm = side + "Arm", hand = side + "Hand";
            sockets.put(
                    hand,
                    PropBinding.rigidHand(
                            sockets.get(hand),
                            sockets.get(arm),
                            source.get(arm),
                            source.get(hand),
                            motion.bind.get(arm),
                            motion.bind.get(hand)));
        }
        return sockets;
    }

    private static Transform adapt(Transform mesh, Transform sourceBind) {
        return new Transform(
                mesh.position(), mesh.rotation().mul(SOURCE_TO_STEVE).mul(sourceBind.rotation()));
    }
}
