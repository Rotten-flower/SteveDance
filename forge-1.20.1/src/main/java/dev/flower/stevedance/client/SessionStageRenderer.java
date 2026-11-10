package dev.flower.stevedance.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;

/**
 * 由PvScenePassMixin在世界通道完成后调用，使用服务器认可的舞台原点。
 * <p>Called after the world pass by PvScenePassMixin, at the server-approved stage origin.</p>
 */
@Mod.EventBusSubscriber(modid = "stevedance", value = Dist.CLIENT)
public final class SessionStageRenderer {
    public static void render(
            com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.Camera view) {
        var anchor = PvPlaybackController.sessionStage();
        if (anchor == null) {
            return;
        }
        var camera = view.getPosition();
        var pos = anchor.getBlockPos();
        pose.pushPose();
        try {
            pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            PvStageRenderer.render(anchor, pose);
            SceneCrowdRenderer.render(anchor, pose);
        } finally {
            pose.popPose();
        }
    }
}
