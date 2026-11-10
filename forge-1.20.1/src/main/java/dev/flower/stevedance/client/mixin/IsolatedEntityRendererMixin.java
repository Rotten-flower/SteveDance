package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.PvPlaybackController;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 独立演出画面仅绘制原曲演员。
 * <p>Only authored performers are drawn over the isolated MV view.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class IsolatedEntityRendererMixin {
    @Inject(
            method =
                    "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            cancellable = true)
    private void stevedance$isolateEntities(
            net.minecraft.world.entity.Entity entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTick,
            com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.MultiBufferSource buffers,
            int light,
            CallbackInfo ci) {
        if (!dev.flower.stevedance.client.PvVisibility.world("entities")
                && PvPlaybackController.actor(entity.getUUID()) < 0
                && !dev.flower.stevedance.client.SceneCrowdRenderer.contains(entity.getUUID())) {
            ci.cancel();
        }
    }
}
