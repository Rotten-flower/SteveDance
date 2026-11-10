package dev.flower.stevedance.client.mixin;

import dev.flower.stevedance.client.ClientPlaybackRuntime;
import dev.flower.stevedance.client.MenuCharacterPresentation;
import dev.flower.stevedance.client.PvPlaybackController;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原曲动作可能使可见演员移出真实实体静止时的包围盒。
 * <p>Original motion can offset the visible actor beyond the real entity's resting AABB.</p>
 */
@Mixin(EntityRenderer.class)
public abstract class PvPerformerCullMixin {
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void stevedance$performer(
            Entity entity,
            Frustum frustum,
            double x,
            double y,
            double z,
            CallbackInfoReturnable<Boolean> ci) {
        if (dev.flower.stevedance.client.PhotoMode.isActor(entity.getUUID())
                || PvPlaybackController.active()
                        && PvPlaybackController.actor(entity.getUUID()) >= 0) {
            ci.setReturnValue(true);
            return;
        }
        var snapshot = ClientPlaybackRuntime.MOTION_SNAPSHOTS.get(entity.getUUID());
        if (PvPlaybackController.active() && snapshot != null && snapshot.nativeMotion() != null) {
            ci.setReturnValue(true);
        }
    }

    @Inject(method = "getPackedLightCoords", at = @At("HEAD"), cancellable = true)
    private void stevedance$presentationLight(
            Entity entity, float tick, CallbackInfoReturnable<Integer> ci) {
        if (MenuCharacterPresentation.active() && MenuCharacterPresentation.contains(entity)) {
            ci.setReturnValue(15728880);
        }
    }
}
